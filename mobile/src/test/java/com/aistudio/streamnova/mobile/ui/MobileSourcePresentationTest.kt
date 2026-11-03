package com.aistudio.streamnova.mobile.ui

import com.example.data.streaming.CacheState
import com.example.data.streaming.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Test JVM dei dati mostrati per ogni sorgente nel selettore.
 *
 * Regola verificata: si espone SOLO ciò che il modello [StreamSource]
 * dichiara. Un campo assente resta assente (nessun metadato inventato).
 */
class MobileSourcePresentationTest {

  private val fullSource = StreamSource(
    streamUrl = "https://example.test/film.mkv",
    quality = "1080p",
    serverName = "TorBox",
    declaredQuality = "1080p",
    addonName = "Torrentio",
    releaseTitle = "Interstellar.2014.1080p.BluRay.ITA.mkv",
    details = "2.18 GB | 474 seeders",
    codec = "HEVC",
    isCached = true,
    cacheState = CacheState.Cached
  )

  private val bareSource = StreamSource(
    streamUrl = "https://example.test/other.mkv",
    quality = "Auto",
    serverName = "Server Sconosciuto"
  )

  @Test
  fun ilProviderMostratoEQuelloDichiaratoDallAddon() {
    assertEquals("Torrentio", MobileSourcePresentation.providerLabel(fullSource))
  }

  @Test
  fun senzaAddonIlProviderEIlServer() {
    assertEquals("Server Sconosciuto", MobileSourcePresentation.providerLabel(bareSource))
  }

  @Test
  fun laRisoluzioneMostrataVieneDalModello() {
    assertEquals("1080p", MobileSourcePresentation.resolutionLabel(fullSource))
    // Qualità "Auto": nessuna risoluzione dichiarata, quindi nessun badge.
    assertNull(MobileSourcePresentation.resolutionLabel(bareSource))
  }

  @Test
  fun laQualitaMostrataEQuellaDichiarata() {
    assertEquals("1080p", MobileSourcePresentation.qualityLabel(fullSource))
    assertEquals("Auto", MobileSourcePresentation.qualityLabel(bareSource))
  }

  @Test
  fun ilNomeDellaReleaseVieneMostrato() {
    assertEquals(
      "Interstellar.2014.1080p.BluRay.ITA.mkv",
      MobileSourcePresentation.releaseLabel(fullSource)
    )
    assertNull(MobileSourcePresentation.releaseLabel(bareSource))
  }

  @Test
  fun leLingueAudioVengonoRiconosciuteDallaRelease() {
    assertEquals("Italiano", MobileSourcePresentation.languageLabel(fullSource))
    assertNull(MobileSourcePresentation.languageLabel(bareSource))
  }

  @Test
  fun laDimensioneVieneRicavataDaiDettagliDichiarati() {
    assertEquals("2.18 GB", MobileSourcePresentation.sizeLabel(fullSource))
    assertNull(MobileSourcePresentation.sizeLabel(bareSource))
  }

  @Test
  fun iDettagliResiduiNonRipetonoLaDimensione() {
    assertEquals(
      "474 seeders",
      MobileSourcePresentation.detailsLabel(fullSource)
    )
    assertNull(MobileSourcePresentation.detailsLabel(bareSource))
  }

  @Test
  fun laDimensioneVieneFormattataComeSullaTv() {
    assertEquals("512 B", MobileSourcePresentation.formatBytes(512L))
    assertEquals("1.00 KB", MobileSourcePresentation.formatBytes(1024L))
    assertEquals("1.50 MB", MobileSourcePresentation.formatBytes(1024L * 1536L))
    assertNull(MobileSourcePresentation.formatBytes(0L))
  }
}
