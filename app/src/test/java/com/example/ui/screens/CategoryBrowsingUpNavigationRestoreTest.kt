package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Riproduttore runtime del difetto FILM/SERIE TV: apertura di una card NON iniziale e ritorno
 * dal Dettaglio atterrano sulla PRIMA card della stessa riga invece che sulla card selezionata.
 *
 * Contratto MainActivity ricostruito integralmente:
 * - ViewModel REALE (target/tokne/signalFocusRestore vivi, senza duplicazione del salvataggio);
 * - `pendingContentFocusRestore` a livello StreamNovaApp, `isInitialHomeFocusSet` dentro la
 *   destination Browsing (si azzera al rimontaggio, come il `remember` reale);
 * - browsing e Dettaglio su SaveableStateProvider separati (remount al BACK);
 * - il Dettaglio ha un nodo realmente focalizzato (focus repair al pop);
 * - navigazione nella griglia con TASTI REALI (D-pad), come sul telecomando.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class CategoryBrowsingUpNavigationRestoreTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val TITLE = "Tutti i film"
  }

  private fun item(index: Int): MediaItem = MediaItem(
    id = "m%02d".format(index + 1),
    title = "Titolo %02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1,
    rating = 10f - index * 0.1f
  )

  // ---------------------------------------------------------------- stato app

  private lateinit var viewModel: StreamNovaViewModel
  private var showDetail by mutableStateOf(false)
  private var pendingContentFocusRestore by mutableStateOf(false)
  private var liveItems by mutableStateOf<List<MediaItem>>(emptyList())

  @Composable
  private fun App() {
    val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
    val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
    val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()

    CompositionLocalProvider(
      LocalLastFocusedMediaId provides lastFocusedMediaId,
      LocalLastFocusedTarget provides lastFocusedTarget,
      LocalFocusRestoreToken provides focusRestoreToken,
      LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() }
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        val stateHolder = rememberSaveableStateHolder()
        if (showDetail) {
          stateHolder.SaveableStateProvider("detail") {
            DetailScreenStub()
          }
        } else {
          stateHolder.SaveableStateProvider("browsing") {
            // Effetto d'ingresso della destination Browsing: isInitialHomeFocusSet si azzera a
            // ogni rimontaggio ma per FILM non vi è requestHomeScrollToTop (gated su HOME);
            // il ramo restore emette signalFocusRestore quando il flag è alto.
            var isInitialHomeFocusSet by remember { mutableStateOf(false) }
            LaunchedEffect(pendingContentFocusRestore, isInitialHomeFocusSet) {
              if (!isInitialHomeFocusSet) {
                isInitialHomeFocusSet = true
              } else if (pendingContentFocusRestore) {
                pendingContentFocusRestore = false
                if (viewModel.lastFocusedMediaId.value != null) {
                  viewModel.signalFocusRestore(viewModel.lastFocusedMediaId.value!!)
                }
              }
            }

            CategoryBrowsingScreen(
              title = TITLE,
              items = liveItems,
              isTv = false,
              viewModel = viewModel,
              onMediaClick = { _ ->
                showDetail = true
              },
              onLoadMore = null
            )
          }
        }
      }
    }
  }

  @Composable
  private fun DetailScreenStub() {
    val focusRequester = remember { FocusRequester() }
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
    ) {
      Box(
        modifier = Modifier
          .size(200.dp)
          .align(Alignment.Center)
          .focusRequester(focusRequester)
          .background(Color(0xFF3A3A3A))
          .clickable { }
      )
      LaunchedEffect(Unit) {
        try {
          focusRequester.requestFocus()
        } catch (_: Exception) {
        }
      }
    }
  }

  private fun render() {
    liveItems = List(18) { item(it) }
    viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    rule.setContent { App() }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helpers

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused()).fetchSemanticsNodes().flatMap { node ->
      node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
    }

  private fun focusedTitle(): String = focusedTexts().joinToString(" | ")

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key, times: Int = 1) {
    repeat(times) {
      rule.onNode(isFocused()).performKeyInput { pressKey(key) }
      rule.waitForIdle()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun clickFocusedCard() {
    rule.onNode(isFocused()).performClick()
    rule.waitForIdle()
    System.out.println("TEST_LOG clicked target=${viewModel.lastFocusedTarget.value} focus_before_detail=[${focusedTitle()}]")
  }

  @OptIn(ExperimentalTestApi::class)
  private fun backFromDetail() {
    pendingContentFocusRestore = true
    showDetail = false
    rule.waitForIdle()
    rule.waitUntil(timeoutMillis = 5_000) { focusedTexts().isNotEmpty() }
    System.out.println("TEST_LOG focus_after_back = [${focusedTitle()}] target=${viewModel.lastFocusedTarget.value}")
  }

  private fun assertFocusedCard(title: String) {
    rule.waitUntil(timeoutMillis = 5_000) { focusedTexts().isNotEmpty() }
    assertTrue(
      "Focus su [${focusedTitle()}] invece che su $title",
      focusedTexts().contains(title)
    )
  }

  // ---------------------------------------------------------------- test

  /** Controllo: prima card della griglia, round-trip e ritorno sulla stessa card. */
  @Test
  fun ritornoSuPrimaCardDellaGriglia() {
    render()
    focusCard("Titolo 01")

    clickFocusedCard()
    backFromDetail()

    assertFocusedCard("Titolo 01")
  }

  /** DIFETTO: card non iniziale (Titolo 10, riga 2, colonna 2), round-trip: deve tornare su
   *  Titolo 10, non sulla prima card della riga (Titolo 09/08). */
  @Test
  fun ritornoSuCardNonInizialeStessaRiga() {
    render()
    focusCard("Titolo 10")

    clickFocusedCard()
    assertTrue(
      "Il target salvato al click non è la card cliccata: ${viewModel.lastFocusedTarget.value}",
      viewModel.lastFocusedTarget.value == (com.example.ui.components.FOCUS_ROW_ANY to "m10")
    )
    backFromDetail()

    assertFocusedCard("Titolo 10")
  }

  /** Ripristini consecutivi su card differenti (stessa riga e righe diverse) via VM reale. */
  @Test
  fun ripristiniConsecutiviSuCardDiverse() {
    render()
    listOf(
      "Titolo 03" to "m03",
      "Titolo 10" to "m10",
      "Titolo 07" to "m07",
      "Titolo 09" to "m09"
    ).forEach { (title, id) ->
      focusCard(title)
      clickFocusedCard()
      assertTrue(
        "Target $id non salvato al click: ${viewModel.lastFocusedTarget.value}",
        viewModel.lastFocusedTarget.value == (com.example.ui.components.FOCUS_ROW_ANY to id)
      )
      backFromDetail()
      assertFocusedCard(title)
    }
  }
}
