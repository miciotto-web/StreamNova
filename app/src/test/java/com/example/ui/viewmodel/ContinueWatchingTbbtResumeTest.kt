package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Percorso completo «Continua a guardare → episodio → sorgente → player» per
 * The Big Bang Theory.
 *
 * La lista episodi dell'item di catalogo è **troncata** (solo le prime stagioni
 * generate): S5E6 non è presente. Prima del fix il ViewModel ripiegava sul primo
 * episodio (S1E1) e la posizione applicata era il vecchio seed di 12 minuti sempre
 * uguale. Qui si verifica che vengano richiesti al provider esattamente S5E6 e la
 * posizione persistita di S5E6, e che gli altri episodi non vengano contaminati.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ContinueWatchingTbbtResumeTest {

  private val testDispatcher = StandardTestDispatcher()

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

    // Repository pulito e senza Room: il progresso è quello in memoria, come subito
    // dopo il caricamento di avvio (loadFromCache). Il riavvio da Room è coperto da
    // EpisodeProgressPersistenceTest.
    MediaRepository.setDatabaseForTesting(null)
    LastWorkingStreamRepository.setInstanceForTesting(null)

    MediaRepository.updateEpisodeProgress(MEDIA_ID, 1, 1, S1E1_POSITION_MS, EPISODE_DURATION_MS)
    MediaRepository.updateEpisodeProgress(MEDIA_ID, 5, 6, S5E6_POSITION_MS, EPISODE_DURATION_MS)
    MediaRepository.updateEpisodeProgress(MEDIA_ID, 5, 7, S5E7_POSITION_MS, EPISODE_DURATION_MS)
    MediaRepository.updateEpisodeProgress(CROWN_ID, 4, 3, CROWN_POSITION_MS, EPISODE_DURATION_MS)
  }

  @After
  fun tearDown() {
    LastWorkingStreamRepository.setInstanceForTesting(null)
    MediaRepository.setDatabaseForTesting(null)
    AppSettingsRepository.setTorBoxApiKey(null)
    Dispatchers.resetMain()
  }

  // ── 1. Il caso riprodotto: S05E06, non S01E01 ──────────────────────────

  @Test
  fun continueWatchingOpensS05E06AndResumesItsOwnPosition() = runTest(testDispatcher) {
    val captured = CapturedRequest()
    val viewModel = createViewModel(capturingProvider(captured))

    viewModel.playFromContinueWatching(tbbt())

    advanceUntilIdle()

    val state = viewModel.playbackState.value
    assertEquals("stagione richiesta al provider", 5, captured.season)
    assertEquals("episodio richiesto al provider", 6, captured.episode)
    assertEquals(5, state.currentEpisode?.seasonNumber)
    assertEquals(6, state.currentEpisode?.episodeNumber)
    assertEquals("posizione salvata di S5E6, non il seed di S1E1", S5E6_POSITION_MS, state.currentPositionMs)
    assertEquals("durata reale di S5E6", EPISODE_DURATION_MS, state.durationMs)
    assertEquals("https://cdn.example/show/s5e6/index.m3u8", state.streamUrl)
  }

  @Test
  fun explicitS01E01KeepsItsOwnPositionAndDoesNotInheritS05E06() = runTest(testDispatcher) {
    assertEquals("progresso S1E1 disponibile prima della riproduzione", S1E1_POSITION_MS, MediaRepository.getEpisodeProgress(MEDIA_ID, 1, 1))
    val captured = CapturedRequest()
    val viewModel = createViewModel(capturingProvider(captured))

    val s1e1 = tbbt().episodes.first { it.seasonNumber == 1 && it.episodeNumber == 1 }
    viewModel.openStreamForMedia(tbbt(), s1e1)

    advanceUntilIdle()

    val state = viewModel.playbackState.value
    assertEquals(1, captured.season)
    assertEquals(1, captured.episode)
    assertEquals(1, state.currentEpisode?.seasonNumber)
    assertEquals(1, state.currentEpisode?.episodeNumber)
    assertEquals("posizione di S1E1", S1E1_POSITION_MS, state.currentPositionMs)
    assertNotEquals("mai la posizione di S5E6", S5E6_POSITION_MS, state.currentPositionMs)
  }

  // ── 2. Salvataggio e indipendenza tra episodi ──────────────────────────

  @Test
  fun savingS05E06DoesNotOverwriteS01E01() = runTest(testDispatcher) {
    val viewModel = createViewModel(capturingProvider(CapturedRequest()))
    viewModel.playFromContinueWatching(tbbt())
    advanceUntilIdle()

    viewModel.updatePlaybackPosition(20 * 60_000L, EPISODE_DURATION_MS, 0L)
    viewModel.saveCurrentPlaybackProgress(20 * 60_000L)
    advanceUntilIdle()

    assertEquals(20 * 60_000L, MediaRepository.getEpisodeProgress(MEDIA_ID, 5, 6))
    assertEquals("S1E1 resta al suo minutaggio", S1E1_POSITION_MS, MediaRepository.getEpisodeProgress(MEDIA_ID, 1, 1))
  }

  @Test
  fun closingPlayerPersistsThePositionOfTheCorrectEpisode() = runTest(testDispatcher) {
    val viewModel = createViewModel(capturingProvider(CapturedRequest()))
    viewModel.playFromContinueWatching(tbbt())
    advanceUntilIdle()

    viewModel.updatePlaybackPosition(19 * 60_000L, EPISODE_DURATION_MS, 0L)
    viewModel.closePlayer()
    advanceUntilIdle()

    assertEquals(19 * 60_000L, MediaRepository.getEpisodeProgress(MEDIA_ID, 5, 6))
    assertEquals(S1E1_POSITION_MS, MediaRepository.getEpisodeProgress(MEDIA_ID, 1, 1))
  }

  // ── 3. Cambio episodio senza contaminazione ────────────────────────────

  @Test
  fun switchingToTheNextEpisodeNeverTransfersTheCurrentPosition() = runTest(testDispatcher) {
    val captured = CapturedRequest()
    val viewModel = createViewModel(capturingProvider(captured))
    viewModel.playFromContinueWatching(tbbt())
    advanceUntilIdle()

    viewModel.updatePlaybackPosition(20 * 60_000L, EPISODE_DURATION_MS, 0L)
    viewModel.playNextEpisode(episode(5, 7))

    // Salvataggio durante la transizione (BACK immediato): la sessione è già S5E7.
    viewModel.saveCurrentPlaybackProgress(4 * 60_000L)
    advanceUntilIdle()

    assertEquals("posizione finale di S5E6", 20 * 60_000L, MediaRepository.getEpisodeProgress(MEDIA_ID, 5, 6))
    assertEquals("S5E7 conserva la propria posizione aggiornata", 4 * 60_000L, MediaRepository.getEpisodeProgress(MEDIA_ID, 5, 7))
    assertEquals("S1E1 non viene toccato", S1E1_POSITION_MS, MediaRepository.getEpisodeProgress(MEDIA_ID, 1, 1))
    assertEquals(5, captured.season)
    assertEquals(7, captured.episode)
    assertEquals(5, viewModel.playbackState.value.currentEpisode?.seasonNumber)
    assertEquals(7, viewModel.playbackState.value.currentEpisode?.episodeNumber)
  }

  // ── 4. Identificativi mancanti e serie con molte stagioni ──────────────

  @Test
  fun seriesWithoutTmdbIdStillResumesTheSavedSeasonAndEpisode() = runTest(testDispatcher) {
    val captured = CapturedRequest()
    val viewModel = createViewModel(capturingProvider(captured))

    val noTmdb = MediaItem(
      id = "stremio_series_1418",
      imdbId = "tt0898266",
      title = "The Big Bang Theory",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = null,
      seasonsCount = 12,
      durationMinutes = 22,
      currentProgressMs = S5E6_POSITION_MS,
      totalDurationMs = EPISODE_DURATION_MS,
      lastWatchedSeason = 5,
      lastWatchedEpisode = 6,
      episodes = MediaRepository.generateEpisodesForTv(1418, "The Big Bang Theory", null, null, 12)
    )

    viewModel.openStreamForMedia(noTmdb)
    advanceUntilIdle()

    assertEquals(5, captured.season)
    assertEquals(6, captured.episode)
    assertEquals(S5E6_POSITION_MS, viewModel.playbackState.value.currentPositionMs)
  }

  @Test
  fun highSeasonOfALongRunningSeriesIsNotReplacedByS1E1() = runTest(testDispatcher) {
    MediaRepository.updateEpisodeProgress(MEDIA_ID, 18, 5, 8 * 60_000L, EPISODE_DURATION_MS)
    val captured = CapturedRequest()
    val viewModel = createViewModel(capturingProvider(captured))

    val media = tbbt().copy(lastWatchedSeason = 18, lastWatchedEpisode = 5, currentProgressMs = 8 * 60_000L)
    viewModel.playFromContinueWatching(media)
    advanceUntilIdle()

    assertEquals(18, captured.season)
    assertEquals(5, captured.episode)
    assertEquals(18, viewModel.playbackState.value.currentEpisode?.seasonNumber)
    assertEquals(5, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertEquals(8 * 60_000L, viewModel.playbackState.value.currentPositionMs)
  }

  @Test
  fun anotherMultiSeasonSeriesResumesItsOwnEpisode() = runTest(testDispatcher) {
    val captured = CapturedRequest()
    val viewModel = createViewModel(capturingProvider(captured))

    viewModel.playFromContinueWatching(crown())
    advanceUntilIdle()

    assertEquals(4, captured.season)
    assertEquals(3, captured.episode)
    assertEquals(4, viewModel.playbackState.value.currentEpisode?.seasonNumber)
    assertEquals(3, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertEquals(CROWN_POSITION_MS, viewModel.playbackState.value.currentPositionMs)
  }

  // ── 5. Dati incompleti: identità corretta, nessun minutaggio inventato ─

  @Test
  fun missingSavedPositionOpensTheRightEpisodeFromZero() = runTest(testDispatcher) {
    MediaRepository.setDatabaseForTesting(null) // azzera i progressi in memoria
    val captured = CapturedRequest()
    val viewModel = createViewModel(capturingProvider(captured))

    val media = tbbt().copy(currentProgressMs = 0L, totalDurationMs = 0L)
    viewModel.playFromContinueWatching(media)
    advanceUntilIdle()

    assertEquals(5, captured.season)
    assertEquals(6, captured.episode)
    assertEquals(5, viewModel.playbackState.value.currentEpisode?.seasonNumber)
    assertEquals(6, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertEquals("nessun progresso inventato", 0L, viewModel.playbackState.value.currentPositionMs)
  }

  // ── Fixture ─────────────────────────────────────────────────────────────

  private fun tbbt(): MediaItem = MediaItem(
    id = MEDIA_ID,
    imdbId = "tt0898266",
    title = "The Big Bang Theory",
    originalTitle = "The Big Bang Theory",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 1418,
    seasonsCount = 12,
    durationMinutes = 22,
    currentProgressMs = S5E6_POSITION_MS,
    totalDurationMs = EPISODE_DURATION_MS,
    lastWatchedSeason = 5,
    lastWatchedEpisode = 6,
    episodes = MediaRepository.generateEpisodesForTv(1418, "The Big Bang Theory", null, null, 12)
  )

  private fun crown(): MediaItem = MediaItem(
    id = CROWN_ID,
    imdbId = "tt4786824",
    title = "The Crown",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 65494,
    seasonsCount = 6,
    durationMinutes = 58,
    currentProgressMs = CROWN_POSITION_MS,
    totalDurationMs = EPISODE_DURATION_MS,
    lastWatchedSeason = 4,
    lastWatchedEpisode = 3,
    episodes = MediaRepository.generateEpisodesForTv(65494, "The Crown", null, null, 6)
  )

  private fun episode(season: Int, episode: Int): Episode = Episode(
    id = "${MEDIA_ID}_s${season}e$episode",
    seasonNumber = season,
    episodeNumber = episode,
    title = "Episodio $episode",
    synopsis = "",
    durationMinutes = 22,
    videoUrl = ""
  )

  private class CapturedRequest {
    var season: Int? = null
    var episode: Int? = null
  }

  private fun capturingProvider(captured: CapturedRequest): StreamProvider = object : StreamProvider {
    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      captured.season = season
      captured.episode = episode
      return listOf(
        StreamSource(
          streamUrl = "https://cdn.example/show/s${season}e${episode}/index.m3u8",
          quality = "1080p",
          serverName = "TestSource"
        )
      )
    }
  }

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

  private companion object {
    const val MEDIA_ID = "tmdb_tv_1418"
    const val CROWN_ID = "tmdb_tv_65494"
    const val S1E1_POSITION_MS = 12 * 60_000L
    const val S5E6_POSITION_MS = 17 * 60_000L + 32_000L
    const val S5E7_POSITION_MS = 3 * 60_000L
    const val CROWN_POSITION_MS = 11 * 60_000L
    const val EPISODE_DURATION_MS = 22 * 60_000L
  }
}
