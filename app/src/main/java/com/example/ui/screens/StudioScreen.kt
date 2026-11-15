package com.example.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.example.R
import com.example.data.model.MediaItem
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.requestFocusWhenAttached
import com.example.ui.components.stabilizeFirstSeenOrder
import com.example.ui.components.StudioDefinition
import com.example.ui.components.TvFocusAttachment
import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusableBox
import com.example.ui.components.guard
import com.example.ui.components.tvFocusRequester
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.StreamNovaViewModel
import com.example.ui.viewmodel.StudioCatalogFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Tag di test della griglia poster della scheda Studio (harness D-pad / test visivo). */
internal const val STUDIO_GRID_TEST_TAG = "studio_media_grid"

/**
 * Scheda STUDIO dedicata (destinazione dell'hub «Studios» della Home).
 *
 * Struttura: header a gradiente di brand con il LOGO ORIGINALE dello studio, fila di badge
 * filtro ([StudioCatalogFilter]: Tutti, Ultimi Film, Ultime Serie TV, Top 10, Azione,
 * Fantascienza, Dramma, Commedia, Animazione, Horror, Fantasy) e griglia di poster con
 * caricamento progressivo TMDB (discover `with_companies`).
 *
 * Il protocollo di focus replica la scheda Provider: BACK su una card riporta griglia e
 * badge a inizio scheda; BACK sui badge esce; UP dalla prima riga raggiunge l'ultima chip
 * focalizzata; la griglia conserva scroll e ordine al ritorno dal Dettaglio.
 */
@Composable
fun StudioScreen(
  studio: StudioDefinition,
  onBackClick: () -> Unit,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: StreamNovaViewModel? = null
) {
  // BACK su una card della griglia: UNA pressione torna in cima alla scheda (badge «Tutti» +
  // primo poster); con il focus fuori dalla griglia il BACK esce dalla scheda.
  var gridHasFocus by remember { mutableStateOf(false) }
  val filterRowState = rememberLazyListState()
  val backToTopScope = rememberCoroutineScope()

  val ambientLastFocusedMediaId = LocalLastFocusedMediaId.current
  val ambientLastFocusedTarget = LocalLastFocusedTarget.current
  val ambientFocusRestoreToken = LocalFocusRestoreToken.current
  val ambientClearLastFocusedMedia = LocalClearLastFocusedMedia.current
  val lastFocusedMediaId by (viewModel?.lastFocusedMediaId ?: MutableStateFlow(ambientLastFocusedMediaId)).collectAsState()
  val lastFocusedTarget by (viewModel?.lastFocusedTarget ?: MutableStateFlow(ambientLastFocusedTarget)).collectAsState()
  val focusRestoreToken by (viewModel?.focusRestoreToken ?: MutableStateFlow(ambientFocusRestoreToken)).collectAsState()

  // Nuovo evento di ripristino a ogni ingresso con un target salvato (ritorno dal Dettaglio).
  LaunchedEffect(Unit) {
    val targetId = viewModel?.lastFocusedMediaId?.value ?: return@LaunchedEffect
    viewModel.signalFocusRestore(targetId)
  }

  val uiState by (viewModel?.studioCatalogUiState ?: MutableStateFlow(
    com.example.ui.viewmodel.StudioCatalogUiState(studioId = studio.id, studioName = studio.name)
  )).collectAsState()

  var selectedFilter by rememberSaveable(studio.id) { mutableStateOf(StudioCatalogFilter.ALL) }

  // Selezione filtro -> ViewModel (cache immediata o fetch pagina iniziale).
  LaunchedEffect(studio, selectedFilter) {
    viewModel?.selectStudio(studio, selectedFilter)
  }

  // Ordine CONGELATO per id di prima comparsa: le riemissioni dello stato (pagine che
  // arrivano, riordini della cache Room) non rimescolano le card già a schermo.
  val frozenOrder = rememberSaveable(
    studio.id,
    saver = androidx.compose.runtime.saveable.listSaver<MutableList<String>, String>(
      save = { order -> order.toList() },
      restore = { saved -> saved.toMutableList() }
    )
  ) { mutableListOf<String>() }
  val orderedItems = remember(uiState.items) {
    stabilizeFirstSeenOrder(uiState.items, frozenOrder) { it.id }
  }

  // Stato di scroll PERSISTENTE durante la navigazione (ritorno dal Dettaglio).
  val gridState = rememberSaveable(studio.id, saver = LazyGridState.Saver) { LazyGridState() }

  val hasMore = uiState.hasMore
  val isLoading = uiState.isLoading

  fun loadNextPage() {
    viewModel?.loadNextStudioPage(studio)
  }

  // Pre-caricamento pagina iniziale al primo ingresso / cambio filtro con cache vuota:
  // il ViewModel ignora le chiamate duplicate o già esaurite.
  LaunchedEffect(studio, selectedFilter) {
    if (orderedItems.isEmpty() && !uiState.isLoading) {
      loadNextPage()
    }
  }

  // Scorrimento progressivo: snapshotFlow rileva l'avvicinamento al fondo griglia (anche
  // via D-pad) e anticipa la pagina successiva senza attendere il bordo esatto.
  LaunchedEffect(gridState, selectedFilter, orderedItems.size, hasMore, isLoading) {
    if (!hasMore) return@LaunchedEffect
    snapshotFlow {
      val layoutInfo = gridState.layoutInfo
      layoutInfo.totalItemsCount to (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)
    }
      .distinctUntilChanged()
      .collect { (totalItems, lastVisibleItemIndex) ->
        if (shouldRequestNextProviderPage(
            totalItems = totalItems,
            lastVisibleItemIndex = lastVisibleItemIndex,
            isLoading = isLoading,
            hasMoreForFilter = hasMore
          )
        ) {
          loadNextPage()
        }
      }
  }

  // Target di ripristino sparito dalla lista: rilascio senza salti, la griglia resta navigabile.
  LaunchedEffect(lastFocusedMediaId, orderedItems) {
    val targetId = lastFocusedMediaId ?: return@LaunchedEffect
    if (orderedItems.none { it.id == targetId }) {
      viewModel?.clearLastFocusedMedia()
    }
  }

  LaunchedEffect(orderedItems, selectedFilter) {
    Log.d(
      "STUDIO_CATALOG",
      "studio=${studio.name} filter=${selectedFilter.name} -> ${orderedItems.size} poster in griglia"
    )
  }

  val firstGridItemFocusRequester = remember { FocusRequester() }
  val firstGridItemAttachment = remember { TvFocusAttachment() }
  val filterFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
  val filterAttachments = remember { mutableMapOf<Int, TvFocusAttachment>() }
  var lastFocusedFilterIndex by remember { mutableStateOf(0) }

  BackHandler {
    if (gridHasFocus) {
      backToTopScope.launch {
        try {
          filterRowState.scrollToItem(0)
        } catch (_: Exception) {
        }
        try {
          gridState.scrollToItem(0)
        } catch (_: Exception) {
        }
        filterFocusRequesters[0]?.let { requestFocusWhenAttached(it) }
      }
    } else {
      onBackClick()
    }
  }

  CompositionLocalProvider(
    LocalLastFocusedMediaId provides lastFocusedMediaId,
    LocalLastFocusedTarget provides lastFocusedTarget,
    LocalFocusRestoreToken provides focusRestoreToken,
    LocalClearLastFocusedMedia provides if (viewModel != null) {
      { viewModel.clearLastFocusedMedia() }
    } else {
      ambientClearLastFocusedMedia
    }
  ) {
    Column(
      modifier = modifier
        .fillMaxSize()
        .background(NovaBackground)
    ) {
      // Header con gradiente di brand e LOGO ORIGINALE dello studio (nessun filtro colore).
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(studio.headerBrush)
          .padding(start = 32.dp, top = 26.dp, end = 32.dp, bottom = 12.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          Image(
            painter = painterResource(id = studio.logoResId),
            contentDescription = studio.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
              .size(width = 96.dp, height = 56.dp)
              .clip(RoundedCornerShape(10.dp))
              .border(1.dp, studio.accentColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
              .padding(horizontal = 6.dp, vertical = 6.dp)
          )

          Text(
            text = studio.name.uppercase(),
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
          )
        }
      }

      // Badge dei filtri (pill, identiche fra loro), ordinati come da specifica.
      LazyRow(
        state = filterRowState,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 32.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        itemsIndexed(StudioCatalogFilter.values(), key = { _, filter -> filter.name }, contentType = { _, _ -> "studio_filter_chip" }) { index, filter ->
          val isSelected = selectedFilter == filter
          val filterRequester = filterFocusRequesters.getOrPut(index) { FocusRequester() }
          val filterAttachment = filterAttachments.getOrPut(index) { TvFocusAttachment() }
          TvFocusableBox(
            modifier = Modifier
              .tvFocusRequester(filterRequester, filterAttachment)
              .focusProperties {
                // Solo se il primo poster è davvero montato; altrimenti D-pad standard.
                down = firstGridItemAttachment.guard(firstGridItemFocusRequester)
                  ?: FocusRequester.Default
              }
              .onFocusChanged {
                if (it.isFocused) {
                  lastFocusedFilterIndex = index
                }
              },
            shape = RoundedCornerShape(50),
            focusedScale = 1.05f,
            onClick = { selectedFilter = filter }
          ) { isFocused ->
            Box(
              modifier = Modifier
                .background(
                  when {
                    isFocused -> studio.accentColor
                    isSelected -> studio.primaryColor.copy(alpha = 0.45f)
                    else -> NovaSurfaceVariant
                  },
                  RoundedCornerShape(50)
                )
                .border(
                  1.dp,
                  if (isSelected || isFocused) studio.accentColor else Color(0xFF1E293B),
                  RoundedCornerShape(50)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
              Text(
                text = stringResource(filter.labelRes),
                color = if (isFocused) Color.Black else if (isSelected) studio.accentColor else NovaTextSecondary,
                fontSize = 13.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Griglia dei poster dello studio per il filtro corrente.
      if (orderedItems.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
          contentAlignment = Alignment.Center
        ) {
          if (isLoading) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = studio.accentColor,
                strokeWidth = 3.dp
              )
              Text(
                text = stringResource(R.string.studio_loading_catalog, studio.name),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
              )
            }
          } else {
            Text(
              text = stringResource(R.string.studio_empty_catalog, studio.name),
              color = NovaTextMuted,
              fontSize = 16.sp
            )
          }
        }
      } else {
        TvFocusBringIntoView(topOffsetDp = 48.dp) {
          BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val gridColumns = with(density) {
              adaptiveGridColumnCountPx(
                availableWidthPx = (maxWidth - PROVIDER_GRID_SIDE_PADDING * 2).roundToPx(),
                minCellWidthPx = PROVIDER_GRID_MIN_CELL_WIDTH.roundToPx(),
                spacingPx = PROVIDER_GRID_SPACING.roundToPx()
              )
            }
            LazyVerticalGrid(
              state = gridState,
              columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = PROVIDER_GRID_MIN_CELL_WIDTH),
              contentPadding = PaddingValues(
                start = PROVIDER_GRID_SIDE_PADDING,
                end = PROVIDER_GRID_SIDE_PADDING,
                top = 24.dp,
                bottom = 48.dp
              ),
              horizontalArrangement = Arrangement.spacedBy(PROVIDER_GRID_SPACING),
              verticalArrangement = Arrangement.spacedBy(PROVIDER_GRID_SPACING),
              modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { gridHasFocus = it.hasFocus }
                .testTag(STUDIO_GRID_TEST_TAG)
            ) {
              itemsIndexed(orderedItems, key = { _, it -> it.id }, contentType = { _, _ -> "studio_poster" }) { index, item ->
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .then(
                      if (index == 0) {
                        Modifier.tvFocusRequester(firstGridItemFocusRequester, firstGridItemAttachment)
                      } else {
                        Modifier
                      }
                    )
                    .focusProperties {
                      // Solo la PRIMA riga della griglia è il limite superiore: da lì il
                      // D-pad UP raggiunge la chip filtro corrente (o l'ultima focalizzata).
                      if (index < gridColumns) {
                        val filterAttachment = filterAttachments[lastFocusedFilterIndex]
                        val filterRequester = filterFocusRequesters[lastFocusedFilterIndex]
                        up = filterAttachment.guard(filterRequester) ?: FocusRequester.Default
                      }
                    },
                  contentAlignment = Alignment.TopCenter
                ) {
                  PosterMediaCard(
                    media = item,
                    onClick = { onMediaClick(item) },
                    confirmFocusRestore = true
                  )
                }
              }

              if (isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                  Box(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(12.dp),
                      modifier = Modifier
                        .background(Color(0xCC141414), RoundedCornerShape(24.dp))
                        .border(1.dp, studio.accentColor.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = studio.accentColor,
                        strokeWidth = 2.dp
                      )
                      Text(
                        text = stringResource(R.string.studio_loading_more, studio.name),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
