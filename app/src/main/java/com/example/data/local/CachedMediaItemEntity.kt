package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Provenienza di un dato utente (preferito, progresso, scheda importata): `null` significa
 * scritto in LOCALE (player, azione dell'utente, dati storici), `"simkl"` significa
 * adottato dalla sincronizzazione SIMKL. È la base della regola "ultimo scrittore
 * possessore": la disconnessione da SIMKL rimuove solo ciò che la sync ha scritto per
 * ultimo, lasciando intatti i dati locali e quelli di altri servizi.
 */
const val MEDIA_SOURCE_SIMKL = "simkl"

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
  /**
   * Provenienza dello stato "preferito" (`MEDIA_SOURCE_SIMKL` quando è la watchlist SIMKL
   * a portarlo nei Preferiti): `null` = preferito locale o storico, MAI rimosso dal logout.
   * Nessun DEFAULT dichiarato: la migrazione aggiunge la colonna TEXT senza default
   * (coerenza richiesta dalla validazione schema Room dopo la migrazione).
   */
  val favoriteSource: String? = null,
  /**
   * Provenienza del progresso corrente della scheda: `null` = scritto dal locale (player o
   * dati storici), `MEDIA_SOURCE_SIMKL` = ultima posizione adottata dalla sincronizzazione.
   */
  val progressSource: String? = null,
  /**
   * true se la scheda è stata CREATA dall'importazione SIMKL (watchlist o punto di
   * ripresa): al logout una scheda sim può essere eliminata, ma solo se non ha acquisito
   * alcun stato locale (preferito o progresso scritti dall'utente/player).
   */
  @ColumnInfo(defaultValue = "0") val importedFromSimkl: Boolean = false,
  val cachedAt: Long = System.currentTimeMillis()
)
