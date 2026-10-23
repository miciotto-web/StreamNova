package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifica la CONTINUITÀ GEOMETRICA della navigazione verticale nativa tra righe LazyRow
 * semplici (senza focusGroup): lo step Su/Giù di Compose deve restare sulla stessa colonna
 * quando le finestre sono allineate e scegliere la colonna valida più vicina nelle righe più
 * corte — la base sulla quale si innesta il modello NuvioTV (riga focusGroup + focusRestorer,
 * esercitato su schermata reale in [HomeFocusNavigationUiTest]).
 *
 * Ogni riga riproduce la stessa catena della Home:
 * `Modifier.rowFocusProperties(coordinator, rowKey, colIndex)` +
 * `Modifier.focusRequester(coordinator.getRequester(rowKey, colIndex))`, dentro
 * `TvFocusBringIntoView { LazyColumn { item { TvFocusRow { LazyRow { ... } } } } }`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class HomeRowFocusNavigationUiTest {

  @get:Rule
  val rule = createComposeRule()

  @OptIn(ExperimentalTestApi::class)
  private fun focusCell(tag: String) {
    rule.onNodeWithTag(tag).requestFocus()
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressDown(tag: String) {
    rule.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionDown) }
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressUp(tag: String) {
    rule.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionUp) }
    rule.waitForIdle()
  }

  @Composable
  private fun RowHarness(
    rowKeys: List<String>,
    counts: Map<String, Int>,
    rowHeightDp: Int = 200
  ) {
    val coordinator = remember { HomeRowFocusCoordinator(rowKeys, counts) }
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    fun rowState(key: String): LazyListState = rowStates.getOrPut(key) { LazyListState() }

    TvFocusBringIntoView(topOffsetDp = 0.dp) {
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        rowKeys.forEach { rowKey ->
          item(key = rowKey) {
            TvFocusRow {
              LazyRow(
                state = rowState(rowKey),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(rowHeightDp.dp)
              ) {
                itemsIndexed(List(counts[rowKey] ?: 0) { it }) { index, _ ->
                  Box(
                    modifier = Modifier
                      .size(width = 150.dp, height = 100.dp)
                      .then(Modifier.rowFocusProperties(coordinator, rowKey, index))
                      .focusRequester(coordinator.getRequester(rowKey, index))
                      .testTag("$rowKey-$index")
                      .clickable { }
                      .background(Color.Gray)
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  fun giuDaColonnaIntermediaRaggiungeLaStessaColonnaNellaRigaAdiacente() {
    val rows = listOf("row_a", "row_b", "row_c")
    val counts = mapOf("row_a" to 8, "row_b" to 8, "row_c" to 8)

    rule.setContent { RowHarness(rows, counts) }

    focusCell("row_a-3")
    rule.onNodeWithTag("row_a-3").assertIsFocused()

    pressDown("row_a-3")
    rule.onNodeWithTag("row_b-3").assertIsFocused()

    pressDown("row_b-3")
    rule.onNodeWithTag("row_c-3").assertIsFocused()
  }

  @Test
  fun suDaColonnaIntermediaRitornaAllaStessaColonna() {
    val rows = listOf("row_a", "row_b", "row_c")
    val counts = mapOf("row_a" to 8, "row_b" to 8, "row_c" to 8)

    rule.setContent { RowHarness(rows, counts) }

    focusCell("row_c-4")
    rule.onNodeWithTag("row_c-4").assertIsFocused()

    pressUp("row_c-4")
    rule.onNodeWithTag("row_b-4").assertIsFocused()

    pressUp("row_b-4")
    rule.onNodeWithTag("row_a-4").assertIsFocused()
  }

  @Test
  fun rigaPiuCortaSceglieLaColonnaValidaPiuVicina() {
    val rows = listOf("row_a", "row_b")
    val counts = mapOf("row_a" to 8, "row_b" to 3)

    rule.setContent { RowHarness(rows, counts) }

    focusCell("row_a-6")
    pressDown("row_a-6")

    // Nessuna card alla colonna 6: si sceglie la più vicina esistente (indice 2), mai l'indice 0.
    rule.onNodeWithTag("row_b-2").assertIsFocused()
  }
}
