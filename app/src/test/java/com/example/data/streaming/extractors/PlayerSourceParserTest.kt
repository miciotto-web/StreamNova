package com.example.data.streaming.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser condiviso delle sorgenti player: fixture offline con le forme viste
 * negli embed (HLS, MP4, relativi, protocol-relative, entità HTML).
 */
class PlayerSourceParserTest {

  private val base = "https://embed.test/e/abc"

  @Test
  fun estraeHlsDaOggettoSources() {
    val html = """
      <script>
        jwplayer("v").setup({
          sources: [{ file: "https://cdn.example.test/master.m3u8?token=1" }]
        });
      </script>
    """.trimIndent()

    assertEquals(
      listOf("https://cdn.example.test/master.m3u8?token=1"),
      PlayerSourceParser.findMediaUrls(html, base)
    )
  }

  @Test
  fun estraeMp4DaFile() {
    val html = """<script>var player = { file: "https://cdn.example.test/movie.mp4" };</script>"""

    assertEquals(
      listOf("https://cdn.example.test/movie.mp4"),
      PlayerSourceParser.findMediaUrls(html, base)
    )
  }

  @Test
  fun risolveUrlRelativiSullOriginDellaPagina() {
    val html = """<video src="/media/video.mp4"></video>"""

    assertEquals(
      listOf("https://embed.test/media/video.mp4"),
      PlayerSourceParser.findMediaUrls(html, base)
    )
  }

  @Test
  fun risolveUrlProtocolRelative() {
    val html = """<script>sources: [{ file: "//cdn.example.test/master.m3u8" }]</script>"""

    assertEquals(
      listOf("https://cdn.example.test/master.m3u8"),
      PlayerSourceParser.findMediaUrls(html, base)
    )
  }

  @Test
  fun decodificaUE0026EEntitaHtmlNellaQuery() {
    val html = """
      <script>
        var a = "https://cdn.example.test/a.m3u8?x=1\u0026y=2";
        var b = "https://cdn.example.test/b.mp4?x=1&amp;y=2";
      </script>
    """.trimIndent()

    val urls = PlayerSourceParser.findMediaUrls(html, base)
    assertTrue(urls.contains("https://cdn.example.test/a.m3u8?x=1&y=2"))
    assertTrue(urls.contains("https://cdn.example.test/b.mp4?x=1&y=2"))
  }

  @Test
  fun hlsPrimaDegliMp4() {
    val html = """
      <video src="https://cdn.example.test/file.mp4"></video>
      <script>file: "https://cdn.example.test/master.m3u8"</script>
    """.trimIndent()

    val urls = PlayerSourceParser.findMediaUrls(html, base)
    assertEquals("https://cdn.example.test/master.m3u8", urls.first())
  }

  @Test
  fun nonInventaSorgentiDaUnaPaginaSenzaMedia() {
    val html = "<html><body><p>Nessun player</p></body></html>"
    assertTrue(PlayerSourceParser.findMediaUrls(html, base).isEmpty())
  }
}
