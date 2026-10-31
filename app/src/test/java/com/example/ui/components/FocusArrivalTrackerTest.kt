package com.example.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il consumo del target di ripristino deve avvenire solo sull'ARRIVO REALE del focus.
 *
 * `Modifier.onFocusChanged` può notificare `isFocused = true` più volte per lo stesso nodo
 * (nuova richiesta sullo stesso nodo, protocollo di ripristino che conferma la cella già
 * focalizzata). Solo la prima transizione fuori -> dentro è l'arrivo che può rilasciare il
 * target: una conferma duplicata non deve bruciarlo mentre l'utente naviga altrove.
 */
class FocusArrivalTrackerTest {

  @Test
  fun laPrimaTransizioneEFUnArrivo() {
    val tracker = FocusArrivalTracker()
    assertTrue(tracker.onFocusChanged(true))
  }

  @Test
  fun unaConfermaDuplicataNonEUnArrivo() {
    val tracker = FocusArrivalTracker()
    assertTrue(tracker.onFocusChanged(true))
    assertFalse(tracker.onFocusChanged(true))
    assertFalse(tracker.onFocusChanged(true))
  }

  @Test
  fun dopoLaPerditaDelFocusIlRientroEUnNuovoArrivo() {
    val tracker = FocusArrivalTracker()
    assertTrue(tracker.onFocusChanged(true))
    assertFalse(tracker.onFocusChanged(false))
    assertTrue(tracker.onFocusChanged(true))
  }

  @Test
  fun laPerditaDelFocusNonEUnArrivo() {
    val tracker = FocusArrivalTracker()
    assertFalse(tracker.onFocusChanged(false))
  }
}
