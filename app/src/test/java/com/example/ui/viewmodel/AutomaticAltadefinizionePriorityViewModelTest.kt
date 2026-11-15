package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.AutoSourcePriority
import com.example.data.streaming.SourcePlayability
import com.example.data.streaming.SourceVerifier
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Integrazione ViewModel ↔ StreamManager della gerarchia automatica:
 * Altadefinizione 720p riproducibile batte VixSrc 4K e il player parte con la
 * sorgente già verificata, senza attendere la finestra Full-HD storica.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutomaticAltadefinizionePriorityViewModelTest {

  private val testDispatcher = StandardTestDispatcher()
  private var createdViewModel: StreamNovaViewModel? = null

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    AppSettingsRepository.setTorBoxApiKey(null)
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setAutoplayEnabled(true)
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

  @Test
  fun altadefinizione720pVinceSuVixSrc4k() = runTest(testDispatcher) {
    val vix = fakeProvider(
      AutoSourcePriority.VIXSRC_ID,
      listOf(source(AutoSourcePriority.VIXSRC_ID, VIX_4K, "4K", 2160))
    )
    val alta = fakeProvider(
      AutoSourcePriority.ALTADEFINIZIONE_ID,
      listOf(
        source(AutoSourcePriority.ALTADEFINIZIONE_ID, ALTA_2160, "4K", 2160),
        source(AutoSourcePriority.ALTADEFINIZIONE_ID, ALTA_1440, "1440p", 1440),
        source(AutoSourcePriority.ALTADEFINIZIONE_ID, ALTA_1080, "1080p", 1080),
        source(AutoSourcePriority.ALTADEFINIZIONE_ID, ALTA_720, "720p", 720)
      )
    )
    val verifier = SourceVerifier { source ->
      if (source.streamUrl == ALTA_720) SourcePlayability.Playable
      else SourcePlayability.Unavailable
    }

    val viewModel = createViewModel(listOf(vix, alta), verifier)
    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    advanceUntilIdle()

    assertNotNull("il player deve essere partito", viewModel.playbackState.value.streamUrl)
    assertEquals(
      "la 720p riproducibile di Altadefinizione deve battere la 4K di VixSrc",
      ALTA_720,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals("720p", viewModel.playbackState.value.streamQuality)
    assertEquals(
      "VixSrc viene interrogato dopo la scelta solo per popolare il selettore",
      1,
      vix.started.get()
    )
    assertTrue(
      "il selettore del player deve contenere anche VixSrc",
      viewModel.sourceCandidates.value.any {
        it.sourceProviderId == AutoSourcePriority.VIXSRC_ID
      }
    )
  }

  private fun createViewModel(
    providers: List<StreamProvider>,
    verifier: SourceVerifier
  ): StreamNovaViewModel {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    createdViewModel = viewModel
    viewModel.streamManager = StreamManager(
      providers = providers,
      timeoutMs = 2_000L,
      addonStreamsFetcher = { _, _, _, _, _ -> emptyList() },
      sourceVerifier = verifier
    )
    viewModel.openSubtitlesProvider =
      object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
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

  private fun movieFixture() = MediaItem(
    id = "tt0111161",
    imdbId = "tt0111161",
    title = "Priorità Altadefinizione",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 278
  )

  private fun source(
    provider: String,
    url: String,
    quality: String,
    height: Int
  ) = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = provider,
    declaredQuality = quality,
    verifiedHeight = height
  )

  private fun fakeProvider(providerId: String, sources: List<StreamSource>) =
    object : StreamProvider {
      override val providerId: String = providerId
      val started = AtomicInteger(0)

      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> {
        started.incrementAndGet()
        return sources
      }
    }

  private companion object {
    const val ALTA_2160 = "https://alta.test/hls/movie/2160.m3u8"
    const val ALTA_1440 = "https://alta.test/hls/movie/1440.m3u8"
    const val ALTA_1080 = "https://alta.test/hls/movie/1080.m3u8"
    const val ALTA_720 = "https://alta.test/hls/movie/720.m3u8"
    const val VIX_4K = "https://vix.test/hls/movie/2160.m3u8"
  }
}
