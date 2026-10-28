package com.example.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class SubtitleOffsetRendererTest {

  private class FakeRenderer : Renderer {
    var lastRenderPositionUs: Long = -1L
    var lastRenderElapsedRealtimeUs: Long = -1L
    var resetPositionCallCount = 0
    var lastResetPositionUs: Long = -1L
    var enableCallCount = 0
    var lastEnablePositionUs: Long = -1L
    var lastEnableStartPositionUs: Long = -1L

    override fun getName(): String = "FakeRenderer"
    override fun getTrackType(): Int = androidx.media3.common.C.TRACK_TYPE_TEXT
    override fun getCapabilities(): RendererCapabilities = throw UnsupportedOperationException()
    override fun init(index: Int, playerId: androidx.media3.exoplayer.analytics.PlayerId, clock: androidx.media3.common.util.Clock) {}
    override fun getMediaClock(): androidx.media3.exoplayer.MediaClock? = null
    override fun getState(): Int = Renderer.STATE_STARTED
    override fun start() {}
    override fun getStream(): SampleStream? = null
    override fun hasReadStreamToEnd(): Boolean = false
    override fun getReadingPositionUs(): Long = 0L
    override fun setCurrentStreamFinal() {}
    override fun isCurrentStreamFinal(): Boolean = false
    override fun maybeThrowStreamError() {}
    override fun setTimeline(timeline: Timeline) {}
    override fun isReady(): Boolean = true
    override fun isEnded(): Boolean = false
    override fun stop() {}
    override fun disable() {}
    override fun reset() {}
    override fun handleMessage(messageType: Int, message: Any?) {}
    override fun replaceStream(
      formats: Array<out Format>,
      stream: SampleStream,
      startPositionUs: Long,
      offsetUs: Long,
      mediaPeriodId: MediaSource.MediaPeriodId
    ) {}

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
      lastRenderPositionUs = positionUs
      lastRenderElapsedRealtimeUs = elapsedRealtimeUs
    }

    override fun resetPosition(positionUs: Long) {
      resetPositionCallCount++
      lastResetPositionUs = positionUs
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long = 1000L

    override fun enable(
      configuration: RendererConfiguration,
      formats: Array<out Format>,
      stream: SampleStream,
      positionUs: Long,
      joining: Boolean,
      mayRenderStartOfStream: Boolean,
      startPositionUs: Long,
      offsetUs: Long,
      mediaPeriodId: MediaSource.MediaPeriodId
    ) {
      enableCallCount++
      lastEnablePositionUs = positionUs
      lastEnableStartPositionUs = startPositionUs
    }
  }

  @Test
  fun renderSenzaOffsetInviaPosizioneInvariata() {
    val fake = FakeRenderer()
    val renderer = SubtitleOffsetRenderer(fake) { 0L }

    renderer.render(10_000_000L, 500L)

    assertEquals(10_000_000L, fake.lastRenderPositionUs)
    assertEquals(500L, fake.lastRenderElapsedRealtimeUs)
  }

  @Test
  fun renderConOffsetPositivoRitardaSottotitoli() {
    val fake = FakeRenderer()
    var offsetMs = 500L // +500 ms di ritardo: i sottotitoli a 10s mostrano quelli di 9.5s
    val renderer = SubtitleOffsetRenderer(fake) { offsetMs }

    renderer.render(10_000_000L, 500L)

    assertEquals(9_500_000L, fake.lastRenderPositionUs)
  }

  @Test
  fun renderConOffsetNegativoAnticipaSottotitoli() {
    val fake = FakeRenderer()
    var offsetMs = -250L // -250 ms di anticipo: a 10s mostrano quelli di 10.25s
    val renderer = SubtitleOffsetRenderer(fake) { offsetMs }

    renderer.render(10_000_000L, 500L)

    assertEquals(10_250_000L, fake.lastRenderPositionUs)
  }

  @Test
  fun renderConOffsetPositivoNonProducePosizioniNegative() {
    val fake = FakeRenderer()
    val renderer = SubtitleOffsetRenderer(fake) { 2_000L } // +2s offset ma posizione a 1s

    renderer.render(1_000_000L, 100L)

    assertEquals(0L, fake.lastRenderPositionUs)
  }

  @Test
  fun cambioDinamicoOffsetInnescaResetPositionImmediato() {
    val fake = FakeRenderer()
    var offsetMs = 0L
    val renderer = SubtitleOffsetRenderer(fake) { offsetMs }

    // Primo frame a 0 ms
    renderer.render(5_000_000L, 100L)
    assertEquals(5_000_000L, fake.lastRenderPositionUs)
    assertEquals(0, fake.resetPositionCallCount)

    // L'utente preme [+250 ms] durante la riproduzione
    offsetMs = 250L
    renderer.render(5_040_000L, 140L)

    // Deve aver chiamato resetPosition con la nuova posizione aggiustata
    assertEquals(1, fake.resetPositionCallCount)
    assertEquals(5_040_000L - 250_000L, fake.lastResetPositionUs)
    assertEquals(5_040_000L - 250_000L, fake.lastRenderPositionUs)
  }

  @Test
  fun resetPositionEsplicitoApplicaOffset() {
    val fake = FakeRenderer()
    val renderer = SubtitleOffsetRenderer(fake) { 1_000L } // +1s

    renderer.resetPosition(8_000_000L)

    assertEquals(7_000_000L, fake.lastResetPositionUs)
  }
}
