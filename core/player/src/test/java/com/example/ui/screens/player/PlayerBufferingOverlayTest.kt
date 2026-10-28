package com.example.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Isteresi dell'overlay "Caricamento" del player.
 *
 * ExoPlayer entra in STATE_BUFFERING anche per transizioni brevissime che non fermano il
 * video (riconfigurazione del decoder a ogni cambio di rendition, istantanea di un seek):
 * mostrare l'overlay per ognuna di esse fa lampeggiare lo spinner a ripetizione. La
 * decisione qui sotto dice quando l'overlay *merita* di apparire; il ritardo
 * [PlayerBufferingOverlay.REVEAL_DELAY_MS] è applicato dall'effetto Compose in
 * `PlayerScreen`.
 *
 * NOTA: è un test della funzione di decisione, non del sistema Compose reale (stesso
 * approccio di PlayerVideoFocusTargetTest per il focus del player).
 */
class PlayerBufferingOverlayTest {

  @Test
  fun unCaricamentoNormaleMeritaLOverlay() {
    assertTrue(
      PlayerBufferingOverlay.isCandidate(
        isBuffering = true,
        isScrubbing = false,
        hasErrorMessage = false
      )
    )
  }

  @Test
  fun senzaCaricamentoNessunOverlay() {
    assertFalse(
      PlayerBufferingOverlay.isCandidate(
        isBuffering = false,
        isScrubbing = false,
        hasErrorMessage = false
      )
    )
  }

  @Test
  fun duranteLoScrubbingLOverlayNonCompare() {
    // A video resta solo l'anteprima della posizione.
    assertFalse(
      PlayerBufferingOverlay.isCandidate(
        isBuffering = true,
        isScrubbing = true,
        hasErrorMessage = false
      )
    )
  }

  @Test
  fun conUnErroreVisibileComandaLOverlayDiErrore() {
    assertFalse(
      PlayerBufferingOverlay.isCandidate(
        isBuffering = true,
        isScrubbing = false,
        hasErrorMessage = true
      )
    )
  }

  @Test
  fun ilRitardoDiRivelazioneAssorbeGliStalliBrevissimi() {
    // Deve essere abbastanza lungo da coprire i cambi di rendition (centinaia di ms nei
    // log di device) e abbastanza corto da non far sembrare lento uno stallo reale.
    assertTrue(PlayerBufferingOverlay.REVEAL_DELAY_MS in 300L..1_500L)
  }
}
