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
  fun rinnovaPrimaDellaScadenzaERiscriveHostEToken() {
    val freshMaster = server.url("/hls/0133093/master.m3u8?t=NEW&e=900000&b=3098")
    server.enqueue(
      MockResponse().setResponseCode(200)
        .setBody("""{"url":"$freshMaster","expire":900000}""")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U"))

    val request = Request.Builder()
      .url(server.url("/hls/0133093/seg-1.ts?t=OLD&e=1000&p=abc"))
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
    assertEquals("3098", url.queryParameter("b"))
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
  fun normalizzaIlPercorsoProxyDelPlayer() {
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
    assertEquals("/hls/0133093/seg-1.ts", cdn.encodedPath)
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
}
