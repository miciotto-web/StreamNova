package com.example.data.stremio

import com.example.data.model.MediaType
import java.util.Calendar

/**
 * Definizione dell'addon di SISTEMA **Cinemeta**: la sorgente dei cataloghi
 * ufficiali di Film e Serie TV di StreamNova.
 *
 * L'addon NON viene scaricato/installato da rete: il manifest è noto staticamente
 * (riproduce `https://v3-cinemeta.strem.io/manifest.json`) e può essere
 * interrogato a ogni avvio. Gli helper `homeCatalogPlans`, `genreCatalogs` e
 * `searchCatalogs` descrivono esclusivamente cataloghi dichiarati dal manifest
 * ufficiale: nessun id o genere è inventato.
 *
 * Cataloghi generali della Home, Film e Serie TV separati:
 *  - `top`        → "Popular";
 *  - `imdbRating` → "Featured";
 *  - `year`       → "New" (richiede `genre` = anno, obbligatorio nel manifest).
 *
 * Il manifest ufficiale dichiara anche `series/last-videos` e
 * `series/calendar-videos`: sono cataloghi speciali che richiedono un elenco di id
 * fornito dal chiamante (`lastVideosIds` / `calendarVideosIds`) e non producono una
 * lista autonoma. Sono riprodotti per fedeltà ma NON vengono mostrati come righe
 * Home perché non esiste un elenco ufficiale da inviare.
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

  /** Catalogo speciale "Last videos" (richiede `lastVideosIds`). */
  const val CATALOG_LAST_VIDEOS = "last-videos"

  /** Catalogo speciale "Calendar videos" (richiede `calendarVideosIds`). */
  const val CATALOG_CALENDAR_VIDEOS = "calendar-videos"

  /** Extra obbligatorio del catalogo `last-videos`. */
  const val EXTRA_LAST_VIDEOS_IDS = "lastVideosIds"

  /** Extra obbligatorio del catalogo `calendar-videos`. */
  const val EXTRA_CALENDAR_VIDEOS_IDS = "calendarVideosIds"

  /** Anno più recente usato come `genre` per il catalogo "Nuovi". */
  val newReleasesYear: Int
    get() = Calendar.getInstance().get(Calendar.YEAR)

  /** Primo anno dichiarato dal manifest per il catalogo `movie/year`. */
  const val MOVIE_FIRST_YEAR = 1920

  /** Primo anno dichiarato dal manifest per il catalogo `series/year`. */
  const val SERIES_FIRST_YEAR = 1960

  /** Generi dichiarati dai cataloghi `movie/top` e `movie/imdbRating`. */
  private val MOVIE_GENRES = listOf(
    "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
    "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
    "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western"
  )

  /**
   * Generi dichiarati dai cataloghi `series/top` e `series/imdbRating`.
   * Rispetto ai film il manifest ufficiale aggiunge Reality-TV, Talk-Show e
   * Game-Show: sono le opzioni realmente restituite per le serie.
   */
  private val SERIES_GENRES = MOVIE_GENRES + listOf("Reality-TV", "Talk-Show", "Game-Show")

  private val MOVIE_YEAR_OPTIONS: List<String>
    get() = (newReleasesYear downTo MOVIE_FIRST_YEAR).map { it.toString() }

  private val SERIES_YEAR_OPTIONS: List<String>
    get() = (newReleasesYear downTo SERIES_FIRST_YEAR).map { it.toString() }

  private fun genreExtra(options: List<String>, required: Boolean = false): List<Any?> {
    val entry = mutableMapOf<String, Any>("name" to EXTRA_GENRE, "options" to options)
    if (required) entry["isRequired"] = true
    return listOf(entry)
  }

  private fun searchExtra(): List<Any?> = listOf(mapOf("name" to EXTRA_SEARCH))

  private fun skipExtra(): List<Any?> = listOf(mapOf("name" to EXTRA_SKIP))

  private fun idsExtra(name: String): List<Any?> = listOf(
    mapOf("name" to name, "isRequired" to true, "optionsLimit" to 100)
  )

  /**
   * Manifest statico di Cinemeta: riproduce i cataloghi del manifest ufficiale
   * (id, nomi, generi, extra e vincoli) senza richiedere rete.
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
        extra = genreExtra(MOVIE_GENRES) + searchExtra() + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SEARCH, EXTRA_SKIP)
      ),
      StremioCatalogDefinition(
        type = TYPE_SERIES,
        id = CATALOG_POPULAR,
        name = "Popular",
        extra = genreExtra(SERIES_GENRES) + searchExtra() + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SEARCH, EXTRA_SKIP)
      ),
      StremioCatalogDefinition(
        type = TYPE_MOVIE,
        id = CATALOG_NEW,
        name = "New",
        extra = genreExtra(MOVIE_YEAR_OPTIONS, required = true) + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SKIP),
        extraRequired = listOf(EXTRA_GENRE)
      ),
      StremioCatalogDefinition(
        type = TYPE_SERIES,
        id = CATALOG_NEW,
        name = "New",
        extra = genreExtra(SERIES_YEAR_OPTIONS, required = true) + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SKIP),
        extraRequired = listOf(EXTRA_GENRE)
      ),
      StremioCatalogDefinition(
        type = TYPE_MOVIE,
        id = CATALOG_TOP_RATED,
        name = "Featured",
        extra = genreExtra(MOVIE_GENRES) + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SKIP)
      ),
      StremioCatalogDefinition(
        type = TYPE_SERIES,
        id = CATALOG_TOP_RATED,
        name = "Featured",
        extra = genreExtra(SERIES_GENRES) + skipExtra(),
        extraSupported = listOf(EXTRA_GENRE, EXTRA_SKIP)
      ),
      StremioCatalogDefinition(
        type = TYPE_SERIES,
        id = CATALOG_LAST_VIDEOS,
        name = "Last videos",
        extra = idsExtra(EXTRA_LAST_VIDEOS_IDS),
        extraSupported = listOf(EXTRA_LAST_VIDEOS_IDS),
        extraRequired = listOf(EXTRA_LAST_VIDEOS_IDS)
      ),
      StremioCatalogDefinition(
        type = TYPE_SERIES,
        id = CATALOG_CALENDAR_VIDEOS,
        name = "Calendar videos",
        extra = idsExtra(EXTRA_CALENDAR_VIDEOS_IDS),
        extraSupported = listOf(EXTRA_CALENDAR_VIDEOS_IDS),
        extraRequired = listOf(EXTRA_CALENDAR_VIDEOS_IDS)
      )
    )
  )

  /** Addon di sistema pronto all'uso (sempre abilitato). */
  val addon: InstalledAddon = InstalledAddon(
    baseUrl = BASE_URL,
    manifest = manifest,
    isEnabled = true
  )

  /** Addon di sistema che l'app interroga come sorgente di default. */
  val systemAddons: List<InstalledAddon> get() = listOf(addon)

  /** true se [addonId] identifica l'addon di sistema Cinemeta. */
  fun isCinemeta(addonId: String?): Boolean {
    val clean = addonId?.trim() ?: return false
    return clean.equals(MANIFEST_ID, ignoreCase = true) ||
      clean.equals(BASE_URL, ignoreCase = true)
  }

  /** true se [addonId] è un addon di sistema (Cinemeta). */
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

  private val allMediaTypes = listOf(MediaType.FILM, MediaType.SERIE_TV)

  /**
   * Cataloghi Cinemeta usati come fonte di DEFAULT per le CATEGORIE della sezione
   * Ricerca ("Esplora per Genere"): il catalogo Popolari (`top`), che dichiara le
   * opzioni `genre`, per Film e Serie TV. Il filtro avviene inviando
   * `genre={NomeGenere}` (vedi la risoluzione delle richieste nel repository).
   *
   * Se [mediaType] è null vengono restituiti entrambi i tipi.
   */
  fun genreCatalogs(mediaType: MediaType? = null): List<StremioCatalogDefinition> {
    val types = mediaType?.let { listOf(it) } ?: allMediaTypes
    return types
      .mapNotNull { findCatalog(it, CATALOG_POPULAR) }
      .filter { it.supportsGenre() && !it.requiresSearch() }
  }

  /**
   * Cataloghi Cinemeta usati come provider di DEFAULT per la RICERCA testuale
   * globale (extra `search`) quando nessun addon utente copre la query.
   * Se [mediaType] è null vengono restituiti entrambi i tipi.
   */
  fun searchCatalogs(mediaType: MediaType? = null): List<StremioCatalogDefinition> {
    val types = mediaType?.let { listOf(it) } ?: allMediaTypes
    return types
      .mapNotNull { findCatalog(it, CATALOG_POPULAR) }
      .filter { it.supportsSearch() }
  }
}
