package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica la logica pura di [HomeRowFocusCoordinator] dopo la stabilizzazione: lo stesso
 * coordinatore sopravvive ai caricamenti asincroni dei cataloghi, i FocusRequester già
 * assegnati restano validi, le registrazioni obsolete vengono rimosse e le richieste di
 * focus pendenti vengono completate/annullate coerentemente.
 *
 * NOTA: questi test verificano la logica del coordinatore, non il sistema di focus Compose
 * reale (quello richiede un dispositivo/emulatore). Vedi il rapporto per i limiti.
 */
class HomeRowFocusCoordinatorTest {

  private fun coordinator(
    keys: List<String> = listOf("hero_banner", "section_providers_hub", "section_trending_film"),
    counts: Map<String, Int> = mapOf(
      "hero_banner" to 1,
      "section_providers_hub" to 4,
      "section_trending_film" to 20
    )
  ) = HomeRowFocusCoordinator(keys, counts)

  @Test
  fun aggiornamentoRigheMantieneLaStessaIstanzaEIlRequester() {
    val c = coordinator()
    val hero = c.getRequester("hero_banner", 0)
    c.registerCell("hero_banner", 0)

    // Nuova ondata di cataloghi (compare "Continua a guardare"): update sul posto.
    c.updateRows(
      listOf("hero_banner", "section_continue_watching", "section_providers_hub", "section_trending_film"),
      mapOf(
        "hero_banner" to 1,
        "section_continue_watching" to 3,
        "section_providers_hub" to 4,
        "section_trending_film" to 20
      )
    )

    assertSame(hero, c.getRequester("hero_banner", 0))
    assertTrue(c.isCellAttached("hero_banner", 0))
  }

  @Test
  fun cellaDiRigaRimossaVienePulita() {
    val c = coordinator()
    val trending = c.getRequester("section_trending_film", 5)
    c.registerCell("section_trending_film", 5)

    c.updateRows(
      listOf("hero_banner", "section_providers_hub"),
      mapOf("hero_banner" to 1, "section_providers_hub" to 4)
    )

    assertFalse(c.isCellAttached("section_trending_film", 5))
    // Il requester della riga eliminata è stato scartato: se ne crea uno nuovo.
    assertNotSame(trending, c.getRequester("section_trending_film", 5))
  }

  @Test
  fun colonneOltreIlNuovoConteggioVengonoPulite() {
    val c = coordinator()
    c.registerCell("section_trending_film", 15)
    assertTrue(c.isCellAttached("section_trending_film", 15))

    c.updateRows(
      listOf("hero_banner", "section_providers_hub", "section_trending_film"),
      mapOf("hero_banner" to 1, "section_providers_hub" to 4, "section_trending_film" to 10)
    )

    assertFalse(c.isCellAttached("section_trending_film", 15))
  }

  @Test
  fun rigaAdiacenteRispettaLOrdine() {
    val c = coordinator()
    assertEquals("section_providers_hub", c.getNextRowKey("hero_banner"))
    assertEquals("section_trending_film", c.getNextRowKey("section_providers_hub"))
    assertNull(c.getNextRowKey("section_trending_film"))

    assertEquals("section_providers_hub", c.getPrevRowKey("section_trending_film"))
    assertNull(c.getPrevRowKey("hero_banner"))
  }

  @Test
  fun targetNullSeLaRigaNonHaCelleMontate() {
    val c = coordinator()
    assertNull(c.getTargetRequester("section_providers_hub", 0))
  }

  @Test
  fun targetUsaLaCellaMontataPiuVicinaAllaColonna() {
    val c = coordinator()
    c.registerCell("section_providers_hub", 3)
    assertSame(c.getRequester("section_providers_hub", 3), c.getTargetRequester("section_providers_hub", 0))
  }

  @Test
  fun richiestaPendenteConsumataUnaSolaVolta() {
    val c = coordinator()
    c.requestPendingFocus("section_trending_film", 4)
    assertEquals("section_trending_film" to 4, c.pendingFocusRequest)
    assertEquals("section_trending_film" to 4, c.consumePendingFocusRequest())
    assertNull(c.pendingFocusRequest)
    assertNull(c.consumePendingFocusRequest())
  }

  @Test
  fun richiestaPendenteAnnullataSeIlFocusVaAltrove() {
    val c = coordinator()
    c.requestPendingFocus("section_trending_film", 4)
    c.onCellFocused("hero_banner", 0)
    assertNull(c.pendingFocusRequest)
  }

  @Test
  fun richiestaPendenteMantenutaQuandoIlTargetStessoRiceveIlFocus() {
    val c = coordinator()
    c.requestPendingFocus("section_trending_film", 4)
    c.onCellFocused("section_trending_film", 4)
    assertEquals("section_trending_film" to 4, c.pendingFocusRequest)
  }

  @Test
  fun richiestaPendenteAnnullataSeLaRigaVieneRimossa() {
    val c = coordinator()
    c.requestPendingFocus("section_trending_film", 4)
    c.updateRows(
      listOf("hero_banner", "section_providers_hub"),
      mapOf("hero_banner" to 1, "section_providers_hub" to 4)
    )
    assertNull(c.pendingFocusRequest)
  }

  @Test
  fun hasItemsRifletteIConteggiCorrenti() {
    val c = coordinator()
    assertTrue(c.hasItems("section_providers_hub"))
    assertFalse(c.hasItems("section_missing"))
  }

  @Test
  fun registrationTickScattaUnaVoltaPerCella() {
    val c = coordinator()
    val start = c.registrationTick
    c.registerCell("hero_banner", 0)
    assertEquals(start + 1, c.registrationTick)
    // Riregistrare la stessa cella non produce un nuovo tick.
    c.registerCell("hero_banner", 0)
    assertEquals(start + 1, c.registrationTick)
    c.registerCell("hero_banner", 1)
    assertEquals(start + 2, c.registrationTick)
  }

  @Test
  fun focusedRowKeyInizializzataConLaPrimaRiga() {
    val c = coordinator()
    assertEquals("hero_banner", c.focusedRowKey)
  }

  @Test
  fun onCellFocusedAggiornaLaRigaFocalizzata() {
    val c = coordinator()
    c.onCellFocused("section_providers_hub", 2)
    assertEquals("section_providers_hub", c.focusedRowKey)
  }

  @Test
  fun updateRowsReimpostaFocusedRowKeySeLaRigaVieneRimossa() {
    val c = coordinator()
    c.onCellFocused("section_trending_film", 1)
    assertEquals("section_trending_film", c.focusedRowKey)

    c.updateRows(
      listOf("hero_banner", "section_providers_hub"),
      mapOf("hero_banner" to 1, "section_providers_hub" to 4)
    )
    assertEquals("hero_banner", c.focusedRowKey)
  }

  @Test
  fun updateRowsPreservaFocusedRowKeySeAncoraPresente() {
    val c = coordinator()
    c.onCellFocused("section_providers_hub", 1)

    c.updateRows(
      listOf("hero_banner", "section_continue_watching", "section_providers_hub"),
      mapOf("hero_banner" to 1, "section_continue_watching" to 2, "section_providers_hub" to 4)
    )
    assertEquals("section_providers_hub", c.focusedRowKey)
  }
}
