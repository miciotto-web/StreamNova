package com.example.data.network

import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della diagnostica TLS [TlsDiagnostics] con la catena REALE di
 * xperience-app.com (snapshot `tls/xperience_chain.pem`, notBefore 16/11/2026
 * 02:51:40 GMT — la rotazione Cloudflare che ha scatenato l'errore
 * "chain validation failed" sull'emulatore con orologio indietro di ~19 ore).
 */
class TlsDiagnosticsTest {

  private fun leaf(): X509Certificate = chain().first()

  private fun chain(): List<X509Certificate> {
    val stream = TlsDiagnosticsTest::class.java.classLoader!!.getResourceAsStream(
      "tls/xperience_chain.pem"
    ) ?: throw IOException("Risorsa tls/xperience_chain.pem assente")
    stream.use {
      return CertificateFactory.getInstance("X.509")
        .generateCertificates(it)
        .toList()
        .map { cert -> cert as X509Certificate }
    }
  }

  @Test
  fun `orologio indietro rispetto al notBefore produce messaggio azionabile`() {
    val cert = leaf()
    val beforeValidity = cert.notBefore.time - 60 * 60 * 1000L
    val message = TlsDiagnostics.clockMismatchMessage(
      deviceNowMillis = beforeValidity,
      notBefore = cert.notBefore,
      notAfter = cert.notAfter,
      host = "xperience-app.com"
    )
    assertNotNull(message)
    assertTrue(message!!.contains("indietro"))
    assertTrue(message.contains("xperience-app.com"))
  }

  @Test
  fun `orologio avanti rispetto al notAfter produce messaggio azionabile`() {
    val cert = leaf()
    val afterExpiry = cert.notAfter.time + 60 * 60 * 1000L
    val message = TlsDiagnostics.clockMismatchMessage(
      deviceNowMillis = afterExpiry,
      notBefore = cert.notBefore,
      notAfter = cert.notAfter,
      host = "xperience-app.com"
    )
    assertNotNull(message)
    assertTrue(message!!.contains("avanti"))
  }

  @Test
  fun `orologio dentro la finestra di validita non produce mismatch`() {
    val cert = leaf()
    val midValidity = cert.notBefore.time + (cert.notAfter.time - cert.notBefore.time) / 2
    assertNull(
      TlsDiagnostics.clockMismatchMessage(
        deviceNowMillis = midValidity,
        notBefore = cert.notBefore,
        notAfter = cert.notAfter,
        host = "xperience-app.com"
      )
    )
  }

  @Test
  fun `rileva il fallimento TLS anche annidato nelle cause`() {
    val nested = IOException("esterno", SSLHandshakeException("Chain validation failed"))
    assertTrue(TlsDiagnostics.isTlsHandshakeFailure(nested))
    assertTrue(TlsDiagnostics.isTlsHandshakeFailure(SSLHandshakeException("Chain validation failed")))
    assertFalse(TlsDiagnostics.isTlsHandshakeFailure(IOException("Manifest non trovato")))
    // Ciclo di cause (a -> b -> a) non deve bloccare il rilevamento.
    val cyclicA = IOException("a")
    val cyclicB = IOException("b", cyclicA)
    cyclicA.initCause(cyclicB)
    assertFalse(TlsDiagnostics.isTlsHandshakeFailure(cyclicA))
  }

  @Test
  fun `fallimenti non TLS restituiscono l'eccezione originale invariata`() {
    val plain = IOException("timeout di rete")
    assertSame(plain, TlsDiagnostics.translateTlsFailure(plain, "https://host.example/manifest.json"))
    // TLS fallito ma diagnosi impossibile (host irraggiungibile): resta invariato.
    val tls = SSLHandshakeException("Chain validation failed")
    val wrapped = IOException("esterno", tls)
    assertSame(
      wrapped,
      TlsDiagnostics.translateTlsFailure(wrapped, "http://127.0.0.1:1/manifest.json")
    )
  }
}
