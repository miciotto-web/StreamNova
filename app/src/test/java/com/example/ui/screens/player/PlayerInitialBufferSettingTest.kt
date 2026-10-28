package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.prefs.InitialBufferOption
import com.example.data.prefs.PlaybackSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Buffer iniziale" (Avvio Rapido / Standard / Stabile) deve arrivare DAVVERO al LoadControl.
 *
 * Prima della Fase 2 l'impostazione era scritta solo su DataStore: il player usava sempre le
 * costanti hard-coded (2,5 s di avvio, 5 s dopo un rebuffer), quindi chi vedeva il video
 * ripartire a singhiozzo non poteva fare nulla dalle impostazioni. Questi test coprono
 * l'intera catena `PlaybackSettings → buildPlayerLoadControl → DefaultLoadControl` guidando
 * la decisione reale di Media3 (`DefaultLoadControl.shouldStartPlayback`), non una copia dei
 * suoi calcoli. Sono quindi ROSSI se la mappatura dell'impostazione viene persa.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerInitialBufferSettingTest {

  private val playerId = PlayerId("streamnova-initial-buffer-test")

  private fun loadControlFor(option: InitialBufferOption): DefaultLoadControl =
    buildPlayerLoadControl(PlaybackSettings(initialBuffer = option))

  /**
   * `true` se il player può partire (o ripartire) con `bufferedMs` di media già scaricata.
   */
  private fun shouldStartPlayback(
    loadControl: DefaultLoadControl,
    bufferedMs: Long,
    rebuffering: Boolean
  ): Boolean {
    loadControl.onPrepared(playerId)
    val parameters = LoadControl.Parameters(
      playerId,
      Timeline.EMPTY,
      /* mediaPeriodId= */ MediaSource.MediaPeriodId(Any()),
      /* playbackPositionUs= */ 0L,
      /* bufferedDurationUs= */ bufferedMs * 1_000L,
      /* playbackSpeed= */ 1f,
      /* playWhenReady= */ true,
      rebuffering,
      /* targetLiveOffsetUs= */ C.TIME_UNSET,
      /* lastRebufferRealtimeMs= */ 0L
    )
    return loadControl.shouldStartPlayback(parameters)
  }

  @Test
  fun stabileRichiedeTreSecondiEMezzoPrimaDiPartire() {
    val loadControl = loadControlFor(InitialBufferOption.STABLE)

    assertFalse(shouldStartPlayback(loadControl, bufferedMs = 3_000L, rebuffering = false))
    assertTrue(shouldStartPlayback(loadControl, bufferedMs = 3_600L, rebuffering = false))
  }

  @Test
  fun avvioRapidoPartePrimaMaNonSottoLaSuaSoglia() {
    val loadControl = loadControlFor(InitialBufferOption.FAST)

    assertFalse(shouldStartPlayback(loadControl, bufferedMs = 1_400L, rebuffering = false))
    assertTrue(shouldStartPlayback(loadControl, bufferedMs = 1_600L, rebuffering = false))
  }

  @Test
  fun standardRestaIlValorePredefinitoDiMedia3() {
    val loadControl = loadControlFor(InitialBufferOption.STANDARD)

    assertFalse(shouldStartPlayback(loadControl, bufferedMs = 2_400L, rebuffering = false))
    assertTrue(shouldStartPlayback(loadControl, bufferedMs = 2_600L, rebuffering = false))
  }

  @Test
  fun dopoUnoStalloLaRipartenzaNonScendeMaiSottoLaSogliaDiAvvio() {
    val loadControl = loadControlFor(InitialBufferOption.STABLE)

    // La soglia di ripartenza resta quella stock (5 s), quindi con 3,4 s NON riparte...
    assertFalse(shouldStartPlayback(loadControl, bufferedMs = 3_400L, rebuffering = true))
    // ...e riparte appena i 5 s sono disponibili.
    assertTrue(shouldStartPlayback(loadControl, bufferedMs = 5_200L, rebuffering = true))
  }

  @Test
  fun nessunaOpzioneSuperaLaSogliaDiRipartenzaStock() {
    // Invariante di DefaultLoadControl: bufferForPlaybackMs <= bufferForPlaybackAfterRebufferMs.
    InitialBufferOption.entries.forEach { option ->
      assertTrue(
        "Opzione $option con soglia di avvio ${option.durationMs} ms sopra i " +
          "${NuvioExoPlayerPerformanceHelper.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS} ms di ripartenza",
        option.durationMs <= NuvioExoPlayerPerformanceHelper.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
      )
    }
  }
}
