package com.example.ui.viewmodel

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.domain.model.Subtitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleSyncViewModelTest {

  @Test
  fun subtitleOffsetRegolaECircondaRangeConsentito() {
    val dispatcher = StandardTestDispatcher()
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    // Offset iniziale 0
    assertEquals(0L, viewModel.playbackState.value.subtitleOffsetMs)

    // Aggiustamento positivo +250 ms
    viewModel.adjustSubtitleOffsetMs(250L)
    assertEquals(250L, viewModel.playbackState.value.subtitleOffsetMs)

    // Aggiustamento negativo -500 ms
    viewModel.adjustSubtitleOffsetMs(-500L)
    assertEquals(-250L, viewModel.playbackState.value.subtitleOffsetMs)

    // Clamp massimo consentito +10.0s (10_000 ms)
    viewModel.setSubtitleOffsetMs(15_000L)
    assertEquals(10_000L, viewModel.playbackState.value.subtitleOffsetMs)

    // Clamp minimo consentito -10.0s (-10_000 ms)
    viewModel.setSubtitleOffsetMs(-15_000L)
    assertEquals(-10_000L, viewModel.playbackState.value.subtitleOffsetMs)

    // Ripristino (0 ms)
    viewModel.resetSubtitleOffsetMs()
    assertEquals(0L, viewModel.playbackState.value.subtitleOffsetMs)
  }

  @Test
  fun injectOnlineSubtitleAggiungeTracciaESenzaDuplicati() {
    val dispatcher = StandardTestDispatcher()
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    val sub1 = Subtitle(
      id = "os-100",
      url = "https://subs.test/it1.srt",
      lang = "ita",
      addonName = "OpenSubtitles v3",
      label = "Italian Track"
    )

    viewModel.injectOnlineSubtitle(sub1)
    assertEquals(1, viewModel.playbackState.value.subtitles.size)
    assertEquals("os-100", viewModel.playbackState.value.subtitles[0].id)

    // Iniezione dello stesso sottotitolo: non deve duplicare
    viewModel.injectOnlineSubtitle(sub1)
    assertEquals(1, viewModel.playbackState.value.subtitles.size)

    val sub2 = Subtitle(
      id = "os-200",
      url = "https://subs.test/en1.srt",
      lang = "eng",
      addonName = "OpenSubtitles v3",
      label = "English Track"
    )

    viewModel.injectOnlineSubtitle(sub2)
    assertEquals(2, viewModel.playbackState.value.subtitles.size)
  }

  @Test
  fun loadOnlineSubtitlesAggiornaStateFlowCorrettamente() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchFromStremioOpenSubtitles(type: String, id: String): List<Subtitle> {
        return listOf(
          Subtitle(id = "os-1", url = "https://subs.test/it.srt", lang = "ita", addonName = "OpenSubtitles v3")
        )
      }
    }

    val movie = MediaItem(id = "tt1234567", title = "Test Movie", synopsis = "", type = MediaType.FILM, videoUrl = "")
    viewModel.loadOnlineSubtitles(mediaItem = movie, episode = null)

    testScheduler.advanceUntilIdle()

    assertEquals(1, viewModel.onlineSubtitles.value.size)
    assertEquals("os-1", viewModel.onlineSubtitles.value[0].id)
    assertEquals(false, viewModel.isOnlineSubtitlesLoading.value)
  }
}
