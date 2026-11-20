package com.example.data.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Soglie centralizzate della classificazione risoluzioni (FASE 4): il 4K è
 * dichiarato da [StreamResolution.MIN_WIDTH_4K]/[StreamResolution.MIN_HEIGHT_4K]
 * e il 1440p non è mai etichettato 4K — né dalle playlist né dall'HUD.
 */
class StreamResolutionThresholdTest {

  @Test
  fun soglie4kCoerentiConDimensioniReal() {
    assertEquals(2160, StreamResolution.heightOfResolution(3840, 2160))
    assertEquals(2160, StreamResolution.heightOfResolution(4096, 2160))
    // Il 4K widescreen (crop) conserva la larghezza 4K.
    assertEquals(2160, StreamResolution.heightOfResolution(3840, 1600))
    // Sotto le soglie 4K non si entra nella fascia UHD.
    assertEquals(1440, StreamResolution.heightOfResolution(3799, 1440))
    assertEquals(1440, StreamResolution.heightOfResolution(2560, 2099))
  }

  @Test
  fun etichettaMisurataDeiVideoReal() {
    // L'HUD usa labelForMeasured: un solo punto per le soglie.
    assertEquals("4K", StreamResolution.labelForMeasured(3840, 2160))
    assertEquals("1440p", StreamResolution.labelForMeasured(2560, 1440))
    assertEquals("1440p", StreamResolution.labelForMeasured(3440, 1440))
    assertEquals("1080p", StreamResolution.labelForMeasured(1920, 1080))
    assertEquals("1080p", StreamResolution.labelForMeasured(1920, 800))
    assertEquals("720p", StreamResolution.labelForMeasured(1280, 720))
    assertEquals("SD", StreamResolution.labelForMeasured(854, 480))
    assertEquals("Auto", StreamResolution.labelForMeasured(0, 0))
  }

  @Test
  fun il1440NonEconfezionatoCome4kDallHUD() {
    // Caso del bug: altezza 1440 con larghezza non nota NON è 4K.
    assertEquals("1440p", StreamResolution.labelForMeasured(0, 1440))
    assertEquals("1440p", StreamResolution.labelForMeasured(2500, 1440))
    // Altezza 2099 (proprio sotto la soglia) non è 4K.
    assertEquals("1440p", StreamResolution.labelForMeasured(0, 2099))
    // La soglia 2100 segna l'inizio della fascia 4K in altezza.
    assertEquals("4K", StreamResolution.labelForMeasured(0, 2100))
  }
}
