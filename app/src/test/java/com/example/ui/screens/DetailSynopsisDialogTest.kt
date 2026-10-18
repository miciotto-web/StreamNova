package com.example.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione della sinossi lunga nella scheda di dettaglio: la sinossi troncata non deve
 * più nascondere i pulsanti (Riproduci, Preferiti, ...) né impedirne la navigazione D-pad.
 * Copre la policy del componente (righe configurabili, casi vuoto/breve/lunga) e la
 * macchina a stati del dialog del testo completo (apertura con OK, chiusura con Back e
 * richiesta di ripristino del focus sulla sinossi ridotta).
 */
class DetailSynopsisDialogTest {

  private val shortSynopsis = "Un film breve e semplice."

  private val longSynopsis = """
      Una vicenda molto articolata: la sinossi originale si estende per una decina di
      righe e, prima della correzione, veniva renderizzata senza limite di righe nella
      scheda di dettaglio, spingendo la barra dei comandi fuori dal viewport dello
      schermo televisivo e rendendo i pulsanti in raggiungibili con il telecomando.
      Questo testo serve solo a superare il limite configurato del componente.
  """.trimIndent()

  // ── Policy del componente: righe configurabili e casi vuoto/breve/lunga ──────

  @Test
  fun ilLimiteDelleRigheEVeroERagionevole() {
    assertTrue(DETAIL_SYNOPSIS_MAX_LINES in 1..6)
  }

  @Test
  fun sinossiVuotaONullaNonRiceveFocusENonApreDialog() {
    assertFalse(hasFocusableSynopsis(null))
    assertFalse(hasFocusableSynopsis(""))
    assertFalse(hasFocusableSynopsis("   "))
  }

  @Test
  fun sinossiBreveRiceveFocusEApreIlDialog() {
    assertTrue(hasFocusableSynopsis(shortSynopsis))
    val state = DetailSynopsisDialogState()
    state.open()
    assertTrue(state.isDialogOpen)
  }

  @Test
  fun sinossiLungaRiceveFocusEApreIlDialog() {
    assertTrue(hasFocusableSynopsis(longSynopsis))
    val state = DetailSynopsisDialogState()
    state.open()
    assertTrue(state.isDialogOpen)
  }

  // ── Dialog del testo completo: apertura con OK, chiusura con Back, ripristino focus ──

  @Test
  fun aperturaEChiusuraConBackLasciaRichiestaDiRipristinoFocus() {
    val state = DetailSynopsisDialogState()
    state.open()
    assertTrue(state.isDialogOpen)

    state.dismiss() // Back / icona di chiusura
    assertFalse(state.isDialogOpen)
    assertTrue(state.refocusRequestPending)
  }

  @Test
  fun ilRipristinoDelFocusVieneConsumatoUnaSolaVolta() {
    val state = DetailSynopsisDialogState()
    state.open()
    state.dismiss()
    assertTrue(state.refocusRequestPending)

    state.consumeRefocusRequest()
    assertFalse(state.refocusRequestPending)
    state.consumeRefocusRequest()
    assertFalse(state.refocusRequestPending) // il secondo consumo è un no-op
  }

  @Test
  fun chiusuraSenzaDialogPrecedentementeApertoNonRichiedeRipristino() {
    val state = DetailSynopsisDialogState()
    state.dismiss()
    assertFalse(state.isDialogOpen)
    assertFalse(state.refocusRequestPending)
  }

  @Test
  fun dialogGiaApertoRestaApertoEOmetteSingolaRichiestaDiRipristino() {
    val state = DetailSynopsisDialogState()
    state.open()
    state.open() // OK ripetuto sulla sinossi mentre il dialog è già aperto
    assertTrue(state.isDialogOpen)
    state.dismiss()
    assertFalse(state.isDialogOpen)
    assertTrue(state.refocusRequestPending) // una sola richiesta di ripristino
  }

  /**
   * Cicli ripetuti di apertura/chiusura: ogni chiusura richiede il ripristino del focus
   * sulla sinossi della scheda (il dialog non "resta chiuso per sempre" né accumula
   * richieste multiple quando l'utente riapre rapidamente).
   */
  @Test
  fun cicliRipetutiAperturaChiusuraRipristinanoIlFocusOgniVolta() {
    val state = DetailSynopsisDialogState()
    repeat(3) {
      state.open()
      assertTrue(state.isDialogOpen)
      state.dismiss()
      assertFalse(state.isDialogOpen)
      assertTrue(state.refocusRequestPending)
      state.consumeRefocusRequest()
      assertFalse(state.refocusRequestPending)
    }
  }
}
