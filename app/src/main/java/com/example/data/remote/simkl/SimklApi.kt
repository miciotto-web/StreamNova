package com.example.data.remote.simkl

import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
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

  @GET("users/settings")
  suspend fun getUserSettings(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String
  ): Response<SimklUserSettingsResponse>

  @POST("scrobble/start")
  suspend fun startScrobble(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Body payload: Map<String, Any>
  ): Response<Map<String, Any>>

  @POST("scrobble/stop")
  suspend fun stopScrobble(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Body payload: Map<String, Any>
  ): Response<Map<String, Any>>

  @POST("sync/history")
  suspend fun addHistory(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Body payload: Map<String, Any>
  ): Response<Map<String, Any>>

  @POST("scrobble/pause")
  suspend fun pauseScrobble(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Body payload: Map<String, Any>
  ): Response<Map<String, Any>>

  @GET("sync/all-items/shows")
  suspend fun getWatchedShows(
    @Query("client_id") clientId: String,
    @Header("Authorization") authorization: String,
    @Query("extended") extended: String = "full",
    @Query("episode_watched_at") episodeWatchedAt: String = "yes",
    @Query("include_all_episodes") includeAllEpisodes: String = "yes"
  ): Response<Map<String, Any>>

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
    .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
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
