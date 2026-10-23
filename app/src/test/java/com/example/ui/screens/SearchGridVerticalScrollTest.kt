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
 * Regressione dello SCROLL VERTICALE della griglia dei risultati nella schermata Cerca.
 *
 * Difetto segnalato su Android TV: scorrendo il catalogo con il D-pad le card in ingresso
 * restavano incollate al bordo inferiore dell'area risultati (e quelle con l'alone di focus
 * venivano tagliate), mentre le righe precedenti restavano visibili a meta'. La causa era lo
 * spec bring-into-view applicato alla griglia ([TvFocusBringIntoViewSpec]): agganciava la riga
 * focalizzata a un BORDO del viewport (fondo area risultati per il Giù, 64dp dall'alto per il
 * Su) e considerava "soddisfatta" la richiesta appena la riga era visibile, quindi non poteva
 * centrarla.
 *
 * Qui si verifica la geometria REALE (bounds dei nodi sotto Robolectric) dopo ogni pressione:
 * 1. la riga focalizzata e' completamente dentro il viewport della griglia (nessun taglio);
 * 2. la riga focalizzata e' centrata verticalmente (compatibilmente con il clamp ai bordi);
 * 3. la riga precedente e' uscita verso l'alto e non si sovrappone a quella focalizzata;
 * 4. un LEFT/RIGHT nella stessa riga non produce spostamenti verticali (niente sobbalzi);
 * 5. l'ultima riga e' interamente visibile grazie al contentPadding inferiore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class SearchGridVerticalScrollTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val RESULT_TITLE_PREFIX = "Risultato "
    const val FILTER_ALL_LABEL = "Tutti"
    const val FILTER_ALL_LABEL_EN = "All"
    const val RESULT_COUNT = 60
    /** Differenza ammessa tra centro della riga e centro del viewport (px). */
    const val CENTER_TOLERANCE_PX = 24f
    /** Spostamento verticale ammesso da un LEFT/RIGHT (px). */
    const val HORIZONTAL_JITTER_TOLERANCE_PX = 2f
  }

  private fun item(index: Int): MediaItem = MediaItem(
    id = "%02d".format(index + 1),
    title = RESULT_TITLE_PREFIX + "%02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1
  )

  private fun render(results: List<MediaItem>) {
    val vm = StreamNovaViewModel()
    vm.updateSearchQuery("dune")
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
          resultsOverride = results
        )
      }
    }
    rule.waitForIdle()
  }

  private fun filterMatcher() =
    hasAnyDescendant(hasText(FILTER_ALL_LABEL)) or
      hasAnyDescendant(hasText(FILTER_ALL_LABEL_EN))

  private fun focusFilter() {
    rule.onNode(isFocusable() and filterMatcher()).requestFocus()
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun focusedTitle(): String? = try {
    rule.onNode(isFocused()).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)
      ?.map { it.text }
      ?.firstOrNull { it.startsWith(RESULT_TITLE_PREFIX) }
  } catch (_: Throwable) {
    null
  }

  private fun cardBounds(title: String): Rect =
    rule.onNode(isFocusable() and hasText(title, substring = true))
      .fetchSemanticsNode().boundsInRoot

  private fun gridBounds(): Rect =
    rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().first().boundsInRoot

  private fun assertFocusedIs(title: String) {
    rule.waitUntil(timeoutMillis = 3_000) { focusedTitle() == title }
    assertTrue("Il focus non è su '$title' ma su '${focusedTitle()}'", focusedTitle() == title)
  }

  /** La riga focalizzata sta interamente dentro il viewport (nessun taglio ai bordi). */
  private fun assertFocusedRowNotClipped(title: String) {
    val grid = gridBounds()
    val card = cardBounds(title)
    assertTrue(
      "La card '$title' è tagliata in alto: cardTop=${card.top}, gridTop=${grid.top}",
      card.top >= grid.top - 0.5f
    )
    assertTrue(
      "La card '$title' è tagliata in basso: cardBottom=${card.bottom}, gridBottom=${grid.bottom}",
      card.bottom <= grid.bottom + 0.5f
    )
  }

  /** La riga focalizzata è centrata verticalmente nel viewport (entro la tolleranza). */
  private fun assertFocusedRowCentered(title: String) {
    val grid = gridBounds()
    val card = cardBounds(title)
    val cardCenter = (card.top + card.bottom) / 2f
    val gridCenter = (grid.top + grid.bottom) / 2f
    assertTrue(
      "La riga '$title' non è centrata: centroCard=$cardCenter, centroGriglia=$gridCenter " +
        "(differenza=${abs(cardCenter - gridCenter)}px)",
      abs(cardCenter - gridCenter) <= CENTER_TOLERANCE_PX
    )
  }

  @Test
  fun scorrendoVersoIlBassoOgniRigaArrivaCentrataECompleta() {
    render(List(RESULT_COUNT) { item(it) })

    focusFilter()
    pressKey(Key.DirectionDown)
    assertFocusedIs("${RESULT_TITLE_PREFIX}01")

    var previous: String? = null
    repeat(5) { step ->
      pressKey(Key.DirectionDown)
      val current = focusedTitle() ?: throw AssertionError("Nessuna card focalizzata al passo $step")
      assertTrue(
        "Il Giù non ha spostato il focus su una card dei risultati diversa ('$previous' -> '$current')",
        previous == null || current != previous
      )

      assertFocusedRowNotClipped(current)
      assertFocusedRowCentered(current)

      // La riga precedente è uscita verso l'alto e non si sovrappone a quella focalizzata.
      previous?.let { prev ->
        val prevBounds = cardBounds(prev)
        val currentBounds = cardBounds(current)
        assertTrue(
          "La riga precedente '$prev' (bottom=${prevBounds.bottom}) si sovrappone alla riga " +
            "'$current' (top=${currentBounds.top})",
          prevBounds.bottom <= currentBounds.top + 0.5f
        )
      }
      previous = current
    }
  }

  @Test
  fun rightNellaStessaRigaNonSpostaIlCatalogoInVerticale() {
    render(List(RESULT_COUNT) { item(it) })

    focusFilter()
    pressKey(Key.DirectionDown)
    pressKey(Key.DirectionDown)
    val first = focusedTitle() ?: throw AssertionError("Nessuna card focalizzata")
    val before = cardBounds(first)

    pressKey(Key.DirectionRight)
    val second = focusedTitle() ?: throw AssertionError("Nessuna card focalizzata dopo RIGHT")
    assertTrue("RIGHT non ha spostato il focus su un'altra card", second != first)
    val after = cardBounds(second)

    assertTrue(
      "RIGHT ha spostato il catalogo in verticale di ${abs(after.top - before.top)}px " +
        "('$first' top=${before.top} -> '$second' top=${after.top})",
      abs(after.top - before.top) <= HORIZONTAL_JITTER_TOLERANCE_PX
    )
    assertFocusedRowNotClipped(second)
  }

  @Test
  fun risalendoConIlSuLeRigheRestanoCompleteECentrate() {
    render(List(RESULT_COUNT) { item(it) })

    focusFilter()
    pressKey(Key.DirectionDown)
    repeat(4) { pressKey(Key.DirectionDown) }

    repeat(3) { step ->
      pressKey(Key.DirectionUp)
      val current = focusedTitle() ?: throw AssertionError("Nessuna card focalizzata in su $step")
      assertFocusedRowNotClipped(current)
      assertFocusedRowCentered(current)
    }

    // Fino in cima: la prima riga resta interamente visibile (allineata al bordo superiore).
    var steps = 0
    while (focusedTitle() != "${RESULT_TITLE_PREFIX}01" && steps < 8) {
      pressKey(Key.DirectionUp)
      steps++
    }
    assertFocusedIs("${RESULT_TITLE_PREFIX}01")
    assertFocusedRowNotClipped("${RESULT_TITLE_PREFIX}01")
  }

  @Test
  fun lUltimaRigaRestaLeggermenteSopraIlBordoGrazieAlPaddingFinale() {
    render(List(RESULT_COUNT) { item(it) })

    focusFilter()
    pressKey(Key.DirectionDown)
    // 9 righe da 7 colonne con 60 risultati: 8 Giù portano all'ultima riga.
    repeat(8) { pressKey(Key.DirectionDown) }

    val last = focusedTitle() ?: throw AssertionError("Nessuna card focalizzata")
    assertFocusedIs("${RESULT_TITLE_PREFIX}57")

    val grid = gridBounds()
    val card = cardBounds(last)
    assertTrue(
      "L'ultima riga '$last' è tagliata in basso: cardBottom=${card.bottom}, gridBottom=${grid.bottom}",
      card.bottom <= grid.bottom + 0.5f
    )
    assertTrue(
      "Manca il margine inferiore dell'ultima riga: distanza=${grid.bottom - card.bottom}px",
      grid.bottom - card.bottom >= 32f
    )
  }
}
