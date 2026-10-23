package com.example.data.streaming.extractors

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test offline degli estrattori VidHide/Filemoon.
 *
 * I fixture riproducono le forme note dei player (blocco packed Dean Edwards,
 * `sources: [{file: ...}]`, API Filemoon con payload AES-256-GCM). Il server
 * locale esercita il percorso HTTP reale di [ExtractorHttp] senza uscire in
 * rete. La verifica su un video pubblico è nel test live di
 * `HosterExtractorTest`.
 */
class HosterPlayerExtractorTest {

  // --- VidHide -------------------------------------------------------------

  @Test
  fun vidHideNormalizzaGliAliasDellaPaginaPlayer() {
    val extractor = VidHideExtractor()
    assertEquals("https://vidhide.com/v/abc", extractor.toEmbedUrl("https://vidhide.com/f/abc"))
    assertEquals("https://vidhide.com/v/abc", extractor.toEmbedUrl("https://vidhide.com/d/abc"))
    assertEquals("https://vidhide.com/v/abc", extractor.toEmbedUrl("https://vidhide.com/download/abc"))
    assertEquals("https://vidhide.com/v/abc", extractor.toEmbedUrl("https://vidhide.com/file/abc"))
    assertEquals("https://vidhide.com/v/abc", extractor.toEmbedUrl("https://vidhide.com/embed/abc"))
    assertEquals("https://vidhide.com/v/abc", extractor.toEmbedUrl("https://vidhide.com/s/abc"))
    assertEquals("https://vidhide.com/e/abc", extractor.toEmbedUrl("https://vidhide.com/e/abc"))
  }

  @Test
  fun parserDecodificaIlBloccoPacked() {
    val media = "https://cdn.example/hls/master.m3u8?token=abc"
    val packed = packedBlock(
      payload = "0:[{1:\"3\"}]",
      words = "sources|file|unused|$media"
    )
    val urls = PlayerSourceParser.findMediaUrls(
      "<html><script>$packed</script></html>",
      "https://vidhide.com/e/abc"
    )
    assertEquals(listOf(media), urls)
  }

  @Test
  fun parserAccettaVariantiDiSpaziaturaDelPacker() {
    val media = "https://cdn.example/master.m3u8"
    val packed = """eval (function (p, a, c, k, e, r){return p}('0 1:"2"',4,3,'sources|file|$media'.split('|'),0,{}))"""
    assertEquals(
      listOf(media),
      PlayerSourceParser.findMediaUrls("<script>$packed</script>", "https://vidhide.com/v/abc")
    )
  }

  @Test
  fun parserLeggeSourcesDirettoERisolveIPercorsiRelativi() {
    val absolute = "https://cdn.example/video/master.m3u8?t=1"
    val html = """<script>jwplayer("vplayer").setup({sources:[{file:"$absolute"}]})</script>"""
    assertEquals(
      listOf(absolute),
      PlayerSourceParser.findMediaUrls(html, "https://vidhide.com/e/abc")
    )

    val relative = """<script>sources:[{file:"/stream/abc/master.m3u8"}]</script>"""
    assertEquals(
      listOf("https://vidhide.com/stream/abc/master.m3u8"),
      PlayerSourceParser.findMediaUrls(relative, "https://vidhide.com/e/abc")
    )

    val protocolRelative = """<script>sources:[{file:"//cdn.example/x/master.m3u8"}]</script>"""
    assertEquals(
      listOf("https://cdn.example/x/master.m3u8"),
      PlayerSourceParser.findMediaUrls(protocolRelative, "https://vidhide.com/e/abc")
    )
  }

  @Test
  fun parserPreferisceHlsEScartaISottotitoli() {
    val html = """
      <script>
        sources: [
          {file: "https://cdn.example/video.mp4"},
          {file: "https://cdn.example/video.m3u8"},
          {file: "https://cdn.example/subtitles.vtt"}
        ]
      </script>
    """.trimIndent()
    assertEquals(
      listOf("https://cdn.example/video.m3u8", "https://cdn.example/video.mp4"),
      PlayerSourceParser.findMediaUrls(html, "https://vidhide.com/e/abc")
    )
    assertTrue(PlayerSourceParser.findMediaUrls("<html></html>", "https://vidhide.com/e/abc").isEmpty())
  }

  @Test
  fun vidHideEstraeLaSorgenteDallaPaginaEmbed() {
    val media = "https://cdn.example/hls/master.m3u8"
    val page = "<html><script>sources:[{file:\"$media\"}]</script></html>"
    FakeSite(mapOf("/v/abc" to { 200 to page })).use { site ->
      val sources = runBlocking { VidHideExtractor().extract("${site.baseUrl}/f/abc") }

      assertEquals(1, sources.size)
      assertEquals(media, sources.single().streamUrl)
      assertEquals("VidHide", sources.single().serverName)
      assertEquals("${site.baseUrl}/", sources.single().headers["Referer"])
      assertEquals(site.baseUrl, sources.single().headers["Origin"])
      assertTrue(sources.single().headers.containsKey("User-Agent"))
      assertTrue(site.paths().contains("/v/abc"))
    }
  }

  @Test
  fun vidHideSegnalaEsplicitamenteUnaPaginaSenzaSorgenti() {
    FakeSite(mapOf("/v/abc" to { 200 to "<html><body>player non disponibile</body></html>" })).use { site ->
      val error = failWith { VidHideExtractor().extract("${site.baseUrl}/v/abc") }
      assertTrue(
        "messaggio inatteso: ${error.message}",
        error.message!!.contains("Nessuna sorgente VidHide")
      )
    }
  }

  // --- Filemoon ------------------------------------------------------------

  @Test
  fun filemoonEstraeIlCodiceVideo() {
    val extractor = FilemoonExtractor()
    assertEquals("vxkeptfavoa6", extractor.videoCode("https://filemoon.to/e/vxkeptfavoa6"))
    assertEquals("vxkeptfavoa6", extractor.videoCode("https://filemoon.to/d/vxkeptfavoa6"))
    assertEquals("vxkeptfavoa6", extractor.videoCode("https://filemoon.sx/vxkeptfavoa6"))
    assertNull(extractor.videoCode("https://filemoon.to/"))
    assertNull(extractor.videoCode("non un url"))
  }

  /**
   * Riproduce la selezione della chiave del player: con `version = 17` e 30
   * parti si usano le parti 17 e 14; il payload reale è AES-256-GCM.
   */
  @Test
  fun filemoonDecifraIlPayloadAesGcm() {
    val plaintext = """{"sources":[{"mime_type":"application/vnd.apple.mpegurl","url":"https://cdn.example/master.m3u8"}]}"""
    val (playback, _) = playbackFixture(plaintext, version = "17")

    val decoded = FilemoonExtractor().decryptPlayback(playback)

    assertEquals(plaintext, decoded)
    assertEquals(
      "https://cdn.example/master.m3u8",
      FilemoonExtractor().pickMediaUrl(decoded)
    )
  }

  @Test
  fun filemoonSenzaVersioneUsaTutteLePartiDellaChiave() {
    val plaintext = """{"sources":[{"mime_type":"application/vnd.apple.mpegurl","url":"https://cdn.example/master.m3u8"}]}"""
    val (playback, _) = playbackFixture(plaintext, version = null, compactKeyParts = true)

    assertEquals(plaintext, FilemoonExtractor().decryptPlayback(playback))
  }

  @Test
  fun filemoonDecodificaGliEscapeUnicodeDegliUrlFirmati() {
    // Il payload reale contiene gli '&' del token CDN come \u0026.
    val payload = "{\"sources\":[{\"mime_type\":\"application/vnd.apple.mpegurl\"," +
      "\"url\":\"https://cdn.example/master.m3u8?t=abc\\u0026s=1792590249\"}]}"
    assertEquals(
      "https://cdn.example/master.m3u8?t=abc&s=1792590249",
      FilemoonExtractor().pickMediaUrl(payload)
    )
  }

  @Test
  fun filemoonSceglieHlsPrimaDiMp4() {
    val payload = """
      {"sources":[
        {"mime_type":"video/mp4","url":"https://cdn.example/video.mp4"},
        {"mime_type":"application/vnd.apple.mpegurl","url":"https://cdn.example/master.m3u8"}
      ]}
    """.trimIndent()
    assertEquals(
      "https://cdn.example/master.m3u8",
      FilemoonExtractor().pickMediaUrl(payload)
    )
  }

  @Test
  fun filemoonEstraeDallApiRealeDelPlayer() {
    val plaintext = """{"sources":[{"mime_type":"application/vnd.apple.mpegurl","url":"https://cdn.example/master.m3u8"}]}"""
    val (playback, _) = playbackFixture(plaintext, version = "17")
    val apiBody = apiJson(playback)

    FakeSite(mapOf("/api/videos/abc123" to { 200 to apiBody })).use { site ->
      val sources = runBlocking { FilemoonExtractor().extract("${site.baseUrl}/e/abc123") }

      assertEquals(1, sources.size)
      assertEquals("https://cdn.example/master.m3u8", sources.single().streamUrl)
      assertEquals("Filemoon", sources.single().serverName)
      assertEquals("${site.baseUrl}/e/abc123", sources.single().headers["Referer"])
      assertTrue(site.paths().contains("/api/videos/abc123"))
    }
  }

  @Test
  fun filemoonRiportaUnErroreEsplicitoSeApiEPaginaFalliscono() {
    FakeSite(emptyMap()).use { site ->
      val error = failWith { FilemoonExtractor().extract("${site.baseUrl}/e/abc123") }
      assertTrue("messaggio inatteso: ${error.message}", error.message!!.contains("Filemoon non risolvibile"))
      assertTrue("dettaglio API mancante: ${error.message}", error.message!!.contains("API"))
    }
  }

  // --- helper --------------------------------------------------------------

  /** Blocco packed con il formato atteso da [JsPacked]. */
  private fun packedBlock(payload: String, words: String): String =
    """eval(function(p,a,c,k,e,d){while(c--){if(k[c])p=p.replace(new RegExp('\\b'+c+'\\b','g'),k[c])}return p}('$payload',4,4,'$words'.split('|'),0,{}))"""

  /** Payload Filemoon cifrato con una chiave composta da due parti da 16 byte. */
  private fun playbackFixture(
    plaintext: String,
    version: String?,
    compactKeyParts: Boolean = false
  ): Pair<PlaybackPayload, ByteArray> {
    val key = ByteArray(32) { (it * 7 + 3).toByte() }
    val iv = ByteArray(12) { (it * 5 + 1).toByte() }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.ENCRYPT_MODE,
      SecretKeySpec(key, "AES"),
      GCMParameterSpec(128, iv)
    )
    val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

    val first = Base64.getUrlEncoder().withoutPadding().encodeToString(key.copyOfRange(0, 16))
    val second = Base64.getUrlEncoder().withoutPadding().encodeToString(key.copyOfRange(16, 32))
    val parts = if (compactKeyParts) {
      listOf(first, second)
    } else {
      MutableList(30) { "AAAA" }.also {
        it[16] = first
        it[13] = second
      }
    }
    val playback = PlaybackPayload(
      version = version,
      keyParts = parts,
      iv = Base64.getUrlEncoder().withoutPadding().encodeToString(iv),
      payload = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
    )
    return playback to key
  }

  private fun apiJson(playback: PlaybackPayload): String {
    val parts = playback.keyParts.joinToString(",") { "\"$it\"" }
    val version = playback.version?.let { "\"$it\"" } ?: "null"
    return """
      {"id":1,"code":"abc123","playback":{
        "algorithm":"AES-256-GCM",
        "version":$version,
        "key_parts":[$parts],
        "iv":"${playback.iv}",
        "payload":"${playback.payload}"
      }}
    """.trimIndent()
  }

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
