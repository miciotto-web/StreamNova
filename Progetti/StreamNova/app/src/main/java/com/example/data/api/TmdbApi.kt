package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// TMDB Data Transfer Objects (DTOs)
data class TmdbMovieDto(
  val id: Int,
  val title: String?,
  @Json(name = "original_title") val originalTitle: String?,
  val overview: String?,
  @Json(name = "backdrop_path") val backdropPath: String?,
  @Json(name = "poster_path") val posterPath: String?,
  @Json(name = "release_date") val releaseDate: String?,
  @Json(name = "vote_average") val voteAverage: Float?,
  @Json(name = "genre_ids") val genreIds: List<Int>? = emptyList(),
)

data class TmdbTvDto(
  val id: Int,
  val name: String?,
  @Json(name = "original_name") val originalName: String?,
  val overview: String?,
  @Json(name = "backdrop_path") val backdropPath: String?,
  @Json(name = "poster_path") val posterPath: String?,
  @Json(name = "first_air_date") val firstAirDate: String?,
  @Json(name = "vote_average") val voteAverage: Float?,
  @Json(name = "genre_ids") val genreIds: List<Int>? = emptyList(),
)

data class TmdbPaginatedResponse<T>(
  val page: Int,
  val results: List<T>,
  @Json(name = "total_pages") val totalPages: Int?,
  @Json(name = "total_results") val totalResults: Int?,
)

data class TmdbImagesDto(
  val id: Int?,
  val backdrops: List<TmdbImageItemDto>? = emptyList(),
  val posters: List<TmdbImageItemDto>? = emptyList(),
  val logos: List<TmdbLogoItemDto>? = emptyList(),
)

data class TmdbImageItemDto(
  @Json(name = "file_path") val filePath: String,
  val width: Int?,
  val height: Int?,
  @Json(name = "aspect_ratio") val aspectRatio: Float?,
)

data class TmdbLogoItemDto(
  @Json(name = "file_path") val filePath: String,
  @Json(name = "iso_639_1") val language: String?,
  @Json(name = "aspect_ratio") val aspectRatio: Float?,
  val width: Int?,
  val height: Int?,
)

interface TmdbApiService {
  @GET("trending/movie/week")
  suspend fun getTrendingMovies(
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT"
  ): TmdbPaginatedResponse<TmdbMovieDto>

  @GET("trending/tv/week")
  suspend fun getTrendingTv(
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT"
  ): TmdbPaginatedResponse<TmdbTvDto>

  @GET("movie/popular")
  suspend fun getPopularMovies(
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbMovieDto>

  @GET("tv/popular")
  suspend fun getPopularTv(
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbTvDto>

  @GET("movie/{movie_id}/images")
  suspend fun getMovieImages(
    @Path("movie_id") movieId: Int,
    @Query("api_key") apiKey: String,
    @Query("include_image_language") languages: String = "it,en,null"
  ): TmdbImagesDto

  @GET("tv/{tv_id}/images")
  suspend fun getTvImages(
    @Path("tv_id") tvId: Int,
    @Query("api_key") apiKey: String,
    @Query("include_image_language") languages: String = "it,en,null"
  ): TmdbImagesDto
}

object TmdbApiClient {
  private const val BASE_URL = "https://api.themoviedb.org/3/"
  const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/"

  // Helper functions for full TMDB URLs
  fun backdropUrl(path: String?, size: String = "w1280"): String? {
    if (path.isNullOrBlank()) return null
    val clean = if (path.startsWith("/")) path else "/$path"
    return "$IMAGE_BASE_URL$size$clean"
  }

  fun posterUrl(path: String?, size: String = "w780"): String? {
    if (path.isNullOrBlank()) return null
    val clean = if (path.startsWith("/")) path else "/$path"
    return "$IMAGE_BASE_URL$size$clean"
  }

  fun logoUrl(path: String?, size: String = "w500"): String? {
    if (path.isNullOrBlank()) return null
    val clean = if (path.startsWith("/")) path else "/$path"
    return "$IMAGE_BASE_URL$size$clean"
  }

  private val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .addInterceptor(
      HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
      }
    )
    .build()

  val service: TmdbApiService by lazy {
    Retrofit.Builder()
      .baseUrl(BASE_URL)
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(TmdbApiService::class.java)
  }
}
