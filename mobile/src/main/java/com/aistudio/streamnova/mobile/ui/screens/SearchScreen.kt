package com.aistudio.streamnova.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MOBILE_SEARCH_CATEGORIES
import com.aistudio.streamnova.mobile.ui.MobileCatalogViewModel
import com.aistudio.streamnova.mobile.ui.MobileSearchCategory
import com.aistudio.streamnova.mobile.ui.MobileSearchCategorySource
import com.aistudio.streamnova.mobile.ui.components.MobileEmptyState
import com.aistudio.streamnova.mobile.ui.components.MobilePosterCard
import com.aistudio.streamnova.mobile.ui.components.MobileTopBar
import com.aistudio.streamnova.mobile.ui.filterMobileSearchCategoryItems
import com.example.data.model.MediaItem
import com.example.data.model.SearchCategoryFilter
import com.example.data.model.SearchTypeFilter
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

@Composable
fun SearchScreen(
  viewModel: MobileCatalogViewModel,
  onMediaClick: (MediaItem) -> Unit
) {
  val query by viewModel.query.collectAsStateWithLifecycle()
  val searchType by viewModel.searchType.collectAsStateWithLifecycle()
  val results by viewModel.searchResults.collectAsStateWithLifecycle()
  val selectedCategoryId by viewModel.selectedSearchCategoryId.collectAsStateWithLifecycle()
  val categoryItems by viewModel.categoryItems.collectAsStateWithLifecycle()
  val isCategoryLoading by viewModel.isCategoryLoading.collectAsStateWithLifecycle()
  val categoryState by viewModel.categoryState.collectAsStateWithLifecycle()
  val focusManager = LocalFocusManager.current
  val selectedCategory = MOBILE_SEARCH_CATEGORIES.find { it.id == selectedCategoryId }
  val displayedCategoryItems = remember(categoryItems, searchType) {
    filterMobileSearchCategoryItems(categoryItems, searchType)
  }
  val categoryGridState = rememberLazyGridState()

  BackHandler(enabled = selectedCategory != null) {
    viewModel.clearSearchCategory()
  }

  LaunchedEffect(categoryGridState, selectedCategoryId) {
    if (selectedCategoryId == null) return@LaunchedEffect
    androidx.compose.runtime.snapshotFlow {
      val layout = categoryGridState.layoutInfo
      val total = layout.totalItemsCount
      val last = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
      total to last
    }.collect { (total, last) ->
      if (total > 0 && last >= total - 4) {
        viewModel.loadNextCategoryPage()
      }
    }
  }

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

    SearchTypeFilters(
      selected = searchType,
      onSelected = viewModel::onSearchTypeChange
    )

    Box(modifier = Modifier.weight(1f)) {
      when {
        selectedCategory != null -> {
          SearchCategoryContent(
            category = selectedCategory,
            items = displayedCategoryItems,
            isLoading = isCategoryLoading,
            source = categoryState.source,
            addonLabel = categoryState.addonLabel,
            nativeTmdbUnavailable = categoryState.nativeTmdbUnavailable,
            nativeTmdbEmpty = categoryState.nativeTmdbEmpty,
            gridState = categoryGridState,
            onBack = viewModel::clearSearchCategory,
            onMediaClick = onMediaClick
          )
        }

        query.trim().length >= 2 -> {
          if (results.isEmpty()) {
            MobileEmptyState(
              title = stringResource(R.string.nav_search),
              body = stringResource(R.string.search_no_results)
            )
          } else {
            SearchResultsGrid(items = results, onMediaClick = onMediaClick)
          }
        }

        else -> {
          SearchCategoryPicker(
            onCategoryClick = viewModel::selectSearchCategory
          )
        }
      }
    }
  }
}

/** Filtri principali scorrevoli: nessun chip resta tagliato su schermi stretti. */
@Composable
private fun SearchTypeFilters(
  selected: SearchTypeFilter,
  onSelected: (SearchTypeFilter) -> Unit
) {
  val filters = listOf(
    SearchTypeFilter.ALL to stringResource(R.string.search_all),
    SearchTypeFilter.FILM to stringResource(R.string.nav_movies),
    SearchTypeFilter.SERIE_TV to stringResource(R.string.nav_tv_series)
  )
  LazyRow(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp),
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    items(filters, key = { it.first }) { (type, label) ->
      FilterChip(
        selected = selected == type,
        onClick = { onSelected(type) },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = NovaCyan.copy(alpha = 0.18f),
          selectedLabelColor = NovaCyan,
          labelColor = NovaTextMuted
        )
      )
    }
  }
}

/** Griglia touch-friendly delle sottocategorie TV, nello stesso ordine della TV. */
@Composable
private fun SearchCategoryPicker(onCategoryClick: (String) -> Unit) {
  Column(modifier = Modifier.fillMaxSize()) {
    Text(
      text = stringResource(R.string.search_categories_title),
      color = NovaCyan,
      fontSize = 13.sp,
      fontWeight = FontWeight.Black,
      letterSpacing = 1.sp,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
    Text(
      text = stringResource(R.string.search_categories_subtitle),
      color = NovaTextSecondary,
      fontSize = 13.sp,
      modifier = Modifier.padding(horizontal = 16.dp)
    )
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 148.dp),
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      items(MOBILE_SEARCH_CATEGORIES, key = { it.id }) { category ->
        SearchCategoryChip(category = category, onClick = { onCategoryClick(category.id) })
      }
    }
  }
}

@Composable
private fun SearchCategoryChip(
  category: MobileSearchCategory,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 52.dp)
      .background(
        brush = Brush.horizontalGradient(listOf(category.gradientStart, category.gradientEnd)),
        shape = RoundedCornerShape(26.dp)
      )
      .border(1.dp, category.accentColor.copy(alpha = 0.65f), RoundedCornerShape(26.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = stringResource(category.labelRes).uppercase(),
      color = androidx.compose.ui.graphics.Color.White,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 0.5.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}



/** Contenuto della sottocategoria attiva: badge, origine dei dati e griglia. */
@Composable
private fun SearchCategoryContent(
  category: MobileSearchCategory,
  items: List<MediaItem>,
  isLoading: Boolean,
  source: MobileSearchCategorySource?,
  addonLabel: String?,
  nativeTmdbUnavailable: Boolean,
  nativeTmdbEmpty: Boolean,
  gridState: LazyGridState,
  onBack: () -> Unit,
  onMediaClick: (MediaItem) -> Unit
) {
  val isNativeTmdb = category.contentFilter == SearchCategoryFilter.TMDB_TV_GENRE
  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 4.dp, end = 16.dp, top = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = onBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = stringResource(R.string.search_back_to_categories),
          tint = NovaTextPrimary
        )
      }
      Box(
        modifier = Modifier
          .background(
            brush = Brush.horizontalGradient(listOf(category.gradientStart, category.gradientEnd)),
            shape = RoundedCornerShape(50)
          )
          .border(1.5.dp, category.accentColor, RoundedCornerShape(50))
          .padding(horizontal = 14.dp, vertical = 6.dp)
      ) {
        Text(
          text = stringResource(category.labelRes).uppercase(),
          color = androidx.compose.ui.graphics.Color.White,
          fontSize = 12.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 0.5.sp,
          maxLines = 1
        )
      }
      if (isLoading) {
        CircularProgressIndicator(
          color = NovaCyan,
          modifier = Modifier
            .padding(start = 10.dp)
            .size(16.dp),
          strokeWidth = 2.dp
        )
      }
    }

    Text(
      text = when {
        isLoading -> if (isNativeTmdb) {
          stringResource(R.string.search_category_loading_tmdb)
        } else {
          stringResource(R.string.search_category_loading)
        }
        source == MobileSearchCategorySource.TMDB_FALLBACK -> stringResource(
          R.string.search_category_tmdb_source,
          stringResource(category.labelRes)
        )
        // Categoria nativa TMDB ("Talk"): la fonte è TMDB, non un fallback dovuto
        // alla mancanza di un catalogo addon. Errore e vuoto restano dichiarati.
        source == MobileSearchCategorySource.TMDB -> when {
          nativeTmdbUnavailable -> stringResource(R.string.search_category_tmdb_unavailable)
          nativeTmdbEmpty -> stringResource(
            R.string.search_category_native_tmdb_no_results,
            stringResource(category.labelRes)
          )
          else -> stringResource(
            R.string.search_category_tmdb_source,
            stringResource(category.labelRes)
          )
        }
        !addonLabel.isNullOrBlank() -> stringResource(R.string.search_category_addon_source, addonLabel)
        else -> ""
      },
      color = NovaTextSecondary,
      fontSize = 12.sp,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )

    if (!isLoading && items.isEmpty()) {
      MobileEmptyState(
        title = stringResource(R.string.nav_search),
        body = stringResource(R.string.search_category_no_content, stringResource(category.labelRes))
      )
    } else {
      LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        items(items, key = { "${category.id}_${it.type}_${it.id}" }) { media ->
          MobilePosterCard(media = media, onClick = { onMediaClick(media) })
        }
      }
    }
  }
}

/** Griglia dei risultati della ricerca testuale. */
@Composable
private fun SearchResultsGrid(
  items: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit
) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 112.dp),
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    items(items, key = { it.id }) { media ->
      MobilePosterCard(media = media, onClick = { onMediaClick(media) })
    }
  }
}
