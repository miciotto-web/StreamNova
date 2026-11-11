package com.aistudio.streamnova.mobile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aistudio.streamnova.mobile.R
import com.example.core.designsystem.R as DesignSystemR
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.ContinueWatchingLayout
import com.example.data.repository.ContinueWatchingRowItem
import com.example.ui.components.NeonCardBorderWidth
import com.example.ui.components.NeonCardCorner
import com.example.ui.components.ProviderBranding
import com.example.ui.components.StreamingProvider
import com.example.ui.components.drawNeonGlow
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import java.util.Locale

/**
 * Top bar mobile: branding ufficiale (Home) oppure titolo, con accesso
 * impostazioni e — solo in modalità Debrid — accesso alla schermata Add-on.
 */
@Composable
fun MobileTopBar(
  title: String?,
  onSettings: (() -> Unit)?,
  onAddons: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (title == null) {
      // Stesso branding della sidebar TV: simbolo ufficiale seguito dalla
      // scritta ufficiale (asset originali, nessun logo ricomposto).
      Image(
        painter = painterResource(id = DesignSystemR.drawable.streamnova_icon),
        contentDescription = stringResource(R.string.app_name),
        modifier = Modifier.size(36.dp),
        contentScale = ContentScale.Fit
      )
      Spacer(modifier = Modifier.width(10.dp))
      Image(
        painter = painterResource(id = DesignSystemR.drawable.streamnova_text),
        contentDescription = stringResource(R.string.app_name),
        modifier = Modifier.height(22.dp),
        contentScale = ContentScale.Fit
      )
    } else {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold
      )
    }
    Spacer(modifier = Modifier.weight(1f))
    if (onAddons != null) {
      IconButton(onClick = onAddons) {
        Icon(
          imageVector = Icons.Default.Extension,
          contentDescription = stringResource(R.string.nav_addons),
          tint = NovaTextSecondary
        )
      }
    }
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
    // Due righe sempre riservate: la LazyRow misura solo le card visibili,
    // quindi un titolo che passa da una a due righe durante lo scroll
    // cambierebbe l'altezza dell'intera riga e sposterebbe quelle sottostanti.
    Text(
      text = media.title,
      color = NovaTextPrimary,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 2,
      minLines = 2,
      overflow = TextOverflow.Ellipsis
    )
    Text(
      text = media.shortMetadata(),
      color = NovaTextMuted,
      fontSize = 11.sp,
      maxLines = 1,
      minLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * Card «Continua a guardare» con i tre layout della TV 1.0.7:
 *  - [ContinueWatchingLayout.CARD] → 16:9 full-bleed, testi sovrapposti (storico);
 *  - [ContinueWatchingLayout.WIDE] → card larga, immagine a sinistra e testi a destra;
 *  - [ContinueWatchingLayout.POSTER] → locandina 2:3 con testi sovrapposti in basso.
 *
 * [artworkUrl] è l'immagine già scelta dal policy (miniatura episodio, backdrop o
 * locandina) e [blurArtwork] è già deciso dal policy (solo episodi non ancora visti):
 * la card non replica nessuna regola delle preferenze.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileContinueCard(
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  layout: ContinueWatchingLayout = ContinueWatchingLayout.CARD,
  artworkUrl: String? = null,
  blurArtwork: Boolean = false
) {
  val cardWidth = when (layout) {
    ContinueWatchingLayout.CARD -> 230.dp
    ContinueWatchingLayout.WIDE -> 330.dp
    ContinueWatchingLayout.POSTER -> 150.dp
  }
  val cardHeight = when (layout) {
    ContinueWatchingLayout.CARD,
    ContinueWatchingLayout.WIDE -> 130.dp
    ContinueWatchingLayout.POSTER -> 225.dp
  }
  Box(
    modifier = modifier
      .width(cardWidth)
      .height(cardHeight)
      .clip(RoundedCornerShape(12.dp))
      .background(NovaCardBg)
      .combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick
      )
  ) {
    when (layout) {
      ContinueWatchingLayout.CARD -> ContinueWatchingCardLayout(
        media = media,
        artworkUrl = artworkUrl,
        blurArtwork = blurArtwork
      )
      ContinueWatchingLayout.WIDE -> ContinueWatchingWideLayout(
        media = media,
        artworkUrl = artworkUrl,
        blurArtwork = blurArtwork
      )
      ContinueWatchingLayout.POSTER -> ContinueWatchingPosterLayout(
        media = media,
        artworkUrl = artworkUrl,
        blurArtwork = blurArtwork
      )
    }
  }
}

/** Layout «Scheda»: immagine full-bleed e testi sovrapposti in basso. */
@Composable
private fun ContinueWatchingCardLayout(
  media: MediaItem,
  artworkUrl: String?,
  blurArtwork: Boolean
) {
  Box(modifier = Modifier.fillMaxSize()) {
    ContinueWatchingArtwork(
      media = media,
      artworkUrl = artworkUrl,
      blurArtwork = blurArtwork,
      modifier = Modifier.fillMaxSize()
    )
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Transparent,
              Color.Transparent,
              Color.Black.copy(alpha = 0.62f),
              Color.Black.copy(alpha = 0.94f)
            )
          )
        )
    )
    ContinueWatchingRemainingBadge(
      media = media,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(8.dp)
    )
    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(start = 12.dp, end = 12.dp, bottom = 18.dp)
    ) {
      ContinueWatchingTextBlock(media = media, onDarkArtwork = true)
    }
    ContinueWatchingProgressBar(
      media = media,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
    )
  }
}

/** Layout «Larga»: immagine a sinistra, pannello testi a destra. */
@Composable
private fun ContinueWatchingWideLayout(
  media: MediaItem,
  artworkUrl: String?,
  blurArtwork: Boolean
) {
  Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.width(130.dp).fillMaxHeight()) {
        ContinueWatchingArtwork(
          media = media,
          artworkUrl = artworkUrl,
          blurArtwork = blurArtwork,
          modifier = Modifier.fillMaxSize()
        )
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.horizontalGradient(
                listOf(Color.Transparent, NovaCardBg.copy(alpha = 0.85f))
              )
            )
        )
        ContinueWatchingRemainingBadge(
          media = media,
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(8.dp)
        )
      }
      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 18.dp)
      ) {
        ContinueWatchingTextBlock(media = media, onDarkArtwork = false)
      }
    }
    ContinueWatchingProgressBar(
      media = media,
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(start = 130.dp, end = 12.dp, bottom = 8.dp)
    )
  }
}

/** Layout «Locandina»: poster 2:3 con testi sovrapposti in basso. */
@Composable
private fun ContinueWatchingPosterLayout(
  media: MediaItem,
  artworkUrl: String?,
  blurArtwork: Boolean
) {
  Box(modifier = Modifier.fillMaxSize()) {
    ContinueWatchingArtwork(
      media = media,
      artworkUrl = artworkUrl,
      blurArtwork = blurArtwork,
      modifier = Modifier.fillMaxSize()
    )
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Transparent,
              Color.Transparent,
              Color.Black.copy(alpha = 0.72f),
              Color.Black.copy(alpha = 0.96f)
            )
          )
        )
    )
    ContinueWatchingRemainingBadge(
      media = media,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(8.dp)
    )
    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(start = 10.dp, end = 10.dp, bottom = 18.dp)
    ) {
      ContinueWatchingTextBlock(media = media, onDarkArtwork = true)
    }
    ContinueWatchingProgressBar(
      media = media,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
    )
  }
}

/** Riga episodio/anno, titolo e titolo della puntata: identica nei tre layout. */
@Composable
private fun ContinueWatchingTextBlock(
  media: MediaItem,
  onDarkArtwork: Boolean
) {
  val primaryColor = if (onDarkArtwork) Color.White else NovaTextPrimary
  val secondaryColor = if (onDarkArtwork) Color.White.copy(alpha = 0.75f) else NovaTextSecondary
  val episodeLabel = media.continueEpisodeLabel()
  if (episodeLabel.isNotEmpty()) {
    Text(
      text = episodeLabel,
      color = if (onDarkArtwork) Color.White.copy(alpha = 0.9f) else NovaCyanBright,
      fontSize = 10.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1
    )
  }
  Text(
    text = media.title,
    color = primaryColor,
    fontSize = if (onDarkArtwork) 14.sp else 15.sp,
    fontWeight = FontWeight.Bold,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis
  )
  media.continueEpisodeTitle()?.let { episodeTitle ->
    Text(
      text = episodeTitle,
      color = secondaryColor,
      fontSize = 10.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * Immagine della card: URL deciso dal policy, con fallback backdrop → poster.
 * La sfocatura è calcolata sulla bitmap (SpoilerBlurredImage), non con
 * `Modifier.blur`, così funziona anche su Android 7-11 (minSdk 24).
 */
@Composable
private fun ContinueWatchingArtwork(
  media: MediaItem,
  artworkUrl: String?,
  blurArtwork: Boolean,
  modifier: Modifier = Modifier
) {
  val primary: Any? = artworkUrl?.takeIf { it.isNotBlank() } ?: media.backdropModel()
  val secondary: Any? = media.posterModel()
  var primaryFailed by remember(primary) { mutableStateOf(false) }
  val target = if (!primaryFailed || secondary == primary) primary else secondary
  val tryFallback = {
    if (!primaryFailed && primary != null && secondary != null && secondary != primary) {
      primaryFailed = true
    }
  }
  if (target == null) {
    ContinueWatchingArtworkPlaceholder(modifier)
    return
  }
  if (blurArtwork) {
    SpoilerBlurredImage(
      data = target,
      contentDescription = media.title,
      modifier = modifier,
      contentScale = ContentScale.Crop,
      onError = tryFallback,
      loading = { ContinueWatchingArtworkPlaceholder(Modifier.fillMaxSize()) },
      error = { ContinueWatchingArtworkPlaceholder(Modifier.fillMaxSize()) }
    )
  } else {
    AsyncImage(
      model = target,
      contentDescription = media.title,
      contentScale = ContentScale.Crop,
      modifier = modifier,
      onError = { tryFallback() }
    )
  }
}

@Composable
private fun ContinueWatchingArtworkPlaceholder(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.background(
      Brush.verticalGradient(listOf(Color(0xFF1B2333), Color(0xFF0F1420)))
    )
  )
}

/** Badge «Xm rimanenti»: solo con un progresso reale e con tempo residuo. */
@Composable
private fun ContinueWatchingRemainingBadge(
  media: MediaItem,
  modifier: Modifier = Modifier
) {
  val remaining = media.continueRemainingMinutes() ?: return
  Box(
    modifier = modifier
      .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
      .padding(horizontal = 7.dp, vertical = 3.dp)
  ) {
    Text(
      text = stringResource(R.string.home_continue_remaining_minutes, remaining),
      color = Color.White,
      fontSize = 10.sp,
      fontWeight = FontWeight.Medium,
      maxLines = 1
    )
  }
}

@Composable
private fun ContinueWatchingProgressBar(
  media: MediaItem,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
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

/** Etichetta «S1 E2» (serie) oppure anno (film). */
private fun MediaItem.continueEpisodeLabel(): String =
  if (type == MediaType.SERIE_TV && lastWatchedEpisode != null) {
    "S${lastWatchedSeason ?: 1} E$lastWatchedEpisode"
  } else {
    displayYear
  }

/** Titolo della puntata di ripresa, solo se i dati della stagione sono disponibili. */
private fun MediaItem.continueEpisodeTitle(): String? =
  episodes.firstOrNull {
    it.seasonNumber == lastWatchedSeason && it.episodeNumber == lastWatchedEpisode
  }?.title?.takeIf { it.isNotBlank() }

/** Minuti rimanenti dai millisecondi reali; null quando non c'è nulla da riprendere. */
private fun MediaItem.continueRemainingMinutes(): Int? {
  if (currentProgressMs <= 0L) return null
  val remainingMs = (totalDurationMs - currentProgressMs).coerceAtLeast(0L)
  return (remainingMs / 60_000L).toInt().takeIf { it > 0 }
}

/** Stile delle card usato dalle righe Home, come le card della Home TV. */
enum class MobileRowStyle { POSTER, CONTINUE, TOP_10 }

/**
 * Riga orizzontale titolata.
 *
 * [badge] è l'attribuzione mostrata in ciano dopo il titolo, identica alla
 * `CarouselHeader` TV (es. "Seleziona Provider", "Novità dell'anno").
 */
@Composable
fun MobileSectionRow(
  title: String,
  items: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  badge: String? = null,
  style: MobileRowStyle = MobileRowStyle.POSTER,
  onContinueClick: ((MediaItem) -> Unit)? = null,
  onContinueLongClick: ((MediaItem) -> Unit)? = null,
  continueWatchingCards: List<ContinueWatchingRowItem> = emptyList(),
  continueWatchingLayout: ContinueWatchingLayout = ContinueWatchingLayout.CARD
) {
  if (items.isEmpty()) return
  val continueCardsById = remember(continueWatchingCards) {
    continueWatchingCards.associateBy { it.media.id }
  }
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
      )
      if (!badge.isNullOrBlank()) {
        Text(
          text = "• $badge",
          color = NovaCyanBright,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    LazyRow(
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      itemsIndexed(items, key = { _, media -> media.id }) { index, media ->
        when (style) {
          MobileRowStyle.POSTER -> MobilePosterCard(
            media = media,
            onClick = { onMediaClick(media) },
            modifier = Modifier.width(132.dp)
          )

          MobileRowStyle.CONTINUE -> {
            val card = continueCardsById[media.id]
            MobileContinueCard(
              media = media,
              onClick = { (onContinueClick ?: onMediaClick)(media) },
              onLongClick = onContinueLongClick?.let { callback -> { callback(media) } },
              layout = continueWatchingLayout,
              artworkUrl = card?.artworkUrl,
              blurArtwork = card?.blurArtwork == true
            )
          }

          MobileRowStyle.TOP_10 -> MobileTop10Card(
            rank = index + 1,
            media = media,
            onClick = { onMediaClick(media) }
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(14.dp))
  }
}

/**
 * Card classificata Top 10: numero gigante + poster, come la card TV.
 * Il numero non è un elemento interattivo separato.
 */
@Composable
fun MobileTop10Card(
  rank: Int,
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .clickable(onClick = onClick)
      .padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = rank.toString(),
      color = Color(0xFF2C394E),
      fontSize = 56.sp,
      fontWeight = FontWeight.Black,
      maxLines = 1,
      softWrap = false,
      textAlign = TextAlign.End,
      modifier = Modifier.width(if (rank >= 10) 62.dp else 32.dp)
    )
    Spacer(modifier = Modifier.width(4.dp))
    Column(modifier = Modifier.width(96.dp)) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(2f / 3f)
          .drawBehind {
            drawNeonGlow(
              cornerRadiusPx = NeonCardCorner.toPx(),
              color = NovaCyan,
              spreadPx = 6.dp.toPx(),
              intensity = 0.24f
            )
          }
          .clip(RoundedCornerShape(NeonCardCorner))
          .background(NovaCardBg)
          .border(
            NeonCardBorderWidth / 3,
            NovaCyan.copy(alpha = 0.18f),
            RoundedCornerShape(NeonCardCorner)
          )
      ) {
        MediaArtwork(
          media = media,
          preferPoster = true,
          modifier = Modifier.fillMaxSize()
        )
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
      Spacer(modifier = Modifier.height(5.dp))
      // Come nella card poster: titolo e anno occupano sempre le stesse
      // righe, così l'altezza della riga non dipende dalle card visibili.
      Text(
        text = media.title,
        color = NovaTextPrimary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        minLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = media.displayYear,
        color = NovaTextMuted,
        fontSize = 10.sp,
        maxLines = 1,
        minLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/**
 * Card provider mobile: stesso wordmark, stesso gradiente e stessa geometria
 * 16:9 della card TV, ridimensionata per il touch.
 */
@Composable
fun MobileProviderCard(
  provider: StreamingProvider,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val shape = RoundedCornerShape(12.dp)
  Box(
    modifier = modifier
      .width(150.dp)
      .height(84.dp)
      .clip(shape)
      .background(ProviderBranding.background(provider.tmdbProviderId))
      .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    val logoRes = ProviderBranding.logoRes(provider.tmdbProviderId)
    val logoModifier = Modifier
      .fillMaxWidth(0.70f)
      .height(ProviderBranding.logoHeight(provider.tmdbProviderId))
    when {
      logoRes != null -> Image(
        painter = painterResource(id = logoRes),
        contentDescription = provider.name,
        contentScale = ContentScale.Fit,
        modifier = logoModifier
      )

      !provider.logoUrl.isNullOrBlank() -> AsyncImage(
        model = provider.logoUrl,
        contentDescription = provider.name,
        contentScale = ContentScale.Fit,
        modifier = logoModifier
      )

      else -> Text(
        text = provider.name,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
fun MobileEmptyState(
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null
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
    if (actionLabel != null && onAction != null) {
      Spacer(modifier = Modifier.height(16.dp))
      Button(
        onClick = onAction,
        colors = ButtonDefaults.buttonColors(
          containerColor = NovaCyan,
          contentColor = Color.Black
        )
      ) {
        Text(actionLabel, fontWeight = FontWeight.Bold)
      }
    }
  }
}

/** Stato di caricamento condiviso da Home e cataloghi. */
@Composable
fun MobileLoadingState(
  message: String,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp, vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    CircularProgressIndicator(color = NovaCyan)
    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = message,
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
