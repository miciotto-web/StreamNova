package com.example.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Utilità per il ripristino del focus su una card specifica all'uscita dal Dettaglio.
 *
 * Perché non basta `FocusRequester.requestFocus()`:
 * - in Compose UI 1.7.x (versione risolta da questo progetto) restituisce `Unit`: la
 *   richiesta viene inoltrata ma NON espone alcun esito booleano;
 * - se il nodo focus non è (ancora) agganciato lancia `IllegalStateException`
 *   ("FocusRequester is not initialized");
 * - una richiesta accettata non garantisce che il focus sia finito sulla card: l'unica
 *   prova è l'evento `onFocusChanged` con `isFocused = true`.
 *
 * Da qui il protocollo: la card inoltra l'esito reale in [FocusRestoreConfirmation] e il
 * tentativo ripete la richiesta entro un numero limitato di volte, attendendo i FRAME
 * (nessun ritardo arbitrario e nessun loop infinito).
 */

/**
 * Regola di attivazione di un tentativo di ripristino per una singola card.
 *
 * Il tentativo parte solo se la card è quella del target E se per il token dell'evento
 * corrente non è già stato eseguito un tentativo. È il TOKEN (non l'id) a riattivare il
 * tentativo: così un nuovo evento di ripristino funziona anche quando `targetMediaId` è
 * rimasto identico (ritorni consecutivi dalla stessa locandina).
 */
fun shouldAttemptFocusRestore(
  cardMediaId: String,
  targetMediaId: String?,
  restoreToken: Int,
  attemptedToken: Int?
): Boolean =
  targetMediaId != null &&
    targetMediaId == cardMediaId &&
    attemptedToken != restoreToken

/**
 * Esito reale di un tentativo di ripristino: diventa `true` solo quando la card riceve
 * davvero il focus (`onFocusChanged`), non quando la richiesta viene semplicemente inviata.
 */
@Stable
class FocusRestoreConfirmation {
  private val confirmedState = mutableStateOf(false)

  val isConfirmed: Boolean
    get() = confirmedState.value

  /** Azzera l'esito prima di un nuovo tentativo. */
  fun reset() {
    confirmedState.value = false
  }

  /** Da invocare dentro `Modifier.onFocusChanged` della card. */
  fun onFocusChanged(isFocused: Boolean) {
    if (isFocused) confirmedState.value = true
  }
}

/**
 * Richiede il focus e attende la CONFERMA reale, con tentativi limitati.
 *
 * Ogni tentativo attende al massimo [maxFramesPerAttempt] frame per la conferma; se il
 * requester non è agganciato (card non ancora composta) si ritenta al frame successivo.
 *
 * @return `true` solo se [confirmation] ha registrato il focus reale sulla card.
 */
suspend fun FocusRequester.requestFocusConfirmed(
  confirmation: FocusRestoreConfirmation,
  maxAttempts: Int = 3,
  maxFramesPerAttempt: Int = 2
): Boolean {
  var attempt = 0
  while (attempt < maxAttempts) {
    attempt++
    confirmation.reset()
    try {
      requestFocus()
    } catch (_: Exception) {
      // Nodo focus non ancora agganciato: un frame e si ritenta (nessun delay arbitrario).
      withFrameNanos { }
      continue
    }
    if (confirmation.awaitConfirmed(maxFramesPerAttempt)) {
      return true
    }
  }
  return false
}

private suspend fun FocusRestoreConfirmation.awaitConfirmed(maxFrames: Int): Boolean {
  var frame = 0
  while (frame < maxFrames) {
    if (isConfirmed) return true
    withFrameNanos { }
    frame++
  }
  return isConfirmed
}

/**
 * Porta la cella [index] nel viewport e VERIFICA che sia davvero composta e visibile.
 *
 * Usa `scrollToItem` (istantaneo) invece di `animateScrollToItem`: un'animazione può essere
 * interrotta da un'altra mutazione della lista ("Mutation interrupted") lasciando la griglia
 * lontana dal target. La visibilità è verificata sul `layoutInfo` reale dopo un frame di
 * layout, quindi il fallimento è rilevato e ritentato invece di essere assunto.
 *
 * @return `true` se la cella risulta tra gli elementi visibili della griglia.
 */
suspend fun LazyGridState.scrollToItemVerified(
  index: Int,
  maxAttempts: Int = 3,
  maxFramesBeforeCheck: Int = 2
): Boolean {
  var attempt = 0
  while (attempt < maxAttempts) {
    attempt++
    try {
      scrollToItem(index)
    } catch (_: Exception) {
      withFrameNanos { }
      continue
    }
    repeat(maxFramesBeforeCheck) {
      if (layoutInfo.visibleItemsInfo.any { it.index == index }) return true
      withFrameNanos { }
    }
    if (layoutInfo.visibleItemsInfo.any { it.index == index }) return true
  }
  return false
}
