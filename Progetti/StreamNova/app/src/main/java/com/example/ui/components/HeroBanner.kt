package com.example.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay

@Composable
fun HeroBanner(
  items: List<MediaItem>,
  onPlayClick: (MediaItem) -> Unit,
  onInfoClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  val safeItems = remember(items) { items.take(9) }
  if (safeItems.isEmpty()) return

  var currentIndex by remember(safeItems) { mutableStateOf(0) }

  // Auto-scorrimento dinamico ogni 10 secondi
  LaunchedEffect(safeItems, currentIndex) {
    if (safeItems.size > 1) {
      delay(10_000L)
      currentIndex = (currentIndex + 1) % safeItems.size
    }
  }

  val currentMedia = safeItems.getOrElse(currentIndex) { safeItems.first() }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(350.dp)
  ) {
    // Backdrop Image con transizione morbida tra i titoli TMDB
    Crossfade(
      targetState = currentMedia,
      animationSpec = tween(durationMillis = 800),
      label = "HeroBackdropCrossfade"
    ) { media ->
      Box(modifier = Modifier.fillMaxSize()) {
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
      }
    }

    // Gradienti cinematografici di contrasto per leggibilità
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

    // Contenuto Principale Sovrapposto
    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(start = 32.dp, bottom = 24.dp, end = 200.dp)
    ) {
      // Titolo / Logo del film o serie TMDB
      if (!currentMedia.logoUrl.isNullOrBlank()) {
        SubcomposeAsyncImage(
          model = ImageRequest.Builder(LocalContext.current)
            .data(currentMedia.logoUrl)
            .crossfade(true)
            .build(),
          contentDescription = currentMedia.title,
          contentScale = ContentScale.Fit,
          alignment = Alignment.CenterStart,
          modifier = Modifier
            .heightIn(min = 40.dp, max = 68.dp)
            .widthIn(max = 380.dp),
          error = {
            Text(
              text = currentMedia.title,
              color = NovaTextPrimary,
              fontSize = 32.sp,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 0.5.sp
            )
          }
        )
      } else {
        Text(
          text = currentMedia.title,
          color = NovaTextPrimary,
          fontSize = 32.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 0.5.sp
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Metadati essenziali: Anno • Durata / Stagioni • Generi
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = currentMedia.year.toString(),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium
        )
        Text(text = "•", color = NovaTextMuted, fontSize = 12.sp)
        Text(
          text = if (currentMedia.type == MediaType.SERIE_TV) {
            stringResource(R.string.hero_seasons_count, currentMedia.seasonsCount ?: 1)
          } else {
            currentMedia.formattedDuration
          },
          color = NovaTextSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium
        )
        if (currentMedia.genres.isNotEmpty()) {
          Text(text = "•", color = NovaTextMuted, fontSize = 12.sp)
          Text(
            text = currentMedia.genres.take(3).joinToString(", "),
            color = NovaTextSecondary,
            fontSize = 13.sp
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Sinossi
      Text(
        text = currentMedia.synopsis,
        color = NovaTextSecondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(16.dp))

      // Pulsanti a forma di pillola più compatti
      Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        TvPillButton(
          text = stringResource(R.string.action_watch_now),
          icon = Icons.Default.PlayArrow,
          isPrimary = true,
          onClick = { onPlayClick(currentMedia) }
        )
        TvPillButton(
          text = stringResource(R.string.action_details),
          icon = Icons.Default.Info,
          isPrimary = false,
          onClick = { onInfoClick(currentMedia) }
        )
      }
    }

    // Indicatori di Scorrimento posizionati in basso a destra
    if (safeItems.size > 1) {
      Row(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 32.dp, bottom = 26.dp)
          .background(
            color = Color(0x66000000),
            shape = RoundedCornerShape(50)
          )
          .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        safeItems.forEachIndexed { index, _ ->
          val isSelected = index == currentIndex
          val indicatorWidth by animateDpAsState(
            targetValue = if (isSelected) 20.dp else 6.dp,
            animationSpec = tween(durationMillis = 300),
            label = "IndicatorWidth"
          )
          val indicatorColor by animateColorAsState(
            targetValue = if (isSelected) NovaCyanBright else Color.White.copy(alpha = 0.35f),
            animationSpec = tween(durationMillis = 300),
            label = "IndicatorColor"
          )

          Box(
            modifier = Modifier
              .height(6.dp)
              .width(indicatorWidth)
              .clip(RoundedCornerShape(50))
              .background(indicatorColor)
              .clickable { currentIndex = index }
          )
        }
      }
    }
  }
}

/**
 * Overload per retrocompatibilità
 */
@Composable
fun HeroBanner(
  media: MediaItem,
  onPlayClick: () -> Unit,
  onInfoClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  HeroBanner(
    items = listOf(media),
    onPlayClick = { onPlayClick() },
    onInfoClick = { onInfoClick() },
    modifier = modifier
  )
}

/**
 * Pulsante a pillola elegante per Hero Banner
 */
@Composable
fun TvPillButton(
  text: String,
  icon: ImageVector? = null,
  isPrimary: Boolean = true,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvActionButton(
    text = text,
    icon = icon,
    isPrimary = isPrimary,
    onClick = onClick,
    modifier = modifier
  )
}
