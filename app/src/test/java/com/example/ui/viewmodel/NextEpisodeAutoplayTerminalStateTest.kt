package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxStreamProvider
import com.example.domain.model.Subtitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Autoplay del prossimo episodio in modalità Debrid: contratto TERMINALE del fallimento
 * della stessa fonte.
 *
 * Copre il loop segnalato ("la stessa sorgente non è disponibile → l'app continua ad
 * avanzare tra gli episodi e ripete la ricerca"):
 *  - CASO A: stessa fonte trovata → un solo avvio, provider mai interrogati;
 *  - CASO B: fonte assente/vuota/errore/timeout → autoplay fermo, nessuna sorgente
 *    diversa avviata, nessun avanzamento e nessun retry;
 *  - callback duplicate o tardive → un solo avvio, nessun salto di episodio;
 *  - selezione manuale dopo il fallimento → riproduzione ripristinata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NextEpisodeAutoplayTerminalStateTest {

  private val testDispatcher = StandardTestDispatcher()

  private companion object {
    const val SERIES_ID = "tt0944947"
    const val INFO_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val EP2_SAME_SOURCE_URL = "https://torbox-cdn.example/show/s1e2-from-pack.mkv"
    const val EP2_OTHER_SOURCE_URL = "https://cdn.example/show/s1e2-other.mkv"
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")
    AppSettingsRepository.setTorBoxInstantDebridEnabled(true)
    AppSettingsRepository.setAutoplayEnabled(true)
  }

  @After
  fun tearDown() {
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)
    Dispatchers.resetMain()
  }

  // ── Fixture ──────────────────────────────────────────────────────────────────

  private fun series() = MediaItem(
    id = SERIES_ID,
    imdbId = SERIES_ID,
    title = "Game of Thrones",
    synopsis = "Series synopsis",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 1399,
    episodes = listOf(episode(1), episode(2), episode(3))
  )

  private fun episode(number: Int) = Episode(
    id = "${SERIES_ID}_s1e$number",
    seasonNumber = 1,
    episodeNumber = number,
    title = "Episodio $number",
    synopsis = "",
    durationMinutes = 60,
    videoUrl = "",
    seriesImdbId = SERIES_ID
  )

  private fun pinnedSource() = StreamSource(
    streamUrl = "https://torbox-cdn.example/show/s1e1.mkv",
    quality = "1080p",
    serverName = "Torrentio 1080p",
    addonName = "Torrentio",
    releaseTitle = "Show.S01.1080p.mkv",
    infoHash = INFO_HASH,
    fileIdx = 0,
    isCached = true
  )

  private fun otherProviderSource() = StreamSource(
    streamUrl = EP2_OTHER_SOURCE_URL,
    quality = "1080p",
    serverName = "Altro Provider",
    releaseTitle = "Show.S01E02.1080p.mkv"
  )

  /** Provider Debrid finto: conta le interrogazioni (deve restare a 0 nel CASO B). */
  private class CountingTorBoxProvider(
    private val sources: List<StreamSource>
  ) : TorBoxStreamProvider() {
    var invocations = 0
      private set

    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?,
      imdbId: String?
    ): List<StreamSource> {
      invocations++
      return sources
    }
  }

  /** Provider HTTP finto: usato per verificare che le modalità non coinvolte non cambino. */
  private class CountingHttpProvider(
    private val sources: List<StreamSource>
  ) : StreamProvider {
    var invocations = 0
      private set

    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      invocations++
      return sources
    }
  }

  private fun createViewModel(
    provider: StreamProvider,
    resolver: suspend (StreamSource, Int, Int) -> StreamSource?
  ): StreamNovaViewModel {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    viewModel.streamManager = StreamManager(providers = listOf(provider))
    viewModel.openSubtitlesProvider = object : OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ) = emptyList<Subtitle>()
    }
    viewModel.seasonPackEpisodeResolver = resolver
    return viewModel
  }

  /** Avvia S1E1 con la fonte-torrent che l'autoplay tenterà di riusare. */
  private fun StreamNovaViewModel.startFirstEpisode() {
    openPlayer(series(), episode(1), pinnedSource())
  }

  // ── CASO A: la stessa sorgente è disponibile ────────────────────────────────

  @Test
  fun sameSourceAvailableStartsNextEpisodeExactlyOnce() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    var resolverCalls = 0
    val viewModel = createViewModel(provider) { pinned, season, episode ->
      resolverCalls++
      assertEquals(INFO_HASH, pinned.infoHash)
      assertEquals(1, season)
      assertEquals(2, episode)
      pinned.copy(streamUrl = EP2_SAME_SOURCE_URL)
    }
    viewModel.startFirstEpisode()
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()

    assertEquals("Una sola risoluzione della stessa fonte", 1, resolverCalls)
    assertEquals("Nessuna sorgente diversa interrogata", 0, provider.invocations)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertEquals(EP2_SAME_SOURCE_URL, viewModel.playbackState.value.streamUrl)
    assertEquals(NextEpisodeAutoplayState.STARTED, viewModel.nextEpisodeAutoplayState.value)
    assertFalse(viewModel.showSourceDialog.value)
  }

  // ── CASO B: la stessa sorgente NON è disponibile ────────────────────────────

  @Test
  fun sameSourceUnavailableStopsAutoplayWithoutStartingAnotherSource() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    var resolverCalls = 0
    val viewModel = createViewModel(provider) { _, _, _ ->
      resolverCalls++
      null
    }
    viewModel.startFirstEpisode()
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()

    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)
    assertEquals("Una sola ricerca della stessa fonte", 1, resolverCalls)
    assertEquals("Nessun provider interrogato: nessuna sorgente alternativa automatica", 0, provider.invocations)
    assertNull("Nessuno stream avviato", viewModel.playbackState.value.streamUrl)
    assertEquals("L'episodio non avanza ulteriormente", 2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertFalse("Transizione conclusa, niente spinner", viewModel.isSwitchingEpisode.value)
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Idle)
    assertTrue(viewModel.availableSources.value.isEmpty())
    assertFalse("Il dialog senza sorgenti non viene forzato", viewModel.showSourceDialog.value)
  }

  @Test
  fun failureDoesNotCauseAnyFurtherAutomaticAdvanceOrRetry() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    var resolverCalls = 0
    val viewModel = createViewModel(provider) { _, _, _ ->
      resolverCalls++
      null
    }
    viewModel.startFirstEpisode()
    advanceUntilIdle()
    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()
    assertEquals(1, resolverCalls)

    // Callback ripetute/tardive del countdown (o di un altro consumatore automatico):
    // il fallimento è terminale, non un nuovo punto di partenza.
    repeat(5) { viewModel.autoPlayNextEpisode(episode(3)) }
    advanceUntilIdle()

    assertEquals("Nessun nuovo episodio", 2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertEquals("Nessuna nuova ricerca della stessa fonte", 1, resolverCalls)
    assertEquals("Nessun provider interrogato", 0, provider.invocations)
    assertNull(viewModel.playbackState.value.streamUrl)
    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)
  }

  @Test
  fun blankUrlFromSameSourceIsTreatedAsUnavailable() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    val viewModel = createViewModel(provider) { pinned, _, _ ->
      // "Trovata" ma senza URL valido: non è una sorgente avviabile.
      pinned.copy(streamUrl = "   ")
    }
    viewModel.startFirstEpisode()
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()

    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)
    assertNull(viewModel.playbackState.value.streamUrl)
    assertEquals(0, provider.invocations)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertFalse(viewModel.isSwitchingEpisode.value)
  }

  @Test
  fun sameSourceErrorIsTreatedAsUnavailableWithoutCrashing() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    val viewModel = createViewModel(provider) { _, _, _ ->
      throw IllegalStateException("errore di rete simulato")
    }
    viewModel.startFirstEpisode()
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()

    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)
    assertNull(viewModel.playbackState.value.streamUrl)
    assertEquals(0, provider.invocations)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertFalse("Nessuno spinner bloccato dopo l'eccezione", viewModel.isSwitchingEpisode.value)
  }

  @Test
  fun sameSourceTimeoutIsTreatedAsUnavailable() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    var resolverCalls = 0
    val viewModel = createViewModel(provider) { _, _, _ ->
      resolverCalls++
      // Lo sblocco non risponde mai: il timeout rigido deve chiudere la ricerca.
      awaitCancellation()
    }
    viewModel.startFirstEpisode()
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()

    assertEquals(1, resolverCalls)
    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)
    assertNull(viewModel.playbackState.value.streamUrl)
    assertEquals(0, provider.invocations)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertFalse(viewModel.isSwitchingEpisode.value)
  }

  // ── Callback duplicate / asincrone ──────────────────────────────────────────

  @Test
  fun duplicateAutomaticAndManualRequestsDuringSearchDoNotStartTwiceOrSkipEpisode() =
    runTest(testDispatcher) {
      val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
      val gate = CompletableDeferred<StreamSource?>()
      var resolverCalls = 0
      val viewModel = createViewModel(provider) { _, _, _ ->
        resolverCalls++
        gate.await()
      }
      viewModel.startFirstEpisode()
      advanceUntilIdle()

      viewModel.autoPlayNextEpisode(episode(2))
      testScheduler.runCurrent()
      assertTrue(viewModel.isSwitchingEpisode.value)
      assertEquals(NextEpisodeAutoplayState.SEARCHING_SAME_SOURCE, viewModel.nextEpisodeAutoplayState.value)

      // Duplicati mentre la ricerca è in corso: ignorati, non riavviano il job
      // e non aprono S1E3 al posto di S1E2.
      viewModel.autoPlayNextEpisode(episode(3))
      viewModel.playNextEpisode(episode(3))
      testScheduler.runCurrent()
      assertEquals(1, resolverCalls)
      assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)

      gate.complete(pinnedSource().copy(streamUrl = EP2_SAME_SOURCE_URL))
      advanceUntilIdle()

      assertEquals(1, resolverCalls)
      assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
      assertEquals(EP2_SAME_SOURCE_URL, viewModel.playbackState.value.streamUrl)
      assertEquals(0, provider.invocations)
    }

  @Test
  fun interruptedSearchBecomesTerminalAndCannotBeRestartedAutomatically() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    val gate = CompletableDeferred<StreamSource?>()
    val viewModel = createViewModel(provider) { _, _, _ -> gate.await() }
    viewModel.startFirstEpisode()
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2))
    testScheduler.runCurrent()
    assertEquals(NextEpisodeAutoplayState.SEARCHING_SAME_SOURCE, viewModel.nextEpisodeAutoplayState.value)

    // BACK/cancel durante il caricamento: la ricerca muore senza sorgente.
    viewModel.resetStreamState()
    advanceUntilIdle()

    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)
    assertFalse(viewModel.isSwitchingEpisode.value)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)

    viewModel.autoPlayNextEpisode(episode(3))
    advanceUntilIdle()
    assertEquals("Nessun avanzamento dopo l'interruzione", 2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertEquals(0, provider.invocations)
  }

  // ── Ripresa manuale ────────────────────────────────────────────────────────

  @Test
  fun manualSourceSelectionAfterFailureResumesPlayback() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    val viewModel = createViewModel(provider) { _, _, _ -> null }
    viewModel.startFirstEpisode()
    advanceUntilIdle()
    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()
    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)

    // L'utente apre la selezione manuale per l'episodio per cui l'autoplay è fallito.
    viewModel.openManualSourceSelection(series(), episode(2))
    advanceUntilIdle()

    assertEquals(NextEpisodeAutoplayState.MANUAL_SELECTION_REQUIRED, viewModel.nextEpisodeAutoplayState.value)
    assertTrue("Il selettore sorgenti deve aprirsi", viewModel.showSourceDialog.value)
    assertEquals("Sorgenti cercate esclusivamente su richiesta manuale", 1, provider.invocations)
    assertNull("Nessuna riproduzione prima della conferma", viewModel.playbackState.value.streamUrl)

    viewModel.selectSource(viewModel.availableSources.value.first())
    advanceUntilIdle()

    assertEquals(NextEpisodeAutoplayState.IDLE, viewModel.nextEpisodeAutoplayState.value)
    assertFalse(viewModel.showSourceDialog.value)
    assertEquals(EP2_OTHER_SOURCE_URL, viewModel.playbackState.value.streamUrl)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Success)
  }

  @Test
  fun manualSelectionWithoutSourcesKeepsTheTerminalState() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(emptyList())
    val viewModel = createViewModel(provider) { _, _, _ -> null }
    viewModel.startFirstEpisode()
    advanceUntilIdle()
    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()
    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)

    viewModel.openManualSourceSelection(series(), episode(2))
    advanceUntilIdle()

    // La ricerca manuale non ha trovato nulla: il messaggio/terminale resta attivo,
    // non si torna a uno stato che possa riarmare il countdown.
    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)
    assertFalse(viewModel.showSourceDialog.value)
    assertNull(viewModel.playbackState.value.streamUrl)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
  }

  @Test
  fun closingTheSourceSelectorWithoutChoiceKeepsAutoplayStopped() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    val viewModel = createViewModel(provider) { _, _, _ -> null }
    viewModel.startFirstEpisode()
    advanceUntilIdle()
    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()
    viewModel.openManualSourceSelection(series(), episode(2))
    advanceUntilIdle()
    assertEquals(NextEpisodeAutoplayState.MANUAL_SELECTION_REQUIRED, viewModel.nextEpisodeAutoplayState.value)

    viewModel.dismissSourceDialog()
    advanceUntilIdle()

    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)
    viewModel.autoPlayNextEpisode(episode(3))
    advanceUntilIdle()
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
  }

  // ── Comportamento normale preservato ───────────────────────────────────────

  @Test
  fun httpModeTransitionStillAutoPicksFromProviders() = runTest(testDispatcher) {
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    val provider = CountingHttpProvider(listOf(otherProviderSource()))
    val viewModel = createViewModel(provider) { _, _, _ ->
      error("Il fast-path Debrid non deve essere usato in modalità HTTP")
    }
    viewModel.openPlayer(series(), episode(1), pinnedSource())
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()

    assertEquals(1, provider.invocations)
    assertEquals(EP2_OTHER_SOURCE_URL, viewModel.playbackState.value.streamUrl)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertEquals(
      "La modalità HTTP mantiene il comportamento esistente",
      NextEpisodeAutoplayState.IDLE,
      viewModel.nextEpisodeAutoplayState.value
    )
  }

  @Test
  fun closingPlayerClearsTheTerminalAutoplayState() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(otherProviderSource()))
    val viewModel = createViewModel(provider) { _, _, _ -> null }
    viewModel.startFirstEpisode()
    advanceUntilIdle()
    viewModel.autoPlayNextEpisode(episode(2))
    advanceUntilIdle()
    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)

    viewModel.closePlayer()
    advanceUntilIdle()

    assertEquals(NextEpisodeAutoplayState.IDLE, viewModel.nextEpisodeAutoplayState.value)
  }
}
