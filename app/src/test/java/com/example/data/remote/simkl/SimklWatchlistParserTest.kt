package com.example.data.remote.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lettura di `GET /sync/all-items/{type}/{status}` — la watchlist di SIMKL.
 *
 * I corpi usati qui sono quelli **della documentazione ufficiale** (esempi OpenAPI
 * `phase1_initial_full_pull` e `movies_completed_filtered`), così il contratto di trasporto
 * resta ancorato alla fonte e non a una forma inventata.
 *
 * La watchlist è un insieme DISTINTO dalla cronologia (`watched_at`) e dai punti di pausa
 * (`/sync/playback`): qui si legge solo "Plan to Watch".
 */
class SimklWatchlistParserTest {

  /** Esempio ufficiale `/sync/all-items/shows/plantowatch`. */
  private val showsBody = mapOf<String, Any>(
    "shows" to listOf(
      mapOf(
        "added_to_watchlist_at" to "2018-02-24T23:55:13Z",
        "last_watched_at" to null,
        "user_rating" to null,
        "status" to "plantowatch",
        "next_to_watch" to "S01E01",
        "watched_episodes_count" to 0,
        "total_episodes_count" to 178,
        "show" to mapOf(
          "title" to "Charmed",
          "poster" to "24/24273cee77f9d9f",
          "year" to 1998,
          "ids" to mapOf("simkl" to 297, "slug" to "charmed", "imdb" to "tt0158552", "tvdb" to "70626", "tmdb" to "1981")
        )
      )
    )
  )

  /** Esempio ufficiale `/sync/all-items/movies/completed` con lo status riscritto. */
  private val moviesBody = mapOf<String, Any>(
    "movies" to listOf(
      mapOf(
        "added_to_watchlist_at" to "2026-04-10T20:13:02Z",
        "status" to "plantowatch",
        "movie" to mapOf(
          "title" to "The Godfather",
          "year" to 1972,
          "ids" to mapOf("simkl" to 53434, "slug" to "the-godfather", "imdb" to "tt0068646", "tmdb" to "238")
        )
      )
    )
  )

  @Test
  fun unFilmInWatchlistDiventaUnaVoceConIGliIdentificativi() {
    val entry = SimklWatchlistParser.planToWatch(moviesBody).single()

    assertEquals(SimklWatchlistKind.MOVIE, entry.kind)
    assertEquals("The Godfather", entry.title)
    assertEquals(1972, entry.year)
    assertEquals(238, entry.tmdbId)
    assertEquals("tt0068646", entry.imdbId)
    assertEquals(53434L, entry.simklId)
    assertTrue(!entry.isAnime)
  }

  @Test
  fun unaSerieInWatchlistVieneLettaDalBucketShows() {
    val entry = SimklWatchlistParser.planToWatch(showsBody).single()

    assertEquals(SimklWatchlistKind.SHOW, entry.kind)
    assertEquals("Charmed", entry.title)
    assertEquals(1981, entry.tmdbId)
    assertEquals("tt0158552", entry.imdbId)
  }

  @Test
  fun gliAnimeSonoTrattatiComeSerieSenzaUnTipoDiverso() {
    val body = mapOf<String, Any>(
      "anime" to listOf(
        mapOf(
          "status" to "plantowatch",
          "show" to mapOf(
            "title" to "Cowboy Bebop",
            "year" to 1998,
            "ids" to mapOf("simkl" to 37089, "mal" to "1", "tmdb" to "30984")
          )
        )
      )
    )

    val entry = SimklWatchlistParser.planToWatch(body).single()
    assertEquals(SimklWatchlistKind.SHOW, entry.kind)
    assertTrue("l'anime resta riconoscibile", entry.isAnime)
    assertEquals(30984, entry.tmdbId)
  }

  @Test
  fun unaWatchlistVuotaEUnOggettoVuotoNonUnErrore() {
    assertTrue(SimklWatchlistParser.planToWatch(emptyMap()).isEmpty())
    assertTrue(SimklWatchlistParser.planToWatch(null).isEmpty())
    assertTrue("un bucket vuoto non produce voci", SimklWatchlistParser.planToWatch(mapOf("shows" to emptyList<Any>())).isEmpty())
  }

  @Test
  fun soloPlantToWatchVieneImportato() {
    // La risposta può contenere altri stati (il bucket `all` è valido): non sono "da
    // guardare" e non devono finire nei Preferiti.
    val body = mapOf<String, Any>(
      "shows" to listOf(
        mapOf("status" to "plantowatch", "show" to mapOf("title" to "Da guardare", "ids" to mapOf("tmdb" to 1))),
        mapOf("status" to "watching", "show" to mapOf("title" to "In corso", "ids" to mapOf("tmdb" to 2))),
        mapOf("status" to "completed", "show" to mapOf("title" to "Finito", "ids" to mapOf("tmdb" to 3))),
        mapOf("status" to "dropped", "show" to mapOf("title" to "Abbandonato", "ids" to mapOf("tmdb" to 4)))
      )
    )

    val titles = SimklWatchlistParser.planToWatch(body).map { it.title }
    assertEquals(listOf("Da guardare"), titles)
  }

  @Test
  fun unaVoceSenzaTitoloOVoceNonValidaVieneScartataSenzaInventareNulla() {
    val body = mapOf<String, Any>(
      "movies" to listOf(
        mapOf("status" to "plantowatch"),                                  // niente contenitore
        mapOf("status" to "plantowatch", "movie" to mapOf("ids" to mapOf("tmdb" to 9))), // niente titolo
        "non una mappa",
        mapOf("status" to "plantowatch", "movie" to mapOf("title" to "Valido", "ids" to mapOf("tmdb" to 10)))
      )
    )

    val entries = SimklWatchlistParser.planToWatch(body)
    assertEquals(listOf("Valido"), entries.map { it.title })
  }

  @Test
  fun unoStatoAssenteVieneTrattatoComePlanToWatch() {
    // Il percorso `/sync/all-items/{type}/plantowatch` è già selettivo: una voce senza
    // `status` esplicito non deve andare persa.
    val body = mapOf<String, Any>(
      "movies" to listOf(mapOf("movie" to mapOf("title" to "Senza stato", "ids" to mapOf("tmdb" to 11))))
    )

    assertEquals("Senza stato", SimklWatchlistParser.planToWatch(body).single().title)
    assertEquals(SIMKL_STATUS_PLAN_TO_WATCH, SimklWatchlistParser.planToWatch(body).single().status)
  }

  @Test
  fun gliIdentificativiArrivanoComeNumeroOComeStringa() {
    val body = mapOf<String, Any>(
      "movies" to listOf(
        mapOf(
          "status" to "plantowatch",
          "movie" to mapOf("title" to "Numeri", "year" to 2001.0, "ids" to mapOf("tmdb" to 101.0, "simkl" to 7.0))
        )
      ),
      "shows" to listOf(
        mapOf(
          "status" to "plantowatch",
          "show" to mapOf("title" to "Stringhe", "year" to "2002", "ids" to mapOf("tmdb" to "202", "simkl" to "8"))
        )
      )
    )

    val entries = SimklWatchlistParser.planToWatch(body)
    assertEquals(101, entries.first { it.title == "Numeri" }.tmdbId)
    assertEquals(2002, entries.first { it.title == "Stringhe" }.year)
    assertEquals(202, entries.first { it.title == "Stringhe" }.tmdbId)
    assertEquals(8L, entries.first { it.title == "Stringhe" }.simklId)
  }

  @Test
  fun unaVistaSenzaIdentificativiNonProduceUnErrore() {
    val entry = SimklWatchlistParser.planToWatch(
      mapOf("movies" to listOf(mapOf("status" to "plantowatch", "movie" to mapOf("title" to "Solo titolo"))))
    ).single()

    assertEquals("Solo titolo", entry.title)
    assertEquals(null, entry.tmdbId)
    assertEquals(null, entry.imdbId)
  }
}
