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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGold
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * Continua a guardare (Landscape 16:9 con progresso)
 */
@Composable
fun ContinueWatchingCard(
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    modifier = modifier.width(260.dp),
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.07f,
    onClick = onClick
  ) { isFocused ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(NovaCardBg)
    ) {
      // Backdrop Thumbnail Container
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(146.dp)
      ) {
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
          val imageRes = media.backdropRes ?: R.drawable.banner_dune
          Image(
            painter = painterResource(id = imageRes),
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        }

        // Gradient overlay
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(
                  Color.Transparent,
                  Color.Black.copy(alpha = 0.8f)
                ),
                startY = 60f
              )
            )
        )

        // Play icon circle
        Box(
          modifier = Modifier
            .align(Alignment.Center)
            .size(42.dp)
            .background(
              color = if (isFocused) NovaCyan else Color.Black.copy(alpha = 0.6f),
              shape = RoundedCornerShape(21.dp)
            ),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Riproduci",
            tint = if (isFocused) Color.Black else Color.White,
            modifier = Modifier.size(24.dp)
          )
        }

        // Remaining time badge (top right)
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
          Text(
            text = media.formattedRemainingOrProgress,
            color = NovaCyanBright,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
        }

        // Quality badge (top left)
        Box(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
        ) {
          QualityBadge(text = media.resolution.badge, isHighlighted = true)
        }

        // Progress bar at the very bottom of the thumbnail
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(4.dp)
            .background(Color(0x55000000))
        ) {
          LinearProgressIndicator(
            progress = { media.progressFraction },
            modifier = Modifier
              .fillMaxWidth()
              .height(4.dp),
            color = NovaCyan,
            trackColor = Color(0x3300A3FF)
          )
        }
      }

      // Title & Episode metadata info
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 10.dp)
      ) {
        Text(
          text = media.title,
          color = if (isFocused) NovaCyanBright else NovaTextPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          val subtext = if (media.lastWatchedEpisode != null) {
            "S${media.lastWatchedSeason ?: 1}:E${media.lastWatchedEpisode} • ${media.formattedRemainingOrProgress}"
          } else {
            "${media.year} • ${media.formattedRemainingOrProgress}"
          }
          Text(
            text = subtext,
            color = NovaTextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}

/**
 * Card Orizzontale Backdrop (16:9 con backdrop TMDB)
 */
@Composable
fun StandardMediaCard(
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    modifier = modifier.width(220.dp),
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.07f,
    onClick = onClick
  ) { isFocused ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(NovaCardBg)
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(124.dp)
      ) {
        val imageUrl = media.backdropUrl ?: media.posterUrl
        if (!imageUrl.isNullOrBlank()) {
          AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(imageUrl)
              .crossfade(true)
              .build(),
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          val imageRes = media.backdropRes ?: R.drawable.banner_dune
          Image(
            painter = painterResource(id = imageRes),
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(
                  Color.Transparent,
                  Color.Black.copy(alpha = 0.75f)
                ),
                startY = 40f
              )
            )
        )

         // Quality badge top left
         Box(
           modifier = Modifier
             .align(Alignment.TopStart)
             .padding(6.dp)
         ) {
           Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
             QualityBadge(text = media.resolution.badge, isHighlighted = isFocused)
             if (media.isNew) {
               Box(
                 modifier = Modifier
                   .background(
                     Color(0xFF00C853),
                     RoundedCornerShape(4.dp)
                   )
                   .padding(horizontal = 5.dp, vertical = 2.dp)
               ) {
                 Text(
                   text = "NUOVO",
                   color = Color.Black,
                   fontSize = 9.sp,
                   fontWeight = FontWeight.Bold
                 )
               }
             }
           }
         }

        // Rating top right
        Row(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = NovaGold,
            modifier = Modifier.size(11.dp)
          )
          Spacer(modifier = Modifier.width(3.dp))
          Text(
            text = "%.1f".format(media.rating),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 10.dp, vertical = 8.dp)
      ) {
        Text(
          text = media.title,
          color = if (isFocused) NovaCyanBright else NovaTextPrimary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
           Text(
             text = media.year.toString(),
             color = NovaTextMuted,
             fontSize = 11.sp
           )
           Text(
             text = "•",
             color = NovaTextMuted,
             fontSize = 10.sp
           )
           Text(
             text = if (media.seasonsCount != null && media.episodes.isNotEmpty()) {
               "${media.seasonsCount} Stag. • ${media.episodes.size} Epi."
             } else if (media.seasonsCount != null) {
               "${media.seasonsCount} Stag."
             } else {
               media.formattedDuration
             },
             color = NovaTextMuted,
             fontSize = 11.sp
           )
           Text(
             text = "•",
             color = NovaTextMuted,
             fontSize = 10.sp
           )
           Text(
             text = media.genres.firstOrNull() ?: media.type.labelItalian,
             color = NovaTextSecondary,
             fontSize = 11.sp,
             maxLines = 1
           )
        }
      }
    }
  }
}

/**
 * Locandina Verticale (Formato Poster 2:3 da TMDB)
 */
@Composable
fun PosterMediaCard(
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    modifier = modifier.width(155.dp),
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.08f,
    onClick = onClick
  ) { isFocused ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(NovaCardBg)
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(232.dp)
      ) {
        val posterUrl = media.posterUrl ?: media.backdropUrl
        if (!posterUrl.isNullOrBlank()) {
          AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(posterUrl)
              .crossfade(true)
              .build(),
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          val fallbackRes = media.posterRes ?: media.backdropRes ?: R.drawable.banner_dune
          Image(
            painter = painterResource(id = fallbackRes),
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        }

        // Gradient overlay at bottom of poster
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(
                  Color.Transparent,
                  Color.Black.copy(alpha = 0.2f),
                  Color.Black.copy(alpha = 0.85f)
                ),
                startY = 120f
              )
            )
        )

         // Quality Badge
         Box(
           modifier = Modifier
             .align(Alignment.TopStart)
             .padding(6.dp)
         ) {
           Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
             QualityBadge(text = media.resolution.badge, isHighlighted = isFocused)
             if (media.isNew) {
               Box(
                 modifier = Modifier
                   .background(
                     Color(0xFF00C853),
                     RoundedCornerShape(4.dp)
                   )
                   .padding(horizontal = 5.dp, vertical = 2.dp)
               ) {
                 Text(
                   text = "NUOVO",
                   color = Color.Black,
                   fontSize = 9.sp,
                   fontWeight = FontWeight.Bold
                 )
               }
             }
           }
         }

        // TMDB Rating Badge
        Row(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = NovaGold,
            modifier = Modifier.size(11.dp)
          )
          Spacer(modifier = Modifier.width(3.dp))
          Text(
            text = "%.1f".format(media.rating),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
        }

        // If focused or at bottom, optionally show mini TMDB logo or title
        if (!media.logoUrl.isNullOrBlank() && isFocused) {
          Box(
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(8.dp)
          ) {
            SubcomposeAsyncImage(
              model = ImageRequest.Builder(LocalContext.current)
                .data(media.logoUrl)
                .crossfade(true)
                .build(),
              contentDescription = media.title,
              contentScale = ContentScale.Fit,
              modifier = Modifier
                .height(26.dp)
                .widthIn(max = 130.dp),
              error = {}
            )
          }
        }
      }

      // Title & Year info below the poster
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 8.dp)
      ) {
        Text(
          text = media.title,
          color = if (isFocused) NovaCyanBright else NovaTextPrimary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = media.year.toString(),
            color = NovaTextMuted,
            fontSize = 11.sp
          )
          Text(
            text = "•",
            color = NovaTextMuted,
            fontSize = 10.sp
          )
          Text(
            text = media.type.labelItalian,
            color = NovaTextSecondary,
            fontSize = 11.sp,
            maxLines = 1
          )
        }
      }
    }
  }
}
