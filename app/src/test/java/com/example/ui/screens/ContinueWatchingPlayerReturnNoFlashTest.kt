package com.example.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.navigation.Screen
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ACCETTAZIONE DEL RIENTRO DAL PLAYER — nessuna cornice di Home.
 *
 * Premendo BACK dal player avviato da una card di «Continua a guardare», la scheda Dettaglio
 * deve comparire DIRETTAMENTE: la Home non deve essere visibile nemmeno per un singolo frame.
 *
 * Il test avanza il frame clock PASSO PER PASSO (nessuna attesa arbitraria) e, per ogni cornice
 * realmente composta, verifica la scena dell'albero di semantica. Le destinazioni dell'harness
 * sono distinte e non ambigue:
 *
 *   - la Home è l'unica destinazione la cui scena può restare sotto l'overlay del player;
 *   - l'overlay del player è a tutto schermo e COPRE la destinazione sottostante (come fa
 *     PlayerScreen, il cui fondo è nero e pieno);
 *   - la scheda Dettaglio è l'unica destinazione che deve poter comparire dopo il player.
 *
 * Invarianti per ogni cornice da quando si preme BACK:
 *  1. la scena della Home NON può esistere senza l'overlay del player che la copre
 *     (Home visibile = difetto: è esattamente il frame in cui comparirebbe il flash);
 *  2. a schermo c'è SEMPRE almeno una destinazione: o l'overlay del player o la scheda
 *     (nessuna cornice di "vuoto" tra il player e i dettagli).
 *
 * Harness fedele a MainActivity: ViewModel reale, NavHost reale con le destinazioni della Home
 * e della scheda (rotta e argomenti REALI, vedi [Screen.Detail]) e le stesse transizioni
 * (istantanee sul rientro dal player), l'effetto che traduce la richiesta del ViewModel in
 * `navigate`, la conclusione della chiusura differita del player quando la scheda è composta, e
 * l'overlay del player composto SOPRA la destinazione attiva finché
 * `screenState == ScreenState.PLAYER`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class ContinueWatchingPlayerReturnNoFlashTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_CONTINUE_WATCHING = "section_continue_watching"
    const val PLAYER_TAG = "player_overlay"
    const val HOME_TAG = "home_destination"
    const val DETAIL_TAG = "detail_destination"

    /** Limite di sicurezza: la transizione completa deve concludersi in poche cornici. */
    const val MAX_FRAMES = 60
  }

  private lateinit var viewModel: StreamNovaViewModel
  private lateinit var navController: NavHostController

  /** Titolo in corso di visione: vive nella riga «Continua a guardare» della Home. */
  private val cwItem = MediaItem(
    id = "cw-3",
    title = "CW 3",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2202,
    tmdbId = 7002,
    currentProgressMs = 10 * 60_000L,
    totalDurationMs = 100 * 60_000L,
    lastWatchedAt = 3_000L
  )

  @Composable
  private fun App() {
    val screenState by viewModel.screenState.collectAsState()
    val selectedMedia by viewModel.selectedMedia.collectAsState()
    val playerDetailReturn by viewModel.playerDetailReturn.collectAsState()

    val controller = rememberNavController()
    SideEffect { navController = controller }

    // MainActivity: la richiesta di rientro dal player diventa la navigazione del NavHost, con
    // la scheda aperta in modalità "rientro dal player" (nessuna transizione).
    LaunchedEffect(playerDetailReturn) {
      val request = playerDetailReturn ?: return@LaunchedEffect
      viewModel.consumePlayerDetailReturn()
      if (controller.currentDestination?.route != Screen.Detail.route) {
        controller.navigate(
          Screen.Detail.createRoute(
            request.media.type,
            request.media.tmdbId ?: 0,
            fromPlayer = true
          )
        )
      } else {
        viewModel.completePlayerClose()
      }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      NavHost(navController = controller, startDestination = Screen.Browsing.route) {
        composable(
          route = Screen.Browsing.route,
          exitTransition = {
            if (targetState.arguments?.getBoolean(Screen.Detail.ARG_FROM_PLAYER) == true) {
              ExitTransition.None
            } else {
              null
            }
          }
        ) {
          // Home: l'unica destinazione che può restare sotto l'overlay del player.
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(Color(0xFF300000))
              .testTag(HOME_TAG)
          )
        }
        composable(
          route = Screen.Detail.route,
          arguments = Screen.Detail.arguments,
          enterTransition = {
            if (targetState.arguments?.getBoolean(Screen.Detail.ARG_FROM_PLAYER) == true) {
              EnterTransition.None
            } else {
              null
            }
          }
        ) { backStackEntry ->
          // MainActivity: la scheda è composta dietro l'overlay del player — solo ora la
          // chiusura differita del player si conclude.
          if (backStackEntry.arguments?.getBoolean(Screen.Detail.ARG_FROM_PLAYER) == true) {
            LaunchedEffect(Unit) { viewModel.completePlayerClose() }
          }
          // Scheda Dettaglio: la destinazione del rientro, col contenuto preparato dal ViewModel.
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(Color(0xFF003000))
              .testTag(DETAIL_TAG)
          ) {
            Text(text = selectedMedia?.title.orEmpty(), color = Color.White)
          }
        }
      }
      // Overlay del player: a tutto schermo e coprente, sopra la destinazione attiva.
      if (screenState == ScreenState.PLAYER) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(PLAYER_TAG)
        )
      }
    }
  }

  private fun render() {
    viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    rule.setContent { App() }
    rule.waitForIdle()
  }

  /** true se la scena composta in questa cornice contiene il nodo con [tag]. */
  private fun nodePresent(tag: String): Boolean =
    rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

  @Before
  fun setUp() {
    // Il ViewModel reale avvia il caricamento dei metadati: nei test le coroutine principali
    // NON devono girare (nessuna rete), come negli altri test del ViewModel.
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /**
   * Il percorso segnalato: «Continua a guardare» -> player -> BACK.
   *
   * La sequenza del BACK è quella REALE del player (`resetStreamState()` + `closePlayer()`);
   * da lì in poi agisce la stessa catena di MainActivity (richiesta del ViewModel ->
   * navigazione del NavHost -> conclusione della chiusura del player).
   */
  @Test
  fun ilBackDalPlayerNonMostraLaHomeNemmenoPerUnFrame() {
    render()

    // Riproduzione avviata dalla card di «Continua a guardare»: stessa sequenza della card
    // (provenienza registrata al click + avvio della riproduzione).
    rule.runOnIdle {
      viewModel.savePlaybackFocusOrigin(ROW_CONTINUE_WATCHING, cwItem.id)
      viewModel.openPlayer(cwItem)
    }
    rule.waitForIdle()
    assertTrue("il player deve essere a schermo prima del BACK", nodePresent(PLAYER_TAG))

    // Cornice per cornice: il frame clock è guidato dal test, nessuna attesa arbitraria.
    rule.mainClock.autoAdvance = false
    rule.runOnIdle {
      viewModel.resetStreamState()
      viewModel.closePlayer()
    }

    var detailSeen = false
    var frames = 0
    while (frames < MAX_FRAMES && !detailSeen) {
      rule.mainClock.advanceTimeByFrame()
      frames++
      val overlay = nodePresent(PLAYER_TAG)
      val home = nodePresent(HOME_TAG)
      val detail = nodePresent(DETAIL_TAG)
      assertFalse(
        "cornice $frames: la Home è visibile durante il rientro dal player " +
          "(overlay=$overlay home=$home detail=$detail)",
        home && !overlay
      )
      assertTrue(
        "cornice $frames: nessuna destinazione a schermo tra il player e la scheda " +
          "(overlay=$overlay home=$home detail=$detail)",
        overlay || detail
      )
      if (detail) detailSeen = true
    }
    assertTrue(
      "dopo il BACK la scheda Dettaglio non è mai comparsa (cornici avanzate=$frames)",
      detailSeen
    )

    // Stato stabile: a schermo resta la scheda, mai la Home.
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()
    assertFalse("la Home non deve restare a schermo dopo il rientro", nodePresent(HOME_TAG))
    assertTrue("la scheda Dettaglio deve essere a schermo", nodePresent(DETAIL_TAG))
    assertEquals(
      "la destinazione corrente deve essere la scheda Dettaglio",
      Screen.Detail.route,
      navController.currentDestination?.route
    )
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertEquals("cw-3", viewModel.selectedMedia.value?.id)
  }
}
