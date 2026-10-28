package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regressione: la riproduzione HTTP non deve essere interrotta da una
 * risoluzione "tardiva".
 *
 * La risoluzione delle sorgenti e' progressiva: ogni provider HTTP che
 * risponde ri-emette l'elenco completo delle sorgenti. Prima della correzione
 * l'emissione tardiva sostituiva la sorgente in riproduzione
 * ([StreamNovaViewModel.upgradeStreamSource]): l'URL cambiava, il download
 * ripartiva da capo (nuovo buffering, spesso bloccato) e, se cambiavano anche
 * gli headers HTTP, la DataSource.Factory cambiava e l'intero ExoPlayer veniva
 * ricreato.
 *
 * Stessa verifica per la scelta manuale della sorgente: una risoluzione ancora
 * in volo non deve poter sovrascrivere la sorgente scelta dall'utente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StreamPlaybackStabilityTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    AppSettingsRepository.setTorBoxApiKey(null)
    // Nessun addon Stremio: le query di catalogo reali farebbero scattare i
    // timeout virtuali (clock jump) e renderebbero i test non deterministici.
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

  /** La modalita' HTTP e l'autoplay vanno impostati nel test: il repository le carica da DataStore. */
  private fun useHttpWebMode() {
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)
  }

  private fun movieFixture() = MediaItem(
    id = "tt0111161",
    imdbId = "tt0111161",
    title = "Stabilita' Stream",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 278
  )

  private fun provider(vararg sources: StreamSource, delayMs: Long = 0L) = object : StreamProvider {
    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      if (delayMs > 0L) delay(delayMs)
      return sources.toList()
    }
  }

  private fun createViewModel(providers: List<StreamProvider>): StreamNovaViewModel {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    viewModel.streamManager = StreamManager(providers = providers)
    // Nessuna chiamata di rete reale: i test devono restare deterministici.
    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ) = emptyList<com.example.domain.model.Subtitle>()
    }
    return viewModel
  }

  @Test
  fun lateResolutionResultDoesNotReplaceTheRunningPlayback() = runTest(testDispatcher) {
    useHttpWebMode()
    val fastSource = StreamSource(
      streamUrl = "https://cdn.example/hls/fast/720p/index.m3u8",
      quality = "720p",
      serverName = "Provider Veloce 720p"
    )
    val betterSource = StreamSource(
      streamUrl = "https://cdn.example/hls/lento/1080p/index.m3u8",
      quality = "1080p",
      serverName = "Provider Lento 1080p"
    )

    val viewModel = createViewModel(
      listOf(
        provider(fastSource),
        // Il secondo provider risponde in ritardo: e' la "risoluzione tardiva"
        // che prima della correzione sostituiva il flusso in riproduzione.
        provider(betterSource, delayMs = 3_000L)
      )
    )

    // Sequenza degli URL passati dal player durante l'intera risoluzione:
    // se una sorgente tardiva sostituisce quella in riproduzione qui compaiono
    // piu' URL distinti.
    val observedUrls = mutableListOf<String?>()
    backgroundScope.launch(testDispatcher) {
      viewModel.playbackState.collect { observedUrls.add(it.streamUrl) }
    }

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    advanceUntilIdle()

    val distinctUrls = observedUrls.filterNotNull().distinct()
    assertEquals(
      "Il player deve essere avviato una volta sola: la risoluzione tardiva non " +
        "puo' sostituire il flusso in riproduzione (rilevati: $distinctUrls)",
      1,
      distinctUrls.size
    )
    // La riproduzione non resta senza sorgente.
    assertTrue("Il player deve avere una sorgente attiva", viewModel.playbackState.value.streamUrl != null)
    // La lista per il selettore sorgenti resta comunque arricchita.
    assertEquals(
      "Le sorgenti risolte devono restare disponibili al selettore",
      2,
      viewModel.availableSources.value.size
    )
  }

  @Test
  fun theBestAvailableSourceIsChosenAndThePlayerStartsOnlyOnce() = runTest(testDispatcher) {
    useHttpWebMode()
    val lowQualitySource = StreamSource(
      streamUrl = "https://cdn.example/hls/veloce/720p/index.m3u8",
      quality = "720p",
      serverName = "Provider Veloce 720p"
    )
    val highQualitySource = StreamSource(
      streamUrl = "https://cdn.example/hls/migliore/1080p/index.m3u8",
      quality = "1080p",
      serverName = "Provider Migliore 1080p"
    )

    val viewModel = createViewModel(listOf(provider(lowQualitySource, highQualitySource)))

    val observedUrls = mutableListOf<String?>()
    backgroundScope.launch(testDispatcher) {
      viewModel.playbackState.collect { observedUrls.add(it.streamUrl) }
    }

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    advanceUntilIdle()

    val distinctUrls = observedUrls.filterNotNull().distinct()
    assertEquals(
      "Il player non deve mai essere riavviato: URL distinti rilevati: $distinctUrls",
      1,
      distinctUrls.size
    )
    assertEquals(
      "La sorgente migliore deve essere usata",
      "https://cdn.example/hls/migliore/1080p/index.m3u8",
      viewModel.playbackState.value.streamUrl
    )
  }

  @Test
  fun manualSourceSelectionIsNotOverwrittenByALateResolution() = runTest(testDispatcher) {
    useHttpWebMode()
    val lateSource = StreamSource(
      streamUrl = "https://cdn.example/hls/tardiva/1080p/index.m3u8",
      quality = "1080p",
      serverName = "Provider Tardivo 1080p"
    )
    val manualSource = StreamSource(
      streamUrl = "https://cdn.example/hls/manuale/720p/index.m3u8",
      quality = "720p",
      serverName = "Sorgente Scelta a Mano"
    )

    val viewModel = createViewModel(listOf(provider(lateSource, delayMs = 5_000L)))

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    // La risoluzione e' ancora in corso: l'utente sceglie una sorgente.
    advanceTimeBy(1_000L)
    viewModel.selectSource(manualSource)
    advanceUntilIdle()

    assertEquals(
      "La scelta manuale dell'utente non puo' essere sovrascritta dalla " +
        "risoluzione ancora in volo",
      "https://cdn.example/hls/manuale/720p/index.m3u8",
      viewModel.playbackState.value.streamUrl
    )
  }
}
