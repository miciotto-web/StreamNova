package com.example.ui.screens

import android.util.Log
import android.view.KeyEvent
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
import com.example.ui.components.TvFocusRow
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.LocalClearLastFocusedMedia
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
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
  val streamingEngineMode by viewModel.streamingEngineMode.collectAsState()
  val allMedia by viewModel.allMedia.collectAsState()
  val homeCatalogs by viewModel.homeCatalogs.collectAsState()
  val handleMediaClick: (MediaItem) -> Unit = onMediaClick ?: { viewModel.openDetail(it) }

  val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
  val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()

  // La sidebar offre la sezione "Addon" solo in modalità Debrid TorBox: se il cambio di
  // modalità l'ha resa non più raggiungibile mentre era aperta, il contenuto ricade sulla
  // Home invece di mostrare una schermata che l'utente non può più richiamare dal menu.
  val activeSection = if (currentSection == SidebarSection.ADDON &&
    !streamingEngineMode.supportsAddonsSection
  ) {
    SidebarSection.HOME
  } else {
    currentSection
  }

  CompositionLocalProvider(
    LocalLastFocusedMediaId provides lastFocusedMediaId,
    LocalLastFocusedTarget provides lastFocusedTarget,
    LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() }
  ) {
    when (activeSection) {
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
  initialRowKeys: List<String>,
  initialRowItemCounts: Map<String, Int>
) {
  // Dati delle righe OSSERVABILI ma aggiornabili sul posto: la stessa istanza del
  // coordinatore sopravvive a tutte le ondate di caricamento dei cataloghi. I
  // [FocusRequester] già assegnati restano validi, quindi le celle non devono essere
  // riagganciate ad ogni cambiamento e i target verticali non si azzerano a metà caricamento.
  private val _activeRowKeys = mutableStateListOf<String>().apply { addAll(initialRowKeys) }
  val activeRowKeys: List<String> get() = _activeRowKeys

  private val _rowItemCounts = mutableStateMapOf<String, Int>().apply { putAll(initialRowItemCounts) }
  val rowItemCounts: Map<String, Int> get() = _rowItemCounts

  val lastFocusedColByRow = mutableStateMapOf<String, Int>()
  private val cellRequesters = mutableMapOf<Pair<String, Int>, FocusRequester>()

  // Mappa OSSERVABILE delle celle montate: chiunque legga lo stato (le focusProperties che
  // assegnano up/down) si ricompone quando una cella entra/esce dalla composizione, cosi'
  // un target non resta mai puntato a un nodo smontato. Una mappa "semplice" lasciava
  // target orfani e il focus search del D-pad scatenava
  // IllegalStateException "FocusRequester is not initialized".
  private val attachedCellState = mutableStateMapOf<Pair<String, Int>, Boolean>()

  // Contatore osservabile incrementato ad ogni registrazione di cella: consente a un unico
  // effetto di completare la richiesta di focus pendente non appena il nodo bersaglio esiste.
  private val _registrationTick = mutableStateOf(0)
  val registrationTick: Int get() = _registrationTick.value

  // Riga attualmente focalizzata (inizialmente la prima riga attiva, es. Hero).
  private val _focusedRowKey = mutableStateOf<String?>(initialRowKeys.firstOrNull() ?: "hero_banner")
  val focusedRowKey: String? get() = _focusedRowKey.value

  // Richiesta di focus differita (una sola): il target verticale è noto (riga in
  // activeRowKeys) ma il suo nodo non è ancora montato. Viene completata quando la cella
  // compare e annullata se diventa obsoleta (riga rimossa o focus andato altrove).
  private val _pendingFocusRequest = mutableStateOf<Pair<String, Int>?>(null)
  val pendingFocusRequest: Pair<String, Int>? get() = _pendingFocusRequest.value

  /**
   * Aggiorna righe e conteggi SENZA ricreare il coordinatore. Le registrazioni non più valide
   * (riga eliminata o colonna oltre il nuovo conteggio) vengono rimosse insieme ai loro
   * [FocusRequester], così un target non punta mai a un nodo che non esiste più.
   */
  fun updateRows(newKeys: List<String>, newCounts: Map<String, Int>) {
    if (_activeRowKeys.toList() == newKeys && _rowItemCounts.toMap() == newCounts) return

    _rowItemCounts.clear()
    _rowItemCounts.putAll(newCounts)
    _activeRowKeys.clear()
    _activeRowKeys.addAll(newKeys)

    val validKeys = newKeys.toSet()
    val isStale: (Pair<String, Int>) -> Boolean = { (row, col) ->
      row !in validKeys || (_rowItemCounts[row] ?: 0) <= col
    }
    cellRequesters.keys.filter { isStale(it) }.forEach { cellRequesters.remove(it) }
    attachedCellState.keys.filter { isStale(it) }.forEach { attachedCellState.remove(it) }
    lastFocusedColByRow.keys
      .filter { it !in validKeys }
      .toList()
      .forEach { lastFocusedColByRow.remove(it) }

    // Se la riga precedentemente focalizzata è stata rimossa, aggiorna la riga corrente.
    if (_focusedRowKey.value !in validKeys) {
      _focusedRowKey.value = newKeys.firstOrNull()
    }

    // Una richiesta pendente verso una riga eliminata non ha più senso.
    _pendingFocusRequest.value?.let { (row, _) ->
      if (row !in validKeys) _pendingFocusRequest.value = null
    }
  }

  fun getRequester(rowKey: String, colIndex: Int): FocusRequester {
    return cellRequesters.getOrPut(Pair(rowKey, colIndex)) { FocusRequester() }
  }

  fun onCellFocused(rowKey: String, colIndex: Int) {
    _focusedRowKey.value = rowKey
    lastFocusedColByRow[rowKey] = colIndex
    // Se il focus raggiunge una cella diversa da quella attesa, la richiesta pendente è obsoleta.
    _pendingFocusRequest.value?.let { pending ->
      if (pending != Pair(rowKey, colIndex)) _pendingFocusRequest.value = null
    }
  }

  fun registerCell(rowKey: String, colIndex: Int) {
    val cell = Pair(rowKey, colIndex)
    if (attachedCellState[cell] != true) {
      attachedCellState[cell] = true
      _registrationTick.value++
    }
  }

  fun unregisterCell(rowKey: String, colIndex: Int) {
    attachedCellState.remove(Pair(rowKey, colIndex))
  }

  fun isCellAttached(rowKey: String, colIndex: Int): Boolean =
    attachedCellState.containsKey(Pair(rowKey, colIndex))

  /** `true` se la riga fa parte delle righe attive e ha almeno un elemento. */
  fun hasItems(rowKey: String): Boolean = (_rowItemCounts[rowKey] ?: 0) > 0

  fun getNextRowKey(currentRowKey: String): String? {
    val idx = activeRowKeys.indexOf(currentRowKey)
    return if (idx in 0 until activeRowKeys.lastIndex) activeRowKeys[idx + 1] else null
  }

  fun getPrevRowKey(currentRowKey: String): String? {
    val idx = activeRowKeys.indexOf(currentRowKey)
    return if (idx > 0) activeRowKeys[idx - 1] else null
  }

  /** Registra una richiesta di focus verso una riga nota il cui nodo non è ancora montato. */
  fun requestPendingFocus(rowKey: String, colIndex: Int) {
    _pendingFocusRequest.value = Pair(rowKey, colIndex)
  }

  /** Consuma (una sola volta) la richiesta pendente, restituendola. */
  fun consumePendingFocusRequest(): Pair<String, Int>? {
    val current = _pendingFocusRequest.value ?: return null
    _pendingFocusRequest.value = null
    return current
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
  // Se il bersaglio non ha ancora celle montate il target resta null e si imposta
  // FocusRequester.Cancel per impedire all'algoritmo 2D di scavalcare la riga bersaglio
  // (transizione rigida N <-> N±1). La discesa viene completata dalla richiesta pendente
  // registrata in onKeyEvent e soddisfatta da LaunchedEffect(coordinator.registrationTick).
  val nextRowKey = coordinator.getNextRowKey(rowKey)
  val downTarget = nextRowKey?.let { coordinator.getTargetRequester(it, colIndex) }
  val prevRowKey = coordinator.getPrevRowKey(rowKey)
  val upTarget = prevRowKey?.let { coordinator.getTargetRequester(it, colIndex) }

  return this
    // Un GIÙ/SU verso una riga NOTA che non ha ancora celle montate non deve risolversi
    // silenziosamente nella ricerca geometrica (FocusRequester.Default). Registriamo qui
    // UNA richiesta pendente: sarà completata quando la cella bersaglio entrerà in
    // composizione. L'evento NON viene consumato: se il target è a schermo la navigazione
    // normale ha già il requester corretto e questo ramo non si attiva.
    .onKeyEvent { keyEvent ->
      val native = keyEvent.nativeKeyEvent
      if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
      when (native.keyCode) {
        KeyEvent.KEYCODE_DPAD_DOWN -> {
          if (downTarget == null && nextRowKey != null && coordinator.hasItems(nextRowKey)) {
            coordinator.requestPendingFocus(nextRowKey, colIndex)
          }
          false
        }
        KeyEvent.KEYCODE_DPAD_UP -> {
          if (upTarget == null && prevRowKey != null && coordinator.hasItems(prevRowKey)) {
            coordinator.requestPendingFocus(prevRowKey, colIndex)
          }
          false
        }
        else -> false
      }
    }
    .focusProperties {
      down = downTarget ?: FocusRequester.Cancel
      up = upTarget ?: FocusRequester.Cancel
    }
    .onFocusChanged { state ->
      if (state.isFocused || state.hasFocus) {
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

  // Click ROW-SCOPED: salva il target `(rowKey, media.id)` PRIMA della navigazione.
  // Al Back solo la riga d'origine ripristina il focus: lo stesso titolo può vivere
  // contemporaneamente in più righe (es. Popolari e Trending) e senza la riga tutte
  // le candidate richiederebbero il focus insieme, vincendo quella sbagliata.
  val handleRowMediaClick: (String, MediaItem) -> Unit = { rowKey, item ->
    viewModel?.saveLastFocusedMedia(rowKey, item.id)
    onMediaClick(item)
  }

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

  // UNICA istanza per tutta la vita della Home: sopravvive ai caricamenti asincroni dei
  // cataloghi. I dati delle righe vengono aggiornati sul posto, così i FocusRequester già
  // assegnati restano validi e la prima navigazione non parte con target azzerati.
  val coordinator = remember { HomeRowFocusCoordinator(activeRowKeys, rowItemCounts) }

  LaunchedEffect(activeRowKeys, rowItemCounts) {
    coordinator.updateRows(activeRowKeys, rowItemCounts)
  }

  // Completa UNA richiesta di focus pendente (riga nota non ancora montata) non appena la
  // cella bersaglio entra in composizione. Nessun loop: l'effetto scatta ad ogni
  // registrazione di cella e consuma la richiesta una sola volta.
  LaunchedEffect(coordinator.registrationTick) {
    val pending = coordinator.pendingFocusRequest ?: return@LaunchedEffect
    val requester = coordinator.getTargetRequester(pending.first, pending.second)
      ?: return@LaunchedEffect
    coordinator.consumePendingFocusRequest()
    try {
      requester.requestFocus()
    } catch (_: Exception) {}
  }

  val listState = rememberLazyListState()

  // Riga bersaglio per lo scroll sincronizzato:
  // Se c'è una richiesta pendente (transizione verso riga non ancora montata), usiamo quella.
  // Altrimenti usiamo la riga che ha attualmente il focus.
  val scrollTargetRow = coordinator.pendingFocusRequest?.first ?: coordinator.focusedRowKey

  LaunchedEffect(scrollTargetRow) {
    val targetRow = scrollTargetRow ?: return@LaunchedEffect
    val targetIndex = coordinator.activeRowKeys.indexOf(targetRow)
    if (targetIndex < 0) return@LaunchedEffect

    if (targetIndex == 0) {
      if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
        listState.animateScrollToItem(0, 0)
      }
    } else {
      if (listState.firstVisibleItemIndex != targetIndex || listState.firstVisibleItemScrollOffset != 0) {
        listState.animateScrollToItem(targetIndex, 0)
      }
    }
  }

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

  // Letto al momento dell'evento senza riavviare il collettore (nessuna perdita di eventi).
  val heroItemsAvailable by rememberUpdatedState(dynamicHeroItems.isNotEmpty())

  // Scroll to top automatico cliccando "Home" dalla sidebar (Punto 4) e focus iniziale
  // della Home: entrambi devono appoggiarsi sull'Hero, prima cella del contenuto.
  // Il collettore resta agganciato UNA SOLA volta: se fosse riavviato ad ogni cambiamento
  // delle righe, un evento emesso nel frattempo andrebbe perso (SharedFlow senza replay).
  LaunchedEffect(viewModel) {
    viewModel?.homeScrollToTopEvent?.collect {
      listState.animateScrollToItem(0)
      try {
        if (heroItemsAvailable) {
          coordinator.getRequester("hero_banner", 0).requestFocus()
        } else {
          pendingHeroFocus = true
          coordinator.getRequester("section_providers_hub", 0).requestFocus()
        }
      } catch (_: Exception) {}
    }
  }

  LaunchedEffect(pendingHeroFocus, dynamicHeroItems.isNotEmpty()) {
    if (pendingHeroFocus && dynamicHeroItems.isNotEmpty()) {
      pendingHeroFocus = false
      try {
        coordinator.getRequester("hero_banner", 0).requestFocus()
      } catch (_: Exception) {}
    }
  }

  LaunchedEffect(focusRestoreSignal.value, listState, coordinator) {
    val mediaId = focusRestoreSignal.value ?: return@LaunchedEffect
    focusRestoreSignal.value = null

    // Il media deve esistere nel catalogo, altrimenti non c'è nulla da ripristinare.
    allMedia.find { it.id == mediaId } ?: return@LaunchedEffect

    val restoreTarget = viewModel?.lastFocusedTarget?.value
    // Target già consumato dalla card della riga corretta: il ripristino è avvenuto (o è
    // in corso) e una richiesta da qui ruberebbe il focus a una riga sbagliata.
    if (restoreTarget == null) return@LaunchedEffect

    fun itemsOf(sectionKey: String): List<MediaItem> = when (sectionKey) {
      "hero_banner" -> dynamicHeroItems
      "section_continue_watching" -> continueWatching
      "section_top_10_film" -> top10Movies
      "section_top_10_serie" -> top10Series
      "section_trending_film" -> trendingMovies
      "section_trending_serie" -> trendingSeries
      "section_for_you_film" -> forYouMovies
      "section_for_you_serie" -> forYouSeries
      "section_popolari_film" -> popularMovies
      "section_popolari_serie" -> popularSeries
      else -> homeCatalogs?.extraSections?.find { "section_${it.id}" == sectionKey }?.items ?: emptyList()
    }

    // La riga salvata nel target ha la PRIORITÀ assoluta: solo quella ripristina il focus.
    val preferredRowKey = restoreTarget.first.takeIf { it.isNotEmpty() }
    val sectionKey = if (preferredRowKey != null && itemsOf(preferredRowKey).any { it.id == mediaId }) {
      preferredRowKey
    } else when {
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

    if (sectionKey == "hero_banner") {
      try {
        coordinator.getRequester("hero_banner", 0).requestFocus()
        viewModel?.clearLastFocusedMedia()
      } catch (_: Exception) {}
      return@LaunchedEffect
    }

    if (sectionKey != null && (rowItemCounts[sectionKey] ?: 0) > 0) {
      val index = itemsOf(sectionKey).indexOfFirst { it.id == mediaId }

      if (index >= 0) {
        val requester = coordinator.getTargetRequester(sectionKey, index)
        if (requester != null) {
          try {
            requester.requestFocus()
            // Focus richiesto con successo: il target viene consumato subito così nessun'altra
            // riga o frame successivo può spostare il focus sulla riga sbagliata.
            viewModel?.clearLastFocusedMedia()
          } catch (_: Exception) {}
        } else {
          coordinator.requestPendingFocus(sectionKey, index)
        }
      }
    }
  }

  TvFocusBringIntoView(topOffsetDp = 0.dp) {
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
          modifier = Modifier.onFocusChanged {
            heroHasFocus = it.hasFocus
            if (it.hasFocus) {
              coordinator.onCellFocused("hero_banner", 0)
            }
          }
        ) {
          HeroBanner(
            items = dynamicHeroItems,
            // OK/Enter e click sull'Hero aprono la scheda del titolo corrente: i pulsanti
            // azione non esistono piu', l'intero banner e' il target di focus.
            onDetailsClick = { handleRowMediaClick("hero_banner", it) },
            heroFocusRequester = coordinator.getRequester("hero_banner", 0),
            // Hero va alla riga successiva determinata dal coordinatore.
            heroDownFocusRequester = coordinator.getNextRowKey("hero_banner")?.let { nextKey ->
              coordinator.getTargetRequester(nextKey, 0)
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
        TvFocusRow {
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
                focusRequester = coordinator.getRequester("section_continue_watching", index),
                rowKey = "section_continue_watching"
              )
            }
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
      TvFocusRow {
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
        TvFocusRow {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Top10RowSpacing)
          ) {
            itemsIndexed(top10Movies, key = { index, item -> "top10_${item.id}_$index" }) { index, item ->
              Top10RankedMediaCard(
                rank = index + 1,
                media = item,
                onClick = { handleRowMediaClick("section_top_10_film", item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_top_10_film", index),
                focusRequester = coordinator.getRequester("section_top_10_film", index),
                rowKey = "section_top_10_film"
              )
            }
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
        TvFocusRow {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Top10RowSpacing)
          ) {
            itemsIndexed(top10Series, key = { index, item -> "top10_${item.id}_$index" }) { index, item ->
              Top10RankedMediaCard(
                rank = index + 1,
                media = item,
                onClick = { handleRowMediaClick("section_top_10_serie", item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_top_10_serie", index),
                focusRequester = coordinator.getRequester("section_top_10_serie", index),
                rowKey = "section_top_10_serie"
              )
            }
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
        TvFocusRow {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(trendingMovies, key = { _, item -> "trend_m_${item.id}" }) { index, item ->
              PosterMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_trending_film", item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_trending_film", index),
                focusRequester = coordinator.getRequester("section_trending_film", index),
                rowKey = "section_trending_film"
              )
            }
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
        TvFocusRow {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(trendingSeries, key = { _, item -> "trend_s_${item.id}" }) { index, item ->
              PosterMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_trending_serie", item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_trending_serie", index),
                focusRequester = coordinator.getRequester("section_trending_serie", index),
                rowKey = "section_trending_serie"
              )
            }
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
        TvFocusRow {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(forYouMovies, key = { _, item -> "foryou_m_${item.id}" }) { index, item ->
              StandardMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_for_you_film", item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_for_you_film", index),
                focusRequester = coordinator.getRequester("section_for_you_film", index),
                rowKey = "section_for_you_film"
              )
            }
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
        TvFocusRow {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(forYouSeries, key = { _, item -> "foryou_s_${item.id}" }) { index, item ->
              StandardMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_for_you_serie", item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_for_you_serie", index),
                focusRequester = coordinator.getRequester("section_for_you_serie", index),
                rowKey = "section_for_you_serie"
              )
            }
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
        TvFocusRow {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(popularMovies, key = { _, item -> "pop_m_${item.id}" }) { index, item ->
              PosterMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_popolari_film", item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_popolari_film", index),
                focusRequester = coordinator.getRequester("section_popolari_film", index),
                rowKey = "section_popolari_film"
              )
            }
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
        TvFocusRow {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(popularSeries, key = { _, item -> "pop_s_${item.id}" }) { index, item ->
              PosterMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_popolari_serie", item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_popolari_serie", index),
                focusRequester = coordinator.getRequester("section_popolari_serie", index),
                rowKey = "section_popolari_serie"
              )
            }
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
          TvFocusRow {
            LazyRow(
              contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
              itemsIndexed(extraSec.items, key = { _, item -> "extra_${extraSec.id}_${item.id}" }) { index, item ->
                PosterMediaCard(
                  media = item,
                  onClick = { handleRowMediaClick("section_${extraSec.id}", item) },
                  modifier = Modifier.rowFocusProperties(coordinator, "section_${extraSec.id}", index),
                  focusRequester = coordinator.getRequester("section_${extraSec.id}", index),
                  rowKey = "section_${extraSec.id}"
                )
              }
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
      onDetails = { handleRowMediaClick("section_continue_watching", media) },
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
