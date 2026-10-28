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
 * Test offline dell'estrattore StreamWish: pagina player JWPlayer-like con
 * `sources:[{file:...}]`, blocco packed Dean-Edwards, redirect della pagina
 * download, errori HTTP espliciti, pagina vuota, pagina senza sorgenti e URL
 * malformato. Il server locale esercita il percorso HTTP reale di
 * [ExtractorHttp] (redirect follow e status di errore) senza uscire in rete.
 */
class StreamWishExtractorTest {

  @Test
  fun streamWishEstraeLaSorgenteDallaPaginaEmbed() {
    val media = "https://cdn.example/hls/master.m3u8"
    val page = "<html><script>jwplayer(\"vplayer\").setup({sources:[{file:\"$media\"}]})</script></html>"
    FakeSite(mapOf("/e/abc" to { 200 to page })).use { site ->
      val sources = runBlocking { StreamWishExtractor().extract("${site.baseUrl}/e/abc") }

      assertEquals(1, sources.size)
      assertEquals(media, sources.single().streamUrl)
      assertEquals("StreamWish", sources.single().serverName)
      assertEquals("Auto", sources.single().quality)
      assertEquals("${site.baseUrl}/", sources.single().headers["Referer"])
      assertEquals(site.baseUrl, sources.single().headers["Origin"])
      assertEquals(ExtractorHttp.USER_AGENT, sources.single().headers["User-Agent"])
      assertTrue(site.paths().contains("/e/abc"))
    }
  }

  @Test
  fun streamWishUsaLUrlFinaleDopoIlRedirectDellaPaginaDownload() {
    // La pagina /d/{code} redirige sull'embed /e/{code}: gli header della
    // sorgente devono essere coerenti con l'URL FINALE, non con quello iniziale.
    val media = "https://cdn.example/hls/master.m3u8"
    val page = "<html><script>sources:[{file:\"$media\"}]</script></html>"
    FakeSite(
      routes = mapOf(
        "/d/abc" to { 302 to "redirect" },
        "/e/abc" to { 200 to page }
      ),
      responseHeaders = mapOf("/d/abc" to mapOf("Location" to "/e/abc"))
    ).use { site ->
      val source = runBlocking { StreamWishExtractor().extract("${site.baseUrl}/d/abc") }.single()

      assertEquals(media, source.streamUrl)
      assertEquals("${site.baseUrl}/", source.headers["Referer"])
      assertEquals(site.baseUrl, source.headers["Origin"])
      assertTrue(site.paths().contains("/d/abc"))
      assertTrue(site.paths().contains("/e/abc"))
    }
  }

  @Test
  fun streamWishPreferisceHlsSuMp4() {
    // Nel markup l'MP4 compare prima: il parser ordina HLS prima dei progressivi.
    val html = """
      <script>
        sources: [
          {file: "https://cdn.example/video.mp4"},
          {file: "https://cdn.example/video.m3u8"}
        ]
      </script>
    """.trimIndent()
    FakeSite(mapOf("/e/abc" to { 200 to html })).use { site ->
      val source = runBlocking { StreamWishExtractor().extract("${site.baseUrl}/e/abc") }.single()
      assertEquals("https://cdn.example/video.m3u8", source.streamUrl)
    }
  }

  @Test
  fun streamWishDecodificaIlBloccoPacked() {
    // Deployment concorrente di StreamWish: sorgente solo dentro l'eval packed.
    val media = "https://cdn.example/hls/master.m3u8?token=abc"
    val packed = packedBlock(
      payload = "0:[{1:\"3\"}]",
      words = "sources|file|unused|$media"
    )
    val page = "<html><script>$packed</script></html>"
    FakeSite(mapOf("/e/abc" to { 200 to page })).use { site ->
      val source = runBlocking { StreamWishExtractor().extract("${site.baseUrl}/e/abc") }.single()
      assertEquals(media, source.streamUrl)
    }
  }

  @Test
  fun streamWishErroreHttpEsplicito() {
    FakeSite(emptyMap()).use { site ->
      val error = failWith { StreamWishExtractor().extract("${site.baseUrl}/e/abc") }
      assertTrue("messaggio inatteso: ${error.message}", error.message!!.contains("HTTP 404"))
    }
  }

  @Test
  fun streamWishSegnalaPaginaVuota() {
    FakeSite(mapOf("/e/abc" to { 200 to "" })).use { site ->
      val error = failWith { StreamWishExtractor().extract("${site.baseUrl}/e/abc") }
      assertTrue(
        "messaggio inatteso: ${error.message}",
        error.message!!.contains("Pagina embed StreamWish vuota")
      )
    }
  }

  @Test
  fun streamWishSegnalaPaginaSenzaSorgenti() {
    // Player protetto da challenge o markup cambiato: errore esplicito, il
    // messaggio contiene "challenge" per la classificazione BLOCKED_BY_CHALLENGE.
    val page = "<html><body>player non disponibile</body></html>"
    FakeSite(mapOf("/e/abc" to { 200 to page })).use { site ->
      val error = failWith { StreamWishExtractor().extract("${site.baseUrl}/e/abc") }
      assertTrue(
        "messaggio inatteso: ${error.message}",
        error.message!!.contains("Nessuna sorgente StreamWish")
      )
      assertTrue(
        "serve la parola challenge per la classificazione diagnostica",
        error.message!!.contains("challenge")
      )
    }
  }

  @Test
  fun streamWishUrlMalformatoEsplicito() {
    val error = failWith { StreamWishExtractor().extract("non un url") }
    assertTrue("messaggio inatteso: ${error.message}", error.message!!.contains("URL non valido"))
  }

  // --- helper --------------------------------------------------------------

  private fun failWith(block: suspend () -> Unit): Throwable {
    val result = runCatching { runBlocking { block() } }
    result.getOrNull()?.let { fail("atteso errore, nessuna eccezione") }
    return result.exceptionOrNull()!!
  }

  /** Blocco packed con il formato atteso da [JsPacked]. */
  private fun packedBlock(payload: String, words: String): String =
    """eval(function(p,a,c,k,e,d){while(c--){if(k[c])p=p.replace(new RegExp('\\b'+c+'\\b','g'),k[c])}return p}('$payload',4,4,'$words'.split('|'),0,{}))"""

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
