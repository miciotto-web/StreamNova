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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Person
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
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.PersonDetailUiState
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Altezza massima (dp) del blocco biografia: tiene l'header compatto su TV 540dp. */
private const val BIO_MAX_HEIGHT = 96

/** Larghezza (dp) dei poster della filmografia (altezza = 1.5x, rapporto 2:3). */
private const val FILM_POSTER_WIDTH = 130

/**
 * Scheda Persona / Regista / Attore.
 *
 * Layout a TUTTO SCHERMO senza scroll verticale iniziale, studiato per il TV (viewport 540dp):
 * - Header fisso (foto verticale 104x140 + nome + nascita + luogo + biografia vincolata a
 *   [BIO_MAX_HEIGHT]) sempre visibile, cosi' l'intera riga della filmografia entra in schermo.
 * - Sezione "Filmografia" con badge contatto e locandine VERTICALI 2:3 ([PosterMediaCard])
 *   larghe [FILM_POSTER_WIDTH]: mai backdrop orizzontali, solo `poster_path` TMDB.
 * - Il focus D-pad parte sul PRIMO poster della filmografia e la viewport resta ferma a offset 0
 *   (il [BringIntoViewSpec] guardato azzera lo scroll verticale finche' l'utente non preme
 *   SU/GIU'): lo scorrimento ORIZZONTALE della riga resta invece libero ([TvFocusRow]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonDetailScreen(
  personId: Int,
  viewModel: StreamNovaViewModel,
  onBackClick: () -> Unit,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  personName: String? = null
) {
  BackHandler { onBackClick() }

  val uiState by viewModel.personDetailUiState.collectAsState()

  LaunchedEffect(personId, personName) {
    viewModel.loadPersonDetails(personId, personName)
  }

  val firstItemFocusRequester = remember { FocusRequester() }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    when (val state = uiState) {
      is PersonDetailUiState.Loading -> {
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

      is PersonDetailUiState.Error -> {
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
              onClick = { viewModel.loadPersonDetails(personId, personName) }
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

      is PersonDetailUiState.Success -> {
        val person = state.person
        val credits = state.credits

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
              // Header sempre visibile: zero auto-scroll all'apertura e sui tasti LEFT/RIGHT.
              if (!isBringIntoViewAllowed) return 0f
              return tvFocusSpec.calculateScrollDistance(offset, size, containerSize)
            }

            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float>
              get() = tvFocusSpec.scrollAnimationSpec
          }
        }

        // Focus iniziale sul PRIMO poster della filmografia, con viewport ferma a 0.
        LaunchedEffect(Unit) {
          if (credits.isEmpty()) return@LaunchedEffect
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

        CompositionLocalProvider(LocalBringIntoViewSpec provides guardedBringIntoViewSpec) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 48.dp, vertical = 20.dp)
              .verticalScroll(scrollState)
              .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                  isBringIntoViewAllowed =
                    event.key == Key.DirectionDown || event.key == Key.DirectionUp
                }
                false
              },
            verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            // HEADER FISSO: FOTO + INFO + BIOGRAFIA (mai scrollato all'apertura)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
              // Foto profilo verticale 104x140 con bordi arrotondati 16dp
              Box(
                modifier = Modifier
                  .width(104.dp)
                  .height(140.dp)
                  .clip(RoundedCornerShape(16.dp))
                  .background(NovaCardBg, RoundedCornerShape(16.dp))
                  .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
              ) {
                if (!person.profileUrl.isNullOrBlank()) {
                  AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                      .data(person.profileUrl)
                      .crossfade(true)
                      .build(),
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                  )
                } else {
                  Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = NovaTextMuted,
                    modifier = Modifier.size(56.dp)
                  )
                }
              }

              // Colonna testi: nome grande, nascita, luogo e biografia
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text(
                  text = person.name,
                  color = Color.White,
                  fontSize = 28.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis
                )

                val birthDate = parseIsoDate(person.birthday)
                if (birthDate != null) {
                  val age = computeAge(birthDate)
                  Text(
                    text = stringResource(
                      R.string.person_born_format,
                      SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(birthDate),
                      age
                    ),
                    color = NovaTextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                  )
                }

                if (!person.placeOfBirth.isNullOrBlank()) {
                  Text(
                    text = person.placeOfBirth,
                    color = NovaTextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                  )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Biografia vincolata in altezza: con scroll interno, ma senza mai
                // spingere la filmografia fuori dallo schermo TV.
                val bioText = person.biography.ifBlank { stringResource(R.string.person_bio_empty) }
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = BIO_MAX_HEIGHT.dp)
                    .verticalScroll(rememberScrollState())
                ) {
                  Text(
                    text = bioText,
                    color = NovaTextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                  )
                }
              }
            }

            // FILMOGRAFIA: titolo + badge scuro a capsula con il conteggio
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Text(
                text = stringResource(R.string.person_credits_title),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
              )
              Box(
                modifier = Modifier
                  .background(NovaCardBg, RoundedCornerShape(8.dp))
                  .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                  .padding(horizontal = 9.dp, vertical = 3.dp)
              ) {
                Text(
                  text = credits.size.toString(),
                  color = NovaTextSecondary,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }

            if (credits.isEmpty()) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(NovaCardBg, RoundedCornerShape(12.dp))
                  .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                  .padding(32.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = stringResource(R.string.person_credits_empty),
                  color = NovaTextSecondary,
                  fontSize = 14.sp
                )
              }
            } else {
              // Locandine ESCLUSIVAMENTE verticali 2:3 (poster_path TMDB, mai backdrop) e
              // riga scorrevole col D-pad LEFT/RIGHT fino all'ultimo elemento.
              val rowState = rememberLazyListState()
              TvFocusRow {
                LazyRow(
                  state = rowState,
                  contentPadding = PaddingValues(vertical = 4.dp),
                  horizontalArrangement = Arrangement.spacedBy(16.dp),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  itemsIndexed(credits, key = { _, item -> "credit_${item.id}" }, contentType = { _, _ -> "credit" }) { index, item ->
                    PosterMediaCard(
                      media = item,
                      onClick = { onMediaClick(item) },
                      focusRequester = if (index == 0) firstItemFocusRequester else null,
                      cardWidth = FILM_POSTER_WIDTH.dp,
                      allowBackdropFallback = false
                    )
                  }
                }
              }
            }
          }
        }
      }

      else -> Unit
    }
  }
}

/** Parsing di una data ISO TMDB (yyyy-MM-dd); null se assente o non interpretabile. */
private fun parseIsoDate(raw: String?): Date? {
  if (raw.isNullOrBlank()) return null
  return try {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(raw.trim())
  } catch (_: Exception) {
    null
  }
}

/**Eta' in anni compiuti calcolata dalla data di nascita fino ad oggi.*/
private fun computeAge(birthday: Date): Int {
  val today = Calendar.getInstance()
  val born = Calendar.getInstance().apply { time = birthday }
  var age = today.get(Calendar.YEAR) - born.get(Calendar.YEAR)
  if (today.get(Calendar.DAY_OF_YEAR) < born.get(Calendar.DAY_OF_YEAR)) {
    age -= 1
  }
  return age.coerceAtLeast(0)
}