package com.example.ui.screens.player

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test offline del refresh token vidxgo: verifica la riscrittura di host/token,
 * il retry su 403 e l'isolamento degli host non-CDN. Nessuna richiesta verso
 * Internet: il refresh e il CDN sono serviti da [MockWebServer].
 */
class VidxgoTokenInterceptorTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun refreshOrigin(): String = server.url("/").toString().trimEnd('/')

  private fun client(interceptor: VidxgoTokenInterceptor): OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor(interceptor)
      .connectTimeout(2, TimeUnit.SECONDS)
      .readTimeout(2, TimeUnit.SECONDS)
      .build()

  private fun interceptor(nowMs: Long): VidxgoTokenInterceptor =
    VidxgoTokenInterceptor(
      refreshOrigin = refreshOrigin(),
      cdnHostPredicate = { true },
      nowMs = { nowMs },
      refreshClient = OkHttpClient()
    )

  @Test
  fun rinnovaPrimaDellaScadenzaERiscriveSoloTokenEScadenza() {
    // Come il `signUrl` del player web: si ruotano solo t/e. Il `b` della
    // rendition in riproduzione e il `p` della richiesta restano invariati
    // anche se la URL restituita da /t ne dichiara di diversi.
    val freshMaster = server.url("/hls/0133093/master.m3u8?t=NEW&e=900000&b=1")
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$freshMaster","expire":900000}""")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val request = Request.Builder()
      .url(server.url("/hls/0133093/seg-1.ts?t=OLD&e=1000&b=1500&p=abc"))
      .build()
    client(interceptor(nowMs = 2_000L)).newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    val refresh = server.takeRequest()
    assertEquals("/t/0133093", refresh.path)

    val cdn = server.takeRequest()
    val url = cdn.requestUrl!!
    assertEquals("/hls/0133093/seg-1.ts", url.encodedPath)
    assertEquals("NEW", url.queryParameter("t"))
    assertEquals("900000", url.queryParameter("e"))
    assertEquals("1500", url.queryParameter("b"))
    assertEquals("abc", url.queryParameter("p"))
  }

  @Test
  fun nonRinnovaSeIlTokenElValido() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val request = Request.Builder()
      .url(server.url("/hls/0133093/seg-1.ts?t=OLD&e=999999&p=abc"))
      .build()
    client(interceptor(nowMs = 1_000L)).newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(1, server.requestCount)
    val url = server.takeRequest().requestUrl!!
    assertEquals("OLD", url.queryParameter("t"))
    assertEquals("999999", url.queryParameter("e"))
    assertEquals("abc", url.queryParameter("p"))
  }

  @Test
  fun su403RinnovaERitentaUnaSolaVolta() {
    // 1) prima richiesta CDN -> 403
    server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
    // 2) refresh -> nuovo token
    val freshMaster = server.url("/hls/0133093/master.m3u8?t=NEW&e=900000&b=1")
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$freshMaster","expire":900000}""")
    )
    // 3) retry -> 200
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val request = Request.Builder()
      .url(server.url("/hls/0133093/seg-1.ts?t=OLD&e=999999&p=abc"))
      .build()
    client(interceptor(nowMs = 1_000L)).newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(3, server.requestCount)
    assertEquals("OLD", server.takeRequest().requestUrl!!.queryParameter("t"))
    assertEquals("/t/0133093", server.takeRequest().path)
    assertEquals("NEW", server.takeRequest().requestUrl!!.queryParameter("t"))
  }

  @Test
  fun serieUsaPercorsoConStagioneEdEpisodio() {
    val freshMaster = server.url("/hls/tv/16358384/2/1/master.m3u8?t=NEW&e=900000&b=1")
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$freshMaster","expire":900000}""")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val request = Request.Builder()
      .url(server.url("/hls/tv/16358384/2/1/seg-1.ts?t=OLD&e=1000&p=abc"))
      .build()
    client(interceptor(nowMs = 2_000L)).newCall(request).execute().close()

    assertEquals("/t/16358384/2/1", server.takeRequest().path)
    val cdn = server.takeRequest().requestUrl!!
    assertEquals("/hls/tv/16358384/2/1/seg-1.ts", cdn.encodedPath)
    assertEquals("NEW", cdn.queryParameter("t"))
  }

  @Test
  fun mantieneIlPercorsoProxyDelPlayer() {
    // La URL del player può essere proxata: il refresh non deve perdere il
    // prefisso `/proxy/media-402/` sostituendolo con il path della risposta
    // token (che è diretto). Il player web mantiene il path della richiesta.
    val freshMaster = server.url("/hls/0133093/master.m3u8?t=NEW&e=900000&b=3098")
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$freshMaster","expire":900000}""")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val request = Request.Builder()
      .url(server.url("/proxy/media-402/hls/0133093/seg-1.ts?t=OLD&e=1000&p=abc"))
      .build()
    client(interceptor(nowMs = 2_000L)).newCall(request).execute().close()

    assertEquals("/t/0133093", server.takeRequest().path)
    val cdn = server.takeRequest().requestUrl!!
    assertEquals("/proxy/media-402/hls/0133093/seg-1.ts", cdn.encodedPath)
    assertEquals("NEW", cdn.queryParameter("t"))
    assertEquals("abc", cdn.queryParameter("p"))
  }

  @Test
  fun hostNonCdnNonVieneToccato() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

    val interceptor = VidxgoTokenInterceptor(
      refreshOrigin = "http://refresh.invalid",
      cdnHostPredicate = { false },
      nowMs = { 2_000L },
      refreshClient = OkHttpClient()
    )
    val request = Request.Builder()
      .url(server.url("/hls/0133093/seg-1.ts?t=OLD&e=1000&p=abc"))
      .build()
    client(interceptor).newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(1, server.requestCount)
    assertTrue(server.takeRequest().path!!.contains("t=OLD"))
  }

  @Test
  fun conOrologioAvantiNonRinnovaAdOgniRichiesta() {
    // Il server dichiara una scadenza (900000) nel passato rispetto
    // all'orologio del dispositivo (2000000): non deve trasformare ogni
    // segmento in un refresh sincrono. Dopo il primo rinnovo la deadline è
    // locale (now + 150 s), quindi la seconda richiesta non rinnova.
    val freshMaster = server.url("/hls/0133093/master.m3u8?t=NEW&e=900000&b=3098")
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$freshMaster","expire":900000}""")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val client = client(interceptor(nowMs = 2_000_000L))
    val first = Request.Builder()
      .url(server.url("/hls/0133093/seg-1.ts?t=OLD&e=1000&b=3098&p="))
      .build()
    val second = Request.Builder()
      .url(server.url("/hls/0133093/seg-2.ts?t=OLD&e=1000&b=3098&p="))
      .build()
    client.newCall(first).execute().close()
    client.newCall(second).execute().close()

    assertEquals(3, server.requestCount)
    assertEquals("/t/0133093", server.takeRequest().path)
    assertEquals("/hls/0133093/seg-1.ts", server.takeRequest().requestUrl!!.encodedPath)
    assertEquals("/hls/0133093/seg-2.ts", server.takeRequest().requestUrl!!.encodedPath)
  }

  @Test
  fun ilRefreshNonSostituisceIParametriDellaRichiesta() {
    // Anche se la risposta token dichiara `p`/`b` diversi, la richiesta firmata
    // resta quella originale: `p` è validato dal CDN e `b` identifica la
    // rendition in riproduzione. Il player web ruota solo t/e.
    val freshMaster = server.url("/hls/0133093/master.m3u8?t=NEW&e=900000&b=1&p=NEWP")
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$freshMaster","expire":900000}""")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val request = Request.Builder()
      .url(server.url("/hls/0133093/seg-1.ts?t=OLD&e=1000&b=1500&p=OLDP"))
      .build()
    client(interceptor(nowMs = 2_000L)).newCall(request).execute().close()

    assertEquals("/t/0133093", server.takeRequest().path)
    val cdn = server.takeRequest().requestUrl!!
    assertEquals("NEW", cdn.queryParameter("t"))
    assertEquals("900000", cdn.queryParameter("e"))
    assertEquals("1500", cdn.queryParameter("b"))
    assertEquals("OLDP", cdn.queryParameter("p"))
  }

  @Test
  fun ilTokenSuUnHostDiversoNonSpostaLaRichiesta() {
    // La risposta token può indicare un host diverso (es. cdn.v1 invece di un
    // edge proxy). La richiesta deve restare sull'host/path originali: il nuovo
    // token viene applicato solo come t/e. Se l'interceptor usasse l'host della
    // risposta, questa richiesta non raggiungerebbe più il MockWebServer.
    val freshMaster = "https://other.invalid/hls/0133093/master.m3u8?t=NEW&e=900000"
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$freshMaster","expire":900000}""")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val request = Request.Builder()
      .url(server.url("/hls/0133093/seg-1.ts?t=OLD&e=1000&b=1500&p=abc"))
      .build()
    client(interceptor(nowMs = 2_000L)).newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals("/t/0133093", server.takeRequest().path)
    val cdn = server.takeRequest().requestUrl!!
    assertEquals(server.hostName, cdn.host)
    assertEquals("/hls/0133093/seg-1.ts", cdn.encodedPath)
    assertEquals("NEW", cdn.queryParameter("t"))
  }

  @Test
  fun unRefreshFallitoNonVieneRipetutoAdOgniRichiesta() {
    // Endpoint token non disponibile: il primo tentativo fallisce, ma le
    // richieste CDN successive non devono restare bloccate su un nuovo
    // tentativo sincrono (backoff). Il token originale prosegue.
    server.enqueue(MockResponse().setResponseCode(500))
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val client = client(interceptor(nowMs = 2_000_000L))
    val first = Request.Builder()
      .url(server.url("/hls/0133093/seg-1.ts?t=OLD&e=1000&b=3098&p="))
      .build()
    val second = Request.Builder()
      .url(server.url("/hls/0133093/seg-2.ts?t=OLD&e=1000&b=3098&p="))
      .build()
    client.newCall(first).execute().close()
    client.newCall(second).execute().close()

    assertEquals(3, server.requestCount)
    assertEquals("/t/0133093", server.takeRequest().path)
    assertEquals("/hls/0133093/seg-1.ts", server.takeRequest().requestUrl!!.encodedPath)
    assertEquals("/hls/0133093/seg-2.ts", server.takeRequest().requestUrl!!.encodedPath)
  }

  @Test
  fun rinnovaDopoLaDeadlineLocaleDelTokenFresco() {
    // Con orologio sano il token appena rinnovato vale 150 s locali:
    // a 149,999 s non si rinnova, a 150 s sì.
    var now = 1_000L
    val firstMaster = server.url("/hls/0133093/master.m3u8?t=T1&e=181000&b=3098")
    val secondMaster = server.url("/hls/0133093/master.m3u8?t=T2&e=331000&b=3098")
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$firstMaster","expire":181000}""")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$secondMaster","expire":331000}""")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val interceptor = VidxgoTokenInterceptor(
      refreshOrigin = refreshOrigin(),
      cdnHostPredicate = { true },
      nowMs = { now },
      refreshClient = OkHttpClient()
    )
    val client = client(interceptor)
    fun request(segment: Int): Request = Request.Builder()
      .url(server.url("/hls/0133093/seg-$segment.ts?t=OLD&e=1000&b=3098&p="))
      .build()

    client.newCall(request(1)).execute().close()
    now = 150_999L
    client.newCall(request(2)).execute().close()
    now = 151_000L
    client.newCall(request(3)).execute().close()

    assertEquals(5, server.requestCount)
    assertEquals("/t/0133093", server.takeRequest().path)
    assertEquals("/hls/0133093/seg-1.ts", server.takeRequest().requestUrl!!.encodedPath)
    assertEquals("/hls/0133093/seg-2.ts", server.takeRequest().requestUrl!!.encodedPath)
    assertEquals("/t/0133093", server.takeRequest().path)
    assertEquals("T2", server.takeRequest().requestUrl!!.queryParameter("t"))
  }
}
