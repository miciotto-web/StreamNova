package com.aistudio.streamnova.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.aistudio.streamnova.mobile.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay

/** Intervallo dell'auto-scorrimento TV 1.0.4, riavviato a ogni interazione manuale. */
internal const val MOBILE_HERO_AUTO_SCROLL_DELAY_MS = 10_000L

/** Dissolvenza incrociata del backdrop, come la Hero TV. */
private const val HERO_CROSSFADE_MS = 350

/** Spazio riservato in basso per i pallini di posizione. */
private val HeroDotsReservedHeight = 52.dp

/**
 * Navigazione pura del carosello Hero mobile.
 *
 * Riprende la semantica della TV: avanti con wrap-around, indietro con stop sul
 * primo poster (nessun wrap all'indietro). Estratta dalla UI per essere
 * verificabile senza Compose.
 */
internal object MobileHeroCarouselNavigation {
  fun nextIndex(currentIndex: Int, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    return (currentIndex.coerceIn(0, itemCount - 1) + 1) % itemCount
  }

  fun previousIndex(currentIndex: Int): Int = if (currentIndex > 0) currentIndex - 1 else 0

  fun canScrollToPrevious(currentIndex: Int, itemCount: Int): Boolean =
    itemCount > 1 && currentIndex > 0
}

/**
 * Clear logo originale del titolo, quando TMDB/Cinemeta lo forniscono.
 * `null` (o stringa vuota) significa: fallback al titolo testuale, come in TV.
 */
internal fun MediaItem.heroLogoUrl(): String? =
  logoUrl?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Hero cinematografica mobile, adattata dalla Hero TV 1.0.4.
 *
 * Comportamento:
 * - mostra fino a 9 titoli già alternati Film/Serie dalla pipeline condivisa;
 * - auto-scorrimento ogni 10 s con dissolvenza incrociata di 350 ms;
 * - swipe orizzontale (adattamento touch di LEFT/RIGHT): avanti con wrap-around,
 *   indietro solo se non si è già sul primo poster, con riavvio del timer;
 * - pallini tappabili in basso;
 * - nessun pulsante Play/Dettagli sovrapposto: l'intera Hero apre il Dettaglio;
 * - logo originale se disponibile, altrimenti titolo testuale.
 */
@Composable
fun MobileHeroCarousel(
  items: List<MediaItem>,
  onItemClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  expanded: Boolean = false,
  compact: Boolean = false,
  height: Dp = if (expanded) 420.dp else 330.dp
) {
  val safeItems = remember(items) { items.take(MAX_HERO_ITEMS) }
  if (safeItems.isEmpty()) return

  var currentIndex by remember(safeItems) { mutableIntStateOf(0) }
  var autoScrollRestartToken by remember(safeItems) { mutableLongStateOf(0L) }

  // Il ciclo vive nello stesso effetto: il delay si ripete finché l'utente non
  // interagisce (cambio token = effetto cancellato e riavviato da zero).
  LaunchedEffect(safeItems, autoScrollRestartToken) {
    if (safeItems.size > 1) {
      while (true) {
        delay(MOBILE_HERO_AUTO_SCROLL_DELAY_MS)
        currentIndex = MobileHeroCarouselNavigation.nextIndex(currentIndex, safeItems.size)
      }
    }
  }

  val selectPoster: (Int) -> Unit = { newIndex ->
    currentIndex = newIndex.coerceIn(0, safeItems.lastIndex)
    autoScrollRestartToken += 1
  }

  val currentMedia = safeItems.getOrElse(currentIndex) { safeItems.first() }
  val latestIndex by rememberUpdatedState(currentIndex)
  val latestSize by rememberUpdatedState(safeItems.size)
  val density = LocalDensity.current
  val swipeThresholdPx = with(density) { 48.dp.toPx() }
  var dragAccumulator by remember { mutableFloatStateOf(0f) }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(height)
      .pointerInput(safeItems) {
        detectHorizontalDragGestures(
          onDragStart = { dragAccumulator = 0f },
          onHorizontalDrag = { change, dragAmount ->
            change.consume()
            dragAccumulator += dragAmount
          },
          onDragEnd = {
            when {
              dragAccumulator <= -swipeThresholdPx ->
                selectPoster(MobileHeroCarouselNavigation.nextIndex(latestIndex, latestSize))
              dragAccumulator >= swipeThresholdPx -> {
                if (MobileHeroCarouselNavigation.canScrollToPrevious(latestIndex, latestSize)) {
                  selectPoster(MobileHeroCarouselNavigation.previousIndex(latestIndex))
                }
              }
            }
            dragAccumulator = 0f
          },
          onDragCancel = { dragAccumulator = 0f }
        )
      }
      .clickable { onItemClick(currentMedia) }
  ) {
    AnimatedContent(
      targetState = currentMedia,
      transitionSpec = {
        fadeIn(animationSpec = tween(durationMillis = HERO_CROSSFADE_MS)) togetherWith
          fadeOut(animationSpec = tween(durationMillis = HERO_CROSSFADE_MS))
      },
      label = "MobileHeroBackdropCrossfade"
    ) { media ->
      HeroBackdrop(media = media, modifier = Modifier.fillMaxSize())
    }

    // Vignette a doppio asse: stessa lingua visiva della Hero TV, con il
    // raccordo allo sfondo della schermata in basso.
    val vignetteColor = NovaBackground
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.horizontalGradient(
            colors = listOf(
              vignetteColor.copy(alpha = 0.85f),
              vignetteColor.copy(alpha = 0.5f),
              Color.Transparent
            )
          )
        )
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Transparent,
              Color.Transparent,
              vignetteColor.copy(alpha = 0.55f),
              vignetteColor
            )
          )
        )
    )

    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(
          start = 20.dp,
          // In orizzontale il testo resta nella metà sinistra, come la Hero TV:
          // niente righe lunghissime e backdrop leggibile a destra.
          end = if (compact || expanded) 200.dp else 20.dp,
          bottom = HeroDotsReservedHeight
        )
    ) {
      HeroLogoOrTitle(media = currentMedia, expanded = expanded, compact = compact)

      Spacer(modifier = Modifier.height(10.dp))

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (currentMedia.year > 0) {
          HeroMetadataBadge(text = currentMedia.displayYear)
        }
        currentMedia.ageRating?.let { age ->
          HeroMetadataBadge(text = "$age+")
        }
        HeroMetadataBadge(
          text = if (currentMedia.type == MediaType.SERIE_TV) {
            stringResource(R.string.hero_seasons_count, currentMedia.seasonsCount ?: 1)
          } else {
            currentMedia.formattedDuration
          }
        )
      }

      if (currentMedia.genres.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = currentMedia.genres.take(3).joinToString(" · "),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = currentMedia.synopsis,
        color = NovaTextSecondary,
        fontSize = if (compact) 12.sp else 13.sp,
        lineHeight = if (compact) 16.sp else 18.sp,
        maxLines = if (compact) 2 else 3,
        overflow = TextOverflow.Ellipsis
      )
    }

    if (safeItems.size > 1) {
      Row(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        safeItems.forEachIndexed { index, _ ->
          val isSelected = index == currentIndex
          val indicatorWidth by animateDpAsState(
            targetValue = if (isSelected) 20.dp else 6.dp,
            animationSpec = tween(durationMillis = 300),
            label = "MobileHeroIndicatorWidth"
          )
          val indicatorColor by animateColorAsState(
            targetValue = if (isSelected) NovaCyanBright else Color.White.copy(alpha = 0.35f),
            animationSpec = tween(durationMillis = 300),
            label = "MobileHeroIndicatorColor"
          )
          Box(
            modifier = Modifier
              .height(6.dp)
              .width(indicatorWidth)
              .clip(RoundedCornerShape(50))
              .background(indicatorColor)
              .clickable { selectPoster(index) }
          )
        }
      }
    }
  }
}

@Composable
private fun HeroBackdrop(
  media: MediaItem,
  modifier: Modifier = Modifier
) {
  Box(modifier = modifier) {
    val localBackdropRes = media.backdropRes
    when {
      !media.backdropUrl.isNullOrBlank() -> AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
          .data(media.backdropUrl)
          .crossfade(HERO_CROSSFADE_MS)
          .build(),
        contentDescription = media.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
      )

      localBackdropRes != null && localBackdropRes != R.drawable.banner_dune -> {
        Image(
          painter = painterResource(id = localBackdropRes),
          contentDescription = media.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      }

      else -> Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(Color(0xFF0F172A), NovaBackground)
            )
          )
      )
    }
  }
}

@Composable
private fun HeroLogoOrTitle(
  media: MediaItem,
  expanded: Boolean,
  compact: Boolean
) {
  val shadowStyle = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), blurRadius = 8f)
  )
  val logoUrl = media.heroLogoUrl()

  if (logoUrl != null) {
    SubcomposeAsyncImage(
      model = ImageRequest.Builder(LocalContext.current)
        .data(logoUrl)
        .crossfade(HERO_CROSSFADE_MS)
        .build(),
      contentDescription = media.title,
      contentScale = ContentScale.Fit,
      alignment = Alignment.CenterStart,
      modifier = Modifier
        .heightIn(
          min = if (compact) 28.dp else 36.dp,
          max = when {
            expanded -> 76.dp
            compact -> 48.dp
            else -> 64.dp
          }
        )
        .widthIn(max = if (expanded) 420.dp else if (compact) 240.dp else 280.dp),
      error = {
        HeroTitleText(
          title = media.title,
          expanded = expanded,
          compact = compact,
          style = shadowStyle
        )
      }
    )
  } else {
    HeroTitleText(
      title = media.title,
      expanded = expanded,
      compact = compact,
      style = shadowStyle
    )
  }
}

@Composable
private fun HeroTitleText(
  title: String,
  expanded: Boolean,
  compact: Boolean,
  style: TextStyle
) {
  Text(
    text = title,
    color = NovaTextPrimary,
    fontSize = when {
      expanded -> 36.sp
      compact -> 24.sp
      else -> 30.sp
    },
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 0.5.sp,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    style = style
  )
}

@Composable
private fun HeroMetadataBadge(text: String) {
  Text(
    text = text,
    color = NovaTextPrimary,
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
      .padding(horizontal = 8.dp, vertical = 3.dp)
  )
}

private const val MAX_HERO_ITEMS = 9
