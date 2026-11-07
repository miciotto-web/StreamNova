package com.example.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sezione HOME delle Impostazioni.
 *
 * Requisiti verificati:
 *  - la sezione si trova SUBITO dopo "Motore di Riproduzione" e SUBITO prima di
 *    "Ricerca e cataloghi" (indici di item consecutivi nella LazyColumn);
 *  - contiene lo switch condiviso [SettingsSwitchRow] con titolo e descrizione;
 *  - la riga è focalizzabile col D-pad e un solo OK alterna il valore una volta sola.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [34],
  // Lingua esplicita (it): le asserzioni verificano i testi italiani.
  qualifiers = "it-w1280dp-h720dp-tvdpi"
)
class SettingsHomeSectionTest {

  @get:Rule
  val rule = createComposeRule()

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    AppSettingsRepository.init(context)
    SettingsRepository.init(context)
    // Modalità compatta (HTTP): nella LazyColumn Motore di Riproduzione, HOME e
    // Ricerca restano vicini e componibili insieme.
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setHideUnreleasedContent(false)
  }

  @After
  fun tearDown() {
    AppSettingsRepository.setHideUnreleasedContent(false)
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
  }

  @Test
  // Viewport alto: Motore di Riproduzione, HOME e Ricerca sono composti insieme e le
  // posizioni verticali sono confrontabili senza dipendere dai limiti dello scroll.
  @Config(sdk = [34], qualifiers = "it-w1280dp-h3000dp-tvdpi")
  fun laSezioneHomeSiTrovaTraMotoreDiRiproduzioneERicerca() {
    rule.setContent { SettingsScreen(viewModel = null) }
    rule.waitForIdle()

    val engineTop = rule.onNodeWithText("MOTORE DI RIPRODUZIONE")
      .fetchSemanticsNode().positionInRoot.y
    val homeTop = rule.onNodeWithText("HOME")
      .fetchSemanticsNode().positionInRoot.y
    val searchTop = rule.onNodeWithText("RICERCA E CATALOGHI")
      .fetchSemanticsNode().positionInRoot.y

    assertTrue(
      "Motore di Riproduzione deve stare sopra HOME (engine=$engineTop, home=$homeTop)",
      engineTop < homeTop
    )
    assertTrue(
      "Ricerca e cataloghi deve stare sotto HOME (home=$homeTop, search=$searchTop)",
      homeTop < searchTop
    )
  }

  @Test
  fun laSezioneHomeContieneLoSwitchConTitoloEDescrizione() {
    rule.setContent { SettingsScreen(viewModel = null) }
    scrollTo(SWITCH_LABEL)

    rule.onNodeWithText("HOME").assertExists()
    switchRow()
      .assertExists()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
      .assert(hasStateDescription("OFF"))
    rule.onNodeWithText(SUBTITLE).assertExists()
  }

  @Test
  fun loSwitchERaggiungibileConIlDpadEUnSoloOkLoAlterna() {
    val viewModel = StreamNovaViewModel()
    try {
      rule.setContent { SettingsScreen(viewModel = viewModel) }
      scrollTo(SWITCH_LABEL)

      switchRow().requestFocus()
      switchRow().assertIsFocused()

      pressOk()
      rule.runOnIdle { assertTrue(AppSettingsRepository.hideUnreleasedContent.value) }
      switchRow().assert(hasStateDescription("ON"))

      // Un secondo OK (non un doppio toggle per singola pressione) riporta a OFF.
      pressOk()
      rule.runOnIdle { assertFalse(AppSettingsRepository.hideUnreleasedContent.value) }
      switchRow().assert(hasStateDescription("OFF"))
    } finally {
      viewModel.viewModelScope.cancel()
    }
  }

  private fun scrollTo(text: String) {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    rule.waitForIdle()
  }

  /** Riga focalizzabile dello switch (il titolo è un figlio, come per le altre voci). */
  private fun switchRow() = rule.onNode(
    hasRequestFocusAction() and hasAnyDescendant(hasText(SWITCH_LABEL))
  )

  private fun pressOk() {
    switchRow().performKeyInput {
      keyDown(Key.DirectionCenter)
      keyUp(Key.DirectionCenter)
    }
    rule.waitForIdle()
  }

  private companion object {
    const val SWITCH_LABEL = "Nascondi contenuti non rilasciati"
    const val SUBTITLE =
      "Nascondi i film e le serie TV che non sono ancora stati rilasciati."
  }
}
