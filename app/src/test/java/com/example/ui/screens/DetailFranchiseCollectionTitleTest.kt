package com.example.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Etichetta della sezione «Franchise - Collezione» della scheda Dettaglio: il nome
 * collezione restituito da TMDB spesso CONTIENE già la parola «Collezione» nelle
 * varie lingue («Harry Potter - Collezione» in it, «Harry Potter Collection» in en).
 * In quel caso l'etichetta deve restare tale quale, senza dopione del suffisso
 * («Harry Potter - Collezione - Collezione», difetto visto in emulatore).
 */
class DetailFranchiseCollectionTitleTest {

  @Test
  fun nomiGiaConSuffissoNonAggiungonoIlTitolo() {
    assertTrue(hasCollectionSuffixWord("Harry Potter - Collezione"))
    assertTrue(hasCollectionSuffixWord("Harry Potter Collection"))
    assertTrue(hasCollectionSuffixWord("Die Hard Kollektion"))
    assertTrue(hasCollectionSuffixWord("Crepúsculo - La Saga"))
    assertTrue(hasCollectionSuffixWord("Il Signor degli Anelli - Trilogia"))
  }

  @Test
  fun laRicercaNonDistingueMaiuscoleMinuscole() {
    assertTrue(hasCollectionSuffixWord("HARRY POTTER COLLECTION"))
    assertTrue(hasCollectionSuffixWord("harry potter - collezione"))
  }

  @Test
  fun nomiSenzaSuffissoRicevonoIlTitoloLocalizzato() {
    assertFalse(hasCollectionSuffixWord("James Bond"))
    assertFalse(hasCollectionSuffixWord("Fast & Furious"))
    assertFalse(hasCollectionSuffixWord("Avengers"))
  }

  @Test
  fun nomiVuotiOConSpaziNonHannoSuffisso() {
    assertFalse(hasCollectionSuffixWord(""))
    assertFalse(hasCollectionSuffixWord("   "))
  }

  @Test
  fun leParoleDelSuffissoCopronoLeLingueDellApp() {
    // it, en, de, es, fr: ogni localizzazione dell'app deve riconoscere il proprio
    // suffisso per non produrre mai il doppione dell'etichetta.
    assertTrue(COLLECTION_NAME_SUFFIX_WORDS.contains("collezione"))
    assertTrue(COLLECTION_NAME_SUFFIX_WORDS.contains("collection"))
    assertTrue(COLLECTION_NAME_SUFFIX_WORDS.contains("kollektion"))
    assertTrue(COLLECTION_NAME_SUFFIX_WORDS.contains("colección") || COLLECTION_NAME_SUFFIX_WORDS.contains("coleccion"))
  }
}
