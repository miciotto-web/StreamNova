package com.example.data.streaming.extractors

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test offline della normalizzazione degli alias VidHide e del parser delle
 * sorgenti player.
 *
 * Copre i casi limite del refactoring: riscrittura del solo primo segmento
 * del path (query string, fragment e segmenti successivi intatti), chiave
 * JSON `profile` scambiata per `file`, ordinamento HLS prima degli MP4 e
 * delimitatori JSON che non devono finire nell'URL estratto. Il server locale
 * esercita il percorso HTTP reale di [ExtractorHttp] (redirect e status di
 * errore) senza uscire in rete.
 */
class VidHideSourceParserTest {

  // --- normalizzazione degli alias ----------------------------------------

  @Test
  fun aliasNormalizzatoSoloNelPrimoSegmento() {
    val extractor = VidHideExtractor()
    assertEquals("https://vidhide.com/v/abc", extractor.toEmbedUrl("https://vidhide.com/f/abc"))
    assertEquals("https://vidhide.com/v/abc", extractor.toEmbedUrl("https://vidhide.com/download/abc"))
    assertEquals("https://vidhide.com/v/abc", extractor.toEmbedUrl("https://vidhide.com/v/abc"))
    assertEquals("https://vidhide.com/e/abc", extractor.toEmbedUrl("https://vidhide.com/e/abc"))
  }

  @Test
  fun aliasNellaQueryNonVieneMancato() {
    val url = "https://vidhide.com/v/abc?next=/f/x"
    assertEquals(url, VidHideExtractor().toEmbedUrl(url))
  }

  @Test
  fun aliasNelFragmentNonVieneMancato() {
    val url = "https://vidhide.com/v/abc#/f/x"
    assertEquals(url, VidHideExtractor().toEmbedUrl(url))
  }

  @Test
  fun aliasNonPrimoSegmentoNonVieneMancato() {
    val url = "https://vidhide.com/v/abc/f/"
    assertEquals(url, VidHideExtractor().toEmbedUrl(url))
  }

  @Test
  fun percorsoConQueryEFragmentoPreservati() {
    assertEquals(
      "https://vidhide.com/v/abc?x=1#y",
      VidHideExtractor().toEmbedUrl("https://vidhide.com/f/abc?x=1#y")
    )
  }

  @Test
  fun urlMalformatoRestaInvariato() {
    val extractor = VidHideExtractor()
    assertEquals("non un url", extractor.toEmbedUrl("non un url"))
    assertEquals("", extractor.toEmbedUrl(""))
    assertEquals("//vidhide.com/f/abc", extractor.toEmbedUrl("//vidhide.com/f/abc"))
  }

  // --- parser delle sorgenti ----------------------------------------------

  @Test
  fun parserNonConfondeProfileConFile() {
    val urls = PlayerSourceParser.findMediaUrls(
      "{\"profile\":\"https://cdn.example/x.m3u8\"}",
      "https://vidhide.com/e/abc"
    )
    assertTrue("sorgenti inattese: $urls", urls.isEmpty())
  }

  @Test
  fun parserNonConfondeProfileConSpazioDopoIDuePunti() {
    // Stessa garanzia anche con la spaziatura JSON `"profile": "..."`.
    val urls = PlayerSourceParser.findMediaUrls(
      "{\"profile\": \"https://cdn.example/x.m3u8\"}",
      "https://vidhide.com/e/abc"
    )
    assertTrue("sorgenti inattese: $urls", urls.isEmpty())
  }

  @Test
  fun parserRiconosceLeChiaviComposte() {
    // Forme usate dai vari deployment: con e senza separatore, camelCase.
    assertEquals(
      listOf("https://cdn.example/a.m3u8"),
      PlayerSourceParser.findMediaUrls(
        "{\"hls_url\":\"https://cdn.example/a.m3u8\"}",
        "https://vidhide.com/e/abc"
      )
    )
    assertEquals(
      listOf("https://cdn.example/b.m3u8"),
      PlayerSourceParser.findMediaUrls(
        "{hlsUrl: \"https://cdn.example/b.m3u8\"}",
        "https://vidhide.com/e/abc"
      )
    )
    assertEquals(
      listOf("https://cdn.example/c.m3u8"),
      PlayerSourceParser.findMediaUrls(
        "{\"video_src\":\"https://cdn.example/c.m3u8\"}",
        "https://vidhide.com/e/abc"
      )
    )
  }

  @Test
  fun parserRiconosceFileComeChiave() {
    assertEquals(
      listOf("https://cdn.example/master.m3u8"),
      PlayerSourceParser.findMediaUrls(
        "sources:[{file:\"https://cdn.example/master.m3u8\"}]",
        "https://vidhide.com/e/abc"
      )
    )
    assertEquals(
      listOf("https://cdn.example/master.m3u8"),
      PlayerSourceParser.findMediaUrls(
        "\"file\":\"https://cdn.example/master.m3u8\"",
        "https://vidhide.com/e/abc"
      )
    )
  }

  @Test
  fun parserDeduplicaLeSorgenti() {
    val media = "https://cdn.example/x.m3u8"
    val html = "<script>sources:[{file:\"$media\"}] $media</script>"
    val urls = PlayerSourceParser.findMediaUrls(html, "https://vidhide.com/e/abc")
    assertEquals(listOf(media), urls)
  }

  @Test
  fun parserNonIncludereIlDelimiterNelUrl() {
    // Valore quotato: il delimitatore JSON non deve entrare nell'URL.
    assertEquals(
      listOf("https://cdn.example/x.m3u8?t=1"),
      PlayerSourceParser.findMediaUrls(
        "{\"url\":\"https://cdn.example/x.m3u8?t=1\"}",
        "https://vidhide.com/e/abc"
      )
    )
    // Valore non quotato: la graffa di chiusura resta fuori dall'URL.
    assertEquals(
      listOf("https://cdn.example/x.m3u8?t=1"),
      PlayerSourceParser.findMediaUrls(
        "{url:https://cdn.example/x.m3u8?t=1}",
        "https://vidhide.com/e/abc"
      )
    )
    // Stessa cosa con la parentesi quadra di un array JavaScript.
    assertEquals(
      listOf("https://cdn.example/x.m3u8?t=1"),
      PlayerSourceParser.findMediaUrls(
        "[https://cdn.example/x.m3u8?t=1]",
        "https://vidhide.com/e/abc"
      )
    )
  }

  // --- percorso HTTP reale --------------------------------------------------

  @Test
  fun vidHideUsaLUrlFinaleDopoRedirect() {
    val media = "https://cdn.example/hls/master.m3u8"
    val page = "<html><script>sources:[{file:\"$media\"}]</script></html>"
    FakeSite(
      routes = mapOf(
        "/f/abc" to { 302 to "redirect" },
        "/e/abc" to { 302 to "redirect" },
        "/v/abc" to { 200 to page }
      ),
      responseHeaders = mapOf(
        "/f/abc" to mapOf("Location" to "/v/abc"),
        "/e/abc" to mapOf("Location" to "/v/abc")
      )
    ).use { site ->
      // `/f/` è un alias: la richiesta va diretta alla pagina player.
      val normalized = runBlocking { VidHideExtractor().extract("${site.baseUrl}/f/abc") }
      assertEquals(1, normalized.size)
      assertEquals(media, normalized.single().streamUrl)
      assertEquals("${site.baseUrl}/", normalized.single().headers["Referer"])

      // `/e/` non è un alias: la richiesta segue il redirect e usa l'URL finale.
      val redirected = runBlocking { VidHideExtractor().extract("${site.baseUrl}/e/abc") }
      assertEquals(1, redirected.size)
      assertEquals(media, redirected.single().streamUrl)
      assertEquals("${site.baseUrl}/", redirected.single().headers["Referer"])
      assertTrue(site.paths().contains("/e/abc"))
      assertTrue(site.paths().contains("/v/abc"))
    }
  }

  @Test
  fun vidHideErroreHttpEsplicito() {
    FakeSite(emptyMap()).use { site ->
      val error = failWith { VidHideExtractor().extract("${site.baseUrl}/v/abc") }
      assertTrue("messaggio inatteso: ${error.message}", error.message!!.contains("HTTP 404"))
    }
  }

  @Test
  fun vidHideRispettaGliHeaderRichiesti() {
    val media = "https://cdn.example/hls/master.m3u8"
    val page = "<html><script>sources:[{file:\"$media\"}]</script></html>"
    FakeSite(mapOf("/v/abc" to { 200 to page })).use { site ->
      val source = runBlocking { VidHideExtractor().extract("${site.baseUrl}/v/abc") }.single()
      assertEquals("${site.baseUrl}/", source.headers["Referer"])
      assertEquals(site.baseUrl, source.headers["Origin"])
      assertEquals(ExtractorHttp.USER_AGENT, source.headers["User-Agent"])
    }
  }

  // --- helper --------------------------------------------------------------

  private fun failWith(block: suspend () -> Unit): Throwable {
    val result = runCatching { runBlocking { block() } }
    result.getOrNull()?.let { fail("atteso errore, nessuna eccezione") }
    return result.exceptionOrNull()!!
  }

  /** Server HTTP locale: i percorsi non mappati rispondono 404. */
  private class FakeSite(
    private val routes: Map<String, () -> Pair<Int, String>>,
    /** Header aggiuntivi della risposta, indicizzati per percorso. */
    private val responseHeaders: Map<String, Map<String, String>> = emptyMap()
  ) : Closeable {

    private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
      server.createContext("/") { exchange ->
        val path = exchange.requestURI.path
        recorded += path
        val response = routes[path]?.invoke() ?: (404 to "not found")
        val bytes = response.second.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html")
        responseHeaders[path]?.forEach { (name, value) ->
          exchange.responseHeaders.add(name, value)
        }
        exchange.sendResponseHeaders(response.first, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.executor = null
      server.start()
    }

    fun paths(): List<String> = recorded.toList()

    override fun close() = server.stop(0)
  }
}
