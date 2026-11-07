package com.example.ui.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.repository.CatalogSection
import com.example.data.repository.HomeCatalogs
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Il cambio dello switch "Nascondi contenuti non rilasciati" deve aggiornare la Home
 * SUBITO, senza riavviare l'app e senza rilanciare la risoluzione dei cataloghi.
 *
 * La vista esposta ([StreamNovaViewModel.homeCatalogs]) è filtrata dal data layer
 * ([com.example.data.repository.UnreleasedContentFilter]); la sorgente completa
 * resta disponibile internamente, così tornare a OFF ripristina la Home originale.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HideUpcomingHomeRefreshTest {

  private lateinit var viewModel: StreamNovaViewModel

  @Before
  fun setUp() {
    AppSettingsRepository.init(ApplicationProvider.getApplicationContext())
    // Protegge il test da un valore persistito da esecuzioni precedenti.
    AppSettingsRepository.setHideUnreleasedContent(false)
    viewModel = StreamNovaViewModel()
  }

  @After
  fun tearDown() {
    AppSettingsRepository.setHideUnreleasedContent(false)
    viewModel.viewModelScope.cancel()
  }

  private fun media(id: String, type: MediaType, releaseDate: String?): MediaItem = MediaItem(
    id = id,
    title = id,
    synopsis = "",
    videoUrl = "",
    type = type,
    year = 2026,
    releaseDate = releaseDate,
    backdropUrl = "https://img.example/$id.jpg"
  )

  private fun homeWith(movies: List<MediaItem>, series: List<MediaItem>): HomeCatalogs {
    val providerSection = CatalogSection(
      id = "novita_provider",
      title = "Novità provider",
      items = movies + series
    )
    return HomeCatalogs(
      heroItems = (movies + series).take(5),
      top10Movies = movies,
      top10Series = series,
      popularMovies = movies,
      popularSeries = series,
      extraSections = listOf(providerSection),
      allMovies = movies,
      allSeries = series
    )
  }

  @Test
  fun loSwitchAggiornaLaHomeSenzaUnNuovoRefresh() {
    val futureMovie = media("film_futuro", MediaType.FILM, "2099-01-01")
    val releasedMovie = media("film_uscito", MediaType.FILM, "2020-01-01")
    val futureSeries = media("serie_futura", MediaType.SERIE_TV, "2099-05-05")
    val releasedSeries = media("serie_uscita", MediaType.SERIE_TV, "2019-03-03")

    viewModel.publishHomeCatalogs(homeWith(listOf(futureMovie, releasedMovie), listOf(futureSeries, releasedSeries)))

    // OFF: la Home è identica a prima dell'introduzione dell'opzione.
    assertEquals(listOf("film_futuro", "film_uscito"), viewModel.homeCatalogs.value.allMovies.map { it.id })
    assertEquals(listOf("serie_futura", "serie_uscita"), viewModel.homeCatalogs.value.allSeries.map { it.id })

    viewModel.setHideUnreleasedContent(true)

    // ON: l'aggiornamento è sincrono; i metadati futuri spariscono da tutte le righe.
    val hidden = viewModel.homeCatalogs.value
    assertEquals(listOf("film_uscito"), hidden.allMovies.map { it.id })
    assertEquals(listOf("serie_uscita"), hidden.allSeries.map { it.id })
    assertEquals(listOf("film_uscito", "serie_uscita"), hidden.heroItems.map { it.id })
    assertEquals(listOf("film_uscito", "serie_uscita"), hidden.extraSections.single().items.map { it.id })
    assertEquals(listOf("film_uscito"), hidden.top10Movies.map { it.id })
    assertEquals(listOf("serie_uscita"), hidden.top10Series.map { it.id })
    assertTrue(AppSettingsRepository.hideUnreleasedContent.value)

    viewModel.setHideUnreleasedContent(false)

    // OFF di nuovo: la sorgente completa era intatta, nulla è andato perso.
    assertEquals(
      listOf("film_futuro", "film_uscito"),
      viewModel.homeCatalogs.value.allMovies.map { it.id }
    )
    assertEquals(
      listOf("serie_futura", "serie_uscita"),
      viewModel.homeCatalogs.value.allSeries.map { it.id }
    )
    assertFalse(AppSettingsRepository.hideUnreleasedContent.value)
  }

  @Test
  fun unNuovoCatalogoPubblicatoConLoSwitchOnVieneFiltratoSubito() {
    viewModel.setHideUnreleasedContent(true)
    assertTrue(AppSettingsRepository.hideUnreleasedContent.value)

    val future = media("film_futuro", MediaType.FILM, "2099-12-31")
    val released = media("film_uscito", MediaType.FILM, "2021-12-31")
    viewModel.publishHomeCatalogs(homeWith(listOf(future, released), emptyList()))

    assertEquals(listOf("film_uscito"), viewModel.homeCatalogs.value.allMovies.map { it.id })
  }

  @Test
  fun laPreferenzaEspostaRifletteIlValorePersistito() {
    viewModel.setHideUnreleasedContent(true)
    assertTrue(viewModel.hideUnreleasedContent.value)
    viewModel.setHideUnreleasedContent(false)
    assertFalse(viewModel.hideUnreleasedContent.value)
  }
}
