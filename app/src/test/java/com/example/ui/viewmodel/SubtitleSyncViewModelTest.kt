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
      var resolveLinksOnDemandRequested: Boolean? = null

      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: com.example.data.model.Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ): List<Subtitle> {
        resolveLinksOnDemandRequested = resolveLinksOnDemand
        return listOf(
          Subtitle(id = "os-api-1", url = "https://api.test/it.srt", lang = "ita", addonName = "OpenSubtitles v3")
        )
      }

      override suspend fun fetchFromStremioOpenSubtitles(type: String, id: String): List<Subtitle> {
        return listOf(
          Subtitle(id = "os-1", url = "https://subs.test/it.srt", lang = "ita", addonName = "OpenSubtitles v3")
        )
      }
    }

    val movie = MediaItem(id = "tt1234567", title = "Test Movie", synopsis = "", type = MediaType.FILM, videoUrl = "")
    viewModel.loadOnlineSubtitles(mediaItem = movie, episode = null)

    testScheduler.advanceUntilIdle()

    // La ricerca passa sia dall'API ufficiale sia dall'addon: i risultati sono uniti.
    assertEquals(2, viewModel.onlineSubtitles.value.size)
    assertEquals(false, viewModel.isOnlineSubtitlesLoading.value)
  }

  @Test
  fun loadOnlineSubtitlesSenzaImdbInterrogaComunqueApiUfficiale() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    var officialApiCalled = false
    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: com.example.data.model.Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ): List<Subtitle> {
        officialApiCalled = true
        assert(!resolveLinksOnDemand) { "La sezione online non deve consumare quota download in ricerca" }
        return listOf(
          Subtitle(id = "os-file-42", url = "", lang = "en", addonName = "OpenSubtitles v3", fileId = 42L)
        )
      }
    }

    // Media SENZA identificativo IMDb (solo TMDB): il vecchio percorso addon rispondeva
    // subito lista vuota; ora l'API ufficiale viene comunque interrogata.
    val movie = MediaItem(
      id = "tmdb-603",
      title = "Test Movie",
      synopsis = "",
      type = MediaType.FILM,
      videoUrl = "",
      tmdbId = 603
    )
    viewModel.loadOnlineSubtitles(mediaItem = movie, episode = null)

    testScheduler.advanceUntilIdle()

    assertEquals(true, officialApiCalled)
    assertEquals(1, viewModel.onlineSubtitles.value.size)
    assertEquals(42L, viewModel.onlineSubtitles.value[0].fileId)
  }

  @Test
  fun loadOnlineSubtitlesUnisceSenzaDuplicatiURL() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: com.example.data.model.Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ): List<Subtitle> = listOf(
        Subtitle(id = "os-a", url = "https://sub.test/shared.srt", lang = "ita", addonName = "OpenSubtitles v3")
      )

      override suspend fun fetchFromStremioOpenSubtitles(type: String, id: String): List<Subtitle> {
        // Stesso URL dell'API ufficiale: i due risultati sono lo stesso sottotitolo.
        return listOf(
          Subtitle(id = "os-b", url = "https://sub.test/shared.srt", lang = "ita", addonName = "OpenSubtitles v3"),
          Subtitle(id = "os-c", url = "https://sub.test/extra.srt", lang = "eng", addonName = "OpenSubtitles v3")
        )
      }
    }

    val movie = MediaItem(id = "tt1234567", title = "Test Movie", synopsis = "", type = MediaType.FILM, videoUrl = "")
    viewModel.loadOnlineSubtitles(mediaItem = movie, episode = null)

    testScheduler.advanceUntilIdle()

    assertEquals(2, viewModel.onlineSubtitles.value.size)
    assertEquals("os-a", viewModel.onlineSubtitles.value[0].id)
    assertEquals("os-c", viewModel.onlineSubtitles.value[1].id)
  }
}
