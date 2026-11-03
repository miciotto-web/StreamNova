package com.aistudio.streamnova.mobile.ui

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.ProviderCoverage
import com.example.ui.components.ProviderBranding
import com.example.ui.components.ProviderConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileProviderCatalogPolicyTest {

  private fun media(
    id: String,
    type: MediaType = MediaType.FILM,
    rating: Float = 7f,
    genres: List<String> = emptyList()
  ) = MediaItem(
    id = id,
    title = id,
    synopsis = "",
    videoUrl = "",
    type = type,
    rating = rating,
    genres = genres
  )

  @Test
  fun leCardProviderUsanoOrdineEBrandingDellaTv() {
    assertEquals(
      listOf("netflix", "hbo", "disney", "prime", "apple", "paramount", "crunchyroll"),
      ProviderConstants.ALL.map { it.id }
    )
    ProviderConstants.ALL.forEach { provider ->
      assertNotNull(
        "Logo ufficiale mancante per ${provider.id}",
        ProviderBranding.logoRes(provider.tmdbProviderId)
      )
    }
  }

  @Test
  fun iFiltriRispettanoTipoGenereETop10DellaTv() {
    val actionMovie = media("action", genres = listOf("Azione"))
    val animationMovie = media("animation", genres = listOf("Animazione"))
    val dramaMovie = media("drama", genres = listOf("Dramma"))
    val comedySeries = media("comedy", type = MediaType.SERIE_TV, genres = listOf("Commedia"))
    val sciFiSeries = media("sci-fi", type = MediaType.SERIE_TV, genres = listOf("Fantascienza"))
    val topRated = media("top", rating = 9.5f)
    val items = listOf(actionMovie, animationMovie, dramaMovie, comedySeries, sciFiSeries, topRated)

    assertEquals(
      listOf(actionMovie, animationMovie, dramaMovie, topRated),
      filterMobileProviderItems(items, MobileProviderFilter.MOVIES)
    )
    assertEquals(listOf(comedySeries, sciFiSeries), filterMobileProviderItems(items, MobileProviderFilter.TV_SERIES))
    // Stessa regola TV: la ricerca "Azione" è case-insensitive e include
    // "Animazione" (sottostringa), quindi il filtro Azione contiene entrambi.
    assertEquals(
      listOf(actionMovie, animationMovie),
      filterMobileProviderItems(items, MobileProviderFilter.ACTION)
    )
    assertEquals(listOf(sciFiSeries), filterMobileProviderItems(items, MobileProviderFilter.SCI_FI))
    assertEquals(listOf(dramaMovie), filterMobileProviderItems(items, MobileProviderFilter.DRAMA))
    assertEquals(listOf(comedySeries), filterMobileProviderItems(items, MobileProviderFilter.COMEDY))
    assertEquals(listOf(animationMovie), filterMobileProviderItems(items, MobileProviderFilter.ANIMATION))
    assertEquals(topRated, filterMobileProviderItems(items, MobileProviderFilter.TOP_10).first())
  }

  @Test
  fun coperturaFullUsaSoloStremio() {
    val stremio = listOf(media("s1"), media("s2", MediaType.SERIE_TV))
    val tmdb = listOf(media("t1"))

    val merged = mergeMobileProviderCatalog(
      stremioItems = stremio,
      tmdbItems = tmdb,
      coverage = ProviderCoverage.FULL,
      fallbackToDefault = false
    )

    assertEquals(stremio, merged)
  }

  @Test
  fun coperturaPartialMetteStremioPrimaEDeduplica() {
    val stremio = listOf(media("same"), media("stremio-only"))
    val tmdb = listOf(media("same"), media("tmdb-only"))

    val merged = mergeMobileProviderCatalog(
      stremioItems = stremio,
      tmdbItems = tmdb,
      coverage = ProviderCoverage.PARTIAL,
      fallbackToDefault = false
    )

    assertEquals(listOf("same", "stremio-only", "tmdb-only"), merged.map { it.id })
  }

  @Test
  fun conFallbackCinemetaIlCatalogoSpecificoTmdbPrecedeIlFallback() {
    val stremioFallback = listOf(media("fallback"))
    val tmdbSpecific = listOf(media("specific"))

    val merged = mergeMobileProviderCatalog(
      stremioItems = stremioFallback,
      tmdbItems = tmdbSpecific,
      coverage = ProviderCoverage.PARTIAL,
      fallbackToDefault = true
    )

    assertEquals(listOf("specific", "fallback"), merged.map { it.id })
  }

  @Test
  fun coperturaNoneUsaSoloTmdb() {
    val tmdb = listOf(media("t1"), media("t2", MediaType.SERIE_TV))

    val merged = mergeMobileProviderCatalog(
      stremioItems = emptyList(),
      tmdbItems = tmdb,
      coverage = ProviderCoverage.NONE,
      fallbackToDefault = false
    )

    assertEquals(tmdb, merged)
    assertTrue(merged.isNotEmpty())
  }
}
