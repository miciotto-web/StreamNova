package com.example

import com.example.data.api.TmdbApiClient
import com.example.data.api.TmdbEpisodeDto
import com.example.data.api.TmdbSeasonDetailDto
import com.example.data.local.MediaCacheMapper
import com.example.data.model.MediaType
import com.example.data.model.MediaItem
import com.example.data.model.SeasonEpisodesUiState
import com.example.data.model.toEpisode
import com.example.data.repository.MediaRepository
import com.example.ui.screens.DetailTab
import com.example.ui.viewmodel.StreamNovaViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SeasonEpisodePickerTest {

  @Test
  fun testTmdbStillUrlHelper() {
    val url = TmdbApiClient.stillUrl("/test_still_path.jpg", "w300")
    assertEquals("https://image.tmdb.org/t/p/w300/test_still_path.jpg", url)

    val fullUrl = TmdbApiClient.stillUrl("https://example.com/still.jpg")
    assertEquals("https://example.com/still.jpg", fullUrl)

    val nullUrl = TmdbApiClient.stillUrl(null)
    assertEquals(null, nullUrl)
  }

  @Test
  fun testSeasonDetailSerializationAndDeserialization() {
    val sampleEpisode = TmdbEpisodeDto(
      id = 101,
      name = "Benvenuti nel gioco",
      episodeNumber = 1,
      seasonNumber = 1,
      overview = "Le sorelle orfane Vi e Powder causano scompiglio...",
      stillPath = "/arcane_s1e1.jpg",
      airDate = "2021-11-06",
      voteAverage = 8.8f,
      runtime = 43
    )

    val sampleSeason = TmdbSeasonDetailDto(
      id = 13579,
      name = "Stagione 1",
      seasonNumber = 1,
      overview = "La prima stagione di Arcane",
      episodes = listOf(sampleEpisode)
    )

    val json = MediaCacheMapper.serializeSeasonDetail(sampleSeason)
    assertTrue("JSON should contain episode name", json.contains("Benvenuti nel gioco"))
    assertTrue("JSON should contain episode_number", json.contains("episode_number"))
    assertTrue("JSON should contain season_number", json.contains("season_number"))

    val deserialized = MediaCacheMapper.deserializeSeasonDetail(json)
    assertNotNull("Deserialized season should not be null", deserialized)
    assertEquals(1, deserialized?.seasonNumber)
    assertEquals(1, deserialized?.episodes?.size)
    assertEquals(101, deserialized?.episodes?.first()?.id)
    assertEquals("Benvenuti nel gioco", deserialized?.episodes?.first()?.name)
    assertEquals(8.8f, deserialized?.episodes?.first()?.voteAverage)
  }

  @Test
  fun testMovieHidesSeasonPicker() {
    val movie = MediaItem(
      id = "movie_godfather",
      title = "Il padrino - Parte II",
      synopsis = "La continuazione della saga dei Corleone.",
      videoUrl = "https://example.com/stream.m3u8",
      type = MediaType.FILM,
      tmdbId = 240
    )

    // Verify tabs computation for movie
    val movieTabs = if (movie.type == MediaType.SERIE_TV) {
      listOf(DetailTab.EPISODI, DetailTab.CONSIGLIATI, DetailTab.DETTAGLI)
    } else {
      listOf(DetailTab.CONSIGLIATI, DetailTab.DETTAGLI)
    }

    assertFalse("Movie tabs must NOT contain DetailTab.EPISODI", movieTabs.contains(DetailTab.EPISODI))
    assertEquals(2, movieTabs.size)
  }

  @Test
  fun testTvSeriesShowsSeasonPicker() {
    val tvSeries = MediaItem(
      id = "tv_arcane",
      title = "Arcane",
      synopsis = "Ambientata a Piltover e Zaun...",
      videoUrl = "https://example.com/stream.m3u8",
      type = MediaType.SERIE_TV,
      tmdbId = 94605,
      seasonsCount = 2
    )

    // Verify tabs computation for TV Series
    val tvTabs = if (tvSeries.type == MediaType.SERIE_TV) {
      listOf(DetailTab.EPISODI, DetailTab.CONSIGLIATI, DetailTab.DETTAGLI)
    } else {
      listOf(DetailTab.CONSIGLIATI, DetailTab.DETTAGLI)
    }

    assertTrue("TV Series tabs MUST contain DetailTab.EPISODI", tvTabs.contains(DetailTab.EPISODI))
    assertEquals(DetailTab.EPISODI, tvTabs.first())
    assertEquals(3, tvTabs.size)
  }

  @Test
  fun testGetSeasonDetailsFallbackAndMapping() = runBlocking {
    val tvSeries = MediaItem(
      id = "tv_test",
      title = "Serie TV Demo",
      synopsis = "Sinossi demo serie TV.",
      videoUrl = "https://example.com/stream.m3u8",
      type = MediaType.SERIE_TV,
      tmdbId = 999999, // Non-existent on network to trigger fallback
      backdropUrl = "https://image.tmdb.org/t/p/w780/fallback.jpg"
    )

    val season1 = MediaRepository.getSeasonDetails(
      tvTmdbId = 999999,
      seasonNumber = 1,
      fallbackSeries = tvSeries
    )

    assertEquals(1, season1.seasonNumber)
    assertTrue("Should have fallback episodes", season1.episodes.isNotEmpty())
    val ep1 = season1.episodes.first()
    assertEquals(1, ep1.episodeNumber)
    assertEquals(1, ep1.seasonNumber)
    assertEquals("E01", ep1.episodeBadge)
    assertEquals("https://image.tmdb.org/t/p/w780/fallback.jpg", ep1.stillUrl)

    // Test conversion to Episode model
    val domainEpisode = ep1.toEpisode(tvSeries.id)
    assertEquals(1, domainEpisode.episodeNumber)
    assertEquals(1, domainEpisode.seasonNumber)
    assertEquals("https://image.tmdb.org/t/p/w780/fallback.jpg", domainEpisode.thumbnailUrl)
  }

  @Test
  fun testRealTmdbArcaneSeasonsLoading() = runBlocking {
    // Arcane TMDB ID: 94605
    val season1 = MediaRepository.getSeasonDetails(tvTmdbId = 94605, seasonNumber = 1)
    assertEquals(1, season1.seasonNumber)
    assertTrue("Season 1 of Arcane should have episodes", season1.episodes.isNotEmpty())
    val s1e1 = season1.episodes.first()
    assertEquals(1, s1e1.episodeNumber)
    assertNotNull("Episode title should not be null", s1e1.title)
    assertTrue("Episode should have stillUrl or fallback", s1e1.stillUrl != null)

    // Load Season 2 of Arcane
    val season2 = MediaRepository.getSeasonDetails(tvTmdbId = 94605, seasonNumber = 2)
    assertEquals(2, season2.seasonNumber)
    assertTrue("Season 2 of Arcane should have episodes", season2.episodes.isNotEmpty())
    assertEquals(2, season2.episodes.first().seasonNumber)

    // Verify cache retrieval for Season 1
    val season1Cached = MediaRepository.getSeasonDetails(tvTmdbId = 94605, seasonNumber = 1)
    assertEquals(season1.episodes.size, season1Cached.episodes.size)
  }

  @Test
  fun testViewModelSeasonStateFlows() {
    val viewModel = StreamNovaViewModel()

    val tvSeries = MediaItem(
      id = "tv_arcane",
      title = "Arcane",
      synopsis = "Ambientata a Piltover e Zaun...",
      videoUrl = "https://example.com/stream.m3u8",
      type = MediaType.SERIE_TV,
      tmdbId = 94605,
      seasonsCount = 2
    )

    // Opening a TV Series auto-loads Season 1
    viewModel.openDetail(tvSeries)
    assertEquals(1, viewModel.selectedSeasonNumber.value)

    // User selects Season 2
    viewModel.selectSeason(94605, 2, tvSeries)
    assertEquals(2, viewModel.selectedSeasonNumber.value)

    // Opening a Movie sets season state to Idle
    val movie = MediaItem(
      id = "movie_godfather",
      title = "Il padrino - Parte II",
      synopsis = "La saga Corleone.",
      videoUrl = "https://example.com/stream.m3u8",
      type = MediaType.FILM,
      tmdbId = 240
    )
    viewModel.openDetail(movie)
    assertEquals(SeasonEpisodesUiState.Idle, viewModel.seasonEpisodesUiState.value)
  }

  @Test
  fun testTmdbTvDetailSeasonsAndEpisodeCountMapping() {
    val sampleTvJson = """
      {
        "id": 1418,
        "name": "The Big Bang Theory",
        "number_of_seasons": 12,
        "number_of_episodes": 279,
        "seasons": [
          {
            "id": 3623,
            "season_number": 0,
            "episode_count": 5,
            "name": "Specials"
          },
          {
            "id": 3624,
            "season_number": 1,
            "episode_count": 17,
            "name": "Stagione 1"
          },
          {
            "id": 3625,
            "season_number": 2,
            "episode_count": 23,
            "name": "Stagione 2"
          },
          {
            "id": 3626,
            "season_number": 3,
            "episode_count": 23,
            "name": "Stagione 3"
          }
        ]
      }
    """.trimIndent()

    val dto = MediaCacheMapper.deserializeTvDetail(sampleTvJson)
    assertNotNull(dto)
    assertEquals(4, dto!!.seasons?.size)

    val s1 = dto.seasons?.find { it.seasonNumber == 1 }
    assertNotNull(s1)
    assertEquals(17, s1?.episodeCount)

    val s2 = dto.seasons?.find { it.seasonNumber == 2 }
    assertNotNull(s2)
    assertEquals(23, s2?.episodeCount)
  }
}

