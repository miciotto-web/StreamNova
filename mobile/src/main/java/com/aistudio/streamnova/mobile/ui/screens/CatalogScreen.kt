package com.aistudio.streamnova.mobile.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MobileCatalogViewModel
import com.aistudio.streamnova.mobile.ui.components.MobileEmptyState
import com.aistudio.streamnova.mobile.ui.components.MobilePosterCard
import com.aistudio.streamnova.mobile.ui.components.MobileTopBar
import com.example.data.model.MediaItem

enum class CatalogKind { MOVIES, SERIES, FAVORITES }

@Composable
fun CatalogScreen(
  @StringRes titleRes: Int,
  kind: CatalogKind,
  onMediaClick: (MediaItem) -> Unit
) {
  val viewModel: MobileCatalogViewModel = viewModel()
  val state by viewModel.catalog.collectAsStateWithLifecycle()

  val items = when (kind) {
    CatalogKind.MOVIES -> state.movies
    CatalogKind.SERIES -> state.series
    CatalogKind.FAVORITES -> state.favorites
  }

  LazyVerticalGrid(
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
        if (kind == CatalogKind.FAVORITES) {
          MobileEmptyState(
            title = stringResource(R.string.favorites_empty_title),
            body = stringResource(R.string.favorites_empty_body)
          )
        } else {
          MobileEmptyState(
            title = stringResource(titleRes),
            body = stringResource(R.string.catalog_empty)
          )
        }
      }
    } else {
      items(items, key = { it.id }) { media ->
        MobilePosterCard(media = media, onClick = { onMediaClick(media) })
      }
    }
  }
}

@Composable
fun FavoritesScreen(
  onMediaClick: (MediaItem) -> Unit,
  onExplore: () -> Unit
) {
  CatalogScreen(
    titleRes = R.string.nav_favorites,
    kind = CatalogKind.FAVORITES,
    onMediaClick = onMediaClick
  )
}
