package com.example.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Badge di ordinamento delle sezioni Film / Serie TV, esercitati come dal telecomando.
 *
 * Copre il contratto richiesto:
 * - i tre badge sono presenti e il D-pad li attiva;
 * - la griglia viene riordinata secondo il badge scelto;
 * - BACK da una card riporta il focus SUL BADGE SCELTO, così si può cambiare ordinamento
 *   senza attraversare la riga dei filtri provider.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class CategoryBrowsingSortBadgesTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val TITLE = "Tutti i film"
  }

  private fun item(id: String, title: String, year: Int, rating: Float) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = year,
    rating = rating
  )

  // Ordine nativo del catalogo volutamente NON allineato né all'anno né al voto.
  private val items = listOf(
    item("m1", "Vecchio", 2001, 9.5f),
    item("m2", "Nuovissimo", 2024, 6.0f),
    item("m3", "Medio", 2015, 8.0f)
  )

  private var outerBackInvocations = 0

  private fun render() {
    rule.setContent {
      // Sentinella: il BackHandler della schermata deve consumare il BACK quando la griglia
      // ha il focus. Se il focus non è nella griglia il BACK arriva qui (e non chiude
      // l'Activity del test, che altrimenti smonterebbe la composizione).
      androidx.activity.compose.BackHandler { outerBackInvocations++ }
      CategoryBrowsingScreen(
        title = TITLE,
        items = items,
        isTv = false,
        viewModel = null,
        onMediaClick = {},
        onLoadMore = null
      )
    }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helpers

  /**
   * true se il badge ha DAVVERO il focus. Non si usa il testo del nodo focalizzato: il
   * semantics del badge (testTag + focusable) non espone i Text figli nel nodo merged, quindi
   * un'asserzione sul testo darebbe un falso negativo anche con il focus corretto.
   */
  private fun isBadgeFocused(sort: CategorySortOrder): Boolean =
    try {
      rule.onNodeWithTag(sortBadgeTestTag(sort)).assertIsFocused()
      true
    } catch (_: AssertionError) {
      false
    } catch (_: IllegalStateException) {
      false
    }

  @OptIn(ExperimentalTestApi::class)
  private fun focusTitle(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  private fun pressDpad(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  /** Attiva un badge come farebbe il tasto OK del telecomando. */
  private fun selectBadge(sort: CategorySortOrder) {
    rule.onNodeWithTag(sortBadgeTestTag(sort)).requestFocus()
    rule.waitForIdle()
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()
  }

  /** BACK di sistema: attraversa l'OnBackPressedDispatcher (i BackHandler composti). */
  private fun pressSystemBack() {
    val composeRule = rule as? AndroidComposeTestRule<*, *> ?: return
    rule.runOnUiThread {
      (composeRule.activity as? androidx.activity.ComponentActivity)
        ?.onBackPressedDispatcher
        ?.onBackPressed()
    }
    rule.waitForIdle()
  }

  private fun leftOf(title: String): Float =
    rule.onNode(hasText(title)).fetchSemanticsNode().boundsInRoot.left

  /** Le card sono tutte sulla stessa riga: l'ordine visivo è dato dalla coordinata x. */
  private fun assertVisualOrder(expected: List<String>) {
    val actual = expected.sortedBy { leftOf(it) }
    assertEquals("Ordine visivo inatteso", expected, actual)
    // Controllo più forte: la x cresce esattamente secondo la lista attesa.
    expected.zipWithNext { first, second ->
      assertTrue(
        "$first dovrebbe precedere $second (${leftOf(first)} > ${leftOf(second)})",
        leftOf(first) < leftOf(second)
      )
    }
  }

  // ---------------------------------------------------------------- test

  @Test
  fun iTreBadgeSonoPresentiEPopolariEIlDefault() {
    render()

    rule.onNodeWithTag(sortBadgeTestTag(CategorySortOrder.RECENTI)).assertExists()
    rule.onNodeWithTag(sortBadgeTestTag(CategorySortOrder.PER_VALUTAZIONE)).assertExists()
    rule.onNodeWithTag(sortBadgeTestTag(CategorySortOrder.POPOLARI)).assertExists()

    // Default «Popolari»: ordine nativo del catalogo, nessun riordino all'apertura.
    assertVisualOrder(listOf("Vecchio", "Nuovissimo", "Medio"))
  }

  @Test
  fun ilBadgeRecentiRiordinaPerAnnoDecrescente() {
    render()

    selectBadge(CategorySortOrder.RECENTI)

    assertVisualOrder(listOf("Nuovissimo", "Medio", "Vecchio"))
  }

  @Test
  fun ilBadgePerValutazioneRiordinaPerVotoDecrescente() {
    render()

    selectBadge(CategorySortOrder.PER_VALUTAZIONE)

    assertVisualOrder(listOf("Vecchio", "Medio", "Nuovissimo"))
  }

  @Test
  fun tornareSuPopolariRipristinaLOrdineNativo() {
    render()

    selectBadge(CategorySortOrder.RECENTI)
    assertVisualOrder(listOf("Nuovissimo", "Medio", "Vecchio"))

    selectBadge(CategorySortOrder.POPOLARI)
    assertVisualOrder(listOf("Vecchio", "Nuovissimo", "Medio"))
  }

  @Test
  fun backDaUnaCardRiportaIlFocusSulBadgeScelto() {
    render()

    // Badge scelto: «Recenti». Poi si scende nella griglia come farebbe l'utente.
    selectBadge(CategorySortOrder.RECENTI)
    focusTitle("Medio")

    pressSystemBack()

    assertEquals("Il BACK non è stato consumato dalla schermata", 0, outerBackInvocations)
    rule.waitUntil(timeoutMillis = 5_000) { isBadgeFocused(CategorySortOrder.RECENTI) }

    // Dal badge scelto si può immediatamente passare a un altro ordinamento.
    pressDpad(Key.DirectionRight)
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()
    assertVisualOrder(listOf("Vecchio", "Medio", "Nuovissimo"))
  }

  @Test
  fun backDaUnaCardRiportaIlFocusSulBadgePopolariQuandoEIlDefault() {
    render()

    focusTitle("Nuovissimo")
    pressSystemBack()

    assertEquals("Il BACK non è stato consumato dalla schermata", 0, outerBackInvocations)
    rule.waitUntil(timeoutMillis = 5_000) { isBadgeFocused(CategorySortOrder.POPOLARI) }
  }
}

