package com.aistudio.streamnova.mobile.ui

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.CacheState
import com.example.data.streaming.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test JVM delle regole della selezione manuale delle sorgenti (pulsante
 * «Seleziona fonte» della scheda Dettaglio).
 *
 * Nessuna rete, nessun Android, nessun Compose: le funzioni verificate sono
 * quelle usate dal ViewModel e dalla UI, quindi i test coprono davvero il
 * comportamento del flusso.
 */
class MobileManualSourcePolicyTest {

  private fun film(id: String = "tmdb_m_1") = MediaItem(
    id = id,
    title = "Interstellar",
    synopsis = "Un equipaggio oltre la gravità.",
    videoUrl = "",
    type = MediaType.FILM
  )

  private fun series(id: String = "tmdb_tv_1") = MediaItem(
    id = id,
    title = "Severance",
    synopsis = "Il confine fra lavoro e vita.",
    videoUrl = "",
    type = MediaType.SERIE_TV
  )

  private fun episode(season: Int, number: Int) = Episode(
    id = "tt1234567:$season:$number",
    seasonNumber = season,
    episodeNumber = number,
    title = "Pasto completo",
    synopsis = "",
    durationMinutes = 45,
    videoUrl = ""
  )

  private fun source(
    serverName: String = "TorBox",
    streamUrl: String? = "https://example.test/film.mkv"
  ) = StreamSource(
    streamUrl = streamUrl,
    quality = "1080p",
    serverName = serverName,
    declaredQuality = "1080p",
    cacheState = CacheState.Cached
  )

  // ---------------------------------------------------------------------
  // Visibilità del pulsante
  // ---------------------------------------------------------------------

  @Test
  fun ilPulsanteSelezionaFonteEsisteSoloInModalitaDebrid() {
    assertTrue(
      MobileManualSourcePolicy.showsSelectSourceAction(StreamingEngineMode.DEBRID_TORBOX)
    )
  }

  @Test
  fun ilPulsanteSelezionaFonteNonEsisteInModalitaHttp() {
    assertFalse(
      MobileManualSourcePolicy.showsSelectSourceAction(StreamingEngineMode.HTTP_WEB)
    )
  }

  // ---------------------------------------------------------------------
  // Avvio della ricerca manuale
  // ---------------------------------------------------------------------

  @Test
  fun laSelezioneManualeNonRiusaMaiLUltimoLink() {
    // Il riuso avvia direttamente il player e bypasserebbe la scelta
    // dell'utente: con selezione manuale la cache non viene consultata.
    assertFalse(
      MobileManualSourcePolicy.shouldReuseLastWorkingStream(
        manualSelection = true,
        reuseEnabled = true
      )
    )
  }

  @Test
  fun senzaSelezioneManualeIlRiusoLUltimoLinkRestaAttivo() {
    assertTrue(
      MobileManualSourcePolicy.shouldReuseLastWorkingStream(
        manualSelection = false,
        reuseEnabled = true
      )
    )
    assertFalse(
      MobileManualSourcePolicy.shouldReuseLastWorkingStream(
        manualSelection = false,
        reuseEnabled = false
      )
    )
  }

  @Test
  fun laSelezioneManualeApreIlSelettoreAncheConAutoplayAttivo() {
    assertTrue(
      MobileManualSourcePolicy.shouldShowSourcePicker(
        manualSelection = true,
        autoplayEnabled = true,
        engineMode = StreamingEngineMode.DEBRID_TORBOX,
        sourceCount = 3
      )
    )
  }

  @Test
  fun laSelezioneManualeApreIlSelettoreAncheSenzaRisultati() {
    // Zero sorgenti non devono diventare un errore fatale: il selettore resta
    // aperto e vuoto, così l'utente può chiudere o riprovare.
    assertTrue(
      MobileManualSourcePolicy.shouldShowSourcePicker(
        manualSelection = true,
        autoplayEnabled = false,
        engineMode = StreamingEngineMode.DEBRID_TORBOX,
        sourceCount = 0
      )
    )
  }

  @Test
  fun senzaSelezioneManualeIlSelettoreServeSoloConAutoplayOffInDebrid() {
    // Flusso storico (Autoplay OFF in modalità Debrid): invariato.
    assertTrue(
      MobileManualSourcePolicy.shouldShowSourcePicker(
        manualSelection = false,
        autoplayEnabled = false,
        engineMode = StreamingEngineMode.DEBRID_TORBOX,
        sourceCount = 3
      )
    )
    assertFalse(
      MobileManualSourcePolicy.shouldShowSourcePicker(
        manualSelection = false,
        autoplayEnabled = true,
        engineMode = StreamingEngineMode.DEBRID_TORBOX,
        sourceCount = 3
      )
    )
    assertFalse(
      MobileManualSourcePolicy.shouldShowSourcePicker(
        manualSelection = false,
        autoplayEnabled = false,
        engineMode = StreamingEngineMode.HTTP_WEB,
        sourceCount = 3
      )
    )
  }

  @Test
  fun laPolicyDelleImpostazioniDelegaAllaStessaRegola() {
    // La stessa decisione è raggiungibile dalla policy delle Impostazioni:
    // l'UI e i test non possono divergere.
    assertTrue(
      MobileSettingsPolicy.shouldShowSourcePicker(
        autoplayEnabled = true,
        engineMode = StreamingEngineMode.DEBRID_TORBOX,
        sourceCount = 2,
        manualSelection = true
      )
    )
    assertFalse(
      MobileSettingsPolicy.shouldShowSourcePicker(
        autoplayEnabled = true,
        engineMode = StreamingEngineMode.DEBRID_TORBOX,
        sourceCount = 2
      )
    )
  }

  // ---------------------------------------------------------------------
  // Stagione ed episodio delle serie
  // ---------------------------------------------------------------------

  @Test
  fun laSerieConservaStagioneEpisodioDiRipresa() {
    val target = MobileManualSourcePolicy.searchTarget(series(), episode(2, 5))
    assertEquals("tmdb_tv_1", target.mediaId)
    assertEquals(2, target.season)
    assertEquals(5, target.episode)
  }

  @Test
  fun ilFilmNonHaPuntata() {
    val target = MobileManualSourcePolicy.searchTarget(film(), null)
    assertEquals("tmdb_m_1", target.mediaId)
    assertNull(target.season)
    assertNull(target.episode)
  }

  @Test
  fun laSerieSenzaEpisodioRisoltoNonFissaUnaPuntata() {
    // Episodi non ancora caricati: la scelta della puntata spetta al
    // risolutore, non alla scheda.
    val target = MobileManualSourcePolicy.searchTarget(series(), null)
    assertNull(target.season)
    assertNull(target.episode)
  }

  @Test
  fun unFilmNonEreditaLaPuntataDiUnaSerie() {
    val target = MobileManualSourcePolicy.searchTarget(film(), episode(3, 7))
    assertNull(target.season)
    assertNull(target.episode)
  }

  // ---------------------------------------------------------------------
  // Selezione, annullamento, elenco vuoto
  // ---------------------------------------------------------------------

  @Test
  fun laSceltaDiUnaSorgenteChiudeIlSelettoreEPreparaIlPlayer() {
    val chosen = source()
    val state = MobilePlayerUiState(
      loading = false,
      sources = listOf(chosen),
      awaitingSourceSelection = true,
      manualSelection = true
    )

    val after = MobileManualSourcePolicy.stateAfterSelection(state, chosen)

    assertFalse(after.awaitingSourceSelection)
    assertEquals(chosen, after.source)
    assertFalse(after.loading)
    assertNull(after.error)
  }

  @Test
  fun ilSbloccoFallitoLasciaApertoIlSelettoreConLElencoInvariato() {
    val chosen = source()
    val state = MobilePlayerUiState(
      loading = false,
      sources = listOf(chosen),
      awaitingSourceSelection = true,
      manualSelection = true
    )

    val after = MobileManualSourcePolicy.stateAfterSelection(state, null)

    assertTrue(after.awaitingSourceSelection)
    assertNull(after.source)
    assertNotNull(after.error)
    assertEquals(listOf(chosen), after.sources)
    assertFalse(after.loading)
  }

  @Test
  fun lAnnullamentoNonAvviaLaRiproduzioneESvuotaLElenco() {
    val chosen = source()
    val state = MobilePlayerUiState(
      loading = false,
      sources = listOf(chosen),
      awaitingSourceSelection = true,
      manualSelection = true
    )

    val after = MobileManualSourcePolicy.stateAfterDismiss(state)

    assertFalse(after.awaitingSourceSelection)
    assertNull(after.source)
    assertTrue(after.sources.isEmpty())
    assertNull(after.error)
    assertFalse(after.loading)
  }

  @Test
  fun laRicercaManualeSenzaRisultatiLasciaIlSelettoreApertoEVuoto() {
    val base = MobilePlayerUiState(loading = false, media = film())

    val after = MobileManualSourcePolicy.stateAfterEmptyManualSearch(base)

    assertTrue(after.awaitingSourceSelection)
    assertTrue(after.manualSelection)
    assertTrue(after.sources.isEmpty())
    assertNull(after.source)
    assertNull(after.error)
    assertFalse(after.loading)
    assertEquals("tmdb_m_1", after.media?.id)
  }

  @Test
  fun lAvvioDelloSbloccoMostraIlCaricamento() {
    val state = MobilePlayerUiState(
      loading = false,
      sources = listOf(source()),
      awaitingSourceSelection = true,
      manualSelection = true
    )

    val after = MobileManualSourcePolicy.stateAfterSelectionStart(state)

    assertTrue(after.loading)
    // Il selettore resta aperto durante lo sblocco: l'elenco non sparisce.
    assertTrue(after.awaitingSourceSelection)
    assertEquals(1, after.sources.size)
  }
}
