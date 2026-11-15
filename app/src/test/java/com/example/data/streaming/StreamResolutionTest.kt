package com.example.data.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

/** Normalizzazione delle risoluzioni e ordinamento della gerarchia automatica. */
class StreamResolutionTest {

  @Test
  fun denominazioniEquivalenti() {
    assertEquals(2160, StreamResolution.heightOf("4K"))
    assertEquals(2160, StreamResolution.heightOf("UHD"))
    assertEquals(2160, StreamResolution.heightOf("2160p"))
    assertEquals(1440, StreamResolution.heightOf("QHD"))
    assertEquals(1440, StreamResolution.heightOf("1440p"))
    assertEquals(1080, StreamResolution.heightOf("Full HD"))
    assertEquals(1080, StreamResolution.heightOf("FHD"))
    assertEquals(1080, StreamResolution.heightOf("1080p"))
    assertEquals(720, StreamResolution.heightOf("HD"))
    assertEquals(720, StreamResolution.heightOf("720p"))
    assertEquals(480, StreamResolution.heightOf("480p"))
    assertEquals(480, StreamResolution.heightOf("SD"))
  }

  @Test
  fun risoluzioneSconosciutaNonVienePromossa() {
    assertEquals(StreamResolution.UNKNOWN, StreamResolution.heightOf("Auto"))
    assertEquals(StreamResolution.UNKNOWN, StreamResolution.heightOf(""))
    assertEquals(StreamResolution.UNKNOWN, StreamResolution.heightOf(null))
    assertEquals(StreamResolution.UNKNOWN, StreamResolution.heightOf("qualità non dichiarata"))
  }

  @Test
  fun altezzaConvenzionaleDelleVariantiWidescreen() {
    assertEquals(2160, StreamResolution.heightOfResolution(3840, 2160))
    assertEquals(1440, StreamResolution.heightOfResolution(2560, 1440))
    assertEquals(1080, StreamResolution.heightOfResolution(1920, 800))
    assertEquals(720, StreamResolution.heightOfResolution(1206, 720))
    assertEquals(480, StreamResolution.heightOfResolution(854, 480))
  }

  @Test
  fun laGerarchiaMetteAltadefinizionePrimaEGliSconosciutiInFondo() {
    val alta1080 = source("Alta 1080", "https://alta.test/1080.m3u8", "1080p", 1080, "Altadefinizione")
    val alta720 = source("Alta 720", "https://alta.test/720.m3u8", "720p", 720, "Altadefinizione")
    val altaUnknown = source("Alta Auto", "https://alta.test/auto.m3u8", "Auto", null, "Altadefinizione")
    val vix4k = source("Vix 4K", "https://vix.test/2160.m3u8", "4K", 2160, "VixSrcProvider")

    val sorted = AutoSourcePriority.sortForAutomaticSelection(
      listOf(vix4k, altaUnknown, alta720, alta1080)
    )

    assertEquals(
      listOf(alta1080, alta720, altaUnknown, vix4k),
      sorted
    )
  }

  @Test
  fun altezzaVerificataPrevaleSullaQualitaDichiarata() {
    val declared4kBut720 = StreamSource(
      streamUrl = "https://cdn.test/720.m3u8",
      quality = "4K",
      declaredQuality = "4K",
      verifiedHeight = 720,
      serverName = "Test"
    )
    assertEquals(720, StreamResolution.heightOf(declared4kBut720))
    assertEquals("720p", declared4kBut720.resolution)
  }

  private fun source(
    name: String,
    url: String,
    quality: String,
    height: Int?,
    provider: String
  ) = StreamSource(
    streamUrl = url,
    quality = quality,
    declaredQuality = quality,
    verifiedHeight = height,
    serverName = name,
    sourceProviderId = provider
  )
}
