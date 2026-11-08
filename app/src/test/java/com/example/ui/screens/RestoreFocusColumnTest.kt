package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Risoluzione della COLONNA di ripristino tramite la KEY STABILE della card.
 *
 * Contratto: l'indice salvato non è mai una posizione "ricordata"; la colonna si risolve per
 * IDENTITÀ del titolo nella lista dati e si VERIFICA con la key Compose della card. Se il
 * catalogo si è riordinato mentre la scheda Dettaglio era aperta, la posizione reale della key
 * vince sull'indice calcolato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreFocusColumnTest {

  private fun items(vararg pairs: Pair<Int, String>): List<RestoreRowItem> =
    pairs.map { (index, key) -> RestoreRowItem(index, key) }

  @Test
  fun senzaKeyStabileVinceLIndiceDiIdentita() {
    assertEquals(
      3,
      resolveRestoreColumn(
        identityIndex = 3,
        stableKey = null,
        visibleItems = items(0 to "a", 1 to "b")
      )
    )
  }

  @Test
  fun laKeyAllIndiceRisoltoConfermaLaPosizione() {
    assertEquals(
      2,
      resolveRestoreColumn(
        identityIndex = 2,
        stableKey = "trend_m_42",
        visibleItems = items(2 to "trend_m_42", 3 to "trend_m_43")
      )
    )
  }

  @Test
  fun cellaNonAncoraCompostaMantieneLIndiceDiIdentita() {
    assertEquals(
      5,
      resolveRestoreColumn(
        identityIndex = 5,
        stableKey = "cw_9",
        visibleItems = items(0 to "cw_1", 1 to "cw_2")
      )
    )
  }

  @Test
  fun riordinoInCorsoVinceLaPosizioneRealeDellaKey() {
    // La lista dati dice "indice 4", ma in quella cella ora c'è un'ALTRA card: la card
    // salvata è composta all'indice 9 e il ripristino deve seguire lei.
    assertEquals(
      9,
      resolveRestoreColumn(
        identityIndex = 4,
        stableKey = "trend_m_77",
        visibleItems = items(3 to "trend_m_1", 4 to "trend_m_2", 9 to "trend_m_77")
      )
    )
  }

  @Test
  fun keySparitaDallaFinestraMantieneLIndiceDiIdentita() {
    assertEquals(
      4,
      resolveRestoreColumn(
        identityIndex = 4,
        stableKey = "trend_m_77",
        visibleItems = items(3 to "trend_m_1", 4 to "trend_m_2")
      )
    )
  }

  @Test
  fun titoloNonPiùPresenteRestituisceIndiceNegativo() {
    assertEquals(
      -1,
      resolveRestoreColumn(
        identityIndex = -1,
        stableKey = "trend_m_77",
        visibleItems = items(0 to "trend_m_1")
      )
    )
  }
}
