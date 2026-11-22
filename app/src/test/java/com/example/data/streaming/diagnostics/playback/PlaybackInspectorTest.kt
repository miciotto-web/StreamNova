package com.example.data.streaming.diagnostics.playback

import com.example.data.streaming.diagnostics.DiagnosticClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratto del Nova Playback Inspector: metriche live e formattazione. */
class PlaybackInspectorTest {

  private class FakeClock : DiagnosticClock {
    var now = 0L
    override fun nowMs(): Long = now
    fun advance(ms: Long) { now += ms }
  }

  private fun session(clock: FakeClock) = HlsPlaybackSession(
    sessionId = 7L,
    providerId = "VixSrcProvider",
    contentLabel = "Film Test",
    clock = clock
  )

  @Test
  fun `live metrics reach the report`() {
    val clock = FakeClock()
    val s = session(clock)
    s.setSource("https://cdn.example.com/master.m3u8", SourceKind.HLS)
    s.updateLivePlayback(
      positionMs = 60_000,
      bufferedPositionMs = 90_000,
      durationMs = 3_600_000,
      bufferedDurationMs = 30_000
    )
    val report = s.buildReport()
    assertEquals(60_000L, report.positionMs)
    assertEquals(90_000L, report.bufferedPositionMs)
    assertEquals(3_600_000L, report.durationMs)
    assertEquals(30_000L, report.bufferAheadMs)
  }

  @Test
  fun `buffer ahead falls back to media3 buffered duration`() {
    val clock = FakeClock()
    val s = session(clock)
    s.updateLivePlayback(
      positionMs = 10_000,
      bufferedPositionMs = -1,
      durationMs = 100_000,
      bufferedDurationMs = 12_000
    )
    assertEquals(12_000L, s.buildReport().bufferAheadMs)
  }

  @Test
  fun `throughput is computed only from successful segments`() {
    val clock = FakeClock()
    val s = session(clock)
    s.onHttpCompleted(
      url = "https://cdn.example.com/seg1.ts",
      phase = PlaybackPhase.MEDIA_SEGMENT,
      httpStatus = 200,
      durationMs = 500,
      bytesLoaded = 5_000_000
    )
    s.onHttpCompleted(
      url = "https://cdn.example.com/seg2.ts",
      phase = PlaybackPhase.MEDIA_SEGMENT,
      httpStatus = 500,
      durationMs = 100,
      bytesLoaded = 1_000,
      isError = true
    )
    val report = s.buildReport()
    // Solo il primo evento: 5 MB in 500 ms = 10 MB/s.
    assertEquals(10_000_000L, report.throughputBps)
    assertEquals(5_000_000L, report.mediaSegmentBytesTotal)
  }

  @Test
  fun `dropped frames accumulate and bandwidth is kept`() {
    val clock = FakeClock()
    val s = session(clock)
    s.onDroppedVideoFrames(3)
    s.onDroppedVideoFrames(2)
    s.onBandwidthEstimate(25_000_000)
    val report = s.buildReport()
    assertEquals(5L, report.droppedVideoFrames)
    assertEquals(25_000_000L, report.bandwidthEstimateBps)
  }

  @Test
  fun `observed tracks are exposed in the report`() {
    val clock = FakeClock()
    val s = session(clock)
    s.onObservedTracks(
      video = ObservedTrackFormat(
        kind = "video",
        codecs = "hvc1.2.4.L123",
        sampleMimeType = "video/hevc",
        width = 1920,
        height = 1080,
        frameRate = 23.976f,
        bitrateBps = 8_000_000
      ),
      audio = ObservedTrackFormat(
        kind = "audio",
        codecs = "ec-3",
        sampleMimeType = "audio/eac3",
        language = "it",
        channelCount = 6,
        sampleRateHz = 48_000,
        bitrateBps = 640_000
      ),
      subtitle = ObservedTrackFormat(
        kind = "text",
        sampleMimeType = "application/x-subrip",
        language = "it"
      )
    )
    val report = s.buildReport()
    assertEquals("hvc1.2.4.L123", report.videoTrack?.codecs)
    assertEquals(6, report.audioTrack?.channelCount)
    assertEquals("it", report.subtitleTrack?.language)
  }

  @Test
  fun `no subtitle selection is represented as null`() {
    val clock = FakeClock()
    val s = session(clock)
    s.onObservedTracks(video = ObservedTrackFormat(kind = "video", width = 1920, height = 1080))
    assertNull(s.buildReport().subtitleTrack)
  }

  @Test
  fun `formats bitrate bytes and durations`() {
    assertEquals("—", PlaybackInspectorFormatter.formatBitrate(null))
    assertEquals("850 kbps", PlaybackInspectorFormatter.formatBitrate(850_000))
    assertEquals("12.4 Mbps", PlaybackInspectorFormatter.formatBitrate(12_400_000))
    assertEquals("1.25 GB", PlaybackInspectorFormatter.formatBytes(1_342_177_280))
    assertEquals("420.5 MB", PlaybackInspectorFormatter.formatBytes(440_926_208))
    assertEquals("0:00", PlaybackInspectorFormatter.formatDurationMs(0))
    assertEquals("12:34", PlaybackInspectorFormatter.formatDurationMs(754_000))
    assertEquals("1:00:00", PlaybackInspectorFormatter.formatDurationMs(3_600_000))
  }

  @Test
  fun `formats position and buffer percentages`() {
    assertEquals("0:10 / 1:40 · 10%", PlaybackInspectorFormatter.formatPosition(10_000, 100_000))
    assertEquals("24 s (24%)", PlaybackInspectorFormatter.formatBuffer(24_000, 100_000))
    assertEquals("—", PlaybackInspectorFormatter.formatBuffer(null, 100_000))
  }

  @Test
  fun `report text contains sections and no full urls`() {
    val clock = FakeClock()
    val s = session(clock)
    s.setSource("https://cdn.example.com/master.m3u8?token=SEGRETO", SourceKind.HLS)
    s.onHttpCompleted(
      url = "https://cdn.example.com/master.m3u8?token=SEGRETO",
      phase = PlaybackPhase.MANIFEST,
      httpStatus = 200,
      durationMs = 100
    )
    s.updateLivePlayback(10_000, 20_000, 100_000, 10_000)
    s.onObservedTracks(
      video = ObservedTrackFormat(kind = "video", codecs = "avc1", width = 1920, height = 1080)
    )
    val text = PlaybackInspectorFormatter.buildReportText(s.buildReport())
    assertTrue(text.contains("StreamNova · Playback Inspector"))
    assertTrue(text.contains("Posizione:"))
    assertTrue(text.contains("Video: avc1"))
    assertTrue(text.contains("Rete:"))
    assertFalse(text.contains("SEGRETO"))
    assertFalse(text.contains("token="))
    assertTrue(text.contains("cdn.example.com"))
  }

  @Test
  fun `classification text is honest about no problems`() {
    val clock = FakeClock()
    val report = session(clock).buildReport()
    assertEquals("nessun problema segnalato", PlaybackInspectorFormatter.classificationText(report))
  }
}
