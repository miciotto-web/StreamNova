package com.example.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.MediaRepository
import com.example.ui.components.ContinueWatchingCard
import com.example.ui.components.HeroBanner
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.StandardMediaCard
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.SidebarSection
import com.example.ui.viewmodel.StreamNovaViewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.collectAsState

@Composable
fun HomeScreen(
  viewModel: StreamNovaViewModel,
  modifier: Modifier = Modifier
) {
  val currentSection by viewModel.currentSection.collectAsState()
  val allMedia by viewModel.allMedia.collectAsState()

  when (currentSection) {
    SidebarSection.HOME -> {
      HomeMainBrowsingContent(
        allMedia = allMedia,
        onMediaClick = { viewModel.openDetail(it) },
        onPlayClick = { viewModel.openPlayer(it) },
        modifier = modifier
      )
    }
    SidebarSection.FILM -> {
      CategoryBrowsingScreen(
        title = "Tutti i Film in 4K HDR",
        subtitle = "I migliori lungometraggi cinematografici disponibili per te",
        items = allMedia.filter { it.type == MediaType.FILM },
        onMediaClick = { viewModel.openDetail(it) },
        modifier = modifier
      )
    }
    SidebarSection.SERIE_TV -> {
      CategoryBrowsingScreen(
        title = "Serie TV & Stagioni Complete",
        subtitle = "Produzioni pluripremiate con audio immersivo Dolby Atmos",
        items = allMedia.filter { it.type == MediaType.SERIE_TV },
        onMediaClick = { viewModel.openDetail(it) },
        modifier = modifier
      )
    }
    SidebarSection.I_MIEI_CONTENUTI -> {
      MyContentScreen(
        allMedia = allMedia,
        onMediaClick = { viewModel.openDetail(it) },
        modifier = modifier
      )
    }
    SidebarSection.CERCA -> {
      SearchScreen(
        viewModel = viewModel,
        onMediaClick = { viewModel.openDetail(it) },
        modifier = modifier
      )
    }
    SidebarSection.IMPOSTAZIONI -> {
      SettingsScreen(
        modifier = modifier
      )
    }
  }
}

enum class SortOption(val label: String) {
  POPOLARI("Popolari"),
  DATA("Data"),
  VALUTAZIONE("Valutazione")
}

@Composable
fun HomeMainBrowsingContent(
  allMedia: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  onPlayClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  // Separate TMDB items (tmdbId != null) from preloaded fallback (tmdbId == null)
  val tmdbItems = allMedia.filter { it.tmdbId != null }
  val preloadedItems = allMedia.filter { it.tmdbId == null }

  // TMDB-first: use TMDB items when available, otherwise fall back to preloaded
  val displayItems = if (tmdbItems.isNotEmpty()) tmdbItems else preloadedItems

  val featuredHero = displayItems.firstOrNull { it.backdropUrl != null && it.rating > 0 } ?: displayItems.firstOrNull()

  val trendingMovies = displayItems.filter { it.type == MediaType.FILM && it.tmdbId != null }.sortedByDescending { it.rating }
  val trendingSeries = displayItems.filter { it.type == MediaType.SERIE_TV && it.tmdbId != null }.sortedByDescending { it.rating }
  val popularMovies = displayItems.filter { it.type == MediaType.FILM && it.tmdbId != null }.sortedByDescending { it.rating }
  val popularSeries = displayItems.filter { it.type == MediaType.SERIE_TV && it.tmdbId != null }.sortedByDescending { it.rating }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground),
    contentPadding = PaddingValues(
      top = 16.dp,
      bottom = 48.dp,
      start = 48.dp,
      end = 32.dp
    )
  ) {
    // 1. Hero / Banner Principale
    item(key = "hero_banner") {
      if (featuredHero != null) {
        HeroBanner(
          media = featuredHero,
          onPlayClick = { onPlayClick(featuredHero) },
          onInfoClick = { onMediaClick(featuredHero) }
        )
        Spacer(modifier = Modifier.height(24.dp))
      }
    }

    // 2. Carosello: "Trending Film"
    if (trendingMovies.isNotEmpty()) {
      item(key = "section_trending_movies") {
        CarouselSection(
          title = "Trending Film",
          items = trendingMovies,
          onMediaClick = { onMediaClick(it) },
          cardType = CardType.POSTER
        )
        Spacer(modifier = Modifier.height(16.dp))
      }
    }

    // 3. Carosello: "Trending Serie"
    if (trendingSeries.isNotEmpty()) {
      item(key = "section_trending_series") {
        CarouselSection(
          title = "Trending Serie",
          items = trendingSeries,
          onMediaClick = { onMediaClick(it) },
          cardType = CardType.POSTER
        )
        Spacer(modifier = Modifier.height(16.dp))
      }
    }

    // 4. Carosello: "Film più popolari"
    if (popularMovies.isNotEmpty()) {
      item(key = "section_popular_movies") {
        CarouselSection(
          title = "Film più popolari",
          items = popularMovies,
          onMediaClick = { onMediaClick(it) },
          cardType = CardType.POSTER
        )
        Spacer(modifier = Modifier.height(16.dp))
      }
    }

    // 5. Carosello: "Serie più popolari"
    if (popularSeries.isNotEmpty()) {
      item(key = "section_popular_series") {
        CarouselSection(
          title = "Serie più popolari",
          items = popularSeries,
          onMediaClick = { onMediaClick(it) },
          cardType = CardType.POSTER
        )
      }
    }
  }
}

enum class CardType { STANDARD, POSTER }

@Composable
fun CarouselSection(
  title: String,
  items: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  cardType: CardType = CardType.STANDARD
) {
  var sortOption by remember { mutableStateOf(SortOption.POPOLARI) }

  val sortedItems = remember(items, sortOption) {
    when (sortOption) {
      SortOption.POPOLARI -> items.sortedByDescending { it.rating }
      SortOption.DATA -> items.sortedByDescending { it.releaseDate ?: 0 }
      SortOption.VALUTAZIONE -> items.sortedByDescending { it.rating }
    }
  }

  val listState = rememberLazyListState()
  var contentWidth by remember { mutableIntStateOf(0) }
  var itemWidth by remember { mutableIntStateOf(236) }

  Column {
    // Header with sorting chips
    Row(
      modifier = Modifier
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
      }

      // Sorting chips
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        SortOption.values().forEach { option ->
          androidx.compose.material3.FilterChip(
            selected = sortOption == option,
            onClick = { sortOption = option },
            label = {
              Text(
                text = option.label,
                fontSize = 11.sp,
                fontWeight = if (sortOption == option) FontWeight.Bold else FontWeight.Normal
              )
            }
          )
        }
      }
    }

    // Carousel with progress indicator
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .onSizeChanged { contentWidth = it.width }
    ) {
      LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        items(sortedItems, key = { it.id }) { item ->
          if (cardType == CardType.POSTER) {
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          } else {
            StandardMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
      }

      // Carousel progress indicator
      if (contentWidth > 0) {
        val layoutInfo = listState.layoutInfo
        val totalVisibleItems = layoutInfo.visibleItemsInfo.size
        val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
        val visibleItems = sortedItems.size
        
        if (totalVisibleItems > 0) {
          Box(
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(16.dp)
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              repeat(visibleItems.coerceAtMost(10)) { index ->
                val isActive = index == firstVisibleIndex || (index in firstVisibleIndex until firstVisibleIndex + totalVisibleItems)
                Box(
                  modifier = Modifier
                    .width(if (isActive) 8.dp else 4.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isActive) NovaCyanBright else Color(0x33FFFFFF))
                )
              }
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
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

