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
 * Regressione Home: lo step verticale SU/GIÙ dentro le righe non deve MAI uscire dal contenuto.
 *
 * Bug: dalla prima card della riga Top 10 Film il SU abbandonava la riga per finire sulla sidebar
 * (la ricerca focus a due dimensioni di Compose è un fascio GEOMETRICO: la sidebar, in alto a
 * sinistra, è un candidato più "vicino" della riga tagliata dal bordo) e spesso lasciava il cursore
 * su un nodo non focalizzabile → D-pad senza focus.
 *
 * Fix verificato qui: `.homeVerticalStepKeys` risolve la prima pressione SU/GIÙ con l'ordine
 * ESPLICITO delle righe del coordinatore, quindi la destinazione è sempre la riga sopra/sotto nel
 * contenuto (Hero compreso) e mai la sidebar.
 *
 * Harness fedele alla MainActivity: contenuto Home con padding iniziale di 60dp (il binario della
 * sidebar) e sidebar composta DOPO il contenuto, con la geometria reale (item da 48dp in cima,
 * sotto header e spacer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class HomeTop10UpNavigationTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val HERO_TITLE = "HERO 1"
    const val TOP10_PREFIX = "T10"
    const val TRENDING_PREFIX = "TF"
    const val PROVIDERS_FIRST = "Netflix"
    const val SIDEBAR_ITEMS = 5
    const val SIDEBAR_ITEM_TAG_PREFIX = "sidebar_item_"
    val SIDEBAR_WIDTH = 60.dp
  }

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
      media("top10-film-${index + 1}", "$TOP10_PREFIX ${index + 1}", year = 2100)
    },
    top10Series = List(10) { index ->
      media("top10-serie-${index + 1}", "T10S ${index + 1}", MediaType.SERIE_TV, year = 2101)
    },
    trendingMovies = List(12) { index ->
      media("trending-film-${index + 1}", "$TRENDING_PREFIX ${index + 1}", year = 2001)
    },
    trendingSeries = List(12) { index ->
      media("trending-serie-${index + 1}", "TS ${index + 1}", MediaType.SERIE_TV, year = 2002)
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

  /** Geometria della sidebar reale: 60dp clipati, item 48dp a partire da y = 24 + 40 + 24. */
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
   * Nodo FOCALIZZABILE che espone [label] come testo o contentDescription (titolo card, nome
   * provider). Richiede l'azione `RequestFocus` per non chiedere il focus a un nodo di solo testo
   * (l'Hero espone il titolo su una Text figlia del banner focalizzabile).
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

  /** Il primo testTag utile risalendo dal nodo (testTag e focus sono su nodi diversi se non merged). */
  private fun testTagOf(node: SemanticsNode): String? =
    generateSequence(node) { it.parent }
      .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
      .firstOrNull()

  /**
   * Il focus DEVE restare nella colonna del contenuto: esiste un nodo focalizzato, non è un item
   * della sidebar e i suoi bounds sono a destra del binario della sidebar.
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
   * Percorso reale del bug: Hero -> GIU' -> riga provider -> GIU' -> Top 10 Film; da qui il SU
   * deve tornare alla riga SOPRA (provider) e il SU successivo all'Hero, mai alla sidebar.
   */
  @Test
  fun heroGiuTop10FilmSuRisaleLeRigheFinoAllHero() {
    renderHome()
    focusContent(HERO_TITLE)
    assertFocusedLabelsContain(HERO_TITLE, "focus iniziale")

    pressKey(Key.DirectionDown)
    assertFocusInsideContentColumn("Hero --GIU'--> riga provider")
    assertFocusedLabelsContain(PROVIDERS_FIRST, "Hero --GIU'--> riga provider")

    pressKey(Key.DirectionDown)
    assertFocusInsideContentColumn("riga provider --GIU'--> Top 10 Film")
    assertFocusedLabelsContain("$TOP10_PREFIX 1", "riga provider --GIU'--> Top 10 Film")

    // Il bug: qui il SU saltava sulla sidebar / perdeva il focus.
    pressKey(Key.DirectionUp)
    assertFocusInsideContentColumn("Top 10 Film --SU--> riga sopra")
    assertFocusedLabelsContain(PROVIDERS_FIRST, "Top 10 Film --SU--> riga sopra")

    pressKey(Key.DirectionUp)
    assertFocusInsideContentColumn("riga provider --SU--> Hero")
    rule.onNode(hasText(HERO_TITLE)).assertIsFocused()
  }

  /**
   * Caso patologico del fascio geometrico: senza Hero la riga sopra la Top 10 è la riga provider,
   * quindi il SU non deve comunque fuggire sulla sidebar.
   */
  @Test
  fun top10FilmSuSenzaHeroRestaNelContenuto() {
    renderHome(catalogs().copy(heroItems = emptyList()))
    focusContent(PROVIDERS_FIRST)
    assertFocusedLabelsContain(PROVIDERS_FIRST, "focus iniziale provider")

    pressKey(Key.DirectionDown)
    assertFocusedLabelsContain("$TOP10_PREFIX 1", "riga provider --GIU'--> Top 10 Film")

    pressKey(Key.DirectionUp)
    assertFocusInsideContentColumn("Top 10 Film --SU--> riga sopra (senza Hero)")
    assertFocusedLabelsContain(PROVIDERS_FIRST, "Top 10 Film --SU--> riga sopra (senza Hero)")
  }

  /**
   * La colonna della card va ricordata anche risalendo: dopo SX (card 2) il SU atterra sulla card
   * 2 della riga sopra, non sulla 1 e non fuori dal contenuto.
   */
  @Test
  fun suConservaLaColonnaNellaRigaSopra() {
    renderHome()
    focusContent(HERO_TITLE)
    pressKey(Key.DirectionDown)
    pressKey(Key.DirectionDown)
    assertFocusedLabelsContain("$TOP10_PREFIX 1", "Top 10 Film card 1")

    pressKey(Key.DirectionRight)
    assertFocusedLabelsContain("$TOP10_PREFIX 2", "Top 10 Film card 2")

    pressKey(Key.DirectionUp)
    assertFocusInsideContentColumn("Top 10 Film card 2 --SU--> riga sopra card 2")
    // La riga provider ha una card per provider, nello stesso ordine di ProviderConstants.ALL
    // (Netflix=0, HBO Max=1, Disney+=2, ...): la colonna 1 (seconda card) è HBO Max, non Disney+
    // (che è la terza card, colonna 2).
    assertFocusedLabelsContain("HBO Max", "Top 10 Film card 2 --SU--> riga sopra card 2")
  }
}

