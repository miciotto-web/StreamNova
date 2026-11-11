package com.aistudio.streamnova.mobile.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.LocalWindowWidthExpanded
import com.aistudio.streamnova.mobile.ui.MobileDetailPolicy
import com.aistudio.streamnova.mobile.ui.MobileDetailUiState
import com.aistudio.streamnova.mobile.ui.MobileDetailViewModel
import com.aistudio.streamnova.mobile.ui.MobileManualSourcePolicy
import com.aistudio.streamnova.mobile.ui.MobileOrientationController
import com.aistudio.streamnova.mobile.ui.MobileTrailerOverlay
import com.aistudio.streamnova.mobile.ui.MobileTrailerState
import com.aistudio.streamnova.mobile.ui.components.MobileCastCarousel
import com.aistudio.streamnova.mobile.ui.components.MobileDetailActionBar
import com.aistudio.streamnova.mobile.ui.components.MobileDetailBadges
import com.aistudio.streamnova.mobile.ui.components.MobileDetailErrorBanner
import com.aistudio.streamnova.mobile.ui.components.MobileDetailSectionHeader
import com.aistudio.streamnova.mobile.ui.components.MobileDetailStatusCard
import com.aistudio.streamnova.mobile.ui.components.MobileDetailTabs
import com.aistudio.streamnova.mobile.ui.components.MobileEpisodeCard
import com.aistudio.streamnova.mobile.ui.components.MobileFullSynopsisDialog
import com.aistudio.streamnova.mobile.ui.components.MobileProductionSection
import com.aistudio.streamnova.mobile.ui.components.MobileProviderBadge
import com.aistudio.streamnova.mobile.ui.components.MobileSeasonChip
import com.aistudio.streamnova.mobile.ui.components.MobileSeasonStatusBar
import com.example.data.model.MediaItem
import com.example.data.prefs.AppSettingsRepository
import com.example.data.repository.EpisodeResumeResolver
import com.example.data.repository.MediaRepository
import com.example.ui.components.ProviderConstants
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

private val DetailHeroMaxWidth = 1000.dp

/**
 * Scheda Dettaglio Mobile allineata alla TV 1.0.4.
 *
 * Contenuti e gerarchia sono quelli della scheda TV: backdrop, logo originale con
 * fallback testuale, tagline, metadati reali, generi, sinossi, barra azioni
 * (Riproduci + Preferiti + Guardato + Trailer + Seleziona fonte in modalità
 * Debrid), cast, tab Episodi/Produzione, stagioni ed episodi TMDB, case di
 * produzione. L'adattamento mobile è solo la disposizione touch/portrait-landscape
 * e la gestione delle safe area, già applicata dalla shell.
 *
 * [onSelectSource] è `null` quando la selezione manuale delle sorgenti non è
 * supportata (modalità HTTP/direct): il pulsante «Seleziona fonte» non viene
 * composto. Quando è presente riceve lo STESSO episodio del pulsante Riproduci,
 * così la ricerca e il contesto SxE coincidono con quelli della TV.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
  mediaId: String,
  onBack: () -> Unit,
  onPlay: (media: MediaItem, season: Int?, episode: Int?) -> Unit,
  orientationController: MobileOrientationController,
  onSelectSource: ((media: MediaItem, season: Int?, episode: Int?) -> Unit)? = null
) {
  BackHandler(onBack = onBack)
  val viewModel: MobileDetailViewModel = viewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  // Stessa preferenza della Home: la sfocatura anti-spoiler si applica anche alle
  // miniature degli episodi non ancora visti nella scheda di una serie.
  val continueWatchingPreferences by
    AppSettingsRepository.continueWatchingPreferences.collectAsStateWithLifecycle()
  val expanded = LocalWindowWidthExpanded.current
  val configuration = LocalConfiguration.current
  val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

  LaunchedEffect(mediaId) { viewModel.load(mediaId) }

  // Trailer: su telefono abilita il landscape finché l'overlay è aperto e
  // riapplica il portrait alla chiusura. Il player principale non è coinvolto:
  // usa il proprio ingresso nel controller (Fase 8.1).
  val trailerActive = state.trailer !is MobileTrailerState.Idle
  LaunchedEffect(orientationController, trailerActive) {
    if (trailerActive) {
      orientationController.setTrailerVisible(visible = true)
    } else {
      orientationController.clearTrailer()
    }
  }
  DisposableEffect(orientationController) {
    onDispose { orientationController.clearTrailer() }
  }

  val media = state.media
  if (media == null) {
    if (state.notFound) {
      MobileDetailFatalState(
        onBack = onBack,
        onRetry = viewModel::retryLoad
      )
    } else {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = NovaCyan)
      }
    }
    return
  }

  val isSeries = state.isSeries
  val tabs = if (isSeries) {
    listOf(
      stringResource(R.string.detail_tab_episodes),
      stringResource(R.string.detail_tab_production)
    )
  } else {
    listOf(stringResource(R.string.detail_tab_production))
  }
  var selectedTab by rememberSaveable(media.id) { mutableIntStateOf(0) }
  var synopsisDialogOpen by rememberSaveable(media.id) { mutableStateOf(false) }

  val resumeEpisode = remember(media, state.episodes, state.selectedSeason, isSeries) {
    if (!isSeries || !MobileDetailPolicy.hasResolvableSeriesTarget(state)) {
      null
    } else {
      EpisodeResumeResolver.resolveWithLookup(
        media = media,
        extraEpisodes = state.episodes,
        progressOf = { season, episode ->
          MediaRepository.getEpisodeProgress(media.id, season, episode)
        },
        durationOf = { season, episode ->
          MediaRepository.getEpisodeDurationMs(media.id, season, episode)
        }
      )
    }
  }

  val playLabel = if (isSeries) {
    val episode = resumeEpisode
    when {
      episode == null -> stringResource(R.string.action_play)
      episode.currentProgressMs > 0L -> stringResource(
        R.string.detail_resume_episode_format,
        episode.seasonNumber,
        episode.episodeNumber
      )
      else -> stringResource(
        R.string.detail_play_episode_format,
        episode.seasonNumber,
        episode.episodeNumber
      )
    }
  } else if (media.currentProgressMs > 0L) {
    stringResource(R.string.home_continue)
  } else {
    stringResource(R.string.action_play)
  }

  /**
   * Bersaglio condiviso da Riproduci e «Seleziona fonte»: stessa puntata per
   * le due azioni (come sulla TV), così la ricerca manuale parte esattamente
   * sull'episodio mostrato dalla scheda.
   */
  val playTarget = remember(media, resumeEpisode) {
    MobileManualSourcePolicy.searchTarget(media, resumeEpisode)
  }

  val metadata = state.metadata
  val synopsisText = state.details?.overview?.takeIf { it.isNotBlank() }
    ?: media.synopsis.takeIf { it.isNotBlank() }
  val backdropUrl = state.details?.backdropUrl?.takeIf { it.isNotBlank() }
    ?: media.backdropUrl?.takeIf { it.isNotBlank() }
  val backdropRes = media.backdropRes
  val provider = ProviderConstants.ALL.firstOrNull {
    it.id == media.provider?.lowercase()
  }

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = DetailHeroMaxWidth),
      contentPadding = PaddingValues(bottom = 36.dp)
    ) {
      item(key = "hero") {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(detailHeroHeight(expanded, isLandscape))
        ) {
          when {
            backdropUrl != null -> AsyncImage(
              model = ImageRequest.Builder(LocalContext.current)
                .data(backdropUrl)
                .crossfade(300)
                .build(),
              contentDescription = media.title,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize()
            )
            backdropRes != null -> Image(
              painter = painterResource(id = backdropRes),
              contentDescription = media.title,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize()
            )
            else -> Box(
              modifier = Modifier
                .fillMaxSize()
                .background(
                  Brush.verticalGradient(
                    listOf(Color(0xFF0F172A), NovaBackground)
                  )
                )
            )
          }

          // Vignette TV: contrasto a sinistra e fusione verticale nel background.
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                Brush.horizontalGradient(
                  colors = listOf(
                    Color.Black.copy(alpha = 0.65f),
                    Color.Black.copy(alpha = 0.22f),
                    Color.Transparent
                  )
                )
              )
              .background(
                Brush.verticalGradient(
                  colors = listOf(
                    Color.Transparent,
                    Color.Transparent,
                    NovaBackground.copy(alpha = 0.75f),
                    NovaBackground
                  )
                )
              )
          )

          IconButton(
            onClick = onBack,
            modifier = Modifier
              .align(Alignment.TopStart)
              .padding(10.dp)
              .size(42.dp)
              .background(Color.Black.copy(alpha = 0.42f), CircleShape)
              .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
          ) {
            Icon(
              imageVector = Icons.Default.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
              tint = NovaTextPrimary
            )
          }
        }
      }

      if (state.isLoadingDetails) {
        item(key = "details-loading") {
          Row(
            modifier = Modifier
              .padding(start = 20.dp, top = 12.dp)
              .background(
                NovaSurfaceVariant.copy(alpha = 0.8f),
                RoundedCornerShape(20.dp)
              )
              .border(1.dp, NovaCyan.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
              .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              color = NovaCyanBright,
              strokeWidth = 2.dp
            )
            Text(
              text = stringResource(R.string.detail_loading_tmdb),
              color = NovaCyanBright,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium
            )
          }
        }
      }

      state.detailsError?.let { detailsErrorRes ->
        item(key = "details-error") {
          MobileDetailErrorBanner(
            message = stringResource(detailsErrorRes),
            onRetry = viewModel::retryDetails,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
          )
        }
      }

      item(key = "header") {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
          if (provider != null) {
            MobileProviderBadge(provider = provider)
            Spacer(modifier = Modifier.height(10.dp))
          }

          DetailLogoOrTitle(
            media = media,
            logoUrl = state.logoUrl,
            expanded = expanded
          )

          metadata.tagline?.let { tagline ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "\"$tagline\"",
              color = NovaCyanBright.copy(alpha = 0.85f),
              fontSize = 13.sp
            )
          }

          Spacer(modifier = Modifier.height(10.dp))
          MobileDetailBadges(
            year = metadata.year,
            durationMinutes = metadata.durationMinutes,
            seasonsCount = metadata.seasonsCount,
            episodesCount = metadata.episodesCount,
            ageRating = metadata.ageRating,
            rating = metadata.rating
          )

          if (metadata.genres.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              metadata.genres.take(5).forEach { genre ->
                GenreChip(genre)
              }
            }
          }

          if (synopsisText != null) {
            Spacer(modifier = Modifier.height(14.dp))
            var hasOverflow by remember(media.id, synopsisText) { mutableStateOf(false) }
            Text(
              text = synopsisText,
              color = NovaTextSecondary,
              fontSize = 14.sp,
              lineHeight = 21.sp,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
              onTextLayout = { hasOverflow = it.hasVisualOverflow }
            )
            if (hasOverflow) {
              TextButton(
                onClick = { synopsisDialogOpen = true },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
              ) {
                Text(
                  text = stringResource(R.string.detail_read_more),
                  color = NovaCyanBright,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(20.dp))
          MobileDetailActionBar(
            playLabel = playLabel,
            onPlay = {
              onPlay(media, playTarget.season, playTarget.episode)
            },
            isFavorite = state.isFavorite,
            onToggleFavorite = viewModel::toggleFavorite,
            isWatched = state.isWatched,
            onToggleWatched = viewModel::toggleWatched,
            onPlayTrailer = viewModel::playTrailer,
            // «Seleziona fonte»: presente solo in modalità Debrid (callback null
            // in HTTP/direct). La puntata è quella del pulsante Riproduci.
            onSelectSource = onSelectSource?.let { callback ->
              { callback(media, playTarget.season, playTarget.episode) }
            }
          )
          Spacer(modifier = Modifier.height(26.dp))
        }
      }

      if (state.cast.isNotEmpty()) {
        item(key = "cast") {
          MobileCastCarousel(cast = state.cast)
          Spacer(modifier = Modifier.height(22.dp))
        }
      }

      // Scheda «Dettagli» (parità con la TV 1.0.5): stato, anno, durata,
      // certificazione e paese d'origine — solo righe con dato reale TMDB.
      item(key = "movie-details") {
        MobileMovieDetailsSection(
          details = state.details,
          media = media,
          isTvSeries = isSeries,
          modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(18.dp))
      }

      item(key = "tabs") {
        MobileDetailTabs(
          labels = tabs,
          selectedIndex = selectedTab.coerceIn(0, tabs.lastIndex),
          onSelect = { selectedTab = it },
          modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
      }

      val showEpisodes = isSeries && (selectedTab.coerceIn(0, tabs.lastIndex) == 0)
      if (showEpisodes) {
        val season = state.selectedSeason
        if (state.seasons.isEmpty()) {
          item(key = "no-seasons") {
            MobileDetailStatusCard(
              text = stringResource(R.string.detail_no_seasons),
              showSpinner = state.isLoadingDetails,
              onRetry = viewModel::retryDetails,
              modifier = Modifier.padding(horizontal = 20.dp)
            )
          }
        } else if (season != null) {
          item(key = "season-selector") {
            Column(modifier = Modifier.fillMaxWidth()) {
              MobileDetailSectionHeader(
                text = stringResource(R.string.detail_season_selection),
                icon = Icons.Default.Tv,
                modifier = Modifier.padding(horizontal = 20.dp)
              )
              Spacer(modifier = Modifier.height(10.dp))
              LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                items(state.seasons, key = { it }) { seasonNumber ->
                  val count = MobileDetailPolicy.seasonEpisodeCount(
                    media = media,
                    details = state.details,
                    season = seasonNumber
                  )
                  MobileSeasonChip(
                    label = if (seasonNumber == 0) {
                      stringResource(R.string.detail_specials)
                    } else {
                      stringResource(R.string.detail_season_format, seasonNumber)
                    },
                    episodeCountLabel = count?.let {
                      stringResource(R.string.detail_episodes_count_badge, it)
                    },
                    isSelected = seasonNumber == season,
                    onClick = { viewModel.selectSeason(seasonNumber) }
                  )
                }
              }
              Spacer(modifier = Modifier.height(12.dp))

              val episodesLoaded = !state.episodesLoading &&
                (state.season != null || state.episodes.isNotEmpty())
              val episodeCount = MobileDetailPolicy.seasonEpisodeCount(
                media = media,
                details = state.details,
                season = season,
                loadedEpisodes = state.episodes,
                loadedSeason = season,
                episodesLoaded = episodesLoaded
              )
              val statusText = if (season == 0) {
                if (episodeCount != null) {
                  stringResource(R.string.detail_specials_episodes_status, episodeCount)
                } else {
                  stringResource(R.string.detail_specials)
                }
              } else if (episodeCount != null) {
                stringResource(R.string.detail_season_episodes_status, season, episodeCount)
              } else {
                stringResource(R.string.detail_season_format, season)
              }
              MobileSeasonStatusBar(
                text = statusText,
                modifier = Modifier.padding(horizontal = 20.dp)
              )

              state.season?.name?.takeIf {
                val normalized = it.trim()
                it.isNotBlank() && season != 0 &&
                  !normalized.equals("Season $season", ignoreCase = true) &&
                  !normalized.equals("Stagione $season", ignoreCase = true)
              }?.let { seasonName ->
                Text(
                  text = seasonName,
                  color = NovaTextPrimary,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
              }
              state.season?.airDate?.takeIf { it.isNotBlank() }?.let { airDate ->
                Text(
                  text = stringResource(R.string.detail_air_date_format, airDate),
                  color = NovaTextMuted,
                  fontSize = 11.sp,
                  modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
              }
              state.season?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                  text = overview,
                  color = NovaTextSecondary,
                  fontSize = 12.sp,
                  lineHeight = 18.sp,
                  maxLines = 3,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
              }
              Spacer(modifier = Modifier.height(8.dp))
            }
          }

          if (state.episodesLoading) {
            if (state.episodes.isEmpty()) {
              item(key = "episodes-loading") {
                MobileDetailStatusCard(
                  text = stringResource(R.string.detail_loading_episodes, season),
                  showSpinner = true,
                  modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
              }
            } else {
              item(key = "episodes-refreshing") {
                LinearProgressIndicator(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(2.dp),
                  color = NovaCyan,
                  trackColor = Color.Transparent
                )
              }
            }
          }

          state.episodesError?.let { episodesErrorRes ->
            item(key = "episodes-error") {
              MobileDetailStatusCard(
                text = stringResource(episodesErrorRes),
                isError = true,
                onRetry = viewModel::retrySeason,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
              )
            }
          }

          if (!state.episodesLoading &&
            state.episodesError == null &&
            state.episodes.isEmpty()
          ) {
            item(key = "episodes-empty") {
              MobileDetailStatusCard(
                text = stringResource(R.string.detail_no_episodes),
                onRetry = viewModel::retrySeason,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
              )
            }
          }

          items(state.episodes, key = { it.id }) { episode ->
            val simklWatched = episode.episodeNumber in state.simklWatchedEpisodes
            MobileEpisodeCard(
              episode = episode,
              isSimklWatched = simklWatched,
              fallbackBackdropUrl = state.details?.backdropUrl ?: media.backdropUrl,
              onClick = { onPlay(media, episode.seasonNumber, episode.episodeNumber) },
              expanded = expanded,
              blurThumbnail = MobileDetailPolicy.shouldBlurEpisodeThumbnail(
                episode = episode,
                blurUnwatched = continueWatchingPreferences.blurUnwatched,
                isSimklWatched = simklWatched
              ),
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp)
            )
          }
        }
      } else {
        item(key = "production") {
          Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            val companies = state.details?.productionCompanies.orEmpty()
            if (state.isLoadingDetails && companies.isEmpty()) {
              MobileDetailStatusCard(
                text = stringResource(R.string.detail_production_loading),
                showSpinner = true
              )
            } else {
              MobileProductionSection(companies = companies)
            }
          }
        }
      }
    }

    MobileTrailerOverlay(
      state = state.trailer,
      onClose = viewModel::closeTrailer
    )

    if (synopsisDialogOpen && synopsisText != null) {
      MobileFullSynopsisDialog(
        title = media.title,
        text = synopsisText,
        onDismiss = { synopsisDialogOpen = false }
      )
    }
  }
}

@Composable
private fun DetailLogoOrTitle(
  media: MediaItem,
  logoUrl: String?,
  expanded: Boolean
) {
  val titleStyle = TextStyle(
    color = NovaTextPrimary,
    fontSize = if (expanded) 36.sp else 28.sp,
    fontWeight = FontWeight.Black,
    letterSpacing = 0.5.sp,
    shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 8f)
  )
  if (logoUrl != null) {
    SubcomposeAsyncImage(
      model = ImageRequest.Builder(LocalContext.current)
        .data(logoUrl)
        .crossfade(true)
        .build(),
      contentDescription = media.title,
      contentScale = ContentScale.Fit,
      alignment = Alignment.CenterStart,
      modifier = Modifier
        .heightIn(min = 52.dp, max = if (expanded) 96.dp else 76.dp)
        .widthIn(max = if (expanded) 480.dp else 320.dp),
      error = {
        Text(text = media.title, style = titleStyle)
      }
    )
  } else {
    Text(text = media.title, style = titleStyle)
  }
}

@Composable
private fun GenreChip(genre: String) {
  Box(
    modifier = Modifier
      .background(NovaSurfaceVariant.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
      .border(1.dp, NovaCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
      .padding(horizontal = 10.dp, vertical = 3.dp)
  ) {
    Text(
      text = genre,
      color = NovaCyanBright,
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium
    )
  }
}

@Composable
private fun MobileDetailFatalState(
  onBack: () -> Unit,
  onRetry: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Text(
      text = stringResource(R.string.detail_not_found),
      color = NovaTextPrimary,
      fontSize = 18.sp,
      fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(
        onClick = onRetry,
        colors = ButtonDefaults.buttonColors(
          containerColor = NovaCyan,
          contentColor = Color.Black
        )
      ) {
        Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Bold)
      }
      TextButton(onClick = onBack) {
        Text(
          text = stringResource(R.string.action_back),
          color = NovaTextSecondary
        )
      }
    }
  }
}

private fun detailHeroHeight(expanded: Boolean, isLandscape: Boolean) = when {
  isLandscape && expanded -> 300.dp
  isLandscape -> 220.dp
  expanded -> 360.dp
  else -> 280.dp
}

/**
 * Scheda «Dettagli» mobile: le stesse informazioni della sezione TV 1.0.5 (stato,
 * anno, durata, certificazione, paese d'origine), presentate in righe compatte
 * senza gestione del fuoco D-pad (interazione touch). Una riga senza dato non
 * viene composta; nessun valore inventato quando TMDB non espone il campo.
 */
@Composable
private fun MobileMovieDetailsSection(
  details: com.example.data.model.ExtendedMediaDetails?,
  media: com.example.data.model.MediaItem,
  isTvSeries: Boolean,
  modifier: Modifier = Modifier
) {
  val status = com.example.data.util.DetailFactsMapper.status(details?.status)
  val releaseYear = com.example.data.util.DetailFactsMapper.releaseYear(
    details?.releaseDate ?: media.releaseDate
  )
  val duration = com.example.data.util.DetailFactsMapper.formatDuration(details?.runtimeMinutes)
  val certification = details?.certification?.takeIf { it.isNotBlank() }
  val originCountry = details?.originCountry?.takeIf { it.isNotBlank() }

  val statusLabel = status?.let {
    stringResource(
      when (it) {
        com.example.data.util.DetailFactsMapper.TitleStatus.RELEASED -> R.string.detail_status_released
        com.example.data.util.DetailFactsMapper.TitleStatus.POST_PRODUCTION -> R.string.detail_status_post_production
        com.example.data.util.DetailFactsMapper.TitleStatus.IN_PRODUCTION -> R.string.detail_status_in_production
        com.example.data.util.DetailFactsMapper.TitleStatus.PLANNED -> R.string.detail_status_planned
        com.example.data.util.DetailFactsMapper.TitleStatus.CANCELED -> R.string.detail_status_canceled
        com.example.data.util.DetailFactsMapper.TitleStatus.RUMORED -> R.string.detail_status_rumored
        com.example.data.util.DetailFactsMapper.TitleStatus.RETURNING_SERIES -> R.string.detail_status_returning_series
        com.example.data.util.DetailFactsMapper.TitleStatus.ENDED -> R.string.detail_status_ended
        com.example.data.util.DetailFactsMapper.TitleStatus.PILOT -> R.string.detail_status_pilot
      }
    )
  }
  val releaseLabel = stringResource(
    if (isTvSeries) R.string.detail_info_first_air else R.string.detail_info_release
  )
  val runtimeLabel = stringResource(
    if (isTvSeries) R.string.detail_info_episode_runtime else R.string.detail_info_runtime
  )
  val rows = listOfNotNull(
    statusLabel?.let { stringResource(R.string.detail_info_status) to it },
    releaseYear?.let { releaseLabel to it.toString() },
    duration?.let { runtimeLabel to it },
    certification?.let { stringResource(R.string.detail_info_certification) to it },
    originCountry?.let { stringResource(R.string.detail_info_origin_country) to it }
  )
  if (rows.isEmpty()) return

  Column(modifier = modifier.fillMaxWidth()) {
    MobileDetailSectionHeader(
      text = stringResource(
        if (isTvSeries) R.string.detail_series_details_title else R.string.detail_movie_details_title
      ),
      icon = Icons.Default.Info,
      modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(6.dp))
    rows.forEachIndexed { index, (label, value) ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = label,
          color = NovaTextMuted,
          fontSize = 13.sp,
          modifier = Modifier.weight(1f)
        )
        Text(
          text = value,
          color = NovaTextPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium,
          textAlign = TextAlign.End
        )
      }
      if (index < rows.lastIndex) {
        HorizontalDivider(
          color = NovaSurfaceVariant,
          thickness = 0.5.dp
        )
      }
    }
  }
}
