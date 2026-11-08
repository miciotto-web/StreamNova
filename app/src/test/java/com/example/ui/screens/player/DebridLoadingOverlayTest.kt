package com.example.ui.screens.player

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifica la resa della schermata di caricamento Debrid:
 *  - logo originale e dicitura localizzata quando il logo esiste;
 *  - fallback elegante sul titolo ORIGINALE quando il logo manca o non è caricabile;
 *  - nessun nodo focalizzabile o cliccabile: l'animazione non interferisce con il D-pad
 *    e non intercetta i tasti destinati al player.
 *
 * Le animazioni sono disattivate (`animationsEnabled = false`) per non tenere impegnato
 * il clock di Compose con transizioni infinite: la verifica riguarda struttura e
 * contenuti, non il campionamento dell'animazione.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "it-w1280dp-h720dp-tvdpi")
class DebridLoadingOverlayTest {

  @get:Rule
  val rule = createComposeRule()

  private fun artwork(
    backgroundUrl: String? = null,
    logoUrl: String? = null,
    fallbackTitle: String = "Tulsa King"
  ) = DebridLoadingArtwork(
    backgroundUrl = backgroundUrl,
    logoUrl = logoUrl,
    fallbackTitle = fallbackTitle
  )

  @Test
  fun mostraLogoPosterEDicituraDiCaricamento() {
    rule.setContent {
      DebridLoadingOverlay(
        // File inesistenti: Coil fallisce subito, senza rete, e la scena resta viva.
        backgroundUrl = "file:///nonexistent/debrid_backdrop.jpg",
        logoUrl = "file:///nonexistent/debrid_logo.png",
        fallbackTitle = "Titolo Di Prova",
        statusText = "Caricamento flusso video…",
        animationsEnabled = false
      )
    }
    rule.waitForIdle()

    rule.onNodeWithTag(DebridLoadingTestTags.ROOT).assertExists()
    rule.onNodeWithTag(DebridLoadingTestTags.BACKGROUND).assertExists()
    rule.onNodeWithTag(DebridLoadingTestTags.LOGO).assertExists()
    rule.onNodeWithText("Caricamento flusso video…").assertExists()
  }

  @Test
  fun nessunNodoFocalizzabileOCliccabilePerIlDpad() {
    rule.setContent {
      DebridLoadingOverlay(
        backgroundUrl = "file:///nonexistent/debrid_backdrop.jpg",
        logoUrl = "file:///nonexistent/debrid_logo.png",
        fallbackTitle = "Titolo Di Prova",
        statusText = "Caricamento flusso video…",
        animationsEnabled = false
      )
    }
    rule.waitForIdle()

    rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    rule.onAllNodes(hasRequestFocusAction()).assertCountEquals(0)
  }

  @Test
  fun senzaLogoMostraIlTitoloOriginaleSenzaSchermataVuota() {
    rule.setContent {
      DebridLoadingOverlay(
        backgroundUrl = null,
        logoUrl = null,
        fallbackTitle = "Original Title",
        statusText = "Caricamento flusso video…",
        animationsEnabled = false
      )
    }
    rule.waitForIdle()

    rule.onNodeWithTag(DebridLoadingTestTags.TITLE_FALLBACK).assertExists()
    rule.onNodeWithText("Original Title").assertExists()
    rule.onNodeWithTag(DebridLoadingTestTags.MESSAGE).assertExists()
    rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    rule.onAllNodes(hasRequestFocusAction()).assertCountEquals(0)
    // Nessuna immagine: lo sfondo pieno della scena resta comunque presente.
    assertTrue(
      rule.onAllNodes(hasTestTag(DebridLoadingTestTags.BACKGROUND))
        .fetchSemanticsNodes()
        .isEmpty()
    )
  }
}
