package com.aistudio.streamnova.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.LocalWindowWidthExpanded
import com.aistudio.streamnova.mobile.ui.MobileDetailViewModel
import com.aistudio.streamnova.mobile.ui.components.MediaArtwork
import com.aistudio.streamnova.mobile.ui.components.NovaPrimaryButton
import com.aistudio.streamnova.mobile.ui.components.backdropModel
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import java.util.Locale

@Composable
fun DetailScreen(
  mediaId: String,
  onBack: () -> Unit,
  onPlay: (season: Int?, episode: Int?) -> Unit
) {
  BackHandler(onBack = onBack)
  val viewModel: MobileDetailViewModel = viewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val expanded = LocalWindowWidthExpanded.current

  LaunchedEffect(mediaId) { viewModel.load(mediaId) }

  val media = state.media
  if (media == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      if (state.error != null) {
        Text(text = state.error.orEmpty(), color = NovaTextSecondary)
      } else {
        CircularProgressIndicator(color = NovaCyan)
      }
    }
    return
  }

  val isSeries = media.type == MediaType.SERIE_TV
  val initialEpisode = state.episodes.firstOrNull {
    it.seasonNumber == (media.lastWatchedSeason ?: 1) &&
      it.episodeNumber == (media.lastWatchedEpisode ?: 1)
  } ?: state.episodes.firstOrNull()

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 1000.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)
    ) {
      item(key = "hero") {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(if (expanded) 420.dp else 300.dp)
        ) {
          MediaArtwork(
            media = media,
            preferPoster = false,
            modifier = Modifier.fillMaxSize()
          )
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  0f to Color(0x6607090E),
                  0.55f to Color(0x2207090E),
                  1f to NovaBackground
                )
              )
          )
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .align(Alignment.TopStart)
              .statusBarsPadding()
              .padding(8.dp)
          ) {
            Icon(
              Icons.Default.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
              tint = NovaTextPrimary
            )
          }
          IconButton(
            onClick = viewModel::toggleFavorite,
            modifier = Modifier
              .align(Alignment.TopEnd)
              .statusBarsPadding()
              .padding(8.dp)
          ) {
            Icon(
              imageVector = if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
              contentDescription = stringResource(
                if (state.isFavorite) R.string.action_favorite_remove
                else R.string.action_favorite_add
              ),
              tint = if (state.isFavorite) NovaCyanBright else NovaTextPrimary
            )
          }
        }
      }

      item(key = "header") {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
          Text(
            text = media.title,
            color = NovaTextPrimary,
            fontSize = if (expanded) 34.sp else 26.sp,
            fontWeight = FontWeight.ExtraBold
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = media.detailMetadata(),
            color = NovaTextSecondary,
            fontSize = 13.sp
          )
          Spacer(modifier = Modifier.height(14.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NovaPrimaryButton(
              text = stringResource(
                if (media.currentProgressMs > 0 || (initialEpisode?.currentProgressMs ?: 0L) > 0)
                  R.string.action_resume
                else R.string.action_play
              ),
              onClick = {
                if (isSeries && initialEpisode != null) {
                  onPlay(initialEpisode.seasonNumber, initialEpisode.episodeNumber)
                } else {
                  onPlay(null, null)
                }
              }
            )
            OutlinedButton(onClick = viewModel::toggleFavorite) {
              Icon(
                imageVector = if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = null,
                tint = if (state.isFavorite) NovaCyanBright else NovaTextSecondary
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (state.isFavorite) "Nei preferiti" else "Preferiti",
                color = NovaTextSecondary
              )
            }
          }
          Spacer(modifier = Modifier.height(18.dp))
          Text(
            text = stringResource(R.string.detail_synopsis),
            color = NovaTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = media.synopsis.ifBlank { "Nessuna trama disponibile." },
            color = NovaTextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp
          )
          Spacer(modifier = Modifier.height(18.dp))
        }
      }

      if (isSeries && state.seasons.isNotEmpty()) {
        item(key = "seasons") {
          Column {
            Text(
              text = stringResource(R.string.detail_seasons),
              color = NovaTextPrimary,
              fontSize = 17.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              items(state.seasons, key = { it }) { season ->
                FilterChip(
                  selected = state.selectedSeason == season,
                  onClick = { viewModel.selectSeason(season) },
                  label = { Text("Stagione $season") },
                  colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NovaCyan.copy(alpha = 0.18f),
                    selectedLabelColor = NovaCyan,
                    labelColor = NovaTextMuted
                  )
                )
              }
            }
          }
        }

        item(key = "episodes-title") {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = stringResource(R.string.detail_episodes),
              color = NovaTextPrimary,
              fontSize = 17.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            if (state.episodesLoading) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = NovaCyan,
                strokeWidth = 2.dp
              )
            }
          }
        }

        if (!state.episodesLoading && state.episodes.isEmpty()) {
          item(key = "episodes-empty") {
            Text(
              text = stringResource(R.string.detail_no_episodes),
              color = NovaTextMuted,
              fontSize = 14.sp,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
          }
        } else {
          items(state.episodes, key = { it.id }) { episode ->
            EpisodeRow(
              episode = episode,
              onClick = { onPlay(episode.seasonNumber, episode.episodeNumber) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EpisodeRow(episode: Episode, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .width(132.dp)
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(10.dp))
        .background(NovaCardBg)
    ) {
      if (!episode.thumbnailUrl.isNullOrBlank()) {
        AsyncImage(
          model = episode.thumbnailUrl,
          contentDescription = episode.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      }
      if (episode.progressFraction > 0.01f) {
        LinearProgressIndicator(
          progress = { episode.progressFraction },
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(3.dp),
          color = NovaCyan,
          trackColor = Color.White.copy(alpha = 0.16f)
        )
      }
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 12.dp)
    ) {
      Text(
        text = "S${episode.seasonNumber}E${episode.episodeNumber} • ${episode.title}",
        color = NovaTextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = episode.synopsis.ifBlank { "${episode.durationMinutes} min" },
        color = NovaTextMuted,
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }
    Icon(
      imageVector = Icons.Default.PlayArrow,
      contentDescription = stringResource(R.string.action_play),
      tint = NovaCyan
    )
  }
}

private fun MediaItem.detailMetadata(): String {
  val typeText = when (type) {
    MediaType.FILM -> "Film"
    MediaType.SERIE_TV -> "Serie TV"
    MediaType.DOCUMENTARIO -> "Documentario"
  }
  val yearText = if (year > 0) year.toString() else ""
  val durationText = if (durationMinutes > 0) "$durationMinutes min" else ""
  val ratingText = if (rating > 0f) String.format(Locale.ITALY, "%.1f", rating) else ""
  val genresText = genres.take(3).joinToString(", ")
  return listOf(typeText, yearText, durationText, ratingText, genresText)
    .filter { it.isNotBlank() }
    .joinToString(" • ")
}
