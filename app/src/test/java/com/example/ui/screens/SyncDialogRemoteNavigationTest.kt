package com.example.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.example.data.sync.SyncCategory
import com.example.data.sync.SyncDirection
import com.example.data.sync.SyncIncomingRequest
import com.example.data.sync.SyncPeerDevice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Navigabilità remote-safe dei dialog Sync (trasferimento e approvazione).
 *
 * Difetto corretto qui: i dialog erano overlay in-window SENZA richiesta di focus
 * iniziale — il focus restava sulla riga delle Impostazioni dietro lo scrim, il D-pad
 * navigava "nel vuoto" e il popup risultava bloccato sul telecomando. Ora sono un vero
 * [androidx.compose.ui.window.Dialog] (come il menu contestuale degli episodi) con:
 *  1. focus iniziale richiesto e VERIFICATO con retry (parte sul primo controllo);
 *  2. `exit = Cancel` sul contenitore: il D-pad non può uscire dalla card;
 *  3. BACK che chiude la finestra (rifiuta la richiesta di sincronizzazione).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [34],
  qualifiers = "it-w1280dp-h720dp-tvdpi"
)
class SyncDialogRemoteNavigationTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val request = SyncIncomingRequest(
    id = "req-1",
    peerDeviceName = "TV Soggiorno",
    direction = SyncDirection.PULL,
    categories = setOf(SyncCategory.SETTINGS, SyncCategory.LIBRARY),
    createdAt = 0L
  )

  private fun awaitDialogFocus() {
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.waitForIdle()
  }

  /** Tasto D-pad consegnato al nodo focalizzato (dentro la finestra del Dialog). */
  private fun pressDpad(key: Key) {
    composeRule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(key) }
    composeRule.waitForIdle()
  }

  /**
   * Il flag `Focused` sta sul Box focusabile ([TvFocusableBox]), non sul nodo Text
   * figlio: l'asserzione matcha il contenitore focalizzato che contiene l'etichetta.
   */
  private fun assertFocused(label: String) {
    composeRule.onNode(
      isFocused() and hasAnyDescendant(hasText(label)),
      useUnmergedTree = true
    ).assertExists()
  }

  @Test
  fun `approvazione - il focus parte su Trasferisci e il D-pad resta confinato nel dialog`() {
    composeRule.setContent {
      SyncApprovalDialog(request = request, onApprove = {}, onDeny = {})
    }
    awaitDialogFocus()
    assertFocused("Trasferisci")

    // DOWN è annullato (pulsanti unici controlli): il focus NON lascia il dialog.
    pressDpad(Key.DirectionDown)
    assertFocused("Trasferisci")
    // LEFT passa a «Rifiuta»; UP è annullato (cancelUp); RIGHT torna su «Trasferisci».
    pressDpad(Key.DirectionLeft)
    assertFocused("Rifiuta")
    pressDpad(Key.DirectionUp)
    assertFocused("Rifiuta")
    pressDpad(Key.DirectionRight)
    assertFocused("Trasferisci")
  }

  @Test
  fun `approvazione - BACK chiude la finestra e rifiuta la richiesta`() {
    var denied = false
    var approved = false
    composeRule.setContent {
      SyncApprovalDialog(request = request, onApprove = { approved = true }, onDeny = { denied = true })
    }
    awaitDialogFocus()
    val dialog = ShadowDialog.getLatestDialog()
    assertTrue(dialog != null && dialog!!.isShowing)
    composeRule.runOnUiThread {
      // Il Dialog di Compose è un ComponentDialog: il BACK attraversa il SUO dispatcher,
      // come sul dispositivo (la finestra dell'Activity non lo vede a popup aperto).
      (dialog as? androidx.activity.ComponentDialog)
        ?.onBackPressedDispatcher
        ?.onBackPressed()
    }
    composeRule.waitForIdle()
    assertTrue(denied)
    assertTrue(!approved)
  }

  @Test
  fun `trasferimento - il focus parte sulla prima direzione e scendendo raggiunge i pulsanti`() {
    composeRule.setContent {
      SyncTransferDialog(
        peer = SyncPeerDevice(displayName = "TV Soggiorno", host = "192.168.1.10", port = 8090, schemaVersion = 1),
        onDismiss = {}
      )
    }
    awaitDialogFocus()
    assertFocused("Importa dal dispositivo")

    // DOWN attraversa ogni riga del dialog senza mai uscire dal gruppo di focus.
    val percorso = listOf(
      "Invia la tua configurazione",
      "Impostazioni e chiavi API",
      "Addon Stremio",
      "Account SIMKL",
      "Preferiti e progressi"
    )
    percorso.forEach { label ->
      pressDpad(Key.DirectionDown)
      assertFocused(label)
    }

    // Ultimo DOWN: atterra su uno dei due pulsanti (ricerca geometrica), poi RIGHT
    // raggiunge sempre «Avvia» (da «Avvia» RIGHT è annullato e resta fermo).
    pressDpad(Key.DirectionDown)
    val onAnnulla = try {
      assertFocused("Annulla")
      true
    } catch (_: AssertionError) {
      false
    }
    if (onAnnulla) {
      pressDpad(Key.DirectionRight)
    }
    assertFocused("Avvia")
  }
}
