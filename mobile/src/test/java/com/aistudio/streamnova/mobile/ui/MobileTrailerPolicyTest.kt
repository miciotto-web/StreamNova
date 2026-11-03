package com.aistudio.streamnova.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileTrailerPolicyTest {

  @Test
  fun lEmbedUsaLaChiaveRealeEImpedisceLAperturaEsterna() {
    val url = trailerEmbedUrl("abc123")

    assertTrue(url.startsWith("https://www.youtube.com/embed/abc123"))
    assertTrue(url.contains("autoplay=1"))
    assertFalse(url.contains("youtu.be"))
  }

  @Test
  fun lHtmlContieneUnUnicoIframeConLaChiave() {
    val html = trailerEmbedHtml("abc123")

    assertTrue(html.contains("https://www.youtube.com/embed/abc123"))
    assertTrue(html.contains("<iframe"))
    assertTrue(html.contains("allow=\"accelerometer; autoplay"))
  }

  @Test
  fun sonoAmmessiSoloIDominiDellEmbed() {
    assertTrue(isAllowedTrailerUrl("https://localhost"))
    assertTrue(isAllowedTrailerUrl("https://www.youtube.com/embed/abc"))
    assertTrue(isAllowedTrailerUrl("https://youtube.com/watch?v=abc"))
    assertTrue(isAllowedTrailerUrl("https://youtube-nocookie.com/embed/abc"))
    assertTrue(isAllowedTrailerUrl("https://rr1---sn-abc.googlevideo.com/videoplayback"))
    assertTrue(isAllowedTrailerUrl("https://i.ytimg.com/vi/abc/hqdefault.jpg"))
    assertTrue(isAllowedTrailerUrl("https://www.google.com/"))
  }

  @Test
  fun iDominiEsterniEUMalformatiSonoBloccati() {
    assertFalse(isAllowedTrailerUrl("https://example.com/"))
    assertFalse(isAllowedTrailerUrl("https://youtube.com.evil.example/embed/abc"))
    assertFalse(isAllowedTrailerUrl("https://youtube.com@evil.example/embed/abc"))
    assertFalse(isAllowedTrailerUrl("http://notyoutube.com/"))
    assertFalse(isAllowedTrailerUrl(""))
  }
}
