package com.example.data.realdebrid

import com.example.data.debrid.DebridCacheEntry
import com.example.data.debrid.DebridFile
import com.example.data.prefs.AppSettingsRepository
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
data class RealDebridUserDto(
  val username: String? = null,
  val email: String? = null,
  /** 0 = account gratuito, 1 = premium (Real-Debrid /user). */
  val premium: Int? = null,
  val premiumLeft: Any? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridAddMagnetDto(
  val id: String? = null,
  val uri: String? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridFileDto(
  val id: Int? = null,
  val path: String? = null,
  val size: Long? = null,
  /** Some responses expose the size as `bytes` instead of `size`. */
  val bytes: Long? = null
) {
  val resolvedSize: Long?
    get() = size ?: bytes
}

/** Variante torrent conosciuta da Real-Debrid (elemento dell'array `rd`). */
@JsonClass(generateAdapter = true)
data class RealDebridVariantDto(
  val id: String? = null,
  val files: List<RealDebridFileDto>? = null
)

/**
 * Esito `GET /torrents/instantAvailability/{hash}` per un hash:
 * `{ "<hash>": { "rd": [ { files: [...] } ] } }` con `rd` NON vuoto solo per gli
 * hash disponibili in cache istantanea.
 */
@JsonClass(generateAdapter = true)
data class RealDebridInstantAvailabilityDto(
  val rd: List<RealDebridVariantDto>? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridTorrentInfoDto(
  val id: String? = null,
  val hash: String? = null,
  val name: String? = null,
  val status: String? = null,
  val files: List<RealDebridFileDto>? = null,
  /** Link `https://real-debrid.com/d/...` per ogni file selezionato. */
  val links: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridUnrestrictDto(
  /** URL CDN diretto riproducibile da ExoPlayer. */
  val download: String? = null,
  val host: String? = null
)

interface RealDebridApi {

  @GET("user")
  suspend fun user(): Response<RealDebridUserDto>

  @GET("torrents/instantAvailability/{hash}")
  suspend fun instantAvailability(@Path("hash") hash: String):
    Map<String, RealDebridInstantAvailabilityDto>

  @POST("torrents/addMagnet")
  @FormUrlEncoded
  suspend fun addMagnet(@Field("magnet") magnet: String): RealDebridAddMagnetDto

  @GET("torrents/info/{id}")
  suspend fun torrentInfo(@Path("id") id: String): RealDebridTorrentInfoDto

  @POST("torrents/selectFiles/{id}")
  @FormUrlEncoded
  suspend fun selectFiles(@Path("id") id: String, @Field("file_ids") fileIds: String):
    Response<Unit>

  @POST("unrestrict/link")
  @FormUrlEncoded
  suspend fun unrestrictLink(@Field("link") link: String): RealDebridUnrestrictDto
}

object RealDebridApiClient {

  const val BASE_URL = "https://api.real-debrid.com/rest/1.0/"

  val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val authInterceptor = Interceptor { chain: Interceptor.Chain ->
    val key = AppSettingsRepository.realDebridApiKey.value
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

  private val defaultApi: RealDebridApi by lazy {
    Retrofit.Builder()
      .baseUrl(BASE_URL)
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(RealDebridApi::class.java)
  }

  @Volatile
  private var testApi: RealDebridApi? = null

  /**
   * API Real-Debrid effettivamente usata da [RealDebridRepository].
   *
   * Nei test viene sostituita da un fake con [setApiForTesting]: l'API reale è un
   * singleton Retrofit e senza questo punto di iniezione il comportamento di
   * sblocco (cache check, selezione file, unrestrict) non sarebbe verificabile.
   */
  val api: RealDebridApi
    get() = testApi ?: defaultApi

  /** Iniezione dell'API per i test (null ripristina l'istanza di produzione). */
  fun setApiForTesting(api: RealDebridApi?) {
    testApi = api
  }
}

/** Utility di parsing dell'endpoint `instantAvailability` (oggetto grezzo per hash). */
internal object RealDebridParsing {

  /**
   * Estrae lo stato di cache dall'oggetto restituito per un hash:
   * `rd` presente e NON vuoto → in cache istantanea; oggetto vuoto o assente →
   * non in cache. I file della PRIMA variante (`rd[0]`) descrivono il torrent.
   */
  fun parseEntry(data: RealDebridInstantAvailabilityDto?): DebridCacheEntry {
    val variants = data?.rd ?: return DebridCacheEntry(cached = false)
    if (variants.isEmpty()) return DebridCacheEntry(cached = false)
    val files = variants.first().files.orEmpty().map { file ->
      DebridFile(
        name = file.path?.trimStart('/')?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() },
        size = file.resolvedSize
      )
    }
    return DebridCacheEntry(cached = true, files = files)
  }

  /** Magnet per un infoHash (Real-Debrid accetta l'hash puro in `addMagnet`, ma il magnet è universale). */
  fun magnetForHash(infoHash: String): String =
    "magnet:?xt=urn:btih:${infoHash.trim().lowercase()}"
}
