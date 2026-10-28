package com.example.data.streaming.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test offline del provider CB01: filtri episodio, decodifica entità e retry
 * controllato degli articoli. Nessuna richiesta verso Internet.
 */
class Cb01ProviderTest {

  private val provider = Cb01Provider()

  private fun episodePage(): String = """
    <html><body>
      <a href="https://cb01.test/serie/">Serie</a>
      <h3>S01E01</h3>
      <a href="https://stayonline.pro/l/EP1">Maxstream</a>
      <h3>S01E02</h3>
      <a href="https://stayonline.pro/l/EP2">Maxstream</a>
      <h3>S01E03</h3>
      <a href="https://stayonline.pro/l/EP3">Maxstream</a>
    </body></html>
  """.trimIndent()

  @Test
  fun selezionaSoloLEpisodioRichiesto() {
    val links = provider.parseHosterLinksForEpisode(episodePage(), isTv = true, season = 1, episode = 2)
    assertEquals(listOf("Maxstream" to "https://stayonline.pro/l/EP2"), links)
  }

  @Test
  fun nonRestituisceEpisodiAdiacenti() {
    val links = provider.parseHosterLinksForEpisode(episodePage(), isTv = true, season = 1, episode = 1)
    assertEquals(listOf("Maxstream" to "https://stayonline.pro/l/EP1"), links)
  }

  @Test
  fun stagioneDifferenteNonTrovaLEpisodio() {
    assertTrue(
      provider.parseHosterLinksForEpisode(episodePage(), isTv = true, season = 2, episode = 2).isEmpty()
    )
  }

  @Test
  fun riconosceMarkerConXEMoltiplicazione() {
    val page = """
      <h3>1x01</h3><a href="https://stayonline.pro/l/A">Maxstream</a>
      <h3>1x02</h3><a href="https://stayonline.pro/l/B">Maxstream</a>
      <h3>1×03</h3><a href="https://stayonline.pro/l/C">Maxstream</a>
    """.trimIndent()

    assertEquals(
      listOf("Maxstream" to "https://stayonline.pro/l/B"),
      provider.parseHosterLinksForEpisode(page, true, 1, 2)
    )
    assertEquals(
      listOf("Maxstream" to "https://stayonline.pro/l/C"),
      provider.parseHosterLinksForEpisode(page, true, 1, 3)
    )
  }

  @Test
  fun riconosceLeVariantiRealiDelMarkerSenzaPrendereLEpisodioSbagliato() {
    val page = """
      <h3>S1E3</h3><a href="https://stayonline.pro/l/A">Maxstream</a>
      <h3>S01.E04</h3><a href="https://stayonline.pro/l/B">Maxstream</a>
      <h3>1x05</h3><a href="https://stayonline.pro/l/C">Maxstream</a>
    """.trimIndent()

    assertEquals(
      listOf("Maxstream" to "https://stayonline.pro/l/A"),
      provider.parseHosterLinksForEpisode(page, true, 1, 3)
    )
    assertEquals(
      listOf("Maxstream" to "https://stayonline.pro/l/B"),
      provider.parseHosterLinksForEpisode(page, true, 1, 4)
    )
    assertTrue(
      "l'episodio 5 non deve soddisfare la richiesta dell'episodio 3",
      provider.parseHosterLinksForEpisode(page, true, 1, 5).single().second.endsWith("/C")
    )
  }

  @Test
  fun contenutoSenzaMarkerNonRestituisceLinkGenerici() {
    val page = """<a href="https://stayonline.pro/l/GEN">Maxstream</a>"""
    assertTrue(provider.parseHosterLinksForEpisode(page, isTv = true, season = 1, episode = 1).isEmpty())
  }

  @Test
  fun sceglieLArticoloCorrettoTraRisultatiOmonimi() {
    // Caso reale: la ricerca contiene il film 1983 e un omonimo parziale.
    val searchHtml = """
      <html><body>
        <a href="https://cb01.test/un-altro-film-2020/">Un altro film</a>
        <a href="https://cb01.test/occhio-malocchio-2024/">Occhio, malocchio</a>
        <a href="https://cb01.test/occhio-malocchio-prezzemolo-e-finocchio-hd-1983/">Occhio, malocchio, prezzemolo e finocchio</a>
      </body></html>
    """.trimIndent()

    val picked = provider.pickArticle(
      searchHtml,
      title = "Occhio, malocchio, prezzemolo e finocchio",
      baseUrl = "https://cb01.test",
      year = 1983
    )

    assertEquals(
      "https://cb01.test/occhio-malocchio-prezzemolo-e-finocchio-hd-1983/",
      picked
    )
  }

  @Test
  fun perUnFilmMantieneTuttiILink() {
    val links = provider.parseHosterLinksForEpisode(episodePage(), isTv = false, season = null, episode = null)
    assertEquals(3, links.size)
  }

  @Test
  fun decodificaLeEntitaHtmlNegliUrl() {
    val page = """
      <a href="https://stayonline.pro/l/ABC?a=1&amp;b=2">Maxstream</a>
      <a href="https://stayonline.pro/l/DEF?a=1&#38;b=2">Mixdrop</a>
      <iframe data-src="https://maxstream.video/e/XYZ?a=1&amp;b=2"></iframe>
    """.trimIndent()

    val urls = provider.parseHosterLinks(page).map { it.second }
    assertTrue(urls.contains("https://stayonline.pro/l/ABC?a=1&b=2"))
    assertTrue(urls.contains("https://stayonline.pro/l/DEF?a=1&b=2"))
    assertTrue(urls.contains("https://maxstream.video/e/XYZ?a=1&b=2"))
  }

  @Test
  fun nonDecodificaDueVolteUnUrlGiaCodificato() {
    val page = """<a href="https://stayonline.pro/l/ABC?a=1&amp;amp;b=2">Maxstream</a>"""

    val url = provider.parseHosterLinks(page).single().second
    assertEquals("https://stayonline.pro/l/ABC?a=1&amp;b=2", url)
  }

  @Test
  fun estraeLeTabelleStreamingEGliIframeDataSrc() {
    // Struttura reale dell'articolo CB01: due tabelle "Streaming" e due tab
    // con data-src. Le etichette HD sono avvolte in <span>.
    val article = """
      <table class="tableinside">
        <tr><td><a href="https://stayonline.pro/l/3W6yZ/">Maxstream</a></td></tr>
        <tr><td><a href="https://stayonline.pro/l/609g1/">Mixdrop</a></td></tr>
      </table>
      <ul class="tabs">
        <li><a class="link-catch-all"><span>Maxstream HD</span></a></li>
        <li><a class="link-catch-all"><span>Mixdrop HD</span></a></li>
      </ul>
      <div id="iframen1" data-src="https://stayonline.pro/e/JnDJr/"></div>
      <div id="iframen2" data-src="https://stayonline.pro/e/N0QXG/"></div>
    """.trimIndent()

    val links = provider.parseHosterLinks(article)

    assertTrue(links.contains("Maxstream" to "https://stayonline.pro/l/3W6yZ/"))
    assertTrue(links.contains("Mixdrop" to "https://stayonline.pro/l/609g1/"))
    assertTrue(links.contains("iframe" to "https://stayonline.pro/e/JnDJr/"))
    assertTrue(links.contains("iframe" to "https://stayonline.pro/e/N0QXG/"))
  }

  @Test
  fun riconosceLabelHDAvvolteInMarkup() {
    val page = """
      <a href="https://stayonline.pro/l/HD1"><span style="font-weight:bold">Maxstream HD</span></a>
      <a href="https://mixdrop.ag/e/DIRECT"><strong>Mixdrop HD (ITA)</strong></a>
    """.trimIndent()

    assertEquals(
      listOf(
        "Maxstream HD" to "https://stayonline.pro/l/HD1",
        "Mixdrop HD (ITA)" to "https://mixdrop.ag/e/DIRECT"
      ),
      provider.parseHosterLinks(page)
    )
  }

  @Test
  fun erroreTransitorioDiParsingConsenteIlRetrySulMirrorSuccessivo() = runBlocking {
    val b1 = "https://cb01-one.test"
    val b2 = "https://cb01-two.test"
    val article = "https://cb01.shared.test/titolo-2020/"
    var articleFetches = 0

    val provider = Cb01Provider(listOf(b1, b2)) { url, _ ->
      when {
        url.startsWith("$b1/?s=") || url.startsWith("$b2/?s=") ->
          """<a href="$article">Titolo</a>"""
        url == article -> {
          articleFetches++
          "<html><body>pagina incompleta</body></html>"
        }
        else -> "<html></html>"
      }
    }

    runCatching {
      provider.getStreams(1, false, null, null, "Titolo", 2020)
    }

    assertEquals("il parsing fallito deve poter essere ritentato", 2, articleFetches)
  }

  @Test
  fun parsingRiuscitoNonRipeteLoStessoArticoloSuUnAltroMirror() = runBlocking {
    val b1 = "https://cb01-one.test"
    val b2 = "https://cb01-two.test"
    val article = "https://cb01.shared.test/titolo-2020/"
    var articleFetches = 0

    val provider = Cb01Provider(listOf(b1, b2)) { url, _ ->
      when {
        url.startsWith("$b1/?s=") || url.startsWith("$b2/?s=") ->
          """<a href="$article">Titolo</a>"""
        url == article -> {
          articleFetches++
          """<a href="https://unsupported.example/e/x">Maxstream</a>"""
        }
        else -> "<html></html>"
      }
    }

    runCatching {
      provider.getStreams(1, false, null, null, "Titolo", 2020)
    }

    assertEquals("l'articolo già parsato non va rifetchato", 1, articleFetches)
  }
}
