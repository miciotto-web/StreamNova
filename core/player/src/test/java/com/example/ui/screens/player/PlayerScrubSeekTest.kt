package com.example.ui.screens.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della logica pura dello scrubbing: mappatura dei tasti, limiti del contenuto e
 * stato della sessione di anteprima (primo passo, passi successivi, cambio di verso,
 * delta rispetto al punto di partenza).
 */
class PlayerScrubSeekTest {

  private val oneHourMs = 3_600_000L

  private fun session(
    originPositionMs: Long = 0L,
    direction: Int = PlayerScrub.FORWARD,
    durationMs: Long = oneHourMs,
    wasPlaying: Boolean = true
  ) = PlayerScrubSession(
    originPositionMs = originPositionMs,
    durationMs = durationMs,
    wasPlaying = wasPlaying,
    startDirection = direction
  )

  // ---------------------------------------------------------------------------
  // Tasti e direzione
  // ---------------------------------------------------------------------------

  @Test
  fun `freccia destra scrubbing avanti e freccia sinistra scrubbing indietro`() {
    assertEquals(PlayerScrub.FORWARD, PlayerScrub.directionForKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
    assertEquals(PlayerScrub.REWIND, PlayerScrub.directionForKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
  }

  @Test
  fun `gli altri tasti non governano lo scrubbing`() {
    assertEquals(0, PlayerScrub.directionForKeyCode(KeyEvent.KEYCODE_DPAD_UP))
    assertEquals(0, PlayerScrub.directionForKeyCode(KeyEvent.KEYCODE_DPAD_DOWN))
    assertEquals(0, PlayerScrub.directionForKeyCode(KeyEvent.KEYCODE_DPAD_CENTER))
    assertEquals(0, PlayerScrub.directionForKeyCode(KeyEvent.KEYCODE_ENTER))
    assertEquals(0, PlayerScrub.directionForKeyCode(KeyEvent.KEYCODE_BACK))
    assertEquals(0, PlayerScrub.directionForKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
  }

  // ---------------------------------------------------------------------------
  // Limiti del contenuto
  // ---------------------------------------------------------------------------

  @Test
  fun `la posizione non esce mai dal contenuto quando la durata e nota`() {
    assertEquals(0L, PlayerScrub.clampPosition(-5_000L, oneHourMs))
    assertEquals(oneHourMs, PlayerScrub.clampPosition(oneHourMs + 30_000L, oneHourMs))
    assertEquals(1_000L, PlayerScrub.clampPosition(1_000L, oneHourMs))
  }

  @Test
  fun `senza durata nota resta solo il limite inferiore`() {
    assertEquals(0L, PlayerScrub.clampPosition(-5_000L, 0L))
    assertEquals(4_000L, PlayerScrub.clampPosition(4_000L, 0L))
    assertEquals(4_000L, PlayerScrub.clampPosition(4_000L, -1L))
  }

  // ---------------------------------------------------------------------------
  // Primo passo dell'anteprima
  // ---------------------------------------------------------------------------

  @Test
  fun `la pressione che apre lo scrubbing vale dieci secondi in avanti`() {
    val s = session(originPositionMs = 100_000L)

    assertEquals("il punto di partenza resta quello del player", 100_000L, s.originPositionMs)
    assertEquals(110_000L, s.positionMs)
    assertEquals(10_000L, s.deltaMs)
    assertEquals(PlayerScrub.FORWARD, s.direction)
    assertTrue(s.wasPlaying)
  }

  @Test
  fun `la pressione che apre lo scrubbing vale dieci secondi indietro`() {
    val s = session(originPositionMs = 100_000L, direction = PlayerScrub.REWIND)

    assertEquals(90_000L, s.positionMs)
    assertEquals(-10_000L, s.deltaMs)
  }

  @Test
  fun `la prima pressione si ferma ai limiti del contenuto`() {
    assertEquals(
      oneHourMs,
      session(originPositionMs = oneHourMs - 3_000L).positionMs
    )
    assertEquals(
      0L,
      session(originPositionMs = 4_000L, direction = PlayerScrub.REWIND).positionMs
    )
  }

  @Test
  fun `con durata ignota la posizione di anteprima non va sotto zero`() {
    assertEquals(0L, session(originPositionMs = 0L, direction = PlayerScrub.REWIND, durationMs = 0L).positionMs)
    assertEquals(10_000L, session(originPositionMs = 0L, durationMs = 0L).positionMs)
  }

  // ---------------------------------------------------------------------------
  // Passi successivi e cambio di verso
  // ---------------------------------------------------------------------------

  @Test
  fun `ogni pressione successiva somma un passo di anteprima`() {
    val s = session(originPositionMs = 0L)

    s.step(PlayerScrub.FORWARD)
    assertEquals(20_000L, s.positionMs)
    s.step(PlayerScrub.FORWARD)
    assertEquals(30_000L, s.positionMs)
    s.step(PlayerScrub.FORWARD)
    assertEquals(40_000L, s.positionMs)
    assertEquals(40_000L, s.deltaMs)
  }

  @Test
  fun `il verso opposto riparte dalla posizione di anteprima raggiunta`() {
    val s = session(originPositionMs = 100_000L)

    s.step(PlayerScrub.FORWARD)
    assertEquals(120_000L, s.positionMs)
    s.step(PlayerScrub.REWIND)
    assertEquals(110_000L, s.positionMs)
    assertEquals(PlayerScrub.REWIND, s.direction)

    // Tornare al punto di partenza è possibile anche superandolo: il delta cambia segno.
    s.step(PlayerScrub.REWIND)
    assertEquals(100_000L, s.positionMs)
    assertEquals(0L, s.deltaMs)
    s.step(PlayerScrub.REWIND)
    assertEquals(90_000L, s.positionMs)
    assertEquals(-10_000L, s.deltaMs)
  }

  @Test
  fun `ai limiti del contenuto la posizione di anteprima si ferma e non accumula passi`() {
    val s = session(originPositionMs = oneHourMs - 15_000L)

    s.step(PlayerScrub.FORWARD)
    assertEquals(oneHourMs, s.positionMs)
    s.step(PlayerScrub.FORWARD)
    s.step(PlayerScrub.FORWARD)
    assertEquals("oltre la durata l'anteprima non si sposta", oneHourMs, s.positionMs)
    assertEquals(15_000L, s.deltaMs)
  }

  @Test
  fun `all inizio del contenuto la posizione di anteprima si ferma`() {
    val s = session(originPositionMs = 5_000L, direction = PlayerScrub.REWIND)

    assertEquals(0L, s.positionMs)
    s.step(PlayerScrub.REWIND)
    assertEquals(0L, s.positionMs)
    assertEquals(-5_000L, s.deltaMs)
  }

  // ---------------------------------------------------------------------------
  // Stato di riproduzione da ripristinare
  // ---------------------------------------------------------------------------

  @Test
  fun `la sessione ricorda se la riproduzione era attiva`() {
    assertTrue(session(wasPlaying = true).wasPlaying)
    assertFalse(session(wasPlaying = false).wasPlaying)
  }

  // ---------------------------------------------------------------------------
  // Stato pubblicato all'interfaccia
  // ---------------------------------------------------------------------------

  @Test
  fun `lo stato di anteprima riporta posizione durata e delta`() {
    val state = PlayerScrubUiState(
      positionMs = 130_000L,
      durationMs = oneHourMs,
      originPositionMs = 100_000L,
      wasPlayingBeforeScrub = true
    )

    assertEquals(30_000L, state.deltaMs)
    assertEquals(-30_000L, state.copy(positionMs = 70_000L).deltaMs)
  }
}
