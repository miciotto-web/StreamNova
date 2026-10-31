package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.data.repository.HomeCatalogs
import com.example.domain.model.Subtitle
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalClearLastFocusedProvider
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedCardKey
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedProviderId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.viewmodel.ScreenState
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * RITORNO DAL PLAYER AL DETTAGLIO (BACK del telecomando).
 *
 * Difetto coperto: Home -> Dettaglio -> riproduzione -> BACK dal Player. Il focus NON deve
 * tornare alla Home — deve restare nella schermata Dettaglio, sul pulsante «Riproduci»
 * (stato di focus REALE, confermato da `onFocusChanged`). Solo il BACK SUCCESSIVO, premuto
 * nel Dettaglio, torna alla Home sulla card di origine.
 *
 * Harness fedele alla MainActivity:
 * - `NavHost` REALE con le route "browsing" e "detail" e lo stesso ordine di composizione
 *   (NavHost prima, poi il BackHandler del player, poi l'overlay del player);
 * - `StreamNovaViewModel` REALE (screenState, target di ripristino, token);
 * - Home REALE ([HomeMainBrowsingContent]) con il gating di [HomeScreen] (target passato solo
 *   quando `focusRestoreRequested` è true) e l'effetto d'ingresso della MainActivity
 *   (`isInitialHomeFocusSet` saveable + `pendingContentFocusRestore`);
 * - [DetailScreen] REALE con il suo effetto di focus iniziale su «Riproduci»;
 * - overlay del player con lo STESSO contratto di focus di PlayerScreen (nodo di riposo che
 *   prende il focus all'ingresso; BACK -> `resetStreamState()` + `closePlayer()`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class PlayerReturnToDetailFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val HOME_ROUTE = "browsing"
    const val DETAIL_ROUTE = "detail"
    const val ROW_TRENDING = "section_trending_film"
    const val HERO_TITLE = "HERO 1"
    const val DETAIL_ROOT_TAG = "detail_root"
    const val PLAYER_TAG = "player_overlay"
    const val PLAY_LABEL_IT = "Riproduci"
    const val PLAY_LABEL_EN = "Play"
    const val YEAR_TRENDING = 2001
  }

  private lateinit var viewModel: StreamNovaViewModel
  private lateinit var navController: NavHostController

  // Stato dell'orchestrazione a livello app (MainActivity): flag del ripristino Home.
  private var pendingContentFocusRestore by mutableStateOf(false)

  // Diagnostica: la Home è mai stata rimontata mentre il focus tornava dal player?
  private var homeMounts = 0
  private var heroFocusedDuringPlayerReturn = false

  // ---------------------------------------------------------------- fixture

  private fun movie() = MediaItem(
    id = "tt0117731",
    title = "Toy Story",
    synopsis = "Sinossi del film.",
    videoUrl = "",
    type = MediaType.FILM,
    year = 1995,
    tmdbId = 862
  )

  private fun series() = MediaItem(
    id = "tt0944947",
    title = "Game of Thrones",
    synopsis = "Sinossi della serie.",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = 2011,
    tmdbId = 1399,
    seasonsCount = 1,
    seasonEpisodesCount = mapOf(1 to 3)
  )

  /** Card della riga Trending Film: il Dettaglio viene aperto da qui (percorso reale). */
  private fun heroCard() = MediaItem(
    id = "hero-1",
    title = HERO_TITLE,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2099,
    tmdbId = 900
  )

  private fun trendingCards(): List<MediaItem> = List(12) { index ->
    MediaItem(
      id = "tf-${index + 1}",
      title = "TF ${index + 1}",
      synopsis = "",
      videoUrl = "",
      type = MediaType.FILM,
      year = YEAR_TRENDING,
      tmdbId = 8000 + index
    )
  }

  private fun catalogs() = HomeCatalogs(
    heroItems = listOf(heroCard()),
    trendingMovies = trendingCards()
  )

  // ---------------------------------------------------------------- harness

  @Composable
  private fun App(detailMedia: MediaItem) {
    navController = rememberNavController()
    val screenState by viewModel.screenState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
      NavHost(navController = navController, startDestination = HOME_ROUTE) {
        composable(HOME_ROUTE) {
          HomeDestination()
        }
        composable(DETAIL_ROUTE) {
          DetailDestination(detailMedia, screenState)
        }
      }

      // Stesso ordine della MainActivity: il BackHandler del player è composto DOPO il
      // NavHost (l'overlay del player ne aggiunge poi uno proprio, come PlayerScreen).
      BackHandler(enabled = screenState == ScreenState.PLAYER) {
        viewModel.resetStreamState()
        viewModel.closePlayer()
      }

      if (screenState == ScreenState.PLAYER) {
        PlayerOverlayStub()
      }
    }
  }

  /**
   * Home con lo stesso contratto di [HomeScreen] e lo stesso effetto d'ingresso della
   * MainActivity: il focus iniziale è UNA TANTUM (`rememberSaveable`), al BACK dal Dettaglio
   * si emette solo il segnale di ripristino.
   */
  @Composable
  private fun HomeDestination() {
    DisposableEffect(Unit) {
      homeMounts++
      onDispose { }
    }
    val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
    val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
    val lastFocusedCardKey by viewModel.lastFocusedCardKey.collectAsState()
    val lastFocusedProviderId by viewModel.lastFocusedProviderId.collectAsState()
    val focusRestoreRequested by viewModel.focusRestoreRequested.collectAsState()
    val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()

    var isInitialHomeFocusSet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pendingContentFocusRestore, isInitialHomeFocusSet) {
      if (!isInitialHomeFocusSet) {
        isInitialHomeFocusSet = true
        viewModel.requestHomeScrollToTop()
      } else if (pendingContentFocusRestore) {
        pendingContentFocusRestore = false
        val saved = viewModel.lastFocusedMediaId.value
        if (saved != null) {
          viewModel.signalFocusRestore(saved)
        }
      }
    }

    val homeRestoreTarget = lastFocusedTarget.takeIf { focusRestoreRequested }
    CompositionLocalProvider(
      LocalLastFocusedMediaId provides homeRestoreTarget?.second,
      LocalLastFocusedTarget provides homeRestoreTarget,
      LocalLastFocusedCardKey provides lastFocusedCardKey.takeIf { homeRestoreTarget != null },
      LocalLastFocusedProviderId provides lastFocusedProviderId,
      LocalFocusRestoreToken provides focusRestoreToken,
      LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() },
      LocalClearLastFocusedProvider provides { viewModel.clearLastFocusedProvider() }
    ) {
      Box(modifier = Modifier.fillMaxSize().padding(start = 60.dp)) {
        HomeMainBrowsingContent(
          allMedia = emptyList(),
          homeCatalogs = catalogs(),
          // Il click di una card di riga salva già la provenienza row-scoped all'interno
          // di HomeMainBrowsingContent (`handleRowMediaClick`): qui si apre la scheda.
          onMediaClick = { item ->
            viewModel.openDetail(item)
            navController.navigate(DETAIL_ROUTE)
          },
          onPlayClick = {},
          onRemoveFromContinueWatching = {},
          viewModel = viewModel
        )
      }
    }
  }

  @Composable
  private fun DetailDestination(media: MediaItem, screenState: ScreenState) {
    val playerClosedToken by viewModel.playerClosedToken.collectAsState()
    // Stesso BackHandler della MainActivity per la destinazione Dettaglio: attivo solo
    // quando il player NON è aperto (il BACK del player appartiene al player).
    BackHandler(enabled = screenState != ScreenState.PLAYER) {
      val hasPreviousDetail = viewModel.popDetail()
      if (!hasPreviousDetail) {
        pendingContentFocusRestore = true
      }
      navController.popBackStack()
    }

    Box(modifier = Modifier.fillMaxSize().testTag(DETAIL_ROOT_TAG)) {
      DetailScreen(
        media = media,
        allMedia = listOf(media),
        onBackClick = {
          val hasPreviousDetail = viewModel.popDetail()
          if (!hasPreviousDetail) pendingContentFocusRestore = true
          navController.popBackStack()
        },
        // La riproduzione reale risolve le sorgenti in rete e poi chiama openPlayer():
        // l'harness salta la risoluzione (non oggetto del test) e apre lo stesso player
        // che la risoluzione aprirebbe, con lo stesso stato del ViewModel.
        onPlayClick = { m, ep -> viewModel.openPlayer(m, ep) },
        onToggleFavorite = {},
        onToggleWatched = {},
        onProviderClick = {},
        onMediaClick = {},
        playerClosedToken = playerClosedToken
      )
    }
  }

  /**
   * Player ridotto all'essenziale per il focus: overlay a tutto schermo con il NODO DI RIPOSO
   * focalizzabile che prende il focus all'ingresso (stesso contratto di PlayerScreen) e BACK
   * che esegue `resetStreamState()` + `closePlayer()`, esattamente come il BackHandler e
   * l'onKeyEvent di PlayerScreen.
   */
  @Composable
  private fun PlayerOverlayStub() {
    val idleRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
      viewModel.resetStreamState()
      viewModel.closePlayer()
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .testTag(PLAYER_TAG)
        .onFocusChanged { hasFocus = it.hasFocus }
    ) {
      Box(
        modifier = Modifier
          .size(1.dp)
          .focusRequester(idleRequester)
          .focusable()
      )
      LaunchedEffect(Unit) {
        repeat(30) {
          if (hasFocus) return@LaunchedEffect
          try {
            idleRequester.requestFocus()
          } catch (_: Exception) {
            // Nodo non ancora agganciato: si ritenta al frame successivo.
          }
          withFrameNanos { }
        }
      }
    }
  }

  private fun render(media: MediaItem = movie()) {
    viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    // Nessuna rete nei test: la ricerca sottotitoli del player risponde subito vuota.
    viewModel.openSubtitlesProvider = object : OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: com.example.data.model.Episode?,
        preferredLanguage: String?
      ) = emptyList<Subtitle>()
    }
    pendingContentFocusRestore = false
    homeMounts = 0
    heroFocusedDuringPlayerReturn = false
    rule.setContent { App(media) }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helpers

  private fun focusedNodes() =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()

  private fun isFocusedNode(
    matcher: androidx.compose.ui.test.SemanticsMatcher,
    useUnmergedTree: Boolean = false
  ): Boolean = try {
    rule.onNode(matcher, useUnmergedTree = useUnmergedTree).assertIsFocused()
    true
  } catch (_: AssertionError) {
    false
  } catch (_: IllegalStateException) {
    false
  }

  private fun focusedTexts(): List<String> = focusedNodes().flatMap { node ->
    (node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
      node.children.flatMap { collectTexts(it) }
  }

  private fun collectTexts(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
    (node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
      node.children.flatMap { collectTexts(it) }

  private fun focusablePlay() =
    isFocusable() and hasAnyDescendant(
      hasText(PLAY_LABEL_IT, substring = true) or hasText(PLAY_LABEL_EN, substring = true)
    )

  private fun detailVisible(): Boolean =
    rule.onAllNodes(hasTestTag(DETAIL_ROOT_TAG), useUnmergedTree = true)
      .fetchSemanticsNodes().isNotEmpty()

  private fun playerVisible(): Boolean =
    rule.onAllNodes(hasTestTag(PLAYER_TAG), useUnmergedTree = true)
      .fetchSemanticsNodes().isNotEmpty()

  private fun focusHomeCard(title: String) {
    rule.onNode(
      hasAnyDescendant(hasText(title)) and hasRequestFocusAction(),
      useUnmergedTree = true
    ).requestFocus()
    rule.waitForIdle()
  }

  private fun pressKey(key: Key) {
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun pressSystemBack() {
    val composeRule = rule as? AndroidComposeTestRule<*, *> ?: return
    rule.runOnUiThread {
      (composeRule.activity as? androidx.activity.ComponentActivity)
        ?.onBackPressedDispatcher
        ?.onBackPressed()
    }
    rule.waitForIdle()
  }

  /** Home -> Dettaglio dalla card [title], come il click D-pad su una card di riga. */
  private fun openDetailFromHome(title: String) {
    focusHomeCard(title)
    pressKey(Key.DirectionCenter)
    rule.waitUntil(timeoutMillis = 5_000) { detailVisible() }
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
  }

  /** OK su «Riproduci»: il player (overlay) si apre e prende il focus. */
  private fun startPlaybackAndWaitForPlayer() {
    rule.onNode(focusablePlay()).performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()
    rule.waitUntil(timeoutMillis = 5_000) { playerVisible() }
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedNodes().any { node ->
        generateSequence(node) { it.parent }
          .any { it.config.getOrNull(SemanticsProperties.TestTag) == PLAYER_TAG }
      }
    }
  }

  // ---------------------------------------------------------------- 1. film

  @Test
  fun backDalPlayerRestaNelDettaglioConFocusSuRiproduci() {
    render(movie())
    openDetailFromHome("TF 3")
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)

    startPlaybackAndWaitForPlayer()
    assertEquals(ScreenState.PLAYER, viewModel.screenState.value)

    pressSystemBack()

    // 1. Navigazione: si resta nella STESSA schermata Dettaglio.
    assertTrue(
      "il BACK dal player ha lasciato la schermata Dettaglio (state=${viewModel.screenState.value})",
      detailVisible()
    )
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertFalse(
      "il BACK dal player non deve popolare la destinazione Dettaglio",
      pendingContentFocusRestore
    )
    assertFalse(
      "il rientro dal player non deve pubblicare una richiesta di ripristino per la Home",
      viewModel.focusRestoreRequested.value
    )

    // 2. Focus REALE sul pulsante «Riproduci» (non solo requestFocus inviato).
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertTrue(
      "focus su [${focusedTexts()}] invece che su «Riproduci»",
      isFocusedNode(focusablePlay())
    )
    assertFalse(
      "il focus non deve restare nell'overlay del player",
      focusedNodes().any { node ->
        generateSequence(node) { it.parent }
          .any { it.config.getOrNull(SemanticsProperties.TestTag) == PLAYER_TAG }
      }
    )
  }

  // ---------------------------------------------------------------- 2. serie

  @Test
  fun backDalPlayerRestaNelDettaglioAnchePerUnaSerie() {
    render(series())
    openDetailFromHome("TF 2")

    startPlaybackAndWaitForPlayer()
    pressSystemBack()

    assertTrue("il BACK dal player ha lasciato la schermata Dettaglio", detailVisible())
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertTrue(
      "focus su [${focusedTexts()}] invece che su «Riproduci» (serie TV)",
      isFocusedNode(focusablePlay())
    )
  }

  // ---------------------------------------------------------------- 3. Home mai ripristinata

  @Test
  fun laHomeELaHeroNonRicevonoIlFocusDuranteIlRitornoDalPlayer() {
    render(movie())
    openDetailFromHome("TF 2")
    val homeMountsWhileOnDetail = homeMounts

    startPlaybackAndWaitForPlayer()

    // Osservazione FRAME per FRAME del BACK dal player: la Home non deve mai tornare in
    // scena e la Hero non deve mai ricevere il focus.
    rule.mainClock.autoAdvance = false
    rule.runOnUiThread {
      (rule as? AndroidComposeTestRule<*, *>)?.let { composeRule ->
        (composeRule.activity as? androidx.activity.ComponentActivity)
          ?.onBackPressedDispatcher
          ?.onBackPressed()
      }
    }
    repeat(120) {
      rule.mainClock.advanceTimeByFrame()
      val texts = focusedTexts()
      if (texts.any { it == HERO_TITLE }) heroFocusedDuringPlayerReturn = true
      if (!detailVisible() && playerVisible().not()) {
        // Né Dettaglio né Player: destinazione cambiata (Home) durante il rientro.
      }
    }
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()

    assertFalse(
      "la Hero ha ricevuto il focus durante il ritorno dal player",
      heroFocusedDuringPlayerReturn
    )
    assertTrue("il Dettaglio non è più la destinazione attiva", detailVisible())
    assertEquals(
      "la Home è stata rimontata durante il ritorno dal player (mount: $homeMounts, prima del player: $homeMountsWhileOnDetail)",
      homeMountsWhileOnDetail,
      homeMounts
    )
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
  }

  // ---------------------------------------------------------------- 4. BACK successivo

  @Test
  fun ilBackSuccessivoDalDettaglioTornaAllaHomeSullaCardOriginale() {
    render(movie())
    openDetailFromHome("TF 3")

    startPlaybackAndWaitForPlayer()
    pressSystemBack()
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }

    // BACK nel Dettaglio: unico passaggio che deve riportare alla Home, sulla card di origine.
    pressSystemBack()
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(hasTestTag(DETAIL_ROOT_TAG), useUnmergedTree = true)
        .fetchSemanticsNodes().isEmpty()
    }
    assertFalse("il Dettaglio è ancora la destinazione attiva", detailVisible())
    assertEquals(ScreenState.BROWSING, viewModel.screenState.value)
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedTexts().let { it.contains("TF 3") && it.contains(YEAR_TRENDING.toString()) }
    }
    val texts = focusedTexts()
    assertTrue(
      "la Home non è tornata sulla card originale (focus su $texts)",
      texts.contains("TF 3") && texts.contains(YEAR_TRENDING.toString())
    )
    assertFalse("la Hero non deve ricevere il focus al BACK dal Dettaglio", texts.contains(HERO_TITLE))
  }

  // ---------------------------------------------------------------- 5. D-pad nel Dettaglio

  @Test
  fun laNavigazioneDpadDelDettaglioContinuaDopoIlRientroDalPlayer() {
    render(movie())
    openDetailFromHome("TF 2")
    startPlaybackAndWaitForPlayer()
    pressSystemBack()
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }

    val playBounds = rule.onNode(focusablePlay()).fetchSemanticsNode().boundsInRoot

    // DESTRA: dalla pillola «Riproduci» il focus passa ai controlli circolari (Cuore).
    pressKey(Key.DirectionRight)
    assertFalse(
      "DESTRA non ha spostato il focus dalla pillola «Riproduci»",
      isFocusedNode(focusablePlay())
    )
    val afterRight = rule.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode()
    assertTrue(
      "DESTRA non è rimasta nella pulsantiera (bounds=$afterRight)",
      afterRight.boundsInRoot.left > playBounds.left
    )

    // SINISTRA: si torna su «Riproduci».
    pressKey(Key.DirectionLeft)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }

    // GIÙ: la navigazione verticale della scheda è viva (nessun focus congelato).
    val beforeDown = rule.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    pressKey(Key.DirectionDown)
    val afterDown = rule.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertTrue(
      "GIÙ non ha spostato il focus dalla pulsantiera",
      afterDown.top > beforeDown.top
    )

    // SU: si risale verso la testata (sinossi o pulsantiera).
    pressKey(Key.DirectionUp)
    pressKey(Key.DirectionUp)
    rule.waitForIdle()
    val afterUp = rule.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertTrue(
      "SU non ha riportato il focus verso la testata",
      afterUp.top <= afterDown.top
    )
  }

  // ---------------------------------------------------------------- 6. percorsi non-Detail

  @Test
  fun ilPlayerApertoDallaHomeNonTornaAlDettaglio() {
    render(movie())
    // Riproduzione avviata direttamente dalla Home (BROWSING), come una card che suona
    // senza passare dalla scheda: al BACK si resta sulla Home.
    rule.runOnUiThread {
      viewModel.savePlaybackFocusOrigin(ROW_TRENDING, "tf-1")
      viewModel.openPlayer(movie())
    }
    rule.waitUntil(timeoutMillis = 5_000) { playerVisible() }

    pressSystemBack()

    assertFalse("il player è ancora aperto", playerVisible())
    assertFalse("si è tornati al Dettaglio da un player aperto dalla Home", detailVisible())
    assertEquals(ScreenState.BROWSING, viewModel.screenState.value)
    // Il ripristino della card di origine resta quello della Home (percorso invariato):
    // la card riceve il focus e, come sempre, il target viene consumato all'arrivo.
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedTexts().let { it.contains("TF 1") && it.contains(YEAR_TRENDING.toString()) }
    }
    assertNull(
      "il target della Home deve essere consumato quando la card riceve il focus",
      viewModel.lastFocusedTarget.value
    )
  }

  // ---------------------------------------------------------------- 7. stato del Player

  @Test
  fun laChiusuraDelPlayerNonConsumaIlTargetDettaglioVersoLaHome() {
    render(movie())
    openDetailFromHome("TF 2")
    val targetBefore = viewModel.lastFocusedTarget.value
    val tokenBefore = viewModel.focusRestoreToken.value

    startPlaybackAndWaitForPlayer()
    pressSystemBack()
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }

    assertEquals(
      "il rientro dal player non deve modificare il target pendente Dettaglio -> Home",
      targetBefore,
      viewModel.lastFocusedTarget.value
    )
    assertEquals(
      "il rientro dal player non deve far avanzare il token della Home",
      tokenBefore,
      viewModel.focusRestoreToken.value
    )
    assertFalse(viewModel.focusRestoreRequested.value)
  }
}
