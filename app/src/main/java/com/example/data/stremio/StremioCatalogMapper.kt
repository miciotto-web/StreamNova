package com.example.data.stremio

import com.example.data.model.MediaItem
import com.example.data.model.MediaType

/**
 * Mapper dedicato per convertire gli elementi [StremioMetaItem] provenienti dai cataloghi Stremio
 * nei modelli interni dell'applicazione ([MediaItem]), senza alterare la struttura del catalogo nativo.
 */
object StremioCatalogMapper {

  /**
   * Converte un singolo [StremioMetaItem] in un [MediaItem] di StreamNova.
   *
   * @param meta elemento grezzo restituito dal catalogo Stremio.
   * @param addonName nome display dell'addon di provenienza (usato per il campo provider).
   */
  fun toMediaItem(meta: StremioMetaItem, addonName: String? = null): MediaItem {
    val rawId = meta.id?.trim().orEmpty()
    val tmdbId = when {
      rawId.startsWith("tmdb:", ignoreCase = true) -> rawId.removePrefix("tmdb:").toIntOrNull()
      else -> null
    }
    val isTv = meta.isSeries
    val ratingVal = meta.ratingFloat ?: 0f

    return MediaItem(
      id = rawId,
      title = meta.displayTitle,
      originalTitle = meta.name.orEmpty(),
      synopsis = meta.description.orEmpty(),
      videoUrl = "", // Gli stream vengono risolti on-demand tramite StreamManager/TorBox
      backdropUrl = meta.effectiveBackdrop,
      posterUrl = meta.effectivePoster,
      logoUrl = meta.logo?.takeIf { it.isNotBlank() },
      tmdbId = tmdbId,
      type = if (isTv) MediaType.SERIE_TV else MediaType.FILM,
      year = meta.year ?: 0,
      rating = ratingVal,
      genres = meta.genreList,
      provider = addonName,
      tmdbRating = ratingVal.takeIf { it > 0f }
    )
  }

  /**
   * Converte una lista di [StremioMetaItem] in una lista di [MediaItem].
   */
  fun toMediaItemList(items: List<StremioMetaItem>, addonName: String? = null): List<MediaItem> {
    return items.map { toMediaItem(it, addonName) }
  }
}
