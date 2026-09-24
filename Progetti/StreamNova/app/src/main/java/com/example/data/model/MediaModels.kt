package com.example.data.model

enum class MediaType(val labelItalian: String) {
  FILM("Film"),
  SERIE_TV("Serie TV"),
  DOCUMENTARIO("Documentario")
}

enum class VideoResolution(val label: String, val badge: String) {
  HD_720P("HD 720p", "HD"),
  FULL_HD_1080P("Full HD 1080p", "1080p"),
  UHD_4K("Ultra HD 4K HDR", "4K HDR")
}

data class AudioTrack(
  val id: String,
  val language: String,
  val format: String,
)

data class SubtitleTrack(
  val id: String,
  val language: String,
  val isClosedCaption: Boolean = false,
)

data class Episode(
  val id: String,
  val seasonNumber: Int,
  val episodeNumber: Int,
  val title: String,
  val synopsis: String,
  val durationMinutes: Int,
  val thumbnailRes: Int? = null,
  val thumbnailUrl: String? = null,
  val videoUrl: String,
  val currentProgressMs: Long = 0L,
  val totalDurationMs: Long = (durationMinutes * 60 * 1000).toLong(),
) {
  val isCompleted: Boolean
    get() = totalDurationMs > 0 && currentProgressMs >= (totalDurationMs * 0.9)

  val progressFraction: Float
    get() = if (totalDurationMs > 0) (currentProgressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f

  val remainingMinutesText: String
    get() {
      val remainingMs = (totalDurationMs - currentProgressMs).coerceAtLeast(0)
      val mins = (remainingMs / 60000).toInt()
      return "${mins}m rimanenti"
    }
}

data class MediaItem(
  val id: String,
  val title: String,
  val originalTitle: String = "",
  val synopsis: String,
  val videoUrl: String,
  val resolution: VideoResolution = VideoResolution.UHD_4K,
  val qualityTags: List<String> = listOf("4K", "HDR", "Dolby Atmos", "16+"),
  val backdropRes: Int? = null,
  val backdropUrl: String? = null,
  val posterRes: Int? = null,
  val posterUrl: String? = null,
  val logoUrl: String? = null,
  val tmdbId: Int? = null,
  val type: MediaType = MediaType.FILM,
  val year: Int = 2024,
  val durationMinutes: Int = 120,
  val seasonsCount: Int? = null,
  val rating: Float = 8.5f,
  val genres: List<String> = emptyList(),
  val director: String = "",
  val cast: List<String> = emptyList(),
  val currentProgressMs: Long = 0L,
  val totalDurationMs: Long = (durationMinutes * 60 * 1000).toLong(),
  val lastWatchedSeason: Int? = null,
  val lastWatchedEpisode: Int? = null,
   val isFavorite: Boolean = false,
   val releaseDate: Int? = null,
   val episodes: List<Episode> = emptyList(),
 ) {
  val isNew: Boolean
    get() = releaseDate != null && releaseDate >= ((System.currentTimeMillis() / 1000 - 30 * 24 * 60 * 60).toInt())
   
   val progressFraction: Float
    get() = if (totalDurationMs > 0) (currentProgressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f

  val formattedDuration: String
    get() {
      val hours = durationMinutes / 60
      val mins = durationMinutes % 60
      return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

  val formattedRemainingOrProgress: String
    get() {
      if (totalDurationMs <= 0 || currentProgressMs <= 0) return formattedDuration
      val remainingMs = (totalDurationMs - currentProgressMs).coerceAtLeast(0)
      val remainingMins = (remainingMs / 60000).toInt()
      val watchedHours = (currentProgressMs / 3600000).toInt()
      val watchedMins = ((currentProgressMs % 3600000) / 60000).toInt()
      val totalHours = (totalDurationMs / 3600000).toInt()
      val totalMins = ((totalDurationMs % 3600000) / 60000).toInt()

      return if (remainingMins < 60) {
        "${remainingMins}m rimanenti"
      } else {
        "${watchedHours}h ${watchedMins}m / ${totalHours}h ${totalMins}m"
      }
    }
}
