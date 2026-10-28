package com.example.data.remote.simkl

import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface SimklApi {
  @GET("oauth/pin")
  suspend fun requestPin(@Query("client_id") clientId: String): Response<SimklDeviceCode>

  @GET("oauth/pin/{user_code}")
  suspend fun pollPin(
    @Path("user_code") userCode: String,
    @Query("client_id") clientId: String
  ): Response<SimklPinPollResponse>

  // ── AUTH V2 ────────────────────────────────────────────────────────────────
  // Richiede un `client_id` registrato per OAuth V2 ("TV, devices & command line"):
  // un client V1 viene rifiutato con 401 invalid_client. Vedi SimklAuthClient.

  @FormUrlEncoded
  @POST("oauth2/device")
  suspend fun requestDeviceAuthorization(
    @Field("client_id") clientId: String,
    @Field("scope") scope: String?
  ): Response<SimklDeviceAuthorization>

  @FormUrlEncoded
  @POST("oauth2/token")
  suspend fun exchangeDeviceCode(
    @Field("grant_type") grantType: String = DEVICE_CODE_GRANT,
    @Field("client_id") clientId: String,
    @Field("device_code") deviceCode: String
  ): Response<SimklTokenResponse>

  @FormUrlEncoded
  @POST("oauth2/token")
  suspend fun refreshAccessToken(
    @Field("grant_type") grantType: String = REFRESH_TOKEN_GRANT,
    @Field("client_id") clientId: String,
    @Field("refresh_token") refreshToken: String
  ): Response<SimklTokenResponse>

  @FormUrlEncoded
  @POST("oauth2/revoke")
  suspend fun revokeToken(
    @Field("client_id") clientId: String,
    @Field("token") token: String
  ): Response<ResponseBody>

  @GET("users/settings")
  suspend fun getUserSettings(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String
  ): Response<SimklUserSettingsResponse>

  @POST("scrobble/start")
  suspend fun startScrobble(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Body payload: Map<String, @JvmSuppressWildcards Any>
  ): Response<Map<String, Any>>

  @POST("scrobble/stop")
  suspend fun stopScrobble(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Body payload: Map<String, @JvmSuppressWildcards Any>
  ): Response<Map<String, Any>>

  @POST("sync/history")
  suspend fun addHistory(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Body payload: Map<String, @JvmSuppressWildcards Any>
  ): Response<Map<String, Any>>

  @POST("scrobble/pause")
  suspend fun pauseScrobble(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Body payload: Map<String, @JvmSuppressWildcards Any>
  ): Response<Map<String, Any>>

  @GET("sync/all-items/shows")
  suspend fun getWatchedShows(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Query("extended") extended: String = "full",
    @Query("episode_watched_at") episodeWatchedAt: String = "yes",
    @Query("include_all_episodes") includeAllEpisodes: String = "yes"
  ): Response<Map<String, Any>>

  /**
   * `GET /sync/playback` — punti di pausa salvati: è la fonte di "Continua a guardare".
   * Risposta = array di sessioni (schema aperto: si parsa in modo difensivo).
   */
  @GET("sync/playback")
  suspend fun getPlaybackSessions(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Query("hide_watched") hideWatched: String = "true",
    @Query("limit") limit: Int = 1000
  ): Response<List<Map<String, Any>>>

  /**
   * `GET /sync/all-items/{type}/{status}` — la watchlist: unica forma documentata per
   * leggere "Plan to Watch" (`status=plantowatch`) di film, serie e anime. La risposta è
   * un oggetto con le chiavi `movies`/`shows`/`anime` (solo quelle con elementi).
   *
   * `extended` non viene richiesto: per importare un titolo in Preferiti bastano titolo,
   * anno e identificativi, e il payload resta piccolo.
   */
  @GET("sync/all-items/{type}/{status}")
  suspend fun getWatchlist(
    @Path("type") type: String,
    @Path("status") status: String,
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String
  ): Response<Map<String, Any>>

  /** `GET /sync/activities` — gate economico prima di ogni pull (vedi Sync guide). */
  @GET("sync/activities")
  suspend fun getActivities(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String
  ): Response<Map<String, Any>>

}

/** Segmenti di `/sync/all-items/{type}/{status}` usati per la watchlist. */
const val SIMKL_TYPE_MOVIES = "movies"
const val SIMKL_TYPE_SHOWS = "shows"
const val SIMKL_TYPE_ANIME = "anime"

/** `status` di "Plan to Watch": il contenuto che l'utente vuole guardare. */
const val SIMKL_STATUS_PLAN_TO_WATCH = "plantowatch"

const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
const val REFRESH_TOKEN_GRANT = "refresh_token"
/** media:write implica media:read; senza write lo scrobble e le scritture prendono 403. */
const val SIMKL_SCOPE_READ_WRITE = "media:read media:write"

/** Sostituisce il codice utente quando compare in una riga di log (nessun PIN in Logcat). */
private const val REDACTED_CODE = "<codice-omesso>"

private val PIN_PATH_REGEX = Regex("(/oauth/pin/)[^/?\\s]+", RegexOption.IGNORE_CASE)
private val USER_CODE_QUERY_REGEX = Regex("(user_code=)[^&\\s]+", RegexOption.IGNORE_CASE)

/**
 * Riga di log HTTP ripulita dal codice utente.
 *
 * Il polling AUTH V1 mette il PIN nell'URL (`GET /oauth/pin/ABCDE`): è un codice temporaneo
 * mostrato a schermo, ma non deve comunque finire in Logcat. I token non compaiono mai nei
 * log: viaggiano nei body delle POST e il livello di logging è `BASIC` (metodo, URL, stato).
 * Il `client_id` resta visibile: è un identificativo dell'app, non un segreto dell'utente.
 */
internal fun redactSimklLogLine(line: String): String =
  USER_CODE_QUERY_REGEX.replace(PIN_PATH_REGEX.replace(line) { "${it.groupValues[1]}$REDACTED_CODE" }) {
    "${it.groupValues[1]}$REDACTED_CODE"
  }

object SimklApiClient {
  const val BASE_URL = "https://api.simkl.com/"
  const val APP_NAME = "streamnova-tv"

  fun createApi(client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .addInterceptor { chain ->
      val original = chain.request()
      val url = original.url.newBuilder()
        .setQueryParameter("app-name", APP_NAME)
        .setQueryParameter("app-version", BuildConfig.VERSION_NAME)
        .build()
      val request = original.newBuilder()
        .url(url)
        .header("User-Agent", "StreamNova/${BuildConfig.VERSION_NAME}")
        .header("Content-Type", "application/json")
        .build()
      chain.proceed(request)
    }
    // BASIC: metodo, URL e stato — mai body o header, quindi mai token. Il codice utente
    // che comparirebbe nell'URL del polling V1 viene omesso da [redactSimklLogLine].
    .addInterceptor(
      HttpLoggingInterceptor { message -> android.util.Log.i("SimklHttp", redactSimklLogLine(message)) }
        .apply { level = HttpLoggingInterceptor.Level.BASIC }
    )
    .build(), baseUrl: String = BASE_URL): SimklApi {
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    return Retrofit.Builder()
      .baseUrl(baseUrl)
      .client(client)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(SimklApi::class.java)
  }

  val api: SimklApi by lazy { createApi() }
}
