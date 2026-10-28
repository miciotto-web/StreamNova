package com.aistudio.streamnova.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aistudio.streamnova.mobile.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.NeonCardBorderWidth
import com.example.ui.components.NeonCardCorner
import com.example.ui.components.StreamNovaWordmark
import com.example.ui.components.drawNeonGlow
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import java.util.Locale

/** Top bar mobile: wordmark ufficiale (Home) oppure titolo, con accesso impostazioni. */
@Composable
fun MobileTopBar(
  title: String?,
  onSettings: (() -> Unit)?,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (title == null) {
      StreamNovaWordmark(fontSize = 26.sp)
    } else {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold
      )
    }
    Spacer(modifier = Modifier.weight(1f))
    if (onSettings != null) {
      IconButton(onClick = onSettings) {
        Icon(
          imageVector = Icons.Default.Settings,
          contentDescription = stringResource(R.string.action_settings),
          tint = NovaTextSecondary
        )
      }
    }
  }
}

/** Pulsante principale del brand: ciano pieno, testo scuro. */
@Composable
fun NovaPrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Button(
    onClick = onClick,
    modifier = modifier,
    colors = ButtonDefaults.buttonColors(
      containerColor = NovaCyan,
      contentColor = Color.Black
    )
  ) {
    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(modifier = Modifier.width(6.dp))
    Text(text, fontWeight = FontWeight.Bold)
  }
}

/**
 * Card poster mobile: stessa palette e stesso alone neon della TV, ma con
 * interazione touch (click) e nessun focus D-pad.
 */
@Composable
fun MobilePosterCard(
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  showFavoriteBadge: Boolean = true
) {
  Column(
    modifier = modifier
      .clickable(onClick = onClick)
      .padding(2.dp)
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f)
        .drawBehind {
          drawNeonGlow(
            cornerRadiusPx = NeonCardCorner.toPx(),
            color = NovaCyan,
            spreadPx = 6.dp.toPx(),
            intensity = 0.28f
          )
        }
        .clip(RoundedCornerShape(NeonCardCorner))
        .background(NovaCardBg)
        .border(NeonCardBorderWidth / 3, NovaCyan.copy(alpha = 0.18f), RoundedCornerShape(NeonCardCorner))
    ) {
      MediaArtwork(
        media = media,
        preferPoster = true,
        modifier = Modifier.fillMaxSize()
      )
      if (showFavoriteBadge && media.isFavorite) {
        Icon(
          imageVector = Icons.Default.Favorite,
          contentDescription = null,
          tint = NovaCyanBright,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(16.dp)
        )
      }
      if (media.progressFraction > 0.01f) {
        LinearProgressIndicator(
          progress = { media.progressFraction },
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(3.dp),
          color = NovaCyan,
          trackColor = Color.White.copy(alpha = 0.16f)
        )
      }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = media.title,
      color = NovaTextPrimary,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis
    )
    Text(
      text = media.shortMetadata(),
      color = NovaTextMuted,
      fontSize = 11.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/** Card orizzontale "Continua a guardare" con progresso reale. */
@Composable
fun MobileContinueCard(
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .width(230.dp)
      .clickable(onClick = onClick)
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(12.dp))
        .background(NovaCardBg)
    ) {
      MediaArtwork(
        media = media,
        preferPoster = false,
        modifier = Modifier.fillMaxSize()
      )
      LinearProgressIndicator(
        progress = { media.progressFraction },
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .height(4.dp),
        color = NovaCyan,
        trackColor = Color.White.copy(alpha = 0.16f)
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = media.title,
      color = NovaTextPrimary,
      fontSize = 14.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Text(
      text = media.continueLabel(),
      color = NovaTextMuted,
      fontSize = 11.sp
    )
  }
}

/** Riga orizzontale titolata. */
@Composable
fun MobileSectionRow(
  title: String,
  items: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  onSeeAll: (() -> Unit)? = null,
  fixedWidth: Boolean = true
) {
  if (items.isEmpty()) return
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.weight(1f))
      if (onSeeAll != null) {
        Text(
          text = "Vedi tutto",
          color = NovaCyan,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSeeAll)
            .padding(horizontal = 8.dp, vertical = 4.dp)
        )
      }
    }
    LazyRow(
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      items(items, key = { it.id }) { media ->
        if (fixedWidth) {
          MobilePosterCard(
            media = media,
            onClick = { onMediaClick(media) },
            modifier = Modifier.width(132.dp)
          )
        } else {
          MobileContinueCard(media = media, onClick = { onMediaClick(media) })
        }
      }
    }
    Spacer(modifier = Modifier.height(14.dp))
  }
}

/** Hero cinematografico: backdrop, gradient e azioni principali. */
@Composable
fun MobileHero(
  media: MediaItem,
  onPlay: () -> Unit,
  onDetails: () -> Unit,
  modifier: Modifier = Modifier,
  expanded: Boolean = false
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(if (expanded) 460.dp else 340.dp)
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
            0f to Color.Transparent,
            0.45f to Color(0x6607090E),
            0.78f to NovaCardBg.copy(alpha = 0.92f),
            1f to com.example.ui.theme.NovaBackground
          )
        )
    )
    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
      Text(
        text = media.title,
        color = NovaTextPrimary,
        fontSize = if (expanded) 36.sp else 28.sp,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = media.heroMetadata(),
        color = NovaTextSecondary,
        fontSize = 13.sp
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = media.synopsis,
        color = NovaTextSecondary,
        fontSize = 13.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(modifier = Modifier.height(14.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NovaPrimaryButton(
          text = if (media.currentProgressMs > 0) stringResource(R.string.action_resume)
          else stringResource(R.string.action_play),
          onClick = onPlay
        )
        Button(
          onClick = onDetails,
          colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = NovaTextPrimary
          )
        ) {
          Text("Dettagli", fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

@Composable
fun MobileEmptyState(
  title: String,
  body: String,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp, vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = title,
      color = NovaTextPrimary,
      fontSize = 20.sp,
      fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = body,
      color = NovaTextMuted,
      fontSize = 14.sp
    )
  }
}

@Composable
fun MediaArtwork(
  media: MediaItem,
  preferPoster: Boolean,
  modifier: Modifier = Modifier
) {
  val model = if (preferPoster) media.posterModel() else media.backdropModel()
  if (model == null) {
    Box(modifier = modifier.background(NovaCardBg))
    return
  }
  AsyncImage(
    model = model,
    contentDescription = media.title,
    contentScale = ContentScale.Crop,
    modifier = modifier
  )
}

fun MediaItem.posterModel(): Any? =
  posterUrl?.takeIf { it.isNotBlank() }
    ?: backdropUrl?.takeIf { it.isNotBlank() }
    ?: posterRes?.takeIf { it != 0 }
    ?: backdropRes?.takeIf { it != 0 }

fun MediaItem.backdropModel(): Any? =
  backdropUrl?.takeIf { it.isNotBlank() }
    ?: backdropRes?.takeIf { it != 0 }
    ?: posterUrl?.takeIf { it.isNotBlank() }
    ?: posterRes?.takeIf { it != 0 }

private fun MediaItem.shortMetadata(): String {
  val yearText = if (year > 0) year.toString() else ""
  val ratingText = if (rating > 0f) String.format(Locale.ITALY, "%.1f", rating) else ""
  return listOf(yearText, ratingText).filter { it.isNotBlank() }.joinToString(" • ")
}

private fun MediaItem.heroMetadata(): String {
  val typeText = when (type) {
    MediaType.FILM -> "Film"
    MediaType.SERIE_TV -> "Serie TV"
    MediaType.DOCUMENTARIO -> "Documentario"
  }
  val yearText = if (year > 0) year.toString() else ""
  val ratingText = if (rating > 0f) String.format(Locale.ITALY, "%.1f", rating) else ""
  val durationText = if (durationMinutes > 0) "$durationMinutes min" else ""
  return listOf(typeText, yearText, ratingText, durationText)
    .filter { it.isNotBlank() }
    .joinToString(" • ")
}

private fun MediaItem.continueLabel(): String {
  val remaining = ((totalDurationMs - currentProgressMs).coerceAtLeast(0L) / 60_000L).toInt()
  return if (remaining > 0) "$remaining min rimanenti" else "In corso"
}
