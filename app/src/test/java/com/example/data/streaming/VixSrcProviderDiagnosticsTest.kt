package com.example.data.streaming

import com.example.data.streaming.diagnostics.StreamAttempt
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Catena provider -> mirror di [VixSrcProvider] registrata offline con
 * MockWebServer: un tentativo per mirror, con host sanificato (mai path,
 * query o token) e esito classificato.
 */
class VixSrcProviderDiagnosticsTest {

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

  private fun client() = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .build()

  private fun sink(): Pair<StreamDiagnosticSink, MutableList<StreamAttempt>> {
    val attempts = Collections.synchronizedList(mutableListOf<StreamAttempt>())
    return StreamDiagnosticSink { attempt -> attempts += attempt } to attempts
  }

  @Test
  fun mirrorNonRisoltoProduceUnTentativoSenzaUrlFirmati() = runBlocking {
    // Ogni mirror risponde 503 con un URL firmato nel body: la diagnostica
    // deve conservare solo l'host del mirror.
    repeat(8) {
      server.enqueue(
        MockResponse()
          .setResponseCode(503)
          .setBody("""{"error":"https://cdn.example.test/e/abc?token=SEGRETO"}""")
      )
    }
    val (diagnostics, attempts) = sink()
    val provider = VixSrcProvider(http = client(), diagnostics = diagnostics)

    runCatching { provider.getStreams(tmdbId = 550, isTv = false) }

    assertTrue("deve essere registrato almeno un tentativo per mirror", attempts.isNotEmpty())
    assertTrue(
      "gli esiti devono essere categorizzati, mai SUCCESS",
      attempts.all { it.outcome != StreamAttemptOutcome.SUCCESS }
    )
    assertTrue("ogni tentativo ha l'host del mirror", attempts.all { it.mirrorId != null })
    assertFalse(
      "nessun token nell'host/messaggio",
      attempts.any {
        it.message.orEmpty().contains("SEGRETO") || it.mirrorId.orEmpty().contains("SEGRETO")
      }
    )
    assertFalse(
      "nessun path nell'identificativo del mirror",
      attempts.any { it.mirrorId.orEmpty().contains("/") }
    )
  }

  @Test
  fun conServerNonRaggiungibileGliErroriRestanoDiRete() = runBlocking {
    server.shutdown()
    val (diagnostics, attempts) = sink()
    val provider = VixSrcProvider(http = client(), diagnostics = diagnostics)

    val error = runCatching { provider.getStreams(tmdbId = 550, isTv = false) }.exceptionOrNull()

    assertTrue("il provider fallisce quando nessun mirror risponde", error != null)
    assertTrue(attempts.isNotEmpty())
    assertTrue(
      "ogni tentativo e' un errore/assenza, mai un falso successo: ${attempts.map { it.outcome }}",
      attempts.all {
        it.outcome != StreamAttemptOutcome.SUCCESS && it.outcome != StreamAttemptOutcome.NO_RESULTS
      }
    )
    assertTrue(
      "gli host non raggiungibili sono classificati come errori di rete/timeout: ${attempts.map { it.outcome }}",
      attempts.any {
        it.outcome == StreamAttemptOutcome.NETWORK_ERROR || it.outcome == StreamAttemptOutcome.TIMEOUT
      }
    )
  }
}
