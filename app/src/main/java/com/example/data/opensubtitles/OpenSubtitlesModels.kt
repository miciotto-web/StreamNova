package com.example.data.opensubtitles

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Modelli DTO per le risposte e richieste OpenSubtitles API v3 (REST).
 * Endpoint base: https://api.opensubtitles.com/api/v1/
 */
@JsonClass(generateAdapter = true)
data class OpenSubtitlesSearchResponse(
  @Json(name = "total_pages") val totalPages: Int? = null,
  @Json(name = "total_count") val totalCount: Int? = null,
  @Json(name = "per_page") val perPage: Int? = null,
  @Json(name = "page") val page: Int? = null,
  @Json(name = "data") val data: List<OpenSubtitlesItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesItemDto(
  @Json(name = "id") val id: String? = null,
  @Json(name = "type") val type: String? = null,
  @Json(name = "attributes") val attributes: OpenSubtitlesAttributesDto? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesAttributesDto(
  @Json(name = "subtitle_id") val subtitleId: String? = null,
  @Json(name = "language") val language: String? = null,
  @Json(name = "download_count") val downloadCount: Long? = null,
  @Json(name = "new_download_count") val newDownloadCount: Long? = null,
  @Json(name = "hearing_impaired") val hearingImpaired: Boolean? = null,
  @Json(name = "hd") val hd: Boolean? = null,
  @Json(name = "fps") val fps: Double? = null,
  @Json(name = "votes") val votes: Int? = null,
  @Json(name = "ratings") val ratings: Double? = null,
  @Json(name = "from_trusted") val fromTrusted: Boolean? = null,
  @Json(name = "foreign_parts_only") val foreignPartsOnly: Boolean? = null,
  @Json(name = "ai_translated") val aiTranslated: Boolean? = null,
  @Json(name = "machine_translated") val machineTranslated: Boolean? = null,
  @Json(name = "release") val release: String? = null,
  @Json(name = "comments") val comments: String? = null,
  @Json(name = "legacy_subtitle_id") val legacySubtitleId: Long? = null,
  @Json(name = "url") val url: String? = null,
  @Json(name = "download_url") val downloadUrl: String? = null,
  @Json(name = "files") val files: List<OpenSubtitlesFileDto>? = null,
  @Json(name = "feature_details") val featureDetails: OpenSubtitlesFeatureDetailsDto? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesFileDto(
  @Json(name = "file_id") val fileId: Long? = null,
  @Json(name = "cd_number") val cdNumber: Int? = null,
  @Json(name = "file_name") val fileName: String? = null,
  @Json(name = "download_url") val downloadUrl: String? = null,
  @Json(name = "link") val link: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesFeatureDetailsDto(
  @Json(name = "feature_id") val featureId: Long? = null,
  @Json(name = "feature_type") val featureType: String? = null,
  @Json(name = "year") val year: Int? = null,
  @Json(name = "title") val title: String? = null,
  @Json(name = "movie_name") val movieName: String? = null,
  @Json(name = "imdb_id") val imdbId: Long? = null,
  @Json(name = "tmdb_id") val tmdbId: Long? = null,
  @Json(name = "season_number") val seasonNumber: Int? = null,
  @Json(name = "episode_number") val episodeNumber: Int? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesDownloadRequest(
  @Json(name = "file_id") val fileId: Long,
  @Json(name = "sub_format") val subFormat: String? = "srt"
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesDownloadResponse(
  @Json(name = "link") val link: String? = null,
  @Json(name = "file_name") val fileName: String? = null,
  @Json(name = "requests") val requests: Int? = null,
  @Json(name = "remaining") val remaining: Int? = null,
  @Json(name = "message") val message: String? = null,
  @Json(name = "reset_time") val resetTime: String? = null,
  @Json(name = "reset_time_utc") val resetTimeUtc: String? = null
)
