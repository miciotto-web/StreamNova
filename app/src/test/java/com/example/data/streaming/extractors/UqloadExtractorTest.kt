package com.example.data.streaming.extractors

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test offline di [UqloadExtractor]: parsing del blocco packed, fallback
 * `file:` in chiaro, normalizzazione del codice e percorso POST `/dl`.
 * Nessuna richiesta di rete.
 */
class UqloadExtractorTest {

  @Test
  fun decodificaIlBloccoPackedETrovaLaSorgente() {
    val packed = """
      eval(function(p,a,c,k,e,d){return p}('0:"1"',10,2,'file|https://cdn.example/video.mp4'.split('|'),0,{}))
    """.trimIndent()

    assertEquals(
      "https://cdn.example/video.mp4",
      UqloadExtractor().findSourceUrl(packed)
    )
  }

  @Test
  fun accettaFileInChiaroQuandoIlPlayerNonEPacked() {
    val html = """<script>player.setup({file: "https://cdn.example/master.m3u8"});</script>"""
    assertEquals(
      "https://cdn.example/master.m3u8",
      UqloadExtractor().findSourceUrl(html)
    )
  }

  @Test
  fun normalizzaUrlProtocolRelative() {
    val html = """<script>file: "//cdn.example/video.mp4"</script>"""
    assertEquals(
      "https://cdn.example/video.mp4",
      UqloadExtractor().findSourceUrl(html)
    )
  }

  @Test
  fun estraeIlCodiceDaEeDaEmbedHtml() {
    val extractor = UqloadExtractor()
    assertEquals("qi0r75y9o5eu", extractor.fileCode("https://uqload.vc/e/qi0r75y9o5eu"))
    assertEquals("abc123", extractor.fileCode("https://uqload.cx/embed-abc123.html"))
    assertNull(extractor.fileCode("not-a-url"))
  }

  @Test
  fun ilPercorsoEPostDlConRefererDellHoster() = runBlocking {
    val packed = """
      eval(function(p,a,c,k,e,d){return p}('0:"1"',10,2,'file|https://cdn.example/video.mp4'.split('|'),0,{}))
    """.trimIndent()
    var postUrl: String? = null
    var postForm: Map<String, String>? = null
    var getCalls = 0

    val extractor = UqloadExtractor(
      httpGet = { _, _, _ ->
        getCalls++
        error("GET inatteso")
      },
      httpPost = { url, _, form, _ ->
        postUrl = url
        postForm = form
        packed
      }
    )

    val sources = extractor.extract("https://uqload.vc/e/qi0r75y9o5eu")

    assertEquals(0, getCalls)
    assertEquals("https://uqload.vc/dl", postUrl)
    assertEquals("embed", postForm?.get("op"))
    assertEquals("qi0r75y9o5eu", postForm?.get("file_code"))
    assertEquals("1", postForm?.get("auto"))
    assertEquals("https://uqload.vc/", postForm?.get("referer"))
    assertEquals(1, sources.size)
    assertEquals("https://cdn.example/video.mp4", sources.single().streamUrl)
    assertEquals("Uqload", sources.single().serverName)
    assertEquals("https://cdn.example/", sources.single().headers["Referer"])
    assertEquals(ExtractorHttp.USER_AGENT, sources.single().headers["User-Agent"])
    assertTrue(sources.single().isProgressive == true)
  }

  @Test
  fun ilPercorsoEmbedHtmlUsaGet() = runBlocking {
    var getUrl: String? = null
    val extractor = UqloadExtractor(
      httpGet = { url, _, _ ->
        getUrl = url
        """<script>file: "https://cdn.example/video.mp4"</script>"""
      },
      httpPost = { _, _, _, _ -> error("POST inatteso") }
    )

    val source = extractor.extract("https://uqload.cx/embed-abc123.html").single()
    assertEquals("https://uqload.cx/embed-abc123.html", getUrl)
    assertEquals("https://cdn.example/video.mp4", source.streamUrl)
  }

  @Test
  fun paginaSenzaSorgenteProduceErroreEsplicito() = runBlocking {
    val extractor = UqloadExtractor(
      httpGet = { _, _, _ -> "<html>nessun player</html>" },
      httpPost = { _, _, _, _ -> "<html>nessun player</html>" }
    )
    try {
      extractor.extract("https://uqload.vc/e/abc")
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("challenge", ignoreCase = true))
    }
  }
}
