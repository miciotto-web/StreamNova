package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamManager
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TorBoxMultiSourceResolutionTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun testMultipleTorBoxSourcesPropagatedToAvailableSourcesAndDialog() = runTest(testDispatcher) {
    // 1. Arrange test sources with different qualities
    val source4k = StreamSource(
      streamUrl = "https://torbox.app/dl/4k.mkv",
      quality = "4K",
      serverName = "TorBox 4K",
      releaseTitle = "Movie.2160p.TB.mkv"
    )
    val source1080p = StreamSource(
      streamUrl = "https://torbox.app/dl/1080p.mkv",
      quality = "1080p",
      serverName = "TorBox 1080p",
      releaseTitle = "Movie.1080p.TB.mkv"
    )
    val source720p = StreamSource(
      streamUrl = "https://torbox.app/dl/720p.mkv",
      quality = "720p",
      serverName = "TorBox 720p",
      releaseTitle = "Movie.720p.TB.mkv"
    )

    val fakeTorBoxProvider = object : TorBoxStreamProvider() {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> {
        return listOf(source1080p, source4k, source720p)
      }
    }

    val customStreamManager = StreamManager(
      providers = listOf(fakeTorBoxProvider)
    )

    // Configure TorBox mode and manual selection (autoplay = false)
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setAutoplayEnabled(false)

    val viewModel = StreamNovaViewModel()
    viewModel.streamManager = customStreamManager

    val mediaItem = MediaItem(
      id = "movie_123",
      title = "Test Movie",
      synopsis = "Test synopsis",
      videoUrl = "",
      type = MediaType.FILM,
      tmdbId = 550
    )

    // 2. Act: open stream for media
    viewModel.openStreamForMedia(mediaItem)
    advanceUntilIdle()

    // 3. Assert: All sources found must be in availableSources
    val available = viewModel.availableSources.value
    assertEquals("All 3 sources must be propagated to availableSources", 3, available.size)
    assertTrue("Show source dialog must be true", viewModel.showSourceDialog.value)
    // 4K has higher quality score than 1080p and 720p, so it must be ranked first
    assertEquals("TorBox 4K", available[0].serverName)
    assertEquals("TorBox 1080p", available[1].serverName)
    assertEquals("TorBox 720p", available[2].serverName)

    // streamResult must remain Idle while waiting for user selection
    assertEquals(StreamResult.Idle, viewModel.streamResult.value)

    // 4. Act: Select a source from the dialog
    val selectedSource = available[1] // TorBox 1080p
    viewModel.selectSource(selectedSource)
    advanceUntilIdle()

    // 5. Assert: Dialog closed and streamResult contains the selected source
    assertFalse("Show source dialog must be dismissed", viewModel.showSourceDialog.value)
    assertTrue("availableSources must be cleared after selection", viewModel.availableSources.value.isEmpty())
    val result = viewModel.streamResult.value
    assertTrue("streamResult must be Success", result is StreamResult.Success)
    val successSources = (result as StreamResult.Success).sources
    assertEquals(1, successSources.size)
    assertEquals("TorBox 1080p", successSources.first().serverName)
  }
}
