package com.example.data.api

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.util.ReleaseDateUtils

/**
 * Mapper PURO dei DTO TMDB verso [MediaItem] per i cataloghi nativi della Home.
 *
 * Differenza rispetto al mapper dei cataloghi TMDB storici
 * ([com.example.data.repository.MediaRepository]): qui non vengono generati episodi
 * provvisori né stream segnaposto. La card Home ha bisogno solo di identità, titolo,
 * immagini, anno e generi; stagioni e puntate le carica il Dettaglio dal suo endpoint
 * TMDB. Gli id restano quelli canonici (`tmdb_m_<id>` / `tmdb_tv_<id>`), quindi sono
 * STABILI tra un refresh e l'altro: le key delle card non cambiano mentre la Home è
 * a schermo.
 */
object TmdbCatalogMapper {

  private val BLOCKED_TMDB_IDS = setOf(318508) // The Pitt Podcast - not the real TV show

  /**
   * Titoli TV che non sono contenuti editoriali (podcast, talk show, recensioni):
   * esclusi anche dai cataloghi della Home, come già accade per i cataloghi TMDB.
   */
  fun isBlockedTv(tvId: Int, tvName: String?): Boolean =
    tvId in BLOCKED_TMDB_IDS ||
      tvName?.contains("Podcast", ignoreCase = true) == true ||
      tvName?.contains("Talk Show", ignoreCase = true) == true ||
      tvName?.contains("After Show", ignoreCase = true) == true ||
      tvName?.contains("Review", ignoreCase = true) == true

  fun movieToMediaItem(movie: TmdbMovieDto, isTop10: Boolean = false): MediaItem {
    val rating = movie.voteAverage?.coerceIn(0f, 10f) ?: 0f
    val genres = TmdbGenreMapper.mapGenreIds(movie.genreIds, isMovie = true)
    return MediaItem(
      id = movieId(movie.id),
      tmdbId = movie.id,
      title = movie.title?.takeIf { it.isNotBlank() } ?: movie.originalTitle ?: "Film TMDB",
      originalTitle = movie.originalTitle.orEmpty(),
      synopsis = movie.overview.orEmpty(),
      videoUrl = "",
      backdropUrl = TmdbApiClient.backdropUrl(movie.backdropPath),
      posterUrl = TmdbApiClient.posterUrl(movie.posterPath, size = "w500"),
      type = MediaType.FILM,
      year = yearOf(movie.releaseDate),
      releaseDate = ReleaseDateUtils.normalizeIsoDate(movie.releaseDate),
      rating = rating,
      genres = if (isTop10) (genres + TOP_10_TAG).distinct() else genres,
      isTop10 = isTop10,
      tmdbRating = rating.takeIf { it > 0f }
    )
  }

  fun tvToMediaItem(tv: TmdbTvDto, isTop10: Boolean = false): MediaItem {
    val rating = tv.voteAverage?.coerceIn(0f, 10f) ?: 0f
    val genres = TmdbGenreMapper.mapGenreIds(tv.genreIds, isMovie = false)
    return MediaItem(
      id = tvId(tv.id),
      tmdbId = tv.id,
      title = tv.name?.takeIf { it.isNotBlank() } ?: tv.originalName ?: "Serie TMDB",
      originalTitle = tv.originalName.orEmpty(),
      synopsis = tv.overview.orEmpty(),
      videoUrl = "",
      backdropUrl = TmdbApiClient.backdropUrl(tv.backdropPath),
      posterUrl = TmdbApiClient.posterUrl(tv.posterPath, size = "w500"),
      type = MediaType.SERIE_TV,
      year = yearOf(tv.firstAirDate),
      releaseDate = ReleaseDateUtils.normalizeIsoDate(tv.firstAirDate),
      durationMinutes = 55,
      rating = rating,
      genres = if (isTop10) (genres + TOP_10_TAG).distinct() else genres,
      isTop10 = isTop10,
      tmdbRating = rating.takeIf { it > 0f }
    )
  }

  /**
   * Etichetta di genere usata dai filtri "Top 10" della schermata categoria: è la
   * stessa che i cataloghi Cinemeta ricevevano in `buildCatalogSection`, così il
   * filtro continua a funzionare con i titoli nativi TMDB.
   */
  private const val TOP_10_TAG = "Top 10"

  /** Id canonico stabile del film (`tmdb_m_<id>`). */
  fun movieId(tmdbId: Int): String = "tmdb_m_$tmdbId"

  /** Id canonico stabile della serie (`tmdb_tv_<id>`). */
  fun tvId(tmdbId: Int): String = "tmdb_tv_$tmdbId"

  /** Anno (`yyyy`) dalla data TMDB; `0` quando la data manca o non è valida. */
  fun yearOf(date: String?): Int = date?.take(4)?.toIntOrNull() ?: 0
}
