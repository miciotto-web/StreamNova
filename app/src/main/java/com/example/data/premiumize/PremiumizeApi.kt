package com.example.data.premiumize

import com.example.data.prefs.AppSettingsRepository
import com.squareup.moshi.Json
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
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class PremiumizeAccountInfoDto(
  val status: String? = null,
  val message: String? = null,
  @Json(name = "customer_id") val customerId: String? = null,
  /** Scadenza premium in secondi epoch (0/assente = account gratuito). */
  @Json(name = "premium_until") val premiumUntil: Long? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeCacheCheckDto(
  val status: String? = null,
  val message: String? = null,
  /** Esito per ciascun elemento inviato, nello stesso ordine. */
  val response: List<Boolean>? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeTransferCreateDto(
  val status: String? = null,
  val message: String? = null,
  val id: String? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeItemFileDto(
  val id: String? = null,
  val name: String? = null,
  val size: Long? = null,
  val mime: String? = null,
  /** Link download (richiede autenticazione per l'uso diretto). */
  val link: String? = null,
  /** Link di streaming diretto HTTPS pronto per il player. */
  @Json(name = "stream_link") val streamLink: String? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeItemDetailsDto(
  val status: String? = null,
  val message: String? = null,
  val id: String? = null,
  val name: String? = null,
  val files: List<PremiumizeItemFileDto>? = null
)

interface PremiumizeApi {

  @GET("account/info")
  suspend fun accountInfo(): PremiumizeAccountInfoDto

  @POST("cache/check")
  @FormUrlEncoded
  suspend fun cacheCheck(@Field("items[]") items: List<String>): PremiumizeCacheCheckDto

  @POST("transfer/create")
  @FormUrlEncoded
  suspend fun transferCreate(@Field("src") src: String): PremiumizeTransferCreateDto

  @GET("item/details")
  suspend fun itemDetails(@Query("id") id: String): PremiumizeItemDetailsDto
}

object PremiumizeApiClient {

  const val BASE_URL = "https://www.premiumize.me/api/"

  val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val authInterceptor = Interceptor { chain: Interceptor.Chain ->
    val key = AppSettingsRepository.premiumizeApiKey.value
    val request = if (key.isNullOrBlank()) {
      chain.request()
    } else {
      // Premiumize accetta la chiave come query parameter su TUTTI gli endpoint.
      val url = chain.request().url.newBuilder()
        .addQueryParameter("apikey", key)
        .build()
      chain.request().newBuilder().url(url).build()
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

  private val defaultApi: PremiumizeApi by lazy {
    Retrofit.Builder()
      .baseUrl(BASE_URL)
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(PremiumizeApi::class.java)
  }

  @Volatile
  private var testApi: PremiumizeApi? = null

  /**
   * API Premiumize effettivamente usata da [PremiumizeRepository].
   *
   * Nei test viene sostituita da un fake con [setApiForTesting]: l'API reale è
   * un singleton Retrofit e senza questo punto di iniezione il comportamento di
   * sblocco (cache check, transfer, selezione file) non sarebbe verificabile.
   */
  val api: PremiumizeApi
    get() = testApi ?: defaultApi

  /** Iniezione dell'API per i test (null ripristina l'istanza di produzione). */
  fun setApiForTesting(api: PremiumizeApi?) {
    testApi = api
  }
}
