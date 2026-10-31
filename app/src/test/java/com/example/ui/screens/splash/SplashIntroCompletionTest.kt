package com.example.ui.screens.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate di chiusura della splash.
 *
 * Qualunque percorso terminale (fine pausa, errore reale, fallback di avvio)
 * deve portare alla Home una sola volta: una callback duplicata non deve
 * riaprire la schermata né far ripartire la navigazione.
 */
class SplashIntroCompletionTest {

  @Test
  fun `la conclusione della sequenza chiude la splash una sola volta`() {
    var finished = 0
    val completion = SplashIntroCompletion { finished++ }

    assertFalse(completion.isFinished)

    assertTrue(completion.onPlaybackEnded())
    assertTrue(completion.isFinished)
    assertEquals(1, finished)
  }

  @Test
  fun `un errore di riproduzione porta subito alla Home`() {
    var finished = 0
    val completion = SplashIntroCompletion { finished++ }

    assertTrue(completion.onPlaybackError())
    assertTrue(completion.isFinished)
    assertEquals(1, finished)
  }

  @Test
  fun `il fallback di avvio chiude la splash`() {
    var finished = 0
    val completion = SplashIntroCompletion { finished++ }

    assertFalse(completion.isFinished)

    assertTrue(completion.onTimeout())
    assertTrue(completion.isFinished)
    assertEquals(1, finished)
  }

  @Test
  fun `un secondo evento terminale non riapre la Home`() {
    var finished = 0
    val completion = SplashIntroCompletion { finished++ }

    assertTrue(completion.onPlaybackError())
    assertFalse(completion.onTimeout())
    assertFalse(completion.onPlaybackEnded())
    assertFalse(completion.finishOnce())
    assertEquals(1, finished)
  }

  @Test
  fun `la pausa post-video dura quattro secondi`() {
    assertEquals(
      "La pausa sull'ultimo frame deve durare 4000 ms",
      4_000L,
      SPLASH_POST_VIDEO_PAUSE_MS
    )
  }

  @Test
  fun `il fallback di avvio resta separato dalla durata della pausa`() {
    assertTrue(
      "Il limite di avvio deve lasciare tempo alla decodifica sui dispositivi lenti",
      SPLASH_VIDEO_START_TIMEOUT_MS >= 6_000L
    )
    assertTrue(
      "Il limite di avvio non deve allungare inutilmente un avvio fallito",
      SPLASH_VIDEO_START_TIMEOUT_MS <= 10_000L
    )
  }
}
