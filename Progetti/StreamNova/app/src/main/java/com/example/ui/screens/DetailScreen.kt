package com.example.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.CastMember
import com.example.data.model.Episode
import com.example.data.model.ExtendedMediaDetails
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SeasonEpisodesUiState
import com.example.data.model.toEpisode
import com.example.ui.components.ProviderBadge
import com.example.ui.components.ProviderConstants
import com.example.ui.components.QualityBadge
import com.example.ui.components.StandardMediaCard
import com.example.ui.components.StreamingProvider
import com.example.ui.components.TvActionButton
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGold
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

enum class DetailTab(val title: String) {
  EPISODI("Episodi"),
  CONSIGLIATI("Consigliati"),
  DETTAGLI("Dettagli Tecnici")
}

@Composable
fun DetailScreen(
  media: MediaItem,
  allMedia: List<MediaItem>,
  onBackClick: () -> Unit,
  onPlayClick: (MediaItem, Episode?) -> Unit,
  onToggleFavorite: (String) -> Unit,
  onProviderClick: ((StreamingProvider) -> Unit)? = null,
  onMediaClick: ((MediaItem) -> Unit)? = null,
  detailUiState: MediaDetailUiState? = null,
  selectedSeasonNumber: Int = 1,
  seasonEpisodesUiState: SeasonEpisodesUiState? = null,
  onSeasonChange: ((Int) -> Unit)? = null,
  onEpisodeClick: ((Episode) -> Unit)? = null,
  onRetry: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  // Resolve active media & extended details depending on UI State
  val (displayMedia, extendedDetails) = when (detailUiState) {
    is MediaDetailUiState.Success -> detailUiState.media to detailUiState.details
    is MediaDetailUiState.Loading -> (detailUiState.baseMedia ?: media) to null
    is MediaDetailUiState.Error -> (detailUiState.baseMedia ?: media) to null
    else -> media to null
  }

  var selectedTab by remember(displayMedia.id) { mutableIntStateOf(0) }
  val isTvSeries = displayMedia.type == MediaType.SERIE_TV
  val tabs = if (isTvSeries) {
    listOf(DetailTab.EPISODI, DetailTab.CONSIGLIATI, DetailTab.DETTAGLI)
  } else {
    listOf(DetailTab.CONSIGLIATI, DetailTab.DETTAGLI)
  }

  // Calculate available seasons
  val availableSeasons = remember(displayMedia, extendedDetails) {
    val totalCount = (extendedDetails?.seasonsCount ?: displayMedia.seasonsCount ?: displayMedia.episodes.map { it.seasonNumber }.maxOrNull() ?: 1).coerceAtLeast(1)
    (1..totalCount).toList()
  }

  var localSelectedSeason by remember(displayMedia.id) {
    val initial = displayMedia.lastWatchedSeason?.takeIf { it in availableSeasons }
      ?: availableSeasons.firstOrNull()
      ?: 1
    mutableIntStateOf(initial)
  }

  val activeSeason = if (selectedSeasonNumber in availableSeasons) {
    selectedSeasonNumber
  } else {
    localSelectedSeason
  }

  val isSeasonLoading = seasonEpisodesUiState is SeasonEpisodesUiState.Loading && seasonEpisodesUiState.seasonNumber == activeSeason
  val seasonErrorMessage = if (seasonEpisodesUiState is SeasonEpisodesUiState.Error && seasonEpisodesUiState.seasonNumber == activeSeason) {
    seasonEpisodesUiState.message
  } else null

  // Filter or resolve episodes for the currently active season
  val episodesForSeason = remember(displayMedia, activeSeason, seasonEpisodesUiState) {
    if (seasonEpisodesUiState is SeasonEpisodesUiState.Success && seasonEpisodesUiState.seasonNumber == activeSeason) {
      seasonEpisodesUiState.season.episodes.map { it.toEpisode(displayMedia.id) }
    } else {
      val eps = displayMedia.episodes.filter { it.seasonNumber == activeSeason }
      if (eps.isNotEmpty()) {
        eps
      } else {
        (1..8).map { epIndex ->
          Episode(
            id = "${displayMedia.id}_s${activeSeason}e${epIndex}",
            seasonNumber = activeSeason,
            episodeNumber = epIndex,
            title = "Episodio $epIndex",
            synopsis = "Episodio $epIndex della Stagione $activeSeason della serie ${displayMedia.title}.",
            durationMinutes = displayMedia.durationMinutes.takeIf { it > 0 } ?: 55,
            thumbnailUrl = displayMedia.backdropUrl,
            thumbnailRes = displayMedia.backdropRes,
            videoUrl = displayMedia.videoUrl,
            rating = displayMedia.rating
          )
        }
      }
    }
  }

  // Cast members from TMDB extended details or fallback from media item
  val castMembers = remember(displayMedia, extendedDetails) {
    extendedDetails?.cast?.takeIf { it.isNotEmpty() }
      ?: displayMedia.cast.mapIndexed { index, name ->
        CastMember(id = index, name = name, character = "Cast principale", profileUrl = null)
      }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    // 1. Backdrop Wallpaper (16:9) with cinematic gradient overlays
    val backdropUrl = extendedDetails?.backdropUrl ?: displayMedia.backdropUrl
    if (!backdropUrl.isNullOrBlank()) {
      AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
          .data(backdropUrl)
          .crossfade(true)
          .build(),
        contentDescription = displayMedia.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxWidth()
          .height(500.dp)
          .align(Alignment.TopCenter)
      )
    } else {
      val backdropRes = displayMedia.backdropRes ?: R.drawable.banner_lastofus
      Image(
        painter = painterResource(id = backdropRes),
        contentDescription = displayMedia.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxWidth()
          .height(500.dp)
          .align(Alignment.TopCenter)
      )
    }

    // Cinematic dark gradient overlay: horizontal fade and vertical fade into NovaBackground
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.horizontalGradient(
            colors = listOf(
              NovaBackground,
              NovaBackground.copy(alpha = 0.95f),
              NovaBackground.copy(alpha = 0.70f),
              Color.Transparent
            ),
            startX = 0f,
            endX = 1400f
          )
        )
    )

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Transparent,
              NovaBackground.copy(alpha = 0.85f),
              NovaBackground
            ),
            startY = 160f,
            endY = 520f
          )
        )
    )

    // Main Scrollable Content
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp)
    ) {
      // Back button row
      item {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          TvFocusableBox(
            shape = CircleShape,
            onClick = onBackClick
          ) { isFocused ->
            Row(
              modifier = Modifier
                .background(
                  if (isFocused) NovaCyan else Color.Black.copy(alpha = 0.5f),
                  CircleShape
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Torna indietro",
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Indietro",
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(16.dp))
      }

      // Loading Shimmer or Error Banner
      if (detailUiState is MediaDetailUiState.Loading) {
        item {
          Row(
            modifier = Modifier
              .padding(start = 32.dp, bottom = 12.dp)
              .background(NovaSurfaceVariant.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
              .border(1.dp, NovaCyan.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
              .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              color = NovaCyanBright,
              strokeWidth = 2.dp
            )
            Text(
              text = "Recupero dettagli estesi da TMDB...",
              color = NovaCyanBright,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium
            )
          }
        }
      } else if (detailUiState is MediaDetailUiState.Error) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 32.dp, vertical = 8.dp)
              .background(Color(0xFF3B1515).copy(alpha = 0.9f), RoundedCornerShape(12.dp))
              .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
              .padding(14.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Warning,
                  contentDescription = null,
                  tint = Color(0xFFEF4444),
                  modifier = Modifier.size(20.dp)
                )
                Text(
                  text = "Dettagli parziali: ${detailUiState.message}",
                  color = Color.White,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium
                )
              }
              if (onRetry != null) {
                TvActionButton(
                  text = "Riprova",
                  icon = Icons.Default.Refresh,
                  isPrimary = true,
                  onClick = onRetry
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(10.dp))
        }
      }

      // Title & Metadata & Action buttons
      item {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 260.dp)
        ) {
          // Quality Tags & Rating Row
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            val providerObj = ProviderConstants.ALL.firstOrNull { it.id == displayMedia.provider?.lowercase() }
            if (providerObj != null) {
              ProviderBadge(
                provider = providerObj,
                onClick = if (onProviderClick != null) { { onProviderClick(providerObj) } } else null
              )
            }
            displayMedia.qualityTags.forEach { tag ->
              QualityBadge(text = tag, isHighlighted = tag.contains("4K") || tag.contains("HDR"))
            }

            // TMDB Rating Badge
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = NovaGold,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "%.1f".format(extendedDetails?.rating ?: displayMedia.rating),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
              if (extendedDetails != null && extendedDetails.voteCount > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "(${extendedDetails.voteCount})",
                  color = NovaTextMuted,
                  fontSize = 11.sp
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Big Main Title (Original TMDB Logo if available)
          if (!displayMedia.logoUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
              model = ImageRequest.Builder(LocalContext.current)
                .data(displayMedia.logoUrl)
                .crossfade(true)
                .build(),
              contentDescription = displayMedia.title,
              contentScale = ContentScale.Fit,
              alignment = Alignment.CenterStart,
              modifier = Modifier
                .heightIn(min = 46.dp, max = 84.dp)
                .widthIn(max = 440.dp),
              error = {
                Text(
                  text = displayMedia.title,
                  color = NovaTextPrimary,
                  fontSize = 36.sp,
                  fontWeight = FontWeight.Black,
                  letterSpacing = 0.5.sp
                )
              }
            )
          } else {
            Text(
              text = displayMedia.title,
              color = NovaTextPrimary,
              fontSize = 36.sp,
              fontWeight = FontWeight.Black,
              letterSpacing = 0.5.sp
            )
          }

          // Tagline if available
          if (!extendedDetails?.tagline.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "\"${extendedDetails?.tagline}\"",
              color = NovaCyanBright.copy(alpha = 0.85f),
              fontSize = 14.sp,
              fontWeight = FontWeight.Normal
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Metadata row: Year • Type • Seasons/Duration
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            val displayYear = extendedDetails?.releaseYear ?: displayMedia.year
            Text(text = displayYear.toString(), color = NovaTextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "•", color = NovaTextMuted)
            Text(text = displayMedia.type.labelItalian, color = NovaTextSecondary, fontSize = 14.sp)
            Text(text = "•", color = NovaTextMuted)

            val durationText = if (displayMedia.type == MediaType.SERIE_TV) {
              val sCount = extendedDetails?.seasonsCount ?: displayMedia.seasonsCount ?: 1
              val epCount = extendedDetails?.episodesCount
              if (epCount != null && epCount > 0) "$sCount Stagioni • $epCount Ep." else "$sCount Stagioni"
            } else {
              val durMins = extendedDetails?.durationMinutes?.takeIf { it > 0 } ?: displayMedia.durationMinutes
              val h = durMins / 60
              val m = durMins % 60
              if (h > 0) "${h}h ${m}m" else "${m}m"
            }
            Text(text = durationText, color = NovaTextSecondary, fontSize = 14.sp)
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Genre tags/chips
          val displayGenres = extendedDetails?.genres?.takeIf { it.isNotEmpty() } ?: displayMedia.genres
          if (displayGenres.isNotEmpty()) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              displayGenres.take(5).forEach { genre ->
                Box(
                  modifier = Modifier
                    .background(NovaSurfaceVariant.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                    .border(1.dp, NovaCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                  Text(
                    text = genre,
                    color = NovaCyanBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          // Full Synopsis (Overview)
          val synopsisText = extendedDetails?.overview?.takeIf { it.isNotBlank() } ?: displayMedia.synopsis
          Text(
            text = synopsisText,
            color = NovaTextSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp
          )

          Spacer(modifier = Modifier.height(24.dp))

          // Action Buttons: "Riproduci" / "Guarda ora" + "+ La mia lista"
          Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            val hasTvProgress = displayMedia.type == MediaType.SERIE_TV && (
              displayMedia.currentProgressMs > 0 ||
              displayMedia.lastWatchedEpisode != null ||
              displayMedia.episodes.any { it.currentProgressMs > 0 }
            )

            val resumeEpisode = if (displayMedia.type == MediaType.SERIE_TV) {
              if (hasTvProgress) {
                displayMedia.episodes.find {
                  it.seasonNumber == (displayMedia.lastWatchedSeason ?: 1) &&
                  it.episodeNumber == (displayMedia.lastWatchedEpisode ?: 1)
                } ?: displayMedia.episodes.firstOrNull { it.currentProgressMs > 0 }
                  ?: episodesForSeason.firstOrNull()
                  ?: displayMedia.episodes.firstOrNull()
              } else {
                episodesForSeason.firstOrNull { it.seasonNumber == 1 && it.episodeNumber == 1 }
                  ?: episodesForSeason.firstOrNull()
                  ?: displayMedia.episodes.firstOrNull()
              }
            } else null

            val playButtonLabel = if (displayMedia.type == MediaType.SERIE_TV) {
              if (hasTvProgress && resumeEpisode != null) {
                "Riprendi S${resumeEpisode.seasonNumber}:E${resumeEpisode.episodeNumber}"
              } else {
                "Riproduci"
              }
            } else if (displayMedia.currentProgressMs > 0) {
              "Riprendi la visione"
            } else {
              "Riproduci"
            }

            TvActionButton(
              text = playButtonLabel,
              icon = Icons.Default.PlayArrow,
              isPrimary = true,
              onClick = { onPlayClick(displayMedia, resumeEpisode) }
            )

            TvActionButton(
              text = if (displayMedia.isFavorite) "Rimuovi dai preferiti" else "Aggiungi a preferiti",
              icon = if (displayMedia.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
              isPrimary = false,
              onClick = { onToggleFavorite(displayMedia.id) }
            )
          }
        }

        Spacer(modifier = Modifier.height(28.dp))
      }

      // Horizontal Carousel of Main Cast (Foto profilo, attore e ruolo)
      if (castMembers.isNotEmpty()) {
        item {
          CastCarousel(cast = castMembers)
          Spacer(modifier = Modifier.height(24.dp))
        }
      }

      // Tabs Header: "Episodi", "Consigliati", "Dettagli Tecnici"
      item {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          tabs.forEachIndexed { index, tab ->
            val isSelected = selectedTab == index
            TvFocusableBox(
              shape = RoundedCornerShape(8.dp),
              onClick = { selectedTab = index }
            ) { isFocused ->
              Column(
                modifier = Modifier
                  .background(
                    if (isFocused) NovaCyan.copy(alpha = 0.2f) else Color.Transparent,
                    RoundedCornerShape(8.dp)
                  )
                  .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Text(
                  text = tab.title,
                  color = if (isSelected || isFocused) NovaCyanBright else NovaTextSecondary,
                  fontSize = 16.sp,
                  fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                  modifier = Modifier
                    .width(40.dp)
                    .height(3.dp)
                    .background(
                      if (isSelected) NovaCyan else Color.Transparent,
                      RoundedCornerShape(2.dp)
                    )
                )
              }
            }
          }
        }
        Spacer(modifier = Modifier.height(16.dp))
      }

      // Tab Content
      val currentTab = tabs.getOrNull(selectedTab) ?: tabs.first()

      when (currentTab) {
        DetailTab.EPISODI -> {
          // Season Selection Section
          item {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 4.dp)
            ) {
              // Header title
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Tv,
                  contentDescription = null,
                  tint = NovaCyanBright,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = "SELEZIONE STAGIONE",
                  color = NovaTextMuted,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  letterSpacing = 1.sp
                )
              }

              // Horizontal scrollable row of season pills
              LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
              ) {
                items(availableSeasons) { seasonNum ->
                  val isSeasonSelected = seasonNum == activeSeason
                  val epCount = if (isSeasonSelected && episodesForSeason.isNotEmpty()) {
                    episodesForSeason.size
                  } else {
                    displayMedia.episodes.count { it.seasonNumber == seasonNum }.takeIf { it > 0 } ?: 8
                  }

                  TvFocusableBox(
                    shape = RoundedCornerShape(50),
                    focusedScale = 1.05f,
                    onClick = {
                      localSelectedSeason = seasonNum
                      onSeasonChange?.invoke(seasonNum)
                    }
                  ) { isFocused ->
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(10.dp),
                      modifier = Modifier
                        .background(
                          color = when {
                            isFocused -> NovaCyan.copy(alpha = 0.35f)
                            isSeasonSelected -> NovaCyan.copy(alpha = 0.22f)
                            else -> NovaSurfaceVariant
                          },
                          shape = RoundedCornerShape(50)
                        )
                        .border(
                          width = if (isFocused) 2.dp else if (isSeasonSelected) 1.5.dp else 1.dp,
                          color = when {
                            isFocused -> NovaCyanBright
                            isSeasonSelected -> NovaCyan
                            else -> Color(0xFF1E293B)
                          },
                          shape = RoundedCornerShape(50)
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                      Text(
                        text = "Stagione $seasonNum",
                        color = when {
                          isFocused -> NovaCyanBright
                          isSeasonSelected -> NovaCyanBright
                          else -> NovaTextSecondary
                        },
                        fontSize = 15.sp,
                        fontWeight = if (isSeasonSelected || isFocused) FontWeight.Bold else FontWeight.Medium
                      )

                      // Badge with episode count
                      Box(
                        modifier = Modifier
                          .background(
                            if (isSeasonSelected || isFocused) NovaCyan.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.45f),
                            RoundedCornerShape(6.dp)
                          )
                          .padding(horizontal = 8.dp, vertical = 3.dp)
                      ) {
                        Text(
                          text = "$epCount ep",
                          color = if (isSeasonSelected || isFocused) NovaCyanBright else NovaTextMuted,
                          fontSize = 11.sp,
                          fontWeight = FontWeight.SemiBold
                        )
                      }
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(14.dp))

              // Current season status bar
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(NovaCardBg.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                  .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                  .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = "Stagione $activeSeason • ${episodesForSeason.size} Episodi",
                  color = NovaTextPrimary,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.SemiBold
                )
                Text(
                  text = "${displayMedia.resolution.badge} • Audio Italiano 5.1",
                  color = NovaCyanBright,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium
                )
              }

              Spacer(modifier = Modifier.height(8.dp))
            }
          }

          if (isSeasonLoading) {
            item {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 32.dp, vertical = 24.dp)
                  .background(NovaSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                  .border(1.dp, NovaCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                  .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(24.dp),
                  color = NovaCyanBright,
                  strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                  text = "Caricamento episodi Stagione $activeSeason da TMDB...",
                  color = NovaCyanBright,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Medium
                )
              }
            }
          } else if (seasonErrorMessage != null) {
            item {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 32.dp, vertical = 16.dp)
                  .background(Color(0xFF7F1D1D).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                  .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                  .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFCA5A5),
                    modifier = Modifier.size(20.dp)
                  )
                  Text(
                    text = seasonErrorMessage,
                    color = Color(0xFFFCA5A5),
                    fontSize = 13.sp
                  )
                }
                TvFocusableBox(
                  shape = RoundedCornerShape(8.dp),
                  onClick = { onSeasonChange?.invoke(activeSeason) }
                ) { isFocused ->
                  Row(
                    modifier = Modifier
                      .background(
                        if (isFocused) NovaCyan else Color(0xFFEF4444).copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                      )
                      .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Default.Refresh,
                      contentDescription = null,
                      tint = if (isFocused) Color.Black else Color.White,
                      modifier = Modifier.size(16.dp)
                    )
                    Text(
                      text = "Riprova",
                      color = if (isFocused) Color.Black else Color.White,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }
            }
          } else {
            items(episodesForSeason, key = { it.id }) { episode ->
              EpisodeRowItem(
                episode = episode,
                fallbackBackdropUrl = displayMedia.backdropUrl,
                fallbackBackdropRes = displayMedia.backdropRes,
                onClick = {
                  onEpisodeClick?.invoke(episode) ?: onPlayClick(displayMedia, episode)
                },
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp)
              )
            }
          }
        }
        DetailTab.CONSIGLIATI -> {
          item {
            val similarRecommendations = remember(extendedDetails, allMedia, displayMedia) {
              extendedDetails?.similarItems?.takeIf { it.isNotEmpty() }
                ?: allMedia.filter { it.id != displayMedia.id }
            }
            LazyRow(
              contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
              items(similarRecommendations, key = { "rec_${it.id}" }) { item ->
                StandardMediaCard(
                  media = item,
                  onClick = { onMediaClick?.invoke(item) }
                )
              }
            }
          }
        }
        DetailTab.DETTAGLI -> {
          item {
            TechnicalDetailsCard(
              media = displayMedia,
              details = extendedDetails,
              modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
            )
          }
        }
      }
    }
  }
}

@Composable
fun CastCarousel(
  cast: List<CastMember>,
  modifier: Modifier = Modifier
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
    ) {
      Icon(
        imageVector = Icons.Default.Person,
        contentDescription = null,
        tint = NovaCyanBright,
        modifier = Modifier.size(18.dp)
      )
      Text(
        text = "CAST PRINCIPALE",
        color = NovaTextMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    LazyRow(
      contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      items(cast, key = { "${it.id}_${it.name}" }) { member ->
        CastMemberCard(member = member)
      }
    }
  }
}

@Composable
fun CastMemberCard(
  member: CastMember,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.05f,
    onClick = { /* Focusable TV Card */ },
    modifier = modifier.width(135.dp)
  ) { isFocused ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaSurfaceVariant else NovaCardBg,
          RoundedCornerShape(12.dp)
        )
        .border(
          width = if (isFocused) 1.5.dp else 1.dp,
          color = if (isFocused) NovaCyanBright else Color(0xFF1E293B),
          shape = RoundedCornerShape(12.dp)
        )
        .padding(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Profile photo
      Box(
        modifier = Modifier
          .size(76.dp)
          .clip(CircleShape)
          .background(Color(0xFF0F172A))
          .border(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) NovaCyanBright else Color(0xFF334155),
            shape = CircleShape
          ),
        contentAlignment = Alignment.Center
      ) {
        if (!member.profileUrl.isNullOrBlank()) {
          AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(member.profileUrl)
              .crossfade(true)
              .build(),
            contentDescription = member.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = NovaTextMuted,
            modifier = Modifier.size(36.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Text(
        text = member.name,
        color = if (isFocused) NovaCyanBright else NovaTextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )

      if (member.character.isNotBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = member.character,
          color = NovaTextMuted,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
fun EpisodeRowItem(
  episode: Episode,
  onClick: () -> Unit,
  fallbackBackdropUrl: String? = null,
  fallbackBackdropRes: Int? = null,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.02f,
    onClick = onClick
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          color = if (isFocused) NovaSurfaceVariant else NovaCardBg,
          shape = RoundedCornerShape(12.dp)
        )
        .border(
          width = if (isFocused) 2.dp else 1.dp,
          color = if (isFocused) NovaCyanBright else Color(0xFF1E293B),
          shape = RoundedCornerShape(12.dp)
        )
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // 16:9 Thumbnail container
      Box(
        modifier = Modifier
          .width(160.dp)
          .height(90.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF0F172A))
          .border(
            width = if (isFocused) 1.5.dp else 1.dp,
            color = if (isFocused) NovaCyan else Color(0xFF334155),
            shape = RoundedCornerShape(8.dp)
          )
      ) {
        val thumbUrl = episode.thumbnailUrl ?: fallbackBackdropUrl
        if (!thumbUrl.isNullOrBlank()) {
          AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(thumbUrl)
              .crossfade(true)
              .build(),
            contentDescription = episode.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          val thumb = episode.thumbnailRes ?: fallbackBackdropRes ?: R.drawable.banner_lastofus
          Image(
            painter = painterResource(id = thumb),
            contentDescription = episode.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        }

        // Play icon overlay
        Box(
          modifier = Modifier
            .align(Alignment.Center)
            .size(34.dp)
            .background(Color.Black.copy(alpha = if (isFocused) 0.75f else 0.5f), CircleShape)
            .border(
              width = 1.dp,
              color = if (isFocused) NovaCyanBright else Color.White.copy(alpha = 0.3f),
              shape = CircleShape
            ),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (isFocused) NovaCyanBright else Color.White,
            modifier = Modifier.size(20.dp)
          )
        }

        // Progress bar at bottom
        if (episode.currentProgressMs > 0) {
          LinearProgressIndicator(
            progress = { episode.progressFraction },
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
              .height(3.dp),
            color = NovaCyan,
            trackColor = Color.Black.copy(alpha = 0.6f)
          )
        }
      }

      Spacer(modifier = Modifier.width(18.dp))

      // Content Column: Episode Title, Badges, Rating, Duration, Synopsis
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        // Row with Episode badge (e.g. E01), Title, Rating, Duration, Completed badge
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f, fill = false)
          ) {
            // Episode number badge (e.g. "E01")
            Box(
              modifier = Modifier
                .background(
                  if (isFocused) NovaCyan.copy(alpha = 0.35f) else NovaCyan.copy(alpha = 0.18f),
                  RoundedCornerShape(6.dp)
                )
                .border(
                  width = 1.dp,
                  color = if (isFocused) NovaCyanBright else NovaCyan.copy(alpha = 0.4f),
                  shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
              Text(
                text = "E%02d".format(episode.episodeNumber),
                color = NovaCyanBright,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
              )
            }

            Text(
              text = episode.title,
              color = if (isFocused) NovaCyanBright else NovaTextPrimary,
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          // Metadata Badges (Rating, Duration, Watched status)
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            // Rating if present
            if (episode.rating != null && episode.rating > 0f) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                  .background(NovaGold.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Star,
                  contentDescription = null,
                  tint = NovaGold,
                  modifier = Modifier.size(13.dp)
                )
                Text(
                  text = "%.1f".format(episode.rating),
                  color = NovaGold,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }

            // Duration
            Text(
              text = "${episode.durationMinutes} min",
              color = NovaTextMuted,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium
            )

            // Watched / in progress badge
            if (episode.isCompleted) {
              Box(
                modifier = Modifier
                  .background(Color(0xFF10B981).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(
                  text = "✓ Visto",
                  color = Color(0xFF10B981),
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            } else if (episode.currentProgressMs > 0) {
              Box(
                modifier = Modifier
                  .background(NovaCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(
                  text = episode.remainingMinutesText,
                  color = NovaCyanBright,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }
        }

        // Air date if available
        if (!episode.airDate.isNullOrBlank()) {
          Text(
            text = "Data d'uscita: ${episode.airDate}",
            color = NovaTextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
          )
        }

        // Synopsis / Overview
        Text(
          text = episode.synopsis,
          color = NovaTextSecondary,
          fontSize = 13.sp,
          lineHeight = 19.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
fun TechnicalDetailsCard(
  media: MediaItem,
  details: ExtendedMediaDetails?,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(NovaCardBg, RoundedCornerShape(12.dp))
      .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
      .padding(24.dp),
    horizontalArrangement = Arrangement.spacedBy(24.dp)
  ) {
    // Visual Separation: Official TMDB Poster (2:3 aspect ratio)
    val posterUrl = details?.posterUrl ?: media.posterUrl
    if (!posterUrl.isNullOrBlank()) {
      Box(
        modifier = Modifier
          .width(160.dp)
          .height(240.dp)
          .clip(RoundedCornerShape(8.dp))
          .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
      ) {
        AsyncImage(
          model = ImageRequest.Builder(LocalContext.current)
            .data(posterUrl)
            .crossfade(true)
            .build(),
          contentDescription = media.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      }
    }

    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = "Specifiche Tecniche & Scheda",
        color = NovaTextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
      )

      Spacer(modifier = Modifier.height(2.dp))

      DetailRow(
        label = "Titolo Originale",
        value = details?.originalTitle?.takeIf { it.isNotBlank() } ?: media.originalTitle.ifBlank { media.title }
      )
      DetailRow(
        label = "Regia / Showrunner",
        value = details?.director?.takeIf { it.isNotBlank() } ?: media.director.ifEmpty { "Produzione Internazionale" }
      )
      DetailRow(
        label = "Generi",
        value = (details?.genres?.takeIf { it.isNotEmpty() } ?: media.genres).joinToString(", ")
      )
      if (!details?.tagline.isNullOrBlank()) {
        DetailRow(label = "Tagline", value = details?.tagline ?: "")
      }
      if (!details?.status.isNullOrBlank()) {
        DetailRow(label = "Stato TMDB", value = details?.status ?: "")
      }
      DetailRow(label = "Risoluzione video", value = "3840 x 2160 (Ultra HD 4K nativo)")
      DetailRow(label = "Formato HDR", value = "HDR10 / Dolby Vision (Wide Color Gamut BT.2020)")
      DetailRow(label = "Codec Audio", value = "Dolby Atmos / Dolby Digital Plus 5.1 (E-AC-3)")
      DetailRow(label = "Lingue audio disponibili", value = "Italiano 5.1, Inglese Dolby Atmos, Italiano Stereo")
      DetailRow(label = "Sottotitoli", value = "Italiano, Italiano Non Udenti, Inglese")
    }
  }
}

@Composable
fun DetailRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Text(
      text = label,
      color = NovaTextMuted,
      fontSize = 13.sp,
      modifier = Modifier.width(180.dp)
    )
    Text(
      text = value,
      color = NovaTextSecondary,
      fontSize = 13.sp,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.weight(1f)
    )
  }
}
