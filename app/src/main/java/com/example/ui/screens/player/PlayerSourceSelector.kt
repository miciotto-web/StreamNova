package com.example.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.streaming.StreamSource
import com.example.ui.components.DebridSourceCard
import com.example.ui.components.providerLabelOf
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/** Tag della lista del selettore: usato dai test UI per lo scrolling della finestra. */
internal const val PLAYER_SOURCE_LIST_TEST_TAG = "player_source_selector_list"

/** Tag della card della sorgente all'indice [index] nel selettore del player. */
internal fun playerSourceCardTestTag(index: Int): String = "player_source_card_$index"

/**
 * Riga del selettore sorgenti del player.
 *
 * @param source sorgente effettiva (payload della selezione).
 * @param providerLabel nome del provider (addon o server).
 * @param qualityLabel qualita' dichiarata, null quando non nota.
 * @param label testo breve usato dall'HUD ("Provider · 1080p").
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
   * Etichetta breve della sorgente: nome del provider e, quando nota, la qualita'.
   * Usata dal solo avviso di cambio sorgente dell'HUD, non dalla lista dettagliata.
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
   * L'identita' e' quella REALE della sorgente, non il testo del provider:
   *  1. torrent: `infoHash` + indice file (`fileIdx`) + installazione Stremio
   *     (`addonInstallId`) — distingue due file dello stesso season pack e lo
   *     stesso torrent restituito da due configurazioni diverse dell'addon;
   *  2. sorgente diretta: l'URL riprodotto;
   *  3. fallback (torrent non ancora sbloccato vs URL risolto senza hash): i
   *     metadati dichiarati, inclusi file e installazione.
   *
   * La sorgente in riproduzione puo' essere stata risolta (es. sblocco TorBox
   * on-demand) e quindi non coincide piu' per uguaglianza strutturale con la
   * voce della lista: il confronto per identita' la riconosce comunque.
   */
  fun isSameSource(a: StreamSource?, b: StreamSource): Boolean {
    if (a == null) return false
    if (a == b) return true

    val aHash = a.infoHash?.trim()?.takeIf { it.isNotBlank() }
    val bHash = b.infoHash?.trim()?.takeIf { it.isNotBlank() }
    if (aHash != null && bHash != null) {
      return aHash.equals(bHash, ignoreCase = true) &&
        sameFileIdx(a.fileIdx, b.fileIdx) &&
        sameInstall(a.addonInstallId, b.addonInstallId)
    }

    val aUrl = a.streamUrl?.trim()?.takeIf { it.isNotBlank() }
    val bUrl = b.streamUrl?.trim()?.takeIf { it.isNotBlank() }
    if (aUrl != null && bUrl != null) return aUrl == bUrl

    // Senza identita' forte comune (es. torrent ancora da sbloccare) il confronto
    // resta sui metadati dichiarati dal provider.
    return a.serverName == b.serverName &&
      a.quality == b.quality &&
      a.declaredQuality == b.declaredQuality &&
      a.releaseTitle == b.releaseTitle &&
      a.fileIdx == b.fileIdx &&
      a.addonInstallId == b.addonInstallId
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

  /**
   * Due voci sono lo stesso file solo se entrambe dichiarano l'indice e coincide.
   * Un indice assente non esclude la corrispondenza (fonti che non lo espongono).
   */
  private fun sameFileIdx(a: Int?, b: Int?): Boolean = a == null || b == null || a == b

  /** Stessa installazione Stremio quando entrambe la dichiarano. */
  private fun sameInstall(a: String?, b: String?): Boolean {
    val aInstall = a?.trim()?.takeIf { it.isNotBlank() } ?: return true
    val bInstall = b?.trim()?.takeIf { it.isNotBlank() } ?: return true
    return aInstall == bInstall
  }
}

/**
 * Selettore delle sorgenti video mostrato DENTRO il player.
 *
 * Comportamento:
 *  - elenca le sorgenti valide disponibili per il titolo corrente con gli STESSI
 *    metadati e lo stesso layout della lista pre-playback ([DebridSourceCard]);
 *  - evidenzia la sorgente attualmente in riproduzione (badge "In riproduzione",
 *    bordo ciano, fondo distinto);
 *  - su Android TV e' navigabile col D-pad (focus visibile, focus iniziale sulla
 *    sorgente in riproduzione, la card focalizzata resta sempre visibile) e su
 *    mobile e' utilizzabile col touch.
 *
 * La finestra e' la stessa (titolo, conteggio, chiusura). La lista usa una
 * [LazyColumn] con altezza disponibile massima e scrolling: le card dettagliate
 * hanno altezze variabili e non vengono mai tagliate.
 *
 * La chiusura (BACK o tap fuori) NON avvia nessun cambio sorgente: la scelta
 * avviene solo toccando/premendo OK su una card.
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
  // La lista parte dalla sorgente in riproduzione: la card e' composta (e quindi
  // focalizzabile) gia' al primo frame anche con centinaia di sorgenti.
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
  // I requester sono stabili rispetto ai cambi della lista: quando arrivano
  // nuove sorgenti (aggiornamento progressivo) il nodo con il focus non deve
  // essere smontato, altrimenti la navigazione D-pad si perde.
  val rowRequesters = remember { mutableMapOf<Int, FocusRequester>() }
  var focusApplied by remember { mutableStateOf(false) }
  // Indice della card focalizzata: guida lo scrolling in modo che nessuna card
  // (e nessun metadato) resti fuori dalla finestra.
  var focusedIndex by remember { mutableStateOf(initialIndex) }

  LaunchedEffect(initialIndex, entries.size) {
    // Il focus iniziale viene applicato una sola volta: gli aggiornamenti
    // progressivi della lista non devono rubare il focus all'utente.
    if (focusApplied || entries.isEmpty()) return@LaunchedEffect
    // Il nodo potrebbe non essere ancora agganciato (o composto dalla lista) al
    // primo giro: si riprova per un numero LIMITATO di frame, senza attese fisse
    // che dipendano dalla velocità del dispositivo.
    repeat(12) {
      withFrameNanos { }
      val requester = rowRequesters[initialIndex]
      if (requester != null) {
        try {
          requester.requestFocus()
          focusApplied = true
          return@LaunchedEffect
        } catch (_: IllegalStateException) {
          // Nodo non ancora agganciato: si ritenta al frame successivo.
        }
      }
    }
  }

  // La card focalizzata resta sempre interamente visibile durante la navigazione.
  LaunchedEffect(focusedIndex) {
    if (focusedIndex in entries.indices) {
      listState.animateScrollToItem(focusedIndex)
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    // La card dettagliata ha bisogno di più larghezza del default di piattaforma
    // (~560dp): la finestra usa quindi l'area disponibile, mantenendo il layout.
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 20.dp),
      contentAlignment = Alignment.Center
    ) {
      Column(
        modifier = Modifier
          // Il limite va applicato PRIMA di fillMaxWidth: altrimenti il vincolo
          // minimo impostato da fillMaxWidth renderebbe il limite inefficace.
          .widthIn(max = 780.dp)
          .fillMaxWidth()
          .fillMaxHeight(0.94f)
          .background(NovaSurface, RoundedCornerShape(20.dp))
          .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
          .padding(horizontal = 20.dp, vertical = 18.dp)
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
        Spacer(modifier = Modifier.height(12.dp))

        if (entries.isEmpty()) {
          Text(
            text = stringResource(R.string.player_source_selector_empty),
            color = NovaTextSecondary,
            fontSize = 13.sp
          )
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .testTag(PLAYER_SOURCE_LIST_TEST_TAG),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            // Padding che evita il taglio dello zoom (1.03x) e del bordo focus
            // delle card ai bordi della lista.
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 20.dp)
          ) {
            itemsIndexed(items = entries) { index, entry ->
              val rowRequester = rowRequesters.getOrPut(index) { FocusRequester() }
              DebridSourceCard(
                source = entry.source,
                isCurrent = entry.isCurrent,
                onClick = { onSelect(entry.source) },
                onFocus = { if (it) focusedIndex = index },
                modifier = Modifier
                  .focusRequester(rowRequester)
                  .testTag(playerSourceCardTestTag(index))
              )
            }
          }
        }
      }
    }
  }
}
