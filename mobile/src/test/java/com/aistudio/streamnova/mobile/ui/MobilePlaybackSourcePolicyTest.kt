package com.aistudio.streamnova.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classificazione HLS/progressiva del player Mobile: stessa semantica di
 * `computeIsHlsStream` della TV.
 *
 * Il caso che ha causato il bug di Fase 7 è il primo: VixSrc serve un manifest
 * HLS da `/playlist/<id>` senza `.m3u8` nell'URL
 * (`Content-Type: application/vnd.apple.mpegurl`).
 */
class MobilePlaybackSourcePolicyTest {

  @Test
  fun endpointHlsSenzaEstensioneEValeHls() {
    assertTrue(
      MobilePlaybackSourcePolicy.isHlsStream(
        "https://vixsrc.to/playlist/781768?ub=1&token=abc&expires=1&h=1&canPlayFHD=1",
        null
      )
    )
    assertTrue(MobilePlaybackSourcePolicy.isHlsStream("https://vixsrc.to/playlist/781768", null))
  }

  @Test
  fun m3u8NelPathODentroLaQuerySempreHls() {
    assertTrue(MobilePlaybackSourcePolicy.isHlsStream("https://cdn.example/master.m3u8", null))
    assertTrue(MobilePlaybackSourcePolicy.isHlsStream("https://cdn.example/master.M3U8?token=1", null))
    assertTrue(MobilePlaybackSourcePolicy.isHlsStream("https://cdn.example/index~.m3u8", null))
  }

  @Test
  fun estensioniProgressiveRiconosciuteAncheInQueryString() {
    assertFalse(MobilePlaybackSourcePolicy.isHlsStream("https://cdn.example/video.mp4", null))
    assertFalse(MobilePlaybackSourcePolicy.isHlsStream("https://cdn.example/video.mkv?x=1", null))
    assertFalse(MobilePlaybackSourcePolicy.isHlsStream("https://cdn.example/video.webm", null))
    assertFalse(MobilePlaybackSourcePolicy.isHlsStream("https://cdn.example/video.avi", null))
    // Link Debrid con estensione anche solo nella query string (regola TV).
    assertFalse(
      MobilePlaybackSourcePolicy.isHlsStream("https://download.torbox.app/file?name=movie.mp4&token=1", null)
    )
    assertFalse(MobilePlaybackSourcePolicy.isHlsStream("https://download.torbox.app/file?name=movie.m4v", null))
  }

  @Test
  fun hintDellaSorgenteHaPrecedenzaQuandoMancaLEstensione() {
    // Link Debrid sbloccato: file progressivo dichiarato dalla sorgente.
    assertFalse(MobilePlaybackSourcePolicy.isHlsStream("https://download.torbox.app/file/abc123", true))
    // Sorgente dichiarata HLS senza estensione nell'URL.
    assertTrue(MobilePlaybackSourcePolicy.isHlsStream("https://provider.example/stream/42", false))
  }

  @Test
  fun estensioneHaPrecedenzaSullHint() {
    assertTrue(MobilePlaybackSourcePolicy.isHlsStream("https://cdn.example/master.m3u8", true))
    assertFalse(MobilePlaybackSourcePolicy.isHlsStream("https://cdn.example/video.mp4", false))
  }

  @Test
  fun urlSenzaEstensioneNeHintEValeHls() {
    assertTrue(MobilePlaybackSourcePolicy.isHlsStream("https://provider.example/stream/42", null))
    assertTrue(MobilePlaybackSourcePolicy.isHlsStream("https://vixsrc.to/playlist/1?token=x", null))
  }
}
