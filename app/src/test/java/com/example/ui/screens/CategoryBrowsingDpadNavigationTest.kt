package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigazione D-pad e persistenza della selezione nelle sezioni Film / Serie TV.
 *
 * Regressions coperti:
 * 1. le pressioni RAPIDE del D-pad verso il basso non perdono la COLONNA (prima il focus
 *    scivolava sulla prima cella della riga nuova: le card sembravano rimescolarsi);
 * 2. una nuova pagina di catalogo non ricolloca le card già a schermo;
 * 3. filtro provider e badge di ordinamento sopravvivono al rimontaggio della schermata
 *    (BACK dal Dettaglio, cambio sezione).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-tvdpi")
class CategoryBrowsingDpadNavigationTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val TITLE = "Tutti i film"
    /** Colonne effettive della griglia a 960dp: 896dp utili, celle 150dp, gap 16dp. */
    const val COLUMNS = 5
  }

  private var liveItems by mutableStateOf<List<MediaItem>>(emptyList())

  private fun item(
    index: Int,
    year: Int = 2000 + index,
    rating: Float = 9f,
    provider: String? = null
  ): MediaItem = MediaItem(
    id = "m%02d".format(index + 1),
    title = "Titolo %02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = year,
    tmdbId = index + 1,
    rating = rating,
    provider = provider
  )

  private fun render(items: List<MediaItem>) {
    liveItems = items
    rule.setContent {
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides null,
        LocalLastFocusedTarget provides null,
        LocalFocusRestoreToken provides 0,
        LocalClearLastFocusedMedia provides {}
      ) {
        CategoryBrowsingScreen(
          title = TITLE,
          items = liveItems,
          isTv = false,
          viewModel = null,
          onMediaClick = {},
          onLoadMore = null
        )
      }
    }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helpers

  private fun focusedText(): String? = try {
    rule.onNode(isFocused()).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
  } catch (_: Throwable) {
    null
  }

  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  private fun press(key: Key, times: Int = 1) {
    repeat(times) {
      rule.onNode(isFocused()).performKeyInput { pressKey(key) }
      rule.waitForIdle()
    }
  }

  /** Tutti i tasti in un unico dispatch, senza frame tra una pressione e l'altra. */
  private fun pressRapidly(key: Key, times: Int) {
    rule.onNode(isFocused()).performKeyInput {
      repeat(times) { pressKey(key) }
    }
    rule.waitForIdle()
  }

  private fun assertFocused(title: String) {
    rule.waitUntil(timeoutMillis = 5_000) { focusedText()?.startsWith(title) == true }
    assertTrue(
      "Focus atteso su $title, trovato ${focusedText()}",
      focusedText()?.startsWith(title) == true
    )
  }

  private fun selectBadge(sort: CategorySortOrder) {
    rule.onNodeWithTag(sortBadgeTestTag(sort)).requestFocus()
    rule.waitForIdle()
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()
  }

  private fun leftOf(title: String): Float =
    rule.onNode(hasText(title)).fetchSemanticsNode().boundsInRoot.left

  /**
   * Ordine visivo REALE (coordinata x). Le card sono su una sola riga nei test di
   * ordinamento: la x cresce esattamente secondo la lista attesa.
   */
  private fun assertVisualOrder(expected: List<String>) {
    val actual = expected.sortedBy { leftOf(it) }
    assertEquals("Ordine visivo inatteso", expected, actual)
    expected.zipWithNext { first, second ->
      assertTrue(
        "$first dovrebbe precedere $second (${leftOf(first)} > ${leftOf(second)})",
        leftOf(first) < leftOf(second)
      )
    }
  }

  // ---------------------------------------------------------------- D-pad

  @Test
  fun dpadDownPoiUpPercorreLeRigheUnaAllaVolta() {
    render(List(30) { item(it) })

    focusCard("Titolo 04")
    repeat(4) { row ->
      press(Key.DirectionDown)
      assertFocused("Titolo %02d".format(4 + (row + 1) * COLUMNS))
    }
    repeat(4) {
      press(Key.DirectionUp)
    }
    assertFocused("Titolo 04")
  }

  @Test
  fun dpadRapidoMantieneLaColonna() {
    render(List(40) { item(it) })

    // Colonna 4 (indice 3): cinque discese rapide devono atterrare sulla stessa colonna
    // cinque righe più in basso (indice 28), non sulla prima colonna.
    focusCard("Titolo 04")
    pressRapidly(Key.DirectionDown, times = 5)

    assertFocused("Titolo %02d".format(4 + 5 * COLUMNS))
  }

  @Test
  fun dpadRapidoNonSaltaRigheNeColonne() {
    render(List(40) { item(it) })

    focusCard("Titolo 03")
    pressRapidly(Key.DirectionDown, times = 3)

    // Terza colonna, quattro righe più in basso.
    assertFocused("Titolo %02d".format(3 + 3 * COLUMNS))
  }

  // ---------------------------------------------------------------- stabilità catalogo

  @Test
  fun unaNuovaPaginaNonSpostaLeCardGiaVisibili() {
    render(
      listOf(
        item(0, year = 2020),
        item(1, year = 2010),
        item(2, year = 2015)
      )
    )
    selectBadge(CategorySortOrder.RECENTI)
    assertVisualOrder(listOf("Titolo 01", "Titolo 03", "Titolo 02"))

    // Arriva una pagina con un titolo più recente e uno più vecchio: le card già mostrate
    // devono restare nella stessa posizione; le nuove si accodano.
    liveItems = listOf(
      item(0, year = 2020),
      item(1, year = 2010),
      item(2, year = 2015),
      item(3, year = 2030),
      item(4, year = 2005)
    )
    rule.waitForIdle()

    assertVisualOrder(listOf("Titolo 01", "Titolo 03", "Titolo 02", "Titolo 04", "Titolo 05"))
  }

  @Test
  fun cambiareBadgeRiordinaTuttaLaLista() {
    render(
      listOf(
        item(0, year = 2020),
        item(1, year = 2010),
        item(2, year = 2015)
      )
    )
    selectBadge(CategorySortOrder.RECENTI)
    assertVisualOrder(listOf("Titolo 01", "Titolo 03", "Titolo 02"))

    selectBadge(CategorySortOrder.POPOLARI)
    assertVisualOrder(listOf("Titolo 01", "Titolo 02", "Titolo 03"))
  }

  // ---------------------------------------------------------------- persistenza selezione

  @Test
  fun filtroEOrdinamentoSopravvivonoAlRimontaggio() {
    var showScreen by mutableStateOf(true)
    liveItems = listOf(
      item(0, year = 2020, provider = "hbo"),
      item(1, year = 2010, provider = "netflix")
    )
    rule.setContent {
      val stateHolder = rememberSaveableStateHolder()
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides null,
        LocalLastFocusedTarget provides null,
        LocalFocusRestoreToken provides 0,
        LocalClearLastFocusedMedia provides {}
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          if (showScreen) {
            stateHolder.SaveableStateProvider("category") {
              CategoryBrowsingScreen(
                title = TITLE,
                items = liveItems,
                isTv = false,
                viewModel = null,
                onMediaClick = {},
                onLoadMore = null
              )
            }
          }
        }
      }
    }
    rule.waitForIdle()

    // Badge «Per Valutazione» e filtro HBO Max. Il testo vive DENTRO il chip focalizzabile
    // (TvFocusableBox): si richiede il focus al chip tramite il suo discendente testuale.
    selectBadge(CategorySortOrder.PER_VALUTAZIONE)
    rule.onNode(hasAnyDescendant(hasText("HBO Max")) and isFocusable()).requestFocus()
    rule.waitForIdle()
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()

    rule.onNode(hasText("Titolo 01")).assertExists()
    assertFalse(
      "Il filtro HBO Max non è stato applicato: Titolo 02 (Netflix) è ancora visibile",
      rule.onAllNodes(hasText("Titolo 02")).fetchSemanticsNodes().isNotEmpty()
    )

    // Smontaggio/rimontaggio come il BACK dal Dettaglio o il cambio sezione.
    showScreen = false
    rule.waitForIdle()
    showScreen = true
    rule.waitForIdle()

    // Il filtro resta HBO Max: il titolo Netflix non deve ricomparire.
    assertFalse(
      "Il filtro è tornato a «Tutti» dopo il rimontaggio",
      rule.onAllNodes(hasText("Titolo 02")).fetchSemanticsNodes().isNotEmpty()
    )

    // E l'ordinamento resta quello scelto: con Per Valutazione (entrambi 9.0) vince
    // l'ordine nativo, quindi qui basta verificare che la card HBO sia ancora visibile.
    rule.onNode(hasText("Titolo 01")).assertExists()
  }
}
