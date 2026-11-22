package com.example.data.addonhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratto dell'Addon Health Monitor: classificazione, riepilogo, codec. */
class AddonHealthTest {

  private fun report(
    id: String,
    status: AddonHealthStatus,
    latency: Long = 0L
  ) = AddonHealthReport(
    installId = id,
    name = "Addon $id",
    baseUrl = "https://$id.example/manifest.json",
    status = status,
    latencyMs = latency,
    checkedAtEpochMs = 1L
  )

  @Test
  fun `fast success is healthy without detail`() {
    val (status, detail) = AddonHealthClassifier.classify(
      AddonProbeOutcome(success = true, latencyMs = 800)
    )
    assertEquals(AddonHealthStatus.HEALTHY, status)
    assertNull(detail)
  }

  @Test
  fun `slow success is degraded but not unreachable`() {
    val (status, detail) = AddonHealthClassifier.classify(
      AddonProbeOutcome(success = true, latencyMs = 2_500)
    )
    assertEquals(AddonHealthStatus.DEGRADED, status)
    assertTrue(detail!!.contains("lenta"))
  }

  @Test
  fun `very slow success stays degraded`() {
    val (status, detail) = AddonHealthClassifier.classify(
      AddonProbeOutcome(success = true, latencyMs = 9_000)
    )
    assertEquals(AddonHealthStatus.DEGRADED, status)
    assertTrue(detail!!.contains("molto lenta"))
  }

  @Test
  fun `404 maps to invalid manifest`() {
    val (status, detail) = AddonHealthClassifier.classify(
      AddonProbeOutcome(success = false, latencyMs = 300, httpCode = 404)
    )
    assertEquals(AddonHealthStatus.INVALID_MANIFEST, status)
    assertTrue(detail!!.contains("404"))
  }

  @Test
  fun `server error is degraded and retryable`() {
    val (status, detail) = AddonHealthClassifier.classify(
      AddonProbeOutcome(success = false, latencyMs = 500, httpCode = 503)
    )
    assertEquals(AddonHealthStatus.DEGRADED, status)
    assertTrue(detail!!.contains("riprova"))
  }

  @Test
  fun `timeout and network failures are unreachable`() {
    assertEquals(
      AddonHealthStatus.UNREACHABLE,
      AddonHealthClassifier.classify(
        AddonProbeOutcome(success = false, latencyMs = 12_000, errorKind = AddonProbeErrorKind.TIMEOUT)
      ).first
    )
    assertEquals(
      AddonHealthStatus.UNREACHABLE,
      AddonHealthClassifier.classify(
        AddonProbeOutcome(success = false, latencyMs = 100, errorKind = AddonProbeErrorKind.NETWORK)
      ).first
    )
  }

  @Test
  fun `tls failure is unreachable`() {
    val (status, _) = AddonHealthClassifier.classify(
      AddonProbeOutcome(
        success = false,
        latencyMs = 100,
        errorKind = AddonProbeErrorKind.TLS,
        message = "Certificato non valido"
      )
    )
    assertEquals(AddonHealthStatus.UNREACHABLE, status)
  }

  @Test
  fun `classifies failure messages`() {
    assertEquals(
      AddonProbeErrorKind.TIMEOUT,
      AddonHealthClassifier.classifyFailureMessage("Timeout dopo 10s")
    )
    assertEquals(
      AddonProbeErrorKind.TLS,
      AddonHealthClassifier.classifyFailureMessage("SSL handshake failed")
    )
    assertEquals(
      AddonProbeErrorKind.INVALID_MANIFEST,
      AddonHealthClassifier.classifyFailureMessage("Manifest JSON non valido per https://x")
    )
    assertEquals(
      AddonProbeErrorKind.NETWORK,
      AddonHealthClassifier.classifyFailureMessage("Unable to resolve host")
    )
    assertEquals(
      AddonProbeErrorKind.UNKNOWN,
      AddonHealthClassifier.classifyFailureMessage(null)
    )
  }

  @Test
  fun `parses http code from repository message`() {
    assertEquals(
      404,
      AddonHealthClassifier.httpCodeFromMessage("Manifest non trovato (HTTP 404) per https://x/manifest.json")
    )
    assertNull(AddonHealthClassifier.httpCodeFromMessage("nessun codice"))
  }

  @Test
  fun `summary aggregates states and latency`() {
    val summary = AddonHealthClassifier.summarize(
      listOf(
        report("a", AddonHealthStatus.HEALTHY, 500),
        report("b", AddonHealthStatus.HEALTHY, 700),
        report("c", AddonHealthStatus.DEGRADED, 3_000),
        report("d", AddonHealthStatus.UNREACHABLE),
        report("e", AddonHealthStatus.INVALID_MANIFEST),
        report("f", AddonHealthStatus.DISABLED),
        report("g", AddonHealthStatus.UNKNOWN)
      )
    )
    assertEquals(6, summary.total)
    assertEquals(2, summary.healthy)
    assertEquals(1, summary.degraded)
    assertEquals(1, summary.unreachable)
    assertEquals(1, summary.invalid)
    assertEquals(1, summary.disabled)
    assertEquals(1_400L, summary.averageLatencyMs)
    assertTrue(summary.hasProblems)
  }

  @Test
  fun `codec roundtrip and corrupted json`() {
    val reports = listOf(
      report("a", AddonHealthStatus.HEALTHY, 500),
      report("b", AddonHealthStatus.DEGRADED, 2_000)
    )
    val decoded = AddonHealthCodec.decode(AddonHealthCodec.encode(reports))
    assertEquals(2, decoded.size)
    assertEquals(AddonHealthStatus.DEGRADED, decoded[1].status)
    assertTrue(AddonHealthCodec.decode("{rotto").isEmpty())
    assertTrue(AddonHealthCodec.decode(null).isEmpty())
  }

  @Test
  fun `detail sanitization removes full urls and credentials`() {
    val detail = AddonHealthRepository.sanitizeDetail(
      "Manifest non trovato (HTTP 404) per https://torrentio.example/secretBase64Key/manifest.json"
    )
    assertTrue(detail!!.contains("torrentio.example"))
    assertFalse(detail.contains("secretBase64Key"))
  }
}
