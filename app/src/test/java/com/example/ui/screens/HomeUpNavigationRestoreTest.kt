package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.example.data.model.MediaType
import com.example.data.model.MediaItem
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.TvFocusableBox
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Riproduttore del difetto runtime HOME "risalita -> Dettaglio -> BACK -> focus fuori dal
 * contenuto". Contratto di MainActivity riprodotto integralmente:
 * - ViewModel REALE: `saveLastFocusedMedia`/`signalFocusRestore`/`homeScrollToTopEvent` vivi;
 * - `pendingContentFocusRestore` vive al livello StreamNovaApp (sopravvive al cambio
 *   destinazione), mentre `isInitialHomeFocusSet` è un `remember` NON saveable dentro la
 *   destination Browsing: al rimontaggio si azzera e l'effetto d'ingresso riesegue
 *   `requestHomeScrollToTop()` (scroll animato a 0 + focus sull'Hero) IN CORSA col ripristino;
 * - browsing e Dettaglio su SaveableStateProvider separati (smontaggio/rimontaggio al BACK);
 * - il Dettaglio ha un nodo realmente focalizzato (focus repair al pop);
 * - il click salva il target ROW-SCOPED con la catena reale `handleRowMediaClick`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class HomeUpNavigationRestoreTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_TRENDING_FILM = "section_trending_film"
    const val ROW_TRENDING_SERIE = "section_trending_serie"
    const val YEAR_TRENDING_FILM = 2001
    const val YEAR_TRENDING_SERIE = 2002
    const val CARDS_PER_ROW = 12
  }

  private fun film(row: String, index: Int): MediaItem = MediaItem(
    id = "$row-${index + 1}",
    title = if (row == ROW_TRENDING_FILM) "TF ${index + 1}" else "TS ${index + 1}",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = if (row == ROW_TRENDING_FILM) YEAR_TRENDING_FILM else YEAR_TRENDING_SERIE,
    tmdbId = index + 1
  )

  private fun catalogs(): HomeCatalogs = HomeCatalogs(
    heroItems = listOf(
      MediaItem(
        id = "hero-0",
        title = "HERO 1",
        synopsis = "",
        videoUrl = "",
        type = MediaType.FILM,
        year = 2099,
        tmdbId = 900
      )
    ),
    trendingMovies = List(CARDS_PER_ROW) { film(ROW_TRENDING_FILM, it) },
    trendingSeries = List(CARDS_PER_ROW) { film(ROW_TRENDING_SERIE, it) }
  )

  // ---------------------------------------------------------------- stato app
  // Livello "StreamNovaApp" del test (sopravvive ai cambi di destinazione).

  private lateinit var viewModel: StreamNovaViewModel
  private var showDetail by mutableStateOf(false)
  private var pendingContentFocusRestore by mutableStateOf(false)

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
            // Stato della destination Browsing: rememberSaveable come nella MainActivity
            // corretta: "focus iniziale post-splash" una tantum; al rientro dal Dettaglio
            // NON si riesegue requestHomeScrollToTop e si emette solo il segnale di restore.
            var isInitialHomeFocusSet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

            // Effetto d'ingresso identico a MainActivity
            // LaunchedEffect(screenState, isSplashActive, pendingContentFocusRestore, ...):
            LaunchedEffect(pendingContentFocusRestore, isInitialHomeFocusSet) {
              if (!isInitialHomeFocusSet) {
                isInitialHomeFocusSet = true
                viewModel.requestHomeScrollToTop()
              } else if (pendingContentFocusRestore) {
                pendingContentFocusRestore = false
                if (viewModel.lastFocusedMediaId.value != null) {
                  viewModel.signalFocusRestore(viewModel.lastFocusedMediaId.value!!)
                }
              }
            }

            Box(
              modifier = Modifier
                .fillMaxSize()
                .padding(start = 60.dp)
            ) {
              HomeMainBrowsingContent(
                allMedia = emptyList(),
                homeCatalogs = catalogs(),
                onMediaClick = { _ ->
                  // Come MainActivity: il salvataggio row-scoped avviene in handleRowMediaClick;
                  // la navigazione qui porta al Dettaglio.
                  showDetail = true
                },
                onPlayClick = {},
                onRemoveFromContinueWatching = {},
                viewModel = viewModel
              )
            }

            // Sidebar: composizione DOPO il contenuto, item sempre focalizzabili mentre la
            // sezione corrente è HOME (canFocus = isSidebarExpanded || section != CERCA).
            Column(
              modifier = Modifier
                .align(Alignment.CenterStart)
                .width(60.dp)
                .fillMaxSize()
                .background(Color(0xFF101820))
            ) {
              TvFocusableBox(
                modifier = Modifier
                  .size(width = 48.dp, height = 48.dp)
                  .background(Color(0xFF26405E)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
              ) { }
            }
          }
        }
      }
    }
  }

  /** Sostituto del Dettaglio: schermo intero con un nodo realmente focalizzato all'ingresso. */
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
    viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    rule.setContent { App() }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helpers

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused()).fetchSemanticsNodes().flatMap { node ->
      node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
    }

  private fun focusedTitles(): String = focusedTexts().joinToString(" | ")

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun clickFocusedCard() {
    rule.onNode(isFocused()).performClick()
    rule.waitForIdle()
  }

  /** BACK dal Dettaglio: remount del browsing + eventi nell'ordine di MainActivity. */
  @OptIn(ExperimentalTestApi::class)
  private fun backFromDetail() {
    // DetailScreen BackHandler -> viewModel.popDetail() (stack esaurito)
    // -> pendingContentFocusRestore = true -> navController.popBackStack().
    pendingContentFocusRestore = true
    showDetail = false
    rule.waitForIdle()
  }

  private fun assertFocusedCard(title: String, year: Int) {
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedTexts().let { it.contains(title) && it.contains(year.toString()) }
    }
    assertTrue(
      "Focus su [${focusedTitles()}] invece che su $title ($year)",
      focusedTexts().contains(title) && focusedTexts().contains(year.toString())
    )
  }

  // ---------------------------------------------------------------- test

  /**
   * CONTROLLO (discesa): TF1 -> GIU' su TS1 (riga raggiunta scendendo), Dettaglio e BACK:
   * il focus deve tornare su TS1. A runtime questo percorso funziona.
   */
  @Test
  fun ritornoDopoDiscesaRipristinaLaCard() {
    render()
    focusCard("TF 1")
    pressKey(Key.DirectionDown)
    assertFocusedCard("TS 1", YEAR_TRENDING_SERIE)

    clickFocusedCard()
    backFromDetail()

    assertFocusedCard("TS 1", YEAR_TRENDING_SERIE)
  }

  /**
   * DIFETTO (risalita): TF1 -> GIU' su TS1 -> SU' su TF1 (riga raggiunta salendo), Dettaglio
   * e BACK: il focus deve tornare su TF1 e NON finire su sidebar/hero/altre righe.
   */
  @Test
  fun ritornoDopoRisalitaRipristinaLaCard() {
    render()
    focusCard("TF 1")
    pressKey(Key.DirectionDown)
    assertFocusedCard("TS 1", YEAR_TRENDING_SERIE)

    pressKey(Key.DirectionUp)
    assertFocusedCard("TF 1", YEAR_TRENDING_FILM)

    clickFocusedCard()
    backFromDetail()

    assertFocusedCard("TF 1", YEAR_TRENDING_FILM)
  }
}
