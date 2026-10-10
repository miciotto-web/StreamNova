package com.example.data.stremio

import com.example.data.model.MediaType
import java.util.Calendar

/**
 * Definizione dell'addon di SISTEMA **Cinemeta**: la sorgente di cataloghi
 * predefinita della Home di StreamNova.
 *
 * Differenza rispetto a un addon utente:
 *  - NON viene scaricato/installato da rete: il manifest è noto staticamente,
 *    quindi può essere registrato e attivato offline a ogni avvio;
 *  - è SEMPRE presente come catalogo di default quando non ci sono cataloghi
 *    personalizzati (addon utente) attivi;
 *  - espone i cataloghi standard del protocollo Stremio che alimentano le righe
 *    principali della Home (Film: Popolari, Più votati, Nuovi — Serie TV: idem).
 *
 * L'URL ufficiale è `https://v3-cinemeta.strem.io/manifest.json`; gli
 * identificativi dei cataloghi sono quelli realmente dichiarati dal manifest
 * (nessun id inventato).
 */
object CinemetaAddon {

  /** ID del manifest ufficiale Cinemeta. */
  const val MANIFEST_ID = "com.linvo.cinemeta"

  /** Base URL dell'addon (senza `/manifest.json`). */
  const val BASE_URL = "https://v3-cinemeta.strem.io"

  /** URL ufficiale del manifest. */
  const val MANIFEST_URL = "https://v3-cinemeta.strem.io/manifest.json"

  /** Nome mostrato all'utente. */
  const val DISPLAY_NAME = "Cinemeta"

  /** Versione dichiarata dal manifest ufficiale. */
  const val VERSION = "3.0.14"

  /** Catalogo "Popolari" (id reale del manifest). */
  const val CATALOG_POPULAR = "top"

  /** Catalogo "Più votati" / Featured (id reale del manifest). */
  const val CATALOG_TOP_RATED = "imdbRating"

  /** Catalogo "Nuovi" / New (id reale del manifest; richiede `genre`). */
  const val CATALOG_NEW = "year"

  /** Anno più recente usato come `genre` per il catalogo "Nuovi". */
  val newReleasesYear: Int
    get() = Calendar.getInstance().get(Calendar.YEAR)

  private val GENRES = listOf(
    "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
    "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
    "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western"
  )

  private val YEAR_OPTIONS: List<String>
    get() = (newReleasesYear downTo 1970).map { it.toString() }

  private fun genreExtra(options: List<String>, required: Boolean = false): List<Any?> {
    val entry = mutableMapOf<String, Any>("name" to EXTRA_GENRE, "options" to options)
    if (required) entry["isRequired"] = true
    return listOf(entry)
  }

  private fun skipExtra(): List<Any?> = listOf(mapOf("name" to EXTRA_SKIP))

  private fun searchExtra(): List<Any?> = listOf(mapOf("name" to EXTRA_SEARCH))

  /**
   * Manifest statico di Cinemeta: identico (per i cataloghi che ci interessano)
   * a `https://v3-cinemeta.strem.io/manifest.json`, senza richiedere rete.
   */
  val manifest: StremioManifest = StremioManifest(
    id = MANIFEST_ID,
    name = DISPLAY_NAME,
    version = VERSION,
    description = "The official addon for movie and series catalogs",
    resources = listOf(RESOURCE_CATALOG, RESOURCE_META, RESOURCE_ADDON_CATALOG),
    types = listOf(TYPE_MOVIE, TYPE_SERIES),
    idPrefixes = listOf("tt"),
    catalogs = listOf(
      StremioCatalogDefinition(
        type = TYPE_MOVIE,
        id = CATALOG_POPULAR,
        name = "Popular",
        extra = genreExtra(GENRES) + searchExtra() + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SEARCH, EXTRA_SKIP)
      ),
      StremioCatalogDefinition(
        type = TYPE_SERIES,
        id = CATALOG_POPULAR,
        name = "Popular",
        extra = genreExtra(GENRES) + searchExtra() + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SEARCH, EXTRA_SKIP)
      ),
      StremioCatalogDefinition(
        type = TYPE_MOVIE,
        id = CATALOG_TOP_RATED,
        name = "Featured",
        extra = genreExtra(GENRES) + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SKIP)
      ),
      StremioCatalogDefinition(
        type = TYPE_SERIES,
        id = CATALOG_TOP_RATED,
        name = "Featured",
        extra = genreExtra(GENRES) + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SKIP)
      ),
      StremioCatalogDefinition(
        type = TYPE_MOVIE,
        id = CATALOG_NEW,
        name = "New",
        extra = genreExtra(YEAR_OPTIONS, required = true) + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SKIP),
        extraRequired = listOf(EXTRA_GENRE)
      ),
      StremioCatalogDefinition(
        type = TYPE_SERIES,
        id = CATALOG_NEW,
        name = "New",
        extra = genreExtra(YEAR_OPTIONS, required = true) + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SKIP),
        extraRequired = listOf(EXTRA_GENRE)
      )
    )
  )

  /** Addon di sistema pronto all'uso (sempre abilitato). */
  val addon: InstalledAddon = InstalledAddon(
    baseUrl = BASE_URL,
    manifest = manifest,
    isEnabled = true
  )

  /** Addon di sistema che l'app registra come catalogo predefinito. */
  val systemAddons: List<InstalledAddon> get() = listOf(addon)

  /** true se [addonId] identifica l'addon di sistema Cinemeta. */
  fun isCinemeta(addonId: String?): Boolean {
    val clean = addonId?.trim() ?: return false
    return clean.equals(MANIFEST_ID, ignoreCase = true) ||
      clean.equals(BASE_URL, ignoreCase = true)
  }

  /** true se [addonId] è un addon di sistema (provider nativo autorizzato). */
  fun isSystemAddon(addonId: String?): Boolean = isCinemeta(addonId)

  /**
   * Piano di caricamento di una riga della Home:
   * quale catalogo Cinemeta interrogare e con quali extra.
   */
  data class HomeCatalogPlan(
    val catalog: StremioCatalogDefinition,
    val extra: Map<String, String> = emptyMap()
  )

  /** Cerca un catalogo dichiarato dal manifest Cinemeta per tipo e id. */
  fun findCatalog(mediaType: MediaType, catalogId: String): StremioCatalogDefinition? {
    val stremioType = if (mediaType == MediaType.SERIE_TV) TYPE_SERIES else TYPE_MOVIE
    return manifest.findCatalog(stremioType, catalogId)
  }

  /**
   * Cataloghi Cinemeta che alimentano le righe principali della Home per il
   * [mediaType] indicato, nell'ordine: Popolari → Più votati → Nuovi.
   *
   * Il catalogo "Nuovi" (`year`) richiede il parametro obbligatorio `genre`:
   * viene passato l'anno più recente disponibile.
   */
  fun homeCatalogPlans(mediaType: MediaType): List<HomeCatalogPlan> = listOfNotNull(
    findCatalog(mediaType, CATALOG_POPULAR)?.let { HomeCatalogPlan(it) },
    findCatalog(mediaType, CATALOG_TOP_RATED)?.let { HomeCatalogPlan(it) },
    findCatalog(mediaType, CATALOG_NEW)?.let {
      HomeCatalogPlan(it, mapOf(EXTRA_GENRE to newReleasesYear.toString()))
    }
  )
}
