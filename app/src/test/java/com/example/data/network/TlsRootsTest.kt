package com.example.data.network

import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test del fallback TLS [TlsRoots] sulla catena REALE servita da
 * `xperience-app.com` (snapshot `tls/xperience_chain.pem`: foglia + intermedi
 * WE1 + GTS Root R4 cross-firmato da GlobalSign Root CA).
 *
 * La catena è quella che l'addon Xperience presentava durante l'errore
 * `SSLHandshakeException: Chain validation failed`: le radici GTS incluse nel
 * fallback devono permettere la validazione anche a un negozio di sistema che
 * NON le contiene (vecchi Android TV).
 */
class TlsRootsTest {

  private fun realXperienceChain(): Array<X509Certificate> {
    val stream = TlsRootsTest::class.java.classLoader!!.getResourceAsStream(
      "tls/xperience_chain.pem"
    ) ?: throw IOException("Risorsa tls/xperience_chain.pem assente")
    stream.use {
      return CertificateFactory.getInstance("X.509")
        .generateCertificates(it)
        .toList()
        .map { cert -> cert as X509Certificate }
        .toTypedArray()
    }
  }

  @Test
  fun `le radici GTS incluse sono le quattro R1-R4 valide fino al 2036`() {
    val roots = TlsRoots.certificates()
    assertEquals(4, roots.size)
    val subjects = roots.map { it.subjectX500Principal.name }
    listOf("GTS Root R1", "GTS Root R2", "GTS Root R3", "GTS Root R4").forEach { name ->
      assertTrue("Radice $name assente dal bundle", subjects.any { it.contains(name) })
    }
    roots.forEach { root ->
      assertTrue(
        "Radice ${root.subjectX500Principal} con validità troppo breve",
        root.notAfter.after(java.util.Date(1767225600000L)) // 1/1/2026
      )
    }
  }

  @Test
  fun `la catena reale di xperience-app com passa con il trust manager di fallback`() {
    val chain = realXperienceChain()
    assertEquals(3, chain.size)
    // Non deve lanciare: la validazione combinata (sistema + radici GTS) accetta
    // la catena Cloudflare/Google Trust Services.
    TlsRoots.combinedTrustManager().checkServerTrusted(chain, "ECDHE_ECDSA")
  }

  @Test
  fun `le radici GTS sono decisive su un negozio di sistema che non le contiene`() {
    // Catena BREVE (foglia + WE1, senza la radice R4 cross-firmata servita dal
    // server): senza un anchor GTS nel negozio è OGGETTIVAMENTE invalidabile su
    // qualunque piattaforma (il percorso non raggiunge nessuna radice nota).
    val shortChain = realXperienceChain().take(2).toTypedArray()
    val fullChain = realXperienceChain()

    // Simula un vecchio Android TV: anchor di sistema SENZA le radici Google
    // Trust Services. La catena breve NON deve passare; con SOLO le radici GTS
    // incluse in TlsRoots DEVE passare (foglia <- WE1 <- anchor GTS Root R4).
    val systemWithoutGts = TlsRoots.systemTrustManager().acceptedIssuers.toList()
      .filterNot { it.subjectX500Principal.name.contains("Google Trust Services") }

    try {
      TlsRoots.trustManagerFor(systemWithoutGts).checkServerTrusted(shortChain, "ECDHE_ECDSA")
      fail("La catena breve non doveva validare senza le radici GTS")
    } catch (expected: CertificateException) {
      // atteso: nessun anchor GTS nel negozio simulato
    }

    // Solo le radici incluse nel bundle bastano per la catena breve.
    TlsRoots.trustManagerFor(TlsRoots.certificates()).checkServerTrusted(
      shortChain,
      "ECDHE_ECDSA"
    )

    // E con negozio simulato + bundle passa anche la catena COMPLETA (la radice
    // cross-firmata da GlobalSign Root CA viene comunque agganciata alle anchor
    // di sistema, come fa il server nella configurazione reale).
    TlsRoots.trustManagerFor(systemWithoutGts + TlsRoots.certificates())
      .checkServerTrusted(fullChain, "ECDHE_ECDSA")
  }

  @Test
  fun `un PEM corrotto produce un errore esplicito`() {
    try {
      TlsRoots.parsePems(listOf("-----BEGIN CERTIFICATE-----\nrotto\n-----END CERTIFICATE-----"))
      fail("Attesa eccezione su PEM invalido")
    } catch (expected: Exception) {
      assertNotNull(expected.message)
    }
  }
}
