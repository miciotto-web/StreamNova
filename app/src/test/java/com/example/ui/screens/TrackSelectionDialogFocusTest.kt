package com.example.ui.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressione UX del telecomando: il focus iniziale di `TrackSelectionDialog` era fissato
 * sull'indice 0, quindi con le impostazioni (e le tracce) il D-Pad si posava sempre sulla
 * prima voce della lista anche quando la spunta era su un'altra. Un DPAD_CENTER immediato
 * confermava quella prima voce: nel dialog "Modalità di riproduzione" bastava aprire e
 * premere OK per passare da Web Streaming a Debrid TorBox.
 *
 * Qui si verifica che il focus iniziale cada sulla voce già attiva, con fallback sicuro
 * quando l'indice non è valido.
 */
@RunWith(AndroidJUnit4::class)
class TrackSelectionDialogFocusTest {

  private val modeOptions = listOf(
    "Debrid TorBox (1080p / 4K Ultra HD)",
    "Web Streaming Gratuito (HTTP Scraper)"
  )

  /** Il caso segnalato: Web Streaming attivo (indice 1) → il focus va sulla seconda voce. */
  @Test
  fun focusSullaVoceAttivaNonSullaPrima() {
    assertEquals(1, trackDialogInitialFocusIndex(modeOptions, selectedIndex = 1))
  }

  /** Con la prima voce attiva il comportamento resta quello di sempre. */
  @Test
  fun focusSullaPrimaVoceQuandoAttiva() {
    assertEquals(0, trackDialogInitialFocusIndex(modeOptions, selectedIndex = 0))
  }

  /** Liste lunghe (es. tracce sottotitoli): il focus raggiunge la voce attiva in fondo. */
  @Test
  fun focusSullUltimaVoceDiUnaListaLunga() {
    val options = List(40) { "Traccia $it" }
    assertEquals(39, trackDialogInitialFocusIndex(options, selectedIndex = 39))
  }

  /**
   * Indici fuori range ricadono su un indice reale della lista: la richiesta di focus
   * resta valida anche se un chiamante passa un valore incoerente.
   */
  @Test
  fun indiceFuoriRangeRicalaSuUnIndiceValido() {
    assertEquals(0, trackDialogInitialFocusIndex(modeOptions, selectedIndex = -1))
    assertEquals(1, trackDialogInitialFocusIndex(modeOptions, selectedIndex = 99))
  }

  /** Lista vuota: nessun crash e nessun indice negativo. */
  @Test
  fun listaVuotaNonProduceIndiciInvalidi() {
    assertEquals(0, trackDialogInitialFocusIndex(emptyList(), selectedIndex = 3))
  }
}
