package com.example.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test della risoluzione PURA della fase di caricamento reale (impostazione "Mostra
 * stato di caricamento"): ogni fase deriva solo da eventi reali di ExoPlayer e del
 * resolver delle sorgenti; nessuna percentuale o tempo simulato.
 */
class PlayerLoadingStatusTest {

  @Test
  fun `senza url la fase e preparazione della sorgente`() {
    // Resolver al lavoro (Loading) o attesa di una sorgente: stessa fase reale.
    assertEquals(
      PlayerLoadingPhase.PREPARING_SOURCE,
      PlayerLoadingStatus.resolvePhase(
        hasPlaybackError = false,
        hasSourceUrl = false,
        isPlayerReady = false,
        isBuffering = true
      )
    )
  }

  @Test
  fun `url pronto e player non ancora in buffering significa connessione`() {
    // Media item impostato ma il transport non è ancora entrato in STATE_BUFFERING.
    assertEquals(
      PlayerLoadingPhase.CONNECTING,
      PlayerLoadingStatus.resolvePhase(
        hasPlaybackError = false,
        hasSourceUrl = true,
        isPlayerReady = false,
        isBuffering = false
      )
    )
  }

  @Test
  fun `primo caricamento con buffering significa caricamento del contenuto`() {
    assertEquals(
      PlayerLoadingPhase.LOADING_CONTENT,
      PlayerLoadingStatus.resolvePhase(
        hasPlaybackError = false,
        hasSourceUrl = true,
        isPlayerReady = false,
        isBuffering = true
      )
    )
  }

  @Test
  fun `buffering dopo il primo ready e la fase buffering`() {
    assertEquals(
      PlayerLoadingPhase.BUFFERING,
      PlayerLoadingStatus.resolvePhase(
        hasPlaybackError = false,
        hasSourceUrl = true,
        isPlayerReady = true,
        isBuffering = true
      )
    )
  }

  @Test
  fun `player pronto senza buffering e pronto per la riproduzione`() {
    assertEquals(
      PlayerLoadingPhase.READY,
      PlayerLoadingStatus.resolvePhase(
        hasPlaybackError = false,
        hasSourceUrl = true,
        isPlayerReady = true,
        isBuffering = false
      )
    )
  }

  @Test
  fun `con errore attivo la fase non e usata per lo stato di caricamento`() {
    // L'errore ha il suo overlay dedicato: la fase è neutra (READY) e non mostrata.
    assertEquals(
      PlayerLoadingPhase.READY,
      PlayerLoadingStatus.resolvePhase(
        hasPlaybackError = true,
        hasSourceUrl = false,
        isPlayerReady = false,
        isBuffering = true
      )
    )
  }
}
