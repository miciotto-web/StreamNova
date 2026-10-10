package com.example.ui.screens.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerIntroUtilsTest {

  private val margin = PlayerIntroUtils.INTRO_BUTTON_END_MARGIN_MS

  @Test
  fun senzaTimestampNonCreaFinestra() {
    assertNull(PlayerIntroUtils.window(null, 90_000L))
    assertNull(PlayerIntroUtils.window(0L, null))
    assertNull(PlayerIntroUtils.window(null, null))
  }

  @Test
  fun finestraIncoerenteVieneScartata() {
    assertNull(PlayerIntroUtils.window(0L, 0L))
    assertNull(PlayerIntroUtils.window(5_000L, 4_000L))
    assertNull(PlayerIntroUtils.window(-1L, 10_000L))
  }

  @Test
  fun finestraValidaCostruita() {
    val w = PlayerIntroUtils.window(15_000L, 75_000L)
    assertTrue(w!!.isValid)
    assertEquals(15_000L, w.startMs)
    assertEquals(75_000L, w.endMs)
  }

  @Test
  fun pulsanteAssenteSenzaFinestra() {
    assertFalse(PlayerIntroUtils.isButtonVisible(10_000L, null))
  }

  @Test
  fun pulsanteVisibileSoloDentroLaSigla() {
    val w = PlayerIntroUtils.window(15_000L, 75_000L)

    assertFalse("prima dell'inizio", PlayerIntroUtils.isButtonVisible(14_999L, w))
    assertTrue("all'inizio", PlayerIntroUtils.isButtonVisible(15_000L, w))
    assertTrue("a metà sigla", PlayerIntroUtils.isButtonVisible(40_000L, w))

    val hideAt = 75_000L - margin
    assertTrue("un ms prima della soglia", PlayerIntroUtils.isButtonVisible(hideAt - 1, w))
    assertFalse("alla soglia di chiusura", PlayerIntroUtils.isButtonVisible(hideAt, w))
    assertFalse("dopo la sigla", PlayerIntroUtils.isButtonVisible(hideAt + 5_000L, w))
  }

  @Test
  fun margineConSiglacortaNonMostraMaiPiuChePositivo() {
    val w = PlayerIntroUtils.window(1_000L, 2_000L)
    assertFalse(PlayerIntroUtils.isButtonVisible(1_000L, w))
    assertFalse(PlayerIntroUtils.isButtonVisible(1_999L, w))
  }

  @Test
  fun costanteMargineValorizzata() {
    assertEquals(2_500L, PlayerIntroUtils.INTRO_BUTTON_END_MARGIN_MS)
  }

  @Test
  fun leggeFinestraDagliExtrasDelMediaItem() {
    val extras = Bundle().apply {
      putLong(PlayerIntroUtils.EXTRA_INTRO_START_MS, 12_000L)
      putLong(PlayerIntroUtils.EXTRA_INTRO_END_MS, 82_000L)
    }
    val item = MediaItem.Builder()
      .setUri("https://v.test/movie.mkv")
      .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
      .build()

    val w = PlayerIntroUtils.windowFromMediaItem(item)
    assertEquals(12_000L, w?.startMs)
    assertEquals(82_000L, w?.endMs)
  }

  @Test
  fun senzaExtrasNessunaFinestra() {
    val item = MediaItem.Builder().setUri("https://v.test/movie.mkv").build()
    assertNull(PlayerIntroUtils.windowFromMediaItem(item))
    assertNull(PlayerIntroUtils.windowFromMediaItem(null))
  }

  @Test
  fun extrasIncompletiNonProduconoFinestra() {
    val extras = Bundle().apply { putLong(PlayerIntroUtils.EXTRA_INTRO_START_MS, 12_000L) }
    val item = MediaItem.Builder()
      .setUri("https://v.test/movie.mkv")
      .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
      .build()
    assertNull(PlayerIntroUtils.windowFromMediaItem(item))
  }
}
