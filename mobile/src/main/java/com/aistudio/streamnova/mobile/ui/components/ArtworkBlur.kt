package com.aistudio.streamnova.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sfocatura anti-spoiler delle miniature, con lo STESSO risultato su ogni dispositivo.
 *
 * Perché non `Modifier.blur`: quella API usa `RenderEffect`, disponibile solo da Android 12
 * (API 31). Il client Mobile supporta Android 7+ (minSdk 24): sui dispositivi più vecchi
 * `Modifier.blur` non ha alcun effetto e la vecchia velatura scura non è una sfocatura,
 * quindi la preferenza «Sfoca non visti» resterebbe invisibile.
 *
 * Qui la sfocatura avviene sulla bitmap, non sulla GPU:
 *  1. l'artwork è caricato a bassa risoluzione (lato massimo [SPOILER_BLUR_SOURCE_MAX_PX]
 *     px): pochi pixel da elaborare anche sui dispositivi più lenti;
 *  2. sulla bitmap ridotta si applica un box blur a [SPOILER_BLUR_PASSES] passate
 *     (approssimazione della gaussiana, costo trascurabile);
 *  3. il risultato è disegnato in scala con filtro bilineare, che completa la sfocatura.
 *
 * Il risultato non dipende dall'hardware grafico né dal livello API: identico su ogni
 * telefono e tablet, emulatore compreso.
 */

/** Lato massimo, in pixel, della bitmap su cui si calcola la sfocatura. */
internal const val SPOILER_BLUR_SOURCE_MAX_PX = 96

/** Raggio del box blur applicato alla bitmap ridotta (in pixel della bitmap ridotta). */
internal const val SPOILER_BLUR_RADIUS_PX = 5

/** Numero di passate del box blur: 2 passate (orizzontale + verticale ciascuna) bastano. */
internal const val SPOILER_BLUR_PASSES = 2

/**
 * Immagine caricata a bassa risoluzione e sfocata sulla bitmap.
 *
 * [data] è qualsiasi sorgente accettata da Coil (URL `String`, risorsa `Int`, `Uri`).
 * [onError] permette al chiamante di applicare la propria catena di fallback (es.
 * primary → secondary URL) mentre il contenuto di [error] copre il caso senza ripiego.
 */
@Composable
fun SpoilerBlurredImage(
  data: Any?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
  onError: (() -> Unit)? = null,
  loading: (@Composable () -> Unit)? = null,
  error: (@Composable () -> Unit)? = null
) {
  val context = LocalContext.current
  val request = remember(context, data) { spoilerBlurRequest(context, data) }
  if (request == null) {
    if (error != null) error() else SpoilerBlurPlaceholder(modifier)
    return
  }
  SubcomposeAsyncImage(
    model = request,
    contentDescription = contentDescription,
    contentScale = contentScale,
    modifier = modifier,
    // La bitmap è già sfocata: il filtro bilineare in scala non deve introdurre bordi duri.
    filterQuality = FilterQuality.Low,
    onError = { onError?.invoke() },
    loading = { if (loading != null) loading() else SpoilerBlurPlaceholder(Modifier.fillMaxSize()) },
    success = { state ->
      // La bitmap ridotta è già stata caricata da Coil: la sfocatura costa poche migliaia
      // di operazioni ed è memorizzata per la stessa immagine, quindi non si ripete a ogni
      // ricomposizione (focus, scroll, animazioni).
      val blurred = remember(state.result.drawable) {
        state.result.drawable.spoilerBlurred()
      }
      if (blurred != null) {
        Image(
          bitmap = blurred,
          contentDescription = contentDescription,
          contentScale = contentScale,
          filterQuality = FilterQuality.Low,
          modifier = Modifier.fillMaxSize()
        )
      } else {
        // Sorgente non bitmap (es. animazione): meglio il segnaposto scuro che l'immagine
        // nitida, che vanificherebbe la protezione anti-spoiler.
        SpoilerBlurPlaceholder(Modifier.fillMaxSize())
      }
    },
    error = { if (error != null) error() else SpoilerBlurPlaceholder(Modifier.fillMaxSize()) }
  )
}

/**
 * Richiesta Coil della sola bitmap di lavoro: piccola, non hardware e senza crossfade.
 *
 * `allowHardware(false)` è obbligatorio: su una hardware bitmap `Bitmap.getPixels()` non è
 * disponibile e la sfocatura fallirebbe proprio sui dispositivi Android 8+ che ne fanno uso.
 */
internal fun spoilerBlurRequest(context: Context, data: Any?): ImageRequest? {
  if (data == null) return null
  return ImageRequest.Builder(context)
    .data(data)
    .size(SPOILER_BLUR_SOURCE_MAX_PX)
    .allowHardware(false)
    .build()
}

/** Sfocatura della bitmap caricata: riduzione difensiva, box blur, conversione Compose. */
internal fun Drawable.spoilerBlurred(): ImageBitmap? {
  val source = toSoftwareBitmap() ?: return null
  val maxSide = max(source.width, source.height)
  val working = if (maxSide > SPOILER_BLUR_SOURCE_MAX_PX) {
    val ratio = SPOILER_BLUR_SOURCE_MAX_PX.toFloat() / maxSide
    Bitmap.createScaledBitmap(
      source,
      (source.width * ratio).roundToInt().coerceAtLeast(1),
      (source.height * ratio).roundToInt().coerceAtLeast(1),
      true
    )
  } else {
    source
  }
  val width = working.width
  val height = working.height
  val pixels = IntArray(width * height)
  working.getPixels(pixels, 0, width, 0, 0, width, height)
  val blurred = boxBlurArgb(pixels, width, height, SPOILER_BLUR_RADIUS_PX, SPOILER_BLUR_PASSES)
  return Bitmap.createBitmap(blurred, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Bitmap software leggibile da `getPixels()`.
 *
 * Coil è già forzato a non usare hardware bitmap dalla richiesta, ma un `Drawable` che non
 * è un [BitmapDrawable] viene disegnato su una bitmap software: nessuna sorgente può
 * quindi far fallire la sfocatura per un dettaglio di decodifica.
 */
private fun Drawable.toSoftwareBitmap(): Bitmap? {
  if (this is BitmapDrawable && bitmap != null && !bitmap.isRecycled) {
    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
      bitmap.config == Bitmap.Config.HARDWARE
    return if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
  }
  val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: return null
  val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: return null
  val rendered = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(rendered)
  setBounds(0, 0, canvas.width, canvas.height)
  draw(canvas)
  return rendered
}

/**
 * Box blur ARGB a finestra scorrevole: O(pixel) per passata, indipendente dal raggio.
 *
 * Separabile (righe poi colonne) e con campionamento ai bordi: nessun pixel trasparente
 * viene introdotto lungo i bordi dell'immagine. Le due direzioni scrivono su buffer distinti,
 * quindi ogni passata legge sempre il risultato completo della precedente.
 */
internal fun boxBlurArgb(
  pixels: IntArray,
  width: Int,
  height: Int,
  radius: Int,
  passes: Int
): IntArray {
  require(width > 0 && height > 0) { "dimensioni non valide: ${width}x$height" }
  require(pixels.size >= width * height) { "pixel insufficienti per ${width}x$height" }
  if (radius <= 0 || passes <= 0) return pixels.copyOf(width * height)
  val front = pixels.copyOf(width * height)
  val back = IntArray(width * height)
  repeat(passes) {
    blurRows(front, back, width, height, radius)
    blurColumns(back, front, width, height, radius)
  }
  return front
}

/** Passata orizzontale: ogni riga è filtrata in modo indipendente. */
private fun blurRows(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
  val divisor = 2 * radius + 1
  for (y in 0 until height) {
    val rowStart = y * width
    var alpha = 0
    var red = 0
    var green = 0
    var blue = 0
    for (offset in -radius..radius) {
      val color = src[rowStart + offset.coerceIn(0, width - 1)]
      alpha += (color ushr 24) and 0xFF
      red += (color ushr 16) and 0xFF
      green += (color ushr 8) and 0xFF
      blue += color and 0xFF
    }
    for (x in 0 until width) {
      dst[rowStart + x] =
        ((alpha / divisor) shl 24) or
          ((red / divisor) shl 16) or
          ((green / divisor) shl 8) or
          (blue / divisor)
      val leaving = src[rowStart + (x - radius).coerceIn(0, width - 1)]
      val entering = src[rowStart + (x + radius + 1).coerceIn(0, width - 1)]
      alpha += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
      red += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
      green += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
      blue += (entering and 0xFF) - (leaving and 0xFF)
    }
  }
}

/** Passata verticale: ogni colonna è filtrata in modo indipendente. */
private fun blurColumns(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
  val divisor = 2 * radius + 1
  for (x in 0 until width) {
    var alpha = 0
    var red = 0
    var green = 0
    var blue = 0
    for (offset in -radius..radius) {
      val color = src[offset.coerceIn(0, height - 1) * width + x]
      alpha += (color ushr 24) and 0xFF
      red += (color ushr 16) and 0xFF
      green += (color ushr 8) and 0xFF
      blue += color and 0xFF
    }
    for (y in 0 until height) {
      dst[y * width + x] =
        ((alpha / divisor) shl 24) or
          ((red / divisor) shl 16) or
          ((green / divisor) shl 8) or
          (blue / divisor)
      val leaving = src[(y - radius).coerceIn(0, height - 1) * width + x]
      val entering = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
      alpha += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
      red += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
      green += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
      blue += (entering and 0xFF) - (leaving and 0xFF)
    }
  }
}

/** Segnaposto scuro mostrato prima che la miniatura sfocata sia pronta o se non arriva. */
@Composable
private fun SpoilerBlurPlaceholder(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.background(
      Brush.verticalGradient(
        colors = listOf(Color(0xFF1B2333), Color(0xFF0F1420))
      )
    )
  )
}
