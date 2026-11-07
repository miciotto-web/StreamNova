package com.example.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della politica PURA dell'overlay in pausa (impostazione "Overlay in pausa"):
 * timer da 5 secondi, annullamento alla ripresa, nessun avvio durante buffering,
 * errore, fine riproduzione o scrubbing.
 */
class PauseOverlayPolicyTest {

  /** Caso tipico: contenuto pronto e in pausa, tutto il resto tranquillo. */
  private fun pausedScene(
    enabled: Boolean = true,
    isPaused: Boolean = true,
    isPlayerReady: Boolean = true,
    isBuffering: Boolean = false,
    hasPlaybackError: Boolean = false,
    playbackEnded: Boolean = false,
    isScrubbing: Boolean = false
  ): Boolean = PauseOverlayPolicy.shouldStartTimer(
    enabled = enabled,
    isPaused = isPaused,
    isPlayerReady = isPlayerReady,
    isBuffering = isBuffering,
    hasPlaybackError = hasPlaybackError,
    playbackEnded = playbackEnded,
    isScrubbing = isScrubbing
  )

  @Test
  fun `la pausa su contenuto pronto avvia il timer`() {
    assertTrue(pausedScene())
  }

  @Test
  fun `impostazione disattivata non avvia mai il timer`() {
    assertFalse(pausedScene(enabled = false))
  }

  @Test
  fun `la ripresa annulla il timer`() {
    assertFalse(pausedScene(isPaused = false))
  }

  @Test
  fun `buffering o player non pronto non avviano il timer`() {
    assertFalse(pausedScene(isBuffering = true))
    assertFalse(pausedScene(isPlayerReady = false))
  }

  @Test
  fun `errore fine riproduzione o scrubbing non avviano il timer`() {
    assertFalse(pausedScene(hasPlaybackError = true))
    assertFalse(pausedScene(playbackEnded = true))
    assertFalse(pausedScene(isScrubbing = true))
  }

  @Test
  fun `il ritardo di comparsa e di 5 secondi`() {
    assertEquals(5_000L, PauseOverlayPolicy.REVEAL_DELAY_MS)
  }
}
