package com.example.data.streaming.extractors

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test offline di [DoodstreamExtractor]: normalizzazione `/d/` -> `/e/`,
 * parsing di `pass_md5` e `token`, composizione dell'URL di playback e header
 * della sessione. Nessuna rete.
 */
class DoodstreamExtractorTest {

  @Test
  fun normalizzaLaPaginaFileInEmbed() {
    val extractor = DoodstreamExtractor()
    assertEquals(
      "https://dood.la/e/abc",
      extractor.toEmbedUrl("https://dood.la/d/abc")
    )
    assertEquals(
      "https://dood.la/e/abc",
      extractor.toEmbedUrl("https://dood.la/e/abc")
    )
  }

  @Test
  fun estraePathPassMd5EToken() {
    val html = """
      <script>
        var url = "/pass_md5/abc/def";
        var token = "?token=TOKEN123";
      </script>
    """.trimIndent()
    val extractor = DoodstreamExtractor()
    assertEquals("/pass_md5/abc/def", extractor.parseMd5Path(html))
    assertEquals("TOKEN123", extractor.parseToken(html))
  }

  @Test
  fun componeLaSorgenteConSuffissoTokenEScadenza() = runBlocking {
    val embedHtml = """
      <html>
        <script>
          var md5 = "/pass_md5/abc/def";
          var token = "?token=TOKEN123";
        </script>
      </html>
    """.trimIndent()
    val finalEmbed = "https://dood.la/e/abc"
    var passMd5Referer: String? = null
    var passMd5Headers: Map<String, String>? = null

    val extractor = DoodstreamExtractor(
      httpGet = { url, referer, headers ->
        when {
          url.endsWith("/e/abc") -> finalEmbed to embedHtml
          url.contains("/pass_md5/abc/def") -> {
            passMd5Referer = referer
            passMd5Headers = headers
            url to "https://cdn.example/video/"
          }
          else -> error("URL inatteso: $url")
        }
      },
      randomSuffix = { "0123456789" },
      now = { 123456789L }
    )

    val source = extractor.extract("https://dood.la/d/abc").single()

    assertEquals(
      "https://cdn.example/video/0123456789?token=TOKEN123&expiry=123456789",
      source.streamUrl
    )
    assertEquals("Doodstream", source.serverName)
    assertEquals(finalEmbed, source.headers["Referer"])
    assertEquals("https://dood.la", source.headers["Origin"])
    assertTrue(source.isProgressive == true)
    assertEquals(finalEmbed, passMd5Referer)
    assertEquals("*/*", passMd5Headers?.get("Accept"))
    assertEquals("bytes=0-", passMd5Headers?.get("Range"))
  }

  @Test
  fun paginaSenzaPassMd5ProduceErroreEsplicito() = runBlocking {
    val extractor = DoodstreamExtractor(
      httpGet = { url, _, _ -> url to "<html>nessun player</html>" },
      randomSuffix = { "0123456789" },
      now = { 0L }
    )
    try {
      extractor.extract("https://dood.la/e/abc")
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("challenge", ignoreCase = true))
    }
  }

  @Test
  fun prefissoPassMd5NonHttpProduceErrore() = runBlocking {
    val embedHtml = """<script>var md5="/pass_md5/abc/def";</script>"""
    val extractor = DoodstreamExtractor(
      httpGet = { url, _, _ ->
        if (url.contains("/pass_md5/")) url to "non-un-url"
        else url to embedHtml
      },
      randomSuffix = { "0123456789" },
      now = { 0L }
    )
    try {
      extractor.extract("https://dood.la/e/abc")
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("Prefisso", ignoreCase = true))
    }
  }
}
