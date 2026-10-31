package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logica pura della navigazione ancorata: confini della fila e guardia dello step in volo.
 * Nessuna UI: verifica il modello che [AnchoredFocusRow] usa per muovere il focus.
 */
class AnchoredRowNavigationTest {

  // ---------------------------------------------------------------- confini

  @Test
  fun destraAvanzaDiUnaCard() {
    assertEquals(
      3,
      resolveAnchoredStepTarget(2, itemCount = 10, direction = AnchoredRowDirection.NEXT)
    )
  }

  @Test
  fun destraSullUltimaCardNonHaDestinazione() {
    assertNull(
      resolveAnchoredStepTarget(9, itemCount = 10, direction = AnchoredRowDirection.NEXT)
    )
  }

  @Test
  fun sinistraTornaIndietroDiUnaCard() {
    assertEquals(
      1,
      resolveAnchoredStepTarget(2, itemCount = 10, direction = AnchoredRowDirection.PREVIOUS)
    )
  }

  @Test
  fun sinistraSullaPrimaCardNonHaDestinazione() {
    assertNull(
      resolveAnchoredStepTarget(0, itemCount = 10, direction = AnchoredRowDirection.PREVIOUS)
    )
  }

  @Test
  fun rigaVuotaNonHaDestinazioni() {
    assertNull(
      resolveAnchoredStepTarget(0, itemCount = 0, direction = AnchoredRowDirection.NEXT)
    )
    assertNull(
      resolveAnchoredStepTarget(0, itemCount = 0, direction = AnchoredRowDirection.PREVIOUS)
    )
  }

  @Test
  fun indiceFuoriIntervalloVieneRiportatoNeiLimiti() {
    assertEquals(
      8,
      resolveAnchoredStepTarget(99, itemCount = 10, direction = AnchoredRowDirection.PREVIOUS)
    )
    assertNull(
      resolveAnchoredStepTarget(99, itemCount = 10, direction = AnchoredRowDirection.NEXT)
    )
  }

  // ---------------------------------------------------------------- controller

  @Test
  fun loStepRestaUnicoFincheNonVieneConcluso() {
    val controller = AnchoredRowController("row")
    controller.onCardFocused(2)

    val first = controller.beginStep(3, AnchoredRowDirection.NEXT)
    assertTrue(controller.isTransitioning)
    assertNull(
      "un secondo step durante l'animazione non deve partire",
      controller.beginStep(4, AnchoredRowDirection.NEXT)
    )

    controller.finishStep(first!!)
    assertFalse(controller.isTransitioning)
    assertTrue(
      "dopo la conclusione lo step successivo è ammesso",
      controller.beginStep(4, AnchoredRowDirection.NEXT) != null
    )
  }

  @Test
  fun perdereIlFocusAnnullaLoStepInVolo() {
    val controller = AnchoredRowController("row")
    controller.onCardFocused(2)
    controller.beginStep(3, AnchoredRowDirection.NEXT)

    controller.onRowFocusChanged(false)

    assertFalse(controller.isTransitioning)
    assertFalse(controller.rowHasFocus)
    assertTrue(
      "l'indice selezionato resta memorizzato per il rientro nella riga",
      controller.currentIndex == 2
    )
  }

  @Test
  fun ilLeftDellaRigaEsisteSoloOltreLaPrimaCard() {
    val controller = AnchoredRowController("row")
    controller.onCardFocused(0)
    assertFalse(controller.wantsLeftKey)

    controller.onCardFocused(3)
    assertTrue(controller.wantsLeftKey)

    // Anche con l'indice 0, uno step in volo appartiene alla riga (protezione ripetuti).
    controller.onCardFocused(0)
    controller.beginStep(1, AnchoredRowDirection.NEXT)
    assertTrue(controller.wantsLeftKey)
    controller.onRowFocusChanged(false)
  }

  @Test
  fun loStatoGlobaleRegistraLaRigaAncorataFocalizzata() {
    val first = AnchoredRowController("row_a")
    val second = AnchoredRowController("row_b")

    first.onRowFocusChanged(true)
    first.onCardFocused(0)
    assertTrue(AnchoredRowKeyState.hasFocusedRow)
    assertEquals("row_a", AnchoredRowKeyState.focusedRowKeyForTest)
    assertFalse(AnchoredRowKeyState.consumesLeftKey)

    first.onCardFocused(2)
    assertTrue(AnchoredRowKeyState.consumesLeftKey)

    second.onRowFocusChanged(true)
    second.onCardFocused(1)
    assertEquals("row_b", AnchoredRowKeyState.focusedRowKeyForTest)
    assertTrue(AnchoredRowKeyState.consumesLeftKey)

    // La riga A che perde il focus NON deve cancellare la registrazione della riga B.
    first.onRowFocusChanged(false)
    assertEquals("row_b", AnchoredRowKeyState.focusedRowKeyForTest)
    assertTrue(AnchoredRowKeyState.consumesLeftKey)

    second.onRowFocusChanged(false)
    assertFalse(AnchoredRowKeyState.hasFocusedRow)
    assertFalse(AnchoredRowKeyState.consumesLeftKey)
  }
}
