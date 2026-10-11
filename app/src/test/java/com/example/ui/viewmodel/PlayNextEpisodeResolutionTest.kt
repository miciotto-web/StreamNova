package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamResult
import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxStreamProvider
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayNextEpisodeResolutionTest {

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

  @Test
  fun testPlayNextEpisodeResolvesBestWorkingStreamAndInheritsImdbId() = runTest(testDispatcher) {
    var capturedImdbId: String? = null
    var capturedSeason: Int? = null
    var capturedEpisode: Int? = null

    val streamEp2 = StreamSource(
      streamUrl = "https://torbox.app/dl/s1e2_1080p.mkv",
      quality = "1080p",
      serverName = "TorBox 1080p S1E2",
      releaseTitle = "Series.S01E02.1080p.mkv"
    )

    val fakeProvider = object : TorBoxStreamProvider() {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        imdbId: String?
      ): List<StreamSource> {
        capturedImdbId = imdbId
        capturedSeason = season
        capturedEpisode = episode
        return listOf(streamEp2)
      }
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")
    AppSettingsRepository.setTorBoxInstantDebridEnabled(true)
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testScheduler))
    viewModel.streamManager = StreamManager(providers = listOf(fakeProvider))
    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?
      ) = emptyList<com.example.domain.model.Subtitle>()
    }

    val series = MediaItem(
      id = "tt0944947",
      imdbId = "tt0944947",
      title = "Game of Thrones",
      synopsis = "Series synopsis",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 1399
    )

    val currentEp = Episode(
      id = "ep_1",
      seasonNumber = 1,
      episodeNumber = 1,
      title = "Winter Is Coming",
      synopsis = "",
      durationMinutes = 60,
      videoUrl = "",
      seriesImdbId = "tt0944947"
    )

    val nextEp = Episode(
      id = "ep_2",
      seasonNumber = 1,
      episodeNumber = 2,
      title = "The Kingsroad",
      synopsis = "",
      durationMinutes = 60,
      videoUrl = ""
      // seriesImdbId omitted to test inheritance
    )

    // Open first episode in player
    viewModel.openPlayer(series, currentEp)
    advanceUntilIdle()

    // Act: play next episode
    viewModel.playNextEpisode(nextEp)
    advanceUntilIdle()

    // Assert: IMDb ID inherited and passed to resolver
    assertEquals("tt0944947", capturedImdbId)
    assertEquals(1, capturedSeason)
    assertEquals(2, capturedEpisode)

    // Assert: stream opened automatically with the chosen stream
    val state = viewModel.playbackState.value
    assertEquals("https://torbox.app/dl/s1e2_1080p.mkv", state.streamUrl)
    assertEquals(2, state.currentEpisode?.episodeNumber)
    assertEquals("tt0944947", state.currentEpisode?.seriesImdbId)
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Success)
  }

  @Test
  fun testPlayNextEpisodeFallsBackToSourceDialogWhenAutoPickFails() = runTest(testDispatcher) {
    val candidateWithNoDirectUrl = StreamSource(
      streamUrl = null,
      infoHash = "unresolvable_hash",
      quality = "1080p",
      serverName = "TorBox Candidate",
      releaseTitle = "Series.S01E02.1080p.mkv"
    )

    val fakeProvider = object : TorBoxStreamProvider() {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        imdbId: String?
      ): List<StreamSource> {
        return listOf(candidateWithNoDirectUrl)
      }
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testScheduler))
    viewModel.streamManager = StreamManager(providers = listOf(fakeProvider))
    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?
      ) = emptyList<com.example.domain.model.Subtitle>()
    }

    val series = MediaItem(
      id = "tt0944947",
      imdbId = "tt0944947",
      title = "Game of Thrones",
      synopsis = "Series synopsis",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 1399
    )

    val currentEp = Episode(
      id = "ep_1",
      seasonNumber = 1,
      episodeNumber = 1,
      title = "Winter Is Coming",
      synopsis = "",
      durationMinutes = 60,
      videoUrl = "",
      seriesImdbId = "tt0944947"
    )

    val nextEp = Episode(
      id = "ep_2",
      seasonNumber = 1,
      episodeNumber = 2,
      title = "The Kingsroad",
      synopsis = "",
      durationMinutes = 60,
      videoUrl = ""
    )

    viewModel.openPlayer(series, currentEp)
    advanceUntilIdle()

    // Act: play next episode
    viewModel.playNextEpisode(nextEp)
    advanceUntilIdle()

    // Assert: instead of failing with black screen error, opens source selection dialog
    assertTrue("showSourceDialog should be opened as fallback", viewModel.showSourceDialog.value)
    assertEquals(1, viewModel.availableSources.value.size)
  }

  // ── Helpers condivisi ─────────────────────────────────────────────

  private fun seriesFixture() = MediaItem(
    id = "tt0944947",
    imdbId = "tt0944947",
    title = "Game of Thrones",
    synopsis = "Series synopsis",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 1399
  )

  private fun currentEpisodeFixture() = Episode(
    id = "ep_1",
    seasonNumber = 1,
    episodeNumber = 1,
    title = "Winter Is Coming",
    synopsis = "",
    durationMinutes = 60,
    videoUrl = "",
    seriesImdbId = "tt0944947"
  )

  private fun nextEpisodeFixture() = Episode(
    id = "ep_2",
    seasonNumber = 1,
    episodeNumber = 2,
    title = "The Kingsroad",
    synopsis = "",
    durationMinutes = 60,
    videoUrl = ""
  )

  private fun createViewModel(
    provider: StreamProvider
  ): StreamNovaViewModel {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    viewModel.streamManager = StreamManager(providers = listOf(provider))
    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?
      ) = emptyList<com.example.domain.model.Subtitle>()
    }
    return viewModel
  }

  // ── Auto-pick HTTP diretta (senza Debrid) ─────────────────────────

  @Test
  fun testPlayNextEpisodeAutoPicksDirectHttpStreamWithoutDebrid() = runTest(testDispatcher) {
    val httpStream = StreamSource(
      streamUrl = "https://cdn.vidsrc.example/hls/s1e2/index.m3u8",
      quality = "1080p",
      serverName = "VixSrc 1080p S1E2",
      releaseTitle = "Series.S01E02.1080p.m3u8"
    )

    val fakeProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = listOf(httpStream)
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = createViewModel(fakeProvider)
    viewModel.openPlayer(seriesFixture(), currentEpisodeFixture())
    advanceUntilIdle()

    viewModel.playNextEpisode(nextEpisodeFixture())
    advanceUntilIdle()

    // Caso B: il primo link HTTP diretto viene selezionato e mandato al player,
    // senza passare dal dialog di selezione e senza errori Debrid.
    val state = viewModel.playbackState.value
    assertEquals("https://cdn.vidsrc.example/hls/s1e2/index.m3u8", state.streamUrl)
    assertEquals(2, state.currentEpisode?.episodeNumber)
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Success)
    assertFalse("No manual dialog when an HTTP stream is auto-picked", viewModel.showSourceDialog.value)
  }

  @Test
  fun testPlayNextEpisodeHttpAutoPickSkipsRawTorrentAndPicksFirstPlayable() = runTest(testDispatcher) {
    val rawTorrent = StreamSource(
      streamUrl = null,
      infoHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      quality = "2160p",
      serverName = "Raw Torrent 4K",
      releaseTitle = "Series.S01E02.2160p.mkv"
    )
    val magnet = StreamSource(
      streamUrl = "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      quality = "1080p",
      serverName = "Magnet 1080p",
      releaseTitle = "Series.S01E02.1080p.mkv"
    )
    val directHttp = StreamSource(
      streamUrl = "https://cdn.embed.example/s1e2/playlist.m3u8",
      quality = "720p",
      serverName = "Direct HTTP 720p",
      releaseTitle = "Series.S01E02.720p.m3u8"
    )

    val fakeProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = listOf(rawTorrent, magnet, directHttp)
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = createViewModel(fakeProvider)
    viewModel.openPlayer(seriesFixture(), currentEpisodeFixture())
    advanceUntilIdle()

    viewModel.playNextEpisode(nextEpisodeFixture())
    advanceUntilIdle()

    // Caso B: torrent grezzi/magnet NON sono riproducibili: si salta al primo HTTP diretto.
    assertEquals("https://cdn.embed.example/s1e2/playlist.m3u8", viewModel.playbackState.value.streamUrl)
    assertFalse("No manual dialog when a playable HTTP stream exists", viewModel.showSourceDialog.value)
  }

  // ── Fallback Caso C: nessuno stream riproducibile automaticamente ─

  @Test
  fun testPlayNextEpisodeHttpFallsBackToDialogWhenNoPlayableStream() = runTest(testDispatcher) {
    val onlyNonPlayable = listOf(
      StreamSource(
        streamUrl = null,
        infoHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        quality = "1080p",
        serverName = "Raw Torrent 1080p",
        releaseTitle = "Series.S01E02.1080p.mkv"
      ),
      StreamSource(
        streamUrl = "magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        quality = "720p",
        serverName = "Magnet 720p",
        releaseTitle = "Series.S01E02.720p.mkv"
      )
    )

    val fakeProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = onlyNonPlayable
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = createViewModel(fakeProvider)
    viewModel.openPlayer(seriesFixture(), currentEpisodeFixture())
    advanceUntilIdle()

    viewModel.playNextEpisode(nextEpisodeFixture())
    advanceUntilIdle()

    // Caso C: nessuno stream riproducibile -> dialog manuale, MAI errore/schermata nera.
    assertTrue("Manual source dialog must open as fallback", viewModel.showSourceDialog.value)
    assertEquals(2, viewModel.availableSources.value.size)
    assertEquals(StreamResolutionState.Idle, viewModel.streamResolutionState.value)
    assertEquals(StreamResult.Idle, viewModel.streamResult.value)
  }

  @Test
  fun testPlayNextEpisodeNeverFailsPrematurelyOnEmptySourceList() = runTest(testDispatcher) {
    val emptyProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = emptyList()
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = createViewModel(emptyProvider)
    viewModel.openPlayer(seriesFixture(), currentEpisodeFixture())
    advanceUntilIdle()

    viewModel.playNextEpisode(nextEpisodeFixture())
    advanceUntilIdle()

    // Req 2: un risultato vuoto (primo tick o flow completo) NON puo' trasformarsi
    // in un fallimento prematuro: si resta sul dialog di selezione, senza errori.
    assertTrue("Fallback dialog must be active also with zero sources", viewModel.showSourceDialog.value)
    assertEquals(emptyList<StreamSource>(), viewModel.availableSources.value)
    assertFalse(
      "Empty resolution must never become an Error state",
      viewModel.streamResolutionState.value is StreamResolutionState.Error
    )
    assertFalse(
      "Switching flag must clear when falling back to the manual dialog",
      viewModel.isSwitchingEpisode.value
    )
    assertEquals(StreamResult.Idle, viewModel.streamResult.value)
  }

  // ── Transizione: stato "caricamento episodio successivo" ──────────

  @Test
  fun testPlayNextEpisodeExposesSwitchingStateWhileResolving() = runTest(testDispatcher) {
    val httpStream = StreamSource(
      streamUrl = "https://cdn.vidsrc.example/hls/s1e2/index.m3u8",
      quality = "1080p",
      serverName = "VixSrc 1080p S1E2",
      releaseTitle = "Series.S01E02.1080p.m3u8"
    )
    val fakeProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = listOf(httpStream)
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = createViewModel(fakeProvider)
    viewModel.openPlayer(seriesFixture(), currentEpisodeFixture())
    advanceUntilIdle()
    assertFalse("Flag idle before switching", viewModel.isSwitchingEpisode.value)

    viewModel.playNextEpisode(nextEpisodeFixture())

    // Prima che lo scheduler avanzi: risoluzione in corso, stato di switching attivo
    // (PlayerScreen non deve mostrare l'errore "Stream non disponibile" in questa fase).
    assertTrue("Switching flag must be set while resolving the next episode", viewModel.isSwitchingEpisode.value)
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Loading)

    advanceUntilIdle()

    assertFalse("Switching flag must clear once the new stream is opened", viewModel.isSwitchingEpisode.value)
    assertEquals("https://cdn.vidsrc.example/hls/s1e2/index.m3u8", viewModel.playbackState.value.streamUrl)
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Success)
  }

  @Test
  fun testResetStreamStateClearsSwitchingFlag() = runTest(testDispatcher) {
    val httpStream = StreamSource(
      streamUrl = "https://cdn.vidsrc.example/hls/s1e2/index.m3u8",
      quality = "1080p",
      serverName = "VixSrc",
      releaseTitle = "Series.S01E02.1080p.m3u8"
    )
    val fakeProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = listOf(httpStream)
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = createViewModel(fakeProvider)
    viewModel.openPlayer(seriesFixture(), currentEpisodeFixture())
    advanceUntilIdle()

    viewModel.playNextEpisode(nextEpisodeFixture())
    assertTrue(viewModel.isSwitchingEpisode.value)

    // BACK durante la ricerca: annulla la transizione e azzera lo stato di switching.
    viewModel.resetStreamState()
    assertFalse("resetStreamState must clear the switching flag", viewModel.isSwitchingEpisode.value)
    assertEquals(StreamResolutionState.Idle, viewModel.streamResolutionState.value)
  }

  // ── Bingewatching: stessa fonte (same-source affinity) ─────────────

  @Test
  fun testNextEpisodeSameTorrentFastPathOpensDirectlyWithoutProviders() = runTest(testDispatcher) {
    val ep1Torrent = StreamSource(
      streamUrl = "https://torbox.app/dl/s1e1-from-pack.mkv",
      quality = "1080p",
      serverName = "Torrentio 1080p",
      addonName = "Torrentio",
      releaseTitle = "Series.S01.1080p.mkv",
      infoHash = "cccccccccccccccccccccccccccccccccccccccc",
      fileIdx = 0,
      isCached = true
    )
    val fakeProvider = object : TorBoxStreamProvider() {
      var invoked = false
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        imdbId: String?
      ): List<StreamSource> {
        invoked = true
        return listOf(
          StreamSource(
            streamUrl = "https://torbox.app/dl/s1e2-should-not-be-used.mkv",
            quality = "1080p",
            serverName = "Fake TorBox",
            releaseTitle = "Series.S01E02.1080p.mkv"
          )
        )
      }
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = createViewModel(fakeProvider)
    // Fast-path iniettabile: il file del nuovo episodio esiste nello stesso torrent.
    viewModel.seasonPackEpisodeResolver = { pinned, _, _ ->
      pinned.copy(streamUrl = "https://torbox.app/dl/s1e2-from-pack.mkv")
    }
    viewModel.openPlayer(seriesFixture(), currentEpisodeFixture(), ep1Torrent)
    advanceUntilIdle()

    viewModel.playNextEpisode(nextEpisodeFixture())
    advanceUntilIdle()

    assertFalse(
      "Providers/addons MUST NOT be queried when the same torrent has the next episode",
      fakeProvider.invoked
    )
    assertEquals("https://torbox.app/dl/s1e2-from-pack.mkv", viewModel.playbackState.value.streamUrl)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Success)
    assertEquals("cccccccccccccccccccccccccccccccccccccccc", viewModel.playbackState.value.currentSource?.infoHash)
  }

  @Test
  fun testNextEpisodeSameTorrentFallsBackToProvidersWhenFileUnavailable() = runTest(testDispatcher) {
    val ep1Torrent = StreamSource(
      streamUrl = "https://torbox.app/dl/s1e1.mkv",
      quality = "1080p",
      serverName = "Torrentio 1080p",
      addonName = "Torrentio",
      releaseTitle = "Series.S01.1080p.mkv",
      infoHash = "dddddddddddddddddddddddddddddddddddddddd",
      fileIdx = 0,
      isCached = true
    )
    val fakeProvider = object : TorBoxStreamProvider() {
      var invoked = false
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        imdbId: String?
      ): List<StreamSource> {
        invoked = true
        return listOf(
          StreamSource(
            streamUrl = "https://torbox.app/dl/s1e2-fallback.mkv",
            quality = "1080p",
            serverName = "Torrentio 1080p",
            addonName = "Torrentio",
            releaseTitle = "Series.S01E02.1080p.mkv",
            infoHash = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            isCached = true
          )
        )
      }
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = createViewModel(fakeProvider)
    // Il torrent corrente NON contiene il file del nuovo episodio: si interrogano i provider.
    viewModel.seasonPackEpisodeResolver = { _, _, _ -> null }
    viewModel.openPlayer(seriesFixture(), currentEpisodeFixture(), ep1Torrent)
    advanceUntilIdle()

    viewModel.playNextEpisode(nextEpisodeFixture())
    advanceUntilIdle()

    assertTrue("Providers must be queried when the season pack has no matching file", fakeProvider.invoked)
    assertEquals("https://torbox.app/dl/s1e2-fallback.mkv", viewModel.playbackState.value.streamUrl)
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Success)
  }

  @Test
  fun testNextEpisodeHttpPrefersSameProviderStream() = runTest(testDispatcher) {
    val ep1Source = StreamSource(
      streamUrl = "https://vix.example/hls/s1e1.m3u8",
      quality = "720p",
      serverName = "VixSrc Alpha",
      releaseTitle = "Series.S01E01.720p.m3u8"
    )
    val sameProviderEp2 = StreamSource(
      streamUrl = "https://vix.example/hls/s1e2.m3u8",
      quality = "720p",
      serverName = "VixSrc Alpha",
      releaseTitle = "Series.S01E02.720p.m3u8"
    )
    val otherProviderEp2 = StreamSource(
      streamUrl = "https://other.example/hls/s1e2-4k.m3u8",
      quality = "4K",
      serverName = "Other Host",
      releaseTitle = "Series.S01E02.2160p.m3u8"
    )

    val providerA = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = listOf(sameProviderEp2)
    }
    val providerB = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = listOf(otherProviderEp2)
    }

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)

    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    viewModel.streamManager = StreamManager(providers = listOf(providerA, providerB))
    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?
      ) = emptyList<com.example.domain.model.Subtitle>()
    }
    viewModel.openPlayer(seriesFixture(), currentEpisodeFixture(), ep1Source)
    advanceUntilIdle()

    viewModel.playNextEpisode(nextEpisodeFixture())
    advanceUntilIdle()

    // L'affinita' di provider vince sulla qualita' grezza: stessa fonte di S01E01.
    assertEquals("https://vix.example/hls/s1e2.m3u8", viewModel.playbackState.value.streamUrl)
    assertFalse("No manual dialog when the affine HTTP stream is auto-picked", viewModel.showSourceDialog.value)
  }

  @Test
  fun rankBySameSourceAffinityPrioritizesSameAddonOverRawQuality() {
    val pinned = StreamSource(
      streamUrl = "https://cdn/torrentio/s1e1",
      quality = "1080p",
      serverName = "Torrentio",
      addonName = "Torrentio",
      releaseTitle = "Show.S01E01.1080p.AMZN.mkv",
      infoHash = "h1",
      isCached = true
    )
    val sameAddonEp2 = StreamSource(
      streamUrl = null,
      quality = "1080p",
      serverName = "Torrentio",
      addonName = "Torrentio",
      releaseTitle = "Show.S01E02.1080p.AMZN.mkv",
      infoHash = "h2",
      isCached = true
    )
    val otherAddon4k = StreamSource(
      streamUrl = null,
      quality = "4K",
      serverName = "Comet",
      addonName = "Comet",
      releaseTitle = "Show.S01E02.2160p.AMZN.mkv",
      infoHash = "h3",
      isCached = true
    )

    val ranked = rankBySameSourceAffinity(listOf(otherAddon4k, sameAddonEp2), pinned)
    assertEquals(listOf(sameAddonEp2, otherAddon4k), ranked)
  }

  @Test
  fun rankBySameSourceAffinityPrioritizesSameTorrent() {
    val pinned = StreamSource(
      streamUrl = "https://cdn/torrentio/s1e1",
      quality = "1080p",
      serverName = "Torrentio",
      addonName = "Torrentio",
      releaseTitle = "Show.S01E01.1080p.AMZN.mkv",
      infoHash = "pack-1",
      isCached = true
    )
    val sameTorrent = StreamSource(
      streamUrl = null,
      quality = "720p",
      serverName = "Comet",
      addonName = "Comet",
      releaseTitle = "Show.S01E02.720p.mkv",
      infoHash = "pack-1",
      isCached = true
    )
    val sameAddon = StreamSource(
      streamUrl = null,
      quality = "4K",
      serverName = "Torrentio",
      addonName = "Torrentio",
      releaseTitle = "Show.S01E02.2160p.AMZN.mkv",
      infoHash = "pack-2",
      isCached = true
    )
    val unrelated = StreamSource(
      streamUrl = null,
      quality = "1080p",
      serverName = "Comet",
      addonName = "Comet",
      releaseTitle = "Show.S01E02.1080p.mkv",
      infoHash = "pack-3",
      isCached = true
    )

    val ranked = rankBySameSourceAffinity(listOf(unrelated, sameAddon, sameTorrent), pinned)
    assertEquals(sameTorrent.infoHash, ranked.first().infoHash)
    assertEquals(sameAddon.infoHash, ranked[1].infoHash)
  }

  @Test
  fun tryResolveNextEpisodeFromSameTorrentUnlocksFileFromSameTorrent() = runTest(testDispatcher) {
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")
    AppSettingsRepository.setTorBoxInstantDebridEnabled(true)

    val pinned = StreamSource(
      streamUrl = "https://torbox.app/dl/s1e1.mkv",
      quality = "1080p",
      serverName = "Torrentio",
      addonName = "Torrentio",
      releaseTitle = "Series.S01.1080p.mkv",
      infoHash = "abc123abc123abc123abc123abc123abc123abc1",
      isCached = true
    )
    var requestedHash: String? = null
    val result = tryResolveNextEpisodeFromSameTorrent(
      pinned = pinned,
      season = 1,
      episode = 2,
      seasonPackUrlResolver = { hash, s, e ->
        requestedHash = hash
        assertEquals(1, s)
        assertEquals(2, e)
        "https://torbox.app/dl/s1e2-from-pack.mkv"
      }
    )

    assertNotNull(result)
    assertEquals("https://torbox.app/dl/s1e2-from-pack.mkv", result?.streamUrl)
    assertEquals("abc123abc123abc123abc123abc123abc123abc1", requestedHash)
    assertEquals(pinned.infoHash, result?.infoHash)
  }

  @Test
  fun tryResolveNextEpisodeFromSameTorrentSkipsWhenTorBoxNotConfigured() = runTest(testDispatcher) {
    AppSettingsRepository.setTorBoxApiKey(null)

    val pinned = StreamSource(
      streamUrl = "https://torbox.app/dl/s1e1.mkv",
      quality = "1080p",
      serverName = "Torrentio",
      releaseTitle = "Series.S01.1080p.mkv",
      infoHash = "abc123abc123abc123abc123abc123abc123abc1",
      isCached = true
    )
    var resolverCalled = false
    val result = tryResolveNextEpisodeFromSameTorrent(
      pinned = pinned,
      season = 1,
      episode = 2,
      seasonPackUrlResolver = { _, _, _ ->
        resolverCalled = true
        "https://torbox.app/dl/x.mkv"
      }
    )

    assertNull(result)
    assertFalse("Resolver must not be called without a configured Debrid account", resolverCalled)
  }
}
