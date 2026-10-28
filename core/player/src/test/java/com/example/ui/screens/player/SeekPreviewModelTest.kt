package com.example.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della logica pura dell'anteprima: timestamp della posizione proposta, posizionamento
 * dell'overlay dentro i bordi dello schermo e guardie su durata/live.
 *
 * Nessun player e nessuna rete: sono le sole decisioni che il componente Compose
 * `SeekPreview` non deve ricalcolare da solo.
 */
class SeekPreviewModelTest {

  private val oneHourMs = 3_600_000L
  private val tenMinutesMs = 600_000L

  // ---------------------------------------------------------------------------
  // Timestamp della posizione selezionata
  // ---------------------------------------------------------------------------

  @Test
  fun `il timestamp e quello della posizione proposta non del player`() {
    val state = PlayerScrubUiState(
      positionMs = 130_000L,
      durationMs = tenMinutesMs,
      originPositionMs = 100_000L,
      wasPlayingBeforeScrub = true
    )

    assertEquals("2:10 / 10:00", SeekPreviewModel.formatTimestamp(state.positionMs, state.durationMs))
    assertEquals(130_000L, state.positionMs)
  }

  @Test
  fun `sotto l ora il tempo e compatto a due cifre`() {
    assertEquals("0:00", SeekPreviewModel.formatTimestamp(0L, tenMinutesMs).substringBefore(" /"))
    assertEquals("0:07", SeekPreviewModel.formatTimestamp(7_000L, tenMinutesMs).substringBefore(" /"))
    assertEquals("12:45", SeekPreviewModel.formatTimestamp(765_000L, tenMinutesMs).substringBefore(" /"))
  }

  @Test
  fun `sopra l ora compaiono ore minuti e secondi`() {
    assertEquals("1:02:03", SeekPreviewModel.formatClock(3_723_000L))
    assertEquals("1:02:03 / 2:00:00", SeekPreviewModel.formatTimestamp(3_723_000L, 7_200_000L))
  }

  @Test
  fun `durata ignota mostra un trattino invece di un tempo impossibile`() {
    assertEquals("1:40 / --:--", SeekPreviewModel.formatTimestamp(100_000L, 0L))
    assertEquals("1:40 / --:--", SeekPreviewModel.formatTimestamp(100_000L, -1L))
  }

  @Test
  fun `posizioni negative non producono tempi negativi`() {
    assertEquals("0:00 / 10:00", SeekPreviewModel.formatTimestamp(-5_000L, tenMinutesMs))
    assertEquals("0:00", SeekPreviewModel.formatClock(-42L))
  }

  @Test
  fun `i millisecondi non vengono arrotondati in avanti`() {
    assertEquals("0:09", SeekPreviewModel.formatClock(9_999L))
  }

  // ---------------------------------------------------------------------------
  // Posizionamento dell'overlay
  // ---------------------------------------------------------------------------

  @Test
  fun `la frazione segue la posizione sulla timeline`() {
    assertEquals(0f, SeekPreviewModel.timelineFraction(0L, tenMinutesMs))
    assertEquals(0.5f, SeekPreviewModel.timelineFraction(300_000L, tenMinutesMs))
    assertEquals(1f, SeekPreviewModel.timelineFraction(tenMinutesMs, tenMinutesMs))
  }

  @Test
  fun `con durata ignota la frazione e zero`() {
    assertEquals(0f, SeekPreviewModel.timelineFraction(120_000L, 0L))
    assertEquals(0f, SeekPreviewModel.timelineFraction(120_000L, -3L))
  }

  @Test
  fun `l overlay non esce mai dai bordi dello schermo`() {
    val width = 0.30f

    val start = SeekPreviewModel.clampedCenterFraction(
      preferredCenterFraction = 0f,
      previewWidthFraction = width
    )
    val end = SeekPreviewModel.clampedCenterFraction(
      preferredCenterFraction = 1f,
      previewWidthFraction = width
    )

    assertTrue("il bordo sinistro del riquadro resta dentro lo schermo", start - width / 2f >= 0f)
    assertTrue("il bordo destro del riquadro resta dentro lo schermo", end + width / 2f <= 1f)
  }

  @Test
  fun `al centro l overlay resta sulla posizione preferita`() {
    assertEquals(
      0.5f,
      SeekPreviewModel.clampedCenterFraction(0.5f, 0.30f)
    )
  }

  @Test
  fun `un anteprima piu larga dello spazio utile resta centrata`() {
    assertEquals(0.5f, SeekPreviewModel.clampedCenterFraction(0.1f, 1.4f))
  }

  // ---------------------------------------------------------------------------
  // Guardie: durata, live, posizioni non valide
  // ---------------------------------------------------------------------------

  @Test
  fun `senza durata non si chiede alcun fotogramma`() {
    assertFalse(SeekPreviewModel.canRequestPreview(30_000L, 0L))
    assertFalse(SeekPreviewModel.canRequestPreview(30_000L, -1L))
  }

  @Test
  fun `posizioni dentro il contenuto sono richiedibili`() {
    assertTrue(SeekPreviewModel.canRequestPreview(0L, oneHourMs))
    assertTrue(SeekPreviewModel.canRequestPreview(oneHourMs - 1L, oneHourMs))
  }

  @Test
  fun `la fine del contenuto e le posizioni negative non lo sono`() {
    assertFalse(SeekPreviewModel.canRequestPreview(oneHourMs, oneHourMs))
    assertFalse(SeekPreviewModel.canRequestPreview(-1L, oneHourMs))
  }

  // ---------------------------------------------------------------------------
  // Prefetch e chiave di cache
  // ---------------------------------------------------------------------------

  @Test
  fun `il prefetch mette in testa la posizione da mostrare e poi i due adiacenti`() {
    assertEquals(
      listOf(100_000L, 110_000L, 90_000L),
      SeekPreviewModel.prefetchPositions(100_000L, oneHourMs, PlayerScrub.STEP_MS)
    )
  }

  @Test
  fun `il prefetch non esce dal contenuto`() {
    assertEquals(
      listOf(0L, 10_000L),
      SeekPreviewModel.prefetchPositions(0L, oneHourMs, PlayerScrub.STEP_MS)
    )
    // Un passo prima della fine: in avanti si ferma all'ultimo istante utile, indietro
    // resta dentro il contenuto.
    assertEquals(
      listOf(oneHourMs - 5_000L, oneHourMs - 15_000L),
      SeekPreviewModel.prefetchPositions(oneHourMs - 5_000L, oneHourMs, PlayerScrub.STEP_MS)
    )
  }

  @Test
  fun `con durata ignota il prefetch e vuoto`() {
    assertTrue(SeekPreviewModel.prefetchPositions(50_000L, 0L, PlayerScrub.STEP_MS).isEmpty())
  }

  @Test
  fun `la chiave di cache arrotonda allo step di scrubbing`() {
    assertEquals(100_000L, SeekPreviewModel.bucketKey(100_000L, 10_000L))
    assertEquals(100_000L, SeekPreviewModel.bucketKey(109_999L, 10_000L))
    assertEquals(110_000L, SeekPreviewModel.bucketKey(110_000L, 10_000L))
    assertEquals(0L, SeekPreviewModel.bucketKey(-4L, 10_000L))
  }

  @Test
  fun `la chiave di cache senza step e la posizione stessa`() {
    assertEquals(1_234L, SeekPreviewModel.bucketKey(1_234L, 0L))
  }
}
