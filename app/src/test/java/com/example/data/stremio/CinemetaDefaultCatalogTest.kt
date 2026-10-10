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
    assertFalse(catalog.hasXperienceMovies)
    assertFalse(catalog.hasXperienceSeries)
    assertTrue(catalog.forYouMovies.isEmpty())
    assertTrue(catalog.forYouSeries.isEmpty())
    assertTrue(catalog.extraSections.isEmpty())
    assertNotNull(catalog.heroItems)
  }

  @Test
  fun gliAddonUtenteRestanoSezioniAggiuntiveSenzaSostituireCinemeta() {
    val cinemeta = movie("tt1000001", "Popolare")
    val userItem = movie("tt9999999", "Da addon Xperience")

    val catalog = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = listOf(section("top", MediaType.FILM, items = listOf(cinemeta))),
      userSections = listOf(
        section("trending_movies", MediaType.FILM, addonName = "Xperience", items = listOf(userItem))
      ),
      allMedia = emptyList()
    )

    assertEquals(listOf(cinemeta), catalog.popularMovies)
    assertEquals(1, catalog.extraSections.size)
    assertEquals("Xperience", catalog.extraSections.single().addonName)
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
}
