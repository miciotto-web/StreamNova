package com.example.data.streaming

import com.example.data.stremio.StremioProxyHeaders
import com.example.data.stremio.StremioStreamBehaviorHints
import com.example.data.stremio.StremioStreamCandidate
import com.example.data.stremio.StremioStreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della conversione degli stream Stremio con **url diretto** (Debrid addon
 * pre-risolti come "Comet | ElfHosted") in [StreamSource] giocabili.
 *
 * Regole verificate:
 *  - url + `infoHash == null` → sorgente cached, con titolo file reale e qualità;
 *  - `infoHash` presente → non gestito qui (competenza di [TorBoxStreamProvider]);
 *  - placeholder di errore e url non riproducibili → scartati;
 *  - `behaviorHints.proxyHeaders.request` propagati al player.
 */
class StreamManagerDirectSourceTest {

  private fun candidate(
    url: String? = null,
    infoHash: String? = null,
    name: String? = null,
    title: String? = null,
    headers: Map<String, String> = emptyMap()
  ): StremioStreamCandidate = StremioStreamCandidate(
    addonName = "Comet | ElfHosted",
    baseUrl = "https://comet.elfhosted.com/eyJjb25maWcifQ",
    item = StremioStreamItem(
      name = name,
      title = title,
      url = url,
      infoHash = infoHash,
      behaviorHints = if (headers.isEmpty()) {
        null
      } else {
        StremioStreamBehaviorHints(proxyHeaders = StremioProxyHeaders(request = headers))
      }
    )
  )

  @Test
  fun streamConUrlDiventaSorgenteCachedGiocabile() {
    val source = StreamManager.directStreamSource(
      candidate(
        url = "https://torbox.app/download/abc",
        name = "Comet | ElfHosted",
        title = "Spider-Man.No.Way.Home.2021.2160p.ITA-ENG.mkv\n16.38 GB \ud83d\udc64 5"
      )
    )!!

    assertEquals("https://torbox.app/download/abc", source.streamUrl)
    assertEquals("4K", source.quality)
    assertEquals("Spider-Man.No.Way.Home.2021.2160p.ITA-ENG.mkv", source.releaseTitle)
    assertEquals(CacheState.Cached, source.cacheState)
    assertTrue(source.isCached)
    assertEquals("Comet | ElfHosted", source.addonName)
    assertTrue("la release ITA deve essere riconosciuta", source.isItalian)
  }

  @Test
  fun qualityEstrattaAncheSoloDaTitleSenzaName() {
    val source = StreamManager.directStreamSource(
      candidate(url = "https://cdn/x", name = "Comet | ElfHosted", title = "Film.2020.1080p.mkv")
    )!!
    assertEquals("1080p", source.quality)
  }

  @Test
  fun streamConInfoHashNonVieneTrattatoComeDiretto() {
    assertNull(
      StreamManager.directStreamSource(
        candidate(url = "https://cdn/x", infoHash = "deadbeef")
      )
    )
  }

  @Test
  fun placeholderDiErroreVieneScartato() {
    assertNull(StreamManager.directStreamSource(candidate(url = "https://cdn/x", title = "No streams found")))
    assertNull(
      StreamManager.directStreamSource(
        candidate(url = "https://cdn/x", title = "Invalid Debrid API key")
      )
    )
    assertNull(
      StreamManager.directStreamSource(
        candidate(url = "https://cdn/x", name = "Comet | ElfHosted", title = "Debrid not configured")
      )
    )
  }

  @Test
  fun urlNonRiproducibileVieneScartato() {
    assertNull(StreamManager.directStreamSource(candidate(url = "magnet:?xt=urn:btih:abc")))
    assertNull(StreamManager.directStreamSource(candidate(url = "https://host/manifest.json")))
    assertNull(StreamManager.directStreamSource(candidate(url = "   ")))
    assertNull(StreamManager.directStreamSource(candidate()))
  }

  @Test
  fun titoloDiFilmRealeNonVieneScartato() {
    // "Forbidden" è un titolo legittimo: nessun marker di errore deve scattare.
    val source = StreamManager.directStreamSource(
      candidate(url = "https://cdn/x", title = "Forbidden.2021.1080p.mkv")
    )
    assertTrue(source != null)
    assertEquals("1080p", source!!.quality)
  }

  @Test
  fun headerProxyVengonoPropagati() {
    val source = StreamManager.directStreamSource(
      candidate(
        url = "https://cdn/x",
        headers = mapOf("User-Agent" to "UA", "Referer" to "https://ref.example/")
      )
    )!!
    assertEquals("UA", source.headers["User-Agent"])
    assertEquals("https://ref.example/", source.headers["Referer"])
    assertFalse(source.headers.isEmpty())
  }
}
