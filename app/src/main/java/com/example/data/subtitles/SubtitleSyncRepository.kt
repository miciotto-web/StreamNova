package com.example.data.subtitles

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.profiles.ProfileStorage
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Offset di sincronizzazione salvato per un contenuto.
 *
 * @param offsetMs offset in millisecondi: **positivo = sottotitoli in ritardo**
 *        (stessa convenzione del player, vedi `SubtitleOffsetRenderer`).
 * @param updatedAt timestamp dell'ultima modifica, usato per la potatura.
 */
@JsonClass(generateAdapter = true)
data class SubtitleSyncRecord(
  val offsetMs: Int,
  val updatedAt: Long
)

/**
 * Codifica tollerante della mappa degli offset.
 *
 * Un JSON corrotto non deve mai impedire l'apertura del player: viene scartato
 * per intero e si riparte da una mappa vuota. I record invalidi vengono filtrati
 * singolarmente.
 */
object SubtitleSyncCodec {

  /** Limite di record conservati: gli offset più vecchi vengono potati. */
  const val MAX_ENTRIES = 500

  private val adapter = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()
    .adapter<Map<String, SubtitleSyncRecord>>(
      Types.newParameterizedType(Map::class.java, String::class.java, SubtitleSyncRecord::class.java)
    )

  fun encode(records: Map<String, SubtitleSyncRecord>): String = try {
    adapter.toJson(prune(records))
  } catch (e: Exception) {
    "{}"
  }

  fun decode(json: String?): Map<String, SubtitleSyncRecord> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
      adapter.fromJson(json)
        ?.filterKeys { it.isNotBlank() }
        ?.filterValues { it.offsetMs in -SubtitleSyncRepository.MAX_OFFSET_MS..SubtitleSyncRepository.MAX_OFFSET_MS }
        ?: emptyMap()
    } catch (e: Exception) {
      emptyMap()
    }
  }

  /**
   * Potatura deterministica: mantiene i [MAX_ENTRIES] record più recenti, a parità
   * di timestamp ordina per chiave (nessun ordine non riproducibile).
   */
  fun prune(
    records: Map<String, SubtitleSyncRecord>,
    maxEntries: Int = MAX_ENTRIES
  ): Map<String, SubtitleSyncRecord> {
    if (records.size <= maxEntries) return records
    return records.entries
      .sortedWith(
        compareByDescending<Map.Entry<String, SubtitleSyncRecord>> { it.value.updatedAt }
          .thenBy { it.key }
      )
      .take(maxEntries)
      .associate { it.key to it.value }
  }
}

/**
 * DataStore degli offset sottotitoli per il profilo [profileId].
 *
 * Gli offset sono personali (ogni membro della famiglia può aver bisogno di una
 * correzione diversa), quindi seguono il profilo attivo come le selezioni
 * OpenSubtitles.
 */
private fun subtitleSyncDataStoreFor(context: Context, profileId: String): DataStore<Preferences> =
  PreferenceDataStoreFactory.create(
    produceFile = {
      ProfileStorage.dataStoreFile(
        context = context,
        baseName = SubtitleSyncRepository.STORE_BASE,
        profileId = profileId
      )
    }
  )

/**
 * Repository dell'offset sottotitoli **persistente per contenuto**.
 *
 * Prima di questa feature l'offset viveva solo in memoria dentro
 * `PlaybackState.subtitleOffsetMs`: chiudendo il player o cambiando episodio la
 * correzione andava persa. Qui ogni film/episodio conserva la propria correzione,
 * ripristinata alla riapertura:
 *
 *  - la chiave è quella canonica dei sottotitoli ([SubtitleContentKey]):
 *    `media.id` per i film, `media.id::season:N:episode:M` per gli episodi;
 *  - la cache in memoria ([offsets]) è aggiornata SUBITO alla scrittura, così una
 *    riapertura immediata non dipende dal round-trip asincrono del DataStore;
 *  - i valori sono sempre limitati a ±[MAX_OFFSET_MS] (stesso clamp del player);
 *  - il JSON corrotto non blocca nulla: [SubtitleSyncCodec] restituisce mappa vuota.
 */
object SubtitleSyncRepository {

  private const val TAG = "SubtitleSyncRepo"

  /** Nome base del DataStore (per profilo) degli offset sottotitoli. */
  const val STORE_BASE = "subtitle_sync"

  /** Limite assoluto dell'offset, identico a quello applicato dal player. */
  const val MAX_OFFSET_MS = 10_000

  private val KEY_OFFSETS_JSON = stringPreferencesKey("offsets_json")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var dataStore: DataStore<Preferences>? = null
  private var dataStoreProfileId: String? = null
  private var observerJob: Job? = null

  @Volatile
  private var appContext: Context? = null

  private val _offsets = MutableStateFlow<Map<String, SubtitleSyncRecord>>(emptyMap())

  /** Mappa reattiva degli offset correnti (chiave contenuto → record). */
  val offsets: StateFlow<Map<String, SubtitleSyncRecord>> = _offsets.asStateFlow()

  fun init(context: Context, profileId: String = ProfileStorage.PRIMARY_PROFILE_ID) {
    appContext = context.applicationContext
    if (dataStore != null && dataStoreProfileId == profileId) return
    val store = subtitleSyncDataStoreFor(context.applicationContext, profileId).also {
      dataStore = it
      dataStoreProfileId = profileId
    }
    observerJob?.cancel()
    observerJob = scope.launch {
      store.data.collect { prefs ->
        _offsets.value = SubtitleSyncCodec.decode(prefs[KEY_OFFSETS_JSON])
      }
    }
  }

  /** Offset salvato per il contenuto (0 se assente o store non inizializzato). */
  fun offsetOf(contentKey: String): Int = _offsets.value[contentKey]?.offsetMs ?: 0

  /**
   * Come [offsetOf], ma attende la prima lettura da disco: da usare all'apertura
   * del player, quando la cache in memoria può non essere ancora popolata.
   */
  suspend fun offsetOfAwait(contentKey: String): Int {
    val store = dataStore ?: return offsetOf(contentKey)
    val prefs = try {
      store.data.firstOrNull()
    } catch (e: Exception) {
      null
    }
    val records = SubtitleSyncCodec.decode(prefs?.get(KEY_OFFSETS_JSON))
    if (records.isNotEmpty()) _offsets.value = records
    return records[contentKey]?.offsetMs ?: offsetOf(contentKey)
  }

  /**
   * Salva l'offset per il contenuto. Valori fuori range vengono limitati, non
   * rifiutati: l'utente ha comunque espresso una correzione.
   */
  fun setOffset(contentKey: String, offsetMs: Int) {
    if (contentKey.isBlank()) return
    val clamped = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
    val record = SubtitleSyncRecord(offsetMs = clamped, updatedAt = System.currentTimeMillis())
    _offsets.update { current ->
      SubtitleSyncCodec.prune(current + (contentKey to record))
    }
    scope.launch { writeOffset(contentKey, record) }
  }

  /** Applica un delta (positivo = ritarda) e restituisce il nuovo offset. */
  fun adjustOffset(contentKey: String, deltaMs: Int): Int {
    val next = (offsetOf(contentKey) + deltaMs).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
    setOffset(contentKey, next)
    return next
  }

  /** Rimuove l'offset del contenuto (ritorno a 0). */
  fun clear(contentKey: String) {
    if (contentKey.isBlank()) return
    _offsets.update { it - contentKey }
    scope.launch {
      dataStore?.edit { prefs ->
        val current = SubtitleSyncCodec.decode(prefs[KEY_OFFSETS_JSON]).toMutableMap()
        current.remove(contentKey)
        prefs[KEY_OFFSETS_JSON] = SubtitleSyncCodec.encode(current)
      }
    }
  }

  /** Cancella tutti gli offset salvati (usato dal ripristino/backup). */
  fun clearAll() {
    _offsets.value = emptyMap()
    scope.launch {
      dataStore?.edit { prefs -> prefs.remove(KEY_OFFSETS_JSON) }
    }
  }

  /**
   * Applica un insieme di offset (usato dall'importazione backup) e restituisce
   * quanti record sono stati accettati.
   */
  fun replaceAll(records: Map<String, SubtitleSyncRecord>): Int {
    val sanitized = records
      .filterKeys { it.isNotBlank() }
      .mapValues { (_, record) ->
        record.copy(offsetMs = record.offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS))
      }
    val pruned = SubtitleSyncCodec.prune(sanitized)
    _offsets.value = pruned
    scope.launch {
      dataStore?.edit { prefs -> prefs[KEY_OFFSETS_JSON] = SubtitleSyncCodec.encode(pruned) }
    }
    return pruned.size
  }

  private suspend fun writeOffset(contentKey: String, record: SubtitleSyncRecord) {
    try {
      dataStore?.edit { prefs ->
        val current = SubtitleSyncCodec.decode(prefs[KEY_OFFSETS_JSON]).toMutableMap()
        current[contentKey] = record
        prefs[KEY_OFFSETS_JSON] = SubtitleSyncCodec.encode(current)
      }
      Log.d(TAG, "Offset sottotitoli salvato: $contentKey → ${record.offsetMs} ms")
    } catch (e: Exception) {
      // La cache in memoria resta valida per la sessione corrente: un errore di
      // scrittura non deve interrompere la riproduzione.
      Log.w(TAG, "Salvataggio offset fallito per $contentKey: ${e.message}")
    }
  }
}
