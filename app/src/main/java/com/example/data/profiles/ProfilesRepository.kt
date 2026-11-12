package com.example.data.profiles

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Catalogo dei profili del dispositivo.
 *
 * Persistenza: un unico DataStore CONDIVISO (`streamnova_profiles`), indipendente dal
 * profilo attivo. Da lì l'Application ricava, prima di aprire Room, quale database e
 * quali DataStore personali usare (vedi [ProfileStorage]).
 *
 * L'oggetto è un singleton inizializzato una sola volta dall'Application. Il [init]
 * legge lo stato in modo SINCRONO (bloccante) perché la scelta del database deve essere
 * nota prima che Room venga costruito; si tratta di una lettura di pochi byte e avviene
 * una sola volta per processo.
 *
 * Nota sul cambio profilo: il profilo attivo è persistito qui, ma i repository già
 * costruiti restano legati al profilo di avvio. Per questo la UI, dopo aver scritto la
 * scelta, riavvia il processo (vedi `AppRestarter`): al nuovo avvio l'Application legge
 * il profilo aggiornato e apre lo storage corretto.
 */
object ProfilesRepository {

  private const val TAG = "ProfilesRepository"
  private val KEY_PROFILE_STATE = stringPreferencesKey("profiles_state_json")

  private val Context.profilesDataStore by preferencesDataStore(
    name = "streamnova_profiles",
    // Il catalogo profili è la porta d'ingresso dell'app: un file corrotto non deve
    // impedire l'avvio. In caso di corruzione si riparte dal solo profilo principale;
    // i database dei profili restano su disco e non vengono toccati.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
  )

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val initLock = Any()
  private val writeMutex = Mutex()

  private var appContext: Context? = null
  private var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>? = null
  private var observerJob: Job? = null

  @Volatile
  private var initialized = false

  private val _profiles = MutableStateFlow<List<UserProfile>>(emptyList())
  /** Profili ordinati per creazione; il principale è sempre presente. */
  val profiles: StateFlow<List<UserProfile>> = _profiles.asStateFlow()

  private val _activeProfileId = MutableStateFlow(ProfileStorage.PRIMARY_PROFILE_ID)
  /** Id del profilo con cui è stato avviato il processo corrente. */
  val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

  /**
   * Inizializza il catalogo e restituisce lo stato necessario al bootstrap.
   *
   * Idempotente: le chiamate successive restituiscono lo snapshot corrente.
   */
  fun init(context: Context): ProfilesSnapshot {
    synchronized(initLock) {
      if (initialized) return snapshot()
      val applicationContext = context.applicationContext
      appContext = applicationContext
      val store = applicationContext.profilesDataStore
      dataStore = store
      val defaultName = applicationContext.getString(R.string.profiles_default_name)

      val restored = runCatching {
        runBlocking { loadOrSeed(store, defaultName) }
      }.getOrElse { error ->
        Log.e(TAG, "Catalogo profili non leggibile, avvio con il profilo principale: ${error.message}")
        RuntimeProfiles(listOf(primaryProfile(defaultName)), ProfileStorage.PRIMARY_PROFILE_ID)
      }
      publish(restored)

      observerJob = scope.launch {
        store.data.collect { prefs ->
          val decoded = ProfileCodec.decode(prefs[KEY_PROFILE_STATE]) ?: return@collect
          publish(normalize(decoded, defaultName))
        }
      }
      initialized = true
      return snapshot()
    }
  }

  /**
   * Profilo attivo corrente. Ritorna null solo se il catalogo non è ancora inizializzato.
   */
  fun activeProfile(): UserProfile? =
    _profiles.value.firstOrNull { it.id == _activeProfileId.value }

  /**
   * Crea un profilo con nome già validato.
   *
   * L'avatar può essere scelto dalla UI oppure lasciato null: in quel caso viene usato
   * il primo colore libero della palette.
   */
  suspend fun createProfile(
    rawName: String,
    avatar: ProfileAvatar? = null
  ): ProfileMutationResult = writeMutex.withLock {
    val currentProfiles = _profiles.value
    val name = ProfileRules.normalizeName(rawName)
    ProfileRules.nameError(name, currentProfiles)?.let {
      return@withLock ProfileMutationResult.InvalidName(it)
    }
    if (currentProfiles.size >= ProfileRules.MAX_PROFILES) {
      return@withLock ProfileMutationResult.LimitReached
    }
    val profile = UserProfile(
      id = generateId(currentProfiles),
      name = name,
      avatar = avatar ?: ProfileRules.nextAvatar(currentProfiles),
      isPrimary = false,
      createdAtEpochMs = System.currentTimeMillis(),
      avatarEmoji = ProfileRules.nextEmoji(currentProfiles)
    )
    if (!persist(currentProfiles + profile, _activeProfileId.value)) {
      return@withLock ProfileMutationResult.Failure
    }
    ProfileMutationResult.Success(profile)
  }

  /** Rinomina un profilo esistente (anche quello principale). */
  suspend fun renameProfile(id: String, rawName: String): ProfileMutationResult =
    writeMutex.withLock {
      val currentProfiles = _profiles.value
      val target = currentProfiles.firstOrNull { it.id == id }
        ?: return@withLock ProfileMutationResult.NotFound
      val name = ProfileRules.normalizeName(rawName)
      ProfileRules.nameError(name, currentProfiles, excludeId = id)?.let {
        return@withLock ProfileMutationResult.InvalidName(it)
      }
      if (target.name == name) return@withLock ProfileMutationResult.Success(target)
      val updated = target.copy(name = name, hasCustomName = true)
      if (!persist(currentProfiles.map { if (it.id == id) updated else it }, _activeProfileId.value)) {
        return@withLock ProfileMutationResult.Failure
      }
      ProfileMutationResult.Success(updated)
    }

  /** Aggiorna insieme colore e emoji dell'avatar. */
  suspend fun updateAvatar(
    id: String,
    avatar: ProfileAvatar,
    avatarEmoji: String?
  ): ProfileMutationResult = writeMutex.withLock {
    val currentProfiles = _profiles.value
    val target = currentProfiles.firstOrNull { it.id == id }
      ?: return@withLock ProfileMutationResult.NotFound
    val updated = target.copy(
      avatar = avatar,
      avatarEmoji = sanitizeEmoji(avatarEmoji)
    )
    if (!persist(currentProfiles.map { if (it.id == id) updated else it }, _activeProfileId.value)) {
      return@withLock ProfileMutationResult.Failure
    }
    ProfileMutationResult.Success(updated)
  }

  /**
   * Elimina un profilo e i suoi dati personali.
   *
   * Il profilo principale e il profilo attivo sono protetti: eliminare il secondo
   * significherebbe cancellare il database aperto dal processo corrente. La UI guida
   * l'utente a passare a un altro profilo prima di eliminarlo.
   */
  suspend fun deleteProfile(id: String): ProfileMutationResult = writeMutex.withLock {
    val currentProfiles = _profiles.value
    val target = currentProfiles.firstOrNull { it.id == id }
      ?: return@withLock ProfileMutationResult.NotFound
    if (target.isPrimary || target.id == ProfileStorage.PRIMARY_PROFILE_ID) {
      return@withLock ProfileMutationResult.ProtectedProfile
    }
    if (target.id == _activeProfileId.value) {
      return@withLock ProfileMutationResult.ActiveProfile
    }
    val remaining = currentProfiles.filterNot { it.id == id }
    if (!persist(remaining, _activeProfileId.value)) {
      return@withLock ProfileMutationResult.Failure
    }
    // Solo DOPO aver persistito il catalogo: se la cancellazione dei file fallisse, il
    // profilo resta comunque eliminato dalla UI e i file orfani non sono raggiungibili.
    appContext?.let { ProfileStorage.deleteProfileData(it, id) }
    ProfileMutationResult.Success(target)
  }

  /**
   * Rende [id] il profilo attivo. La scelta è persistita; il chiamante deve riavviare
   * l'app perché i repository vengano ricostruiti sullo storage del nuovo profilo.
   *
   * @return true se il profilo esiste e la scelta è stata scritta.
   */
  suspend fun setActiveProfile(id: String): Boolean = writeMutex.withLock {
    if (_profiles.value.none { it.id == id }) return@withLock false
    if (_activeProfileId.value == id) return@withLock true
    persist(_profiles.value, id)
  }

  private fun snapshot(): ProfilesSnapshot =
    ProfilesSnapshot(
      activeProfileId = _activeProfileId.value,
      profiles = _profiles.value
    )

  private fun publish(state: RuntimeProfiles) {
    _profiles.value = state.profiles
    _activeProfileId.value = state.activeProfileId
  }

  private suspend fun loadOrSeed(
    store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    defaultName: String
  ): RuntimeProfiles {
    val prefs = store.data.first()
    val decoded = ProfileCodec.decode(prefs[KEY_PROFILE_STATE])
    if (decoded != null) return normalize(decoded, defaultName)

    val primary = primaryProfile(defaultName)
    val seed = ProfileStoreState(
      profiles = listOf(primary.toStored()),
      activeProfileId = primary.id
    )
    store.edit { it[KEY_PROFILE_STATE] = ProfileCodec.encode(seed) }
    return RuntimeProfiles(listOf(primary), primary.id)
  }

  /**
   * Ripara i casi degeneri di un catalogo letto da disco (id/nomi vuoti, duplicati,
   * profilo principale assente, profilo attivo inesistente) senza mai toccare i file
   * dei singoli profili.
   */
  private fun normalize(state: ProfileStoreState, defaultName: String): RuntimeProfiles {
    val seenIds = mutableSetOf<String>()
    val decoded = state.profiles.mapNotNull { stored ->
      val id = stored.id.trim()
      val name = ProfileRules.normalizeName(stored.name)
      if (id.isEmpty() || name.isEmpty() || !seenIds.add(id)) return@mapNotNull null
      UserProfile(
        id = id,
        name = name,
        avatar = ProfileAvatar.fromStorage(stored.avatar),
        isPrimary = stored.isPrimary,
        createdAtEpochMs = stored.createdAtEpochMs,
        // Campo assente nei cataloghi scritti prima di questa versione: il profilo
        // principale auto-creato non ha un nome personalizzato, gli altri sì.
        hasCustomName = stored.hasCustomName
          ?: !(stored.isPrimary || stored.id == ProfileStorage.PRIMARY_PROFILE_ID),
        avatarEmoji = sanitizeEmoji(stored.avatarEmoji)
      )
    }.toMutableList()

    if (decoded.none { it.id == ProfileStorage.PRIMARY_PROFILE_ID }) {
      decoded.add(0, primaryProfile(defaultName))
    }
    val profiles = decoded.map { profile ->
      // Un solo profilo principale: il flag è derivato dall'id storico.
      if (profile.isPrimary != (profile.id == ProfileStorage.PRIMARY_PROFILE_ID)) {
        profile.copy(isPrimary = profile.id == ProfileStorage.PRIMARY_PROFILE_ID)
      } else {
        profile
      }
    }
    val activeId = profiles.firstOrNull { it.id == state.activeProfileId }?.id
      ?: ProfileStorage.PRIMARY_PROFILE_ID
    return RuntimeProfiles(profiles, activeId)
  }

  private suspend fun persist(profiles: List<UserProfile>, activeProfileId: String): Boolean {
    val store = dataStore ?: return false
    val json = ProfileCodec.encode(
      ProfileStoreState(
        profiles = profiles.map { it.toStored() },
        activeProfileId = activeProfileId
      )
    )
    return try {
      store.edit { it[KEY_PROFILE_STATE] = json }
      _profiles.value = profiles
      _activeProfileId.value = activeProfileId
      true
    } catch (error: IOException) {
      Log.e(TAG, "Impossibile salvare il catalogo profili: ${error.message}")
      false
    } catch (error: RuntimeException) {
      Log.e(TAG, "Errore imprevisto salvando il catalogo profili: ${error.message}")
      false
    }
  }

  private fun primaryProfile(defaultName: String): UserProfile =
    UserProfile(
      id = ProfileStorage.PRIMARY_PROFILE_ID,
      name = ProfileRules.normalizeName(defaultName).ifBlank { "Profilo principale" },
      avatar = ProfileAvatar.CYAN,
      isPrimary = true,
      createdAtEpochMs = 0L,
      hasCustomName = false,
      avatarEmoji = ProfileRules.DEFAULT_PRIMARY_EMOJI
    )

  /**
   * Difesa sui dati persistiti: un'emoji valida è corta e non contiene righe.
   * Un valore manomesso non deve rompere il layout dell'avatar.
   */
  private fun sanitizeEmoji(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    return trimmed.takeIf { it.isNotEmpty() && it.length <= 8 && '\n' !in it }
  }

  private fun generateId(existing: List<UserProfile>): String {
    while (true) {
      val candidate = "p" + UUID.randomUUID().toString().replace("-", "").take(12)
      if (existing.none { it.id == candidate }) return candidate
    }
  }
}

/** Stato restituito dal bootstrap: profilo attivo e catalogo completo. */
data class ProfilesSnapshot(
  val activeProfileId: String,
  val profiles: List<UserProfile>
)

/** Esito di una modifica al catalogo profili. */
sealed interface ProfileMutationResult {
  data class Success(val profile: UserProfile) : ProfileMutationResult
  data class InvalidName(val error: ProfileNameError) : ProfileMutationResult
  data object LimitReached : ProfileMutationResult
  data object NotFound : ProfileMutationResult
  /** Il profilo principale non può essere eliminato. */
  data object ProtectedProfile : ProfileMutationResult
  /** Il profilo attivo non può essere eliminato finché non se ne sceglie un altro. */
  data object ActiveProfile : ProfileMutationResult
  data object Failure : ProfileMutationResult
}

internal data class RuntimeProfiles(
  val profiles: List<UserProfile>,
  val activeProfileId: String
)

@JsonClass(generateAdapter = true)
internal data class ProfileStoreState(
  val profiles: List<StoredProfile> = emptyList(),
  val activeProfileId: String = ProfileStorage.PRIMARY_PROFILE_ID
)

@JsonClass(generateAdapter = true)
internal data class StoredProfile(
  val id: String,
  val name: String,
  val avatar: String = ProfileAvatar.CYAN.name,
  val isPrimary: Boolean = false,
  val createdAtEpochMs: Long = 0L,
  /**
   * null nei cataloghi antecedenti al campo: il significato viene inferito in
   * [ProfilesRepository.normalize] (principale = nome automatico).
   */
  val hasCustomName: Boolean? = null,
  /** Emoji avatar; null = iniziale del nome. */
  val avatarEmoji: String? = null
)

internal fun UserProfile.toStored(): StoredProfile =
  StoredProfile(
    id = id,
    name = name,
    avatar = avatar.name,
    isPrimary = isPrimary,
    createdAtEpochMs = createdAtEpochMs,
    hasCustomName = hasCustomName,
    avatarEmoji = avatarEmoji
  )

/** Codifica compatta e versionabile del catalogo (un solo oggetto JSON). */
internal object ProfileCodec {
  // Adapter GENERATI (KSP) e non riflessivi: il codec resta valido anche nella build
  // release con R8, dove la riflessione sui data class interni sarebbe fragile.
  private val moshi = Moshi.Builder().build()
  private val adapter: JsonAdapter<ProfileStoreState> =
    moshi.adapter(ProfileStoreState::class.java)

  fun encode(state: ProfileStoreState): String = adapter.toJson(state)

  fun decode(json: String?): ProfileStoreState? {
    if (json.isNullOrBlank()) return null
    return runCatching { adapter.fromJson(json) }.getOrNull()
  }
}
