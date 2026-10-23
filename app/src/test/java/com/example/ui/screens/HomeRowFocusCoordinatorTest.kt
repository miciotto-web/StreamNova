package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    ),
    identities: Map<String, List<String>> = emptyMap()
  ) = HomeRowFocusCoordinator(keys, counts, identities)

  /** Identità sintetiche "b0".."b{count-1}" con prefisso distinto. */
  private fun ids(prefix: String, count: Int): List<String> = List(count) { "$prefix$it" }

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
  fun focusedRowKeyRestaNullFincheUnaCellaNonRiceveIlFocus() {
    val c = coordinator()
    assertNull(c.focusedRowKey)
    c.onCellFocused("hero_banner", 0)
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

  // ------------------------------------------------------ movimento verticale

  @Test
  fun rigaAdiacenteNonVieneMaiSaltata() {
    val c = coordinator()
    assertEquals("section_providers_hub", c.adjacentRowKey("hero_banner", HomeVerticalDirection.DOWN))
    assertEquals("section_trending_film", c.adjacentRowKey("section_providers_hub", HomeVerticalDirection.DOWN))
    assertEquals("section_providers_hub", c.adjacentRowKey("section_trending_film", HomeVerticalDirection.UP))
    assertNull(c.adjacentRowKey("hero_banner", HomeVerticalDirection.UP))
    assertNull(c.adjacentRowKey("section_trending_film", HomeVerticalDirection.DOWN))
  }

  @Test
  fun ilGiùRisolveLaStessaColonnaNellaRigaAdiacente() {
    val c = coordinator()
    val target = c.resolveVerticalTarget("section_providers_hub", fromCol = 3, HomeVerticalDirection.DOWN)
    assertEquals(HomeCellTarget("section_trending_film", 3), target)
  }

  @Test
  fun rigaPiuCortaSceglieLaColonnaValidaPiuVicina() {
    val c = coordinator(
      keys = listOf("row_lunga", "row_corta"),
      counts = mapOf("row_lunga" to 20, "row_corta" to 3)
    )
    // Colonna di partenza 10 su una riga da 3 card: si sceglie l'ultima valida, mai l'indice 0.
    assertEquals(HomeCellTarget("row_corta", 2), c.resolveVerticalTarget("row_lunga", 10, HomeVerticalDirection.DOWN))
    assertEquals(HomeCellTarget("row_corta", 0), c.resolveVerticalTarget("row_lunga", 0, HomeVerticalDirection.DOWN))
  }

  @Test
  fun laColonnaRicordataDellaRigaDiDestinazioneNonSostituisceLaColonnaDiPartenza() {
    val c = coordinator()
    // La riga di destinazione è già stata visitata sulla colonna 0.
    c.onCellFocused("section_trending_film", 0)

    val target = c.resolveVerticalTarget("section_providers_hub", fromCol = 6, HomeVerticalDirection.DOWN)
    assertEquals(HomeCellTarget("section_trending_film", 6), target)
  }

  @Test
  fun senzaColonnaDiPartenzaSiUsaLaColonnaRicordata() {
    val c = coordinator()
    c.onCellFocused("section_providers_hub", 3)
    // Superficie senza colonne proprie (Hero): si riparte da dove il focus aveva lasciato la riga.
    assertEquals(
      HomeCellTarget("section_providers_hub", 3),
      c.resolveVerticalTarget("hero_banner", null, HomeVerticalDirection.DOWN)
    )
  }

  @Test
  fun direzioneSenzaRigaAdiacenteNonConsumaIlTasto() {
    val c = coordinator()
    assertFalse(c.requestVerticalMove("section_trending_film", 0, HomeVerticalDirection.DOWN))
    assertFalse(c.requestVerticalMove("hero_banner", 0, HomeVerticalDirection.UP))
    assertNull(c.pendingFocusRequest)
  }

  @Test
  fun richiestaVerticaleVersoCellaNonMontataRestaPendente() {
    val c = coordinator()
    assertTrue(c.requestVerticalMove("section_providers_hub", 3, HomeVerticalDirection.DOWN))
    assertEquals("section_trending_film" to 3, c.pendingFocusRequest)
    assertFalse(c.isCellAttached("section_trending_film", 3))

    // Un aggiornamento delle righe che non tocca la destinazione non la cancella: la richiesta
    // resta pendente finché la cella non compare (a completarla è HomeVerticalFocusSync).
    c.updateRows(
      listOf("hero_banner", "section_providers_hub", "section_trending_film"),
      mapOf("hero_banner" to 1, "section_providers_hub" to 4, "section_trending_film" to 20)
    )
    assertEquals("section_trending_film" to 3, c.pendingFocusRequest)
  }

  @Test
  fun richiestaPendenteSiCompletaQuandoLaCellaDiventaDisponibile() {
    val c = coordinator()
    c.requestVerticalMove("section_providers_hub", 3, HomeVerticalDirection.DOWN)
    c.registerCell("section_trending_film", 3)

    assertTrue(c.isCellAttached("section_trending_film", 3))
    assertEquals("section_trending_film" to 3, c.consumePendingFocusRequest())
    // Il requester della cella bersaglio esiste ed è quello che la sincronizzazione usa.
    assertNotNull(c.getRequester("section_trending_film", 3))
  }

  @Test
  fun ilFocusSuUnaCellaDiversaAnnullaLaRichiestaPendente() {
    val c = coordinator()
    c.requestVerticalMove("section_providers_hub", 3, HomeVerticalDirection.DOWN)
    c.onCellFocused("section_providers_hub", 2)
    assertNull(c.pendingFocusRequest)
  }

  @Test
  fun ilFocusSullaCellaBersaglioMantieneLaRichiestaPendente() {
    val c = coordinator()
    c.requestVerticalMove("section_providers_hub", 3, HomeVerticalDirection.DOWN)
    c.onCellFocused("section_trending_film", 3)
    assertEquals("section_trending_film" to 3, c.pendingFocusRequest)
    assertTrue(c.isCellFocused("section_trending_film", 3))
  }

  @Test
  fun getTargetRequesterUsaLaCellaMontataPiuVicinaAncheOltreLaColonnaZero() {
    val c = coordinator()
    // Solo le prime tre celle della riga di destinazione sono montate.
    c.registerCell("section_trending_film", 0)
    c.registerCell("section_trending_film", 1)
    c.registerCell("section_trending_film", 2)

    // Colonna desiderata 5: si usa la 2 (la più vicina montata), mai un requester orfano.
    assertSame(c.getRequester("section_trending_film", 2), c.getTargetRequester("section_trending_film", 5))
  }

  @Test
  fun rimozioneDellaRigaFocalizzataAzzeraAncheLaCellaFocalizzata() {
    val c = coordinator()
    c.onCellFocused("section_trending_film", 2)
    assertTrue(c.isCellFocused("section_trending_film", 2))

    c.updateRows(
      listOf("hero_banner", "section_providers_hub"),
      mapOf("hero_banner" to 1, "section_providers_hub" to 4)
    )

    assertNull(c.focusedCell)
    assertFalse(c.isCellFocused("section_trending_film", 2))
  }

  @Test
  fun isRowAttachedRifletteLeCelleMontate() {
    val c = coordinator()
    assertFalse(c.isRowAttached("section_trending_film"))
    c.registerCell("section_trending_film", 7)
    assertTrue(c.isRowAttached("section_trending_film"))
    c.unregisterCell("section_trending_film", 7)
    assertFalse(c.isRowAttached("section_trending_film"))
  }

  // ------------------------------------------------------ riallineamento per identità

  private val rowKeysAB = listOf("row_a", "row_b")

  @Test
  fun inserimentoPrimaDelTargetLaRichiestaRipuntaAllaStessaCard() {
    val c = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 10)
    )
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10))
    )
    c.requestVerticalMove("row_a", 3, HomeVerticalDirection.DOWN)
    assertEquals("row_b" to 3, c.pendingFocusRequest)
    assertEquals("b3", c.pendingFocusIdentity)

    // Ondata successiva: un contenuto appare in testa alla riga bersaglio (b3 slitta a 4).
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 11),
      mapOf("row_a" to ids("a", 20), "row_b" to listOf("b_nuovo") + ids("b", 10))
    )
    assertEquals("row_b" to 4, c.pendingFocusRequest)
    assertEquals("b3", c.pendingFocusIdentity)
  }

  @Test
  fun riordinoDellaRigaRilocalizzatoTramiteIdentita() {
    val c = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 10)
    )
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10))
    )
    c.requestVerticalMove("row_a", 6, HomeVerticalDirection.DOWN)
    assertEquals("row_b" to 6, c.pendingFocusRequest)
    assertEquals("b6", c.pendingFocusIdentity)

    // Scambio posizioni 6 <-> 2 a parità di chiavi e conteggi: l'early return NON deve
    // saltare l'aggiornamento quando cambiano le identità, e la richiesta segue b6.
    val reordered = ids("b", 10).toMutableList()
    reordered[6] = "b2"
    reordered[2] = "b6"
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to reordered.toList())
    )
    assertEquals("row_b" to 2, c.pendingFocusRequest)
    assertEquals("b6", c.pendingFocusIdentity)
  }

  @Test
  fun rimozioneDiElementiPrecedentiAggiornaLIndiceDelTarget() {
    val c = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 10)
    )
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10))
    )
    c.requestVerticalMove("row_a", 4, HomeVerticalDirection.DOWN)
    assertEquals("row_b" to 4, c.pendingFocusRequest)

    // Ondata: b0 e b1 spariscono (b4 si sposta a 2).
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 8),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10).drop(2))
    )
    assertEquals("row_b" to 2, c.pendingFocusRequest)
    assertEquals("b4", c.pendingFocusIdentity)
  }

  @Test
  fun targetEliminatoRipiegaSulFallbackPosizionaleConClamp() {
    val c = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 10)
    )
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10))
    )
    c.requestVerticalMove("row_a", 8, HomeVerticalDirection.DOWN)
    assertEquals("b8", c.pendingFocusIdentity)

    // Ondata: la riga si accorcia a 5 elementi e b8 non esiste più: fallback posizionale con
    // clamp all'ultimo indice valido, e l'identità (non più raggiungibile) viene dimenticata.
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 5),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 5))
    )
    assertEquals("row_b" to 4, c.pendingFocusRequest)
    assertNull(c.pendingFocusIdentity)
  }

  @Test
  fun rigaAccorciataConTargetAncoraPresenteLIndiceRestaValido() {
    val c = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 10)
    )
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10))
    )
    c.requestVerticalMove("row_a", 8, HomeVerticalDirection.DOWN)
    assertEquals("b8", c.pendingFocusIdentity)

    // La riga si accorcia ma il contenuto target resta: il seguito segue l'identità sul
    // nuovo indice, che è dentro i nuovi limiti della riga.
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 6),
      mapOf("row_a" to ids("a", 20), "row_b" to listOf("b2", "b3", "b4", "b5", "b8", "b9"))
    )
    assertEquals("row_b" to 4, c.pendingFocusRequest)
    assertEquals("b8", c.pendingFocusIdentity)
  }

  @Test
  fun rigaSenzaIdentitaAccorciataRipiegaSulClampPosizionale() {
    val c = coordinator(
      keys = listOf("row_a", "providers"),
      counts = mapOf("row_a" to 20, "providers" to 10)
    )
    // Nessuna identità per "providers" (superficie speciale come l'hub provider reale).
    c.requestVerticalMove("row_a", 6, HomeVerticalDirection.DOWN)
    assertEquals("providers" to 6, c.pendingFocusRequest)
    assertNull(c.pendingFocusIdentity)

    c.updateRows(listOf("row_a", "providers"), mapOf("row_a" to 20, "providers" to 4))
    // Indice oltre il nuovo conteggio: clamp posizionale all'ultimo valido (comportamento
    // equivalente a quello che il completamento applicava via resolveTargetColumn).
    assertEquals("providers" to 3, c.pendingFocusRequest)
    assertNull(c.pendingFocusIdentity)
  }

  @Test
  fun rigaSvuotataAnnullaLaRichiestaELeRegistrazioni() {
    val c = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 10)
    )
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10))
    )
    c.registerCell("row_b", 0)
    c.requestVerticalMove("row_a", 1, HomeVerticalDirection.DOWN)
    assertEquals("row_b" to 1, c.pendingFocusRequest)

    // Riga svuotata (riga ancora in chiavi ma senza contenuti): nessuna cella potrà mai
    // soddisfare la richiesta, quindi è annullata come per una riga rimossa.
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 0),
      mapOf("row_a" to ids("a", 20), "row_b" to emptyList())
    )
    assertNull(c.pendingFocusRequest)
    assertNull(c.pendingFocusIdentity)
    assertFalse(c.isCellAttached("row_b", 0))
  }

  @Test
  fun rigaRimossaAnnullaRichiestaEIdentitaSenzaRequesterObsoleti() {
    val c = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 10)
    )
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10))
    )
    val oldRequester = c.getRequester("row_b", 1)
    c.registerCell("row_b", 1)
    c.requestVerticalMove("row_a", 1, HomeVerticalDirection.DOWN)
    assertEquals("row_b" to 1, c.pendingFocusRequest)
    assertEquals("b1", c.pendingFocusIdentity)

    c.updateRows(
      listOf("row_a"),
      mapOf("row_a" to 20),
      mapOf("row_a" to ids("a", 20))
    )
    assertNull(c.pendingFocusRequest)
    assertNull(c.pendingFocusIdentity)
    assertFalse(c.isCellAttached("row_b", 1))
    // Il requester della riga rimossa è stato scartato: se ne crea uno nuovo, mai riusato.
    assertNotSame(oldRequester, c.getRequester("row_b", 1))
  }

  @Test
  fun laPoliticaDellaColonnaDiPartenzaRestaInvariataConLeIdentita() {
    val c = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 3)
    )
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 3),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 3))
    )
    // La riga di destinazione è già stata visitata sulla colonna 0.
    c.onCellFocused("row_b", 0)

    // La colonna di PARTENZA (6) vince sulla colonna ricordata della destinazione, con il
    // clamp del caso (riga da 3): identità o no, la policy non cambia.
    val target = c.resolveVerticalTarget("row_a", 6, HomeVerticalDirection.DOWN)
    assertEquals(HomeCellTarget("row_b", 2), target)

    c.requestVerticalMove("row_a", 6, HomeVerticalDirection.DOWN)
    assertEquals("row_b" to 2, c.pendingFocusRequest)
    assertEquals("b2", c.pendingFocusIdentity)

    // Un riordino della riga bersaglio riallinea la richiesta ma NON tocca la memoria di
    // colonna (lastFocusedColByRow ricorda la colonna, non l'ultima card).
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 3),
      mapOf("row_a" to ids("a", 20), "row_b" to listOf("b2", "b0", "b1"))
    )
    assertEquals(0, c.lastFocusedColByRow["row_b"])
    // Coerentemente, la richiesta prosegue il contenuto b2 alla nuova posizione 0.
    assertEquals("row_b" to 0, c.pendingFocusRequest)
  }

  @Test
  fun confermaDelFocusInvalidataNonConsideraPiuLaCellaFocalizzata() {
    val c = coordinator(
      keys = listOf("section_continue_watching", "section_trending_film"),
      counts = mapOf("section_continue_watching" to 4, "section_trending_film" to 20)
    )
    c.registerCell("section_continue_watching", 2)
    c.onCellFocused("section_continue_watching", 2)
    assertTrue(c.isCellFocused("section_continue_watching", 2))

    // Il contenuto perde il focus (es. overlay del player sopra la Home): la cella NON ha più
    // il focus, quindi la conferma letta dalla sincronizzazione non deve più risultare vera —
    // altrimenti il ripristino verrebbe consumato senza che il focus si sia mosso.
    c.onCellFocusInvalidated()
    assertFalse(c.isCellFocused("section_continue_watching", 2))
    assertNull(c.focusedCell)

    // La MEMORIA della partenza resta: riga e colonna servono ancora al recupero e al
    // ripristino della riga (si perde la conferma, non la memoria).
    assertEquals("section_continue_watching", c.focusedRowKey)
    assertEquals(2, c.lastFocusedColumn("section_continue_watching"))
    assertTrue(c.isCellAttached("section_continue_watching", 2))
  }

  @Test
  fun confermaDelFocusInvalidataPoiRiassuntaDallaCellaDiRientro() {
    val c = coordinator(
      keys = listOf("section_continue_watching"),
      counts = mapOf("section_continue_watching" to 4)
    )
    c.registerCell("section_continue_watching", 1)
    c.onCellFocused("section_continue_watching", 1)
    c.onCellFocusInvalidated()
    assertFalse(c.isCellFocused("section_continue_watching", 1))

    // Al rientro la card bersaglio riconferma il focus (onFocusChanged): la verifica torna
    // vera e la richiesta pendente può essere consumata.
    c.requestPendingFocus("section_continue_watching", 1)
    c.onCellFocused("section_continue_watching", 1)
    assertTrue(c.isCellFocused("section_continue_watching", 1))
    assertNotNull(c.pendingFocusRequest)
  }

  @Test
  fun cellaDiPartenzaSparitaRipiegaSullaPrimaCellaMontataDellaRiga() {
    val c = coordinator(
      keys = listOf("section_continue_watching", "section_trending_film"),
      counts = mapOf("section_continue_watching" to 4, "section_trending_film" to 20)
    )
    c.registerCell("section_continue_watching", 2)
    c.onCellFocused("section_continue_watching", 2)
    // L'overlay del player prende il focus: la conferma di cella non e' piu' valida.
    c.onCellFocusInvalidated()

    // Al rientro la card di origine (indice 2) non esiste piu': la riga si e' accorciata e la
    // sua cella non e' piu' montata. Il recupero della navigabilità deve ripiegare sulla PRIMA
    // cella MONTATA della riga di origine: mai un indice fuori range e mai una cella non
    // montata (un FocusRequester orfano fa esplodere la ricerca del focus del D-pad).
    c.unregisterCell("section_continue_watching", 2)
    c.updateRows(
      listOf("section_continue_watching", "section_trending_film"),
      mapOf("section_continue_watching" to 2, "section_trending_film" to 20)
    )
    c.registerCell("section_continue_watching", 0)
    c.registerCell("section_continue_watching", 1)

    assertEquals(
      "la colonna di partenza resta memorizzata (si perde la conferma, non la memoria)",
      2,
      c.lastFocusedColumn("section_continue_watching")
    )
    assertFalse(c.isCellAttached("section_continue_watching", 2))
    assertEquals(0, c.firstAttachedColumn("section_continue_watching"))
    // Colonna riallineata alla riga più corta: la cella di partenza non è più raggiungibile.
    assertEquals(1, c.resolveTargetColumn("section_continue_watching", 2))
  }

  @Test
  fun updateRowsSenzaParametroIdentitaMantieneIdentitaERichieste() {
    val c = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 10)
    )
    c.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10))
    )
    c.requestVerticalMove("row_a", 3, HomeVerticalDirection.DOWN)
    assertEquals("row_b" to 3, c.pendingFocusRequest)
    assertEquals("b3", c.pendingFocusIdentity)

    // Chiamata legacy (senza identità, onda con conteggio cambiato): le identità già note
    // restano valide e la richiesta non viene toccata a torto.
    c.updateRows(rowKeysAB, mapOf("row_a" to 21, "row_b" to 10))
    assertEquals("row_b" to 3, c.pendingFocusRequest)
    assertEquals("b3", c.pendingFocusIdentity)

    // Senza richiesta pendente, le ondate (anche di solo riordino) non lasciano nessuno
    // stato residuo di identità.
    val d = coordinator(
      keys = rowKeysAB,
      counts = mapOf("row_a" to 20, "row_b" to 10)
    )
    d.updateRows(
      rowKeysAB,
      mapOf("row_a" to 20, "row_b" to 10),
      mapOf("row_a" to ids("a", 20), "row_b" to ids("b", 10))
    )
    assertNull(d.pendingFocusRequest)
    assertNull(d.pendingFocusIdentity)
  }
}
