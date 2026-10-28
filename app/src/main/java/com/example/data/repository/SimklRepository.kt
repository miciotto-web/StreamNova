package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
   * Il refresh V2 è proattivo: un access token dura 7 giorni, quindi si rinnova quando
   * manca meno di un giorno alla scadenza (evita di scoprire la scadenza durante lo
   * scrobble, mentre il refresh token resta valido 180 giorni con finestra scorrevole).
   */
  private const val REFRESH_WINDOW_MS = 24L * 60L * 60L * 1000L
  private const val WATCHED_CACHE_TTL_MS = 5L * 60L * 1000L

  private val accountMutable = MutableStateFlow(SimklAccountState())
  val accountState: StateFlow<SimklAccountState> = accountMutable.asStateFlow()

  private val lastSyncMutable = MutableStateFlow<SimklSyncResult?>(null)
  val lastSyncResult: StateFlow<SimklSyncResult?> = lastSyncMutable.asStateFlow()

  private val syncingMutable = MutableStateFlow(false)
  val isSyncing: StateFlow<Boolean> = syncingMutable.asStateFlow()

  private var appContext: Context? = null
  private var api: SimklApi = SimklApiClient.api
  private var authClient: SimklAuthClient = SimklAuthClient(SimklApiClient.api)
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
  fun init(context: Context, simklApi: SimklApi = SimklApiClient.api) {
    val applicationContext = context.applicationContext
    // Rientrare con lo stesso contesto E la stessa implementazione è un no-op; se l'API
    // iniettata cambia (test, ambiente alternativo) l'oggetto si riconfigura.
    if (appContext === applicationContext && api === simklApi) return
    appContext = applicationContext
    api = simklApi
    authClient = SimklAuthClient(simklApi)
    scope.launch {
      val store = applicationContext.simklDataStore
      val first = store.data.first()
      loginFlowCache.set(parseFlow(first[KEY_AUTH_FLOW]))
      hydrateSyncMetadata(first)
      accountMutable.value = first.toAccountState()
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
   * metadati di sincronizzazione. I progressi di visione locali non vengono toccati.
   */
  suspend fun signOut() {
    val current = accountMutable.value
    if (current.flow == SimklAuthFlow.V2_DEVICE) {
      val token = current.refreshToken?.takeIf { it.isNotBlank() }
        ?: current.accessToken?.takeIf { it.isNotBlank() }
      if (token != null && hasClientId()) {
        runCatching { authClient.revoke(getClientId(), token) }
      }
    }
    appContext?.simklDataStore?.edit { prefs -> prefs.clear() }
    accountMutable.value = SimklAccountState()
    lastSyncMutable.value = null
    loginFlowCache.set(null)
    invalidateWatchedCache()
    watchedLibrary.set(emptyMap())
  }

  /**
   * Segna la sessione come scaduta (refresh rifiutato): i token restano su disco — nessuna
   * cancellazione per un errore — ma l'app sa che serve una nuova autorizzazione.
   */
  private suspend fun markSessionExpired(message: String) {
    val current = accountMutable.value
    if (current.sessionExpired) return
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
      try {
        val bundle = authClient.refresh(requireClientId(), currentRefresh)
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

  private class ScrobbleRequest(
    val event: SimklScrobbleEvent,
    val media: SimklMediaPayload,
    val progressPercent: Double
  )

  /**
   * Coda delle scritture verso SIMKL.
   *
   * Gli eventi nascono da callback del player e devono **sopravvivere alla schermata che li
   * ha prodotti**: la chiusura del player cancella lo scope della composizione
   * (`rememberCoroutineScope`), quindi una richiesta lanciata lì viene annullata a metà — ed
   * è esattamente il caso del `stop` di uscita, cioè il punto di ripresa da salvare.
   *
   * La coda vive su [scope] (durata di processo), ha **un solo consumatore** (l'ordine
   * start → pause → stop dello stesso titolo resta quello prodotto) e ritenta una volta le
   * scritture che possono riuscire più tardi (5xx, rete). Un 4xx non viene ritentato: la
   * richiesta non diventerà valida da sola.
   */
  private val scrobbleQueue = Channel<ScrobbleRequest>(capacity = SCROBBLE_QUEUE_CAPACITY)
  private val scrobbleWorkerStarted = AtomicBoolean(false)
  private val scrobblePending = AtomicInteger(0)
  private val scrobbleReports = java.util.Collections.synchronizedList(mutableListOf<SimklScrobbleReport>())

  private const val SCROBBLE_QUEUE_CAPACITY = 64
  private const val SCROBBLE_MAX_ATTEMPTS = 2
  private const val SCROBBLE_RETRY_DELAY_MS = 750L
  private const val SCROBBLE_REPORT_HISTORY = 20

  /**
   * Accoda una scrittura scrobble e ritorna subito: non sospende, non blocca e non lancia.
   *
   * @param progressPercent percentuale corrente, **`null` quando la durata non è nota**.
   *        Un payload senza identificativi o con una percentuale non calcolabile viene
   *        rifiutato e registrato in [scrobbleDiagnostics], invece di essere spedito.
   * @return `true` se l'evento è entrato in coda (non se ha già avuto successo).
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
    ensureScrobbleWorkerStarted()
    scrobblePending.incrementAndGet()
    val accepted = scrobbleQueue.trySend(
      ScrobbleRequest(event, media, progressPercent!!)
    ).isSuccess
    if (!accepted) {
      scrobblePending.decrementAndGet()
      recordScrobbleReport(
        SimklScrobbleReport(event, SimklScrobbleOutcome.QUEUE_FULL, label, progressPercent)
      )
      Log.w(TAG, "Scrobble ${event.endpoint}: coda piena, evento non accodato per $label.")
    }
    return accepted
  }

  private fun ensureScrobbleWorkerStarted() {
    if (!scrobbleWorkerStarted.compareAndSet(false, true)) return
    scope.launch {
      for (request in scrobbleQueue) {
        try {
          performScrobble(request)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Ultima rete di sicurezza: un guasto inatteso non deve fermare la coda.
          Log.w(TAG, "Scrobble ${request.event.endpoint}: errore inatteso in coda", e)
        } finally {
          scrobblePending.decrementAndGet()
        }
      }
    }
  }

  private suspend fun performScrobble(request: ScrobbleRequest) {
    val label = request.media.scrobbleLabel()
    if (!accountMutable.value.isLoggedIn) {
      recordScrobbleReport(
        SimklScrobbleReport(request.event, SimklScrobbleOutcome.NOT_LOGGED_IN, label, request.progressPercent)
      )
      Log.i(TAG, "Scrobble ${request.event.endpoint}: account SIMKL non collegato, $label ignorato.")
      return
    }
    var attempts = 0
    var lastCode = 0
    var lastError: Throwable? = null
    while (attempts < SCROBBLE_MAX_ATTEMPTS) {
      attempts++
      lastCode = 0
      lastError = null
      try {
        val response = postScrobble(request.event, request.media, request.progressPercent)
        if (response.isSuccessful) {
          if (request.event == SimklScrobbleEvent.STOP && request.progressPercent >= 80.0) {
            invalidateWatchedCache()
          }
          recordScrobbleReport(
            SimklScrobbleReport(
              request.event, SimklScrobbleOutcome.SUCCESS, label,
              request.progressPercent, response.code(), attempts
            )
          )
          Log.i(
            TAG,
            "Scrobble ${request.event.endpoint} riuscito per $label " +
              "(${percentText(request.progressPercent)}%, tentativo $attempts)."
          )
          return
        }
        lastCode = response.code()
        Log.w(
          TAG,
          "Scrobble ${request.event.endpoint} rifiutato per $label: " +
            "HTTP ${response.code()} al tentativo $attempts."
        )
        // 4xx: token senza `media:write`, payload rifiutato, sessione revocata. Riprovare
        // la stessa richiesta non la rende valida.
        if (response.code() < 500) break
      } catch (e: CancellationException) {
        throw e
      } catch (e: SimklClientConfigException) {
        recordScrobbleReport(
          SimklScrobbleReport(
            request.event, SimklScrobbleOutcome.NOT_CONFIGURED, label,
            request.progressPercent, attempts = attempts, detail = e.javaClass.simpleName
          )
        )
        Log.w(
          TAG,
          "Scrobble ${request.event.endpoint} non inviato per $label: " +
            "configurazione del client SIMKL assente (${e.javaClass.simpleName})."
        )
        return
      } catch (e: SimklRefreshException) {
        // Un refresh rifiutato richiede una nuova autorizzazione; un errore di rete no.
        val outcome = if (e.needsReauthorization) SimklScrobbleOutcome.NOT_LOGGED_IN
        else SimklScrobbleOutcome.NETWORK_ERROR
        recordScrobbleReport(
          SimklScrobbleReport(
            request.event, outcome, label,
            request.progressPercent, attempts = attempts, detail = e.javaClass.simpleName
          )
        )
        Log.w(TAG, "Scrobble ${request.event.endpoint} non eseguito per $label: ${e.localizedMessage}")
        return
      } catch (e: SimklAuthException) {
        recordScrobbleReport(
          SimklScrobbleReport(
            request.event, SimklScrobbleOutcome.NOT_LOGGED_IN, label,
            request.progressPercent, attempts = attempts, detail = e.javaClass.simpleName
          )
        )
        Log.w(
          TAG,
          "Scrobble ${request.event.endpoint} non eseguito per $label: " +
            "autorizzazione SIMKL non disponibile (${e.localizedMessage})."
        )
        return
      } catch (e: Exception) {
        lastError = e
        Log.w(
          TAG,
          "Scrobble ${request.event.endpoint} fallito per $label " +
            "(tentativo $attempts): ${e.javaClass.simpleName} ${e.message}"
        )
        // Solo un guasto di trasporto può risolversi riprovando: un payload che Retrofit
        // rifiuta di costruire o una serializzazione impossibile no.
        if (e !is java.io.IOException) break
      }
      if (attempts < SCROBBLE_MAX_ATTEMPTS) delay(SCROBBLE_RETRY_DELAY_MS)
    }
    val lastFailure = lastError
    val outcome = when {
      lastCode > 0 -> SimklScrobbleOutcome.HTTP_ERROR
      lastFailure is java.io.IOException -> SimklScrobbleOutcome.NETWORK_ERROR
      else -> SimklScrobbleOutcome.REQUEST_ERROR
    }
    recordScrobbleReport(
      SimklScrobbleReport(
        request.event, outcome, label, request.progressPercent, lastCode, attempts,
        detail = lastFailure?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
          .orEmpty()
          .take(200)
      )
    )
    Log.w(
      TAG,
      "Scrobble ${request.event.endpoint} NON riuscito per $label dopo $attempts tentativi: " +
        when {
          lastCode > 0 -> "HTTP $lastCode."
          lastFailure is java.io.IOException -> "errore di rete (${lastFailure.javaClass.simpleName})."
          else -> "richiesta non costruibile (${lastFailure?.javaClass?.simpleName}: ${lastFailure?.message})."
        }
    )
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
      SimklScrobbleEvent.START -> api.startScrobble(clientId, authorization, payload)
      SimklScrobbleEvent.PAUSE -> api.pauseScrobble(clientId, authorization, payload)
      SimklScrobbleEvent.STOP -> api.stopScrobble(clientId, authorization, payload)
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

  suspend fun markWatched(media: SimklMediaPayload) = withContext(Dispatchers.IO) {
    if (!accountMutable.value.isLoggedIn) return@withContext
    val item = media.toHistoryItem()
    val payload = if (media.episode == null) mapOf("movies" to listOf(item)) else mapOf("shows" to listOf(item))
    val response = withAuthorizedToken { token ->
      api.addHistory(requireClientId(), "Bearer $token", payload)
    }
    check(response.isSuccessful) { "Salvataggio cronologia SIMKL fallito (${response.code()})." }
    invalidateWatchedCache()
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
   * Libreria/history completa: episodi visti per serie e stagione (badge "Visto su SIMKL")
   * **più le serie in visione con l'ultimo episodio visto** (`status=watching`), che sono
   * l'unica fonte dei progressi episodici quando il titolo non ha una sessione di pausa
   * attiva (`GET /sync/playback` non contiene alcun episodio in quel caso). Una sola
   * chiamata: i due dati arrivano dalla stessa risposta.
   */
  suspend fun fetchWatchedLibrary(): SimklWatchedLibraryData = withContext(Dispatchers.IO) {
    val response = withAuthorizedToken { token ->
      api.getWatchedShows(requireClientId(), "Bearer $token")
    }
    val parsed = SimklWatchedLibrary.parse(
      response.requireBody("Lettura cronologia SIMKL")
    )
    watchedLibrary.set(parsed.watchedEpisodesByShow)
    invalidateWatchedCache()
    parsed
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

data class SimklMediaPayload(
  val title: String,
  val year: Int?,
  val tmdbId: Int?,
  val imdbId: String?,
  val episode: SimklEpisodePayload? = null
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
        put("episode", mapOf("season" to episode.season, "episode" to episode.number))
      }
    }
  }

  fun toHistoryItem(): Map<String, Any> {
    val payload = toPayload(100.0)
    return if (episode == null) payload.getValue("movie") as Map<String, Any>
    else buildMap {
      put("show", payload.getValue("show"))
      put("seasons", listOf(mapOf("number" to episode.season, "episodes" to listOf(mapOf("number" to episode.number)))))
    }
  }
}

data class SimklEpisodePayload(val season: Int, val number: Int)
