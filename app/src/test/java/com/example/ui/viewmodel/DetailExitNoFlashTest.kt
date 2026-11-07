package com.example.ui.viewmodel

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SeasonEpisodesUiState
import com.example.ui.components.ProviderConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uscita dal Dettaglio senza flash (Fire TV, release 1.0.4).
 *
 * Segnalazione: con la lista episodi aperta, il BACK faceva ricomparire per una frazione
 * di secondo la lista episodi SENZA miniature, poi la Home. Causa: la scheda restava
 * composta durante la transizione di uscita del NavHost, ma `popDetail()` azzerava subito
 * `selectedMedia` e la stagione caricata: il Dettaglio si ridisegnava con gli episodi
 * ricostruiti dal catalogo (fallback) prima di sparire.
 *
 * Contratto verificato qui: durante l'uscita la scheda conserva ESATTAMENTE gli stessi
 * dati che stava mostrando; l'azzeramento avviene solo quando la destinazione ha lasciato
 * la composizione (`completeDetailExit`), cioè a transizione conclusa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailExitNoFlashTest {

  private val series = MediaItem(
    id = "tt0898266",
    title = "The Big Bang Theory",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 1418,
    year = 2007,
    durationMinutes = 22,
    seasonsCount = 12,
    lastWatchedSeason = 8,
    lastWatchedEpisode = 1,
    currentProgressMs = 9 * 60_000L,
    totalDurationMs = 22 * 60_000L
  )

  private val film = MediaItem(
    id = "tmdb_m_27205",
    title = "Inception",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 27205,
    year = 2010,
    durationMinutes = 148,
    currentProgressMs = 30 * 60_000L,
    totalDurationMs = 148 * 60_000L
  )

  private fun viewModel() = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())

  // ── Serie TV ───────────────────────────────────────────────────────────────

  @Test
  fun `BACK da una serie non azzera la scheda prima della fine della transizione`() {
    val viewModel = viewModel()
    viewModel.openDetail(series)

    val mediaPrimaDelBack = viewModel.selectedMedia.value
    val stagionePrimaDelBack = viewModel.seasonEpisodesUiState.value
    assertNotNull(mediaPrimaDelBack)
    assertTrue(
      "la stagione di ripresa deve essere in caricamento (o già caricata)",
      stagionePrimaDelBack is SeasonEpisodesUiState.Loading ||
        stagionePrimaDelBack is SeasonEpisodesUiState.Success
    )

    // BACK: la destinazione viene poppata, ma la scheda è ancora composta per la
    // transizione di uscita e deve continuare a disegnare gli stessi contenuti.
    assertFalse(viewModel.popDetail(clearDetailUiState = false))
    assertEquals(ScreenState.BROWSING, viewModel.screenState.value)
    assertEquals(
      "selectedMedia resta disponibile durante il fade",
      mediaPrimaDelBack,
      viewModel.selectedMedia.value
    )
    assertEquals(
      "la stagione caricata non viene sostituita da una lista ricostruita (senza still)",
      stagionePrimaDelBack,
      viewModel.seasonEpisodesUiState.value
    )

    // Transizione conclusa: la scheda è uscita dalla composizione, ora si può azzerare.
    viewModel.completeDetailExit()
    assertNull(viewModel.selectedMedia.value)
    assertEquals(SeasonEpisodesUiState.Idle, viewModel.seasonEpisodesUiState.value)
  }

  @Test
  fun `completeDetailExit non azzera una scheda ancora attiva`() {
    val viewModel = viewModel()
    viewModel.openDetail(series)
    val media = viewModel.selectedMedia.value

    // Es. dispose per una ricreazione della composizione, senza alcun BACK in corso.
    viewModel.completeDetailExit()

    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertEquals("nessun dato perso mentre il Dettaglio è corrente", media, viewModel.selectedMedia.value)
  }

  @Test
  fun `uscita verso il Provider conserva la scheda e azzera solo alla fine`() {
    val viewModel = viewModel()
    viewModel.openProvider(ProviderConstants.NETFLIX)
    viewModel.openDetail(series)
    val media = viewModel.selectedMedia.value

    assertFalse(viewModel.popDetail(clearDetailUiState = false))
    assertEquals(ScreenState.PROVIDER, viewModel.screenState.value)
    assertEquals(media, viewModel.selectedMedia.value)

    viewModel.completeDetailExit()
    assertNull(viewModel.selectedMedia.value)
  }

  // ── Film (nessuna regressione) ─────────────────────────────────────────────

  @Test
  fun `BACK da un film non mostra contenuti intermedi e azzera dopo l'uscita`() {
    val viewModel = viewModel()
    viewModel.openDetail(film)
    val media = viewModel.selectedMedia.value
    assertNotNull(media)
    assertEquals(SeasonEpisodesUiState.Idle, viewModel.seasonEpisodesUiState.value)

    assertFalse(viewModel.popDetail(clearDetailUiState = false))
    assertEquals(ScreenState.BROWSING, viewModel.screenState.value)
    assertEquals("la scheda film resta disegnabile durante il fade", media, viewModel.selectedMedia.value)

    viewModel.completeDetailExit()
    assertNull(viewModel.selectedMedia.value)
    assertEquals(SeasonEpisodesUiState.Idle, viewModel.seasonEpisodesUiState.value)
  }

  // ── Catena Dettaglio -> Dettaglio ─────────────────────────────────────────

  @Test
  fun `con piu schede il BACK ripristina subito la scheda precedente`() {
    val viewModel = viewModel()
    viewModel.openDetail(film)
    viewModel.openDetail(series)

    assertTrue(viewModel.popDetail(clearDetailUiState = false))
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertEquals(film.id, viewModel.selectedMedia.value?.id)
    assertEquals("focus sulla card chiusa", series.id, viewModel.chainRestoreMediaId.value)
  }

  // ── Comportamento storico esplicito ────────────────────────────────────────

  @Test
  fun `backFromDetail senza flag mantiene l'azzeramento immediato`() {
    val viewModel = viewModel()
    viewModel.openDetail(series)

    viewModel.backFromDetail()

    assertEquals(ScreenState.BROWSING, viewModel.screenState.value)
    assertNull(viewModel.selectedMedia.value)
    assertEquals(SeasonEpisodesUiState.Idle, viewModel.seasonEpisodesUiState.value)
  }
}
