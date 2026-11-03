package com.aistudio.streamnova.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MobileProviderFilter
import com.aistudio.streamnova.mobile.ui.MobileProviderViewModel
import com.aistudio.streamnova.mobile.ui.components.MobileEmptyState
import com.aistudio.streamnova.mobile.ui.components.MobilePosterCard
import com.aistudio.streamnova.mobile.ui.filterMobileProviderItems
import com.example.data.model.MediaItem
import com.example.ui.components.ProviderBranding
import com.example.ui.components.StreamingProvider
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * Schermata Provider mobile: controparte touch della schermata TV 1.0.4.
 *
 * Mostra la prima pagina reale del provider con gli stessi filtri e le stesse
 * regole di composizione delle sorgenti (Stremio/TMDB). In questa fase non è
 * prevista paginazione o infinite scroll.
 */
@Composable
fun MobileProviderScreen(
  provider: StreamingProvider,
  onBack: () -> Unit,
  onMediaClick: (MediaItem) -> Unit,
  viewModel: MobileProviderViewModel = viewModel()
) {
  BackHandler(onBack = onBack)
  val state by viewModel.state.collectAsStateWithLifecycle()
  var selectedFilter by rememberSaveable(provider.id) {
    mutableStateOf(
      if (provider.tmdbProviderId == CRUNCHYROLL_TMDB_ID) {
        MobileProviderFilter.TV_SERIES
      } else {
        MobileProviderFilter.ALL
      }
    )
  }

  LaunchedEffect(provider.id) { viewModel.load(provider) }

  val filteredItems = remember(state.items, selectedFilter) {
    filterMobileProviderItems(state.items, selectedFilter)
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    ProviderHeader(provider = provider, onBack = onBack)

    LazyRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(MobileProviderFilter.values().toList(), key = { it.name }) { filter ->
        ProviderFilterChip(
          provider = provider,
          filter = filter,
          selected = filter == selectedFilter,
          onClick = { selectedFilter = filter }
        )
      }
    }

    Box(modifier = Modifier.weight(1f)) {
      when {
        state.isLoading && state.items.isEmpty() -> ProviderLoading(provider = provider)

        filteredItems.isEmpty() -> {
          val body = when {
            state.error -> stringResource(R.string.catalog_error_body)
            state.items.isEmpty() -> stringResource(R.string.provider_empty_catalog, provider.name)
            else -> stringResource(
              R.string.provider_empty_filter,
              stringResource(selectedFilter.labelRes)
            )
          }
          MobileEmptyState(
            title = provider.name,
            body = body,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { viewModel.retry(provider) }
          )
        }

        else -> LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 112.dp),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(filteredItems, key = { it.id }) { media ->
            MobilePosterCard(
              media = media,
              onClick = { onMediaClick(media) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ProviderHeader(
  provider: StreamingProvider,
  onBack: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        Brush.verticalGradient(
          colors = listOf(
            provider.primaryColor.copy(alpha = 0.40f),
            provider.secondaryColor.copy(alpha = 0.85f),
            NovaBackground
          )
        )
      )
      .padding(start = 4.dp, top = 6.dp, end = 16.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    IconButton(onClick = onBack) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = stringResource(R.string.action_back),
        tint = NovaTextPrimary
      )
    }
    Spacer(modifier = Modifier.width(4.dp))
    val logoRes = ProviderBranding.logoRes(provider.tmdbProviderId)
    if (logoRes != null) {
      Image(
        painter = painterResource(id = logoRes),
        contentDescription = provider.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .height(30.dp)
          .widthIn(max = 150.dp)
      )
    } else if (!provider.logoUrl.isNullOrBlank()) {
      AsyncImage(
        model = provider.logoUrl,
        contentDescription = provider.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .height(30.dp)
          .widthIn(max = 150.dp)
      )
    } else {
      Text(
        text = provider.name.uppercase(),
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
private fun ProviderFilterChip(
  provider: StreamingProvider,
  filter: MobileProviderFilter,
  selected: Boolean,
  onClick: () -> Unit
) {
  val shape = RoundedCornerShape(50)
  Box(
    modifier = Modifier
      .clip(shape)
      .background(
        if (selected) provider.primaryColor.copy(alpha = 0.40f) else NovaSurfaceVariant,
        shape
      )
      .border(
        width = 1.dp,
        color = if (selected) provider.accentColor else Color(0xFF1E293B),
        shape = shape
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 7.dp)
  ) {
    Text(
      text = stringResource(filter.labelRes),
      color = if (selected) provider.accentColor else NovaTextSecondary,
      fontSize = 13.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      maxLines = 1
    )
  }
}

@Composable
private fun ProviderLoading(provider: StreamingProvider) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    CircularProgressIndicator(color = provider.accentColor)
    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = stringResource(R.string.provider_loading_catalog, provider.name),
      color = NovaTextMuted,
      fontSize = 14.sp
    )
  }
}

private const val CRUNCHYROLL_TMDB_ID = 283
