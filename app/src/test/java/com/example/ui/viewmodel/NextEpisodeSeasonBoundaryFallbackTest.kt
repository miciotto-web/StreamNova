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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fallback del fast-path "stesso torrent" al CONFINE DI STAGIONE (autoplay prossimo
 * episodio in modalità Debrid).
 *
 * Un pacchetto di stagione contiene solo gli episodi della propria stagione: alla
 * prima puntata della stagione successiva (es. S3E8 → S4E1) la STESSA fonte non può
 * avere il file, quindi il fallimento del fast-path NON è terminale — si prosegue con
 * la ricerca normale, ordinata per affinità con la fonte corrente (stesso addon in
 * cima), che auto-seleziona la sorgente riproducibile.
 *
 * Dentro la stessa stagione il contratto TERMINALE resta invariato: è coperto da
 * [NextEpisodeAutoplayTerminalStateTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NextEpisodeSeasonBoundaryFallbackTest {

  private val testDispatcher = StandardTestDispatcher()

  private companion object {
    const val SERIES_ID = "tt0944947"
    const val S1_PACK_HASH = "1111111111111111111111111111111111111111"
    const val S2E1_SAME_ADDON_URL = "https://torbox-cdn.example/show/s2e1-from-s2-pack.mkv"
    const val S2E1_OTHER_ADDON_URL = "https://cdn.example/show/s2e1-other.mkv"
    const val S2E1_FROM_S1_PACK_URL = "https://torbox-cdn.example/show/s2e1-from-s1-pack.mkv"
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

  /** Serie con l'ultima puntata di S1 e le prime due di S2 (nessun caricamento TMDB). */
  private fun series() = MediaItem(
    id = SERIES_ID,
    imdbId = SERIES_ID,
    title = "Game of Thrones",
    synopsis = "Series synopsis",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 1399,
    episodes = listOf(
      episode(season = 1, number = 1),
      episode(season = 2, number = 1),
      episode(season = 2, number = 2)
    )
  )

  private fun episode(season: Int, number: Int) = Episode(
    id = "${SERIES_ID}_s${season}e$number",
    seasonNumber = season,
    episodeNumber = number,
    title = "Stagione $season, episodio $number",
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
    infoHash = S1_PACK_HASH,
    fileIdx = 0,
    isCached = true
  )

  /** Sorgente S2E1 dello STESSO addon della stagione precedente (qualità inferiore). */
  private fun sameAddonSource() = StreamSource(
    streamUrl = S2E1_SAME_ADDON_URL,
    quality = "720p",
    serverName = "Torrentio 720p",
    addonName = "Torrentio",
    releaseTitle = "Show.S02E01.720p.mkv",
    infoHash = "2222222222222222222222222222222222222222",
    isCached = true
  )

  /** Sorgente S2E1 di un addon DIVERSO (qualità superiore ma fonte non affine). */
  private fun otherAddonSource() = StreamSource(
    streamUrl = S2E1_OTHER_ADDON_URL,
    quality = "1080p",
    serverName = "Comet 1080p",
    addonName = "Comet",
    releaseTitle = "Show.S02E01.1080p.mkv",
    infoHash = "3333333333333333333333333333333333333333",
    isCached = true
  )

  /** Provider Debrid finto: conta le interrogazioni e registra i parametri. */
  private class CountingTorBoxProvider(
    private val sources: List<StreamSource>
  ) : TorBoxStreamProvider() {
    var invocations = 0
      private set
    var capturedSeason: Int? = null
      private set
    var capturedEpisode: Int? = null
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
      capturedSeason = season
      capturedEpisode = episode
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

  /** Avvia S1E1 con il pacchetto della stagione 1 (la fonte che l'autoplay tenterà di riusare). */
  private fun StreamNovaViewModel.startLastEpisodeOfSeasonOne() {
    openPlayer(series(), episode(1, 1), pinnedSource())
  }

  // ── Confine di stagione: il fallimento del fast-path NON è terminale ─────────

  @Test
  fun seasonBoundaryFallbackQueriesProvidersAndStartsTheSameAddonSource() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(sameAddonSource(), otherAddonSource()))
    var resolverCalls = 0
    val viewModel = createViewModel(provider) { _, season, episode ->
      resolverCalls++
      assertEquals(2, season)
      assertEquals(1, episode)
      null // il pacchetto S1 non può contenere S2E1
    }
    viewModel.startLastEpisodeOfSeasonOne()
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2, 1))
    advanceUntilIdle()

    assertTrue("Al confine di stagione i provider DEVONO essere interrogati", provider.invocations > 0)
    assertEquals("La ricerca riguarda la puntata della nuova stagione", 2, provider.capturedSeason)
    assertEquals(1, provider.capturedEpisode)
    assertEquals("L'episodio corrente è la prima puntata della stagione 2", 2, viewModel.playbackState.value.currentEpisode?.seasonNumber)
    assertEquals(1, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertEquals(
      "L'affinità di fonte vince sulla qualità grezza: parte lo stesso addon della stagione precedente",
      S2E1_SAME_ADDON_URL,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "L'autoplay è riuscito: lo stato deve essere STARTED, non bloccato in ricerca",
      NextEpisodeAutoplayState.STARTED,
      viewModel.nextEpisodeAutoplayState.value
    )
    assertFalse("Nessuna scelta manuale quando una sorgente affine è stata avviata", viewModel.showSourceDialog.value)
    assertFalse("Transizione conclusa, niente spinner", viewModel.isSwitchingEpisode.value)
    assertTrue(viewModel.streamResolutionState.value is StreamResolutionState.Success)
    assertTrue(viewModel.availableSources.value.isNotEmpty())
  }

  @Test
  fun seasonBoundaryFallbackWithoutSourcesOpensTheManualSelector() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(emptyList())
    val viewModel = createViewModel(provider) { _, _, _ -> null }
    viewModel.startLastEpisodeOfSeasonOne()
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2, 1))
    advanceUntilIdle()

    assertEquals("Il fallback al confine di stagione ha interrogato i provider una volta", 1, provider.invocations)
    assertEquals(NextEpisodeAutoplayState.MANUAL_SELECTION_REQUIRED, viewModel.nextEpisodeAutoplayState.value)
    assertTrue("Senza sorgenti il selettore manuale si apre per la nuova puntata", viewModel.showSourceDialog.value)
    assertFalse(viewModel.isSwitchingEpisode.value)
    assertNull("Nessuno stream avviato", viewModel.playbackState.value.streamUrl)
    assertTrue(viewModel.availableSources.value.isEmpty())

    // Chiusura senza scelta: il ciclo di autoplay torna al fallimento terminale.
    viewModel.dismissSourceDialog()
    advanceUntilIdle()
    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)

    // Un nuovo avanzamento automatico non riavvia nulla dopo la chiusura senza scelta.
    val invocationsBeforeRetry = provider.invocations
    viewModel.autoPlayNextEpisode(episode(2, 1))
    advanceUntilIdle()
    assertEquals("Nessuna nuova ricerca dopo il fallimento terminale", invocationsBeforeRetry, provider.invocations)
    assertEquals(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE, viewModel.nextEpisodeAutoplayState.value)
  }

  @Test
  fun multiSeasonPackFastPathStillStartsDirectlyWithoutProviderQuery() = runTest(testDispatcher) {
    val provider = CountingTorBoxProvider(listOf(sameAddonSource(), otherAddonSource()))
    val viewModel = createViewModel(provider) { pinned, season, episode ->
      // Pacchetto MULTI-STAGIONE: S2E1 esiste nello stesso torrent e viene sbloccato.
      assertEquals(2, season)
      assertEquals(1, episode)
      pinned.copy(streamUrl = S2E1_FROM_S1_PACK_URL)
    }
    viewModel.startLastEpisodeOfSeasonOne()
    advanceUntilIdle()

    viewModel.autoPlayNextEpisode(episode(2, 1))
    advanceUntilIdle()

    assertEquals("Il fast-path vince ancora quando il torrent contiene l'episodio", 0, provider.invocations)
    assertEquals(S2E1_FROM_S1_PACK_URL, viewModel.playbackState.value.streamUrl)
    assertEquals(2, viewModel.playbackState.value.currentEpisode?.seasonNumber)
    assertEquals(1, viewModel.playbackState.value.currentEpisode?.episodeNumber)
    assertEquals(NextEpisodeAutoplayState.STARTED, viewModel.nextEpisodeAutoplayState.value)
    assertEquals(
      "L'identità del torrent resta la stessa anche al confine di stagione",
      S1_PACK_HASH,
      viewModel.playbackState.value.currentSource?.infoHash
    )
    assertFalse(viewModel.showSourceDialog.value)
  }
}
