package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.TvFocusableBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressione del difetto segnalato: Hero -> GIU' -> righe della Home -> riga Top 10 Film, poi
 * risalita con SU: il focus deve tornare sulla riga sopra (provider) e poi sulla Hero, SENZA mai
 * finire sulla sidebar e senza restare "bloccato" sulla riga Top 10 Film.
 *
 * Perche' questo test esiste (causa dimostrata del difetto originale): lo step SINGOLO Su/Giù
 * lasciato alla ricerca geometrica del focus e' un FASCIO a due dimensioni. Quando la riga
 * sopra la Top 10 Film non e' composta (riga fuori dal viewport, oppure mai visitata) il
 * candidato geometricamente piu' vicino e' una VOCE DELLA SIDEBAR (in alto a sinistra): il SU
 * abbandonava il contenuto. Il modello attuale decide lo step con l'ordine ESPLICITO delle
 * righe ([HomeRowFocusCoordinator]) in [Modifier.homeVerticalStepKeys], quindi la destinazione
 * resta sempre dentro il contenuto.
 *
 * I tre test coprono: la sequenza richiesta dalla Hero, la risalita da una riga sottostante
 * (Top 10 Serie), e il caso patologico della riga sopra MAI visitata (la riga Top 10 Film
 * focalizzata direttamente: con la ricerca geometrica il SU finiva sulla sidebar).
 *
 * Harness fedele alla MainActivity: contenuto della Home con il binario della sidebar (padding
 * iniziale di 60dp) e sidebar composta DOPO il contenuto, con item focusabili alla stessa
 * geometria reale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class HomeHeroTop10FilmRiseFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val HERO_TITLE = "HERO 1"
    const val PROVIDERS_FIRST = "Netflix"
    const val TOP10_FILM_PREFIX = "T10F"
    const val TOP10_SERIE_PREFIX = "T10S"
    const val SIDEBAR_ITEMS = 5
    const val SIDEBAR_ITEM_TAG_PREFIX = "sidebar_item_"
    val SIDEBAR_WIDTH = 60.dp
  }

  // ---------------------------------------------------------------- fixture

  private fun media(
    id: String,
    title: String,
    type: MediaType = MediaType.FILM,
    year: Int
  ): MediaItem = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = type,
    year = year,
    tmdbId = id.hashCode()
  )

  private fun catalogs(): HomeCatalogs = HomeCatalogs(
    heroItems = listOf(media("hero-1", HERO_TITLE, year = 2099)),
    top10Movies = List(10) { index ->
      media("top10-film-${index + 1}", "$TOP10_FILM_PREFIX ${index + 1}", year = 2100)
    },
    top10Series = List(10) { index ->
      media("top10-serie-${index + 1}", "$TOP10_SERIE_PREFIX ${index + 1}", MediaType.SERIE_TV, year = 2101)
    },
    trendingMovies = List(12) { index ->
      media("trending-film-${index + 1}", "TRF ${index + 1}", year = 2001)
    },
    trendingSeries = List(12) { index ->
      media("trending-serie-${index + 1}", "TRS ${index + 1}", MediaType.SERIE_TV, year = 2002)
    }
  )

  @Composable
  private fun App(homeCatalogs: HomeCatalogs) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
      HomeMainBrowsingContent(
        allMedia = emptyList(),
        homeCatalogs = homeCatalogs,
        onMediaClick = {},
        onPlayClick = {},
        onRemoveFromContinueWatching = {},
        viewModel = null,
        modifier = Modifier
          .fillMaxSize()
          .padding(start = SIDEBAR_WIDTH)
      )
      SidebarStub()
    }
  }

  @Composable
  private fun BoxScope.SidebarStub() {
    Box(
      modifier = Modifier
        .align(Alignment.CenterStart)
        .fillMaxHeight()
        .width(SIDEBAR_WIDTH)
        .background(Color(0xFF101820))
    ) {
      Column(
        modifier = Modifier
          .fillMaxHeight()
          .padding(vertical = 24.dp, horizontal = 4.dp)
      ) {
        Spacer(modifier = Modifier.height(40.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          repeat(SIDEBAR_ITEMS) { index ->
            TvFocusableBox(
              modifier = Modifier
                .height(48.dp)
                .width(48.dp)
                .testTag("$SIDEBAR_ITEM_TAG_PREFIX$index")
            ) { }
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------- helper

  private fun renderHome(homeCatalogs: HomeCatalogs = catalogs()) {
    rule.setContent { App(homeCatalogs) }
    rule.waitForIdle()
  }

  /**
   * Nodo FOCALIZZABILE che espone [label] come testo o contentDescription (titolo della card o
   * nome del provider). Richiede l'azione `RequestFocus` per non chiedere il focus a un nodo di
   * solo testo (la Hero espone il titolo su una Text figlia del banner focalizzabile).
   */
  private fun focusableWithLabel(label: String): SemanticsMatcher =
    (hasText(label) or hasContentDescription(label)) and
      SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)

  @OptIn(ExperimentalTestApi::class)
  private fun focusContent(label: String) {
    val direct = rule.onAllNodes(focusableWithLabel(label))
    if (direct.fetchSemanticsNodes().isNotEmpty()) {
      direct.onFirst().requestFocus()
    } else {
      rule.onAllNodes(hasText(label) or hasContentDescription(label)).onFirst().requestFocus()
    }
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  /** Testi + contentDescription del nodo focalizzato (tree merged), per le asserzioni. */
  @OptIn(ExperimentalTestApi::class)
  private fun focusedLabels(): String =
    rule.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString(" | ") { node ->
      val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("/") { it.text }
      val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("/")
      "text=$text desc=$desc"
    }

  private fun focusedNodes(): List<SemanticsNode> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()

  /** Il primo testTag utile risalendo dal nodo (testTag e focus stanno su nodi diversi). */
  private fun testTagOf(node: SemanticsNode): String? =
    generateSequence(node) { it.parent }
      .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
      .firstOrNull()

  /**
   * Invariante del difetto: dopo OGNI passo esiste un nodo focalizzato, non appartiene alla
   * sidebar e i suoi bounds stanno a destra del binario della sidebar (dentro il contenuto).
   */
  private fun assertFocusInsideContentColumn(step: String) {
    val sidebarRightPx = with(rule.density) { SIDEBAR_WIDTH.toPx() }
    val nodes = focusedNodes()
    assertTrue("$step: nessun nodo focalizzato (D-pad senza focus)", nodes.isNotEmpty())
    nodes.forEach { node ->
      val tag = testTagOf(node)
      assertFalse(
        "$step: il focus è finito sulla SIDEBAR (tag=$tag, bounds=${node.boundsInRoot})",
        tag?.startsWith(SIDEBAR_ITEM_TAG_PREFIX) == true
      )
      assertTrue(
        "$step: il focus è uscito dal contenuto (tag=$tag, bounds=${node.boundsInRoot})",
        node.boundsInRoot.left >= sidebarRightPx - 1f
      )
    }
  }

  private fun assertFocusedLabelsContain(expected: String, step: String) {
    val labels = focusedLabels()
    assertTrue("$step: atteso '$expected' nel nodo focalizzato, trovato [$labels]", labels.contains(expected))
  }

  // ---------------------------------------------------------------- regressione

  /**
   * Sequenza richiesta: focus iniziale sulla Hero, GIU' fino alla riga Top 10 Film, poi SU fino
   * alla Hero. A ogni passo il focus deve restare nel contenuto e la destinazione deve essere la
   * riga sopra in ordine esplicito (provider), mai la sidebar.
   */
  @Test
  fun heroGiuRigheTop10FilmSuTornaAllaHero() {
    renderHome()

    focusContent(HERO_TITLE)
    assertFocusedLabelsContain(HERO_TITLE, "focus iniziale Hero")
    assertFocusInsideContentColumn("focus iniziale Hero")

    pressKey(Key.DirectionDown)
    assertFocusedLabelsContain(PROVIDERS_FIRST, "Hero --GIU'--> riga provider")
    assertFocusInsideContentColumn("Hero --GIU'--> riga provider")

    pressKey(Key.DirectionDown)
    assertFocusedLabelsContain("$TOP10_FILM_PREFIX 1", "riga provider --GIU'--> riga Top 10 Film")
    assertFocusInsideContentColumn("riga provider --GIU'--> riga Top 10 Film")

    // Difetto segnalato: qui il SU abbandonava la riga Top 10 Film (sidebar o focus perso).
    pressKey(Key.DirectionUp)
    assertFocusedLabelsContain(PROVIDERS_FIRST, "riga Top 10 Film --SU--> riga provider")
    assertFocusInsideContentColumn("riga Top 10 Film --SU--> riga provider")

    pressKey(Key.DirectionUp)
    assertFocusInsideContentColumn("riga provider --SU--> Hero")
    rule.onNode(hasText(HERO_TITLE)).assertIsFocused()
  }

  /**
   * Variante "tornando verso l'alto" da una riga SOTTOSTANTE: la risalita attraversa la riga
   * Top 10 Film e non deve fermarsi lì né deviare sulla sidebar.
   */
  @Test
  fun risalitaDallaTop10SerieAttraversaLaTop10FilmFinoAllaHero() {
    renderHome()

    focusContent(HERO_TITLE)
    pressKey(Key.DirectionDown)
    pressKey(Key.DirectionDown)
    assertFocusedLabelsContain("$TOP10_FILM_PREFIX 1", "riga Top 10 Film")

    pressKey(Key.DirectionDown)
    assertFocusedLabelsContain("$TOP10_SERIE_PREFIX 1", "riga Top 10 Serie")
    assertFocusInsideContentColumn("riga Top 10 Serie")

    pressKey(Key.DirectionUp)
    assertFocusedLabelsContain("$TOP10_FILM_PREFIX 1", "Top 10 Serie --SU--> Top 10 Film")
    assertFocusInsideContentColumn("Top 10 Serie --SU--> Top 10 Film")

    pressKey(Key.DirectionUp)
    assertFocusedLabelsContain(PROVIDERS_FIRST, "Top 10 Film --SU--> provider")
    assertFocusInsideContentColumn("Top 10 Film --SU--> provider")

    pressKey(Key.DirectionUp)
    assertFocusInsideContentColumn("provider --SU--> Hero")
    rule.onNode(hasText(HERO_TITLE)).assertIsFocused()
  }

  /**
   * Caso patologico alla radice del difetto: la riga Top 10 Film e' focalizzata senza che la
   * riga SOPRA (provider) sia mai stata visitata ne' composta dalla navigazione. Con la ricerca
   * geometrica il SU non trovava alcun candidato nel contenuto e atterrava sulla sidebar.
   */
  @Test
  fun suDallaTop10FilmMaiVisitataSopraRestaNelContenuto() {
    renderHome(catalogs().copy(heroItems = emptyList()))

    // La riga sopra (provider) non e' mai stata raggiunta: si entra direttamente nella Top 10.
    focusContent("$TOP10_FILM_PREFIX 1")
    assertFocusedLabelsContain("$TOP10_FILM_PREFIX 1", "ingresso diretto nella Top 10 Film")

    pressKey(Key.DirectionUp)
    assertFocusedLabelsContain(PROVIDERS_FIRST, "Top 10 Film --SU--> riga provider")
    assertFocusInsideContentColumn("Top 10 Film --SU--> riga provider")
  }
}
