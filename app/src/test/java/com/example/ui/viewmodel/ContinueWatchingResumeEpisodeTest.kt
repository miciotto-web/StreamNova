package com.example.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.StreamNovaDatabase
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.repository.LastWorkingStreamRepository
import com.example.data.repository.MediaRepository
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxStreamProvider
import com.example.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * «Continua a guardare»: ripresa dell'episodio CORRETTO e della sorgente Debrid.
 *
 * Copre i difetti reali:
 *  - la serie parte da S1E1 anche se l'ultima visione è S5E2 (lista episodi troncata);
 *  - la sorgente Debrid salvata non viene recuperata / un link scaduto non ha fallback;
 *  - una voce di cache di un ALTRO episodio non deve mai essere riprodotta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ContinueWatchingResumeEpisodeTest {

  private val testDispatcher = StandardTestDispatcher()
  private lateinit var database: StreamNovaDatabase

  private val savedPositionMs = 20 * 60 * 1000L
  private val episodeDurationMs = 60 * 60 * 1000L

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setTorBoxInstantDebridEnabled(true)
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setAutoplayEnabled(true)
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()

    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
    LastWorkingStreamRepository.setInstanceForTesting(database)
    // Isola il repository dal caricamento cache dell'Application: un loadFromCache
    // concorrente può svuotare la mappa dei progressi appena scritta dal test.
    MediaRepository.setDatabaseForTesting(database)
  }

  @After
  fun tearDown() {
    MediaRepository.setDatabaseForTesting(null)
    LastWorkingStreamRepository.setInstanceForTesting(null)
    database.close()
    AppSettingsRepository.setTorBoxApiKey(null)
    Dispatchers.resetMain()
  }

  // ── 1. Episodio corretto anche con lista locale troncata ────────────────

  @Test
  fun continueWatchingResumesS5E2EvenWhenLocalEpisodeListStopsAtSeason1() = runTest(testDispatcher) {
    val mediaId = "tt9900001"
    MediaRepository.updateEpisodeProgress(mediaId, 5, 2, savedPositionMs, episodeDurationMs)

    var capturedSeason: Int? = null
    var capturedEpisode: Int? = null
    val httpProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> {
        capturedSeason = season
        capturedEpisode = episode
        return listOf(
          StreamSource(
            streamUrl = "https://cdn.example/hls/s5e2/index.m3u8",
            quality = "1080p",
            serverName = "VixSrc"
          )
        )
      }
    }

    val viewModel = createViewModel(httpProvider)
    viewModel.openStreamForMedia(series(mediaId))
    advanceUntilIdle()

    val state = viewModel.playbackState.value
    assertEquals("la ricerca deve riguardare S5E2", 5, capturedSeason)
    assertEquals(2, capturedEpisode)
    assertEquals(5, state.currentEpisode?.seasonNumber)
    assertEquals(2, state.currentEpisode?.episodeNumber)
    assertEquals("https://cdn.example/hls/s5e2/index.m3u8", state.streamUrl)
    assertEquals("la posizione salvata deve essere mantenuta", savedPositionMs, state.currentPositionMs)

    // Il progresso salvato in chiusura appartiene a S5E2, mai alla prima puntata.
    viewModel.saveCurrentPlaybackProgress(25 * 60 * 1000L)
    advanceUntilIdle()
    assertEquals(25 * 60 * 1000L, MediaRepository.getEpisodeProgress(mediaId, 5, 2))
    assertEquals(0L, MediaRepository.getEpisodeProgress(mediaId, 1, 1))
  }

  // ── 2. Ripresa diretta della sorgente Debrid salvata ───────────────────

  @Test
  fun continueWatchingRefreshesTheSavedDebridLinkAndSkipsProviderSearch() = runTest(testDispatcher) {
    val mediaId = "tt9900002"
    val infoHash = "1234abcd1234abcd1234abcd1234abcd1234abcd"
    MediaRepository.updateEpisodeProgress(mediaId, 5, 2, savedPositionMs, episodeDurationMs)
    LastWorkingStreamRepository.saveStream(
      mediaId = mediaId,
      seasonNumber = 5,
      episodeNumber = 2,
      streamUrl = "https://torbox-cdn.example/show/s5e2-expired.mkv",
      streamTitle = "Show.S05.1080p.mkv",
      streamQuality = "1080p",
      serverName = "Torrentio",
      headers = emptyMap(),
      isProgressive = true,
      streamMode = StreamingEngineMode.DEBRID_TORBOX.name,
      infoHash = infoHash,
      fileIdx = 4,
      addonName = "Torrentio"
    )

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")

    val provider = object : TorBoxStreamProvider() {
      var invocations = 0
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
        return emptyList()
      }
    }

    val viewModel = createViewModel(provider)
    var unlockSeason: Int? = null
    var unlockEpisode: Int? = null
    var unlockedHash: String? = null
    viewModel.reusedStreamUnlocker = { hash, fileIdx, season, episode ->
      unlockedHash = hash
      unlockSeason = season
      unlockEpisode = episode
      assertEquals("fileIdx della sorgente salvata", 4, fileIdx)
      "https://torbox-cdn.example/show/s5e2-fresh.mkv"
    }

    viewModel.openStreamForMedia(series(mediaId))
    advanceUntilIdle()

    val state = viewModel.playbackState.value
    assertEquals(infoHash, unlockedHash)
    assertEquals(5, unlockSeason)
    assertEquals(2, unlockEpisode)
    assertEquals("link rigenerato usato direttamente", "https://torbox-cdn.example/show/s5e2-fresh.mkv", state.streamUrl)
    assertTrue("la riproduzione arriva dalla cache, non dalla ricerca", state.isReusedStream)
    assertEquals("nessuna interrogazione dei provider", 0, provider.invocations)
    assertEquals(5, state.currentEpisode?.seasonNumber)
    assertEquals(2, state.currentEpisode?.episodeNumber)
    assertEquals(savedPositionMs, state.currentPositionMs)
  }

  // ── 3. Un episodio diverso non riusa la cache ──────────────────────────

  @Test
  fun cachedStreamOfADifferentEpisodeIsNeverPlayedForTheRequestedOne() = runTest(testDispatcher) {
    val mediaId = "tt9900003"
    MediaRepository.updateEpisodeProgress(mediaId, 5, 2, savedPositionMs, episodeDurationMs)
    // In cache esiste SOLO S5E1: non è valida per la ripresa di S5E2.
    LastWorkingStreamRepository.saveStream(
      mediaId = mediaId,
      seasonNumber = 5,
      episodeNumber = 1,
      streamUrl = "https://torbox-cdn.example/show/s5e1.mkv",
      streamTitle = "Show.S05.1080p.mkv",
      streamQuality = "1080p",
      serverName = "Torrentio",
      headers = emptyMap(),
      isProgressive = true,
      streamMode = StreamingEngineMode.DEBRID_TORBOX.name,
      infoHash = "aaaabbbbccccddddeeeeffff0000111122223333",
      fileIdx = 0,
      addonName = "Torrentio"
    )

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")

    val provider = object : TorBoxStreamProvider() {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        imdbId: String?
      ): List<StreamSource> = listOf(
        StreamSource(
          streamUrl = "https://torbox-cdn.example/show/s5e2-from-search.mkv",
          quality = "1080p",
          serverName = "Torrentio",
          releaseTitle = "Show.S05E02.1080p.mkv"
        )
      )
    }

    val viewModel = createViewModel(provider)
    var unlockCalls = 0
    viewModel.reusedStreamUnlocker = { _, _, _, _ ->
      unlockCalls++
      "https://torbox-cdn.example/wrong-episode.mkv"
    }

    viewModel.openStreamForMedia(series(mediaId))
    advanceUntilIdle()

    val state = viewModel.playbackState.value
    assertEquals("nessuno sblocco sulla cache di un'altra puntata", 0, unlockCalls)
    assertFalse(state.isReusedStream)
    assertEquals("https://torbox-cdn.example/show/s5e2-from-search.mkv", state.streamUrl)
    assertEquals(5, state.currentEpisode?.seasonNumber)
    assertEquals(2, state.currentEpisode?.episodeNumber)
  }

  // ── 4. Sorgente scaduta: fallback senza perdere identità ───────────────

  @Test
  fun expiredReusedSourceFallsBackToResolutionKeepingTheCorrectEpisode() = runTest(testDispatcher) {
    val mediaId = "tt9900004"
    val infoHash = "ffffffffffffffffffffffffffffffffffffffff"
    MediaRepository.updateEpisodeProgress(mediaId, 5, 2, savedPositionMs, episodeDurationMs)
    LastWorkingStreamRepository.saveStream(
      mediaId = mediaId,
      seasonNumber = 5,
      episodeNumber = 2,
      streamUrl = "https://dead-cdn.example/show/s5e2.mkv",
      streamTitle = "Show.S05.1080p.mkv",
      streamQuality = "1080p",
      serverName = "Torrentio",
      headers = emptyMap(),
      isProgressive = true,
      streamMode = StreamingEngineMode.DEBRID_TORBOX.name,
      infoHash = infoHash,
      fileIdx = 4,
      addonName = "Torrentio"
    )

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")

    val provider = object : TorBoxStreamProvider() {
      var invocations = 0
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
        return listOf(
          StreamSource(
            streamUrl = "https://torbox-cdn.example/show/s5e2-fallback.mkv",
            quality = "1080p",
            serverName = "Torrentio",
            releaseTitle = "Show.S05E02.1080p.mkv"
          )
        )
      }
    }

    val viewModel = createViewModel(provider)
    // Lo sblocco non riesce (torrent scaduto/rimosso): si usa il link salvato...
    viewModel.reusedStreamUnlocker = { _, _, _, _ -> null }
    viewModel.openStreamForMedia(series(mediaId))
    advanceUntilIdle()
    assertEquals("https://dead-cdn.example/show/s5e2.mkv", viewModel.playbackState.value.streamUrl)
    assertTrue(viewModel.playbackState.value.isReusedStream)

    // ...e quando il player fallisce scatta il fallback classico, sulla STESSA puntata.
    assertTrue(viewModel.handleReusedStreamFailure())
    advanceUntilIdle()

    val state = viewModel.playbackState.value
    assertTrue("i provider vengono interrogati", provider.invocations > 0)
    assertEquals("https://torbox-cdn.example/show/s5e2-fallback.mkv", state.streamUrl)
    assertFalse(state.isReusedStream)
    assertEquals(5, state.currentEpisode?.seasonNumber)
    assertEquals(2, state.currentEpisode?.episodeNumber)
    assertEquals(savedPositionMs, state.currentPositionMs)

    // L'identità Debrid non viene buttata via: il torrent resta recuperabile.
    val cached = LastWorkingStreamRepository.getValidStream(
      mediaId = mediaId,
      seasonNumber = 5,
      episodeNumber = 2,
      durationHours = 24
    )
    assertNotNull("voce di cache conservata per un nuovo tentativo", cached)
    assertEquals(infoHash, cached?.infoHash)
  }

  // ── Fixture ──────────────────────────────────────────────────────────────

  private fun series(mediaId: String): MediaItem = MediaItem(
    id = mediaId,
    imdbId = mediaId,
    title = "Show",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 1399,
    durationMinutes = 60,
    totalDurationMs = episodeDurationMs,
    lastWatchedSeason = 5,
    lastWatchedEpisode = 2,
    // Lista locale volutamente troncata: S5 NON è presente.
    episodes = listOf(episode(mediaId, 1, 1), episode(mediaId, 1, 2))
  )

  private fun episode(mediaId: String, season: Int, episode: Int): Episode = Episode(
    id = "${mediaId}_s${season}e$episode",
    seasonNumber = season,
    episodeNumber = episode,
    title = "Episodio $episode",
    synopsis = "",
    durationMinutes = 60,
    videoUrl = ""
  )

  private fun createViewModel(provider: StreamProvider): StreamNovaViewModel {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    viewModel.streamManager = StreamManager(providers = listOf(provider))
    viewModel.openSubtitlesProvider = object : OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ): List<Subtitle> = emptyList()
    }
    return viewModel
  }
}
