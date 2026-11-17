package com.example.data.provider

import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.VideoExtractor
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test offline di [AnimeWorldClient] e dei suoi parser: ricerca, pagina
 * episodi, API `episode/info`, distinzione ITA/SUB ITA, mapping relativo/
 * assoluto e delega dell'hoster Streamtape. Nessuna richiesta di rete.
 */
class AnimeWorldClientTest {

  private val base = "https://aw.test"

  private val searchHtml = """
    <html><body>
      <div class="film-list">
        <div class="item"><div class="inner">
          <a href="/play/naruto-ita.Ze1Qv" class="poster"><img alt="Naruto (ITA)"></a>
          <a href="/play/naruto-ita.Ze1Qv" data-jtitle="Naruto" class="name">Naruto (ITA)</a>
        </div></div>
        <div class="item"><div class="inner">
          <a href="/play/naruto-sub.qzG-LE" class="poster"><img alt="Naruto"></a>
          <a href="/play/naruto-sub.qzG-LE" data-jtitle="Naruto" class="name">Naruto</a>
        </div></div>
      </div>
    </body></html>
  """.trimIndent()

  private fun page(vararg episodes: Pair<Int, String>, playerId: String? = null): String {
    val anchors = episodes.joinToString("\n") { (num, id) ->
      """<a data-episode-id="e$num" data-id="$id" data-episode-num="$num" href="/play/x/$id">$num</a>"""
    }
    val player = if (playerId == null) "" else
      """<div id="player" class="player-box" data-id="$playerId" data-episode-id="0"></div>"""
    return "<html><body>$player<div class=\"server\"><ul class=\"episodes\">$anchors</ul></div></body></html>"
  }

  @Test
  fun parseSearchRiconosceItaSubItaETitoloOriginale() {
    val entries = AnimeWorldClient(baseUrls = listOf(base)).parseSearch(searchHtml, base)

    assertEquals(2, entries.size)
    val ita = entries.first { it.slug.contains("-ita") }
    val sub = entries.first { it.slug == "naruto-sub.qzG-LE" }
    assertTrue("la variante ITA deve essere marcata italiana", ita.italian)
    assertFalse("la variante SUB ITA non deve essere marcata italiana", sub.italian)
    assertEquals("Naruto", ita.originalTitle)
    assertEquals("$base/play/naruto-ita.Ze1Qv", ita.pageUrl)
  }

  @Test
  fun parsePlayPageRaggruppaGliIdPerNumeroEpisodio() {
    val html = page(1 to "ita1", 2 to "ita2", 2 to "ita2bis", playerId = "ita1")
    val parsed = AnimeWorldClient().parsePlayPage(html)

    assertEquals(setOf(1, 2), parsed.episodes.keys)
    assertEquals(listOf("ita1"), parsed.episodes[1])
    assertEquals(listOf("ita2", "ita2bis"), parsed.episodes[2])
    assertEquals("ita1", parsed.playerId)
  }

  @Test
  fun resolveRestituisceItaESubItaDallaStessaRicerca() = runBlocking {
    val client = clientWithRoutes(
      "/search?keyword=Naruto" to searchHtml,
      "/play/naruto-ita.Ze1Qv" to page(1 to "ita1", 2 to "ita2", playerId = "ita1"),
      "/play/naruto-sub.qzG-LE" to page(1 to "sub1", 2 to "sub2", playerId = "sub1"),
      "/api/episode/info?id=ita1" to """{"grabber":"https://cdn.example/ita1.mp4"}""",
      "/api/episode/info?id=sub1" to """{"grabber":"https://cdn.example/sub1.mp4"}"""
    )

    val sources = client.resolve(
      queries = listOf("Naruto"),
      episodeCandidates = listOf(1),
      year = 2002,
      isTv = true
    )

    assertEquals(2, sources.size)
    val ita = sources.first { it.streamUrl == "https://cdn.example/ita1.mp4" }
    val sub = sources.first { it.streamUrl == "https://cdn.example/sub1.mp4" }
    assertEquals("AnimeWorld ITA", ita.serverName)
    assertTrue(ita.isItalian)
    assertEquals("AnimeWorld", sub.serverName)
    assertFalse(sub.isItalian)
    assertEquals("$base/", ita.headers["Referer"])
    assertEquals(true, ita.isProgressive)
  }

  @Test
  fun nonRisolvonoSequelOSpinOffConLoStessoTitoloParziale() = runBlocking {
    val extendedSearch = """
      <html><body>
        <div class="film-list">
          <div class="item"><div class="inner">
            <a href="/play/naruto-ita.Ze1Qv" class="poster"></a>
            <a href="/play/naruto-ita.Ze1Qv" data-jtitle="Naruto" class="name">Naruto (ITA)</a>
          </div></div>
          <div class="item"><div class="inner">
            <a href="/play/naruto-sub.qzG-LE" class="poster"></a>
            <a href="/play/naruto-sub.qzG-LE" data-jtitle="Naruto" class="name">Naruto</a>
          </div></div>
          <div class="item"><div class="inner">
            <a href="/play/naruto-shippuden.v3U8a" class="poster"></a>
            <a href="/play/naruto-shippuden.v3U8a" data-jtitle="Naruto: Shippuuden" class="name">Naruto Shippuden</a>
          </div></div>
          <div class="item"><div class="inner">
            <a href="/play/boruto.lYBFQ" class="poster"></a>
            <a href="/play/boruto.lYBFQ" data-jtitle="Boruto: Naruto Next Generations" class="name">Boruto: Naruto Next Generations</a>
          </div></div>
        </div>
      </body></html>
    """.trimIndent()

    val client = clientWithRoutes(
      "/search?keyword=Naruto" to extendedSearch,
      "/play/naruto-ita.Ze1Qv" to page(1 to "ita1", playerId = "ita1"),
      "/play/naruto-sub.qzG-LE" to page(1 to "sub1", playerId = "sub1"),
      "/play/naruto-shippuden.v3U8a" to page(1 to "ship1", playerId = "ship1"),
      "/play/boruto.lYBFQ" to page(1 to "bor1", playerId = "bor1"),
      "/api/episode/info?id=ita1" to """{"grabber":"https://cdn.example/naruto-ita.mp4"}""",
      "/api/episode/info?id=sub1" to """{"grabber":"https://cdn.example/naruto-sub.mp4"}""",
      "/api/episode/info?id=ship1" to """{"grabber":"https://cdn.example/shippuden.mp4"}""",
      "/api/episode/info?id=bor1" to """{"grabber":"https://cdn.example/boruto.mp4"}"""
    )

    val sources = client.resolve(
      queries = listOf("Naruto"),
      episodeCandidates = listOf(1),
      year = 2002,
      isTv = true
    )

    val urls = sources.mapNotNull { it.streamUrl }.toSet()
    assertEquals(
      setOf("https://cdn.example/naruto-ita.mp4", "https://cdn.example/naruto-sub.mp4"),
      urls
    )
    assertTrue("shippuden non deve essere incluso", urls.none { it.contains("shippuden") })
    assertTrue("boruto non deve essere incluso", urls.none { it.contains("boruto") })
  }

  @Test
  fun resolveSceglieLEpisodioRichiesto() = runBlocking {
    val client = clientWithRoutes(
      "/search?keyword=Naruto" to searchHtml,
      "/play/naruto-ita.Ze1Qv" to page(1 to "ita1", 2 to "ita2", playerId = "ita1"),
      "/play/naruto-sub.qzG-LE" to page(1 to "sub1", 2 to "sub2", playerId = "sub1"),
      "/api/episode/info?id=ita2" to """{"grabber":"https://cdn.example/ita2.mp4"}""",
      "/api/episode/info?id=sub2" to """{"grabber":"https://cdn.example/sub2.mp4"}"""
    )

    val sources = client.resolve(
      queries = listOf("Naruto"),
      episodeCandidates = listOf(2),
      year = 2002,
      isTv = true
    )

    assertEquals(
      setOf("https://cdn.example/ita2.mp4", "https://cdn.example/sub2.mp4"),
      sources.mapNotNull { it.streamUrl }.toSet()
    )
  }

  @Test
  fun resolveMappaLEpisodioAssolutoQuandoLaEntryEContinua() = runBlocking {
    val continuousPage = page(
      1 to "e1", 2 to "e2", 3 to "e3", 4 to "e4", 5 to "e5",
      6 to "e6", 7 to "e7", 8 to "e8", 9 to "e9", 10 to "e10",
      playerId = "e1"
    )
    val client = clientWithRoutes(
      "/search?keyword=Naruto" to searchHtml,
      "/play/naruto-ita.Ze1Qv" to continuousPage,
      "/play/naruto-sub.qzG-LE" to page(1 to "sub1", playerId = "sub1"),
      "/api/episode/info?id=e8" to """{"grabber":"https://cdn.example/e8.mp4"}"""
    )

    val sources = client.resolve(
      queries = listOf("Naruto"),
      episodeCandidates = listOf(2, 8),
      year = 2002,
      isTv = true
    )

    assertEquals("https://cdn.example/e8.mp4", sources.first().streamUrl)
  }

  @Test
  fun resolveUsaIlPlayerPerUnFilmSenzaEpisodi() = runBlocking {
    val client = clientWithRoutes(
      "/search?keyword=Naruto" to searchHtml,
      "/play/naruto-ita.Ze1Qv" to page(playerId = "movie1"),
      "/play/naruto-sub.qzG-LE" to page(playerId = "movie2"),
      "/api/episode/info?id=movie1" to """{"grabber":"https://cdn.example/movie1.mp4"}""",
      "/api/episode/info?id=movie2" to """{"grabber":"https://cdn.example/movie2.mp4"}"""
    )

    val sources = client.resolve(
      queries = listOf("Naruto"),
      episodeCandidates = listOf(1),
      year = 2002,
      isTv = false
    )

    assertEquals(
      setOf("https://cdn.example/movie1.mp4", "https://cdn.example/movie2.mp4"),
      sources.mapNotNull { it.streamUrl }.toSet()
    )
  }

  @Test
  fun unFilmNonRisolveUnaSerieConLoStessoTitolo() = runBlocking {
    val client = clientWithRoutes(
      "/search?keyword=Naruto" to searchHtml,
      "/play/naruto-ita.Ze1Qv" to page(1 to "ita1", 2 to "ita2", playerId = "ita1"),
      "/play/naruto-sub.qzG-LE" to page(1 to "sub1", 2 to "sub2", playerId = "sub1")
    )

    try {
      client.resolve(
        queries = listOf("Naruto"),
        episodeCandidates = listOf(1),
        year = 2002,
        isTv = false
      )
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("AnimeWorld non risolvibile"))
    }
  }

  @Test
  fun unGrabberHosterVieneDelegatoAllEstrattore() = runBlocking {
    val fakeExtractor = object : VideoExtractor {
      override suspend fun extract(embedUrl: String): List<StreamSource> {
        // URL distinti per entry: la deduplica del client usa lo streamUrl.
        val code = embedUrl.substringAfterLast('/')
        return listOf(
          StreamSource(
            streamUrl = "https://cdn.example/streamtape-$code.mp4",
            serverName = "Streamtape"
          )
        )
      }
    }
    val client = AnimeWorldClient(
      baseUrls = listOf(base),
      http = routeHandler(
        "/search?keyword=Naruto" to searchHtml,
        "/play/naruto-ita.Ze1Qv" to page(1 to "ita1", playerId = "ita1"),
        "/play/naruto-sub.qzG-LE" to page(1 to "sub1", playerId = "sub1"),
        "/api/episode/info?id=ita1" to """{"grabber":"https://streamtape.com/e/abc"}""",
        "/api/episode/info?id=sub1" to """{"grabber":"https://streamtape.com/e/def"}"""
      ),
      hosterExtractor = { url -> if (url.contains("streamtape")) fakeExtractor else null }
    )

    val sources = client.resolve(
      queries = listOf("Naruto"),
      episodeCandidates = listOf(1),
      year = 2002,
      isTv = true
    )

    assertEquals(2, sources.size)
    val ita = sources.first { it.serverName == "Streamtape (ITA)" }
    val sub = sources.first { it.serverName == "Streamtape" }
    assertTrue(ita.isItalian)
    assertFalse(sub.isItalian)
  }

  @Test
  fun unGrabberNonMediaSenzaEstrattoreNonVieneConsegnatoAlPlayer() = runBlocking {
    val client = clientWithRoutes(
      "/search?keyword=Naruto" to searchHtml,
      "/play/naruto-ita.Ze1Qv" to page(1 to "ita1", playerId = "ita1"),
      "/play/naruto-sub.qzG-LE" to page(1 to "sub1", playerId = "sub1"),
      "/api/episode/info?id=ita1" to """{"grabber":"https://example.com/watch/123"}""",
      "/api/episode/info?id=sub1" to """{"grabber":"https://example.com/watch/456"}"""
    )

    try {
      client.resolve(
        queries = listOf("Naruto"),
        episodeCandidates = listOf(1),
        year = 2002,
        isTv = true
      )
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("AnimeWorld non risolvibile"))
    }
  }

  @Test
  fun unGrabberAssenteNonProduceSorgenti() = runBlocking {
    val client = clientWithRoutes(
      "/search?keyword=Naruto" to searchHtml,
      "/play/naruto-ita.Ze1Qv" to page(1 to "ita1", playerId = "ita1"),
      "/play/naruto-sub.qzG-LE" to page(1 to "sub1", playerId = "sub1"),
      "/api/episode/info?id=ita1" to """{"error":true}""",
      "/api/episode/info?id=sub1" to """{"error":true}"""
    )

    try {
      client.resolve(
        queries = listOf("Naruto"),
        episodeCandidates = listOf(1),
        year = 2002,
        isTv = true
      )
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("AnimeWorld non risolvibile"))
    }
  }

  private fun clientWithRoutes(vararg routes: Pair<String, String>): AnimeWorldClient =
    AnimeWorldClient(
      baseUrls = listOf(base),
      http = routeHandler(*routes),
      hosterExtractor = { null }
    )

  private fun routeHandler(vararg routes: Pair<String, String>): AnimeWorldHttpGet = { url, _, _ ->
    routes.firstOrNull { (path, _) -> url.contains(path) }?.second
      ?: error("URL inatteso: $url")
  }
}
