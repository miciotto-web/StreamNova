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
 * Il caso che ha originato il difetto: l'ultima visione è S5E2 ma la lista locale
 * della serie contiene solo le prime stagioni (episodi generati/troncati o non ancora
 * caricati da TMDB). Il vecchio fallback al primo episodio faceva partire S1E1.
 */
class EpisodeResumeResolverTest {

  @Test
  fun `ultima visione in stagione avanzata non viene sostituita da S1E1`() {
    val media = series(
      lastWatchedSeason = 5,
      lastWatchedEpisode = 2,
      totalDurationMs = 61 * 60_000L,
      episodes = listOf(episode(1, 1), episode(1, 2), episode(2, 1), episode(3, 4))
    )

    val resolved = EpisodeResumeResolver.resolve(media)

    assertEquals(5, resolved?.seasonNumber)
    assertEquals(2, resolved?.episodeNumber)
    assertEquals("${media.id}_s5e2", resolved?.id)
    assertEquals("la durata nota appartiene proprio a questo episodio", 61 * 60_000L, resolved?.totalDurationMs)
    assertEquals(media.effectiveImdbId, resolved?.seriesImdbId)
  }

  @Test
  fun `episodio presente nella lista conserva titolo e identita`() {
    val exact = episode(5, 2).copy(
      id = "ep-s5e2",
      title = "Il drago e il lupo",
      durationMinutes = 58,
      totalDurationMs = 58 * 60_000L
    )
    val media = series(
      lastWatchedSeason = 5,
      lastWatchedEpisode = 2,
      totalDurationMs = 60 * 60_000L,
      episodes = listOf(episode(1, 1), exact)
    )

    val resolved = EpisodeResumeResolver.resolve(media)

    assertEquals("ep-s5e2", resolved?.id)
    assertEquals("Il drago e il lupo", resolved?.title)
    // La durata misurata dal player per l'ultima visione (60 min) è più affidabile
    // del runtime stimato dell'episodio: appartiene alla stessa puntata.
    assertEquals(60 * 60_000L, resolved?.totalDurationMs)
  }

  @Test
  fun `episodio scelto esplicitamente ha priorita sullo stato salvato`() {
    val media = series(lastWatchedSeason = 5, lastWatchedEpisode = 2)

    val resolved = EpisodeResumeResolver.resolve(
      media = media,
      requestedSeason = 5,
      requestedEpisode = 7
    )

    assertEquals(5, resolved?.seasonNumber)
    assertEquals(7, resolved?.episodeNumber)
  }

  @Test
  fun `stagione speciale 0 esplicitamente scelta resta valida`() {
    val media = series(lastWatchedSeason = 1, lastWatchedEpisode = 1)

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
    val media = series(lastWatchedSeason = 0, lastWatchedEpisode = 2)

    val resolved = EpisodeResumeResolver.resolve(media)

    assertEquals(0, resolved?.seasonNumber)
    assertEquals(2, resolved?.episodeNumber)
  }

  @Test
  fun `progresso salvato viene applicato all'episodio risolto`() {
    val media = series(lastWatchedSeason = 5, lastWatchedEpisode = 2)

    val resolved = EpisodeResumeResolver.resolve(media, savedProgressMs = 22 * 60_000L)

    assertEquals(22 * 60_000L, resolved?.currentProgressMs)
  }

  @Test
  fun `serie mai avviata parte dal primo episodio disponibile`() {
    val media = series(
      lastWatchedSeason = null,
      lastWatchedEpisode = null,
      episodes = listOf(episode(2, 1), episode(1, 2), episode(1, 1))
    )

    val resolved = EpisodeResumeResolver.resolve(media)

    assertEquals(1, resolved?.seasonNumber)
    assertEquals(1, resolved?.episodeNumber)
  }

  @Test
  fun `serie mai avviata senza episodi crea S1E1`() {
    val resolved = EpisodeResumeResolver.resolve(series())

    assertEquals(1, resolved?.seasonNumber)
    assertEquals(1, resolved?.episodeNumber)
    assertEquals(0L, resolved?.totalDurationMs)
  }

  @Test
  fun `episodio diverso dall'ultima visione non eredita la durata del titolo`() {
    val media = series(
      lastWatchedSeason = 5,
      lastWatchedEpisode = 2,
      totalDurationMs = 61 * 60_000L
    )

    val resolved = EpisodeResumeResolver.resolve(
      media = media,
      requestedSeason = 5,
      requestedEpisode = 3
    )

    assertEquals(5, resolved?.seasonNumber)
    assertEquals(3, resolved?.episodeNumber)
    assertEquals("durata sconosciuta: mai spacciare quella di un altro episodio", 0L, resolved?.totalDurationMs)
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

  // ── Fixture ──────────────────────────────────────────────────────────────

  private fun series(
    lastWatchedSeason: Int? = null,
    lastWatchedEpisode: Int? = null,
    totalDurationMs: Long = 0L,
    episodes: List<Episode> = emptyList()
  ): MediaItem = MediaItem(
    id = "tt0944947",
    imdbId = "tt0944947",
    title = "Il Trono di Spade",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = 1399,
    durationMinutes = 55,
    totalDurationMs = totalDurationMs,
    lastWatchedSeason = lastWatchedSeason,
    lastWatchedEpisode = lastWatchedEpisode,
    episodes = episodes
  )

  private fun episode(season: Int, episode: Int): Episode = Episode(
    id = "tt0944947_s${season}e$episode",
    seasonNumber = season,
    episodeNumber = episode,
    title = "Episodio $episode",
    synopsis = "",
    durationMinutes = 55,
    videoUrl = ""
  )
}
