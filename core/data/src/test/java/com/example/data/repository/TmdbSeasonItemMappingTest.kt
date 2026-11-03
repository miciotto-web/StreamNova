package com.example.data.repository

import com.example.data.api.TmdbApiClient
import com.example.data.api.TmdbEpisodeDto
import com.example.data.api.TmdbSeasonDetailDto
import com.example.data.config.StreamNovaClientConfig
import com.example.data.config.StreamNovaConfig
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La modalità "strict" è quella del client Mobile: nessun titolo, sinossi o
 * runtime di comodo, nessun episodio sintetico in caso di rete assente. La TV
 * mantiene i fallback storici tramite la configurazione del client.
 */
class TmdbSeasonItemMappingTest {

  private lateinit var server: MockWebServer
  private lateinit var previousConfig: StreamNovaClientConfig

  @Before
  fun setUp() {
    previousConfig = StreamNovaConfig.current
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
    TmdbApiClient.overrideServiceBaseUrl(null)
    StreamNovaConfig.install(previousConfig)
  }

  private fun installClient(generateSyntheticEpisodes: Boolean) {
    StreamNovaConfig.install(
      StreamNovaClientConfig(
        appId = "test",
        versionName = "test",
        isDebug = true,
        tmdbApiKeyCandidates = listOf("test-api-key-1234567890"),
        generateSyntheticEpisodes = generateSyntheticEpisodes
      )
    )
  }

  private fun fallbackSeries() = MediaItem(
    id = "tmdb_tv_1",
    title = "Serie",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    durationMinutes = 55,
    rating = 8.5f,
    backdropUrl = "https://img.example/backdrop.jpg"
  )

  private fun seasonDto(
    title: String?,
    overview: String?,
    runtime: Int?,
    rating: Float?
  ) = TmdbSeasonDetailDto(
    id = 10,
    name = "Stagione 1",
    seasonNumber = 1,
    overview = "Panoramica stagione",
    airDate = "2024-09-01",
    episodes = listOf(
      TmdbEpisodeDto(
        id = 100,
        name = title,
        episodeNumber = 1,
        seasonNumber = 1,
        overview = overview,
        stillPath = null,
        airDate = "2024-09-02",
        voteAverage = rating,
        runtime = runtime
      )
    )
  )

  @Test
  fun laModalitaStrictNonInventaTitoloSinossiEDurata() {
    val season = with(MediaRepository) {
      seasonDto(title = null, overview = null, runtime = null, rating = null)
        .toSeasonItem(tvTmdbId = 1, fallbackSeries = fallbackSeries(), strictRealMetadata = true)
    }

    val episode = season.episodes.single()
    assertEquals("", episode.title)
    assertEquals("", episode.overview)
    assertEquals(0, episode.durationMinutes)
    assertEquals(0f, episode.rating)
    // L'artwork di fallback resta la copertina della serie, come sulla TV.
    assertEquals("https://img.example/backdrop.jpg", episode.stillUrl)
  }

  @Test
  fun laModalitaTvMantieneIFallbackStorici() {
    val season = with(MediaRepository) {
      seasonDto(title = null, overview = null, runtime = null, rating = null)
        .toSeasonItem(tvTmdbId = 1, fallbackSeries = fallbackSeries(), strictRealMetadata = false)
    }

    val episode = season.episodes.single()
    assertEquals("Episodio 1", episode.title)
    assertEquals("Nessuna sinossi disponibile per questo episodio.", episode.overview)
    assertEquals(55, episode.durationMinutes)
    assertEquals(8.5f, episode.rating)
  }

  @Test
  fun laModalitaStrictConservaITreValoriRealiDiTmdb() {
    val season = with(MediaRepository) {
      seasonDto(
        title = "Il primo episodio",
        overview = "Sinossi reale",
        runtime = 47,
        rating = 7.9f
      ).toSeasonItem(tvTmdbId = 1, fallbackSeries = fallbackSeries(), strictRealMetadata = true)
    }

    val episode = season.episodes.single()
    assertEquals("Il primo episodio", episode.title)
    assertEquals("Sinossi reale", episode.overview)
    assertEquals(47, episode.durationMinutes)
    assertEquals(7.9f, episode.rating)
  }

  @Test
  fun iMetadatiDellaStagioneSonoQuelliRestituitiDaTmdb() {
    val season = with(MediaRepository) {
      seasonDto("Episodio", "Sinossi", 50, 8f)
        .toSeasonItem(tvTmdbId = 1, fallbackSeries = null, strictRealMetadata = true)
    }

    assertEquals("Stagione 1", season.name)
    assertEquals("Panoramica stagione", season.overview)
    assertEquals("2024-09-01", season.airDate)
    assertEquals(1, season.seasonNumber)
  }

  @Test
  fun unaStagioneSenzaEpisodiRestaVuota() {
    val dto = TmdbSeasonDetailDto(
      id = 1,
      name = "Stagione 1",
      seasonNumber = 1,
      overview = "",
      episodes = emptyList()
    )
    val season = with(MediaRepository) {
      dto.toSeasonItem(tvTmdbId = 1, fallbackSeries = null, strictRealMetadata = true)
    }

    assertTrue(season.episodes.isEmpty())
  }

  @Test
  fun ilClientMobileRiceveLErroreRealeInveceDegliOttoEpisodiSintetici() {
    installClient(generateSyntheticEpisodes = false)
    TmdbApiClient.overrideServiceBaseUrl(server.url("/").toString())
    server.enqueue(MockResponse().setResponseCode(500))

    assertThrows(Exception::class.java) {
      runBlocking {
        MediaRepository.getSeasonDetails(
          tvTmdbId = 987_654,
          seasonNumber = 1,
          fallbackSeries = null
        )
      }
    }
  }

  @Test
  fun ilClientTvMantieneIlFallbackSinteticoQuandoLaReteFallisce() {
    installClient(generateSyntheticEpisodes = true)
    TmdbApiClient.overrideServiceBaseUrl(server.url("/").toString())
    server.enqueue(MockResponse().setResponseCode(500))

    val season = runBlocking {
      MediaRepository.getSeasonDetails(
        tvTmdbId = 987_655,
        seasonNumber = 1,
        fallbackSeries = null
      )
    }

    assertEquals(8, season.episodes.size)
    assertEquals("Episodio 1", season.episodes.first().title)
  }
}
