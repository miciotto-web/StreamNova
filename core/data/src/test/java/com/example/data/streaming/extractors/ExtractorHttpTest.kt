package com.example.data.streaming.extractors

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test offline del wrapper HTTP condiviso, con MockWebServer locale:
 * redirect, status di errore, callTimeout e cancellazione della coroutine.
 */
class ExtractorHttpTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    ExtractorHttp.clearCookies()
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    ExtractorHttp.clearCookies()
    server.shutdown()
  }

  @Test
  fun segueIlRedirectERestituisceUrlFinale() = runBlocking {
    server.enqueue(
      MockResponse()
        .setResponseCode(302)
        .setHeader("Location", "/final/master.m3u8")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U\n"))

    val (finalUrl, body) = ExtractorHttp.getWithFinalUrl(server.url("/start").toString())

    assertEquals(server.url("/final/master.m3u8").toString(), finalUrl)
    assertTrue(body.startsWith("#EXTM3U"))
  }

  @Test
  fun statusDiErroreSollevaIOExceptionConIlCodice() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(500).setBody("errore"))

    val error = runCatching {
      ExtractorHttp.get(server.url("/boom").toString())
    }.exceptionOrNull()

    assertTrue(error is IOException)
    assertTrue(error?.message.orEmpty().contains("HTTP 500"))
  }

  @Test
  fun callTimeoutInterrompeUnaRispostaLenta() = runBlocking {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("#EXTM3U")
        .setBodyDelay(2, TimeUnit.SECONDS)
    )

    val error = runCatching {
      ExtractorHttp.get(
        server.url("/slow").toString(),
        callTimeoutMs = 200
      )
    }.exceptionOrNull()

    assertTrue("atteso IOException di timeout, ottenuto $error", error is IOException)
  }

  @Test
  fun iCookieDiSessioneRestanoContinuiFraPaginaEPlayer() = runBlocking {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("Set-Cookie", "sid=abc; Path=/player; Max-Age=3600")
        .setBody("<html></html>")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U\n"))
    server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

    ExtractorHttp.get(server.url("/embed").toString())
    ExtractorHttp.get(server.url("/player/master.m3u8").toString())
    ExtractorHttp.get(server.url("/other").toString())

    server.takeRequest()
    val playerRequest = server.takeRequest()
    val otherRequest = server.takeRequest()

    assertEquals("cookie della pagina padre mancante", "sid=abc", playerRequest.getHeader("Cookie"))
    assertNull("il cookie non deve uscire dal Path dichiarato", otherRequest.getHeader("Cookie"))
  }

  @Test
  fun unCookieScadutoNonVieneReinviato() = runBlocking {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("Set-Cookie", "sid=old; Path=/; Max-Age=0")
        .setBody("<html></html>")
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U\n"))

    ExtractorHttp.get(server.url("/embed").toString())
    ExtractorHttp.get(server.url("/player/master.m3u8").toString())

    server.takeRequest()
    val playerRequest = server.takeRequest()
    assertNull("un cookie scaduto non va inviato", playerRequest.getHeader("Cookie"))
  }

  @Test
  fun cancellazioneDellaCoroutineInterrompeLaRichiesta() = runBlocking {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("#EXTM3U")
        .setBodyDelay(2, TimeUnit.SECONDS)
    )

    val thrown = runCatching {
      withTimeout(150) {
        ExtractorHttp.get(server.url("/cancel").toString())
      }
    }.exceptionOrNull()

    assertTrue("atteso TimeoutCancellationException, ottenuto $thrown", thrown is TimeoutCancellationException)
  }
}
