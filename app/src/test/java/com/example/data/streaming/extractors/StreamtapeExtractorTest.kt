package com.example.data.streaming.extractors

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test offline di [StreamtapeExtractor]: ricostruzione del link `get_video`
 * dal JavaScript `botlink`, varianti base+suffix, fallback su link completo e
 * risoluzione dell'URL finale senza scaricare il video. Nessuna rete.
 */
class StreamtapeExtractorTest {

  private val origin = "https://streamtape.com"

  @Test
  fun ricostruisceIlLinkDaBaseESuffixConPrefissoRimosso() {
    // Forma del player: il path finisce con '?' e il suffix contiene la query
    // completa preceduta da un prefisso di 4 caratteri rimosso da substring(4).
    val html = """
      <script>
        document.getElementById('botlink').innerHTML =
          'https://streamtape.com/get_video?' +
          ('xxxxid=abc&expires=123&ip=1.2.3.4&token=xyz').substring(4);
      </script>
    """.trimIndent()

    assertEquals(
      "https://streamtape.com/get_video?id=abc&expires=123&ip=1.2.3.4&token=xyz&stream=1",
      StreamtapeExtractor().findVideoUrl(html, origin)
    )
  }

  @Test
  fun laBaseCompletaHaPrioritaSulSuffixTroncato() {
    // Forma "base con query + suffix &stream=1.substring(4)": il suffix
    // diventa "eam=1" e non deve corrompere il token della base.
    val html = """
      <script>
        document.getElementById('botlink').innerHTML =
          'https://streamtape.com/get_video?id=abc&expires=123&ip=1.2.3.4&token=xyz' +
          ('&stream=1').substring(4);
      </script>
    """.trimIndent()

    assertEquals(
      "https://streamtape.com/get_video?id=abc&expires=123&ip=1.2.3.4&token=xyz&stream=1",
      StreamtapeExtractor().findVideoUrl(html, origin)
    )
  }

  @Test
  fun accettaLaVarianteConVirgoletteDoppie() {
    val html = """
      <script>
        document.getElementById("botlink").innerHTML =
          "https://streamtape.com/get_video?" +
          ("xxxxid=abc&expires=123&ip=1.2.3.4&token=xyz").substring(4);
      </script>
    """.trimIndent()

    assertEquals(
      "https://streamtape.com/get_video?id=abc&expires=123&ip=1.2.3.4&token=xyz&stream=1",
      StreamtapeExtractor().findVideoUrl(html, origin)
    )
  }

  @Test
  fun ricostruisceIlLinkQuandoLaQueryStaNelSuffix() {
    val html = """
      <script>
        document.getElementById('botlink').innerHTML =
          'https://streamtape.com/get_video?' + ('id=abc&expires=123&ip=1.2.3.4&token=xyz').substring(0);
      </script>
    """.trimIndent()

    assertEquals(
      "https://streamtape.com/get_video?id=abc&expires=123&ip=1.2.3.4&token=xyz&stream=1",
      StreamtapeExtractor().findVideoUrl(html, origin)
    )
  }

  @Test
  fun accettaUnLinkGetVideoGiaCompleto() {
    val html = """<script src="https://streamtape.com/get_video?id=abc&expires=123&ip=1.2.3.4&token=xyz"></script>"""
    assertEquals(
      "https://streamtape.com/get_video?id=abc&expires=123&ip=1.2.3.4&token=xyz&stream=1",
      StreamtapeExtractor().findVideoUrl(html, origin)
    )
  }

  @Test
  fun linkSenzaTokenNonVieneAccettato() {
    val html = """
      <script>
        document.getElementById('botlink').innerHTML =
          'https://streamtape.com/get_video?id=abc&expires=123' + ('').substring(0);
      </script>
    """.trimIndent()
    assertNull(StreamtapeExtractor().findVideoUrl(html, origin))
  }

  @Test
  fun estraeLaSorgenteSenzaScaricareIlVideo() = runBlocking {
    val html = """
      <script>
        document.getElementById('botlink').innerHTML =
          'https://streamtape.com/get_video?' +
          ('xxxxid=abc&expires=123&ip=1.2.3.4&token=xyz').substring(4);
      </script>
    """.trimIndent()
    var resolvedInput: String? = null

    val extractor = StreamtapeExtractor(
      httpGet = { _, _ -> html },
      resolveFinalUrl = { url, _ ->
        resolvedInput = url
        "https://cdn.example/video.mp4"
      }
    )

    val source = extractor.extract("https://streamtape.com/e/abc").single()

    assertEquals(
      "https://streamtape.com/get_video?id=abc&expires=123&ip=1.2.3.4&token=xyz&stream=1",
      resolvedInput
    )
    assertEquals("https://cdn.example/video.mp4", source.streamUrl)
    assertEquals("Streamtape", source.serverName)
    assertEquals("https://streamtape.com/", source.headers["Referer"])
    assertEquals(ExtractorHttp.USER_AGENT, source.headers["User-Agent"])
    assertTrue(source.isProgressive == true)
  }

  @Test
  fun paginaSenzaBotlinkProduceErroreEsplicito() = runBlocking {
    val extractor = StreamtapeExtractor(
      httpGet = { _, _ -> "<html>nessun player</html>" },
      resolveFinalUrl = { _, _ -> error("non deve essere chiamato") }
    )
    try {
      extractor.extract("https://streamtape.com/e/abc")
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("challenge", ignoreCase = true))
    }
  }
}
