package com.example.data.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rilevamento della lingua italiana nelle stringhe di sorgente/addon.
 *
 * La priorità italiana ("Privilegia tracce italiane") si basa su questo flag:
 * un falso positivo farebbe scavalcare una release italiana reale da una
 * release non italiana, un falso negativo farebbe partire l'inglese.
 */
class StreamSourceLanguageDetectionTest {

  @Test
  fun recognizesItalianReleaseTokens() {
    assertTrue(StreamSource.isItalianSource("Movie.2024.1080p.ITA-ENG.mkv"))
    assertTrue(StreamSource.isItalianSource("Movie.2024.1080p.ITA.mkv"))
    assertTrue(StreamSource.isItalianSource("Movie.2024.ITA 5.1.1080p"))
    assertTrue(StreamSource.isItalianSource("Film Italiano 1080p"))
    assertTrue(StreamSource.isItalianSource("IlCorsaroViola IT"))
  }

  @Test
  fun doesNotMatchItalianSubstringInsideOtherWords() {
    // "digital" contiene la sequenza "ita" ma non è una lingua.
    assertFalse(StreamSource.isItalianSource("Movie.2024.1080p.Digital.mkv"))
    assertFalse(StreamSource.isItalianSource("Movie.2024.1080p.ENG.mkv"))
    assertFalse(StreamSource.isItalianSource("Movie.2024.1080p.MULTI.mkv"))
  }
}
