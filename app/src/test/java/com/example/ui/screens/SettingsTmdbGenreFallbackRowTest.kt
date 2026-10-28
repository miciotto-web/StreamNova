package com.example.ui.screens

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.lifecycle.viewModelScope
import com.example.data.prefs.AppSettingsRepository
import androidx.test.core.app.ApplicationProvider
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
 * Switch "Usa TMDB come fallback per i generi" nella schermata Impostazioni.
 *
 * Verifica i requisiti Android TV: la voce esiste nella sua sezione, è focalizzabile con
 * il D-pad (focus visibile = nodo focalizzato) e attivabile con il tasto OK del
 * telecomando, esattamente come le altre righe di impostazione.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [34],
  // Lingua esplicita (it): le asserzioni verificano i testi italiani, indipendentemente dal
  // locale della macchina che esegue i test.
  qualifiers = "it-w1280dp-h720dp-tvdpi"
)
class SettingsTmdbGenreFallbackRowTest {

  @get:Rule
  val rule = createComposeRule()

  @Before
  fun setUp() {
    AppSettingsRepository.init(ApplicationProvider.getApplicationContext())
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
  }

  @After
  fun tearDown() {
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
  }

  /** Porta in vista la riga dello switch (LazyColumn: le voci lontane non sono composte). */
  private fun scrollToSwitch() {
    rule.onNode(androidx.compose.ui.test.hasScrollToNodeAction())
      .performScrollToNode(hasText("Usa TMDB come fallback per i generi"))
    rule.waitForIdle()
  }

  @Test
  fun loSwitchSiTrovaNellaSezioneRicercaConLaDescrizione() {
    rule.setContent { SettingsScreen(viewModel = null) }
    // La schermata è una LazyColumn: la sezione va portata in vista prima di verificarla.
    scrollToSwitch()

    // Le intestazioni di sezione sono rese in maiuscolo.
    rule.onNodeWithText("RICERCA E CATALOGHI").assertExists()
    rule.onNodeWithText("Usa TMDB come fallback per i generi").assertExists()
    rule.onNode(
      hasText(
        "Trova film e serie TV tramite TMDB quando nessuno dei cataloghi configurati " +
          "supporta il genere selezionato.",
        substring = true
      )
    ).assertExists()
  }

  @Test
  fun loSwitchERaggiungibileConIlDpadEAttivabileConOk() {
    val viewModel = com.example.ui.viewmodel.StreamNovaViewModel()
    try {
      rule.setContent { SettingsScreen(viewModel = viewModel) }
      scrollToSwitch()

      // La riga (non il solo testo) è il nodo focalizzabile del D-pad, come per tutte le
      // altre voci delle Impostazioni.
      val row = rule.onNode(hasRequestFocusAction() and hasAnyDescendant(hasText(SWITCH_LABEL)))
      rule.runOnIdle { row.requestFocus() }
      row.assertIsFocused()

      // Navigazione D-pad: il focus resta su una riga focalizzabile della schermata.
      row.performKeyInput { pressKey(Key.DirectionDown) }
      rule.onNode(isFocused()).assertExists()

      // Tasto OK del telecomando (DPAD_CENTER) sulla riga a fuoco: stessa azione
      // dell'onClick. Il contenitore focalizzabile gestisce l'OK tramite eventi di
      // tasto (non semantica di click), quindi il test invia i tasti del D-pad.
      switchRow().requestFocus()
      rule.waitForIdle()
      pressOk()
      rule.runOnIdle { assertFalse(AppSettingsRepository.tmdbGenreFallbackEnabled.value) }
      rule.onNode(
        hasRequestFocusAction() and hasAnyDescendant(hasText(SWITCH_LABEL)) and hasAnyDescendant(hasText("OFF"))
      ).assertExists()

      switchRow().requestFocus()
      rule.waitForIdle()
      pressOk()
      rule.runOnIdle { assertTrue(AppSettingsRepository.tmdbGenreFallbackEnabled.value) }
    } finally {
      viewModel.viewModelScope.cancel()
    }
  }

  /** Riga focalizzabile dello switch (il testo è un figlio, come per le altre voci). */
  private fun switchRow() = rule.onNode(
    hasRequestFocusAction() and hasAnyDescendant(hasText(SWITCH_LABEL))
  )

  /** Invia il tasto OK (DPAD_CENTER) alla riga a fuoco, come il telecomando. */
  private fun pressOk() {
    switchRow().performKeyInput {
      keyDown(Key.DirectionCenter)
      keyUp(Key.DirectionCenter)
    }
    rule.waitForIdle()
  }

  private companion object {
    const val SWITCH_LABEL = "Usa TMDB come fallback per i generi"
  }
}
