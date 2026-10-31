package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxStreamProvider
import com.example.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Selezione MANUALE delle sorgenti dalla scheda di dettaglio (pulsante «Seleziona fonte»,
 * solo in modalità Debrid). Copre il contratto di [StreamNovaViewModel.openManualSourceSelection]:
 *
 * 1. il bersaglio del dialog ([StreamNovaViewModel.sourceDialogMedia]) è valorizzato subito
 *    con l'identità corretta di film e serie (stagione/episodio compresi);
 * 2. la schermata di scelta si apre ANCHE con autoplay attivo: nessuna sorgente deve partire
 *    in riproduzione senza la conferma esplicita dell'utente;
 * 3. la conferma ([StreamNovaViewModel.selectSource]) avvia ESATTAMENTE la sorgente scelta;
 * 4. l'annullamento ([StreamNovaViewModel.dismissSourceDialog]) chiude la selezione senza
 *    avviare nulla e ripristina lo stato.
 *
 * Stessa struttura di [PlayNextEpisodeResolutionTest]: provider TorBox fake (nessuna rete),
 * dispatcher di test iniettato, repository impostazioni pilotati direttamente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ManualSourceSelectionTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setTorBoxInstantDebridEnabled(true)
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()
    // Modalità Debrid con autoplay ATTIVO: è il caso peggiore. La selezione manuale
    // deve comunque aprire il dialog invece di auto-selezionare una sorgente.
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setAutoplayEnabled(true)
  }

  @After
  fun tearDown() {
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()
    AppSettingsRepository.setTorBoxApiKey(null)
    Dispatchers.resetMain()
  }

  // ── Fixture ──────────────────────────────────────────────────────────────────────

  private fun film() = MediaItem(
    id = "tt0117731",
    imdbId = "tt0117731",
    title = "Toy Story",
    synopsis = "Film animato",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 862
  )

  private fun series() = MediaItem(
    id = "tt0944947",
    imdbId = "tt0944947",
    title = "Game of Thrones",
    synopsis = "Series synopsis",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 1399
  )

  private fun directSource(url: String = "https://example.com/movie.1080p.mkv") = StreamSource(
    streamUrl = url,
    quality = "1080p",
    serverName = "Provider A",
    releaseTitle = "Movie.1080p.mkv"
  )

  private fun episode(season: Int, number: Int) = Episode(
    id = "tt0944947_s${season}e$number",
    seasonNumber = season,
    episodeNumber = number,
    title = "Episodio $number",
    synopsis = "",
    durationMinutes = 60,
    videoUrl = "",
    seriesImdbId = "tt0944947"
  )

  private fun viewModelWithSource(source: StreamSource): StreamNovaViewModel {
    val fakeProvider = object : TorBoxStreamProvider() {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        imdbId: String?
      ): List<StreamSource> = listOf(source)
    }
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    viewModel.streamManager = StreamManager(providers = listOf(fakeProvider))
    viewModel.openSubtitlesProvider = object : OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ) = emptyList<Subtitle>()
    }
    return viewModel
  }

  // ── 1. Bersaglio del dialog: identità del contenuto ─────────────────────────────

  @Test
  fun manualSelectionForFilmSetsCorrectTargetImmediately() {
    val viewModel = viewModelWithSource(directSource())

    viewModel.openManualSourceSelection(film())

    // Atomico e immediato: prima di qualsiasi risoluzione di sorgenti.
    val target = viewModel.sourceDialogMedia.value
    assertEquals("tt0117731", target?.media?.id)
    assertEquals("Toy Story", target?.media?.title)
    assertNull("Per un film il target non ha episodio", target?.episode)
    assertFalse("Nessuna riproduzione al solo set del bersaglio", viewModel.showSourceDialog.value)
  }

  @Test
  fun manualSelectionForSeriesKeepsSeasonAndEpisodeIdentity() {
    val viewModel = viewModelWithSource(directSource())
    val fishes = episode(season = 2, number = 6).copy(title = "Fishes")

    viewModel.openManualSourceSelection(series(), fishes)

    val target = viewModel.sourceDialogMedia.value
    assertEquals("tt0944947", target?.media?.id)
    assertEquals("Game of Thrones", target?.media?.title)
    assertEquals(2, target?.episode?.seasonNumber)
    assertEquals(6, target?.episode?.episodeNumber)
    assertEquals("Fishes", target?.episode?.title)
  }

  @Test
  fun manualSelectionForSeriesWithoutExplicitEpisodeUsesWatchedContext() = runTest(testDispatcher) {
    val viewModel = viewModelWithSource(directSource())
    val watched = series().copy(
      lastWatchedSeason = 1,
      lastWatchedEpisode = 2,
      episodes = listOf(episode(1, 1), episode(1, 2), episode(2, 1))
    )

    viewModel.openManualSourceSelection(watched, episode = null)
    advanceUntilIdle()

    val target = viewModel.sourceDialogMedia.value
    assertEquals("tt0944947", target?.media?.id)
    assertEquals("Il contesto stagione/episodio guardato resta nel dialog", 1, target?.episode?.seasonNumber)
    assertEquals(2, target?.episode?.episodeNumber)
  }

  // ── 2. Dialog aperto anche con autoplay attivo: NESSUNA riproduzione automatica ──

  @Test
  fun manualSelectionOpensDialogWithoutAutoPlayingEvenWithAutoplayEnabled() = runTest(testDispatcher) {
    val viewModel = viewModelWithSource(directSource())

    viewModel.openManualSourceSelection(film())
    advanceUntilIdle()

    assertTrue("Il dialog di selezione deve aprirsi", viewModel.showSourceDialog.value)
    assertEquals("La sorgente risolta è proposta nella lista", 1, viewModel.availableSources.value.size)
    assertNotEquals(
      "La riproduzione NON deve partire da sola",
      ScreenState.PLAYER,
      viewModel.screenState.value
    )
    assertNull("Nessuno stream avviato senza conferma utente", viewModel.playbackState.value.streamUrl)
    assertTrue(
      "Stato di risoluzione in attesa di scelta, non in riproduzione",
      viewModel.streamResolutionState.value is StreamResolutionState.Idle
    )
  }

  // ── 3. Conferma: parte ESATTAMENTE la sorgente scelta ───────────────────────────

  @Test
  fun selectSourceFromManualDialogStartsPlaybackOfChosenSource() = runTest(testDispatcher) {
    val chosenUrl = "https://example.com/chosen-source.1080p.mkv"
    val viewModel = viewModelWithSource(directSource(chosenUrl))

    viewModel.openManualSourceSelection(film())
    advanceUntilIdle()
    assertTrue(viewModel.showSourceDialog.value)

    val chosen = viewModel.availableSources.value.first()
    viewModel.selectSource(chosen)
    advanceUntilIdle()

    assertFalse("Il dialog si chiude dopo la scelta", viewModel.showSourceDialog.value)
    assertEquals(ScreenState.PLAYER, viewModel.screenState.value)
    assertEquals(
      "Il flusso avviato è la sorgente selezionata dall'utente",
      chosenUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Success)
  }

  // ── 4. Annullamento: chiusura pulita senza riproduzione ─────────────────────────

  @Test
  fun dismissSourceDialogFromManualSelectionClosesWithoutPlayback() = runTest(testDispatcher) {
    val viewModel = viewModelWithSource(directSource())

    viewModel.openManualSourceSelection(film())
    advanceUntilIdle()
    assertTrue(viewModel.showSourceDialog.value)

    viewModel.dismissSourceDialog()
    advanceUntilIdle()

    assertFalse(viewModel.showSourceDialog.value)
    assertNull(viewModel.sourceDialogMedia.value)
    assertNotEquals(ScreenState.PLAYER, viewModel.screenState.value)
    assertNull("Nessuna riproduzione dopo l'annullamento", viewModel.playbackState.value.streamUrl)
  }

  // ── 5. Segnale di ritorno al dettaglio: il focus torna su «Riproduci» ────────────

  /**
   * Regressione del focus al ritorno dal selettore: quando il selettore si chiude
   * (Back o «Annulla», entrambi via [StreamNovaViewModel.dismissSourceDialog]) il token
   * [StreamNovaViewModel.sourceDialogDismissedToken] DEVE avanzare, perché la scheda di
   * dettaglio resta composta sotto l'overlay e usa quel segnale per riprendere il focus
   * sul pulsante «Riproduci» (invece di lasciarlo alla sinossi).
   */
  @Test
  fun dismissSourceDialogAdvancesDismissedTokenForDetailFocusRestore() = runTest(testDispatcher) {
    val viewModel = viewModelWithSource(directSource())
    assertEquals("Nessuna chiusura prima di aprire il selettore", 0, viewModel.sourceDialogDismissedToken.value)

    viewModel.openManualSourceSelection(film())
    advanceUntilIdle()
    assertTrue(viewModel.showSourceDialog.value)
    val tokenBeforeDismiss = viewModel.sourceDialogDismissedToken.value

    viewModel.dismissSourceDialog()
    advanceUntilIdle()

    assertFalse(viewModel.showSourceDialog.value)
    assertEquals(
      "La chiusura del selettore deve segnalare al dettaglio il ritorno del focus su Riproduci",
      tokenBeforeDismiss + 1,
      viewModel.sourceDialogDismissedToken.value
    )
  }

  /**
   * Solo la CHIUSURA senza riproduzione è un ritorno al dettaglio: la conferma di una
   * sorgente ([StreamNovaViewModel.selectSource]) apre il player e la chiusura del player
   * ([StreamNovaViewModel.closePlayer]) ha i suoi flussi di ripristino. Nessuno dei due
   * deve far avanzare il token, così la scheda non strappa il focus a player e flussi
   * già esistenti.
   */
  @Test
  fun selectSourceAndClosePlayerDoNotAdvanceDismissedToken() = runTest(testDispatcher) {
    val viewModel = viewModelWithSource(directSource())

    viewModel.openManualSourceSelection(film())
    advanceUntilIdle()
    assertTrue(viewModel.showSourceDialog.value)
    val tokenBeforeSelection = viewModel.sourceDialogDismissedToken.value

    viewModel.selectSource(viewModel.availableSources.value.first())
    advanceUntilIdle()
    assertEquals(ScreenState.PLAYER, viewModel.screenState.value)
    assertEquals(
      "La scelta della sorgente apre il player: il token di chiusura non avanza",
      tokenBeforeSelection,
      viewModel.sourceDialogDismissedToken.value
    )

    viewModel.closePlayer()
    advanceUntilIdle()
    assertNotEquals(ScreenState.PLAYER, viewModel.screenState.value)
    assertEquals(
      "La chiusura del player non è una chiusura del selettore: il token non avanza",
      tokenBeforeSelection,
      viewModel.sourceDialogDismissedToken.value
    )
  }

  /**
   * Chiusure ripetute: ogni annullamento è un ritorno al dettaglio e deve avanzare il
   * token di uno (nessun consumo doppio, nessuna chiusura "muta").
   */
  @Test
  fun repeatedDismissalsAdvanceTheTokenEachTime() = runTest(testDispatcher) {
    val viewModel = viewModelWithSource(directSource())

    repeat(3) {
      viewModel.openManualSourceSelection(film())
      advanceUntilIdle()
      assertTrue(viewModel.showSourceDialog.value)
      val before = viewModel.sourceDialogDismissedToken.value

      viewModel.dismissSourceDialog()
      advanceUntilIdle()

      assertEquals(before + 1, viewModel.sourceDialogDismissedToken.value)
    }
  }
}
