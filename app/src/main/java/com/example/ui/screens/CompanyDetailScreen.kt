package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.MediaItem
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.TvFocusBringIntoViewSpec
import com.example.ui.components.TvFocusRow
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.CompanyDetailUiState
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.delay

/**
 * Scheda Casa di Produzione.
 *
 * Header non scrollato all'apertura: sovrascritta "Casa di produzione", nome gigante
 * dello studio, localizzazione (paese • sede) e logo ufficiale ORIGINALE (colori reali,
 * nessun filtro bianco) racchiuso in un badge chiaro per il contrasto su sfondo scuro.
 * Sotto, righe orizzontali tematiche ("Film • Popolari", "Film • Più votati", ...)
 * con locandine VERTICALI 2:3 ([PosterMediaCard]).
 * Il focus D-pad parte sul primo poster della prima riga con viewport ferma a offset 0:
 * lo scroll verticale e' consentito solo quando l'utente preme attivamente SU/GIU',
 * mentre lo scorrimento ORIZZONTALE di ogni riga resta nativo ([TvFocusRow]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompanyDetailScreen(
  companyId: Int,
  companyName: String?,
  logoUrl: String?,
  viewModel: StreamNovaViewModel,
  onBackClick: () -> Unit,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  BackHandler { onBackClick() }

  val uiState by viewModel.companyDetailUiState.collectAsState()

  LaunchedEffect(companyId) {
    viewModel.loadCompanyDetails(companyId, companyName, logoUrl)
  }

  val firstItemFocusRequester = remember { FocusRequester() }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    when (val state = uiState) {
      is CompanyDetailUiState.Loading -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            CircularProgressIndicator(
              color = NovaCyanBright,
              strokeWidth = 3.dp,
              modifier = Modifier.size(48.dp)
            )
            Text(
              text = stringResource(R.string.detail_loading_tmdb),
              color = NovaTextSecondary,
              fontSize = 14.sp
            )
          }
        }
      }

      is CompanyDetailUiState.Error -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            Text(
              text = state.message,
              color = Color(0xFFEF4444),
              fontSize = 16.sp,
              fontWeight = FontWeight.SemiBold
            )
            TvFocusableBox(
              shape = RoundedCornerShape(8.dp),
              onClick = { viewModel.loadCompanyDetails(companyId, companyName, logoUrl) }
            ) { isFocused ->
              Row(
                modifier = Modifier
                  .background(
                    if (isFocused) NovaCyan else NovaSurfaceVariant,
                    RoundedCornerShape(8.dp)
                  )
                  .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = null,
                  tint = if (isFocused) Color.Black else Color.White,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = stringResource(R.string.action_retry),
                  color = if (isFocused) Color.Black else Color.White,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }
      }

      is CompanyDetailUiState.Success -> {
        val rows = mutableListOf<Pair<String, List<MediaItem>>>()
        if (state.movies.isNotEmpty()) {
          rows.add(stringResource(R.string.studio_row_movies_popular) to state.movies)
        }
        if (state.topRatedMovies.isNotEmpty()) {
          rows.add(stringResource(R.string.studio_row_movies_top_rated) to state.topRatedMovies)
        }
        if (state.tvSeries.isNotEmpty()) {
          rows.add(stringResource(R.string.studio_row_tv_popular) to state.tvSeries)
        }
        if (state.topRatedTvSeries.isNotEmpty()) {
          rows.add(stringResource(R.string.studio_row_tv_top_rated) to state.topRatedTvSeries)
        }

        val scrollState = rememberScrollState()
        // Guard: nessuno scroll automatico finche' l'utente non preme attivamente SU/GIU'.
        var isBringIntoViewAllowed by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        val tvFocusSpec = remember(density) {
          TvFocusBringIntoViewSpec(
            parentFraction = 0.3f,
            topHeaderOffsetPx = with(density) { 64.dp.toPx() },
            bottomMarginPx = with(density) { 48.dp.toPx() }
          )
        }
        val guardedBringIntoViewSpec = remember(tvFocusSpec) {
          object : BringIntoViewSpec {
            override fun calculateScrollDistance(
              offset: Float,
              size: Float,
              containerSize: Float
            ): Float {
              // Testata sempre visibile: zero auto-scroll all'apertura e sui tasti LEFT/RIGHT.
              if (!isBringIntoViewAllowed) return 0f
              return tvFocusSpec.calculateScrollDistance(offset, size, containerSize)
            }

            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float>
              get() = tvFocusSpec.scrollAnimationSpec
          }
        }

        // Focus iniziale sul PRIMO poster della prima riga ("Film • Popolari").
        LaunchedEffect(Unit) {
          if (rows.isEmpty()) return@LaunchedEffect
          delay(120)
          repeat(6) {
            try {
              firstItemFocusRequester.requestFocus()
              return@LaunchedEffect
            } catch (_: Exception) {
              // FocusRequester non ancora agganciato: ritenta al ciclo successivo.
              delay(48)
            }
          }
        }

        val locationParts = mutableListOf<String>()
        state.originCountry?.takeIf { it.isNotBlank() }?.let { locationParts.add(it) }
        state.headquarters?.takeIf { it.isNotBlank() }?.let { locationParts.add(it) }
        val location = locationParts.joinToString(" • ")

        CompositionLocalProvider(LocalBringIntoViewSpec provides guardedBringIntoViewSpec) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 48.dp, vertical = 24.dp)
              .verticalScroll(scrollState)
              .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                  isBringIntoViewAllowed =
                    event.key == Key.DirectionDown || event.key == Key.DirectionUp
                }
                false
              },
            verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            // HEADER FISSO: sovrascritta + nome gigante + localizzazione | logo a destra
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(24.dp),
              verticalAlignment = Alignment.Top
            ) {
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Text(
                  text = stringResource(R.string.studio_company_overline),
                  color = NovaTextSecondary,
                  fontSize = 17.sp,
                  fontWeight = FontWeight.Medium
                )
                Text(
                  text = state.companyName,
                  color = Color.White,
                  fontSize = 46.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 2,
                  lineHeight = 50.sp,
                  overflow = TextOverflow.Ellipsis
                )
                if (location.isNotBlank()) {
                  Text(
                    text = location,
                    color = NovaTextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }

              // Logo ufficiale dello studio: colori e grafica ORIGINALI (nessun ColorFilter),
              // racchiuso in un badge chiaro per garantire contrasto su sfondo scuro.
              if (!state.logoUrl.isNullOrBlank()) {
                Box(
                  modifier = Modifier
                    .width(210.dp)
                    .height(112.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFD7DCE5), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                  contentAlignment = Alignment.Center
                ) {
                  AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                      .data(state.logoUrl)
                      .crossfade(true)
                      .build(),
                    contentDescription = state.companyName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                  )
                }
              } else {
                Box(
                  modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(NovaCardBg, RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp)),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    imageVector = Icons.Default.Business,
                    contentDescription = null,
                    tint = NovaCyanBright,
                    modifier = Modifier.size(48.dp)
                  )
                }
              }
            }

            // Righe catalogo tematiche, tutte con locandine verticali 2:3 e scroll
            // orizzontale nativo col D-pad LEFT/RIGHT fino all'ultimo poster.
            rows.forEachIndexed { rowIndex, (rowTitle, rowItems) ->
              Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Text(
                  text = rowTitle,
                  color = Color.White,
                  fontSize = 20.sp,
                  fontWeight = FontWeight.Bold
                )
                val rowState = rememberLazyListState()
                TvFocusRow {
                  LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    itemsIndexed(rowItems, key = { index, item -> "sec_${rowIndex}_${index}_${item.id}" }, contentType = { index, item -> "sec_${rowIndex}_${item.type}" }) { index, item ->
                      PosterMediaCard(
                        media = item,
                        onClick = { onMediaClick(item) },
                        focusRequester = if (rowIndex == 0 && index == 0) firstItemFocusRequester else null
                      )
                    }
                  }
                }
              }
            }

            if (rows.isEmpty()) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(NovaCardBg, RoundedCornerShape(12.dp))
                  .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                  .padding(32.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = stringResource(R.string.production_empty),
                  color = NovaTextSecondary,
                  fontSize = 14.sp
                )
              }
            }
          }
        }
      }

      else -> Unit
    }
  }
}