package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ripristino del focus sulla card selezionata al BACK dal Dettaglio, nella schermata Ricerca
 * reale ([SearchScreen]) sotto Robolectric.
 *
 * Difetto coperto (segnalato su Android TV): dopo aver aperto il Dettaglio da una card di una
 * categoria, il BACK riportava il focus sui chip "Film"/"Serie TV" invece che sulla card.
 * Due cause, entrambe esercitate qui:
 * - il click sulla card NON salvava il target di ripristino, quindi al ritorno non c'era
 *   nulla da ripristinare;
 * - due effetti della schermata chiedevano esplicitamente il focus al chip del filtro al
 *   rientro, vincendo sulla card.
 *
 * Il round-trip è riprodotto come il NavHost: la schermata viene smontata e rimontata
 * (SaveableStateProvider) e il token di ripristino avanza come fa `signalFocusRestore` al
 * BACK. La geometria è quella di un TV 1080p (960x540dp), dove il viewport della griglia è
 * alto quanto una riga: la card ripristinata deve essere riportata in vista dallo scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-mdpi")
class SearchDetailFocusRestoreTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val TITLE_PREFIX = "Risultato "
    const val CATEGORY_ID = "horror"
    const val QUERY = "dune"
  }

  private lateinit var viewModel: StreamNovaViewModel
  private var showDetail by mutableStateOf(false)

  private fun item(index: Int): MediaItem = MediaItem(
    id = "%02d".format(index + 1),
    title = TITLE_PREFIX + "%02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1
  )

  private fun title(index: Int): String = TITLE_PREFIX + "%02d".format(index + 1)

  private fun render(
    categoryItems: List<MediaItem>? = null,
    results: List<MediaItem>? = null
  ) {
    val vm = StreamNovaViewModel()
    viewModel = vm
    if (categoryItems != null) vm.setSelectedSearchCategoryId(CATEGORY_ID)
    if (results != null) vm.updateSearchQuery(QUERY)
    showDetail = false
    rule.setContent {
      val stateHolder = rememberSaveableStateHolder()
      CompositionLocalProvider(
        LocalLastFocusedTarget provides vm.lastFocusedTarget.collectAsState().value,
        LocalLastFocusedMediaId provides vm.lastFocusedMediaId.collectAsState().value,
        LocalFocusRestoreToken provides vm.focusRestoreToken.collectAsState().value,
        LocalClearLastFocusedMedia provides { vm.clearLastFocusedMedia() }
      ) {
        if (showDetail) {
          // Il Dettaglio non è oggetto di questo test: conta solo che la Ricerca venga
          // SMONTATA (come fa il NavHost) e rimontata al ritorno.
          stateHolder.SaveableStateProvider("detail") {
            Box(modifier = Modifier.fillMaxSize()) { Text("DETTAGLIO") }
          }
        } else {
          stateHolder.SaveableStateProvider("search") {
            SearchScreen(
              viewModel = vm,
              onMediaClick = { showDetail = true },
              resultsOverride = results,
              categoryItemsOverride = categoryItems
            )
          }
        }
      }
    }
    rule.waitForIdle()
  }

  private fun focusedTitle(): String? = try {
    rule.onNode(isFocused()).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)
      ?.map { it.text }
      ?.firstOrNull { it.startsWith(TITLE_PREFIX) }
  } catch (_: Throwable) {
    null
  }

  /**
   * Testo del nodo focalizzato che NON è una card (es. un chip dei filtri). I chip non
   * fondono il testo dei discendenti, quindi si legge il testo del sottoalbero.
   */
  private fun focusedNonCardText(): String? = try {
    val node = rule.onNode(isFocused()).fetchSemanticsNode()
    val own = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    own ?: node.children.joinToString(" ") { child ->
      child.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }
  } catch (_: Throwable) {
    null
  }

  private fun bounds(title: String): Rect? = try {
    rule.onNode(isFocusable() and hasText(title, substring = true))
      .fetchSemanticsNode().boundsInRoot
  } catch (_: Throwable) {
    null
  }

  private fun gridBounds(): Rect =
    rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().first().boundsInRoot

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  /** Porta l'indice [index] in composizione e dà il focus alla sua card (come il D-pad). */
  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(index: Int) {
    if (bounds(title(index)) == null) {
      rule.onNode(hasScrollAction()).performScrollToIndex(index)
      rule.waitForIdle()
    }
    rule.onNode(isFocusable() and hasText(title(index), substring = true)).requestFocus()
    rule.waitForIdle()
    assertEquals(
      "Il focus non è sulla card '${title(index)}' ma su '${focusedTitle()}'",
      title(index),
      focusedTitle()
    )
  }

  /** Apre il Dettaglio con OK sulla card focalizzata (stesso percorso del telecomando). */
  @OptIn(ExperimentalTestApi::class)
  private fun openDetailFromFocusedCard() {
    rule.onNode(isFocused()).performClick()
    rule.waitForIdle()
  }

  /** BACK dal Dettaglio: la Ricerca viene rimontata e il token di ripristino avanza. */
  private fun returnFromDetail(restoreId: String) {
    showDetail = false
    viewModel.signalFocusRestore(restoreId)
    rule.waitForIdle()
  }

  private fun assertFocusedCard(index: Int) {
    val expected = title(index)
    rule.waitUntil(timeoutMillis = 5_000) { focusedTitle() == expected }
    assertEquals(
      "Al rientro dal Dettaglio il focus non è sulla card '$expected': " +
        "nodo focalizzato='${focusedNonCardText()}'",
      expected,
      focusedTitle()
    )
  }

  /** La card ripristinata è interamente dentro il viewport della griglia. */
  private fun assertCardVisible(index: Int) {
    val card = bounds(title(index))
      ?: throw AssertionError("La card '${title(index)}' non è composta al rientro")
    val grid = gridBounds()
    assertTrue(
      "La card '${title(index)}' è tagliata in alto: cardTop=${card.top}, gridTop=${grid.top}",
      card.top >= grid.top - 0.5f
    )
    assertTrue(
      "La card '${title(index)}' è tagliata in basso: cardBottom=${card.bottom}, " +
        "gridBottom=${grid.bottom}",
      card.bottom <= grid.bottom + 0.5f
    )
  }

  // ------------------------------------------------------ 1. card in prima colonna

  @Test
  fun clickSalvaIlTargetEIlBackRiportaIlFocusSullaCardNellaCategoria() {
    render(categoryItems = List(30) { item(it) })

    focusCard(0)
    openDetailFromFocusedCard()

    // Il click DEVE aver salvato il target di ripristino (identità della card).
    assertEquals(
      "Il click sulla card non ha salvato il target di ripristino",
      "01",
      viewModel.lastFocusedMediaId.value
    )

    returnFromDetail("01")
    assertFocusedCard(0)
    assertCardVisible(0)
  }

  // ------------------------------------------------------ 2. card non in prima colonna

  @Test
  fun backRiportaIlFocusSullaTerzaColonnaDellaCategoria() {
    render(categoryItems = List(30) { item(it) })

    // Terza card della prima riga.
    focusCard(2)
    openDetailFromFocusedCard()
    returnFromDetail("03")

    assertFocusedCard(2)
    assertCardVisible(2)
  }

  // ------------------------------------------------------ 3. scroll mantenuto

  @Test
  fun backMantieneLoScrollERiportaIlFocusSuUnaCardProfonda() {
    render(categoryItems = List(60) { item(it) })

    focusCard(23)
    openDetailFromFocusedCard()
    returnFromDetail("24")

    assertFocusedCard(23)
    assertCardVisible(23)
    assertNull(
      "Il ripristino ha riportato la griglia all'inizio: la card profonda non era visibile",
      bounds(title(0))
    )
  }

  // ------------------------------------------------------ 4. fallback valido

  @Test
  fun cardNonPiuDisponibileRipiegaSuUnaCardDellaStessaSezione() {
    var items by mutableStateOf(List(30) { item(it) })
    // Il catalogo cambia mentre il Dettaglio è aperto: la card aperta non esiste più.
    val withRemoval = items.filterNot { it.id == "04" }

    val vm = StreamNovaViewModel()
    viewModel = vm
    vm.setSelectedSearchCategoryId(CATEGORY_ID)
    rule.setContent {
      val stateHolder = rememberSaveableStateHolder()
      CompositionLocalProvider(
        LocalLastFocusedTarget provides vm.lastFocusedTarget.collectAsState().value,
        LocalLastFocusedMediaId provides vm.lastFocusedMediaId.collectAsState().value,
        LocalFocusRestoreToken provides vm.focusRestoreToken.collectAsState().value,
        LocalClearLastFocusedMedia provides { vm.clearLastFocusedMedia() }
      ) {
        if (showDetail) {
          stateHolder.SaveableStateProvider("detail") {
            Box(modifier = Modifier.fillMaxSize()) { Text("DETTAGLIO") }
          }
        } else {
          stateHolder.SaveableStateProvider("search") {
            SearchScreen(
              viewModel = vm,
              onMediaClick = { showDetail = true },
              categoryItemsOverride = items
            )
          }
        }
      }
    }
    rule.waitForIdle()

    focusCard(3)
    openDetailFromFocusedCard()
    items = withRemoval

    returnFromDetail("04")

    // Il target orfano viene rilasciato: nessun cancellettto di ripristino resta chiuso.
    rule.waitUntil(timeoutMillis = 5_000) { vm.lastFocusedMediaId.value == null }
    assertNull(
      "Il target di una card non più disponibile è rimasto pendente",
      vm.lastFocusedMediaId.value
    )

    // Il focus è su una CARD della stessa sezione, non sui chip Film/Serie TV.
    rule.waitUntil(timeoutMillis = 5_000) { focusedTitle() != null }
    val focused = focusedTitle()
    assertNotNull(
      "Dopo un ripristino fallito il focus non è su alcuna card " +
        "(nodo focalizzato='${focusedNonCardText()}')",
      focused
    )
    assertTrue(
      "Dopo un ripristino fallito il focus è finito fuori dalle card " +
        "(nodo focalizzato='${focusedNonCardText()}')",
      focused?.startsWith(TITLE_PREFIX) == true
    )

    // La sezione è ancora la categoria selezionata e la griglia resta navigabile.
    val before = focusedTitle()
    pressKey(Key.DirectionRight)
    assertTrue(
      "Il D-pad è morto dopo un ripristino fallito",
      focusedTitle() != null && focusedTitle() != before
    )
  }

  // ------------------------------------------------------ 5. ripristini consecutivi

  @Test
  fun ripristiniConsecutiviSullaStessaCard() {
    render(categoryItems = List(30) { item(it) })

    repeat(2) {
      focusCard(2)
      openDetailFromFocusedCard()
      returnFromDetail("03")
      assertFocusedCard(2)
      assertCardVisible(2)
    }
  }

  // ------------------------------------------------------ 6. griglia dei risultati

  @Test
  fun backRiportaIlFocusSullaCardDeiRisultatiDellaQuery() {
    render(results = List(30) { item(it) })

    focusCard(2)
    openDetailFromFocusedCard()
    assertEquals("Il click non ha salvato il target", "03", viewModel.lastFocusedMediaId.value)

    returnFromDetail("03")
    assertFocusedCard(2)
    assertCardVisible(2)
  }
}
