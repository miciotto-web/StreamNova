package com.example.ui.viewmodel

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SeasonEpisodesUiState
import org.junit.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceDialogMediaAlignmentTest {

  @Test
  fun sourceDialogMediaIsImmediatelySetUponOpenStreamForMedia() {
    val dispatcher = StandardTestDispatcher()
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    val series = MediaItem(
      id = "series_brothers",
      title = "Brothers",
      synopsis = "Brothers synopsis",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 12345
    )
    val ep = Episode(
      id = "ep_1",
      title = "Pilot",
      synopsis = "",
      seasonNumber = 1,
      episodeNumber = 1,
      durationMinutes = 45,
      videoUrl = ""
    )

    // Inizialmente null
    assertNull(viewModel.sourceDialogMedia.value)

    viewModel.openStreamForMedia(series, ep)

    // Atomico e immediato: appena invocata, prima di qualsiasi risoluzione
    val target = viewModel.sourceDialogMedia.value
    assertNotNull(target)
    assertEquals("Brothers", target?.media?.title)
    assertEquals(1, target?.episode?.seasonNumber)
    assertEquals(1, target?.episode?.episodeNumber)
  }

  @Test
  fun sourceDialogMediaOverwritesPreviousMediaAtomically() {
    val dispatcher = StandardTestDispatcher()
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    val cinaDoc = MediaItem(
      id = "cina_doc",
      title = "Cina: meraviglie antiche dall'alto",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 9999
    )
    val brothers = MediaItem(
      id = "series_brothers",
      title = "Brothers",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 12345
    )

    viewModel.openStreamForMedia(cinaDoc, null)
    assertEquals("Cina: meraviglie antiche dall'alto", viewModel.sourceDialogMedia.value?.media?.title)

    // Nuova chiamata per Brothers: sovrascrittura atomica immediata
    viewModel.openStreamForMedia(brothers, null)
    assertEquals("Brothers", viewModel.sourceDialogMedia.value?.media?.title)
  }

  @Test
  fun dismissSourceDialogAndClosePlayerResetSourceDialogMedia() {
    val dispatcher = StandardTestDispatcher()
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    val series = MediaItem(
      id = "series_brothers",
      title = "Brothers",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 12345
    )

    viewModel.openStreamForMedia(series, null)
    assertNotNull(viewModel.sourceDialogMedia.value)

    // Dismiss dialog azzera il target
    viewModel.dismissSourceDialog()
    assertNull(viewModel.sourceDialogMedia.value)
    assertFalse(viewModel.showSourceDialog.value)

    viewModel.openStreamForMedia(series, null)
    assertNotNull(viewModel.sourceDialogMedia.value)

    // Close player azzera il target
    viewModel.closePlayer()
    assertNull(viewModel.sourceDialogMedia.value)
    assertFalse(viewModel.showSourceDialog.value)

    viewModel.openStreamForMedia(series, null)
    assertNotNull(viewModel.sourceDialogMedia.value)

    // Reset stream state azzera il target
    viewModel.resetStreamState()
    assertNull(viewModel.sourceDialogMedia.value)
    assertFalse(viewModel.showSourceDialog.value)
  }

  @Test
  fun loadEpisodesForSeasonTagsUiStateWithCorrectMediaId() {
    val dispatcher = StandardTestDispatcher()
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    val series = MediaItem(
      id = "series_brothers",
      title = "Brothers",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 12345
    )

    viewModel.loadEpisodesForSeason(series, 1)

    val state = viewModel.seasonEpisodesUiState.value
    assertTrue("Stato deve essere Loading", state is SeasonEpisodesUiState.Loading)
    assertEquals("series_brothers", (state as SeasonEpisodesUiState.Loading).seriesId)
    assertEquals(1, state.seasonNumber)
  }

  @Test
  fun backFromDetailResetsSeasonEpisodesStateAndJob() {
    val dispatcher = StandardTestDispatcher()
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    val series = MediaItem(
      id = "series_brothers",
      title = "Brothers",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 12345
    )

    viewModel.loadEpisodesForSeason(series, 1)
    assertTrue(viewModel.seasonEpisodesUiState.value is SeasonEpisodesUiState.Loading)

    viewModel.backFromDetail()
    assertEquals(SeasonEpisodesUiState.Idle, viewModel.seasonEpisodesUiState.value)
    assertNull(viewModel.selectedMedia.value)
  }

  @Test
  fun episodeClickPassesCurrentMediaAndEpisodeAccurately() {
    val dispatcher = StandardTestDispatcher()
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)

    val currentMedia = MediaItem(
      id = "series_the_bear",
      title = "The Bear",
      synopsis = "Cucina a Chicago",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 136283
    )

    val episode = Episode(
      id = "series_the_bear_s2e6",
      title = "Fishes",
      synopsis = "Cena di Natale",
      seasonNumber = 2,
      episodeNumber = 6,
      durationMinutes = 66,
      videoUrl = ""
    )

    viewModel.openStreamForMedia(currentMedia, episode)

    val target = viewModel.sourceDialogMedia.value
    assertNotNull(target)
    assertEquals("series_the_bear", target?.media?.id)
    assertEquals("The Bear", target?.media?.title)
    assertEquals(2, target?.episode?.seasonNumber)
    assertEquals(6, target?.episode?.episodeNumber)
    assertEquals("Fishes", target?.episode?.title)
  }
}
