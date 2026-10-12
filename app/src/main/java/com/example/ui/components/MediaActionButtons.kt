package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextSecondary

/**
 * FocusRequester dei quattro controlli rapidi della scheda.
 *
 * Permette alla navigazione D-pad di incatenare i pulsanti nell'ordine
 * `Riproduci -> Cuore -> Occhio -> Trailer` e di uscire a destra verso la lista
 * delle sorgenti (o verso il resto della scheda).
 */
class MediaActionBarFocus {
  val play = FocusRequester()
  val favorite = FocusRequester()
  val watched = FocusRequester()
  val trailer = FocusRequester()
}

/**
 * Barra azioni della scheda dettagli / selezione sorgenti.
 *
 * Riga orizzontale composta da:
 *  - pillola evidenziata **Riproduci** (avvia la riproduzione o focalizza l'elenco sorgenti);
 *  - bottone circolare **Cuore** (toggle preferiti);
 *  - bottone circolare **Occhio** (toggle visto / non visto);
 *  - bottone circolare **YouTube** (trailer interno all'app, mai Intent esterno).
 *
 * Catena di focus esplicita: `Riproduci -> Cuore -> Occhio -> Trailer`. Gli argomenti
 * `leftTarget` / `rightTarget` / `downTarget` collegano la barra al resto della
 * schermata (es. a destra la prima sorgente della lista) lasciando non impostate le
 * direzioni non specificate, cosi' la ricerca naturale del focus continua a funzionare.
 * Con `upTarget = FocusRequester.Cancel` la freccia SU non esce mai dalla barra:
 * sopra la pulsantiera non esiste nessun elemento focusabile.
 *
 * `onFocusChanged` riporta se il focus e' in uno qualunque dei quattro controlli
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
  focus: MediaActionBarFocus = remember { MediaActionBarFocus() },
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
        rightTarget = focus.watched,
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

    // Trailer interno: cerchio rosso stile YouTube con play bianco.
    CircularMediaAction(
      onClick = onPlayTrailer,
      contentDescription = stringResource(R.string.detail_action_trailer),
      tint = Color.White,
      background = Color(0xFFE5322D),
      modifier = Modifier.actionFocus(
        requester = focus.trailer,
        leftTarget = focus.watched,
        rightTarget = rightTarget,
        downTarget = downTarget,
        upTarget = upTarget
      )
    ) { color ->
      Icon(
        imageVector = Icons.Default.PlayArrow,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(26.dp)
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
