package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.domain.model.Subtitle
import com.example.ui.viewmodel.ScreenState
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BACK DEL TELECOMANDO: UNA PRESSIONE FISICA = UNA SOLA AZIONE.
 *
 * Un tasto BACK reale produce un KEY_DOWN e un KEY_UP separati da decine di millisecondi.
 * Sulle TV con dispatch "legacy" del BACK (Android 11 e precedenti) `Activity.onKeyUp`
 * chiama `onBackPressed()` solo se il KEY_DOWN ha armato il tracking: un DOWN consumato
 * nella gerarchia di viste faceva perdere la pressione e serviva un secondo BACK per uscire
 * dal player (difetto riscontrato sul telecomando di una TV reale).
 *
 * Il player ora gestisce il tasto fisico direttamente in `onPreviewKeyEvent`: consuma il
 * DOWN senza eseguire nulla e agisce UNA volta sul rilascio. Il `BackHandler` resta per i
 * BACK avviati dal sistema (predictive back), che non hanno un KEY_DOWN del player.
 *
 * I test preesistenti premevano BACK con `onBackPressedDispatcher.onBackPressed()` (solo
 * la fase di rilascio): non potevano vedere la consegna reale. Qui si usa il [PlayerScreen]
 * REALE e si iniettano gli eventi di tasto grezzi, DOWN e UP separati da frame di
 * composizione, come fa un telecomando vero.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class PlayerBackKeySingleDeliveryTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val DETAIL_ROUTE = "detail"
    const val PLAYER_ROOT_TAG = "player_screen_root"
  }

  private lateinit var viewModel: StreamNovaViewModel
  private lateinit var navController: NavHostController
  private var detailPops = 0

  private fun movie() = MediaItem(
    id = "media-player-back",
    title = "Film per il BACK",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2024,
    tmdbId = 0
  )

  private fun createViewModel(): StreamNovaViewModel =
    StreamNovaViewModel(ioDispatcher = StandardTestDispatcher()).also { vm ->
      // Nessuna rete: la ricerca sottotitoli del player risponde subito vuota.
      vm.openSubtitlesProvider = object : OpenSubtitlesSubtitleProvider() {
        override suspend fun fetchSubtitles(
          mediaItem: MediaItem,
          episode: Episode?,
          preferredLanguage: String?,
          year: Int?,
          resolveLinksOnDemand: Boolean
        ) = emptyList<Subtitle>()
      }
    }

  @Composable
  private fun App() {
    navController = rememberNavController()
    val screenState by viewModel.screenState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      NavHost(navController = navController, startDestination = DETAIL_ROUTE) {
        composable(DETAIL_ROUTE) {
          // Stesso contratto della destinazione Dettaglio della MainActivity: attivo solo
          // quando il player NON è aperto, e il suo BACK poppa la destinazione.
          BackHandler(enabled = screenState != ScreenState.PLAYER) {
            detailPops++
            navController.popBackStack()
          }
          Box(modifier = Modifier.fillMaxSize())
        }
      }

      // Stesso ordine della MainActivity: BackHandler del player dopo il NavHost, poi
      // l'overlay reale del player (che gestisce il tasto fisico in preview).
      BackHandler(enabled = screenState == ScreenState.PLAYER) {
        viewModel.closePlayer()
      }

      if (screenState == ScreenState.PLAYER) {
        PlayerScreen(
          viewModel = viewModel,
          modifier = Modifier.testTag(PLAYER_ROOT_TAG)
        )
      }
    }
  }

  private fun render() {
    viewModel = createViewModel()
    detailPops = 0
    val media = movie()
    viewModel.openDetail(media)
    viewModel.openPlayer(media)
    rule.setContent { App() }
    rule.waitForIdle()
  }

  private fun activity(): androidx.activity.ComponentActivity =
    (rule as AndroidComposeTestRule<*, *>).activity as androidx.activity.ComponentActivity

  /**
   * Prima metà di una pressione fisica: il KEY_DOWN arriva al nodo focalizzato del player.
   * Non deve eseguire alcuna azione (l'uscita avviene sul rilascio).
   */
  private fun backKeyDown() {
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { keyDown(Key.Back) }
    rule.waitForIdle()
  }

  /**
   * Seconda metà: il KEY_UP arriva allo stesso nodo. Il player lo consuma e chiude
   * l'overlay UNA sola volta, quindi l'`onBackPressed()` dell'Activity non viene
   * interpellato (sul dispatch legacy il DOWN consumato non arma il tracking).
   */
  private fun backKeyUp() {
    rule.mainClock.advanceTimeBy(64)
    rule.waitForIdle()
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { keyUp(Key.Back) }
    rule.waitForIdle()
  }

  /** BACK di sistema (nessun KEY_DOWN del player), es. predictive back / dispatcher. */
  private fun backViaActivityDispatcher() {
    rule.mainClock.advanceTimeBy(64)
    rule.waitForIdle()
    rule.runOnUiThread {
      activity().onBackPressedDispatcher.onBackPressed()
    }
    rule.waitForIdle()
  }

  @Test
  fun ilKeyDownDaSoloNonChiudeIlPlayer() {
    render()
    assertEquals(ScreenState.PLAYER, viewModel.screenState.value)

    backKeyDown()

    assertEquals(
      "il KEY_DOWN non deve eseguire l'uscita: l'azione avviene sul rilascio",
      ScreenState.PLAYER,
      viewModel.screenState.value
    )
    assertEquals(0, detailPops)
  }

  @Test
  fun backConDownEUpSeparatiChiudeIlPlayerSenzaPoppareIlDettaglio() {
    render()
    assertEquals(ScreenState.PLAYER, viewModel.screenState.value)
    val tokenBefore = viewModel.playerClosedToken.value

    backKeyDown()
    backKeyUp()

    // La destinazione Dettaglio è ancora quella attiva: nessun pop verso la Home.
    assertEquals(
      "il BACK dal player ha poppato la destinazione Dettaglio",
      0,
      detailPops
    )
    assertEquals(
      "il player deve chiudersi lasciando il Dettaglio come schermata attiva",
      ScreenState.DETAIL,
      viewModel.screenState.value
    )
    assertEquals(
      "la chiusura del player deve emettere UN solo segnale di rientro",
      tokenBefore + 1,
      viewModel.playerClosedToken.value
    )
    assertEquals(
      "la route attiva deve restare quella del Dettaglio",
      DETAIL_ROUTE,
      navController.currentDestination?.route
    )
  }

  @Test
  fun ilBackSuccessivoDalDettaglioContinuaATornareAllaHome() {
    render()
    backKeyDown()
    backKeyUp()
    assertEquals(0, detailPops)

    // BACK premuto nel Dettaglio (player già chiuso): unico passaggio che deve poppare.
    backViaActivityDispatcher()
    assertEquals(
      "il BACK nel Dettaglio deve continuare a tornare alla Home",
      1,
      detailPops
    )
  }

  @Test
  fun ilBackDiSistemaSenzaKeyDownContinuaAChiudereIlPlayer() {
    render()
    assertEquals(ScreenState.PLAYER, viewModel.screenState.value)
    val tokenBefore = viewModel.playerClosedToken.value

    // Predictive back / dispatcher: nessun KEY_DOWN consegnato al player.
    backViaActivityDispatcher()

    assertEquals(0, detailPops)
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertEquals(tokenBefore + 1, viewModel.playerClosedToken.value)
  }
}
