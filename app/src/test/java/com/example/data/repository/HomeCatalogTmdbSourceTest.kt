package com.example.data.repository

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.stremio.CinemetaAddon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compatibilità del mapping delle sezioni TMDB su [StremioCatalogRepository.buildHomeCatalogs],
 * senza rete.
 *
 * La Home di produzione usa Cinemeta per le righe stock e TMDB solo per le Novità
 * provider; questi test restano utili per la funzione PURA e per il percorso
 * legacy/test di [TmdbHomeCatalogProvider.loadHomeSections].
 *
 * Regole coperte:
 *  - le sezioni stock (slot POPULAR / TOP_RATED / NEW) alimentano le righe esistenti;
 *  - le sezioni senza slot sono righe EXTRA e restano nell'ordine fornito;
 *  - un addon che popola un tipo sopprime TUTTE le righe native di quel tipo;
 *  - la sorgente TMDB imposta i flag di paginazione corretti;
 *  - le liste categoria deduplicano i titoli e usano la cache locale come fallback.
 */
class HomeCatalogTmdbSourceTest {

  private fun movie(id: String, title: String, year: Int = 2026) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = year
  )

  private fun series(id: String, title: String, year: Int = 2026) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = year
  )

  private fun tmdbSection(
    id: String,
    mediaType: MediaType,
    items: List<MediaItem>,
    slot: HomeCatalogSlot? = null
  ) = CatalogSection(
    id = id,
    title = id,
    catalogId = id,
    mediaType = mediaType,
    origin = CatalogSourceOrigin.TMDB_NATIVE,
    items = items,
    homeSlot = slot
  )

  private fun stockSections(): List<CatalogSection> = listOf(
    tmdbSection("tmdb_home_movies_popular", MediaType.FILM, listOf(movie("tmdb_m_1", "Popolare")), HomeCatalogSlot.POPULAR),
    tmdbSection("tmdb_home_movies_top_rated", MediaType.FILM, listOf(movie("tmdb_m_2", "Votato")), HomeCatalogSlot.TOP_RATED),
    tmdbSection("tmdb_home_movies_new", MediaType.FILM, listOf(movie("tmdb_m_3", "Nuovo")), HomeCatalogSlot.NEW),
    tmdbSection("tmdb_home_series_popular", MediaType.SERIE_TV, listOf(series("tmdb_tv_1", "Popolare TV")), HomeCatalogSlot.POPULAR),
    tmdbSection("tmdb_home_series_top_rated", MediaType.SERIE_TV, listOf(series("tmdb_tv_2", "Votata TV")), HomeCatalogSlot.TOP_RATED),
    tmdbSection("tmdb_home_series_new", MediaType.SERIE_TV, listOf(series("tmdb_tv_3", "Nuova TV")), HomeCatalogSlot.NEW)
  )

  private fun nativeExtras(): List<CatalogSection> = listOf(
    tmdbSection(
      TmdbHomeCatalogProvider.SECTION_ON_AIR_SERIES,
      MediaType.SERIE_TV,
      listOf(series("tmdb_tv_10", "In onda"))
    ),
    tmdbSection(
      TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW,
      MediaType.FILM,
      listOf(movie("tmdb_m_10", "Digitale"))
    ),
    tmdbSection(
      TmdbHomeCatalogProvider.SECTION_HBO_NEW,
      MediaType.SERIE_TV,
      listOf(series("tmdb_tv_11", "HBO"))
    )
  )

  @Test
  fun leSezioniStockTmdbAlimentanoLeRigheEsistenti() {
    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = stockSections() + nativeExtras(),
      userSections = emptyList(),
      allMedia = emptyList()
    )

    assertEquals(listOf("tmdb_m_1"), catalog.popularMovies.map { it.id })
    assertEquals(listOf("tmdb_m_2"), catalog.top10Movies.map { it.id })
    assertEquals(listOf("tmdb_m_3"), catalog.trendingMovies.map { it.id })
    assertEquals(listOf("tmdb_tv_1"), catalog.popularSeries.map { it.id })
    assertEquals(listOf("tmdb_tv_2"), catalog.top10Series.map { it.id })
    assertEquals(listOf("tmdb_tv_3"), catalog.trendingSeries.map { it.id })

    // Le tre nuove righe viaggiano in extraSections, nell'ordine fornito.
    assertEquals(
      listOf(
        TmdbHomeCatalogProvider.SECTION_ON_AIR_SERIES,
        TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW,
        TmdbHomeCatalogProvider.SECTION_HBO_NEW
      ),
      catalog.extraSections.map { it.id }
    )

    assertTrue(catalog.moviesFromTmdbDefault)
    assertTrue(catalog.seriesFromTmdbDefault)
    assertFalse(catalog.moviesFromDefaultCatalog)
    assertFalse(catalog.seriesFromDefaultCatalog)
    assertFalse(catalog.isMoviesFromStremio)
    assertFalse(catalog.isSeriesFromStremio)
    assertFalse(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    // Righe stock alimentate da TMDB: la UI può attribuirle solo a TMDB.
    assertEquals(CatalogSourceOrigin.TMDB_NATIVE, catalog.stockSourceOrigin)
  }

  @Test
  fun unattribuzioneAddonENegataAlleSezioniNativeAncheSeIlNomeEStatoPopolato() {
    val nativeWithLegacyName = tmdbSection(
      "tmdb_native",
      MediaType.FILM,
      listOf(movie("tmdb_m_90", "Nativo"))
    ).copy(addonId = "org.stremio.cinemeta", addonName = "Cinemeta")

    // Un costruttore legacy non può far comparire "Cinemeta" sopra contenuti TMDB.
    assertNull(nativeWithLegacyName.attributionAddonName)

    val realCinemetaRow = CatalogSection(
      id = "stremio_cinemeta_catalog_top",
      title = "Top • Cinemeta",
      addonId = "org.stremio.cinemeta",
      addonName = "Cinemeta",
      catalogId = "top",
      mediaType = MediaType.FILM,
      origin = CatalogSourceOrigin.STREMIO_ADDON,
      items = listOf(movie("tt1", "Reale addon"))
    )
    // Per un catalogo che proviene DAVVERO da Cinemeta l'attribuzione resta.
    assertEquals("Cinemeta", realCinemetaRow.attributionAddonName)
  }

  @Test
  fun leRigheProviderNativeSonoSoppressePerTipoComeLeAltreRigheTmdb() {
    val providerExtras = listOf(
      tmdbSection(
        TmdbHomeCatalogProvider.SECTION_NETFLIX_MOVIES_NEW,
        MediaType.FILM,
        listOf(movie("tmdb_m_200", "Novità Netflix Film"))
      ),
      tmdbSection(
        TmdbHomeCatalogProvider.SECTION_NETFLIX_SERIES_NEW,
        MediaType.SERIE_TV,
        listOf(series("tmdb_tv_200", "Novità Netflix Serie"))
      )
    )
    val addonMovies = CatalogSection(
      id = "addon_movies",
      title = "Addon Movies",
      addonId = "test.addon",
      addonName = "Test Addon",
      catalogId = "home_movies",
      mediaType = MediaType.FILM,
      items = listOf(movie("tt9000001", "Film Addon"))
    )

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = stockSections() + nativeExtras() + providerExtras,
      userSections = listOf(addonMovies),
      allMedia = emptyList()
    )

    // Un addon FILM non tocca le righe provider Serie TV...
    assertTrue(
      catalog.extraSections.any { it.id == TmdbHomeCatalogProvider.SECTION_NETFLIX_SERIES_NEW }
    )
    // ...ma sopprime tutte le righe native FILM, provider inclusi.
    assertTrue(
      catalog.extraSections.none { it.id == TmdbHomeCatalogProvider.SECTION_NETFLIX_MOVIES_NEW }
    )
    // Le righe stock Serie TV restano TMDB: il badge sorgente resta coerente.
    assertEquals(CatalogSourceOrigin.TMDB_NATIVE, catalog.stockSourceOrigin)
  }

  @Test
  fun unAddonSoloFilmSopprimeSoloLeRigheNativeFilm() {
    val addonMovies = CatalogSection(
      id = "addon_movies",
      title = "Addon Movies",
      addonId = "test.addon",
      addonName = "Test Addon",
      catalogId = "home_movies",
      mediaType = MediaType.FILM,
      items = listOf(movie("tt9000001", "Film Addon"))
    )

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = stockSections() + nativeExtras(),
      userSections = listOf(addonMovies),
      allMedia = emptyList()
    )

    assertTrue(catalog.hasCustomCatalogMovies)
    assertTrue(catalog.popularMovies.isEmpty())
    assertTrue(catalog.top10Movies.isEmpty())
    assertTrue(catalog.trendingMovies.isEmpty())
    assertEquals(listOf("tt9000001"), catalog.allMovies.map { it.id })
    assertFalse(catalog.moviesFromTmdbDefault)

    // Le serie restano native: righe stock, "Serie TV in onda" e "Novità HBO".
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.seriesFromTmdbDefault)
    assertEquals(listOf("tmdb_tv_1"), catalog.popularSeries.map { it.id })
    assertEquals(
      listOf(
        "addon_movies",
        TmdbHomeCatalogProvider.SECTION_ON_AIR_SERIES,
        TmdbHomeCatalogProvider.SECTION_HBO_NEW
      ),
      catalog.extraSections.map { it.id }
    )
    // La riga "Novità in digitale" è film: soppressa insieme alle altre righe film.
    assertTrue(catalog.extraSections.none { it.id == TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW })
  }

  @Test
  fun unAddonSoloSerieSopprimeSoloLeRigheNativeSerie() {
    val addonSeries = CatalogSection(
      id = "addon_series",
      title = "Addon Series",
      addonId = "test.addon",
      addonName = "Test Addon",
      catalogId = "home_series",
      mediaType = MediaType.SERIE_TV,
      items = listOf(series("tt9100001", "Serie Addon"))
    )

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = stockSections() + nativeExtras(),
      userSections = listOf(addonSeries),
      allMedia = emptyList()
    )

    assertTrue(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.popularSeries.isEmpty())
    assertTrue(catalog.top10Series.isEmpty())
    assertTrue(catalog.trendingSeries.isEmpty())
    assertEquals(listOf("tt9100001"), catalog.allSeries.map { it.id })
    assertFalse(catalog.seriesFromTmdbDefault)

    assertFalse(catalog.hasCustomCatalogMovies)
    assertTrue(catalog.moviesFromTmdbDefault)
    assertEquals(listOf("tmdb_m_1"), catalog.popularMovies.map { it.id })

    // Delle righe extra resta solo "Novità in digitale" (film) più la riga addon.
    assertEquals(
      listOf(
        "addon_series",
        TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW
      ),
      catalog.extraSections.map { it.id }
    )
  }

  @Test
  fun senzaCataloghiNativiLeListeCategoriaUsanoLaCacheLocale() {
    val cached = listOf(movie("tmdb_m_50", "Cache Film"), series("tmdb_tv_50", "Cache Serie"))

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = emptyList(),
      userSections = emptyList(),
      allMedia = cached
    )

    assertEquals(listOf("tmdb_m_50"), catalog.allMovies.map { it.id })
    assertEquals(listOf("tmdb_tv_50"), catalog.allSeries.map { it.id })
    assertFalse(catalog.moviesFromTmdbDefault)
    assertFalse(catalog.seriesFromTmdbDefault)
    assertFalse(catalog.moviesFromDefaultCatalog)
    assertFalse(catalog.seriesFromDefaultCatalog)
    assertTrue(catalog.extraSections.isEmpty())
  }

  @Test
  fun leListeCategoriaDeduplicanoLoStessoTitoloTraLeRighe() {
    val shared = movie("tmdb_m_77", "Stesso Film")
    val defaults = listOf(
      tmdbSection("popular", MediaType.FILM, listOf(shared), HomeCatalogSlot.POPULAR),
      tmdbSection("top", MediaType.FILM, listOf(shared), HomeCatalogSlot.TOP_RATED),
      tmdbSection("new", MediaType.FILM, listOf(shared), HomeCatalogSlot.NEW)
    )

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = defaults,
      userSections = emptyList(),
      allMedia = emptyList()
    )

    assertEquals(listOf("tmdb_m_77"), catalog.allMovies.map { it.id })
  }

  @Test
  fun leSezioniCinemetaLegacyRestanoMappateDalCatalogId() {
    // Compatibilità: una sezione Cinemeta costruita senza homeSlot viene comunque
    // riconosciuta dal catalogId del manifest (top / imdbRating / year).
    val legacy = listOf(
      CatalogSection(
        id = "legacy_top",
        title = "Popular",
        addonId = CinemetaAddon.MANIFEST_ID,
        catalogId = CinemetaAddon.CATALOG_POPULAR,
        mediaType = MediaType.FILM,
        items = listOf(movie("tt1", "Legacy popolare"))
      ),
      CatalogSection(
        id = "legacy_top_rated",
        title = "Featured",
        addonId = CinemetaAddon.MANIFEST_ID,
        catalogId = CinemetaAddon.CATALOG_TOP_RATED,
        mediaType = MediaType.FILM,
        items = listOf(movie("tt2", "Legacy votato"))
      ),
      CatalogSection(
        id = "legacy_new",
        title = "New",
        addonId = CinemetaAddon.MANIFEST_ID,
        catalogId = CinemetaAddon.CATALOG_NEW,
        mediaType = MediaType.FILM,
        items = listOf(movie("tt3", "Legacy nuovo"))
      )
    )

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = legacy,
      userSections = emptyList(),
      allMedia = emptyList()
    )

    assertEquals(listOf("tt1"), catalog.popularMovies.map { it.id })
    assertEquals(listOf("tt2"), catalog.top10Movies.map { it.id })
    assertEquals(listOf("tt3"), catalog.trendingMovies.map { it.id })
    // Sorgente Stremio dichiarata: paginazione legacy.
    assertTrue(catalog.moviesFromDefaultCatalog)
    assertFalse(catalog.moviesFromTmdbDefault)
    assertTrue(catalog.extraSections.isEmpty())
    // La UI non mostrerà il badge TMDB per righe alimentate dal loader legacy.
    assertEquals(CatalogSourceOrigin.STREMIO_ADDON, catalog.stockSourceOrigin)
  }

  @Test
  fun unaRigaExtraSenzaContenutiNonCompare() {
    val emptyExtra = tmdbSection(
      TmdbHomeCatalogProvider.SECTION_HBO_NEW,
      MediaType.SERIE_TV,
      emptyList()
    )

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = stockSections() + emptyExtra,
      userSections = emptyList(),
      allMedia = emptyList()
    )

    assertNull(catalog.extraSections.firstOrNull { it.id == TmdbHomeCatalogProvider.SECTION_HBO_NEW })
  }
}
