package com.example.ui.screens.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaTextPrimary

/** Tag di semantica della scena di caricamento Debrid (verifiche UI nei test). */
object DebridLoadingTestTags {
  const val ROOT = "debrid_loading_root"
  const val BACKGROUND = "debrid_loading_background"
  const val LOGO = "debrid_loading_logo"
  const val TITLE_FALLBACK = "debrid_loading_title_fallback"
  const val MESSAGE = "debrid_loading_message"
}

/**
 * Schermata di caricamento del percorso Debrid: poster/immagine del titolo a tutto
 * schermo, scrim scuro per la leggibilità, logo originale pulsante al centro e dicitura
 * localizzata del caricamento.
 *
 * La decisione "quando" mostrarla vive in [DebridLoadingScreen]: qui c'è solo la resa.
 *
 * Vincoli di interazione (D-pad / telecomando):
 *  - nessun nodo è focalizzabile o cliccabile (`canFocus = false` sul contenitore), quindi
 *    l'animazione non sottrae il focus ai controlli e non intercetta i tasti;
 *  - la pulsazione agisce solo su opacità e scala del logo (`graphicsLayer`) e non
 *    modifica layout, posizione o dimensioni dei nodi;
 *  - le immagini sono quelle già presenti nel modello del titolo: nessuna richiesta di
 *    rete aggiuntiva oltre al caricamento dell'artwork già in cache Coil.
 *
 * @param artwork artwork risolto dai metadati del titolo (vedi [DebridLoadingScreen]).
 * @param statusText stato REALE del caricamento mostrato al posto del messaggio fisso
 *   (impostazione "Mostra stato di caricamento"): fase derivata dagli eventi del
 *   player, mai percentuali simulate. `null` = messaggio fisso storico.
 * @param animationsEnabled `false` disattiva pulsazione e indicatore animato: serve ai
 *   test Robolectric per non tenere occupato il clock di Compose con animazioni infinite.
 *   In produzione resta sempre `true`.
 */
@Composable
fun DebridLoadingOverlay(
  backgroundUrl: String?,
  logoUrl: String?,
  fallbackTitle: String,
  statusText: String,
  modifier: Modifier = Modifier,
  animationsEnabled: Boolean = true
) {
  val context = LocalContext.current

  // Pulsazione delicata (opacità + scala): andata e ritorno continuo, nessun movimento
  // brusco. Il valore 0..1 viene mappato sui due effetti solo nel graphicsLayer del logo.
  val pulse = if (animationsEnabled) {
    val transition = rememberInfiniteTransition(label = "debrid_loading_pulse")
    val animated by transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 1_700, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
      ),
      label = "debrid_loading_pulse_value"
    )
    animated
  } else {
    0.5f
  }
  val logoAlpha = 0.72f + 0.28f * pulse
  val logoScale = 0.98f + 0.04f * pulse

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .focusProperties { canFocus = false }
      .testTag(DebridLoadingTestTags.ROOT)
  ) {
    // 1. Artwork a tutto schermo. Se manca o non si carica resta lo sfondo pieno:
    //    logo e dicitura rimangono comunque leggibili (mai schermata vuota).
    val backgroundUrl = backgroundUrl
    if (backgroundUrl != null) {
      SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
          .data(backgroundUrl)
          .crossfade(250)
          .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxSize()
          .testTag(DebridLoadingTestTags.BACKGROUND),
        loading = { Box(Modifier.fillMaxSize().background(NovaBackground)) },
        error = { Box(Modifier.fillMaxSize().background(NovaBackground)) }
      )
    }

    // 2. Overlay scuro con gradiente: centro leggibile, bordi cinematografici.
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.46f),
            0.45f to Color.Black.copy(alpha = 0.26f),
            1f to Color.Black.copy(alpha = 0.78f)
          )
        )
    )

    // 3. Logo originale pulsante + dicitura di caricamento.
    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .fillMaxWidth()
        .padding(horizontal = 48.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      val logoUrl = logoUrl
      if (logoUrl != null) {
        SubcomposeAsyncImage(
          model = ImageRequest.Builder(context)
            .data(logoUrl)
            .crossfade(200)
            .build(),
          contentDescription = fallbackTitle,
          contentScale = ContentScale.Fit,
          alignment = Alignment.Center,
          modifier = Modifier
            .widthIn(max = 520.dp)
            .heightIn(min = 48.dp, max = 180.dp)
            .graphicsLayer {
              alpha = logoAlpha
              scaleX = logoScale
              scaleY = logoScale
            }
            .testTag(DebridLoadingTestTags.LOGO),
          // Mentre il logo si carica non si mostra nulla: il titolo testuale è un
          // FALLBACK, non un sostituto temporaneo del logo.
          loading = {},
          error = { DebridLoadingTitleFallback(title = fallbackTitle) }
        )
      } else {
        DebridLoadingTitleFallback(title = fallbackTitle)
      }

      Spacer(modifier = Modifier.height(22.dp))

      Text(
        text = statusText,
        color = Color.White.copy(alpha = 0.92f),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(DebridLoadingTestTags.MESSAGE)
      )

      // Indicatore discreto: stessa tinta del testo, dimensione ridotta. È assente
      // quando le animazioni sono disattivate (solo nei test).
      if (animationsEnabled) {
        Spacer(modifier = Modifier.height(14.dp))
        CircularProgressIndicator(
          color = Color.White.copy(alpha = 0.72f),
          strokeWidth = 2.dp,
          modifier = Modifier.size(22.dp)
        )
      }
    }
  }
}

/** Fallback elegante quando il logo manca o l'immagine non è caricabile. */
@Composable
private fun DebridLoadingTitleFallback(title: String) {
  Text(
    text = title,
    color = NovaTextPrimary,
    fontSize = 40.sp,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 1.5.sp,
    textAlign = TextAlign.Center,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.testTag(DebridLoadingTestTags.TITLE_FALLBACK)
  )
}
