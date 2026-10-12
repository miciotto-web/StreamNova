package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.stremio.StremioCatalogMapper
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextMuted

/**
 * Spaziatura orizzontale della riga Top 10: garantisce che ogni coppia [Numero + Poster]
 * risulti chiaramente separata dalla coppia successiva, evitando confusioni ottiche.
 */
internal val Top10RowSpacing = 32.dp

/**
 * Geometria della card Top 10.
 * Il numero gigante stilizzato e il poster formano una coppia coesa:
 * il numero è affiancato al proprio poster con un gap compatto ([Top10RankGapWidth]),
 * mentre la separazione tra coppie consecutive nella LazyRow è gestita da [Top10RowSpacing].
 */
private val Top10RankGapWidth = 8.dp
private val Top10PosterWidth = 148.dp
private val Top10PosterHeight = 220.dp
/** Altezza del blocco numero + poster (il titolo sotto è aggiunto a parte). */
private val Top10CardHeight = 235.dp

/**
 * Card stile "TOP 10" con numero gigante in stile TV streaming (es. Netflix/HBO style)
 * Il numero gigante è a sinistra e la locandina è allineata a destra.
 *
 * Il poster contiene SOLO l'artwork: anno, voto (stellina) e titolo sono stati
 * rimossi dagli overlay. Titolo e anno vivono in una colonna dedicata SOTTO la
 * locandina (titolo `titleSmall` max 2 righe con ellissi; anno `labelSmall` attenuato),
 * così la locandina resta pulita.
 *
 * Il numero di classifica è un elemento puramente grafico: non è focusable, non si
 * sposta e non viene scalato al focus, e resta fuori dall'area di focus del poster.
 *
 * Restyle premium "Neon Glow + Glass Specular Highlight" (condiviso con
 * [PosterMediaCard]/[StandardMediaCard]): il poster ha alone Neon diffuso, bordo
 * luminescente [NovaCyanBright] e riflesso di vetro diagonale. Le animazioni (scale,
 * alone, riflesso) leggono i valori solo nelle lambda di disegno, quindi non
 * ricompongono a ogni frame.
 *
 * Il focus resta esclusivamente sul poster, ma quando è focalizzato lo scroll
 * (bring-into-view) usa come area di riferimento l'INTERO contenitore della card, così il
 * numero di classifica resta visibile e non finisce sotto la Sidebar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Top10RankedMediaCard(
  rank: Int,
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  focusRequester: FocusRequester? = null
) {
  // Traccia lo stato di focus del poster per gli elementi non focusable (numero di classifica).
  var isFocused by remember { mutableStateOf(false) }

  // Animazioni GPU: i valori sono letti solo dentro graphicsLayer/drawBehind/drawWithContent.
  val scaleState = animateFloatAsState(
    targetValue = if (isFocused) 1.06f else 1f,
    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
    label = "top10PosterScale"
  )
  val glowState = animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
    label = "top10PosterGlow"
  )
  val specularState = animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
    label = "top10PosterSpecular"
  )
  val posterShape = RoundedCornerShape(NeonCardCorner)

  val targetMediaId = LocalLastFocusedMediaId.current
  val clearLastFocusedMedia = LocalClearLastFocusedMedia.current
  val internalFocusRequester = remember { FocusRequester() }
  val posterFocusRequester = focusRequester ?: internalFocusRequester

  LaunchedEffect(targetMediaId) {
    if (targetMediaId != null && targetMediaId == media.id) {
      try {
        posterFocusRequester.requestFocus()
        clearLastFocusedMedia?.invoke()
      } catch (_: Exception) {
        delay(32)
        try {
          posterFocusRequester.requestFocus()
          clearLastFocusedMedia?.invoke()
        } catch (_: Exception) {}
      }
    }
  }

  Row(
    modifier = modifier
      // L'intero blocco (numero + poster + titolo) sale sopra le card vicine quando è a fuoco.
      .zIndex(if (isFocused) 10f else 1f)
  ) {
    // 1. Area riservata al numero di classifica (NON focusable, a ridosso del proprio poster)
    Box(
      modifier = Modifier
        .height(Top10CardHeight)
        .align(Alignment.Top),
      contentAlignment = Alignment.BottomEnd
    ) {
      // Numero gigante stilizzato della posizione in Top 10
      Text(
        text = "$rank",
        fontSize = 115.sp,
        fontWeight = FontWeight.Black,
        fontFamily = FontFamily.SansSerif,
        color = if (isFocused) NovaCyanBright.copy(alpha = 0.95f) else Color(0xFF2C394E),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.offset(y = 14.dp)
      )
    }

    // 2. Gap compatto tra numero di classifica e proprio poster
    Spacer(modifier = Modifier.width(Top10RankGapWidth))

    // 3. Colonna Poster + Dettagli (titolo e anno SOTTO la locandina)
    Column(
      modifier = Modifier.width(Top10PosterWidth)
    ) {
      // Area poster: unico elemento focusable della card
      Box(
        modifier = Modifier
          .height(Top10CardHeight)
          .fillMaxWidth(),
        contentAlignment = Alignment.Center
      ) {
        Box(
          modifier = Modifier
            .width(Top10PosterWidth)
            .height(Top10PosterHeight)
            .focusRequester(posterFocusRequester)
            .graphicsLayer {
              val s = scaleState.value
              scaleX = s
              scaleY = s
            }
            .onFocusChanged {
              isFocused = it.isFocused
              if (it.isFocused && targetMediaId == media.id) {
                clearLastFocusedMedia?.invoke()
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
            // Alone Neon PRIMA del clip: si estende oltre i bordi del poster.
            .drawBehind {
              drawNeonGlow(
                cornerRadiusPx = NeonCardCorner.toPx(),
                color = NovaCyanBright,
                spreadPx = NeonCardGlowSpread.toPx(),
                intensity = glowState.value
              )
            }
            .clip(posterShape)
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
          val posterUrl = StremioCatalogMapper.extractImageUrl(media.posterUrl ?: media.backdropUrl)
          val fallbackRes = (media.posterRes ?: media.backdropRes)?.takeIf { it != R.drawable.banner_dune }
          CardMediaImage(
            primaryUrl = posterUrl,
            secondaryUrl = media.backdropUrl,
            fallbackRes = fallbackRes,
            contentDescription = media.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
          )
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      // 4. Titolo + anno FUORI dal poster, incolonnati perfettamente sotto la locandina
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(end = 4.dp)
      ) {
        Text(
          text = media.title,
          color = if (isFocused) NovaCyanBright else Color.White,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
        // Anno di produzione/rilascio: riga secondaria discreta, gerarchia sotto il titolo.
        if (media.year > 0) {
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = media.year.toString(),
            color = NovaTextMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}
