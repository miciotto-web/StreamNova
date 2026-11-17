package com.example.data.streaming.extractors

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test offline di [VoeExtractor]: round-trip della decodifica testuale e
 * parsing del payload `source`. Nessuna esecuzione di JavaScript e nessuna
 * richiesta di rete.
 */
class VoeExtractorTest {

  private val json = """
    {"source":"https://cdn.example/hls/master.m3u8","captions":[]}
  """.trimIndent()

  @Test
  fun decodificaIlPayloadProdottoDalPlayer() {
    val encoded = encodePayload(json)
    assertEquals(json, VoeExtractor().decryptPayload(encoded))
  }

  @Test
  fun estraeIlPayloadDalTagApplicationJson() {
    val encoded = encodePayload(json)
    val html = """<html><script type="application/json">$encoded</script></html>"""
    assertEquals(encoded, VoeExtractor().findEncodedPayload(html))
  }

  @Test
  fun estraeLaSorgenteHlsConGliHeaderCorretti() = runBlocking {
    val encoded = encodePayload(json)
    val html = """<html><script type="application/json">$encoded</script></html>"""
    val extractor = VoeExtractor(httpGet = { _, _ -> html })

    val source = extractor.extract("https://voe.sx/e/abc123").single()

    assertEquals("https://cdn.example/hls/master.m3u8", source.streamUrl)
    assertEquals("Voe", source.serverName)
    assertEquals("https://voe.sx/e/abc123", source.headers["Referer"])
    assertEquals("https://voe.sx", source.headers["Origin"])
    assertEquals(ExtractorHttp.USER_AGENT, source.headers["User-Agent"])
    assertFalse(source.isProgressive == true)
  }

  @Test
  fun supportaAncheUnMp4Progressivo() = runBlocking {
    val mp4Json = """{"source":"https://cdn.example/video.mp4"}"""
    val encoded = encodePayload(mp4Json)
    val extractor = VoeExtractor(
      httpGet = { _, _ -> """<script type="application/json">$encoded</script>""" }
    )

    val source = extractor.extract("https://voe.sx/e/abc123").single()
    assertEquals("https://cdn.example/video.mp4", source.streamUrl)
    assertTrue(source.isProgressive == true)
  }

  @Test
  fun paginaSenzaPayloadProduceErroreEsplicito() = runBlocking {
    val extractor = VoeExtractor(httpGet = { _, _ -> "<html>nessun player</html>" })
    try {
      extractor.extract("https://voe.sx/e/abc123")
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("challenge", ignoreCase = true))
    }
  }

  @Test
  fun payloadNonDecodificabileProduceErroreEsplicito() = runBlocking {
    val extractor = VoeExtractor(
      httpGet = { _, _ -> """<script type="application/json">non-base64!!!</script>""" }
    )
    try {
      extractor.extract("https://voe.sx/e/abc123")
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("Decodifica", ignoreCase = true))
    }
  }

  /**
   * Inverso esatto di [VoeExtractor.decryptPayload]:
   * JSON -> Base64 -> reverse -> shift +3 -> Base64 -> rot13.
   */
  private fun encodePayload(value: String): String {
    val first = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    val shifted = first.reversed().map { (it.code + 3).toChar() }.joinToString("")
    val second = Base64.getEncoder()
      .encodeToString(shifted.toByteArray(Charsets.UTF_8))
    return rot13(second)
  }

  private fun rot13(value: String): String = value.map { c ->
    when (c) {
      in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
      in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
      else -> c
    }
  }.joinToString("")
}
