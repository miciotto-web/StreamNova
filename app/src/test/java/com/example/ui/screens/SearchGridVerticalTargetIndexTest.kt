package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regola del movimento verticale D-pad nelle griglie della Ricerca, verificata come funzione
 * pura: colonna preservata, riga finale corta → colonna valida più vicina, nessuna
 * destinazione oltre i bordi.
 */
class SearchGridVerticalTargetIndexTest {

  private fun down(row: Int, column: Int, count: Int = 30, columns: Int = 5) =
    searchGridVerticalTargetIndex(
      currentRow = row,
      currentColumn = column,
      itemCount = count,
      columns = columns,
      direction = SearchGridVerticalDirection.DOWN
    )

  private fun up(row: Int, column: Int, count: Int = 30, columns: Int = 5) =
    searchGridVerticalTargetIndex(
      currentRow = row,
      currentColumn = column,
      itemCount = count,
      columns = columns,
      direction = SearchGridVerticalDirection.UP
    )

  @Test
  fun giuMantieneLaColonnaInUnaRigaPiena() {
    // Terza colonna: riga 0 → riga 1, indice 2 → 7; anche su righe successive.
    assertEquals(7, down(0, 2))
    assertEquals(12, down(1, 2))
    assertEquals(17, down(2, 2))
    assertEquals(5, down(0, 0))
    assertEquals(9, down(0, 4))
  }

  @Test
  fun giuVersoUnaRigaFinaleCortaSceglieLaColonnaPiuVicina() {
    // 17 card, 5 colonne: ultima riga con 2 card (indici 15, 16).
    // Colonna 2 richiesta → colonna 1 (l'ultima disponibile, la più vicina).
    assertEquals(16, down(2, 2, count = 17))
    assertEquals(15, down(2, 0, count = 17))
    assertEquals(16, down(2, 4, count = 17))
    // Riga precedente piena: colonna preservata.
    assertEquals(12, down(1, 2, count = 17))
  }

  @Test
  fun giuDallUltimaRigaNonHaDestinazione() {
    assertNull(down(5, 2, count = 30))
    assertNull(down(3, 0, count = 17))
    assertNull(down(0, 0, count = 3))
  }

  @Test
  fun suMantieneLaColonnaERisaleLeRighe() {
    assertEquals(7, up(2, 2))
    assertEquals(2, up(1, 2))
    assertNull(up(0, 2))
    // Su da una riga finale corta: riga piena, colonna più vicina nella riga precedente.
    assertEquals(11, up(3, 1, count = 17))
    assertEquals(10, up(3, 0, count = 17))
  }

  @Test
  fun indiciOColonneNonValidiNonProduconoDestinazioni() {
    assertNull(down(0, -1))
    assertNull(down(0, 5))
    assertNull(down(-1, 0))
    assertNull(down(0, 0, count = 0))
    assertNull(down(0, 0, columns = 0))
    assertNull(up(0, 0, count = 0))
  }
}
