package com.aistudio.streamnova.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MobileCatalogViewModel
import com.aistudio.streamnova.mobile.ui.components.MobileEmptyState
import com.aistudio.streamnova.mobile.ui.components.MobilePosterCard
import com.aistudio.streamnova.mobile.ui.components.MobileTopBar
import com.example.data.model.MediaItem
import com.example.data.model.SearchTypeFilter
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary

@Composable
fun SearchScreen(onMediaClick: (MediaItem) -> Unit) {
  val viewModel: MobileCatalogViewModel = viewModel()
  val query by viewModel.query.collectAsStateWithLifecycle()
  val searchType by viewModel.searchType.collectAsStateWithLifecycle()
  val results by viewModel.searchResults.collectAsStateWithLifecycle()
  val focusManager = LocalFocusManager.current

  Column(modifier = Modifier.fillMaxSize()) {
    MobileTopBar(title = stringResource(R.string.nav_search), onSettings = null)

    OutlinedTextField(
      value = query,
      onValueChange = viewModel::onQueryChange,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
      placeholder = { Text(stringResource(R.string.search_hint), color = NovaTextMuted) },
      leadingIcon = {
        Icon(Icons.Default.Search, contentDescription = null, tint = NovaTextMuted)
      },
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NovaCyan,
        unfocusedBorderColor = NovaSurfaceVariant,
        focusedTextColor = NovaTextPrimary,
        unfocusedTextColor = NovaTextPrimary,
        cursorColor = NovaCyan
      )
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      listOf(
        SearchTypeFilter.ALL to stringResource(R.string.search_all),
        SearchTypeFilter.FILM to stringResource(R.string.nav_movies),
        SearchTypeFilter.SERIE_TV to stringResource(R.string.nav_series)
      ).forEach { (type, label) ->
        FilterChip(
          selected = searchType == type,
          onClick = { viewModel.onSearchTypeChange(type) },
          label = { Text(label) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = NovaCyan.copy(alpha = 0.18f),
            selectedLabelColor = NovaCyan,
            labelColor = NovaTextMuted
          )
        )
      }
    }

    when {
      query.trim().length < 2 -> {
        MobileEmptyState(
          title = stringResource(R.string.search_empty_title),
          body = stringResource(R.string.search_empty_body)
        )
      }

      results.isEmpty() -> {
        MobileEmptyState(
          title = stringResource(R.string.nav_search),
          body = stringResource(R.string.search_no_results)
        )
      }

      else -> {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 112.dp),
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(results, key = { it.id }) { media ->
            MobilePosterCard(media = media, onClick = { onMediaClick(media) })
          }
        }
      }
    }
  }
}
