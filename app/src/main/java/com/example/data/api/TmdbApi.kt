package com.example.data.api

import android.util.Log
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
import com.example.data.prefs.AppSettingsRepository

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
  @Json(name = "original_language") val originalLanguage: String? = null
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
  @Json(name = "original_language") val originalLanguage: String? = null
)

data class TmdbMultiSearchResultDto(
  val id: Int,
  @Json(name = "media_type") val mediaType: String? = null,
  val title: String? = null,
  @Json(name = "original_title") val originalTitle: String? = null,
  val name: String? = null,
  @Json(name = "original_name") val originalName: String? = null,
  val overview: String? = null,
  @Json(name = "backdrop_path") val backdropPath: String? = null,
  @Json(name = "poster_path") val posterPath: String? = null,
  @Json(name = "release_date") val releaseDate: String? = null,
  @Json(name = "first_air_date") val firstAirDate: String? = null,
  @Json(name = "vote_average") val voteAverage: Float? = null,
  @Json(name = "vote_count") val voteCount: Int? = null,
  @Json(name = "genre_ids") val genreIds: List<Int>? = emptyList(),
  val popularity: Float? = null
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

data class TmdbVideoDto(
  val id: String? = null,
  val key: String? = null,
  val name: String? = null,
  val site: String? = null,
  val type: String? = null,
  @Json(name = "iso_639_1") val language: String? = null,
  val official: Boolean? = null,
  @Json(name = "published_at") val publishedAt: String? = null
)

data class TmdbVideosResponseDto(
  val id: Int? = null,
  val results: List<TmdbVideoDto>? = emptyList()
)

/**
 * ID esterni del titolo: serve per ottenere l'**id IMDb** (`tt...`) richiesto
 * dal protocollo Stremio (`idPrefixes: ["tt"]`) per interrogare gli addon.
 */
data class TmdbExternalIdsDto(
  val id: Int? = null,
  @Json(name = "imdb_id") val imdbId: String? = null,
  @Json(name = "tvdb_id") val tvdbId: Int? = null
)

data class TmdbFindItemDto(
  val id: Int
)

data class TmdbFindResultDto(
  @Json(name = "movie_results") val movieResults: List<TmdbFindItemDto>? = emptyList(),
  @Json(name = "tv_results") val tvResults: List<TmdbFindItemDto>? = emptyList()
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

data class TmdbReleaseDateItemDto(
  val certification: String?,
  val type: Int? = null,
  val note: String? = null
)

data class TmdbCountryReleaseDatesDto(
  @Json(name = "iso_3166_1") val iso3166_1: String,
  @Json(name = "release_dates") val releaseDates: List<TmdbReleaseDateItemDto>? = emptyList()
)

data class TmdbMovieReleaseDatesResponseDto(
  val id: Int? = null,
  val results: List<TmdbCountryReleaseDatesDto>? = emptyList()
)

data class TmdbTvContentRatingItemDto(
  @Json(name = "iso_3166_1") val iso3166_1: String,
  val rating: String?
)

data class TmdbTvContentRatingsResponseDto(
  val id: Int? = null,
  val results: List<TmdbTvContentRatingItemDto>? = emptyList()
)

data class TmdbProductionCompanyDto(
  val id: Int,
  val name: String,
  @Json(name = "logo_path") val logoPath: String? = null,
  @Json(name = "origin_country") val originCountry: String? = null
)

data class TmdbCompanyDetailDto(
  val id: Int,
  val name: String,
  @Json(name = "logo_path") val logoPath: String? = null,
  val description: String? = null,
  val headquarters: String? = null,
  val homepage: String? = null,
  @Json(name = "origin_country") val originCountry: String? = null
)

data class TmdbPersonDetailDto(
  val id: Int,
  val name: String,
  val biography: String? = null,
  val birthday: String? = null,
  @Json(name = "deathday") val deathday: String? = null,
  @Json(name = "place_of_birth") val placeOfBirth: String? = null,
  @Json(name = "profile_path") val profilePath: String? = null,
  @Json(name = "known_for_department") val knownForDepartment: String? = null,
  val popularity: Float? = null
)

data class TmdbPersonCreditItemDto(
  val id: Int,
  @Json(name = "media_type") val mediaType: String? = null,
  val title: String? = null,
  val name: String? = null,
  @Json(name = "original_title") val originalTitle: String? = null,
  @Json(name = "original_name") val originalName: String? = null,
  @Json(name = "poster_path") val posterPath: String? = null,
  @Json(name = "backdrop_path") val backdropPath: String? = null,
  val overview: String? = null,
  @Json(name = "release_date") val releaseDate: String? = null,
  @Json(name = "first_air_date") val firstAirDate: String? = null,
  @Json(name = "vote_average") val voteAverage: Float? = null,
  val character: String? = null,
  val job: String? = null,
  val department: String? = null,
  val popularity: Float? = null
)

data class TmdbPersonCombinedCreditsDto(
  val id: Int? = null,
  val cast: List<TmdbPersonCreditItemDto>? = emptyList(),
  val crew: List<TmdbPersonCreditItemDto>? = emptyList()
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
  val similar: TmdbPaginatedResponse<TmdbMovieDto>? = null,
  @Json(name = "release_dates") val releaseDates: TmdbMovieReleaseDatesResponseDto? = null,
  @Json(name = "production_companies") val productionCompanies: List<TmdbProductionCompanyDto>? = emptyList(),
  val translations: TmdbTranslationsDto? = null
)

data class TmdbCreatedByDto(
  val id: Int,
  val name: String,
  @Json(name = "profile_path") val profilePath: String? = null
)

data class TmdbTvDetailDto(
  val id: Int,
  @Json(name = "created_by") val createdBy: List<TmdbCreatedByDto>? = emptyList(),
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
  val similar: TmdbPaginatedResponse<TmdbTvDto>? = null,
  val seasons: List<TmdbSeasonInfoDto>? = emptyList(),
  @Json(name = "content_ratings") val contentRatings: TmdbTvContentRatingsResponseDto? = null,
  @Json(name = "production_companies") val productionCompanies: List<TmdbProductionCompanyDto>? = emptyList(),
  val translations: TmdbTranslationsDto? = null
)

/**
 * Blocco `translations` restituito da `append_to_response=translations`.
 * Usato per recuperare la sinossi localizzata quando la lingua richiesta non
 * ha una `overview` (TMDB restituisce la versione originale, spesso inglese).
 */
data class TmdbTranslationsDto(
  val translations: List<TmdbTranslationDto>? = emptyList()
)

data class TmdbTranslationDto(
  @Json(name = "iso_639_1") val iso6391: String? = null,
  @Json(name = "iso_3166_1") val iso31661: String? = null,
  val name: String? = null,
  @Json(name = "english_name") val englishName: String? = null,
  val data: TmdbTranslationDataDto? = null
)

data class TmdbTranslationDataDto(
  val name: String? = null,
  val title: String? = null,
  val overview: String? = null,
  val tagline: String? = null
)

data class TmdbSeasonInfoDto(
  val id: Int? = null,
  val name: String? = null,
  @Json(name = "season_number") val seasonNumber: Int? = null,
  @Json(name = "episode_count") val episodeCount: Int? = null,
  val overview: String? = null,
  @Json(name = "poster_path") val posterPath: String? = null,
  @Json(name = "air_date") val airDate: String? = null,
  @Json(name = "vote_average") val voteAverage: Float? = null
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
  val episodes: List<TmdbEpisodeDto>? = emptyList(),
  @Json(name = "air_date") val airDate: String? = null
)

interface TmdbApiService {
  @GET("movie/{movie_id}/release_dates")
  suspend fun getMovieReleaseDates(
    @Path("movie_id") movieId: Int,
    @Query("api_key") apiKey: String
  ): TmdbMovieReleaseDatesResponseDto

  @GET("tv/{tv_id}/content_ratings")
  suspend fun getTvContentRatings(
    @Path("tv_id") tvId: Int,
    @Query("api_key") apiKey: String
  ): TmdbTvContentRatingsResponseDto

  @GET("movie/{movie_id}/external_ids")
  suspend fun getMovieExternalIds(
    @Path("movie_id") movieId: Int,
    @Query("api_key") apiKey: String
  ): TmdbExternalIdsDto

  @GET("tv/{tv_id}/external_ids")
  suspend fun getTvExternalIds(
    @Path("tv_id") tvId: Int,
    @Query("api_key") apiKey: String
  ): TmdbExternalIdsDto

  @GET("find/{external_id}")
  suspend fun findByExternalId(
    @Path("external_id") externalId: String,
    @Query("api_key") apiKey: String,
    @Query("external_source") externalSource: String = "imdb_id"
  ): TmdbFindResultDto

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
    @Query("append_to_response") append: String = "credits,similar,release_dates,translations"
  ): TmdbMovieDetailDto

  @GET("tv/{series_id}")
  suspend fun getTvDetails(
    @Path("series_id") seriesId: Int,
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT",
    @Query("append_to_response") append: String = "credits,similar,content_ratings,translations"
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
    @Query("watch_region") region: String = "IT",
    @Query("sort_by") sortBy: String = "popularity.desc",
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbMovieDto>

  @GET("discover/tv")
  suspend fun discoverTvByProvider(
    @Query("api_key") apiKey: String,
    @Query("with_watch_providers") providerId: String,
    @Query("watch_region") region: String = "IT",
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

  // Priorita' ai trailer italiani: `language=it-IT` + `include_video_language=it,en,null`
  // fanno tornare a TMDB in UNA sola chiamata sia i video in italiano sia quelli
  // inglesi/privi di lingua (senza `include_video_language` l'API puo' restituire la
  // sola lingua richiesta). La scelta finale it -> en -> primo avviene lato client
  // con [TmdbMediaAssets.pickTrailer]. L'interceptor globale NON riscrive `language`
  // su questi endpoint (controlla `include_video_language`), altrimenti un utente in
  // lingua inglese perderebbe tutti i video italiani.
  @GET("movie/{movie_id}/videos")
  suspend fun getMovieVideos(
    @Path("movie_id") movieId: Int,
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT",
    @Query("include_video_language") includeVideoLanguage: String = "it,en,null"
  ): TmdbVideosResponseDto

  @GET("tv/{tv_id}/videos")
  suspend fun getTvVideos(
    @Path("tv_id") tvId: Int,
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT",
    @Query("include_video_language") includeVideoLanguage: String = "it,en,null"
  ): TmdbVideosResponseDto

  @GET("search/multi")
  suspend fun searchMulti(
    @Query("api_key") apiKey: String,
    @Query("query") query: String,
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1,
    @Query("include_adult") includeAdult: Boolean = false
  ): TmdbPaginatedResponse<TmdbMultiSearchResultDto>

  @GET("search/movie")
  suspend fun searchMovie(
    @Query("api_key") apiKey: String,
    @Query("query") query: String,
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1,
    @Query("include_adult") includeAdult: Boolean = false
  ): TmdbPaginatedResponse<TmdbMovieDto>

  @GET("search/tv")
  suspend fun searchTv(
    @Query("api_key") apiKey: String,
    @Query("query") query: String,
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1,
    @Query("include_adult") includeAdult: Boolean = false
  ): TmdbPaginatedResponse<TmdbTvDto>

  @GET("search/person")
  suspend fun searchPerson(
    @Query("api_key") apiKey: String,
    @Query("query") query: String,
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1
  ): TmdbPaginatedResponse<TmdbPersonDetailDto>

  @GET("discover/movie")
  suspend fun discoverMoviesByCompany(
    @Query("api_key") apiKey: String,
    @Query("with_companies") companyId: String,
    @Query("sort_by") sortBy: String = "popularity.desc",
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1,
    @Query("vote_count.gte") voteCountGte: Int? = null
  ): TmdbPaginatedResponse<TmdbMovieDto>

  @GET("discover/tv")
  suspend fun discoverTvByCompany(
    @Query("api_key") apiKey: String,
    @Query("with_companies") companyId: String,
    @Query("sort_by") sortBy: String = "popularity.desc",
    @Query("language") language: String = "it-IT",
    @Query("page") page: Int = 1,
    @Query("vote_count.gte") voteCountGte: Int? = null
  ): TmdbPaginatedResponse<TmdbTvDto>

  @GET("person/{person_id}")
  suspend fun getPersonDetails(
    @Path("person_id") personId: Int,
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT"
  ): TmdbPersonDetailDto

  @GET("person/{person_id}/combined_credits")
  suspend fun getPersonCombinedCredits(
    @Path("person_id") personId: Int,
    @Query("api_key") apiKey: String,
    @Query("language") language: String = "it-IT"
  ): TmdbPersonCombinedCreditsDto

  @GET("company/{company_id}")
  suspend fun getCompanyDetails(
    @Path("company_id") companyId: Int,
    @Query("api_key") apiKey: String
  ): TmdbCompanyDetailDto
}

object TmdbApiClient {
  private const val BASE_URL = "https://api.themoviedb.org/3/"
  const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/"

  private const val TAG_AUTH = "TmdbAuth"

  /**
   * Distingue un **Read Access Token v4** (JWT: header base64url `eyJ`, tre segmenti
   * separati da punto) da una **API key v3** (stringa esadecimale, nessun punto).
   * Solo il primo va autenticato via header `Authorization: Bearer`.
   */
  internal fun looksLikeReadAccessToken(credential: String): Boolean =
    credential.startsWith("eyJ") && credential.count { it == '.' } == 2

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

  private val okHttpClient: OkHttpClient = run {
    val builder = OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .addInterceptor { chain ->
        val original = chain.request()
        val originalUrl = original.url
        // Endpoint video (riconoscibili da `include_video_language`): la lingua va
        // preservata così com'è stata richiesta (it-IT, o en-US nel fallback), altrimenti
        // la lingua app riscriverebbe la query e perdremmo i trailer in italiano.
        val preserveLanguage = originalUrl.queryParameter("include_video_language") != null
        val tmdbLang = AppSettingsRepository.tmdbLanguage()
        var newUrl = if (!preserveLanguage && originalUrl.queryParameter("language") != null) {
          originalUrl.newBuilder()
            .setQueryParameter("language", tmdbLang)
            .build()
        } else {
          originalUrl
        }

        // Il Read Access Token v4 è un JWT, non una API key v3: va inviato come
        // `Authorization: Bearer`. Passato in `api_key` farebbe rispondere 401 a
        // ogni chiamata anche con un token perfettamente valido.
        val credential = newUrl.queryParameter("api_key").orEmpty()
        val requestBuilder = original.newBuilder()
        if (looksLikeReadAccessToken(credential)) {
          newUrl = newUrl.newBuilder().removeAllQueryParameters("api_key").build()
          requestBuilder.header("Authorization", "Bearer $credential")
        } else if (credential.isBlank()) {
          Log.w(
            TAG_AUTH,
            "Richiesta TMDB ${newUrl.encodedPath} senza credenziale (api_key vuota): " +
              "il server risponderà 401/404 e i metadati resteranno assenti. " +
              "Configurare TMDB_API_KEY (variabile d'ambiente, local.properties o .env)."
          )
        }

        val response = chain.proceed(requestBuilder.url(newUrl).build())
        if (response.code == 401 || response.code == 403) {
          Log.w(
            TAG_AUTH,
            "TMDB ha risposto HTTP ${response.code} su ${newUrl.encodedPath}: " +
              "credenziale assente, revocata o di tipo sbagliato (API key v3 o Read Access Token v4)."
          )
        }
        response
      }
      .addInterceptor(
        HttpLoggingInterceptor().apply {
          level = HttpLoggingInterceptor.Level.BASIC
        }
      )
    try {
      val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
        object : javax.net.ssl.X509TrustManager {
          override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
          override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
          override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
      )
      val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
      }
      builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
      builder.hostnameVerifier { _, _ -> true }
    } catch (e: Exception) {
      // Fallback a default in caso di ambiente ristretto
    }
    builder.build()
  }

  val service: TmdbApiService by lazy {
    Retrofit.Builder()
      .baseUrl(BASE_URL)
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(TmdbApiService::class.java)
  }
}
