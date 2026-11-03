package com.aistudio.streamnova.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileTrailerOrientationTest {

  @Test
  fun telefonoSenzaTrailerRestaPortrait() {
    assertEquals(
      MobileOrientationTarget.PORTRAIT,
      mobileOrientationTarget(
        formFactor = MobileFormFactor.PHONE,
        isPlayerScreen = false,
        playbackActive = false,
        isMultiWindow = false
      )
    )
  }

  @Test
  fun telefonoConTrailerPuoRuotareInLandscape() {
    assertEquals(
      MobileOrientationTarget.SENSOR_LANDSCAPE,
      mobileTrailerOrientationTarget(
        formFactor = MobileFormFactor.PHONE,
        isMultiWindow = false
      )
    )
  }

  @Test
  fun allaChiusuraDelTrailerLaPolicyBaseRiportaIlPortrait() {
    // La chiusura richiama clearTrailer(): il controller rivaluta la policy base,
    // che per un telefono non-player è PORTRAIT.
    assertEquals(
      MobileOrientationTarget.PORTRAIT,
      mobileOrientationTarget(
        formFactor = MobileFormFactor.PHONE,
        isPlayerScreen = false,
        playbackActive = false,
        isMultiWindow = false
      )
    )
  }

  @Test
  fun tabletRestaLandscapeAnchePerIlTrailer() {
    assertEquals(
      MobileOrientationTarget.SENSOR_LANDSCAPE,
      mobileTrailerOrientationTarget(
        formFactor = MobileFormFactor.TABLET,
        isMultiWindow = false
      )
    )
  }

  @Test
  fun inMultiWindowIlTrailerNonImponeOrientamento() {
    assertEquals(
      MobileOrientationTarget.UNSPECIFIED,
      mobileTrailerOrientationTarget(
        formFactor = MobileFormFactor.PHONE,
        isMultiWindow = true
      )
    )
  }

  @Test
  fun laPolicyDelPlayerPrincipaleNonCambia() {
    // Fase 8.1 invariata: telefono player attivo → landscape.
    assertEquals(
      MobileOrientationTarget.SENSOR_LANDSCAPE,
      mobileOrientationTarget(
        formFactor = MobileFormFactor.PHONE,
        isPlayerScreen = true,
        playbackActive = true,
        isMultiWindow = false
      )
    )
    // Telefono player in loading/picker → portrait (non viene anticipato il landscape).
    assertEquals(
      MobileOrientationTarget.PORTRAIT,
      mobileOrientationTarget(
        formFactor = MobileFormFactor.PHONE,
        isPlayerScreen = true,
        playbackActive = false,
        isMultiWindow = false
      )
    )
  }
}
