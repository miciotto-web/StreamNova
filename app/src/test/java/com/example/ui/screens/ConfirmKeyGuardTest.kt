package com.example.ui.screens

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardia sul tasto di conferma del popup di aggiornamento: distingue una
 * pressione NUOVA dell'utente dal rilascio di una pressione iniziata altrove.
 *
 * Il caso che difende è quello del telecomando: il popup compare da solo (il
 * controllo di avvio finisce mentre la splash si chiude) e un OK ancora premuto
 * verrebbe consegnato al pulsante a fuoco al rilascio, facendo partire un download
 * che l'utente non ha chiesto.
 */
class ConfirmKeyGuardTest {

  private val center = KeyEvent.KEYCODE_DPAD_CENTER

  @Test
  fun aReleaseWithoutANewPressIsConsumed() {
    val guard = ConfirmKeyGuard()

    assertTrue(
      "Il rilascio di una pressione iniziata prima dell'apertura non deve arrivare al pulsante",
      guard.onKeyEvent(center, KeyEvent.ACTION_UP, repeatCount = 0)
    )
  }

  @Test
  fun aReleaseAfterANewPressIsNotConsumed() {
    val guard = ConfirmKeyGuard()

    assertFalse(
      "La pressione iniziale non è una scelta e passa oltre",
      guard.onKeyEvent(center, KeyEvent.ACTION_DOWN, repeatCount = 0)
    )
    assertFalse(
      "Il rilascio di una pressione nuova è la scelta dell'utente: non va consumato",
      guard.onKeyEvent(center, KeyEvent.ACTION_UP, repeatCount = 0)
    )
  }

  @Test
  fun aHeldKeyDoesNotRearmTheChoice() {
    val guard = ConfirmKeyGuard()

    // Tasto premuto prima dell'apertura: le ripetizioni arrivano al popup, ma non
    // sono pressioni nuove.
    assertFalse(guard.onKeyEvent(center, KeyEvent.ACTION_DOWN, repeatCount = 12))
    assertTrue(
      "Il rilascio di un tasto tenuto premuto da prima resta consumato",
      guard.onKeyEvent(center, KeyEvent.ACTION_UP, repeatCount = 0)
    )
  }

  @Test
  fun afterAChoiceTheGuardAsksForANewPressAgain() {
    val guard = ConfirmKeyGuard()

    guard.onKeyEvent(center, KeyEvent.ACTION_DOWN, repeatCount = 0)
    guard.onKeyEvent(center, KeyEvent.ACTION_UP, repeatCount = 0)

    assertTrue(
      "Dopo una scelta la guardia torna in attesa di una pressione nuova",
      guard.onKeyEvent(center, KeyEvent.ACTION_UP, repeatCount = 0)
    )
  }

  @Test
  fun otherKeysAreNeverConsumed() {
    val guard = ConfirmKeyGuard()

    assertFalse(guard.onKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP, 0))
    assertFalse(guard.onKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 0))
    assertFalse(guard.onKeyEvent(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP, 0))
  }
}
