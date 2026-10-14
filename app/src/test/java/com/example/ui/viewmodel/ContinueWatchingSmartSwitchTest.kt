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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Continua a guardare" + Smart Switch HTTP <-> Debrid.
 *
 * Verifica che la fonte salvata venga riusata solo se appartiene alla modalità attiva e
 * che, in caso di mismatch, si risolva da zero con i provider corretti mantenendo il minuto
 * di ripresa (nessuna schermata nera, nessun riuso della sorgente sbagliata).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ContinueWatchingSmartSwitchTest {

  private val testDispatcher = StandardTestDispatcher()
  private lateinit var database: StreamNovaDatabase

  private val savedMinuteMs = 7 * 60 * 1000L

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
      // Le query Room girano sullo stesso scheduler del test: `advanceUntilIdle()` le attende
      // in modo deterministico (niente race con l'executor di Room).
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
    LastWorkingStreamRepository.setInstanceForTesting(database)
  }

  @After
  fun tearDown() {
    LastWorkingStreamRepository.setInstanceForTesting(null)
    database.close()
    AppSettingsRepository.setTorBoxApiKey(null)
    Dispatchers.resetMain()
  }

  // ── HTTP (salvato) -> Debrid (attivo) ────────────────────────────────

  @Test
  fun continueWatchingSavedInHttpIsDiscardedWhenDebridIsActiveAndResumeIsKept() = runTest(testDispatcher) {
    val savedHttpUrl = "https://cdn.vidsrc.example/movie/1080p.m3u8"
    LastWorkingStreamRepository.saveStream(
      mediaId = MEDIA_ID,
      seasonNumber = LastWorkingStreamRepository.NO_EPISODE,
      episodeNumber = LastWorkingStreamRepository.NO_EPISODE,
      streamUrl = savedHttpUrl,
      streamTitle = "VixSrc 1080p",
      streamQuality = "1080p",
      serverName = "VixSrc",
      headers = emptyMap(),
      isProgressive = false,
      streamMode = StreamingEngineMode.HTTP_WEB.name
    )

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")

    val debridUrl = "https://torbox-cdn.example/movie/1080p.mkv"
    val debridProvider = object : TorBoxStreamProvider() {
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
          streamUrl = debridUrl,
          quality = "1080p",
          serverName = "TorBox Instant",
          releaseTitle = "Movie.1080p.mkv"
        )
      )
    }

    val viewModel = createViewModel(debridProvider)
    viewModel.openStreamForMedia(movie()) // allowReuse = true (Continua a guardare)
    advanceUntilIdle()

    val state = viewModel.playbackState.value
    assertEquals("La sorgente HTTP salvata deve essere scartata", debridUrl, state.streamUrl)
    assertFalse("Nessun riuso quando la modalità cambia", state.isReusedStream)
    assertEquals("Il minuto di ripresa deve essere mantenuto", savedMinuteMs, state.currentPositionMs)
  }

  // ── Debrid (salvato) -> HTTP (attivo) ────────────────────────────────

  @Test
  fun continueWatchingSavedInDebridIsDiscardedWhenHttpIsActiveAndResumeIsKept() = runTest(testDispatcher) {
    val savedDebridUrl = "https://torbox-cdn.example/movie/1080p.mkv"
    LastWorkingStreamRepository.saveStream(
      mediaId = MEDIA_ID,
      seasonNumber = LastWorkingStreamRepository.NO_EPISODE,
      episodeNumber = LastWorkingStreamRepository.NO_EPISODE,
      streamUrl = savedDebridUrl,
      streamTitle = "Movie.1080p.mkv",
      streamQuality = "1080p",
      serverName = "TorBox Instant",
      headers = emptyMap(),
      isProgressive = true,
      streamMode = StreamingEngineMode.DEBRID_TORBOX.name
    )

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)

    val httpUrl = "https://cdn.vixsrc.example/movie/1080p.m3u8"
    val httpProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = listOf(
        StreamSource(streamUrl = httpUrl, quality = "1080p", serverName = "VixSrc")
      )
    }

    val viewModel = createViewModel(httpProvider)
    viewModel.openStreamForMedia(movie())
    advanceUntilIdle()

    val state = viewModel.playbackState.value
    assertEquals("La sorgente Debrid salvata deve essere scartata", httpUrl, state.streamUrl)
    assertFalse("Nessun riuso quando la modalità cambia", state.isReusedStream)
    assertEquals("Il minuto di ripresa deve essere mantenuto", savedMinuteMs, state.currentPositionMs)
  }

  // ── Tracciamento: la modalità viene persistita col flusso funzionante ─

  @Test
  fun playbackReadyPersistsTheModeOfTheResolvedStream() = runTest(testDispatcher) {
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")

    val debridUrl = "https://torbox-cdn.example/movie/1080p.mkv"
    val debridProvider = object : TorBoxStreamProvider() {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        imdbId: String?
      ): List<StreamSource> = listOf(
        StreamSource(streamUrl = debridUrl, quality = "1080p", serverName = "TorBox Instant")
      )
    }

    val viewModel = createViewModel(debridProvider)
    viewModel.openStreamForMedia(movie())
    advanceUntilIdle()

    viewModel.onStreamPlaybackReady()
    advanceUntilIdle()

    val cached = LastWorkingStreamRepository.getValidStream(
      mediaId = MEDIA_ID,
      seasonNumber = LastWorkingStreamRepository.NO_EPISODE,
      episodeNumber = LastWorkingStreamRepository.NO_EPISODE,
      durationHours = 24
    )
    assertEquals(debridUrl, cached?.streamUrl)
    assertEquals(StreamingEngineMode.DEBRID_TORBOX.name, cached?.streamMode)
  }

  @Test
  fun cachedStreamWithoutRecordedModeIsNotReusedInDebridWhenItLooksHttp() = runTest(testDispatcher) {
    // Voce legacy (nessuna modalità registrata) con una fonte chiaramente HTTP: in modalità
    // Debrid NON deve essere riusata, si risolve ex-novo.
    val legacyHttpUrl = "https://cdn.vixsrc.example/movie/1080p.m3u8"
    LastWorkingStreamRepository.saveStream(
      mediaId = MEDIA_ID,
      seasonNumber = LastWorkingStreamRepository.NO_EPISODE,
      episodeNumber = LastWorkingStreamRepository.NO_EPISODE,
      streamUrl = legacyHttpUrl,
      streamTitle = "VixSrc 1080p",
      streamQuality = "1080p",
      serverName = "VixSrc",
      headers = emptyMap(),
      isProgressive = false,
      streamMode = null
    )

    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")

    val debridUrl = "https://torbox-cdn.example/movie/1080p.mkv"
    val debridProvider = object : TorBoxStreamProvider() {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?,
        imdbId: String?
      ): List<StreamSource> = listOf(
        StreamSource(streamUrl = debridUrl, quality = "1080p", serverName = "TorBox Instant")
      )
    }

    val viewModel = createViewModel(debridProvider)
    viewModel.openStreamForMedia(movie())
    advanceUntilIdle()

    assertEquals(debridUrl, viewModel.playbackState.value.streamUrl)
    assertFalse(viewModel.playbackState.value.isReusedStream)
    assertNull(LastWorkingStreamRepository.parseEngineMode(null))
  }

  // ── Fixture ──────────────────────────────────────────────────────────

  private fun movie(): MediaItem = MediaItem(
    id = MEDIA_ID,
    title = "Fight Club",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 550,
    durationMinutes = 120,
    currentProgressMs = savedMinuteMs,
    totalDurationMs = 120 * 60 * 1000L
  )

  private fun createViewModel(provider: StreamProvider): StreamNovaViewModel {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    viewModel.streamManager = StreamManager(providers = listOf(provider))
    viewModel.openSubtitlesProvider = object : OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?
      ): List<Subtitle> = emptyList()
    }
    return viewModel
  }

  private companion object {
    const val MEDIA_ID = "test_smart_switch_movie"
  }
}
