package com.example.data.streaming.providers

import com.example.data.streaming.extractors.FilemoonExtractor
import com.example.data.streaming.extractors.MaxStreamExtractor
import com.example.data.streaming.extractors.MixdropExtractor
import com.example.data.streaming.extractors.VidHideExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Riconoscimento degli hoster basato sul **dominio effettivo** dell'URL.
 *
 * I casi negativi riproducono i falsi positivi tipici: parametri query che
 * contengono il nome dell'hoster, frammenti, path arbitrari, domini che imitano
 * quelli veri e URL malformati.
 */
class HosterResolverTest {

  @Test
  fun riconosceIDominiDegliHosterIntegrati() {
    assertTrue(HosterResolver.isHosterUrl("https://maxstream.video/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://mixdrop.co/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://mxdrop.top/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://m1xdrop.net/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://vidhide.com/v/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://vidhide.fun/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://filemoon.to/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://filemoon.sx/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://stayonline.pro/l/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://uprot.net/msf/abc"))
  }

  @Test
  fun accettaSottodominiLegittimi() {
    assertTrue(HosterResolver.isHosterUrl("https://www.maxstream.video/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://cdn.mxdrop.top/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://player.filemoon.to/e/abc"))
    assertTrue(HosterResolver.isHosterUrl("https://WWW.VIDHIDE.COM/v/abc"))
    // La porta esplicita non altera l'host
    assertTrue(HosterResolver.isHosterUrl("https://maxstream.video:8443/e/abc"))
  }

  @Test
  fun nonAccettaHosterSoloComeSottostringa() {
    // Nome dell'hoster nella query o nel frammento: l'host è un altro.
    assertFalse(HosterResolver.isHosterUrl("https://example.com/watch?next=maxstream.video"))
    assertFalse(HosterResolver.isHosterUrl("https://example.com/#https://mixdrop.co/e/x"))
    // Nome dell'hoster nel path.
    assertFalse(HosterResolver.isHosterUrl("https://example.com/maxstream.video/e/x"))
    // Domini che imitano quelli veri.
    assertFalse(HosterResolver.isHosterUrl("https://notmaxstream.video/e/x"))
    assertFalse(HosterResolver.isHosterUrl("https://maxstream.video.evil.com/e/x"))
    assertFalse(HosterResolver.isHosterUrl("https://evil-filemoon.to/e/x"))
    assertFalse(HosterResolver.isHosterUrl("https://mixdrop.co.evil.net/e/x"))
  }

  @Test
  fun urlMalformatiOSenzaSchemaNonProduconoEccezioni() {
    assertFalse(HosterResolver.isHosterUrl(""))
    assertFalse(HosterResolver.isHosterUrl("maxstream.video/e/abc"))
    assertFalse(HosterResolver.isHosterUrl("https://"))
    assertFalse(HosterResolver.isHosterUrl("https:///e/abc"))
    assertFalse(HosterResolver.isHosterUrl("not a url"))
    assertFalse(HosterResolver.isHosterUrl("ftp://maxstream.video/e/abc"))
    assertFalse(HosterResolver.isHosterUrl("http://127.0.0.1:1/maxstream.video"))
  }

  @Test
  fun confrontoDominioRispettaISottodomini() {
    assertTrue(HosterResolver.matchesDomain("maxstream.video", "maxstream.video"))
    assertTrue(HosterResolver.matchesDomain("eu.maxstream.video", "maxstream.video"))
    assertFalse(HosterResolver.matchesDomain("notmaxstream.video", "maxstream.video"))
    assertFalse(HosterResolver.matchesDomain("maxstream.video.evil.com", "maxstream.video"))
  }

  @Test
  fun extractorForMappaLaFamigliaCorretta() {
    assertEquals(
      VidHideExtractor::class.java,
      HosterResolver.extractorFor("https://vidhide.com/v/abc")?.javaClass
    )
    assertEquals(
      VidHideExtractor::class.java,
      HosterResolver.extractorFor("https://filelions.to/e/abc")?.javaClass
    )
    assertEquals(
      FilemoonExtractor::class.java,
      HosterResolver.extractorFor("https://filemoon.to/e/abc")?.javaClass
    )
    assertEquals(
      MaxStreamExtractor::class.java,
      HosterResolver.extractorFor("https://www.maxstream.video/e/abc")?.javaClass
    )
    assertEquals(
      MixdropExtractor::class.java,
      HosterResolver.extractorFor("https://mxdrop.top/e/abc")?.javaClass
    )
    // Hoster riconosciuto ma senza estrattore: null, non un'estrazione sbagliata.
    assertNull(HosterResolver.extractorFor("https://stayonline.pro/l/abc"))
    assertNull(HosterResolver.extractorFor("https://notmaxstream.video/e/abc"))
    assertNull(HosterResolver.extractorFor("https://maxstream.video.evil.com/e/abc"))
    assertNull(HosterResolver.extractorFor("non un url"))
  }

  @Test
  fun riconosceLeLabelDeiNuoviHoster() {
    assertTrue(HosterResolver.matchesHosterLabel("VidHide"))
    assertTrue(HosterResolver.matchesHosterLabel("  FileMoon  "))
    assertTrue(HosterResolver.matchesHosterLabel("StreamWish"))
    assertTrue(HosterResolver.matchesHosterLabel("Maxstream"))
    assertTrue(HosterResolver.matchesHosterLabel("Mixdrop"))
    assertTrue(HosterResolver.matchesHosterLabel("Delta Bit"))
    assertFalse(HosterResolver.matchesHosterLabel("YouTube"))
    assertFalse(HosterResolver.matchesHosterLabel("VidHideX"))
  }
}
