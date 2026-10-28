package com.example.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Geometria condivisa del restyle Neon Glow delle poster card. */
val NeonCardCorner = 14.dp
val NeonCardGlowSpread = 10.dp
val NeonCardBorderWidth = 2.5.dp

/**
 * Alone Neon diffuso: layer di rounded-rect concentrici con alpha decrescente,
 * disegnati anche oltre i bordi. Va richiamato in un [drawBehind] NON clippato: la somma
 * dei layer produce una sfumatura morbida che si estende di [spreadPx] oltre la card.
 */
fun DrawScope.drawNeonGlow(
  cornerRadiusPx: Float,
  color: Color,
  spreadPx: Float,
  intensity: Float
) {
  if (intensity <= 0.01f || spreadPx <= 0f) return
  val layers = 14
  val perLayerAlpha = 0.04f * intensity
  for (i in layers downTo 1) {
    val t = i.toFloat() / layers
    val inflate = spreadPx * t
    drawRoundRect(
      color = color.copy(alpha = perLayerAlpha),
      topLeft = Offset(-inflate, -inflate),
      size = Size(size.width + inflate * 2f, size.height + inflate * 2f),
      cornerRadius = CornerRadius(cornerRadiusPx + inflate, cornerRadiusPx + inflate)
    )
  }
}

/**
 * Riflesso di vetro (specular highlight): gradiente lineare diagonale bianco sul terzo
 * superiore dell'immagine. Disegnato SOPRA il contenuto, dentro il clip arrotondato.
 */
fun DrawScope.drawSpecularHighlight(intensity: Float) {
  if (intensity <= 0.01f) return
  val brush = Brush.linearGradient(
    colors = listOf(
      Color.White.copy(alpha = 0.22f * intensity),
      Color.White.copy(alpha = 0.05f * intensity),
      Color.Transparent
    ),
    start = Offset(0f, 0f),
    end = Offset(size.width, size.height * 0.45f)
  )
  drawRect(brush = brush)
}

/** Bordo nitido luminescente lungo il perimetro, disegnato dentro il clip. */
fun DrawScope.drawNeonBorder(
  cornerRadiusPx: Float,
  color: Color,
  widthPx: Float,
  intensity: Float
) {
  if (intensity <= 0.01f || widthPx <= 0f) return
  val inset = widthPx / 2f
  val innerRadius = (cornerRadiusPx - inset).coerceAtLeast(0f)
  drawRoundRect(
    color = color.copy(alpha = intensity),
    topLeft = Offset(inset, inset),
    size = Size(
      (size.width - widthPx).coerceAtLeast(0f),
      (size.height - widthPx).coerceAtLeast(0f)
    ),
    cornerRadius = CornerRadius(innerRadius, innerRadius),
    style = Stroke(width = widthPx)
  )
}
