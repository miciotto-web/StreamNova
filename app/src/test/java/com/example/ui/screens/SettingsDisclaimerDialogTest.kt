package com.example.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.ApiKeyCipher
import com.example.data.prefs.ApiKeyCipherFormat
import com.example.data.prefs.AppSettingsRepository
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Voce «Disclaimer e responsabilità» (Impostazioni → Informazioni legali) e relativo
 * pannello informativo.
 *
 * Regressioni coperte:
 * 1. la voce apre il pannello, che porta il testo ufficiale per intero (nessun
 *    paragrafo assente);
 * 2. il focus iniziale parte dal testo, e lo scorrimento D-pad (GIÙ) esaurisce tutto
 *    il documento e rilascia il tasto al bordo: «Chiudi» diventa raggiungibile;
 * 3. SU dal bordo superiore resta sulla lettura (nessuna fuga sull'icona di chiusura);
 * 4. «Chiudi» e BACK chiudono il pannello e riportano alle Impostazioni con il focus
 *    sulla riga che l'ha aperta.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [34],
  // Lingua esplicita (it): le asserzioni verificano il testo ufficiale italiano.
  qualifiers = "it-w1280dp-h720dp-tvdpi"
)
class SettingsDisclaimerDialogTest {

  @get:Rule
  val rule = createComposeRule()

  /** Cifratura finta: il Keystore Android non esiste su Robolectric. */
  private class TrivialCipher : ApiKeyCipher {
    override fun encrypt(plain: String): String = ApiKeyCipherFormat.MARKER + plain.reversed()
    override fun decrypt(stored: String): String? = if (ApiKeyCipherFormat.isEncrypted(stored)) {
      ApiKeyCipherFormat.payload(stored).reversed()
    } else {
      null
    }
  }

  @Before
  fun setUp() {
    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(ApplicationProvider.getApplicationContext(), TrivialCipher())
  }

  @After
  fun tearDown() {
    AppSettingsRepository.resetForTesting()
  }

  private fun renderSettings() {
    rule.setContent { SettingsScreen(viewModel = null) }
    rule.waitForIdle()
  }

  /** La schermata è una LazyColumn: la sezione va portata in vista prima di toccarla. */
  private fun scrollToDisclaimerRow() {
    rule.onNode(hasScrollToNodeAction())
      .performScrollToNode(hasText(ENTRY_LABEL))
    rule.waitForIdle()
  }

  /**
   * Il nodo a fuoco DENTRO il pannello: a dialog aperto anche la riga delle Impostazioni
   * sotto conserva lo stato «focused» della sua finestra, quindi il matcher va ristretto
   * ai discendenti della finestra del pannello.
   */
  private fun panelFocusedNode() =
    rule.onNode(
      isFocused() and hasAnyAncestor(hasTestTag(SETTINGS_DISCLAIMER_DIALOG_TAG)),
      useUnmergedTree = true
    )

  /** Il click televisivo: focus sulla riga, poi conferma con il tasto centrale. */
  private fun openDisclaimerPanel() {
    renderSettings()
    scrollToDisclaimerRow()
    focusDisclaimerRow()
    confirmFocusedRow()
    rule.onNodeWithTag(SETTINGS_DISCLAIMER_DIALOG_TAG).assertExists()
  }

  /** OK sulla riga a fuoco (finestra Impostazioni): apre il pannello. */
  private fun confirmFocusedRow() {
    rule.onNode(
      isFocused() and hasAnyDescendant(hasText(ENTRY_LABEL)),
      useUnmergedTree = true
    ).performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()
  }

  /**
   * BACK di sistema: con il pannello aperto il tasto attraversa il dispatcher DEL DIALOG
   * (come sul dispositivo, dove la finestra delle Impostazioni non lo vede). Stesso
   * approccio di ContinueWatchingContextMenuFocusTest.
   */
  private fun pressSystemBack() {
    val composeRule = rule as? AndroidComposeTestRule<*, *> ?: return
    rule.runOnUiThread {
      val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
      if (dialog is ComponentDialog) {
        dialog.onBackPressedDispatcher.onBackPressed()
      } else {
        (composeRule.activity as? ComponentActivity)
          ?.onBackPressedDispatcher
          ?.onBackPressed()
      }
    }
    rule.waitForIdle()
  }

  private fun focusDisclaimerRow() {
    val nodes = focusableNodes()
    val index = nodes.indexOfFirst { node -> allTextsOf(node).any { it == ENTRY_LABEL } }
    check(index >= 0) { "Riga '$ENTRY_LABEL' non trovata tra i nodi focusabili" }
    rule.onAllNodes(
      SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus),
      useUnmergedTree = true
    )[index].requestFocus()
    rule.waitForIdle()
  }

  private fun pressFocusedKey(key: Key) {
    panelFocusedNode().performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  // ── Helper di focus (stessi di AddonsScreenInstallFocusTest) ─────────────────

  private fun focusableNodes(): List<SemanticsNode> =
    rule.onAllNodes(
      SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus),
      useUnmergedTree = true
    ).fetchSemanticsNodes()

  private fun allTextsOf(node: SemanticsNode): List<String> {
    val own = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
    return own + node.children.flatMap { allTextsOf(it) }
  }

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true)
      .fetchSemanticsNodes()
      .flatMap { allTextsOf(it) }

  // ── Regressioni ───────────────────────────────────────────────────────────────

  @Test
  fun laVoceNelleInformazioniLegaliantiApreIlPannelloConIlTestoUfficiale() {
    renderSettings()
    scrollToDisclaimerRow()

    rule.onNodeWithText("INFORMAZIONI LEGALI").assertExists()
    focusDisclaimerRow()
    confirmFocusedRow()
    rule.waitForIdle()

    rule.onNodeWithTag(SETTINGS_DISCLAIMER_DIALOG_TAG).assertExists()
    // Titolo del documento (in maiuscolo, come l'intestazione ufficiale).
    rule.onNodeWithText(PANEL_TITLE).assertExists()
    // Il testo è composto per intero (verticalScroll non è lazy): ogni paragrafo
    // esiste nel grafo anche se visivamente fuori viewport.
    PARAGRAPH_SNIPPETS.forEach { snippet ->
      rule.onNode(hasText(snippet, substring = true)).assertExists()
    }
    // Azione dedicata al telecomando presente nel pannello.
    rule.onNodeWithText("Chiudi").assertExists()
  }

  @Test
  fun ilFocusInizialeParteDalTesto() {
    openDisclaimerPanel()
    rule.onNodeWithTag(SETTINGS_DISCLAIMER_BODY_TAG, useUnmergedTree = true).assertIsFocused()
  }

  @Test
  fun suDalBordoSuperioreRestaSullaLettura() {
    openDisclaimerPanel()
    rule.onNodeWithTag(SETTINGS_DISCLAIMER_BODY_TAG, useUnmergedTree = true).assertIsFocused()
    // In cima al documento SU non ha nulla da raggiungere: il focus resta sul testo
    // invece di fuggire sull'intestazione.
    pressFocusedKey(Key.DirectionUp)
    rule.onNodeWithTag(SETTINGS_DISCLAIMER_BODY_TAG, useUnmergedTree = true).assertIsFocused()
  }

  @Test
  fun loScorrimentoEsaurisceIlDocumentoEDelegaAChiudi() {
    openDisclaimerPanel()
    rule.onNodeWithTag(SETTINGS_DISCLAIMER_BODY_TAG, useUnmergedTree = true).assertIsFocused()

    // Più pressioni di quante servano a coprire il documento: le pressioni oltre il
    // fondo sono rilasciate al grafo del pannello e portano il focus su «Chiudi».
    repeat(DPAD_SCROLL_PRESSES) { pressFocusedKey(Key.DirectionDown) }

    rule.onNodeWithText("Chiudi").assertExists()
    assertTrue(
      "Al fondo del documento il focus deve passare a «Chiudi», trovato ${focusedTexts()}",
      focusedTexts().any { it == "Chiudi" }
    )
  }

  @Test
  fun ilPulsanteChiudiRiportaAlleImpostazioni() {
    openDisclaimerPanel()
    // Percorso reale del telecomando: GIÙ fino in fondo al documento, poi OK su «Chiudi».
    repeat(DPAD_SCROLL_PRESSES) { pressFocusedKey(Key.DirectionDown) }
    assertTrue(
      "Al fondo del documento il focus deve passare a «Chiudi», trovato ${focusedTexts()}",
      focusedTexts().any { it == "Chiudi" }
    )
    pressFocusedKey(Key.DirectionCenter)
    rule.waitForIdle()

    rule.onNodeWithTag(SETTINGS_DISCLAIMER_DIALOG_TAG).assertDoesNotExist()
    scrollToDisclaimerRow()
    assertTrue(
      "Dopo «Chiudi» il focus deve tornare sulla riga del disclaimer, trovato ${focusedTexts()}",
      focusedTexts().any { it == ENTRY_LABEL }
    )
  }

  @Test
  fun ilTastoBACKChiudeIlPannelloERiportaAlleImpostazioni() {
    openDisclaimerPanel()
    pressSystemBack()

    rule.onNodeWithTag(SETTINGS_DISCLAIMER_DIALOG_TAG).assertDoesNotExist()
    scrollToDisclaimerRow()
    rule.onNodeWithText(ENTRY_LABEL).assertExists()
    assertTrue(
      "Dopo BACK il focus deve tornare sulla riga del disclaimer, trovato ${focusedTexts()}",
      focusedTexts().any { it == ENTRY_LABEL }
    )
  }

  private companion object {
    const val ENTRY_LABEL = "Disclaimer e responsabilità"
    const val PANEL_TITLE = "DISCLAIMER E LIMITAZIONE DI RESPONSABILITÀ"

    /** Pressioni GIÙ abbondanti: coprono il documento per intero in ogni locale. */
    const val DPAD_SCROLL_PRESSES = 16

    /** Una frase chiave per paragrafo (p1…p7) del testo ufficiale italiano. */
    val PARAGRAPH_SNIPPETS = listOf(
      "contenitore e interfaccia tecnologica",
      "non ospita, non possiede, non produce, non distribuisce e non controlla",
      "pirateria",
      "unico responsabile delle sorgenti",
      "controllo editoriale sui contenuti",
      "proprietà intellettuale",
      "strumento tecnologico"
    )
  }
}
