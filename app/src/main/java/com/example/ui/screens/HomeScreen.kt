package com.example.ui.screens

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.data.repository.StremioCatalogRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.example.ui.components.ContinueWatchingCard
import com.example.ui.components.MediaContextMenu
import com.example.ui.components.HeroBanner
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.ProviderConstants
import com.example.ui.components.ProviderCard
import com.example.ui.components.StandardMediaCard
import com.example.ui.components.StreamingProvider
import com.example.ui.components.Top10RankedMediaCard
import com.example.ui.components.Top10RowSpacing
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.SidebarSection
import com.example.ui.viewmodel.StreamNovaViewModel

import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusBringIntoViewSpec
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalClearLastFocusedMedia
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
  viewModel: StreamNovaViewModel,
  onProviderClick: (StreamingProvider) -> Unit = {},
  onMediaClick: ((MediaItem) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val currentSection by viewModel.currentSection.collectAsState()
  val allMedia by viewModel.allMedia.collectAsState()
  val homeCatalogs by viewModel.homeCatalogs.collectAsState()
  val handleMediaClick: (MediaItem) -> Unit = onMediaClick ?: { viewModel.openDetail(it) }

  val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()

  CompositionLocalProvider(
    LocalLastFocusedMediaId provides lastFocusedMediaId,
    LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() }
  ) {
    when (currentSection) {
    SidebarSection.HOME -> {
      HomeMainBrowsingContent(
        allMedia = allMedia,
        homeCatalogs = homeCatalogs,
        onMediaClick = handleMediaClick,
        onPlayClick = { viewModel.openStreamForMedia(it) },
        onRemoveFromContinueWatching = { viewModel.removeFromContinueWatching(it) },
        onProviderClick = onProviderClick,
        viewModel = viewModel,
        modifier = modifier
      )
    }
    SidebarSection.FILM -> {
      val moviesItems = remember(homeCatalogs, allMedia) {
        if (homeCatalogs.allMovies.isNotEmpty()) {
          homeCatalogs.allMovies
        } else {
          allMedia.filter { it.type == MediaType.FILM }
        }
      }
      CategoryBrowsingScreen(
        title = stringResource(R.string.category_all_movies),
        items = moviesItems,
        isTv = false,
        viewModel = viewModel,
        onMediaClick = handleMediaClick,
        onLoadMore = { viewModel.loadNextMoviesPage() },
        modifier = modifier
      )
    }
    SidebarSection.SERIE_TV -> {
      val seriesItems = remember(homeCatalogs, allMedia) {
        if (homeCatalogs.allSeries.isNotEmpty()) {
          homeCatalogs.allSeries
        } else {
          allMedia.filter { it.type == MediaType.SERIE_TV }
        }
      }
      CategoryBrowsingScreen(
        title = stringResource(R.string.category_all_tv_series),
        items = seriesItems,
        isTv = true,
        viewModel = viewModel,
        onMediaClick = handleMediaClick,
        onLoadMore = { viewModel.loadNextTvPage() },
        modifier = modifier
      )
    }
    SidebarSection.I_MIEI_CONTENUTI -> {
      FavoritesScreen(
        allMedia = allMedia,
        onMediaClick = handleMediaClick,
        modifier = modifier,
        viewModel = viewModel
      )
    }
    SidebarSection.CERCA -> {
      SearchScreen(
        viewModel = viewModel,
        onMediaClick = handleMediaClick,
        modifier = modifier
      )
    }
    SidebarSection.ADDON -> {
      AddonsScreen(
        viewModel = viewModel,
        modifier = modifier
      )
    }
    SidebarSection.IMPOSTAZIONI -> {
      SettingsScreen(
        viewModel = viewModel,
        modifier = modifier
      )
    }
  }
}
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * Coordinatore del focus verticale e orizzontale tra righe della Home TV.
 *
 * Garantisce una navigazione rigorosa riga-per-riga:
 * - D-Pad DOWN: scende esattamente alla riga sottostante (memorizzando/ripristinando la colonna).
 * - D-Pad UP: sale esattamente alla riga soprastante (senza mai saltare righe).
 * - Isolamento bidirezionale: Hero <-> Providers <-> Prima riga poster.
 */
class HomeRowFocusCoordinator(
  val activeRowKeys: List<String>,
  val rowItemCounts: Map<String, Int>
) {
  val lastFocusedColByRow = mutableStateMapOf<String, Int>()
  private val cellRequesters = mutableMapOf<Pair<String, Int>, FocusRequester>()

  // Mappa OSSERVABILE delle celle montate: chiunque legga lo stato (le focusProperties che
  // assegnano up/down) si ricompone quando una cella entra/esce dalla composizione, cosi'
  // un target non resta mai puntato a un nodo smontato. Una mappa "semplice" lasciava
  // target orfani e il focus search del D-pad scatenava
  // IllegalStateException "FocusRequester is not initialized".
  private val attachedCellState = mutableStateMapOf<Pair<String, Int>, Boolean>()

  fun getRequester(rowKey: String, colIndex: Int): FocusRequester {
    return cellRequesters.getOrPut(Pair(rowKey, colIndex)) { FocusRequester() }
  }

  fun onCellFocused(rowKey: String, colIndex: Int) {
    lastFocusedColByRow[rowKey] = colIndex
  }

  fun registerCell(rowKey: String, colIndex: Int) {
    attachedCellState[Pair(rowKey, colIndex)] = true
  }

  fun unregisterCell(rowKey: String, colIndex: Int) {
    attachedCellState.remove(Pair(rowKey, colIndex))
  }

  fun isCellAttached(rowKey: String, colIndex: Int): Boolean =
    attachedCellState.containsKey(Pair(rowKey, colIndex))

  fun getNextRowKey(currentRowKey: String): String? {
    val idx = activeRowKeys.indexOf(currentRowKey)
    return if (idx in 0 until activeRowKeys.lastIndex) activeRowKeys[idx + 1] else null
  }

  fun getPrevRowKey(currentRowKey: String): String? {
    val idx = activeRowKeys.indexOf(currentRowKey)
    return if (idx > 0) activeRowKeys[idx - 1] else null
  }

  /**
   * Requester della cella MONTATA piu' vicina alla colonna desiderata nella riga bersaglio.
   *
   * Ritorna `null` se la riga bersaglio non ha alcuna cella montata: assegnare un
   * FocusRequester non agganciato a `focusProperties.down/up` fa esplodere il focus search
   * del D-pad (`IllegalStateException: FocusRequester is not initialized`).
   */
  fun getTargetRequester(targetRowKey: String, preferredCol: Int): FocusRequester? {
    val count = rowItemCounts[targetRowKey] ?: return null
    if (count <= 0) return null

    val desiredCol = (lastFocusedColByRow[targetRowKey] ?: preferredCol).coerceIn(0, count - 1)

    // 1. Se la cella desiderata è montata, usala direttamente
    if (isCellAttached(targetRowKey, desiredCol)) {
      return getRequester(targetRowKey, desiredCol)
    }

    // 2. Altrimenti seleziona la cella montata più vicina in quella riga; se la riga non ha
    //    alcuna cella montata NON restituire mai un requester orfano.
    val closestAttachedCol = attachedCellState.keys
      .asSequence()
      .filter { it.first == targetRowKey }
      .map { it.second }
      .minByOrNull { kotlin.math.abs(it - desiredCol) }
      ?: return null

    return getRequester(targetRowKey, closestAttachedCol)
  }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.rowFocusProperties(
  coordinator: HomeRowFocusCoordinator,
  rowKey: String,
  colIndex: Int
): Modifier {
  DisposableEffect(coordinator, rowKey, colIndex) {
    coordinator.registerCell(rowKey, colIndex)
    onDispose {
      coordinator.unregisterCell(rowKey, colIndex)
    }
  }

  // Target calcolati nel CORPO composable (e non dentro la lambda focusProperties): le
  // letture dello snapshot del coordinator vengono tracciate dalla composizione chiamante,
  // quindi montare/smontare una cella del bersaglio ricompone la riga e aggiorna i target.
  // Se il bersaglio non ha celle montate il target resta null e si lascia a
  // FocusRequester.Default la ricerca standard del D-pad (MAI un requester non agganciato).
  val nextRowKey = coordinator.getNextRowKey(rowKey)
  val downTarget = nextRowKey?.let { coordinator.getTargetRequester(it, colIndex) }
  val prevRowKey = coordinator.getPrevRowKey(rowKey)
  val upTarget = prevRowKey?.let { coordinator.getTargetRequester(it, colIndex) }

  return this
    .focusProperties {
      if (nextRowKey != null) {
        down = downTarget ?: FocusRequester.Default
      } else {
        down = FocusRequester.Cancel
      }

      if (prevRowKey != null) {
        up = upTarget ?: FocusRequester.Default
      } else {
        up = FocusRequester.Cancel
      }
    }
    .onFocusChanged { state ->
      if (state.isFocused) {
        coordinator.onCellFocused(rowKey, colIndex)
      }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeMainBrowsingContent(
  allMedia: List<MediaItem>,
  homeCatalogs: HomeCatalogs? = null,
  onMediaClick: (MediaItem) -> Unit,
  onPlayClick: (MediaItem) -> Unit,
  onRemoveFromContinueWatching: (String) -> Unit,
  onProviderClick: (StreamingProvider) -> Unit = {},
  viewModel: StreamNovaViewModel? = null,
  modifier: Modifier = Modifier
) {
  val focusRestoreSignal = remember { mutableStateOf<String?>(null) }

  LaunchedEffect(viewModel) {
    viewModel?.focusRestoreSignal?.collect { mediaId ->
      focusRestoreSignal.value = mediaId
    }
  }
  // Liste dinamiche dai dati reali TMDB sincronizzati nel repository
  val continueWatching = remember(allMedia) {
    allMedia
      .filter { it.currentProgressMs > 0 }
      .sortedByDescending { it.lastWatchedAt ?: 0L }
  }
  var contextMenuMedia by remember { mutableStateOf<MediaItem?>(null) }

  val hasCustomCatalogMovies = homeCatalogs?.hasCustomCatalogMovies == true
  val hasCustomCatalogSeries = homeCatalogs?.hasCustomCatalogSeries == true

  val top10Movies = remember(homeCatalogs) {
    if (hasCustomCatalogMovies) emptyList()
    else homeCatalogs?.top10Movies?.take(StremioCatalogRepository.HOME_TOP_10_SIZE) ?: emptyList()
  }
  val top10Series = remember(homeCatalogs) {
    if (hasCustomCatalogSeries) emptyList()
    else homeCatalogs?.top10Series?.take(StremioCatalogRepository.HOME_TOP_10_SIZE) ?: emptyList()
  }
  val trendingMovies = remember(homeCatalogs) { if (hasCustomCatalogMovies) emptyList() else homeCatalogs?.trendingMovies ?: emptyList() }
  val trendingSeries = remember(homeCatalogs) { if (hasCustomCatalogSeries) emptyList() else homeCatalogs?.trendingSeries ?: emptyList() }
  val forYouMovies = remember(homeCatalogs) { if (hasCustomCatalogMovies) emptyList() else homeCatalogs?.forYouMovies ?: emptyList() }
  val forYouSeries = remember(homeCatalogs) { if (hasCustomCatalogSeries) emptyList() else homeCatalogs?.forYouSeries ?: emptyList() }

  val popularMovies = remember(homeCatalogs) { if (hasCustomCatalogMovies) emptyList() else homeCatalogs?.popularMovies ?: emptyList() }
  val popularSeries = remember(homeCatalogs) { if (hasCustomCatalogSeries) emptyList() else homeCatalogs?.popularSeries ?: emptyList() }

  // Hero Banner Dinamico: selezione di massimo 9 titoli alternati tra Film e Serie TV
  val dynamicHeroItems = remember(homeCatalogs, allMedia) {
    if (homeCatalogs != null && homeCatalogs.heroItems.isNotEmpty()) {
      homeCatalogs.heroItems
    } else {
      val movies = allMedia.filter { it.type == MediaType.FILM && (!it.backdropUrl.isNullOrBlank() || it.backdropRes != null) }
      val series = allMedia.filter { it.type == MediaType.SERIE_TV && (!it.backdropUrl.isNullOrBlank() || it.backdropRes != null) }
      val combined = mutableListOf<MediaItem>()
      val maxLen = maxOf(movies.size, series.size)
      for (i in 0 until maxLen) {
        if (i < movies.size && combined.size < 9 && !combined.any { it.id == movies[i].id }) {
          combined.add(movies[i])
        }
        if (i < series.size && combined.size < 9 && !combined.any { it.id == series[i].id }) {
          combined.add(series[i])
        }
      }
      if (combined.isEmpty()) allMedia.take(9) else combined.take(9)
    }
  }

  // Definizione ordinata di tutte le righe attive della Home
  val activeRowKeys = remember(
    dynamicHeroItems.isNotEmpty(),
    continueWatching,
    hasCustomCatalogMovies,
    hasCustomCatalogSeries,
    top10Movies,
    top10Series,
    trendingMovies,
    trendingSeries,
    forYouMovies,
    forYouSeries,
    popularMovies,
    popularSeries,
    homeCatalogs?.extraSections
  ) {
    buildList {
      if (dynamicHeroItems.isNotEmpty()) add("hero_banner")
      if (continueWatching.isNotEmpty()) add("section_continue_watching")
      add("section_providers_hub")
      if (!hasCustomCatalogMovies && top10Movies.isNotEmpty()) add("section_top_10_film")
      if (!hasCustomCatalogSeries && top10Series.isNotEmpty()) add("section_top_10_serie")
      if (!hasCustomCatalogMovies && trendingMovies.isNotEmpty()) add("section_trending_film")
      if (!hasCustomCatalogSeries && trendingSeries.isNotEmpty()) add("section_trending_serie")
      if (!hasCustomCatalogMovies && forYouMovies.isNotEmpty()) add("section_for_you_film")
      if (!hasCustomCatalogSeries && forYouSeries.isNotEmpty()) add("section_for_you_serie")
      if (!hasCustomCatalogMovies && popularMovies.isNotEmpty()) add("section_popolari_film")
      if (!hasCustomCatalogSeries && popularSeries.isNotEmpty()) add("section_popolari_serie")
      homeCatalogs?.extraSections?.forEach { extra ->
        if (extra.items.isNotEmpty()) {
          add("section_${extra.id}")
        }
      }
    }
  }

  val rowItemCounts = remember(
    dynamicHeroItems.size,
    continueWatching.size,
    top10Movies.size,
    top10Series.size,
    trendingMovies.size,
    trendingSeries.size,
    forYouMovies.size,
    forYouSeries.size,
    popularMovies.size,
    popularSeries.size,
    homeCatalogs?.extraSections
  ) {
    buildMap {
      // L'Hero e' una superficie unica di focus (nessun pulsante interno).
      put("hero_banner", 1)
      put("section_providers_hub", ProviderConstants.ALL.size)
      put("section_continue_watching", continueWatching.size)
      put("section_top_10_film", top10Movies.size)
      put("section_top_10_serie", top10Series.size)
      put("section_trending_film", trendingMovies.size)
      put("section_trending_serie", trendingSeries.size)
      put("section_for_you_film", forYouMovies.size)
      put("section_for_you_serie", forYouSeries.size)
      put("section_popolari_film", popularMovies.size)
      put("section_popolari_serie", popularSeries.size)
      homeCatalogs?.extraSections?.forEach { extra ->
        put("section_${extra.id}", extra.items.size)
      }
    }
  }

  val coordinator = remember(activeRowKeys, rowItemCounts) {
    HomeRowFocusCoordinator(activeRowKeys, rowItemCounts)
  }

  val listState = rememberLazyListState()

  // D-pad: quando il focus torna dalla prima riga verso la Hero, animiamo la lista a posizione 0
  var heroHasFocus by remember { mutableStateOf(false) }

  LaunchedEffect(heroHasFocus) {
    if (heroHasFocus && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
      listState.animateScrollToItem(0)
    }
  }

  // Richiesta di focus sull'Hero ancora in attesa: al primo avvio della Home (e al click su
  // "Home" dalla Sidebar) i cataloghi TMDB possono non essere ancora arrivati, quindi la hero
  // non esiste ancora. La richiesta viene completata appena compare, cosi' il focus non resta
  // su una card provider qualunque.
  var pendingHeroFocus by remember { mutableStateOf(false) }

  // Letti al momento dell'evento: il coordinatore viene RICREATO quando cambiano le righe
  // attive (i cataloghi TMDB arrivano dopo la splash) e il collettore resta agganciato una
  // sola volta. Usando l'istanza catturata alla partenza si chiederebbe il focus a un
  // FocusRequester mai agganciato ad alcun nodo ("FocusRequester is not initialized").
  val currentCoordinator by rememberUpdatedState(coordinator)
  val heroItemsAvailable by rememberUpdatedState(dynamicHeroItems.isNotEmpty())

  // Scroll to top automatico cliccando "Home" dalla sidebar (Punto 4) e focus iniziale
  // della Home: entrambi devono appoggiarsi sull'Hero, prima cella del contenuto.
  LaunchedEffect(viewModel) {
    viewModel?.homeScrollToTopEvent?.collect {
      listState.animateScrollToItem(0)
      try {
        if (heroItemsAvailable) {
          currentCoordinator.getRequester("hero_banner", 0).requestFocus()
        } else {
          pendingHeroFocus = true
          currentCoordinator.getRequester("section_providers_hub", 0).requestFocus()
        }
      } catch (_: Exception) {}
    }
  }

  LaunchedEffect(pendingHeroFocus, dynamicHeroItems.isNotEmpty()) {
    if (pendingHeroFocus && dynamicHeroItems.isNotEmpty()) {
      pendingHeroFocus = false
      try {
        currentCoordinator.getRequester("hero_banner", 0).requestFocus()
      } catch (_: Exception) {}
    }
  }

  LaunchedEffect(focusRestoreSignal.value, listState, currentCoordinator) {
    val mediaId = focusRestoreSignal.value ?: return@LaunchedEffect
    focusRestoreSignal.value = null

    // Il media deve esistere nel catalogo, altrimenti non c'è nulla da ripristinare.
    allMedia.find { it.id == mediaId } ?: return@LaunchedEffect
    val sectionKey = when {
      continueWatching.any { it.id == mediaId } -> "section_continue_watching"
      top10Movies.any { it.id == mediaId } -> "section_top_10_film"
      top10Series.any { it.id == mediaId } -> "section_top_10_serie"
      trendingMovies.any { it.id == mediaId } -> "section_trending_film"
      trendingSeries.any { it.id == mediaId } -> "section_trending_serie"
      forYouMovies.any { it.id == mediaId } -> "section_for_you_film"
      forYouSeries.any { it.id == mediaId } -> "section_for_you_serie"
      popularMovies.any { it.id == mediaId } -> "section_popolari_film"
      popularSeries.any { it.id == mediaId } -> "section_popolari_serie"
      homeCatalogs?.extraSections?.any { section -> section.items.any { it.id == mediaId } } == true -> {
        homeCatalogs.extraSections.find { section -> section.items.any { it.id == mediaId } }?.let { "section_${it.id}" }
      }
      else -> null
    }

    if (sectionKey != null && rowItemCounts[sectionKey] != null && rowItemCounts[sectionKey]!! > 0) {
      val index = when (sectionKey) {
        "section_continue_watching" -> continueWatching.indexOfFirst { it.id == mediaId }
        "section_top_10_film" -> top10Movies.indexOfFirst { it.id == mediaId }
        "section_top_10_serie" -> top10Series.indexOfFirst { it.id == mediaId }
        "section_trending_film" -> trendingMovies.indexOfFirst { it.id == mediaId }
        "section_trending_serie" -> trendingSeries.indexOfFirst { it.id == mediaId }
        "section_for_you_film" -> forYouMovies.indexOfFirst { it.id == mediaId }
        "section_for_you_serie" -> forYouSeries.indexOfFirst { it.id == mediaId }
        "section_popolari_film" -> popularMovies.indexOfFirst { it.id == mediaId }
        "section_popolari_serie" -> popularSeries.indexOfFirst { it.id == mediaId }
        else -> -1
      }

      if (index >= 0) {
        val requester = currentCoordinator.getTargetRequester(sectionKey, index)
        if (requester != null) {
          try {
            requester.requestFocus()
          } catch (_: Exception) {}
        }
      }
    }
  }

  TvFocusBringIntoView(topOffsetDp = 64.dp) {
  LazyColumn(
    state = listState,
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground),
    contentPadding = PaddingValues(top = 16.dp, bottom = 60.dp)
  ) {
    // 1. Hero / Banner Principale Cinematografico Dinamico (max 9 titoli)
    if (dynamicHeroItems.isNotEmpty()) {
      item(key = "hero_banner") {
        DisposableEffect(coordinator) {
          coordinator.registerCell("hero_banner", 0)
          onDispose {
            coordinator.unregisterCell("hero_banner", 0)
          }
        }
        Column(
          modifier = Modifier.onFocusChanged { heroHasFocus = it.hasFocus }
        ) {
          HeroBanner(
            items = dynamicHeroItems,
            // OK/Enter e click sull'Hero aprono la scheda del titolo corrente: i pulsanti
            // azione non esistono piu', l'intero banner e' il target di focus.
            onDetailsClick = { onMediaClick(it) },
            heroFocusRequester = coordinator.getRequester("hero_banner", 0),
            // Hero va prima su "Continua a guardare" se presente, altrimenti su Providers.
            // Null finche' la destinazione non ha celle montate: HeroBanner lascia allora
            // il D-pad la ricerca standard (FocusRequester.Default) invece di usare un
            // requester non agganciato (crash "FocusRequester is not initialized").
            heroDownFocusRequester = if (continueWatching.isNotEmpty()) {
              coordinator.getTargetRequester("section_continue_watching", 0)
            } else {
              coordinator.getTargetRequester("section_providers_hub", 0)
            },
            onHeroFocusChanged = { if (it) coordinator.onCellFocused("hero_banner", 0) }
          )
          Spacer(modifier = Modifier.height(16.dp))
        }
      }
    }

    // 2. "Continua a guardare" (se ci sono titoli iniziati)
    if (continueWatching.isNotEmpty()) {
      item(key = "section_continue_watching") {
        CarouselHeader(
          title = stringResource(R.string.home_section_continue_watching),
          badge = stringResource(R.string.home_badge_in_progress, continueWatching.size)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(continueWatching, key = { _, it -> "cw_${it.id}" }) { index, item ->
            ContinueWatchingCard(
              media = item,
              onClick = {
                Log.d("HomeScreen", "ContinueWatchingCard onClick (resume playback) called for ${item.title}")
                onPlayClick(item)
              },
              onLongPress = {
                Log.d("HomeScreen", "ContinueWatchingCard onLongPress called for ${item.title}")
                contextMenuMedia = item
              },
              modifier = Modifier.rowFocusProperties(coordinator, "section_continue_watching", index),
              focusRequester = coordinator.getRequester("section_continue_watching", index)
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 3. Hub Streaming Providers (Netflix, HBO, Disney+, Prime Video)
    item(key = "section_providers_hub") {
      CarouselHeader(
        title = stringResource(R.string.home_section_streaming_platforms),
        badge = stringResource(R.string.home_badge_select_provider)
      )
      LazyRow(
        modifier = Modifier
          .fillMaxWidth()
          .height(125.dp),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        itemsIndexed(ProviderConstants.ALL, key = { _, it -> it.id }) { index, provider ->
          ProviderCard(
            provider = provider,
            onClick = {
              onProviderClick(provider)
            },
            modifier = Modifier.rowFocusProperties(coordinator, "section_providers_hub", index),
            focusRequester = coordinator.getRequester("section_providers_hub", index)
          )
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }

    // 4. TOP 10 FILM (Card con numero gigante stilizzato)
    if (!hasCustomCatalogMovies && top10Movies.isNotEmpty()) {
      item(key = "section_top_10_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_top_10_movies),
          badge = stringResource(R.string.home_badge_tmdb_ranking_italy)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(Top10RowSpacing)
        ) {
          itemsIndexed(top10Movies, key = { _, item -> "top10_film_${item.id}" }) { index, item ->
            Top10RankedMediaCard(
              rank = index + 1,
              media = item,
              onClick = { onMediaClick(item) },
              modifier = Modifier.rowFocusProperties(coordinator, "section_top_10_film", index),
              focusRequester = coordinator.getRequester("section_top_10_film", index)
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 5. TOP 10 SERIE (Card con numero gigante stilizzato)
    if (!hasCustomCatalogSeries && top10Series.isNotEmpty()) {
      item(key = "section_top_10_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_top_10_series),
          badge = stringResource(R.string.home_badge_most_watched_tmdb)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(Top10RowSpacing)
        ) {
          itemsIndexed(top10Series, key = { _, item -> "top10_serie_${item.id}" }) { index, item ->
            Top10RankedMediaCard(
              rank = index + 1,
              media = item,
              onClick = { onMediaClick(item) },
              modifier = Modifier.rowFocusProperties(coordinator, "section_top_10_serie", index),
              focusRequester = coordinator.getRequester("section_top_10_serie", index)
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 6. TRENDING FILM
    if (!hasCustomCatalogMovies && trendingMovies.isNotEmpty()) {
      item(key = "section_trending_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_trending_movies),
          badge = stringResource(R.string.home_badge_trending_week)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(trendingMovies, key = { _, item -> "trend_m_${item.id}" }) { index, item ->
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) },
              modifier = Modifier.rowFocusProperties(coordinator, "section_trending_film", index),
              focusRequester = coordinator.getRequester("section_trending_film", index)
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 7. TRENDING SERIE
    if (!hasCustomCatalogSeries && trendingSeries.isNotEmpty()) {
      item(key = "section_trending_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_trending_series),
          badge = stringResource(R.string.home_badge_trending_phenomena)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(trendingSeries, key = { _, item -> "trend_s_${item.id}" }) { index, item ->
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) },
              modifier = Modifier.rowFocusProperties(coordinator, "section_trending_serie", index),
              focusRequester = coordinator.getRequester("section_trending_serie", index)
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 8. FOR YOU FILM (Consigliati per te - Film)
    if (!hasCustomCatalogMovies && forYouMovies.isNotEmpty()) {
      item(key = "section_for_you_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_for_you_movies),
          badge = stringResource(R.string.home_badge_based_on_preferences)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(forYouMovies, key = { _, item -> "foryou_m_${item.id}" }) { index, item ->
            StandardMediaCard(
              media = item,
              onClick = { onMediaClick(item) },
              modifier = Modifier.rowFocusProperties(coordinator, "section_for_you_film", index),
              focusRequester = coordinator.getRequester("section_for_you_film", index)
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 9. FOR YOU SERIE (Consigliate per te - Serie TV)
    if (!hasCustomCatalogSeries && forYouSeries.isNotEmpty()) {
      item(key = "section_for_you_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_for_you_series),
          badge = stringResource(R.string.home_badge_suggested_for_you)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(forYouSeries, key = { _, item -> "foryou_s_${item.id}" }) { index, item ->
            StandardMediaCard(
              media = item,
              onClick = { onMediaClick(item) },
              modifier = Modifier.rowFocusProperties(coordinator, "section_for_you_serie", index),
              focusRequester = coordinator.getRequester("section_for_you_serie", index)
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 10. POPOLARI FILM
    if (!hasCustomCatalogMovies && popularMovies.isNotEmpty()) {
      item(key = "section_popolari_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_popular_movies),
          badge = stringResource(R.string.home_badge_most_watched_cinema)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(popularMovies, key = { _, item -> "pop_m_${item.id}" }) { index, item ->
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) },
              modifier = Modifier.rowFocusProperties(coordinator, "section_popolari_film", index),
              focusRequester = coordinator.getRequester("section_popolari_film", index)
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 11. POPOLARI SERIE
    if (!hasCustomCatalogSeries && popularSeries.isNotEmpty()) {
      item(key = "section_popolari_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_popular_series),
          badge = stringResource(R.string.home_badge_most_discussed_series)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(popularSeries, key = { _, item -> "pop_s_${item.id}" }) { index, item ->
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) },
              modifier = Modifier.rowFocusProperties(coordinator, "section_popolari_serie", index),
              focusRequester = coordinator.getRequester("section_popolari_serie", index)
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 12. Sezioni catalogo Stremio (addon)
    homeCatalogs?.extraSections?.forEach { extraSec ->
      if (extraSec.items.isNotEmpty()) {
        item(key = "section_${extraSec.id}") {
          CarouselHeader(
            title = extraSec.title,
            badge = extraSec.addonName
          )
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(extraSec.items, key = { _, item -> "extra_${extraSec.id}_${item.id}" }) { index, item ->
              PosterMediaCard(
                media = item,
                onClick = { onMediaClick(item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_${extraSec.id}", index),
                focusRequester = coordinator.getRequester("section_${extraSec.id}", index)
              )
            }
          }
          Spacer(modifier = Modifier.height(20.dp))
        }
      }
    }
  }
  }

  // Context menu per long-press su Continue Watching (posto fuori da LazyColumn per essere in composable scope)
  if (contextMenuMedia != null) {
    val media = contextMenuMedia!!
    MediaContextMenu(
      media = media,
      onPlay = { onPlayClick(media) },
      onDetails = { onMediaClick(media) },
      onRemoveFromContinueWatching = { onRemoveFromContinueWatching(media.id) },
      onDismiss = { contextMenuMedia = null }
    )
  }
}

@Composable
fun CarouselHeader(
  title: String,
  badge: String? = null,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp
      )
      if (badge != null) {
        Text(
          text = "• $badge",
          color = NovaCyanBright,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
  }
}
