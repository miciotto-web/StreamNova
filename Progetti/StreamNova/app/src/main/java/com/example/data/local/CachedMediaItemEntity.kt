package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity persisting TMDB Media items.
 * Allows instant cold-start loading, zero-latency scrolling through large lists,
 * and preserves user favorite state and playback progress.
 */
@Entity(tableName = "cached_media_items")
data class CachedMediaItemEntity(
  @PrimaryKey val id: String,
  val tmdbId: Int?,
  val title: String,
  val originalTitle: String,
  val synopsis: String,
  val videoUrl: String,
  val resolution: String, // UHD_4K, FHD_1080P, HD_720P
  val qualityTags: String, // Comma-separated (e.g., "4K,Dolby Atmos,16+")
  val backdropUrl: String?,
  val posterUrl: String?,
  val logoUrl: String?,
  val type: String, // FILM, SERIE_TV
  val year: Int,
  val durationMinutes: Int,
  val seasonsCount: Int?,
  val rating: Float,
  val genres: String, // Comma-separated (e.g., "Fantascienza,Azione")
  val director: String?,
  val cast: String, // Comma-separated
  val provider: String?, // netflix, hbo, disney, prime
  val isTrending: Boolean = false,
  val isTop10: Boolean = false,
  val isFavorite: Boolean = false,
  val currentProgressMs: Long = 0L,
  val totalDurationMs: Long = 0L,
  /** Stagione/episodio visti per ultimo: preservato nei refresh TMDB (mai azzerato). */
  val lastWatchedSeason: Int? = null,
  val lastWatchedEpisode: Int? = null,
  /** Timestamp dell'ultima visione: usato per ordinare "Continua a guardare". */
  val lastWatchedAt: Long? = null,
  val ageRating: Int? = null,
  val cachedAt: Long = System.currentTimeMillis()
)
