package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Preferenze "Debrid / TorBox" in un unico oggetto value-class-friendly.
 *
 * @param apiKey chiave API TorBox dell'utente (null = non configurata).
 * @param instantDebridEnabled toggle "Usa TorBox Instant Debrid".
 */
data class TorBoxPreferences(
  val apiKey: String? = null,
  val instantDebridEnabled: Boolean = true
)

/**
 * Modalità del motore di streaming.
 * Determina se usare Debrid TorBox o Web Streaming (HTTP).
 */
enum class StreamingEngineMode {
    DEBRID_TORBOX, // Usa ESCLUSIVAMENTE Stremio Addon + TorBox Instant (1080p/4K). Tutti gli scraper HTTP web sono disattivati.
    HTTP_WEB       // Usa ESCLUSIVAMENTE gli scraper web gratuiti (VixSrc, AnimeSaturn, CB01, ecc.). TorBox disattivato.
}

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "streamnova_app_settings"
)

/**
 * Gestore delle preferenze persistenti dell'app **al di fuori** della sola
 * riproduzione: chiave API TorBox, toggle Instant Debrid e lista degli addon
 * Stremio installati (serializzata in JSON).
 *
 * Stesso pattern di [SettingsRepository]: singleton inizializzato una sola volta
 * dall'[android.app.Application], con [StateFlow] come sorgente di verità in
 * memoria e DataStore Preferences come persistenza su disco.
 */
object AppSettingsRepository {

  private val KEY_TORBOX_API_KEY = stringPreferencesKey("torbox_api_key")
  private val KEY_OPENSUBTITLES_API_KEY = stringPreferencesKey("opensubtitles_api_key")
  private val KEY_TORBOX_INSTANT_DEBRID = booleanPreferencesKey("torbox_instant_debrid_enabled")
  private val KEY_INSTALLED_ADDONS_JSON = stringPreferencesKey("installed_addons_json")
  private val KEY_USER_PROVIDER_BINDINGS_JSON = stringPreferencesKey("user_provider_bindings_json")
  private val KEY_STREAMING_ENGINE_MODE = stringPreferencesKey("streaming_engine_mode")
  private val KEY_AUTOPLAY_ENABLED = booleanPreferencesKey("autoplay_enabled")
  private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
  private val KEY_PARENTAL_CONTROL_ENABLED = booleanPreferencesKey("parental_control_enabled")
  private val KEY_PARENTAL_CONTROL_PIN = stringPreferencesKey("parental_control_pin")
  private val KEY_PARENTAL_CONTROL_LEVEL = stringPreferencesKey("parental_control_level")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _torBoxApiKey = MutableStateFlow<String?>(null)
  /** Chiave API TorBox corrente (null = nessuna chiave salvata). */
  val torBoxApiKey: StateFlow<String?> = _torBoxApiKey.asStateFlow()

  private val _openSubtitlesApiKey = MutableStateFlow<String?>(null)
  /** Chiave API OpenSubtitles corrente (null = nessuna chiave salvata). */
  val openSubtitlesApiKey: StateFlow<String?> = _openSubtitlesApiKey.asStateFlow()

  private val _torBoxInstantDebridEnabled = MutableStateFlow(true)
  /** Toggle "Usa TorBox Instant Debrid" (default ON: ha effetto solo con chiave valida). */
  val torBoxInstantDebridEnabled: StateFlow<Boolean> = _torBoxInstantDebridEnabled.asStateFlow()

  private val _installedAddonsJson = MutableStateFlow("[]")
  /** Lista addon Stremio installata serializzata in JSON (fonte di verità persistita). */
  val installedAddonsJson: StateFlow<String> = _installedAddonsJson.asStateFlow()

  private val _userProviderBindingsJson = MutableStateFlow("[]")
  /**
   * Associazioni catalogo Stremio → provider dichiarate dall'utente, serializzate in JSON.
   * Sono la sorgente con precedenza massima nel resolver: nessuna euristica li produce.
   * Formato: lista di `{addonManifestId, type, catalogId, providerId}`.
   */
  val userProviderBindingsJson: StateFlow<String> = _userProviderBindingsJson.asStateFlow()

  private val _streamingEngineMode = MutableStateFlow<StreamingEngineMode>(StreamingEngineMode.HTTP_WEB)
  /** Modalità del motore di streaming. */
  val streamingEngineMode: StateFlow<StreamingEngineMode> = _streamingEngineMode.asStateFlow()

  private val _autoplayEnabled = MutableStateFlow(false)
  /** Se false, mostra il dialog di selezione sorgente invece di autoplay. */
  val autoplayEnabled: StateFlow<Boolean> = _autoplayEnabled.asStateFlow()

  private val _appLanguage = MutableStateFlow("it")
  /** Lingua dell'app / metadati TMDB (default "it"). */
  val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

  private val _parentalControlEnabled = MutableStateFlow(false)
  /** Controllo genitori abilitato (default false). */
  val parentalControlEnabled: StateFlow<Boolean> = _parentalControlEnabled.asStateFlow()

  private val _parentalControlPin = MutableStateFlow("")
  /** Hash SHA-256 del PIN numerico di 4 cifre (default vuoto). */
  val parentalControlPin: StateFlow<String> = _parentalControlPin.asStateFlow()

  private val _parentalControlLevel = MutableStateFlow("18")
  /** Soglia di età del controllo genitori (default "18"). */
  val parentalControlLevel: StateFlow<String> = _parentalControlLevel.asStateFlow()

  private var dataStore: DataStore<Preferences>? = null

  /** Completa alla prima lettura effettiva da disco (serve ad attendere il load iniziale). */
  private val loaded = CompletableDeferred<Unit>()

  // Flag "scrittura locale": i read-back di DataStore (che arrivano in ritardo,
  // dopo l'edit asincrono) NON devono mai sovrascrivere un valore già aggiornato
  // in memoria dall'utente: la scrittura locale ha sempre precedenza.
  @Volatile private var apiKeyLocalWrite = false
  @Volatile private var openSubtitlesApiKeyLocalWrite = false
  @Volatile private var debridLocalWrite = false
  @Volatile private var addonsLocalWrite = false
  @Volatile private var userBindingsLocalWrite = false
  @Volatile private var streamingModeLocalWrite = false
  @Volatile private var autoplayLocalWrite = false
  @Volatile private var languageLocalWrite = false
  @Volatile private var parentalEnabledLocalWrite = false
  @Volatile private var parentalPinLocalWrite = false
  @Volatile private var parentalLevelLocalWrite = false

  fun init(context: Context) {
    if (dataStore != null) return
    val store = context.applicationContext.appSettingsDataStore
    dataStore = store
    scope.launch {
      store.data.collect { prefs ->
        if (!apiKeyLocalWrite) {
          _torBoxApiKey.value = prefs[KEY_TORBOX_API_KEY]?.takeIf { it.isNotBlank() }
        }
        if (!openSubtitlesApiKeyLocalWrite) {
          _openSubtitlesApiKey.value = prefs[KEY_OPENSUBTITLES_API_KEY]?.takeIf { it.isNotBlank() }
        }
        if (!debridLocalWrite) {
          _torBoxInstantDebridEnabled.value = prefs[KEY_TORBOX_INSTANT_DEBRID] ?: true
        }
        if (!addonsLocalWrite) {
          _installedAddonsJson.value = prefs[KEY_INSTALLED_ADDONS_JSON] ?: "[]"
        }
        if (!userBindingsLocalWrite) {
          _userProviderBindingsJson.value = prefs[KEY_USER_PROVIDER_BINDINGS_JSON] ?: "[]"
        }
        if (!streamingModeLocalWrite) {
          val modeString = prefs[KEY_STREAMING_ENGINE_MODE]
          _streamingEngineMode.value = when (modeString) {
            "DEBRID_TORBOX" -> StreamingEngineMode.DEBRID_TORBOX
            else -> StreamingEngineMode.HTTP_WEB
          }
        }
        if (!autoplayLocalWrite) {
          _autoplayEnabled.value = prefs[KEY_AUTOPLAY_ENABLED] ?: false
        }
        if (!languageLocalWrite) {
          _appLanguage.value = prefs[KEY_APP_LANGUAGE] ?: "it"
        }
        if (!parentalEnabledLocalWrite) {
          _parentalControlEnabled.value = prefs[KEY_PARENTAL_CONTROL_ENABLED] ?: false
        }
        if (!parentalPinLocalWrite) {
          _parentalControlPin.value = prefs[KEY_PARENTAL_CONTROL_PIN] ?: ""
        }
        if (!parentalLevelLocalWrite) {
          _parentalControlLevel.value = prefs[KEY_PARENTAL_CONTROL_LEVEL] ?: "18"
        }
        loaded.complete(Unit)
      }
    }
  }

  /**
   * Attende (best-effort) il primo caricamento da disco. Da chiamare prima di
   * una prima scrittura, così una lista/chiave già salvata in precedenza non
   * viene persa sovrascrivendo prima del load.
   */
  suspend fun awaitLoaded(timeoutMs: Long = 3_000L) {
    if (dataStore == null) return
    withTimeoutOrNull(timeoutMs) { loaded.await() }
  }

  /** Snapshot corrente delle preferenze Debrid/TorBox. */
  val torBoxPreferences: TorBoxPreferences
    get() = TorBoxPreferences(
      apiKey = _torBoxApiKey.value,
      instantDebridEnabled = _torBoxInstantDebridEnabled.value
    )

  /**
   * Salva (o rimuove, con null/blank) la chiave API TorBox.
   * La scrittura è asincrona: lo [StateFlow] viene aggiornato subito per la UI.
   */
  fun setTorBoxApiKey(value: String?) {
    val normalized = value?.trim()?.takeIf { it.isNotBlank() }
    apiKeyLocalWrite = true
    _torBoxApiKey.value = normalized
    scope.launch {
      dataStore?.edit { prefs ->
        if (normalized == null) prefs.remove(KEY_TORBOX_API_KEY)
        else prefs[KEY_TORBOX_API_KEY] = normalized
      }
    }
  }

  /**
   * Salva (o rimuove, con null/blank) la chiave API OpenSubtitles.
   * La scrittura è asincrona: lo [StateFlow] viene aggiornato subito per la UI.
   */
  fun setOpenSubtitlesApiKey(value: String?) {
    val normalized = value?.trim()?.takeIf { it.isNotBlank() }
    openSubtitlesApiKeyLocalWrite = true
    _openSubtitlesApiKey.value = normalized
    scope.launch {
      dataStore?.edit { prefs ->
        if (normalized == null) prefs.remove(KEY_OPENSUBTITLES_API_KEY)
        else prefs[KEY_OPENSUBTITLES_API_KEY] = normalized
      }
    }
  }

  fun setTorBoxInstantDebridEnabled(enabled: Boolean) {
    debridLocalWrite = true
    _torBoxInstantDebridEnabled.value = enabled
    scope.launch { dataStore?.edit { it[KEY_TORBOX_INSTANT_DEBRID] = enabled } }
  }

  /** Salva la lista degli addon installata come JSON (già serializzato dal repository). */
  fun setInstalledAddonsJson(json: String) {
    addonsLocalWrite = true
    _installedAddonsJson.value = json.ifBlank { "[]" }
    scope.launch { dataStore?.edit { it[KEY_INSTALLED_ADDONS_JSON] = json.ifBlank { "[]" } } }
  }

  /** Salva le associazioni catalogo → provider dichiarate dall'utente. */
  fun setUserProviderBindingsJson(json: String) {
    userBindingsLocalWrite = true
    _userProviderBindingsJson.value = json.ifBlank { "[]" }
    scope.launch { dataStore?.edit { it[KEY_USER_PROVIDER_BINDINGS_JSON] = json.ifBlank { "[]" } } }
  }

  /**
   * Imposta la modalità del motore di streaming.
   * Se torBoxApiKey è presente, imposta la modalità DEBRID_TORBOX, altrimenti HTTP_WEB.
   */
  fun setStreamingEngineMode(mode: StreamingEngineMode) {
    streamingModeLocalWrite = true
    _streamingEngineMode.value = mode
    scope.launch {
      dataStore?.edit { prefs ->
        prefs[KEY_STREAMING_ENGINE_MODE] = mode.name
      }
    }
  }

  /** Imposta se l'autoplay è abilitato. Se false, mostra il dialog di selezione sorgente. */
  fun setAutoplayEnabled(enabled: Boolean) {
    autoplayLocalWrite = true
    _autoplayEnabled.value = enabled
    scope.launch { dataStore?.edit { it[KEY_AUTOPLAY_ENABLED] = enabled } }
  }

  /** Imposta la lingua dell'app e dei metadati TMDB. */
  fun setAppLanguage(language: String) {
    languageLocalWrite = true
    _appLanguage.value = language
    scope.launch { dataStore?.edit { it[KEY_APP_LANGUAGE] = language } }
  }

  /** Converte il codice lingua app ("it", "en", ecc.) nel formato ISO regionale per TMDB ("it-IT", "en-US", ecc.). */
  fun tmdbLanguage(): String = when (_appLanguage.value.trim().lowercase()) {
    "en" -> "en-US"
    "es" -> "es-ES"
    "fr" -> "fr-FR"
    "de" -> "de-DE"
    else -> "it-IT"
  }

  /**
   * Determina automaticamente la modalità del motore di streaming in base alla presenza della chiave TorBox.
   * Se torBoxApiKey è presente, imposta la modalità DEBRID_TORBOX, altrimenti HTTP_WEB.
   */
  fun autoDetectStreamingEngineMode() {
    val mode = if (_torBoxApiKey.value.isNullOrBlank()) {
      StreamingEngineMode.HTTP_WEB
    } else {
      StreamingEngineMode.DEBRID_TORBOX
    }
    setStreamingEngineMode(mode)
  }

  /** Abilita o disabilita il controllo genitori. */
  fun setParentalControlEnabled(enabled: Boolean) {
    parentalEnabledLocalWrite = true
    _parentalControlEnabled.value = enabled
    scope.launch { dataStore?.edit { it[KEY_PARENTAL_CONTROL_ENABLED] = enabled } }
  }

  /**
   * Salva l'hash SHA-256 del PIN numerico di 4 cifre.
   * Il PIN in chiaro non viene MAI persistito né loggato.
   */
  fun setParentalControlPin(pin: String) {
    val clean = pin.trim()
    if (clean.length != 4 || !clean.all { it.isDigit() }) return
    val hashed = hashPin(clean)
    parentalPinLocalWrite = true
    _parentalControlPin.value = hashed
    scope.launch { dataStore?.edit { it[KEY_PARENTAL_CONTROL_PIN] = hashed } }
  }

  /**
   * Verifica se il PIN inserito corrisponde all'hash SHA-256 salvato.
   */
  fun verifyParentalControlPin(pin: String): Boolean {
    val storedHash = _parentalControlPin.value
    if (storedHash.isBlank()) return false
    val inputHash = hashPin(pin.trim())
    return inputHash.equals(storedHash, ignoreCase = true)
  }

  /**
   * Imposta la soglia di età del controllo genitori ("14", "16", "18").
   */
  fun setParentalControlLevel(level: String) {
    val clean = level.trim().removeSuffix("+")
    val validLevel = if (clean in listOf("14", "16", "18")) clean else "18"
    parentalLevelLocalWrite = true
    _parentalControlLevel.value = validLevel
    scope.launch { dataStore?.edit { it[KEY_PARENTAL_CONTROL_LEVEL] = validLevel } }
  }

  /** Ritorna true se un PIN è stato configurato. */
  fun hasParentalControlPin(): Boolean = _parentalControlPin.value.isNotBlank()

  /** Calcola l'hash SHA-256 di una stringa. */
  private fun hashPin(pin: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
    return hashBytes.joinToString("") { "%02x".format(it) }
  }
}
