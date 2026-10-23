package com.example.ui.screens.player

import androidx.compose.ui.input.key.Key
import android.content.Context
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.streaming.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Selettore sorgenti interno al player: verifica da UI (Robolectric + Compose).
 *
 * Contratti verificati:
 *  - ogni riga mostra il nome del provider e la qualita' quando nota;
 *  - la sorgente in riproduzione e' evidenziata e le altre no;
 *  - l'elenco e' utilizzabile col TOUCH (mobile): il click su una riga seleziona
 *    quella sorgente;
 *  - l'elenco e' navigabile col D-PAD (Android TV): le righe sono focalizzabili
 *    e su/giu' spostano il focus fra le voci.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class PlayerSourceSelectorDialogUiTest {

  @get:Rule
  val rule = createComposeRule()

  private val sourceA = StreamSource(
    streamUrl = "https://cdn.example/a/1080p/index.m3u8",
    quality = "1080p",
    serverName = "Provider A"
  )
  private val sourceB = StreamSource(
    streamUrl = "https://cdn.example/b/720p/index.m3u8",
    quality = "720p",
    serverName = "Provider B"
  )

  @Test
  fun rowsShowProviderAndQuality() {
    val entries = PlayerSourceSelector.buildEntries(listOf(sourceA, sourceB), sourceA)
    rule.setContent {
      PlayerSourceSelectorDialog(entries = entries, onSelect = {}, onDismiss = {})
    }

    rule.onNodeWithText("Provider A · 1080p").assertExists()
    rule.onNodeWithText("Provider B · 720p").assertExists()
  }

  @Test
  fun theCurrentSourceIsMarked() {
    val entries = PlayerSourceSelector.buildEntries(listOf(sourceA, sourceB), sourceB)
    rule.setContent {
      PlayerSourceSelectorDialog(entries = entries, onSelect = {}, onDismiss = {})
    }

    // Solo la riga in riproduzione mostra il badge. Il testo viene risolto
    // dall'app context: il test non deve dipendere dalla locale del dispositivo.
    val context = ApplicationProvider.getApplicationContext<Context>()
    val currentBadge = context.getString(R.string.player_source_selector_current)
    rule.onNodeWithText(currentBadge).assertExists()
  }

  @Test
  fun clickingARowSelectsThatSource() {
    var selected: StreamSource? = null
    val entries = PlayerSourceSelector.buildEntries(listOf(sourceA, sourceB), sourceA)
    rule.setContent {
      PlayerSourceSelectorDialog(
        entries = entries,
        onSelect = { selected = it },
        onDismiss = {}
      )
    }

    rule.onNodeWithText("Provider B · 720p").performClick()
    rule.waitForIdle()

    assertNotNull("Il click su una riga deve selezionare la sorgente", selected)
    assertEquals(sourceB, selected)
  }

  @Test
  fun rowsAreFocusableAndDpadMovesTheFocus() {
    val entries = PlayerSourceSelector.buildEntries(listOf(sourceA, sourceB), sourceA)
    rule.setContent {
      PlayerSourceSelectorDialog(entries = entries, onSelect = {}, onDismiss = {})
    }
    rule.waitForIdle()

    // Le righe devono essere focalizzabili (D-pad su Android TV).
    rule.onNodeWithText("Provider A · 1080p").requestFocus()
    rule.waitForIdle()
    rule.onNodeWithText("Provider A · 1080p").assertIsFocused()
    rule.onNodeWithText("Provider B · 720p").assertIsNotFocused()

    // D-pad: giu' sposta il focus sulla riga successiva.
    rule.onNodeWithText("Provider A · 1080p").performKeyInput { pressKey(Key.DirectionDown) }
    rule.waitForIdle()
    rule.onNodeWithText("Provider B · 720p").assertIsFocused()

    // D-pad: su risale alla riga precedente.
    rule.onNodeWithText("Provider B · 720p").performKeyInput { pressKey(Key.DirectionUp) }
    rule.waitForIdle()
    rule.onNodeWithText("Provider A · 1080p").assertIsFocused()
  }
}
