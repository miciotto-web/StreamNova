package com.example.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Bring-into-view spec TV condiviso.
 *
 * Su TV Compose usa di default `PivotBringIntoViewSpec`, che a ogni cambio di focus
 * scrolla il contenitore per portare il bordo dell'elemento al 30% dell'asse. Nelle
 * griglie questo provoca un micro-scroll verticale quando LEFT/RIGHT cambia card
 * nella stessa riga.
 *
 * Questo spec non esegue alcuno scroll se l'elemento focalizzato è già interamente
 * visibile, mantenendo il comportamento "pivot" TV solo per gli elementi fuori viewport
 * (così UP/DOWN continua a scorrere normalmente).
 */
@OptIn(ExperimentalFoundationApi::class)
object TvFocusBringIntoViewSpec : BringIntoViewSpec {
  private const val ParentFraction = 0.3f

  override val scrollAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = 150, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))

  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    if (size <= 0f || containerSize <= 0f) return 0f

    val trailingEdge = offset + size

    // Tolleranza minima per precisione subpixel float (1px)
    val isCompletelyVisible = (offset >= -1f) && (trailingEdge <= containerSize + 1f)

    // Se l'elemento è realmente e completamente visibile all'interno del viewport:
    // NESSUNO SCROLL (0f).
    // Questo garantisce che durante lo spostamento LEFT/RIGHT tra le card della stessa riga
    // (che si trovano alla medesima coordinata verticale), non avvenga alcun micro-scroll verticale.
    if (isCompletelyVisible) {
      return 0f
    }

    // Se l'elemento è parzialmente fuori dal viewport (navigazione UP/DOWN):
    // Calcoliamo la posizione di destinazione al pivot TV (30% del viewport),
    // assicurando che l'intero elemento rientri sempre completamente nello schermo.
    val targetLeadingEdge = minOf(ParentFraction * containerSize, maxOf(0f, containerSize - size))

    // Se il bordo superiore è fuori viewport (offset < 0) -> scrolla verso l'alto (valore negativo).
    // Se il bordo inferiore è fuori viewport (trailingEdge > containerSize) -> scrolla verso il basso (valore positivo).
    return offset - targetLeadingEdge
  }
}

/**
 * Applica [TvFocusBringIntoViewSpec] a un contenitore scrollabile (es. LazyVerticalGrid).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocusBringIntoView(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalBringIntoViewSpec provides TvFocusBringIntoViewSpec) {
    content()
  }
}
