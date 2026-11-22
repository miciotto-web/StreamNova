package com.example.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratto del New Episodes Center (motore puro). */
class NewEpisodesEngineTest {

  private val now = NewEpisodesEngine.parseAirDateMillis("2025-01-01")!!

  private fun series(
    season: Int? = 1,
    episode: Int? = 1,
    completedAt: Long? = null
  ) = TrackedSeries(
    mediaId = "tmdb_tv_1",
    title = "Serie",
    tmdbId = 1,
    lastWatchedSeason = season,
    lastWatchedEpisode = episode,
    completedAt = completedAt
  )

  private val episodes = listOf(
    AiredEpisodeInfo(1, 1, "Pilota", "2024-01-01"),
    AiredEpisodeInfo(1, 2, "Secondo", "2024-02-01"),
    AiredEpisodeInfo(1, 3, "Futuro", "2025-02-01"),
    AiredEpisodeInfo(2, 1, "Nuova stagione", "2024-06-01")
  )

  @Test
  fun `detects episodes after the last watched position`() {
    val update = NewEpisodesEngine.evaluate(series(1, 1), episodes, now)
    assertEquals(NewEpisodesStatus.NEW_EPISODES, update.status)
    assertEquals(2, update.newEpisodeCount)
    // Il primo episodio non visto è il più vecchio già andato in onda (S1E2).
    assertEquals(1, update.newestUnwatched!!.season)
    assertEquals(2, update.newestUnwatched!!.episode)
  }

  @Test
  fun `future episodes are never counted as new`() {
    val update = NewEpisodesEngine.evaluate(series(1, 2), episodes, now)
    // S1E3 è futura: l'ultimo andato in onda è S2E1.
    assertEquals(2, update.latestAired!!.season)
    assertEquals(1, update.latestAired!!.episode)
    assertEquals(1, update.newEpisodeCount)
  }

  @Test
  fun `up to date series reports no new episodes`() {
    val update = NewEpisodesEngine.evaluate(series(2, 1), episodes, now)
    assertEquals(NewEpisodesStatus.UP_TO_DATE, update.status)
    assertEquals(0, update.newEpisodeCount)
    assertNull(update.newestUnwatched)
    assertFalse(update.hasNewEpisodes)
  }

  @Test
  fun `series without progress is not started`() {
    val update = NewEpisodesEngine.evaluate(series(season = null, episode = null), episodes, now)
    assertEquals(NewEpisodesStatus.NOT_STARTED, update.status)
    assertEquals(0, update.newEpisodeCount)
  }

  @Test
  fun `missing episodes yield no data`() {
    val update = NewEpisodesEngine.evaluate(series(), emptyList(), now)
    assertEquals(NewEpisodesStatus.NO_DATA, update.status)
  }

  @Test
  fun `episodes without air date are ignored`() {
    val withUnknown = episodes + AiredEpisodeInfo(3, 1, "Senza data", null)
    val update = NewEpisodesEngine.evaluate(series(2, 1), withUnknown, now)
    // S3E1 non ha data: non è "andata in onda", l'ultima verificata resta S2E1.
    assertEquals(2, update.latestAired!!.season)
    assertEquals(NewEpisodesStatus.UP_TO_DATE, update.status)
  }

  @Test
  fun `numeric comparison is per season then episode`() {
    val episode = AiredEpisodeInfo(2, 1)
    assertTrue(NewEpisodesEngine.comparePositions(episode, 1, 10) > 0)
    assertTrue(NewEpisodesEngine.comparePositions(episode, 2, 1) == 0)
    assertTrue(NewEpisodesEngine.comparePositions(episode, 2, 2) < 0)
  }

  @Test
  fun `parses iso air dates strictly`() {
    assertEquals(0L, NewEpisodesEngine.parseAirDateMillis("1970-01-01"))
    assertNull(NewEpisodesEngine.parseAirDateMillis(null))
    assertNull(NewEpisodesEngine.parseAirDateMillis("domani"))
  }
}
