package com.example.data.streaming.diagnostics

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classificazione e sanificazione della diagnostica, senza rete. */
class StreamDiagnosticsTest {

  @Test
  fun timeoutDiCoroutineETimeoutDiSocketSonoEntrambiTimeout() = runBlocking {
    val coroutineTimeout = try {
      withTimeout(1) { awaitCancellation() }
      null
    } catch (e: TimeoutCancellationException) {
      e
    }

    assertEquals(StreamAttemptOutcome.TIMEOUT, StreamDiagnostics.classify(coroutineTimeout!!))
    assertEquals(
      StreamAttemptOutcome.TIMEOUT,
      StreamDiagnostics.classify(SocketTimeoutException("read timed out"))
    )
  }

  @Test
  fun cancellazioneEsplicitaNonDiventaUnErroreGenerico() {
    assertEquals(
      StreamAttemptOutcome.CANCELLED,
      StreamDiagnostics.classify(CancellationException("annullato"))
    )
  }

  @Test
  fun erroreHttpVieneDistintoDallErroreDiRete() {
    assertEquals(
      StreamAttemptOutcome.HTTP_ERROR,
      StreamDiagnostics.classify(IOException("HTTP 503 su https://cdn.example/e/x"))
    )
    assertEquals(
      StreamAttemptOutcome.NETWORK_ERROR,
      StreamDiagnostics.classify(IOException("unable to resolve host cdn.example"))
    )
    assertEquals(
      StreamAttemptOutcome.EXTRACTION_FAILED,
      StreamDiagnostics.classify(IllegalStateException("payload inatteso"))
    )
    // Un IOException con messaggio semantico non e' un problema di rete.
    assertEquals(
      StreamAttemptOutcome.EXTRACTION_FAILED,
      StreamDiagnostics.classify(IOException("nessun link hoster nell'articolo"))
    )
  }

  @Test
  fun challengeRiconosciutaSenzaBypass() {
    assertEquals(
      StreamAttemptOutcome.BLOCKED_BY_CHALLENGE,
      StreamDiagnostics.classify(IOException("Cloudflare challenge: captcha richiesto"))
    )
    assertEquals(
      StreamAttemptOutcome.BLOCKED_BY_CHALLENGE,
      StreamDiagnostics.classify(IOException("Just a moment... checking your browser"))
    )
    // Un 403 ordinario non e' una challenge.
    assertEquals(
      StreamAttemptOutcome.HTTP_ERROR,
      StreamDiagnostics.classify(IOException("HTTP 403 su https://cdn.example/e/x"))
    )
  }

  @Test
  fun sanificazioneRimuoveQueryTokenECredenziali() {
    val message =
      "HTTP 403 su https://cdn.example.test/e/abc?token=SEGRETO&sig=FIRMA con Authorization: Bearer abcdef123456"

    val sanitized = StreamDiagnostics.sanitize(message).orEmpty()

    assertFalse("la query firmata non deve restare", sanitized.contains("SEGRETO"))
    assertFalse("la firma non deve restare", sanitized.contains("FIRMA"))
    assertFalse("il token bearer non deve restare", sanitized.contains("abcdef123456"))
    assertTrue(sanitized.contains("https://cdn.example.test"))
    assertTrue(sanitized.contains("[redacted]"))
  }

  @Test
  fun sanificazioneRimuoveCookieEParametriSensibili() {
    val sanitized = StreamDiagnostics.sanitize(
      "cookie=sessionid=abc123 api_key=XYZ token = TOPSECRET"
    ).orEmpty()

    assertFalse(sanitized.contains("abc123"))
    assertFalse(sanitized.contains("XYZ"))
    assertFalse(sanitized.contains("TOPSECRET"))
  }

  @Test
  fun sanificazioneDiMessaggiVuotiNonInventaTesto() {
    assertNull(StreamDiagnostics.sanitize(null))
    assertNull(StreamDiagnostics.sanitize("   "))
  }

  @Test
  fun hostIdNonEsponePathOQuery() {
    assertEquals(
      "cdn.example.test",
      StreamDiagnostics.hostId("https://cdn.example.test/e/abc?token=SEGRETO")
    )
    assertNull(StreamDiagnostics.hostId("non un url"))
  }
}
