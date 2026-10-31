package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Contratto del segnale di chiusura del trailer interno
 * ([StreamNovaViewModel.trailerDismissedToken]).
 *
 * Il trailer è un overlay globale composto sopra il NavHost: alla sua chiusura il
 * Dettaglio sottostante deve riprendere il focus su «Riproduci». Il segnale che lo
 * autorizza è questo token, che:
 *
 * 1. avanza SOLO quando un trailer era davvero aperto (`Loading` o `Playing`), così una
 *    chiusura a vuoto non strappa il focus alla scheda;
 * 2. avanza anche chiudendo l'overlay di ERRORE, che è comunque un ritorno alla scheda;
 * 3. è INDIPENDENTE dal token del selettore delle sorgenti (Fase 4): i due flussi di
 *    ripristino non si consumano a vicenda.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrailerDismissTokenTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun film() = MediaItem(
    id = "tt0117731",
    imdbId = "tt0117731",
    title = "Toy Story",
    synopsis = "Film animato",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 862
  )

  private fun viewModel() =
    StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))

  @Test
  fun playTrailerNonAvanzaIlToken() {
    val viewModel = viewModel()

    viewModel.playTrailer(film())

    assertEquals(TrailerPlaybackState.Loading, viewModel.trailerState.value)
    assertEquals("L'apertura del trailer non è una chiusura", 0, viewModel.trailerDismissedToken.value)
  }

  @Test
  fun laChiusuraDelTrailerAvanzaIlTokenUnaVolta() {
    val viewModel = viewModel()

    viewModel.playTrailer(film())
    viewModel.closeTrailer()

    assertEquals(TrailerPlaybackState.Idle, viewModel.trailerState.value)
    assertEquals(1, viewModel.trailerDismissedToken.value)

    // Chiusure a vuoto successive: lo stato resta Idle e il token non avanza più, così
    // nessun segnale spurio può strappare il focus alla scheda.
    viewModel.closeTrailer()
    assertEquals(TrailerPlaybackState.Idle, viewModel.trailerState.value)
    assertEquals(1, viewModel.trailerDismissedToken.value)
  }

  @Test
  fun laChiusuraDelTrailerNonDisponibileAvanzaIlToken() {
    val viewModel = viewModel()

    // Nessun media: l'overlay passa subito a Error, che è comunque un overlay del
    // trailer: chiuderlo è un ritorno alla scheda e deve ripristinare il focus.
    viewModel.playTrailer(null)
    assertTrue(viewModel.trailerState.value is TrailerPlaybackState.Error)

    viewModel.closeTrailer()

    assertEquals(TrailerPlaybackState.Idle, viewModel.trailerState.value)
    assertEquals(1, viewModel.trailerDismissedToken.value)
  }

  @Test
  fun iTokenDelTrailerEDelleSorgentiSonoIndipendenti() {
    val viewModel = viewModel()

    viewModel.playTrailer(film())
    viewModel.closeTrailer()
    assertEquals(1, viewModel.trailerDismissedToken.value)
    assertEquals(
      "La chiusura del trailer non deve toccare il canale delle sorgenti",
      0,
      viewModel.sourceDialogDismissedToken.value
    )

    viewModel.dismissSourceDialog()
    assertEquals(1, viewModel.sourceDialogDismissedToken.value)
    assertEquals(
      "La chiusura del selettore sorgenti non deve toccare il canale del trailer",
      1,
      viewModel.trailerDismissedToken.value
    )
  }
}
