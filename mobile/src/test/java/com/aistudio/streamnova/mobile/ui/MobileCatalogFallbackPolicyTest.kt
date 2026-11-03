package com.aistudio.streamnova.mobile.ui

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileCatalogFallbackPolicyTest {

  @Test
  fun fallbackAttivoSoloQuandoIlToggleEOnEGliAddonSonoVuoti() {
    assertTrue(MobileCatalogFallbackPolicy.shouldUseTmdbFallback(enabled = true, addonContentAvailable = false))
    assertFalse(MobileCatalogFallbackPolicy.shouldUseTmdbFallback(enabled = false, addonContentAvailable = false))
    assertFalse(MobileCatalogFallbackPolicy.shouldUseTmdbFallback(enabled = true, addonContentAvailable = true))
  }

  @Test
  fun ilFallbackRisultaApplicatoSoloConElementiTmdbReali() {
    assertTrue(
      MobileCatalogFallbackPolicy.isFallbackApplied(
        enabled = true,
        addonContentAvailable = false,
        tmdbItemCount = 12
      )
    )
    assertFalse(
      MobileCatalogFallbackPolicy.isFallbackApplied(
        enabled = true,
        addonContentAvailable = false,
        tmdbItemCount = 0
      )
    )
    assertFalse(
      MobileCatalogFallbackPolicy.isFallbackApplied(
        enabled = false,
        addonContentAvailable = false,
        tmdbItemCount = 12
      )
    )
  }

  @Test
  fun conAddonVuotiEToggleOnIlCatalogoFilmUsaTmdb() {
    val tmdbMovie = movie("tmdb_m_1")
    val state = buildMobileCatalogUiState(
      home = HomeCatalogs(allMovies = listOf(tmdbMovie)),
      continueWatching = emptyList(),
      favorites = emptyList(),
      isLoading = false,
      isRefreshing = false,
      error = null,
      parentalEnabled = false,
      parentalLevel = "18",
      tmdbFallbackEnabled = true
    )
    assertEquals(listOf(tmdbMovie), state.movies)
    assertTrue(state.moviesTmdbFallback)
  }

  @Test
  fun conAddonVuotiEToggleOffIlCatalogoFilmRestaVuoto() {
    val tmdbMovie = movie("tmdb_m_1")
    val state = buildMobileCatalogUiState(
      home = HomeCatalogs(allMovies = listOf(tmdbMovie)),
      continueWatching = emptyList(),
      favorites = emptyList(),
      isLoading = false,
      isRefreshing = false,
      error = null,
      parentalEnabled = false,
      parentalLevel = "18",
      tmdbFallbackEnabled = false
    )
    assertEquals(emptyList<MediaItem>(), state.movies)
    assertFalse(state.moviesTmdbFallback)
  }

  @Test
  fun conAddonPresentiTmdbNonSostituisceNeDuplicaIlCatalogo() {
    val addonMovie = movie("stremio_m_1")
    val state = buildMobileCatalogUiState(
      home = HomeCatalogs(
        hasCustomCatalogMovies = true,
        allMovies = listOf(addonMovie)
      ),
      continueWatching = emptyList(),
      favorites = emptyList(),
      isLoading = false,
      isRefreshing = false,
      error = null,
      parentalEnabled = false,
      parentalLevel = "18",
      tmdbFallbackEnabled = true
    )
    assertEquals(listOf(addonMovie), state.movies)
    assertFalse(state.moviesTmdbFallback)
  }

  @Test
  fun ilFallbackSerieSegueLeStesseRegole() {
    val tmdbSeries = movie("tmdb_tv_1").copy(type = MediaType.SERIE_TV)
    val fallbackOn = buildMobileCatalogUiState(
      home = HomeCatalogs(allSeries = listOf(tmdbSeries)),
      continueWatching = emptyList(),
      favorites = emptyList(),
      isLoading = false,
      isRefreshing = false,
      error = null,
      parentalEnabled = false,
      parentalLevel = "18",
      tmdbFallbackEnabled = true
    )
    val fallbackOff = buildMobileCatalogUiState(
      home = HomeCatalogs(allSeries = listOf(tmdbSeries)),
      continueWatching = emptyList(),
      favorites = emptyList(),
      isLoading = false,
      isRefreshing = false,
      error = null,
      parentalEnabled = false,
      parentalLevel = "18",
      tmdbFallbackEnabled = false
    )
    assertEquals(listOf(tmdbSeries), fallbackOn.series)
    assertTrue(fallbackOn.seriesTmdbFallback)
    assertTrue(fallbackOff.series.isEmpty())
    assertFalse(fallbackOff.seriesTmdbFallback)
  }

  private fun movie(id: String): MediaItem = MediaItem(
    id = id,
    title = "Titolo",
    synopsis = "Sinossi",
    videoUrl = ""
  )
}
