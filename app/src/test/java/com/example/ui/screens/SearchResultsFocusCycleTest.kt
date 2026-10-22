package com.example.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.viewmodel.StreamNovaViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Ciclo del focus nella schermata Ricerca REALE ([SearchScreen]) sotto Robolectric.
 *
 * Difetto coperto (segnalato su Android TV): il primo ingresso nei risultati funzionava, ma
 * dopo essere risaliti alla barra/ai filtri il D-pad Giù non rientrava più nelle card.
 *
 * Causa: la `focusProperties` dei filtri assegnava a `down` il [FocusRequester] della cella 0
 * della griglia dei risultati. Le griglie lazy smontano la cella 0 appena la lista scorre (i
 * risultati arrivano in più ondate e la griglia mantiene in vista la card corrente), quindi il
 * requester resta orfano: la focus search del D-pad lancia `FocusRequester is not initialized`
 * e il focus resta bloccato in alto. Il `guard` sull'aggancio non salvava: le focusProperties
 * vengono applicate alla composizione/aggiornamento del nodo, non ad ogni focus search, e il
 * cambio di aggancio della cella non ricompone i filtri.
 *
 * Il test verifica DUE cicli completi barra -> filtri -> risultati -> filtri -> barra con la
 * lista dei risultati IDENTICA, e poi lo stesso ciclo dopo lo scroll/riordino della lista
 * (cella 0 smontata), che è la condizione che rompeva il D-pad.
 *
 * Ogni ingresso è verificato su TRE livelli, non solo "nessuna eccezione":
 * 1. la card a fuoco ha il titolo della prima card VISIBILE della griglia;
 * 2. i log reali di `onFocusChanged` contengono `content surface hasFocus=true` (il focus è
 *    dentro l'area contenuti, non sul contenitore stesso) e `RESULT CARD FOCUSED id=...`;
 * 3. il log `DOWN from filters` mostra la card scelta e l'esito dell'invio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class SearchResultsFocusCycleTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    /** Qualunque etichetta del filtro "Tutti": la lingua del test dipende dalla locale. */
    const val FILTER_ALL_LABEL = "All"
    const val FILTER_ALL_LABEL_IT = "Tutti"
    const val RESULT_TITLE_PREFIX = "Risultato "
  }

  private var liveResults by mutableStateOf<List<MediaItem>>(emptyList())
  private var restoreTarget by mutableStateOf<Pair<String, String>?>(null)
  private var restoreToken by mutableStateOf(0)
  private lateinit var viewModel: StreamNovaViewModel

  private fun item(index: Int, prefix: String = ""): MediaItem = MediaItem(
    id = "$prefix%02d".format(index + 1),
    title = RESULT_TITLE_PREFIX + "%02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1
  )

  private fun render(initial: List<MediaItem>) {
    liveResults = initial
    val vm = StreamNovaViewModel()
    viewModel = vm
    vm.updateSearchQuery("dune")
    rule.setContent {
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides restoreTarget?.second,
        LocalLastFocusedTarget provides restoreTarget,
        LocalFocusRestoreToken provides restoreToken,
        LocalClearLastFocusedMedia provides { restoreTarget = null }
      ) {
        // resultsOverride: i test pilotano i risultati, quindi nessuna dipendenza dalla rete.
        SearchScreen(
          viewModel = viewModel,
          onMediaClick = {},
          resultsOverride = liveResults
        )
      }
    }
    rule.waitForIdle()
  }

  private fun filterMatcher() =
    hasAnyDescendant(hasText(FILTER_ALL_LABEL)) or hasAnyDescendant(hasText(FILTER_ALL_LABEL_IT))

  private fun focusFilter() {
    rule.onNode(isFocusable() and filterMatcher()).requestFocus()
    rule.waitForIdle()
  }

  private fun assertFilterFocused() {
    rule.waitUntil(timeoutMillis = 3_000) {
      try {
        rule.onNode(isFocused() and filterMatcher()).fetchSemanticsNode()
        true
      } catch (_: Throwable) {
        false
      }
    }
  }

  private fun focusedText(): String? = try {
    rule.onNode(isFocused()).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
  } catch (_: Throwable) {
    null
  }

  private data class VisibleCard(val title: String, val top: Float, val left: Float)

  /** Card dei risultati realmente composte nel viewport, in ordine di lettura della griglia. */
  private fun visibleResultCards(): List<VisibleCard> =
    rule.onAllNodes(isFocusable() and hasText(RESULT_TITLE_PREFIX, substring = true))
      .fetchSemanticsNodes()
      .mapNotNull { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }
          ?: return@mapNotNull null
        val title = texts.firstOrNull { it.startsWith(RESULT_TITLE_PREFIX) }
          ?: return@mapNotNull null
        val bounds = node.boundsInRoot
        VisibleCard(title, bounds.top, bounds.left)
      }

  /** Titolo della card che l'utente vede in alto a sinistra nella griglia. */
  private fun topLeftVisibleTitle(): String? =
    visibleResultCards().sortedWith(compareBy({ it.top }, { it.left })).firstOrNull()?.title

  /**
   * Prova che il focus è DAVVERO su una card composta: non basta che il tasto non lanci
   * eccezioni o che un requester sia stato inviato.
   */
  private fun assertFocusedCardTitle(expected: String?) {
    rule.waitUntil(timeoutMillis = 3_000) {
      focusedText()?.startsWith(expected.orEmpty()) == true
    }
    val actual = focusedText()
    assertTrue(
      "Il focus non è sulla card '$expected' ma su '$actual'",
      expected != null && actual?.startsWith(expected) == true
    )
  }

  private fun assertResultFocused() {
    rule.waitUntil(timeoutMillis = 3_000) {
      focusedText()?.contains(RESULT_TITLE_PREFIX) == true
    }
    assertTrue(
      "Il focus non è su una card dei risultati ma su '${focusedText()}'",
      focusedText()?.contains(RESULT_TITLE_PREFIX) == true
    )
  }

  /** Log emessi dalla diagnostica reale della schermata (`SearchFocusDiagnostics.TAG`). */
  private fun searchFocusLogs(): List<ShadowLog.LogItem> =
    ShadowLog.getLogs().filter { it.tag == "SEARCH_FOCUS" }

  /**
   * Verifica sui LOG REALI di `onFocusChanged` che il focus è entrato nell'area contenuti
   * (`hasFocus=true` sul contenitore) e che una card ha ricevuto il focus. Non basta
   * asserire che il tasto non ha lanciato eccezioni.
   */
  private fun assertFocusActuallyEnteredGrid(expectedTitle: String?) {
    rule.waitUntil(timeoutMillis = 3_000) {
      searchFocusLogs().any { log ->
        log.msg.contains("RESULT CARD FOCUSED") &&
          (expectedTitle == null || log.msg.contains(expectedTitle))
      }
    }
    assertTrue(
      "Nessun log `content surface hasFocus=true`: il focus non è entrato nella griglia.\n" +
        searchFocusLogs().joinToString("\n") { it.msg },
      searchFocusLogs().any { it.msg.contains("content surface hasFocus=true") }
    )
    assertTrue(
      "Nessun log `RESULT CARD FOCUSED` per la card attesa '$expectedTitle'.\n" +
        searchFocusLogs().joinToString("\n") { it.msg },
      searchFocusLogs().any { log ->
        log.msg.contains("RESULT CARD FOCUSED") &&
          (expectedTitle == null || log.msg.contains(expectedTitle))
      }
    )
  }

  /** La barra di ricerca è l'unico elemento focalizzabile sopra la riga dei filtri. */
  private fun assertSearchBarFocused() {
    rule.waitUntil(timeoutMillis = 3_000) {
      try {
        val top = rule.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.top
        top < 195f
      } catch (_: Throwable) {
        false
      }
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  /** Risale dai risultati fino ai filtri come farebbe l'utente (al massimo [maxSteps] Su). */
  @OptIn(ExperimentalTestApi::class)
  private fun pressUpUntilFilterFocused(maxSteps: Int = 40) {
    repeat(maxSteps) {
      rule.waitForIdle()
      val onFilter = try {
        rule.onNode(isFocused() and filterMatcher()).fetchSemanticsNode()
        true
      } catch (_: Throwable) {
        false
      }
      if (onFilter) return
      pressKey(Key.DirectionUp)
    }
    assertFilterFocused()
  }

  @Test
  fun dueCicliBarraFiltriRisultatiConListaInvariata() {
    render(List(24) { item(it) })

    repeat(2) { cycle ->
      focusFilter()
      assertFilterFocused()

      ShadowLog.clear()
      val expectedEntry = topLeftVisibleTitle()
      pressKey(Key.DirectionDown)
      assertFocusedCardTitle(expectedEntry)
      assertFocusActuallyEnteredGrid(expectedEntry)

      if (cycle == 0) {
        // Destra/Sinistra continuano a muoversi tra le card della riga.
        val before = focusedText()
        pressKey(Key.DirectionRight)
        assertResultFocused()
        val afterRight = focusedText()
        assertTrue(
          "Destra non ha spostato il focus sulla card successiva ($before -> $afterRight)",
          afterRight != null && afterRight != before
        )
        pressKey(Key.DirectionLeft)
        assertResultFocused()
      }

      // Risalita: risultati -> filtri -> barra di ricerca.
      pressUpUntilFilterFocused()
      assertFilterFocused()
      pressKey(Key.DirectionUp)
      assertSearchBarFocused()

      // Discesa: barra -> filtri -> risultati.
      pressKey(Key.DirectionDown)
      assertFilterFocused()
      ShadowLog.clear()
      val expectedSecondEntry = topLeftVisibleTitle()
      pressKey(Key.DirectionDown)
      assertFocusedCardTitle(expectedSecondEntry)
      assertFocusActuallyEnteredGrid(expectedSecondEntry)
    }
  }

  @Test
  fun giuRientraNeiRisultatiDopoLoScrollDellaGriglia() {
    render(List(24) { item(it) })
    focusFilter()
    assertFilterFocused()

    repeat(2) { cycle ->
      // Ricomposizione della schermata mentre la cella 0 della griglia è ancora montata:
      // è il momento in cui la vecchia implementazione memorizzava il FocusRequester della
      // cella 0 come target del "giù" (le focusProperties sono applicate al nodo, non ad
      // ogni focus search).
      viewModel.updateSearchQuery(if (cycle == 0) "dune " else "dune  ")
      rule.waitForIdle()
      assertFilterFocused()

      // La griglia scorre SENZA spostare il focus: la cella 0 esce dalla composizione e
      // nessuna ricomposizione ricalcola le focusProperties dei filtri.
      rule.onNode(hasScrollAction()).performScrollToIndex(23)
      rule.waitForIdle()
      rule.onNode(hasText("Risultato 01", substring = true)).assertDoesNotExist()
      assertFilterFocused()

      ShadowLog.clear()
      val expected = topLeftVisibleTitle()
      pressKey(Key.DirectionDown)
      assertFocusedCardTitle(expected)
      assertFocusActuallyEnteredGrid(expected)
      pressUpUntilFilterFocused()
      assertFilterFocused()
    }
  }

  @Test
  fun giuSenzaRisultatiNonBloccaIlFocus() {
    render(emptyList())
    focusFilter()
    assertFilterFocused()

    // Nessun contenuto focalizzabile sotto i filtri: il Giù non deve lanciare
    // "FocusRequester is not initialized" né spostare il focus altrove.
    pressKey(Key.DirectionDown)
    assertFilterFocused()

    // Al comparire dei risultati lo stesso Giù deve entrare nella griglia.
    liveResults = List(12) { item(it) }
    rule.waitForIdle()
    ShadowLog.clear()
    val expected = topLeftVisibleTitle()
    pressKey(Key.DirectionDown)
    assertFocusedCardTitle(expected)
    assertFocusActuallyEnteredGrid(expected)
  }
}
