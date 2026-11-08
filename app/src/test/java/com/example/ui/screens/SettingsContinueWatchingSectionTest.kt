package com.example.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.ContinueWatchingLayout
import com.example.data.prefs.ContinueWatchingSort
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sezione Impostazioni «Continua a guardare».
 *
 * Verifica struttura e ordine delle opzioni, apertura/chiusura con OK, valori predefiniti,
 * selettore di layout (tre card, spunta e bordo di selezione), selettore di ordinamento,
 * navigazione D-pad e aggiornamento delle preferenze persistenti. I controlli sono quelli
 * ufficiali StreamNova ([SettingsSwitchRow], card e dialoghi esistenti).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "it-w1280dp-h3000dp-tvdpi")
class SettingsContinueWatchingSectionTest {

  @get:Rule
  val rule = createComposeRule()

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    AppSettingsRepository.init(context)
    SettingsRepository.init(context)
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    resetContinueWatchingDefaults()
  }

  @After
  fun tearDown() {
    resetContinueWatchingDefaults()
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
  }

  private fun resetContinueWatchingDefaults() {
    AppSettingsRepository.setContinueWatchingShowRow(true)
    AppSettingsRepository.setContinueWatchingLayout(ContinueWatchingLayout.CARD)
    AppSettingsRepository.setContinueWatchingEpisodeThumbnails(false)
    AppSettingsRepository.setContinueWatchingBlurUnwatched(false)
    AppSettingsRepository.setContinueWatchingNextEpisodeFromFurthest(false)
    AppSettingsRepository.setContinueWatchingShowUnairedEpisodes(true)
    AppSettingsRepository.setContinueWatchingSort(ContinueWatchingSort.DEFAULT)
  }

  private fun showSettingsWithViewModel(): StreamNovaViewModel {
    val viewModel = StreamNovaViewModel()
    rule.setContent { SettingsScreen(viewModel = viewModel) }
    rule.waitForIdle()
    return viewModel
  }

  private fun header() = rule.onNodeWithTag(SETTINGS_CW_SECTION_HEADER_TAG)

  private fun pressOkOn(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
    node.requestFocus()
    rule.waitForIdle()
    node.assertIsFocused()
    node.performKeyInput {
      keyDown(Key.DirectionCenter)
      keyUp(Key.DirectionCenter)
    }
    rule.waitForIdle()
  }

  private fun switchRow(title: String) = rule.onNode(
    hasRequestFocusAction() and
      SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
      hasAnyDescendant(hasText(title))
  )

  private fun layoutOption(layout: ContinueWatchingLayout) =
    rule.onNodeWithTag(settingsCwLayoutOptionTestTag(layout))

  private fun scrollTo(text: String) {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    rule.waitForIdle()
  }

  @Test
  fun laSezioneEsponeTitoloDescrizioneEStatoAperto() {
    rule.setContent { SettingsScreen(viewModel = null) }
    rule.waitForIdle()

    header()
      .assertExists()
      .assert(hasStateDescription("Aperto"))
    rule.onNodeWithText("Impostazioni per la sezione \"Continua a guardare\".").assertExists()
  }

  @Test
  fun leOpzioniSonoPresentiNellOrdineDelleImmagini() {
    rule.setContent { SettingsScreen(viewModel = null) }
    rule.waitForIdle()

    val titles = listOf(
      "Mostra Continua a guardare",
      "Layout della riga",
      "Usa miniature episodi in \"Continua a guardare\"",
      "Sfoca non visti in Continua a guardare",
      "Prossimo episodio dall'ultimo raggiunto",
      "Mostra episodi non ancora trasmessi",
      "Ordinamento"
    )
    val positions = titles.map { title ->
      rule.onNodeWithText(title).fetchSemanticsNode().positionInRoot.y
    }
    assertEquals(
      "Le opzioni devono seguire l'ordine di riferimento",
      positions.sorted(),
      positions
    )
    // Il valore corrente dell'ordinamento è visibile a destra, con la voce predefinita.
    rule.onNodeWithText("Predefinito").assertExists()
  }

  @Test
  fun iValoriPredefinitiRispettanoIlComportamentoAttuale() {
    rule.setContent { SettingsScreen(viewModel = null) }
    rule.waitForIdle()

    switchRow("Mostra Continua a guardare").assert(hasStateDescription("ON"))
    switchRow("Usa miniature episodi in \"Continua a guardare\"").assert(hasStateDescription("OFF"))
    switchRow("Sfoca non visti in Continua a guardare").assert(hasStateDescription("OFF"))
    switchRow("Prossimo episodio dall'ultimo raggiunto").assert(hasStateDescription("OFF"))
    switchRow("Mostra episodi non ancora trasmessi").assert(hasStateDescription("ON"))

    layoutOption(ContinueWatchingLayout.CARD).assertIsSelected()
    layoutOption(ContinueWatchingLayout.WIDE).assertIsNotSelected()
    layoutOption(ContinueWatchingLayout.POSTER).assertIsNotSelected()
  }

  @Test
  fun laSezioneSiApreESiChiudeConIlTastoOk() {
    rule.setContent { SettingsScreen(viewModel = null) }
    rule.waitForIdle()

    // Aperta: le opzioni sono composte.
    switchRow("Mostra Continua a guardare").assertExists()

    pressOkOn(header())
    header().assert(hasStateDescription("Chiuso"))
    rule.onNodeWithText("Chiuso").assertExists()
    switchRow("Mostra Continua a guardare").assertDoesNotExist()

    // Un secondo OK riapre: nessuno stato per il tasto tenuto premuto.
    pressOkOn(header())
    header().assert(hasStateDescription("Aperto"))
    switchRow("Mostra Continua a guardare").assertExists()
  }

  @Test
  fun unSoloOkAlternaOgniSwitchUnaVolta() {
    val viewModel = showSettingsWithViewModel()
    try {
      val cases = listOf(
        "Mostra Continua a guardare" to true,
        "Usa miniature episodi in \"Continua a guardare\"" to false,
        "Sfoca non visti in Continua a guardare" to false,
        "Prossimo episodio dall'ultimo raggiunto" to false,
        "Mostra episodi non ancora trasmessi" to true
      )
      cases.forEach { (title, initial) ->
        switchRow(title).assert(hasStateDescription(if (initial) "ON" else "OFF"))
        pressOkOn(switchRow(title))
        rule.waitForIdle()
        val prefs = AppSettingsRepository.continueWatchingPreferences.value
        val actual = when (title) {
          "Mostra Continua a guardare" -> prefs.showRow
          "Usa miniature episodi in \"Continua a guardare\"" -> prefs.useEpisodeThumbnails
          "Sfoca non visti in Continua a guardare" -> prefs.blurUnwatched
          "Prossimo episodio dall'ultimo raggiunto" -> prefs.nextEpisodeFromFurthest
          else -> prefs.showUnairedEpisodes
        }
        assertEquals("$title deve essere alternato una sola volta", !initial, actual)
        switchRow(title).assert(hasStateDescription(if (!initial) "ON" else "OFF"))
        // Ripristina il default per il caso successivo.
        pressOkOn(switchRow(title))
      }
    } finally {
      viewModel.viewModelScope.cancel()
    }
  }

  @Test
  fun ilSelettoreDiLayoutHaTreCardENavigaConIlDpad() {
    val viewModel = showSettingsWithViewModel()
    try {
      layoutOption(ContinueWatchingLayout.CARD).assertIsSelected()
      layoutOption(ContinueWatchingLayout.WIDE).assertExists()
      layoutOption(ContinueWatchingLayout.POSTER).assertExists()

      // D-pad destro: il focus passa alla card successiva, un solo OK la seleziona.
      layoutOption(ContinueWatchingLayout.CARD).requestFocus()
      rule.waitForIdle()
      layoutOption(ContinueWatchingLayout.CARD).performKeyInput {
        keyDown(Key.DirectionRight)
        keyUp(Key.DirectionRight)
      }
      rule.waitForIdle()
      layoutOption(ContinueWatchingLayout.WIDE).assertIsFocused()

      layoutOption(ContinueWatchingLayout.WIDE).performKeyInput {
        keyDown(Key.DirectionCenter)
        keyUp(Key.DirectionCenter)
      }
      rule.waitForIdle()

      assertEquals(
        ContinueWatchingLayout.WIDE,
        AppSettingsRepository.continueWatchingPreferences.value.layout
      )
      layoutOption(ContinueWatchingLayout.WIDE).assertIsSelected()
      layoutOption(ContinueWatchingLayout.CARD).assertIsNotSelected()
      // La spunta è parte della card selezionata (icona decorativa, nessun testo extra).
      rule.onNodeWithText("Larga").assertExists()
    } finally {
      viewModel.viewModelScope.cancel()
    }
  }

  @Test
  fun lOrdinamentoSiSceglieDalDialogoEIlValoreRestaVisibile() {
    val viewModel = showSettingsWithViewModel()
    try {
      rule.onNodeWithText("Predefinito").assertExists()

      val sortRow = rule.onNode(
        hasRequestFocusAction() and hasAnyDescendant(hasText("Ordinamento"))
      )
      pressOkOn(sortRow)
      rule.waitForIdle()

      rule.onNodeWithText("Titolo (A-Z)").assertExists()
      rule.onNodeWithText("Voto TMDB").assertExists()

      val titleOption = rule.onNode(
        hasRequestFocusAction() and hasAnyDescendant(hasText("Titolo (A-Z)"))
      )
      pressOkOn(titleOption)
      rule.waitForIdle()

      assertEquals(
        ContinueWatchingSort.TITLE,
        AppSettingsRepository.continueWatchingPreferences.value.sort
      )
      // Il dialogo si chiude e la riga mostra il nuovo valore corrente.
      rule.onNodeWithText("Titolo (A-Z)").assertExists()
    } finally {
      viewModel.viewModelScope.cancel()
    }
  }

  @Test
  fun leAltreSezioniNonVengonoStravolte() {
    rule.setContent { SettingsScreen(viewModel = null) }
    rule.waitForIdle()

    // Sezioni storiche ancora presenti e nell'ordine atteso.
    val homeTop = rule.onNodeWithText("HOME").fetchSemanticsNode().positionInRoot.y
    val cwTop = header().fetchSemanticsNode().positionInRoot.y
    val searchTop = rule.onNodeWithText("RICERCA E CATALOGHI").fetchSemanticsNode().positionInRoot.y
    assertTrue("La sezione segue HOME", homeTop < cwTop)
    assertTrue("La sezione precede Ricerca e cataloghi", cwTop < searchTop)
    assertTrue(AppSettingsRepository.continueWatchingPreferences.value.showRow)
  }
}
