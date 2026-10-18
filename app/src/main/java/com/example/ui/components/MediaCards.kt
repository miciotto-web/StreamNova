package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.annotation.DrawableRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.WatchProgress
import com.example.data.stremio.StremioCatalogMapper
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGold
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay

/** CompositionLocal per il ripristino del focus sull'esatta card all'uscita dal Dettaglio (Punto 3). */
val LocalLastFocusedMediaId = compositionLocalOf<String?> { null }

/**
 * CompositionLocal del target di ripristino ROW-SCOPED: coppia `(rowKey, mediaId)`.
 * Solo la card appartenente alla riga `rowKey` può consumare il target.
 */
val LocalLastFocusedTarget = compositionLocalOf<Pair<String, String>?> { null }

/** CompositionLocal per il token di ripristino che cambia ad ogni tentativo. */
val LocalFocusRestoreToken = compositionLocalOf<Int> { 0 }

/** CompositionLocal per cancellare il target salvato non appena la card riceve il focus. */
val LocalClearLastFocusedMedia = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Componente neutro ed elegante per Android TV in sostituzione definitiva di qualsiasi
 * immagine placeholder generica (es. deserto). Mostra un gradiente scuro moderno, un'icona
 * stilizzata del film/play al centro e il titolo del contenuto in basso (Punto 4).
 */
@Composable
fun MediaPosterFallback(
  title: String?,
  modifier: Modifier = Modifier,
  isTv: Boolean = false
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(
            Color(0xFF1B2333),
            Color(0xFF111724),
            Color(0xFF090D15)
          )
        )
      )
      .padding(horizontal = 8.dp, vertical = 10.dp),
    contentAlignment = Alignment.Center
  ) {
    // Cerchio stilizzato con icona del contenuto al centro
    Box(
      modifier = Modifier
        .size(44.dp)
        .background(NovaCyan.copy(alpha = 0.12f), CircleShape)
        .border(1.dp, NovaCyanBright.copy(alpha = 0.35f), CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.Default.PlayArrow,
        contentDescription = null,
        tint = NovaCyanBright.copy(alpha = 0.85f),
        modifier = Modifier.size(24.dp)
      )
    }

    // Titolo del film/serie in basso per chiara identificazione del poster
    if (!title.isNullOrBlank()) {
      Text(
        text = title,
        color = NovaTextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 4.dp)
      )
    }
  }
}

/**
 * Componente unificato per il caricamento dell'artwork con fallback robusto:
 * 1. Prova prima primaryUrl (es. posterUrl per card verticale, backdropUrl per card orizzontale)
 * 2. Se primaryUrl fallisce o è assente, passa a secondaryUrl (es. backdropUrl / posterUrl TMDB)
 * 3. Se fallisce anche il secondario o entrambi sono vuoti, usa la risorsa locale valida (se != banner_dune)
 *    oppure il componente neutro ed elegante [MediaPosterFallback].
 * 4. NESSUNA immagine generica estranea al film (es. deserto).
 */
@Composable
fun CardMediaImage(
  primaryUrl: String?,
  secondaryUrl: String?,
  @DrawableRes fallbackRes: Int? = null,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop
) {
  val cleanPrimary = StremioCatalogMapper.extractImageUrl(primaryUrl)?.trim()?.takeIf { it.isNotEmpty() }
  val cleanSecondary = StremioCatalogMapper.extractImageUrl(secondaryUrl)?.trim()?.takeIf { it.isNotEmpty() && it != cleanPrimary }

  var primaryFailed by remember(cleanPrimary) { mutableStateOf(false) }

  val targetUrl = when {
    cleanPrimary != null && !primaryFailed -> cleanPrimary
    cleanSecondary != null -> cleanSecondary
    else -> null
  }

  val effectiveFallbackRes = fallbackRes?.takeIf { it != R.drawable.banner_dune }

  if (targetUrl != null) {
    SubcomposeAsyncImage(
      model = ImageRequest.Builder(LocalContext.current)
        .data(targetUrl)
        .crossfade(true)
        .build(),
      contentDescription = contentDescription,
      contentScale = contentScale,
      modifier = modifier,
      onError = {
        if (!primaryFailed && targetUrl == cleanPrimary && cleanSecondary != null) {
          primaryFailed = true
        }
      },
      loading = {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(Color(0xFF1B2333), Color(0xFF0F1420))
              )
            )
        )
      },
      error = {
        if (effectiveFallbackRes != null) {
          Image(
            painter = painterResource(id = effectiveFallbackRes),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          MediaPosterFallback(
            title = contentDescription,
            modifier = Modifier.fillMaxSize()
          )
        }
      }
    )
  } else if (effectiveFallbackRes != null) {
    Image(
      painter = painterResource(id = effectiveFallbackRes),
      contentDescription = contentDescription,
      contentScale = contentScale,
      modifier = modifier
    )
  } else {
    MediaPosterFallback(
      title = contentDescription,
      modifier = modifier
    )
  }
}

/**
 * Pillola del voto TMDB (stella + punteggio) in stile minimal.
 * Pensata per essere ancorata in basso sulla locandina, dove il gradiente
 * scuro garantisce ottima leggibilità senza coprire barre di progresso o titoli.
 */
@Composable
fun PosterRatingBadge(
  rating: Float,
  modifier: Modifier = Modifier,
  compact: Boolean = false
) {
  Row(
    modifier = modifier
      .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
      .padding(horizontal = if (compact) 4.dp else 5.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = Icons.Default.Star,
      contentDescription = "Voto TMDB",
      tint = NovaGold,
      modifier = Modifier.size(if (compact) 9.dp else 11.dp)
    )
    Spacer(modifier = Modifier.width(if (compact) 2.dp else 3.dp))
    Text(
      text = "%.1f".format(rating),
      color = Color.White,
      fontSize = if (compact) 10.sp else 11.sp,
      fontWeight = FontWeight.Bold
    )
  }
}

/**
 * Continua a guardare - Full-bleed 16:9 card con overlay testi e progresso
 * Design: immagine occupa tutta la card, testi sovrapposti in basso a sinistra
 */
@Composable
fun ContinueWatchingCard(
  media: MediaItem,
  onClick: () -> Unit,
  onLongPress: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  focusRequester: FocusRequester? = null,
  rowKey: String? = null
) {
  val fallbackRes = (media.backdropRes ?: media.posterRes)?.takeIf { it != R.drawable.banner_dune }
  val targetMediaId = LocalLastFocusedMediaId.current
  val restoreTarget = LocalLastFocusedTarget.current ?: targetMediaId?.let { FOCUS_ROW_ANY to it }
  val focusRestoreToken = LocalFocusRestoreToken.current
  val clearLastFocusedMedia = LocalClearLastFocusedMedia.current
  val internalFocusRequester = remember { FocusRequester() }
  val cardFocusRequester = focusRequester ?: internalFocusRequester

  // Track whether we've successfully requested focus for the current token
  var focusRequestedToken by remember { mutableStateOf<Int?>(null) }

  // Reset focusRequested when token changes (new restoration attempt)
  LaunchedEffect(focusRestoreToken) {
    if (focusRequestedToken != null && focusRestoreToken != focusRequestedToken) {
      focusRequestedToken = null
    }
  }

  // Ripristino ROW-SCOPED: solo la card della riga salvata nel target richiede il focus.
  LaunchedEffect(targetMediaId, cardFocusRequester) {
    if (matchesFocusRestoreTarget(restoreTarget, rowKey, media.id) && focusRequestedToken == null) {
      try {
        cardFocusRequester.requestFocus()
        // Target consumato immediatamente: nessun'altra riga/frame può rubare il focus.
        clearLastFocusedMedia?.invoke()
      } catch (_: Exception) {
      }
      focusRequestedToken = focusRestoreToken
    }
  }

    TvFocusableBox(
    modifier = modifier
      .width(280.dp)
      .height(158.dp)
      .focusRequester(cardFocusRequester),
    shape = RoundedCornerShape(14.dp),
    focusedScale = 1.05f,
    onClick = onClick,
    onLongPress = onLongPress
  ) { isFocused ->
    // Clear the target only when focus is actually gained
    if (isFocused && focusRequestedToken != null && matchesFocusRestoreTarget(restoreTarget, rowKey, media.id)) {
      clearLastFocusedMedia?.invoke()
      focusRequestedToken = null
    }
    Box(
      modifier = Modifier
        .fillMaxSize()
        .clip(RoundedCornerShape(14.dp))
    ) {
      // Immagine full-bleed 16:9
      CardMediaImage(
        primaryUrl = media.backdropUrl,
        secondaryUrl = media.posterUrl,
        fallbackRes = fallbackRes,
        contentDescription = media.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )

      // Gradiente inferiore per i testi
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = 0.65f),
                Color.Black.copy(alpha = 0.95f)
              ),
              startY = 0f,
              endY = Float.POSITIVE_INFINITY
            )
          )
      )

      // Badge "Xm rimanenti" in alto a destra: SEMPRE dai millisecondi reali salvati dal
      // player (posizione/durata); il runtime TMDB e' solo fallback per record legacy.
      val remainingMins = if (media.currentProgressMs > 0) {
        WatchProgress.remainingMinutes(
          totalDurationMs = media.totalDurationMs,
          resumePositionMs = media.currentProgressMs,
          runtimeMinutes = media.durationMinutes
        )
      } else null
      if (remainingMins != null) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
          Text(
            text = stringResource(R.string.badge_minutes_remaining, remainingMins),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
          )
        }
      }

      // Testi in basso a sinistra
      Column(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .fillMaxWidth()
          .padding(start = 12.dp, bottom = 18.dp, end = 12.dp)
      ) {
        // Riga 1: Stagione ed Episodio (se serie TV)
        val seasonEpisodeText = if (media.type == com.example.data.model.MediaType.SERIE_TV && media.lastWatchedEpisode != null) {
          "S${media.lastWatchedSeason ?: 1} E${media.lastWatchedEpisode}"
        } else {
          media.displayYear
        }
        if (seasonEpisodeText.isNotEmpty()) {
          Text(
            text = seasonEpisodeText,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
          )
        }

        // Riga 2: Titolo del Media / Serie
        Text(
          text = media.title,
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )

        // Riga 3: Titolo dell'episodio (se disponibile)
        val episodeTitle = remember(media, media.lastWatchedSeason, media.lastWatchedEpisode) {
          media.episodes.find { ep ->
            ep.seasonNumber == media.lastWatchedSeason && ep.episodeNumber == media.lastWatchedEpisode
          }?.title
        }
        if (episodeTitle != null) {
          Text(
            text = episodeTitle,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      // Barra di progresso sottile in basso
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
          .height(3.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(Color(0x55000000))
      ) {
        LinearProgressIndicator(
          progress = { media.progressFraction },
          modifier = Modifier
            .fillMaxWidth()
            .height(3.dp),
          color = NovaCyan,
          trackColor = Color(0x3300A3FF)
        )
      }
    }
  }
}

/**
 * Card Orizzontale Backdrop (16:9 con backdrop TMDB)
 *
 * Restyle premium coerente con [PosterMediaCard] (alone Neon + bordo luminescente +
 * riflesso di vetro), con le stesse garanzie di performance: i valori animati sono letti
 * solo nelle lambda di disegno (graphicsLayer/drawBehind/drawWithContent).
 */
@Composable
fun StandardMediaCard(
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  focusRequester: FocusRequester? = null,
  rowKey: String? = null
) {
  var isFocused by remember { mutableStateOf(false) }
  val targetMediaId = LocalLastFocusedMediaId.current
  val restoreTarget = LocalLastFocusedTarget.current ?: targetMediaId?.let { FOCUS_ROW_ANY to it }
  val focusRestoreToken = LocalFocusRestoreToken.current
  val clearLastFocusedMedia = LocalClearLastFocusedMedia.current
  val internalFocusRequester = remember { FocusRequester() }
  val cardFocusRequester = focusRequester ?: internalFocusRequester

  // Track whether we've successfully requested focus for the current token
  var focusRequestedToken by remember { mutableStateOf<Int?>(null) }

  // Reset focusRequested when token changes (new restoration attempt)
  LaunchedEffect(focusRestoreToken) {
    if (focusRequestedToken != null && focusRestoreToken != focusRequestedToken) {
      focusRequestedToken = null
    }
  }

  // Ripristino ROW-SCOPED: solo la card della riga salvata nel target richiede il focus.
  LaunchedEffect(targetMediaId, cardFocusRequester) {
    if (matchesFocusRestoreTarget(restoreTarget, rowKey, media.id) && focusRequestedToken == null) {
      try {
        cardFocusRequester.requestFocus()
        // Target consumato immediatamente: nessun'altra riga/frame può rubare il focus.
        clearLastFocusedMedia?.invoke()
      } catch (_: Exception) {
      }
      focusRequestedToken = focusRestoreToken
    }
  }

  val scaleState = animateFloatAsState(
    targetValue = if (isFocused) 1.06f else 1f,
    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
    label = "standardCardScale"
  )
  val glowState = animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
    label = "standardCardGlow"
  )
  val specularState = animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
    label = "standardCardSpecular"
  )
  val shape = RoundedCornerShape(NeonCardCorner)

  Column(
    modifier = modifier
      .width(220.dp)
      .focusRequester(cardFocusRequester)
      // La card in focus deve coprire le card vicine
      .zIndex(if (isFocused) 10f else 1f)
      .graphicsLayer {
        val s = scaleState.value
        scaleX = s
        scaleY = s
      }
      .onFocusChanged {
        isFocused = it.isFocused
        // Clear the target only when focus is actually gained
        if (it.isFocused && focusRequestedToken != null && matchesFocusRestoreTarget(restoreTarget, rowKey, media.id)) {
          clearLastFocusedMedia?.invoke()
          focusRequestedToken = null
        }
      }
      .onKeyEvent { keyEvent ->
        val isConfirm = keyEvent.key == Key.Enter ||
          keyEvent.key == Key.NumPadEnter ||
          keyEvent.key == Key.DirectionCenter
        if (isConfirm && keyEvent.type == KeyEventType.KeyUp) {
          onClick()
          true
        } else {
          false
        }
      }
      .clickable { onClick() }
      // Alone Neon PRIMA del clip: si estende oltre i bordi della card.
      .drawBehind {
        drawNeonGlow(
          cornerRadiusPx = NeonCardCorner.toPx(),
          color = NovaCyanBright,
          spreadPx = NeonCardGlowSpread.toPx(),
          intensity = glowState.value
        )
      }
      .clip(shape)
      .background(NovaCardBg)
      // Riflesso di vetro SOPRA l'immagine + bordo nitido, dentro il clip.
      .drawWithContent {
        drawContent()
        drawSpecularHighlight(intensity = specularState.value)
        drawNeonBorder(
          cornerRadiusPx = NeonCardCorner.toPx(),
          color = NovaCyanBright,
          widthPx = NeonCardBorderWidth.toPx(),
          intensity = glowState.value
        )
      }
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(124.dp)
    ) {
      val fallbackRes = (media.backdropRes ?: media.posterRes)?.takeIf { it != R.drawable.banner_dune }
      CardMediaImage(
        primaryUrl = media.backdropUrl,
        secondaryUrl = media.posterUrl,
        fallbackRes = fallbackRes,
        contentDescription = media.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )

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

      // TMDB Rating Badge (in basso a destra, sul gradiente scuro)
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(6.dp)
      ) {
        PosterRatingBadge(rating = media.rating)
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
        if (media.year > 0) {
          Text(
            text = media.displayYear,
            color = NovaTextMuted,
            fontSize = 11.sp
          )
          Text(
            text = "•",
            color = NovaTextMuted,
            fontSize = 10.sp
          )
        }
        Text(
          text = if (media.seasonsCount != null) stringResource(R.string.media_card_seasons_abbr, media.seasonsCount ?: 1) else media.formattedDuration,
          color = NovaTextMuted,
          fontSize = 11.sp
        )
        Text(
          text = "•",
          color = NovaTextMuted,
          fontSize = 10.sp
        )
        Text(
          text = media.genres.firstOrNull() ?: stringResource(if (media.type == MediaType.SERIE_TV) R.string.nav_tv_series else R.string.nav_movies),
          color = NovaTextSecondary,
          fontSize = 11.sp,
          maxLines = 1
        )
      }
    }
  }
}

/**
 * Locandina verticale (formato poster 2:3) con metadati esterni sotto il poster.
 *
 * Restyle premium "Neon Glow + Glass Specular Highlight":
 * - Animazioni GPU (scale, alone, riflesso) guidate da [animateFloatAsState]; i valori
 *   animati sono letti DENTRO graphicsLayer/drawBehind/drawWithContent, quindi ogni
 *   frame invalida solo la fase di disegno: zero ricomposizioni.
 * - Alone Neon ciano diffuso attorno al poster quando è a fuoco (~10dp oltre i bordi).
 * - Bordo nitido luminescente 2.5dp in [NovaCyanBright] sul perimetro.
 * - Riflesso "di vetro" diagonale semitrasparente sul terzo superiore dell'immagine.
 * - Tutto clippato a [RoundedCornerShape] 14dp: immagine, riflesso e bordo non sbordano.
 *
 * @param cardWidth larghezza del poster in dp (altezza calcolata = 1.5x, rapporto 2:3).
 * @param allowBackdropFallback se `false` la card NON usa MAI il backdrop orizzontale (16:9)
 *   come sostituto della locandina: senza `poster_path` viene mostrato il placeholder neutro.
 * @param confirmFocusRestore attiva il protocollo di ripristino con CONFERMA del focus reale
 *   (usato dalla griglia Provider). Con `false` resta il comportamento storico delle altre
 *   schermate (Home, Film, Serie TV, Cerca, Preferiti, Dettaglio).
 */
@Composable
fun PosterMediaCard(
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  focusRequester: FocusRequester? = null,
  cardWidth: Dp = 150.dp,
  allowBackdropFallback: Boolean = true,
  confirmFocusRestore: Boolean = false,
  rowKey: String? = null
) {
  var isFocused by remember { mutableStateOf(false) }
  val targetMediaId = LocalLastFocusedMediaId.current
  val restoreTarget = LocalLastFocusedTarget.current ?: targetMediaId?.let { FOCUS_ROW_ANY to it }
  val focusRestoreToken = LocalFocusRestoreToken.current
  val clearLastFocusedMedia = LocalClearLastFocusedMedia.current
  val internalFocusRequester = remember { FocusRequester() }
  val cardFocusRequester = focusRequester ?: internalFocusRequester

  // Track whether we've requested focus for the current token
  var focusRequestedToken by remember { mutableStateOf<Int?>(null) }

  // Reset focusRequested when token changes (new restoration attempt)
  LaunchedEffect(focusRestoreToken) {
    if (focusRequestedToken != null && focusRestoreToken != focusRequestedToken) {
      focusRequestedToken = null
    }
  }

  // Percorso storico (Home, Film, Serie TV, Cerca, Preferiti, Dettaglio): un solo
  // tentativo quando la card corrisponde al target, condizionato anche alla riga
  // (rowKey) quando il target è row-scoped.
  LaunchedEffect(targetMediaId, cardFocusRequester) {
    if (!confirmFocusRestore &&
      matchesFocusRestoreTarget(restoreTarget, rowKey, media.id) && focusRequestedToken == null
    ) {
      try {
        cardFocusRequester.requestFocus()
        // Target consumato immediatamente: nessun'altra riga/frame può rubare il focus.
        clearLastFocusedMedia?.invoke()
      } catch (_: Exception) {
      }
      focusRequestedToken = focusRestoreToken
    }
  }

  // Conferma dell'acquisizione reale del focus (protocollo Provider): aggiornata dal
  // Modifier.onFocusChanged della card, letta dal tentativo di ripristino qui sotto.
  val restoreConfirmation = remember { FocusRestoreConfirmation() }

  if (confirmFocusRestore) {
    // Protocollo Provider: la richiesta è ripetuta (tentativi limitati) finché il focus non è
    // CONFERMATO da onFocusChanged; il target viene rilasciato solo dopo la conferma.
    //
    // La chiave è il TOKEN di ripristino, non l'id: un nuovo evento di ripristino riattiva il
    // tentativo anche quando `targetMediaId` è rimasto identico (ritorni consecutivi dalla
    // stessa locandina). L'id è solo la condizione di corrispondenza, letta a ogni esecuzione.
    val currentTargetMediaId by rememberUpdatedState(targetMediaId)
    var restoreAttemptToken by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(focusRestoreToken, cardFocusRequester) {
      if (!matchesFocusRestoreTarget(restoreTarget, rowKey, media.id) ||
        !shouldAttemptFocusRestore(
          cardMediaId = media.id,
          targetMediaId = currentTargetMediaId,
          restoreToken = focusRestoreToken,
          attemptedToken = restoreAttemptToken
        )
      ) {
        return@LaunchedEffect
      }
      restoreAttemptToken = focusRestoreToken
      // `isFocused` copre il caso in cui la griglia ha già ripristinato il focus sulla card:
      // la richiesta non genererebbe alcun onFocusChanged e la conferma resterebbe assente.
      val confirmed = isFocused || cardFocusRequester.requestFocusConfirmed(restoreConfirmation)
      if (confirmed) {
        clearLastFocusedMedia?.invoke()
      }
    }
  }

  // Stato di scala: 1.0 -> 1.06 al focus, con easing GPU-friendly.
  val scaleState = animateFloatAsState(
    targetValue = if (isFocused) 1.06f else 1f,
    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
    label = "posterCardScale"
  )
  // Intensità dell'alone Neon (0 = spento, 1 = massimo).
  val glowState = animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
    label = "posterCardGlow"
  )
  // Entrata delicata del riflesso speculare.
  val specularState = animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
    label = "posterCardSpecular"
  )
  val shape = RoundedCornerShape(NeonCardCorner)

  Column(
    modifier = Modifier
      .width(cardWidth)
      .then(modifier)
      .focusRequester(cardFocusRequester)
      // La card in focus deve coprire le card vicine
      .zIndex(if (isFocused) 10f else 1f)
      .graphicsLayer {
        val s = scaleState.value
        scaleX = s
        scaleY = s
      }
      .onFocusChanged {
        isFocused = it.isFocused
        if (confirmFocusRestore) restoreConfirmation.onFocusChanged(it.isFocused)
        // Clear the target only when focus is actually gained
        if (it.isFocused && focusRequestedToken != null && matchesFocusRestoreTarget(restoreTarget, rowKey, media.id)) {
          clearLastFocusedMedia?.invoke()
          focusRequestedToken = null
        }
      }
      // OK/Enter della tastiera D-pad -> onClick (consumato prima di clickable)
      .onKeyEvent { keyEvent ->
        val isConfirm = keyEvent.key == Key.Enter ||
          keyEvent.key == Key.NumPadEnter ||
          keyEvent.key == Key.DirectionCenter
        if (isConfirm && keyEvent.type == KeyEventType.KeyUp) {
          onClick()
          true
        } else {
          false
        }
      }
      .clickable { onClick() }
  ) {
    // 1) Poster verticale 2:3 (cardWidth x cardWidth * 1.5): alone Neon, clip arrotondato,
    // riflesso speculare e bordo.
    Box(
      modifier = Modifier
        .width(cardWidth)
        .height(cardWidth * 1.5f)
        // Alone disegnato PRIMA del clip: si estende oltre i bordi della card.
        .drawBehind {
          drawNeonGlow(
            cornerRadiusPx = NeonCardCorner.toPx(),
            color = NovaCyanBright,
            spreadPx = NeonCardGlowSpread.toPx(),
            intensity = glowState.value
          )
        }
        .clip(shape)
        .background(NovaCardBg)
        // Riflesso di vetro SOPRA l'immagine (drawContent) + bordo nitido, dentro il clip.
        .drawWithContent {
          drawContent()
          drawSpecularHighlight(intensity = specularState.value)
          drawNeonBorder(
            cornerRadiusPx = NeonCardCorner.toPx(),
            color = NovaCyanBright,
            widthPx = NeonCardBorderWidth.toPx(),
            intensity = glowState.value
          )
        }
    ) {
      val fallbackRes = (media.posterRes ?: media.backdropRes)?.takeIf { it != R.drawable.banner_dune }
      CardMediaImage(
        primaryUrl = media.posterUrl,
        secondaryUrl = if (allowBackdropFallback) media.backdropUrl else null,
        fallbackRes = fallbackRes,
        contentDescription = media.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )

      // Velo gradiente minimo in basso (nessun badge interno: la locandina resta pulita)
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(40.dp)
          .align(Alignment.BottomCenter)
          .background(
            Brush.verticalGradient(
              listOf(Color.Transparent, Color(0x40000000))
            )
          )
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 3) Titolo
    Text(
      text = media.title,
      color = Color.White,
      fontSize = 15.sp,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )

    // 4) Anno
    if (media.year > 0) {
      Text(
        text = media.displayYear,
        color = Color(0xFFAAAAAA),
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/** Geometria condivisa del restyle Neon Glow delle poster card. */
internal val NeonCardCorner = 14.dp
internal val NeonCardGlowSpread = 10.dp
internal val NeonCardBorderWidth = 2.5.dp

/**
 * Alone Neon diffuso: layer di rounded-rect concentrici con alpha decrescente,
 * disegnati anche oltre i bordi. Va richiamato in un [drawBehind] NON clippato: la somma
 * dei layer produce una sfumatura morbida che si estende di [spreadPx] oltre la card.
 */
internal fun DrawScope.drawNeonGlow(
  cornerRadiusPx: Float,
  color: Color,
  spreadPx: Float,
  intensity: Float
) {
  if (intensity <= 0.01f || spreadPx <= 0f) return
  val layers = 14
  val perLayerAlpha = 0.04f * intensity
  for (i in layers downTo 1) {
    val t = i.toFloat() / layers
    val inflate = spreadPx * t
    drawRoundRect(
      color = color.copy(alpha = perLayerAlpha),
      topLeft = Offset(-inflate, -inflate),
      size = Size(size.width + inflate * 2f, size.height + inflate * 2f),
      cornerRadius = CornerRadius(cornerRadiusPx + inflate, cornerRadiusPx + inflate)
    )
  }
}

/**
 * Riflesso di vetro (specular highlight): gradiente lineare diagonale bianco sul terzo
 * superiore dell'immagine. Disegnato SOPRA il contenuto, dentro il clip arrotondato.
 */
internal fun DrawScope.drawSpecularHighlight(intensity: Float) {
  if (intensity <= 0.01f) return
  val brush = Brush.linearGradient(
    colors = listOf(
      Color.White.copy(alpha = 0.22f * intensity),
      Color.White.copy(alpha = 0.05f * intensity),
      Color.Transparent
    ),
    start = Offset(0f, 0f),
    end = Offset(size.width, size.height * 0.45f)
  )
  drawRect(brush = brush)
}

/** Bordo nitido luminescente lungo il perimetro, disegnato dentro il clip. */
internal fun DrawScope.drawNeonBorder(
  cornerRadiusPx: Float,
  color: Color,
  widthPx: Float,
  intensity: Float
) {
  if (intensity <= 0.01f || widthPx <= 0f) return
  val inset = widthPx / 2f
  val innerRadius = (cornerRadiusPx - inset).coerceAtLeast(0f)
  drawRoundRect(
    color = color.copy(alpha = intensity),
    topLeft = Offset(inset, inset),
    size = Size(
      (size.width - widthPx).coerceAtLeast(0f),
      (size.height - widthPx).coerceAtLeast(0f)
    ),
    cornerRadius = CornerRadius(innerRadius, innerRadius),
    style = Stroke(width = widthPx)
  )
}
