package com.example.data.repository

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.util.ReleaseDateUtils

/**
 * Filtro condiviso "Nascondi contenuti non rilasciati" per la Home e i cataloghi
 * TMDB che la alimentano.
 *
 * PUNTO UNICO: le schermate non filtrano mai per conto proprio. Il ViewModel applica
 * questo oggetto alle [HomeCatalogs] pubblicate, così tutte le righe (Hero, Top 10,
 * Popolari, Novità, righe extra e le liste `allMovies`/`allSeries` usate dalle
 * schermate Film/Serie) condividono la stessa regola.
 *
 * REGOLE
 *  - OFF: nessun filtro, la Home resta identica.
 *  - ON, film: nascosto solo se [MediaItem.releaseDate] è una data ISO valida
 *    successiva a oggi (TMDB `release_date`).
 *  - ON, serie TV: nascosta solo se [MediaItem.releaseDate] (TMDB `first_air_date`)
 *    è futura. Se lo stato TMDB dichiara che la serie è già in onda o conclusa
 *    (`Returning Series`, `Ended`, `Canceled`) resta visibile, perché la serie ha
 *    già iniziato la messa in onda.
 *  - Data assente o non valida: il contenuto NON viene nascosto. Vale per i
 *    cataloghi addon che espongono solo l'anno (`releaseInfo: "2026"`) e per ogni
 *    metadato non affidabile: non si filtra ciò che non si può verificare.
 */
object UnreleasedContentFilter {

  /** Stati TMDB che indicano una serie già avviata (non "non rilasciata"). */
  private val RELEASED_SERIES_STATUSES = setOf(
    "returning series",
    "ended",
    "canceled",
    "cancelled"
  )

  /**
   * true se [item] non è ancora uscito alla data [todayIso] (`yyyy-MM-dd`).
   *
   * Un contenuto con data sconosciuta/non valida restituisce sempre `false`.
   */
  fun isUnreleased(item: MediaItem, todayIso: String): Boolean {
    val releaseDate = ReleaseDateUtils.normalizeIsoDate(item.releaseDate) ?: return false
    if (!ReleaseDateUtils.isAfter(releaseDate, todayIso)) return false
    // Una serie con stato di messa in onda già avviato non è "non rilasciata",
    // anche se il dato di prima messa in onda fosse incoerente.
    if (item.type == MediaType.SERIE_TV) {
      val status = item.tvStatus?.trim()?.lowercase()
      if (status != null && status in RELEASED_SERIES_STATUSES) return false
    }
    return true
  }

  /**
   * Filtra una lista preservando l'ordine. Con [enabled] = false restituisce la
   * stessa lista (nessuna copia, nessun filtro).
   */
  fun filter(
    items: List<MediaItem>,
    enabled: Boolean,
    todayIso: String = ReleaseDateUtils.todayIso()
  ): List<MediaItem> {
    if (!enabled || items.isEmpty()) return items
    val visible = items.filterNot { isUnreleased(it, todayIso) }
    return if (visible.size == items.size) items else visible
  }

  /**
   * Applica il filtro a TUTTE le liste della Home in un solo punto.
   *
   * Le righe extra che restano senza contenuti visibili vengono rimosse (la Home non
   * mostra una riga vuota); le altre mantengono titolo, identità e ordine. Se il
   * filtro svuota l'Hero ma la Home ha ancora contenuti visibili, l'Hero viene
   * ricostruito dalle liste già filtrate, così la UI non ricade sui dati non filtrati.
   */
  fun filterHomeCatalogs(
    catalogs: HomeCatalogs,
    enabled: Boolean,
    todayIso: String = ReleaseDateUtils.todayIso()
  ): HomeCatalogs {
    if (!enabled) return catalogs

    val movies = filter(catalogs.allMovies, enabled = true, todayIso = todayIso)
    val series = filter(catalogs.allSeries, enabled = true, todayIso = todayIso)

    var hero = filter(catalogs.heroItems, enabled = true, todayIso = todayIso)
    if (hero.isEmpty() && catalogs.heroItems.isNotEmpty()) {
      hero = StremioCatalogRepository.buildHeroItems(
        movies = movies,
        series = series,
        fallback = movies + series
      )
    }

    val extraSections = catalogs.extraSections.mapNotNull { section ->
      val visibleItems = filter(section.items, enabled = true, todayIso = todayIso)
      when {
        visibleItems.isEmpty() -> null
        visibleItems.size == section.items.size -> section
        else -> section.copy(items = visibleItems)
      }
    }

    return catalogs.copy(
      heroItems = hero,
      top10Movies = filter(catalogs.top10Movies, enabled = true, todayIso = todayIso),
      top10Series = filter(catalogs.top10Series, enabled = true, todayIso = todayIso),
      trendingMovies = filter(catalogs.trendingMovies, enabled = true, todayIso = todayIso),
      trendingSeries = filter(catalogs.trendingSeries, enabled = true, todayIso = todayIso),
      forYouMovies = filter(catalogs.forYouMovies, enabled = true, todayIso = todayIso),
      forYouSeries = filter(catalogs.forYouSeries, enabled = true, todayIso = todayIso),
      popularMovies = filter(catalogs.popularMovies, enabled = true, todayIso = todayIso),
      popularSeries = filter(catalogs.popularSeries, enabled = true, todayIso = todayIso),
      extraSections = extraSections,
      allMovies = movies,
      allSeries = series
    )
  }
}
