package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * FocusRequester dei controlli rapidi della scheda.
 *
 * Serie TV: `Riproduci -> Cuore -> Serie già vista -> Trailer -> Seleziona fonte`.
 * Film: `Riproduci -> Cuore -> Occhio -> Trailer -> Seleziona fonte`
 * (quest'ultimo presente solo in modalità Debrid).
 */
class MediaActionBarFocus {
  val play = FocusRequester()
  val favorite = FocusRequester()
  val seriesWatched = FocusRequester()
  val watched = FocusRequester()
  val trailer = FocusRequester()
  val selectSource = FocusRequester()
}

/**
 * Barra azioni della scheda dettagli / selezione sorgenti.
 *
 * Riga orizzontale composta da:
 *  - pillola evidenziata **Riproduci** (avvia la riproduzione o focalizza l'elenco sorgenti);
 *  - bottone circolare **Cuore** (toggle preferiti);
 *  - per le SOLE serie TV: pillola **Serie già vista** con l'icona occhio. È l'UNICO
 *    controllo «visto» della serie: marca tutti gli episodi noti e conclude il titolo,
 *    oppure azzera serie e cronologia. Il bottone circolare Occhio NON viene composto
 *    per le serie (stessa funzione, nessun doppione);
 *  - per i film: bottone circolare **Occhio** (toggle visto / non visto del titolo);
 *  - bottone circolare **Trailer** (ciak con play, solo icona senza etichetta: apre il
 *    trailer interno all'app, mai Intent esterno);
 *  - bottone circolare **Seleziona fonte** (SOLO quando [onSelectSource] non è null,
 *    cioè in modalità Debrid: apre la schermata di scelta manuale della sorgente).
 *
 * Catena di focus esplicita:
 *  - serie: `Riproduci -> Cuore -> Serie già vista -> Trailer -> [Seleziona fonte]`;
 *  - film:  `Riproduci -> Cuore -> Occhio -> Trailer -> [Seleziona fonte]`.
 * Con [onSelectSource] null (modalità HTTP o canale live) il pulsante non esiste e la
 * catena resta quella storica a quattro controlli, con Trailer che esce a destra verso
 * [rightTarget]. Gli argomenti `leftTarget` / `rightTarget` / `downTarget` collegano la
 * barra al resto della schermata (es. a destra la prima sorgente della lista) lasciando
 * non impostate le direzioni non specificate, cosi' la ricerca naturale del focus
 * continua a funzionare. Con `upTarget = FocusRequester.Cancel` la freccia SU non esce
 * mai dalla barra: sopra la pulsantiera non esiste nessun elemento focusabile.
 *
 * `onFocusChanged` riporta se il focus e' in uno qualunque dei controlli
 * (serve al chiamante per congelare lo scroll mentre ci si muove in orizzontale),
 * mentre `onPlayFocusChanged` segnala specificamente quando il focus atterra sul
 * pulsante **Riproduci**, primo nodo focusabile della scheda.
 */
@Composable
fun MediaActionBar(
  playLabel: String,
  onPlay: () -> Unit,
  isFavorite: Boolean,
  onToggleFavorite: () -> Unit,
  isWatched: Boolean,
  onToggleWatched: () -> Unit,
  onPlayTrailer: () -> Unit,
  modifier: Modifier = Modifier,
  /**
   * Bottone «Serie già vista» (solo serie TV): marca TUTTA la serie, non il singolo
   * titolo. `null` = pulsante non composto (film, canali live).
   */
  onToggleSeriesWatched: (() -> Unit)? = null,
  isSeriesWatched: Boolean = false,
  focus: MediaActionBarFocus = remember { MediaActionBarFocus() },
  onSelectSource: (() -> Unit)? = null,
  leftTarget: FocusRequester? = null,
  rightTarget: FocusRequester? = null,
  downTarget: FocusRequester? = null,
  upTarget: FocusRequester? = null,
  onFocusChanged: (Boolean) -> Unit = {},
  onPlayFocusChanged: (Boolean) -> Unit = {}
) {
  Row(
    modifier = modifier.onFocusChanged { onFocusChanged(it.hasFocus) },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    TvActionButton(
      text = playLabel,
      icon = Icons.Default.PlayArrow,
      isPrimary = true,
      // "Riproduci": focusRequester + focusable sullo stesso nodo (TvFocusableBox),
      // e' il primo nodo focusabile della scheda dettagli.
      modifier = Modifier
        .actionFocus(
          requester = focus.play,
          leftTarget = leftTarget,
          rightTarget = focus.favorite,
          downTarget = downTarget,
          upTarget = upTarget
        )
        .onFocusChanged { onPlayFocusChanged(it.isFocused) },
      onClick = onPlay
    )

    CircularMediaAction(
      onClick = onToggleFavorite,
      contentDescription = stringResource(
        if (isFavorite) R.string.detail_remove_favorite else R.string.detail_add_favorite
      ),
      tint = if (isFavorite) Color(0xFFF43F5E) else NovaTextSecondary,
      modifier = Modifier.actionFocus(
        requester = focus.favorite,
        leftTarget = focus.play,
        rightTarget = if (onToggleSeriesWatched != null) focus.seriesWatched else focus.watched,
        downTarget = downTarget,
        upTarget = upTarget
      )
    ) { color ->
      Icon(
        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(24.dp)
      )
    }

    // «Serie già vista»: SOLO per le serie TV, subito a destra del cuore. È un'azione
    // esplicita di SERIE (tutti gli episodi noti + conclusione del titolo), distinta
    // dall'occhio che tocca il solo stato del titolo. Etichetta visibile perché il
    // telecomando non espone tooltip: l'utente deve leggere cosa sta marcando.
    if (onToggleSeriesWatched != null) {
      SeriesWatchedActionButton(
        isWatched = isSeriesWatched,
        onClick = onToggleSeriesWatched,
        modifier = Modifier.actionFocus(
          requester = focus.seriesWatched,
          leftTarget = focus.favorite,
          // Per le serie l'occhio circolare non esiste: dalla pillola si passa
          // direttamente al Trailer.
          rightTarget = focus.trailer,
          downTarget = downTarget,
          upTarget = upTarget
        )
      )
    } else {
      // Occhio circolare: SOLO per i film. Per le serie la stessa funzione vive nella
      // pillola «Serie già vista», che è l'unico controllo «visto» della scheda.
      CircularMediaAction(
        onClick = onToggleWatched,
        contentDescription = stringResource(
          if (isWatched) R.string.detail_action_mark_unwatched else R.string.detail_action_mark_watched
        ),
        tint = if (isWatched) NovaGreen else NovaTextSecondary,
        modifier = Modifier.actionFocus(
          requester = focus.watched,
          leftTarget = focus.favorite,
          rightTarget = focus.trailer,
          downTarget = downTarget,
          upTarget = upTarget
        )
      ) { color ->
        Icon(
          imageVector = if (isWatched) Icons.Default.Visibility else Icons.Default.VisibilityOff,
          contentDescription = null,
          tint = color,
          modifier = Modifier.size(24.dp)
        )
      }
    }

    // Trailer interno: stesso cerchio blu notte degli altri pulsanti, con ciak + play e
    // senza etichetta (l'azione resta identificata solo dall'icona e dal contentDescription
    // di accessibilità). A destra esce verso "Seleziona fonte" quando esiste, altrimenti
    // verso il resto della scheda.
    CircularMediaAction(
      onClick = onPlayTrailer,
      contentDescription = stringResource(R.string.detail_action_trailer),
      tint = NovaTextSecondary,
      modifier = Modifier.actionFocus(
        requester = focus.trailer,
        leftTarget = if (onToggleSeriesWatched != null) focus.seriesWatched else focus.watched,
        rightTarget = if (onSelectSource != null) focus.selectSource else rightTarget,
        downTarget = downTarget,
        upTarget = upTarget
      )
    ) { color ->
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Default.MovieCreation,
          contentDescription = null,
          tint = color,
          modifier = Modifier.size(25.dp)
        )
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          tint = color,
          modifier = Modifier.size(12.dp)
        )
      }
    }

    // Selezione manuale della sorgente (solo modalità Debrid): cerchio standard con
    // icona di regolazione; la scelta della fonte parte SOLO da una conferma utente.
    if (onSelectSource != null) {
      CircularMediaAction(
        onClick = onSelectSource,
        contentDescription = stringResource(R.string.detail_action_select_source),
        tint = NovaTextSecondary,
        modifier = Modifier.actionFocus(
          requester = focus.selectSource,
          leftTarget = focus.trailer,
          rightTarget = rightTarget,
          downTarget = downTarget,
          upTarget = upTarget
        )
      ) { color ->
        Icon(
          imageVector = Icons.Default.Tune,
          contentDescription = null,
          tint = color,
          modifier = Modifier.size(24.dp)
        )
      }
    }
  }
}

/**
 * Pillola «Serie già vista» / «Serie vista»: stessa geometria del pulsante Riproduci
 * (pillola, non cerchio) così l'etichetta resta leggibile dal divano. L'icona è
 * l'occhio (aperto quando la serie è vista, barrato quando è da vedere), la stessa
 * che aveva il vecchio bottone circolare. Verde quando la serie risulta conclusa,
 * ciano quando è focalizzata.
 */
@Composable
private fun SeriesWatchedActionButton(
  isWatched: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val label = stringResource(
    if (isWatched) R.string.detail_series_watched_badge else R.string.detail_action_mark_series_watched
  )
  val description = stringResource(
    if (isWatched) R.string.detail_action_mark_series_unwatched else R.string.detail_action_mark_series_watched
  )
  TvFocusableBox(
    modifier = modifier,
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    onClick = onClick
  ) { isFocused ->
    val contentColor = when {
      isWatched -> NovaGreen
      isFocused -> NovaCyanBright
      else -> NovaTextPrimary
    }
    Row(
      modifier = Modifier
        .background(
          if (isFocused) NovaCyan.copy(alpha = 0.22f) else Color(0xFF1E2638),
          RoundedCornerShape(50)
        )
        .border(
          width = 1.dp,
          color = if (isFocused) NovaCyanBright else Color(0x33475569),
          shape = RoundedCornerShape(50)
        )
        .padding(horizontal = 16.dp, vertical = 9.dp)
        .semantics { this.contentDescription = description },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      Icon(
        // Stessa icona dell'ex bottone circolare Occhio: la pillola è l'unico controllo
        // «visto» delle serie.
        imageVector = if (isWatched) Icons.Default.Visibility else Icons.Default.VisibilityOff,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(16.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = label,
        color = contentColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

/**
 * Bottone circolare di azione rapida (stile 10-foot UI): 48dp, zoom + bordo luminoso
 * quando focalizzato, attivabile con OK/INVIO del telecomando.
 */
@Composable
private fun CircularMediaAction(
  onClick: () -> Unit,
  contentDescription: String,
  tint: Color,
  modifier: Modifier = Modifier,
  background: Color = NovaSurfaceVariant,
  content: @Composable (color: Color) -> Unit
) {
  // Alias locale: dentro `semantics { }` il nome `contentDescription` e' la proprieta'
  // del receiver, non il parametro.
  val label = contentDescription
  TvFocusableBox(
    modifier = modifier,
    shape = CircleShape,
    focusedScale = 1.1f,
    borderWidth = 2.dp,
    onClick = onClick
  ) { isFocused ->
    Box(
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .semantics { this.contentDescription = label }
        .background(if (isFocused) NovaCyan.copy(alpha = 0.35f) else background)
        .border(
          width = if (isFocused) 2.dp else 1.dp,
          color = if (isFocused) Color.Transparent else NovaCyan.copy(alpha = 0.45f),
          shape = CircleShape
        ),
      contentAlignment = Alignment.Center
    ) {
      content(if (isFocused && tint == NovaTextSecondary) NovaCyanBright else tint)
    }
  }
}

/** Dichiarazione focus di un controllo della barra azioni (direzioni non usate: non impostate). */
private fun Modifier.actionFocus(
  requester: FocusRequester,
  leftTarget: FocusRequester? = null,
  rightTarget: FocusRequester? = null,
  downTarget: FocusRequester? = null,
  upTarget: FocusRequester? = null
): Modifier = this
  .focusRequester(requester)
  .focusProperties {
    leftTarget?.let { left = it }
    rightTarget?.let { right = it }
    downTarget?.let { down = it }
    upTarget?.let { up = it }
  }
