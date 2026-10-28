package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.TvFocusableBox
import com.example.ui.viewmodel.ScreenState
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ritorno dal player quando la riproduzione è avviata da una card di «Continua a guardare».
 *
 * Contratto verificato (allineato a MainActivity e a PlayerScreen):
 * - OK sulla card di «Continua a guardare» avvia la riproduzione DIRETTAMENTE (nessuna scheda
 *   Dettaglio in mezzo) e il player è un OVERLAY sopra la destinazione attiva;
 * - BACK dal player chiude il player e porta alla SCHEDA DETTAGLIO del contenuto appena
 *   riprodotto — non alla card di «Continua a guardare» — che è la richiesta emessa dal
 *   ViewModel e tradotta in navigazione da MainActivity;
 * - il focus della scheda è sulla SUA card di riproduzione/ripresa del contenuto (per identità
 *   del contenuto riprodotto, non per posizione) e la navigazione D-pad resta viva;
 * - il player aperto da altre schermate (es. dalla scheda Dettaglio) rientra dov'è partito,
 *   senza navigazioni aggiuntive.
 *
 * Harness fedele a MainActivity:
 * - ViewModel REALE (provenienza della riproduzione, richiesta di rientro, target di focus);
 * - NavHost REALE con le due destinazioni (Browsing con la Home reale, Dettaglio) e
 *   l'effetto che traduce la richiesta del ViewModel in `navigate`;
 * - contenuto Home REALE ([HomeMainBrowsingContent]) con la riga «Continua a guardare»;
 * - sidebar composta DOPO il contenuto, con item focalizzabili alla stessa geometria reale;
 * - overlay del player composto SOPRA la destinazione attiva quando `screenState == PLAYER`,
 *   con un nodo di riposo focalizzabile come quello di PlayerScreen.
 *
 * La scheda Dettaglio è ridotta all'essenziale per il focus ([DetailStub]), con lo stesso
 * contratto della schermata reale: media selezionato dal ViewModel e card di riproduzione/
 * ripresa del contenuto come prima superficie focalizzabile, richiesta per FRAME finché il
 * focus non viene confermato (`onFocusChanged`) — nessun ritardo arbitrario.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class ContinueWatchingPlayerReturnFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_TRENDING = "section_trending_film"
    const val HERO_TITLE = "HERO 1"
    const val PLAYER_TAG = "player_overlay"
    const val DETAIL_TAG = "detail_destination"
    const val DETAIL_PLAY_TAG_PREFIX = "detail_play_card_"
    const val DETAIL_EXTRA_TAG = "detail_action_extra"
    const val SIDEBAR_TAG_PREFIX = "sidebar_item_"
    const val SIDEBAR_ITEMS = 4
    const val ROUTE_BROWSING = "browsing"
    const val ROUTE_DETAIL = "detail"
    val SIDEBAR_WIDTH = 60.dp
  }

  // ---------------------------------------------------------------- fixture

  /** Titolo in corso di visione: la card vive SOLO nella riga «Continua a guardare». */
  private fun cwItem(
    index: Int,
    lastWatchedAt: Long,
    id: String = "cw-${index + 1}",
    tmdbId: Int = 7000 + index
  ): MediaItem = MediaItem(
    id = id,
    title = "CW ${index + 1}",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2200 + index,
    tmdbId = tmdbId,
    currentProgressMs = 10 * 60_000L,
    totalDurationMs = 100 * 60_000L,
    lastWatchedAt = lastWatchedAt
  )

  private fun cwItems(): List<MediaItem> = listOf(
    cwItem(0, lastWatchedAt = 5_000L),
    cwItem(1, lastWatchedAt = 4_000L),
    cwItem(2, lastWatchedAt = 3_000L),
    cwItem(3, lastWatchedAt = 2_000L)
  )

  /**
   * Stesso catalogo con una SCHEDA GEMELLA: il titolo di «Continua a guardare» è identificato
   * da un id IMDb (`tt…`) ed è presente anche nella riga Trending con id TMDB — lo stesso
   * contenuto, due schede e due identificatori diversi. Il rientro deve puntare al contenuto
   * RIPRODOTTO (la card cliccata), mai alla gemella.
   */
  private fun cwItemsWithTwin(): List<MediaItem> = cwItems().map { item ->
    if (item.id == "cw-3") item.copy(id = "tt7000123", imdbId = "tt7000123") else item
  }

  /** Gemella del titolo giocato: stesso film, anno e titolo, scheda e identificatore TMDB. */
  private fun twinItem(): MediaItem = MediaItem(
    id = "tmdb_movie_7000",
    title = "CW 3",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2202,
    tmdbId = 7000
  )

  private fun trendingItem(index: Int): MediaItem = MediaItem(
    id = "$ROW_TRENDING-${index + 1}",
    title = "TRF ${index + 1}",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2001,
    tmdbId = 8000 + index
  )

  private fun catalogs(trending: List<MediaItem> = List(10) { trendingItem(it) }): HomeCatalogs = HomeCatalogs(
    heroItems = listOf(
      MediaItem(
        id = "hero-1",
        title = HERO_TITLE,
        synopsis = "",
        videoUrl = "",
        type = MediaType.FILM,
        year = 2099,
        tmdbId = 900
      )
    ),
    trendingMovies = trending
  )

  // ---------------------------------------------------------------- harness

  private lateinit var viewModel: StreamNovaViewModel

  /** Riga «Continua a guardare» osservabile: il player, al salvataggio del progresso, la
   * riordina (Room -> `lastWatchedAt`), quindi la lista deve poter cambiare sotto il test. */
  private var cwList by mutableStateOf(emptyList<MediaItem>())

  /** Catalogo Home osservabile (riga Trending + Hero): la gemella entra da qui. */
  private var homeCatalog by mutableStateOf(catalogs())

  /** Destinazione attiva del NavHost: come MainActivity, il test la legge dal controller. */
  private lateinit var navController: NavHostController

  @Composable
  private fun App() {
    val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
    val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
    val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()
    val screenState by viewModel.screenState.collectAsState()
    val selectedMedia by viewModel.selectedMedia.collectAsState()
    val playerDetailReturn by viewModel.playerDetailReturn.collectAsState()

    val controller = rememberNavController()
    SideEffect { navController = controller }

    // MainActivity: la richiesta di rientro dal player diventa la navigazione del NavHost. La
    // chiusura del player è DIFFERITA e si conclude quando la scheda è composta (vedi il
    // composable della destinazione): l'overlay copre la destinazione finché non è a schermo.
    LaunchedEffect(playerDetailReturn) {
      if (playerDetailReturn == null) return@LaunchedEffect
      viewModel.consumePlayerDetailReturn()
      if (controller.currentDestination?.route != ROUTE_DETAIL) {
        controller.navigate(ROUTE_DETAIL)
      } else {
        viewModel.completePlayerClose()
      }
    }

    CompositionLocalProvider(
      LocalLastFocusedMediaId provides lastFocusedMediaId,
      LocalLastFocusedTarget provides lastFocusedTarget,
      LocalFocusRestoreToken provides focusRestoreToken,
      LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() }
    ) {
      Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        NavHost(navController = controller, startDestination = ROUTE_BROWSING) {
          composable(route = ROUTE_BROWSING) {
            HomeMainBrowsingContent(
              allMedia = cwList,
              homeCatalogs = homeCatalog,
              // Click di una card di riga: apre la scheda Dettaglio (MainActivity).
              onMediaClick = { media ->
                viewModel.openDetail(media)
                controller.navigate(ROUTE_DETAIL)
              },
              // Click su una card di «Continua a guardare»: avvia la riproduzione
              // direttamente, senza passare dalla scheda (MainActivity -> openStreamForMedia).
              onPlayClick = { media -> viewModel.openPlayer(media) },
              onRemoveFromContinueWatching = {},
              viewModel = viewModel,
              modifier = Modifier
                .fillMaxSize()
                .padding(start = SIDEBAR_WIDTH)
            )
            SidebarStub()
          }
          composable(route = ROUTE_DETAIL) {
            // MainActivity: quando la scheda è composta (dietro l'overlay del player) la
            // chiusura differita del player si conclude — la destinazione è già a schermo.
            LaunchedEffect(Unit) { viewModel.completePlayerClose() }
            DetailStub(media = selectedMedia)
          }
        }
        // Il player è un OVERLAY composto SOPRA la destinazione attiva (MainActivity).
        if (screenState == ScreenState.PLAYER) {
          PlayerOverlayStub()
        }
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
          .padding(vertical = 16.dp, horizontal = 4.dp)
      ) {
        Spacer(modifier = Modifier.height(48.dp))
        Column(
          modifier = Modifier.fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          repeat(SIDEBAR_ITEMS) { index ->
            TvFocusableBox(
              modifier = Modifier
                .height(48.dp)
                .width(48.dp)
                .testTag("$SIDEBAR_TAG_PREFIX$index")
            ) { }
          }
        }
      }
    }
  }

  /**
   * Player ridotto all'essenziale per il focus: overlay a tutto schermo con il NODO DI RIPOSO
   * focalizzabile (1dp) che prende il focus all'ingresso — lo stesso contratto di PlayerScreen
   * (sotto il player la destinazione resta composta, senza focus).
   */
  @Composable
  private fun PlayerOverlayStub() {
    val idleRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
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
      // Richiesta di focus ripetuta (limitata) finché il nodo non è agganciato e il focus non
      // è arrivato: nessun ciclo infinito (waitForIdle non deve mai girare a vuoto).
      LaunchedEffect(Unit) {
        repeat(20) {
          if (hasFocus) return@LaunchedEffect
          try {
            idleRequester.requestFocus()
          } catch (_: Exception) {
          }
          withFrameNanos { }
        }
      }
    }
  }

  /**
   * Scheda Dettaglio ridotta all'essenziale per il focus, con il contratto della schermata
   * reale ([DetailScreen]): mostra il contenuto selezionato dal ViewModel (come MainActivity) e
   * la sua PRIMA superficie focalizzabile è la card di riproduzione/ripresa del contenuto, con
   * la stessa etichetta della pillola reale («Continua a guardare» per un film in corso,
   * «Riprendi SxEy» per una serie). La richiesta di focus si ripete per FRAME finché
   * `onFocusChanged` non la conferma: nessun ritardo arbitrario, nessuna richiesta a un nodo
   * non ancora agganciato.
   */
  @Composable
  private fun DetailStub(media: MediaItem?) {
    if (media == null) return
    val playRequester = remember(media.id) { FocusRequester() }
    var isPlayFocused by remember(media.id) { mutableStateOf(false) }
    LaunchedEffect(media.id) {
      repeat(20) {
        if (isPlayFocused) return@LaunchedEffect
        try {
          playRequester.requestFocus()
        } catch (_: Exception) {
        }
        withFrameNanos { }
      }
    }
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF0B0F14))
        .testTag(DETAIL_TAG)
    ) {
      Text(
        text = media.title,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
      )
      Row(
        modifier = Modifier.padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        TvFocusableBox(
          modifier = Modifier
            .height(48.dp)
            .width(260.dp)
            .testTag("$DETAIL_PLAY_TAG_PREFIX${media.id}")
            .focusRequester(playRequester)
            .onFocusChanged { isPlayFocused = it.isFocused },
          onClick = { viewModel.openPlayer(media) }
        ) { isFocused ->
          Text(
            text = detailPlayLabel(media),
            color = if (isFocused) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
        }
        TvFocusableBox(
          modifier = Modifier
            .height(48.dp)
            .width(48.dp)
            .testTag(DETAIL_EXTRA_TAG),
          onClick = {}
        ) { }
      }
    }
  }

  /** Etichetta della card di riproduzione/ripresa: stessa regola della scheda reale. */
  @Composable
  private fun detailPlayLabel(media: MediaItem): String =
    if (media.type == MediaType.SERIE_TV) {
      val season = media.lastWatchedSeason ?: 1
      val episode = media.lastWatchedEpisode ?: 1
      if (media.currentProgressMs > 0L) {
        stringResource(R.string.detail_resume_episode_format, season, episode)
      } else {
        stringResource(R.string.detail_play_episode_format, season, episode)
      }
    } else if (media.currentProgressMs > 0L) {
      stringResource(R.string.home_section_continue_watching)
    } else {
      stringResource(R.string.action_play)
    }

  private fun render(
    items: List<MediaItem> = cwItems(),
    catalogs: HomeCatalogs = catalogs()
  ) {
    viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    cwList = items
    homeCatalog = catalogs
    rule.setContent { App() }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helpers

  private fun focusedNodes(): List<SemanticsNode> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()

  /**
   * Testi dell'albero focalizzato: il nodo a fuoco e i suoi discendenti.
   *
   * La card di «Continua a guardare» NON fonde i propri testi nel nodo focalizzato (a
   * differenza di altre card), quindi la lettura deve scendere nell'albero.
   */
  private fun focusedTexts(): List<String> = focusedNodes().flatMap { allTextsOf(it) }

  private fun allTextsOf(node: SemanticsNode): List<String> {
    val own = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
    return own + node.children.flatMap { allTextsOf(it) }
  }

  private fun testTagOf(node: SemanticsNode): String? =
    generateSequence(node) { it.parent }
      .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
      .firstOrNull()

  private fun focusedTags(): List<String?> = focusedNodes().map { testTagOf(it) }

  /** true se la card con [title] ha REALMENTE il focus. */
  private fun isCardFocused(title: String): Boolean {
    val focusable = rule.onAllNodes(
      SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus),
      useUnmergedTree = true
    )
    return focusable.fetchSemanticsNodes().any { node ->
      node.config.getOrNull(SemanticsProperties.Focused) == true && allTextsOf(node).contains(title)
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    // Il titolo compare DUE volte per card quando l'immagine non è disponibile (placeholder
    // [MediaPosterFallback] + testo della card): il bersaglio è il nodo FOCALIZZABILE.
    val focusable = rule.onAllNodes(
      SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus),
      useUnmergedTree = true
    )
    val index = focusable.fetchSemanticsNodes().indexOfFirst { allTextsOf(it).contains(title) }
    require(index >= 0) { "card '$title' non trovata" }
    focusable[index].requestFocus()
    rule.waitForIdle()
  }

  /** OK sulla card a fuoco: avvia la riproduzione e il player (overlay) prende il focus. */
  @OptIn(ExperimentalTestApi::class)
  private fun confirmFocused() {
    // OK del telecomando: la card TvFocusableBox conferma su KeyUp del tasto di conferma
    // (il click semantico non esiste su questa card).
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()
  }

  private fun waitForPlayerFocus() {
    rule.waitUntil(timeoutMillis = 3_000) {
      focusedNodes().any { node ->
        generateSequence(node) { it.parent }
          .any { it.config.getOrNull(SemanticsProperties.TestTag) == PLAYER_TAG }
      }
    }
  }

  /**
   * La riga Trending è sotto la piega: la card si raggiunge con il protocollo della Home
   * (target row-scoped + segnale) — lo stesso percorso del rientro dal Dettaglio — e poi la si
   * conferma con l'OK del telecomando, come farebbe l'utente.
   */
  @OptIn(ExperimentalTestApi::class)
  private fun focusTrendingCardAndConfirm() {
    viewModel.saveLastFocusedMedia(ROW_TRENDING, "$ROW_TRENDING-4")
    viewModel.signalFocusRestore("$ROW_TRENDING-4")
    rule.waitUntil(timeoutMillis = 5_000) { isCardFocused("TRF 4") }
    confirmFocused()
  }

  /**
   * BACK dal player: `resetStreamState()` + `closePlayer()`, esattamente la sequenza del
   * BackHandler e dell'`onKeyEvent` di PlayerScreen.
   */
  private fun backFromPlayer() {
    viewModel.resetStreamState()
    viewModel.closePlayer()
    rule.waitForIdle()
  }

  private fun isOnDetailDestination(): Boolean = navController.currentDestination?.route == ROUTE_DETAIL

  /** Il focus deve essere sulla card di riproduzione/ripresa della scheda per [mediaId]. */
  private fun assertFocusedDetailPlayCard(mediaId: String, step: String) {
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedTags().contains("$DETAIL_PLAY_TAG_PREFIX$mediaId")
    }
    assertTrue(
      "$step: focus su [${focusedTexts()}] (tag=${focusedTags()}) invece che sulla card " +
        "della scheda Dettaglio del contenuto $mediaId",
      focusedTags().contains("$DETAIL_PLAY_TAG_PREFIX$mediaId")
    )
  }

  private fun assertFocusedTagsIs(tag: String, step: String) {
    rule.waitUntil(timeoutMillis = 5_000) { focusedTags().contains(tag) }
    assertTrue("$step: atteso il tag $tag, trovati ${focusedTags()}", focusedTags().contains(tag))
  }

  /** Invariante del difetto: nessun nodo focalizzato sulla SIDEBAR (e focus mai perso). */
  private fun assertFocusNotOnSidebar(step: String) {
    val nodes = focusedNodes()
    assertTrue("$step: nessun nodo focalizzato (D-pad senza focus)", nodes.isNotEmpty())
    nodes.forEach { node ->
      val tag = testTagOf(node)
      assertFalse(
        "$step: il focus e' finito sulla SIDEBAR (tag=$tag, bounds=${node.boundsInRoot})",
        tag?.startsWith(SIDEBAR_TAG_PREFIX) == true
      )
    }
  }

  /** Home: il focus deve stare dentro il contenuto (a destra del binario della sidebar). */
  private fun assertFocusInsideContent(step: String) {
    assertFocusNotOnSidebar(step)
    val sidebarRightPx = with(rule.density) { SIDEBAR_WIDTH.toPx() }
    focusedNodes().forEach { node ->
      val tag = testTagOf(node)
      assertTrue(
        "$step: il focus e' uscito dal contenuto (tag=$tag, bounds=${node.boundsInRoot})",
        node.boundsInRoot.left >= sidebarRightPx - 1f
      )
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun string(resId: Int): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId)

  @Before
  fun setUp() {
    // Il ViewModel reale avvia il caricamento dei metadati della scheda: nei test le coroutine
    // principali NON devono girare (nessuna rete), come nei test del ViewModel.
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  // ---------------------------------------------------------------- 1. rientro dal player

  /**
   * Il percorso segnalato: card «Continua a guardare» -> riproduzione -> BACK -> il player si
   * chiude e si rientra nella SCHEDA DETTAGLIO del contenuto appena riprodotto, con il focus
   * sulla sua card di riproduzione/ripresa — non sulla card di «Continua a guardare».
   */
  @Test
  fun ritornoDalPlayerDaContinuaAGuardareApreIlDettaglio() {
    render()

    focusCard("CW 3")
    confirmFocused()
    waitForPlayerFocus()
    backFromPlayer()

    // Destinazione: la scheda Dettaglio (richiesta del ViewModel -> navigazione).
    rule.waitUntil(timeoutMillis = 5_000) { isOnDetailDestination() }
    assertEquals(ROUTE_DETAIL, navController.currentDestination?.route)
    assertEquals(
      "la scheda deve essere quella del contenuto riprodotto",
      "cw-3",
      viewModel.selectedMedia.value?.id
    )

    // Focus: la card di riproduzione/ripresa della scheda (per identità del contenuto).
    assertFocusedDetailPlayCard("cw-3", "rientro dal player")
    assertTrue(
      "la card deve essere quella del contenuto riprodotto ('${string(R.string.home_section_continue_watching)}')",
      focusedTexts().contains(string(R.string.home_section_continue_watching))
    )
    assertFalse(
      "il focus non deve restare/tornare sulla card di «Continua a guardare»",
      isCardFocused("CW 3")
    )
    assertFocusNotOnSidebar("rientro dal player")
    assertNull(
      "la Home non deve ricevere un target di ripristino",
      viewModel.lastFocusedTarget.value
    )
  }

  /**
   * Ricomposizione della riga durante il rientro: al salvataggio del progresso la riga
   * «Continua a guardare» si riordina (il titolo appena visto va in testa). Il rientro non
   * dipende dalla posizione della card: la destinazione e il focus sono quelli del contenuto
   * riprodotto.
   */
  @Test
  fun ilRiordinoDellaRigaNonCambiaLaDestinazioneDelRientro() {
    render()

    focusCard("CW 3")
    confirmFocused()
    waitForPlayerFocus()
    backFromPlayer()

    // Ondata di dati (progresso salvato dal player): la card giocata passa in prima posizione.
    val reordered = cwList.sortedByDescending { it.lastWatchedAt ?: 0L }
    val played = cwList.first { it.title == "CW 3" }
    cwList = listOf(played) + reordered.filterNot { it.id == played.id }

    rule.waitUntil(timeoutMillis = 5_000) { isOnDetailDestination() }
    assertFocusedDetailPlayCard("cw-3", "rientro dopo la ricomposizione della riga")
    assertFocusNotOnSidebar("rientro dopo la ricomposizione della riga")
  }

  /**
   * Lo stesso titolo può comparire nel catalogo in DUE schede con identificatori diversi
   * (id IMDb per la card di «Continua a guardare», id TMDB nella riga Trending). Il rientro
   * deve puntare al contenuto RIPRODOTTO (la card cliccata) e alla sua identità, mai alla
   * gemella: la scheda aperta E il titolo della card a fuoco sono quelli giusti.
   */
  @Test
  fun ilRientroRiconosceIlContenutoRiprodottoAncheConUnaSchedaGemella() {
    render(
      items = cwItemsWithTwin(),
      catalogs = catalogs(trending = listOf(twinItem()) + List(9) { trendingItem(it) })
    )

    focusCard("CW 3")
    confirmFocused()
    waitForPlayerFocus()
    backFromPlayer()

    rule.waitUntil(timeoutMillis = 5_000) { isOnDetailDestination() }
    assertEquals(
      "la scheda deve essere quella della card RIPRODOTTA",
      "tt7000123",
      viewModel.selectedMedia.value?.id
    )
    assertFocusedDetailPlayCard("tt7000123", "rientro con scheda gemella nel catalogo")
    assertFocusNotOnSidebar("rientro con scheda gemella nel catalogo")
  }

  // ---------------------------------------------------------------- 2. D-pad dopo il rientro

  /**
   * Dopo il rientro il D-pad deve essere vivo DENTRO la scheda Dettaglio: i movimenti restano
   * sulla schermata di destinazione (nessuna perdita di focus, nessun ritorno alla sidebar).
   */
  @Test
  fun laNavigazioneDPadRestaVivaDopoIlRientroNelDettaglio() {
    render()

    focusCard("CW 3")
    confirmFocused()
    waitForPlayerFocus()
    backFromPlayer()
    rule.waitUntil(timeoutMillis = 5_000) { isOnDetailDestination() }
    assertFocusedDetailPlayCard("cw-3", "rientro dal player")

    pressKey(Key.DirectionRight)
    assertFocusedTagsIs(DETAIL_EXTRA_TAG, "D-pad a destra dopo il rientro")
    assertFocusNotOnSidebar("D-pad a destra dopo il rientro")

    pressKey(Key.DirectionLeft)
    assertFocusedDetailPlayCard("cw-3", "D-pad a sinistra dopo il rientro")
    assertFocusNotOnSidebar("D-pad a sinistra dopo il rientro")

    assertTrue("la destinazione deve restare la scheda Dettaglio", isOnDetailDestination())
  }

  // ---------------------------------------------------------------- 3. altri ingressi al player

  /**
   * Nessuna regressione per gli altri ingressi: il player aperto DALLA SCHEDA Dettaglio rientra
   * nella scheda stessa, senza navigazioni aggiuntive e con il focus riportato sulla sua card
   * di riproduzione.
   */
  @Test
  fun ilPlayerApertoDalDettaglioRientraNelDettaglio() {
    render()

    // Click su una card di riga: si apre la scheda Dettaglio del titolo.
    focusTrendingCardAndConfirm()
    rule.waitUntil(timeoutMillis = 5_000) { isOnDetailDestination() }
    val detailMediaId = viewModel.selectedMedia.value?.id
    assertFocusedDetailPlayCard(detailMediaId!!, "ingresso nella scheda Dettaglio")

    // OK sulla card «Riproduci» della scheda: la riproduzione NON registra nessuna
    // provenienza da card di riga.
    confirmFocused()
    waitForPlayerFocus()
    backFromPlayer()

    assertNull(
      "il player aperto dalla scheda non deve chiedere nessuna navigazione",
      viewModel.playerDetailReturn.value
    )
    assertEquals(
      "si deve restare nella scheda Dettaglio di partenza",
      ROUTE_DETAIL,
      navController.currentDestination?.route
    )
    assertEquals(detailMediaId, viewModel.selectedMedia.value?.id)
    assertFocusedDetailPlayCard(detailMediaId, "rientro dal player aperto dalla scheda")
  }

  /**
   * Nessuna regressione nel ripristino della Home dal Dettaglio: il protocollo
   * target + segnale + token continua a riportare il focus sulla card di origine della riga.
   */
  @Test
  fun ilRipristinoDellaHomeDalDettaglioRestaInvariato() {
    render()

    focusTrendingCardAndConfirm()
    rule.waitUntil(timeoutMillis = 5_000) { isOnDetailDestination() }

    // BACK dal Dettaglio (come il BackHandler di MainActivity): si pop la destinazione e si
    // chiede il ripristino del contenuto della Home sulla card di origine.
    viewModel.saveLastFocusedMedia(ROW_TRENDING, "$ROW_TRENDING-4")
    rule.runOnIdle { navController.popBackStack() }
    viewModel.signalFocusRestore("$ROW_TRENDING-4")

    rule.waitUntil(timeoutMillis = 5_000) { isCardFocused("TRF 4") }
    assertTrue("ripristino Home dal Dettaglio", isCardFocused("TRF 4"))
    assertFocusInsideContent("ripristino Home dal Dettaglio")
  }
}
