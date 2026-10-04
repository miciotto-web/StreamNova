package com.example.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Wordmark ufficiale "StreamNova".
 *
 * E' la STESSA firma tipografica/cromatica usata dalla Splash Screen cinematografica:
 *  - stessa tipografia (font geometrico-grottesco ExtraBold del brand);
 *  - stessa proporzione (cap height / larghezza invariata, tracking leggermente stretto);
 *  - stesso trattamento cromatico: "Stream" perla bianco-fredda, "Nova" in gradiente
 *    ciano -> blu -> viola -> magenta campionato dalla reference;
 *  - stesso carattere premium/cinematografico (halo glow sottile attorno alle lettere).
 *
 * Nessuna variante: questo composable e' l'unico punto che disegna il wordmark fuori
 * dalla Splash (dove invece e' parte del bitmap `splash_cinematic_bg`).
 */
@Composable
fun StreamNovaWordmark(
  modifier: Modifier = Modifier,
  fontSize: TextUnit = 18.sp,
  withGlow: Boolean = true
) {
  val glowRadiusPx = with(LocalDensity.current) { 10.dp.toPx() }

  Row(modifier = modifier) {
    Text(
      text = "Stream",
      style = TextStyle(
        brush = Brush.verticalGradient(
          colors = listOf(Color(0xFFFFFFFF), Color(0xFFF3F5FD), Color(0xFFEBEEFA))
        ),
        fontFamily = StreamNovaWordmarkFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = fontSize,
        letterSpacing = WordmarkTracking,
        shadow = if (withGlow) {
          Shadow(color = WordmarkCoolGlow, offset = Offset.Zero, blurRadius = glowRadiusPx)
        } else {
          null
        }
      )
    )

    Text(
      text = "Nova",
      style = TextStyle(
        brush = Brush.horizontalGradient(*WordmarkNovaGradient.toTypedArray()),
        fontFamily = StreamNovaWordmarkFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = fontSize,
        letterSpacing = WordmarkTracking,
        shadow = if (withGlow) {
          Shadow(color = WordmarkWarmGlow, offset = Offset.Zero, blurRadius = glowRadiusPx)
        } else {
          null
        }
      )
    )
  }
}

/** Famiglia tipografica del wordmark StreamNova (Rethink Sans ExtraBold). */
private val StreamNovaWordmarkFamily = FontFamily(
  Font(resId = R.font.streamnova_wordmark, weight = FontWeight.ExtraBold)
)

/**
 * Tracking leggermente stretto: mantiene la stessa larghezza relativa del wordmark
 * della Splash (larghezza / cap height ~= 7.43) rispetto a quella nativa del font.
 */
private val WordmarkTracking = (-0.013f).em

/**
 * Gradiente "Nova" ciano -> blu -> viola -> magenta,
 * campionato pixel per pixel dal wordmark della Splash Screen.
 */
private val WordmarkNovaGradient = listOf(
  0.00f to Color(0xFF00D4FF),
  0.20f to Color(0xFF03BFFD),
  0.33f to Color(0xFF2580FC),
  0.45f to Color(0xFF5F4FFB),
  0.56f to Color(0xFF8A3FFC),
  0.70f to Color(0xFFC02CFB),
  0.82f to Color(0xFFDE22FC),
  1.00f to Color(0xFFF21FF6)
)

/** Halo glow freddo attorno a "Stream". */
private val WordmarkCoolGlow = Color(0x6686B8FF)

/** Halo glow viola/magenta attorno a "Nova". */
private val WordmarkWarmGlow = Color(0x66A855FF)
