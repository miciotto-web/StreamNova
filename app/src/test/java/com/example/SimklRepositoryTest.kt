package com.example

import com.example.data.repository.SimklEpisodePayload
import com.example.data.repository.SimklMediaPayload
import com.example.data.repository.SimklRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklRepositoryTest {
  @Test
  fun moviePayloadUsesIdsAndClampsProgress() {
    val payload = SimklMediaPayload("Film", 2024, 123, "tt1234567").toPayload(140.0)
    assertEquals(100.0, payload["progress"])
    val movie = payload["movie"] as Map<*, *>
    assertEquals("Film", movie["title"])
    val ids = movie["ids"] as Map<*, *>
    assertEquals("123", ids["tmdb"])
    assertEquals("tt1234567", ids["imdb"])
  }

  @Test
  fun episodePayloadAndHistoryUseSimklShowShape() {
    val media = SimklMediaPayload("Show", 2020, 77, null, SimklEpisodePayload(2, 4))
    val payload = media.toPayload(42.0)
    assertEquals(42.0, payload["progress"])
    assertTrue(payload.containsKey("show"))
    assertEquals(mapOf("season" to 2, "episode" to 4), payload["episode"])
    assertEquals(
      listOf(mapOf("number" to 2, "episodes" to listOf(mapOf("number" to 4)))),
      media.toHistoryItem()["seasons"]
    )
  }

  @Test
  fun parsesWatchedEpisodesForMatchingShowAndSeasonOnly() {
    val body = mapOf("shows" to listOf(
      mapOf(
        "show" to mapOf("ids" to mapOf("tmdb" to 42)),
        "seasons" to listOf(
          mapOf("number" to 1, "episodes" to listOf(mapOf("number" to 1, "watched_at" to "2024-01-01"))),
          mapOf("number" to 2, "episodes" to listOf(
            mapOf("number" to 2, "watched_at" to "2024-02-01"),
            mapOf("number" to 3, "watched_at" to null),
            mapOf("number" to 4, "watched_at" to "2024-03-01")
          ))
        )
      ),
      mapOf("show" to mapOf("ids" to mapOf("tmdb" to 99)), "seasons" to emptyList<Any>())
    ))
    assertEquals(setOf(2, 4), SimklRepository.parseWatchedEpisodes(body, 42, 2))
    assertTrue(SimklRepository.parseWatchedEpisodes(body, 42, 3).isEmpty())
  }
}
