package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Finestra Full-HD dell'auto-pick in modalità HTTP automatica.
 *
 * Contratti verificati:
 *  - in selezione automatica ("Provider HTTP principale" = auto) una sorgente
 *    720p veloce NON fa partire il player prima che i provider capaci di 1080p
 *    abbiano risposto: una sorgente Full HD arrivata nella finestra parte
 *    subito e prevale sul Fast-Start del provider più veloce;
 *  - se nessun provider ha il titolo in 1080p, la riproduzione parte comunque
 *    con la migliore sorgente disponibile (mai spinner infinito);
 *  - la scelta della finestra resta corta quando attendere il 1080p non ha
 *    senso: provider principale scelto manualmente, motore Debrid, oppure
 *    "Risoluzione preferita" = 720p.
 *
 * sdk = [34] come le altre classi Android della suite viewmodel: senza @Config
 * la classe girerebbe a targetSdk 36 e la coesistenza di due sandbox SDK nella
 * stessa JVM rompe l'inizializzazione dei font di Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutoPickFullHdWindowTest {

  private val testDispatcher = StandardTestDispatcher()

  /** ViewModel creato dal test: le sue coroutine vengono chiuse in [tearDown]. */
  private var createdViewModel: StreamNovaViewModel? = null

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
    createdViewModel?.let { vm ->
      vm.viewModelScope.coroutineContext[Job]?.let { job ->
        job.cancel()
        testDispatcher.scheduler.advanceUntilIdle()
      }
    }
    createdViewModel = null
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setAutoplayEnabled(false)
    Dispatchers.resetMain()
  }

  // ── 1. Selezione della finestra (funzione pura) ──────────────────────────

  @Test
  fun autoHttpModeWithDefaultResolutionUsesTheFullHdWindow() {
    assertEquals(
      AUTO_PICK_FULLHD_SETTLE_WINDOW_MS,
      autoPickSettleWindowMs(StreamingEngineMode.HTTP_WEB, null, PreferredResolution.AUTO)
    )
    assertEquals(
      AUTO_PICK_FULLHD_SETTLE_WINDOW_MS,
      autoPickSettleWindowMs(StreamingEngineMode.HTTP_WEB, null, PreferredResolution.FULL_HD_1080P)
    )
    assertEquals(
      AUTO_PICK_FULLHD_SETTLE_WINDOW_MS,
      autoPickSettleWindowMs(StreamingEngineMode.HTTP_WEB, null, PreferredResolution.UHD_4K)
    )
  }

  @Test
  fun fullHdWindowIsSkippedWhenItWouldBePointless() {
    // Risoluzione preferita 720p: attendere il 1080p non avrebbe senso.
    assertEquals(
      AUTO_PICK_SETTLE_WINDOW_MS,
      autoPickSettleWindowMs(StreamingEngineMode.HTTP_WEB, null, PreferredResolution.HD_720P)
    )
    // Motore Debrid: le sorgenti cached non devono attendere i provider HTTP.
    assertEquals(
      AUTO_PICK_SETTLE_WINDOW_MS,
      autoPickSettleWindowMs(StreamingEngineMode.DEBRID_TORBOX, null, PreferredResolution.AUTO)
    )
    // Provider principale scelto manualmente: finestra del provider preferito.
    assertEquals(
      PRIMARY_PROVIDER_SETTLE_WINDOW_MS,
      autoPickSettleWindowMs(StreamingEngineMode.HTTP_WEB, "VixSrcProvider", PreferredResolution.AUTO)
    )
  }

  // ── 2. Il 1080p lento prevale sul 720p veloce ────────────────────────────

  @Test
  fun slowFullHdSourceBeatsTheFast720pFastStart() = runTest(testDispatcher) {
    useHttpWebMode()
    val fast720pUrl = "https://cdn.example/vixsrc/720p/index.m3u8"
    val slow1080pUrl = "https://cdn.example/altadefinizione/1080p/index.m3u8"
    val viewModel = createViewModel(
      listOf(
        // Provider veloce ma solo HD (come VixSrc su molte serie TV).
        provider(StreamSource(streamUrl = fast720pUrl, quality = "720p", serverName = "Veloce 720p")),
        // Provider più lento con il titolo in Full HD.
        provider(
          StreamSource(streamUrl = slow1080pUrl, quality = "1080p", serverName = "Lento 1080p"),
          delayMs = 3_000L
        )
      )
    )

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)

    // Con la vecchia finestra da 1.2s il player sarebbe già partito in 720p:
    // con la finestra Full-HD la riproduzione non è ancora iniziata.
    advanceTimeBy(1_500L)
    assertNull(
      "Il Fast-Start 720p non deve avviare il player entro la vecchia finestra " +
        "(stato=${viewModel.streamResolutionState.value})",
      viewModel.playbackState.value.streamUrl
    )

    // La sorgente Full HD arriva nella finestra: parte SUBITO e prevale.
    advanceTimeBy(2_000L)
    advanceUntilIdle()

    assertEquals(
      "Il provider con il contenuto in 1080p deve vincere la selezione automatica",
      slow1080pUrl,
      viewModel.playbackState.value.streamUrl
    )

    assertEquals(
      "Il provider con il contenuto in 1080p deve vincere la selezione automatica",
      slow1080pUrl,
      viewModel.playbackState.value.streamUrl
    )
  }

  // ── 3. Nessun 1080p: parte la migliore disponibile, mai spinner infinito ──

  @Test
  fun withoutFullHdSourcesPlaybackStartsWithTheBestAvailable() = runTest(testDispatcher) {
    useHttpWebMode()
    val fast720pUrl = "https://cdn.example/vixsrc/720p/index.m3u8"
    val later720pUrl = "https://cdn.example/altro/720p/index.m3u8"
    val viewModel = createViewModel(
      listOf(
        provider(StreamSource(streamUrl = fast720pUrl, quality = "720p", serverName = "Veloce 720p")),
        provider(
          StreamSource(streamUrl = later720pUrl, quality = "720p", serverName = "Altro 720p"),
          delayMs = 2_000L
        )
      )
    )

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    advanceUntilIdle()

    assertTrue(
      "Senza sorgenti Full HD il player deve partire comunque (stato=${viewModel.streamResolutionState.value})",
      viewModel.streamResolutionState.value is StreamResolutionState.Success
    )
    val startedUrl = viewModel.playbackState.value.streamUrl
    assertTrue(
      "La sorgente avviata deve essere una delle 720p raccolte (trovata: $startedUrl)",
      startedUrl == fast720pUrl || startedUrl == later720pUrl
    )
  }

  // ── harness ───────────────────────────────────────────────────────────────

  private fun useHttpWebMode() {
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)
  }

  private fun movieFixture() = MediaItem(
    id = "tt0111161",
    imdbId = "tt0111161",
    title = "Finestra Full-HD Auto-Pick",
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
    createdViewModel = viewModel
    viewModel.streamManager = StreamManager(providers = providers)
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
}
