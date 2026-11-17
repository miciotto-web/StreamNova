package com.example.data.streaming.providers

import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.extractors.VideoExtractor
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test offline di [GuardaPlayProvider]: parsing ricerca/articolo/trembed,
 * risoluzione Uqload, `loadm.cam`, URL media diretti e skip di VixSrc.
 * Nessuna richiesta di rete.
 */
class GuardaPlayProviderTest {

  private val base = "https://gp.test"

  private val searchTitleUrl = "$base/?s=Inception"
  private val movieUrl = "$base/movies/inception/"
  private val movie1980Url = "$base/movies/inception-1980/"
  private val trembed0 = "$base/?trembed=0&trid=1&trtype=1"

  private val searchHtml = """
    <html><body>
      <div id="aa-movies">
        <ul class="post-lst">
          <li><article class="post">
            <h2 class="entry-title">Inception</h2>
            <span class="year">2010</span>
            <a href="$movieUrl" class="lnk-blk"></a>
          </article></li>
          <li><article class="post">
            <h2 class="entry-title">Inception</h2>
            <span class="year">1980</span>
            <a href="$movie1980Url" class="lnk-blk"></a>
          </article></li>
        </ul>
      </div>
      <aside class="sidebar">
        <article class="post">
          <h2 class="entry-title">Altro Film</h2>
          <a href="$base/movies/altro/" class="lnk-blk"></a>
        </article>
      </aside>
    </body></html>
  """.trimIndent()

  private val movieHtml = """
    <html><body>
      <div id="aa-options">
        <div id="options-0">
          <iframe src="$base/?trembed=0&#038;trid=1&trtype=1"></iframe>
        </div>
        <div id="options-1">
          <iframe data-src="$base/?trembed=1&trid=1&trtype=1"></iframe>
        </div>
      </div>
    </body></html>
  """.trimIndent()

  private val uqloadTrembed = """
    <html><body>
      <div class="Video"><IFRAME SRC="https://uqload.vc/e/abc"></IFRAME></div>
    </body></html>
  """.trimIndent()

  private val vixsrcTrembed = """
    <html><body>
      <div class="Video"><iframe src="https://vixsrc.to/movie/27205/"></iframe></div>
    </body></html>
  """.trimIndent()

  private val uqloadSource = StreamSource(
    streamUrl = "https://cdn.example/uqload.mp4",
    serverName = "Uqload"
  )

  private fun uqloadExtractor() = object : VideoExtractor {
    override suspend fun extract(embedUrl: String): List<StreamSource> = listOf(uqloadSource)
  }

  private fun provider(
    routes: Map<String, String>,
    extractor: (String) -> VideoExtractor? = { null }
  ): GuardaPlayProvider {
    val http = GuardaPlayHttp { url, _ ->
      val body = routes.entries.firstOrNull { (needle, _) -> url.contains(needle) }?.value
        ?: error("URL inatteso: $url")
      url to body
    }
    return GuardaPlayProvider(
      baseUrls = listOf(base),
      diagnostics = StreamDiagnosticSink.NONE,
      http = http,
      hosterExtractor = extractor
    )
  }

  @Test
  fun parseSearchEscludeLaSidebarELeggeTitoloAnno() {
    val entries = provider(emptyMap()).parseSearch(searchHtml, base)

    assertEquals(2, entries.size)
    assertEquals(setOf("Inception"), entries.map { it.title }.toSet())
    assertEquals(setOf(2010, 1980), entries.mapNotNull { it.year }.toSet())
    assertFalse(entries.any { it.title == "Altro Film" })
  }

  @Test
  fun rankEntriesPreferisceLAnnoCorretto() {
    val entries = provider(emptyMap()).parseSearch(searchHtml, base)
    val ranked = provider(emptyMap()).rankEntries(entries, "Inception", 2010)

    assertEquals(movieUrl, ranked.first().url)
    assertEquals(2010, ranked.first().year)
  }

  @Test
  fun buildSearchQueriesParteDalTitoloSenzaAnno() {
    val queries = provider(emptyMap()).buildSearchQueries(
      "Deep Water - Incubo degli abissi",
      2026
    )

    assertEquals("Deep Water - Incubo degli abissi", queries.first())
    assertTrue(queries.contains("Deep Water - Incubo degli abissi 2026"))
    assertTrue(queries.contains("Deep Water"))
  }

  @Test
  fun parseTrembedFramesNormalizzaLeEntita() {
    val frames = provider(emptyMap()).parseTrembedFrames(movieHtml, base)

    assertEquals(
      listOf(
        "$base/?trembed=0&trid=1&trtype=1",
        "$base/?trembed=1&trid=1&trtype=1"
      ),
      frames
    )
  }

  @Test
  fun parsePlayerCandidatesTrovaUrlUqloadInChiaro() {
    val html = """
      <html><script>
        var source = "https://uqload.vc/e/abc?strm10.uqload.vc/i/02/06217/abc";
      </script></html>
    """.trimIndent()

    val candidates = provider(emptyMap()).parsePlayerCandidates(html, base)
    assertTrue(candidates.any { it.startsWith("https://uqload.vc/e/abc") })
  }

  @Test
  fun risolveUqloadDalPlayerTrembed() = runBlocking {
    val provider = provider(
      routes = mapOf(
        searchTitleUrl to searchHtml,
        movieUrl to movieHtml,
        "trembed=0" to uqloadTrembed,
        "trembed=1" to vixsrcTrembed
      ),
      extractor = { url -> if (url.contains("uqload")) uqloadExtractor() else null }
    )

    val sources = provider.getStreams(
      tmdbId = 27205,
      isTv = false,
      season = null,
      episode = null,
      title = "Inception",
      year = 2010
    )

    assertEquals(1, sources.size)
    assertEquals("https://cdn.example/uqload.mp4", sources.single().streamUrl)
    assertEquals("Uqload", sources.single().serverName)
  }

  @Test
  fun saltaVixSrcPercheGiaCopertoDalProviderDedicato() = runBlocking {
    val calls = AtomicInteger(0)
    val provider = provider(
      routes = mapOf(
        searchTitleUrl to searchHtml,
        movieUrl to movieHtml,
        "trembed=0" to vixsrcTrembed,
        "trembed=1" to vixsrcTrembed
      ),
      extractor = {
        calls.incrementAndGet()
        uqloadExtractor()
      }
    )

    try {
      provider.getStreams(
        tmdbId = 27205,
        isTv = false,
        season = null,
        episode = null,
        title = "Inception",
        year = 2010
      )
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("GuardaPlay non risolvibile"))
    }
    assertEquals("VixSrc non deve arrivare all'hoster resolver", 0, calls.get())
  }

  @Test
  fun risolveLoadmTramiteApiPublicResolve() = runBlocking {
    val loadmTrembed = """
      <html><body>
        <div class="Video"><iframe src="https://loadm.cam/#abc"></iframe></div>
      </body></html>
    """.trimIndent()
    val provider = provider(
      routes = mapOf(
        searchTitleUrl to searchHtml,
        movieUrl to movieHtml,
        "trembed=0" to loadmTrembed,
        "trembed=1" to vixsrcTrembed,
        "/api/public/resolve?file1=abc" to """{"ok":true,"url":"https://uqload.vc/e/def"}"""
      ),
      extractor = { url -> if (url.contains("uqload")) uqloadExtractor() else null }
    )

    val sources = provider.getStreams(
      tmdbId = 27205,
      isTv = false,
      season = null,
      episode = null,
      title = "Inception",
      year = 2010
    )

    assertEquals("https://cdn.example/uqload.mp4", sources.single().streamUrl)
  }

  @Test
  fun risolveUnUrlMediaDirettoSenzaEstrattore() = runBlocking {
    val directTrembed = """
      <html><body>
        <div class="Video"><iframe src="https://cdn.example/video.mp4"></iframe></div>
      </body></html>
    """.trimIndent()
    val provider = provider(
      routes = mapOf(
        searchTitleUrl to searchHtml,
        movieUrl to movieHtml,
        "trembed=0" to directTrembed,
        "trembed=1" to vixsrcTrembed
      ),
      extractor = { null }
    )

    val source = provider.getStreams(
      tmdbId = 27205,
      isTv = false,
      season = null,
      episode = null,
      title = "Inception",
      year = 2010
    ).single()

    assertEquals("https://cdn.example/video.mp4", source.streamUrl)
    assertEquals("GuardaPlay", source.serverName)
    assertTrue(source.isProgressive == true)
    assertTrue(source.isItalian)
  }

  @Test
  fun leSerieTvNonVengonoRichieste() = runBlocking {
    val calls = AtomicInteger(0)
    val provider = GuardaPlayProvider(
      baseUrls = listOf(base),
      diagnostics = StreamDiagnosticSink.NONE,
      http = { _, _ ->
        calls.incrementAndGet()
        error("HTTP non atteso")
      }
    )

    try {
      provider.getStreams(
        tmdbId = 1,
        isTv = true,
        season = 1,
        episode = 1,
        title = "Inception",
        year = 2010
      )
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("solo film"))
    }
    assertEquals(0, calls.get())
  }

  @Test
  fun nessunRisultatoProduceErroreEsplicito() = runBlocking {
    val provider = provider(
      routes = mapOf(searchTitleUrl to "<html><div id=\"aa-movies\"></div><aside></aside></html>")
    )

    try {
      provider.getStreams(
        tmdbId = 27205,
        isTv = false,
        season = null,
        episode = null,
        title = "Inception",
        year = 2010
      )
      fail("attesa IOException")
    } catch (e: IOException) {
      assertTrue(e.message.orEmpty().contains("GuardaPlay non risolvibile"))
    }
  }

  @Test
  fun estraeLHashDalFragmentLoadm() {
    val provider = provider(emptyMap())
    assertEquals("abc", provider.extractLoadmHash("https://loadm.cam/#abc"))
    assertEquals("xyz", provider.extractLoadmHash("https://loadm.cam/player/xyz"))
    assertEquals(null, provider.extractLoadmHash("not-a-url"))
  }
}
