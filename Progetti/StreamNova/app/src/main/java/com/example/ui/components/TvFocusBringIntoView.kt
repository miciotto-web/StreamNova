package com.example.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * Bring-into-view spec TV condiviso per contenitori scrollabili (LazyVerticalGrid, LazyColumn).
 *
 * Risolve il problema dello scroll verticale a scatti o asimmetrico:
 * - Nelle griglie verticali (LazyVerticalGrid) con elementi che occupano >= 40% del viewport
 *   (es. le poster card dei film/serie TV), la riga focalizzata viene sempre centrata
 *   verticalmente nell'area utile del viewport: `(containerSize - size) / 2`.
 *   Questo garantisce che la riga focalizzata sia pienamente visibile e impedisce che le card
 *   della riga precedente restino tagliate o visibili solo parzialmente nella parte superiore.
 *
 * - Quando ci si sposta in orizzontale (LEFT/RIGHT) tra card della STESSA riga:
 *   il calcolo verticale restituisce ESATTAMENTE 0f, azzerando qualsiasi micro-sobbalzo
 *   o ricalcolo dell'offset verticale.
 *
 * - Nei caroselli orizzontali e nelle righe di chip (elementi con larghezza < 40% del viewport),
 *   se l'elemento è già interamente visibile non viene eseguito alcuno scroll (0f).
 *   Quando esce dai bordi del viewport, viene applicato il pivot TV al 30% (`ParentFraction`),
 *   preservando inalterato il comportamento nativo dello scroll orizzontale.
 */
@OptIn(ExperimentalFoundationApi::class)
open class TvFocusBringIntoViewSpec(
  private val parentFraction: Float = 0.3f
) : BringIntoViewSpec {

  companion object : TvFocusBringIntoViewSpec()

  private var lastRowTop: Float? = null

  override val scrollAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = 150, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))

  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    if (size <= 0f || containerSize <= 0f) return 0f

    val trailingEdge = offset + size

    // Tolleranza minima per precisione subpixel float (1px)
    val isCompletelyVisible = (offset >= -1f) && (trailingEdge <= containerSize + 1f)

    // Elementi orizzontali/piccoli (card caroselli orizzontali e chip, larghi < 40% del viewport):
    // se già interamente visibili non provocano alcuno scroll (0f). Quando escono dal viewport,
    // usano il pivot TV standard (30% del viewport) per preservare lo scroll orizzontale.
    if (size <= containerSize * 0.4f) {
      if (isCompletelyVisible) return 0f
      val targetLeadingEdge = (parentFraction * containerSize).coerceAtLeast(0f)
      return offset - targetLeadingEdge
    }

    // Elementi verticali/griglie (LazyVerticalGrid, altezza >= 40% del viewport):
    val centeredLeadingEdge = ((containerSize - size) / 2f).coerceAtLeast(0f)

    // Caso A: Row 0 ancorata all'inizio del contenitore scrollabile.
    // L'elemento si trova già nell'area superiore valida (offset >= -1f e offset <= centeredLeadingEdge)
    // e non può/non deve scrollare all'indietro. Restituisce esattamente 0f per eliminare
    // qualsiasi sobbalzo o tentativo di scroll negativo negli spostamenti LEFT/RIGHT su Row 0.
    if (offset >= -1f && offset <= centeredLeadingEdge) {
      lastRowTop = offset
      return 0f
    }

    // Caso B: Elementi della STESSA riga già posizionata / centrata.
    // Tutte le card della stessa riga condividono lo stesso allineamento superiore (offset).
    // Evitiamo di ricalcolare l'offset di centratura per le variazioni di altezza dovute a titoli
    // su 1 o 2 righe (differenza top <= 10px): restituisce ESATTAMENTE 0f senza alcuno scroll verticale.
    val prevTop = lastRowTop
    if (prevTop != null && kotlin.math.abs(offset - prevTop) <= 10f) {
      return 0f
    }

    // Caso C: Navigazione effettiva tra righe diverse (DOWN / UP):
    // La nuova riga deve essere centrata verticalmente nell'area utile del viewport.
    val scrollDistance = offset - centeredLeadingEdge
    lastRowTop = centeredLeadingEdge
    return scrollDistance
  }
}

/**
 * Applica [TvFocusBringIntoViewSpec] a un contenitore scrollabile (es. LazyVerticalGrid).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocusBringIntoView(content: @Composable () -> Unit) {
  val spec = remember { TvFocusBringIntoViewSpec() }
  CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
    content()
  }
}
