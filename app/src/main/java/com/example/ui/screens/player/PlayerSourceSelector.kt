package com.example.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.streaming.StreamSource
import com.example.ui.components.providerLabelOf
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay

/**
 * Riga del selettore sorgenti del player.
 *
 * @param source sorgente effettiva (payload della selezione).
 * @param providerLabel nome del provider (addon o server).
 * @param qualityLabel qualita' dichiarata, null quando non nota.
 * @param label testo mostrato nella riga ("Provider · 1080p").
 * @param isCurrent true quando la riga e' la sorgente in riproduzione.
 */
data class PlayerSourceEntry(
  val source: StreamSource,
  val providerLabel: String,
  val qualityLabel: String?,
  val label: String,
  val isCurrent: Boolean
)

/**
 * Logica (pura) del selettore sorgenti interno al player.
 *
 * Tutte le funzioni sono pure e deterministiche: sono il contratto verificabile
 * del selettore (etichette, ordinamento, riconoscimento della sorgente attuale)
 * e non dipendono dal framework Compose.
 */
object PlayerSourceSelector {

  /**
   * Qualita' mostrata per la sorgente, oppure null quando non nota.
   *
   * Il valore "Auto" (default del provider) NON e' una qualita' nota: in quel
   * caso la riga mostra solo il nome del provider.
   */
  fun qualityLabelOf(source: StreamSource): String? {
    val raw = source.declaredQuality.takeIf { it.isNotBlank() } ?: source.quality
    val cleaned = raw.trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.equals("auto", ignoreCase = true)) return null
    return cleaned
  }

  /**
   * Etichetta della riga: nome del provider e, quando nota, la qualita'.
   *
   * Nessun dato viene inventato: se il provider e' ignoto si mostra la release,
   * altrimenti il solo URL (ultimo fallback utile per l'utente).
   */
  fun labelOf(source: StreamSource): String {
    val provider = providerLabelOf(source).trim()
    val quality = qualityLabelOf(source)
    return when {
      provider.isNotBlank() && quality != null -> "$provider · $quality"
      provider.isNotBlank() -> provider
      quality != null -> quality
      else -> source.releaseTitle?.takeIf { it.isNotBlank() }
        ?: source.streamUrl?.takeIf { it.isNotBlank() }
        ?: ""
    }
  }

  /**
   * Verifica se due sorgenti sono la stessa sorgente logica.
   *
   * La sorgente in riproduzione puo' essere stata risolta (es. sblocco TorBox
   * on-demand) e quindi non coincide piu' per uguaglianza strutturale con la
   * voce della lista: si confrontano quindi infoHash/URL e metadati principali.
   */
  fun isSameSource(a: StreamSource?, b: StreamSource): Boolean {
    if (a == null) return false
    if (a == b) return true
    val aHash = a.infoHash
    val bHash = b.infoHash
    if (!aHash.isNullOrBlank() && !bHash.isNullOrBlank()) {
      return aHash == bHash && a.serverName == b.serverName
    }
    val aUrl = a.streamUrl?.takeIf { it.isNotBlank() }
    val bUrl = b.streamUrl?.takeIf { it.isNotBlank() }
    if (!aUrl.isNullOrBlank() && !bUrl.isNullOrBlank()) {
      return aUrl == bUrl && (a.serverName == b.serverName || a.quality == b.quality)
    }
    // Senza URL (es. torrent ancora da sbloccare) il confronto resta sui
    // metadati dichiarati dal provider.
    return a.serverName == b.serverName && a.quality == b.quality &&
      a.releaseTitle == b.releaseTitle
  }

  /** Indice della sorgente in riproduzione nella lista, -1 se non presente. */
  fun currentIndexOf(sources: List<StreamSource>, current: StreamSource?): Int {
    if (current == null) return -1
    val exact = sources.indexOfFirst { it == current }
    if (exact >= 0) return exact
    return sources.indexOfFirst { isSameSource(current, it) }
  }

  /** Costruisce le righe del selettore, marcando quella in riproduzione. */
  fun buildEntries(sources: List<StreamSource>, current: StreamSource?): List<PlayerSourceEntry> {
    val currentIndex = currentIndexOf(sources, current)
    return sources.mapIndexed { index, source ->
      PlayerSourceEntry(
        source = source,
        providerLabel = providerLabelOf(source).trim(),
        qualityLabel = qualityLabelOf(source),
        label = labelOf(source),
        isCurrent = index == currentIndex
      )
    }
  }
}

/**
 * Selettore delle sorgenti video mostrato DENTRO il player.
 *
 * Comportamento:
 *  - elenca le sorgenti valide disponibili per il titolo corrente (provider e
 *    qualita' quando nota);
 *  - evidenzia la sorgente attualmente in riproduzione;
 *  - su Android TV e' navigabile col D-pad (focus visibile, focus iniziale sulla
 *    sorgente in riproduzione) e su mobile e' utilizzabile col touch.
 *
 * La chiusura (BACK o tap fuori) NON avvia nessun cambio sorgente: la scelta
 * avviene solo toccando/premendo OK su una riga.
 */
@Composable
fun PlayerSourceSelectorDialog(
  entries: List<PlayerSourceEntry>,
  onSelect: (StreamSource) -> Unit,
  onDismiss: () -> Unit
) {
  // Focus iniziale: la sorgente in riproduzione (o la prima voce disponibile).
  val initialIndex = remember(entries) {
    val current = entries.indexOfFirst { it.isCurrent }
    if (current >= 0) current else 0
  }
  // I requester sono stabili rispetto ai cambi della lista: quando arrivano
  // nuove sorgenti (aggiornamento progressivo) il nodo con il focus non deve
  // essere smontato, altrimenti la navigazione D-pad si perde.
  val rowRequesters = remember { mutableMapOf<Int, FocusRequester>() }
  var focusApplied by remember { mutableStateOf(false) }
  val listState = rememberScrollState()

  LaunchedEffect(initialIndex) {
    // Il focus iniziale viene applicato una sola volta: gli aggiornamenti
    // progressivi della lista non devono rubare il focus all'utente.
    if (focusApplied) return@LaunchedEffect
    // Il nodo potrebbe non essere ancora agganciato nel primo frame: si riprova
    // qualche volta prima di desistere (stesso schema degli altri dialog).
    repeat(8) {
      val requester = rowRequesters[initialIndex]
      if (requester != null) {
        try {
          requester.requestFocus()
          focusApplied = true
          return@LaunchedEffect
        } catch (_: IllegalStateException) {
          delay(40)
        }
      }
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .width(460.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(22.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(R.string.player_source_selector_title),
          color = NovaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        // Chiudi: raggiungibile sia col D-pad (Android TV) sia col tocco.
        var closeFocused by remember { mutableStateOf(false) }
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_close),
          tint = if (closeFocused) NovaCyanBright else NovaTextSecondary,
          modifier = Modifier
            .size(22.dp)
            .onFocusChanged { closeFocused = it.isFocused }
            .focusable()
            .clickable { onDismiss() }
        )
      }

      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.player_source_selector_subtitle, entries.size),
        color = NovaTextSecondary,
        fontSize = 12.sp
      )
      Spacer(modifier = Modifier.height(14.dp))

      if (entries.isEmpty()) {
        Text(
          text = stringResource(R.string.player_source_selector_empty),
          color = NovaTextSecondary,
          fontSize = 13.sp
        )
      } else {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .verticalScroll(listState),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          entries.forEachIndexed { index, entry ->
            val rowRequester = rowRequesters.getOrPut(index) { FocusRequester() }
            PlayerSourceRow(
              entry = entry,
              modifier = Modifier.focusRequester(rowRequester),
              onClick = { onSelect(entry.source) }
            )
          }
        }
      }
    }
  }
}

/**
 * Riga singola del selettore.
 *
 * La riga e' sia `focusable()` (D-pad su Android TV) sia `clickable` (touch su
 * mobile): `TvFocusableBox` infatti risponde solo ai tasti di conferma e non
 * al tocco.
 */
@Composable
private fun PlayerSourceRow(
  entry: PlayerSourceEntry,
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  var isFocused by remember { mutableStateOf(false) }
  val background = when {
    isFocused -> NovaCyan.copy(alpha = 0.18f)
    entry.isCurrent -> NovaSurfaceVariant
    else -> Color.Transparent
  }
  val borderColor = when {
    isFocused -> NovaCyanBright
    entry.isCurrent -> NovaCyan.copy(alpha = 0.6f)
    else -> Color.Transparent
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(background, RoundedCornerShape(12.dp))
      .border(1.dp, borderColor, RoundedCornerShape(12.dp))
      .onFocusChanged { isFocused = it.isFocused }
      .focusable()
      .clickable { onClick() }
      .padding(horizontal = 14.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Box(
      modifier = Modifier
        .size(8.dp)
        .background(
          if (entry.isCurrent) NovaGreen else NovaTextSecondary.copy(alpha = 0.5f),
          RoundedCornerShape(50)
        )
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.label,
        color = if (isFocused || entry.isCurrent) NovaCyanBright else NovaTextPrimary,
        fontSize = 14.sp,
        fontWeight = if (entry.isCurrent) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (entry.isCurrent) {
        Text(
          text = stringResource(R.string.player_source_selector_current),
          color = NovaGreen,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
    if (entry.isCurrent) {
      Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = stringResource(R.string.player_cd_selected),
        tint = NovaCyanBright,
        modifier = Modifier.size(18.dp)
      )
    }
  }
}
