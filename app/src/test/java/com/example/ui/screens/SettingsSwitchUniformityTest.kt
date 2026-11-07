package com.example.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Uniformità degli switch nelle Impostazioni.
 *
 * Tutte le opzioni ON/OFF devono usare lo stesso componente ([SettingsSwitchRow]): una
 * riga focalizzabile col D-pad, titolo (ed eventuale descrizione) a sinistra, switch a
 * destra e stato esposto alla semantica come "ON"/"OFF". Le voci che aprono un dialog o
 * un selettore restano righe normali e non devono diventare switch.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [34],
  // Lingua esplicita (it): le asserzioni verificano i testi italiani.
  qualifiers = "it-w1280dp-h720dp-tvdpi"
)
class SettingsSwitchUniformityTest {

  @get:Rule
  val rule = createComposeRule()

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    AppSettingsRepository.init(context)
    SettingsRepository.init(context)
    // Il toggle TorBox è visibile solo nella modalità Debrid: lo si abilita per poter
    // verificare anche quella riga.
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
  }

  @After
  fun tearDown() {
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
  }

  @Test
  fun tutteLeOpzioniOnOffUsanoLoStessoSwitch() {
    rule.setContent { SettingsScreen(viewModel = null) }

    BOOLEAN_TITLES.forEach { title ->
      scrollTo(title)
      switchRow(title)
        .assertExists()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        .assert(hasStateDescription("ON") or hasStateDescription("OFF"))
    }
  }

  @Test
  fun leVociNonBooleaneMantengonoIlControlloOriginale() {
    rule.setContent { SettingsScreen(viewModel = null) }

    NON_BOOLEAN_TITLES.forEach { title ->
      scrollTo(title)
      // La riga esiste ed è navigabile...
      rule.onNode(hasRequestFocusAction() and hasAnyDescendant(hasText(title))).assertExists()
      // ...ma non è stata trasformata in uno switch.
      rule.onNode(
        hasRequestFocusAction() and
          SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
          hasAnyDescendant(hasText(title))
      ).assertDoesNotExist()
    }
  }

  @Test
  fun ilTastoOkAlternaUnaSolaVoltaEAggiornaLaPreferenza() {
    val viewModel = StreamNovaViewModel()
    try {
      SettingsRepository.setAutoResume(true)
      rule.setContent { SettingsScreen(viewModel = viewModel) }
      // La scrittura su DataStore è asincrona: attende che lo stato iniziale noto sia
      // davvero quello mostrato dalla schermata prima di verificare il toggle.
      rule.waitUntil(timeoutMillis = 5_000) {
        viewModel.playbackSettings.value.autoResume
      }
      scrollTo(AUTO_RESUME_LABEL)

      switchRow(AUTO_RESUME_LABEL)
        .assert(hasStateDescription("ON"))

      // Un solo OK (KeyDown + KeyUp), come il telecomando: lo stato cambia una volta.
      pressOk(AUTO_RESUME_LABEL)
      rule.waitUntil(timeoutMillis = 5_000) {
        !viewModel.playbackSettings.value.autoResume
      }
      switchRow(AUTO_RESUME_LABEL)
        .assert(hasStateDescription("OFF"))

      // Un secondo OK ripristina il valore: nessun doppio toggle per pressione.
      pressOk(AUTO_RESUME_LABEL)
      rule.waitUntil(timeoutMillis = 5_000) {
        viewModel.playbackSettings.value.autoResume
      }
      switchRow(AUTO_RESUME_LABEL)
        .assert(hasStateDescription("ON"))
    } finally {
      SettingsRepository.setAutoResume(true)
      viewModel.viewModelScope.cancel()
    }
  }

  private fun scrollTo(title: String) {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
    rule.waitForIdle()
  }

  /** Riga focalizzabile dello switch, riconosciuta dal ruolo semantico e dal titolo. */
  private fun switchRow(title: String) = rule.onNode(
    hasRequestFocusAction() and
      SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
      hasAnyDescendant(hasText(title))
  )

  private fun pressOk(title: String) {
    switchRow(title).requestFocus()
    rule.waitForIdle()
    switchRow(title).performKeyInput {
      keyDown(Key.DirectionCenter)
      keyUp(Key.DirectionCenter)
    }
    rule.waitForIdle()
  }

  private companion object {
    const val AUTO_RESUME_LABEL = "Riprendi automaticamente la riproduzione"

    val BOOLEAN_TITLES = listOf(
      "Usa TorBox Instant Debrid",
      "Usa TMDB come fallback per i generi",
      "Avvio automatico (Autoplay)",
      "Privilegia tracce italiane",
      "Riproduci automaticamente il prossimo episodio",
      AUTO_RESUME_LABEL,
      "Riusa l'ultimo link",
      "Abilita automaticamente i sottotitoli",
      "Sottotitoli Forced se presenti nel video",
      "Audio Passthrough / Bitstream",
      "Audio Tunneling",
      "Auto Frame Rate Matching (AFR)",
      "Dolby Vision Fallback",
      "Statistiche Avanzate (Stats for Nerds)"
    )

    val NON_BOOLEAN_TITLES = listOf(
      "Lingua dell'app",
      "Controllo genitori",
      "Risoluzione preferita",
      "Durata cache ultimo link"
    )
  }
}
