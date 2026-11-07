package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.MediaCacheMapper.toEntity
import com.example.data.local.StreamNovaDatabase
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pipeline delle miniature TMDB per «Continua a guardare».
 *
 * Regressione Fire TV (release 1.0.4): la preferenza «Usa miniature episodi» era attiva,
 * ma le card delle serie mostravano il backdrop. Le schede arrivate dai cataloghi
 * Stremio hanno id `tt…` e **nessun `tmdbId`**: il recupero veniva filtrato via prima
 * ancora di chiedere la stagione. Inoltre gli episodi della stagione di ripresa (es. S8)
 * non sono generati dal catalogo locale: senza un registro riapplicato a ogni emissione
 * Room (che ricostruisce gli episodi) sparivano insieme alla loro still.
 *
 * Qui si verificano i due anelli mancanti con la stessa pipeline della produzione.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EpisodeThumbnailPipelineTest {

  private companion object {
    const val IMDB_ID = "tt0898266" // The Big Bang Theory (catalogo Stremio)
    const val TMDB_ID = 1418
    const val TMDB_TWIN_ID = "tmdb_tv_1418"
    const val SERIES_BACKDROP = "https://image.tmdb.org/t/p/w1280/backdrop_serie.jpg"
    const val SERIES_POSTER = "https://image.tmdb.org/t/p/w500/poster_serie.jpg"
    const val STILL_S08E01 = "https://image.tmdb.org/t/p/w300/still_s8e1.jpg"
    const val STILL_S08E02 = "https://image.tmdb.org/t/p/w300/still_s8e2.jpg"
    const val PROGRESS_MS = 9 * 60_000L
    const val DURATION_MS = 22 * 60_000L
  }

  private lateinit var database: StreamNovaDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    MediaRepository.setDatabaseForTesting(database)
    MediaRepository.init(context, database)
  }

  @After
  fun tearDown() {
    MediaRepository.setDatabaseForTesting(null)
    database.close()
  }

  // ── Selezione dei bersagli (nessuna rete) ──────────────────────────────────

  @Test
  fun `serie IMDb senza tmdbId viene recuperata con l'id TMDB RISOLTO`() {
    val item = imdbSeries()
    assertNull("la scheda Stremio/IMDb non dichiara tmdbId", item.tmdbId)

    val targets = MediaRepository.resolveEpisodeThumbnailTargets(listOf(item)) { TMDB_ID }

    assertEquals("la serie non deve più essere filtrata via", 1, targets.size)
    val target = targets.single()
    assertEquals(IMDB_ID, target.mediaId)
    assertEquals("id TMDB risolto da IMDb", TMDB_ID, target.tmdbId)
    assertEquals(8, target.seasonNumber)
    assertEquals(1, target.episodeNumber)
  }

  @Test
  fun `serie senza id TMDB risolvibile non produce richieste inventate`() {
    val targets = MediaRepository.resolveEpisodeThumbnailTargets(listOf(imdbSeries())) { null }
    assertTrue("nessuna still può essere chiesta senza id TMDB", targets.isEmpty())
  }

  @Test
  fun `film, serie mai avviate e serie con still specifica sono esclusi`() {
    val film = imdbSeries(id = "tt0133093", imdbId = "tt0133093")
      .copy(type = MediaType.FILM, tmdbId = 603)
    val neverStarted = imdbSeries(id = "tt1111111", imdbId = "tt1111111")
      .copy(lastWatchedSeason = null, lastWatchedEpisode = null)
    val alreadyHasStill = imdbSeries(id = "tt2222222", imdbId = "tt2222222").copy(
      episodes = listOf(episode(8, 1, STILL_S08E01))
    )

    val targets = MediaRepository.resolveEpisodeThumbnailTargets(
      listOf(film, neverStarted, alreadyHasStill)
    ) { TMDB_ID }

    assertTrue("niente da arricchire", targets.isEmpty())
  }

  @Test
  fun `schede gemelle della stessa serie e stagione producono UNA sola richiesta`() {
    val imdb = imdbSeries()
    val tmdb = imdb.copy(id = TMDB_TWIN_ID, tmdbId = TMDB_ID)

    val targets = MediaRepository.resolveEpisodeThumbnailTargets(listOf(imdb, tmdb)) { TMDB_ID }

    assertEquals("nessuna richiesta duplicata della stessa stagione", 1, targets.size)
  }

  @Test
  fun `seriesTwinIds riconosce la gemella TMDB tramite l'id risolto`() {
    val imdb = imdbSeries()
    val tmdb = imdb.copy(id = TMDB_TWIN_ID, tmdbId = TMDB_ID)
    val other = imdbSeries(id = "tt9999999", imdbId = "tt9999999")

    val ids = MediaRepository.seriesTwinIds(
      items = listOf(imdb, tmdb, other),
      seed = imdb,
      resolvedTmdbId = TMDB_ID
    )

    assertEquals(setOf(IMDB_ID, TMDB_TWIN_ID), ids)
  }

  // ── Riapplicazione dopo una ricostruzione Room ─────────────────────────────

  @Test
  fun `applyEpisodeOverlays riaggiunge le puntate TMDB assenti dal catalogo generato`() {
    val regenerated = imdbSeries().copy(
      episodes = MediaRepository.generateEpisodesForTv(
        tvId = TMDB_ID,
        tvTitle = "The Big Bang Theory",
        backdropUrl = SERIES_BACKDROP,
        posterUrl = SERIES_POSTER,
        seasons = 12
      )
    )
    assertFalse(
      "il generatore locale non conosce la stagione 8",
      regenerated.episodes.any { it.seasonNumber == 8 }
    )

    val overlaid = MediaRepository.applyEpisodeOverlays(
      item = regenerated,
      stills = mapOf("${IMDB_ID}_s8e1" to STILL_S08E01),
      progress = emptyMap(),
      episodesByMedia = mapOf(
        IMDB_ID to mapOf("s8e1" to episode(8, 1, STILL_S08E01))
      )
    )

    val s8e1 = overlaid.episodes.firstOrNull { it.seasonNumber == 8 && it.episodeNumber == 1 }
    assertNotNull("puntata riaggiunta dal registro TMDB", s8e1)
    assertEquals("con la still TMDB", STILL_S08E01, s8e1?.thumbnailUrl)
  }

  @Test
  fun `applyEpisodeOverlays non applica la still di un'altra serie`() {
    val item = imdbSeries().copy(
      episodes = MediaRepository.generateEpisodesForTv(
        tvId = TMDB_ID,
        tvTitle = "The Big Bang Theory",
        backdropUrl = SERIES_BACKDROP,
        posterUrl = SERIES_POSTER,
        seasons = 12
      )
    )

    val overlaid = MediaRepository.applyEpisodeOverlays(
      item = item,
      stills = mapOf("tt9999999_s8e1" to STILL_S08E01),
      progress = emptyMap(),
      episodesByMedia = mapOf(
        "tt9999999" to mapOf("s8e1" to episode(8, 1, STILL_S08E01))
      )
    )

    assertFalse(
      "nessuna puntata di un'altra serie può comparire",
      overlaid.episodes.any { it.seasonNumber == 8 }
    )
  }

  // ── Percorso reale repository + Room ──────────────────────────────────────

  @Test
  fun `la miniatura sopravvive alle emissioni Room e segue il cambio episodio`() = runBlocking {
    val item = imdbSeries()
    MediaRepository.upsertExternalItem(item)
    attendi { MediaRepository.mediaList.value.any { it.id == item.id } }

    val seasonEpisodes = listOf(
      episode(8, 1, STILL_S08E01),
      episode(8, 2, STILL_S08E02)
    )
    MediaRepository.updateEpisodesForMedia(item.id, seasonEpisodes, tmdbId = TMDB_ID)
    assertEquals(STILL_S08E01, thumbnailOf(item.id, 8, 1))

    // Nuova emissione Room: il progresso del titolo cambia e il catalogo viene ricostruito
    // dai generatori. Le puntate S8 NON sono generate: devono tornare dal registro.
    database.cachedMediaDao().updateProgress(item.id, 777_000L)
    attendi {
      MediaRepository.mediaList.value.firstOrNull { it.id == item.id }?.currentProgressMs == 777_000L
    }

    assertEquals(
      "la still S8E1 non deve perdersi alla ricostruzione Room",
      STILL_S08E01,
      thumbnailOf(item.id, 8, 1)
    )
    assertEquals(
      "la puntata successiva resta disponibile",
      STILL_S08E02,
      thumbnailOf(item.id, 8, 2)
    )

    // Cambio episodio di ripresa: la card deve seguire la nuova puntata.
    database.cachedMediaDao().updateTvProgress(item.id, 45_000L, 8, 2)
    attendi {
      MediaRepository.mediaList.value.firstOrNull { it.id == item.id }?.lastWatchedEpisode == 2
    }
    assertEquals(STILL_S08E02, thumbnailOf(item.id, 8, 2))
  }

  @Test
  fun `le still raggiungono anche la gemella TMDB della stessa serie`() = runBlocking {
    val imdb = imdbSeries()
    val tmdbTwin = imdb.copy(id = TMDB_TWIN_ID, tmdbId = TMDB_ID)
    MediaRepository.upsertExternalItem(imdb)
    // La gemella arriva come riga separata (un upsert verrebbe fuso sulla scheda IMDb).
    database.cachedMediaDao().insertOrUpdate(tmdbTwin.toEntity())
    attendi {
      MediaRepository.mediaList.value.any { it.id == TMDB_TWIN_ID } &&
        MediaRepository.mediaList.value.any { it.id == IMDB_ID }
    }

    MediaRepository.updateEpisodesForMedia(
      IMDB_ID,
      listOf(episode(8, 1, STILL_S08E01)),
      tmdbId = TMDB_ID
    )

    assertEquals(
      "la scheda scelta dalla Home (IMDb) ha la still",
      STILL_S08E01,
      thumbnailOf(IMDB_ID, 8, 1)
    )
    assertEquals(
      "anche la gemella TMDB ha la stessa still",
      STILL_S08E01,
      thumbnailOf(TMDB_TWIN_ID, 8, 1)
    )
  }

  // ── Helper ────────────────────────────────────────────────────────────────

  /** Scheda di una serie arrivata da Stremio: id IMDb, `tmdbId` NON dichiarato. */
  private fun imdbSeries(id: String = IMDB_ID, imdbId: String = IMDB_ID): MediaItem =
    MediaItem(
      id = id,
      title = "The Big Bang Theory",
      originalTitle = "The Big Bang Theory",
      synopsis = "",
      videoUrl = "",
      backdropUrl = SERIES_BACKDROP,
      posterUrl = SERIES_POSTER,
      tmdbId = null,
      imdbId = imdbId,
      type = MediaType.SERIE_TV,
      year = 2007,
      durationMinutes = 22,
      seasonsCount = 12,
      rating = 8.2f,
      currentProgressMs = PROGRESS_MS,
      totalDurationMs = DURATION_MS,
      lastWatchedSeason = 8,
      lastWatchedEpisode = 1,
      lastWatchedAt = 1_700_000_000_000L
    )

  private fun episode(season: Int, ep: Int, stillUrl: String?): Episode = Episode(
    id = "${IMDB_ID}_s${season}e$ep",
    seasonNumber = season,
    episodeNumber = ep,
    title = "Episodio $ep",
    synopsis = "",
    durationMinutes = 22,
    thumbnailUrl = stillUrl,
    videoUrl = ""
  )

  private fun thumbnailOf(mediaId: String, season: Int, episode: Int): String? =
    MediaRepository.mediaList.value
      .firstOrNull { it.id == mediaId }
      ?.episodes
      ?.firstOrNull { it.seasonNumber == season && it.episodeNumber == episode }
      ?.thumbnailUrl

  private suspend fun attendi(predicate: () -> Boolean) {
    withTimeout(5_000L) {
      while (!predicate()) delay(20L)
    }
  }
}
