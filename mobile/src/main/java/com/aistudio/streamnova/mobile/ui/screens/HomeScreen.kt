package com.aistudio.streamnova.mobile.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.LocalWindowWidthExpanded
import com.aistudio.streamnova.mobile.ui.MobileCatalogViewModel
import com.aistudio.streamnova.mobile.ui.components.MobileHero
import com.aistudio.streamnova.mobile.ui.components.MobileSectionRow
import com.aistudio.streamnova.mobile.ui.components.MobileTopBar
import com.example.data.model.MediaItem
import com.example.ui.theme.NovaBackground

@Composable
fun HomeScreen(
  onMediaClick: (MediaItem) -> Unit,
  onPlayMedia: (MediaItem) -> Unit,
  onSeeAllMovies: () -> Unit,
  onSeeAllSeries: () -> Unit,
  onSettings: () -> Unit
) {
  val viewModel: MobileCatalogViewModel = viewModel()
  val state by viewModel.catalog.collectAsStateWithLifecycle()
  val expanded = LocalWindowWidthExpanded.current

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 28.dp)
  ) {
    item(key = "topbar") {
      MobileTopBar(title = null, onSettings = onSettings)
    }

    state.hero?.let { hero ->
      item(key = "hero") {
        MobileHero(
          media = hero,
          onPlay = { onPlayMedia(hero) },
          onDetails = { onMediaClick(hero) },
          expanded = expanded
        )
      }
    }

    if (state.continueWatching.isNotEmpty()) {
      item(key = "continue") {
        MobileSectionRow(
          title = stringResource(R.string.home_continue),
          items = state.continueWatching,
          onMediaClick = onMediaClick,
          fixedWidth = false
        )
      }
    }

    if (state.popular.isNotEmpty()) {
      item(key = "popular") {
        MobileSectionRow(
          title = stringResource(R.string.home_popular),
          items = state.popular,
          onMediaClick = onMediaClick
        )
      }
    }

    if (state.top10.isNotEmpty()) {
      item(key = "top10") {
        MobileSectionRow(
          title = stringResource(R.string.home_top10),
          items = state.top10,
          onMediaClick = onMediaClick
        )
      }
    }

    if (state.movies.isNotEmpty()) {
      item(key = "movies") {
        MobileSectionRow(
          title = stringResource(R.string.home_movies),
          items = state.movies,
          onMediaClick = onMediaClick,
          onSeeAll = onSeeAllMovies
        )
      }
    }

    if (state.series.isNotEmpty()) {
      item(key = "series") {
        MobileSectionRow(
          title = stringResource(R.string.home_series),
          items = state.series,
          onMediaClick = onMediaClick,
          onSeeAll = onSeeAllSeries
        )
      }
    }

    if (state.forYou.isNotEmpty()) {
      item(key = "foryou") {
        MobileSectionRow(
          title = stringResource(R.string.home_for_you),
          items = state.forYou,
          onMediaClick = onMediaClick
        )
      }
    }
  }
}
