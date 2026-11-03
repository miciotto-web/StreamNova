package com.aistudio.streamnova.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Matrice di orientamento Fase 8.1: telefono, tablet, player e multi-window.
 * Verifica anche la misura stabile del display e la soglia 599/600 dp.
 */
class MobileOrientationPolicyTest {

  private fun target(
    formFactor: MobileFormFactor,
    isPlayerScreen: Boolean = false,
    playbackActive: Boolean = false,
    isMultiWindow: Boolean = false
  ): MobileOrientationTarget = mobileOrientationTarget(
    formFactor = formFactor,
    isPlayerScreen = isPlayerScreen,
    playbackActive = playbackActive,
    isMultiWindow = isMultiWindow
  )

  // --- Form factor e misura del display -------------------------------------

  @Test
  fun laSogliaTabletEEsattamenteSeicentoDp() {
    assertEquals(MobileFormFactor.PHONE, mobileFormFactor(599))
    assertEquals(MobileFormFactor.TABLET, mobileFormFactor(600))
    assertEquals(MobileFormFactor.TABLET, mobileFormFactor(601))
  }

  @Test
  fun unDisplaySottoLaSogliaRestaTelefono() {
    assertEquals(MobileFormFactor.PHONE, mobileFormFactor(0))
    assertEquals(MobileFormFactor.PHONE, mobileFormFactor(448))
  }

  @Test
  fun ilLatoCortoDelDisplayEIndipendenteDallOrientamento() {
    assertEquals(800, smallestDisplayWidthDp(widthPx = 2560, heightPx = 1600, densityDpi = 320))
    assertEquals(800, smallestDisplayWidthDp(widthPx = 1600, heightPx = 2560, densityDpi = 320))
  }

  @Test
  fun laMisuraDelDisplayUsaLaDensitaCorretta() {
    // Telefono emulatore: 1344x2992 px a 480 dpi -> 448 dp.
    assertEquals(448, smallestDisplayWidthDp(widthPx = 1344, heightPx = 2992, densityDpi = 480))
    // Pixel Tablet: 2560x1600 px a 320 dpi -> 800 dp.
    assertEquals(800, smallestDisplayWidthDp(widthPx = 2560, heightPx = 1600, densityDpi = 320))
  }

  @Test
  fun unaMisuraNonValidaRestituisceNull() {
    assertNull(smallestDisplayWidthDp(widthPx = 0, heightPx = 1600, densityDpi = 320))
    assertNull(smallestDisplayWidthDp(widthPx = 2560, heightPx = -1, densityDpi = 320))
    assertNull(smallestDisplayWidthDp(widthPx = 2560, heightPx = 1600, densityDpi = 0))
  }

  @Test
  fun senzaMisuraDelDisplaySiUsaIlFallbackDellaConfigurazione() {
    assertEquals(MobileFormFactor.TABLET, resolveMobileFormFactor(null, 800))
    assertEquals(MobileFormFactor.PHONE, resolveMobileFormFactor(null, 448))
  }

  @Test
  fun laMisuraDelDisplayHaLaPrecedenzaSulFallback() {
    assertEquals(MobileFormFactor.PHONE, resolveMobileFormFactor(599, 800))
    assertEquals(MobileFormFactor.TABLET, resolveMobileFormFactor(800, 448))
  }

  @Test
  fun laLarghezzaDellaFinestraNonIdentificaIlTablet() {
    // Finestra letterbox 600x800 px @320dpi = 300 dp, mentre il display dello
    // stesso tablet misura 800 dp: usare la finestra lo classificherebbe telefono.
    val windowWidthDp = smallestDisplayWidthDp(widthPx = 600, heightPx = 800, densityDpi = 320)
    val displayWidthDp = smallestDisplayWidthDp(widthPx = 2560, heightPx = 1600, densityDpi = 320)

    assertEquals(MobileFormFactor.PHONE, mobileFormFactor(windowWidthDp!!))
    assertEquals(MobileFormFactor.TABLET, mobileFormFactor(displayWidthDp!!))
  }

  // --- Telefono -------------------------------------------------------------

  @Test
  fun ilTelefonoInNavigazioneRestaPortrait() {
    assertEquals(
      MobileOrientationTarget.PORTRAIT,
      target(MobileFormFactor.PHONE, isPlayerScreen = false)
    )
  }

  @Test
  fun ilTelefonoDuranteLaRiproduzioneVaInLandscape() {
    assertEquals(
      MobileOrientationTarget.SENSOR_LANDSCAPE,
      target(MobileFormFactor.PHONE, isPlayerScreen = true, playbackActive = true)
    )
  }

  @Test
  fun ilTelefonoInLoadingErroreOSceltaSorgenteRestaPortrait() {
    assertEquals(
      MobileOrientationTarget.PORTRAIT,
      target(MobileFormFactor.PHONE, isPlayerScreen = true, playbackActive = false)
    )
  }

  // --- Tablet ---------------------------------------------------------------

  @Test
  fun ilTabletInNavigazioneRestaLandscape() {
    assertEquals(
      MobileOrientationTarget.SENSOR_LANDSCAPE,
      target(MobileFormFactor.TABLET, isPlayerScreen = false)
    )
  }

  @Test
  fun ilTabletDuranteLaRiproduzioneRestaLandscape() {
    assertEquals(
      MobileOrientationTarget.SENSOR_LANDSCAPE,
      target(MobileFormFactor.TABLET, isPlayerScreen = true, playbackActive = true)
    )
  }

  @Test
  fun ilTabletNegliStatiIntermediDelPlayerRestaLandscape() {
    assertEquals(
      MobileOrientationTarget.SENSOR_LANDSCAPE,
      target(MobileFormFactor.TABLET, isPlayerScreen = true, playbackActive = false)
    )
  }

  // --- Multi-window ---------------------------------------------------------

  @Test
  fun inMultiWindowNessunaForzaturaSuNessunaCombinazione() {
    for (formFactor in MobileFormFactor.entries) {
      for (isPlayerScreen in listOf(false, true)) {
        for (playbackActive in listOf(false, true)) {
          assertEquals(
            "formFactor=$formFactor player=$isPlayerScreen active=$playbackActive",
            MobileOrientationTarget.UNSPECIFIED,
            target(
              formFactor = formFactor,
              isPlayerScreen = isPlayerScreen,
              playbackActive = playbackActive,
              isMultiWindow = true
            )
          )
        }
      }
    }
  }

  @Test
  fun laPolicyFuoriDalMultiWindowRispettoAllaMatrice() {
    assertEquals(
      MobileOrientationTarget.PORTRAIT,
      target(MobileFormFactor.PHONE, isPlayerScreen = false, isMultiWindow = false)
    )
    assertEquals(
      MobileOrientationTarget.SENSOR_LANDSCAPE,
      target(MobileFormFactor.TABLET, isPlayerScreen = false, isMultiWindow = false)
    )
  }
}
