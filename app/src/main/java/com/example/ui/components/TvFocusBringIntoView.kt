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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bring-into-view spec TV condiviso per contenitori scrollabili (LazyVerticalGrid, LazyColumn).
 *
 * Risolve i problemi di navigazione TV con telecomando (D-pad):
 * 1. Risalita verticale (D-Pad UP): garantisce che la prima riga di poster o qualsiasi card
 *    mantenga sempre visibile l'header con il titolo della sezione (offset di almeno 48-64dp).
 *    Il titolo della sezione non viene mai tagliato né spinto fuori dallo schermo.
 * 2. Spostamenti orizzontali (LEFT/RIGHT): tra card della STESSA riga, il calcolo verticale
 *    restituisce ESATTAMENTE 0f, azzerando qualsiasi micro-sobbalzo o scatto verticale.
 * 3. Discesa verticale (D-Pad DOWN): porta la riga in vista garantendo margine inferiore adeguato.
 */
@OptIn(ExperimentalFoundationApi::class)
open class TvFocusBringIntoViewSpec(
  private val parentFraction: Float = 0.3f,
  private val topHeaderOffsetPx: Float = 96f,
  private val bottomMarginPx: Float = 48f
) : BringIntoViewSpec {

  companion object : TvFocusBringIntoViewSpec(
    parentFraction = 0.3f,
    topHeaderOffsetPx = 96f,
    bottomMarginPx = 48f
  )

  override val scrollAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = 150, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))

  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    if (size <= 0f || containerSize <= 0f) return 0f

    val trailingEdge = offset + size

    // Tolleranza minima per precisione subpixel float (1px)
    // Se l'elemento e il suo margine sopra per l'header sono già completamente visibili nel viewport,
    // azzeriamo lo scroll (0f) per evitare sobbalzi durante spostamenti LEFT/RIGHT o ricalcoli di scroll.
    val isCompletelyVisible = (offset >= topHeaderOffsetPx - 1f) && (trailingEdge <= containerSize - bottomMarginPx + 1f)
    if (isCompletelyVisible) {
      return 0f
    }

    // Caso A: L'elemento si trova troppo in alto o risale verso l'alto (D-Pad UP).
    // Per non tagliare o far sparire l'header/titolo della sezione, manteniamo sempre
    // almeno `topHeaderOffsetPx` sopra la card focalizzata.
    if (offset < topHeaderOffsetPx) {
      return offset - topHeaderOffsetPx
    }

    // Caso B: L'elemento si trova troppo in basso (D-Pad DOWN).
    // Scrolliamo verso il basso per mostrare la card completa con margine inferiore.
    if (trailingEdge > containerSize - bottomMarginPx) {
      val scrollDownDistance = trailingEdge - (containerSize - bottomMarginPx)
      // Evitiamo che uno scroll down eccessivo spinga la parte alta della riga oltre topHeaderOffsetPx
      val maxScrollDown = (offset - topHeaderOffsetPx).coerceAtLeast(0f)
      return if (size > (containerSize - topHeaderOffsetPx - bottomMarginPx)) {
        offset - topHeaderOffsetPx
      } else {
        scrollDownDistance.coerceAtMost(maxScrollDown)
      }
    }

    return 0f
  }
}

/**
 * Applica [TvFocusBringIntoViewSpec] a un contenitore scrollabile (es. LazyColumn, LazyVerticalGrid).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocusBringIntoView(
  topOffsetDp: Dp = 64.dp,
  bottomMarginDp: Dp = 48.dp,
  content: @Composable () -> Unit
) {
  val density = LocalDensity.current
  val topOffsetPx = with(density) { topOffsetDp.toPx() }
  val bottomMarginPx = with(density) { bottomMarginDp.toPx() }
  val spec = remember(topOffsetPx, bottomMarginPx) {
    TvFocusBringIntoViewSpec(
      parentFraction = 0.3f,
      topHeaderOffsetPx = topOffsetPx,
      bottomMarginPx = bottomMarginPx
    )
  }
  CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
    content()
  }
}
