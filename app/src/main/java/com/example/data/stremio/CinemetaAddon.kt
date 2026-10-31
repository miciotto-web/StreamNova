package com.example.data.stremio

import com.example.data.model.MediaType
import java.util.Calendar

/**
 * Definizione dell'addon di SISTEMA **Cinemeta**.
 *
 * STATO ATTUALE (migrazione TMDB): Cinemeta NON è più la sorgente dei cataloghi
 * nativi. La Home, le categorie, la ricerca e i provider sono alimentati da TMDB
 * ([com.example.data.repository.TmdbHomeCatalogProvider] e
 * [com.example.data.repository.MediaRepository]); questo oggetto resta come
 * definizione statica del manifest storico e per riconoscere/ignorare l'addon
 * (`isSystemAddon`) dove serve compatibilità.
 *
 * Differenza rispetto a un addon utente:
 *  - NON viene scaricato/installato da rete: il manifest è noto staticamente;
 *  - non viene registrato come catalogo predefinito di nessuna schermata;
 *  - gli helper `homeCatalogPlans` / `genreCatalogs` / `searchCatalogs` descrivono
 *    il manifest storico e sono usati solo da test di compatibilità.
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

  /** Addon di sistema storico (definizione statica, non registrato come sorgente). */
  val addon: InstalledAddon = InstalledAddon(
    baseUrl = BASE_URL,
    manifest = manifest,
    isEnabled = true
  )

  /**
   * Definizione dell'addon di sistema storico.
   *
   * LEGACY: l'app non lo registra più come catalogo predefinito; le schermate
   * native usano TMDB. Nessun chiamante di produzione deve usarlo come sorgente.
   */
  val systemAddons: List<InstalledAddon> get() = listOf(addon)

  /** true se [addonId] identifica l'addon di sistema Cinemeta. */
  fun isCinemeta(addonId: String?): Boolean {
    val clean = addonId?.trim() ?: return false
    return clean.equals(MANIFEST_ID, ignoreCase = true) ||
      clean.equals(BASE_URL, ignoreCase = true)
  }

  /** true se [addonId] identifica l'addon di sistema legacy (Cinemeta). */
  fun isSystemAddon(addonId: String?): Boolean = isCinemeta(addonId)

  /**
   * Piano di caricamento storico di una riga Cinemeta.
   *
   * LEGACY: non è più usato dalla Home (sorgente TMDB). Resta per i test di
   * compatibilità del manifest.
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
   * Cataloghi Cinemeta storici (Popolari → Più votati → Nuovi) per [mediaType].
   *
   * LEGACY: la Home usa [com.example.data.repository.TmdbHomeCatalogProvider].
   */
  fun homeCatalogPlans(mediaType: MediaType): List<HomeCatalogPlan> = listOfNotNull(
    findCatalog(mediaType, CATALOG_POPULAR)?.let { HomeCatalogPlan(it) },
    findCatalog(mediaType, CATALOG_TOP_RATED)?.let { HomeCatalogPlan(it) },
    findCatalog(mediaType, CATALOG_NEW)?.let {
      HomeCatalogPlan(it, mapOf(EXTRA_GENRE to newReleasesYear.toString()))
    }
  )

  private val allMediaTypes = listOf(MediaType.FILM, MediaType.SERIE_TV)

  /**
   * Cataloghi Cinemeta storici per le CATEGORIE della sezione Ricerca.
   *
   * LEGACY: le categorie native usano TMDB Discover (`MediaRepository.discoverByGenre`).
   * Resta solo per i test di compatibilità del manifest storico.
   */
  fun genreCatalogs(mediaType: MediaType? = null): List<StremioCatalogDefinition> {
    val types = mediaType?.let { listOf(it) } ?: allMediaTypes
    return types
      .mapNotNull { findCatalog(it, CATALOG_POPULAR) }
      .filter { it.supportsGenre() && !it.requiresSearch() }
  }

  /**
   * Cataloghi Cinemeta storici per la RICERCA testuale (extra `search`).
   *
   * LEGACY: la ricerca nativa usa TMDB (`MediaRepository.searchTmdb`). Resta solo
   * per i test di compatibilità del manifest storico.
   */
  fun searchCatalogs(mediaType: MediaType? = null): List<StremioCatalogDefinition> {
    val types = mediaType?.let { listOf(it) } ?: allMediaTypes
    return types
      .mapNotNull { findCatalog(it, CATALOG_POPULAR) }
      .filter { it.supportsSearch() }
  }
}
