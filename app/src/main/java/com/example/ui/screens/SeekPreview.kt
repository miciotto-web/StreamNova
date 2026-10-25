package com.example.ui.screens

import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.player.SeekPreviewFrame
import com.example.ui.screens.player.SeekPreviewModel
import com.example.ui.screens.player.SeekPreviewState
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextSecondary

/** Frazione della larghezza dello schermo riservata all'anteprima, per il clamp ai bordi. */
internal const val SEEK_PREVIEW_WIDTH_FRACTION = 0.30f

/**
 * Anteprima video sopra la timeline (stile YouTube).
 *
 * Componente puramente visivo e senza focus: mostra lo [SeekPreviewState] pubblicato dal
 * controller e il timestamp della posizione *proposta* dallo scrubbing. Non tocca il
 * player, non gestisce tasti e non sposta il focus: può essere sovrapposto all'HUD o
 * all'overlay di scrubbing senza alterarne la navigazione D-pad.
 *
 * Tre situazioni, tutte distinguibili a colpo d'occhio:
 * - fotogramma pronto: immagine 16:9 con bordo cyan e timestamp;
 * - fotogramma in arrivo: riquadro con banda luminosa che scorre e timestamp già leggibile
 *   (se c'è un frame della posizione precedente resta a video, discretamente attenuato);
 * - nessun fotogramma (fonte non ricercabile, live, errore): placeholder elegante con
 *   icona e timestamp, mai un'immagine di un'altra posizione.
 *
 * @param state stato pubblicato dal [com.example.ui.screens.player.SeekPreviewController].
 * @param durationMs durata nota del contenuto, per la stringa "posizione / durata".
 * @param width larghezza del riquadro; l'altezza segue il rapporto 16:9.
 */
@Composable
internal fun SeekPreview(
  state: SeekPreviewState,
  durationMs: Long,
  width: Dp,
  modifier: Modifier = Modifier
) {
  val frame: SeekPreviewFrame? = when (state) {
    is SeekPreviewState.Frame -> state.frame
    is SeekPreviewState.Loading -> state.frame
    is SeekPreviewState.Unavailable -> null
  }
  val isLoading = state is SeekPreviewState.Loading
  val image = remember(frame?.bitmap) { frame?.bitmap?.asImageBitmap() }
  val staleFrame = isLoading && frame != null
  /**
   * Vero quando l'immagine a video è reale ma NON il risultato definitivo della posizione
   * scelta: PTS non dichiarato dal renderer, o scarto oltre la tolleranza di coincidenza.
   *
   * Si distingue a colpo d'occhio (badge "≈" e bordo attenuato) perché durante il movimento un
   *'approssimazione può e deve comparire, ma non deve mai essere scambiata per il fotogramma
   * del punto finale: prima dell'introduzione di questo stato l'utente non aveva modo di sapere
   * se la miniatura rappresentasse la scena che vedeva o quella del passo precedente.
   */
  val approximateFrame = state is SeekPreviewState.Frame && !state.isDefinitive
  // Tracciamento del solo passaggio "pubblicato ma non visualizzato": questo log compare
  // quando la bitmap arriva DAVVERO al compositor, non quando viene solo pubblicata.
  // L'impronta arriva già col frame: qui prima si ricomputava (createScaledBitmap + 64 getPixel)
  // DENTRO la composizione, cioè sul main thread a ogni frame mostrato.
  LaunchedEffect(image) {
    val shown = frame
    if (image != null && shown != null) {
      Log.i(
        "SeekPreview",
        "render: pos=${shown.positionMs}ms bitmap=${image.width}x${image.height} " +
          "hash=${shown.contentHash} stale=$staleFrame approssimazione=$approximateFrame"
      )
    }
  }
  // Tempo scritto sul fotogramma: quello DECODIFICATO del frame (PTS), non la posizione
  // proposta dallo slider. Se il frame è ancora quello precedente, il badge continua a
  // dichiarare la sua posizione reale: nessuna immagine vecchia etichettata con un tempo
  // nuovo. La tacca sulla timeline usa invece la posizione proposta (dove sta lo slider).
  val badgePositionMs = frame?.positionMs ?: state.positionMs
  val shape = RoundedCornerShape(10.dp)

  Box(
    modifier = modifier
      .width(width)
      .aspectRatio(16f / 9f)
      .shadow(
        elevation = 20.dp,
        shape = shape,
        spotColor = NovaCyanBright,
        ambientColor = Color.Black
      )
      .background(Color(0xE60B111E), shape)
      .border(
        width = 1.5.dp,
        // Bordo attenuato quando il fotogramma è un'approssimazione: la differenza si vede
        // anche senza leggere il badge.
        color = if (approximateFrame) NovaCyan.copy(alpha = 0.35f) else NovaCyan.copy(alpha = 0.85f),
        shape
      )
      .clip(shape)
  ) {
    if (image != null) {
      Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Medium,
        alpha = if (staleFrame) 0.65f else if (approximateFrame) 0.85f else 1f,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      SeekPreviewPlaceholder(isLoading = isLoading)
    }

    // Velo inferiore: il timestamp resta leggibile su qualunque fotogramma senza coprire
    // la parte centrale della scena.
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colorStops = arrayOf(
              0f to Color.Transparent,
              0.58f to Color.Transparent,
              1f to Color.Black.copy(alpha = 0.74f)
            )
          )
        )
    )

    if (isLoading) {
      LoadingSheen(modifier = Modifier.fillMaxSize())
    }

    TimestampBadge(
      text = (if (approximateFrame) "\u2248 " else "") +
        SeekPreviewModel.formatTimestamp(badgePositionMs, durationMs),
      isMuted = (!isLoading && image == null) || approximateFrame,
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(start = 10.dp, bottom = 8.dp)
    )

    // Indice della posizione nella timeline: la stessa informazione della barra sotto,
    // così l'anteprima resta ancorata visivamente alla posizione scelta.
    TimelineAnchor(fraction = SeekPreviewModel.timelineFraction(state.positionMs, durationMs))
  }
}

@Composable
private fun SeekPreviewPlaceholder(isLoading: Boolean) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Brush.verticalGradient(listOf(Color(0xFF16202F), Color(0xFF0B111E)))),
    contentAlignment = Alignment.Center
  ) {
    if (!isLoading) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Image,
          contentDescription = null,
          tint = NovaTextSecondary,
          modifier = Modifier.size(20.dp)
        )
        Text(
          text = "Anteprima non disponibile",
          color = NovaTextSecondary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium
        )
      }
    }
  }
}

/** Banda luminosa discreta che scorre mentre il fotogramma viene recuperato. */
@Composable
private fun LoadingSheen(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "SeekPreviewLoading")
  val progress by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1_100, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "SeekPreviewSheen"
  )
  val head = progress.coerceIn(0f, 1f)
  Box(
    modifier = modifier.background(
      Brush.horizontalGradient(
        colorStops = arrayOf(
          0f to Color.Transparent,
          (head - 0.18f).coerceIn(0f, 1f) to Color.Transparent,
          head to NovaCyanBright.copy(alpha = 0.22f),
          (head + 0.18f).coerceIn(0f, 1f) to Color.Transparent,
          1f to Color.Transparent
        )
      )
    )
  )
}

@Composable
private fun TimestampBadge(
  text: String,
  isMuted: Boolean,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
      .border(
        width = 1.dp,
        color = if (isMuted) Color.White.copy(alpha = 0.12f) else NovaCyan.copy(alpha = 0.45f),
        shape = RoundedCornerShape(6.dp)
      )
      .padding(horizontal = 8.dp, vertical = 3.dp)
  ) {
    Text(
      text = text,
      color = if (isMuted) NovaTextSecondary else Color.White,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold
    )
  }
}

/** Tacca che indica, nella larghezza dell'anteprima, la posizione relativa sulla timeline. */
@Composable
private fun TimelineAnchor(fraction: Float) {
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(start = ((maxWidth - 6.dp) * clamped).coerceAtLeast(0.dp))
        .size(width = 6.dp, height = 3.dp)
        .background(NovaCyanBright, RoundedCornerShape(1.5.dp))
    )
  }
}
