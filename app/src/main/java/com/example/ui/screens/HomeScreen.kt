package com.example.ui.screens

import android.util.Log
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
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.data.repository.StremioCatalogRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.example.ui.components.ContinueWatchingCard
import com.example.ui.components.MediaContextMenu
import com.example.ui.components.HeroBanner
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.ProviderConstants
import com.example.ui.components.ProviderCard
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
  val homeCatalogs by viewModel.homeCatalogs.collectAsState()
  val handleMediaClick: (MediaItem) -> Unit = onMediaClick ?: { viewModel.openDetail(it) }

  when (currentSection) {
    SidebarSection.HOME -> {
      HomeMainBrowsingContent(
        allMedia = allMedia,
        homeCatalogs = homeCatalogs,
        onMediaClick = handleMediaClick,
        onPlayClick = { viewModel.openStreamForMedia(it) },
        onRemoveFromContinueWatching = { viewModel.removeFromContinueWatching(it) },
        onProviderClick = onProviderClick,
        modifier = modifier
      )
    }
    SidebarSection.FILM -> {
      val moviesItems = remember(homeCatalogs, allMedia) {
        if (homeCatalogs.allMovies.isNotEmpty()) {
          homeCatalogs.allMovies
        } else {
          allMedia.filter { it.type == MediaType.FILM }
        }
      }
      CategoryBrowsingScreen(
        title = stringResource(R.string.category_all_movies),
        items = moviesItems,
        onMediaClick = handleMediaClick,
        onLoadMore = { viewModel.loadNextMoviesPage() },
        modifier = modifier
      )
    }
    SidebarSection.SERIE_TV -> {
      val seriesItems = remember(homeCatalogs, allMedia) {
        if (homeCatalogs.allSeries.isNotEmpty()) {
          homeCatalogs.allSeries
        } else {
          allMedia.filter { it.type == MediaType.SERIE_TV }
        }
      }
      CategoryBrowsingScreen(
        title = stringResource(R.string.category_all_tv_series),
        items = seriesItems,
        onMediaClick = handleMediaClick,
        onLoadMore = { viewModel.loadNextTvPage() },
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
    SidebarSection.ADDON -> {
      AddonsScreen(
        viewModel = viewModel,
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
  homeCatalogs: HomeCatalogs? = null,
  onMediaClick: (MediaItem) -> Unit,
  onPlayClick: (MediaItem) -> Unit,
  onRemoveFromContinueWatching: (String) -> Unit,
  onProviderClick: (StreamingProvider) -> Unit = {},
  modifier: Modifier = Modifier
) {
  // Liste dinamiche dai dati reali TMDB sincronizzati nel repository
  val continueWatching = remember(allMedia) {
    allMedia
      .filter { it.currentProgressMs > 0 }
      .sortedByDescending { it.lastWatchedAt ?: 0L }
  }
  var contextMenuMedia by remember { mutableStateOf<MediaItem?>(null) }

  // Regola di priorità Xperience: se i cataloghi Xperience/addon sono attivi per un
  // tipo, le 4 righe TMDB locali di quel tipo vengono escluse dalla Home (per evitare
  // duplicati). Senza Xperience attivo restano la base predefinita.
  val hasXperienceMovies = homeCatalogs?.hasXperienceMovies == true
  val hasXperienceSeries = homeCatalogs?.hasXperienceSeries == true

  // Le righe della Home arrivano ESCLUSIVAMENTE dai cataloghi risolti (Cinemeta di
  // default + addon utente). Nessun fallback ai feed nativi TMDB: se il catalogo di
  // default non è disponibile la riga resta semplicemente vuota.
  // Classifica Top 10: limite rigido a 10 card (difensivo, oltre al troncamento alla fonte).
  val top10Movies = remember(homeCatalogs) {
    if (hasXperienceMovies) emptyList()
    else homeCatalogs?.top10Movies?.take(StremioCatalogRepository.HOME_TOP_10_SIZE) ?: emptyList()
  }
  val top10Series = remember(homeCatalogs) {
    if (hasXperienceSeries) emptyList()
    else homeCatalogs?.top10Series?.take(StremioCatalogRepository.HOME_TOP_10_SIZE) ?: emptyList()
  }
  val trendingMovies = remember(homeCatalogs) { if (hasXperienceMovies) emptyList() else homeCatalogs?.trendingMovies ?: emptyList() }
  val trendingSeries = remember(homeCatalogs) { if (hasXperienceSeries) emptyList() else homeCatalogs?.trendingSeries ?: emptyList() }
  val forYouMovies = remember(homeCatalogs) { if (hasXperienceMovies) emptyList() else homeCatalogs?.forYouMovies ?: emptyList() }
  val forYouSeries = remember(homeCatalogs) { if (hasXperienceSeries) emptyList() else homeCatalogs?.forYouSeries ?: emptyList() }

  val popularMovies = remember(homeCatalogs) { if (hasXperienceMovies) emptyList() else homeCatalogs?.popularMovies ?: emptyList() }
  val popularSeries = remember(homeCatalogs) { if (hasXperienceSeries) emptyList() else homeCatalogs?.popularSeries ?: emptyList() }

  // Hero Banner Dinamico: selezione di massimo 9 titoli alternati tra Film e Serie TV
  val dynamicHeroItems = remember(homeCatalogs, allMedia) {
    if (homeCatalogs != null && homeCatalogs.heroItems.isNotEmpty()) {
      homeCatalogs.heroItems
    } else {
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
        title = stringResource(R.string.home_section_streaming_platforms),
        badge = stringResource(R.string.home_badge_select_provider)
      )
      LazyRow(
        modifier = Modifier
          .fillMaxWidth()
          .height(125.dp),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        items(ProviderConstants.ALL, key = { it.id }) { provider ->
          ProviderCard(
            provider = provider,
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
          title = stringResource(R.string.home_section_continue_watching),
          badge = stringResource(R.string.home_badge_in_progress, continueWatching.size)
        )
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(continueWatching, key = { "cw_${it.id}" }) { item ->
            ContinueWatchingCard(
              media = item,
              onClick = {
                // Il click semplice su "Continua a guardare" NON apre il Dettaglio:
                // avvia direttamente il Player riprendendo dal punto salvato
                // (l'episodio/stagione e la posizione sono ricavati dal MediaItem
                // e dal progresso persistito in Room dentro openStreamForMedia).
                Log.d("HomeScreen", "ContinueWatchingCard onClick (resume playback) called for ${item.title}")
                onPlayClick(item)
              },
              onLongPress = {
                Log.d("HomeScreen", "ContinueWatchingCard onLongPress called for ${item.title}")
                contextMenuMedia = item
              }
            )
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 4. TOP 10 FILM (Card con numero gigante stilizzato) - esclusa se Xperience Film è attivo
    if (!hasXperienceMovies && top10Movies.isNotEmpty()) {
      item(key = "section_top_10_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_top_10_movies),
          badge = stringResource(R.string.home_badge_tmdb_ranking_italy)
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

    // 5. TOP 10 SERIE (Card con numero gigante stilizzato) - esclusa se Xperience Serie è attivo
    if (!hasXperienceSeries && top10Series.isNotEmpty()) {
      item(key = "section_top_10_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_top_10_series),
          badge = stringResource(R.string.home_badge_most_watched_tmdb)
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

    // 6. TRENDING FILM - esclusa se Xperience Film è attivo
    if (!hasXperienceMovies && trendingMovies.isNotEmpty()) {
      item(key = "section_trending_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_trending_movies),
          badge = stringResource(R.string.home_badge_trending_week)
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

    // 7. TRENDING SERIE - esclusa se Xperience Serie è attivo
    if (!hasXperienceSeries && trendingSeries.isNotEmpty()) {
      item(key = "section_trending_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_trending_series),
          badge = stringResource(R.string.home_badge_trending_phenomena)
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

    // 8. FOR YOU FILM (Consigliati per te - Film) - esclusa se Xperience Film è attivo
    if (!hasXperienceMovies && forYouMovies.isNotEmpty()) {
      item(key = "section_for_you_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_for_you_movies),
          badge = stringResource(R.string.home_badge_based_on_preferences)
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

    // 9. FOR YOU SERIE (Consigliate per te - Serie TV) - esclusa se Xperience Serie è attivo
    if (!hasXperienceSeries && forYouSeries.isNotEmpty()) {
      item(key = "section_for_you_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_for_you_series),
          badge = stringResource(R.string.home_badge_suggested_for_you)
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

    // 12. POPOLARI FILM - esclusa se Xperience Film è attivo
    if (!hasXperienceMovies && popularMovies.isNotEmpty()) {
      item(key = "section_popolari_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_popular_movies),
          badge = stringResource(R.string.home_badge_most_watched_cinema)
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

    // 15. POPOLARI SERIE - esclusa se Xperience Serie è attivo
    if (!hasXperienceSeries && popularSeries.isNotEmpty()) {
      item(key = "section_popolari_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_popular_series),
          badge = stringResource(R.string.home_badge_most_discussed_series)
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
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // Sezioni catalogo Stremio (addon): si AGGIUNGONO alle righe TMDB, così la Home
    // mostra l'unione dei cataloghi locali e di quelli degli addon abilitati.
    homeCatalogs?.extraSections?.forEach { extraSec ->
      if (extraSec.items.isNotEmpty()) {
        item(key = "section_${extraSec.id}") {
          CarouselHeader(
            title = extraSec.title,
            badge = extraSec.addonName
          )
          LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            items(extraSec.items, key = { "extra_${extraSec.id}_${it.id}" }) { item ->
              PosterMediaCard(
                media = item,
                onClick = { onMediaClick(item) }
              )
            }
          }
          Spacer(modifier = Modifier.height(20.dp))
        }
      }
    }
  }
  }

  // Context menu per long-press su Continue Watching (posto fuori da LazyColumn per essere in composable scope)
  if (contextMenuMedia != null) {
    val media = contextMenuMedia!!
    MediaContextMenu(
      media = media,
      onPlay = { onPlayClick(media) },
      onDetails = { onMediaClick(media) },
      onRemoveFromContinueWatching = { onRemoveFromContinueWatching(media.id) },
      onDismiss = { contextMenuMedia = null }
    )
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
