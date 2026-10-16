package com.example.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica la regola che riattiva il tentativo di ripristino del focus sulla card del target.
 *
 * Il caso critico è il ritorno consecutivo dalla STESSA locandina: l'id del target non cambia,
 * quindi solo il token dell'evento di ripristino può far ripartire il tentativo. Senza questa
 * regola un secondo ritorno sulla stessa card non produceva alcun nuovo tentativo.
 */
class FocusRestoreAttemptTest {

  private val card = "tmdb_tv_42705"

  @Test
  fun primoTentativoSuCardCorrispondente() {
    assertTrue(shouldAttemptFocusRestore(card, targetMediaId = card, restoreToken = 1, attemptedToken = null))
  }

  @Test
  fun cardNonCorrispondenteNonTenta() {
    assertFalse(shouldAttemptFocusRestore(card, targetMediaId = "tmdb_m_1322562", restoreToken = 1, attemptedToken = null))
  }

  @Test
  fun nessunTargetNonTenta() {
    assertFalse(shouldAttemptFocusRestore(card, targetMediaId = null, restoreToken = 1, attemptedToken = null))
  }

  @Test
  fun stessoTokenGiaTentatoNonRipeteIlTentativo() {
    assertFalse(shouldAttemptFocusRestore(card, targetMediaId = card, restoreToken = 1, attemptedToken = 1))
  }

  @Test
  fun nuovoEventoConIdInvariatoRiattivaIlTentativo() {
    // Ritorno n.1 riuscito: il tentativo è stato consumato sul token 1.
    assertFalse(shouldAttemptFocusRestore(card, targetMediaId = card, restoreToken = 1, attemptedToken = 1))
    // Ritorno n.2 sulla STESSA locandina: l'id non cambia, ma l'evento di ripristino sì.
    assertTrue(shouldAttemptFocusRestore(card, targetMediaId = card, restoreToken = 2, attemptedToken = 1))
  }

  @Test
  fun tentativoFallitoVieneRipresoDalNuovoEvento() {
    // Tentativo sul token 3 non confermato (target mantenuto): resta il token 3 come "tentato".
    assertFalse(shouldAttemptFocusRestore(card, targetMediaId = card, restoreToken = 3, attemptedToken = 3))
    // Il token successivo riapre un nuovo tentativo sullo stesso target.
    assertTrue(shouldAttemptFocusRestore(card, targetMediaId = card, restoreToken = 4, attemptedToken = 3))
  }
}
