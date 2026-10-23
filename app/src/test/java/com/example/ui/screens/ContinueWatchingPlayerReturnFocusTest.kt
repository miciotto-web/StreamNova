package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
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
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.TvFocusableBox
import com.example.ui.viewmodel.ScreenState
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DIFETTO: «Continua a guardare» -> riproduzione -> BACK dal player -> il focus finisce sulla
 * sidebar invece che sulla card di origine.
 *
 * Percorso (senza Dettaglio in mezzo): il click sulla card di «Continua a guardare» avvia la
 * riproduzione DIRETTAMENTE ([HomeMainBrowsingContent] -> `onPlayClick`), il player è un
 * OVERLAY sopra la Home ([MainActivity]: `if (screenState == PLAYER) PlayerScreen(...)`) e al
 * BACK esce con `closePlayer()`. La Home non viene mai smontata e nessun segnale di ripristino
 * veniva emesso: il focus restava alla prima superficie focalizzabile (la sidebar).
 *
 * Harness fedele a MainActivity:
 * - ViewModel REALE (target di ripristino + token + provenienza della riproduzione);
 * - contenuto Home REALE ([HomeMainBrowsingContent]) con la riga «Continua a guardare»;
 * - sidebar composta DOPO il contenuto, con item focalizzabili alla stessa geometria reale;
 * - overlay del player composto SOPRA il contenuto e realmente focalizzato (nodo di riposo
 *   focalizzabile come quello di PlayerScreen).
 *
 * Caso "contenuto non più presente nella riga" (titolo uscito da «Continua a guardare» mentre
 * il player è aperto): il ramo di codice è lo stesso del rientro dal Dettaglio ed è coperto da
 * [HomeFocusNavigationUiTest.titoloSparitoDallaRigaSalvataNonRipristinaSuUnAltraRiga] e
 * [HomeFocusNavigationUiTest.ripristinoSenzaDestinazioneAssegnaAncoraDiFocusEHomeRestaNavigabile]
 * (ancora di recupero, verificata anche con la richiesta pendente del recupero), più i test del
 * coordinatore su cella di partenza sparita e clamp della colonna. Un test UI dedicato NON è
 * riproducibile in harness: la rimozione di una chiave da una LazyRow mentre il focusRestorer
 * della riga tiene il pin dell'item fa fallire la DISPOSAL della composizione di test con
 * "Release should only be called once" (quirk interno di Compose 1.7.x, presente anche senza le
 * modifiche sotto test). La verifica su device è elencata nel resoconto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class ContinueWatchingPlayerReturnFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val CW_ROW = "section_continue_watching"
    const val ROW_TRENDING = "section_trending_film"
    const val HERO_TITLE = "HERO 1"
    const val PLAYER_TAG = "player_overlay"
    const val SIDEBAR_TAG_PREFIX = "sidebar_item_"
    const val SIDEBAR_ITEMS = 4
    val SIDEBAR_WIDTH = 60.dp
  }

  // ---------------------------------------------------------------- fixture

  /** Titolo in corso di visione: la card vive SOLO nella riga «Continua a guardare». */
  private fun cwItem(index: Int, lastWatchedAt: Long): MediaItem = MediaItem(
    id = "cw-${index + 1}",
    title = "CW ${index + 1}",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2200 + index,
    tmdbId = 7000 + index,
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

  private fun trendingItem(index: Int): MediaItem = MediaItem(
    id = "$ROW_TRENDING-${index + 1}",
    title = "TRF ${index + 1}",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2001,
    tmdbId = 8000 + index
  )

  private fun catalogs(): HomeCatalogs = HomeCatalogs(
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
    trendingMovies = List(10) { trendingItem(it) }
  )

  // ---------------------------------------------------------------- harness

  private lateinit var viewModel: StreamNovaViewModel

  /** Riga «Continua a guardare» osservabile: il player, al salvataggio del progresso, la
   * riordina (Room -> `lastWatchedAt`), quindi la lista deve poter cambiare sotto il test. */
  private var cwList by mutableStateOf(emptyList<MediaItem>())

  @Composable
  private fun App(initiallyOpenPlayer: Boolean = false) {
    val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
    val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
    val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()
    val screenState by viewModel.screenState.collectAsState()
    var playerOpen by remember { mutableStateOf(initiallyOpenPlayer) }

    CompositionLocalProvider(
      LocalLastFocusedMediaId provides lastFocusedMediaId,
      LocalLastFocusedTarget provides lastFocusedTarget,
      LocalFocusRestoreToken provides focusRestoreToken,
      LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() }
    ) {
      Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        HomeMainBrowsingContent(
          allMedia = cwList,
          homeCatalogs = catalogs(),
          onMediaClick = {},
          // Come MainActivity: il click di una card di riga avvia la riproduzione.
          onPlayClick = { playerOpen = true },
          onRemoveFromContinueWatching = {},
          viewModel = viewModel,
          modifier = Modifier
            .fillMaxSize()
            .padding(start = SIDEBAR_WIDTH)
        )
        SidebarStub()
        // Il player è un OVERLAY: la Home resta composta sotto di esso (MainActivity).
        if (playerOpen || screenState == ScreenState.PLAYER) {
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
   * focalizzabile (1dp) che prende il focus all'ingresso — lo stesso contratto di
   * PlayerScreen (sotto il player la Home resta composta, senza focus).
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

  private fun render() {
    viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    cwList = cwItems()
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
  private fun playFocusedCard() {
    // OK del telecomando: la card TvFocusableBox conferma su KeyUp del tasto di conferma
    // (il click semantico non esiste su questa card).
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()
    rule.waitUntil(timeoutMillis = 3_000) {
      focusedNodes().any { node ->
        generateSequence(node) { it.parent }
          .any { it.config.getOrNull(SemanticsProperties.TestTag) == PLAYER_TAG }
      }
    }
  }

  /** BACK dal player: `closePlayer()` come fanno il BackHandler e l'onKeyEvent di PlayerScreen. */
  private fun backFromPlayer() {
    viewModel.closePlayer()
    rule.waitForIdle()
  }

  private fun assertFocusedTitle(title: String, step: String) {
    rule.waitUntil(timeoutMillis = 5_000) { isCardFocused(title) }
    assertTrue(
      "$step: focus su [${focusedTexts()}] invece che su $title",
      isCardFocused(title)
    )
  }

  private fun assertFocusInsideContent(step: String) {
    val sidebarRightPx = with(rule.density) { SIDEBAR_WIDTH.toPx() }
    val nodes = focusedNodes()
    assertTrue("$step: nessun nodo focalizzato (D-pad senza focus)", nodes.isNotEmpty())
    nodes.forEach { node ->
      val tag = testTagOf(node)
      assertFalse(
        "$step: il focus e' finito sulla SIDEBAR (tag=$tag, bounds=${node.boundsInRoot})",
        tag?.startsWith(SIDEBAR_TAG_PREFIX) == true
      )
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

  // ---------------------------------------------------------------- 1. ritorno dal player

  /**
   * Il percorso segnalato: card «Continua a guardare» -> riproduzione -> BACK -> il focus
   * deve tornare sulla STESSA card (riga «Continua a guardare», terza posizione).
   */
  @Test
  fun ritornoDalPlayerRipristinaLaCardContinuaAGuardare() {
    render()

    focusCard("CW 3")
    playFocusedCard()
    backFromPlayer()

    assertFocusedTitle("CW 3", "ritorno dal player")
    assertFocusInsideContent("ritorno dal player")

    // Il D-pad è vivo: GIÙ dalla card ripristinata porta il focus alla riga successiva.
    val signatureBefore = focusedNodes().joinToString("|") { "${testTagOf(it)}:${it.boundsInRoot}" }
    pressKey(Key.DirectionDown)
    val signatureAfter = focusedNodes().joinToString("|") { "${testTagOf(it)}:${it.boundsInRoot}" }
    assertTrue("D-pad morto dopo il rientro dal player", focusedNodes().isNotEmpty())
    assertTrue(
      "il focus non si e' mosso (prima=$signatureBefore, dopo=$signatureAfter)",
      signatureBefore != signatureAfter
    )
    assertFocusInsideContent("navigazione dopo il rientro dal player")
  }

  /**
   * Ricomposizione della riga durante il rientro: al salvataggio del progresso la riga
   * «Continua a guardare» si riordina (il titolo appena visto va in testa) e la card cambia
   * POSIZIONE. Il ripristino deve seguire l'IDENTITÀ della card, non l'indice salvato.
   */
  @Test
  fun ripristinoDopoRicomposizioneDellaRigaRitrovaLaCardAllaNuovaPosizione() {
    render()

    focusCard("CW 3")
    playFocusedCard()
    backFromPlayer()

    // Ondata di dati (progresso salvato dal player): la card giocata passa in prima posizione.
    val reordered = cwList.sortedByDescending { it.lastWatchedAt ?: 0L }
    val played = cwList.first { it.title == "CW 3" }
    cwList = listOf(played) + reordered.filterNot { it.id == played.id }

    assertFocusedTitle("CW 3", "ripristino dopo ricomposizione della riga")
    assertFocusInsideContent("ripristino dopo ricomposizione della riga")
  }

  /**
   * Nessuna regressione per le ALTRE righe: dopo un rientro dal player sulla card di
   * «Continua a guardare», un ripristino normale (rientro dal Dettaglio) su un'altra riga
   * continua ad atterrare sulla card di quella riga.
   */
  @Test
  fun ripristinoDelleAltreRigheRestaInvariato() {
    render()

    focusCard("CW 3")
    playFocusedCard()
    backFromPlayer()
    assertFocusedTitle("CW 3", "primo rientro dal player")

    // Rientro dal Dettaglio da un'altra riga (target row-scoped + token, come MainActivity).
    viewModel.saveLastFocusedMedia(ROW_TRENDING, "$ROW_TRENDING-4")
    viewModel.signalFocusRestore("$ROW_TRENDING-4")

    assertFocusedTitle("TRF 4", "rientro dal Dettaglio su un'altra riga")
    assertFocusInsideContent("rientro dal Dettaglio su un'altra riga")
  }
}
