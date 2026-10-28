package com.example.data.repository

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.remote.simkl.SimklPlaybackEntry
import com.example.data.remote.simkl.SimklPlaybackKind
import com.example.data.remote.simkl.SimklWatchedLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corrispondenza tra una sessione SIMKL e il catalogo locale, e parsing della libreria
 * "visto" usata dai badge del Dettaglio.
 *
 * Il match usa gli stessi identificatori con cui l'app parla a SIMKL: `tmdb` prima di
 * tutto, poi `imdb`, poi titolo normalizzato + anno. Se niente corrisponde si restituisce
 * null: il titolo viene importato da TMDB o segnalato, mai inventato.
 */
class SimklLocalMatcherTest {

  private fun item(
    id: String,
    title: String,
    type: MediaType,
    year: Int = 2008,
    tmdbId: Int? = null,
    imdbId: String? = null
  ) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = type,
    year = year,
    tmdbId = tmdbId,
    imdbId = imdbId
  )

  private fun movieEntry(
    title: String = "Inception",
    year: Int? = 2010,
    tmdbId: Int? = 27205,
    imdbId: String? = "tt1375666"
  ) = SimklPlaybackEntry(
    playbackId = 1L,
    kind = SimklPlaybackKind.MOVIE,
    progressPercent = 40.0,
    pausedAtEpochMs = 1_700_000_000_000L,
    title = title,
    year = year,
    tmdbId = tmdbId,
    imdbId = imdbId,
    simklId = 1L
  )

  @Test
  fun ilMatchPreferisceLIdTmdb() {
    val candidates = listOf(
      item("tmdb_m_27205", "Inception", MediaType.FILM, 2010, tmdbId = 27205),
      item("tmdb_m_999", "Inception", MediaType.FILM, 2010, tmdbId = 999)
    )

    assertEquals("tmdb_m_27205", SimklLocalMatcher.find(candidates, movieEntry())?.id)
  }

  @Test
  fun senzaTmdbIlMatchUsaLImdbIgnorandoLeMaiuscole() {
    val candidates = listOf(
      item("tmdb_m_27205", "Inception", MediaType.FILM, 2010, imdbId = "TT1375666")
    )

    assertEquals("tmdb_m_27205", SimklLocalMatcher.find(candidates, movieEntry(tmdbId = null))?.id)
  }

  @Test
  fun comeUltimaRisorsaUsaTitoloNormalizzatoEAnno() {
    val candidates = listOf(
      item("tmdb_m_27205", "Inception:  il  risveglio", MediaType.FILM, 2010)
    )
    val entry = movieEntry(title = "inception il risveglio", tmdbId = null, imdbId = null)

    assertEquals("tmdb_m_27205", SimklLocalMatcher.find(candidates, entry)?.id)
  }

  @Test
  fun unAnnoDiversoNonVieneConsideratoUnMatch() {
    val candidates = listOf(item("tmdb_m_27205", "Inception", MediaType.FILM, 2010))

    assertNull(SimklLocalMatcher.find(candidates, movieEntry(tmdbId = null, imdbId = null, year = 1975)))
  }

  @Test
  fun ilTipoDeveCoincidere() {
    val candidates = listOf(item("tmdb_tv_27205", "Inception", MediaType.SERIE_TV, 2010, tmdbId = 27205))

    assertNull("un film SIMKL non deve agganciare una serie locale", SimklLocalMatcher.find(candidates, movieEntry()))
  }

  @Test
  fun unaSerieSiAgganciaAllaSchedaSerieConLoStessoTmdb() {
    val candidates = listOf(
      item("tmdb_m_1396", "Breaking Bad", MediaType.FILM, 2008, tmdbId = 1396),
      item("tmdb_tv_1396", "Breaking Bad", MediaType.SERIE_TV, 2008, tmdbId = 1396)
    )
    val entry = SimklPlaybackEntry(
      playbackId = 2L,
      kind = SimklPlaybackKind.EPISODE,
      progressPercent = 30.0,
      pausedAtEpochMs = null,
      title = "Breaking Bad",
      year = 2008,
      tmdbId = 1396,
      imdbId = null,
      simklId = 12345L,
      season = 5,
      episode = 14
    )

    assertEquals("tmdb_tv_1396", SimklLocalMatcher.find(candidates, entry)?.id)
  }

  @Test
  fun laNormalizzazioneDelTitoloNonDipendeDallaPunteggiatura() {
    assertEquals("stranger things", SimklLocalMatcher.normalizeTitle("Stranger  Things"))
    assertEquals("chapter three holly jolly", SimklLocalMatcher.normalizeTitle("Chapter Three: Holly Jolly"))
    assertEquals("", SimklLocalMatcher.normalizeTitle("   "))
  }

  @Test
  fun laLibreriaVistaDiventaUnaMappaPerSerieEStagione() {
    // Forma reale di GET /sync/all-items/shows?extended=full&episode_watched_at=yes
    val body = mapOf(
      "shows" to listOf(
        mapOf(
          "show" to mapOf("title" to "Breaking Bad", "ids" to mapOf("tmdb" to "1396")),
          "seasons" to listOf(
            mapOf(
              "number" to 1,
              "episodes" to listOf(
                mapOf("number" to 1, "watched_at" to "2024-01-01T10:00:00Z"),
                mapOf("number" to 2, "watched_at" to null),
                mapOf("number" to 3, "watched_at" to "2024-01-02T10:00:00Z")
              )
            ),
            mapOf(
              "number" to 2,
              "episodes" to listOf(mapOf("number" to 1, "watched_at" to "2024-02-01T10:00:00Z"))
            )
          )
        ),
        // Titolo senza id TMDB: non può essere agganciato al catalogo, viene ignorato.
        mapOf("show" to mapOf("title" to "Sconosciuta", "ids" to mapOf("imdb" to "tt0000001")), "seasons" to emptyList<Any>())
      ),
      "anime" to listOf(
        mapOf(
          "anime" to mapOf("title" to "Attack on Titan", "ids" to mapOf("tmdb" to "1429")),
          "seasons" to listOf(
            mapOf("number" to 1, "episodes" to listOf(mapOf("number" to 9, "watched_at" to "2024-03-01T10:00:00Z")))
          )
        )
      )
    )

    val library = SimklWatchedLibrary.parseWatchedEpisodesByShow(body)

    assertEquals(setOf(1, 3), library[1396]?.get(1))
    assertEquals(setOf(1), library[1396]?.get(2))
    assertEquals(setOf(9), library[1429]?.get(1))
    assertTrue("nessun id TMDB ⇒ nessuna voce inventata", library.keys.none { it == 0 })

    // La vista a stagione singola (usata dai badge) legge la stessa struttura.
    assertEquals(setOf(1, 3), SimklWatchedLibrary.parseWatchedEpisodes(body, 1396, 1))
    assertTrue(SimklWatchedLibrary.parseWatchedEpisodes(body, 1396, 7).isEmpty())
  }
}
