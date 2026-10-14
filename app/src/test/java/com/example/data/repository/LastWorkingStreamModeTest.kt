package com.example.data.repository

import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della logica **pura** del tracciamento modalità della cache "Riusa l'ultimo link":
 * classificazione di una sorgente live (HTTP vs Debrid) e risoluzione della modalità di una
 * voce di cache, con inferenza best-effort per le voci legacy prive di `streamMode`.
 */
class LastWorkingStreamModeTest {

  // ── classifySourceMode (registrazione al salvataggio) ─────────────────

  @Test
  fun `sorgente con infoHash classificata come debrid`() {
    val source = StreamSource(
      streamUrl = "https://cdn.example/movie.mkv",
      serverName = "Addon X",
      infoHash = "abcdefabcdefabcdefabcdefabcdefabcdefabcd"
    )
    assertEquals(
      StreamingEngineMode.DEBRID_TORBOX,
      LastWorkingStreamRepository.classifySourceMode(source, StreamingEngineMode.HTTP_WEB)
    )
  }

  @Test
  fun `sorgente con server TorBox classificata come debrid`() {
    val source = StreamSource(streamUrl = "https://cdn/movie.mkv", serverName = "TorBox Instant")
    assertEquals(
      StreamingEngineMode.DEBRID_TORBOX,
      LastWorkingStreamRepository.classifySourceMode(source, StreamingEngineMode.HTTP_WEB)
    )
  }

  @Test
  fun `sorgente con url magnet classificata come debrid`() {
    val source = StreamSource(
      streamUrl = "magnet:?xt=urn:btih:abcdefabcdefabcdefabcdefabcdefabcdefabcd",
      serverName = "Generic"
    )
    assertEquals(
      StreamingEngineMode.DEBRID_TORBOX,
      LastWorkingStreamRepository.classifySourceMode(source, StreamingEngineMode.HTTP_WEB)
    )
  }

  @Test
  fun `sorgente HTTP senza marker segue la modalita attiva`() {
    val vix = StreamSource(streamUrl = "https://cdn.vixsrc.example/hls/movie.m3u8", serverName = "VixSrc")
    assertEquals(
      StreamingEngineMode.HTTP_WEB,
      LastWorkingStreamRepository.classifySourceMode(vix, StreamingEngineMode.HTTP_WEB)
    )
    assertEquals(
      StreamingEngineMode.DEBRID_TORBOX,
      LastWorkingStreamRepository.classifySourceMode(vix, StreamingEngineMode.DEBRID_TORBOX)
    )
  }

  @Test
  fun `sorgente assente segue la modalita attiva`() {
    assertEquals(
      StreamingEngineMode.DEBRID_TORBOX,
      LastWorkingStreamRepository.classifySourceMode(null, StreamingEngineMode.DEBRID_TORBOX)
    )
  }

  // ── resolveEngineMode (lettura al riuso) ─────────────────────────────

  @Test
  fun `modalita esplicita viene rispettata senza inferenza`() {
    val httpCached = cached(streamUrl = "https://torbox.example/x.mkv", serverName = "TorBox", mode = "HTTP_WEB")
    assertEquals(StreamingEngineMode.HTTP_WEB, LastWorkingStreamRepository.resolveEngineMode(httpCached))

    val debridCached = cached(streamUrl = "https://cdn.vixsrc/x.m3u8", serverName = "VixSrc", mode = "DEBRID_TORBOX")
    assertEquals(StreamingEngineMode.DEBRID_TORBOX, LastWorkingStreamRepository.resolveEngineMode(debridCached))
  }

  @Test
  fun `modalita esplicita riconosciuta ignorando maiuscole e spazi`() {
    val cached = cached(streamUrl = "https://cdn/x.mkv", serverName = "Any", mode = "  debrid_torbox ")
    assertEquals(StreamingEngineMode.DEBRID_TORBOX, LastWorkingStreamRepository.resolveEngineMode(cached))
  }

  @Test
  fun `voce legacy con server TorBox inferita come debrid`() {
    val cached = cached(streamUrl = "https://cdn/x.mkv", serverName = "TorBox Instant", mode = null)
    assertEquals(StreamingEngineMode.DEBRID_TORBOX, LastWorkingStreamRepository.resolveEngineMode(cached))
  }

  @Test
  fun `voce legacy HTTP senza marker inferita come HTTP`() {
    val cached = cached(streamUrl = "https://cdn.vixsrc.example/x.m3u8", serverName = "VixSrc", mode = null)
    assertEquals(StreamingEngineMode.HTTP_WEB, LastWorkingStreamRepository.resolveEngineMode(cached))
  }

  @Test
  fun `voce legacy senza indizi assunta HTTP per non riusare fonti HTTP in debrid`() {
    val cached = cached(streamUrl = "https://opaque-cdn.example/x.mkv", serverName = "Sconosciuto", mode = null)
    assertEquals(StreamingEngineMode.HTTP_WEB, LastWorkingStreamRepository.resolveEngineMode(cached))
  }

  // ── helper minimi ────────────────────────────────────────────────────

  @Test
  fun `parseEngineMode gestisce valori invalidi`() {
    assertNull(LastWorkingStreamRepository.parseEngineMode(null))
    assertNull(LastWorkingStreamRepository.parseEngineMode(""))
    assertNull(LastWorkingStreamRepository.parseEngineMode("qualcosa"))
    assertEquals(StreamingEngineMode.HTTP_WEB, LastWorkingStreamRepository.parseEngineMode("http_web"))
  }

  @Test
  fun `looksLikeDebridSource riconosce i marker principali`() {
    assertTrue(LastWorkingStreamRepository.looksLikeDebridSource("TorBox 1080p"))
    assertTrue(LastWorkingStreamRepository.looksLikeDebridSource("Real-Debrid"))
    assertTrue(LastWorkingStreamRepository.looksLikeDebridSource("magnet:?xt=urn:btih:x"))
    assertFalse(LastWorkingStreamRepository.looksLikeDebridSource("VixSrc", "1080p"))
    assertFalse(LastWorkingStreamRepository.looksLikeDebridSource(null))
  }

  private fun cached(
    streamUrl: String,
    serverName: String,
    mode: String?
  ): CachedWorkingStream = CachedWorkingStream(
    mediaId = "movie_1",
    seasonNumber = LastWorkingStreamRepository.NO_EPISODE,
    episodeNumber = LastWorkingStreamRepository.NO_EPISODE,
    streamUrl = streamUrl,
    serverName = serverName,
    streamMode = mode
  )
}
