package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.HOME_CONTENT_ANCHOR_ID
import com.example.HOME_CONTENT_ANCHOR_ROW
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.CatalogSection
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedCardKey
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.MEDIA_CONTEXT_MENU_TEST_TAG
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * RIPRISTINO DEL FOCUS AL RITORNO DAL DETTAGLIO (BACK) nelle righe ancorate della Home.
 *
 * Il ciclo è riprodotto integralmente come in produzione:
 * - `NavHost` reale: aprendo la scheda la Home viene SMONTATA e al BACK ricomposta; durante la
 *   transizione di uscita la Home resta però composta sotto il Dettaglio (è la finestra in cui
 *   il vecchio protocollo consumava il target e al rientro il focus finiva sulla Hero);
 * - `StreamNovaViewModel` REALE: il salvataggio della provenienza (`saveLastFocusedMedia` con
 *   rowKey + id + key stabile della card) e la richiesta di ripristino (`signalFocusRestore`)
 *   passano dalla catena vera;
 * - orchestrazione della MainActivity al BACK (`pendingContentFocusRestore` -> signal, con
 *   l'ancora di contenuto come fallback) e `isInitialHomeFocusSet` saveable;
 * - gating della Home come in `HomeScreen`: la Home riceve il target SOLO quando è una
 *   richiesta di ripristino, non quando è la sola provenienza salvata al click.
 *
 * Requisiti verificati:
 * 1. prima card di una riga: la stessa card torna a fuoco;
 * 2. card raggiunta dopo ≥5 step a destra: la stessa card torna a fuoco;
 * 3. card di «Continua a guardare» aperta dal menu contestuale: la stessa card torna a fuoco;
 * 4. card di una riga di catalogo addon: la stessa card torna a fuoco;
 * 5. la card ripristinata è quella originale e il suo `boundsInRoot.left` coincide con l'ancora;
 * 6. la Hero non riceve MAI il focus durante il ripristino;
 * 7. dopo il ripristino la navigazione D-pad prosegue normalmente.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class HomeBackFocusRestoreUiTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_TRENDING_FILM = "section_trending_film"
    const val ROW_TRENDING_SERIE = "section_trending_serie"
    const val YEAR_TRENDING_FILM = 2001
    const val YEAR_TRENDING_SERIE = 2002
    const val CARDS_PER_ROW = 12
    const val ANCHOR_TOLERANCE_PX = 1.5f
    const val DETAIL_TAG = "detail_stub"
  }

  private lateinit var viewModel: StreamNovaViewModel
  private lateinit var navController: androidx.navigation.NavHostController

  private var pendingContentFocusRestore by mutableStateOf(false)
  private var liveCatalogs by mutableStateOf<HomeCatalogs?>(null)
  private var liveAllMedia by mutableStateOf<List<MediaItem>>(emptyList())

  // ---------------------------------------------------------------- fixture

  private fun film(
    id: String,
    title: String,
    year: Int,
    progress: Long = 0L,
    watchedAt: Long = 0L
  ): MediaItem = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = year,
    tmdbId = id.hashCode(),
    currentProgressMs = progress,
    totalDurationMs = if (progress > 0) 100 * 60_000L else 0L,
    lastWatchedAt = watchedAt
  )

  private fun row(prefix: String, idPrefix: String, year: Int): List<MediaItem> =
    List(CARDS_PER_ROW) { index ->
      film("$idPrefix-${index + 1}", "$prefix ${index + 1}", year)
    }

  private fun catalogs(
    trendingMovies: List<MediaItem> = row("TF", ROW_TRENDING_FILM, YEAR_TRENDING_FILM),
    trendingSeries: List<MediaItem> = row("TS", ROW_TRENDING_SERIE, YEAR_TRENDING_SERIE),
    extraSections: List<CatalogSection> = emptyList()
  ): HomeCatalogs = HomeCatalogs(
    heroItems = List(3) { index -> film("hero-$index", "HERO ${index + 1}", 2099) },
    trendingMovies = trendingMovies,
    trendingSeries = trendingSeries,
    extraSections = extraSections
  )

  private fun continueWatching(count: Int = 8): List<MediaItem> = List(count) { index ->
    film(
      id = "cw-${index + 1}",
      title = "CW ${index + 1}",
      year = 2200 + index,
      progress = 10 * 60_000L,
      watchedAt = 10_000L - index * 1_000L
    ).copy(
      // Card di «Continua a guardare» con posizione reale: dalla v12 il default del
      // flag è `false` (una scheda TMDB fresca non è candidata).
      inContinueWatching = true
    )
  }

  // ---------------------------------------------------------------- harness

  @Composable
  private fun App(catalogs: HomeCatalogs, allMedia: List<MediaItem>) {
    navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
      composable("home") {
        HomeDestination(catalogs, allMedia)
      }
      composable("detail") {
        DetailStub()
      }
    }
  }

  @Composable
  private fun HomeDestination(catalogs: HomeCatalogs, allMedia: List<MediaItem>) {
    // Gating identico a `HomeScreen`: la Home riceve il target SOLO quando il ViewModel lo ha
    // promosso a RICHIESTA di ripristino (BACK dal Dettaglio / ritorno dal Player).
    val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
    val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
    val lastFocusedCardKey by viewModel.lastFocusedCardKey.collectAsState()
    val focusRestoreRequested by viewModel.focusRestoreRequested.collectAsState()
    val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()

    // Effetto d'ingresso della destination Browsing (MainActivity): il focus iniziale della
    // Home è UNA TANTUM (saveable), al BACK si emette solo il segnale di ripristino.
    var isInitialHomeFocusSet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pendingContentFocusRestore, isInitialHomeFocusSet) {
      if (!isInitialHomeFocusSet) {
        isInitialHomeFocusSet = true
        viewModel.requestHomeScrollToTop()
      } else if (pendingContentFocusRestore) {
        pendingContentFocusRestore = false
        val savedTargetId = viewModel.lastFocusedMediaId.value
        if (savedTargetId != null) {
          viewModel.signalFocusRestore(savedTargetId)
        } else {
          // Ancora di contenuto (stessa politica di MainActivity): se il target fosse stato
          // consumato durante l'uscita, il focus finirebbe sull'Hero. I test lo rilevano.
          viewModel.saveLastFocusedMedia(HOME_CONTENT_ANCHOR_ROW, HOME_CONTENT_ANCHOR_ID)
          viewModel.signalFocusRestore(HOME_CONTENT_ANCHOR_ID)
        }
      }
    }

    val homeRestoreTarget = lastFocusedTarget.takeIf { focusRestoreRequested }
    CompositionLocalProvider(
      LocalLastFocusedMediaId provides homeRestoreTarget?.second,
      LocalLastFocusedTarget provides homeRestoreTarget,
      LocalLastFocusedCardKey provides lastFocusedCardKey.takeIf { homeRestoreTarget != null },
      LocalFocusRestoreToken provides focusRestoreToken,
      LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() }
    ) {
      Box(modifier = Modifier.fillMaxSize().padding(start = 60.dp)) {
        HomeMainBrowsingContent(
          allMedia = allMedia,
          homeCatalogs = catalogs,
          onMediaClick = { navController.navigate("detail") },
          onPlayClick = {},
          onRemoveFromContinueWatching = {},
          viewModel = viewModel
        )
      }
    }
  }

  /** Schermata Dettaglio minimale con un nodo realmente focalizzato all'ingresso. */
  @Composable
  private fun DetailStub() {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
      repeat(10) {
        try {
          focusRequester.requestFocus()
          return@LaunchedEffect
        } catch (_: IllegalStateException) {
          androidx.compose.runtime.withFrameNanos { }
        }
      }
    }
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF101010))
        .testTag(DETAIL_TAG)
        .focusRequester(focusRequester)
        .clickable { }
    ) {
      Box(
        modifier = Modifier
          .size(200.dp)
          .align(Alignment.Center)
          .background(Color(0xFF3A3A3A))
      )
    }
  }

  private fun render(
    catalogs: HomeCatalogs = catalogs(),
    allMedia: List<MediaItem> = emptyList()
  ) {
    liveCatalogs = catalogs
    liveAllMedia = allMedia
    viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    rule.setContent { App(liveCatalogs ?: catalogs(), liveAllMedia) }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helper

  private fun collectTexts(node: SemanticsNode): List<String> =
    (node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
      node.children.flatMap { collectTexts(it) }

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
      .flatMap { collectTexts(it) }

  private fun focusCard(title: String) {
    rule.onNode(
      hasAnyDescendant(hasText(title)) and hasRequestFocusAction(),
      useUnmergedTree = true
    ).requestFocus()
    rule.waitForIdle()
  }

  /**
   * Il popup è un Dialog: mentre è aperto convivono due focus interni (la Home resta sulla
   * card di partenza come memoria di ripristino, il Dialog ha il proprio). I tasti vanno
   * consegnati al nodo focalizzato DENTRO il popup, l'unica finestra attiva.
   */
  private val popupFocusedMatcher =
    isFocused() and hasAnyAncestor(hasTestTag(MEDIA_CONTEXT_MENU_TEST_TAG))

  private fun showingDialog(): android.app.Dialog? =
    ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }

  private fun pressKey(key: Key, times: Int = 1) {
    repeat(times) {
      val popupFocused = rule.onAllNodes(popupFocusedMatcher, useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
      if (popupFocused) {
        rule.onAllNodes(popupFocusedMatcher, useUnmergedTree = true).onFirst()
          .performKeyInput { pressKey(key) }
      } else {
        // Albero UNMERGED: le card espongono il nodo focalizzato solo lì.
        rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(key) }
      }
      rule.waitForIdle()
    }
  }

  /** Apre la scheda Dettaglio dalla card focalizzata (conferma D-pad). */
  private fun openDetailFromFocusedCard() {
    pressKey(Key.DirectionCenter)
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(hasTestTag(DETAIL_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /** BACK dal Dettaglio: stesso ordine della MainActivity (flag + popBackStack). */
  private fun backFromDetail() {
    pendingContentFocusRestore = true
    rule.runOnIdle { navController.popBackStack() }
    rule.waitForIdle()
  }

  private fun waitForFocusedCard(title: String, year: Int) {
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedTexts().let { it.contains(title) && it.contains(year.toString()) }
    }
  }

  private fun focusedLeftPx(): Float =
    rule.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.left

  private fun assertHeroNotFocused(context: String) {
    assertFalse(
      "$context: il focus è finito sulla Hero (${focusedTexts()})",
      focusedTexts().any { it.startsWith("HERO ") }
    )
  }

  /**
   * La card ripristinata è quella ORIGINALE e il suo bordo sinistro coincide con l'ancora
   * (misurata sulla stessa card prima di aprire la scheda: stessa geometria di focus).
   */
  private fun assertRestoredCardAtAnchor(
    title: String,
    year: Int,
    anchorLeftPx: Float,
    context: String
  ) {
    waitForFocusedCard(title, year)
    assertHeroNotFocused(context)
    val actual = focusedLeftPx()
    assertEquals(
      "$context: la card non è tornata sull'ancora (left=$actual, atteso=$anchorLeftPx)",
      anchorLeftPx.toDouble(),
      actual.toDouble(),
      ANCHOR_TOLERANCE_PX.toDouble()
    )
  }

  // ---------------------------------------------------------------- 1. prima card

  @Test
  fun ritornoDalDettaglioRipristinaLaPrimaCardDellaRiga() {
    render()
    focusCard("TF 1")
    waitForFocusedCard("TF 1", YEAR_TRENDING_FILM)
    val anchor = focusedLeftPx()

    openDetailFromFocusedCard()
    backFromDetail()

    assertRestoredCardAtAnchor("TF 1", YEAR_TRENDING_FILM, anchor, "prima card della riga")
  }

  // ---------------------------------------------------------------- 2. card oltre 5 step

  @Test
  fun ritornoDalDettaglioRipristinaLaCardRaggiuntaDopoCinqueStep() {
    render()
    focusCard("TF 1")
    val anchor = focusedLeftPx()
    pressKey(Key.DirectionRight, times = 7)
    waitForFocusedCard("TF 8", YEAR_TRENDING_FILM)

    openDetailFromFocusedCard()
    backFromDetail()

    assertRestoredCardAtAnchor("TF 8", YEAR_TRENDING_FILM, anchor, "ottava card della riga")
  }

  // ---------------------------------------------------------------- 3. Continua a guardare

  @Test
  fun ritornoDalDettaglioRipristinaLaCardDiContinuaAGuardare() {
    render(allMedia = continueWatching())
    focusCard("CW 1")
    waitForFocusedCard("CW 1", 2200)
    val anchor = focusedLeftPx()
    pressKey(Key.DirectionRight, times = 3)
    waitForFocusedCard("CW 4", 2203)

    // Il Dettaglio della card «Continua a guardare» si apre dal menu contestuale (il click
    // sulla card avvia la riproduzione): pressione prolungata -> «Dettagli».
    longPressFocusedCard()
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(hasTestTag(MEDIA_CONTEXT_MENU_TEST_TAG), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
    // Il menu si apre con la prima voce a fuoco («Riprendi visione»): GIU' porta su
    // «Vai ai dettagli» (seconda voce), confermata con il tasto centrale.
    pressKey(Key.DirectionDown)
    pressKey(Key.DirectionCenter)
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(hasTestTag(DETAIL_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    backFromDetail()

    assertRestoredCardAtAnchor("CW 4", 2203, anchor, "quarta card di Continua a guardare")
  }

  // ---------------------------------------------------------------- 4. riga addon

  @Test
  fun ritornoDalDettaglioRipristinaLaCardDiUnaRigaAddon() {
    val addonRow = CatalogSection(
      id = "addon_pack",
      title = "ADDON PACK",
      addonName = "Test Addon",
      items = row("AX", "addon_pack", 2100)
    )
    render(
      catalogs = catalogs(
        trendingMovies = emptyList(),
        trendingSeries = emptyList(),
        extraSections = listOf(addonRow)
      )
    )
    focusCard("HERO 1")
    waitForFocusedCard("HERO 1", 2099)
    pressKey(Key.DirectionDown, times = 2)
    rule.waitUntil(timeoutMillis = 5_000) { focusedTexts().any { it.startsWith("AX ") } }
    pressKey(Key.DirectionRight, times = 5)
    waitForFocusedCard("AX 6", 2100)
    val anchor = focusedLeftPx()

    openDetailFromFocusedCard()
    backFromDetail()

    assertRestoredCardAtAnchor("AX 6", 2100, anchor, "sesta card della riga addon")
  }

  // ---------------------------------------------------------------- 5. identità dopo riordino

  @Test
  fun ilRipristinoTrovaLaStessaCardAncheSeIlCatalogoSiERiordinato() {
    val base = catalogs()
    render(catalogs = base)
    focusCard("TF 1")
    val anchor = focusedLeftPx()
    pressKey(Key.DirectionRight, times = 4)
    waitForFocusedCard("TF 5", YEAR_TRENDING_FILM)

    openDetailFromFocusedCard()

    // Ondata catalogo mentre la scheda è aperta: la card salvata passa IN TESTA alla riga.
    // Il ripristino è per identità/key stabile, non per indice.
    val moved = base.trendingMovies.first { it.id == "$ROW_TRENDING_FILM-5" }
    val reordered = base.copy(trendingMovies = listOf(moved) + (base.trendingMovies - moved))
    liveCatalogs = reordered
    rule.waitForIdle()

    backFromDetail()

    assertRestoredCardAtAnchor("TF 5", YEAR_TRENDING_FILM, anchor, "riordino durante il Dettaglio")
  }

  // ---------------------------------------------------------------- 6. Hero mai focalizzata

  @Test
  fun quandoLaHeroEESorgenteIlRipristinoTornaSullaHero() {
    render()
    focusCard("HERO 1")
    waitForFocusedCard("HERO 1", 2099)

    openDetailFromFocusedCard()
    backFromDetail()

    // Eccezione esplicita alla regola: se la sorgente della navigazione ERA la Hero, il
    // ripristino deve tornare lì (il gate sulla Hero non si chiude in questo caso).
    rule.waitUntil(timeoutMillis = 5_000) { focusedTexts().any { it.startsWith("HERO ") } }
  }

  @Test
  fun laHeroNonRiceveMaiIlFocusDuranteIlRipristino() {
    render()
    focusCard("TF 1")
    val anchor = focusedLeftPx()
    pressKey(Key.DirectionRight, times = 6)
    waitForFocusedCard("TF 7", YEAR_TRENDING_FILM)

    openDetailFromFocusedCard()

    // Ripristino osservato FRAME per FRAME dal pop: la Hero non deve mai comparire a fuoco.
    rule.mainClock.autoAdvance = false
    rule.runOnUiThread {
      pendingContentFocusRestore = true
      navController.popBackStack()
    }
    var heroFocused = false
    var restored = false
    repeat(240) {
      rule.mainClock.advanceTimeByFrame()
      val texts = focusedTexts()
      if (texts.any { it.startsWith("HERO ") }) heroFocused = true
      if (texts.contains("TF 7") && texts.contains(YEAR_TRENDING_FILM.toString())) restored = true
    }
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()

    assertFalse("la Hero ha ricevuto il focus durante il ripristino", heroFocused)
    assertTrue("la card salvata non ha mai ricevuto il focus (${focusedTexts()})", restored)
    assertRestoredCardAtAnchor("TF 7", YEAR_TRENDING_FILM, anchor, "ripristino osservato a frame")
  }

  // ---------------------------------------------------------------- 7. navigazione dopo il ripristino

  @Test
  fun dopoIlRipristinoLaNavigazioneContinuaANormalmente() {
    render()
    focusCard("TF 1")
    val anchor = focusedLeftPx()
    pressKey(Key.DirectionRight, times = 2)
    waitForFocusedCard("TF 3", YEAR_TRENDING_FILM)

    openDetailFromFocusedCard()
    backFromDetail()
    assertRestoredCardAtAnchor("TF 3", YEAR_TRENDING_FILM, anchor, "ripristino")

    // Destra: lo step ancorato prosegue dalla card ripristinata.
    pressKey(Key.DirectionRight)
    waitForFocusedCard("TF 4", YEAR_TRENDING_FILM)
    assertEquals(
      "RIGHT dopo il ripristino: la card non è sull'ancora",
      anchor.toDouble(),
      focusedLeftPx().toDouble(),
      ANCHOR_TOLERANCE_PX.toDouble()
    )

    // Sinistra: torna indietro nella stessa riga.
    pressKey(Key.DirectionLeft, times = 2)
    waitForFocusedCard("TF 2", YEAR_TRENDING_FILM)
    assertEquals(
      "LEFT dopo il ripristino: la card non è sull'ancora",
      anchor.toDouble(),
      focusedLeftPx().toDouble(),
      ANCHOR_TOLERANCE_PX.toDouble()
    )

    // Verticale: la navigazione tra righe resta attiva.
    pressKey(Key.DirectionDown)
    rule.waitUntil(timeoutMillis = 5_000) { focusedTexts().any { it.startsWith("TS ") } }
    pressKey(Key.DirectionUp)
    waitForFocusedCard("TF 2", YEAR_TRENDING_FILM)
    assertEquals(
      "rientro verticale dopo il ripristino: la card non è sull'ancora",
      anchor.toDouble(),
      focusedLeftPx().toDouble(),
      ANCHOR_TOLERANCE_PX.toDouble()
    )
  }

  // ---------------------------------------------------------------- pressione prolungata

  private fun longPressFocusedCard() {
    rule.mainClock.autoAdvance = false
    // KeyDown sulla finestra della Home, timer di pressione prolungata, poi KeyUp sulla
    // finestra del Dialog appena aperta: è la sequenza reale del telecomando.
    val composeRule = rule as? AndroidComposeTestRule<*, *>
    rule.runOnUiThread {
      composeRule?.activity?.window?.decorView?.dispatchKeyEvent(
        android.view.KeyEvent(
          android.view.KeyEvent.ACTION_DOWN,
          android.view.KeyEvent.KEYCODE_DPAD_CENTER
        )
      )
    }
    rule.mainClock.advanceTimeBy(700)
    rule.waitForIdle()
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(hasTestTag(MEDIA_CONTEXT_MENU_TEST_TAG), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
    rule.runOnUiThread {
      showingDialog()?.window?.decorView?.dispatchKeyEvent(
        android.view.KeyEvent(
          android.view.KeyEvent.ACTION_UP,
          android.view.KeyEvent.KEYCODE_DPAD_CENTER
        )
      )
    }
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()
  }
}
