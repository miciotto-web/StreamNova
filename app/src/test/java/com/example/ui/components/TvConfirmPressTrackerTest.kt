package com.example.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pressione del tasto di conferma su una card TV (long-press del menu contestuale di
 * «Continua a guardare»).
 *
 * Difetti coperti:
 * - una pressione prolungata poteva scattare PIÙ VOLTE se il focus usciva e rientrava nella
 *   card mentre il tasto era ancora premuto (gli eventi ripetuti riarmavano il timer);
 * - il rilascio di una pressione iniziata prima della perdita di focus produceva un click
 *   implicito al rientro (avvio della riproduzione non richiesto).
 */
class TvConfirmPressTrackerTest {

  @Test
  fun unaNuovaPressioneArmaIlTimerDiPressioneProlungata() {
    val tracker = TvConfirmPressTracker()

    assertTrue("repeatCount == 0 è una pressione nuova", tracker.onKeyDown(repeatCount = 0))
    assertTrue(tracker.keyDownActive)
    assertFalse(tracker.longPressTriggered)
  }

  @Test
  fun leRipetizioniDelTastoNonRiarmanoMaiIlTimer() {
    val tracker = TvConfirmPressTracker()
    tracker.onKeyDown(repeatCount = 0)

    repeat(10) { repeatIndex ->
      assertFalse(
        "il ripetuto $repeatIndex non deve riarmare il timer",
        tracker.onKeyDown(repeatCount = repeatIndex + 1)
      )
    }
    assertTrue(tracker.keyDownActive)
    assertFalse("la pressione prolungata non è ancora scattata", tracker.longPressTriggered)
  }

  @Test
  fun laPressioneProlungataScattaUnaSolaVoltaPerPressione() {
    val tracker = TvConfirmPressTracker()
    tracker.onKeyDown(repeatCount = 0)

    assertTrue("prima scadenza: scatta", tracker.onLongPressTimeout())
    assertFalse("seconda scadenza dello stesso timer: già scattata", tracker.onLongPressTimeout())
    repeat(5) { tracker.onKeyDown(repeatCount = it + 1) }
    assertFalse(
      "i ripetuti dopo lo scatto non devono far ripartire la pressione prolungata",
      tracker.onLongPressTimeout()
    )
  }

  @Test
  fun ilRilascioDellaPressioneProlungataNonValeComeClick() {
    val tracker = TvConfirmPressTracker()
    tracker.onKeyDown(repeatCount = 0)
    tracker.onLongPressTimeout()

    assertTrue(tracker.onKeyUp() == TvConfirmRelease.CONSUMED)
    assertFalse(tracker.keyDownActive)
    assertFalse(tracker.longPressTriggered)
  }

  @Test
  fun unaPressioneNormaleValeComeClick() {
    val tracker = TvConfirmPressTracker()
    tracker.onKeyDown(repeatCount = 0)

    assertTrue(tracker.onKeyUp() == TvConfirmRelease.CLICK)
  }

  @Test
  fun laPerditaDiFocusConsumaLaPressioneInVolo() {
    val tracker = TvConfirmPressTracker()
    tracker.onKeyDown(repeatCount = 0)
    tracker.onFocusLost()

    assertTrue(
      "il rilascio arrivato dopo il rientro del focus non deve cliccare",
      tracker.onKeyUp() == TvConfirmRelease.CONSUMED
    )
  }

  @Test
  fun laPerditaDiFocusDopoLaPressioneProlungataMantieneIlRilascioConsumato() {
    val tracker = TvConfirmPressTracker()
    tracker.onKeyDown(repeatCount = 0)
    tracker.onLongPressTimeout()
    tracker.onFocusLost()

    assertTrue(tracker.onKeyUp() == TvConfirmRelease.CONSUMED)
  }

  @Test
  fun dopoUnRientroDelFocusISoloIRipetutiNonRiattivanoLaPressioneProlungata() {
    val tracker = TvConfirmPressTracker()

    // Pressione prolungata che ha aperto il popup: il focus si sposta nel popup.
    tracker.onKeyDown(repeatCount = 0)
    tracker.onLongPressTimeout()
    tracker.onFocusLost()

    // Il focus rientra nella card mentre il tasto è ancora premuto: arrivano solo ripetuti.
    assertFalse(tracker.onKeyDown(repeatCount = 3))
    assertFalse("nessuna seconda apertura", tracker.onLongPressTimeout())
    assertTrue("il rilascio resta consumato", tracker.onKeyUp() == TvConfirmRelease.CONSUMED)

    // Una pressione NUOVA (dopo il rilascio) torna a funzionare normalmente.
    assertTrue(tracker.onKeyDown(repeatCount = 0))
    assertTrue(tracker.onKeyUp() == TvConfirmRelease.CLICK)
  }

  @Test
  fun unRilascioOrfanoSenzaPressioneTracciataNonProduceClick() {
    val tracker = TvConfirmPressTracker()

    assertTrue(tracker.onKeyUp() == TvConfirmRelease.CONSUMED)
  }
}
