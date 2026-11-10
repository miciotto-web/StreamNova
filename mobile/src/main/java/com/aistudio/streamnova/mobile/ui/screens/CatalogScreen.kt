package com.aistudio.streamnova.mobile.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MobileCatalogViewModel
import com.aistudio.streamnova.mobile.ui.components.MobileEmptyState
import com.aistudio.streamnova.mobile.ui.components.MobileLoadingState
import com.aistudio.streamnova.mobile.ui.components.MobilePosterCard
import com.aistudio.streamnova.mobile.ui.components.MobileTopBar
import com.aistudio.streamnova.mobile.ui.shouldRequestNextMobilePage
import com.example.data.model.MediaItem
import kotlinx.coroutines.flow.distinctUntilChanged

enum class CatalogKind { MOVIES, SERIES, FAVORITES }

@Composable
fun CatalogScreen(
  viewModel: MobileCatalogViewModel,
  @StringRes titleRes: Int,
  kind: CatalogKind,
  onMediaClick: (MediaItem) -> Unit
) {
  val state by viewModel.catalog.collectAsStateWithLifecycle()
  val isPaginatingMovies by viewModel.isPaginatingMovies.collectAsStateWithLifecycle()
  val isPaginatingSeries by viewModel.isPaginatingSeries.collectAsStateWithLifecycle()
  val gridState = rememberLazyGridState()

  val items = when (kind) {
    CatalogKind.MOVIES -> state.movies
    CatalogKind.SERIES -> state.series
    CatalogKind.FAVORITES -> state.favorites
  }

  // Caricamento progressivo (parità con la TV 1.0.5): avvicinandosi alla fine della
  // griglia si richiede la pagina successiva della sorgente attiva (Cinemeta/addon
  // via `skip`, oppure TMDB). I Preferiti sono una lista locale: nessuna paginazione.
  // Lo stato si legge SEMPRE dal ViewModel dentro il collect: la closure non deve
  // catturare valori di una composizione precedente.
  LaunchedEffect(gridState, kind) {
    snapshotFlow {
      val layoutInfo = gridState.layoutInfo
      layoutInfo.totalItemsCount to (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)
    }
      .distinctUntilChanged()
      .collect { (totalItems, lastVisibleItemIndex) ->
        val catalogState = viewModel.catalog.value
        if (catalogState.isLoading) return@collect
        when (kind) {
          CatalogKind.MOVIES -> {
            if (catalogState.movies.isEmpty()) return@collect
            if (shouldRequestNextMobilePage(
                totalItems = totalItems,
                lastVisibleItemIndex = lastVisibleItemIndex,
                isLoading = viewModel.isPaginatingMovies.value
              )
            ) {
              viewModel.loadNextMoviesPage()
            }
          }
          CatalogKind.SERIES -> {
            if (catalogState.series.isEmpty()) return@collect
            if (shouldRequestNextMobilePage(
                totalItems = totalItems,
                lastVisibleItemIndex = lastVisibleItemIndex,
                isLoading = viewModel.isPaginatingSeries.value
              )
            ) {
              viewModel.loadNextTvPage()
            }
          }
          CatalogKind.FAVORITES -> Unit
        }
      }
  }

  LazyVerticalGrid(
    state = gridState,
    columns = GridCells.Adaptive(minSize = 112.dp),
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item(key = "topbar", span = { GridItemSpan(maxLineSpan) }) {
      MobileTopBar(title = stringResource(titleRes), onSettings = null)
    }
    if (items.isEmpty()) {
      item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
        when {
          state.isLoading -> MobileLoadingState(
            message = stringResource(R.string.catalog_loading)
          )

          state.error != null -> MobileEmptyState(
            title = stringResource(R.string.catalog_error_title),
            body = stringResource(R.string.catalog_error_body),
            actionLabel = stringResource(R.string.action_retry),
            onAction = viewModel::retry
          )

          kind == CatalogKind.FAVORITES -> MobileEmptyState(
            title = stringResource(R.string.favorites_empty_title),
            body = stringResource(R.string.favorites_empty_body)
          )

          else -> MobileEmptyState(
            title = stringResource(titleRes),
            body = stringResource(R.string.catalog_empty),
            actionLabel = stringResource(R.string.action_retry),
            onAction = viewModel::retry
          )
        }
      }
    } else {
      items(items, key = { it.id }) { media ->
        MobilePosterCard(media = media, onClick = { onMediaClick(media) })
      }
      // Footer di caricamento: la pagina successiva è in volo.
      val isPaginating = when (kind) {
        CatalogKind.MOVIES -> isPaginatingMovies
        CatalogKind.SERIES -> isPaginatingSeries
        CatalogKind.FAVORITES -> false
      }
      if (isPaginating) {
        item(key = "paging_footer", span = { GridItemSpan(maxLineSpan) }) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(56.dp),
            contentAlignment = Alignment.Center
          ) {
            CircularProgressIndicator()
          }
        }
      }
    }
  }
}

@Composable
fun FavoritesScreen(
  viewModel: MobileCatalogViewModel,
  onMediaClick: (MediaItem) -> Unit,
  onExplore: () -> Unit
) {
  CatalogScreen(
    viewModel = viewModel,
    titleRes = R.string.nav_favorites,
    kind = CatalogKind.FAVORITES,
    onMediaClick = onMediaClick
  )
}
