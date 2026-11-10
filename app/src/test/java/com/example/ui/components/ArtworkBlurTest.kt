package com.example.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sfocatura anti-spoiler delle miniature.
 *
 * La sfocatura non usa `Modifier.blur` (RenderEffect, disponibile solo da API 31): è
 * calcolata sulla bitmap ridotta, così funziona anche su Fire TV (Android 9/11) e sui
 * Google TV con Android 10/11, dove `Modifier.blur` non ha alcun effetto. Questi test
 * coprono il nucleo dell'elaborazione, che è puro Kotlin e verificabile sulla JVM.
 */
class ArtworkBlurTest {

  private fun color(alpha: Int, red: Int, green: Int, blue: Int): Int =
    (alpha shl 24) or (red shl 16) or (green shl 8) or blue

  private fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF
  private fun redOf(color: Int): Int = (color ushr 16) and 0xFF

  @Test
  fun `un colore uniforme resta invariato`() {
    val pixel = color(alpha = 255, red = 40, green = 90, blue = 160)
    val width = 12
    val height = 7
    val blurred = boxBlurArgb(
      pixels = IntArray(width * height) { pixel },
      width = width,
      height = height,
      radius = 4,
      passes = 2
    )

    assertTrue("stessa dimensione", blurred.size == width * height)
    assertTrue("nessun pixel cambiato: ${blurred.toSet()}", blurred.all { it == pixel })
  }

  @Test
  fun `la sfocatura non introduce trasparenza ai bordi`() {
    val width = 16
    val height = 9
    val opaqueWhite = color(alpha = 255, red = 255, green = 255, blue = 255)
    val pixels = IntArray(width * height) { index ->
      if (index % 2 == 0) opaqueWhite else color(255, 0, 0, 0)
    }

    val blurred = boxBlurArgb(pixels, width, height, radius = 5, passes = 2)

    assertTrue(
      "ogni pixel resta opaco (campionamento ai bordi, non fuori immagine)",
      blurred.all { alphaOf(it) == 255 }
    )
  }

  @Test
  fun `il pixel isolato si diffonde e perde intensita al centro`() {
    val width = 21
    val height = 21
    val center = (height / 2) * width + (width / 2)
    val black = color(255, 0, 0, 0)
    val bright = color(255, 255, 255, 255)
    val pixels = IntArray(width * height) { black }
    pixels[center] = bright

    val blurred = boxBlurArgb(pixels, width, height, radius = 3, passes = 2)

    assertTrue("il centro non è più bianco pieno", redOf(blurred[center]) in 1..254)
    assertTrue("il vicino è stato illuminato", redOf(blurred[center + 1]) > 0)
    assertEquals("a distanza dal pixel isolato resta nero", 0, redOf(blurred[0]))
  }

  @Test
  fun `un raggio o zero passate non modificano i pixel`() {
    val pixels = IntArray(20) { color(255, it * 10, 255 - it, 128) }

    assertArrayEquals(pixels, boxBlurArgb(pixels, 5, 4, radius = 0, passes = 3))
    assertArrayEquals(pixels, boxBlurArgb(pixels, 5, 4, radius = 4, passes = 0))
  }

  @Test
  fun `le righe e le colonne sono filtrate con le rispettive dimensioni`() {
    // Immagine NON quadrata: se una passata usasse la dimensione sbagliata, i valori
    // uscirebbero dall'intervallo o i bordi cambierebbero.
    val width = 15
    val height = 4
    val pixels = IntArray(width * height) { index ->
      color(255, (index * 37) % 256, (index * 11) % 256, (index * 5) % 256)
    }

    val blurred = boxBlurArgb(pixels, width, height, radius = 2, passes = 2)

    assertEquals("dimensione preservata", width * height, blurred.size)
    assertTrue("alfa preservato", blurred.all { alphaOf(it) == 255 })
  }

  @Test
  fun `la sfocatura riduce il contrasto di un bordo netto`() {
    val width = 20
    val height = 10
    val pixels = IntArray(width * height) { index ->
      val x = index % width
      if (x < width / 2) color(255, 0, 0, 0) else color(255, 255, 255, 255)
    }

    val blurred = boxBlurArgb(pixels, width, height, radius = 2, passes = 2)

    val middleRow = (height / 2) * width
    val left = redOf(blurred[middleRow + width / 2 - 1])
    val right = redOf(blurred[middleRow + width / 2])
    assertTrue("il nero a ridosso del bordo si schiarisce", left > 0)
    assertTrue("il bianco a ridosso del bordo si scurisce", right < 255)
  }
}
