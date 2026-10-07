package com.example.data.opensubtitles

import com.example.data.prefs.AppSettingsRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Interfaccia Retrofit per le API OpenSubtitles v3 (REST).
 * Base URL: https://api.opensubtitles.com/api/v1/
 */
interface OpenSubtitlesApi {

  /**
   * Ricerca sottotitoli per film o episodi di serie.
   */
  @GET("subtitles")
  suspend fun searchSubtitles(
    @Query("imdb_id") imdbId: Int? = null,
    @Query("tmdb_id") tmdbId: Int? = null,
    @Query("type") type: String? = null,
    @Query("season_number") seasonNumber: Int? = null,
    @Query("episode_number") episodeNumber: Int? = null,
    @Query("languages") languages: String? = null,
    @Query("query") query: String? = null
  ): Response<OpenSubtitlesSearchResponse>

  /**
   * Richiede un URL di download temporaneo per un file_id specifico.
   */
  @POST("download")
  suspend fun requestDownloadLink(
    @Body request: OpenSubtitlesDownloadRequest
  ): Response<OpenSubtitlesDownloadResponse>
}

object OpenSubtitlesApiClient {

  const val BASE_URL = "https://api.opensubtitles.com/api/v1/"
  const val DEFAULT_USER_AGENT = "StreamNova v0.1.0"

  val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  fun getEffectiveApiKey(): String? {
    return AppSettingsRepository.openSubtitlesApiKey.value?.takeIf { it.isNotBlank() }
      ?: System.getenv("OPENSUBTITLES_API_KEY")?.trim()?.takeIf { it.isNotBlank() }
  }

  fun createOkHttpClient(
    apiKeyProvider: () -> String? = { getEffectiveApiKey() },
    timeoutSeconds: Long = 10L
  ): OkHttpClient {
    val authInterceptor = Interceptor { chain ->
      val key = apiKeyProvider()
      val builder = chain.request().newBuilder()
        .header("User-Agent", DEFAULT_USER_AGENT)
        .header("Accept", "application/json")

      if (!key.isNullOrBlank()) {
        builder.header("Api-Key", key)
      }

      chain.proceed(builder.build())
    }

    return OkHttpClient.Builder()
      .addInterceptor(authInterceptor)
      .addInterceptor(
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
      )
      .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
      .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
      .build()
  }

  fun createApi(client: OkHttpClient = createOkHttpClient(), baseUrl: String = BASE_URL): OpenSubtitlesApi {
    return Retrofit.Builder()
      .baseUrl(baseUrl)
      .client(client)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(OpenSubtitlesApi::class.java)
  }

  val api: OpenSubtitlesApi by lazy {
    createApi()
  }
}
