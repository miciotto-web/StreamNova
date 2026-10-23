package com.example.data.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Deduplica deterministica delle sorgenti HTTP per URL + header rilevanti. */
class StreamManagerDedupTest {

  private fun source(
    url: String,
    headers: Map<String, String> = emptyMap(),
    name: String = "Test"
  ) = StreamSource(streamUrl = url, quality = "Auto", serverName = name, headers = headers)

  @Test
  fun stessoUrlEStessiHeaderProduconOUnaSolaSorgente() {
    val url = "https://cdn.example.test/master.m3u8"
    val headers = mapOf("Referer" to "https://embed.test/", "Authorization" to "Bearer abc")

    val collected = mutableListOf(
      source(url, headers),
      source(url, headers),
      source(url, headers)
    )
    val added = StreamManager.addDistinctSources(
      collected,
      listOf(source(url, headers), source(url, headers))
    )

    assertEquals(3, collected.size)
    assertEquals(0, added)
    assertEquals(StreamManager.sourceDedupKey(collected[0]), StreamManager.sourceDedupKey(collected[1]))
  }

  @Test
  fun stessoUrlConAuthorizationDiversaProduceSorgentiDistinte() {
    val url = "https://cdn.example.test/master.m3u8"

    val collected = mutableListOf(source(url, mapOf("Authorization" to "Bearer token-a")))
    val added = StreamManager.addDistinctSources(
      collected,
      listOf(
        source(url, mapOf("Authorization" to "Bearer token-b")),
        source(url, mapOf("Authorization" to "Bearer token-a"))
      )
    )

    assertEquals(2, collected.size)
    assertEquals(1, added)
    assertNotEquals(
      StreamManager.sourceDedupKey(collected[0]),
      StreamManager.sourceDedupKey(collected[1])
    )
  }

  @Test
  fun nomiHeaderSonoCaseInsensitiveESpaziEsterniIrrilevanti() {
    val url = "https://cdn.example.test/master.m3u8"
    val a = source(url, mapOf("Authorization" to "Bearer abc", "Referer" to "https://embed.test/"))
    val b = source(url, mapOf("authorization" to "  Bearer abc  ", "referer" to "https://embed.test/ "))

    assertEquals(StreamManager.sourceDedupKey(a), StreamManager.sourceDedupKey(b))
  }

  @Test
  fun ordineDegliHeaderNonCambiaLaChiave() {
    val url = "https://cdn.example.test/master.m3u8"
    val a = source(
      url,
      linkedMapOf("Referer" to "https://embed.test/", "Authorization" to "Bearer abc")
    )
    val b = source(
      url,
      linkedMapOf("Authorization" to "Bearer abc", "Referer" to "https://embed.test/")
    )

    assertEquals(StreamManager.sourceDedupKey(a), StreamManager.sourceDedupKey(b))
  }

  @Test
  fun headerNonRilevantiNonDuplicanoLaSorgente() {
    val url = "https://cdn.example.test/master.m3u8"
    val a = source(url, mapOf("X-Request-Id" to "1"))
    val b = source(url, mapOf("X-Request-Id" to "2"))

    // X-* è considerato rilevante (spesso trasporta token): qui si verifica che
    // un header NON presente nella lista dei rilevanti non incida.
    val c = source(url, mapOf("Accept" to "video/*"))
    val d = source(url, mapOf("Accept" to "application/x-mpegURL"))

    assertNotEquals(StreamManager.sourceDedupKey(a), StreamManager.sourceDedupKey(b))
    assertEquals(StreamManager.sourceDedupKey(c), StreamManager.sourceDedupKey(d))
  }

  @Test
  fun torrentUsaInfoHashEFileIdxComePrima() {
    val a = StreamSource(infoHash = "abc", fileIdx = 1, serverName = "TorBox")
    val b = StreamSource(infoHash = "abc", fileIdx = 1, serverName = "TorBox")
    val c = StreamSource(infoHash = "abc", fileIdx = 2, serverName = "TorBox")

    assertEquals(StreamManager.sourceDedupKey(a), StreamManager.sourceDedupKey(b))
    assertNotEquals(StreamManager.sourceDedupKey(a), StreamManager.sourceDedupKey(c))
  }
}
