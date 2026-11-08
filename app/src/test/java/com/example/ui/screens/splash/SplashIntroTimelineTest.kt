package com.example.ui.screens.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sequenza obbligatoria della splash: video integrale → pausa di 4 s
 * sull'ultimo frame → Home.
 *
 * I test verificano che la Home non compaia mai durante la riproduzione né
 * durante la pausa, che la pausa parta una sola volta dopo `STATE_ENDED` e che
 * il tempo trascorso in background non venga conteggiato.
 *
 * Orologio finto: nessun decoder, nessun Robolectric, nessuna attesa reale.
 */
class SplashIntroTimelineTest {

  private var nowMs = 10_000L
  private var finishes = 0

  private fun newTimeline(foreground: Boolean = true): SplashIntroTimeline =
    SplashIntroTimeline(
      postVideoPauseMs = SPLASH_POST_VIDEO_PAUSE_MS,
      foreground = foreground,
      now = { nowMs },
      onFinished = { finishes++ }
    )

  private fun advance(ms: Long) {
    nowMs += ms
  }

  @Test
  fun `la fine del video non apre la Home ma avvia la pausa`() {
    val timeline = newTimeline()
    timeline.onFirstFrameRendered()
    advance(5_040)
    timeline.onPlaybackEnded()

    assertFalse("La Home non può comparire alla fine del video", timeline.isFinished)
    assertEquals(0, finishes)
    assertEquals(SPLASH_POST_VIDEO_PAUSE_MS, timeline.remainingPostVideoPauseMs())
  }

  @Test
  fun `la Home compare solo dopo quattro secondi dalla fine del video`() {
    val timeline = newTimeline()
    timeline.onFirstFrameRendered()
    advance(5_040)
    timeline.onPlaybackEnded()

    advance(3_999)
    timeline.onPostVideoPauseElapsed()
    assertFalse("Manca ancora 1 ms alla fine della pausa", timeline.isFinished)

    advance(1)
    timeline.onPostVideoPauseElapsed()
    assertTrue(timeline.isFinished)
    assertEquals(1, finishes)
  }

  @Test
  fun `il timer della pausa non parte durante la riproduzione`() {
    val timeline = newTimeline()
    timeline.onFirstFrameRendered()
    advance(3_000)

    timeline.onPostVideoPauseElapsed()

    assertFalse("Senza STATE_ENDED non esiste alcuna pausa", timeline.isFinished)
    assertEquals(0, timeline.postVideoPauseElapsedMs())
  }

  @Test
  fun `callback duplicate di fine video non azzerano la pausa`() {
    val timeline = newTimeline()
    timeline.onFirstFrameRendered()
    advance(5_000)
    timeline.onPlaybackEnded()

    advance(2_000)
    timeline.onPlaybackEnded()
    advance(1_999)
    timeline.onPostVideoPauseElapsed()
    assertFalse("Il duplicato non deve far ripartire i 4 s", timeline.isFinished)

    advance(1)
    timeline.onPostVideoPauseElapsed()
    assertTrue(timeline.isFinished)
  }

  @Test
  fun `il tempo in background non conta nella pausa`() {
    val timeline = newTimeline()
    timeline.onFirstFrameRendered()
    advance(5_000)
    timeline.onPlaybackEnded()
    advance(1_000)

    timeline.setForeground(false)
    advance(60_000)
    assertEquals(1_000, timeline.postVideoPauseElapsedMs())
    assertEquals(3_000, timeline.remainingPostVideoPauseMs())

    timeline.setForeground(true)
    advance(2_999)
    timeline.onPostVideoPauseElapsed()
    assertFalse("Il rientro non deve saltare il tempo mancante", timeline.isFinished)

    advance(1)
    timeline.onPostVideoPauseElapsed()
    assertTrue(timeline.isFinished)
  }

  @Test
  fun `la pausa parte al rientro se il video finisce in background`() {
    val timeline = newTimeline(foreground = false)
    timeline.onFirstFrameRendered()
    advance(5_000)
    timeline.onPlaybackEnded()
    advance(10_000)
    assertEquals("Senza visibilità la pausa non consuma tempo", 0, timeline.postVideoPauseElapsedMs())

    timeline.setForeground(true)
    advance(1_000)
    assertEquals(1_000, timeline.postVideoPauseElapsedMs())
    assertEquals(3_000, timeline.remainingPostVideoPauseMs())
  }

  @Test
  fun `un errore del player apre subito la Home`() {
    val timeline = newTimeline()

    timeline.onPlaybackError()

    assertTrue("L'errore reale non deve attendere la pausa", timeline.isFinished)
    assertEquals(1, finishes)
  }

  @Test
  fun `senza primo frame il fallback di avvio apre la Home`() {
    val timeline = newTimeline()

    timeline.onNoFirstFrameTimeout()

    assertTrue("Video non avviabile: la Home non deve attendere", timeline.isFinished)
    assertEquals(1, finishes)
  }

  @Test
  fun `il fallback di avvio non interrompe un video già avviato`() {
    val timeline = newTimeline()
    timeline.onFirstFrameRendered()
    advance(1_000)

    timeline.onNoFirstFrameTimeout()

    assertFalse("Un video che mostra frame non deve essere tagliato", timeline.isFinished)
  }

  @Test
  fun `eventi terminali successivi non riaprono la Home`() {
    val timeline = newTimeline()
    timeline.onFirstFrameRendered()
    advance(5_000)
    timeline.onPlaybackEnded()
    advance(4_000)
    timeline.onPostVideoPauseElapsed()
    assertEquals(1, finishes)

    timeline.onPlaybackError()
    timeline.onNoFirstFrameTimeout()
    timeline.onPostVideoPauseElapsed()

    assertEquals("La transizione verso la Home avviene una sola volta", 1, finishes)
  }

  @Test
  fun `la durata della riproduzione è esposta per i log`() {
    val timeline = newTimeline()
    timeline.onPlaybackStarted()
    advance(5_041)
    timeline.onPlaybackEnded()

    assertEquals(5_041L, timeline.videoPlaybackDurationMs())
    assertEquals(
      "La pausa parte dall'istante di STATE_ENDED",
      nowMs,
      timeline.videoEndedAtMs
    )
    assertEquals(
      "La durata si misura dall'avvio della riproduzione",
      nowMs - 5_041,
      timeline.playbackStartedAtMs
    )
  }
}
