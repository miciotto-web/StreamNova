package com.example.data.api

/**
 * Mappa UNICA degli ID genere TMDB sui nomi mostrati dall'app.
 *
 * È condivisa dai cataloghi TMDB già esistenti ([com.example.data.repository.MediaRepository])
 * e dai nuovi cataloghi nativi della Home, così lo stesso titolo non assume etichette
 * diverse a seconda della schermata che lo mostra. I nomi restano quelli italiani già
 * usati dai filtri della schermata categoria ("Azione", "Fantascienza", ...).
 */
object TmdbGenreMapper {

  private val MOVIE_GENRES = mapOf(
    28 to "Azione", 12 to "Avventura", 16 to "Animazione", 35 to "Commedia",
    80 to "Crime", 99 to "Documentario", 18 to "Dramma", 10751 to "Famiglia",
    14 to "Fantasy", 36 to "Storia", 27 to "Horror", 10402 to "Musica",
    9648 to "Mistero", 10749 to "Romance", 878 to "Fantascienza", 10770 to "Film TV",
    53 to "Thriller", 10752 to "Guerra", 37 to "Western"
  )

  private val TV_GENRES = mapOf(
    10759 to "Azione & Avventura", 16 to "Animazione", 35 to "Commedia",
    80 to "Crime", 99 to "Documentario", 18 to "Dramma", 10762 to "Kids",
    9648 to "Mistero", 10763 to "News", 10764 to "Reality",
    10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk",
    10768 to "Guerra & Politica", 37 to "Western"
  )

  /**
   * Nomi dei generi per [genreIds]. Quando TMDB non dichiara alcun genere il titolo
   * resta classificabile con l'etichetta di fallback già usata dai cataloghi
   * ("Cinema" per i film, "Serie TV" per le serie).
   */
  fun mapGenreIds(genreIds: List<Int>?, isMovie: Boolean): List<String> {
    if (genreIds.isNullOrEmpty()) return listOf(if (isMovie) "Cinema" else "Serie TV")
    val dict = if (isMovie) MOVIE_GENRES else TV_GENRES
    val result = genreIds.mapNotNull { dict[it] }
    return if (result.isNotEmpty()) result else listOf(if (isMovie) "Cinema" else "Serie TV")
  }
}
