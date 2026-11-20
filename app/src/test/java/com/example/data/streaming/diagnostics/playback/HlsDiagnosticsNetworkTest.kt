package com.example.data.streaming.diagnostics.playback

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integrazione diagnostica con fixture locali su MockWebServer: nessun host
 * reale, test deterministici. La sonda HTTP della verifica di riproducibilità
 * produce gli esiti che il classificatore della FASE 5 mappa in categorie.
 */
class HlsDiagnosticsNetworkTest {

  private lateinit var server: MockWebServer
  private val master = """
    #EXTM3U
    #EXT-X-STREAM-INF:RESOLUTION=3840x2160,BANDWIDTH=9000000
    v2160.m3u8
    #EXT-X-STREAM-INF:RESOLUTION=2560x1440,BANDWIDTH=4000000
    v1440.m3u8
  """.trimIndent()

  private val masterWithCodecs = """
    #EXTM3U
    #EXT-X-STREAM-INF:RESOLUTION=3840x2160,BANDWIDTH=9000000,CODECS="avc1.640033,mp4a.40.2"
    v2160.m3u8
    #EXT-X-STREAM-INF:RESOLUTION=2560x1440,BANDWIDTH=4000000,CODECS="avc1.640028,mp4a.40.2"
    v1440.m3u8
  """.trimIndent()

  private val variantMedia = """
    #EXTM3U
    #EXT-X-TARGETDURATION:6
    #EXTINF:6.006,
    seg0.ts
    #EXT-X-ENDLIST
  """.trimIndent()

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun url(path: String = "/master.m3u8") = server.url(path).toString()

  // ------------------------------------------------------------------
  // Manifest serviti dal server locale
  // ------------------------------------------------------------------

  @Test
  fun manifestServitoConVarianti2160e1440Distinte() {
    server.enqueue(MockResponse().setBody(master))
    val body = okhttp3.Request.Builder().url(url()).build().let { request ->
      OkHttpClient().newCall(request).execute().use { it.body!!.string() }
    }

    val summary = com.example.data.streaming.HlsPlaylistSummaryParser.analyze(body, url())

    assertEquals(com.example.data.streaming.HlsPlaylistKind.MASTER, summary.kind)
    val heights = summary.variants.mapNotNull { it.height }
    assertEquals(listOf(2160, 1440), heights)
    // La distinzione 1440p/4K è garantita dalle etichette centralizzate.
    assertEquals("4K", com.example.data.streaming.StreamResolution.labelForHeight(heights[0]))
    assertEquals("1440p", com.example.data.streaming.StreamResolution.labelForHeight(heights[1]))
  }

  @Test
  fun verifyDetailedRestituisceLaSintesiDellaPlaylistOsservata() = runBlocking {
    // Master -> variante (2160p, la più alta) -> primo segmento: la sonda segue
    // il percorso reale della verifica, senza richieste extra per la sintesi.
    server.enqueue(MockResponse().setBody(masterWithCodecs))
    server.enqueue(MockResponse().setBody(variantMedia))
    server.enqueue(MockResponse().setBody("0123456789"))
    val probe = com.example.data.streaming.OkHttpStreamProbe(
      client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
    )
    val source = com.example.data.streaming.StreamSource(
      streamUrl = url(),
      serverName = "Test",
      quality = "Auto"
    )
    val verification = com.example.data.streaming.HttpSourceVerifier(probe).verifyDetailed(source)

    assertEquals(com.example.data.streaming.SourcePlayability.Playable, verification.playability)
    val summary = verification.summary
    assertNotNull(summary)
    // La playlist GIOCATA è la media playlist della variante scelta.
    assertEquals(com.example.data.streaming.HlsPlaylistKind.MEDIA, summary!!.kind)
    // Qualità e codec dichiarati dalla MASTER osservata: dati reali, uniti alla
    // sintesi della playlist giocata.
    assertEquals(2160, summary.declaredResolutionHeight)
    assertTrue(summary.declaredCodecs.contains("avc1.640033"))
    assertEquals(1, summary.segmentCount)
    assertFalse(summary.isLiveStream == true)
  }

  @Test
  fun verifyDetailedSuFileProgressivoNonHaSintesi() = runBlocking {
    server.enqueue(MockResponse().setBody("falso-video-binario"))
    val probe = com.example.data.streaming.OkHttpStreamProbe(
      client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
    )
    val source = com.example.data.streaming.StreamSource(streamUrl = url(), serverName = "Test")
    val verification = com.example.data.streaming.HttpSourceVerifier(probe).verifyDetailed(source)

    assertEquals(com.example.data.streaming.SourcePlayability.Playable, verification.playability)
    assertNull("Un progressivo non ha playlist HLS da sintetizzare", verification.summary)
  }

  // ------------------------------------------------------------------
  // Codici HTTP: la sonda li classifica e il classificatore li mappa
  // ------------------------------------------------------------------

  private suspend fun verifyAndClassify(status: Int): Pair<com.example.data.streaming.SourcePlayability, HlsErrorCategory?> {
    server.enqueue(MockResponse().setResponseCode(status))
    val probe = com.example.data.streaming.OkHttpStreamProbe(
      client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
    )
    val source = com.example.data.streaming.StreamSource(
      streamUrl = url(),
      serverName = "Test",
      quality = "Auto"
    )
    val verdict = com.example.data.streaming.HttpSourceVerifier(probe).verify(source)
    val category = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(httpStatus = status, phase = PlaybackPhase.MANIFEST)
    )?.category
    return verdict to category
  }

  @Test
  fun http403EautorizzazioneDefinitiva() = runBlocking {
    val (verdict, category) = verifyAndClassify(403)
    assertEquals(com.example.data.streaming.SourcePlayability.Unavailable, verdict)
    assertEquals(HlsErrorCategory.AUTHORIZATION_ERROR, category)
  }

  @Test
  fun http404EsorgenteNonDisponibile() = runBlocking {
    val (verdict, category) = verifyAndClassify(404)
    assertEquals(com.example.data.streaming.SourcePlayability.Unavailable, verdict)
    assertEquals(HlsErrorCategory.SOURCE_UNAVAILABLE, category)
  }

  @Test
  fun http429ETemporaneo() = runBlocking {
    val (verdict, category) = verifyAndClassify(429)
    assertEquals(com.example.data.streaming.SourcePlayability.TransientFailure, verdict)
    assertEquals(HlsErrorCategory.HTTP_ERROR, category)
  }

  @Test
  fun http500ETemporaneo() = runBlocking {
    val (verdict, category) = verifyAndClassify(500)
    assertEquals(com.example.data.streaming.SourcePlayability.TransientFailure, verdict)
    assertEquals(HlsErrorCategory.HTTP_ERROR, category)
  }

  @Test
  fun timeoutDiChiamataEFallimentoTemporaneo() = runBlocking {
    // Corpo ritardato oltre il callTimeout corto della sonda di test:
    // nessun host reale, tutto locale.
    server.enqueue(
      MockResponse().setBody(master).setBodyDelay(1500, TimeUnit.MILLISECONDS)
    )
    val probe = com.example.data.streaming.OkHttpStreamProbe(
      client = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build()
    )
    val source = com.example.data.streaming.StreamSource(
      streamUrl = url(),
      serverName = "Test"
    )
    val verdict = com.example.data.streaming.HttpSourceVerifier(probe).verify(source)

    assertEquals(com.example.data.streaming.SourcePlayability.TransientFailure, verdict)
    // Il timeout non produce MAI una categoria definitiva.
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        exceptionClassNames = listOf("SocketTimeoutException"),
        phase = PlaybackPhase.MANIFEST
      )
    )
    assertTrue(diagnosis!!.temporary)
  }

  @Test
  fun manifestMalformatoNonEattribuitoARete() {
    // Pagina HTML al posto del manifest: la verifica la scarta e la
    // classificazione resta non-rete.
    val category = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        media3ErrorCode = PlaybackErrorClassifier.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        phase = PlaybackPhase.MANIFEST
      )
    )!!.category
    assertEquals(HlsErrorCategory.MANIFEST_ERROR, category)
    assertFalse(category == HlsErrorCategory.NETWORK_ERROR)
  }

  // ------------------------------------------------------------------
  // Registro di sessioni (FASE 7): statistiche senza prendere decisioni
  // ------------------------------------------------------------------

  @Test
  fun ilRegistroSeparaLeSessioniEAggregaPerProvider() {
    val registry = PlaybackDiagnosticsRegistry
    registry.clear()
    try {
      val ok = HlsPlaybackSession(1L, providerId = "Provider A")
      ok.onPlayerStateChanged(PlaybackStateKind.READY)
      ok.onFirstFrameRendered()

      val transient = HlsPlaybackSession(2L, providerId = "Provider A")
      transient.onError(PlaybackErrorInput(exceptionClassNames = listOf("SocketTimeoutException")))

      val definitive = HlsPlaybackSession(3L, providerId = "Provider B")
      definitive.onError(PlaybackErrorInput(httpStatus = 403, phase = PlaybackPhase.MANIFEST))

      listOf(ok, transient, definitive).forEach { registry.register(it) }

      assertEquals(3, registry.size)
      assertTrue(registry.latestSession()?.sessionId == 3L)
      assertEquals(ok, registry.sessionById(1L))

      val stats = registry.providerStats()
      val providerA = stats.first { it.providerId == "Provider A" }
      val providerB = stats.first { it.providerId == "Provider B" }

      assertEquals(2, providerA.sessionCount)
      assertEquals(0, providerA.sessionsWithDefinitiveFailure)
      assertEquals(1, providerA.sessionsWithTransientProblem)
      assertEquals(1, providerA.sessionsWithoutIssues)
      assertEquals(1, providerB.sessionCount)
      assertEquals(1, providerB.sessionsWithDefinitiveFailure)
    } finally {
      registry.clear()
    }
  }

  @Test
  fun ilRegistroMantieneAlMassimoLeSessioniConsentite() {
    val registry = PlaybackDiagnosticsRegistry
    registry.clear()
    try {
      repeat(12) { index ->
        registry.register(HlsPlaybackSession(index + 100L, providerId = "P"))
      }
      assertEquals(8, registry.size)
      // Le più vecchie sono state eliminate (LRU per inserimento).
      assertNull(registry.sessionById(100L))
      assertNotNull(registry.sessionById(111L))
    } finally {
      registry.clear()
    }
  }
}
