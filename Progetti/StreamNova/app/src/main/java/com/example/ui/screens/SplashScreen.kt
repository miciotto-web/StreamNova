package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.NovaBackground

/**
 * Splash Screen Cinematografica di StreamNova (16:9 Android TV).
 *
 * Replica fedelmente la direzione artistica della visual reference:
 *  - Composizione orizzontale widescreen 16:9 immersiva;
 *  - Galleria di poster laterali incurvata con profondità di campo ottica e sfocatura progressiva;
 *  - Ambiente cinematografico ultra-scuro e pavimento lucido con riflessi specchiati;
 *  - Logo centrale Play StreamNova scultoreo in gradiente ciano elettrico (#00F0FF),
 *    blu cobalto (#0055FF), viola (#7B2CBF) e magenta brillante (#FF007F);
 *  - Wordmark "StreamNova" coordinato posto direttamente sotto il logo;
 *  - Doppia fascia neon orizzontale ciano/magenta alla base della parete;
 *  - Micro-pulsazione luminosa ambientale;
 *  - Nessuna tagline, nessun pulsante, nessun elemento interattivo.
 */
@Composable
fun SplashScreen(
  modifier: Modifier = Modifier
) {
  // Micro-animazione fluida di pulsazione del glow neon
  val infiniteTransition = rememberInfiniteTransition(label = "SplashNeonPulse")
  val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 0.85f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "SplashGlowIntensity"
  )

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    // 1. Risorsa grafica cinematografica principale a tutto schermo (16:9 nativo)
    Image(
      painter = painterResource(id = R.drawable.splash_cinematic_bg),
      contentDescription = "StreamNova Splash Screen",
      modifier = Modifier
        .fillMaxSize()
        .alpha(pulseAlpha),
      contentScale = ContentScale.Crop
    )

    // 2. Layer di vignettatura periferica cinematografica (sfumatura bordi per massima immersività)
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.radialGradient(
            colors = listOf(
              Color.Transparent,
              Color.Transparent,
              Color(0x99050811),
              Color(0xDD03050A)
            ),
            radius = 1600f
          )
        )
    )

    // 3. Accentuation neon gradient sul pavimento inferiore
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(180.dp)
        .align(Alignment.BottomCenter)
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Transparent,
              Color(0x2200F0FF),
              Color(0x44050811)
            )
          )
        )
    )

    // 4. Spinner di caricamento: sotto l'intero blocco centrale (logo + wordmark),
    //    centrato orizzontalmente e con spazio visivo netto dal wordmark.
    //    0.79 dell'altezza lascia il wordmark (che termina a ~0.70) ben sopra lo spinner,
    //    mantenendolo oltre la fascia neon di base (~0.75).
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = maxHeight * 0.79f),
      contentAlignment = Alignment.TopCenter
    ) {
      NeonSpinner()
    }
  }
}

/**
 * Spinner circolare monocromatico StreamNova: 12 segmenti arrotondati disposti radialmente,
 * un unico ciano neon (#00F0FF) coerente con il logo, glow molto leggero e rotazione
 * lenta e fluida. Nessuna GIF: animazione nativa Jetpack Compose.
 */
@Composable
private fun NeonSpinner(modifier: Modifier = Modifier) {
  val infiniteTransition = rememberInfiniteTransition(label = "SpinnerRotation")
  val angle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1600, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "SpinnerAngle"
  )

  val segmentCount = 12
  // Un solo colore: ciano neon StreamNova. Nessun gradiente, nessun viola/magenta.
  val neonCyan = Color(0xFF00F0FF)

  Canvas(modifier = modifier.size(34.dp)) {
    val segmentAngle = 360f / segmentCount
    val outerRadius = this.size.minDimension / 2f
    val innerRadius = outerRadius * 0.58f
    val strokeWidth = outerRadius * 0.16f

    for (i in 0 until segmentCount) {
      // Sfumatura di opacità (stesso colore) per una coda discreta e "premium"
      val progress = i / (segmentCount - 1f)
      val segAlpha = 0.20f + 0.80f * progress

      rotate(degrees = i * segmentAngle + angle) {
        val start = Offset(center.x, center.y - innerRadius)
        val end = Offset(center.x, center.y - outerRadius)

        // Glow molto leggero: una singola passata extra, stesso colore
        drawLine(
          color = neonCyan.copy(alpha = 0.10f * segAlpha),
          start = start,
          end = end,
          strokeWidth = strokeWidth * 2.6f,
          cap = StrokeCap.Round
        )

        drawLine(
          color = neonCyan.copy(alpha = segAlpha),
          start = start,
          end = end,
          strokeWidth = strokeWidth,
          cap = StrokeCap.Round
        )
      }
    }
  }
}
