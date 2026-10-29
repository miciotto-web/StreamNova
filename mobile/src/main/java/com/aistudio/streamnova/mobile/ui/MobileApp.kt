package com.aistudio.streamnova.mobile.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.aistudio.streamnova.mobile.ui.screens.MobilePlayerScreen
import com.aistudio.streamnova.mobile.ui.screens.SearchScreen
import com.aistudio.streamnova.mobile.ui.screens.SettingsScreen
import com.example.data.model.MediaItem
import com.example.data.prefs.AppSettingsRepository
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary

/** Rotte dell'app mobile. Gli argomenti numerici usano -1 come "non specificato". */
object MobileRoutes {
  const val HOME = "home"
  const val MOVIES = "movies"
  const val SERIES = "series"
  const val SEARCH = "search"
  const val FAVORITES = "favorites"
  const val SETTINGS = "settings"
  const val DETAIL = "detail/{mediaId}"
  const val PLAYER = "player/{mediaId}?season={season}&episode={episode}"

  fun detail(mediaId: String): String = "detail/${Uri.encode(mediaId)}"

  fun player(mediaId: String, season: Int? = null, episode: Int? = null): String =
    "player/${Uri.encode(mediaId)}?season=${season ?: -1}&episode=${episode ?: -1}"
}

private sealed class TopDestination(
  val route: String,
  @StringRes val labelRes: Int,
  val icon: ImageVector
) {
  data object Home : TopDestination(MobileRoutes.HOME, R.string.nav_home, Icons.Filled.Home)
  data object Movies : TopDestination(MobileRoutes.MOVIES, R.string.nav_movies, Icons.Filled.Movie)
  data object Series : TopDestination(MobileRoutes.SERIES, R.string.nav_series, Icons.Filled.Tv)
  data object Search : TopDestination(MobileRoutes.SEARCH, R.string.nav_search, Icons.Filled.Search)
  data object Favorites :
    TopDestination(MobileRoutes.FAVORITES, R.string.nav_favorites, Icons.Filled.Favorite)
}

private val topDestinations = listOf(
  TopDestination.Home,
  TopDestination.Movies,
  TopDestination.Series,
  TopDestination.Search,
  TopDestination.Favorites
)

/**
 * Shell adattiva: bottom bar su smartphone, navigation rail su tablet (>= 840dp).
 * Il player occupa l'intero schermo e non mostra alcuna barra.
 */
@Composable
fun MobileApp() {
  BoxWithConstraints {
    val expanded = maxWidth >= 840.dp
    CompositionLocalProvider(LocalWindowWidthExpanded provides expanded) {
      MobileAppContent()
    }
  }
}

@Composable
private fun MobileAppContent() {
  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route
  val isPlayer = currentRoute?.startsWith("player") == true
  val isExpanded = LocalWindowWidthExpanded.current
  val showChrome = !isPlayer

  val parentalEnabled by AppSettingsRepository.parentalControlEnabled.collectAsStateWithLifecycle()
  val parentalLevel by AppSettingsRepository.parentalControlLevel.collectAsStateWithLifecycle()
  val savedParentalPin by AppSettingsRepository.parentalControlPin.collectAsStateWithLifecycle()
  val playbackController = remember { ParentalPlaybackController() }
  var pinPromptTitle by remember { mutableStateOf<String?>(null) }
  var pinInput by remember { mutableStateOf("") }
  var pinError by remember { mutableStateOf(false) }

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
      }
    }
  }

  Scaffold(
    containerColor = NovaBackground,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    bottomBar = {
      if (showChrome && !isExpanded) {
        MobileBottomBar(
          currentRoute = currentRoute,
          onNavigate = { route -> navController.navigateTopLevel(route) }
        )
      }
    }
  ) { padding ->
    Row(modifier = Modifier.fillMaxSize().padding(padding)) {
      if (showChrome && isExpanded) {
        MobileNavigationRail(
          currentRoute = currentRoute,
          onNavigate = { route -> navController.navigateTopLevel(route) }
        )
      }
      Box(modifier = Modifier.weight(1f)) {
        NavHost(
          navController = navController,
          startDestination = MobileRoutes.HOME,
          modifier = Modifier.fillMaxSize()
        ) {
          composable(MobileRoutes.HOME) {
            HomeScreen(
              onMediaClick = { media -> navController.navigate(MobileRoutes.detail(media.id)) },
              onPlayMedia = { media -> requestPlayback(media, null, null) },
              onSeeAllMovies = { navController.navigateTopLevel(MobileRoutes.MOVIES) },
              onSeeAllSeries = { navController.navigateTopLevel(MobileRoutes.SERIES) },
              onSettings = { navController.navigate(MobileRoutes.SETTINGS) }
            )
          }
          composable(MobileRoutes.MOVIES) {
            CatalogScreen(
              titleRes = R.string.nav_movies,
              kind = CatalogKind.MOVIES,
              onMediaClick = { media -> navController.navigate(MobileRoutes.detail(media.id)) }
            )
          }
          composable(MobileRoutes.SERIES) {
            CatalogScreen(
              titleRes = R.string.nav_series,
              kind = CatalogKind.SERIES,
              onMediaClick = { media -> navController.navigate(MobileRoutes.detail(media.id)) }
            )
          }
          composable(MobileRoutes.SEARCH) {
            SearchScreen(
              onMediaClick = { media -> navController.navigate(MobileRoutes.detail(media.id)) }
            )
          }
          composable(MobileRoutes.FAVORITES) {
            FavoritesScreen(
              onMediaClick = { media -> navController.navigate(MobileRoutes.detail(media.id)) },
              onExplore = { navController.navigateTopLevel(MobileRoutes.HOME) }
            )
          }
          composable(MobileRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
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
              }
            )
          }
          composable(
            route = MobileRoutes.PLAYER,
            arguments = listOf(
              navArgument("mediaId") { type = NavType.StringType },
              navArgument("season") { type = NavType.IntType; defaultValue = -1 },
              navArgument("episode") { type = NavType.IntType; defaultValue = -1 }
            )
          ) { entry ->
            val mediaId = entry.arguments?.getString("mediaId").orEmpty()
            val season = entry.arguments?.getInt("season")?.takeIf { it > 0 }
            val episode = entry.arguments?.getInt("episode")?.takeIf { it > 0 }
            MobilePlayerScreen(
              mediaId = mediaId,
              season = season,
              episode = episode,
              onBack = {
                // Come la TV: alla chiusura del player lo sblocco decade.
                playbackController.lock()
                navController.popBackStack()
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
                    MobileRoutes.player(outcome.mediaId, outcome.season, outcome.episode)
                  )
                }
                PinVerificationResult.WrongPin -> {
                  pinError = true
                  pinInput = ""
                }
                PinVerificationResult.NoPendingPlayback -> {
                  pinPromptTitle = null
                  pinInput = ""
                  pinError = false
                }
              }
            },
            onDismiss = {
              playbackController.cancelPin()
              pinPromptTitle = null
              pinInput = ""
              pinError = false
            }
          )
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

@Composable
private fun MobileBottomBar(
  currentRoute: String?,
  onNavigate: (String) -> Unit
) {
  NavigationBar(containerColor = NovaSurface) {
    topDestinations.forEach { destination ->
      NavigationBarItem(
        selected = currentRoute == destination.route,
        onClick = { onNavigate(destination.route) },
        icon = { Icon(destination.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
        label = { Text(stringResource(destination.labelRes)) },
        colors = NavigationBarItemDefaults.colors(
          selectedIconColor = NovaCyan,
          selectedTextColor = NovaCyan,
          unselectedIconColor = NovaTextMuted,
          unselectedTextColor = NovaTextMuted,
          indicatorColor = NovaCyan.copy(alpha = 0.14f)
        )
      )
    }
  }
}

@Composable
private fun MobileNavigationRail(
  currentRoute: String?,
  onNavigate: (String) -> Unit
) {
  NavigationRail(containerColor = NovaSurface) {
    androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
    topDestinations.forEach { destination ->
      NavigationRailItem(
        selected = currentRoute == destination.route,
        onClick = { onNavigate(destination.route) },
        icon = { Icon(destination.icon, contentDescription = null) },
        label = { Text(stringResource(destination.labelRes), color = NovaTextPrimary) },
        colors = NavigationRailItemDefaults.colors(
          selectedIconColor = NovaCyan,
          selectedTextColor = NovaCyan,
          unselectedIconColor = NovaTextMuted,
          unselectedTextColor = NovaTextMuted,
          indicatorColor = NovaCyan.copy(alpha = 0.14f)
        )
      )
    }
  }
}
