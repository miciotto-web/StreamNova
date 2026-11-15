package com.example.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressione dello SCROLL VERTICALE nella sezione Cerca → categoria (es. Azione e Avventura).
 *
 * Difetto segnalato su Android TV: con il D-pad verso il BASSO la griglia non scorreva, le
 * card della riga successiva comparivano di colpo in cima e quella precedente spariva nello
 * stesso frame (in salita invece lo scorrimento era regolare). La causa era il pre-scroll
 * secco di una riga intera in `moveGridFocusVertically`: la riga di destinazione finiva già
 * interamente visibile, quindi il bring-into-view dello spec restituiva 0f e non animava
 * nulla. Il pre-scroll ora rivela la riga di pochi pixel con la stessa animazione del
 * bring-into-view, che completa il movimento.
 *
 * Il qualifier usa un viewport più BASSO di una riga di card: è la geometria in cui la riga
 * successiva non è composta e scattava il vecchio salto secco (su un TV reale il rapporto tra
 * altezza del viewport e altezza della card dipende da densità e font scale).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h470dp-tvdpi")
class SearchCategoryVerticalScrollAnimationTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val TITLE_PREFIX = "Risultato "
    const val FIRST = "${TITLE_PREFIX}01"
    const val TARGET = "${TITLE_PREFIX}06"
  }

  private fun item(index: Int): MediaItem = MediaItem(
    id = "%02d".format(index + 1),
    title = TITLE_PREFIX + "%02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1
  )

  private fun render(items: List<MediaItem>) {
    val vm = StreamNovaViewModel()
    vm.setSelectedSearchCategoryId("azione")
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

  private fun cardBounds(title: String): Rect? = try {
    rule.onNode(isFocusable() and hasText(title, substring = true))
      .fetchSemanticsNode().boundsInRoot
  } catch (_: Throwable) {
    null
  }

  @Test
  fun giuVersoUnaRigaNonCompostaNonSaltaMaScorre() {
    render(List(60) { item(it) })
    rule.onNode(isFocusable() and hasText(FIRST, substring = true)).requestFocus()
    rule.waitForIdle()

    rule.mainClock.autoAdvance = false
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }

    // Al primo frame la vecchia riga deve essere ANCORA visibile: il vecchio scroll secco la
    // faceva sparire istantaneamente (bounds assenti già al frame 0).
    rule.mainClock.advanceTimeByFrame()
    assertNotNull(
      "La riga precedente è sparita nel primo frame: il Giù ha applicato un salto secco " +
        "invece di uno scroll animato",
      cardBounds(FIRST)
    )

    // Segue la card di destinazione: deve ENTRARE dal bordo inferiore e risalire per più
    // frame, non comparire già nella posizione finale.
    val targetTops = mutableListOf<Float>()
    repeat(40) {
      rule.mainClock.advanceTimeByFrame()
      if (focusedTitle() == TARGET) {
        cardBounds(TARGET)?.let { targetTops.add(it.top) }
      }
    }
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()

    assertTrue(
      "Il focus non è arrivato su '$TARGET' (trovato '${focusedTitle()}')",
      focusedTitle() == TARGET
    )
    val finalTop = cardBounds(TARGET)?.top
      ?: throw AssertionError("La card '$TARGET' non è visibile a scroll concluso")
    val distinctTops = targetTops.distinct()
    assertTrue(
      "La riga '$TARGET' è comparsa già ferma nella posizione finale: nessuna animazione " +
        "di scroll (posizioni campionate=$distinctTops)",
      distinctTops.size >= 3
    )
    assertTrue(
      "La riga '$TARGET' non è entrata dal basso: prima posizione=${distinctTops.first()}, " +
        "finale=$finalTop",
      distinctTops.first() > finalTop + 32f
    )
  }
}
