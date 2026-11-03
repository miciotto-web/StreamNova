package com.aistudio.streamnova.mobile.ui

import androidx.annotation.StringRes
import com.aistudio.streamnova.mobile.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.ProviderCoverage
import com.example.data.repository.StremioCatalogRepository

/**
 * Filtri della schermata Provider, gli stessi della TV 1.0.4 e nello stesso
 * ordine. Nessun filtro viene aggiunto per convenzione.
 */
enum class MobileProviderFilter(@StringRes val labelRes: Int) {
  ALL(R.string.filter_all),
  MOVIES(R.string.filter_movies),
  TV_SERIES(R.string.filter_tv_series),
  TOP_10(R.string.filter_top_10),
  ACTION(R.string.filter_action),
  SCI_FI(R.string.filter_sci_fi),
  DRAMA(R.string.filter_drama),
  COMEDY(R.string.filter_comedy),
  ANIMATION(R.string.filter_animation)
}

/**
 * Filtro PURo della griglia Provider: replica le regole TV su tipo e generi.
 * I generi sono confrontati in italiano e in inglese perché le sorgenti reali
 * (TMDB e addon Stremio) non usano sempre la stessa lingua.
 */
internal fun filterMobileProviderItems(
  items: List<MediaItem>,
  filter: MobileProviderFilter
): List<MediaItem> = when (filter) {
  MobileProviderFilter.ALL -> items
  MobileProviderFilter.MOVIES -> items.filter { it.type == MediaType.FILM }
  MobileProviderFilter.TV_SERIES -> items.filter { it.type == MediaType.SERIE_TV }
  MobileProviderFilter.TOP_10 -> items.sortedByDescending { it.rating }.take(10)
  MobileProviderFilter.ACTION -> items.filter { item ->
    item.genres.any { genre ->
      genre.contains("Azione", ignoreCase = true) ||
        genre.contains("Action", ignoreCase = true) ||
        genre.contains("Avventura", ignoreCase = true) ||
        genre.contains("Adventure", ignoreCase = true)
    }
  }

  MobileProviderFilter.SCI_FI -> items.filter { item ->
    item.genres.any { genre ->
      genre.contains("Fantascienza", ignoreCase = true) ||
        genre.contains("Sci-Fi", ignoreCase = true) ||
        genre.contains("Sci Fi", ignoreCase = true) ||
        genre.contains("Fantasy", ignoreCase = true)
    }
  }

  MobileProviderFilter.DRAMA -> items.filter { item ->
    item.genres.any { genre ->
      genre.contains("Dramma", ignoreCase = true) ||
        genre.contains("Drama", ignoreCase = true)
    }
  }

  MobileProviderFilter.COMEDY -> items.filter { item ->
    item.genres.any { genre ->
      genre.contains("Commedia", ignoreCase = true) ||
        genre.contains("Comedy", ignoreCase = true)
    }
  }

  MobileProviderFilter.ANIMATION -> items.filter { item ->
    item.type == MediaType.FILM &&
      item.genres.any { genre ->
        genre.contains("Animazione", ignoreCase = true) ||
          genre.contains("Animation", ignoreCase = true)
      }
  }
}

/**
 * Compone la griglia Provider con le stesse regole della schermata TV:
 * - FULL: cataloghi Stremio confermati, nessun fallback TMDB;
 * - PARTIAL: Stremio primario + completamento TMDB, deduplicato; con fallback
 *   Cinemeta il catalogo specifico TMDB precede il fallback;
 * - NONE: solo catalogo nativo TMDB.
 */
internal fun mergeMobileProviderCatalog(
  stremioItems: List<MediaItem>,
  tmdbItems: List<MediaItem>,
  coverage: ProviderCoverage,
  fallbackToDefault: Boolean
): List<MediaItem> = when (coverage) {
  ProviderCoverage.FULL ->
    StremioCatalogRepository.stabilizeProviderOrder(stremioItems)

  ProviderCoverage.PARTIAL ->
    StremioCatalogRepository.stabilizeProviderOrder(
      StremioCatalogRepository.deduplicateCrossSource(
        if (fallbackToDefault) tmdbItems + stremioItems else stremioItems + tmdbItems
      )
    )

  ProviderCoverage.NONE ->
    StremioCatalogRepository.stabilizeProviderOrder(tmdbItems)
}
