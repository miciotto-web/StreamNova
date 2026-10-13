package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Tracker dell'aggancio di un [FocusRequester] alla composizione.
 *
 * Problema risolto: quando una `focusProperties` assegna a `down`/`up`/`left`/`right` un
 * `FocusRequester` il cui nodo focus NON e' montato (cella di una LazyRow/LazyVerticalGrid
 * uscite dal viewport, o non ancora composte), il focus search del D-pad scatena
 * `IllegalStateException: FocusRequester is not initialized`
 * (`FocusOwnerImpl.focusSearch` -> `FocusRequester.findFocusTargetNode`).
 *
 * La presenza in composizione di una cella equivale all'aggancio del suo nodo focus (stesso
 * batch di applyChanges), quindi un [DisposableEffect] sul contenuto della cella e' un
 * proxy affidabile dello stato di aggancio del requester applicato con
 * [Modifier.tvFocusRequester].
 *
 * Uso:
 * ```
 * val firstItemAttachment = remember { TvFocusAttachment() }
 * // nel contenuto della cella (es. inside itemsIndexed di una Lazy grid):
 *   Modifier.tvFocusRequester(firstItemRequester, firstItemAttachment)
 * // nel punto che assegna la navigazione:
 *   .focusProperties {
 *     down = firstItemAttachment.guard(firstItemRequester) ?: FocusRequester.Default
 *   }
 * ```
 */
@Stable
class TvFocusAttachment {
  private val attachedState = mutableStateOf(false)

  /** True solo mentre il nodo focus associato e' montato nella composizione. */
  val isAttached: Boolean
    get() = attachedState.value

  internal fun setAttached(attached: Boolean) {
    attachedState.value = attached
  }
}

/**
 * Restituisce [requester] SOLO se il relativo nodo focus e' attualmente montato;
 * altrimenti `null`, cosi' il chiamante puo' cadere su `FocusRequester.Default`
 * (ricerca standard del D-pad) invece di un requester orfano.
 *
 * Estensione su ricevitore nullable: `attachment?.guard(r)` e `attachment.guard(r)` sono
 * equivalenti quando l'attachment non esiste ancora.
 */
fun TvFocusAttachment?.guard(requester: FocusRequester?): FocusRequester? =
  if (this != null && isAttached) requester else null

/**
 * Applica [requester] alla cella e segnala l'aggancio a [attachment] finche' la cella resta
 * in composizione. Da usare al posto di `Modifier.focusRequester` per i requester che
 * vengono poi passati come target di navigazione (`focusProperties`).
 */
@Composable
fun Modifier.tvFocusRequester(
  requester: FocusRequester,
  attachment: TvFocusAttachment
): Modifier {
  DisposableEffect(attachment, requester) {
    attachment.setAttached(true)
    onDispose { attachment.setAttached(false) }
  }
  return this.focusRequester(requester)
}
