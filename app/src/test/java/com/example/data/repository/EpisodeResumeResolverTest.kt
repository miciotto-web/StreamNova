package com.example.data.repository

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ripresa dell'episodio corretto da «Continua a guardare».
 *
 * Il caso che ha originato il difetto: l'ultima visione è S5E6 ma la lista locale
 * della serie contiene solo le prime stagioni (episodi generati/troncati o non ancora
 * caricati da TMDB). Il vecchio fallback al primo episodio faceva partire S1E1.
 */
class EpisodeResumeResolverTest {

  @Test
  fun `the big bang theory S5E6 non viene sostituito da S1E1`() {
    val media = tbbt(
      lastWatchedSeason = 5,
      lastWatchedEpisode = 6,
      // Lista locale troncata alle prime 3 stagioni, come generate dal catalogo.
      episodes = MediaRepository.generateEpisodesForTv(1418, "The Big Bang Theory", null, null, 12)
    )

    val resolved = EpisodeResumeResolver.resolve(
      media = media,
      savedProgressMs = 17 * 60_000L + 32_000L,
      savedDurationMs = 22 * 60_000L
    )

    assertEquals("stagione salvata", 5, resolved?.seasonNumber)
    assertEquals("episodio salvato", 6, resolved?.episodeNumber)
    assertEquals("identità sintetica coerente", "${media.id}_s5e6", resolved?.id)
    assertEquals("posizione di S5E6", 17 * 60_000L + 32_000L, resolved?.currentProgressMs)
    assertEquals("durata di S5E6", 22 * 60_000L, resolved?.totalDurationMs)
    assertEquals(media.effectiveImdbId, resolved?.seriesImdbId)
  }

  @Test
  fun `episodio presente nella lista conserva titolo e identita`() {
    val exact = episode(5, 6).copy(
      id = "ep-s5e6",
      title = "La proposta",
      durationMinutes = 21,
      totalDurationMs = 21 * 60_000L
    )
    val media = tbbt(
      lastWatchedSeason = 5,
      lastWatchedEpisode = 6,
      totalDurationMs = 21 * 60_000L,
      episodes = listOf(episode(1, 1), exact)
    )

    val resolved = EpisodeResumeResolver.resolve(media)

    assertEquals("ep-s5e6", resolved?.id)
    assertEquals("La proposta", resolved?.title)
    assertEquals(21 * 60_000L, resolved?.totalDurationMs)
  }

  @Test
  fun `episodio scelto esplicitamente ha priorita sullo stato salvato`() {
    val media = tbbt(lastWatchedSeason = 5, lastWatchedEpisode = 6)

    val resolved = EpisodeResumeResolver.resolve(
      media = media,
      requestedSeason = 2,
      requestedEpisode = 3
    )

    assertEquals(2, resolved?.seasonNumber)
    assertEquals(3, resolved?.episodeNumber)
  }

  @Test
  fun `episodio esplicito diverso non eredita posizione e durata dell'ultima visione`() {
    val media = tbbt(
      lastWatchedSeason = 5,
      lastWatchedEpisode = 6,
      totalDurationMs = 22 * 60_000L
    )

    val resolved = EpisodeResumeResolver.resolveWithLookup(
      media = media,
      requestedSeason = 1,
      requestedEpisode = 1,
      progressOf = { season, episode -> if (season == 5 && episode == 6) 17 * 60_000L else 12 * 60_000L },
      durationOf = { season, episode -> if (season == 5 && episode == 6) 22 * 60_000L else 21 * 60_000L }
    )

    assertEquals(1, resolved?.seasonNumber)
    assertEquals(1, resolved?.episodeNumber)
    assertEquals("posizione della puntata scelta, non di S5E6", 12 * 60_000L, resolved?.currentProgressMs)
    assertEquals(21 * 60_000L, resolved?.totalDurationMs)
  }

  @Test
  fun `stagione speciale 0 esplicitamente scelta resta valida`() {
    val media = tbbt(lastWatchedSeason = 1, lastWatchedEpisode = 1)

    val resolved = EpisodeResumeResolver.resolve(
      media = media,
      requestedSeason = 0,
      requestedEpisode = 3
    )

    assertEquals(0, resolved?.seasonNumber)
    assertEquals(3, resolved?.episodeNumber)
  }

  @Test
  fun `speciale salvato in stagione 0 viene ripreso`() {
    val media = tbbt(lastWatchedSeason = 0, lastWatchedEpisode = 2)

    val resolved = EpisodeResumeResolver.resolve(media)

    assertEquals(0, resolved?.seasonNumber)
    assertEquals(2, resolved?.episodeNumber)
  }

  @Test
  fun `progresso salvato viene applicato all'episodio risolto`() {
    val media = tbbt(lastWatchedSeason = 5, lastWatchedEpisode = 6)

    val resolved = EpisodeResumeResolver.resolve(media, savedProgressMs = 22 * 60_000L)

    assertEquals(22 * 60_000L, resolved?.currentProgressMs)
  }

  @Test
  fun `record legacy senza episode_progress usa la posizione aggregata del titolo`() {
    val media = tbbt(
      lastWatchedSeason = 5,
      lastWatchedEpisode = 6,
      currentProgressMs = 9 * 60_000L,
      totalDurationMs = 22 * 60_000L
    )

    val resolved = EpisodeResumeResolver.resolve(media)

    assertEquals(5, resolved?.seasonNumber)
    assertEquals(6, resolved?.episodeNumber)
    assertEquals(9 * 60_000L, resolved?.currentProgressMs)
    assertEquals(22 * 60_000L, resolved?.totalDurationMs)
  }

  @Test
  fun `serie mai avviata parte dal primo episodio disponibile`() {
    val media = tbbt(
      lastWatchedSeason = null,
      lastWatchedEpisode = null,
      episodes = listOf(episode(2, 1), episode(1, 2), episode(1, 1))
    )

    val resolved = EpisodeResumeResolver.resolve(media)

    assertEquals(1, resolved?.seasonNumber)
    assertEquals(1, resolved?.episodeNumber)
    assertEquals("nessun progresso inventato", 0L, resolved?.currentProgressMs)
  }

  @Test
  fun `serie mai avviata senza episodi crea S1E1`() {
    val resolved = EpisodeResumeResolver.resolve(tbbt())

    assertEquals(1, resolved?.seasonNumber)
    assertEquals(1, resolved?.episodeNumber)
    assertEquals(0L, resolved?.totalDurationMs)
  }

  @Test
  fun `serie senza lista episodi riprende comunque l'ultima visione salvata`() {
    val media = tbbt(lastWatchedSeason = 5, lastWatchedEpisode = 6)

    val resolved = EpisodeResumeResolver.resolve(
      media = media,
      savedProgressMs = 17 * 60_000L,
      savedDurationMs = 22 * 60_000L
    )

    assertEquals(5, resolved?.seasonNumber)
    assertEquals(6, resolved?.episodeNumber)
    assertEquals(17 * 60_000L, resolved?.currentProgressMs)
  }

  @Test
  fun `stagione avanzata di una serie con molte stagioni non degrada a S1E1`() {
    val media = tbbt(
      lastWatchedSeason = 18,
      lastWatchedEpisode = 5,
      episodes = MediaRepository.generateEpisodesForTv(1418, "The Big Bang Theory", null, null, 20)
    )

    val resolved = EpisodeResumeResolver.resolve(media)

    assertEquals(18, resolved?.seasonNumber)
    assertEquals(5, resolved?.episodeNumber)
  }

  @Test
  fun `un film non ha episodi da riprendere`() {
    val movie = MediaItem(
      id = "tmdb_m_550",
      title = "Fight Club",
      synopsis = "",
      videoUrl = "",
      type = MediaType.FILM,
      lastWatchedSeason = 1,
      lastWatchedEpisode = 2
    )

    assertNull(EpisodeResumeResolver.resolve(movie))
  }

  @Test
  fun `episodi generati non contengono progressi inventati`() {
    val generated = MediaRepository.generateEpisodesForTv(1418, "The Big Bang Theory", null, null, 12)

    assertEquals("nessun seed di 12 minuti su S1E1", 0L, generated.first().currentProgressMs)
    assertEquals("nessun episodio generato con progresso", 0L, generated.sumOf { it.currentProgressMs })
  }

  // ── Fixture ──────────────────────────────────────────────────────────────

  private fun tbbt(
    lastWatchedSeason: Int? = null,
    lastWatchedEpisode: Int? = null,
    currentProgressMs: Long = 0L,
    totalDurationMs: Long = 0L,
    episodes: List<Episode> = emptyList()
  ): MediaItem = MediaItem(
    id = "tmdb_tv_1418",
    imdbId = "tt0898266",
    title = "The Big Bang Theory",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 1418,
    seasonsCount = 12,
    durationMinutes = 22,
    currentProgressMs = currentProgressMs,
    totalDurationMs = totalDurationMs,
    lastWatchedSeason = lastWatchedSeason,
    lastWatchedEpisode = lastWatchedEpisode,
    episodes = episodes
  )

  private fun episode(season: Int, episode: Int): Episode = Episode(
    id = "tmdb_tv_1418_s${season}e$episode",
    seasonNumber = season,
    episodeNumber = episode,
    title = "Episodio $episode",
    synopsis = "",
    durationMinutes = 22,
    videoUrl = ""
  )
}
