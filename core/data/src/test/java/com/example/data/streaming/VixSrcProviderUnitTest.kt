package com.example.data.streaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.prefs.StreamingEngineMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VixSrcProviderUnitTest {

  private fun createMockClient(handler: (url: String) -> Response): OkHttpClient {
    val interceptor = Interceptor { chain ->
      val request = chain.request()
      handler(request.url.toString())
    }
    return OkHttpClient.Builder()
      .addInterceptor(interceptor)
      .connectTimeout(2, TimeUnit.SECONDS)
      .readTimeout(2, TimeUnit.SECONDS)
      .build()
  }

  private fun response(url: String, code: Int, body: String, contentType: String = "application/json"): Response {
    return Response.Builder()
      .request(okhttp3.Request.Builder().url(url).build())
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message(if (code == 200) "OK" else "Error")
      .body(body.toResponseBody(contentType.toMediaType()))
      .build()
  }

  // 1. Risposta valida con almeno una sorgente per un film
  @Test
  fun testMovieValidResponseProducesSource(): Unit = runBlocking {
    val client = createMockClient { url ->
      when {
        url.contains("/api/movie/550") -> {
          response(url, 200, """{"src":"/embed/170060?token=mockToken&canPlayFHD=1"}""")
        }
        url.contains("/embed/170060") -> {
          val html = """
            <script>
            window.streams = [{"name":"Server1","active":true,"url":"https://vixsrc.to/playlist/170060?ub=1"}];
            window.masterPlaylist = {
                params: {
                    'token': 'mockParamToken',
                    'expires': '1796636697'
                },
                url: 'https://vixsrc.to/playlist/170060'
            }
            window.canPlayFHD = true
            </script>
          """.trimIndent()
          response(url, 200, html, "text/html")
        }
        url.contains("/playlist/170060") -> {
          val m3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            https://vixsrc.to/playlist/170060?rendition=1080p
          """.trimIndent()
          response(url, 200, m3u8, "application/vnd.apple.mpegurl")
        }
        else -> response(url, 404, "Not Found")
      }
    }

    val provider = VixSrcProvider(http = client)
    val sources = provider.getStreams(tmdbId = 550, isTv = false)

    assertTrue("Deve produrre almeno una sorgente per film", sources.isNotEmpty())
    val first = sources.first()
    assertEquals("1080p", first.quality)
    assertEquals("VixSrc", first.serverName)
    assertTrue("streamUrl deve contenere il token", first.streamUrl?.contains("mockParamToken") == true)
  }

  // 2. Risposta valida con almeno una sorgente per una serie e corretti dati stagione/episodio
  @Test
  fun testTvValidResponseProducesSourceWithCorrectSeasonAndEpisode(): Unit = runBlocking {
    var requestedApiPath: String? = null
    val client = createMockClient { url ->
      when {
        url.contains("/api/tv/1418/2/5") -> {
          requestedApiPath = "/api/tv/1418/2/5"
          response(url, 200, """{"src":"/embed/620626?token=mockTvToken&canPlayFHD=1"}""")
        }
        url.contains("/embed/620626") -> {
          val html = """
            <script>
            window.streams = [{"name":"Server1","active":true,"url":"https://vixsrc.to/playlist/620626?ub=1"}];
            window.masterPlaylist = {
                params: {
                    'token': 'tvPlaylistToken',
                    'expires': '1796636720'
                },
                url: 'https://vixsrc.to/playlist/620626'
            }
            </script>
          """.trimIndent()
          response(url, 200, html, "text/html")
        }
        url.contains("/playlist/620626") -> {
          val m3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2150000,RESOLUTION=1280x720
            https://vixsrc.to/playlist/620626?rendition=720p
          """.trimIndent()
          response(url, 200, m3u8, "application/vnd.apple.mpegurl")
        }
        else -> response(url, 404, "Not Found")
      }
    }

    val provider = VixSrcProvider(http = client)
    val sources = provider.getStreams(tmdbId = 1418, isTv = true, season = 2, episode = 5)

    assertEquals("L'endpoint TV deve essere interrogato con stagione 2 ed episodio 5", "/api/tv/1418/2/5", requestedApiPath)
    assertTrue("Deve produrre almeno una sorgente per la serie", sources.isNotEmpty())
    val first = sources.first()
    assertEquals("720p", first.quality)
    assertEquals("VixSrc", first.serverName)
  }

  // 3. Risposta HTTP di errore
  @Test
  fun testHttpErrorThrowsIOException(): Unit = runBlocking {
    val client = createMockClient { url ->
      response(url, 500, "Internal Server Error")
    }

    val provider = VixSrcProvider(http = client)
    try {
      provider.getStreams(tmdbId = 550, isTv = false)
      fail("Doveva lanciare IOException su HTTP 500")
    } catch (e: IOException) {
      assertTrue("Il messaggio deve contenere dettagli sull'errore", e.message?.contains("Nessun mirror") == true)
    }
  }

  // 4. Risposta vuota
  @Test
  fun testEmptyResponseThrowsIOException(): Unit = runBlocking {
    val client = createMockClient { url ->
      response(url, 200, "")
    }

    val provider = VixSrcProvider(http = client)
    try {
      provider.getStreams(tmdbId = 550, isTv = false)
      fail("Doveva lanciare IOException su risposta vuota")
    } catch (e: IOException) {
      assertTrue(e.message?.contains("Nessun mirror") == true)
    }
  }

  // 5. JSON malformato o schema inatteso
  @Test
  fun testMalformedJsonThrowsIOException(): Unit = runBlocking {
    val client = createMockClient { url ->
      response(url, 200, """{"invalidField":"no_src_here"}""")
    }

    val provider = VixSrcProvider(http = client)
    try {
      provider.getStreams(tmdbId = 550, isTv = false)
      fail("Doveva lanciare IOException su schema senza campo 'src'")
    } catch (e: IOException) {
      assertTrue(e.message?.contains("Nessun mirror") == true)
    }
  }

  // 6. URL/sorgente valida attraversa la pipeline e viene emessa
  @Test
  fun testValidSourceFlowsThroughStreamManager(): Unit = runBlocking {
    val client = createMockClient { url ->
      when {
        url.contains("/api/movie/550") -> response(url, 200, """{"src":"/embed/170060"}""")
        url.contains("/embed/170060") -> {
          val html = """
            <script>
            window.streams = [{"name":"Server1","active":true,"url":"https://vixsrc.to/playlist/170060?ub=1"}];
            window.masterPlaylist = {
                params: { 'token': 'abc', 'expires': '123' },
                url: 'https://vixsrc.to/playlist/170060'
            }
            </script>
          """.trimIndent()
          response(url, 200, html, "text/html")
        }
        url.contains("/playlist/170060") -> {
          response(url, 200, "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nhttps://vixsrc.to/stream.m3u8", "application/vnd.apple.mpegurl")
        }
        else -> response(url, 404, "Not Found")
      }
    }

    val vixProvider = VixSrcProvider(http = client)
    val streamManager = StreamManager(
      providers = listOf(vixProvider),
      timeoutMs = 3000L
    )

    val emissions = streamManager.resolveFlow(
      tmdbId = 550,
      isTv = false,
      streamingEngineMode = StreamingEngineMode.HTTP_WEB
    ).first { it.isNotEmpty() }

    assertTrue("La sorgente VixSrc deve essere emessa dalla pipeline", emissions.isNotEmpty())
    val best = emissions.first()
    assertEquals("VixSrc", best.serverName)
    assertEquals("1080p", best.quality)
    assertNotNull(best.streamUrl)
  }

  // 7. Provider escluso dalla modalità non compatibile e incluso in quella prevista
  @Test
  fun testProviderExcludedInDebridTorboxAndIncludedInHttpWeb(): Unit = runBlocking {
    val mockVix = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int, isTv: Boolean, season: Int?, episode: Int?, title: String?, year: Int?
      ): List<StreamSource> = listOf(
        StreamSource(streamUrl = "https://vixsrc.to/stream.m3u8", quality = "1080p", serverName = "VixSrc")
      )
    }

    val streamManager = StreamManager(
      providers = listOf(mockVix),
      timeoutMs = 1000L
    )

    // In DEBRID_TORBOX: VixSrc non deve essere eseguito (riservato a TorBox)
    val debridEmissions = streamManager.resolveFlow(
      tmdbId = 550,
      isTv = false,
      streamingEngineMode = StreamingEngineMode.DEBRID_TORBOX
    ).toList()
    assertTrue("In DEBRID_TORBOX VixSrc deve essere escluso", debridEmissions.all { it.isEmpty() } || debridEmissions.isEmpty())

    // In HTTP_WEB: VixSrc deve essere incluso ed emettere sorgenti
    val httpWebEmissions = streamManager.resolveFlow(
      tmdbId = 550,
      isTv = false,
      streamingEngineMode = StreamingEngineMode.HTTP_WEB
    ).first { it.isNotEmpty() }
    assertTrue("In HTTP_WEB VixSrc deve essere incluso", httpWebEmissions.isNotEmpty())
    assertEquals("VixSrc", httpWebEmissions.first().serverName)
  }

  // 8. Errore di rete/timeout senza eccezioni silenziate
  @Test
  fun testNetworkTimeoutThrowsIOExceptionWithoutSilentSuppression(): Unit = runBlocking {
    val client = createMockClient { _ ->
      throw SocketTimeoutException("Read timed out mock")
    }

    val provider = VixSrcProvider(http = client)
    try {
      provider.getStreams(tmdbId = 550, isTv = false)
      fail("Doveva lanciare IOException sul timeout di rete")
    } catch (e: IOException) {
      assertTrue("L'eccezione non deve essere silenziata e deve spiegare il fallimento", e.message?.contains("Read timed out mock") == true || e.message?.contains("Nessun mirror") == true)
    }
  }
}
