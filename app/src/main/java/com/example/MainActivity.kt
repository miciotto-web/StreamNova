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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.MediaRepository
import com.example.data.streaming.StreamResult
import com.example.ui.components.HeroCarouselState
import com.example.ui.components.ModalOverlayState
import com.example.ui.components.ProviderConstants
import com.example.ui.components.TvPinDialog
import com.example.ui.components.SidebarNavigation
import com.example.ui.components.TorBoxSourceSelectionScreen
import com.example.ui.components.StreamStatusOverlay
import com.example.ui.components.StreamingProvider
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.navigation.DetailNavArgs
import com.example.ui.navigation.Screen
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.ProviderScreen
import com.example.ui.screens.PersonDetailScreen
import com.example.ui.screens.CompanyDetailScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.TrailerErrorOverlay
import com.example.ui.screens.TrailerLoadingOverlay
import com.example.ui.screens.TrailerPlayerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NovaBackground
import com.example.ui.viewmodel.ScreenState
import com.example.ui.viewmodel.SidebarSection
import com.example.ui.viewmodel.StreamNovaViewModel
import com.example.ui.viewmodel.StreamResolutionState
import com.example.ui.viewmodel.TrailerPlaybackState
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
          StreamNovaApp(viewModel = viewModel, activity = this@MainActivity)
        }
      }
    }
  }
}

@Composable
fun StreamNovaApp(
  viewModel: StreamNovaViewModel,
  activity: Activity,
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
  val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()

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
        // Stato apertura sidebar: false sin dal primo frame.
        var isSidebarExpanded by remember { mutableStateOf(false) }

        // FocusRequester per la sidebar e mappa per ogni singolo item (Punto 5: memoria focus)
        val sidebarFocusRequester = remember { FocusRequester() }
        val sidebarItemFocusRequesters = remember { SidebarSection.entries.associateWith { FocusRequester() } }

        // (4) Sezione laterale != Home -> se la sidebar è chiusa la apre posizionandosi sul pulsante attivo;
        // se la sidebar è già aperta, torna alla Home
        BackHandler(
          enabled = screenState == ScreenState.BROWSING && currentSection != SidebarSection.HOME
        ) {
          if (!isSidebarExpanded) {
            isSidebarExpanded = true
            try {
              (sidebarItemFocusRequesters[currentSection] ?: sidebarFocusRequester).requestFocus()
            } catch (_: Exception) {}
          } else {
            viewModel.setSection(SidebarSection.HOME)
            try {
              (sidebarItemFocusRequesters[SidebarSection.HOME] ?: sidebarFocusRequester).requestFocus()
            } catch (_: Exception) {}
          }
        }

        // (5) Home -> doppio Back con feedback breve, poi uscita regolare (nessun loop/blocco)
        BackHandler(
          enabled = screenState == ScreenState.BROWSING && currentSection == SidebarSection.HOME
        ) {
          val now = SystemClock.elapsedRealtime()
          if (now - lastExitRequestAt < EXIT_CONFIRM_WINDOW_MS) {
            // Uscita regolare dell'Activity (il processo resta vivo).
            // NOTA: qui `context` è il contesto localizzato creato con
            // createConfigurationContext e NON è un'Activity, quindi si usa
            // l'Activity passata da MainActivity.
            activity.finishAndRemoveTask()
          } else {
            lastExitRequestAt = now
            Toast.makeText(context, context.getString(R.string.press_back_again_to_exit), Toast.LENGTH_SHORT).show()
          }
        }

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

        LaunchedEffect(screenState, isSplashActive, pendingContentFocusRestore, isInitialHomeFocusSet) {
          if (screenState == ScreenState.BROWSING && !isSplashActive) {
            if (!isInitialHomeFocusSet) {
              isInitialHomeFocusSet = true
              if (currentSection == SidebarSection.HOME) {
                // Focus iniziale sull'Hero, prima cella del contenuto Home: il
                // moveFocus(Down) da un contenitore a tutto schermo non lo raggiunge mai,
                // perche' la ricerca geometrica preferisce la card provider piu' vicina al
                // centro dello schermo. HomeScreen completa la richiesta appena i dati della
                // hero sono pronti (i cataloghi TMDB arrivano dopo la splash).
                viewModel.requestHomeScrollToTop()
              }
            } else if (pendingContentFocusRestore) {
              pendingContentFocusRestore = false
              // Se abbiamo un target specifico salvato, emetti il segnale di ripristino
              if (viewModel.lastFocusedMediaId.value != null) {
                viewModel.signalFocusRestore(viewModel.lastFocusedMediaId.value!!)
              } else {
                // Fallback: nessun target salvato, focus generico
                try { homeFocusRequester.requestFocus() } catch (_: Exception) {}
                focusManager.moveFocus(FocusDirection.Down)
              }
            }
          }
        }

        LaunchedEffect(isSidebarExpanded) {
          if (isSidebarExpanded) {
            try {
              (sidebarItemFocusRequesters[currentSection] ?: sidebarFocusRequester).requestFocus()
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
            //
            // HeroCarouselState.consumesLeftKey: con il focus su un pulsante dell'Hero e un
            // poster successivo al primo, il LEFT appartiene al carosello (torna al poster
            // precedente) e questa intercettazione deve lasciarlo passare. Sul primo poster
            // resta invece false, quindi il LEFT esce regolarmente verso la Sidebar.
            .onPreviewKeyEvent { keyEvent ->
              if (!ModalOverlayState.isAnyOpen &&
                !isSidebarExpanded &&
                !HeroCarouselState.consumesLeftKey &&
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
                    try {
                      (sidebarItemFocusRequesters[currentSection] ?: sidebarFocusRequester).requestFocus()
                    } catch (_: Exception) {}
                    true
                  }
                  // CASO B: focus andato su una card Home a sinistra. Navigazione normale.
                  moved -> true
                  // CASO C: nessun elemento a sinistra (non dovrebbe accadere con la sidebar
                  // presente, ma lo gestiamo per sicurezza).
                  else -> {
                    isSidebarExpanded = true
                    try {
                      (sidebarItemFocusRequesters[currentSection] ?: sidebarFocusRequester).requestFocus()
                    } catch (_: Exception) {}
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
              viewModel.saveLastFocusedMedia(media.id)
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
            itemFocusRequesters = sidebarItemFocusRequesters,
            onSectionSelected = { section ->
              if (section == SidebarSection.HOME) {
                isSidebarExpanded = false
                viewModel.requestHomeScrollToTop()
              }
              if (section == SidebarSection.CERCA) {
                isSidebarExpanded = false
              }
              viewModel.setSection(section)
            },
            isSidebarExpanded = isSidebarExpanded,
            onCollapseRequest = { isSidebarExpanded = false },
            // La voce "Addon" compare nella sidebar solo in modalità Debrid TorBox:
            // in Web Streaming (HTTP) gli addon non alimentano la risoluzione sorgenti.
            showAddons = streamingEngineMode.supportsAddonsSection,
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
        var cachedMedia by remember(backStackEntry.id) { mutableStateOf<MediaItem?>(null) }
        val currentFromVm = selectedMedia?.takeIf { (args.tmdbId > 0 && it.tmdbId == args.tmdbId) || args.tmdbId <= 0 }
        if (currentFromVm != null && cachedMedia != currentFromVm) {
          cachedMedia = currentFromVm
        }
        val baseMedia = currentFromVm
          ?: cachedMedia
          ?: allMedia.find { args.tmdbId > 0 && it.tmdbId == args.tmdbId }
          ?: selectedMedia

        val selectedSeasonNumber by viewModel.selectedSeasonNumber.collectAsState()
        val seasonEpisodesUiState by viewModel.seasonEpisodesUiState.collectAsState()
        val lastFocusedCastMemberId by viewModel.lastFocusedCastMemberId.collectAsState()
        val lastFocusedCompanyId by viewModel.lastFocusedCompanyId.collectAsState()

        // (2) Back dal Dettaglio: popBackStack verso la schermata precedente.
        // Se c'erano dettagli aperti a catena dai Consigliati, risale alla scheda precedente
        // ripristinandone stato e metadati. Solo quando lo stack è esaurito si torna a Home/Provider.
        BackHandler(enabled = screenState != ScreenState.PLAYER) {
          val hasPreviousDetail = viewModel.popDetail()
          if (!hasPreviousDetail) {
            pendingContentFocusRestore = true
          }
          navController.popBackStack()
        }

        if (baseMedia != null) {
          val currentMedia = when (val state = detailUiState) {
            is MediaDetailUiState.Success -> {
              val candidate = state.media
              if ((candidate.tmdbId != null && candidate.tmdbId > 0 && (candidate.tmdbId == baseMedia.tmdbId || baseMedia.tmdbId == null || baseMedia.tmdbId <= 0)) || (baseMedia.id.isNotBlank() && (candidate.id.equals(baseMedia.id, ignoreCase = true) || baseMedia.id.startsWith("tt", ignoreCase = true)))) {
                candidate
              } else {
                baseMedia
              }
            }
            is MediaDetailUiState.Loading -> state.baseMedia ?: baseMedia
            is MediaDetailUiState.Error -> state.baseMedia ?: baseMedia
            else -> baseMedia
          }

          CompositionLocalProvider(
            LocalLastFocusedMediaId provides lastFocusedMediaId,
            LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() }
          ) {
            DetailScreen(
            media = currentMedia,
            allMedia = allMedia,
            onBackClick = {
              val hasPreviousDetail = viewModel.popDetail()
              if (!hasPreviousDetail) {
                pendingContentFocusRestore = true
              }
              navController.popBackStack()
            },
            onPlayClick = { m, ep -> viewModel.openStreamForMedia(m, ep) },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onToggleWatched = { viewModel.toggleWatched(it) },
            onPlayTrailer = { viewModel.playTrailer(it) },
            onProviderClick = { provider ->
              viewModel.openProvider(provider)
              navController.navigate(Screen.Provider.createRoute(provider.id))
            },
            onMediaClick = { nextMedia ->
              viewModel.saveLastFocusedMedia(nextMedia.id)
              viewModel.openDetail(nextMedia)
              val nextTmdbId = nextMedia.tmdbId ?: 0
              navController.navigate(Screen.Detail.createRoute(nextMedia.type, nextTmdbId))
            },
            detailUiState = detailUiState,
            selectedSeasonNumber = selectedSeasonNumber,
            seasonEpisodesUiState = seasonEpisodesUiState,
            onSeasonChange = { media, seasonNum ->
              viewModel.loadEpisodesForSeason(media, seasonNum)
            },
            onEpisodeClick = { media, episode ->
              viewModel.openStreamForMedia(media, episode)
            },
            onRetry = { viewModel.retryLoadDetail() },
            onCastMemberClick = { member ->
              viewModel.saveLastFocusedCastMember(member.id)
              navController.navigate(Screen.PersonDetail.createRoute(member.id, member.name))
            },
            onCompanyClick = { company ->
              viewModel.saveLastFocusedCompany(company.id)
              navController.navigate(Screen.CompanyDetail.createRoute(company.id, company.name, company.logoUrl))
            },
            targetCastMemberId = lastFocusedCastMemberId,
            onClearTargetCastMember = { viewModel.clearLastFocusedCastMember() },
            targetCompanyId = lastFocusedCompanyId,
            onClearTargetCompany = { viewModel.clearLastFocusedCompany() }
          )
          }
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
            viewModel.saveLastFocusedMedia(media.id)
            viewModel.openDetail(media)
            val tmdbId = media.tmdbId ?: 0
            navController.navigate(Screen.Detail.createRoute(media.type, tmdbId))
          }
        )
      }

      composable(
        route = Screen.PersonDetail.route,
        arguments = Screen.PersonDetail.arguments
      ) { backStackEntry ->
        val personId = backStackEntry.arguments?.getInt(Screen.PersonDetail.ARG_PERSON_ID) ?: 0
        val personName = backStackEntry.arguments?.getString(Screen.PersonDetail.ARG_PERSON_NAME)
        PersonDetailScreen(
          personId = personId,
          personName = personName,
          viewModel = viewModel,
          onBackClick = {
            navController.popBackStack()
          },
          onMediaClick = { media ->
            viewModel.saveLastFocusedMedia(media.id)
            viewModel.openDetail(media)
            val tmdbId = media.tmdbId ?: 0
            navController.navigate(Screen.Detail.createRoute(media.type, tmdbId))
          }
        )
      }

      composable(
        route = Screen.CompanyDetail.route,
        arguments = Screen.CompanyDetail.arguments
      ) { backStackEntry ->
        val companyId = backStackEntry.arguments?.getInt(Screen.CompanyDetail.ARG_COMPANY_ID) ?: 0
        val encodedName = backStackEntry.arguments?.getString(Screen.CompanyDetail.ARG_COMPANY_NAME).orEmpty()
        val encodedLogo = backStackEntry.arguments?.getString(Screen.CompanyDetail.ARG_LOGO_URL).orEmpty()
        val companyName = try { java.net.URLDecoder.decode(encodedName, "UTF-8") } catch (_: Exception) { encodedName }
        val logoUrl = try { java.net.URLDecoder.decode(encodedLogo, "UTF-8") } catch (_: Exception) { encodedLogo }

        CompanyDetailScreen(
          companyId = companyId,
          companyName = companyName.takeIf { it.isNotBlank() },
          logoUrl = logoUrl.takeIf { it.isNotBlank() },
          viewModel = viewModel,
          onBackClick = {
            navController.popBackStack()
          },
          onMediaClick = { media ->
            viewModel.saveLastFocusedMedia(media.id)
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
    val sourceDialogMedia by viewModel.sourceDialogMedia.collectAsState()
    if (showSourceDialog && availableSources.isNotEmpty() && sourceDialogMedia != null) {
      TorBoxSourceSelectionScreen(
        sources = availableSources,
        target = sourceDialogMedia,
        onSelect = { source -> viewModel.selectSource(source) },
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

    // Trailer interno (YouTube embedded): sopra ogni schermata, mai Intent esterno.
    val trailerState by viewModel.trailerState.collectAsState()
    when (val trailer = trailerState) {
      is TrailerPlaybackState.Playing -> TrailerPlayerScreen(
        youtubeKey = trailer.youtubeKey,
        onClose = { viewModel.closeTrailer() }
      )
      is TrailerPlaybackState.Error -> TrailerErrorOverlay(onClose = { viewModel.closeTrailer() })
      is TrailerPlaybackState.Loading -> TrailerLoadingOverlay(onClose = { viewModel.closeTrailer() })
      TrailerPlaybackState.Idle -> Unit
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
