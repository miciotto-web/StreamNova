package com.example.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della logica **pura** dell'overlay "Prossimo Episodio":
 * finestra degli ultimi 35 secondi, STATE_ENDED, chiusura esplicita e guardie su serie TV,
 * episodio successivo e sessione fallita.
 */
class NextEpisodePromptUtilsTest {

  private val hourDurationMs = 1_400_000L

  private fun shouldShow(
    positionMs: Long = 1_000L,
    durationMs: Long = hourDurationMs,
    playbackEnded: Boolean = false,
    isTvShow: Boolean = true,
    hasNextEpisode: Boolean = true,
    dismissed: Boolean = false,
    playbackFailed: Boolean = false
  ): Boolean = NextEpisodePromptUtils.shouldShow(
    positionMs = positionMs,
    durationMs = durationMs,
    playbackEnded = playbackEnded,
    isTvShow = isTvShow,
    hasNextEpisode = hasNextEpisode,
    dismissed = dismissed,
    playbackFailed = playbackFailed
  )

  @Test
  fun `non mostra overlay a inizio episodio`() {
    assertFalse(shouldShow(positionMs = 1_000L))
  }

  @Test
  fun `attiva overlay esattamente a 35 secondi dalla fine`() {
    assertTrue(shouldShow(positionMs = hourDurationMs - NextEpisodePromptUtils.PROMPT_WINDOW_MS))
  }

  @Test
  fun `attiva overlay dentro la finestra degli ultimi 35 secondi`() {
    assertTrue(shouldShow(positionMs = hourDurationMs - 1_000L))
  }

  @Test
  fun `non attiva overlay un istante prima della finestra`() {
    assertFalse(shouldShow(positionMs = hourDurationMs - NextEpisodePromptUtils.PROMPT_WINDOW_MS - 1))
  }

  @Test
  fun `attiva overlay a STATE_ENDED`() {
    assertTrue(shouldShow(playbackEnded = true))
  }

  @Test
  fun `mai overlay per un film`() {
    assertFalse(shouldShow(isTvShow = false))
    assertFalse(shouldShow(isTvShow = false, playbackEnded = true))
  }

  @Test
  fun `mai overlay senza episodio successivo`() {
    assertFalse(shouldShow(hasNextEpisode = false))
    assertFalse(shouldShow(hasNextEpisode = false, playbackEnded = true))
  }

  @Test
  fun `chiusura esplicita nasconde overlay anche a fine episodio`() {
    assertFalse(shouldShow(positionMs = hourDurationMs - 1_000L, dismissed = true))
    assertFalse(shouldShow(playbackEnded = true, dismissed = true))
  }

  @Test
  fun `sessione fallita non mostra overlay`() {
    assertFalse(shouldShow(positionMs = hourDurationMs - 1_000L, playbackFailed = true))
    assertFalse(shouldShow(playbackEnded = true, playbackFailed = true))
  }

  @Test
  fun `episodi piu brevi della finestra si attivano solo a STATE_ENDED`() {
    assertFalse(shouldShow(positionMs = 5_000L, durationMs = 20_000L))
    assertTrue(shouldShow(positionMs = 20_000L, durationMs = 20_000L, playbackEnded = true))
  }

  @Test
  fun `durata sconosciuta non attiva la finestra temporale`() {
    assertFalse(shouldShow(positionMs = 1_000L, durationMs = 0L))
  }

  @Test
  fun `countdown di dieci secondi`() {
    assertEquals(10, NextEpisodePromptUtils.COUNTDOWN_SECONDS)
  }

  @Test
  fun `finestra di attivazione di trentacinque secondi`() {
    assertEquals(35_000L, NextEpisodePromptUtils.PROMPT_WINDOW_MS)
  }
}
