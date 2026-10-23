package com.example.data.streaming.providers

import com.example.data.streaming.StreamSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite **offline** di [TwoEmbedProvider].
 *
 * Il fetcher HTTP è iniettato: nessuna richiesta verso Internet, nessuna
 * dipendenza da mirror o hoster reali. Le fixture simulano anche i redirect
 * restituendo un URL finale diverso da quello richiesto.
 */
class TwoEmbedProviderTest {

  private val base = "https://twoembed.test"

  @Test
  fun parseVarQConImdbIdReale() {
    val page = """
      <html><script>
        var Q = {"type":"movie","id":"tt1375666","t":"token-1","s":0,"e":0};
      </script></html>
    """.trimIndent()

    val q = TwoEmbedProvider.parseVarQ(page, isTv = false, fallbackImdb = null)

    assertEquals("movie", q?.type)
    assertEquals("tt1375666", q?.imdbId)
    assertEquals("token-1", q?.token)
    assertEquals(0, q?.season)
    assertEquals(0, q?.episode)
  }

  @Test
  fun parseVarQConIdNumericoUsaImdbDiFallback() {
    val page = """<script>var Q = {"type":"tv","id":"12345","t":"tok","s":2,"e":7};</script>"""

    val q = TwoEmbedProvider.parseVarQ(page, isTv = true, fallbackImdb = "tt0903747")

    assertEquals("tv", q?.type)
    assertEquals("tt0903747", q?.imdbId)
    assertEquals(2, q?.season)
    assertEquals(7, q?.episode)
  }

  @Test
  fun parseVarQConCampiInOrdineDiversoEJsonFormattato() {
    val page = """
      <script>
        var Q = {
          "e": 3,
          "t": "abc",
          "s": 1,
          "id": "tt1234567",
          "type": "tv"
        };
      </script>
    """.trimIndent()

    val q = TwoEmbedProvider.parseVarQ(page, isTv = true, fallbackImdb = null)

    assertEquals("tv", q?.type)
    assertEquals("tt1234567", q?.imdbId)
    assertEquals("abc", q?.token)
    assertEquals(1, q?.season)
    assertEquals(3, q?.episode)
  }

  @Test
  fun parseVarQAssenteRestituisceNull() {
    assertNull(TwoEmbedProvider.parseVarQ("<html>not found</html>", false, "tt1234567"))
  }

  @Test
  fun parseServersConCampiInvertiti() {
    val json = """
      {"servers":[
        {"name":"Alpha","ref":"ref-a"},
        {"ref":"ref-b","name":"Beta"}
      ]}
    """.trimIndent()

    assertEquals(
      listOf("ref-a" to "Alpha", "ref-b" to "Beta"),
      TwoEmbedProvider.parseServers(json)
    )
  }

  @Test
  fun parseServersFallbackNonAssociaOggettiDiversi() {
    // JSON non valido per Moshi: il fallback deve restare confinato all'oggetto.
    val json = """[{"name":"SoloNome"},{"ref":"ref-b","name":"Beta"}]"""

    assertEquals(listOf("ref-b" to "Beta"), TwoEmbedProvider.parseServers(json))
  }

  @Test
  fun unaPaginaGenericaNonEUnPlayer() {
    assertFalse(TwoEmbedProvider.isPlayerPage(""))
    assertFalse(TwoEmbedProvider.isPlayerPage("<html><body>404 Not Found</body></html>"))
    assertTrue(TwoEmbedProvider.isPlayerPage("""<iframe src="https://vidsrc.example/e/1"></iframe>"""))
    assertTrue(TwoEmbedProvider.isPlayerPage("""<video src="https://cdn.example.test/a.mp4"></video>"""))
    assertTrue(TwoEmbedProvider.isPlayerPage("""<script>var Q = {"id":"tt1"};</script>"""))
  }

  @Test
  fun qualitaHlsRiconosce4K1080p720pEIgnota() {
    assertEquals("4K", TwoEmbedProvider.qualityForHeight(2160))
    assertEquals("1080p", TwoEmbedProvider.qualityForHeight(1080))
    assertEquals("720p", TwoEmbedProvider.qualityForHeight(720))
    assertEquals("Auto", TwoEmbedProvider.qualityForHeight(null))
    assertEquals("Auto", TwoEmbedProvider.qualityForHeight(360))
  }

  @Test
  fun altezzaMassimaLettaDaiTagResolution() {
    val playlist = """
      #EXTM3U
      #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
      low.m3u8
      #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
      high.m3u8
      #EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160
      uhd.m3u8
    """.trimIndent()

    assertEquals(2160, TwoEmbedProvider.maxResolutionHeight(playlist))
    assertEquals(1080, TwoEmbedProvider.maxResolutionHeight("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nx.m3u8"))
    assertEquals(720, TwoEmbedProvider.maxResolutionHeight("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\nx.m3u8"))
    assertNull(TwoEmbedProvider.maxResolutionHeight("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nx.m3u8"))
  }

  @Test
  fun headerUsanoOriginDellaPaginaEmbed() {
    val provider = providerWith { _, _, _ -> "" to "" }

    val headers = provider.headersFor("https://vidsrc.example/embed/movie/1")

    assertEquals("https://vidsrc.example/", headers["Referer"])
    assertEquals("https://vidsrc.example", headers["Origin"])
    assertTrue(headers.containsKey("User-Agent"))
    assertEquals("https://other.example:8443", provider.originOf("https://other.example:8443/x"))
  }

  @Test
  fun fallbackAlCandidatoSuccessivoEAllImdbIdPassato() = runBlocking {
    val requested = mutableListOf<String>()
    val provider = TwoEmbedProvider(
      listOf(base),
      TwoEmbedHttp { url, _, _ ->
        requested += url
        when {
          url == "$base/embed/movie/123" ->
            "https://twoembed.test/embed/movie/123" to "<html><body>404 Not Found</body></html>"
          url == "$base/embed/123" ->
            "$base/embed/123" to """<iframe src="https://vidsrc.example/embed/movie/123"></iframe>"""
          url.startsWith("https://vidsrc.example/embed/movie/123") ->
            url to """<script>var Q = {"type":"movie","id":"999","t":"tok","s":0,"e":0};</script>"""
          url.contains("/pl/api.php?a=sources") ->
            url to """{"servers":[{"name":"Alpha","ref":"ref-a"}]}"""
          url.contains("/pl/api.php?a=play") ->
            url to """{"url":"/stream/master.m3u8","type":"hls"}"""
          url == "https://vidsrc.example/stream/master.m3u8" ->
            "https://cdn.example.test/media/master.m3u8" to
              "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160\nuhd.m3u8"
          else -> throw IllegalStateException("richiesta inattesa: $url")
        }
      }
    )

    val sources = provider.getStreams(
      tmdbId = 123,
      isTv = false,
      season = null,
      episode = null,
      title = "Test",
      year = 2026,
      imdbId = "tt1234567"
    )

    assertEquals(1, sources.size)
    val source: StreamSource = sources.first()
    assertEquals("https://cdn.example.test/media/master.m3u8", source.streamUrl)
    assertEquals("4K", source.quality)
    assertEquals(2160, source.verifiedHeight)
    assertEquals("https://vidsrc.example/", source.headers["Referer"])
    assertEquals("https://vidsrc.example", source.headers["Origin"])
    assertTrue(requested.contains("https://vidsrc.example/embed/movie/123"))
    assertTrue(requested.any { it.contains("a=play") && it.contains("id=tt1234567") })
  }

  @Test
  fun seVidSrcFallisceProvaUnAltroIframe() = runBlocking {
    val provider = TwoEmbedProvider(
      listOf(base),
      TwoEmbedHttp { url, _, _ ->
        when {
          url == "$base/embed/movie/55" ->
            url to """
              <html>
                <iframe src="https://vidsrc.example/embed/movie/55"></iframe>
                <iframe src="https://player.example/embed/55"></iframe>
              </html>
            """.trimIndent()
          url.startsWith("https://vidsrc.example/") ->
            url to "<html><body>nessun player</body></html>"
          url.startsWith("https://player.example/embed/55") ->
            url to """<video src="https://cdn.example.test/alt/master.m3u8"></video>"""
          else -> throw IllegalStateException("richiesta inattesa: $url")
        }
      }
    )

    val sources = provider.getStreams(
      tmdbId = 55,
      isTv = false,
      season = null,
      episode = null,
      title = "Fallback",
      year = null
    )

    assertEquals("https://cdn.example.test/alt/master.m3u8", sources.first().streamUrl)
  }

  @Test
  fun mp4DirettoHaQualitaAuto() = runBlocking {
    val provider = TwoEmbedProvider(
      listOf(base),
      TwoEmbedHttp { url, _, _ ->
        if (url == "$base/embed/movie/77") {
          url to """<iframe src="https://player.example/embed/77"></iframe>"""
        } else if (url.startsWith("https://player.example/")) {
          url to """<html><video src="https://cdn.example.test/file.mp4"></video></html>"""
        } else {
          throw IllegalStateException("richiesta inattesa: $url")
        }
      }
    )

    val source = provider.getStreams(77, false, null, null, "Mp4", null).first()
    assertEquals("https://cdn.example.test/file.mp4", source.streamUrl)
    assertEquals("Auto", source.quality)
    assertNull(source.verifiedHeight)
  }

  @Test
  fun pagineEmbedTutteGenericheProduconoErroreEsplicito() = runBlocking {
    val provider = TwoEmbedProvider(
      listOf(base),
      TwoEmbedHttp { url, _, _ -> url to "<html><body>nessuna pagina</body></html>" }
    )

    val error = runCatching {
      provider.getStreams(1, false, null, null, "Generico", null)
    }.exceptionOrNull()

    assertTrue(error is java.io.IOException)
    assertTrue(error?.message.orEmpty().contains("nessuna pagina embed", ignoreCase = true))
  }

  @Test
  fun cancellazioneDelChiamanteNonDiventaUnErroreDiRete() = runBlocking {
    val provider = TwoEmbedProvider(
      listOf(base),
      TwoEmbedHttp { _, _, _ ->
        delay(30_000)
        error("irraggiungibile")
      }
    )

    val thrown = runCatching {
      withTimeout(150) {
        provider.getStreams(1, false, null, null, "Cancellazione", null)
      }
    }.exceptionOrNull()

    assertTrue("attesa CancellationException, ottenuto $thrown", thrown is TimeoutCancellationException)
  }

  @Test
  fun erroreSuUnMirrorProvaIlMirrorSuccessivo() = runBlocking {
    val first = "https://mirror-one.test"
    val second = "https://mirror-two.test"
    val attempted = mutableListOf<String>()
    val provider = TwoEmbedProvider(
      listOf(first, second),
      TwoEmbedHttp { url, _, _ ->
        attempted += url
        if (url.startsWith(first)) {
          throw java.io.IOException("HTTP 503")
        }
        when {
          url == "$second/embed/movie/9" ->
            url to """<iframe src="https://player.example/embed/9"></iframe>"""
          url.startsWith("https://player.example/") ->
            url to """<video src="https://cdn.example.test/ok.mp4"></video>"""
          else -> throw IllegalStateException("richiesta inattesa: $url")
        }
      }
    )

    val sources = provider.getStreams(9, false, null, null, "Mirror", null)

    assertEquals("https://cdn.example.test/ok.mp4", sources.single().streamUrl)
    assertTrue(attempted.any { it.startsWith(first) })
    assertTrue(attempted.any { it.startsWith(second) })
  }

  @Test
  fun fixtureMasterPlaylistOffline() {
    assertEquals("4K", qualityForFixture("hls_2160.m3u8"))
    assertEquals("1080p", qualityForFixture("hls_1080.m3u8"))
    assertEquals("720p", qualityForFixture("hls_720.m3u8"))
    assertEquals("Auto", qualityForFixture("hls_unknown.m3u8"))
  }

  // --- Serie TV: selezione dell'episodio e iframe annidati ------------------

  @Test
  fun serieUsaEmbedtvEIgnoraIlPlayerMovieConIdConcatenato() = runBlocking {
    val requested = mutableListOf<String>()
    val provider = TwoEmbedProvider(
      listOf(base),
      TwoEmbedHttp { url, _, _ ->
        requested += url
        when {
          // Path TV "rotto": player movie con id 1399+1+1 = 139911.
          url == "$base/embed/tv/1399/1/1" ->
            url to """
              <html><title>2Embed.cc - Player</title>
                <iframe id="iframesrc" src="about:blank"
                        data-src="https://vidsrc.example/embed/movie/139911"></iframe>
              </html>
            """.trimIndent()
          // Endpoint TV documentato: player corretto per S01E01.
          url == "$base/embedtv/1399&s=1&e=1" ->
            url to """
              <html><title>Serie (2011) (S01E01)</title>
                <iframe id="iframesrc" src="about:blank"
                        data-src="https://vidsrc.example/embed/tv/1399/1/1"></iframe>
              </html>
            """.trimIndent()
          url.startsWith("https://vidsrc.example/embed/tv/1399/1/1") ->
            url to """<script>var Q = {"type":"tv","id":"tt0944947","t":"tok","s":1,"e":1};</script>"""
          url.contains("/pl/api.php?a=sources") ->
            url to """{"servers":[{"name":"Alpha","ref":"ref-a"}]}"""
          url.contains("/pl/api.php?a=play") ->
            url to """{"url":"/stream/master.m3u8","type":"hls"}"""
          url == "https://vidsrc.example/stream/master.m3u8" ->
            "https://cdn.example.test/serie/master.m3u8" to
              "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nhd.m3u8"
          else -> throw IllegalStateException("richiesta inattesa: $url")
        }
      }
    )

    val sources = provider.getStreams(1399, isTv = true, season = 1, episode = 1, title = "Serie", year = 2011)

    assertEquals("https://cdn.example.test/serie/master.m3u8", sources.single().streamUrl)
    assertEquals(1080, sources.single().verifiedHeight)
    assertTrue(
      "il path TV rotto non deve essere interrogato se embedtv risponde",
      requested.none { it.contains("videm") || it.contains("/embed/movie/139911") }
    )
  }

  @Test
  fun paginaTvConPlayerMovieConcatenatoRiconosciutaECartata() {
    val provider = providerWith { _, _, _ -> "" to "" }

    val rotta = """
      <iframe id="iframesrc" src="about:blank"
              data-src="https://vidsrc.buzz/embed/movie/139911"></iframe>
    """.trimIndent()
    val corretta = """
      <iframe id="iframesrc" src="about:blank"
              data-src="https://vidsrc.buzz/embed/tv/1399/1/1"></iframe>
    """.trimIndent()

    assertTrue(provider.isBrokenConcatenatedTvPage(rotta, 1399, 1, 1))
    assertFalse(provider.isBrokenConcatenatedTvPage(corretta, 1399, 1, 1))
    // Per un film lo stesso markup non è "rotto": non esiste stagione/episodio.
    assertFalse(provider.isBrokenConcatenatedTvPage(rotta, 1399, 0, 0))
  }

  @Test
  fun paginaTvDiUnAltroEpisodioNonVieneAccettata() {
    val provider = providerWith { _, _, _ -> "" to "" }

    assertTrue(provider.pageMatchesRequestedEpisode("<title>Serie S01E01</title>", 1, 1))
    assertFalse(provider.pageMatchesRequestedEpisode("<title>Serie S01E02</title>", 1, 1))
    assertFalse(provider.pageMatchesRequestedEpisode("<title>Serie S02E01</title>", 1, 1))
    // Pagina senza marker: player generico, resta valida.
    assertTrue(provider.pageMatchesRequestedEpisode("<html><body>player</body></html>", 1, 1))
  }

  @Test
  fun segueIframeAnnidatiFinoAllaSorgenteMedia() = runBlocking {
    val provider = TwoEmbedProvider(
      listOf(base),
      TwoEmbedHttp { url, _, _ ->
        when (url) {
          "$base/embed/movie/77" ->
            url to """<iframe src="https://streamsrcs.example/vcr?tmdb=77"></iframe>"""
          "https://streamsrcs.example/vcr?tmdb=77" ->
            url to """<iframe src="player?autoplay=true"></iframe>"""
          "https://streamsrcs.example/player?autoplay=true" ->
            url to """<html><video src="https://cdn.example.test/annidato.mp4"></video></html>"""
          else -> throw IllegalStateException("richiesta inattesa: $url")
        }
      }
    )

    val source = provider.getStreams(77, false, null, null, "Annidato", null).single()

    assertEquals("https://cdn.example.test/annidato.mp4", source.streamUrl)
    assertEquals("https://streamsrcs.example/", source.headers["Referer"])
  }

  @Test
  fun preferisceHlsAlMp4QuandoLaPaginaEsponeEntrambi() = runBlocking {
    val provider = TwoEmbedProvider(
      listOf(base),
      TwoEmbedHttp { url, _, _ ->
        if (url == "$base/embed/movie/88") {
          url to """
            <html><body>
              <video src="https://cdn.example.test/progressivo.mp4"></video>
              <script>var player = { file: "https://cdn.example.test/master.m3u8" };</script>
            </body></html>
          """.trimIndent()
        } else {
          throw IllegalStateException("richiesta inattesa: $url")
        }
      }
    )

    assertEquals("https://cdn.example.test/master.m3u8", provider.getStreams(88, false, null, null, "Hls", null).single().streamUrl)
  }

  private fun qualityForFixture(name: String): String {
    val body = checkNotNull(javaClass.getResource("/fixtures/$name")) { "fixture mancante: $name" }
      .readText()
    return TwoEmbedProvider.qualityForHeight(TwoEmbedProvider.maxResolutionHeight(body))
  }

  private fun providerWith(fetcher: TwoEmbedHttp): TwoEmbedProvider =
    TwoEmbedProvider(listOf(base), fetcher)
}
