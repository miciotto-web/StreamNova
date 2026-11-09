package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.data.remote.simkl.SIMKL_MIN_PROGRESS
import com.example.data.remote.simkl.SIMKL_STATUS_PLAN_TO_WATCH
import com.example.data.remote.simkl.SIMKL_TYPE_ANIME
import com.example.data.remote.simkl.SIMKL_TYPE_MOVIES
import com.example.data.remote.simkl.SIMKL_TYPE_SHOWS
import com.example.data.remote.simkl.SimklAccountState
import com.example.data.remote.simkl.SimklActivitiesSnapshot
import com.example.data.remote.simkl.SimklApi
import com.example.data.remote.simkl.SimklApiClient
import com.example.data.remote.simkl.SimklAuthClient
import com.example.data.remote.simkl.SimklAuthException
import com.example.data.remote.simkl.SimklAuthFlow
import com.example.data.remote.simkl.SimklClientConfigException
import com.example.data.remote.simkl.SimklClientIdMissingException
import com.example.data.remote.simkl.SimklCompletedKind
import com.example.data.remote.simkl.SimklCompletedTitle
import com.example.data.remote.simkl.SimklLoginPrompt
import com.example.data.remote.simkl.SimklPlaybackEntry
import com.example.data.remote.simkl.SimklPlaybackParser
import com.example.data.remote.simkl.SimklPendingPlayback
import com.example.data.remote.simkl.SimklPendingPlaybackStore
import com.example.data.remote.simkl.SimklRefreshException
import com.example.data.remote.simkl.SimklSyncResult
import com.example.data.remote.simkl.SimklTokenBundle
import com.example.data.remote.simkl.SimklWatchlistEntry
import com.example.data.remote.simkl.SimklWatchlistParser
import com.example.data.remote.simkl.SimklWatchedLibrary
import com.example.data.remote.simkl.SimklWatchedLibraryData
import com.example.data.prefs.AppSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response

private val Context.simklDataStore by preferencesDataStore(name = "simkl_preferences")

/**
 * Messaggio dell'eccezione quando manca la configurazione applicativa (Client ID). La UI
 * lo sostituisce con un testo per l'utente: qui resta la riga da predisporre per lo
 * sviluppatore, utile nei log e nei test.
 */
const val SIMKL_CLIENT_ID_MISSING_MESSAGE =
  "Client ID SIMKL non configurato in questa build: impostare SIMKL_CLIENT_ID in .env, " +
    "local.properties o nell'ambiente e ricompilare."

object SimklRepository {
  private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
  private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
  private val KEY_TOKEN_EXPIRES_AT = stringPreferencesKey("token_expires_at")
  private val KEY_TOKEN_EXPIRES_AT_EPOCH = stringPreferencesKey("token_expires_at_epoch_ms")
  private val KEY_TOKEN_SCOPE = stringPreferencesKey("token_scope")
  private val KEY_AUTH_FLOW = stringPreferencesKey("auth_flow")
  private val KEY_USERNAME = stringPreferencesKey("username")
  private val KEY_ACCOUNT_ID = longPreferencesKey("account_id")
  private val KEY_ACCOUNT_TYPE = stringPreferencesKey("account_type")
  private val KEY_SESSION_EXPIRED = booleanPreferencesKey("session_expired")
  private val KEY_ACTIVITIES_SNAPSHOT = stringPreferencesKey("activities_snapshot")
  private val KEY_LAST_SYNC_AT = stringPreferencesKey("last_sync_at_epoch_ms")

  /**
   * Voci di playback temporaneamente non applicabili (durata o metadati non ancora noti).
   * Stanno **fuori** dallo snapshot delle attività: quello dice "cosa è cambiato su SIMKL",
   * questo dice "cosa non sono ancora riuscito ad applicare". Confonderli rendeva una voce
   * saltata irrecuperabile, perché il checkpoint avanzava comunque.
   */
  private val KEY_PENDING_PLAYBACKS = stringPreferencesKey("pending_playbacks")

  /**
   * Outbox persistente degli scrobble: eventi non ancora consegnati a SIMKL, legati ad
   * account e sessione. Vive per processo e per riavvio; viene svuotata alla disconnessione
   * (insieme al resto delle credenziali).
   */
  private val KEY_SCROBBLE_OUTBOX = stringPreferencesKey("scrobble_outbox")

  /**
   * Il refresh V2 è proattivo: un access token dura 7 giorni, quindi si rinnova quando
   * manca meno di un giorno alla scadenza (evita di scoprire la scadenza durante lo
   * scrobble, mentre il refresh token resta valido 180 giorni con finestra scorrevole).
   */
  private const val REFRESH_WINDOW_MS = 24L * 60L * 60L * 1000L
  private const val WATCHED_CACHE_TTL_MS = 5L * 60L * 1000L

  private val accountMutable = MutableStateFlow(SimklAccountState())
  val accountState: StateFlow<SimklAccountState> = accountMutable.asStateFlow()

  /**
   * Epoca della sessione SIMKL: incrementa a OGNI disconnessione. La sincronizzazione
   * cattura l'epoca all'ingresso e la riverifica (dentro [withSessionGate]) prima di ogni
   * scrittura: una sync avviata prima del logout che riprende DOPO la disconnessione trova
   * l'epoca cambiata e si abortisce senza pubblicare nulla. "La disconnessione ha sempre
   * la precedenza su una sincronizzazione precedente."
   */
  private val sessionEpoch = AtomicInteger(0)

  fun currentSessionEpoch(): Int = sessionEpoch.get()

  /**
   * Gate che serializza le scritture della sincronizzazione con la pulizia del logout:
   * la sync verifica l'epoca e scrive DENTRO il gate, il logout azzera l'epoca e pulisce
   * DENTRO il gate. Ogni scrittura di una sync finisce quindi o interamente PRIMA della
   * pulizia (e ne viene rimossa) o dopo un controllo di epoca fallito (e non avviene).
   */
  private val sessionGate = Mutex()

  suspend fun <T> withSessionGate(block: suspend () -> T): T = sessionGate.withLock { block() }

  private val lastSyncMutable = MutableStateFlow<SimklSyncResult?>(null)
  val lastSyncResult: StateFlow<SimklSyncResult?> = lastSyncMutable.asStateFlow()

  private val syncingMutable = MutableStateFlow(false)
  val isSyncing: StateFlow<Boolean> = syncingMutable.asStateFlow()

  private var appContext: Context? = null

  // L'API SIMKL (Retrofit + Moshi + OkHttp) veniva costruita durante
  // l'inizializzazione dell'oggetto, quindi PRIMA del primo frame (~150 ms,
  // misurato con StartupTrace). Ora il default è lazily: la costruzione avviene
  // alla prima chiamata reale, che gira su Dispatchers.IO. I test mantengono la
  // possibilità di iniettare un'API finta tramite [init].
  private val defaultApi: SimklApi by lazy { SimklApiClient.api }
  @Volatile private var apiOverride: SimklApi? = null

  private val api: SimklApi
    get() = apiOverride ?: defaultApi

  /**
   * Client di autenticazione: DEVE avvolgere la stessa [api] effettiva (finta nei
   * test, reale in produzione). Non è un `by lazy` perché [init] può riconfigurare
   * l'API: in quel caso l'istanza viene ricostruita al primo uso successivo.
   * Con l'API di default la costruzione resta differita a dopo il primo frame.
   */
  @Volatile private var authClientInstance: SimklAuthClient? = null
  private val authClient: SimklAuthClient
    get() = authClientInstance ?: synchronized(this) {
      authClientInstance ?: SimklAuthClient(api).also { authClientInstance = it }
    }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Un solo refresh per volta: due refresh paralleli si invaliderebbero a vicenda. */
  private val refreshMutex = Mutex()

  private val watchedEpisodesCache = ConcurrentHashMap<String, CacheEntry>()
  private val watchedLibrary = AtomicReference<Map<Int, Map<Int, Set<Int>>>>(emptyMap())
  private val watchedRevisionMutable = MutableStateFlow(0L)
  val watchedRevision: StateFlow<Long> = watchedRevisionMutable.asStateFlow()

  private val loginFlowCache = AtomicReference<SimklAuthFlow?>(null)

  /** Un solo warning "Client ID non configurato" per processo, per non inondare Logcat. */
  private val missingClientIdWarned = java.util.concurrent.atomic.AtomicBoolean(false)

  private data class CacheEntry(val expiresAt: Long, val episodes: Set<Int>)

  @Synchronized
  fun init(context: Context, simklApi: SimklApi? = null) {
    val applicationContext = context.applicationContext
    // Rientrare con lo stesso contesto E la stessa implementazione è un no-op; se l'API
    // iniettata cambia (test, ambiente alternativo) l'oggetto si riconfigura.
    // `simklApi == null` significa "usa il default lazy": non va risolto qui, altrimenti
    // Retrofit/Moshi/OkHttp tornerebbero sul percorso di avvio.
    if (appContext === applicationContext && apiOverride === simklApi) return
    appContext = applicationContext
    apiOverride = simklApi
    // Con un'API iniettata il client va ricostruito subito attorno a quella (i test
    // usano un MockWebServer). Con il default resta differito al primo uso reale.
    authClientInstance = simklApi?.let { SimklAuthClient(it) }
    scope.launch {
      val store = applicationContext.simklDataStore
      val first = store.data.first()
      loginFlowCache.set(parseFlow(first[KEY_AUTH_FLOW]))
      hydrateSyncMetadata(first)
      accountMutable.value = first.toAccountState()
      // Ora l'account è idratato: il worker può distinguere gli eventi della sessione
      // corrente da quelli rimasti da una sessione precedente.
      ensureScrobbleWorkerStarted()
      store.data.collect {
        loginFlowCache.set(parseFlow(it[KEY_AUTH_FLOW]))
        hydrateSyncMetadata(it)
        accountMutable.value = it.toAccountState()
      }
    }
  }

  private fun parseFlow(raw: String?): SimklAuthFlow? = when (raw) {
    SimklAuthFlow.V1_PIN.name -> SimklAuthFlow.V1_PIN
    SimklAuthFlow.V2_DEVICE.name -> SimklAuthFlow.V2_DEVICE
    else -> null
  }

  private fun androidx.datastore.preferences.core.Preferences.toAccountState() = SimklAccountState(
    accessToken = this[KEY_ACCESS_TOKEN],
    refreshToken = this[KEY_REFRESH_TOKEN],
    username = this[KEY_USERNAME],
    accountId = this[KEY_ACCOUNT_ID],
    accountType = this[KEY_ACCOUNT_TYPE],
    tokenExpiresAt = this[KEY_TOKEN_EXPIRES_AT],
    tokenExpiresAtEpochMs = this[KEY_TOKEN_EXPIRES_AT_EPOCH]?.toLongOrNull(),
    scope = this[KEY_TOKEN_SCOPE],
    flow = parseFlow(this[KEY_AUTH_FLOW]),
    sessionExpired = this[KEY_SESSION_EXPIRED] ?: false
  )

  fun hasClientId(): Boolean = getClientId().isNotBlank()

  /**
   * Un canale di configurazione del Client ID, con il nome con cui compare nella diagnosi
   * di [requireClientId]. [raw] è `null` quando il canale non offre nulla.
   */
  internal data class SimklClientIdChannel(val name: String, val raw: String?)

  /** Client ID utilizzabile e canale che l'ha fornito: `source` è `null` se non ce n'è nessuno. */
  internal data class SimklClientIdResolution(val value: String, val source: String?) {
    val isConfigured: Boolean get() = value.isNotBlank()
  }

  /**
   * Client ID effettivo: una **configurazione dello sviluppatore**, mai una richiesta
   * all'utente (l'app non espone più alcun campo per inserirlo — vedi `.env.example`).
   *
   * Canali, in ordine di priorità:
   *  1. `BuildConfig.ENV_SIMKL_CLIENT_ID`, valorizzato **in fase di build** da
   *     `SIMKL_CLIENT_ID` (variabile d'ambiente, `local.properties` o `.env`, tutti fuori
   *     dal repository);
   *  2. `BuildConfig.SIMKL_CLIENT_ID`, generato dal plugin `secrets` a partire da `.env`
   *     (`.env.example` copre anche il caso in cui `.env` non esista, con un segnaposto);
   *  3. valore residuo in DataStore, scritto dalle build di Fase 3 (nessuna UI lo scrive).
   *
   * I primi due sono **costanti incluse nell'APK**: è il valore dell'app a essere usato, non
   * una variabile d'ambiente del dispositivo. `System.getenv("SIMKL_CLIENT_ID")` sopravvive
   * solo dentro Gradle, dove serve a scrivere il campo di build: a runtime su una TV
   * quell'ambiente non esiste e non va interrogato (una build priva di Client ID deve
   * dichiararsi tale, non sperare in un valore esterno).
   *
   * I segnaposto di `.env.example` vengono scartati: con un placeholder l'app si comporta
   * come se il Client ID mancasse, invece di spedire una richiesta con un ID finto.
   */
  internal fun simklClientIdChannels(): List<SimklClientIdChannel> = listOf(
    SimklClientIdChannel("build:ENV_SIMKL_CLIENT_ID", BuildConfig.ENV_SIMKL_CLIENT_ID),
    SimklClientIdChannel("build:SIMKL_CLIENT_ID", BuildConfig.SIMKL_CLIENT_ID),
    SimklClientIdChannel("prefs:simkl_client_id", AppSettingsRepository.simklClientId.value)
  )

  /** Primo canale utilizzabile, con il nome del canale: serve a spiegare *dove* guardare. */
  internal fun resolveSimklClientId(channels: List<SimklClientIdChannel>): SimklClientIdResolution =
    channels.firstNotNullOfOrNull { channel ->
      sanitizeSimklClientId(channel.raw)?.let { SimklClientIdResolution(it, channel.name) }
    } ?: SimklClientIdResolution("", null)

  /** `null` per vuoti, segnaposto (`DEFAULT_…`, `YOUR_…`) e virgolette residue. */
  internal fun sanitizeSimklClientId(raw: String?): String? {
    val value = raw?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null
    val upper = value.uppercase()
    return value.takeUnless {
      upper.startsWith("DEFAULT_") || upper.startsWith("YOUR_") || upper == "SIMKL_CLIENT_ID"
    }
  }

  private fun getClientId(): String = resolveSimklClientId(simklClientIdChannels()).value

  /**
   * Stato di ogni canale, **senza il valore**: finisce in Logcat quando il Client ID manca,
   * così chi sviluppa vede subito se il problema è la build (canale vuoto o segnaposto) o un
   * residuo su disco, senza che un identificativo dell'app venga mai stampato.
   */
  private fun clientIdChannelReport(): String = simklClientIdChannels().joinToString("; ") { channel ->
    val state = when {
      channel.raw.isNullOrBlank() -> "vuoto"
      sanitizeSimklClientId(channel.raw) == null -> "segnaposto (rifiutato)"
      else -> "utilizzabile"
    }
    "${channel.name}=$state"
  }

  /**
   * Senza Client ID non esiste alcun flusso di autorizzazione: si fallisce subito, con un
   * messaggio che non chiede nulla di tecnico all'utente. La riga da configurare **per lo
   * sviluppatore** finisce una sola volta in Logcat, con lo stato di ogni canale.
   */
  private fun requireClientId(): String = getClientId().takeIf { it.isNotBlank() }
    ?: run {
      if (missingClientIdWarned.compareAndSet(false, true)) {
        android.util.Log.w(
          TAG,
          "Client ID SIMKL non configurato: impostare SIMKL_CLIENT_ID in .env, in " +
            "local.properties o come variabile d'ambiente e ricompilare. Canali: " +
            clientIdChannelReport()
        )
      }
      throw SimklClientIdMissingException(SIMKL_CLIENT_ID_MISSING_MESSAGE)
    }

  // ── LOGIN (device flow V2 con QR precompilato, fallback PIN V1) ──────────────

  /** Avvia il login e restituisce il prompt (QR + codice) da mostrare all'utente. */
  suspend fun startLogin(): SimklLoginPrompt = withContext(Dispatchers.IO) {
    val prompt = authClient.startLogin(requireClientId(), rememberedFlow = loginFlowCache.get())
    loginFlowCache.set(prompt.flow)
    prompt
  }

  /**
   * Attende l'approvazione dell'utente e salva i token. Il polling vive nella coroutine
   * del chiamante: annullarla (chiusura del pannello) ferma anche il polling.
   */
  suspend fun awaitLogin(prompt: SimklLoginPrompt): SimklAccountState = withContext(Dispatchers.IO) {
    val bundle = authClient.awaitLogin(requireClientId(), prompt)
    persistTokens(bundle)
  }

  private suspend fun persistTokens(bundle: SimklTokenBundle): SimklAccountState {
    // /users/settings è best-effort: un errore qui non deve far fallire un login riuscito.
    val settings = runCatching {
      api.getUserSettings(requireClientId(), "Bearer ${bundle.accessToken}")
        .takeIf { it.isSuccessful }?.body()
    }.getOrNull()
    val state = SimklAccountState(
      accessToken = bundle.accessToken,
      refreshToken = bundle.refreshToken,
      username = settings?.user?.name,
      accountId = settings?.account?.id,
      accountType = settings?.account?.type,
      tokenExpiresAt = bundle.expiresAtEpochMs?.toString(),
      tokenExpiresAtEpochMs = bundle.expiresAtEpochMs,
      scope = bundle.scope,
      flow = bundle.flow,
      sessionExpired = false
    )
    appContext?.simklDataStore?.edit { prefs -> prefs.writeAccount(state) }
    accountMutable.value = state
    loginFlowCache.set(bundle.flow)
    invalidateWatchedCache()
    ensureScrobbleWorkerStarted()
    return state
  }

  private fun androidx.datastore.preferences.core.MutablePreferences.writeAccount(state: SimklAccountState) {
    state.accessToken?.let { this[KEY_ACCESS_TOKEN] = it } ?: remove(KEY_ACCESS_TOKEN)
    state.refreshToken?.let { this[KEY_REFRESH_TOKEN] = it } ?: remove(KEY_REFRESH_TOKEN)
    state.tokenExpiresAt?.let { this[KEY_TOKEN_EXPIRES_AT] = it } ?: remove(KEY_TOKEN_EXPIRES_AT)
    state.tokenExpiresAtEpochMs?.let { this[KEY_TOKEN_EXPIRES_AT_EPOCH] = it.toString() }
      ?: remove(KEY_TOKEN_EXPIRES_AT_EPOCH)
    state.scope?.let { this[KEY_TOKEN_SCOPE] = it } ?: remove(KEY_TOKEN_SCOPE)
    state.flow?.let { this[KEY_AUTH_FLOW] = it.name } ?: remove(KEY_AUTH_FLOW)
    state.username?.let { this[KEY_USERNAME] = it } ?: remove(KEY_USERNAME)
    state.accountId?.let { this[KEY_ACCOUNT_ID] = it } ?: remove(KEY_ACCOUNT_ID)
    state.accountType?.let { this[KEY_ACCOUNT_TYPE] = it } ?: remove(KEY_ACCOUNT_TYPE)
    this[KEY_SESSION_EXPIRED] = state.sessionExpired
  }

  /**
   * Scollega l'account. Con AUTH V2 revoca il grant sul server (best-effort: `200` non
   * prova che la revoca sia avvenuta) e in ogni caso cancella SOLO le credenziali e i
   * metadati di sincronizzazione. Nessun dato dell'account remoto SIMKL viene cancellato:
   * la revoca invalida soltanto il grant di QUESTA app.
   *
   * Oltre alle credenziali, vengono rimossi dal catalogo locale i dati che la
   * sincronizzazione aveva copiato (preferiti e progressi di provenienza SIMKL — vedi
   * [MediaRepository.purgeSimklData]): i dati LOCALI restano intatti.
   *
   * Ordine delle operazioni:
   *  1. l'epoca di sessione cambia SUBITO (una sync in corso abortisce al prossimo
   *     controllo, prima di qualunque nuova scrittura);
   *  2. pulizia di credenziali, metadati e dati importati, dentro [withSessionGate]:
   *     nessun salvataggio terminale di una sync in corso può ricreare chiavi nel
   *     datastore appena svuotato;
   *  3. la revoca di rete arriva PER ULTIMA, best-effort: la disconnessione non deve
   *     attendere la rete per aggiornare l'interfaccia.
   */
  suspend fun signOut() {
    sessionEpoch.incrementAndGet()
    val current = accountMutable.value
    val token = current.refreshToken?.takeIf { it.isNotBlank() }
      ?: current.accessToken?.takeIf { it.isNotBlank() }
    val canRevoke = current.flow == SimklAuthFlow.V2_DEVICE &&
      token != null && hasClientId()
    withSessionGate {
      appContext?.simklDataStore?.edit { prefs -> prefs.clear() }
      accountMutable.value = SimklAccountState()
      lastSyncMutable.value = null
      // Gli eventi non consegnati appartengono all'account disconnesso: non devono
      // sopravvivere né essere spediti con una sessione futura.
      scrobblePending.set(0)
      // Gli snapshot non-Flow di sincronizzazione non devono sopravvivere alla
      // disconnessione: altrimenti la prossima sync del nuovo collegamento partirebbe
      // da un checkpoint della sessione precedente.
      activitiesSnapshotCache = null
      activitiesSnapshotRaw = null
      lastSyncRaw = null
      loginFlowCache.set(null)
      invalidateWatchedCache()
      watchedLibrary.set(emptyMap())
      // I preferiti e i progressi copiati nel catalogo dalla sincronizzazione escono
      // dall'interfaccia con la disconnessione: i dati locali restano.
      MediaRepository.purgeSimklData()
    }
    if (canRevoke) {
      runCatching { authClient.revoke(getClientId(), token) }
    }
  }

  /**
   * Segna la sessione come scaduta (refresh rifiutato): i token restano su disco — nessuna
   * cancellazione per un errore — ma l'app sa che serve una nuova autorizzazione.
   * Non fa nulla se l'account risulta già scollegato: dopo una disconnessione nessuna
   * scrittura residua deve riapparire nel datastore appena svuotato.
   */
  private suspend fun markSessionExpired(message: String) {
    val current = accountMutable.value
    if (!current.isLoggedIn || current.sessionExpired) return
    val updated = current.copy(sessionExpired = true)
    appContext?.simklDataStore?.edit { prefs -> prefs.writeAccount(updated) }
    accountMutable.value = updated
    android.util.Log.w(TAG, "Sessione SIMKL non più valida: $message")
  }

  /** Rinnova il token V2 quando manca meno di un giorno (o forzatamente dopo un 401). */
  private suspend fun ensureFreshToken(force: Boolean = false) {
    val state = accountMutable.value
    state.accessToken?.takeIf { it.isNotBlank() } ?: throw SimklAuthException("Account SIMKL non collegato.")
    if (state.flow != SimklAuthFlow.V2_DEVICE) return
    val refreshToken = state.refreshToken?.takeIf { it.isNotBlank() } ?: return
    val expiresAt = state.tokenExpiresAtEpochMs
    val aboutToExpire = expiresAt != null && System.currentTimeMillis() >= expiresAt - REFRESH_WINDOW_MS
    if (!force && !aboutToExpire) return
    refreshMutex.withLock {
      // Un altro chiamante può aver già rinfrescato mentre si attendeva il lock.
      val latest = accountMutable.value
      if (!force) {
        val latestExpiry = latest.tokenExpiresAtEpochMs
        if (latestExpiry != null && System.currentTimeMillis() < latestExpiry - REFRESH_WINDOW_MS) return@withLock
      }
      val currentRefresh = latest.refreshToken?.takeIf { it.isNotBlank() } ?: return@withLock
      // Il refresh attraversa la rete: se la disconnessione avviene mentre è in volo, le
      // credenziali appena ottenute NON devono essere riscritte — altrimenti un account
      // appena scollegato "risuscita" con i token del refresh partito prima del logout.
      val epochBeforeRefresh = sessionEpoch.get()
      try {
        val bundle = authClient.refresh(requireClientId(), currentRefresh)
        if (sessionEpoch.get() != epochBeforeRefresh) {
          Log.i(TAG, "Refresh completato dopo la disconnessione: credenziali scartate.")
          return@withLock
        }
        persistTokens(bundle)
      } catch (e: SimklRefreshException) {
        if (e.needsReauthorization) markSessionExpired(e.localizedMessage ?: "refresh rifiutato")
        throw e
      }
    }
  }

  /**
   * Richiesta autenticata con autorecupero: se il token V2 è scaduto (401) si rinnova
   * una volta e si ritenta. Gli errori di rete del refresh NON cancellano le credenziali.
   */
  private suspend fun <T> withAuthorizedToken(block: suspend (String) -> Response<T>): Response<T> {
    ensureFreshToken()
    var token = accountMutable.value.accessToken?.takeIf { it.isNotBlank() }
      ?: throw SimklAuthException("Account SIMKL non collegato.")
    val first = block(token)
    val state = accountMutable.value
    if (first.code() == 401 && state.flow == SimklAuthFlow.V2_DEVICE && !state.refreshToken.isNullOrBlank()) {
      ensureFreshToken(force = true)
      token = accountMutable.value.accessToken?.takeIf { it.isNotBlank() } ?: return first
      return block(token)
    }
    return first
  }

  // ── SCRITTURE (scrobble e cronologia) ───────────────────────────────────────

  /** Evento del player che produce una scrittura scrobble. */
  enum class SimklScrobbleEvent(val endpoint: String) {
    START("scrobble/start"),
    PAUSE("scrobble/pause"),
    STOP("scrobble/stop")
  }

  /** Motivo per cui un evento NON è diventato una richiesta HTTP. */
  enum class SimklScrobbleRejection(val label: String) {
    /** Nessun identificativo utilizzabile: SIMKL non potrebbe agganciare titolo ed episodio. */
    MISSING_IDS("identificativi assenti"),

    /**
     * Durata non nota: la percentuale sarebbe uno zero **inventato**, non una posizione
     * reale. Inviarla direbbe a SIMKL "riprendi da zero" per un titolo già a metà.
     */
    UNKNOWN_DURATION("durata non nota")
  }

  enum class SimklScrobbleOutcome {
    SUCCESS,

    /** Risposta HTTP non riuscita (4xx/5xx): il server ha parlato. */
    HTTP_ERROR,

    /** Guasto di trasporto (`IOException`): la richiesta non è arrivata. Ritentabile. */
    NETWORK_ERROR,

    /**
     * La richiesta non è stata nemmeno costruita (errore di programmazione o di
     * serializzazione del payload): NON è un guasto di rete e non si ritenta. Deve restare
     * distinguibile, perché un errore di questo tipo rende impossibile ogni scrittura e
     * fino a oggi era invisibile (assorbito da un `runCatching` senza log).
     */
    REQUEST_ERROR,

    NOT_LOGGED_IN,
    NOT_CONFIGURED,
    REJECTED,
    QUEUE_FULL
  }

  /**
   * Esito reale di un evento di scrittura. È la traccia che rende diagnosticabile ciò che
   * altrimenti sparisce: prima ogni errore era assorbito da un `runCatching` senza log,
   * senza retry e senza alcun esito osservabile.
   */
  data class SimklScrobbleReport(
    val event: SimklScrobbleEvent,
    val outcome: SimklScrobbleOutcome,
    /** Come compare nei log: titolo, con `S…E…` quando è un episodio. */
    val label: String,
    val progressPercent: Double? = null,
    val httpCode: Int = 0,
    val attempts: Int = 0,
    val rejection: SimklScrobbleRejection? = null,
    val detail: String = ""
  ) {
    val isSuccess: Boolean get() = outcome == SimklScrobbleOutcome.SUCCESS
  }

  /**
   * Outbox PERSISTENTE delle scritture verso SIMKL.
   *
   * Gli eventi nascono da callback del player e devono sopravvivere **sia alla schermata
   * sia al processo**: la chiusura del player cancellava la coroutine che aveva in volo lo
   * `stop`, e un riavvio dell'app perdeva gli eventi rimasti in memoria. Ogni evento viene
   * quindi scritto su DataStore prima di essere inviato, con `accountId` e `sessionEpoch`:
   * un evento di una sessione precedente non viene mai spedito con le credenziali nuove.
   *
   * Un solo worker consuma l'outbox in ordine FIFO (start → pause → stop). Il lock di 20
   * secondi di SIMKL (`400 RATE_LIMIT`, non 429) non fa perdere lo STOP: l'evento resta
   * nell'outbox e viene ritentato dopo il lock, senza consumare il budget degli errori di
   * rete. Anche un 409 su `stop` (già registrato entro un'ora) è un successo: il watch è
   * già nella cronologia.
   */
  private val scrobbleOutboxMutex = Mutex()
  private val scrobbleWake = Channel<Unit>(Channel.CONFLATED)
  private val scrobbleWorkerStarted = AtomicBoolean(false)
  private val scrobblePending = AtomicInteger(0)
  private val scrobbleReports = java.util.Collections.synchronizedList(mutableListOf<SimklScrobbleReport>())

  private const val SCROBBLE_QUEUE_CAPACITY = 64
  private const val SCROBBLE_MAX_ATTEMPTS = 2
  private const val SCROBBLE_MAX_LOCK_RETRIES = 12
  private const val SCROBBLE_RETRY_DELAY_MS = 750L
  private const val SCROBBLE_REPORT_HISTORY = 20

  /**
   * Ritardo dopo un lock SIMKL. La documentazione indica 20 s; è `internal var` per i test,
   * che non devono attendere venti secondi reali.
   */
  internal var lockRetryDelayMs: Long = 20_000L

  private sealed interface ScrobbleAttempt {
    data class Done(
      val code: Int,
      val action: String?,
      val detail: String = ""
    ) : ScrobbleAttempt

    data class Retry(
      val delayMs: Long,
      val outcome: SimklScrobbleOutcome,
      val code: Int = 0,
      val detail: String = "",
      val isLock: Boolean = false
    ) : ScrobbleAttempt

    data class Permanent(
      val outcome: SimklScrobbleOutcome,
      val code: Int = 0,
      val detail: String = ""
    ) : ScrobbleAttempt
  }

  /**
   * Accoda una scrittura scrobble e ritorna subito: non sospende, non blocca e non lancia.
   *
   * @param progressPercent percentuale corrente, **`null` quando la durata non è nota**.
   *        Un payload senza identificativi o con una percentuale non calcolabile viene
   *        rifiutato e registrato in [scrobbleDiagnostics], invece di essere spedito.
   * @return `true` se l'evento è entrato nell'outbox (non se ha già avuto successo).
   */
  fun enqueueScrobble(
    event: SimklScrobbleEvent,
    media: SimklMediaPayload,
    progressPercent: Double?
  ): Boolean {
    val label = media.scrobbleLabel()
    val rejection = when {
      !media.hasIdentifiers -> SimklScrobbleRejection.MISSING_IDS
      progressPercent == null || !progressPercent.isFinite() -> SimklScrobbleRejection.UNKNOWN_DURATION
      else -> null
    }
    if (rejection != null) {
      recordScrobbleReport(
        SimklScrobbleReport(event, SimklScrobbleOutcome.REJECTED, label, progressPercent, rejection = rejection)
      )
      Log.w(TAG, "Scrobble ${event.endpoint}: non inviato per $label (${rejection.label}).")
      return false
    }
    val account = accountMutable.value
    if (!account.isLoggedIn) {
      recordScrobbleReport(
        SimklScrobbleReport(event, SimklScrobbleOutcome.NOT_LOGGED_IN, label, progressPercent)
      )
      Log.i(TAG, "Scrobble ${event.endpoint}: account SIMKL non collegato, $label ignorato.")
      return false
    }
    if (scrobblePending.get() >= SCROBBLE_QUEUE_CAPACITY) {
      recordScrobbleReport(
        SimklScrobbleReport(event, SimklScrobbleOutcome.QUEUE_FULL, label, progressPercent)
      )
      Log.w(TAG, "Scrobble ${event.endpoint}: outbox piena, evento non accodato per $label.")
      return false
    }
    val entry = SimklOutboxEntry(
      id = java.util.UUID.randomUUID().toString(),
      accountId = account.accountId,
      sessionEpoch = currentSessionEpoch(),
      event = event,
      media = media,
      progressPercent = progressPercent!!.coerceIn(0.0, 100.0),
      createdAt = System.currentTimeMillis()
    )
    ensureScrobbleWorkerStarted()
    scrobblePending.incrementAndGet()
    scope.launch {
      scrobbleOutboxMutex.withLock { appendOutboxEntry(entry) }
      scrobbleWake.trySend(Unit)
    }
    return true
  }

  private fun ensureScrobbleWorkerStarted() {
    if (!scrobbleWorkerStarted.compareAndSet(false, true)) return
    scope.launch {
      // Gli eventi rimasti da un processo precedente sono già nell'outbox.
      scrobblePending.set(loadOutbox().size)
      while (isActive) {
        val now = System.currentTimeMillis()
        val entry = scrobbleOutboxMutex.withLock { loadOutbox().minByOrNull { it.createdAt } }
        if (entry == null) {
          withTimeoutOrNull(60_000L) { scrobbleWake.receive() }
          continue
        }
        if (!isEntryForCurrentSession(entry)) {
          removeOutboxEntry(entry.id)
          continue
        }
        if (entry.nextAttemptAt > now) {
          delay((entry.nextAttemptAt - now).coerceAtMost(60_000L))
          continue
        }
        when (val attempt = attemptScrobble(entry)) {
          is ScrobbleAttempt.Done -> {
            removeOutboxEntry(entry.id)
            onScrobbleSucceeded(entry, attempt)
          }
          is ScrobbleAttempt.Permanent -> {
            removeOutboxEntry(entry.id)
            recordScrobbleReport(
              SimklScrobbleReport(
                entry.event, attempt.outcome, entry.media.scrobbleLabel(),
                entry.progressPercent, attempt.code, entry.attempts + 1,
                detail = attempt.detail.take(200)
              )
            )
            Log.w(
              TAG,
              "Scrobble ${entry.event.endpoint} NON riuscito per ${entry.media.scrobbleLabel()}: " +
                "${attempt.outcome}${if (attempt.code > 0) " HTTP ${attempt.code}" else ""} " +
                attempt.detail
            )
          }
          is ScrobbleAttempt.Retry -> {
            if (attempt.isLock) {
              val lockRetries = entry.lockRetries + 1
              if (lockRetries > SCROBBLE_MAX_LOCK_RETRIES) {
                removeOutboxEntry(entry.id)
                recordScrobbleReport(
                  SimklScrobbleReport(
                    entry.event, SimklScrobbleOutcome.HTTP_ERROR, entry.media.scrobbleLabel(),
                    entry.progressPercent, attempt.code, entry.attempts + 1,
                    detail = "lock SIMKL: ${attempt.detail}".take(200)
                  )
                )
                Log.w(
                  TAG,
                  "Scrobble ${entry.event.endpoint}: lock SIMKL persistente per " +
                    "${entry.media.scrobbleLabel()}, evento abbandonato dopo $lockRetries tentativi."
                )
              } else {
                updateOutboxEntry(
                  entry.copy(
                    lockRetries = lockRetries,
                    nextAttemptAt = System.currentTimeMillis() + attempt.delayMs
                  )
                )
                Log.i(
                  TAG,
                  "Scrobble ${entry.event.endpoint}: lock SIMKL per ${entry.media.scrobbleLabel()}, " +
                    "ritento tra ${attempt.delayMs} ms (tentativo lock $lockRetries)."
                )
              }
            } else {
              val attempts = entry.attempts + 1
              if (attempts >= SCROBBLE_MAX_ATTEMPTS) {
                removeOutboxEntry(entry.id)
                recordScrobbleReport(
                  SimklScrobbleReport(
                    entry.event, attempt.outcome, entry.media.scrobbleLabel(),
                    entry.progressPercent, attempt.code, attempts, detail = attempt.detail.take(200)
                  )
                )
                Log.w(
                  TAG,
                  "Scrobble ${entry.event.endpoint} NON riuscito per ${entry.media.scrobbleLabel()} " +
                    "dopo $attempts tentativi: ${attempt.detail}"
                )
              } else {
                updateOutboxEntry(
                  entry.copy(
                    attempts = attempts,
                    nextAttemptAt = System.currentTimeMillis() + attempt.delayMs
                  )
                )
              }
            }
          }
        }
      }
    }
  }

  private suspend fun attemptScrobble(entry: SimklOutboxEntry): ScrobbleAttempt {
    val label = entry.media.scrobbleLabel()
    if (!accountMutable.value.isLoggedIn) {
      return ScrobbleAttempt.Permanent(SimklScrobbleOutcome.NOT_LOGGED_IN)
    }
    return try {
      val response = postScrobble(entry.event, entry.media, entry.progressPercent)
      val action = response.body()?.get("action")?.toString()
      if (response.isSuccessful) {
        if (entry.event == SimklScrobbleEvent.STOP && action == "scrobble") {
          markWatchedFromResponse(entry)
          invalidateWatchedCache()
        }
        ScrobbleAttempt.Done(response.code(), action)
      } else if (entry.event == SimklScrobbleEvent.STOP && response.code() == 409) {
        // Già registrato entro l'ora: il watch esiste, non è un errore da ritentare.
        markWatchedFromResponse(entry)
        ScrobbleAttempt.Done(response.code(), "already_watched")
      } else {
        val errorBody = runCatching { response.errorBody()?.string().orEmpty() }.getOrDefault("")
        val rateLimited = response.code() == 429 ||
          (response.code() == 400 && errorBody.contains("RATE_LIMIT", ignoreCase = true))
        if (rateLimited) {
          val retryAfterMs = parseRetryAfterMs(response.headers()["Retry-After"])
            ?: lockRetryDelayMs
          ScrobbleAttempt.Retry(
            delayMs = retryAfterMs,
            outcome = SimklScrobbleOutcome.HTTP_ERROR,
            code = response.code(),
            detail = "RATE_LIMIT",
            isLock = true
          )
        } else if (response.code() >= 500) {
          ScrobbleAttempt.Retry(
            delayMs = SCROBBLE_RETRY_DELAY_MS,
            outcome = SimklScrobbleOutcome.HTTP_ERROR,
            code = response.code(),
            detail = "HTTP ${response.code()}"
          )
        } else {
          // 4xx: token senza media:write, payload rifiutato, sessione revocata.
          ScrobbleAttempt.Permanent(
            SimklScrobbleOutcome.HTTP_ERROR,
            response.code(),
            "HTTP ${response.code()} $label"
          )
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: SimklClientConfigException) {
      ScrobbleAttempt.Permanent(SimklScrobbleOutcome.NOT_CONFIGURED, detail = e.javaClass.simpleName)
    } catch (e: SimklRefreshException) {
      if (e.needsReauthorization) {
        ScrobbleAttempt.Permanent(SimklScrobbleOutcome.NOT_LOGGED_IN, detail = e.javaClass.simpleName)
      } else {
        ScrobbleAttempt.Retry(
          delayMs = SCROBBLE_RETRY_DELAY_MS,
          outcome = SimklScrobbleOutcome.NETWORK_ERROR,
          detail = e.javaClass.simpleName
        )
      }
    } catch (e: SimklAuthException) {
      ScrobbleAttempt.Permanent(SimklScrobbleOutcome.NOT_LOGGED_IN, detail = e.javaClass.simpleName)
    } catch (e: java.io.IOException) {
      ScrobbleAttempt.Retry(
        delayMs = SCROBBLE_RETRY_DELAY_MS,
        outcome = SimklScrobbleOutcome.NETWORK_ERROR,
        detail = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
      )
    } catch (e: Exception) {
      // Errore di costruzione/serializzazione: riprovare non lo rende valido.
      ScrobbleAttempt.Permanent(
        SimklScrobbleOutcome.REQUEST_ERROR,
        detail = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
      )
    }
  }

  private fun onScrobbleSucceeded(entry: SimklOutboxEntry, attempt: ScrobbleAttempt.Done) {
    val actionSuffix = attempt.action?.let { " action=$it" }.orEmpty()
    recordScrobbleReport(
      SimklScrobbleReport(
        entry.event, SimklScrobbleOutcome.SUCCESS, entry.media.scrobbleLabel(),
        entry.progressPercent, attempt.code, entry.attempts + 1,
        detail = (attempt.detail + actionSuffix).trim()
      )
    )
    Log.i(
      TAG,
      "Scrobble ${entry.event.endpoint} riuscito per ${entry.media.scrobbleLabel()} " +
        "(${percentText(entry.progressPercent)}%$actionSuffix)."
    )
  }

  /**
   * Verifica la risposta effettiva di SIMKL: un `action: "scrobble"` (o un 409 su stop)
   * significa che il watch è stato registrato. Solo allora il film viene marcato concluso
   * localmente e, per un episodio, viene consolidata la posizione finale.
   */
  private suspend fun markWatchedFromResponse(entry: SimklOutboxEntry) {
    // La risposta può arrivare dopo una disconnessione: un completamento della sessione
    // precedente non deve essere scritto nello stato locale della sessione nuova.
    if (!isEntryForCurrentSession(entry)) return
    val mediaId = entry.media.mediaId ?: return
    val episode = entry.media.episode
    if (episode == null) {
      MediaRepository.markCompletedFromSimkl(
        mediaId = mediaId,
        // Istante in cui l'utente ha prodotto l'evento, non l'ora della consegna: un
        // ritentativo dopo il lock non deve spostare la card come se fosse più recente.
        completedAtMs = entry.createdAt,
        setProgressToEnd = true
      )
    } else {
      MediaRepository.markEpisodeCompletedFromSimkl(
        mediaId = mediaId,
        seasonNumber = episode.season,
        episodeNumber = episode.number,
        completedAtMs = entry.createdAt
      )
    }
  }

  private fun parseRetryAfterMs(raw: String?): Long? {
    val seconds = raw?.trim()?.toLongOrNull() ?: return null
    return (seconds * 1_000L).coerceIn(1_000L, 120_000L)
  }

  // ── Persistenza dell'outbox ────────────────────────────────────────────────

  private suspend fun loadOutbox(): List<SimklOutboxEntry> {
    val store = appContext?.simklDataStore ?: return emptyList()
    return SimklScrobbleOutboxStore.decode(store.data.first()[KEY_SCROBBLE_OUTBOX])
  }

  private suspend fun appendOutboxEntry(entry: SimklOutboxEntry) {
    val store = appContext?.simklDataStore ?: return
    store.edit { prefs ->
      val current = SimklScrobbleOutboxStore.decode(prefs[KEY_SCROBBLE_OUTBOX])
      prefs[KEY_SCROBBLE_OUTBOX] = SimklScrobbleOutboxStore.encode(current + entry)
    }
  }

  private suspend fun updateOutboxEntry(entry: SimklOutboxEntry) {
    val store = appContext?.simklDataStore ?: return
    store.edit { prefs ->
      val current = SimklScrobbleOutboxStore.decode(prefs[KEY_SCROBBLE_OUTBOX])
      val updated = current.map { if (it.id == entry.id) entry else it }
      prefs[KEY_SCROBBLE_OUTBOX] = SimklScrobbleOutboxStore.encode(updated)
    }
  }

  private suspend fun removeOutboxEntry(id: String) {
    val store = appContext?.simklDataStore ?: return
    var removed = false
    store.edit { prefs ->
      val current = SimklScrobbleOutboxStore.decode(prefs[KEY_SCROBBLE_OUTBOX])
      val updated = current.filterNot { it.id == id }
      removed = updated.size != current.size
      if (updated.isEmpty()) prefs.remove(KEY_SCROBBLE_OUTBOX)
      else prefs[KEY_SCROBBLE_OUTBOX] = SimklScrobbleOutboxStore.encode(updated)
    }
    if (removed) scrobblePending.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
  }

  private fun isEntryForCurrentSession(entry: SimklOutboxEntry): Boolean {
    val account = accountMutable.value
    if (!account.isLoggedIn) return false
    if (entry.sessionEpoch != currentSessionEpoch()) return false
    return entry.accountId == null || account.accountId == null || entry.accountId == account.accountId
  }

  /** Richiesta autenticata dell'endpoint dello scrobble, con autorecupero del token. */
  private suspend fun postScrobble(
    event: SimklScrobbleEvent,
    media: SimklMediaPayload,
    progressPercent: Double
  ): Response<Map<String, Any>> = withAuthorizedToken { token ->
    val clientId = requireClientId()
    val authorization = "Bearer $token"
    val payload = media.toPayload(progressPercent)
    when (event) {
      SimklScrobbleEvent.START ->
        api.startScrobble(clientId, SIMKL_MIN_PROGRESS, authorization, payload)
      SimklScrobbleEvent.PAUSE ->
        api.pauseScrobble(clientId, SIMKL_MIN_PROGRESS, authorization, payload)
      SimklScrobbleEvent.STOP ->
        api.stopScrobble(clientId, SIMKL_MIN_PROGRESS, authorization, payload)
    }
  }

  private fun recordScrobbleReport(report: SimklScrobbleReport) {
    synchronized(scrobbleReports) {
      scrobbleReports.add(report)
      while (scrobbleReports.size > SCROBBLE_REPORT_HISTORY) scrobbleReports.removeAt(0)
    }
  }

  /** Esiti delle scritture, dal più vecchio al più recente (diagnosi e test). */
  internal fun scrobbleDiagnostics(): List<SimklScrobbleReport> =
    synchronized(scrobbleReports) { scrobbleReports.toList() }

  internal fun clearScrobbleDiagnostics() {
    synchronized(scrobbleReports) { scrobbleReports.clear() }
  }

  /** Richieste accodate non ancora concluse: i test attendono che scenda a zero. */
  internal fun pendingScrobbleCount(): Int = scrobblePending.get()

  private fun percentText(percent: Double): String = String.format(java.util.Locale.US, "%.1f", percent)

  /**
   * Invia «visto» a SIMKL (`POST /sync/history`).
   *
   * SOLO i film viaggiano senza coordinate episodio: per una serie SIMKL espanderebbe la
   * marcatura a TUTTI gli episodi (comportamento documentato), quindi le marcature a
   * livello di serie vengono rifiutate — i singoli episodi arrivano già a SIMKL come
   * scrobble del player (`stop` a fine visione).
   *
   * Non lancia: la marcatura LOCALE è già avvenuta quando qui si arriva, e un guasto
   * SIMKL non deve farla regredire. Restituisce `false` con log su HTTP non riuscito o
   * su `not_found` (un titolo che il resolver SIMKL non riconosce non è un salvataggio).
   *
   * @return true se SIMKL ha confermato la cronologia.
   */
  suspend fun markWatched(media: SimklMediaPayload): Boolean = withContext(Dispatchers.IO) {
    if (!accountMutable.value.isLoggedIn) return@withContext false
    if (!media.hasIdentifiers) return@withContext false
    if (media.episode == null && media.kind == SimklMediaKind.SERIES) {
      Log.w(TAG, "Marcatura «intera serie» non inviata a SIMKL per ${media.title}: espanderebbe tutti gli episodi.")
      return@withContext false
    }
    val item = media.toHistoryItem()
    val payload = if (media.episode == null) mapOf("movies" to listOf(item)) else mapOf("shows" to listOf(item))
    try {
      val response = withAuthorizedToken { token ->
        api.addHistory(requireClientId(), "Bearer $token", payload)
      }
      if (!response.isSuccessful) {
        Log.w(TAG, "Salvataggio cronologia SIMKL fallito per ${media.scrobbleLabel()} (${response.code()}).")
        return@withContext false
      }
      // Non basta il 2xx: la risposta documenta `not_found` per gli item che il resolver
      // non ha riconosciuto. Un titolo "non trovato" non è un salvataggio riuscito.
      val notFound = (response.body()?.get("not_found") as? Map<*, *>)
      val notFoundCount = listOf("movies", "shows", "episodes").sumOf { key ->
        (notFound?.get(key) as? List<*>)?.size ?: 0
      }
      if (notFoundCount > 0) {
        Log.w(TAG, "SIMKL non ha riconosciuto $notFoundCount elemento/i per ${media.scrobbleLabel()}.")
        return@withContext false
      }
      invalidateWatchedCache()
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Salvataggio cronologia SIMKL fallito per ${media.scrobbleLabel()}: ${e.message}")
      false
    }
  }

  /**
   * Invia «non visto» a SIMKL (`POST /sync/history/remove`): la stessa forma del body di
   * [markWatched] ma SENZA `watched_at`, così il server rimuove la cronologia invece di
   * fissarla a un momento nuovo. Come [markWatched] non lancia e vale solo per i film.
   *
   * @return true se SIMKL ha confermato la rimozione.
   */
  suspend fun unmarkWatched(media: SimklMediaPayload): Boolean = withContext(Dispatchers.IO) {
    if (!accountMutable.value.isLoggedIn) return@withContext false
    if (!media.hasIdentifiers) return@withContext false
    if (media.episode != null) {
      Log.w(TAG, "Rimozione cronologia SIMKL non supportata per un singolo episodio (${media.scrobbleLabel()}).")
      return@withContext false
    }
    val item = media.toHistoryItem()
    val payload = mapOf("movies" to listOf(item))
    try {
      val response = withAuthorizedToken { token ->
        api.removeHistory(requireClientId(), "Bearer $token", payload)
      }
      if (!response.isSuccessful) {
        Log.w(TAG, "Rimozione cronologia SIMKL fallita per ${media.scrobbleLabel()} (${response.code()}).")
        return@withContext false
      }
      invalidateWatchedCache()
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Rimozione cronologia SIMKL fallita per ${media.scrobbleLabel()}: ${e.message}")
      false
    }
  }

  /**
   * Chiude sul server le sessioni di pausa SIMKL del titolo (ogni episodio ha la sua
   * sessione): è ciò che rende PERSISTENTE la rimozione di una card da «Continua a
   * guardare» — senza questa chiamata la sessione resta aperta lato SIMKL e la card
   * rientra alla sincronizzazione successiva. Il match usa gli stessi identificativi
   * della sincronizzazione (TMDB, poi IMDb, poi titolo+anno normalizzato).
   *
   * Best-effort: la rimozione LOCALE è già avvenuta quando qui si arriva; un guasto SIMKL
   * non la invalida. Se nessuna sessione corrisponde (es. è solo un `next_to_watch`),
   * non c'è nulla da chiudere e la rimozione locale è già definitiva perché il flag
   * locale protegge la card.
   *
   * @return numero di sessioni effettivamente chiuse sul server.
   */
  suspend fun deletePlaybackSessionsFor(
    title: String,
    year: Int?,
    tmdbId: Int?,
    imdbId: String?
  ): Int = withContext(Dispatchers.IO) {
    if (!accountMutable.value.isLoggedIn) return@withContext 0
    val sessions = try {
      fetchPlaybackSessions()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Rimozione CW: lettura sessioni SIMKL fallita: ${e.message}")
      return@withContext 0
    }
    val normalizedTitle = MediaIdentity.normalizeTitle(title)
    var deleted = 0
    for (session in sessions) {
      val matches = when {
        tmdbId != null && tmdbId > 0 && session.tmdbId == tmdbId -> true
        !imdbId.isNullOrBlank() && session.imdbId?.equals(imdbId, ignoreCase = true) == true -> true
        else -> {
          val sessionTitle = MediaIdentity.normalizeTitle(session.title)
          val yearMatches = session.year == null || year == null || session.year == year
          sessionTitle.isNotEmpty() && sessionTitle == normalizedTitle && yearMatches
        }
      }
      val playbackId = session.playbackId ?: continue
      if (!matches) continue
      try {
        withAuthorizedToken { token ->
          api.deletePlaybackSession(playbackId, requireClientId(), "Bearer $token")
        }
        deleted++
        Log.i(TAG, "Sessione SIMKL chiusa (#$playbackId) per ${session.title}.")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Chiusura sessione SIMKL #$playbackId fallita: ${e.message}")
      }
    }
    deleted
  }

  // ── LETTURE PER LA SINCRONIZZAZIONE ────────────────────────────────────────

  suspend fun fetchActivities(): SimklActivitiesSnapshot = withContext(Dispatchers.IO) {
    val response = withAuthorizedToken { token ->
      api.getActivities(requireClientId(), "Bearer $token")
    }
    // Un corpo vuoto su 200 è una risposta VALIDA (nessun bucket cambiato), non un errore.
    SimklActivitiesSnapshot.fromResponse(response.requireBody("Lettura attività SIMKL"))
  }

  /**
   * Punti di pausa salvati (`GET /sync/playback`): la fonte di "Continua a guardare".
   * `hide_watched=true` (default) esclude solo i playback superati da una marcatura vista.
   * Un array vuoto è una risposta valida: nessun punto di ripresa salvato.
   */
  suspend fun fetchPlaybackSessions(): List<SimklPlaybackEntry> = withContext(Dispatchers.IO) {
    val response = withAuthorizedToken { token ->
      api.getPlaybackSessions(requireClientId(), "Bearer $token")
    }
    if (!response.isSuccessful) {
      throw SimklAuthException("Lettura punti di ripresa SIMKL non riuscita (${response.code()}).")
    }
    SimklPlaybackParser.parse(response.body())
  }

  /**
   * Voci di playback rimandate dalla sincronizzazione precedente: la sessione di pausa con
   * il numero di tentativi già fatti. Si leggono dall'archivio (sono poche e cambiano una
   * volta per sincronizzazione) e non da una copia in memoria.
   */
  suspend fun loadPendingPlaybacks(): List<SimklPendingPlayback> = withContext(Dispatchers.IO) {
    val store = appContext?.simklDataStore ?: return@withContext emptyList()
    SimklPendingPlaybackStore.decode(store.data.first()[KEY_PENDING_PLAYBACKS])
  }

  /** Riscrive l'elenco delle voci rimandate (vuoto = chiave rimossa, niente residui). */
  suspend fun savePendingPlaybacks(pending: List<SimklPendingPlayback>) {
    val encoded = if (pending.isEmpty()) "" else SimklPendingPlaybackStore.encode(pending)
    appContext?.simklDataStore?.edit { prefs ->
      if (encoded.isEmpty()) prefs.remove(KEY_PENDING_PLAYBACKS) else prefs[KEY_PENDING_PLAYBACKS] = encoded
    }
  }

  /**
   * Watchlist (`GET /sync/all-items/{type}/{status}` con `status=plantowatch`): i titoli che
   * l'utente vuole guardare, distinti sia dalla cronologia sia dai punti di pausa.
   *
   * Un tipo che fallisce manda in errore l'intera lettura: dichiarare riuscita una
   * sincronizzazione in cui una parte della watchlist non è arrivata sarebbe peggio di un
   * errore visibile. Nulla viene cancellato in locale: l'importazione è additiva.
   */
  suspend fun fetchWatchlist(): List<SimklWatchlistEntry> = withContext(Dispatchers.IO) {
    val entries = mutableListOf<SimklWatchlistEntry>()
    for (type in listOf(SIMKL_TYPE_MOVIES, SIMKL_TYPE_SHOWS, SIMKL_TYPE_ANIME)) {
      val response = withAuthorizedToken { token ->
        api.getWatchlist(type, SIMKL_STATUS_PLAN_TO_WATCH, requireClientId(), "Bearer $token")
      }
      if (!response.isSuccessful) {
        throw SimklAuthException(
          "Lettura watchlist SIMKL non riuscita per '$type' (${response.code()})."
        )
      }
      // `{}` è la risposta documentata di una watchlist vuota.
      entries += SimklWatchlistParser.planToWatch(response.body())
    }
    entries
  }

  /**
   * Libreria/history completa: episodi visti per serie e stagione (badge "Visto su SIMKL"),
   * serie in visione con `next_to_watch` e titoli `completed`.
   *
   * Le due letture (serie e anime) sono entrambe necessarie: un anime può vivere solo nel
   * bucket `anime` e senza questa chiamata i suoi episodi non avrebbero né badge né punto
   * di ripresa. Gli anime sono letti con `extended=full_anime_seasons`, che porta la
   * coordinata `tvdb.season`/`tvdb.episode` per mappare `E2` sulla stagione locale.
   *
   * Il fallimento di una delle due letture propaga: dichiarare completa una libreria a cui
   * manca metà dei dati sarebbe peggio di un errore visibile.
   */
  suspend fun fetchWatchedLibrary(): SimklWatchedLibraryData = withContext(Dispatchers.IO) {
    val showsResponse = withAuthorizedToken { token ->
      api.getWatchedShows(requireClientId(), "Bearer $token")
    }
    val shows = SimklWatchedLibrary.parse(
      showsResponse.requireBody("Lettura cronologia SIMKL")
    )
    val animeResponse = withAuthorizedToken { token ->
      api.getWatchedAnime(requireClientId(), "Bearer $token")
    }
    val anime = SimklWatchedLibrary.parse(
      animeResponse.requireBody("Lettura cronologia anime SIMKL")
    )
    val merged = SimklWatchedLibraryData(
      watchedEpisodesByShow = mergeWatchedEpisodes(shows.watchedEpisodesByShow, anime.watchedEpisodesByShow),
      watchingResumes = shows.watchingResumes + anime.watchingResumes,
      watchingTmdbIds = shows.watchingTmdbIds + anime.watchingTmdbIds,
      completedTitles = shows.completedTitles + anime.completedTitles
    )
    watchedLibrary.set(merged.watchedEpisodesByShow)
    invalidateWatchedCache()
    merged
  }

  /**
   * Completamenti dei FILM verificati direttamente su SIMKL (`GET /sync/all-items/movies`,
   * tutti gli stati). Le serie/anime arrivano dalla libreria già letta.
   */
  suspend fun fetchCompletedMovies(): List<SimklCompletedTitle> = withContext(Dispatchers.IO) {
    val response = withAuthorizedToken { token ->
      api.getWatchedMovies(requireClientId(), "Bearer $token")
    }
    SimklWatchedLibrary.parseCompletedTitles(
      response.requireBody("Lettura libreria film SIMKL")
    ).filter { it.kind == SimklCompletedKind.MOVIE }
  }

  /** Unione per titolo/stagione/episodio delle mappe serie+anime (nessun dato perso). */
  internal fun mergeWatchedEpisodes(
    first: Map<Int, Map<Int, Set<Int>>>,
    second: Map<Int, Map<Int, Set<Int>>>
  ): Map<Int, Map<Int, Set<Int>>> {
    if (first.isEmpty()) return second
    if (second.isEmpty()) return first
    val merged = LinkedHashMap<Int, MutableMap<Int, MutableSet<Int>>>()
    for (source in listOf(first, second)) {
      for ((tmdbId, seasons) in source) {
        val target = merged.getOrPut(tmdbId) { LinkedHashMap() }
        for ((season, episodes) in seasons) {
          target.getOrPut(season) { LinkedHashSet() }.addAll(episodes)
        }
      }
    }
    return merged
  }

  /**
   * Corpo di una risposta di lettura: errore HTTP esplicito se la richiesta non è riuscita,
   * `{}` se il server ha risposto 200 con un corpo vuoto (risposta valida, non un guasto).
   */
  private fun retrofit2.Response<Map<String, Any>>.requireBody(what: String): Map<String, Any> {
    if (!isSuccessful) throw SimklAuthException("$what non riuscita (${code()}).")
    return body() ?: emptyMap()
  }

  fun loadActivitiesSnapshot(): SimklActivitiesSnapshot? =
    activitiesSnapshotCache ?: run {
      val loaded = SimklActivitiesSnapshot.fromStorageString(activitiesSnapshotRaw)
      if (loaded != null) activitiesSnapshotCache = loaded
      loaded
    }

  suspend fun saveActivitiesSnapshot(snapshot: SimklActivitiesSnapshot) {
    activitiesSnapshotCache = snapshot
    activitiesSnapshotRaw = snapshot.toStorageString()
    appContext?.simklDataStore?.edit { prefs -> prefs[KEY_ACTIVITIES_SNAPSHOT] = snapshot.toStorageString() }
  }

  /** Data dell'ultima sincronizzazione riuscita (epoch millis), `null` se mai eseguita. */
  fun lastSyncAtEpochMs(): Long? = lastSyncRaw

  suspend fun saveLastSync(result: SimklSyncResult) {
    lastSyncRaw = result.finishedAtEpochMs
    lastSyncMutable.value = result
    appContext?.simklDataStore?.edit { prefs ->
      prefs[KEY_LAST_SYNC_AT] = result.finishedAtEpochMs.toString()
    }
  }

  fun setSyncing(inProgress: Boolean) {
    syncingMutable.value = inProgress
  }

  // ── BADGE "VISTO" NEL DETTAGLIO ────────────────────────────────────────────

  suspend fun getWatchedEpisodes(seriesTmdbId: Int, season: Int): Set<Int> = withContext(Dispatchers.IO) {
    if (!accountMutable.value.isLoggedIn) return@withContext emptySet()
    // 1) Libreria scaricata dalla sincronizzazione: nessuna rete.
    watchedLibrary.get()[seriesTmdbId]?.get(season)?.let { return@withContext it }
    val key = "${accountMutable.value.accountId}:$seriesTmdbId:$season"
    watchedEpisodesCache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
      return@withContext it.episodes
    }
    val response = withAuthorizedToken { token ->
      api.getWatchedShows(requireClientId(), "Bearer $token")
    }
    if (!response.isSuccessful) return@withContext emptySet()
    val episodes = parseWatchedEpisodes(
      response.body() ?: return@withContext emptySet(),
      seriesTmdbId,
      season
    )
    watchedEpisodesCache[key] = CacheEntry(System.currentTimeMillis() + WATCHED_CACHE_TTL_MS, episodes)
    episodes
  }

  internal fun parseWatchedEpisodes(body: Map<String, Any>, tmdbId: Int, season: Int): Set<Int> =
    SimklWatchedLibrary.parseWatchedEpisodes(body, tmdbId, season)

  private fun invalidateWatchedCache() {
    watchedEpisodesCache.clear()
    watchedRevisionMutable.value += 1L
  }

  // ── Stato non-Flow (snapshot) ──────────────────────────────────────────────
  @Volatile private var activitiesSnapshotCache: SimklActivitiesSnapshot? = null
  @Volatile private var activitiesSnapshotRaw: String? = null
  @Volatile private var lastSyncRaw: Long? = null

  /** Idrata snapshot e data ultima sincronizzazione (chiamata da [init] tramite DataStore). */
  internal suspend fun hydrateSyncMetadata(preferences: androidx.datastore.preferences.core.Preferences) {
    activitiesSnapshotRaw = preferences[KEY_ACTIVITIES_SNAPSHOT]
    activitiesSnapshotCache = null
    lastSyncRaw = preferences[KEY_LAST_SYNC_AT]?.toLongOrNull()
  }

  /**
   * Access token come sta nel **DataStore** (su disco), non lo stato in memoria del
   * repository. È la prova di persistenza del login: se il token non è qui, il
   * collegamento andrebbe perso al riavvio dell'app.
   */
  internal suspend fun persistedAccessToken(): String? =
    appContext?.simklDataStore?.data?.first()?.get(KEY_ACCESS_TOKEN)

  private const val TAG = "SimklRepository"
}

/**
 * Tipo di contenuto del payload: decide il bucket SIMKL per le scritture senza
 * coordinate episodio. Una SERIES senza episodio NON viene mai inviata a
 * `/sync/history` (SIMKL la espanderebbe a tutti gli episodi).
 */
enum class SimklMediaKind { MOVIE, SERIES }

data class SimklMediaPayload(
  val title: String,
  val year: Int?,
  val tmdbId: Int?,
  val imdbId: String?,
  val episode: SimklEpisodePayload? = null,
  val kind: SimklMediaKind = SimklMediaKind.MOVIE,
  /** Id locale della scheda: serve a marcare un completamento verificato dalla risposta. */
  val mediaId: String? = null
) {
  /**
   * true se c'è almeno un identificativo con cui SIMKL possa agganciare il titolo: senza di
   * esso il payload avrebbe `ids: {}` e il server non saprebbe a cosa riferire il progresso
   * (la richiesta verrebbe spedita per nulla, come se fosse riuscita).
   */
  val hasIdentifiers: Boolean
    get() = (tmdbId != null && tmdbId > 0) || !imdbId.isNullOrBlank()

  /** Come compare nei log: titolo, con `S…E…` quando è un episodio. */
  fun scrobbleLabel(): String = episode?.let { "$title S${it.season}E${it.number}" } ?: title

  fun toPayload(progress: Double): Map<String, Any> {
    val ids = buildMap<String, String> {
      tmdbId?.takeIf { it > 0 }?.let { put("tmdb", it.toString()) }
      imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
    }
    val item = buildMap<String, Any> {
      put("title", title)
      year?.takeIf { it > 0 }?.let { put("year", it) }
      put("ids", ids)
    }
    return buildMap {
      put("progress", progress.coerceIn(0.0, 100.0))
      if (episode == null) put("movie", item) else {
        put("show", item)
        // Schema documentato: `episode.season` + `episode.number`. La vecchia chiave
        // `episode.episode` non esiste e faceva scartare/agganciare male il payload.
        put("episode", mapOf("season" to episode.season, "number" to episode.number))
      }
    }
  }

  /**
   * Item per `POST /sync/history`. La forma è DIVERSA dallo scrobble: qui `shows[]` e
   * `movies[]` contengono l'item con `ids` al primo livello (non un wrapper `show`), e
   * l'episodio usa `seasons[].episodes[].number`.
   */
  fun toHistoryItem(): Map<String, Any> {
    val payload = toPayload(100.0)
    return if (episode == null) {
      payload.getValue("movie") as Map<String, Any>
    } else {
      buildMap {
        putAll(payload.getValue("show") as Map<String, Any>)
        put(
          "seasons",
          listOf(
            mapOf(
              "number" to episode.season,
              "episodes" to listOf(mapOf("number" to episode.number))
            )
          )
        )
        // App TMDB-primary: Simkl mappa la coordinata TVDB/TMDB sull'eventuale record
        // anime corretto. Per le serie non-anime il flag è un no-op documentato.
        put("use_tvdb_anime_seasons", true)
      }
    }
  }
}

data class SimklEpisodePayload(val season: Int, val number: Int)
