package com.example.data.remote.simkl

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing di `GET /sync/playback`, la sorgente documentata del "Continua a guardare".
 *
 * Il primo test usa **l'esempio reale della documentazione SIMKL** end-to-end
 * (Retrofit + Moshi + parser), così resta coperto anche il contratto di trasporto: la
 * risposta è un array e gli id esterni arrivano come stringhe.
 */
class SimklPlaybackParserTest {

  @Test
  fun laRispostaRealeDellaDocumentazioneDiventaDueSessioniRiprendibili() = runBlocking {
    val server = MockWebServer()
    server.start()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
          """
          [
            {
              "id": 123,
              "progress": 45.5,
              "paused_at": "2024-01-15T10:30:00.000Z",
              "type": "episode",
              "episode": {"season": 1, "episode": 5, "title": "Episode 5", "tvdb_season": 1, "tvdb_number": 5},
              "show": {"title": "Breaking Bad", "year": 2008,
                       "ids": {"simkl": 12345, "slug": "breaking-bad", "tmdb": "1429", "imdb": "tt0903747"}}
            },
            {
              "id": 124,
              "progress": 75,
              "paused_at": "2024-01-15T11:15:00.000Z",
              "type": "movie",
              "movie": {"title": "Inception", "year": 2010,
                        "ids": {"simkl": 67890, "slug": "inception", "tmdb": "27205", "imdb": "tt1375666"}}
            }
          ]
          """.trimIndent()
        )
    )
    val api = SimklApiClient.createApi(
      client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
      baseUrl = server.url("/").toString()
    )

    val response = api.getPlaybackSessions("client", "Bearer token")
    val entries = SimklPlaybackParser.parse(response.body())

    assertEquals(2, entries.size)
    val episode = entries[0]
    assertEquals(SimklPlaybackKind.EPISODE, episode.kind)
    assertEquals(45.5, episode.progressPercent, 0.0001)
    assertEquals(1, episode.season)
    assertEquals(5, episode.episode)
    assertEquals("Episode 5", episode.episodeTitle)
    assertEquals("Breaking Bad", episode.title)
    assertEquals(2008, episode.year)
    assertEquals(1429, episode.tmdbId)
    assertEquals("tt0903747", episode.imdbId)
    assertEquals(123L, episode.playbackId)
    // 2024-01-15T10:30:00Z
    assertEquals(1_705_314_600_000L, episode.pausedAtEpochMs)
    assertTrue(SimklPlaybackParser.isResumable(episode))

    val movie = entries[1]
    assertEquals(SimklPlaybackKind.MOVIE, movie.kind)
    assertEquals(75.0, movie.progressPercent, 0.0001)
    assertEquals(27205, movie.tmdbId)
    assertNull(movie.season)
    assertTrue(SimklPlaybackParser.isResumable(movie))

    server.shutdown()
  }

  @Test
  fun accettaEntrambeLeChiaviDelNumeroEpisodioEPercentualiIntere() {
    val entry = SimklPlaybackParser.parseSession(
      mapOf(
        "id" to 7L,
        "progress" to 42, // percentuale intera, non Double
        "paused_at" to "2026-04-11T10:36:01Z",
        "type" to "episode",
        // Schema OpenAPI: la chiave è `number`.
        "episode" to mapOf("season" to 2, "number" to 3, "title" to "Chapter Three"),
        "show" to mapOf("title" to "Stranger Things", "year" to 2016, "ids" to mapOf("tmdb" to "66732"))
      )
    )

    assertEquals(42.0, entry!!.progressPercent, 0.0001)
    assertEquals(2, entry.season)
    assertEquals(3, entry.episode)
    assertEquals(66732, entry.tmdbId)
    // 2026-04-11T10:36:01Z
    assertEquals(1_775_903_761_000L, entry.pausedAtEpochMs)
  }

  @Test
  fun gliAnimeSonoRiconosciutiDalContenitoreAnime() {
    val entry = SimklPlaybackParser.parseSession(
      mapOf(
        "progress" to 12.0,
        "type" to "episode",
        "episode" to mapOf("season" to 1, "number" to 9),
        "anime" to mapOf(
          "title" to "Attack on Titan",
          "year" to 2013,
          "anime_type" to "tv",
          "ids" to mapOf("tmdb" to "1429", "mal" to "16498")
        )
      )
    )

    assertEquals(true, entry?.isAnime)
    assertEquals(SimklPlaybackKind.EPISODE, entry?.kind)
    assertEquals("Attack on Titan", entry?.title)
  }

  @Test
  fun unaSessioneSenzaTitoloOMancanteDiContenitoreVieneScartata() {
    assertNull(SimklPlaybackParser.parseSession(mapOf("progress" to 10.0, "type" to "movie")))
    assertNull(
      SimklPlaybackParser.parseSession(
        mapOf("progress" to 10.0, "movie" to mapOf("year" to 2010))
      )
    )
  }

  @Test
  fun unEpisodioSenzaNumeroNonERiprendibile() {
    val entry = SimklPlaybackParser.parseSession(
      mapOf(
        "progress" to 30.0,
        "type" to "episode",
        "episode" to mapOf("title" to "Senza numeri"),
        "show" to mapOf("title" to "Serie", "ids" to mapOf("tmdb" to "1"))
      )
    )

    assertEquals(SimklPlaybackKind.EPISODE, entry?.kind)
    assertNull(entry?.season)
    assertFalse(SimklPlaybackParser.isResumable(entry!!))
  }

  @Test
  fun unaPausaANessunaPercentualeNonERiprendibile() {
    val entry = SimklPlaybackParser.parseSession(
      mapOf(
        "progress" to 0.0,
        "type" to "movie",
        "movie" to mapOf("title" to "Film", "ids" to mapOf("tmdb" to "5"))
      )
    )

    assertFalse(SimklPlaybackParser.isResumable(entry!!))
  }

  @Test
  fun leDateIsoSonoLetteConOSenzaMillisecondiEMaiInventate() {
    assertEquals(1_705_314_600_000L, SimklPlaybackParser.parseIsoUtcToEpochMs("2024-01-15T10:30:00Z"))
    assertEquals(1_705_314_600_000L, SimklPlaybackParser.parseIsoUtcToEpochMs("2024-01-15T10:30:00.000Z"))
    // Frazione più lunga di 3 cifre: si tronca senza perdere il resto.
    assertEquals(
      SimklPlaybackParser.parseIsoUtcToEpochMs("2024-01-15T10:30:00.123Z"),
      SimklPlaybackParser.parseIsoUtcToEpochMs("2024-01-15T10:30:00.123456Z")
    )
    assertNull(SimklPlaybackParser.parseIsoUtcToEpochMs("non una data"))
    assertNull(SimklPlaybackParser.parseIsoUtcToEpochMs(""))
    assertNull(SimklPlaybackParser.parseIsoUtcToEpochMs(null))
  }
}
