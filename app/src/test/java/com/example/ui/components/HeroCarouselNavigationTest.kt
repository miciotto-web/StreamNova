package com.example.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica la navigazione del carosello Hero con il D-pad (poster successivo con
 * wrap-around, poster precedente con stop sul primo) e lo stato condiviso che decide se il
 * LEFT appartiene all'Hero o deve uscire verso la Sidebar.
 *
 * L'Hero e' una superficie unica di focus (nessun pulsante azione interno): lo stato
 * condiviso traccia solo il focus dell'intero banner, non quello di singoli pulsanti.
 */
class HeroCarouselNavigationTest {

  @After
  fun tearDown() {
    HeroCarouselState.reset()
  }

  @Test
  fun prossimoPosterAvanzaEChiudeIlGiro() {
    assertEquals(1, HeroCarouselNavigation.nextIndex(currentIndex = 0, itemCount = 5))
    assertEquals(3, HeroCarouselNavigation.nextIndex(currentIndex = 2, itemCount = 5))
    // Dall'ultimo poster si torna al primo.
    assertEquals(0, HeroCarouselNavigation.nextIndex(currentIndex = 4, itemCount = 5))
  }

  @Test
  fun indiceNonValidoNonEsceDallIntervallo() {
    assertEquals(0, HeroCarouselNavigation.nextIndex(currentIndex = 0, itemCount = 0))
    assertEquals(0, HeroCarouselNavigation.nextIndex(currentIndex = 0, itemCount = 1))
    // Indice rimasto indietro rispetto ai dati: si rientra nell'intervallo valido.
    assertTrue(HeroCarouselNavigation.nextIndex(currentIndex = 9, itemCount = 3) in 0..2)
  }

  @Test
  fun posterPrecedenteSiFermaAlPrimo() {
    assertEquals(2, HeroCarouselNavigation.previousIndex(currentIndex = 3))
    assertEquals(0, HeroCarouselNavigation.previousIndex(currentIndex = 1))
    // Nessun wrap-around all'indietro: sul primo poster l'indice resta fermo.
    assertEquals(0, HeroCarouselNavigation.previousIndex(currentIndex = 0))
  }

  @Test
  fun leftAppartieneAllHeroSoloDopoIlPrimoPoster() {
    assertFalse(HeroCarouselNavigation.canScrollToPrevious(currentIndex = 0, itemCount = 5))
    assertTrue(HeroCarouselNavigation.canScrollToPrevious(currentIndex = 1, itemCount = 5))
    assertTrue(HeroCarouselNavigation.canScrollToPrevious(currentIndex = 4, itemCount = 5))
    // Con un solo poster non esiste scorrimento: il LEFT esce sempre verso la Sidebar.
    assertFalse(HeroCarouselNavigation.canScrollToPrevious(currentIndex = 0, itemCount = 1))
  }

  @Test
  fun leftConsumatoDallHeroSoloConIlFocusSulBanner() {
    HeroCarouselState.onCarouselChanged(index = 2, size = 5)

    // Carosello su un poster successivo al primo, ma focus sulle card Home: LEFT libero.
    assertFalse(HeroCarouselState.consumesLeftKey)

    HeroCarouselState.onHeroFocusChanged(true)
    assertTrue(HeroCarouselState.consumesLeftKey)

    // Focus uscito dall'Hero (verso la Sidebar o la riga sotto): l'Hero non trattiene il LEFT.
    HeroCarouselState.onHeroFocusChanged(false)
    assertFalse(HeroCarouselState.consumesLeftKey)
  }

  @Test
  fun sulPrimoPosterIlLeftRestaAllaSidebar() {
    HeroCarouselState.onCarouselChanged(index = 0, size = 5)
    HeroCarouselState.onHeroFocusChanged(true)

    assertFalse(HeroCarouselState.consumesLeftKey)
  }

  @Test
  fun conUnSoloPosterIlLeftRestaAllaSidebar() {
    HeroCarouselState.onCarouselChanged(index = 0, size = 1)
    HeroCarouselState.onHeroFocusChanged(true)

    assertFalse(HeroCarouselState.consumesLeftKey)
  }

  @Test
  fun loStatoSegueLaPosizioneDelCarosello() {
    HeroCarouselState.onHeroFocusChanged(true)

    HeroCarouselState.onCarouselChanged(index = 3, size = 5)
    assertTrue(HeroCarouselState.consumesLeftKey)

    // Tornati al primo poster il LEFT deve tornare a MainActivity (apertura Sidebar).
    HeroCarouselState.onCarouselChanged(index = 0, size = 5)
    assertFalse(HeroCarouselState.consumesLeftKey)
  }

  @Test
  fun uscireDallaHomeAzzeraLoStatoCondiviso() {
    HeroCarouselState.onCarouselChanged(index = 4, size = 5)
    HeroCarouselState.onHeroFocusChanged(true)
    assertTrue(HeroCarouselState.consumesLeftKey)

    HeroCarouselState.reset()

    assertFalse(HeroCarouselState.consumesLeftKey)
  }
}
