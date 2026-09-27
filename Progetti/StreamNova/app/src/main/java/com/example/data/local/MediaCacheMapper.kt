package com.example.data.local

import com.example.data.api.TmdbApiClient
import com.example.data.api.TmdbMovieDetailDto
import com.example.data.api.TmdbMovieDto
import com.example.data.api.TmdbPaginatedResponse
import com.example.data.api.TmdbSeasonDetailDto
import com.example.data.api.TmdbTvDetailDto
import com.example.data.api.TmdbTvDto
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.VideoResolution
import com.example.data.repository.MediaRepository
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types

object MediaCacheMapper {

  private val movieDetailAdapter: JsonAdapter<TmdbMovieDetailDto> =
    TmdbApiClient.moshi.adapter(TmdbMovieDetailDto::class.java)

  private val tvDetailAdapter: JsonAdapter<TmdbTvDetailDto> =
    TmdbApiClient.moshi.adapter(TmdbTvDetailDto::class.java)

  private val seasonDetailAdapter: JsonAdapter<TmdbSeasonDetailDto> =
    TmdbApiClient.moshi.adapter(TmdbSeasonDetailDto::class.java)

  private val movieResponseType = Types.newParameterizedType(
    TmdbPaginatedResponse::class.java,
    TmdbMovieDto::class.java
  )
  private val movieAdapter: JsonAdapter<TmdbPaginatedResponse<TmdbMovieDto>> =
    TmdbApiClient.moshi.adapter(movieResponseType)

  private val tvResponseType = Types.newParameterizedType(
    TmdbPaginatedResponse::class.java,
    TmdbTvDto::class.java
  )
  private val tvAdapter: JsonAdapter<TmdbPaginatedResponse<TmdbTvDto>> =
    TmdbApiClient.moshi.adapter(tvResponseType)

  fun serializeMovieResponse(response: TmdbPaginatedResponse<TmdbMovieDto>): String {
    return movieAdapter.toJson(response)
  }

  fun deserializeMovieResponse(json: String): TmdbPaginatedResponse<TmdbMovieDto>? {
    return try {
      movieAdapter.fromJson(json)
    } catch (_: Exception) {
      null
    }
  }

  fun serializeTvResponse(response: TmdbPaginatedResponse<TmdbTvDto>): String {
    return tvAdapter.toJson(response)
  }

  fun deserializeTvResponse(json: String): TmdbPaginatedResponse<TmdbTvDto>? {
    return try {
      tvAdapter.fromJson(json)
    } catch (_: Exception) {
      null
    }
  }

  fun serializeMovieDetail(dto: TmdbMovieDetailDto): String {
    return movieDetailAdapter.toJson(dto)
  }

  fun deserializeMovieDetail(json: String): TmdbMovieDetailDto? {
    return try {
      movieDetailAdapter.fromJson(json)
    } catch (_: Exception) {
      null
    }
  }

  fun serializeTvDetail(dto: TmdbTvDetailDto): String {
    return tvDetailAdapter.toJson(dto)
  }

  fun deserializeTvDetail(json: String): TmdbTvDetailDto? {
    return try {
      tvDetailAdapter.fromJson(json)
    } catch (_: Exception) {
      null
    }
  }

  fun serializeSeasonDetail(dto: TmdbSeasonDetailDto): String {
    return seasonDetailAdapter.toJson(dto)
  }

  fun deserializeSeasonDetail(json: String): TmdbSeasonDetailDto? {
    return try {
      seasonDetailAdapter.fromJson(json)
    } catch (_: Exception) {
      null
    }
  }

  fun MediaItem.toEntity(): CachedMediaItemEntity {
    return CachedMediaItemEntity(
      id = this.id,
      tmdbId = this.tmdbId,
      title = this.title,
      originalTitle = this.originalTitle,
      synopsis = this.synopsis,
      videoUrl = this.videoUrl,
      resolution = this.resolution.name,
      qualityTags = this.qualityTags.joinToString(","),
      backdropUrl = this.backdropUrl,
      posterUrl = this.posterUrl,
      logoUrl = this.logoUrl,
      type = this.type.name,
      year = this.year,
      durationMinutes = this.durationMinutes,
      seasonsCount = this.seasonsCount,
      rating = this.rating,
      genres = this.genres.joinToString(","),
      director = this.director,
      cast = this.cast.joinToString(","),
      provider = this.provider,
      isTrending = this.isTrending,
      isTop10 = this.isTop10,
      isFavorite = this.isFavorite,
      currentProgressMs = this.currentProgressMs,
      totalDurationMs = this.totalDurationMs,
      lastWatchedSeason = this.lastWatchedSeason,
      lastWatchedEpisode = this.lastWatchedEpisode
    )
  }

  fun CachedMediaItemEntity.toMediaItem(existingEpisodes: Map<String, List<com.example.data.model.Episode>> = emptyMap()): MediaItem {
    val mediaType = try {
      MediaType.valueOf(this.type)
    } catch (_: Exception) {
      MediaType.FILM
    }
    val res = try {
      VideoResolution.valueOf(this.resolution)
    } catch (_: Exception) {
      VideoResolution.UHD_4K
    }
    val qualityList = if (this.qualityTags.isBlank()) {
      listOf("4K", "HDR")
    } else {
      this.qualityTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val genreList = if (this.genres.isBlank()) {
      listOf(if (mediaType == MediaType.FILM) "Cinema" else "Serie TV")
    } else {
      this.genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val castList = if (this.cast.isBlank()) {
      listOf("Cast Principale")
    } else {
      this.cast.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Reuse existing episodes or generate for TV
    val eps = if (mediaType == MediaType.SERIE_TV) {
      existingEpisodes[this.id] ?: MediaRepository.generateEpisodesForTv(
        tvId = this.tmdbId ?: (this.id.hashCode() and 0x7FFFFFFF),
        tvTitle = this.title,
        backdropUrl = this.backdropUrl,
        posterUrl = this.posterUrl,
        seasons = this.seasonsCount ?: 2
      )
    } else {
      emptyList()
    }

    val cleanPoster = this.posterUrl?.takeIf { !it.contains("Z7Vb4Zg2f7Z") && it.isNotBlank() }
    val cleanBackdrop = this.backdropUrl?.takeIf { !it.contains("Z7Vb4Zg2f7Z") && it.isNotBlank() }

    return MediaItem(
      id = this.id,
      tmdbId = this.tmdbId,
      title = this.title,
      originalTitle = this.originalTitle,
      synopsis = this.synopsis,
      videoUrl = this.videoUrl.ifBlank { MediaRepository.FALLBACK_VIDEO_URL },
      resolution = res,
      qualityTags = qualityList,
      backdropRes = null,
      backdropUrl = cleanBackdrop,
      posterRes = null,
      posterUrl = cleanPoster,
      logoUrl = this.logoUrl,
      type = mediaType,
      year = this.year,
      durationMinutes = this.durationMinutes,
      seasonsCount = this.seasonsCount,
      rating = this.rating,
      genres = genreList,
      director = this.director ?: "Regia StreamNova",
      cast = castList,
      currentProgressMs = this.currentProgressMs,
      totalDurationMs = if (this.totalDurationMs > 0) this.totalDurationMs else (this.durationMinutes * 60 * 1000L),
      lastWatchedSeason = this.lastWatchedSeason,
      lastWatchedEpisode = this.lastWatchedEpisode,
      isFavorite = this.isFavorite,
      provider = this.provider,
      isTrending = this.isTrending,
      isTop10 = this.isTop10,
      episodes = eps
    )
  }
}
