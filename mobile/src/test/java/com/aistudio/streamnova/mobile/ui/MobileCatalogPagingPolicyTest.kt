package com.aistudio.streamnova.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della politica di caricamento progressivo delle griglie mobile (Film, Serie
 * TV e catalogo Provider). Stessa semantica del trigger TV
 * (`shouldRequestNextProviderPage`): la pagina successiva parte quando l'utente
 * si avvicina alla fine della lista.
 */
class MobileCatalogPagingPolicyTest {

  @Test
  fun laPaginaSuccessivaParteVicinoAllaFineDellaLista() {
    // 60 card a schermo, l'utente è alla card 55 (>= 60 - 6): la pagina parte.
    assertTrue(
      shouldRequestNextMobilePage(
        totalItems = 60,
        lastVisibleItemIndex = 55,
        isLoading = false
      )
    )
    // Ancora lontano dalla fine: nessuna richiesta.
    assertFalse(
      shouldRequestNextMobilePage(
        totalItems = 60,
        lastVisibleItemIndex = 40,
        isLoading = false
      )
    )
  }

  @Test
  fun grigliaVuotaRichiedeSubitoLaPaginaSuccessiva() {
    // Catalogo non ancora composto: è l'unico modo per riempire la schermata.
    assertTrue(
      shouldRequestNextMobilePage(
        totalItems = 0,
        lastVisibleItemIndex = 0,
        isLoading = false
      )
    )
  }

  @Test
  fun unCaricamentoInCorsoBloccaIlTrigger() {
    assertFalse(
      shouldRequestNextMobilePage(
        totalItems = 60,
        lastVisibleItemIndex = 59,
        isLoading = true
      )
    )
  }

  @Test
  fun unaSorgenteEsauritaBloccaIlTrigger() {
    assertFalse(
      shouldRequestNextMobilePage(
        totalItems = 60,
        lastVisibleItemIndex = 59,
        isLoading = false,
        hasMore = false
      )
    )
  }

  @Test
  fun ilPreCaricamentoFermoAllaFineEsattaNonGeneraRichiesteInfinte() {
    // L'utente è sull'ultima card e la pagina è stata caricata (isPaginating true):
    // il trigger non deve ripartire finché il caricamento non è concluso.
    assertFalse(
      shouldRequestNextMobilePage(
        totalItems = 80,
        lastVisibleItemIndex = 79,
        isLoading = true
      )
    )
  }

  @Test
  fun laDistanzaDiPreCaricamentoEUgualeAllaSogliaDichiarata() {
    // Con 6 card di pre-caricamento, la richiesta parte esattamente alla soglia.
    val total = 100
    val threshold = total - MOBILE_PAGE_PREFETCH_DISTANCE
    assertTrue(
      shouldRequestNextMobilePage(
        totalItems = total,
        lastVisibleItemIndex = threshold,
        isLoading = false
      )
    )
    assertFalse(
      shouldRequestNextMobilePage(
        totalItems = total,
        lastVisibleItemIndex = threshold - 1,
        isLoading = false
      )
    )
  }
}
