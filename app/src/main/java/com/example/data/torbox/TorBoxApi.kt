package com.example.data.torbox

import com.example.data.prefs.AppSettingsRepository
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class TorBoxUserDto(
  @Json(name = "api_key") val apiKey: String? = null,
  val email: String? = null,
  val plan: String? = null,
  val verified: Boolean? = null,
  @Json(name = "premium_left") val premiumLeft: Any? = null
)

data class TorBoxUserResponse(
  val success: Boolean? = null,
  val error: String? = null,
  val data: TorBoxUserDto? = null
)

data class TorBoxCheckCachedResponse(
  val success: Boolean? = null,
  val error: String? = null,
  val data: Any? = null
)

data class TorBoxDownloadLinkResponse(
  val success: Boolean? = null,
  val error: String? = null,
  val data: Any? = null
)

data class TorBoxMyListResponse(
  val success: Boolean? = null,
  val error: String? = null,
  val data: Any? = null
)

interface TorBoxApi {

  @GET("user/me")
  suspend fun getUserMe(): TorBoxUserResponse

  @GET("torrents/checkcached")
  suspend fun checkCached(
    @Query("hash") hashes: String,
    @Query("format") format: String = "object"
  ): TorBoxCheckCachedResponse

  @Multipart
  @POST("torrents/createtorrent")
  suspend fun createTorrent(
    @Header("Authorization") authorization: String,
    @Part("magnet") magnet: RequestBody,
    @Part("add_only_if_cached") addOnlyIfCached: RequestBody,
    @Part("allow_zip") allowZip: RequestBody
  ): Response<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>

  @GET("torrents/mylist")
  suspend fun getTorrent(
    @Header("Authorization") authorization: String,
    @Query("id") id: Int,
    @Query("bypass_cache") bypassCache: Boolean = true
  ): Response<TorboxEnvelopeDto<TorboxTorrentDataDto>>

  @GET("torrents/requestdl")
  suspend fun requestDownloadLink(
    @Header("Authorization") authorization: String,
    @Query("token") token: String,
    @Query("torrent_id") torrentId: Int,
    @Query("file_id") fileId: Int?,
    @Query("zip_link") zipLink: Boolean = false,
    @Query("redirect") redirect: Boolean = false,
    @Query("append_name") appendName: Boolean = false
  ): Response<TorboxEnvelopeDto<String>>

  @GET("torrents/mylist")
  suspend fun getMyList(
    @Header("Authorization") authorization: String,
    @Query("hash") hash: String,
    @Query("limit") limit: Int = 10,
    @Query("offset") offset: Int = 0
  ): Response<TorboxEnvelopeDto<List<TorboxTorrentDataDto>>>
}

object TorBoxApiClient {

  const val BASE_URL = "https://api.torbox.app/v1/api/"

  val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val authInterceptor = Interceptor { chain: Interceptor.Chain ->
    val key = AppSettingsRepository.torBoxApiKey.value
    val request = if (key.isNullOrBlank()) {
      chain.request()
    } else {
      chain.request().newBuilder()
        .header("Authorization", "Bearer $key")
        .build()
    }
    chain.proceed(request)
  }

  private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(authInterceptor)
    .addInterceptor(
      HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
    )
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  val api: TorBoxApi by lazy {
    Retrofit.Builder()
      .baseUrl(BASE_URL)
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(TorBoxApi::class.java)
  }
}
