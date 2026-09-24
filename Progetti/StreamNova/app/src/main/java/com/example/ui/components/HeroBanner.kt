package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.data.model.MediaItem
import com.example.ui.theme.NovaGold
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

@Composable
fun HeroBanner(
  media: MediaItem,
  onPlayClick: () -> Unit,
  onInfoClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(350.dp)
  ) {
    // Backdrop Image from TMDB
    if (!media.backdropUrl.isNullOrBlank()) {
      AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
          .data(media.backdropUrl)
          .crossfade(true)
          .build(),
        contentDescription = media.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      val backdrop = media.backdropRes ?: R.drawable.banner_dune
      Image(
        painter = painterResource(id = backdrop),
        contentDescription = media.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
      )
    }

    // Gradients: dark from left, and dark from bottom
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.horizontalGradient(
            colors = listOf(
              Color(0xFF07090E),
              Color(0xEE07090E),
              Color(0x8807090E),
              Color.Transparent
            ),
            startX = 0f,
            endX = 1300f
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
              Color(0x9907090E),
              Color(0xFF07090E)
            ),
            startY = 170f
          )
        )
    )

    // Content Overlay
    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(start = 32.dp, bottom = 24.dp, end = 240.dp)
    ) {
      // Top row: "IN EVIDENZA" pill + Quality tags + Rating
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        QualityBadge(text = "IN EVIDENZA", isHighlighted = true)
        media.qualityTags.forEach { tag ->
          QualityBadge(text = tag, isHighlighted = false)
        }
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = NovaGold,
            modifier = Modifier.size(14.dp)
          )
          Spacer(modifier = Modifier.width(3.dp))
          Text(
            text = "%.1f".format(media.rating),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // TMDB Original Title Logo or Title Text
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
            .heightIn(min = 40.dp, max = 68.dp)
            .widthIn(max = 380.dp),
          error = {
            Text(
              text = media.title,
              color = NovaTextPrimary,
              fontSize = 32.sp,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 0.5.sp
            )
          }
        )
      } else {
        Text(
          text = media.title,
          color = NovaTextPrimary,
          fontSize = 32.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 0.5.sp
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Metadata line: Year • Duration • Genres
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = media.year.toString(),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium
        )
        Text(text = "•", color = NovaTextMuted, fontSize = 12.sp)
        Text(
          text = media.formattedDuration,
          color = NovaTextSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium
        )
        Text(text = "•", color = NovaTextMuted, fontSize = 12.sp)
        Text(
          text = media.genres.joinToString(", "),
          color = NovaTextSecondary,
          fontSize = 13.sp
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Synopsis snippet from TMDB
      Text(
        text = media.synopsis,
        color = NovaTextSecondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(16.dp))

      // Primary Actions
      Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        TvActionButton(
          text = "Guarda Ora",
          icon = Icons.Default.PlayArrow,
          isPrimary = true,
          onClick = onPlayClick
        )
        TvActionButton(
          text = "Scheda Dettagli",
          icon = Icons.Default.Info,
          isPrimary = false,
          onClick = onInfoClick
        )
      }
    }
  }
}
