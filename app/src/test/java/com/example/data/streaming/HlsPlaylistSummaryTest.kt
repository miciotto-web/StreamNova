package com.example.data.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing di sintesi HLS (FASE 3 del piano HLS Diagnostics Engine): master,
 * media, renditions, varianti 2160p/1440p/1080p/720p, distinzione 1440p/4K,
 * URI relative con propagazione del token. Tutto da fixture locali.
 */
class HlsPlaylistSummaryTest {

  private val masterUrl = "https://cdn.example.com/hls/master.m3u8?token=SECRET&e=1"

  private val master = """
    #EXTM3U
    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Italiano",LANGUAGE=it,DEFAULT=YES,URI="audio/ita/playlist.m3u8"
    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE=en,URI="audio/eng/playlist.m3u8"
    #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Italiano",LANGUAGE=it,DEFAULT=YES,URI="subs/ita/playlist.m3u8"
    #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160,CODECS="hvc1.2.4.L153.B0,mp4a.40.2"
    2160/playlist.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
    1080/playlist.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
    720/playlist.m3u8
  """.trimIndent()

  private val media = """
    #EXTM3U
    #EXT-X-VERSION:3
    #EXT-X-TARGETDURATION:6
    #EXT-X-MEDIA-SEQUENCE:0
    #EXT-X-MAP:URI="init.mp4"
    #EXTINF:6.006,
    seg0.mp4
    #EXTINF:6.006,
    seg1.mp4
    #EXTINF:4.0,
    seg2.mp4
    #EXT-X-ENDLIST
  """.trimIndent()

  private val liveMedia = """
    #EXTM3U
    #EXT-X-TARGETDURATION:4
    #EXTINF:4.0,
    seg0.ts
  """.trimIndent()

  @Test
  fun masterPlaylistRilevataConVariantiERenditions() {
    val summary = HlsPlaylistSummaryParser.analyze(master, masterUrl)

    assertEquals(HlsPlaylistKind.MASTER, summary.kind)
    assertEquals(3, summary.variants.size)
    // 4K dichiarata: 3840x2160
    assertEquals(2160, summary.declaredResolutionHeight)
    // Codec dichiarati in ordine di apparizione, distinti
    assertTrue(summary.declaredCodecs.contains("hvc1.2.4.L153.B0"))
    assertTrue(summary.declaredCodecs.contains("avc1.640028"))
    assertTrue(summary.declaredCodecs.contains("mp4a.40.2"))
    // Renditions
    assertEquals(2, summary.audioRenditions.size)
    assertEquals(1, summary.subtitleRenditions.size)
    val italiano = summary.audioRenditions.first()
    assertEquals("AUDIO", italiano.type)
    assertEquals("Italiano", italiano.name)
    assertEquals("it", italiano.language)
    assertTrue(italiano.isDefault)
    assertTrue(italiano.uri!!.contains("audio/ita/playlist.m3u8"))
    assertNull(summary.totalDurationSec)
    assertNull(summary.isLiveStream)
  }

  @Test
  fun variantiRisolteConUrlAbsoluteETokenPropagato() {
    val summary = HlsPlaylistSummaryParser.analyze(master, masterUrl)

    // URI relative risolte rispetto alla master.
    assertEquals(
      "https://cdn.example.com/hls/1080/playlist.m3u8?token=SECRET&e=1",
      summary.variants[1].url
    )
    assertEquals(3_000_000L, summary.variants[1].bandwidth)
    assertEquals(1080, summary.variants[1].height)
  }

  @Test
  fun mediaPlaylistConSegmentiDurataEInit() {
    val summary = HlsPlaylistSummaryParser.analyze(media, "https://cdn.example.com/hls/1080/playlist.m3u8")

    assertEquals(HlsPlaylistKind.MEDIA, summary.kind)
    assertTrue(summary.variants.isEmpty())
    assertEquals(3, summary.segmentCount)
    // 6.006 + 6.006 + 4.0
    assertEquals(16.012, summary.totalDurationSec!!, 0.001)
    assertEquals(6, summary.targetDurationSec)
    assertTrue(summary.hasInitSegment)
    assertFalse(summary.isLiveStream!!)
    assertNull(summary.declaredResolutionHeight)
  }

  @Test
  fun livePlaylistSenzaEndlist() {
    val summary = HlsPlaylistSummaryParser.analyze(liveMedia, "https://cdn.example.com/live/playlist.m3u8")

    assertEquals(HlsPlaylistKind.MEDIA, summary.kind)
    assertEquals(1, summary.segmentCount)
    assertTrue(summary.isLiveStream == true)
  }

  @Test
  fun variante2160pRilevata() {
    val playlist = """
      #EXTM3U
      #EXT-X-STREAM-INF:RESOLUTION=3840x2160,BANDWIDTH=9000000
      v2160.m3u8
    """.trimIndent()
    val variants = HlsPlaylist.variants(playlist, masterUrl)
    assertEquals(2160, variants.single().height)
    assertEquals("4K", StreamResolution.labelForHeight(variants.single().height!!))
  }

  @Test
  fun variante1440pRilevataENonEtichettata4K() {
    val playlist = """
      #EXTM3U
      #EXT-X-STREAM-INF:RESOLUTION=2560x1440,BANDWIDTH=4000000
      v1440.m3u8
    """.trimIndent()
    val variants = HlsPlaylist.variants(playlist, masterUrl)
    assertEquals(1440, variants.single().height)
    assertEquals("1440p", StreamResolution.labelForHeight(variants.single().height!!))
  }

  @Test
  fun ultrawideQhd3440x1440Resta1440p() {
    // Il bug dell'audit: un'ultrawide QHD era classificata 4K (larghezza >= 3000).
    assertEquals(1440, StreamResolution.heightOfResolution(3440, 1440))
    assertEquals("1440p", StreamResolution.labelForHeight(1440))
  }

  @Test
  fun variante1080pRilevataConWidescreen() {
    assertEquals(1080, StreamResolution.heightOfResolution(1920, 1080))
    // Widescreen cinema: l'altezza 800 non è 720p né 4K.
    assertEquals(1080, StreamResolution.heightOfResolution(1920, 800))
    assertEquals("1080p", StreamResolution.labelForHeight(1080))
  }

  @Test
  fun variante720pRilevata() {
    assertEquals(720, StreamResolution.heightOfResolution(1280, 720))
    assertEquals(720, StreamResolution.heightOfResolution(1206, 720))
    assertEquals("720p", StreamResolution.labelForHeight(720))
  }

  @Test
  fun soloIl4kRealeVieneClassificato4K() {
    // Dimensioni reali 4K: UHD e DCI.
    assertEquals(2160, StreamResolution.heightOfResolution(3840, 2160))
    assertEquals(2160, StreamResolution.heightOfResolution(4096, 2160))
    // Widescreen 4K (crop): larghezza 4K conservata.
    assertEquals(2160, StreamResolution.heightOfResolution(3840, 1600))
    // QHD ultrawide: NON è 4K.
    assertEquals(1440, StreamResolution.heightOfResolution(3440, 1440))
    // QHD standard: NON è 4K.
    assertEquals(1440, StreamResolution.heightOfResolution(2560, 1440))
  }

  @Test
  fun playlistNonHlsENonInventata() {
    val summary = HlsPlaylistSummaryParser.analyze("<html><body>403</body></html>", masterUrl)
    assertEquals(HlsPlaylistKind.NOT_HLS, summary.kind)
    assertTrue(summary.variants.isEmpty())
    assertEquals(0, summary.segmentCount)
  }

  @Test
  fun variantiSenzaRisoluzioneRestanoConAltezzaNulla() {
    val playlist = """
      #EXTM3U
      #EXT-X-STREAM-INF:BANDWIDTH=800000
      auto.m3u8
    """.trimIndent()
    val summary = HlsPlaylistSummaryParser.analyze(playlist, masterUrl)
    assertNull(summary.declaredResolutionHeight)
    assertNull(summary.variants.single().height)
  }

  @Test
  fun mappingTipoPlaylistVersoReportETotale() {
    assertEquals(
      com.example.data.streaming.diagnostics.playback.HlsDiagnosticsReport.PlaylistKind.MASTER,
      HlsPlaylistKind.MASTER.toReportPlaylistKind()
    )
    assertEquals(
      com.example.data.streaming.diagnostics.playback.HlsDiagnosticsReport.PlaylistKind.MEDIA,
      HlsPlaylistKind.MEDIA.toReportPlaylistKind()
    )
    assertEquals(
      com.example.data.streaming.diagnostics.playback.HlsDiagnosticsReport.PlaylistKind.NOT_HLS,
      HlsPlaylistKind.NOT_HLS.toReportPlaylistKind()
    )
    assertEquals(
      com.example.data.streaming.diagnostics.playback.HlsDiagnosticsReport.PlaylistKind.UNKNOWN,
      HlsPlaylistKind.UNKNOWN.toReportPlaylistKind()
    )
  }
}
