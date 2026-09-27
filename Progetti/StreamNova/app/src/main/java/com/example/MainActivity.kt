package com.example

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.model.MediaType
import com.example.data.repository.MediaRepository
import com.example.ui.components.ProviderConstants
import com.example.ui.components.SidebarNavigation
import com.example.ui.components.StreamingProvider
import com.example.ui.navigation.DetailNavArgs
import com.example.ui.navigation.Screen
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.ProviderScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NovaBackground
import com.example.ui.viewmodel.ScreenState
import com.example.ui.viewmodel.SidebarSection
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.delay

/** Finestra temporale del doppio Back sulla Home per uscire dall'app (feedback breve). */
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

class MainActivity : ComponentActivity() {
  private val viewModel: StreamNovaViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MediaRepository.init(applicationContext)
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        StreamNovaApp(viewModel = viewModel)
      }
    }
  }
}

@Composable
fun StreamNovaApp(
  viewModel: StreamNovaViewModel,
  navController: NavHostController = rememberNavController()
) {
  val context = LocalContext.current
  val screenState by viewModel.screenState.collectAsState()
  val currentSection by viewModel.currentSection.collectAsState()
  val allMedia by viewModel.allMedia.collectAsState()
  val detailUiState by viewModel.detailUiState.collectAsState()

  // Timestamp dell'ultima pressione di Back sulla Home (doppio Back per uscire)
  var lastExitRequestAt by remember { mutableLongStateOf(0L) }

  // Flag: ripristina il focus sul contenuto (riga/card) quando si torna dal Dettaglio
  var pendingContentFocusRestore by remember { mutableStateOf(false) }

  /*
   * BACK GERARCHICO DEL TELECOMANDO TV — ordine di priorità:
   *   1) modale/dialogo aperto  -> chiude prima il modale (BackHandler interno a PlayerScreen/SubScreens)
   *   2) player in riproduzione -> chiude SOLO il player: ferma la riproduzione, salva il progresso
   *                                in Room e ripristina la schermata sottostante (Home o Dettaglio)
   *   3) Detail / Provider      -> navController.popBackStack() + stato ViewModel (torna alla Home)
   *   4) sezione laterale       -> torna alla sezione Home
   *   5) Home                   -> doppio Back con feedback breve, poi uscita regolare
   * Ogni livello è disabilitato quando un livello più alto è attivo, così è SEMPRE attivo
   * un solo gestore: niente loop, niente blocchi e nessuna pressione di Back ignorata.
   * Il gestore del player è inoltre composto DOPO il NavHost (in fondo al Box) per avere
   * priorità anche rispetto ai BackHandler dichiarati dentro le schermate.
   */

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    NavHost(
      navController = navController,
      startDestination = Screen.Browsing.route
    ) {
      composable(route = Screen.Browsing.route) {
        // (4) Sezione laterale != Home -> torna alla Home (mai mentre il player è attivo)
        BackHandler(
          enabled = screenState == ScreenState.BROWSING && currentSection != SidebarSection.HOME
        ) {
          viewModel.setSection(SidebarSection.HOME)
        }

        // (5) Home -> doppio Back con feedback breve, poi uscita regolare (nessun loop/blocco)
        BackHandler(
          enabled = screenState == ScreenState.BROWSING && currentSection == SidebarSection.HOME
        ) {
          val now = SystemClock.elapsedRealtime()
          if (now - lastExitRequestAt < EXIT_CONFIRM_WINDOW_MS) {
            (context as? Activity)?.finish()
          } else {
            lastExitRequestAt = now
            Toast.makeText(context, "Premi ancora Indietro per uscire", Toast.LENGTH_SHORT).show()
          }
        }

        // Ripristino del focus sul contenuto (riga/card) dopo il Back dal Dettaglio:
        // la schermata torna alla Home con lo scroll già ripristinato e il focus
        // viene riportato dalla sidebar alla griglia dei contenuti. Il flag NON è
        // una chiave dell'effetto: resettarlo non cancella il ciclo di retry.
        val focusManager = LocalFocusManager.current
        LaunchedEffect(screenState) {
          if (screenState == ScreenState.BROWSING && pendingContentFocusRestore) {
            pendingContentFocusRestore = false
            // Attende il completamento del teardown del Dettaglio e il settle di
            // layout/scroll della Home: senza questa attesa il framework riporta il
            // focus sul primo focusable (voce sidebar) subito dopo il nostro spostamento.
            delay(500)
            repeat(10) {
              if (focusManager.moveFocus(FocusDirection.Right)) return@LaunchedEffect
              delay(150)
            }
          }
        }

        Row(modifier = Modifier.fillMaxSize()) {
          // Left Sidebar Menu
          SidebarNavigation(
            currentSection = currentSection,
            onSectionSelected = { viewModel.setSection(it) }
          )

          // Main Screen Content
          HomeScreen(
            viewModel = viewModel,
            onProviderClick = { provider ->
              viewModel.openProvider(provider)
              navController.navigate(Screen.Provider.createRoute(provider.id))
            },
            onMediaClick = { media ->
              viewModel.openDetail(media)
              val tmdbId = media.tmdbId ?: 0
              navController.navigate(Screen.Detail.createRoute(media.type, tmdbId))
            },
            modifier = Modifier.weight(1f)
          )
        }
      }

      composable(
        route = Screen.Detail.route,
        arguments = Screen.Detail.arguments
      ) { backStackEntry ->
        val args = DetailNavArgs.fromBundle(backStackEntry.arguments)
        val selectedMedia by viewModel.selectedMedia.collectAsState()
        val baseMedia = selectedMedia?.takeIf { it.tmdbId == args.tmdbId }
          ?: allMedia.find { it.tmdbId == args.tmdbId }
          ?: allMedia.firstOrNull()

        val selectedSeasonNumber by viewModel.selectedSeasonNumber.collectAsState()
        val seasonEpisodesUiState by viewModel.seasonEpisodesUiState.collectAsState()

        // (3) Back dal Dettaglio: popBackStack verso la Home (o Provider) e azzera lo stato
        // del ViewModel, così la riga/card viene ripristinata alla navigazione precedente.
        // Disabilitato mentre il player è aperto: in quel caso chiude solo il player.
        BackHandler(enabled = screenState != ScreenState.PLAYER) {
          pendingContentFocusRestore = true
          viewModel.backFromDetail()
          navController.popBackStack()
        }

        if (baseMedia != null) {
          DetailScreen(
            media = baseMedia,
            allMedia = allMedia,
            onBackClick = {
              pendingContentFocusRestore = true
              viewModel.backFromDetail()
              navController.popBackStack()
            },
            onPlayClick = { m, ep -> viewModel.openPlayer(m, ep) },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onProviderClick = { provider ->
              viewModel.openProvider(provider)
              navController.navigate(Screen.Provider.createRoute(provider.id))
            },
            onMediaClick = { nextMedia ->
              viewModel.openDetail(nextMedia)
              val nextTmdbId = nextMedia.tmdbId ?: 0
              navController.navigate(Screen.Detail.createRoute(nextMedia.type, nextTmdbId))
            },
            detailUiState = detailUiState,
            selectedSeasonNumber = selectedSeasonNumber,
            seasonEpisodesUiState = seasonEpisodesUiState,
            onSeasonChange = { seasonNum ->
              viewModel.selectSeason(baseMedia.tmdbId ?: 0, seasonNum, baseMedia)
            },
            onEpisodeClick = { episode ->
              viewModel.openPlayer(baseMedia, episode)
            },
            onRetry = { viewModel.retryLoadDetail() }
          )
        }
      }

      composable(
        route = Screen.Provider.route,
        arguments = Screen.Provider.arguments
      ) { backStackEntry ->
        val providerId = backStackEntry.arguments?.getString(Screen.Provider.ARG_PROVIDER_ID)
        val selectedProvider by viewModel.selectedProvider.collectAsState()
        val provider = selectedProvider
          ?: ProviderConstants.ALL.find { it.id == providerId }
          ?: ProviderConstants.NETFLIX

        ProviderScreen(
          provider = provider,
          allMedia = allMedia,
          onBackClick = {
            viewModel.closeProvider()
            navController.popBackStack()
          },
          onMediaClick = { media ->
            viewModel.openDetail(media)
            val tmdbId = media.tmdbId ?: 0
            navController.navigate(Screen.Detail.createRoute(media.type, tmdbId))
          }
        )
      }
    }

    // (2) BACK sul player: composto DOPO il NavHost, quindi ha priorità su tutti i
    // gestori delle schermate sottostanti quando il player è visibile.
    BackHandler(enabled = screenState == ScreenState.PLAYER) {
      // Chiude esclusivamente il player: la riproduzione si ferma con il dispose di
      // PlayerScreen, il progresso viene salvato in Room (closePlayer + onDispose) e
      // si torna alla schermata sottostante (Detail se aperto dal dettaglio, altrimenti Home).
      viewModel.closePlayer()
    }

    // Player Screen overlay when media playback is requested
    if (screenState == ScreenState.PLAYER) {
      PlayerScreen(viewModel = viewModel)
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

@Preview(name = "TV 16:9 1080p", device = "id:tv_1080p", showBackground = true)
@Preview(name = "TV 16:9 720p", device = "id:tv_720p", showBackground = true)
@Composable
fun StreamNovaTv16NinePreview() {
  MyApplicationTheme {
    Row(modifier = Modifier.fillMaxSize().background(NovaBackground)) {
      SidebarNavigation(
        currentSection = SidebarSection.HOME,
        onSectionSelected = {}
      )
      HomeScreen(
        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
        modifier = Modifier.weight(1f)
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  MyApplicationTheme { Greeting("StreamNova TV") }
}
