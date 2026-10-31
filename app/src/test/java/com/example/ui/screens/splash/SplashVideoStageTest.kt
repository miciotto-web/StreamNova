package com.example.ui.screens.splash

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La splash video non deve mai diventare un blocco.
 *
 * Quando il player non è disponibile (costruzione fallita su un dispositivo
 * senza Media3 utilizzabile) la schermata deve chiudersi subito e lasciare la
 * Home già composta sotto di sé.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class SplashVideoStageTest {

  @get:Rule
  val rule = createComposeRule()

  @Test
  fun `senza player la splash si chiude subito`() {
    var finished = 0

    rule.setContent {
      SplashVideoStage(player = null, onFinished = { finished++ })
    }
    rule.waitForIdle()

    assertEquals(
      "Il fallback deve completare l'intro esattamente una volta",
      1,
      finished
    )
  }
}
