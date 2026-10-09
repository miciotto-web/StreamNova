package com.example.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test dell'estrazione della risoluzione da uno stream Stremio
 * ([StremioStreamCandidate.quality]).
 *
 * Regole verificate:
 *  - la qualità dichiarata nel campo `name` ha priorità;
 *  - se `name` non dichiara nulla (addon localizzati come "Torrentio 🇮🇹"),
 *    si applica il regex di fallback sul nome file (`title`);
 *  - in assenza di qualsiasi token riconosciuto si resta su "Auto".
 */
class StremioStreamQualityTest {

  private fun candidate(name: String?, title: String?): StremioStreamCandidate =
    StremioStreamCandidate(
      addonName = "Torrentio 🇮🇹",
      baseUrl = "https://torrentio.strem.fun",
      item = StremioStreamItem(name = name, title = title)
    )

  @Test
  fun qualitaDichiarataNelNameHaPriorita() {
    assertEquals("1080p", candidate("Torrentio\n1080p", "release.mkv").quality)
    assertEquals("4K", candidate("Torrentio\n4K HDR", "release.mkv").quality)
  }

  @Test
  fun fallbackSuTitleQuandoNameLocalizzatoNonDichiaraQualita() {
    val c = candidate(
      name = "Torrentio 🇮🇹",
      title = "Spider-Man.No.Way.Home.2021.2160p.ITA-ENG.mkv"
    )
    assertEquals("4K", c.quality)
  }

  @Test
  fun fallbackSuTitleCopreTutteLeRisoluzioni() {
    assertEquals("1080p", candidate("Torrentio 🇮🇹", "Film.2020.1080p.mkv").quality)
    assertEquals("720p", candidate("Torrentio 🇮🇹", "Film.2020.720p.mkv").quality)
    assertEquals("480p", candidate("Torrentio 🇮🇹", "Film.2020.480p.mkv").quality)
    assertEquals("4K", candidate("Torrentio 🇮🇹", "Film.2020.4K.mkv").quality)
  }

  @Test
  fun nessunTokenRiconosciutoRestaAuto() {
    assertEquals("Auto", candidate("Torrentio 🇮🇹", "Film.senza.risoluzione.mkv").quality)
    assertEquals("Auto", candidate(null, null).quality)
  }

  @Test
  fun ilRegexNonScattaSuSempliciSottostringheNumericheNelTitle() {
    // "7200" non è un token di risoluzione: niente falsi positivi.
    assertEquals("Auto", candidate("Torrentio 🇮🇹", "Documentario.7200.mkv").quality)
  }
}
