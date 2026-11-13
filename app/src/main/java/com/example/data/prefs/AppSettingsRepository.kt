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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    HTTP_WEB;      // Usa ESCLUSIVAMENTE gli scraper web gratuiti (VixSrc, AnimeSaturn, CB01, ecc.). TorBox disattivato.

    /**
     * True se in questa modalità l'area "Addon" esiste per l'utente.
     *
     * Gli addon Stremio alimentano solo il percorso Debrid: in [HTTP_WEB] non partecipano
     * alla risoluzione delle sorgenti, quindi la voce (e la schermata) vanno nascoste.
     * Regola unica riusata da sidebar, routing e reindirizzamento di fallback.
     */
    val supportsAddonsSection: Boolean
        get() = this == DEBRID_TORBOX
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
  private val KEY_SIMKL_CLIENT_ID = stringPreferencesKey("simkl_client_id")
  private val KEY_TORBOX_INSTANT_DEBRID = booleanPreferencesKey("torbox_instant_debrid_enabled")
  private val KEY_INSTALLED_ADDONS_JSON = stringPreferencesKey("installed_addons_json")
  private val KEY_USER_PROVIDER_BINDINGS_JSON = stringPreferencesKey("user_provider_bindings_json")
  private val KEY_STREAMING_ENGINE_MODE = stringPreferencesKey("streaming_engine_mode")
  private val KEY_HTTP_PRIMARY_PROVIDER = stringPreferencesKey("http_primary_provider")
  private val KEY_HTTP_PRIMARY_PROVIDER_STRICT =
    booleanPreferencesKey("http_primary_provider_strict")
  private val KEY_HTTP_EXPERIMENTAL_PROVIDERS =
    booleanPreferencesKey("http_experimental_providers_enabled")
  private val KEY_AUTOPLAY_ENABLED = booleanPreferencesKey("autoplay_enabled")
  private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
  private val KEY_PARENTAL_CONTROL_ENABLED = booleanPreferencesKey("parental_control_enabled")
  private val KEY_PARENTAL_CONTROL_PIN = stringPreferencesKey("parental_control_pin")
  private val KEY_PARENTAL_CONTROL_LEVEL = stringPreferencesKey("parental_control_level")

  /**
   * Switch "Usa TMDB come fallback per i generi" (sezione Ricerca).
   *
   * Chiave stabile: rinominarla farebbe perdere la scelta dell'utente dopo un
   * aggiornamento. Nome coerente con le altre chiavi (`<area>_<feature>_enabled`).
   */
  private val KEY_TMDB_GENRE_FALLBACK_ENABLED = booleanPreferencesKey("tmdb_genre_fallback_enabled")

  /**
   * Switch "Nascondi contenuti non rilasciati" (sezione Home).
   *
   * Default OFF: la Home continua a mostrare esattamente ciò che mostrava prima
   * dell'introduzione dell'opzione. Chiave stabile per non perdere la scelta
   * dell'utente negli aggiornamenti.
   */
  private val KEY_HIDE_UNRELEASED_ENABLED = booleanPreferencesKey("home_hide_unreleased_enabled")

  // ── «Continua a guardare» (sezione Impostazioni dedicata) ─────────────────
  // Chiavi stabili e unite per area: cambiarle farebbe perdere le scelte dell'utente
  // dopo un aggiornamento. I default sono quelli documentati in
  // [ContinueWatchingPreferences] (comportamento storico dell'app).
  private val KEY_CW_SHOW_ROW = booleanPreferencesKey("continue_watching_show_row")
  private val KEY_CW_LAYOUT = stringPreferencesKey("continue_watching_layout")
  private val KEY_CW_EPISODE_THUMBNAILS = booleanPreferencesKey("continue_watching_episode_thumbnails")
  private val KEY_CW_BLUR_UNWATCHED = booleanPreferencesKey("continue_watching_blur_unwatched")
  private val KEY_CW_NEXT_FROM_FURTHEST = booleanPreferencesKey("continue_watching_next_from_furthest")
  private val KEY_CW_SHOW_UNAIRED = booleanPreferencesKey("continue_watching_show_unaired")
  private val KEY_CW_SORT = stringPreferencesKey("continue_watching_sort")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _torBoxApiKey = MutableStateFlow<String?>(null)
  /** Chiave API TorBox corrente (null = nessuna chiave salvata). */
  val torBoxApiKey: StateFlow<String?> = _torBoxApiKey.asStateFlow()

  private val _openSubtitlesApiKey = MutableStateFlow<String?>(null)
  private val _simklClientId = MutableStateFlow<String?>(null)
  val simklClientId: StateFlow<String?> = _simklClientId.asStateFlow()
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

  private val _httpPrimaryProviderId = MutableStateFlow(HttpProviderChoices.AUTO_ID)
  /**
   * Provider HTTP scelto come principale, oppure [HttpProviderChoices.AUTO_ID]
   * per il comportamento automatico (tutti i provider, ordine storico).
   *
   * Il valore è persistito su DataStore e viene letto da
   * [com.example.data.streaming.StreamManager] a ogni ricerca.
   */
  val httpPrimaryProviderId: StateFlow<String> = _httpPrimaryProviderId.asStateFlow()

  private val _httpPrimaryProviderStrict = MutableStateFlow(true)
  /**
   * `true`: quando è impostato un provider HTTP principale, viene usato
   * **solo** quello (nessun fallback automatico). Default `true`: la scelta
   * manuale è rispettata; l'utente può disattivarlo per riattivare il fallback.
   */
  val httpPrimaryProviderStrict: StateFlow<Boolean> = _httpPrimaryProviderStrict.asStateFlow()

  private val _httpExperimentalProvidersEnabled = MutableStateFlow(false)
  /**
   * `true`: esegue anche i provider HTTP sperimentali (spesso bloccati da
   * captcha/403). Default `false`: in modalità automatica restano attivi solo
   * i provider verificati (VixSrc, Altadefinizione).
   */
  val httpExperimentalProvidersEnabled: StateFlow<Boolean> =
    _httpExperimentalProvidersEnabled.asStateFlow()

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

  private val _tmdbGenreFallbackEnabled = MutableStateFlow(true)
  /**
   * Switch "Usa TMDB come fallback per i generi" (default ON: la ricerca resta più
   * completa finché l'utente non lo disattiva).
   *
   * Riguarda SOLO la scoperta per tipo+genere della sezione Ricerca: quando nessun
   * catalogo configurato copre la combinazione richiesta, TMDB viene interrogato con
   * gli endpoint `discover`. Non tocca le altre funzionalità TMDB (metadati, dettagli,
   * provider), che continuano a funzionare anche con lo switch disattivato.
   */
  val tmdbGenreFallbackEnabled: StateFlow<Boolean> = _tmdbGenreFallbackEnabled.asStateFlow()

  private val _hideUnreleasedContent = MutableStateFlow(false)
  /**
   * Switch "Nascondi contenuti non rilasciati" (sezione Home), default OFF.
   *
   * Con OFF la Home non cambia. Con ON il data layer nasconde i contenuti con data
   * di uscita TMDB futura (metadati assenti/non validi → contenuto mantenuto).
   */
  val hideUnreleasedContent: StateFlow<Boolean> = _hideUnreleasedContent.asStateFlow()

  private val _continueWatchingPreferences = MutableStateFlow(ContinueWatchingPreferences())
  /**
   * Preferenze della riga «Continua a guardare», in un unico snapshot osservabile.
   *
   * Un solo [StateFlow] (invece di sette flussi separati) evita ricomposizioni multiple
   * della Home a ogni modifica e tiene i default in un unico punto. La persistenza resta
   * su chiavi DataStore separate, così ogni opzione è indipendente sul disco.
   */
  val continueWatchingPreferences: StateFlow<ContinueWatchingPreferences> =
    _continueWatchingPreferences.asStateFlow()

  private var dataStore: DataStore<Preferences>? = null

  /**
   * Collettore attivo del DataStore. Deve essere annullato quando lo stato viene
   * azzerato ([resetForTesting]): un collettore superstite continuerebbe a scrivere
   * i valori letti dal disco SOPRA i default appena ripristinati, rendendo i test
   * dipendenti dal timing.
   */
  private var dataStoreCollectorJob: Job? = null

  /**
   * Ultima scrittura della chiave OpenSubtitles. Le scritture sono asincrone:
   * annullare quella precedente prima di avviarne una nuova garantisce che un
   * valore vecchio non possa atterrare su disco DOPO la rimozione.
   */
  private var openSubtitlesWriteJob: Job? = null

  /**
   * Meccanismo di cifratura per la chiave API OpenSubtitles prima della persistenza:
   * Android Keystore in produzione, finto in test. La sostituzione avviene in [init],
   * prima di qualsiasi lettura/scrittura.
   */
  @Volatile
  private var openSubtitlesCipher: ApiKeyCipher = AndroidKeystoreApiKeyCipher()

  /** Completa alla prima lettura effettiva da disco (serve ad attendere il load iniziale). */
  private var loaded = CompletableDeferred<Unit>()

  // Flag "scrittura locale": i read-back di DataStore (che arrivano in ritardo,
  // dopo l'edit asincrono) NON devono mai sovrascrivere un valore già aggiornato
  // in memoria dall'utente: la scrittura locale ha sempre precedenza.
  @Volatile private var apiKeyLocalWrite = false
  @Volatile private var openSubtitlesApiKeyLocalWrite = false
  @Volatile private var simklClientIdLocalWrite = false
  @Volatile private var debridLocalWrite = false
  @Volatile private var addonsLocalWrite = false
  @Volatile private var userBindingsLocalWrite = false
  @Volatile private var streamingModeLocalWrite = false
  @Volatile private var httpPrimaryProviderLocalWrite = false
  @Volatile private var httpPrimaryProviderStrictLocalWrite = false
  @Volatile private var httpExperimentalProvidersLocalWrite = false
  @Volatile private var autoplayLocalWrite = false
  @Volatile private var languageLocalWrite = false
  @Volatile private var parentalEnabledLocalWrite = false
  @Volatile private var parentalPinLocalWrite = false
  @Volatile private var parentalLevelLocalWrite = false
  @Volatile private var tmdbGenreFallbackLocalWrite = false
  @Volatile private var hideUnreleasedLocalWrite = false
  @Volatile private var cwShowRowLocalWrite = false
  @Volatile private var cwLayoutLocalWrite = false
  @Volatile private var cwEpisodeThumbnailsLocalWrite = false
  @Volatile private var cwBlurUnwatchedLocalWrite = false
  @Volatile private var cwNextFromFurthestLocalWrite = false
  @Volatile private var cwShowUnairedLocalWrite = false
  @Volatile private var cwSortLocalWrite = false

  fun init(context: Context, cipher: ApiKeyCipher = AndroidKeystoreApiKeyCipher()) {
    if (dataStore != null) return
    openSubtitlesCipher = cipher
    val store = context.applicationContext.appSettingsDataStore
    dataStore = store
    dataStoreCollectorJob?.cancel()
    dataStoreCollectorJob = scope.launch {
      store.data.collect { prefs ->
        if (!apiKeyLocalWrite) {
          _torBoxApiKey.value = prefs[KEY_TORBOX_API_KEY]?.takeIf { it.isNotBlank() }
        }
        if (!openSubtitlesApiKeyLocalWrite) {
          val storedKey = prefs[KEY_OPENSUBTITLES_API_KEY]?.takeIf { it.isNotBlank() }
          _openSubtitlesApiKey.value = when {
            storedKey == null -> null
            ApiKeyCipherFormat.isEncrypted(storedKey) ->
              // Blob cifrato: se non decifrabile (chiave Keystore invalidata) la
              // configurazione è irreperibile → trattata come "non configurata".
              openSubtitlesCipher.decrypt(storedKey)?.takeIf { it.isNotBlank() }
            // Valore storico in chiaro scritto da una build precedente: resta
            // utilizzabile; verrà ricifrato al primo salvataggio dalla UI.
            else -> storedKey
          }
        }
        if (!simklClientIdLocalWrite) {
          _simklClientId.value = prefs[KEY_SIMKL_CLIENT_ID]?.takeIf { it.isNotBlank() }
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
        if (!httpPrimaryProviderLocalWrite) {
          // Un id sconosciuto (provider rimosso in una versione futura) torna
          // automatico: nessuna ricerca deve restare bloccata su un valore vecchio.
          _httpPrimaryProviderId.value = prefs[KEY_HTTP_PRIMARY_PROVIDER]
            ?.takeIf { HttpProviderChoices.isKnown(it) }
            ?: HttpProviderChoices.AUTO_ID
        }
        if (!httpPrimaryProviderStrictLocalWrite) {
          _httpPrimaryProviderStrict.value =
            prefs[KEY_HTTP_PRIMARY_PROVIDER_STRICT] ?: true
        }
        if (!httpExperimentalProvidersLocalWrite) {
          _httpExperimentalProvidersEnabled.value =
            prefs[KEY_HTTP_EXPERIMENTAL_PROVIDERS] ?: false
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
        if (!tmdbGenreFallbackLocalWrite) {
          _tmdbGenreFallbackEnabled.value = prefs[KEY_TMDB_GENRE_FALLBACK_ENABLED] ?: true
        }
        if (!hideUnreleasedLocalWrite) {
          _hideUnreleasedContent.value = prefs[KEY_HIDE_UNRELEASED_ENABLED] ?: false
        }
        // «Continua a guardare»: ogni campo è protetto dal proprio flag di scrittura
        // locale. L'aggiornamento è ATOMICO (`update`): se una preferenza viene scritta
        // mentre il read-back sta applicando i valori di disco, il confronto e scambio
        // rilegge lo stato corrente, così la scelta dell'utente non può essere
        // sovrascritta da uno snapshot precedente.
        _continueWatchingPreferences.update { current ->
          current.copy(
            showRow = if (cwShowRowLocalWrite) current.showRow else prefs[KEY_CW_SHOW_ROW] ?: true,
            layout = if (cwLayoutLocalWrite) {
              current.layout
            } else {
              ContinueWatchingLayout.fromStorage(prefs[KEY_CW_LAYOUT])
            },
            useEpisodeThumbnails = if (cwEpisodeThumbnailsLocalWrite) {
              current.useEpisodeThumbnails
            } else {
              prefs[KEY_CW_EPISODE_THUMBNAILS] ?: false
            },
            blurUnwatched = if (cwBlurUnwatchedLocalWrite) {
              current.blurUnwatched
            } else {
              prefs[KEY_CW_BLUR_UNWATCHED] ?: false
            },
            nextEpisodeFromFurthest = if (cwNextFromFurthestLocalWrite) {
              current.nextEpisodeFromFurthest
            } else {
              prefs[KEY_CW_NEXT_FROM_FURTHEST] ?: false
            },
            showUnairedEpisodes = if (cwShowUnairedLocalWrite) {
              current.showUnairedEpisodes
            } else {
              prefs[KEY_CW_SHOW_UNAIRED] ?: true
            },
            sort = if (cwSortLocalWrite) {
              current.sort
            } else {
              ContinueWatchingSort.fromStorage(prefs[KEY_CW_SORT])
            }
          )
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

  /**
   * Azzera lo stato IN MEMORIA (solo test): simula il riavvio del processo lasciando
   * intatto il DataStore su disco, così un test può verificare che una preferenza
   * venga riletta da disco. Non usarla in produzione.
   */
  internal fun resetForTesting() {
    dataStoreCollectorJob?.cancel()
    dataStoreCollectorJob = null
    // NON annullare openSubtitlesWriteJob: un reset di test non deve perdere una
    // scrittura già avviata; la cancellazione avviene solo quando arriva un nuovo
    // valore dallo stesso setter.
    openSubtitlesWriteJob = null
    dataStore = null
    _torBoxApiKey.value = null
    _openSubtitlesApiKey.value = null
    _simklClientId.value = null
    _torBoxInstantDebridEnabled.value = true
    _installedAddonsJson.value = "[]"
    _userProviderBindingsJson.value = "[]"
    _streamingEngineMode.value = StreamingEngineMode.HTTP_WEB
    _httpPrimaryProviderId.value = HttpProviderChoices.AUTO_ID
    _httpPrimaryProviderStrict.value = true
    _httpExperimentalProvidersEnabled.value = false
    _autoplayEnabled.value = false
    _appLanguage.value = "it"
    _parentalControlEnabled.value = false
    _parentalControlPin.value = ""
    _parentalControlLevel.value = "18"
    _tmdbGenreFallbackEnabled.value = true
    _hideUnreleasedContent.value = false
    _continueWatchingPreferences.value = ContinueWatchingPreferences()
    apiKeyLocalWrite = false
    openSubtitlesApiKeyLocalWrite = false
    simklClientIdLocalWrite = false
    debridLocalWrite = false
    addonsLocalWrite = false
    userBindingsLocalWrite = false
    streamingModeLocalWrite = false
    httpPrimaryProviderLocalWrite = false
    httpPrimaryProviderStrictLocalWrite = false
    httpExperimentalProvidersLocalWrite = false
    autoplayLocalWrite = false
    languageLocalWrite = false
    parentalEnabledLocalWrite = false
    parentalPinLocalWrite = false
    parentalLevelLocalWrite = false
    tmdbGenreFallbackLocalWrite = false
    hideUnreleasedLocalWrite = false
    cwShowRowLocalWrite = false
    cwLayoutLocalWrite = false
    cwEpisodeThumbnailsLocalWrite = false
    cwBlurUnwatchedLocalWrite = false
    cwNextFromFurthestLocalWrite = false
    cwShowUnairedLocalWrite = false
    cwSortLocalWrite = false
    loaded = CompletableDeferred()
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
  fun setSimklClientId(value: String?) {
    val normalized = value?.trim()?.takeIf { it.isNotBlank() }
    simklClientIdLocalWrite = true
    _simklClientId.value = normalized
    scope.launch {
      dataStore?.edit { prefs ->
        if (normalized == null) prefs.remove(KEY_SIMKL_CLIENT_ID)
        else prefs[KEY_SIMKL_CLIENT_ID] = normalized
      }
    }
  }

  /**
   * Salva (o rimuove, con null/blank) la chiave API OpenSubtitles.
   *
   * Lo [StateFlow] contiene il testo in chiaro (serve al client HTTP in memoria);
   * sul disco la chiave viene scritta CIFRATA tramite [openSubtitlesCipher]: il file
   * DataStore non contiene mai la chiave leggibile. La scrittura è asincrona.
   */
  fun setOpenSubtitlesApiKey(value: String?) {
    val normalized = value?.trim()?.takeIf { it.isNotBlank() }
    openSubtitlesApiKeyLocalWrite = true
    _openSubtitlesApiKey.value = normalized
    // Il cipher viene catturato alla chiamata: una eventuale re-init (test) con un
    // altro cipher non altera una scrittura già in volo.
    val cipher = openSubtitlesCipher
    // Cattura anche lo store: un reset concorrente (test) non deve far perdere
    // una scrittura già avviata.
    val store = dataStore
    openSubtitlesWriteJob?.cancel()
    openSubtitlesWriteJob = scope.launch {
      store?.edit { prefs ->
        if (normalized == null) prefs.remove(KEY_OPENSUBTITLES_API_KEY)
        else prefs[KEY_OPENSUBTITLES_API_KEY] = cipher.encrypt(normalized)
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

  /**
   * Imposta il provider HTTP principale.
   *
   * @param id `"auto"` per il comportamento automatico, oppure l'id di un
   * provider presente in [HttpProviderChoices]. Un valore sconosciuto viene
   * riportato ad `"auto"`: un provider rimosso in futuro non lascia l'app in
   * uno stato non valido.
   */
  fun setHttpPrimaryProviderId(id: String?) {
    val normalized = id?.trim()?.takeIf { HttpProviderChoices.isKnown(it) }
      ?: HttpProviderChoices.AUTO_ID
    httpPrimaryProviderLocalWrite = true
    _httpPrimaryProviderId.value = normalized
    val store = dataStore
    scope.launch {
      store?.edit { prefs ->
        prefs[KEY_HTTP_PRIMARY_PROVIDER] = normalized
      }
    }
  }

  /**
   * Attiva/disattiva la modalità "solo provider scelto": con `true` non viene
   * eseguito alcun fallback automatico sugli altri provider HTTP.
   */
  fun setHttpPrimaryProviderStrict(enabled: Boolean) {
    httpPrimaryProviderStrictLocalWrite = true
    _httpPrimaryProviderStrict.value = enabled
    val store = dataStore
    scope.launch {
      store?.edit { prefs ->
        prefs[KEY_HTTP_PRIMARY_PROVIDER_STRICT] = enabled
      }
    }
  }

  /**
   * Abilita/disabilita i provider HTTP sperimentali (spesso bloccati) nella
   * modalità automatica. Il provider scelto manualmente resta sempre eseguito.
   */
  fun setHttpExperimentalProvidersEnabled(enabled: Boolean) {
    httpExperimentalProvidersLocalWrite = true
    _httpExperimentalProvidersEnabled.value = enabled
    val store = dataStore
    scope.launch {
      store?.edit { prefs ->
        prefs[KEY_HTTP_EXPERIMENTAL_PROVIDERS] = enabled
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

  /**
   * Abilita o disabilita l'uso di TMDB come fallback per i generi della sezione
   * Ricerca. La scelta è persistita: sopravvive alla chiusura e al riavvio dell'app.
   *
   * Disattivandola, le altre funzionalità TMDB (metadati, dettagli, provider) NON
   * vengono toccate: cambia solo la scoperta per genere quando nessun catalogo
   * configurato copre la combinazione tipo+genere richiesta.
   */
  fun setTmdbGenreFallbackEnabled(enabled: Boolean) {
    tmdbGenreFallbackLocalWrite = true
    _tmdbGenreFallbackEnabled.value = enabled
    scope.launch { dataStore?.edit { it[KEY_TMDB_GENRE_FALLBACK_ENABLED] = enabled } }
  }

  /**
   * Abilita o disabilita "Nascondi contenuti non rilasciati" nella Home.
   *
   * La scelta è persistita (DataStore) e sopravvive alla chiusura e al riavvio
   * dell'app. Lo [StateFlow] viene aggiornato subito, così la Home può riflettere
   * il cambiamento senza riavvio.
   */
  fun setHideUnreleasedContent(enabled: Boolean) {
    hideUnreleasedLocalWrite = true
    _hideUnreleasedContent.value = enabled
    scope.launch { dataStore?.edit { it[KEY_HIDE_UNRELEASED_ENABLED] = enabled } }
  }

  /**
   * Mostra/nasconde l'intera riga «Continua a guardare» nella Home.
   *
   * Lo [StateFlow] è aggiornato subito: la Home nasconde la riga senza riavvio e senza
   * una nuova risoluzione dei cataloghi (i dati restano quelli già in memoria).
   */
  fun setContinueWatchingShowRow(enabled: Boolean) {
    cwShowRowLocalWrite = true
    _continueWatchingPreferences.update { it.copy(showRow = enabled) }
    scope.launch { dataStore?.edit { it[KEY_CW_SHOW_ROW] = enabled } }
  }

  /** Layout delle card della riga «Continua a guardare». */
  fun setContinueWatchingLayout(layout: ContinueWatchingLayout) {
    cwLayoutLocalWrite = true
    _continueWatchingPreferences.update { it.copy(layout = layout) }
    scope.launch { dataStore?.edit { it[KEY_CW_LAYOUT] = layout.name } }
  }

  /** Immagine delle card: miniatura dell'episodio (ON) oppure backdrop (OFF). */
  fun setContinueWatchingEpisodeThumbnails(enabled: Boolean) {
    cwEpisodeThumbnailsLocalWrite = true
    _continueWatchingPreferences.update { it.copy(useEpisodeThumbnails = enabled) }
    scope.launch { dataStore?.edit { it[KEY_CW_EPISODE_THUMBNAILS] = enabled } }
  }

  /** Sfocatura delle immagini degli episodi non ancora visti. */
  fun setContinueWatchingBlurUnwatched(enabled: Boolean) {
    cwBlurUnwatchedLocalWrite = true
    _continueWatchingPreferences.update { it.copy(blurUnwatched = enabled) }
    scope.launch { dataStore?.edit { it[KEY_CW_BLUR_UNWATCHED] = enabled } }
  }

  /** ON: episodio di ripresa calcolato dal punto più avanzato della visione. */
  fun setContinueWatchingNextEpisodeFromFurthest(enabled: Boolean) {
    cwNextFromFurthestLocalWrite = true
    _continueWatchingPreferences.update { it.copy(nextEpisodeFromFurthest = enabled) }
    scope.launch { dataStore?.edit { it[KEY_CW_NEXT_FROM_FURTHEST] = enabled } }
  }

  /** Inclusione degli episodi non ancora trasmessi nella riga «Continua a guardare». */
  fun setContinueWatchingShowUnairedEpisodes(enabled: Boolean) {
    cwShowUnairedLocalWrite = true
    _continueWatchingPreferences.update { it.copy(showUnairedEpisodes = enabled) }
    scope.launch { dataStore?.edit { it[KEY_CW_SHOW_UNAIRED] = enabled } }
  }

  /** Ordinamento della riga «Continua a guardare». */
  fun setContinueWatchingSort(sort: ContinueWatchingSort) {
    cwSortLocalWrite = true
    _continueWatchingPreferences.update { it.copy(sort = sort) }
    scope.launch { dataStore?.edit { it[KEY_CW_SORT] = sort.name } }
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
   * Salva direttamente l'hash SHA-256 del PIN (modalità Sync: dall'altro dispositivo
   * arriva l'hash, mai il PIN in chiaro). Accetta solo un esadecimale a 64 caratteri:
   * un valore estraneo viene ignorato, così la chiave resta coerente con le scritture
   * di [setParentalControlPin].
   */
  fun setParentalControlPinHash(hash: String) {
    val clean = hash.trim().lowercase()
    if (clean.length != 64 || !clean.all { it.isDigit() || it in 'a'..'f' }) return
    parentalPinLocalWrite = true
    _parentalControlPin.value = clean
    scope.launch { dataStore?.edit { it[KEY_PARENTAL_CONTROL_PIN] = clean } }
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
