package com.example.data.stremio

import com.example.data.model.MediaItem
import com.example.data.model.MediaType

/**
 * Mapper dedicato per convertire gli elementi [StremioMetaItem] provenienti dai cataloghi Stremio
 * nei modelli interni dell'applicazione ([MediaItem]), senza alterare la struttura del catalogo nativo.
 */
object StremioCatalogMapper {

  private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

  private const val BROKEN_IMAGE_TOKEN = "Z7Vb4Zg2f7Z"

  private val FOUR_DIGIT_YEAR = Regex("""\d{4}""")

  private val IMAGE_EXTENSION = Regex("""\.(?:jpe?g|png|webp|gif)$""", RegexOption.IGNORE_CASE)

  private val MARKDOWN_URL = Regex("""\[([^\]]*)]\(([^)]+)\)""")

  /**
   * Risolutore opzionale della locandina per id Stremio (`tmdb:` / numerico / `tt...`).
   * Iniettato dal repository con la cache TMDB locale, così il mapper resta puro.
   */
  @Volatile
  var posterResolver: ((id: String, isTv: Boolean) -> String?)? = null

  /**
   * Sanifica il poster grezzo del meta: i path relativi TMDB (`/abc.jpg`) diventano
   * URL assoluti, `http` viene promosso a `https`, i path nudi tipo `abc.jpg` vengono
   * risolti con l'immagine TMDB. Il risultato può essere un link markdown `[url](url)`.
   */
  fun sanitizePoster(rawPoster: String?): String? = when {
    rawPoster.isNullOrBlank() -> null
    rawPoster.startsWith("/") -> tmdbPosterLink(rawPoster)
    rawPoster.startsWith("http://") -> rawPoster.replace("http://", "https://")
    isBareTmdbPath(rawPoster) -> tmdbPosterLink("/$rawPoster")
    else -> rawPoster
  }

  /** Estrae l'URL caricabile da una stringa che può essere un link markdown `[url](url)`. */
  fun extractImageUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return MARKDOWN_URL.find(value)?.groupValues?.get(2)?.takeIf { it.isNotBlank() } ?: value
  }

  private fun tmdbPosterLink(path: String): String {
    val url = "$TMDB_IMAGE_BASE$path"
    return "[$url]($url)"
  }

  private fun isBareTmdbPath(raw: String): Boolean =
    !raw.contains("://") && !raw.contains("/") && IMAGE_EXTENSION.containsMatchIn(raw)

  private fun isRenderableUrl(url: String?): Boolean {
    val value = extractImageUrl(url)?.trim().orEmpty()
    if (value.isEmpty() || value.contains(BROKEN_IMAGE_TOKEN)) return false
    return value.contains("://")
  }

  private fun isResolvableId(rawId: String): Boolean {
    val id = rawId.trim()
    if (id.isEmpty()) return false
    if (id.startsWith("tt", ignoreCase = true)) return true
    if (id.startsWith("tmdb:", ignoreCase = true)) {
      return id.substringAfter(":").trim().toIntOrNull() != null
    }
    if (id.startsWith("tmdb_m_", ignoreCase = true) || id.startsWith("tmdb_tv_", ignoreCase = true)) return true
    return id.toIntOrNull() != null
  }

  /**
   * Locandina del meta: poster sanificato, poi `posterFallback` dichiarato dall'addon,
   * infine risoluzione tramite id TMDB/IMDb ([posterResolver]) quando l'URL è recuperabile.
   */
  private fun posterUrlOf(meta: StremioMetaItem, rawId: String, isTv: Boolean): String? {
    sanitizePoster(meta.effectivePoster)?.let { if (isRenderableUrl(it)) return it }
    sanitizePoster(meta.posterFallback)?.let { if (isRenderableUrl(it)) return it }
    if (!isResolvableId(rawId)) return null
    val resolved = runCatching { posterResolver?.invoke(rawId, isTv) }.getOrNull()
    return sanitizePoster(resolved)?.takeIf { isRenderableUrl(it) }
  }

  /**
   * Anno di uscita: `releaseInfo` quando valido, altrimenti i primi 4 caratteri
   * numerici di `releaseInfo` / `release_date` / `released`. 0 quando irrecuperabile,
   * così la UI non mostra mai uno "0" a schermo.
   */
  fun resolveYear(meta: StremioMetaItem): Int {
    meta.year?.takeIf { it > 0 }?.let { return it }
    return yearFrom(meta.formattedReleaseInfo ?: meta.releaseDate ?: meta.released)
  }

  private fun yearFrom(raw: String?): Int {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return 0
    FOUR_DIGIT_YEAR.find(value)?.value?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    val digits = value.filter { it.isDigit() }
    if (digits.length >= 4) {
      digits.take(4).toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    }
    return 0
  }

  /**
   * Converte un singolo [StremioMetaItem] in un [MediaItem] di StreamNova.
   *
   * @param meta elemento grezzo restituito dal catalogo Stremio.
   * @param addonName nome display dell'addon di provenienza (usato per il campo provider).
   * @param fallbackType tipo dichiarato dal CATALOGO che ha restituito il meta:
   *        usato solo quando il metas non dichiara `type`, così un item di un
   *        catalogo serie non viene mai presentato come film.
   */
  fun toMediaItem(
    meta: StremioMetaItem,
    addonName: String? = null,
    fallbackType: MediaType? = null,
    extraGenres: List<String> = emptyList()
  ): MediaItem {
    val rawId = meta.id?.trim().orEmpty()
    val tmdbId = when {
      rawId.startsWith("tmdb:", ignoreCase = true) -> rawId.substringAfter(":").trim().toIntOrNull()
      else -> null
    }
    val isTv = when {
      meta.isSeries -> true
      meta.isMovie -> false
      else -> fallbackType == MediaType.SERIE_TV
    }
    val ratingVal = meta.ratingFloat ?: 0f

    val combinedGenres = (meta.genreList + extraGenres).distinct()

    return MediaItem(
      id = rawId,
      title = meta.displayTitle,
      originalTitle = meta.name.orEmpty(),
      synopsis = meta.description.orEmpty(),
      videoUrl = "", // Gli stream vengono risolti on-demand tramite StreamManager/TorBox
      backdropUrl = meta.effectiveBackdrop,
      posterUrl = posterUrlOf(meta, rawId, isTv),
      logoUrl = meta.logo?.takeIf { it.isNotBlank() },
      tmdbId = tmdbId,
      type = if (isTv) MediaType.SERIE_TV else MediaType.FILM,
      year = resolveYear(meta),
      rating = ratingVal,
      genres = combinedGenres,
      provider = addonName,
      tmdbRating = ratingVal.takeIf { it > 0f }
    )
  }

  /**
   * Converte una lista di [StremioMetaItem] in una lista di [MediaItem].
   */
  fun toMediaItemList(
    items: List<StremioMetaItem>,
    addonName: String? = null,
    fallbackType: MediaType? = null,
    extraGenres: List<String> = emptyList()
  ): List<MediaItem> {
    return items.map { toMediaItem(it, addonName, fallbackType, extraGenres) }
  }
}
