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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.HOME_CONTENT_ANCHOR_ID
import com.example.HOME_CONTENT_ANCHOR_ROW
import com.example.contentFocusRestorePlan
import com.example.ContentFocusRestorePlan
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.TvFocusableBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ritorno dal Dettaglio/Provider quando il target di ripristino e' gia' stato CONSUMATO.
 *
 * Difetto coperto (segnalato sull'emulatore): aprendo il Dettaglio dalla Hero, oppure
 * tornando da una schermata Provider, la pressione di BACK lasciava il focus sulla SIDEBAR.
 *
 * Causa (confermata sul codice): la Home resta composta durante la transizione di uscita
 * verso il Dettaglio e il suo effetto di rilascio consuma il target di ripristino quando
 * l'Hero e' ancora focalizzato. Al ritorno il target non esiste piu' e il fallback della
 * MainActivity sparava `moveFocus(Down)` a freddo: quando le righe della Home non sono ancora
 * composte l'unica superficie focalizzabile e' la sidebar, e il movimento ci finisce dentro.
 *
 * I test coprono:
 * - la diagnosi: il target viene consumato mentre la Home e' ancora composta sotto il Dettaglio;
 * - l'ancora di contenuto: il focus atterra sul contenuto (Hero), MAI sulla sidebar;
 * - il ripristino vero: il target viene ripristinato quando la riga di destinazione e' pronta;
 * - la navigazione verticale Hero -> GIU' -> righe -> SU -> Hero, che deve restare invariata.
 *
 * Harness fedele alla MainActivity: contenuto della Home con il binario della sidebar
 * (padding iniziale di 60dp) e sidebar composta DOPO il contenuto, con item focalizzabili
 * alla stessa geometria reale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class DetailReturnContentFocusFallbackTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val HERO_TITLE = "HERO 1"
    const val PROVIDERS_FIRST = "Netflix"
    const val TOP10_FILM_PREFIX = "T10F"
    const val SIDEBAR_ITEMS = 5
    const val SIDEBAR_ITEM_TAG_PREFIX = "sidebar_item_"
    const val DETAIL_MARKER = "SCHERMATA DETTAGLIO"
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
      media("top10-serie-${index + 1}", "T10S ${index + 1}", MediaType.SERIE_TV, year = 2101)
    },
    trendingMovies = List(12) { index ->
      media("trending-film-${index + 1}", "TRF ${index + 1}", year = 2001)
    },
    trendingSeries = List(12) { index ->
      media("trending-serie-${index + 1}", "TRS ${index + 1}", MediaType.SERIE_TV, year = 2002)
    }
  )

  // ---------------------------------------------------------------- harness

  /** Target di ripristino (rowKey, mediaId) e token, come nel ViewModel. */
  private var restoreTarget by mutableStateOf<Pair<String, String>?>(null)
  private var restoreToken by mutableStateOf(0)

  // Tracce della diagnosi: la Home e' ancora viva (non smontata) nel momento in cui il
  // target e' stato consumato?
  private var homeCompositionCount = 0
  private var homeDisposed = false
  private var detailActive = false
  private var consumedWhileHomeAlive: Boolean? = null
  private var consumedWhileDetailActive: Boolean? = null

  @Composable
  private fun App(
    homeCatalogs: HomeCatalogs,
    onMediaClick: (MediaItem) -> Unit = {}
  ) {
    SideEffect { homeCompositionCount++ }
    DisposableEffect(Unit) {
      onDispose { homeDisposed = true }
    }
    CompositionLocalProvider(
      LocalLastFocusedMediaId provides restoreTarget?.second,
      LocalLastFocusedTarget provides restoreTarget,
      LocalFocusRestoreToken provides restoreToken,
      LocalClearLastFocusedMedia provides {
        // Il rilascio del target avviene dentro un effetto della Home: se la Home e' viva
        // in questo istante, il target e' stato consumato mentre la Home era ancora
        // composta (non ancora smontata dalla destinazione Dettaglio).
        consumedWhileHomeAlive = !homeDisposed
        consumedWhileDetailActive = detailActive
        restoreTarget = null
      }
    ) {
      Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        HomeMainBrowsingContent(
          allMedia = emptyList(),
          homeCatalogs = homeCatalogs,
          onMediaClick = onMediaClick,
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

  private fun render(homeCatalogs: HomeCatalogs = catalogs()) {
    rule.setContent { App(homeCatalogs) }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helper

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

  @OptIn(ExperimentalTestApi::class)
  private fun focusedLabels(): String =
    rule.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString(" | ") { node ->
      val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("/") { it.text }
      val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("/")
      "text=$text desc=$desc"
    }

  private fun focusedNodes(): List<SemanticsNode> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()

  private fun testTagOf(node: SemanticsNode): String? =
    generateSequence(node) { it.parent }
      .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
      .firstOrNull()

  /**
   * Invariante del difetto: il nodo focalizzato non appartiene alla sidebar e sta dentro la
   * colonna del contenuto.
   */
  private fun assertFocusInsideContentColumn(step: String) {
    val sidebarRightPx = with(rule.density) { SIDEBAR_WIDTH.toPx() }
    val nodes = focusedNodes()
    assertTrue("$step: nessun nodo focalizzato (D-pad senza focus)", nodes.isNotEmpty())
    nodes.forEach { node ->
      val tag = testTagOf(node)
      assertFalse(
        "$step: il focus e' finito sulla SIDEBAR (tag=$tag, bounds=${node.boundsInRoot})",
        tag?.startsWith(SIDEBAR_ITEM_TAG_PREFIX) == true
      )
      assertTrue(
        "$step: il focus e' uscito dal contenuto (tag=$tag, bounds=${node.boundsInRoot})",
        node.boundsInRoot.left >= sidebarRightPx - 1f
      )
    }
  }

  private fun assertFocusedLabelsContain(expected: String, step: String) {
    val labels = focusedLabels()
    assertTrue("$step: atteso '$expected' nel nodo focalizzato, trovato [$labels]", labels.contains(expected))
  }

  // ---------------------------------------------------------------- 1. piano

  /**
   * Il piano di ripristino: con un target salvato si ripristina la card (percorso
   * invariato); senza target si affida il focus al CONTENUTO (mai un movimento cieco
   * verso la sidebar).
   */
  @Test
  fun ilPianoDiRipristinoSenzaTargetAffidaIlFocusAlContenuto() {
    assertEquals(
      ContentFocusRestorePlan.RESTORE_SAVED_TARGET,
      contentFocusRestorePlan("hero-1")
    )
    assertEquals(
      ContentFocusRestorePlan.ANCHOR_CONTENT,
      contentFocusRestorePlan(null)
    )
  }

  // ---------------------------------------------------------------- 2. diagnosi

  /**
   * DIAGNOSI: il target di ripristino viene CONSUMATO dalla Home mentre la Home e' ancora
   * composta sotto la destinazione Dettaglio (la transizione di uscita del NavHost
   * mantiene la destinazione uscente in composizione). Al ritorno il target non esiste
   * piu': e' il caso in cui il fallback non puo' puntare a nessuna card.
   */
  @Test
  fun ilTargetVieneConsumatoMentreLaHomeEAncoraCompostaSottoIlDettaglio() {
    rule.setContent {
      val navController = rememberNavController()
      NavHost(navController = navController, startDestination = "home") {
        composable("home") {
          App(
            homeCatalogs = catalogs(),
            onMediaClick = { item ->
              // Salvataggio del target come fa `handleRowMediaClick` nella Home reale.
              restoreTarget = "hero_banner" to item.id
              restoreToken += 1
              navController.navigate("detail")
            }
          )
        }
        composable("detail") {
          SideEffect { detailActive = true }
          Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.material3.Text(DETAIL_MARKER)
          }
        }
      }
    }
    rule.waitForIdle()

    // L'utente apre il Dettaglio dall'Hero (l'Hero e' focalizzato).
    focusContent(HERO_TITLE)
    rule.onAllNodes(focusableWithLabel(HERO_TITLE)).onFirst()
      .performSemanticsAction(SemanticsActions.OnClick)
    rule.waitForIdle()

    // Il Dettaglio e' la destinazione attiva...
    assertTrue(
      "il Dettaglio non e' la destinazione attiva",
      rule.onAllNodes(hasText(DETAIL_MARKER)).fetchSemanticsNodes().isNotEmpty()
    )
    // ...e il target e' gia' stato consumato: al ritorno non ci sara' nessuna card da
    // ripristinare e il fallback dovra' affidare il focus al CONTENUTO.
    assertNull(
      "il target non e' stato consumato dalla Home ancora composta sotto il Dettaglio",
      restoreTarget
    )
    assertEquals(
      "il target non e' stato consumato dalla Home ANCORA COMPOSTA (non smontata)",
      true,
      consumedWhileHomeAlive
    )
  }

  // ---------------------------------------------------------------- 2b. meccanismo del difetto

  /**
   * Meccanismo del difetto: quando il contenuto non e' (ancora) composto, l'unica
   * superficie focalizzabile e' la sidebar e un movimento del focus "a freddo" ci finisce
   * dentro. E' il motivo per cui il fallback non puo' piu' muovere il focus da solo: deve
   * affidarlo al contenuto, che sa quando una cella e' realmente pronta.
   */
  @Test
  fun unMovimentoCiecoDelFocusFinisceSullaSidebarSenzaContenutoComposto() {
    lateinit var focusManager: FocusManager
    rule.setContent {
      focusManager = LocalFocusManager.current
      Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        // Nessun contenuto: solo la sidebar, come nell'istante in cui le righe della Home
        // non sono ancora composte.
        SidebarStub()
      }
    }
    rule.waitForIdle()

    focusManager.moveFocus(FocusDirection.Down)
    rule.waitForIdle()

    val nodes = focusedNodes()
    assertTrue("nessun nodo focalizzato", nodes.isNotEmpty())
    val tag = testTagOf(nodes.first())
    assertTrue(
      "il movimento cieco doveva finire sulla sidebar (tag=$tag)",
      tag?.startsWith(SIDEBAR_ITEM_TAG_PREFIX) == true
    )
  }

  // ---------------------------------------------------------------- 3. ancora di contenuto

  /**
   * RITORNO DAL DETTAGLIO SENZA TARGET: la MainActivity affida il focus al contenuto con
   * l'ancora dell'Hero. Il focus deve atterrare sull'Hero (il contenuto), MAI sulla sidebar.
   */
  @Test
  fun senzaTargetIlFocusAtterraSulContenutoENonSullaSidebar() {
    render()

    // Stessa azione del fallback della MainActivity (ancora di contenuto + segnale).
    restoreTarget = HOME_CONTENT_ANCHOR_ROW to HOME_CONTENT_ANCHOR_ID
    restoreToken += 1
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) {
      focusedLabels().contains(HERO_TITLE)
    }
    assertFocusedLabelsContain(HERO_TITLE, "ancora di contenuto")
    assertFocusInsideContentColumn("ancora di contenuto")
  }

  /**
   * Il target salvato (riga di destinazione) viene ripristinato quando la riga e' pronta: la
   * riga Top 10 Film non e' ancora composta (e' sotto il fold), quindi il ripristino
   * ATTENDE che la cella esista e poi atterra sulla card, sempre dentro il contenuto.
   */
  @Test
  fun ilTargetVieneRipristinatoQuandoLaRigaDiDestinazioneEPronta() {
    render()

    restoreTarget = "section_top_10_film" to "top10-film-3"
    restoreToken += 1
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) {
      focusedLabels().contains("$TOP10_FILM_PREFIX 3")
    }
    assertFocusedLabelsContain("$TOP10_FILM_PREFIX 3", "ripristino sulla riga di destinazione")
    assertFocusInsideContentColumn("ripristino sulla riga di destinazione")
  }

  // ---------------------------------------------------------------- 4. navigazione invariata

  /**
   * La navigazione verticale verificata sull'emulatore deve restare invariata anche dopo
   * il ripristino con ancora di contenuto: Hero -> GIU' -> righe -> SU -> Hero.
   */
  @Test
  fun laNavigazioneVerticaleRestaInvariataDopoIlRipristino() {
    render()

    restoreTarget = HOME_CONTENT_ANCHOR_ROW to HOME_CONTENT_ANCHOR_ID
    restoreToken += 1
    rule.waitForIdle()
    rule.waitUntil(timeoutMillis = 5_000) { focusedLabels().contains(HERO_TITLE) }

    pressKey(Key.DirectionDown)
    assertFocusedLabelsContain(PROVIDERS_FIRST, "Hero --GIU'--> riga provider")
    assertFocusInsideContentColumn("Hero --GIU'--> riga provider")

    pressKey(Key.DirectionUp)
    assertFocusInsideContentColumn("riga provider --SU--> Hero")
    rule.onNode(hasText(HERO_TITLE)).assertIsFocused()
  }
}
