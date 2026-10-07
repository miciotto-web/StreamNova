package com.example.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.ProviderCoverage
import com.example.data.repository.StremioCatalogRepository
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.StreamingProvider
import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class ProviderCatalogFilter(val labelRes: Int) {
  ALL(R.string.filter_all),
  MOVIES(R.string.filter_movies),
  TV_SERIES(R.string.filter_tv_series),
  TOP_10(R.string.filter_top_10),
  ACTION(R.string.filter_action),
  SCI_FI(R.string.filter_sci_fi),
  DRAMA(R.string.filter_drama),
  COMEDY(R.string.filter_comedy),
  ANIMATION(R.string.filter_animation)
}

@Composable
fun ProviderScreen(
  provider: StreamingProvider,
  allMedia: List<MediaItem>,
  onBackClick: () -> Unit,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: StreamNovaViewModel? = null,
  onLoadMore: ((isTv: Boolean) -> Unit)? = null
) {
  BackHandler { onBackClick() }

  val stremioProviderMap by (viewModel?.stremioProviderMedia ?: MutableStateFlow(emptyMap())).collectAsState()
  val stremioItems = stremioProviderMap[provider.id].orEmpty()

  // La fonte dati dipende dalla COVERAGE del provider, non più da un flag binario:
  //   NONE    -> solo TMDB nativo,
  //   FULL    -> solo Stremio,
  //   PARTIAL -> Stremio primario + TMDB come completamento (merge/dedup).
  val stremioStateMap by (viewModel?.stremioProviderStates ?: MutableStateFlow(emptyMap())).collectAsState()
  val coverage = stremioStateMap[provider.id]?.coverage
    ?: remember(provider) { StremioCatalogRepository.providerCoverage(provider) }

  val nativeProviderMedia = remember(allMedia, provider) {
    allMedia.filter { it.provider?.contains(provider.id, ignoreCase = true) == true }
  }

  val providerMedia = remember(stremioItems, nativeProviderMedia, coverage) {
    when (coverage) {
      ProviderCoverage.FULL -> stremioItems
      // Stremio resta davanti, TMDB completa; dedup cross-source obbligatorio.
      ProviderCoverage.PARTIAL ->
        StremioCatalogRepository.deduplicateCrossSource(stremioItems + nativeProviderMedia)
      ProviderCoverage.NONE -> nativeProviderMedia
    }
  }

  LaunchedEffect(provider) {
    viewModel?.ensureProviderLoaded(provider)
  }

  val gridState = rememberLazyGridState()
  val isProviderLoading by (viewModel?.isProviderLoading ?: MutableStateFlow(false)).collectAsState()

  // Crunchyroll: catalogo dominato da serie TV/anime -> filtro iniziale dedicato
  var selectedFilter by rememberSaveable(provider) {
    mutableStateOf(if (provider.tmdbProviderId == 283) ProviderCatalogFilter.TV_SERIES else ProviderCatalogFilter.ALL)
  }

  // Reset scroll in cima quando si cambia scheda/filtro
  LaunchedEffect(selectedFilter) {
    if (providerMedia.isNotEmpty()) {
      gridState.scrollToItem(0)
    }
  }

  // Pre-caricamento pagina iniziale al cambio filtro se necessario:
  // consentito per NONE e per PARTIAL (completamento TMDB), non per FULL.
  LaunchedEffect(provider, selectedFilter, coverage) {
    if (coverage != ProviderCoverage.FULL) {
    when (selectedFilter) {
      ProviderCatalogFilter.TV_SERIES -> {
        viewModel?.loadNextProviderPage(provider.tmdbProviderId, isTv = true)
        onLoadMore?.invoke(true)
      }
      ProviderCatalogFilter.MOVIES -> {
        viewModel?.loadNextProviderPage(provider.tmdbProviderId, isTv = false)
        onLoadMore?.invoke(false)
      }
      else -> {
        viewModel?.loadNextProviderPage(provider.tmdbProviderId, isTv = false)
        viewModel?.loadNextProviderPage(provider.tmdbProviderId, isTv = true)
        onLoadMore?.invoke(false)
        onLoadMore?.invoke(true)
      }
    }
    }
  }

  // Scorrimento Infinito: snapshotFlow rileva quando l'utente si avvicina alla fine dell'elenco
  LaunchedEffect(gridState, selectedFilter, provider) {
    snapshotFlow {
      val layoutInfo = gridState.layoutInfo
      val totalItems = layoutInfo.totalItemsCount
      val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      totalItems to lastVisibleItemIndex
    }.distinctUntilChanged()
     .collect { (totalItems, lastVisibleItemIndex) ->
       if (totalItems > 0 && lastVisibleItemIndex >= totalItems - 6) {
         when (selectedFilter) {
           ProviderCatalogFilter.TV_SERIES -> {
             viewModel?.loadNextProviderPage(provider.tmdbProviderId, isTv = true)
             onLoadMore?.invoke(true)
           }
           ProviderCatalogFilter.MOVIES -> {
             viewModel?.loadNextProviderPage(provider.tmdbProviderId, isTv = false)
             onLoadMore?.invoke(false)
           }
           else -> {
             viewModel?.loadNextProviderPage(provider.tmdbProviderId, isTv = false)
             viewModel?.loadNextProviderPage(provider.tmdbProviderId, isTv = true)
             onLoadMore?.invoke(false)
             onLoadMore?.invoke(true)
           }
         }
       }
     }
  }

  val filters = remember { ProviderCatalogFilter.values().toList() }

  val filteredItems = remember(providerMedia, selectedFilter) {
    when (selectedFilter) {
      ProviderCatalogFilter.ALL -> providerMedia
      ProviderCatalogFilter.MOVIES -> providerMedia.filter { it.type == MediaType.FILM }
      ProviderCatalogFilter.TV_SERIES -> providerMedia.filter { it.type == MediaType.SERIE_TV }
      ProviderCatalogFilter.TOP_10 -> providerMedia.sortedByDescending { it.rating }.take(10)
      ProviderCatalogFilter.ACTION -> providerMedia.filter { item ->
        item.genres.any { g ->
          g.contains("Azione", ignoreCase = true) ||
          g.contains("Action", ignoreCase = true) ||
          g.contains("Avventura", ignoreCase = true) ||
          g.contains("Adventure", ignoreCase = true)
        }
      }
      ProviderCatalogFilter.SCI_FI -> providerMedia.filter { item ->
        item.genres.any { g ->
          g.contains("Fantascienza", ignoreCase = true) ||
          g.contains("Sci-Fi", ignoreCase = true) ||
          g.contains("Sci Fi", ignoreCase = true) ||
          g.contains("Fantasy", ignoreCase = true)
        }
      }
      ProviderCatalogFilter.DRAMA -> providerMedia.filter { item ->
        item.genres.any { g ->
          g.contains("Dramma", ignoreCase = true) ||
          g.contains("Drama", ignoreCase = true)
        }
      }
      ProviderCatalogFilter.COMEDY -> providerMedia.filter { item ->
        item.genres.any { g ->
          g.contains("Commedia", ignoreCase = true) ||
          g.contains("Comedy", ignoreCase = true)
        }
      }
      ProviderCatalogFilter.ANIMATION -> providerMedia.filter { item ->
        item.type == MediaType.FILM &&
          item.genres.any { g ->
            g.contains("Animazione", ignoreCase = true) ||
            g.contains("Animation", ignoreCase = true)
          }
      }
    }
  }

  // Log di debug: conteggio dei poster effettivamente renderizzati
  LaunchedEffect(filteredItems, selectedFilter) {
    Log.d(
      "PROVIDER_CATALOG",
      "provider=${provider.name} filter=${selectedFilter.name} -> ${filteredItems.size} poster in griglia"
    )
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    // Header con gradiente personalizzato del Provider
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              provider.primaryColor.copy(alpha = 0.4f),
              provider.secondaryColor.copy(alpha = 0.85f),
              NovaBackground
            )
          )
        )
        .padding(start = 32.dp, top = 26.dp, end = 32.dp, bottom = 12.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        if (provider.logoResId != null) {
          Image(
            painter = painterResource(id = provider.logoResId),
            contentDescription = provider.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .size(44.dp)
              .clip(RoundedCornerShape(10.dp))
              .border(1.dp, provider.accentColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
          )
        } else if (!provider.logoUrl.isNullOrBlank()) {
          AsyncImage(
            model = provider.logoUrl,
            contentDescription = provider.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .size(44.dp)
              .clip(RoundedCornerShape(10.dp))
              .border(1.dp, provider.accentColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
          )
        }

        Text(
          text = provider.name.uppercase(),
          color = Color.White,
          fontSize = 28.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 1.sp
        )
      }
    }

    // Filter Chips Pills con forma a pillola identica
    LazyRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 32.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      items(filters) { filter ->
        val isSelected = selectedFilter == filter
        TvFocusableBox(
          shape = RoundedCornerShape(50),
          focusedScale = 1.05f,
          onClick = { selectedFilter = filter }
        ) { isFocused ->
          Box(
            modifier = Modifier
              .background(
                when {
                  isFocused -> provider.accentColor
                  isSelected -> provider.primaryColor.copy(alpha = 0.4f)
                  else -> NovaSurfaceVariant
                },
                RoundedCornerShape(50)
              )
              .border(
                1.dp,
                if (isSelected || isFocused) provider.accentColor else Color(0xFF1E293B),
                RoundedCornerShape(50)
              )
              .padding(horizontal = 16.dp, vertical = 8.dp)
          ) {
            Text(
              text = stringResource(filter.labelRes),
              color = if (isFocused) Color.Black else if (isSelected) provider.accentColor else NovaTextSecondary,
              fontSize = 13.sp,
              fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Griglia dei contenuti dedicati
    if (filteredItems.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(32.dp),
        contentAlignment = Alignment.Center
      ) {
        if (isProviderLoading) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(28.dp),
              color = provider.accentColor,
              strokeWidth = 3.dp
            )
            Text(
              text = stringResource(R.string.provider_loading_catalog, provider.name),
              color = Color.White.copy(alpha = 0.85f),
              fontSize = 15.sp,
              fontWeight = FontWeight.Medium
            )
          }
        } else {
          Text(
            text = if (providerMedia.isEmpty()) {
              stringResource(R.string.provider_empty_catalog, provider.name)
            } else {
              stringResource(R.string.provider_empty_filter, stringResource(selectedFilter.labelRes))
            },
            color = NovaTextMuted,
            fontSize = 16.sp
          )
        }
      }
    } else {
      TvFocusBringIntoView {
        LazyVerticalGrid(
          state = gridState,
          columns = GridCells.Adaptive(minSize = 150.dp),
          contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 48.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(filteredItems, key = { it.id }) { item ->
            Box(
              modifier = Modifier.fillMaxWidth(),
              contentAlignment = Alignment.TopCenter
            ) {
              PosterMediaCard(
                media = item,
                onClick = { onMediaClick(item) }
              )
            }
          }

          if (isProviderLoading) {
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
                    .border(1.dp, provider.accentColor.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = provider.accentColor,
                    strokeWidth = 2.dp
                  )
                  Text(
                    text = stringResource(R.string.provider_loading_more, provider.name),
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

/**
 * Alias per compatibilità ProviderCatalogScreen
 */
@Composable
fun ProviderCatalogScreen(
  provider: StreamingProvider,
  allMedia: List<MediaItem>,
  onBackClick: () -> Unit,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: StreamNovaViewModel? = null,
  onLoadMore: ((isTv: Boolean) -> Unit)? = null
) {
  ProviderScreen(
    provider = provider,
    allMedia = allMedia,
    onBackClick = onBackClick,
    onMediaClick = onMediaClick,
    modifier = modifier,
    viewModel = viewModel,
    onLoadMore = onLoadMore
  )
}
