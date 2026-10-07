package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.MediaItem
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * Geometria della card Top 10.
 * Il numero di classifica e il poster occupano due aree orizzontali distinte,
 * separate da un gap esplicito: nessuna sovrapposizione tra i due elementi.
 *
 * L'area del numero è dimensionata per contenere il rank a due cifre (fino a "10")
 * alla dimensione attuale del font (115.sp), così il numero non invade mai il poster,
 * nemmeno quando il poster è focalizzato e scala a 1.08.
 */
private val Top10RankAreaWidth = 130.dp
private val Top10RankGapWidth = 16.dp
private val Top10PosterWidth = 148.dp
private val Top10PosterHeight = 220.dp
private val Top10CardHeight = 235.dp
private val Top10CardWidth = Top10RankAreaWidth + Top10RankGapWidth + Top10PosterWidth

/**
 * Card stile "TOP 10" con numero gigante in stile TV streaming (es. Netflix/HBO style)
 * Il numero gigante è a sinistra e la locandina è allineata a destra.
 *
 * Il numero di classifica è un elemento puramente grafico: non è focusable, non si
 * sposta e non viene scalato al focus, e resta fuori dall'area di focus del poster.
 *
 * Il focus resta esclusivamente sul poster, ma quando il poster è focalizzato lo scroll
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
  // Traccia lo stato di focus del poster per gli elementi non focusable (numero di classifica)
  var isFocused by remember { mutableStateOf(false) }

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

  Box(
    modifier = modifier
      .width(Top10CardWidth)
      .height(Top10CardHeight)
      .bringIntoViewRequester(cardBringIntoViewRequester)
      .onFocusChanged { isFocused = it.isFocused }
  ) {
    Row(
      modifier = Modifier.fillMaxSize()
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
      TvFocusableBox(
        modifier = Modifier
          .align(Alignment.CenterVertically)
          .width(Top10PosterWidth)
          .height(Top10PosterHeight),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.08f,
        onClick = onClick
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(NovaCardBg)
            .border(
              width = if (isFocused) 2.dp else 1.dp,
              color = if (isFocused) NovaCyanBright else Color(0x33334155),
              shape = RoundedCornerShape(12.dp)
            )
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

          // Gradiente sfumato in basso
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.2f),
                    Color.Black.copy(alpha = 0.9f)
                  ),
                  startY = 120f
                )
              )
          )

          // Badge TOP 10 in alto a sinistra
          Box(
            modifier = Modifier
              .align(Alignment.TopStart)
              .padding(6.dp)
              .background(Color(0xFFE50914), RoundedCornerShape(4.dp))
              .padding(horizontal = 6.dp, vertical = 2.dp)
          ) {
            Text(
              text = "TOP 10",
              color = Color.White,
              fontSize = 9.sp,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 0.5.sp
            )
          }

          // Titolo, dettagli e voto TMDB allineato in basso a destra
          Row(
            modifier = Modifier
              .align(Alignment.BottomStart)
              .fillMaxWidth()
              .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = media.title,
                color = if (isFocused) NovaCyanBright else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = "${media.year} • ${media.type.labelItalian}",
                color = NovaTextMuted,
                fontSize = 9.5.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )
            }
            PosterRatingBadge(rating = media.rating, compact = true)
          }
        }
      }
    }
  }
}
