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

data class TmdbGenreDto(
  val id: Int,
  val name: String
)

data class TmdbCastDto(
  val id: Int,
  val name: String,
  val character: String? = null,
  @Json(name = "profile_path") val profilePath: String? = null,
  val order: Int? = null,
  @Json(name = "known_for_department") val knownForDepartment: String? = null
)

data class TmdbCrewDto(
  val id: Int,
  val name: String,
  val job: String? = null,
  val department: String? = null,
  @Json(name = "profile_path") val profilePath: String? = null
)

data class TmdbCreditsDto(
  val id: Int? = null,
  val cast: List<TmdbCastDto>? = emptyList(),
  val crew: List<TmdbCrewDto>? = emptyList()
)

data class TmdbMovieDetailDto(
  val id: Int,
  val title: String?,
  @Json(name = "original_title") val originalTitle: String? = null,
  val overview: String? = null,
  @Json(name = "backdrop_path") val backdropPath: String? = null,
  @Json(name = "poster_path") val posterPath: String? = null,
  @Json(name = "release_date") val releaseDate: String? = null,
  @Json(name = "vote_average") val voteAverage: Float? = null,
  @Json(name = "vote_count") val voteCount: Int? = null,
  val runtime: Int? = null,
  val genres: List<TmdbGenreDto>? = emptyList(),
  val tagline: String? = null,
  val status: String? = null,
  val credits: TmdbCreditsDto? = null,
  val similar: TmdbPaginatedResponse<TmdbMovieDto>? = null
)

data class TmdbTvDetailDto(
  val id: Int,
  val name: String?,
  @Json(name = "original_name") val originalName: String? = null,
  val overview: String? = null,
  @Json(name = "backdrop_path") val backdropPath: String? = null,
  @Json(name = "poster_path") val posterPath: String? = null,
  @Json(name = "first_air_date") val firstAirDate: String? = null,
  @Json(name = "vote_average") val voteAverage: Float? = null,
  @Json(name = "vote_count") val voteCount: Int? = null,
  @Json(name = "number_of_seasons") val numberOfSeasons: Int? = null,
  @Json(name = "number_of_episodes") val numberOfEpisodes: Int? = null,
  @Json(name = "episode_run_time") val episodeRunTime: List<Int>? = emptyList(),
  val genres: List<TmdbGenreDto>? = emptyList(),
  val tagline: String? = null,
  val status: String? = null,
  val credits: TmdbCreditsDto? = null,
  val similar: TmdbPaginatedResponse<TmdbTvDto>? = null
)

data class TmdbEpisodeDto(
  val id: Int,
  val name: String?,
  @Json(name = "episode_number") val episodeNumber: Int,
  @Json(name = "season_number") val seasonNumber: Int,
  val overview: String? = null,
  @Json(name = "still_path") val stillPath: String? = null,
  @Json(name = "air_date") val airDate: String? = null,
  @Json(name = "vote_average") val voteAverage: Float? = null,
  val runtime: Int? = null
)

data class TmdbSeasonDetailDto(
  val id: Int,
  val name: String?,
  @Json(name = "season_number") val seasonNumber: Int,
  val overview: String? = null,
  val episodes: List<TmdbEpisodeDto>? = emptyList()
)

interface TmdbApiService {
  @GET("tv/{series_id}/season/{season_number}")
  suspend fun getSeasonDetails(
    @Path("series_id") seriesId: Int,
    @Path("season_number") seasonNumber: Int,
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT"
  ): TmdbSeasonDetailDto

  @GET("movie/{movie_id}")
  suspend fun getMovieDetails(
    @Path("movie_id") movieId: Int,
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT",
    @Query("append_to_response") append: String = "credits,similar"
  ): TmdbMovieDetailDto

  @GET("tv/{series_id}")
  suspend fun getTvDetails(
    @Path("series_id") seriesId: Int,
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT",
    @Query("append_to_response") append: String = "credits,similar"
  ): TmdbTvDetailDto

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

  @GET("movie/top_rated")
  suspend fun getTopRatedMovies(
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbMovieDto>

  @GET("tv/top_rated")
  suspend fun getTopRatedTv(
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbTvDto>

  @GET("discover/movie")
  suspend fun discoverMoviesByProvider(
    @Query("api_key") apiKey: String,
    @Query("with_watch_providers") providerId: String,
    @Query("watch_region") region: String? = null,
    @Query("sort_by") sortBy: String = "popularity.desc",
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbMovieDto>

  @GET("discover/tv")
  suspend fun discoverTvByProvider(
    @Query("api_key") apiKey: String,
    @Query("with_watch_providers") providerId: String,
    @Query("watch_region") region: String? = null,
    @Query("sort_by") sortBy: String = "popularity.desc",
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbTvDto>

  @GET("discover/tv")
  suspend fun discoverTvByNetwork(
    @Query("api_key") apiKey: String,
    @Query("with_networks") networkId: String,
    @Query("sort_by") sortBy: String = "popularity.desc",
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbTvDto>

  @GET("discover/movie")
  suspend fun discoverMoviesByGenre(
    @Query("api_key") apiKey: String,
    @Query("with_genres") genreId: String,
    @Query("sort_by") sortBy: String = "popularity.desc",
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbMovieDto>

  @GET("discover/tv")
  suspend fun discoverTvByGenre(
    @Query("api_key") apiKey: String,
    @Query("with_genres") genreId: String,
    @Query("sort_by") sortBy: String = "popularity.desc",
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
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val clean = if (path.startsWith("/")) path else "/$path"
    return "$IMAGE_BASE_URL$size$clean"
  }

  fun posterUrl(path: String?, size: String = "w780"): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val clean = if (path.startsWith("/")) path else "/$path"
    return "$IMAGE_BASE_URL$size$clean"
  }

  fun logoUrl(path: String?, size: String = "w500"): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val clean = if (path.startsWith("/")) path else "/$path"
    return "$IMAGE_BASE_URL$size$clean"
  }

  fun profileUrl(path: String?, size: String = "w185"): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val clean = if (path.startsWith("/")) path else "/$path"
    return "$IMAGE_BASE_URL$size$clean"
  }

  fun stillUrl(path: String?, size: String = "w300"): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val clean = if (path.startsWith("/")) path else "/$path"
    return "$IMAGE_BASE_URL$size$clean"
  }

  val moshi: Moshi = Moshi.Builder()
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
