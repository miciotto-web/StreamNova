package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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

  // Ombra leggera di supporto per il titolo: garantisce leggibilità del testo su artwork chiari.
  val titleShadowStyle = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), blurRadius = 8f)
  )

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(350.dp)
  ) {
    // Backdrop Image con dissolvenza incrociata cinematografica tra i titoli (~350ms)
    AnimatedContent(
      targetState = currentMedia,
      transitionSpec = {
        fadeIn(animationSpec = tween(durationMillis = 350)) togetherWith
          fadeOut(animationSpec = tween(durationMillis = 350))
      },
      label = "HeroBackdropCrossfade"
    ) { media ->
      Box(modifier = Modifier.fillMaxSize()) {
        if (!media.backdropUrl.isNullOrBlank()) {
          AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(media.backdropUrl)
              .crossfade(350)
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

    // Vignette Gradient a doppio asse: profondità e leggibilità su qualsiasi artwork.
    // Il colore di raccordo è lo sfondo della schermata, così l'Hero si fonde con la
    // prima riga di poster senza stacco.
    val vignetteColor = MaterialTheme.colorScheme.background
    Box(
      modifier = Modifier
        .fillMaxSize()
        // Orizzontale (sinistra -> destra): protegge titolo, testo e pulsanti a sinistra.
        .background(
          Brush.horizontalGradient(
            colors = listOf(
              vignetteColor.copy(alpha = 0.85f),
              vignetteColor.copy(alpha = 0.5f),
              Color.Transparent
            ),
            startX = 0f,
            endX = Float.POSITIVE_INFINITY
          )
        )
        // Verticale (fondo -> alto): sfuma al 100% nello sfondo verso la prima riga di poster.
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Transparent,
              Color.Transparent,
              vignetteColor.copy(alpha = 0.5f),
              vignetteColor
            ),
            startY = 0f,
            endY = Float.POSITIVE_INFINITY
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
            .crossfade(350)
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
              letterSpacing = 0.5.sp,
              style = titleShadowStyle
            )
          }
        )
      } else {
        Text(
          text = currentMedia.title,
          color = NovaTextPrimary,
          fontSize = 32.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 0.5.sp,
          style = titleShadowStyle
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Badge compatti e allineati: Anno • Classificazione • Durata/Stagioni (+ generi)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        QualityBadge(text = currentMedia.year.toString())
        currentMedia.ageRating?.let { age ->
          QualityBadge(text = "$age+")
        }
        QualityBadge(
          text = if (currentMedia.type == MediaType.SERIE_TV) {
            stringResource(R.string.hero_seasons_count, currentMedia.seasonsCount ?: 1)
          } else {
            currentMedia.formattedDuration
          }
        )
        if (currentMedia.genres.isNotEmpty()) {
          Text(
            text = currentMedia.genres.take(3).joinToString(" · "),
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
