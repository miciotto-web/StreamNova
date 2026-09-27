package com.example.ui.screens

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.MediaRepository
import com.example.ui.components.ContinueWatchingCard
import com.example.ui.components.HeroBanner
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.ProviderConstants
import com.example.ui.components.ProviderHubCard
import com.example.ui.components.StandardMediaCard
import com.example.ui.components.StreamingProvider
import com.example.ui.components.Top10RankedMediaCard
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.SidebarSection
import com.example.ui.viewmodel.StreamNovaViewModel

import com.example.ui.components.TvFocusBringIntoViewSpec

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
  viewModel: StreamNovaViewModel,
  onProviderClick: (StreamingProvider) -> Unit = {},
  onMediaClick: ((MediaItem) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val currentSection by viewModel.currentSection.collectAsState()
  val allMedia by viewModel.allMedia.collectAsState()
  val handleMediaClick: (MediaItem) -> Unit = onMediaClick ?: { viewModel.openDetail(it) }

  when (currentSection) {
    SidebarSection.HOME -> {
      HomeMainBrowsingContent(
        allMedia = allMedia,
        onMediaClick = handleMediaClick,
        onPlayClick = { viewModel.openPlayer(it) },
        onProviderClick = onProviderClick,
        modifier = modifier
      )
    }
    SidebarSection.FILM -> {
      CategoryBrowsingScreen(
        title = "Tutti i Film in 4K HDR",
        subtitle = "Catalogo cinematografico completo da TMDB con classificazione 4K HDR",
        items = allMedia.filter { it.type == MediaType.FILM },
        onMediaClick = handleMediaClick,
        modifier = modifier
      )
    }
    SidebarSection.SERIE_TV -> {
      CategoryBrowsingScreen(
        title = "Serie TV & Stagioni Complete",
        subtitle = "Produzioni pluripremiate con audio immersivo Dolby Atmos da TMDB",
        items = allMedia.filter { it.type == MediaType.SERIE_TV },
        onMediaClick = handleMediaClick,
        modifier = modifier
      )
    }
    SidebarSection.I_MIEI_CONTENUTI -> {
      FavoritesScreen(
        allMedia = allMedia,
        onMediaClick = handleMediaClick,
        modifier = modifier
      )
    }
    SidebarSection.CERCA -> {
      SearchScreen(
        viewModel = viewModel,
        onMediaClick = handleMediaClick,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeMainBrowsingContent(
  allMedia: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  onPlayClick: (MediaItem) -> Unit,
  onProviderClick: (StreamingProvider) -> Unit = {},
  modifier: Modifier = Modifier
) {
  // Liste dinamiche dai dati reali TMDB sincronizzati nel repository
  val continueWatching = remember(allMedia) { allMedia.filter { it.currentProgressMs > 0 } }
  val top10Movies = remember(allMedia) { MediaRepository.getTop10Movies() }
  val top10Series = remember(allMedia) { MediaRepository.getTop10Series() }
  val trendingMovies = remember(allMedia) { MediaRepository.getTrendingMovies() }
  val trendingSeries = remember(allMedia) { MediaRepository.getTrendingSeries() }
  val forYouMovies = remember(allMedia) { MediaRepository.getForYouMovies() }
  val forYouSeries = remember(allMedia) { MediaRepository.getForYouSeries() }
  val popularMovies = remember(allMedia) { MediaRepository.getPopularMovies() }
  val popularSeries = remember(allMedia) { MediaRepository.getPopularSeries() }

  // Hero Banner Dinamico: selezione di massimo 9 titoli alternati tra Film e Serie TV TMDB
  val dynamicHeroItems = remember(allMedia) {
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

  val listState = rememberLazyListState()

  // D-pad: quando il focus torna dalla prima riga dei contenuti verso la Hero,
  // lo scroll automatico della LazyColumn (bring-into-view) lascia la Hero
  // parzialmente fuori viewport. Manteniamo esplicitamente la lista a posizione 0
  // finché il focus rimane all'interno dell'item della Hero.
  var heroHasFocus by remember { mutableStateOf(false) }

  LaunchedEffect(heroHasFocus) {
    if (!heroHasFocus) return@LaunchedEffect
    while (heroHasFocus) {
      if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
        listState.scrollToItem(0)
      }
      withFrameNanos { }
    }
  }

  // Su TV Compose usa di default un bring-into-view "pivot" che riposiziona
  // l'elemento al 30% del contenitore: a ogni cambio di focus LEFT/RIGHT tra card
  // della stessa riga provoca un micro-scroll verticale della LazyColumn.
  // Applichiamo uno spec che non scrolla quando l'elemento è già visibile.
  CompositionLocalProvider(LocalBringIntoViewSpec provides TvFocusBringIntoViewSpec) {
  LazyColumn(
    state = listState,
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground),
    contentPadding = PaddingValues(bottom = 60.dp)
  ) {
    // 1. Hero / Banner Principale Cinematografico Dinamico (max 9 titoli)
    if (dynamicHeroItems.isNotEmpty()) {
      item(key = "hero_banner") {
        Column(
          modifier = Modifier.onFocusChanged { heroHasFocus = it.hasFocus }
        ) {
          HeroBanner(
            items = dynamicHeroItems,
            onPlayClick = { onPlayClick(it) },
            onInfoClick = { onMediaClick(it) }
          )
          Spacer(modifier = Modifier.height(16.dp))
        }
      }
    }

    // 2. Hub Streaming Providers (Netflix, HBO, Disney+, Prime Video)
    item(key = "section_providers_hub") {
      CarouselHeader(
        title = "Piattaforme di Streaming",
        badge = "Seleziona Provider"
      )
      LazyRow(
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        items(ProviderConstants.ALL, key = { it.id }) { provider ->
          ProviderHubCard(
            provider = provider,
            isSelected = false,
            onClick = {
              onProviderClick(provider)
            }
          )
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }

    // 3. "Continua a guardare" (se ci sono titoli iniziati)
    if (continueWatching.isNotEmpty()) {
      item(key = "section_continue_watching") {
        CarouselHeader(
          title = "Continua a guardare",
          badge = "${continueWatching.size} titoli in corso"
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(continueWatching, key = { "cw_${it.id}" }) { item ->
            ContinueWatchingCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 4. TOP 10 FILM (Card con numero gigante stilizzato)
    if (top10Movies.isNotEmpty()) {
      item(key = "section_top_10_film") {
        CarouselHeader(
          title = "Top 10 Film di Oggi",
          badge = "Classifica TMDB Italia"
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(top10Movies, key = { _, item -> "top10_film_${item.id}" }) { index, item ->
            Top10RankedMediaCard(
              rank = index + 1,
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 5. TOP 10 SERIE (Card con numero gigante stilizzato)
    if (top10Series.isNotEmpty()) {
      item(key = "section_top_10_serie") {
        CarouselHeader(
          title = "Top 10 Serie TV di Oggi",
          badge = "Più Viste su TMDB"
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(top10Series, key = { _, item -> "top10_serie_${item.id}" }) { index, item ->
            Top10RankedMediaCard(
              rank = index + 1,
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 6. TRENDING FILM
    if (trendingMovies.isNotEmpty()) {
      item(key = "section_trending_film") {
        CarouselHeader(
          title = "Trending Film",
          badge = "In Tendenza questa settimana"
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(trendingMovies, key = { "trend_m_${it.id}" }) { item ->
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 7. TRENDING SERIE
    if (trendingSeries.isNotEmpty()) {
      item(key = "section_trending_serie") {
        CarouselHeader(
          title = "Trending Serie TV",
          badge = "Fenomeni del momento"
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(trendingSeries, key = { "trend_s_${it.id}" }) { item ->
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 8. FOR YOU FILM (Consigliati per te - Film)
    if (forYouMovies.isNotEmpty()) {
      item(key = "section_for_you_film") {
        CarouselHeader(
          title = "For You: Film scelti per te",
          badge = "In base alle tue preferenze"
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(forYouMovies, key = { "foryou_m_${it.id}" }) { item ->
            StandardMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 9. FOR YOU SERIE (Consigliate per te - Serie TV)
    if (forYouSeries.isNotEmpty()) {
      item(key = "section_for_you_serie") {
        CarouselHeader(
          title = "For You: Serie TV da non perdere",
          badge = "Suggerite per te"
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(forYouSeries, key = { "foryou_s_${it.id}" }) { item ->
            StandardMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 12. POPOLARI FILM
    if (popularMovies.isNotEmpty()) {
      item(key = "section_popolari_film") {
        CarouselHeader(
          title = "Popolari Film",
          badge = "I più visti del cinema"
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(popularMovies, key = { "pop_m_${it.id}" }) { item ->
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 15. POPOLARI SERIE
    if (popularSeries.isNotEmpty()) {
      item(key = "section_popolari_serie") {
        CarouselHeader(
          title = "Popolari Serie",
          badge = "Le serie più discusse"
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(popularSeries, key = { "pop_s_${it.id}" }) { item ->
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
      }
    }
  }
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
