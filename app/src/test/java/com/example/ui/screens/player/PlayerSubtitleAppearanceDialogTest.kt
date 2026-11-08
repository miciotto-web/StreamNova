package com.example.ui.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitleOpacity
import com.example.data.prefs.SubtitleSize
import com.example.ui.screens.PlayerSubtitleSelectionDialog
import com.example.ui.screens.subtitleCaptionStyleFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sezione "Aspetto dei sottotitoli" dentro il menu Sottotitoli del player.
 *
 * Contratti verificati da UI (Robolectric + Compose):
 * - le tre preferenze condivise (5 dimensioni, 4 sfondi, 4 opacità) sono presenti
 *   nello STESSO dialog già esistente, senza pulsanti aggiuntivi sul player;
 * - i chip sono raggiungibili e attivabili col D-pad (focus + OK del telecomando);
 * - il focus resta sul chip appena confermato dopo la ricomposizione (ripristino);
 * - le selezioni notificano i callback che il player inoltra a SettingsRepository.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "it-w1280dp-h720dp-tvdpi")
class PlayerSubtitleAppearanceDialogTest {

  @get:Rule
  val rule = createComposeRule()

  private fun setDialog(
    size: SubtitleSize = SubtitleSize.MEDIUM,
    background: SubtitleBackground = SubtitleBackground.BLACK,
    opacity: SubtitleOpacity = SubtitleOpacity.FIFTY,
    onSelectSize: (SubtitleSize) -> Unit = {},
    onSelectBackground: (SubtitleBackground) -> Unit = {},
    onSelectOpacity: (SubtitleOpacity) -> Unit = {}
  ) {
    rule.setContent {
      PlayerSubtitleSelectionDialog(
        title = "Sottotitoli",
        localOptions = listOf("Nessuno", "Italiano"),
        selectedLocalIndex = 0,
        onSelectLocal = {},
        onlineSubtitles = emptyList(),
        isOnlineLoading = false,
        onSelectOnline = {},
        offsetMs = 0L,
        onAdjustOffset = {},
        onResetOffset = {},
        subtitleSize = size,
        subtitleBackground = background,
        subtitleOpacity = opacity,
        onSelectSubtitleSize = onSelectSize,
        onSelectSubtitleBackground = onSelectBackground,
        onSelectSubtitleOpacity = onSelectOpacity,
        onDismiss = {}
      )
    }
    rule.waitForIdle()
  }

  @Test
  fun ilMenuSottotitoliContieneTutteELeOpzioniDiAspetto() {
    setDialog()

    rule.onNodeWithText("Aspetto dei sottotitoli").assertExists()
    rule.onNodeWithText("Dimensione").assertExists()
    rule.onNodeWithText("Sfondo").assertExists()
    rule.onNodeWithText("Opacità").assertExists()

    // 5 dimensioni: il chip mostra "Aa" crescente, la descrizione accessibile è il nome.
    listOf("Piccolissima", "Piccola", "Media", "Grande", "Molto grande").forEach { label ->
      rule.onNodeWithContentDescription(label).assertExists()
    }
    // 4 sfondi.
    listOf("Nessuno", "Nero", "Bianco", "Blu").forEach { label ->
      rule.onNodeWithContentDescription(label).assertExists()
    }
    // 4 opacità.
    listOf("100%", "75%", "50%", "25%").forEach { label ->
      rule.onNodeWithContentDescription(label).assertExists()
    }
  }

  @Test
  fun unChipSelezionatoConOkNotificaLaPreferenza() {
    var selectedBackground: SubtitleBackground? = null
    setDialog(onSelectBackground = { selectedBackground = it })

    val bianco = rule.onNodeWithContentDescription("Bianco")
    bianco.requestFocus()
    bianco.assertIsFocused()
    pressOk(bianco)
    rule.waitForIdle()

    assertEquals(SubtitleBackground.WHITE, selectedBackground)
  }

  @Test
  fun ilDpadSpostaIlFocusTraISizeEIlFocusRestaDopoLaSelezione() {
    rule.setContent {
      var size by remember { mutableStateOf(SubtitleSize.MEDIUM) }
      PlayerSubtitleSelectionDialog(
        title = "Sottotitoli",
        localOptions = listOf("Nessuno", "Italiano"),
        selectedLocalIndex = 0,
        onSelectLocal = {},
        onlineSubtitles = emptyList(),
        isOnlineLoading = false,
        onSelectOnline = {},
        offsetMs = 0L,
        onAdjustOffset = {},
        onResetOffset = {},
        subtitleSize = size,
        subtitleBackground = SubtitleBackground.BLACK,
        subtitleOpacity = SubtitleOpacity.FIFTY,
        onSelectSubtitleSize = { size = it },
        onDismiss = {}
      )
    }
    rule.waitForIdle()

    val media = rule.onNodeWithContentDescription("Media")
    media.requestFocus()
    media.assertIsFocused()

    // Il chip successivo nella stessa riga riceve il focus con la freccia destra.
    media.performKeyInput { pressKey(Key.DirectionRight) }
    rule.waitForIdle()
    val grande = rule.onNodeWithContentDescription("Grande")
    grande.assertIsFocused()

    // OK conferma "Grande": la ricomposizione aggiorna la selezione ma NON sposta il focus.
    pressOk(grande)
    rule.waitForIdle()
    rule.onNodeWithContentDescription("Grande").assertIsFocused()
  }

  /** Invia il tasto OK (DPAD_CENTER) al nodo a fuoco, come il telecomando. */
  private fun pressOk(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
    node.performKeyInput {
      keyDown(Key.DirectionCenter)
      keyUp(Key.DirectionCenter)
    }
  }

  @Test
  fun loStileApplicatoCombinaTestoColoreEOpacita() {
    // Sfondo bianco: testo nero automatico e alpha dell'opacità sul riquadro.
    val white = subtitleCaptionStyleFor(SubtitleBackground.WHITE, SubtitleOpacity.FIFTY)
    assertEquals(0xFF000000.toInt(), white.foregroundColor)
    assertEquals(0x80FFFFFF.toInt(), white.backgroundColor)

    // Sfondo nero al 25%: testo bianco, riquadro nero trasparente.
    val black = subtitleCaptionStyleFor(SubtitleBackground.BLACK, SubtitleOpacity.TWENTY_FIVE)
    assertEquals(0xFFFFFFFF.toInt(), black.foregroundColor)
    assertEquals(0x40000000, black.backgroundColor)
  }

  @Test
  fun loStileNessunoSfondoNonDisegnaIlRiquadro() {
    val style = subtitleCaptionStyleFor(SubtitleBackground.NONE, SubtitleOpacity.FULL)
    assertEquals(0, style.backgroundColor)
    assertEquals(0xFFFFFFFF.toInt(), style.foregroundColor)
  }
}
