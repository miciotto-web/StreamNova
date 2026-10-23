package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stabilizzazione dell'ordine di prima comparsa dei cataloghi (utilità del ripristino del
 * focus): l'ordine delle card è parte della loro posizione visiva e non deve cambiare quando
 * la lista di catalogo viene ricostruita in un ordine diverso (l'osservatore Room riemette
 * `allMedia` riordinato per rating ad ogni persistenza dei dettagli).
 */
class StabilizeFirstSeenOrderTest {

  private val order = mutableListOf<String>()
  private fun stabilize(items: List<Pair<String, String>>): List<String> =
    stabilizeFirstSeenOrder(items, order) { it.first }.map { it.second }

  @Test
  fun ilPrimoOrdineVieneMantenuto() {
    val result = stabilize(listOf("a" to "A", "b" to "B", "c" to "C"))
    assertEquals(listOf("A", "B", "C"), result)
  }

  @Test
  fun unRiordinoDelCatalogoNonRicollocaLeCardGiaNote() {
    stabilize(listOf("a" to "A", "b" to "B", "c" to "C"))
    // Ricostruzione della lista in ordine diverso (come sortedByDescending { rating }).
    val result = stabilize(listOf("c" to "C", "a" to "A", "b" to "B"))
    assertEquals(listOf("A", "B", "C"), result)
  }

  @Test
  fun leNuoveVociSiAccodanoNellOrdineDiArrivo() {
    stabilize(listOf("a" to "A", "b" to "B"))
    val result = stabilize(listOf("d" to "D", "b" to "B", "a" to "A", "c" to "C"))
    assertEquals(listOf("A", "B", "D", "C"), result)
  }

  @Test
  fun leVociRimosseEsconoERientranoNellaPosizioneDiPrimaComparsa() {
    stabilize(listOf("a" to "A", "b" to "B", "c" to "C"))
    assertEquals(listOf("A", "C"), stabilize(listOf("c" to "C", "a" to "A")))
    assertEquals(listOf("A", "B", "C"), stabilize(listOf("a" to "A", "b" to "B", "c" to "C")))
  }

  @Test
  fun iDuplicatiDiIdNonGeneranoCardDuplicate() {
    val result = stabilize(listOf("a" to "A1", "a" to "A2", "b" to "B"))
    assertEquals(listOf("A1", "B"), result)
  }

  @Test
  fun listaVuotaNonConsumaMemoria() {
    assertEquals(emptyList<String>(), stabilize(emptyList()))
    assertEquals(0, order.size)
  }
}
