package com.example.ui.viewmodel

import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROVENIENZA vs RICHIESTA di ripristino del focus.
 *
 * Il click su una card salva la provenienza del focus (riga + titolo + KEY STABILE), ma NON è
 * una richiesta di ripristino: la Home resta composta durante la transizione di uscita verso
 * il Dettaglio e, se il salvataggio attivasse il protocollo, la card appena cliccata
 * consumerebbe il target PRIMA del BACK (al rientro il focus finiva sull'Hero).
 *
 * La richiesta arriva SOLO con `signalFocusRestore`, emesso al rientro (BACK dal Dettaglio,
 * ritorno dal Player, ancora di contenuto della MainActivity).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusRestoreRequestTest {

  private fun viewModel() = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())

  @Test
  fun ilSalvataggioDellaProvenienzaNonEUnaRichiestaDiRipristino() {
    val vm = viewModel()

    vm.saveLastFocusedMedia("section_trending_film", "trending-film-8", "trend_m_8")

    assertEquals("section_trending_film" to "trending-film-8", vm.lastFocusedTarget.value)
    assertEquals("trending-film-8", vm.lastFocusedMediaId.value)
    assertEquals("trend_m_8", vm.lastFocusedCardKey.value)
    assertFalse(
      "il click non deve attivare il protocollo di ripristino della Home",
      vm.focusRestoreRequested.value
    )
    assertEquals(0, vm.focusRestoreToken.value)
  }

  @Test
  fun ilSegnaleDiRientroPromuoveIlTargetARichiesta() {
    val vm = viewModel()
    vm.saveLastFocusedMedia("section_popolari_film", "popolari-film-3", "pop_m_3")

    vm.signalFocusRestore("popolari-film-3")

    assertTrue(vm.focusRestoreRequested.value)
    assertEquals(1, vm.focusRestoreToken.value)
    // La provenienza (riga + key stabile) resta intatta per il ripristino.
    assertEquals("section_popolari_film" to "popolari-film-3", vm.lastFocusedTarget.value)
    assertEquals("pop_m_3", vm.lastFocusedCardKey.value)
  }

  @Test
  fun ilConsumoDelTargetAzzeraAncheLaRichiesta() {
    val vm = viewModel()
    vm.saveLastFocusedMedia("section_trending_film", "trending-film-8", "trend_m_8")
    vm.signalFocusRestore("trending-film-8")

    vm.clearLastFocusedMedia()

    assertNull(vm.lastFocusedTarget.value)
    assertNull(vm.lastFocusedMediaId.value)
    assertNull(vm.lastFocusedCardKey.value)
    assertFalse(vm.focusRestoreRequested.value)
  }

  @Test
  fun unNuovoSalvataggioDopoUnRipristinoNonEUnaRichiesta() {
    val vm = viewModel()
    vm.saveLastFocusedMedia("section_trending_film", "trending-film-8", "trend_m_8")
    vm.signalFocusRestore("trending-film-8")
    vm.clearLastFocusedMedia()

    vm.saveLastFocusedMedia("section_trending_film", "trending-film-2", "trend_m_2")

    assertFalse(vm.focusRestoreRequested.value)
    assertEquals("trend_m_2", vm.lastFocusedCardKey.value)
  }

  @Test
  fun ilSalvataggioSenzaRigaAzzeraLaKeyStabile() {
    val vm = viewModel()
    vm.saveLastFocusedMedia("section_trending_film", "trending-film-8", "trend_m_8")

    vm.saveLastFocusedMedia("trending-film-8")

    // Il salvataggio senza riga NON degrada un target row-scoped già salvato per lo stesso
    // titolo (comportamento documentato), ma la key stabile non è più quella della card.
    assertEquals("section_trending_film" to "trending-film-8", vm.lastFocusedTarget.value)
    assertNull(vm.lastFocusedCardKey.value)
    assertFalse(vm.focusRestoreRequested.value)
  }

  @Test
  fun laCardProviderSostituisceIlTargetMedia() {
    val vm = viewModel()
    vm.saveLastFocusedMedia("section_trending_film", "trending-film-8", "trend_m_8")

    vm.saveLastFocusedProvider("netflix")

    assertEquals("netflix", vm.lastFocusedProviderId.value)
    assertNull(vm.lastFocusedTarget.value)
    assertNull(vm.lastFocusedMediaId.value)
    assertNull(vm.lastFocusedCardKey.value)
    assertFalse(vm.focusRestoreRequested.value)
  }
}
