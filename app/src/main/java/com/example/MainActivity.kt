package com.example

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.model.MediaType
import com.example.data.repository.MediaRepository
import com.example.data.streaming.StreamResult
import com.example.ui.components.ProviderConstants
import com.example.ui.components.TvPinDialog
import com.example.ui.components.SidebarNavigation
import com.example.ui.components.SourceItem
import com.example.ui.components.SourceSelectionDialog
import com.example.ui.components.StreamStatusOverlay
import com.example.ui.components.StreamingProvider
import com.example.ui.navigation.DetailNavArgs
import com.example.ui.navigation.Screen
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.ProviderScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NovaBackground
import com.example.ui.viewmodel.ScreenState
import com.example.ui.viewmodel.SidebarSection
import com.example.ui.viewmodel.StreamNovaViewModel
import com.example.ui.viewmodel.StreamResolutionState
import kotlinx.coroutines.delay

/** Finestra temporale del doppio Back sulla Home per uscire dall'app (feedback breve). */
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

/** Durata della Splash Screen iniziale, ottenuta con coroutine/delay (mai Thread.sleep). */
private const val SPLASH_DURATION_MS = 10_000L

class MainActivity : ComponentActivity() {
  private val viewModel: StreamNovaViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MediaRepository.init(applicationContext)
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    enableEdgeToEdge()
    setContent {
      val appLanguage by viewModel.appLanguage.collectAsState()
      val context = LocalContext.current
      val currentLocale = remember(appLanguage) { java.util.Locale(appLanguage) }
      val localizedContext = remember(context, currentLocale) {
        val config = android.content.res.Configuration(context.resources.configuration).apply {
          setLocale(currentLocale)
          setLayoutDirection(currentLocale)
        }
        context.createConfigurationContext(config)
      }
      val localizedConfiguration = remember(localizedContext, currentLocale) {
        localizedContext.resources.configuration
      }

      androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalContext provides localizedContext,
        androidx.compose.ui.platform.LocalConfiguration provides localizedConfiguration
      ) {
        MyApplicationTheme {
          StreamNovaApp(viewModel = viewModel)
        }
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
  val streamResult by viewModel.streamResult.collectAsState()
  val streamResolutionState by viewModel.streamResolutionState.collectAsState()
  val streamingEngineMode by viewModel.streamingEngineMode.collectAsState()

  // Feedback errore di risoluzione sorgenti (es. timeout o nessuna sorgente):
  // mostra un toast con il messaggio di errore e reimposta lo stato su Idle
  LaunchedEffect(streamResolutionState) {
    val error = streamResolutionState as? StreamResolutionState.Error ?: return@LaunchedEffect
    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
    viewModel.resetStreamState()
  }

  // Timestamp dell'ultima pressione di Back sulla Home (doppio Back per uscire)
  var lastExitRequestAt by remember { mutableLongStateOf(0L) }

  // Flag: ripristina il focus sul contenuto (riga/card) quando si torna dal Dettaglio
  var pendingContentFocusRestore by remember { mutableStateOf(false) }

  // Splash Screen iniziale cinematografica StreamNova
  var isSplashActive by remember { mutableStateOf(true) }

  // Fine Splash: coroutine sospesa (delay) sullo scope di Compose, NON blocca il
  // Main Thread e non congela il rendering. Al termine dei 10s la Splash viene
  // smontata e resta esposto il normale contenuto iniziale (NavHost -> Home).
  LaunchedEffect(Unit) {
    delay(SPLASH_DURATION_MS)
    isSplashActive = false
  }

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
            Toast.makeText(context, context.getString(R.string.press_back_again_to_exit), Toast.LENGTH_SHORT).show()
          }
        }

        // Stato apertura sidebar: false sin dal primo frame.
        // Viene impostato a true ESCLUSIVAMENTE quando LEFT rileva che il focus ha
        // raggiunto il bordo sinistro (nessuna card Home a sinistra).
        var isSidebarExpanded by remember { mutableStateOf(false) }

        // FocusRequester per la sidebar (usato per spostare il focus al primo item).
        val sidebarFocusRequester = remember { FocusRequester() }

        // FocusRequester per il Box Home (focus iniziale esplicito post-splash).
        val homeFocusRequester = remember { FocusRequester() }

        // Tracking SOLO LETTURA: diventa true quando un qualsiasi figlio della sidebar
        // ha il focus. NON viene mai usato per aprire/chiudere la sidebar.
        // Serve in onPreviewKeyEvent per distinguere "moveFocus(Left) è andato sulla sidebar"
        // da "moveFocus(Left) è andato su una card Home". Il rilevamento è sincrono:
        // onFocusChanged scatta dentro moveFocus() prima che esso ritorni.
        var sidebarHasFocus by remember { mutableStateOf(false) }

        // Focus iniziale esplicito sul contenuto Home dopo la fine della splash.
        val focusManager = LocalFocusManager.current
        var isInitialHomeFocusSet by remember { mutableStateOf(false) }

        LaunchedEffect(screenState, isSplashActive) {
          if (screenState == ScreenState.BROWSING && !isSplashActive) {
            if (!isInitialHomeFocusSet || pendingContentFocusRestore) {
              isInitialHomeFocusSet = true
              pendingContentFocusRestore = false
              if (currentSection == SidebarSection.HOME) {
                try { homeFocusRequester.requestFocus() } catch (_: Exception) {}
                focusManager.moveFocus(FocusDirection.Down)
              }
            }
          }
        }

        LaunchedEffect(isSidebarExpanded) {
          if (isSidebarExpanded) {
            try {
              sidebarFocusRequester.requestFocus()
            } catch (_: Exception) {}
          }
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .focusRequester(homeFocusRequester)
            // onPreviewKeyEvent: intercetta LEFT PRIMA che i figli lo ricevano.
            //
            // PROBLEMA RISOLTO: i TvFocusableBox della sidebar sono fisicamente a sinistra
            // delle card Home anche quando la sidebar è chiusa (60dp). Quindi:
            //   - moveFocus(Left) da una card non-prima → va alla card precedente → true, sidebarHasFocus=false
            //   - moveFocus(Left) dalla prima card → va a un icon sidebar → true, sidebarHasFocus=true
            //   - moveFocus(Left) quando non c'è nulla → false
            //
            // sidebarHasFocus è aggiornato SINCRONO dentro moveFocus() (onFocusChanged del
            // modifier esterno scatta prima che moveFocus() ritorni), quindi la lettura
            // immediata dopo moveFocus() rispecchia sempre lo stato reale del focus.
            .onPreviewKeyEvent { keyEvent ->
              if (!isSidebarExpanded &&
                keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
              ) {
                val moved = focusManager.moveFocus(FocusDirection.Left)
                when {
                  // CASO A: focus andato su un icon sidebar (prima card Home, bordo sinistro).
                  // sidebarHasFocus è già true perché onFocusChanged ha scattato sincrono
                  // dentro moveFocus(). Apriamo la sidebar e impostiamo il focus sul primo item.
                  sidebarHasFocus -> {
                    isSidebarExpanded = true
                    try { sidebarFocusRequester.requestFocus() } catch (_: Exception) {}
                    true
                  }
                  // CASO B: focus andato su una card Home a sinistra. Navigazione normale.
                  moved -> true
                  // CASO C: nessun elemento a sinistra (non dovrebbe accadere con la sidebar
                  // presente, ma lo gestiamo per sicurezza).
                  else -> {
                    isSidebarExpanded = true
                    try { sidebarFocusRequester.requestFocus() } catch (_: Exception) {}
                    true
                  }
                }
              } else {
                false
              }
            }
        ) {
          // Main Screen Content — larghezza e posizione fisse, non si sposta mai.
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
            modifier = Modifier
              .fillMaxSize()
              .padding(start = 60.dp)
          )

          // Left Sidebar Menu — overlay sopra la Home.
          // onFocusChanged ESTERNO aggiorna sidebarHasFocus (solo tracking, NON colapsa la sidebar).
          SidebarNavigation(
            currentSection = currentSection,
            onSectionSelected = {
              if (it == SidebarSection.CERCA) {
                isSidebarExpanded = false
              }
              viewModel.setSection(it)
            },
            isSidebarExpanded = isSidebarExpanded,
            onCollapseRequest = { isSidebarExpanded = false },
            modifier = Modifier
              .align(Alignment.CenterStart)
              .focusRequester(sidebarFocusRequester)
              .onFocusChanged { sidebarHasFocus = it.hasFocus }
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
        BackHandler(enabled = screenState != ScreenState.PLAYER) {
          viewModel.resetStreamState()
          pendingContentFocusRestore = true
          viewModel.backFromDetail()
          navController.popBackStack()
        }

        if (baseMedia != null) {
          DetailScreen(
            media = baseMedia,
            allMedia = allMedia,
            onBackClick = {
              viewModel.resetStreamState()
              pendingContentFocusRestore = true
              viewModel.backFromDetail()
              navController.popBackStack()
            },
            onPlayClick = { m, ep -> viewModel.openStreamForMedia(m, ep) },
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
              viewModel.openStreamForMedia(baseMedia, episode)
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
          viewModel = viewModel,
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

    BackHandler(enabled = screenState == ScreenState.PLAYER) {
      Log.d("BACK_TRACE", "MAINACTIVITY: ingresso del BackHandler")
      Log.d("BACK_TRACE", "MAINACTIVITY: valore di screenState: $screenState")
      // Chiude il player: la riproduzione si ferma con il dispose di PlayerScreen e il
      // progresso viene salvato in Room (closePlayer). Se il player è stato aperto dal
      // Dettaglio (catalogo -> Detail -> Player), closePlayer() ripristina la sezione
      // (CERCA/altro) e qui si esegue solo il popBackStack per tornare al Detail.
      Log.d("BACK_TRACE", "MAINACTIVITY: chiamata a closePlayer()")
      viewModel.resetStreamState()
      viewModel.closePlayer()
      if (viewModel.playerOpenedFromDetail()) {
        Log.d("BACK_TRACE", "MAINACTIVITY: eventuale popBackStack() eseguito")
        navController.popBackStack()
      } else {
        Log.d("BACK_TRACE", "MAINACTIVITY: eventuale popBackStack() non eseguito")
      }
    }

    // BackHandler per annullare la ricerca in corso se l'utente preme BACK sul telecomando
    BackHandler(enabled = streamResolutionState is StreamResolutionState.Loading) {
      Log.d("BACK_TRACE", "MAINACTIVITY: cancellazione ricerca stream al BACK")
      viewModel.resetStreamState()
    }

    // Feedback "Ricerca sorgenti in corso..." durante l'estrazione del provider:
    // Visibile SOLO se streamResolutionState is StreamResolutionState.Loading.
    if (streamResolutionState is StreamResolutionState.Loading) {
      StreamStatusOverlay(streamingEngineMode = streamingEngineMode)
    }

    val showSourceDialog by viewModel.showSourceDialog.collectAsState()
    val availableSources by viewModel.availableSources.collectAsState()
    if (showSourceDialog && availableSources.isNotEmpty()) {
      val sourceItems = availableSources.map { source ->
        SourceItem(
          source = source,
          isItalian = source.isItalian,
          resolutionBadge = source.quality,
          sourceName = source.serverName,
          codecBadge = source.codec,
          addonName = source.addonName,
          instantTag = source.instantTag,
          releaseTitle = source.releaseTitle,
          details = source.details,
          releaseType = source.releaseType
        )
      }
      SourceSelectionDialog(
        sources = sourceItems,
        onSelect = { item -> viewModel.selectSource(item.source) },
        onDismiss = { viewModel.dismissSourceDialog() }
      )
    }

    val showParentalPinDialog by viewModel.showParentalPinDialog.collectAsState()
    val parentalPinErrorResId by viewModel.parentalPinErrorResId.collectAsState()
    if (showParentalPinDialog) {
      TvPinDialog(
        title = stringResource(R.string.parental_playback_restricted_title),
        subtitle = stringResource(R.string.parental_playback_restricted_subtitle),
        errorMessage = parentalPinErrorResId?.let { stringResource(it) },
        onPinSubmit = { pin ->
          viewModel.verifyParentalPinForPlayback(pin)
        },
        onDismiss = {
          viewModel.dismissParentalPinDialog()
        }
      )
    }

    // Player Screen overlay when media playback is requested
    if (screenState == ScreenState.PLAYER) {
      PlayerScreen(viewModel = viewModel)
    }

    if (isSplashActive) {
      SplashScreen()
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
    Box(modifier = Modifier.fillMaxSize().background(NovaBackground)) {
      HomeScreen(
        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
        modifier = Modifier.fillMaxSize().padding(start = 60.dp)
      )
      SidebarNavigation(
        currentSection = SidebarSection.HOME,
        onSectionSelected = {},
        isSidebarExpanded = false,
        onCollapseRequest = {},
        modifier = Modifier.align(Alignment.CenterStart)
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  MyApplicationTheme { Greeting("StreamNova TV") }
}
