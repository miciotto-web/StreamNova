package com.example.data.streaming.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test offline di Eurostreaming: segmentazione episodi (formati misti e
 * risoluzioni), riconoscimento DL/Download e retry degli articoli.
 */
class EurostreamingProviderTest {

  private val provider = EurostreamingProvider()

  @Test
  fun segmentoConMarkerInizialeSxxExxESuccessivoNxNN() {
    val html = """
      <h2>S01E02</h2>
      <a href="https://stayonline.pro/l/A">Maxstream</a>
      <h2>1x03</h2>
      <a href="https://stayonline.pro/l/B">Maxstream</a>
    """.trimIndent()

    val segment = provider.episodeSegment(html, season = 1, episode = 2).orEmpty()

    assertTrue(segment.contains("stayonline.pro/l/A"))
    assertTrue("il marker successivo deve chiudere il segmento", !segment.contains("stayonline.pro/l/B"))
  }

  @Test
  fun segmentoConMarkerInizialeNxNNESuccessivoSxxExx() {
    val html = """
      <p>1×02</p>
      <a href="https://stayonline.pro/l/A">Maxstream</a>
      <p>S01E03</p>
      <a href="https://stayonline.pro/l/B">Maxstream</a>
    """.trimIndent()

    val segment = provider.episodeSegment(html, 1, 2).orEmpty()

    assertTrue(segment.contains("stayonline.pro/l/A"))
    assertTrue(!segment.contains("stayonline.pro/l/B"))
  }

  @Test
  fun ilMarkerSuccessivoPuoEssereInUnFormatoDiverso() {
    val html = """
      S02E04
      <a href="https://stayonline.pro/l/TARGET">Maxstream</a>
      2x05
      <a href="https://stayonline.pro/l/NEXT">Maxstream</a>
    """.trimIndent()

    val segment = provider.episodeSegment(html, 2, 4).orEmpty()
    assertTrue(segment.contains("/TARGET"))
    assertTrue(!segment.contains("/NEXT"))
  }

  @Test
  fun leRisoluzioniVideoNonSonoMarkerDiEpisodio() {
    val html = """
      1280x720 1920x1080 3840x2160
      1x02
      <a href="https://stayonline.pro/l/A">Maxstream</a>
      720x480
      <a href="https://stayonline.pro/l/B">Maxstream</a>
      1x03
      <a href="https://stayonline.pro/l/C">Maxstream</a>
    """.trimIndent()

    val segment = provider.episodeSegment(html, 1, 2).orEmpty()
    assertTrue(segment.contains("/A"))
    assertTrue("720x480 non deve chiudere l'episodio", segment.contains("/B"))
    assertTrue(!segment.contains("/C"))
  }

  @Test
  fun senzaMarkerSuccessivoIlSegmentoArrivaAFineDocumento() {
    val html = """
      2×01
      <a href="https://stayonline.pro/l/LAST">Maxstream</a>
      <p>fine pagina</p>
    """.trimIndent()

    val segment = provider.episodeSegment(html, 2, 1).orEmpty()
    assertTrue(segment.contains("/LAST"))
    assertTrue(segment.contains("fine pagina"))
  }

  @Test
  fun episodioAssenteRestituisceNull() {
    assertNull(provider.episodeSegment("<p>1x01</p>", 2, 5))
  }

  @Test
  fun labelDlAccettataSoloVersoUnHosterSupportato() {
    val html = """
      <a href="https://maxstream.video/e/OK">DL</a>
      <a href="https://adv.example/banner">Download</a>
      <a href="https://mixdrop.co/e/MIX">Download</a>
    """.trimIndent()

    val urls = provider.parseHosterLinks(html).map { it.second }
    assertTrue(urls.contains("https://maxstream.video/e/OK"))
    assertTrue(urls.contains("https://mixdrop.co/e/MIX"))
    assertTrue("un banner pubblicitario non è una sorgente", urls.none { it.contains("adv.example") })
  }

  @Test
  fun labelNormaleConHosterSupportatoRestaAccettata() {
    val html = """<a href="https://uprot.net/msf/CODE">MaxStream</a>"""

    assertEquals(
      listOf("MaxStream" to "https://uprot.net/msf/CODE"),
      provider.parseHosterLinks(html)
    )
  }

  @Test
  fun decodificaEntitaHtmlNegliAttributi() {
    val html = """<a href="https://stayonline.pro/l/A?a=1&amp;b=2">Maxstream</a>"""

    assertEquals(
      "https://stayonline.pro/l/A?a=1&b=2",
      provider.parseHosterLinks(html).single().second
    )
  }

  @Test
  fun erroreTransitorioConsenteRetrySulMirrorSuccessivo() = runBlocking {
    val b1 = "https://euro-one.test"
    val b2 = "https://euro-two.test"
    val article = "https://euro.shared.test/serie-2020/"
    var articleFetches = 0

    val provider = EurostreamingProvider(listOf(b1, b2)) { url, _ ->
      when {
        url.startsWith("$b1/?s=") || url.startsWith("$b2/?s=") ->
          """<a href="$article">Serie</a>"""
        url == article -> {
          articleFetches++
          "<html><body>risposta incompleta</body></html>"
        }
        else -> "<html></html>"
      }
    }

    runCatching {
      provider.getStreams(1, true, 1, 1, "Serie", 2020)
    }

    assertEquals(2, articleFetches)
  }
}
