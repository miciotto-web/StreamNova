package com.example.data.backup.google

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Payload dell'avvio del Device Authorization Grant (RFC 8628).
 *
 * Google risponde con `verification_url` (formato storico) e/o `verification_uri`
 * (nome RFC 8628): entrambi letti, il primo non-null vince.
 */
@JsonClass(generateAdapter = true)
data class GoogleDeviceCodeResponse(
  @Json(name = "device_code") val deviceCode: String,
  @Json(name = "user_code") val userCode: String,
  @Json(name = "verification_url") val verificationUrl: String? = null,
  @Json(name = "verification_uri") val verificationUri: String? = null,
  @Json(name = "verification_uri_complete") val verificationUriComplete: String? = null,
  @Json(name = "expires_in") val expiresInSeconds: Long = 0L,
  @Json(name = "interval") val intervalSeconds: Long = 5L
) {
  /** URL da aprire su un qualsiasi altro dispositivo per autorizzare l'accesso. */
  val verificationUrlResolved: String
    get() = verificationUriComplete ?: verificationUrl ?: verificationUri ?: "https://google.com/device"
}

/** Risposta del token endpoint (successo o errore HTTP 400 del flusso device). */
@JsonClass(generateAdapter = true)
data class GoogleTokenResponse(
  @Json(name = "access_token") val accessToken: String? = null,
  @Json(name = "expires_in") val expiresInSeconds: Long = 0L,
  @Json(name = "refresh_token") val refreshToken: String? = null,
  @Json(name = "scope") val scope: String? = null,
  @Json(name = "token_type") val tokenType: String? = null,
  @Json(name = "id_token") val idToken: String? = null,
  val error: String? = null,
  @Json(name = "error_description") val errorDescription: String? = null
) {
  val hasAccessToken: Boolean get() = !accessToken.isNullOrBlank()
}

/**
 * Esito di UNA singola interrogazione del token endpoint durante il flusso device.
 * Il LOOP di polling vive nel chiamante (coroutine annullabile, delay fra gli step).
 */
sealed interface GoogleTokenPoll {
  /** Autorizzazione non ancora concessa: riprovare dopo [retryAfterSeconds]. */
  data class Pending(val retryAfterSeconds: Long) : GoogleTokenPoll

  /** L'endpoint chiede di rallentare: allungare l'intervallo (RFC 8628 §3.5). */
  data class SlowDown(val retryAfterSeconds: Long) : GoogleTokenPoll

  /** Autorizzazione negata o codice scaduto: il flusso termina con errore. */
  data class Denied(val reason: String, val description: String?) : GoogleTokenPoll

  /** Token ottenuti. */
  data class Success(val token: GoogleTokenResponse) : GoogleTokenPoll

  /** Errore di rete/protocollo: il flusso può riprovare senza perdere il device_code. */
  data class Transient(val message: String) : GoogleTokenPoll
}

/** Decodifica minimale dell'identity scope di un id_token Google (solo payload email/sub). */
object GoogleIdTokenDecoder {

  /** Email dell'account autorizzato, se leggibile dall'id_token. NESSUN altro uso. */
  fun extractEmail(idToken: String?): String? = extractClaim(idToken, "email")

  /** Identificatore stabile Google dell'account (`sub`), se presente nell'id_token. */
  fun extractSub(idToken: String?): String? = extractClaim(idToken, "sub")

  private fun extractClaim(idToken: String?, claim: String): String? {
    if (idToken.isNullOrBlank()) return null
    val parts = idToken.split(".")
    if (parts.size < 2) return null
    return try {
      val payload = java.util.Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)
      val needle = "\"$claim\":\""
      val start = payload.indexOf(needle)
      if (start < 0) return null
      val from = start + needle.length
      val end = payload.indexOf('"', from)
      if (end <= from) null else payload.substring(from, end)
    } catch (_: Exception) {
      null
    }
  }
}

/**
 * Client di rete per il flusso OAuth Google su dispositivi a input limitato:
 *
 * - **Device Authorization Grant (RFC 8628)** — l'utente apre `google.com/device`
 *   su QUALSIASI altro dispositivo e inserisce il codice mostrato a schermo.
 *   Non richiede browser sul TV né Google Play Services (requisito reale di
 *   Fire TV, dove entrambi mancano).
 * - Refresh dell'access token (l'app conserva SOLO il refresh token, cifrato).
 * - Revoca del token alla disconnessione dell'account.
 *
 * Nessun token viene loggato. Gli endpoint sono parametri di costruzione perché
 * i test unitari li puntano a un MockWebServer.
 */
class GoogleDriveAuthClient(
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build(),
  private val deviceCodeEndpointUrl: String = "https://oauth2.googleapis.com/device/code",
  private val tokenEndpointUrl: String = "https://oauth2.googleapis.com/token",
  private val revokeEndpointUrl: String = "https://oauth2.googleapis.com/revoke",
  private val userInfoEndpointUrl: String = "https://openidconnect.googleapis.com/v1/userinfo"
) {

  private val moshi = Moshi.Builder().build()
  private val deviceCodeAdapter = moshi.adapter(GoogleDeviceCodeResponse::class.java)
  private val tokenAdapter = moshi.adapter(GoogleTokenResponse::class.java)
  private val userInfoAdapter = moshi.adapter(UserInfoResponse::class.java)

  @JsonClass(generateAdapter = true)
  data class UserInfoResponse(
    val email: String? = null,
    val name: String? = null
  )

  /** Scopo OAuth richiesto dall'app (minimo privilegio: vedi report, FASE 5). */
  object Scopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val OPENID = "openid"
    const val EMAIL = "https://www.googleapis.com/auth/userinfo.email"

    /** Stringa scopes per il device flow: Drive per-file + identità per riconoscere l'account. */
    val DEVICE_FLOW: String = "$DRIVE_FILE $OPENID $EMAIL"
  }

  /**
   * Passo 1 del flusso device: chiede a Google il device_code e il user_code da
   * mostrare all'utente. `clientId` è l'OAuth client di tipo "TVs and Limited
   * Input devices" configurato dallo sviluppatore (mai inventato a runtime).
   */
  suspend fun startDeviceFlow(clientId: String, scopes: String = Scopes.DEVICE_FLOW): Result<GoogleDeviceCodeResponse> =
    withContext(Dispatchers.IO) {
      if (clientId.isBlank()) {
        return@withContext Result.failure(IllegalStateException("Client ID Google non configurato"))
      }
      val body = FormBody.Builder()
        .add("client_id", clientId)
        .add("scope", scopes)
        .build()
      val request = Request.Builder()
        .url(deviceCodeEndpointUrl)
        .post(body)
        .build()
      try {
        http.newCall(request).execute().use { response ->
          val text = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            val description = runCatching { tokenAdapter.fromJson(text)?.errorDescription }.getOrNull()
            return@withContext Result.failure(
              IOException("Google device code rifiutato (${response.code}): ${description ?: "nessun dettaglio"}")
            )
          }
          val parsed = deviceCodeAdapter.fromJson(text)
            ?: return@withContext Result.failure(IOException("Risposta device code non leggibile"))
          if (parsed.deviceCode.isBlank() || parsed.userCode.isBlank()) {
            return@withContext Result.failure(IOException("Risposta device code incompleta"))
          }
          Result.success(parsed)
        }
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  /**
   * Passo 3 del flusso device: UNA interrogazione del token endpoint.
   *
   * Google richiede il `client_secret` anche per i client "TVs and Limited Input
   * devices" (documentazione ufficiale del Device Authorization Grant): l'endpoint
   * risponde "Missing required parameter: client_secret" senza. Il segreto per
   * questo tipo di client vive nell'APK per natura del flusso TV (non è un segreto
   * confidenziale lato server).
   */
  suspend fun pollTokenOnce(clientId: String, clientSecret: String, deviceCode: String): GoogleTokenPoll =
    withContext(Dispatchers.IO) {
      val body = FormBody.Builder()
        .add("client_id", clientId)
        .add("client_secret", clientSecret)
        .add("device_code", deviceCode)
        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        .build()
      val request = Request.Builder()
        .url(tokenEndpointUrl)
        .post(body)
        .build()
      try {
        http.newCall(request).execute().use { response ->
          val text = response.body?.string().orEmpty()
          val parsed = tokenAdapter.fromJson(text) ?: GoogleTokenResponse()
          when {
            response.isSuccessful && parsed.hasAccessToken -> GoogleTokenPoll.Success(parsed)
            parsed.error == "authorization_pending" -> GoogleTokenPoll.Pending(retryAfterSeconds = 5L)
            parsed.error == "slow_down" -> GoogleTokenPoll.SlowDown(retryAfterSeconds = 5L)
            parsed.error != null -> GoogleTokenPoll.Denied(parsed.error, parsed.errorDescription)
            else -> GoogleTokenPoll.Transient("Risposta token inattesa (HTTP ${response.code})")
          }
        }
      } catch (e: Exception) {
        GoogleTokenPoll.Transient(e.message ?: "Errore di rete")
      }
    }

  /** Refresh dell'access token usando il refresh token conservato cifrato. */
  suspend fun refreshAccessToken(clientId: String, clientSecret: String, refreshToken: String): Result<GoogleTokenResponse> =
    withContext(Dispatchers.IO) {
      val body = FormBody.Builder()
        .add("client_id", clientId)
        .add("client_secret", clientSecret)
        .add("refresh_token", refreshToken)
        .add("grant_type", "refresh_token")
        .build()
      val request = Request.Builder()
        .url(tokenEndpointUrl)
        .post(body)
        .build()
      try {
        http.newCall(request).execute().use { response ->
          val text = response.body?.string().orEmpty()
          val parsed = tokenAdapter.fromJson(text) ?: GoogleTokenResponse()
          if (response.isSuccessful && parsed.hasAccessToken) {
            Result.success(parsed)
          } else {
            Result.failure(
              GoogleAuthException(
                code = GoogleAuthException.Code.TOKEN_REJECTED,
                message = parsed.errorDescription ?: parsed.error ?: "Refresh rifiutato (HTTP ${response.code})"
              )
            )
          }
        }
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  /** Revoca del token (disconnessione esplicita dell'account). */
  suspend fun revokeToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
    val body = FormBody.Builder()
      .add("token", token)
      .build()
    val request = Request.Builder()
      .url(revokeEndpointUrl)
      .post(body)
      .build()
    try {
      http.newCall(request).execute().use { response ->
        if (response.isSuccessful) Result.success(Unit)
        else Result.failure(IOException("Revoca rifiutata (HTTP ${response.code})"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /** Email dell'account via userinfo endpoint (fallback se id_token assente). */
  suspend fun fetchEmail(accessToken: String): Result<String> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
      .url(userInfoEndpointUrl)
      .header("Authorization", "Bearer $accessToken")
      .get()
      .build()
    try {
      http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          return@use Result.failure(IOException("userinfo rifiutato (HTTP ${response.code})"))
        }
        val parsed = userInfoAdapter.fromJson(text)
        if (parsed?.email.isNullOrBlank()) {
          Result.failure(IOException("userinfo senza email"))
        } else {
          Result.success(parsed!!.email!!)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}

/**
 * Errore di autenticazione Google classificato: la UI lo trasforma in un messaggio
 * comprensibile senza esporre mai token o dettagli sensibili.
 */
data class GoogleAuthException(
  val code: Code,
  override val message: String
) : Exception(message) {
  enum class Code {
    NOT_CONFIGURED,
    TOKEN_REJECTED,
    ACCOUNT_CHANGED,
    EXPIRED,
    DENIED
  }
}
