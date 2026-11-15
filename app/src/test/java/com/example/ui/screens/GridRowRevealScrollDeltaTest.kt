package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regola del pre-scroll animato delle griglie TV, verificata come funzione pura.
 *
 * La riga di destinazione non va allineata subito alla posizione finale (il bring-into-view
 * la considererebbe già visibile e non animerebbe nulla): deve entrare di pochi pixel nel
 * viewport, così la card viene composta e il focus può animare il resto.
 */
class GridRowRevealScrollDeltaTest {

  private fun delta(
    targetRow: Int,
    visibleRow: Int = 0,
    visibleRowOffset: Float = 0f,
    rowHeight: Float = 300f,
    rowSpacing: Float = 16f,
    viewport: Float = 280f,
    direction: SearchGridVerticalDirection = SearchGridVerticalDirection.DOWN
  ) = gridRowRevealScrollDelta(
    targetRow = targetRow,
    visibleRow = visibleRow,
    visibleRowOffset = visibleRowOffset,
    rowHeight = rowHeight,
    rowSpacingPx = rowSpacing,
    viewportHeight = viewport,
    direction = direction
  )

  @Test
  fun giuRivelaDiPochiPixelLaRigaSuccessivaFuoriDalViewport() {
    // Riga 0 in cima, passo 316, viewport 280: la riga 1 inizia 36px sotto il bordo.
    // Serve 36px + 1px di margine, NON un passo intero (316) che la lascerebbe già
    // allineata alla posizione della riga precedente.
    assertEquals(37f, delta(targetRow = 1)!!, 0.01f)
  }

  @Test
  fun giuConPiuRigheDiDistanzaRivelaSoloLaDestinazione() {
    // Riga 3 (due passi più in basso della riga 1): distanza dal bordo calcolata sulla
    // posizione reale, senza accumulare passi già percorsi.
    assertEquals(2 * 316f + 37f, delta(targetRow = 3)!!, 0.01f)
  }

  @Test
  fun giuUsaLaPosizioneRealeDellaPrimaRigaVisibile() {
    // Prima riga visibile = 2, già scorsa di 100px: la riga 4 dista DUE passi meno i 100px
    // già percorsi; il calcolo è assoluto sul layout reale, non relativo all'ancora.
    assertEquals(2 * 316f - 100f - 280f + 1f, delta(
      targetRow = 4,
      visibleRow = 2,
      visibleRowOffset = -100f
    )!!, 0.01f)
  }

  @Test
  fun giuNonScorreSeLaRigaEUscaEntrata() {
    // Riga 1 già dentro il viewport (top 200 < 280): ci pensa il bring-into-view.
    assertNull(delta(targetRow = 1, viewport = 320f))
    assertNull(delta(targetRow = 0, visibleRow = 0))
  }

  @Test
  fun suRivelaDalBassoLaRigaSopraIlViewport() {
    // Riga 1 in cima (offset 0), si torna alla riga 0: il suo bordo inferiore è a -16px,
    // serve -17px (negativo) per farla entrare di 1px dal bordo superiore.
    assertEquals(-17f, delta(
      targetRow = 0,
      visibleRow = 1,
      direction = SearchGridVerticalDirection.UP
    )!!, 0.01f)
  }

  @Test
  fun suNonScorreSeLaRigaEAncoraVisibile() {
    // Riga 2 in cima al viewport (offset 100): la riga 1 resta parzialmente visibile
    // (bottom 84px). La rivela il bring-into-view, non serve pre-scroll.
    assertNull(delta(
      targetRow = 1,
      visibleRow = 2,
      visibleRowOffset = 100f,
      direction = SearchGridVerticalDirection.UP
    ))
  }

  @Test
  fun datiDiLayoutNonValidiNonProduconoDistanze() {
    assertNull(delta(targetRow = -1))
    assertNull(delta(targetRow = 1, visibleRow = -1))
    assertNull(delta(targetRow = 1, rowHeight = 0f))
    assertNull(delta(targetRow = 1, viewport = 0f))
  }
}
