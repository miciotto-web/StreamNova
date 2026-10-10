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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
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
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyanBright

/**
 * Geometria della card Top 10.
 * Il numero di classifica e il poster occupano due aree orizzontali distinte,
 * separate da un gap esplicito: nessuna sovrapposizione tra i due elementi.
 *
 * L'area del numero è dimensionata per contenere il rank a due cifre (fino a "10")
 * alla dimensione attuale del font (115.sp), così il numero non invade mai il poster,
 * nemmeno quando il poster è focalizzato e scala.
 */
private val Top10RankAreaWidth = 130.dp
private val Top10RankGapWidth = 16.dp
private val Top10PosterWidth = 148.dp
private val Top10PosterHeight = 220.dp
/** Altezza del blocco numero + poster (il titolo sotto è aggiunto a parte). */
private val Top10CardHeight = 235.dp
private val Top10CardWidth = Top10RankAreaWidth + Top10RankGapWidth + Top10PosterWidth

/**
 * Card stile "TOP 10" con numero gigante in stile TV streaming (es. Netflix/HBO style)
 * Il numero gigante è a sinistra e la locandina è allineata a destra.
 *
 * Il poster contiene SOLO l'artwork: anno, voto (stellina) e titolo sono stati
 * rimossi dagli overlay. Il titolo vive in una riga dedicata SOTTO la locandina
 * (`titleSmall`, max 2 righe con ellissi), così la locandina resta pulita.
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
  modifier: Modifier = Modifier
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

  // Richiesta di bring-into-view agganciata al contenitore completo della card (NON focusable):
  // il poster continua a essere l'unico elemento focalizzabile, ma è il contenitore a definire
  // l'area che deve risultare visibile durante lo scroll orizzontale della LazyRow.
  val cardBringIntoViewRequester = remember { BringIntoViewRequester() }

  // Riutilizza TvFocusBringIntoViewSpec (ereditata dalla composizione): nessun nuovo spec,
  // nessuna modifica alla LazyRow né alla Sidebar.
  LaunchedEffect(isFocused) {
    if (isFocused) {
      cardBringIntoViewRequester.bringIntoView()
    }
  }

  Column(
    modifier = modifier
      .width(Top10CardWidth)
      // L'intero blocco (numero + poster + titolo) sale sopra le card vicine quando è a fuoco.
      .zIndex(if (isFocused) 10f else 1f)
      .bringIntoViewRequester(cardBringIntoViewRequester)
      .onFocusChanged { isFocused = it.isFocused }
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(Top10CardHeight)
    ) {
      // 1. Area riservata al numero di classifica (NON focusable)
      Box(
        modifier = Modifier
          .width(Top10RankAreaWidth)
          .fillMaxHeight()
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
          modifier = Modifier
            .align(Alignment.BottomStart)
            .offset(x = (-4).dp, y = 14.dp)
        )
      }

      // 2. Gap fisso tra numero di classifica e poster
      Spacer(modifier = Modifier.width(Top10RankGapWidth))

      // 3. Area poster: unico elemento focusable della card
      Box(
        modifier = Modifier
          .align(Alignment.CenterVertically)
          .width(Top10PosterWidth)
          .height(Top10PosterHeight)
          .graphicsLayer {
            val s = scaleState.value
            scaleX = s
            scaleY = s
          }
          .onFocusChanged { isFocused = it.isFocused }
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
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // 4. Titolo FUORI dal poster: riga dedicata sotto la locandina, allineata alla
    //    colonna del poster. Nessuna stellina né anno: solo il testo, minimale.
    Row(modifier = Modifier.fillMaxWidth()) {
      Spacer(modifier = Modifier.width(Top10RankAreaWidth + Top10RankGapWidth))
      Text(
        text = media.title,
        color = if (isFocused) NovaCyanBright else Color.White,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .weight(1f)
          .padding(end = 4.dp)
      )
    }
  }
}
