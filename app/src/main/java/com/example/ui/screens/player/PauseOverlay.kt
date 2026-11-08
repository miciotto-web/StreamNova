package com.example.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaTextPrimary

/** Tag di semantica dell'overlay in pausa (verifiche UI nei test). */
internal object PauseOverlayTestTags {
  const val ROOT = "pause_overlay_root"
  const val POSTER = "pause_overlay_poster"
  const val TITLE = "pause_overlay_title"
  const val SYNOPSIS = "pause_overlay_synopsis"
}

/**
 * Politica PURA dell'overlay in pausa (opzione "Overlay in pausa"):
 *
 *  - la pausa dura almeno [REVEAL_DELAY_MS] → l'overlay compare;
 *  - la ripresa (o un cambio contenuto/episodio, un errore, il buffering) prima dei
 *    5 secondi ANNULLA il timer: l'overlay non è mai comparso;
 *  - la ripresa mentre è visibile lo nasconde IMMEDIATAMENTE.
 *
 * Il timer vive in un solo `LaunchedEffect` di PlayerScreen (nessun timer duplicato):
 * il riavvio dell'effetto su ogni cambio dei criteri implementa sia l'annullamento
 * sia l'occultamento immediato, senza stato extra.
 */
internal object PauseOverlayPolicy {

  /** Millisecondi di pausa prima della comparsa dell'overlay. */
  const val REVEAL_DELAY_MS = 5_000L

  /**
   * `true` quando il timer di comparsa deve essere avviato (o lasciato correre) in
   * questo istante. Il chiamante passa SOLO stati reali già tracciati dal player.
   */
  fun shouldStartTimer(
    enabled: Boolean,
    isPaused: Boolean,
    isPlayerReady: Boolean,
    isBuffering: Boolean,
    hasPlaybackError: Boolean,
    playbackEnded: Boolean,
    isScrubbing: Boolean
  ): Boolean = enabled && isPaused && isPlayerReady && !isBuffering &&
    !hasPlaybackError && !playbackEnded && !isScrubbing
}

/**
 * Overlay informativo mostrato dopo [PauseOverlayPolicy.REVEAL_DELAY_MS] di pausa.
 *
 * Contenuto (tutti dati già presenti nella sessione, nessuna richiesta di rete):
 * poster del contenuto, logo StreamNova, titolo ORIGINALE del film/serie,
 * stagione/episodio quando disponibili e sinossi (dell'episodio per le serie,
 * altrimenti quella del titolo).
 *
 * Vincoli di interazione (D-pad / telecomando), come per [DebridLoadingOverlay]:
 *  - nessun nodo focalizzabile o cliccabile (`canFocus = false`): i comandi DPAD e i
 *    controlli del player non sono mai intercettati né bloccati;
 *  - posizionato in alto a sinistra: non copre la timeline né la barra dei controlli,
 *    che restano pienamente operative sotto (l'HUD resta visibile in pausa).
 *
 * @param title titolo originale (fallback: titolo localizzato).
 * @param episodeSubtitle "S1E1 • Titolo episodio" per le serie; `null` per i film.
 * @param synopsis sinossi mostrata; stringa vuota = nessun paragrafo.
 */
@Composable
internal fun PauseInfoOverlay(
  posterUrl: String?,
  title: String,
  episodeSubtitle: String?,
  synopsis: String,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  Row(
    verticalAlignment = Alignment.Top,
    modifier = modifier
      .widthIn(max = 660.dp)
      .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(20.dp))
      .border(1.dp, NovaCyan.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
      .padding(20.dp)
      .focusProperties { canFocus = false }
      .testTag(PauseOverlayTestTags.ROOT)
  ) {
    // Poster del contenuto. Se manca o non si carica resta il riquadro scuro: la
    // card non è mai vuota né deforme.
    SubcomposeAsyncImage(
      model = ImageRequest.Builder(context)
        .data(posterUrl)
        .crossfade(200)
        .build(),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .width(132.dp)
        .height(198.dp)
        .clip(RoundedCornerShape(12.dp))
        .testTag(PauseOverlayTestTags.POSTER),
      loading = {
        Box(
          Modifier
            .width(132.dp)
            .height(198.dp)
            .background(Color.White.copy(alpha = 0.06f))
        )
      },
      error = {
        Box(
          Modifier
            .width(132.dp)
            .height(198.dp)
            .background(Color.White.copy(alpha = 0.06f))
        )
      }
    )

    Spacer(modifier = Modifier.width(20.dp))

    Column {
      Image(
        painter = painterResource(R.drawable.streamnova_logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .width(104.dp)
          .height(28.dp)
      )

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = stringResource(R.string.player_pause_overlay_paused),
        color = NovaCyan,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp
      )

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(PauseOverlayTestTags.TITLE)
      )

      if (episodeSubtitle != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = episodeSubtitle,
          color = Color.White.copy(alpha = 0.78f),
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      if (synopsis.isNotBlank()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = synopsis,
          color = Color.White.copy(alpha = 0.72f),
          fontSize = 13.sp,
          lineHeight = 18.sp,
          maxLines = 5,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.testTag(PauseOverlayTestTags.SYNOPSIS)
        )
      }
    }
  }
}
