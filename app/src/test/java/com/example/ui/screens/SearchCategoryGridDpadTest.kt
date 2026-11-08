package com.example.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Rect
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
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigazione D-pad VERTICALE nella griglia di una categoria della schermata Ricerca.
 *
 * Difetto coperto (segnalato su Android TV): con il focus sulla terza card della prima riga,
 * il D-pad Giù riportava il focus sulla PRIMA colonna della riga successiva.
 *
 * Causa: con la geometria reale di un TV 1080p (960x540dp) il viewport della griglia è alto
 * quanto UNA riga, quindi la riga successiva non è composta. La ricerca del focus di Compose,
 * non trovando candidati nella sottostruttura composta, scorre la griglia e consegna il focus
 * alla PRIMA cella della riga appena composta. La schermata ora calcola esplicitamente la
 * cella di destinazione (riga/colonna reali dal layout) e le chiede il focus.
 *
 * La verifica usa la geometria REALE dei nodi sotto Robolectric: la card raggiunta deve
 * mantenere la colonna (stesso centro orizzontale) e appartenere alla riga successiva.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-mdpi")
class SearchCategoryGridDpadTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val TITLE_PREFIX = "Risultato "
    const val CATEGORY_ID = "horror"
    const val FILTER_ALL_LABEL = "Tutti"
    const val FILTER_ALL_LABEL_EN = "All"
    /** Tolleranza subpixel: le colonne hanno coordinate x fisse. */
    const val COLUMN_TOLERANCE_PX = 1.0f
  }

  private lateinit var viewModel: StreamNovaViewModel

  private fun item(index: Int): MediaItem = MediaItem(
    id = "%02d".format(index + 1),
    title = TITLE_PREFIX + "%02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1
  )

  /** Titolo della card all'indice [index] della lista (0-based). */
  private fun title(index: Int): String = TITLE_PREFIX + "%02d".format(index + 1)

  private fun render(items: List<MediaItem>) {
    val vm = StreamNovaViewModel()
    viewModel = vm
    vm.setSelectedSearchCategoryId(CATEGORY_ID)
    rule.setContent {
      CompositionLocalProvider(
        LocalLastFocusedTarget provides vm.lastFocusedTarget.collectAsState().value,
        LocalLastFocusedMediaId provides vm.lastFocusedMediaId.collectAsState().value,
        LocalFocusRestoreToken provides vm.focusRestoreToken.collectAsState().value,
        LocalClearLastFocusedMedia provides { vm.clearLastFocusedMedia() }
      ) {
        SearchScreen(
          viewModel = vm,
          onMediaClick = {},
          categoryItemsOverride = items
        )
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

  private fun bounds(title: String): Rect =
    rule.onNode(isFocusable() and hasText(title, substring = true))
      .fetchSemanticsNode().boundsInRoot

  private fun gridBounds(): Rect =
    rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().first().boundsInRoot

  /** Card realmente composte nel viewport, in ordine di lettura della griglia. */
  private fun visibleCards(): List<Pair<String, Rect>> =
    rule.onAllNodes(isFocusable() and hasText(TITLE_PREFIX, substring = true))
      .fetchSemanticsNodes()
      .mapNotNull { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }
          ?: return@mapNotNull null
        val title = texts.firstOrNull { it.startsWith(TITLE_PREFIX) } ?: return@mapNotNull null
        title to node.boundsInRoot
      }

  /**
   * Colonne della griglia = colonne distinte occupate dalle card visibili. A questa
   * geometria una riga piena occupa l'intera larghezza, quindi il conteggio è esatto.
   */
  private fun columnsCount(): Int {
    rule.waitUntil(timeoutMillis = 3_000) {
      visibleCards().map { Math.round(it.second.center.x) }.distinct().size >= 3
    }
    return visibleCards().map { Math.round(it.second.center.x) }.distinct().size
  }

  @OptIn(ExperimentalTestApi::class)
  private fun scrollToIndex(index: Int) {
    rule.onNode(hasScrollAction()).performScrollToIndex(index)
    rule.waitForIdle()
  }

  private fun focusCard(index: Int) {
    focusCard(title(index))
  }

  private fun focusCard(title: String) {
    rule.onNode(isFocusable() and hasText(title, substring = true)).requestFocus()
    rule.waitForIdle()
    assertTrue(
      "Il focus non è su '$title' ma su '${focusedTitle()}'",
      focusedTitle() == title
    )
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun assertFocusedCard(index: Int) {
    val expected = title(index)
    rule.waitUntil(timeoutMillis = 3_000) { focusedTitle() == expected }
    assertTrue(
      "Il focus non è su '$expected' ma su '${focusedTitle()}'",
      focusedTitle() == expected
    )
  }

  /** La card focalizzata è interamente dentro il viewport della griglia. */
  private fun assertFocusedCardVisible() {
    val focused = focusedTitle() ?: throw AssertionError("Nessuna card focalizzata")
    val grid = gridBounds()
    val card = bounds(focused)
    assertTrue(
      "La card '$focused' è tagliata in alto: cardTop=${card.top}, gridTop=${grid.top}",
      card.top >= grid.top - 0.5f
    )
    assertTrue(
      "La card '$focused' è tagliata in basso: cardBottom=${card.bottom}, gridBottom=${grid.bottom}",
      card.bottom <= grid.bottom + 0.5f
    )
  }

  /**
   * Dalla prima card a destra fino alla terza, poi Giù: la destinazione è la TERZA colonna
   * della riga successiva, non la prima. Il movimento si ripete su più righe e viene
   * verificato che Destra/Sinistra e Su restino coerenti.
   */
  @Test
  fun giuDallaTerzaCardMantieneLaColonnaSuPiuRighe() {
    render(List(30) { item(it) })
    val columns = columnsCount()
    assertTrue("Colonne non rilevate dal layout (columns=$columns)", columns >= 3)

    focusCard(0)
    pressKey(Key.DirectionRight)
    pressKey(Key.DirectionRight)
    assertFocusedCard(2)

    // Giù: riga successiva, STESSA colonna.
    val before = bounds(title(2))
    pressKey(Key.DirectionDown)
    assertFocusedCard(2 + columns)
    val after = bounds(title(2 + columns))
    assertTrue(
      "Giù ha cambiato colonna: prima '${title(2)}' centerX=${before.center.x}, " +
        "dopo '${title(2 + columns)}' centerX=${after.center.x}",
      abs(after.center.x - before.center.x) <= COLUMN_TOLERANCE_PX
    )
    assertFocusedCardVisible()

    // Seconda riga consecutiva: la colonna resta la stessa.
    pressKey(Key.DirectionDown)
    assertFocusedCard(2 + 2 * columns)
    assertTrue(
      "La colonna non è rimasta la stessa alla seconda riga",
      abs(bounds(title(2 + 2 * columns)).center.x - before.center.x) <= COLUMN_TOLERANCE_PX
    )
    assertFocusedCardVisible()

    // Destra nella stessa riga: colonna +1, poi Giù mantiene la nuova colonna.
    pressKey(Key.DirectionRight)
    assertFocusedCard(3 + 2 * columns)
    val beforeRight = bounds(title(3 + 2 * columns))
    pressKey(Key.DirectionDown)
    assertFocusedCard(3 + 3 * columns)
    assertTrue(
      "Dopo Destra, Giù non ha mantenuto la nuova colonna",
      abs(bounds(title(3 + 3 * columns)).center.x - beforeRight.center.x) <=
        COLUMN_TOLERANCE_PX
    )
    assertFocusedCardVisible()

    // Su torna alla riga precedente sulla stessa colonna.
    pressKey(Key.DirectionUp)
    assertFocusedCard(3 + 2 * columns)
    // Sinistra torna alla colonna precedente senza cambiare riga.
    pressKey(Key.DirectionLeft)
    assertFocusedCard(2 + 2 * columns)
    assertFocusedCardVisible()
  }

  /**
   * Ultima riga con MENO card: si sceglie la card valida più vicina alla colonna corrente,
   * non la prima colonna.
   */
  @Test
  fun giuVersoRigaFinaleCortaSceglieLaColonnaPiuVicina() {
    // 17 card, 5 colonne a questa geometria: ultima riga con 2 card (indici 15 e 16).
    render(List(17) { item(it) })
    val columns = columnsCount()
    assertTrue(
      "Geometria del test inattesa: columns=$columns (attese 5)",
      columns == 5
    )

    scrollToIndex(12)
    focusCard(12)
    assertFocusedCard(12)
    // Colonne adiacenti della riga corrente: servono a esprimere la colonna di
    // destinazione ATTESA in coordinate, senza dipendere dallo stato di composizione
    // della riga corta subito dopo lo scroll.
    val columnTwoX = bounds(title(12)).center.x
    val columnPitch = columnTwoX - bounds(title(11)).center.x

    pressKey(Key.DirectionDown)
    // Colonna 2 richiesta, l'ultima riga ne ha 2 (colonne 0 e 1): si sceglie la più vicina,
    // cioè la colonna 1, non la prima colonna.
    assertFocusedCard(16)
    assertFocusedCardVisible()
    assertTrue(
      "La card raggiunta non è sulla colonna più vicina alla corrente: " +
        "attesa x=${columnTwoX - columnPitch}, reale x=${bounds(title(16)).center.x}",
      abs(bounds(title(16)).center.x - (columnTwoX - columnPitch)) <= COLUMN_TOLERANCE_PX
    )

    // Su dalla riga corta torna alla riga piena sulla colonna più vicina.
    pressKey(Key.DirectionUp)
    assertFocusedCard(11)
    assertFocusedCardVisible()
  }

  /** Su dalla prima riga esce dalla griglia verso i filtri (nessuna intercettazione). */
  @Test
  fun suDallaPrimaRigaArrivaAiFiltri() {
    render(List(30) { item(it) })
    focusCard(0)
    pressKey(Key.DirectionUp)

    rule.waitUntil(timeoutMillis = 3_000) {
      try {
        rule.onNode(
          isFocused() and (hasAnyDescendant(hasText(FILTER_ALL_LABEL)) or
            hasAnyDescendant(hasText(FILTER_ALL_LABEL_EN)))
        ).fetchSemanticsNode()
        true
      } catch (_: Throwable) {
        false
      }
    }
  }
}
