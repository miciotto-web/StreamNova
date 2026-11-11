package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.StreamNovaDatabase
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Marcature manuali «visto / da vedere» dalla lista episodi della scheda:
 *  - un episodio PRECEDENTE al punto di ripresa non lo sposta all'indietro;
 *  - un episodio corrente completato porta il titolo a fine episodio;
 *  - «da vedere» ritira il completamento della serie;
 *  - «stagione già vista» completa tutte le puntate note della stagione;
 *  - «serie già vista» completa le puntate note di TUTTE le stagioni e conclude la serie;
 *  - il reset «serie da vedere» azzera righe episodio, punto di ripresa e completamento.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeManualWatchedTest {

  private lateinit var db: StreamNovaDatabase
  private lateinit var defaultFactsProvider: suspend (MediaItem) -> SeriesCompletionFacts?
  private var defaultCompletionSink: MediaRepository.LocalCompletionSink? = null

  private val minute = 60_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    // Nessun observer Room: la memoria viene aggiornata solo dalle funzioni sotto test.
    // Un observer della classe precedente può altrimenti riemettere una lista vuota e
    // cancellare le schede appena inserite.
    MediaRepository.setDatabaseForTesting(db)
    MediaRepository.resetCompletionReconciliationForTesting()
    defaultFactsProvider = MediaRepository.seriesCompletionFactsProvider
    defaultCompletionSink = MediaRepository.localCompletionSink
    // Nessuna rete e nessuna notifica SIMKL nei test delle marcature locali.
    MediaRepository.seriesCompletionFactsProvider = { null }
    MediaRepository.localCompletionSink = null
  }

  @After
  fun tearDown() {
    MediaRepository.seriesCompletionFactsProvider = defaultFactsProvider
    MediaRepository.localCompletionSink = defaultCompletionSink
    MediaRepository.resetCompletionReconciliationForTesting()
    MediaRepository.setDatabaseForTesting(null)
    db.close()
  }

  @Test
  fun `marcare un episodio precedente non sposta il punto di ripresa`() = runBlocking {
    val item = series(
      id = "manual_prev",
      lastSeason = 5,
      lastEpisode = 1,
      progressMs = 10 * minute
    )
    assertNotNull(MediaRepository.upsertExternalItem(item))

    MediaRepository.markEpisodeWatchedLocally(item.id, 1, 2)

    val media = MediaRepository.getMediaById(item.id)
    assertNotNull(media)
    assertEquals("lo stagione corrente resta S5", 5, media!!.lastWatchedSeason)
    assertEquals("l'episodio corrente resta S5E1", 1, media.lastWatchedEpisode)
    assertEquals(
      "la posizione corrente non viene toccata",
      10 * minute,
      media.currentProgressMs
    )
    assertTrue(
      "l'episodio marcato è davvero completo",
      MediaRepository.getEpisodeProgress(item.id, 1, 2) >= 50 * minute
    )
    assertTrue(
      attendi { db.episodeProgressDao().getEpisodeProgress(item.id, 1, 2)?.progressMs == 50 * minute }
    )
  }

  @Test
  fun `marcare l episodio corrente lo completa e aggiorna il titolo`() = runBlocking {
    val item = series(
      id = "manual_current",
      lastSeason = 1,
      lastEpisode = 2,
      progressMs = 10 * minute
    )
    assertNotNull(MediaRepository.upsertExternalItem(item))

    MediaRepository.markEpisodeWatchedLocally(item.id, 1, 2)

    val media = MediaRepository.getMediaById(item.id)
    assertNotNull(media)
    assertEquals(50 * minute, media!!.currentProgressMs)
    assertTrue("il titolo risulta visto per l'episodio corrente", media.isWatched)
    assertTrue(
      attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 50 * minute }
    )
    assertTrue(
      attendi { db.episodeProgressDao().getEpisodeProgress(item.id, 1, 2)?.progressMs == 50 * minute }
    )
  }

  @Test
  fun `marcare da vedere ritira il completamento della serie`() = runBlocking {
    val item = series(
      id = "manual_unwatch",
      lastSeason = 1,
      lastEpisode = 2,
      progressMs = 50 * minute
    )
    assertNotNull(MediaRepository.upsertExternalItem(item))
    MediaRepository.markSeriesCompletedFromSimkl(item.id, System.currentTimeMillis())
    // L'osservatore Room può riemettere l'upsert appena fatto: si attende lo stato
    // consolidato invece di assumere che l'ultima scrittura in memoria sia già visibile.
    assertTrue(attendi { MediaRepository.getMediaById(item.id)?.completedAt != null })

    MediaRepository.markEpisodeUnwatchedLocally(item.id, 1, 2)

    assertTrue(
      attendi {
        val media = MediaRepository.getMediaById(item.id)
        media != null && media.completedAt == null && media.currentProgressMs == 0L
      }
    )
    val media = MediaRepository.getMediaById(item.id)
    assertNotNull(media)
    assertNull("la serie non è più conclusa", media!!.completedAt)
    assertEquals("l'episodio corrente riparte da zero", 0L, media.currentProgressMs)
    assertTrue("la scheda torna in Continua a guardare", media.inContinueWatching)
    assertEquals(0L, MediaRepository.getEpisodeProgress(item.id, 1, 2))
    assertTrue(
      attendi {
        val row = db.cachedMediaDao().getMediaById(item.id)
        row != null && row.completedAt == null &&
          db.episodeProgressDao().getEpisodeProgress(item.id, 1, 2)?.progressMs == 0L
      }
    )
  }

  @Test
  fun `stagione già vista completa tutte le puntate note`() = runBlocking {
    val item = series(
      id = "manual_season",
      lastSeason = 1,
      lastEpisode = 1,
      progressMs = 5 * minute
    )
    assertNotNull(MediaRepository.upsertExternalItem(item))

    MediaRepository.markSeasonWatchedLocally(item.id, 1, listOf(1, 2, 3))

    val media = MediaRepository.getMediaById(item.id)
    assertNotNull(media)
    assertEquals("il punto di ripresa è l'ultima puntata della stagione", 3, media!!.lastWatchedEpisode)
    assertEquals(1, media.lastWatchedSeason)
    assertTrue(media.isWatched)
    (1..3).forEach { number ->
      assertEquals(
        "S1E$number completa",
        50 * minute,
        MediaRepository.getEpisodeProgress(item.id, 1, number)
      )
    }
    assertTrue(
      attendi {
        (1..3).all { number ->
          db.episodeProgressDao().getEpisodeProgress(item.id, 1, number)?.progressMs == 50 * minute
        }
      }
    )
  }

  @Test
  fun `serie già vista completa le stagioni note e conclude la serie`() = runBlocking {
    val item = series(
      id = "manual_series",
      lastSeason = 1,
      lastEpisode = 1,
      progressMs = 5 * minute,
      seasonEpisodesCount = mapOf(1 to 3, 2 to 2)
    )
    assertNotNull(MediaRepository.upsertExternalItem(item))

    MediaRepository.markSeriesWatchedLocally(item.id)

    assertTrue(
      attendi {
        val media = MediaRepository.getMediaById(item.id)
        media != null && media.completedAt != null && !media.inContinueWatching
      }
    )
    val media = MediaRepository.getMediaById(item.id)!!
    assertEquals("la serie risulta vista al titolo", 50 * minute, media.currentProgressMs)
    listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2).forEach { (season, episode) ->
      assertEquals(
        "S${season}E$episode completa",
        50 * minute,
        MediaRepository.getEpisodeProgress(item.id, season, episode)
      )
    }
    assertTrue(
      attendi {
        db.episodeProgressDao().getProgressForMedia(item.id).count { it.progressMs == 50 * minute } == 5
      }
    )
  }

  @Test
  fun `smarcare un episodio non corrente ritira anche il visto del titolo`() = runBlocking {
    val item = series(
      id = "manual_unwatch_other",
      lastSeason = 5,
      lastEpisode = 1,
      progressMs = 10 * minute
    )
    assertNotNull(MediaRepository.upsertExternalItem(item))
    MediaRepository.markSeriesCompletedFromSimkl(item.id, System.currentTimeMillis())
    assertTrue(attendi { MediaRepository.getMediaById(item.id)?.completedAt != null })

    MediaRepository.markEpisodeUnwatchedLocally(item.id, 1, 1)

    val media = MediaRepository.getMediaById(item.id)
    assertNotNull(media)
    assertNull("la serie non è più conclusa", media!!.completedAt)
    assertEquals("lo stato «visto» del titolo viene ritirato", 0L, media.currentProgressMs)
    assertFalse("il punto di ripresa resta S5E1", media.inContinueWatching)
    assertEquals(
      "il punto di ripresa non viene spostato all'indietro",
      5,
      media.lastWatchedSeason
    )
    assertEquals(1, media.lastWatchedEpisode)
  }

  @Test
  fun `serie già vista usa i conteggi TMDB per le stagioni non ancora caricate`() = runBlocking {
    // Caso reale delle schede Stremio/IMDb: in memoria ci sono solo gli episodi generati
    // della S1, ma la scheda conosce le stagioni TMDB. La marcatura deve coprirle tutte.
    val item = series(
      id = "manual_series_tmdb_counts",
      lastSeason = 1,
      lastEpisode = 1,
      progressMs = 5 * minute,
      seasonEpisodesCount = emptyMap()
    ).copy(seasonsCount = 2)
    assertNotNull(MediaRepository.upsertExternalItem(item))

    MediaRepository.markSeriesWatchedLocally(
      item.id,
      seasonEpisodeCounts = mapOf(1 to 3, 2 to 4),
      totalSeasons = 2
    )

    listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3, 2 to 4).forEach { (season, episode) ->
      assertTrue(
        "S${season}E$episode deve risultare vista",
        MediaRepository.getEpisodeProgress(item.id, season, episode) > 0L
      )
    }
    assertTrue(
      attendi { db.episodeProgressDao().getProgressForMedia(item.id).count { it.progressMs > 0L } == 7 }
    )
  }

  @Test
  fun `serie da vedere azzera righe episodio punto di ripresa e completamento`() = runBlocking {
    val item = series(
      id = "manual_series_reset",
      lastSeason = 1,
      lastEpisode = 1,
      progressMs = 5 * minute,
      seasonEpisodesCount = mapOf(1 to 3)
    )
    assertNotNull(MediaRepository.upsertExternalItem(item))
    MediaRepository.markSeriesWatchedLocally(item.id)
    assertTrue(attendi { MediaRepository.getMediaById(item.id)?.completedAt != null })

    MediaRepository.clearSeriesWatchedLocally(item.id)

    val media = MediaRepository.getMediaById(item.id)
    assertNotNull(media)
    assertNull(media!!.completedAt)
    assertNull(media.lastWatchedSeason)
    assertNull(media.lastWatchedEpisode)
    assertEquals(0L, media.currentProgressMs)
    assertFalse(media.inContinueWatching)
    (1..3).forEach { number ->
      assertEquals(0L, MediaRepository.getEpisodeProgress(item.id, 1, number))
    }
    assertTrue(
      attendi {
        val row = db.cachedMediaDao().getMediaById(item.id)
        row != null && row.completedAt == null && row.lastWatchedSeason == null &&
          row.currentProgressMs == 0L &&
          db.episodeProgressDao().getProgressForMedia(item.id).isEmpty()
      }
    )
  }

  @Test
  fun `serie già vista allinea anche la scheda gemella e il reset le azzera entrambe`() = runBlocking {
    val tmdbCard = series(
      id = "twin_tmdb",
      lastSeason = 1,
      lastEpisode = 1,
      progressMs = 0L,
      seasonEpisodesCount = mapOf(1 to 3)
    ).copy(title = "Twin Show", originalTitle = "Twin Show", tmdbId = 700_001)
    val imdbCard = series(
      id = "twin_imdb",
      lastSeason = 1,
      lastEpisode = 1,
      progressMs = 0L,
      seasonEpisodesCount = mapOf(1 to 3)
    ).copy(title = "Twin Show", originalTitle = "Twin Show", tmdbId = 700_002, imdbId = "tt7000001")
    assertNotNull(MediaRepository.upsertExternalItem(tmdbCard))
    assertNotNull(MediaRepository.upsertExternalItem(imdbCard))

    MediaRepository.markSeriesWatchedLocally(tmdbCard.id, mapOf(1 to 3), 1)

    assertTrue(
      attendi {
        MediaRepository.getMediaById(tmdbCard.id)?.completedAt != null &&
          MediaRepository.getMediaById(imdbCard.id)?.completedAt != null
      }
    )
    listOf(tmdbCard.id, imdbCard.id).forEach { id ->
      assertEquals(
        "la gemella $id deve mostrare le stesse puntate viste",
        50 * minute,
        MediaRepository.getEpisodeProgress(id, 1, 1)
      )
      assertEquals(50 * minute, MediaRepository.getEpisodeProgress(id, 1, 2))
      assertEquals(50 * minute, MediaRepository.getEpisodeProgress(id, 1, 3))
    }
    assertTrue(
      attendi {
        listOf(tmdbCard.id, imdbCard.id).all { id ->
          db.episodeProgressDao().getProgressForMedia(id).count { it.progressMs > 0L } == 3
        }
      }
    )

    // Smarcare UNA puntata su una scheda deve smarcarla (e ritirare il completamento)
    // anche sulla gemella: altrimenti l'altra scheda continuerebbe a dire «Serie vista».
    MediaRepository.markEpisodeUnwatchedLocally(tmdbCard.id, 1, 1)
    assertTrue(
      attendi {
        listOf(tmdbCard.id, imdbCard.id).all { id ->
          MediaRepository.getEpisodeProgress(id, 1, 1) == 0L &&
            MediaRepository.getMediaById(id)?.completedAt == null
        }
      }
    )

    MediaRepository.clearSeriesWatchedLocally(tmdbCard.id)

    assertTrue(
      attendi {
        listOf(tmdbCard.id, imdbCard.id).all { id ->
          val media = MediaRepository.getMediaById(id)
          media != null && media.completedAt == null &&
            db.episodeProgressDao().getProgressForMedia(id).isEmpty()
        }
      }
    )
  }

  // ── Fixture ──────────────────────────────────────────────────────────────

  private fun series(
    id: String,
    lastSeason: Int,
    lastEpisode: Int,
    progressMs: Long,
    seasonEpisodesCount: Map<Int, Int> = mapOf(1 to 3)
  ): MediaItem = MediaItem(
    id = id,
    tmdbId = id.hashCode().let { if (it < 0) -it else it } + 1,
    title = "Serie $id",
    originalTitle = "Series $id",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = 2020,
    durationMinutes = 50,
    totalDurationMs = 50 * minute,
    seasonsCount = seasonEpisodesCount.keys.maxOrNull() ?: 1,
    seasonEpisodesCount = seasonEpisodesCount,
    lastWatchedSeason = lastSeason,
    lastWatchedEpisode = lastEpisode,
    currentProgressMs = progressMs,
    inContinueWatching = true,
    episodes = (1..3).map { number ->
      Episode(
        id = "${id}_s1e$number",
        seasonNumber = 1,
        episodeNumber = number,
        title = "Episodio $number",
        synopsis = "",
        durationMinutes = 50,
        videoUrl = "",
        totalDurationMs = 50 * minute
      )
    }
  )

  private suspend fun attendi(timeoutMs: Long = 5_000L, condizione: suspend () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condizione()) return true
      delay(25)
    }
    return condizione()
  }
}
