package com.example.data.remote.simkl

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Punti di ripresa episodici dalla libreria SIMKL (`GET /sync/all-items/shows`).
 *
 * La risposta reale dell'account contiene 124 serie e **126 episodi visti**, ma
 * `GET /sync/playback` non contiene alcun episodio: i progressi degli episodi vivono solo
 * qui, nei campi `status`, `last_watched` e `last_watched_at` che prima venivano scartati.
 * La forma qui sotto è quella documentata e osservata (`S01E07`, data ISO-8601 UTC).
 */
class SimklWatchedLibraryResumeTest {

  /** La forma reale di The Big Bang Theory: 58 episodi visti, ultimo S1E7. */
  private val rispostaReale = """
    {
      "shows": [
        {
          "last_watched_at": "2026-10-26T06:05:29Z",
          "status": "watching",
          "last_watched": "S01E07",
          "next_to_watch": "S01E08",
          "watched_episodes_count": 58,
          "total_episodes_count": 279,
          "show": {
            "title": "The Big Bang Theory",
            "year": 2007,
            "runtime": 25,
            "ids": {"simkl": 10280, "imdb": "tt0898266", "tmdb": "1418", "tvdb": "80379"}
          },
          "seasons": [
            {"number": 1, "episodes": [
              {"number": 1, "watched_at": "2026-09-30T05:55:12Z"},
              {"number": 7, "watched_at": "2026-10-26T06:05:29Z"}
            ]}
          ]
        }
      ]
    }
  """.trimIndent()

  /**
   * Corpo JSON → la stessa `Map<String, Any>` che Retrofit/Moshi consegnano al parser in
   * produzione: il test resta ancorato al testo reale della risposta, non a una mappa
   * costruita a mano (che nasconderebbe proprio i disallineamenti di forma).
   */
  private fun json(raw: String): Map<String, Any> {
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    return moshi.adapter<Map<String, Any>>(type).fromJson(raw) ?: error("corpo non leggibile")
  }

  @Test
  fun laSerieInVisioneDiventaUnPuntoDiRipresaConIlProssimoEpisodio() {
    val dati = SimklWatchedLibrary.parse(json(rispostaReale))

    assertEquals(1, dati.watchingResumes.size)
    val resume = dati.watchingResumes.single()
    assertEquals("The Big Bang Theory", resume.title)
    assertEquals(1418, resume.tmdbId)
    assertEquals("tt0898266", resume.imdbId)
    assertEquals(10280L, resume.simklId)
    // `next_to_watch` è il target: S1E8 (l'ultimo visto S1E7 non è la posizione da
    // riprendere, altrimenti la card mostrerebbe un episodio già finito).
    assertEquals(1, resume.season)
    assertEquals(8, resume.episode)
    assertEquals("ultimo episodio visto conservato per il report", 7, resume.lastWatchedEpisode)
    assertEquals(
      "la data è quella di SIMKL",
      SimklPlaybackParser.parseIsoUtcToEpochMs("2026-10-26T06:05:29Z"),
      resume.watchedAtEpochMs
    )
  }

  @Test
  fun iBadgeDellaLibreriaRestanoInvariati() {
    val dati = SimklWatchedLibrary.parse(json(rispostaReale))

    assertEquals(setOf(1, 7), dati.watchedEpisodesByShow[1418]?.get(1))
    assertEquals(setOf(1, 7), SimklWatchedLibrary.parseWatchedEpisodes(json(rispostaReale), 1418, 1))
  }

  @Test
  fun unaSerieConclusaNonEUnPuntoDiRipresa() {
    val body = json(
      """
      {"shows":[
        {"last_watched_at":"2026-10-01T10:00:00Z","status":"completed","last_watched":"S02E10",
         "show":{"title":"Finita","year":2010,"ids":{"tmdb":"77"}}},
        {"last_watched_at":"2026-10-01T10:00:00Z","status":"dropped","last_watched":"S01E02",
         "show":{"title":"Abbandonata","year":2011,"ids":{"tmdb":"78"}}},
        {"last_watched_at":"2026-10-01T10:00:00Z","status":"hold","last_watched":"S01E03",
         "show":{"title":"In Pausa","year":2012,"ids":{"tmdb":"79"}}}
      ]}
      """.trimIndent()
    )

    assertTrue(SimklWatchedLibrary.parseWatchingResumes(body).isEmpty())
  }

  @Test
  fun senzaDataOSenzaEpisodioNonSiInventaUnaPosizione() {
    val body = json(
      """
      {"shows":[
        {"status":"watching","last_watched":"S01E02",
         "show":{"title":"Senza Data","year":2015,"ids":{"tmdb":"80"}}},
        {"last_watched_at":"2026-10-01T10:00:00Z","status":"watching",
         "show":{"title":"Senza Episodio","year":2016,"ids":{"tmdb":"81"}}},
        {"last_watched_at":"2026-10-01T10:00:00Z","status":"watching","last_watched":"boh",
         "show":{"title":"Codice Illeggibile","year":2017,"ids":{"tmdb":"82"}}}
      ]}
      """.trimIndent()
    )

    assertTrue(SimklWatchedLibrary.parseWatchingResumes(body).isEmpty())
  }

  @Test
  fun unaCourAncoraInVisioneProteggeLaSerieAncheSeInPari() {
    val body = json(
      """
      {"anime":[
        {"status":"watching","last_watched_at":"2026-05-01T10:00:00Z",
         "last_watched":"E12","next_to_watch":null,
         "anime":{"title":"Cour in pari","ids":{"tmdb":"333"}}},
        {"status":"completed","last_watched_at":"2026-05-02T10:00:00Z",
         "anime":{"title":"Cour conclusa","ids":{"tmdb":"333"}}}
      ]}
      """.trimIndent()
    )

    val data = SimklWatchedLibrary.parse(body)
    assertTrue("la cour in pari non produce un resume", data.watchingResumes.isEmpty())
    assertEquals(
      "ma il suo TMDB resta 'in visione' per non chiudere l'intera serie",
      setOf(333),
      data.watchingTmdbIds
    )
  }

  @Test
  fun iCompletatiIncludonoFilmSerieAnimeEAnimeFilm() {
    val body = json(
      """
      {
        "movies":[{"status":"completed","last_watched_at":"2026-05-01T10:00:00Z",
          "movie":{"title":"Film","year":2020,"ids":{"tmdb":"101","imdb":"tt101"}}}],
        "shows":[{"status":"completed","last_watched_at":"2026-05-02T10:00:00Z",
          "show":{"title":"Serie","year":2021,"ids":{"tmdb":"102"}}}],
        "anime":[
          {"status":"completed","anime_type":"tv","last_watched_at":"2026-05-03T10:00:00Z",
           "anime":{"title":"Anime","year":2022,"ids":{"tmdb":"103"}}},
          {"status":"completed","anime_type":"movie","last_watched_at":null,
           "anime":{"title":"Anime Film","year":2023,"ids":{"tmdb":"104"}}}
        ]
      }
      """.trimIndent()
    )

    val completed = SimklWatchedLibrary.parseCompletedTitles(body)
    assertEquals(4, completed.size)
    assertEquals(SimklCompletedKind.MOVIE, completed[0].kind)
    assertEquals(SimklCompletedKind.SHOW, completed[1].kind)
    assertEquals(SimklCompletedKind.SHOW, completed[2].kind)
    assertTrue(completed[2].isAnime)
    assertEquals("un anime-film è un FILM", SimklCompletedKind.MOVIE, completed[3].kind)
    assertNull(completed[3].completedAtEpochMs)
  }

  @Test
  fun ilCodiceEpisodioAccettaLeFormeDocumentate() {
    assertEquals(1 to 7, SimklWatchedLibrary.parseEpisodeCode("S01E07"))
    assertEquals(10 to 2, SimklWatchedLibrary.parseEpisodeCode("s10e2"))
    assertEquals(3 to 12, SimklWatchedLibrary.parseEpisodeCode("3x12"))
    assertEquals("formato anime-native E2", 1 to 2, SimklWatchedLibrary.parseEpisodeCode("E2"))
    assertNull(SimklWatchedLibrary.parseEpisodeCode("S00E00"))
    assertNull(SimklWatchedLibrary.parseEpisodeCode("nessun episodio"))
    assertNull(SimklWatchedLibrary.parseEpisodeCode(null))
  }

  @Test
  fun gliAnimeInVisioneSonoPuntiDiRipresa() {
    val body = json(
      """
      {"anime":[
        {"last_watched_at":"2026-09-01T10:00:00Z","status":"watching","last_watched":"S02E04",
         "next_to_watch":"S02E05",
         "anime":{"title":"Anime in Corso","year":2019,"ids":{"tmdb":"90","simkl":"900"}}}
      ]}
      """.trimIndent()
    )

    val resume = SimklWatchedLibrary.parseWatchingResumes(body).single()
    assertTrue(resume.isAnime)
    assertEquals(90, resume.tmdbId)
    assertEquals(2, resume.season)
    assertEquals(5, resume.episode)
  }

  @Test
  fun unAnimeConCodiceSenzaStagioneUsaLaMappaturaTvdb() {
    val body = json(
      """
      {"anime":[
        {"last_watched_at":"2026-09-01T10:00:00Z","status":"watching","last_watched":"E2",
         "next_to_watch":"E3","mapped_tvdb_seasons":[2],
         "anime":{"title":"Anime Cour","year":2019,"ids":{"tmdb":"91","simkl":"901"}},
         "seasons":[{"number":1,"episodes":[
           {"number":1,"tvdb":{"season":2,"episode":1}},
           {"number":2,"tvdb":{"season":2,"episode":2}},
           {"number":3,"tvdb":{"season":2,"episode":3}}
         ]}]}
      ]}
      """.trimIndent()
    )

    val resume = SimklWatchedLibrary.parseWatchingResumes(body).single()
    assertEquals("E2 → ultimo visto S2E2", 2, resume.lastWatchedSeason)
    assertEquals(2, resume.lastWatchedEpisode)
    assertEquals("E3 → prossimo episodio S2E3", 2, resume.season)
    assertEquals(3, resume.episode)
  }

  @Test
  fun ilProssimoEpisodioDellaLibreriaEPosizioneZero() {
    val resume = SimklWatchedLibrary.parseWatchingResumes(json(rispostaReale)).single()

    val entry = SimklWatchedLibrary.resumeEntry(resume)

    assertEquals(SimklPlaybackKind.EPISODE, entry.kind)
    assertEquals(SimklResumeOrigin.LIBRARY, entry.origin)
    assertTrue("è un next_to_watch, non una posizione parziale", entry.isNextToWatch)
    assertEquals("nessuna percentuale inventata: si parte da zero", 0.0, entry.progressPercent, 0.0)
    assertEquals(1, entry.season)
    assertEquals(8, entry.episode)
    assertEquals(resume.watchedAtEpochMs, entry.pausedAtEpochMs)
    assertTrue("l'episodio è identificato: è riprendibile", SimklPlaybackParser.isResumable(entry))
    assertTrue(entry.resumeKey.startsWith("k:library:"))
    assertTrue("la chiave è stabile fra due giri", entry.resumeKey == SimklWatchedLibrary.resumeEntry(resume).resumeKey)
  }

  @Test
  fun laChiaveDellaLibreriaNonCollideConUnaSessioneDiPausa() {
    val resume = SimklWatchedLibrary.parseWatchingResumes(json(rispostaReale)).single()
    val osservato = SimklWatchedLibrary.resumeEntry(resume)
    // Una sessione di pausa senza id per lo stesso episodio: stessa forma, origine diversa.
    val sessione = osservato.copy(origin = SimklResumeOrigin.PLAYBACK)

    assertNotEquals(osservato.resumeKey, sessione.resumeKey)
    assertEquals(
      "l'origine sopravvive alla persistenza delle voci rimandate",
      osservato.resumeKey,
      SimklPendingPlaybackStore.decode(
        SimklPendingPlaybackStore.encode(listOf(SimklPendingPlayback(osservato, attempts = 1)))
      ).single().entry.resumeKey
    )
  }
}
