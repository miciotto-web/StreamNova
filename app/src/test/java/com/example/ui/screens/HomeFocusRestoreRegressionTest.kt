package com.example.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressione del ripristino del focus della Home ([HomeMainBrowsingContent]), già funzionante
 * dopo il porting NuvioTV: il ritorno dal Dettaglio deve continuare ad atterrare sulla card
 * salvata, nella riga di origine (target ROW-SCOPED), anche con ripristini consecutivi su
 * card e righe diverse.
 *
 * Il comportamento della Home NON deve cambiare con le correzioni di Film/Serie TV e Provider:
 * questo test lo mette sotto protezione insieme a quelli già presenti in
 * [HomeFocusNavigationUiTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class HomeFocusRestoreRegressionTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_TRENDING = "section_trending_film"
    const val ROW_POPULAR = "section_popolari_film"
    const val YEAR_TRENDING = 2001
    const val YEAR_POPULAR = 2003
    const val CARDS_PER_ROW = 12
  }

  private fun film(rowKey: String, index: Int): MediaItem = MediaItem(
    id = "$rowKey-${index + 1}",
    title = "Card ${index + 1}",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = if (rowKey == ROW_TRENDING) YEAR_TRENDING else YEAR_POPULAR,
    tmdbId = index + 1
  )

  private fun catalogs(): HomeCatalogs = HomeCatalogs(
    trendingMovies = List(CARDS_PER_ROW) { film(ROW_TRENDING, it) },
    popularMovies = List(CARDS_PER_ROW) { film(ROW_POPULAR, it) }
  )

  // ------------------------------------------------------------- harness

  private var restoreTarget by mutableStateOf<Pair<String, String>?>(null)
  private var restoreToken by mutableStateOf(0)

  private fun render() {
    rule.setContent {
      // Stesso contratto di MainActivity al ritorno dal Dettaglio.
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides restoreTarget?.second,
        LocalLastFocusedTarget provides restoreTarget,
        LocalFocusRestoreToken provides restoreToken,
        LocalClearLastFocusedMedia provides { restoreTarget = null }
      ) {
        HomeMainBrowsingContent(
          allMedia = emptyList(),
          homeCatalogs = catalogs(),
          onMediaClick = {},
          onPlayClick = {},
          onRemoveFromContinueWatching = {},
          viewModel = null
        )
      }
    }
    rule.waitForIdle()
  }

  private fun openDetailAndReturn(rowKey: String, mediaId: String) {
    // Come il click di una card Home (handleRowMediaClick salva la coppia row-scoped) e come
    // il BACK dal Dettaglio (signalFocusRestore avanza il token).
    restoreTarget = rowKey to mediaId
    restoreToken += 1
    rule.waitForIdle()
  }

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused()).fetchSemanticsNodes().flatMap { node ->
      node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
    }

  private fun assertFocusedCard(title: String, year: Int) {
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedTexts().let { it.contains(title) && it.contains(year.toString()) }
    }
    val texts = focusedTexts()
    assertTrue(
      "Focus su $texts invece che su $title ($year)",
      texts.contains(title) && texts.contains(year.toString())
    )
  }

  // ------------------------------------------------------------- 5. regressione Home

  @Test
  fun ripristinoHomeSullaCardDellaRigaDiOrigine() {
    render()

    // Lo stesso indice esiste in entrambe le righe: vince la riga salvata nel target.
    openDetailAndReturn(ROW_TRENDING, "$ROW_TRENDING-3")
    assertFocusedCard("Card 3", YEAR_TRENDING)

    // Rientro su un'altra card di un'altra riga.
    openDetailAndReturn(ROW_POPULAR, "$ROW_POPULAR-4")
    assertFocusedCard("Card 4", YEAR_POPULAR)

    // Terzo ripristino consecutivo: ancora sulla riga di origine.
    openDetailAndReturn(ROW_TRENDING, "$ROW_TRENDING-2")
    assertFocusedCard("Card 2", YEAR_TRENDING)
  }
}
