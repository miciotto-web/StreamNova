package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fine stagione ≠ fine serie.
 *
 * La rimozione automatica da «Continua a guardare» è ammessa solo quando TUTTI i
 * dati provano che non esiste più nulla da vedere. Qualunque informazione mancante
 * o incoerente conserva la serie.
 */
class SeriesCompletionPolicyTest {

  @Test
  fun `ultimo episodio della stagione finale con serie conclusa`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 5,
      episodeNumber = 10,
      facts = facts(seasons = 5, episodes = mapOf(1 to 10, 4 to 10, 5 to 10), status = "Ended")
    )

    assertEquals(SeriesCompletionDecision.Completed, decision)
  }

  @Test
  fun `fine di una stagione intermedia non conclude la serie`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 4,
      episodeNumber = 10,
      facts = facts(seasons = 5, episodes = mapOf(4 to 10, 5 to 10), status = "Ended")
    )

    assertIncomplete(decision)
  }

  @Test
  fun `esistono episodi successivi nella stagione finale`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 5,
      episodeNumber = 4,
      facts = facts(seasons = 5, episodes = mapOf(5 to 10), status = "Ended")
    )

    assertIncomplete(decision)
  }

  @Test
  fun `serie ancora in corso resta in continua a guardare`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 5,
      episodeNumber = 10,
      facts = facts(seasons = 5, episodes = mapOf(5 to 10), status = "Returning Series")
    )

    assertIncomplete(decision)
  }

  @Test
  fun `stagione successiva solo annunciata`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 5,
      episodeNumber = 10,
      facts = facts(seasons = 6, episodes = mapOf(1 to 10, 5 to 10), status = "Ended")
    )

    assertIncomplete(decision)
  }

  @Test
  fun `conteggio episodi della stagione finale non disponibile`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 6,
      episodeNumber = 1,
      facts = facts(seasons = 6, episodes = mapOf(1 to 10, 5 to 10), status = "Ended")
    )

    assertIncomplete(decision)
  }

  @Test
  fun `metadati assenti non eliminano la serie`() {
    assertIncomplete(SeriesCompletionPolicy.decide(5, 10, facts = null))
  }

  @Test
  fun `conteggi stagioni assenti non eliminano la serie`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 5,
      episodeNumber = 10,
      facts = SeriesCompletionFacts(seasonsCount = null, seasonEpisodeCounts = emptyMap(), status = "Ended")
    )
    assertIncomplete(decision)
  }

  @Test
  fun `stato sconosciuto non elimina la serie`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 5,
      episodeNumber = 10,
      facts = facts(seasons = 5, episodes = mapOf(5 to 10), status = null)
    )
    assertIncomplete(decision)
  }

  @Test
  fun `posizione oltre gli episodi noti non elimina la serie`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 5,
      episodeNumber = 12,
      facts = facts(seasons = 5, episodes = mapOf(5 to 10), status = "Ended")
    )
    assertIncomplete(decision)
  }

  @Test
  fun `serie cancellata senza altri episodi e conclusa`() {
    val decision = SeriesCompletionPolicy.decide(
      seasonNumber = 2,
      episodeNumber = 8,
      facts = facts(seasons = 2, episodes = mapOf(2 to 8), status = "Canceled")
    )
    assertEquals(SeriesCompletionDecision.Completed, decision)
  }

  @Test
  fun `posizione non valida non conclude la serie`() {
    assertIncomplete(SeriesCompletionPolicy.decide(null, 10, facts(1, mapOf(1 to 10), "Ended")))
    assertIncomplete(SeriesCompletionPolicy.decide(1, null, facts(1, mapOf(1 to 10), "Ended")))
    assertIncomplete(SeriesCompletionPolicy.decide(0, 0, facts(1, mapOf(1 to 10), "Ended")))
  }

  private fun facts(
    seasons: Int?,
    episodes: Map<Int, Int>,
    status: String?
  ) = SeriesCompletionFacts(
    seasonsCount = seasons,
    seasonEpisodeCounts = episodes,
    status = status
  )

  private fun assertIncomplete(decision: SeriesCompletionDecision) {
    assertTrue(
      "atteso Incomplete, trovato $decision",
      decision is SeriesCompletionDecision.Incomplete
    )
  }
}
