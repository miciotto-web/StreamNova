package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Categoria "Talk" della schermata Ricerca: griglia, D-pad e ripristino del focus.
 *
 * La nuova categoria riusa la griglia e le card esistenti: questo test la apre per
 * nome (`talk`) e verifica che il badge sia quello nuovo e che i comportamenti della
 * TV restino quelli delle altre categorie (D-pad con colonna mantenuta e focus
 * ripristinato sulla card al rientro dal Dettaglio).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-mdpi")
class SearchCategoryTalkUiTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val TITLE_PREFIX = "Talk "
    const val CATEGORY_ID = "talk"
    const val BADGE_LABEL = "TALK"
    const val COLUMN_TOLERANCE_PX = 1.0f
  }

  private lateinit var viewModel: StreamNovaViewModel
  private var showDetail by mutableStateOf(false)

  private fun item(index: Int): MediaItem = MediaItem(
    id = "%02d".format(index + 1),
    title = TITLE_PREFIX + "%02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = 2000 + index,
    tmdbId = 90_000 + index
  )

  private fun title(index: Int): String = TITLE_PREFIX + "%02d".format(index + 1)

  private fun render(items: List<MediaItem>) {
    val vm = StreamNovaViewModel()
    viewModel = vm
    vm.setSelectedSearchCategoryId(CATEGORY_ID)
    showDetail = false
    rule.setContent {
      CompositionLocalProvider(
        LocalLastFocusedTarget provides vm.lastFocusedTarget.collectAsState().value,
        LocalLastFocusedMediaId provides vm.lastFocusedMediaId.collectAsState().value,
        LocalFocusRestoreToken provides vm.focusRestoreToken.collectAsState().value,
        LocalClearLastFocusedMedia provides { vm.clearLastFocusedMedia() }
      ) {
        if (showDetail) {
          // Il Dettaglio non è oggetto del test: conta solo che la Ricerca venga
          // smontata e rimontata come fa il NavHost.
          Box(modifier = Modifier.fillMaxSize()) { Text("DETTAGLIO") }
        } else {
          SearchScreen(
            viewModel = vm,
            onMediaClick = { showDetail = true },
            categoryItemsOverride = items
          )
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

  private fun visibleCards(): List<Pair<String, Rect>> =
    rule.onAllNodes(isFocusable() and hasText(TITLE_PREFIX, substring = true))
      .fetchSemanticsNodes()
      .mapNotNull { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }
          ?: return@mapNotNull null
        val title = texts.firstOrNull { it.startsWith(TITLE_PREFIX) } ?: return@mapNotNull null
        title to node.boundsInRoot
      }

  private fun columnsCount(): Int {
    rule.waitUntil(timeoutMillis = 3_000) {
      visibleCards().map { Math.round(it.second.center.x) }.distinct().size >= 3
    }
    return visibleCards().map { Math.round(it.second.center.x) }.distinct().size
  }

  private fun bounds(title: String): Rect =
    rule.onNode(isFocusable() and hasText(title, substring = true))
      .fetchSemanticsNode().boundsInRoot

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(index: Int) {
    if (runCatching { bounds(title(index)) }.isFailure) {
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

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun assertFocusedCard(index: Int) {
    val expected = title(index)
    rule.waitUntil(timeoutMillis = 3_000) { focusedTitle() == expected }
    assertEquals(
      "Il focus non è su '$expected' ma su '${focusedTitle()}'",
      expected,
      focusedTitle()
    )
  }

  @Test
  fun badgeTalkEGrigliaConDpadInvariato() {
    render(List(30) { item(it) })

    val badgeNodes = rule.onAllNodes(hasText(BADGE_LABEL, substring = false))
      .fetchSemanticsNodes()
    assertTrue("Badge 'Talk' assente nella categoria", badgeNodes.isNotEmpty())

    val columns = columnsCount()
    assertTrue("Colonne non rilevate dal layout (columns=$columns)", columns >= 3)

    focusCard(0)
    pressKey(Key.DirectionRight)
    assertFocusedCard(1)

    val before = bounds(title(1))
    pressKey(Key.DirectionDown)
    assertFocusedCard(1 + columns)
    val after = bounds(title(1 + columns))
    assertTrue(
      "Giù ha cambiato colonna nella categoria Talk: prima '${title(1)}' " +
        "centerX=${before.center.x}, dopo '${title(1 + columns)}' centerX=${after.center.x}",
      abs(after.center.x - before.center.x) <= COLUMN_TOLERANCE_PX
    )
  }

  @Test
  fun backDalDettaglioRipristinaIlFocusSullaCardTalk() {
    render(List(30) { item(it) })

    focusCard(2)
    rule.onNode(isFocused()).performClick()
    rule.waitForIdle()
    assertTrue("Il Dettaglio non è stato aperto", showDetail)
    assertEquals(
      "Il click sulla card Talk non ha salvato il target di ripristino",
      "03",
      viewModel.lastFocusedMediaId.value
    )

    showDetail = false
    viewModel.signalFocusRestore("03")
    rule.waitForIdle()

    assertFocusedCard(2)
  }
}
