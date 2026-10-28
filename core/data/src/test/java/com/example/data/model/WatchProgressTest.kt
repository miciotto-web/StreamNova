package com.example.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Continua a guardare": i minuti rimanenti devono derivare dai millisecondi **reali**
 * del player, con il runtime TMDB come solo fallback, e una durata non valida
 * (`C.TIME_UNSET`/0) non deve MAI sovrascrivere quella gia' nota.
 */
class WatchProgressTest {

  /** Valore di `androidx.media3.common.C.TIME_UNSET` (negativo). */
  private val timeUnset = Long.MIN_VALUE + 1L

  // ── Minuti rimanenti dai millisecondi reali ──────────────────────────────

  @Test
  fun remainingMinutes_fromRealPositionAndDuration() {
    val total = 120 * 60_000L
    val position = 30 * 60_000L
    assertEquals(90, WatchProgress.remainingMinutes(total, position)!!)
  }

  @Test
  fun remainingMinutes_roundsUpPartialMinute() {
    // 62 minuti totali, guardati 10 min e 30 s -> 51 min e 30 s rimasti -> 52 min.
    val total = 62 * 60_000L
    val position = 10 * 60_000L + 30_000L
    assertEquals(52, WatchProgress.remainingMinutes(total, position)!!)
  }

  @Test
  fun remainingMinutes_neverNegativeWhenPositionPastEnd() {
    val total = 45 * 60_000L
    assertEquals(0, WatchProgress.remainingMinutes(total, total + 5 * 60_000L)!!)
  }

  @Test
  fun remainingMinutes_zeroWhenExactlyAtEnd() {
    val total = 45 * 60_000L
    assertEquals(0, WatchProgress.remainingMinutes(total, total)!!)
  }

  @Test
  fun remainingMinutes_oneMinuteForSubMinuteTail() {
    val total = 45 * 60_000L
    // Restano 500 ms: il player li mostra come "0:01", la card arrotonda a 1 minuto.
    assertEquals(1, WatchProgress.remainingMinutes(total, total - 500L)!!)
  }

  // ── Fallback runtime TMDB solo se la durata reale non e' nota ────────────

  @Test
  fun remainingMinutes_fallsBackToRuntimeOnlyWhenDurationUnknown() {
    val position = 40 * 60_000L
    // Durata reale non nota: si usa il runtime TMDB (100 min) -> 60 min rimasti.
    assertEquals(60, WatchProgress.remainingMinutes(0L, position, runtimeMinutes = 100)!!)
  }

  @Test
  fun remainingMinutes_realDurationWinsOverRuntime() {
    val total = 62 * 60_000L
    val position = 10 * 60_000L
    // Il runtime TMDB (200 min) NON deve essere usato: comanda la durata reale.
    assertEquals(52, WatchProgress.remainingMinutes(total, position, runtimeMinutes = 200)!!)
  }

  @Test
  fun remainingMinutes_nullWhenNoDurationAtAll() {
    assertNull(WatchProgress.remainingMinutes(0L, 10 * 60_000L, runtimeMinutes = 0))
  }

  @Test
  fun remainingMs_clampsNegativePositionToZero() {
    val total = 50 * 60_000L
    assertEquals(total, WatchProgress.remainingMs(total, -1_000L)!!)
  }

  // ── Protezione: una durata non valida non sovrascrive quella nota ────────

  @Test
  fun isValidDuration_rejectsUnsetZeroAndNegative() {
    assertFalse(WatchProgress.isValidDuration(timeUnset))
    assertFalse(WatchProgress.isValidDuration(0L))
    assertFalse(WatchProgress.isValidDuration(-1L))
    assertTrue(WatchProgress.isValidDuration(1L))
  }

  @Test
  fun resolveDuration_keepsKnownValueWhenPlayerReportsTimeUnset() {
    val known = 48 * 60_000L
    assertEquals(known, WatchProgress.resolveDuration(known, timeUnset))
  }

  @Test
  fun resolveDuration_keepsKnownValueWhenPlayerReportsZero() {
    val known = 48 * 60_000L
    assertEquals(known, WatchProgress.resolveDuration(known, 0L))
  }

  @Test
  fun resolveDuration_keepsKnownValueWhenPlayerReportsNegative() {
    val known = 48 * 60_000L
    assertEquals(known, WatchProgress.resolveDuration(known, -1L))
  }

  @Test
  fun resolveDuration_prefersValidReportedValue() {
    val known = 48 * 60_000L
    val real = 51 * 60_000L
    assertEquals(real, WatchProgress.resolveDuration(known, real))
  }

  @Test
  fun resolveDuration_zeroWhenNothingKnown() {
    assertEquals(0L, WatchProgress.resolveDuration(0L, timeUnset))
    assertEquals(0L, WatchProgress.resolveDuration(-5L, 0L))
  }

  // ── Propagazione nel modello UI delle card ───────────────────────────────

  @Test
  fun mediaItem_formattedRemainingUsesRealMilliseconds() {
    val item = mediaItem(
      totalDurationMs = 62 * 60_000L,
      currentProgressMs = 10 * 60_000L,
      durationMinutes = 200 // runtime TMDB errato: deve essere ignorato
    )
    assertEquals("52m rimanenti", item.formattedRemainingOrProgress)
  }

  @Test
  fun mediaItem_formattedRemainingUsesTmdbRuntimeWhenDurationUnknown() {
    val item = mediaItem(
      totalDurationMs = 0L,
      currentProgressMs = 50 * 60_000L,
      durationMinutes = 100
    )
    // Durata reale non nota: si usa il runtime TMDB (100 min) -> 50 min rimasti.
    assertEquals("50m rimanenti", item.formattedRemainingOrProgress)
  }

  @Test
  fun episode_remainingMinutesTextUsesRealMilliseconds() {
    val episode = Episode(
      id = "series_s1e2",
      seasonNumber = 1,
      episodeNumber = 2,
      title = "Episodio",
      synopsis = "",
      durationMinutes = 50,
      videoUrl = "",
      currentProgressMs = 20 * 60_000L,
      totalDurationMs = 45 * 60_000L
    )
    assertEquals("25m rimanenti", episode.remainingMinutesText)
  }

  private fun mediaItem(
    totalDurationMs: Long,
    currentProgressMs: Long,
    durationMinutes: Int
  ): MediaItem = MediaItem(
    id = "m",
    title = "Titolo",
    synopsis = "",
    videoUrl = "",
    durationMinutes = durationMinutes,
    currentProgressMs = currentProgressMs,
    totalDurationMs = totalDurationMs
  )
}
