package com.example.ui.viewmodel

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.ProviderConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Test

/**
 * Regressione del bug di Back nella catena «Altro come questo».
 *
 * Segnalazione sulla Fire TV: aprendo un dettaglio dai Consigliati, il primo Back non
 * riportava al contesto «Altro come questo» e il secondo saltava direttamente alla Home.
 * Il contratto dello stack dei dettagli deve essere:
 *
 *   Dettaglio originale -> Altro come questo -> Dettaglio consigliato
 *     -> Back -> Altro come questo (scheda originale, target focus = card cliccata)
 *     -> Back -> Dettaglio originale
 *     -> Back -> Home (catena esaurita, nessun target pendente)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailChainBackTest {

  private val original = MediaItem(
    id = "tmdb_movie_101",
    title = "Film Originale",
    synopsis = "Sinossi del film originale",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 101
  )

  private val recommended = MediaItem(
    id = "tmdb_movie_102",
    title = "Film Consigliato",
    synopsis = "Sinossi del film consigliato",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 102
  )

  private val third = MediaItem(
    id = "tmdb_movie_103",
    title = "Film Consegnato dalla catena",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 103
  )

  @Before
  fun chainTargetStartsEmpty() {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    assertNull(viewModel.chainRestoreMediaId.value)
  }

  @Test
  fun backDaiConsigliatiRitornaAllaSchedaOriginaleConTargetSullaCardChiusa() {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())

    // 1) Home -> Dettaglio originale
    viewModel.openDetail(original)
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertEquals(original.id, viewModel.selectedMedia.value?.id)
    assertNull(viewModel.chainRestoreMediaId.value)

    // 2) Scheda originale, tab «Altro come questo»: click sul consiglio
    viewModel.openDetail(recommended)
    assertEquals(recommended.id, viewModel.selectedMedia.value?.id)
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)

    // 3) Back dal dettaglio consigliato -> contesto «Altro come questo» della scheda
    //    originale, con target di focus sulla card appena chiusa.
    assertTrue(viewModel.popDetail())
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertEquals(original.id, viewModel.selectedMedia.value?.id)
    assertEquals(recommended.id, viewModel.chainRestoreMediaId.value)

    // Consumatore UI: il focus è tornato sulla card -> azzera il target.
    viewModel.clearChainRestoreMedia()
    assertNull(viewModel.chainRestoreMediaId.value)
  }

  @Test
  fun catenaLungaOgniBackAggiornaIlTargetAllaSchedaRientrante() {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    viewModel.openDetail(original)
    viewModel.openDetail(recommended)
    viewModel.openDetail(third)

    // Back da terza -> seconda: il focus va sulla card terza nella scheda seconda.
    assertTrue(viewModel.popDetail())
    assertEquals(recommended.id, viewModel.selectedMedia.value?.id)
    assertEquals(third.id, viewModel.chainRestoreMediaId.value)

    // Back da seconda -> prima (il consumo del target è responsabilità della UI).
    assertTrue(viewModel.popDetail())
    assertEquals(original.id, viewModel.selectedMedia.value?.id)
    assertEquals(recommended.id, viewModel.chainRestoreMediaId.value)
  }

  @Test
  fun backDaDettaglioApertoDaHomeRitornaInBrowsingSenzaTargetPendente() {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    viewModel.openDetail(original)

    assertFalse(viewModel.popDetail())
    assertEquals(ScreenState.BROWSING, viewModel.screenState.value)
    assertNull(viewModel.selectedMedia.value)
    assertNull(viewModel.chainRestoreMediaId.value)
  }

  @Test
  fun backDaDettaglioApertoDaSezioneCatalogoRitornaAllaSezione() {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    viewModel.openProvider(ProviderConstants.NETFLIX)
    viewModel.openDetail(original)

    assertFalse(viewModel.popDetail())
    assertEquals(ScreenState.PROVIDER, viewModel.screenState.value)
    assertNull(viewModel.chainRestoreMediaId.value)
  }

  @Test
  fun nuovaCatenaDaBrowsingAzzeraIlTargetEreditato() {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    viewModel.openDetail(original)
    viewModel.openDetail(recommended)
    assertTrue(viewModel.popDetail())
    assertEquals(recommended.id, viewModel.chainRestoreMediaId.value)

    // Uscita regolare a Browsing (es. backToBrowsing dopo una riproduzione)...
    viewModel.backToBrowsing()
    assertNull(viewModel.chainRestoreMediaId.value)

    // ...e una nuova apertura NON deve ereditare il target della vecchia catena:
    // altrimenti una card qualsiasi dei Consigliati receberrebbe un focus non richiesto.
    viewModel.openDetail(third)
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertNull(viewModel.chainRestoreMediaId.value)
  }
}
