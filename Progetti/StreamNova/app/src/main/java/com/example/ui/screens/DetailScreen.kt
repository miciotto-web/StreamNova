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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
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
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.QualityBadge
import com.example.ui.components.StandardMediaCard
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
  DETTAGLI("Dettagli")
}

@Composable
fun DetailScreen(
  media: MediaItem,
  allMedia: List<MediaItem>,
  onBackClick: () -> Unit,
  onPlayClick: (MediaItem, Episode?) -> Unit,
  onToggleFavorite: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  var selectedTab by remember(media.id) { mutableIntStateOf(0) }
  val tabs = if (media.type == MediaType.SERIE_TV) {
    listOf(DetailTab.EPISODI, DetailTab.CONSIGLIATI, DetailTab.DETTAGLI)
  } else {
    listOf(DetailTab.CONSIGLIATI, DetailTab.DETTAGLI)
  }

  // Calculate available seasons
  val availableSeasons = remember(media) {
    val seasonNums = media.episodes.map { it.seasonNumber }.distinct().sorted()
    if (seasonNums.isNotEmpty()) {
      seasonNums
    } else {
      val count = (media.seasonsCount ?: 1).coerceAtLeast(1)
      (1..count).toList()
    }
  }

  val initialSeason = remember(media.id, availableSeasons) {
    media.lastWatchedSeason?.takeIf { it in availableSeasons }
      ?: availableSeasons.firstOrNull()
      ?: 1
  }

  var selectedSeason by remember(media.id) { mutableIntStateOf(initialSeason) }

  // Filter episodes for the currently selected season
  val episodesForSeason = remember(media, selectedSeason) {
    val eps = media.episodes.filter { it.seasonNumber == selectedSeason }
    if (eps.isNotEmpty()) {
      eps
    } else {
      (1..8).map { epIndex ->
        Episode(
          id = "${media.id}_s${selectedSeason}e${epIndex}",
          seasonNumber = selectedSeason,
          episodeNumber = epIndex,
          title = "Episodio $epIndex",
          synopsis = "Episodio $epIndex della Stagione $selectedSeason della serie ${media.title}.",
          durationMinutes = media.durationMinutes.takeIf { it > 0 } ?: 55,
          thumbnailUrl = media.backdropUrl,
          thumbnailRes = media.backdropRes,
          videoUrl = media.videoUrl,
        )
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    // Backdrop Wallpaper with cinematic fade
    if (!media.backdropUrl.isNullOrBlank()) {
      AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
          .data(media.backdropUrl)
          .crossfade(true)
          .build(),
        contentDescription = media.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxWidth()
          .height(480.dp)
          .align(Alignment.TopCenter)
      )
    } else {
      val backdropRes = media.backdropRes ?: R.drawable.banner_lastofus
      Image(
        painter = painterResource(id = backdropRes),
        contentDescription = media.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxWidth()
          .height(480.dp)
          .align(Alignment.TopCenter)
      )
    }

    // Cinematic dark gradient overlay: left-to-right & top-to-bottom
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.horizontalGradient(
            colors = listOf(
              NovaBackground,
              NovaBackground.copy(alpha = 0.95f),
              NovaBackground.copy(alpha = 0.7f),
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
              NovaBackground.copy(alpha = 0.8f),
              NovaBackground
            ),
            startY = 180f,
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
        Spacer(modifier = Modifier.height(20.dp))
      }

      // Title & Metadata & Action buttons
      item {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 260.dp)
        ) {
          // Quality Tags Row
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            media.qualityTags.forEach { tag ->
              QualityBadge(text = tag, isHighlighted = tag.contains("4K") || tag.contains("HDR"))
            }
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(start = 4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = NovaGold,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "%.1f".format(media.rating),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Big Main Title (Original TMDB Logo if available)
          if (!media.logoUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
              model = ImageRequest.Builder(LocalContext.current)
                .data(media.logoUrl)
                .crossfade(true)
                .build(),
              contentDescription = media.title,
              contentScale = ContentScale.Fit,
              alignment = Alignment.CenterStart,
              modifier = Modifier
                .heightIn(min = 46.dp, max = 84.dp)
                .widthIn(max = 420.dp),
              error = {
                Text(
                  text = media.title,
                  color = NovaTextPrimary,
                  fontSize = 36.sp,
                  fontWeight = FontWeight.Black,
                  letterSpacing = 0.5.sp
                )
              }
            )
          } else {
            Text(
              text = media.title,
              color = NovaTextPrimary,
              fontSize = 36.sp,
              fontWeight = FontWeight.Black,
              letterSpacing = 0.5.sp
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Metadata line: Year • Type • Seasons/Duration • Genres
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(text = media.year.toString(), color = NovaTextSecondary, fontSize = 14.sp)
            Text(text = "•", color = NovaTextMuted)
            Text(text = media.type.labelItalian, color = NovaTextSecondary, fontSize = 14.sp)
            Text(text = "•", color = NovaTextMuted)
            Text(
              text = if (media.seasonsCount != null) "${media.seasonsCount} Stagione" else media.formattedDuration,
              color = NovaTextSecondary,
              fontSize = 14.sp
            )
            Text(text = "•", color = NovaTextMuted)
            Text(text = media.genres.joinToString(", "), color = NovaCyanBright, fontSize = 14.sp)
          }

          Spacer(modifier = Modifier.height(12.dp))

          // Italian detailed synopsis
          Text(
            text = media.synopsis,
            color = NovaTextSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp
          )

          Spacer(modifier = Modifier.height(24.dp))

          // Action Buttons: "Riprendi S1 E5" / "Guarda ora" + "+ La mia lista"
          Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            val resumeEpisode = media.episodes.find {
              it.seasonNumber == selectedSeason && it.episodeNumber == (media.lastWatchedEpisode ?: 1)
            } ?: episodesForSeason.firstOrNull() ?: media.episodes.firstOrNull()

            val playButtonLabel = if (media.type == MediaType.SERIE_TV) {
              if (resumeEpisode != null) {
                if (resumeEpisode.currentProgressMs > 0 && !resumeEpisode.isCompleted) {
                  "Riprendi S${resumeEpisode.seasonNumber}:E${resumeEpisode.episodeNumber}"
                } else {
                  "Guarda S${resumeEpisode.seasonNumber}:E${resumeEpisode.episodeNumber}"
                }
              } else {
                "Guarda ora"
              }
            } else if (media.currentProgressMs > 0) {
              "Riprendi la visione"
            } else {
              "Guarda ora"
            }

            TvActionButton(
              text = playButtonLabel,
              icon = Icons.Default.PlayArrow,
              isPrimary = true,
              onClick = { onPlayClick(media, resumeEpisode) }
            )

            TvActionButton(
              text = if (media.isFavorite) "✓ Nella mia lista" else "+ La mia lista",
              icon = if (media.isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
              isPrimary = false,
              onClick = { onToggleFavorite(media.id) }
            )
          }
        }

        Spacer(modifier = Modifier.height(36.dp))
      }

      // Tabs Header: "Episodi", "Consigliati", "Dettagli"
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
                  val isSeasonSelected = seasonNum == selectedSeason
                  val epCount = media.episodes.count { it.seasonNumber == seasonNum }.let { if (it > 0) it else 8 }

                  TvFocusableBox(
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.05f,
                    onClick = { selectedSeason = seasonNum }
                  ) { isFocused ->
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(10.dp),
                      modifier = Modifier
                        .background(
                          color = when {
                            isFocused -> NovaCyan.copy(alpha = 0.32f)
                            isSeasonSelected -> NovaCyan.copy(alpha = 0.18f)
                            else -> NovaSurfaceVariant
                          },
                          shape = RoundedCornerShape(12.dp)
                        )
                        .border(
                          width = if (isFocused) 2.dp else if (isSeasonSelected) 1.5.dp else 1.dp,
                          color = when {
                            isFocused -> NovaCyanBright
                            isSeasonSelected -> NovaCyan
                            else -> Color(0xFF1E293B)
                          },
                          shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                      Text(
                        text = "Stagione $seasonNum",
                        color = when {
                          isFocused -> NovaCyanBright
                          isSeasonSelected -> NovaTextPrimary
                          else -> NovaTextSecondary
                        },
                        fontSize = 15.sp,
                        fontWeight = if (isSeasonSelected || isFocused) FontWeight.Bold else FontWeight.Medium
                      )

                      // Badge with episode count
                      Box(
                        modifier = Modifier
                          .background(
                            if (isSeasonSelected) NovaCyan.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.45f),
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
                  text = "Stagione $selectedSeason • ${episodesForSeason.size} Episodi",
                  color = NovaTextPrimary,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.SemiBold
                )
                Text(
                  text = "${media.resolution.badge} • Audio Italiano 5.1",
                  color = NovaCyanBright,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium
                )
              }

              Spacer(modifier = Modifier.height(8.dp))
            }
          }

          items(episodesForSeason, key = { it.id }) { episode ->
            EpisodeRowItem(
              episode = episode,
              onClick = { onPlayClick(media, episode) },
              modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp)
            )
          }
        }
        DetailTab.CONSIGLIATI -> {
          item {
            val recommendations = allMedia.filter { it.id != media.id }
            LazyRow(
              contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
              items(recommendations, key = { "rec_${it.id}" }) { item ->
                StandardMediaCard(
                  media = item,
                  onClick = { /* Navigate or open */ }
                )
              }
            }
          }
        }
        DetailTab.DETTAGLI -> {
          item {
            TechnicalDetailsCard(
              media = media,
              modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
            )
          }
        }
      }
    }
  }
}

@Composable
fun EpisodeRowItem(
  episode: Episode,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(10.dp),
    focusedScale = 1.02f,
    onClick = onClick
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaSurfaceVariant else NovaCardBg,
          RoundedCornerShape(10.dp)
        )
        .border(
          width = 1.dp,
          color = if (isFocused) NovaCyan else Color(0xFF1E293B),
          shape = RoundedCornerShape(10.dp)
        )
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Episode number badge
      Text(
        text = "${episode.episodeNumber}",
        color = if (isFocused) NovaCyanBright else NovaTextMuted,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(36.dp)
      )

      // Episode Thumbnail
      Box(
        modifier = Modifier
          .width(130.dp)
          .height(74.dp)
          .clip(RoundedCornerShape(6.dp))
      ) {
        if (!episode.thumbnailUrl.isNullOrBlank()) {
          AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(episode.thumbnailUrl)
              .crossfade(true)
              .build(),
            contentDescription = episode.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          val thumb = episode.thumbnailRes ?: R.drawable.banner_lastofus
          Image(
            painter = painterResource(id = thumb),
            contentDescription = episode.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        }

        // Play icon
        Box(
          modifier = Modifier
            .align(Alignment.Center)
            .size(30.dp)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (isFocused) NovaCyanBright else Color.White,
            modifier = Modifier.size(18.dp)
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
            trackColor = Color.Black.copy(alpha = 0.5f)
          )
        }
      }

      Spacer(modifier = Modifier.width(16.dp))

      // Title & Synopsis & Duration
      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = episode.title,
            color = if (isFocused) NovaCyanBright else NovaTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            if (episode.isCompleted) {
              Box(
                modifier = Modifier
                  .background(Color(0xFF10B981).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(text = "✓ Visto", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            } else if (episode.currentProgressMs > 0) {
              Box(
                modifier = Modifier
                  .background(NovaCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(text = episode.remainingMinutesText, color = NovaCyanBright, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
              }
            }
            Text(
              text = "${episode.durationMinutes} min",
              color = NovaTextMuted,
              fontSize = 12.sp
            )
          }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = episode.synopsis,
          color = NovaTextSecondary,
          fontSize = 12.sp,
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
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(NovaCardBg, RoundedCornerShape(12.dp))
      .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Text(
      text = "Specifiche Tecniche & Cast",
      color = NovaTextPrimary,
      fontSize = 18.sp,
      fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(4.dp))

    DetailRow(label = "Regia", value = media.director.ifEmpty { "Non disponibile" })
    DetailRow(label = "Cast principale", value = media.cast.joinToString(", ").ifEmpty { "Non disponibile" })
    DetailRow(label = "Risoluzione video", value = "3840 x 2160 (Ultra HD 4K nativo)")
    DetailRow(label = "Formato HDR", value = "HDR10 / Dolby Vision (Wide Color Gamut BT.2020)")
    DetailRow(label = "Codec Audio", value = "Dolby Atmos / Dolby Digital Plus 5.1 (E-AC-3)")
    DetailRow(label = "Lingue audio disponibili", value = "Italiano 5.1, Inglese Atmos, Italiano Stereo")
    DetailRow(label = "Sottotitoli", value = "Italiano, Italiano Non Udenti, Inglese")
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
