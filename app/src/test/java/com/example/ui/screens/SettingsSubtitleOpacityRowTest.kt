package com.example.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Riga "Opacità sfondo" nella sezione Sottotitoli delle Impostazioni generali.
 *
 * La stessa preferenza è modificabile dal menu Sottotitoli del player: qui si verifica
 * che la voce esista anche nelle Impostazioni, mostri il valore corrente e apra il
 * selettore con i quattro livelli, ed è usabile col solo D-pad (requisito Android TV).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "it-w1280dp-h720dp-tvdpi")
class SettingsSubtitleOpacityRowTest {

  @get:Rule
  val rule = createComposeRule()

  /** La schermata è una LazyColumn: la sezione va portata in vista prima di verificarla. */
  private fun scrollToOpacityRow() {
    rule.onNode(hasScrollToNodeAction())
      .performScrollToNode(hasText(OPACITY_LABEL))
    rule.waitForIdle()
  }

  private fun opacityRow() = rule.onNode(
    hasRequestFocusAction() and hasAnyDescendant(hasText(OPACITY_LABEL))
  )

  @Test
  fun laRigaEsisteConIlValoreCorrente() {
    rule.setContent { SettingsScreen(viewModel = null) }
    scrollToOpacityRow()

    rule.onNodeWithText("SOTTOTITOLI").assertExists()
    rule.onNodeWithText(OPACITY_LABEL).assertExists()
    // Default storico: nero semi-trasparente = 50%.
    rule.onNodeWithText("50%").assertExists()
  }

  @Test
  fun laRigaERaggiungibileColDpadEApreISelettore() {
    rule.setContent { SettingsScreen(viewModel = null) }
    scrollToOpacityRow()

    val row = opacityRow()
    rule.runOnIdle { row.requestFocus() }
    row.assertIsFocused()

    // OK del telecomando: si apre il selettore (stesso TrackSelectionDialog delle altre
    // impostazioni) con tutti e quattro i livelli richiesti.
    row.performKeyInput {
      keyDown(Key.DirectionCenter)
      keyUp(Key.DirectionCenter)
    }
    rule.waitForIdle()

    rule.onNodeWithText("100%").assertExists()
    rule.onNodeWithText("75%").assertExists()
    rule.onNodeWithText("25%").assertExists()
  }

  private companion object {
    const val OPACITY_LABEL = "Opacità sfondo"
  }
}
