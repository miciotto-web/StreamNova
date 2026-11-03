package com.aistudio.streamnova.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.components.ParentalPinDialog
import com.aistudio.streamnova.mobile.ui.screens.CatalogKind
import com.aistudio.streamnova.mobile.ui.screens.CatalogScreen
import com.aistudio.streamnova.mobile.ui.screens.DetailScreen
import com.aistudio.streamnova.mobile.ui.screens.FavoritesScreen
import com.aistudio.streamnova.mobile.ui.screens.HomeScreen
import com.aistudio.streamnova.mobile.ui.screens.MobileAddonsScreen
import com.aistudio.streamnova.mobile.ui.screens.MobilePlayerScreen
import com.aistudio.streamnova.mobile.ui.screens.MobileProviderScreen
import com.aistudio.streamnova.mobile.ui.screens.SearchScreen
import com.aistudio.streamnova.mobile.ui.screens.SettingsScreen
import com.example.data.model.MediaItem
import com.example.data.prefs.AppSettingsRepository
import com.example.data.repository.MediaRepository
import com.example.ui.components.ProviderConstants
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaTextMuted

/**
 * Shell adattiva: un'unica barra inferiore scorrevole in portrait, landscape e
 * tablet (Fase 8.4). Tutte le voci — Home, Film, Serie TV, Preferiti, Cerca,
 * Impostazioni e Addon — stanno su una sola riga orizzontale; le safe area
 * (`safeDrawing`) comprendono status/navigation bar e display cutout. Il player
 * resta a schermo intero e gestisce le proprie insets.
 *
 * Le voci e l'ordine sono quelli della TV (Home, Film, Serie TV, Preferiti,
 * Cerca); Addon e Impostazioni restano fuori da questa fase. Le safe area
 * (`safeDrawing`) comprendono status/navigation bar e display cutout; il player
 * resta a schermo intero e gestisce le proprie insets.
 *
 * [orientationController] è il controller Activity-scoped della Fase 8.1: viene
 * passato al player perché ingresso, stati intermedi e uscita usino la stessa
 * policy del resto dell'app.
 */
@Composable
fun MobileApp(orientationController: MobileOrientationController) {
  BoxWithConstraints {
    val expanded = maxWidth >= 840.dp
    CompositionLocalProvider(LocalWindowWidthExpanded provides expanded) {
      MobileAppContent(
        orientationController = orientationController
      )
    }
  }
}

@Composable
private fun MobileAppContent(
  orientationController: MobileOrientationController
) {
  val navController = rememberNavController()
  // Un solo ViewModel per l'intera shell: Home, cataloghi, ricerca e preferiti
  // condividono la stessa pipeline (un refresh TMDB, una risoluzione Cinemeta),
  // come il ViewModel unico della TV.
  val catalogViewModel: MobileCatalogViewModel = viewModel()
  // Solo un cambio lingua ESPLICITO dalle Impostazioni svuota la cache TMDB e
  // ricarica il catalogo: il caricamento iniziale da DataStore non è un cambio.
  LaunchedEffect(catalogViewModel) {
    MobileLanguageEvents.changes.collect {
      catalogViewModel.onAppLanguageChanged()
    }
  }
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route
  val isPlayer = currentRoute?.startsWith("player") == true
  // Il player è l'unica schermata senza chrome. Impostazioni e Addon sono voci
  // top-level della barra: la barra resta visibile e ne mostra la selezione.
  val showChrome = !isPlayer
  // La sezione Add-on esiste solo in modalità Debrid, come la TV: la modalità
  // motore è lo stesso dato condiviso di Impostazioni e player.
  val engineMode by AppSettingsRepository.streamingEngineMode.collectAsStateWithLifecycle()
  val showAddons = engineMode.supportsAddonsSection
  // «Seleziona fonte» esiste SOLO in modalità Debrid, come sulla TV: in
  // HTTP/direct il motore non espone un elenco di sorgenti negoziabile.
  val showSelectSource = MobileManualSourcePolicy.showsSelectSourceAction(engineMode)
  val destinations = visibleTopDestinations(showAddons)

  val parentalEnabled by AppSettingsRepository.parentalControlEnabled.collectAsStateWithLifecycle()
  val parentalLevel by AppSettingsRepository.parentalControlLevel.collectAsStateWithLifecycle()
  val savedParentalPin by AppSettingsRepository.parentalControlPin.collectAsStateWithLifecycle()
  val playbackController = remember { ParentalPlaybackController() }
  var pinPromptTitle by remember { mutableStateOf<String?>(null) }
  var pinInput by remember { mutableStateOf("") }
  var pinError by remember { mutableStateOf(false) }
  // La richiesta in attesa di PIN può essere una riproduzione oppure una
  // selezione manuale delle sorgenti: dopo il PIN l'utente deve tornare nella
  // schermata giusta.
  var pinPromptManualSelection by remember { mutableStateOf(false) }

  /**
   * Apre la scheda Dettaglio registrando prima il titolo nel catalogo condiviso.
   *
   * Le card Cinemeta/addon nascono dalle sezioni del catalogo e non sono in
   * `MediaRepository.mediaList`: senza questa registrazione la scheda non
   * troverebbe il titolo e preferiti/progressi non avrebbero una riga su cui
   * essere persistiti. La registrazione non sovrascrive un record esistente.
   */
  fun openDetail(media: MediaItem) {
    MediaRepository.registerExternalMedia(media)
    navController.navigate(MobileRoutes.detail(media.id))
  }

  /**
   * Unico punto d'ingresso alla riproduzione: Home, scheda dettaglio ed episodi
   * passano tutti da qui, quindi il PIN non può essere aggirato navigando altrove.
   */
  fun requestPlayback(media: MediaItem, season: Int?, episode: Int?) {
    when (
      val result = playbackController.requestPlayback(
        mediaId = media.id,
        ageRating = media.ageRating,
        enabled = parentalEnabled,
        level = parentalLevel,
        hasPin = savedParentalPin.isNotBlank(),
        season = season,
        episode = episode
      )
    ) {
      is PlaybackRequestResult.Allowed ->
        navController.navigate(MobileRoutes.player(result.mediaId, result.season, result.episode))
      PlaybackRequestResult.PinRequired -> {
        pinPromptTitle = media.title
        pinInput = ""
        pinError = false
        pinPromptManualSelection = false
      }
    }
  }

  /**
   * Avvia la SELEZIONE MANUALE delle sorgenti dalla scheda Dettaglio.
   *
   * Stessa protezione genitori della riproduzione: la ricerca delle fonti non
   * aggira il PIN. A differenza della riproduzione, nessuna sorgente parte
   * automaticamente: la schermata di scelta viene mostrata e solo la conferma
   * dell'utente avvia il player.
   */
  fun requestSourceSelection(media: MediaItem, season: Int?, episode: Int?) {
    when (
      val result = playbackController.requestPlayback(
        mediaId = media.id,
        ageRating = media.ageRating,
        enabled = parentalEnabled,
        level = parentalLevel,
        hasPin = savedParentalPin.isNotBlank(),
        season = season,
        episode = episode
      )
    ) {
      is PlaybackRequestResult.Allowed ->
        navController.navigate(
          MobileRoutes.player(
            mediaId = result.mediaId,
            season = result.season,
            episode = result.episode,
            manual = true
          )
        )
      PlaybackRequestResult.PinRequired -> {
        pinPromptTitle = media.title
        pinInput = ""
        pinError = false
        pinPromptManualSelection = true
      }
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(NovaBackground)
      .then(
        // Il player è l'unica schermata edge-to-edge (gestisce le proprie insets).
        // Le altre schermate rispettano status bar, cutout e safe area laterali;
        // l'inset inferiore è applicato dalla bottom bar, che resta sopra la
        // navigation bar (3 pulsanti) o l'area gesti obbligatoria (gesture nav).
        if (!isPlayer) {
          Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
          )
        } else {
          Modifier
        }
      )
  ) {
    Scaffold(
      containerColor = NovaBackground,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      bottomBar = {
        if (showChrome) {
          MobileBottomBar(
            currentRoute = currentRoute,
            destinations = destinations,
            onNavigate = { route -> navController.navigateTopLevel(route) }
          )
        }
      }
    ) { padding ->
      Row(modifier = Modifier.fillMaxSize().padding(padding)) {
        Box(modifier = Modifier.weight(1f)) {
          NavHost(
            navController = navController,
            startDestination = MobileRoutes.HOME,
            modifier = Modifier.fillMaxSize()
          ) {
            composable(MobileRoutes.HOME) {
              HomeScreen(
                viewModel = catalogViewModel,
                onMediaClick = { media -> openDetail(media) },
                onPlayMedia = { media -> requestPlayback(media, season = null, episode = null) },
                onProviderClick = { provider ->
                  navController.navigate(MobileRoutes.provider(provider.id))
                }
              )
            }
            composable(
              route = MobileRoutes.PROVIDER,
              arguments = listOf(navArgument("providerId") { type = NavType.StringType })
            ) { entry ->
              val providerId = entry.arguments?.getString("providerId").orEmpty()
              val provider = ProviderConstants.ALL.firstOrNull { it.id == providerId }
              if (provider != null) {
                MobileProviderScreen(
                  provider = provider,
                  onBack = { navController.popBackStack() },
                  onMediaClick = { media ->
                    openDetail(media)
                  }
                )
              }
            }
            composable(MobileRoutes.MOVIES) {
              CatalogScreen(
                viewModel = catalogViewModel,
                titleRes = R.string.nav_movies,
                kind = CatalogKind.MOVIES,
                onMediaClick = { media -> openDetail(media) }
              )
            }
            composable(MobileRoutes.SERIES) {
              CatalogScreen(
                viewModel = catalogViewModel,
                titleRes = R.string.nav_tv_series,
                kind = CatalogKind.SERIES,
                onMediaClick = { media -> openDetail(media) }
              )
            }
            composable(MobileRoutes.SEARCH) {
              SearchScreen(
                viewModel = catalogViewModel,
                onMediaClick = { media -> openDetail(media) }
              )
            }
            composable(MobileRoutes.FAVORITES) {
              FavoritesScreen(
                viewModel = catalogViewModel,
                onMediaClick = { media -> openDetail(media) },
                onExplore = { navController.navigateTopLevel(MobileRoutes.HOME) }
              )
            }
            composable(MobileRoutes.SETTINGS) {
              SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(MobileRoutes.ADDONS) {
              if (showAddons) {
                MobileAddonsScreen(onBack = { navController.popBackStack() })
              } else {
                // Modalità passata a Web Streaming con la schermata Add-on
                // aperta: rientro automatico, come il fallback TV dalla
                // sezione non più disponibile.
                LaunchedEffect(Unit) { navController.popBackStack() }
              }
            }
            composable(
              route = MobileRoutes.DETAIL,
              arguments = listOf(navArgument("mediaId") { type = NavType.StringType })
            ) { entry ->
              val mediaId = entry.arguments?.getString("mediaId").orEmpty()
              DetailScreen(
                mediaId = mediaId,
                onBack = { navController.popBackStack() },
                onPlay = { media, season, episode ->
                  requestPlayback(media, season, episode)
                },
                // «Seleziona fonte»: presente solo in modalità Debrid.
                onSelectSource = if (showSelectSource) {
                  { media, season, episode -> requestSourceSelection(media, season, episode) }
                } else {
                  null
                },
                orientationController = orientationController
              )
            }
            composable(
              route = MobileRoutes.PLAYER,
              arguments = listOf(
                navArgument("mediaId") { type = NavType.StringType },
                navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                navArgument("autoplay") { type = NavType.BoolType; defaultValue = false },
                navArgument("manual") { type = NavType.BoolType; defaultValue = false }
              )
            ) { entry ->
              val mediaId = entry.arguments?.getString("mediaId").orEmpty()
              val season = entry.arguments?.getInt("season")?.takeIf { it > 0 }
              val episode = entry.arguments?.getInt("episode")?.takeIf { it > 0 }
              val autoStart = entry.arguments?.getBoolean("autoplay") ?: false
              // Selezione manuale delle sorgenti dalla scheda Dettaglio: il
              // selettore si apre comunque e nessun flusso parte in automatico.
              val manualSelection = entry.arguments?.getBoolean("manual") ?: false
              MobilePlayerScreen(
                mediaId = mediaId,
                season = season,
                episode = episode,
                autoStart = autoStart,
                manualSelection = manualSelection,
                orientationController = orientationController,
                onBack = {
                  // Come la TV: alla chiusura del player lo sblocco decade.
                  playbackController.lock()
                  navController.popBackStack()
                },
                onPlayNextEpisode = { nextSeason, nextEpisode ->
                  // Avanzamento automatico: sostituisce la voce del player, così
                  // il Back torna alla scheda Dettaglio e non all'episodio vecchio.
                  // `autoStart` fa partire il nuovo episodio senza source picker.
                  navController.navigate(
                    MobileRoutes.player(
                      mediaId = mediaId,
                      season = nextSeason,
                      episode = nextEpisode,
                      autoStart = true
                    )
                  ) {
                    popUpTo(MobileRoutes.PLAYER) { inclusive = true }
                    launchSingleTop = true
                  }
                }
              )
            }
          }

          if (pinPromptTitle != null) {
            ParentalPinDialog(
              mediaTitle = pinPromptTitle.orEmpty(),
              pin = pinInput,
              error = pinError,
              onPinChange = { value ->
                pinInput = value
                if (pinError) pinError = false
              },
              onConfirm = {
                when (
                  val outcome = playbackController.verifyPin(pinInput) { candidate ->
                    AppSettingsRepository.verifyParentalControlPin(candidate)
                  }
                ) {
                  is PinVerificationResult.Unlocked -> {
                    pinPromptTitle = null
                    pinInput = ""
                    pinError = false
                    navController.navigate(
                      MobileRoutes.player(
                        mediaId = outcome.mediaId,
                        season = outcome.season,
                        episode = outcome.episode,
                        // La selezione manuale attende ancora la scelta della
                        // sorgente: dopo il PIN si torna al selettore, non al
                        // player.
                        manual = pinPromptManualSelection
                      )
                    )
                    pinPromptManualSelection = false
                  }
                  PinVerificationResult.WrongPin -> {
                    pinError = true
                    pinInput = ""
                  }
                  PinVerificationResult.NoPendingPlayback -> {
                    pinPromptTitle = null
                    pinInput = ""
                    pinError = false
                    pinPromptManualSelection = false
                  }
                }
              },
              onDismiss = {
                playbackController.cancelPin()
                pinPromptTitle = null
                pinInput = ""
                pinError = false
                pinPromptManualSelection = false
              }
            )
          }
        }
      }
    }
  }
}

private fun NavHostController.navigateTopLevel(route: String) {
  navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
  }
}

private fun destinationIcon(destination: MobileTopDestination): ImageVector = when (destination) {
  MobileTopDestination.HOME -> Icons.Filled.Home
  MobileTopDestination.MOVIES -> Icons.Filled.Movie
  MobileTopDestination.SERIES -> Icons.Filled.Tv
  MobileTopDestination.FAVORITES -> Icons.Filled.Favorite
  MobileTopDestination.SEARCH -> Icons.Filled.Search
  MobileTopDestination.SETTINGS -> Icons.Filled.Settings
  MobileTopDestination.ADDONS -> Icons.Filled.Extension
}

/**
 * Barra inferiore a riga singola scorrevole.
 *
 * Ogni voce mantiene un'area touch di almeno 56×64 dp con icona 22 dp: quando le voci
 * superano la larghezza disponibile la `LazyRow` scorre orizzontalmente e la voce
 * selezionata viene portata in vista con uno scroll animato, senza comprimere le
 * icone né introdurre una seconda fila.
 *
 * La `Surface` copre anche l'area di sistema inferiore, mentre il contenuto
 * applica l'inset inferiore: con la navigazione a gesti e l'hint nascosto
 * `navigationBars` vale 0 px, ma l'area gesti obbligatoria resta e non deve
 * coprire icone ed etichette. Il padding usa quindi il massimo tra
 * `safeDrawing` (navigation bar/IME) e `mandatorySystemGestures`.
 */
@Composable
private fun MobileBottomBar(
  currentRoute: String?,
  destinations: List<MobileTopDestination>,
  onNavigate: (String) -> Unit
) {
  val listState = rememberLazyListState()
  val selectedIndex = destinations.indexOfFirst { it.route == currentRoute }

  LaunchedEffect(currentRoute, destinations) {
    if (selectedIndex < 0) return@LaunchedEffect
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
    val viewportStart = listState.layoutInfo.viewportStartOffset
    val viewportEnd = listState.layoutInfo.viewportEndOffset
    val fullyVisible = info != null &&
      info.offset >= viewportStart &&
      info.offset + info.size <= viewportEnd
    if (!fullyVisible) {
      listState.animateScrollToItem(selectedIndex)
    }
  }

  Surface(
    color = NovaSurface,
    modifier = Modifier.fillMaxWidth()
  ) {
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(
          WindowInsets.safeDrawing
            .only(WindowInsetsSides.Bottom)
            .union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
        )
    ) {
      val count = destinations.size.coerceAtLeast(1)
      // La voce non scende mai sotto la larghezza reale dell'etichetta più lunga
      // (10 sp, font scale di sistema): "Impostazioni" a font scale 1.3 supera i
      // 72 dp e verrebbe troncata. A font scale 1.0 le sei voci entrano su un
      // telefono da 384 dp con 4 dp di margine; con font scale più alti la barra
      // scorre e la voce selezionata viene portata interamente in vista.
      val density = LocalDensity.current
      // lineHeight esplicito: quello ereditato (24 sp) farebbe crescere la barra
      // anche a font scale 1.0; 14 sp mantiene i 64 dp attuali e scala con il
      // font scale di sistema senza tagliare i glifi. Lo spacing 0 sp evita che
      // l'etichetta più lunga superi la voce sui telefoni da 384 dp.
      val labelStyle = LocalTextStyle.current.copy(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
      )
      val textMeasurer = rememberTextMeasurer()
      val context = LocalContext.current
      val widestLabel = remember(destinations, labelStyle, textMeasurer, context) {
        destinations.maxOf { destination ->
          textMeasurer.measure(
            text = context.getString(destination.labelRes),
            style = labelStyle
          ).size.width
        }
      }
      val labelWidth = with(density) { widestLabel.toDp() }
      val minItemWidth = labelWidth.coerceAtLeast(56.dp).coerceAtMost(120.dp)
      // Su tablet le voci si distribuiscono sull'intera larghezza. Quando non
      // entrano tutte, la riga mostra solo voci intere: si calcola quante ne
      // stanno e, se la voce successiva resterebbe visibile solo come sliver
      // (meno di 24 dp), si sposta la riga quanto basta per nasconderla del
      // tutto. Così nessuna etichetta viene tagliata a metà bordo; una voce più
      // larga di 24 dp resta invece visibile come indizio di scroll.
      val horizontalPadding = 4.dp
      val minPeekWidth = 24.dp
      val availableWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(1.dp)
      val visibleCount = (availableWidth / minItemWidth).toInt()
        .coerceAtLeast(1)
        .coerceAtMost(count)
      val allVisible = visibleCount >= count
      val itemWidth = if (allVisible) {
        (availableWidth / count).coerceAtMost(120.dp)
      } else {
        minItemWidth
      }
      val visibleWidth = itemWidth * visibleCount
      val basePadding = if (allVisible) {
        ((maxWidth - visibleWidth) / 2).coerceAtLeast(0.dp)
      } else {
        horizontalPadding
      }
      val nextItemPeek = if (allVisible) {
        0.dp
      } else {
        (maxWidth - basePadding - visibleWidth).coerceAtLeast(0.dp)
      }
      val contentPadding = if (!allVisible && nextItemPeek < minPeekWidth) {
        basePadding + nextItemPeek
      } else {
        basePadding
      }
      LazyRow(
        state = listState,
        modifier = Modifier
          .fillMaxWidth()
          // Altezza minima 64 dp, ma cresce con il font scale di sistema:
          // con un'altezza fissa la riga dell'etichetta verrebbe tagliata
          // (icona 40 dp + testo + padding).
          .heightIn(min = 64.dp),
        contentPadding = PaddingValues(horizontal = contentPadding),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        items(destinations, key = { it.route }) { destination ->
          val selected = currentRoute == destination.route
          Column(
            modifier = Modifier
              .width(itemWidth)
              .heightIn(min = 64.dp)
              .clickable { onNavigate(destination.route) }
              .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
          ) {
            Box(
              modifier = Modifier
                .size(40.dp)
                .background(
                  if (selected) NovaCyan.copy(alpha = 0.14f) else Color.Transparent,
                  RoundedCornerShape(20.dp)
                ),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = destinationIcon(destination),
                contentDescription = stringResource(destination.labelRes),
                tint = if (selected) NovaCyan else NovaTextMuted,
                modifier = Modifier.size(22.dp)
              )
            }
            Text(
              text = stringResource(destination.labelRes),
              color = if (selected) NovaCyan else NovaTextMuted,
              style = labelStyle,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }
  }
}
