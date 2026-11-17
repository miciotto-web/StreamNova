package com.example.data.streaming.providers

import com.example.data.streaming.extractors.DoodstreamExtractor
import com.example.data.streaming.extractors.StreamtapeExtractor
import com.example.data.streaming.extractors.UqloadExtractor
import com.example.data.streaming.extractors.VoeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica il cablaggio dei nuovi hoster nel resolver: dominio -> estrattore,
 * label riconosciute e stato "supportato". Nessuna rete.
 */
class HosterResolverNewHostersTest {

  @Test
  fun risolveIDominiDeiNuoviHoster() {
    assertEquals(UqloadExtractor::class.java, HosterResolver.extractorFor("https://uqload.vc/e/abc")?.javaClass)
    assertEquals(UqloadExtractor::class.java, HosterResolver.extractorFor("https://uqload.cx/e/abc")?.javaClass)
    assertEquals(StreamtapeExtractor::class.java, HosterResolver.extractorFor("https://streamtape.com/e/abc")?.javaClass)
    assertEquals(StreamtapeExtractor::class.java, HosterResolver.extractorFor("https://streamta.site/e/abc")?.javaClass)
    assertEquals(VoeExtractor::class.java, HosterResolver.extractorFor("https://voe.sx/e/abc")?.javaClass)
    assertEquals(DoodstreamExtractor::class.java, HosterResolver.extractorFor("https://dood.la/e/abc")?.javaClass)
    assertEquals(DoodstreamExtractor::class.java, HosterResolver.extractorFor("https://doodstream.com/e/abc")?.javaClass)
    assertEquals(DoodstreamExtractor::class.java, HosterResolver.extractorFor("https://vide0.net/e/abc")?.javaClass)
  }

  @Test
  fun iNuoviDominiSonoRiconosciutiESupportati() {
    listOf(
      "https://uqload.vc/e/abc",
      "https://streamtape.com/e/abc",
      "https://voe.sx/e/abc",
      "https://dood.la/e/abc"
    ).forEach { url ->
      assertTrue("$url deve essere un hoster noto", HosterResolver.isHosterUrl(url))
      assertTrue("$url deve essere supportato", HosterResolver.isSupportedHosterUrl(url))
    }
  }

  @Test
  fun accettaLeLabelDeiNuoviHoster() {
    listOf(
      "Uqload",
      "Uqload HD",
      "Streamtape",
      "Voe",
      "Voe ITA",
      "Doodstream",
      "Dood HD"
    ).forEach { label ->
      assertTrue("label '$label' non riconosciuta", HosterResolver.matchesHosterLabel(label))
    }
  }

  @Test
  fun unDominioSconosciutoRestaNonSupportato() {
    assertFalse(HosterResolver.isSupportedHosterUrl("https://example.com/e/abc"))
    assertEquals(null, HosterResolver.extractorFor("https://example.com/e/abc"))
  }
}
