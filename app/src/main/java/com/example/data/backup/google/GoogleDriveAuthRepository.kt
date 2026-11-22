package com.example.data.backup.google

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.data.prefs.ApiKeyCipher
import com.example.data.prefs.AndroidKeystoreApiKeyCipher
import com.example.data.profiles.ProfileStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "GoogleDriveAuth"

/**
 * Account Google collegato per i backup su Drive personale.
 *
 * @param accountKey identificatore STABILE dell'account (Google `sub`, con fallback
 *        all'email): chiave di isolamento della cartella Drive. Se l'utente collega
 *        un account diverso, la cartella memorizzata del precedente non viene mai
 *        riutilizzata automaticamente.
 * @param email indirizzo mostrato in UI ("Account mostrato correttamente").
 */
data class GoogleDriveAccount(
  val accountKey: String,
  val email: String?
)

/**
 * Stato del collegamento Google (per la UI): non collegato / collegamento in corso /
 * collegato. È una COPIA osservabile del repository, senza segreti: il refresh token
 * non vi compare mai.
 */
sealed interface GoogleDriveAuthState {
  data object NotConnected : GoogleDriveAuthState
  data class Connecting(val userCode: String, val verificationUrl: String) : GoogleDriveAuthState
  data class Connected(val email: String?) : GoogleDriveAuthState
}

/**
 * DataStore per profilo del collegamento Google Drive.
 */
private fun googleDriveDataStoreFor(context: Context, profileId: String): DataStore<Preferences> =
  PreferenceDataStoreFactory.create(
    produceFile = {
      File(
        File(context.applicationContext.filesDir, "datastore"),
        ProfileStorage.dataStoreName(GOOGLE_DRIVE_STORE_BASE, profileId) + ".preferences_pb"
      )
    }
  )

private const val GOOGLE_DRIVE_STORE_BASE = "google_drive_preferences"

/**
 * Ciclo di vita dell'autenticazione Google per i backup personali su Drive.
 *
 * Flusso scelto: **OAuth 2.0 Device Authorization Grant (RFC 8628)**, il flusso
 * Google supportato per "TVs and Limited Input devices". Nessun browser sul TV e
 * nessun Google Play Services sono necessari (entrambi assenti su Fire TV).
 *
 * Sicurezza dei token:
 * - il REFRESH token persiste cifrato ([ApiKeyCipher], chiave Keystore non esportabile);
 * - l'ACCESS token vive SOLO in memoria e viene rinnovato su richiesta;
 * - nessun token viene loggato;
 * - la disconnessione revoca il token su Google e cancella ogni dato locale.
 *
 * Cartella Drive: il folderId persistito è valido SOLO per l'account con cui è stato
 * creato ([GoogleDriveAccount.accountKey]). Un cambio account invalida il riferimento:
 * la cartella del precedente non viene mai usata per il nuovo.
 */
object GoogleDriveAuthRepository {

  private val KEY_REFRESH_TOKEN_ENC = stringPreferencesKey("refresh_token_enc")
  private val KEY_ACCOUNT_KEY = stringPreferencesKey("account_key")
  private val KEY_ACCOUNT_EMAIL = stringPreferencesKey("account_email")
  private val KEY_FOLDER_ID = stringPreferencesKey("backups_folder_id")
  private val KEY_FOLDER_ACCOUNT = stringPreferencesKey("backups_folder_account_key")
  private val KEY_FOLDER_MISSING = booleanPreferencesKey("backups_folder_missing")
  private val KEY_CONNECTED_AT = longPreferencesKey("connected_at_epoch_ms")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile private var applicationContext: Context? = null
  @Volatile private var cipher: ApiKeyCipher = AndroidKeystoreApiKeyCipher(DEFAULT_CIPHER_ALIAS)
  @Volatile private var profileDataStore: DataStore<Preferences>? = null
  @Volatile private var profileDataStoreProfileId: String? = null

  private val authClientDefault = GoogleDriveAuthClient()

  /**
   * Client Secret del client OAuth "TVs and Limited Input devices": richiesto da
   * Google sugli endpoint token (poll e refresh). Configurato dallo sviluppatore
   * (BuildConfig), mai inventato; per la natura del flusso TV vive nell'APK.
   */
  @Volatile private var clientSecret: String = ""

  /** Seam di test: i test unitari puntano il client a un MockWebServer. */
  @Volatile internal var authClientOverride: GoogleDriveAuthClient? = null
  private fun client(): GoogleDriveAuthClient = authClientOverride ?: authClientDefault

  /** Access token in memoria: mai persistito, mai loggato. */
  private val tokenMutex = Mutex()
  private var accessToken: String? = null
  private var accessTokenExpiresAtMs: Long = 0L
  private var accessTokenAccountKey: String? = null

  private val _authState = MutableStateFlow<GoogleDriveAuthState>(GoogleDriveAuthState.NotConnected)
  /** Stato osservabile del collegamento (senza segreti). */
  val authState: StateFlow<GoogleDriveAuthState> = _authState.asStateFlow()

  /** Job del polling del device flow (annullato al BACK/dialog dismiss). */
  @Volatile private var connectingJob: Job? = null

  private const val DEFAULT_CIPHER_ALIAS = "streamnova_google_drive_token"

  /**
   * Inizializza il repository per il profilo attivo. Segue lo stesso modello di
   * [com.example.data.repository.SimklRepository.init]: chiamato dall'Application.
   */
  fun init(
    context: Context,
    profileId: String = ProfileStorage.PRIMARY_PROFILE_ID,
    keyCipher: ApiKeyCipher? = null,
    oauthClientSecret: String = ""
  ) {
    applicationContext = context.applicationContext
    keyCipher?.let { cipher = it }
    clientSecret = oauthClientSecret.trim()
    switchProfile(profileId)
  }

  /** Riaggancia il DataStore al profilo [profileId] e ricarica lo stato in memoria. */
  fun switchProfile(profileId: String) {
    val context = applicationContext ?: return
    val store = profileDataStore?.takeIf { profileDataStoreProfileId == profileId }
      ?: googleDriveDataStoreFor(context, profileId).also {
        profileDataStore = it
        profileDataStoreProfileId = profileId
      }
    scope.launch {
      val prefs = runCatching { store.data.first() }.getOrElse { emptyPreferences() }
      val refreshToken = prefs[KEY_REFRESH_TOKEN_ENC]?.let { cipher.decrypt(it) }
      val accountKey = prefs[KEY_ACCOUNT_KEY]
      if (refreshToken.isNullOrBlank() || accountKey.isNullOrBlank()) {
        _authState.value = GoogleDriveAuthState.NotConnected
      } else {
        _authState.value = GoogleDriveAuthState.Connected(prefs[KEY_ACCOUNT_EMAIL])
      }
    }
  }

  /** True se esiste un refresh token collegato (account collegato, token vivo o da rinnovare). */
  suspend fun isConnected(): Boolean {
    val store = profileDataStore ?: return false
    val prefs = runCatching { store.data.first() }.getOrElse { emptyPreferences() }
    val refreshToken = prefs[KEY_REFRESH_TOKEN_ENC]?.let { cipher.decrypt(it) }
    return !refreshToken.isNullOrBlank() && !prefs[KEY_ACCOUNT_KEY].isNullOrBlank()
  }

  /** Account corrente (senza segreti) o `null`. */
  suspend fun currentAccount(): GoogleDriveAccount? {
    val store = profileDataStore ?: return null
    val prefs = runCatching { store.data.first() }.getOrElse { emptyPreferences() }
    val accountKey = prefs[KEY_ACCOUNT_KEY] ?: return null
    return GoogleDriveAccount(accountKey = accountKey, email = prefs[KEY_ACCOUNT_EMAIL])
  }

  /**
   * Avvia il collegamento Google: richiede il device_code e mostra a schermo il
   * user_code con l'URL di verifica. Il polling parte con [awaitAuthorization];
   * un eventuale collegamento in corso viene annullato.
   *
   * @return errore con `IllegalStateException("Client ID Google non configurato…")`
   *         se la build non ha il Client ID (l'UI lo espone come "non disponibile").
   */
  suspend fun startDeviceFlow(clientId: String): Result<GoogleDeviceCodeResponse> {
    connectingJob?.cancel()
    // Google richiede il client_secret sui token endpoint per il device flow: senza,
    // il device_code sarebbe inutile (il primo poll fallirebbe). Fail fast con
    // messaggio chiaro, prima di mostrare all'utente un codice che non può usare.
    if (clientSecret.isBlank()) {
      return Result.failure(
        GoogleAuthException(
          GoogleAuthException.Code.NOT_CONFIGURED,
          "Client Secret Google non configurato in questa build " +
            "(GOOGLE_DRIVE_CLIENT_SECRET): impossibile completare il collegamento."
        )
      )
    }
    val grant = client().startDeviceFlow(clientId).fold(
      onSuccess = { it },
      onFailure = { return Result.failure(it) }
    )
    _authState.value = GoogleDriveAuthState.Connecting(
      userCode = grant.userCode,
      verificationUrl = grant.verificationUrlResolved
    )
    return Result.success(grant)
  }

  /**
   * Loop di polling finché l'utente NON ha completato l'autorizzazione su
   * `google.com/device` (o scade/nega). Cancellabile: il chiamante può annullare
   * il job (BACK) e il flusso termina senza modifiche permanenti.
   *
   * Al successo persiste refresh token cifrato + account e azzera il riferimento
   * alla cartella (una nuova sessione di collegamento implica risolvere/creare la
   * cartella per l'account appena collegato).
   *
   * @return l'account collegato.
   */
  suspend fun awaitAuthorization(
    clientId: String,
    grant: GoogleDeviceCodeResponse,
    onUserCodeNeeded: (suspend (GoogleDeviceCodeResponse) -> Unit)? = null
  ): Result<GoogleDriveAccount> {
    val expiresAtMs = System.currentTimeMillis() + grant.expiresInSeconds * 1000L
    var intervalMs = (grant.intervalSeconds * 1000L).coerceAtLeast(2000L)
    var consecutiveTransient = 0
    while (System.currentTimeMillis() < expiresAtMs) {
      delay(intervalMs)
      when (val poll = client().pollTokenOnce(clientId, clientSecret, grant.deviceCode)) {
        is GoogleTokenPoll.Pending -> {}
        is GoogleTokenPoll.SlowDown -> intervalMs += 5000L
        is GoogleTokenPoll.Denied -> {
          _authState.value = GoogleDriveAuthState.NotConnected
          return Result.failure(
            GoogleAuthException(
              GoogleAuthException.Code.DENIED,
              when (poll.reason) {
                "access_denied" -> "Autorizzazione negata dall'account Google"
                "expired_token" -> "Il codice mostrato è scaduto: riavvia il collegamento"
                else -> poll.description ?: "Autorizzazione non completata"
              }
            )
          )
        }
        is GoogleTokenPoll.Success -> {
          val email = GoogleIdTokenDecoder.extractEmail(poll.token.idToken)
            ?: client().fetchEmail(poll.token.accessToken!!).getOrNull()
          val accountKey = GoogleIdTokenDecoder.extractSub(poll.token.idToken)
            ?: email
            ?: return Result.failure(
              GoogleAuthException(GoogleAuthException.Code.TOKEN_REJECTED, "Identità account non determinabile")
            )
          persistAccount(
            refreshToken = poll.token.refreshToken,
            accountKey = accountKey,
            email = email
          )
          invalidateFolderReference(accountKey)
          _authState.value = GoogleDriveAuthState.Connected(email)
          return Result.success(GoogleDriveAccount(accountKey = accountKey, email = email))
        }
        is GoogleTokenPoll.Transient -> {
          consecutiveTransient++
          if (consecutiveTransient >= 20) {
            return Result.failure(
              GoogleAuthException(GoogleAuthException.Code.NOT_CONFIGURED, poll.message)
            )
          }
        }
      }
    }
    _authState.value = GoogleDriveAuthState.NotConnected
    return Result.failure(
      GoogleAuthException(GoogleAuthException.Code.EXPIRED, "Collegamento non completato in tempo")
    )
  }

  /**
   * Access token valido per le operazioni Drive. Rinnovato con il refresh token
   * quando mancano meno di 60 secondi alla scadenza. Se il refresh è rifiutato
   * (token revocato/account cambiato) lo stato torna "non collegato".
   */
  suspend fun getValidAccessToken(clientId: String): Result<String> = tokenMutex.withLock {
    val now = System.currentTimeMillis()
    val currentAccountKey = runCatching { profileDataStore?.data?.first() }.getOrNull()
      ?.let { it[KEY_ACCOUNT_KEY] }
    accessToken?.let { cached ->
      if (accessTokenAccountKey == currentAccountKey && currentAccountKey != null &&
        now < accessTokenExpiresAtMs - 60_000L
      ) {
        return Result.success(cached)
      }
    }
    val store = profileDataStore ?: return Result.failure(
      GoogleAuthException(GoogleAuthException.Code.NOT_CONFIGURED, "Account Google non collegato")
    )
    val prefs = runCatching { store.data.first() }.getOrElse { emptyPreferences() }
    val refreshToken = prefs[KEY_REFRESH_TOKEN_ENC]?.let { cipher.decrypt(it) }
    if (refreshToken.isNullOrBlank()) {
      return Result.failure(
        GoogleAuthException(GoogleAuthException.Code.NOT_CONFIGURED, "Account Google non collegato")
      )
    }
    val refreshed = client().refreshAccessToken(clientId, clientSecret, refreshToken).fold(
      onSuccess = { it },
      onFailure = { e ->
        if (e is GoogleAuthException && e.code == GoogleAuthException.Code.TOKEN_REJECTED) {
          // Refresh token revocato o invalido: il collegamento non è più valido.
          Log.w(TAG, "Refresh token rifiutato: collegamento invalidato")
          clearStoredAccount()
          _authState.value = GoogleDriveAuthState.NotConnected
        }
        return Result.failure(e)
      }
    )
    accessToken = refreshed.accessToken
    accessTokenExpiresAtMs = now + (refreshed.expiresInSeconds * 1000L).coerceAtLeast(60_000L)
    accessTokenAccountKey = prefs[KEY_ACCOUNT_KEY]
    Result.success(refreshed.accessToken!!)
  }

  /** folderId della cartella `StreamNova Backups`, SOLO se appartiene all'account corrente. */
  suspend fun cachedBackupsFolderId(): String? {
    val store = profileDataStore ?: return null
    val prefs = runCatching { store.data.first() }.getOrElse { emptyPreferences() }
    val folderId = prefs[KEY_FOLDER_ID] ?: return null
    val accountKey = prefs[KEY_ACCOUNT_KEY] ?: return null
    if (prefs[KEY_FOLDER_ACCOUNT] != accountKey) return null
    if (prefs[KEY_FOLDER_MISSING] == true) return null
    return folderId
  }

  /** Memorizza il folderId validato per l'account corrente. */
  suspend fun rememberBackupsFolderId(folderId: String) {
    val store = profileDataStore ?: return
    val prefs = runCatching { store.data.first() }.getOrElse { emptyPreferences() }
    val accountKey = prefs[KEY_ACCOUNT_KEY] ?: return
    store.edit {
      it[KEY_FOLDER_ID] = folderId
      it[KEY_FOLDER_ACCOUNT] = accountKey
      it[KEY_FOLDER_MISSING] = false
    }
  }

  /** Ricorda che la cartella memorizzata non esiste più (utente l'ha eliminata dal Drive). */
  suspend fun markBackupsFolderMissing() {
    val store = profileDataStore ?: return
    store.edit { it[KEY_FOLDER_MISSING] = true }
  }

  private suspend fun invalidateFolderReference(newAccountKey: String) {
    val store = profileDataStore ?: return
    store.edit { it[KEY_FOLDER_ACCOUNT] = newAccountKey }
  }

  /** Percorso di persistenza dell'account (stesso del device flow): interno per i test. */
  internal suspend fun persistAccount(refreshToken: String?, accountKey: String, email: String?) {
    val store = profileDataStore ?: return
    store.edit {
      if (refreshToken.isNullOrBlank()) {
        // Il device flow di Google emette SEMPRE un refresh token per client "TV and
        // Limited Input": l'assenza indicherebbe un client sbagliato. Un account senza
        // refresh token non è collegabile in modo duraturo.
        it.remove(KEY_REFRESH_TOKEN_ENC)
        it.remove(KEY_ACCOUNT_KEY)
        it.remove(KEY_ACCOUNT_EMAIL)
    } else {
      it[KEY_REFRESH_TOKEN_ENC] = cipher.encrypt(refreshToken)
      it[KEY_ACCOUNT_KEY] = accountKey
      it[KEY_ACCOUNT_EMAIL] = email ?: ""
      it[KEY_CONNECTED_AT] = System.currentTimeMillis()
    }
    }
    // Access token di sessione nati prima del collegamento: invalidi per il nuovo account.
    tokenMutex.withLock {
      accessToken = null
      accessTokenExpiresAtMs = 0L
      accessTokenAccountKey = null
    }
    if (refreshToken.isNullOrBlank()) {
      _authState.value = GoogleDriveAuthState.NotConnected
    } else {
      _authState.value = GoogleDriveAuthState.Connected(email)
    }
  }

  /**
   * Disconnette l'account: revoca il token su Google (best-effort) e cancella
   * OGNI dato locale (refresh token cifrato, account, riferimento cartella).
   */
  suspend fun disconnect(): Result<Unit> {
    connectingJob?.cancel()
    val store = profileDataStore ?: return Result.failure(IllegalStateException("Repository non inizializzato"))
    val prefs = runCatching { store.data.first() }.getOrElse { emptyPreferences() }
    val refreshToken = prefs[KEY_REFRESH_TOKEN_ENC]?.let { cipher.decrypt(it) }
    clearStoredAccount()
    _authState.value = GoogleDriveAuthState.NotConnected
    if (!refreshToken.isNullOrBlank()) {
      return client().revokeToken(refreshToken)
    }
    return Result.success(Unit)
  }

  private suspend fun clearStoredAccount() {
    val store = profileDataStore ?: return
    store.edit {
      it.remove(KEY_REFRESH_TOKEN_ENC)
      it.remove(KEY_ACCOUNT_KEY)
      it.remove(KEY_ACCOUNT_EMAIL)
      it.remove(KEY_FOLDER_ID)
      it.remove(KEY_FOLDER_ACCOUNT)
      it.remove(KEY_FOLDER_MISSING)
      it.remove(KEY_CONNECTED_AT)
    }
    tokenMutex.withLock {
      accessToken = null
      accessTokenExpiresAtMs = 0L
      accessTokenAccountKey = null
    }
  }

  /** Attende che il loop di collegamento in corso finisca (uso dei soli test). */
  suspend fun awaitConnectingJobSettled() {
    withTimeoutOrNull(5000L) { connectingJob?.join() }
  }

  internal fun setConnectingJob(job: Job?) {
    connectingJob = job
  }

  /**
   * Annulla un collegamento in corso (BACK sul dialog di collegamento): il job del
   * chiamante viene cancellato e lo stato torna "non collegato", senza dati parziali.
   */
  fun cancelConnecting() {
    connectingJob?.cancel()
    connectingJob = null
    if (_authState.value is GoogleDriveAuthState.Connecting) {
      _authState.value = GoogleDriveAuthState.NotConnected
    }
  }
}
