package com.example.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regole PURE del caricamento progressivo e della geometria della griglia Provider.
 *
 * Il vecchio trigger richiedeva `totalItems > 12`: una prima pagina di 8 titoli non
 * poteva mai chiedere la successiva. Qui la condizione è verificata ai bordi reali,
 * indipendentemente dalla dimensione della prima pagina.
 */
class ProviderGridPaginationPolicyTest {

  // ── caricamento progressivo ────────────────────────────────────────────────

  @Test
  fun `una prima pagina di 8 titoli richiede subito la pagina successiva`() {
    assertTrue(
      shouldRequestNextProviderPage(
        totalItems = 8,
        lastVisibleItemIndex = 7,
        isLoading = false,
        hasMoreForFilter = true
      )
    )
  }

  @Test
  fun `una lista lunga non richiede pagine finche il focus non si avvicina alla fine`() {
    assertFalse(
      shouldRequestNextProviderPage(
        totalItems = 20,
        lastVisibleItemIndex = 9,
        isLoading = false,
        hasMoreForFilter = true
      )
    )
    assertTrue(
      "penultima riga visibile: il prefetch deve partire",
      shouldRequestNextProviderPage(
        totalItems = 20,
        lastVisibleItemIndex = 17,
        isLoading = false,
        hasMoreForFilter = true
      )
    )
  }

  @Test
  fun `griglia vuota richiede la pagina per riempire la schermata`() {
    assertTrue(
      shouldRequestNextProviderPage(
        totalItems = 0,
        lastVisibleItemIndex = 0,
        isLoading = false,
        hasMoreForFilter = true
      )
    )
  }

  @Test
  fun `nessuna richiesta quando il catalogo e esaurito o una fetch e in corso`() {
    assertFalse(
      "hasMore=false: il catalogo è finito",
      shouldRequestNextProviderPage(
        totalItems = 5,
        lastVisibleItemIndex = 4,
        isLoading = false,
        hasMoreForFilter = false
      )
    )
    assertFalse(
      "una fetch già in corso non va duplicata",
      shouldRequestNextProviderPage(
        totalItems = 5,
        lastVisibleItemIndex = 4,
        isLoading = true,
        hasMoreForFilter = true
      )
    )
  }

  // ── colonne effettive (D-pad) ──────────────────────────────────────────────

  @Test
  fun `il numero di colonne replica GridCells Adaptive`() {
    // Fire TV 960dp: 960 - 2*32 = 896dp utili; celle 150dp + 16dp di gap -> 5 colonne.
    assertEquals(5, adaptiveGridColumnCount(896.dp))
    // Finestra 1280dp: 1216dp utili -> 7 colonne.
    assertEquals(7, adaptiveGridColumnCount(1216.dp))
    // Schermi stretti: almeno una colonna.
    assertEquals(1, adaptiveGridColumnCount(100.dp))
    assertEquals(1, adaptiveGridColumnCount(0.dp))
  }
}
