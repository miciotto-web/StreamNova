package com.example.ui.screens

import com.example.ui.components.DpadVisibleRowBounds
import com.example.ui.components.resolveFastScrollLandingRowKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Geometria pura dell'atterraggio del fast-scroll verticale (port di NuvioTV
 * `ModernHomeRowsList.resolveVerticalLanding` + `DpadFastScrollModifier.dpadVerticalFastScroll`):
 *
 * - tenendo GIÙ: si atterra sulla prima riga completamente visibile; se l'ULTIMA riga è già
 *   interamente in fondo, quella (il drag è finito perché la lista non può più scorrere);
 * - tenendo SU: la riga di cima se è almeno a metà dentro il viewport;
 * - le righe non note all'ordine (residui di rimozione) non sono candidati validi.
 *
 * Il drag REALE (ripetuti di sistema dei tasti tenuti) non è iniettabile dalle API dei test di
 * Compose, quindi il ciclo completo drag+atterraggio richiede la procedura per emulatore
 * descritta nel report; la geometria di atterraggio è invece dimostrata qui in modo
 * deterministico, mentre il takeover del viewport è il codice NuvioTV portato verbatim
 * (unica differenza: il parametro `shouldEngage` di
 * [com.example.ui.components.dpadVerticalFastScroll], che esclude l'Hero dal takeover).
 */
class HomeFastScrollLandingTest {

  /** Righe alte 200: [offset] -> DpadVisibleRowBounds. */
  private fun rows(vararg defs: Pair<String, Int>) =
    defs.map { (key, offset) -> DpadVisibleRowBounds(key, offset, 200) }

  private val allKeys = listOf("hero_banner", "row_a", "row_b", "row_last")

  // ---------------------------------------------------------------- GIÙ (sign > 0)

  @Test
  fun giuDopoIlDragAtterraSullaPrimaRigaConBordoInternoNelViewport() {
    // Il drag ha spinto la lista: hero e row_a son tagliate dal bordo superiore (offset<0),
    // row_b è la prima con offset>=0, row_last è tagliata dal bordo inferiore.
    val visible = rows("hero_banner" to -400, "row_a" to -160, "row_b" to 40, "row_last" to 560)

    val landing = resolveFastScrollLandingRowKey(
      visibleRows = visible, viewportEndOffset = 700, sign = 1, rowKeysInOrder = allKeys
    )

    assertEquals("row_b", landing)
  }

  @Test
  fun giuSulFondoAtterraSullUltimaRiga() {
    // L'ultima riga è interamente nel viewport: il drag non può proseguire e l'atterraggio è
    // sull'ultima riga (un Giù tenuto a fondo lista deve fermarsi sull'ultima riga, non
    // atterrare su una riga intermedia).
    val visible = rows("row_b" to 100, "row_last" to 300)

    val landing = resolveFastScrollLandingRowKey(
      visibleRows = visible, viewportEndOffset = 700, sign = 1, rowKeysInOrder = allKeys
    )

    assertEquals("row_last", landing)
  }

  // ---------------------------------------------------------------- SU (sign < 0)

  @Test
  fun suConLaRigaDiCimaAMetaDentroViewportAtterraSuDiLei() {
    // La riga di cima è appena sfiorata dal bordo (-60 su 200, ben oltre la metà dentro):
    // risalendo il drag atterra lì. row_last resta tagliata dal bordo inferiore (non è il
    // caso "sul fondo").
    val visible = rows("row_a" to -60, "row_b" to 140, "row_last" to 560)

    val landing = resolveFastScrollLandingRowKey(
      visibleRows = visible, viewportEndOffset = 700, sign = -1, rowKeysInOrder = allKeys
    )

    assertEquals("row_a", landing)
  }

  @Test
  fun suConLaRigaDiCimaQuasiInteramenteUscitaAtterraSullaSuccessiva() {
    // La riga di cima ha oltrepassato il confine della metà (-150 su 200): non è un
    // atterraggio leggibile, si considera la riga successiva (bordo interno >= 0:
    // row_b). row_last resta tagliata dal bordo inferiore.
    val visible = rows("row_a" to -150, "row_b" to 50, "row_last" to 550)

    val landing = resolveFastScrollLandingRowKey(
      visibleRows = visible, viewportEndOffset = 700, sign = -1, rowKeysInOrder = allKeys
    )

    assertEquals("row_b", landing)
  }

  // ---------------------------------------------------------------- robustezza

  @Test
  fun rigaVisibileNonNotaAllOrdineNonEUnCandidato() {
    // La riga fantasma (offset 200) verrebbe scelta dalla geometria ma non appartiene
    // alle righe note: l'atterraggio è annullato (il focus resta dov'è, nessun salto).
    val visible = rows("row_b" to 0, "riga_fantasma" to 200)

    val landing = resolveFastScrollLandingRowKey(
      visibleRows = visible, viewportEndOffset = 700, sign = 1, rowKeysInOrder = allKeys
    )

    assertEquals("row_b", landing)
  }

  @Test
  fun candidataNonNotaComePrimaRigaVisibileAnnullaLAtterraggio() {
    // La prima riga con bordo interno nel viewport non è nota: nessun atterraggio.
    val visible = rows("riga_fantasma" to 0, "row_b" to 200)

    val landing = resolveFastScrollLandingRowKey(
      visibleRows = visible, viewportEndOffset = 700, sign = 1, rowKeysInOrder = allKeys
    )

    assertNull(landing)
  }

  @Test
  fun nessunaRigaVisibileNonDaAtterraggio() {
    val landing = resolveFastScrollLandingRowKey(
      visibleRows = emptyList(), viewportEndOffset = 700, sign = 1, rowKeysInOrder = allKeys
    )

    assertNull(landing)
  }
}
