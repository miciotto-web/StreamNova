package com.example.data.stremio

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.CatalogSection
import com.example.data.repository.StremioCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica che la sorgente predefinita della Home sia **Cinemeta** e non più i
 * feed nativi TMDB:
 *  - l'addon di sistema usa l'URL/manifest ufficiali e dichiara i cataloghi
 *    standard (Popolari / Più votati / Nuovi) per Film e Serie TV;
 *  - [StremioCatalogRepository.buildHomeCatalogs] mappa quei cataloghi sulle
 *    righe della Home, senza reintrodurre feed TMDB.
 */
class CinemetaDefaultCatalogTest {

  private fun movie(id: String, title: String) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM
  )

  private fun series(id: String, title: String) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV
  )

  private fun section(
    catalogId: String,
    mediaType: MediaType,
    addonName: String = CinemetaAddon.DISPLAY_NAME,
    items: List<MediaItem>
  ) = CatalogSection(
    id = "stremio_${CinemetaAddon.MANIFEST_ID}_${if (mediaType == MediaType.SERIE_TV) TYPE_SERIES else TYPE_MOVIE}_$catalogId",
    title = "$catalogId • $addonName",
    addonId = CinemetaAddon.MANIFEST_ID,
    addonName = addonName,
    catalogId = catalogId,
    mediaType = mediaType,
    items = items
  )

  @Test
  fun lAddonDiSistemaUsaUrlEManifestUfficiali() {
    assertEquals("com.linvo.cinemeta", CinemetaAddon.MANIFEST_ID)
    assertEquals("https://v3-cinemeta.strem.io", CinemetaAddon.BASE_URL)
    assertEquals("https://v3-cinemeta.strem.io/manifest.json", CinemetaAddon.MANIFEST_URL)
    assertEquals(CinemetaAddon.BASE_URL, CinemetaAddon.addon.baseUrl)
    assertEquals(CinemetaAddon.MANIFEST_ID, CinemetaAddon.addon.manifest.id)
    assertTrue(CinemetaAddon.addon.isEnabled)
  }

  @Test
  fun lAddonDiSistemaEVieneRiconosciuto() {
    assertTrue(CinemetaAddon.isSystemAddon(CinemetaAddon.MANIFEST_ID))
    assertTrue(CinemetaAddon.isSystemAddon(CinemetaAddon.BASE_URL))
    assertTrue(StremioCatalogRepository.isSystemAddon(CinemetaAddon.MANIFEST_ID))
    assertTrue(StremioAddonRepository.isSystemAddon(CinemetaAddon.MANIFEST_ID))
    assertFalse(CinemetaAddon.isSystemAddon("com.example.altro"))
  }

  @Test
  fun ilManifestDichiaraPopolariPiuVotatiENuoviPerFilmESerie() {
    val movieIds = CinemetaAddon.manifest.movieCatalogs().map { it.effectiveId }.toSet()
    val seriesIds = CinemetaAddon.manifest.seriesCatalogs().map { it.effectiveId }.toSet()

    assertTrue(movieIds.containsAll(listOf("top", "imdbRating", "year")))
    assertTrue(seriesIds.containsAll(listOf("top", "imdbRating", "year")))
  }

  @Test
  fun ilCatalogoNuoviRiceveLAnnoCorrenteComeGenreObbligatorio() {
    val plans = CinemetaAddon.homeCatalogPlans(MediaType.FILM)
    val newPlan = plans.first { it.catalog.effectiveId == CinemetaAddon.CATALOG_NEW }

    assertEquals(
      CinemetaAddon.newReleasesYear.toString(),
      newPlan.extra[EXTRA_GENRE]
    )
    assertTrue(newPlan.catalog.isExtraRequired(EXTRA_GENRE))
  }

  @Test
  fun leRigheHomeArrivanoDaiCataloghiCinemeta() {
    val m1 = movie("tt1000001", "Popolare")
    val m2 = movie("tt1000002", "Votato")
    val m3 = movie("tt1000003", "Nuovo")
    val s1 = series("tt2000001", "Popolare TV")
    val s2 = series("tt2000002", "Votato TV")
    val s3 = series("tt2000003", "Nuovo TV")

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = listOf(
        section("top", MediaType.FILM, items = listOf(m1)),
        section("top", MediaType.SERIE_TV, items = listOf(s1)),
        section("imdbRating", MediaType.FILM, items = listOf(m2)),
        section("imdbRating", MediaType.SERIE_TV, items = listOf(s2)),
        section("year", MediaType.FILM, items = listOf(m3)),
        section("year", MediaType.SERIE_TV, items = listOf(s3))
      ),
      userSections = emptyList(),
      allMedia = emptyList()
    )

    assertEquals(listOf(m1), catalog.popularMovies)
    assertEquals(listOf(m2), catalog.top10Movies)
    assertEquals(listOf(m3), catalog.trendingMovies)
    assertEquals(listOf(s1), catalog.popularSeries)
    assertEquals(listOf(s2), catalog.top10Series)
    assertEquals(listOf(s3), catalog.trendingSeries)

    assertEquals(listOf(m1.id, m2.id, m3.id), catalog.allMovies.map { it.id })
    assertEquals(listOf(s1.id, s2.id, s3.id), catalog.allSeries.map { it.id })

    // Le righe Cinemeta non vengono soppresse e non ci sono feed TMDB extra.
    assertFalse(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.forYouMovies.isEmpty())
    assertTrue(catalog.forYouSeries.isEmpty())
    assertTrue(catalog.extraSections.isEmpty())
    assertNotNull(catalog.heroItems)
  }

  @Test
  fun gliAddonUtenteSostituisconoLeRigheCinemetaSoloDelLoroTipo() {
    val cinemetaMovie = movie("tt1000001", "Popolare")
    val cinemetaSeries = series("tt2000001", "Popolare TV")
    val userItem = movie("tt9999999", "Da addon Xperience")

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = listOf(
        section("top", MediaType.FILM, items = listOf(cinemetaMovie)),
        section("top", MediaType.SERIE_TV, items = listOf(cinemetaSeries))
      ),
      userSections = listOf(
        section("trending_movies", MediaType.FILM, addonName = "Xperience", items = listOf(userItem))
      ),
      allMedia = emptyList()
    )

    // Film: l'addon sostituisce le righe Cinemeta dello stesso tipo.
    assertTrue(catalog.popularMovies.isEmpty())
    assertTrue(catalog.hasCustomCatalogMovies)
    assertEquals(listOf(userItem.id), catalog.allMovies.map { it.id })
    assertEquals(1, catalog.extraSections.size)
    assertEquals("Xperience", catalog.extraSections.single().addonName)

    // Serie TV: nessun catalogo addon utilizzabile, Cinemeta resta la sorgente.
    assertFalse(catalog.hasCustomCatalogSeries)
    assertEquals(listOf(cinemetaSeries), catalog.popularSeries)
  }

  @Test
  fun senzaCinemetaNessunFeedTmdbVieneReintrodottoMaRestaLaCacheCategoria() {
    val cached = movie("tmdb_m_1", "Dalla cache")

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = emptyList(),
      userSections = emptyList(),
      allMedia = listOf(cached)
    )

    assertTrue(catalog.popularMovies.isEmpty())
    assertTrue(catalog.top10Movies.isEmpty())
    assertTrue(catalog.trendingMovies.isEmpty())
    assertTrue(catalog.popularSeries.isEmpty())
    // La cache resta disponibile per le schermate categoria (fallback offline).
    assertEquals(listOf(cached.id), catalog.allMovies.map { it.id })
  }

  @Test
  fun leRigheTop10SonoLimitateAEsattamenteDieciElementi() {
    // Il catalogo di default restituisce una pagina intera (~49-50 elementi).
    val manyMovies = (1..50).map { movie("tt100%04d".format(it), "Film $it") }
    val manySeries = (1..50).map { series("tt200%04d".format(it), "Serie $it") }

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = listOf(
        section(CinemetaAddon.CATALOG_TOP_RATED, MediaType.FILM, items = manyMovies),
        section(CinemetaAddon.CATALOG_TOP_RATED, MediaType.SERIE_TV, items = manySeries)
      ),
      userSections = emptyList(),
      allMedia = emptyList()
    )

    assertEquals(10, StremioCatalogRepository.HOME_TOP_10_SIZE)
    assertEquals(10, catalog.top10Movies.size)
    assertEquals(10, catalog.top10Series.size)
    // Truncamento dei PRIMI 10 in classifica (ordine preservato).
    assertEquals(manyMovies.take(10).map { it.id }, catalog.top10Movies.map { it.id })
    assertEquals(manySeries.take(10).map { it.id }, catalog.top10Series.map { it.id })
  }

  @Test
  fun quandoUnAddonConCataloghiEAttivoLeRigheStockCinemetaVengonoSoppresseEPopolatiAllMoviesAllSeries() {
    val cinemeta = movie("tt1000001", "Popolare Cinemeta")
    val userMovie = movie("tt9999001", "Movie Xperience")
    val userSeries = series("tt9999002", "Series Xperience")

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = listOf(section("top", MediaType.FILM, items = listOf(cinemeta))),
      userSections = listOf(
        section("trending_movies", MediaType.FILM, addonName = "Xperience", items = listOf(userMovie)),
        section("trending_series", MediaType.SERIE_TV, addonName = "Xperience", items = listOf(userSeries))
      ),
      allMedia = emptyList()
    )

    // Tutte le righe stock Cinemeta devono essere soppresse
    assertTrue("popularMovies deve essere vuoto", catalog.popularMovies.isEmpty())
    assertTrue("top10Movies deve essere vuoto", catalog.top10Movies.isEmpty())
    assertTrue("trendingMovies deve essere vuoto", catalog.trendingMovies.isEmpty())
    assertTrue("popularSeries deve essere vuoto", catalog.popularSeries.isEmpty())
    assertTrue("top10Series deve essere vuoto", catalog.top10Series.isEmpty())
    assertTrue("trendingSeries deve essere vuoto", catalog.trendingSeries.isEmpty())
    assertTrue("forYouMovies deve essere vuoto", catalog.forYouMovies.isEmpty())
    assertTrue("forYouSeries deve essere vuoto", catalog.forYouSeries.isEmpty())

    // Flag addon personalizzati attivi
    assertTrue(catalog.hasCustomCatalogMovies)
    assertTrue(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.hasCustomCatalogAddons)

    // allMovies e allSeries popolati dall'addon
    assertEquals(listOf(userMovie.id), catalog.allMovies.map { it.id })
    assertEquals(listOf(userSeries.id), catalog.allSeries.map { it.id })

    // Sezioni extra presenti
    assertEquals(2, catalog.extraSections.size)
  }

  @Test
  fun detectBrandRiconoscePiattaformeDiStreaming() {
    val netflixTarget = StremioCatalogTarget(
      addonId = "test",
      addonName = "Xperience",
      baseUrl = "https://xperience",
      catalog = StremioCatalogDefinition(type = "movie", id = "snoak_netflix_top10_movies", name = "Netflix Top 10")
    )
    val disneyTarget = StremioCatalogTarget(
      addonId = "test",
      addonName = "Xperience",
      baseUrl = "https://xperience",
      catalog = StremioCatalogDefinition(type = "movie", id = "streaming_disney_movies", name = "Disney+")
    )
    val hboTarget = StremioCatalogTarget(
      addonId = "test",
      addonName = "Xperience",
      baseUrl = "https://xperience",
      catalog = StremioCatalogDefinition(type = "series", id = "snoak_hbo_top10_series", name = "HBO Max Top 10")
    )
    val primeTarget = StremioCatalogTarget(
      addonId = "test",
      addonName = "Xperience",
      baseUrl = "https://xperience",
      catalog = StremioCatalogDefinition(type = "movie", id = "streaming_prime_movies", name = "Prime Video")
    )
    val appleTarget = StremioCatalogTarget(
      addonId = "test",
      addonName = "Xperience",
      baseUrl = "https://xperience",
      catalog = StremioCatalogDefinition(type = "series", id = "streaming_apple_series", name = "Apple TV+")
    )

    assertEquals("Netflix", StremioCatalogRepository.detectBrandFromTarget(netflixTarget))
    assertEquals("Disney+", StremioCatalogRepository.detectBrandFromTarget(disneyTarget))
    assertEquals("HBO Max", StremioCatalogRepository.detectBrandFromTarget(hboTarget))
    assertEquals("Prime Video", StremioCatalogRepository.detectBrandFromTarget(primeTarget))
    assertEquals("Apple TV+", StremioCatalogRepository.detectBrandFromTarget(appleTarget))
  }

  @Test
  fun deduplicateItemsFondeProviderSpecificoEGeneri() {
    val genericItem = MediaItem(
      id = "tt001",
      title = "Test Show",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      provider = "Xperience",
      genres = listOf("Drama")
    )
    val netflixItem = MediaItem(
      id = "tt001",
      title = "Test Show",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      provider = "Netflix",
      genres = listOf("Drama", "Top 10")
    )

    val merged = StremioCatalogRepository.deduplicateItems(listOf(genericItem, netflixItem))
    assertEquals(1, merged.size)
    assertEquals("Netflix", merged.single().provider)
    assertTrue(merged.single().genres.contains("Top 10"))
    assertTrue(merged.single().genres.contains("Drama"))
  }
}
