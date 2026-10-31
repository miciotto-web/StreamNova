package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.StreamNovaDatabase
import com.example.data.local.MediaCacheMapper.toEntity
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rimozione **persistente** delle serie concluse da «Continua a guardare».
 *
 * Verifica il percorso reale repository + Room:
 *  - fine stagione ≠ fine serie (nessuna rimozione prematura);
 *  - serie in corso / metadati incompleti → card conservata;
 *  - serie conclusa → posizione azzerata in memoria E su Room (sopravvive al riavvio);
 *  - riconciliazione all'avvio conservativa (solo posizioni arrivate davvero a fine).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaRepositorySeriesCompletionTest {

  private lateinit var db: StreamNovaDatabase
  private lateinit var defaultFactsProvider: suspend (MediaItem) -> SeriesCompletionFacts?

  private val minute = 60_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    MediaRepository.init(context, db)
    MediaRepository.resetCompletionReconciliationForTesting()
    defaultFactsProvider = MediaRepository.seriesCompletionFactsProvider
  }

  @After
  fun tearDown() {
    MediaRepository.seriesCompletionFactsProvider = defaultFactsProvider
    MediaRepository.resetCompletionReconciliationForTesting()
    db.close()
  }

  @Test
  fun completedSeriesIsRemovedFromContinueWatchingAndPersisted() = runBlocking {
    val item = series(id = "tmdb_tv_91001", tmdbId = 91001)
    assertNotNull(MediaRepository.upsertExternalItem(item))
    MediaRepository.updateEpisodeProgress(item.id, 1, 10, 50 * minute, 50 * minute)
    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 50 * minute }
    attendi { MediaRepository.getMediaById(item.id)?.currentProgressMs == 50 * minute }

    MediaRepository.seriesCompletionFactsProvider = { completedFacts(seasons = 1, episodes = mapOf(1 to 10)) }

    assertTrue(MediaRepository.completeSeriesIfFinished(item.id, 1, 10))
    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 0L }
    attendi { MediaRepository.getMediaById(item.id)?.currentProgressMs == 0L }

    assertTrue(
      "la card non deve più comparire in Continua a guardare",
      MediaRepository.getContinueWatching().none { it.id == item.id }
    )
    val row = db.cachedMediaDao().getMediaById(item.id)
    assertNotNull(row)
    assertEquals("posizione azzerata in modo persistente", 0L, row!!.currentProgressMs)
    assertEquals("l'episodio visto resta noto", 1, row.lastWatchedSeason)
    assertEquals(10, row.lastWatchedEpisode)

    // "Riavvio": la riga Room è la fonte persistente e non fa riapparire la card.
    MediaRepository.loadFromCache()
    attendi { MediaRepository.mediaList.value.any { it.id == item.id } }
    assertTrue(
      "nessuna ricomparsa dopo il riavvio",
      MediaRepository.getContinueWatching().none { it.id == item.id }
    )
  }

  @Test
  fun seasonFinaleIsNotSeriesFinale() = runBlocking {
    val item = series(id = "tmdb_tv_91002", tmdbId = 91002)
    assertNotNull(MediaRepository.upsertExternalItem(item))
    MediaRepository.updateEpisodeProgress(item.id, 1, 10, 50 * minute, 50 * minute)
    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 50 * minute }
    attendi { MediaRepository.getMediaById(item.id)?.currentProgressMs == 50 * minute }

    // S1E10 è l'ultimo episodio della S1, ma esiste la stagione 2.
    MediaRepository.seriesCompletionFactsProvider = {
      completedFacts(seasons = 2, episodes = mapOf(1 to 10, 2 to 10))
    }

    assertFalse(MediaRepository.completeSeriesIfFinished(item.id, 1, 10))
    assertTrue(
      "fine stagione non è fine serie: la card resta",
      MediaRepository.getContinueWatching().any { it.id == item.id }
    )
    assertTrue(db.cachedMediaDao().getMediaById(item.id)!!.currentProgressMs > 0L)
  }

  @Test
  fun returningSeriesWithFutureEpisodesIsKept() = runBlocking {
    val item = series(id = "tmdb_tv_91003", tmdbId = 91003)
    assertNotNull(MediaRepository.upsertExternalItem(item))
    MediaRepository.updateEpisodeProgress(item.id, 1, 10, 50 * minute, 50 * minute)
    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 50 * minute }
    attendi { MediaRepository.getMediaById(item.id)?.currentProgressMs == 50 * minute }

    MediaRepository.seriesCompletionFactsProvider = {
      completedFacts(seasons = 1, episodes = mapOf(1 to 10), status = "Returning Series")
    }

    assertFalse(MediaRepository.completeSeriesIfFinished(item.id, 1, 10))
    assertTrue(MediaRepository.getContinueWatching().any { it.id == item.id })
  }

  @Test
  fun unverifiableCompletionNeverRemovesTheSeries() = runBlocking {
    val item = series(id = "tmdb_tv_91004", tmdbId = 91004)
    assertNotNull(MediaRepository.upsertExternalItem(item))
    MediaRepository.updateEpisodeProgress(item.id, 1, 10, 50 * minute, 50 * minute)
    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 50 * minute }
    attendi { MediaRepository.getMediaById(item.id)?.currentProgressMs == 50 * minute }

    MediaRepository.seriesCompletionFactsProvider = { null }

    assertFalse(MediaRepository.completeSeriesIfFinished(item.id, 1, 10))
    assertTrue(MediaRepository.getContinueWatching().any { it.id == item.id })
    assertTrue(db.cachedMediaDao().getMediaById(item.id)!!.currentProgressMs > 0L)
  }

  @Test
  fun aDifferentEpisodeStartedMeanwhilePreventsRemoval() = runBlocking {
    val item = series(id = "tmdb_tv_91005", tmdbId = 91005)
    assertNotNull(MediaRepository.upsertExternalItem(item))
    MediaRepository.updateEpisodeProgress(item.id, 1, 10, 50 * minute, 50 * minute)
    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 50 * minute }
    attendi { MediaRepository.getMediaById(item.id)?.currentProgressMs == 50 * minute }
    // L'utente ha già ripreso la puntata successiva: lo stato salvato non è più S1E10.
    MediaRepository.updateEpisodeProgress(item.id, 2, 1, 3 * minute, 50 * minute)

    MediaRepository.seriesCompletionFactsProvider = { completedFacts(seasons = 1, episodes = mapOf(1 to 10)) }

    assertFalse(MediaRepository.completeSeriesIfFinished(item.id, 1, 10))
    assertTrue(MediaRepository.getContinueWatching().any { it.id == item.id })
  }

  @Test
  fun startupReconciliationRemovesAnAlreadyEndedSeries() = runBlocking {
    val item = series(id = "tmdb_tv_91006", tmdbId = 91006)
    assertNotNull(MediaRepository.upsertExternalItem(item))
    MediaRepository.updateEpisodeProgress(item.id, 1, 10, 50 * minute, 50 * minute)
    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 50 * minute }
    attendi { MediaRepository.getMediaById(item.id)?.currentProgressMs == 50 * minute }

    MediaRepository.seriesCompletionFactsProvider = { completedFacts(seasons = 1, episodes = mapOf(1 to 10)) }
    MediaRepository.reconcileCompletedSeries()

    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 0L }
    assertEquals(0L, db.cachedMediaDao().getMediaById(item.id)!!.currentProgressMs)
    assertTrue(MediaRepository.getContinueWatching().none { it.id == item.id })
  }

  @Test
  fun reconciliationIgnoresAnAlmostAtEndPositionBasedOnAnEstimate() = runBlocking {
    val item = series(id = "tmdb_tv_91007", tmdbId = 91007)
    assertNotNull(MediaRepository.upsertExternalItem(item))
    // 49:55 su 50:00: "quasi alla fine", ma non è la prova richiesta dalla riconciliazione.
    MediaRepository.updateEpisodeProgress(item.id, 1, 10, 50 * minute - 5_000L, 50 * minute)
    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 50 * minute - 5_000L }

    MediaRepository.seriesCompletionFactsProvider = { completedFacts(seasons = 1, episodes = mapOf(1 to 10)) }
    MediaRepository.reconcileCompletedSeries()

    assertTrue(
      "nessuna rimozione basata su una posizione non arrivata esattamente a fine",
      MediaRepository.getContinueWatching().any { it.id == item.id }
    )
    assertTrue(db.cachedMediaDao().getMediaById(item.id)!!.currentProgressMs > 0L)
  }

  @Test
  fun appStartClearsAPersistedFinishedSeries() = runBlocking {
    val item = series(id = "tmdb_tv_91008", tmdbId = 91008).copy(
      currentProgressMs = 50 * minute,
      totalDurationMs = 50 * minute,
      lastWatchedSeason = 1,
      lastWatchedEpisode = 10
    )
    db.cachedMediaDao().insertOrUpdate(item.toEntity())

    MediaRepository.seriesCompletionFactsProvider = { completedFacts(seasons = 1, episodes = mapOf(1 to 10)) }
    MediaRepository.resetCompletionReconciliationForTesting()
    // Percorso reale di avvio: riconciliazione dalle righe persistite.
    MediaRepository.loadFromCache()

    attendi { db.cachedMediaDao().getMediaById(item.id)?.currentProgressMs == 0L }
    val row = db.cachedMediaDao().getMediaById(item.id)
    assertNotNull(row)
    assertEquals("la card non riappare dopo il riavvio", 0L, row!!.currentProgressMs)
  }

  // ── Fixture ──────────────────────────────────────────────────────────────

  private fun series(id: String, tmdbId: Int): MediaItem = MediaItem(
    id = id,
    tmdbId = tmdbId,
    title = "Serie $tmdbId",
    originalTitle = "Series $tmdbId",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = 2020,
    durationMinutes = 50,
    totalDurationMs = 50 * minute,
    seasonsCount = 1,
    lastWatchedSeason = 1,
    lastWatchedEpisode = 10,
    episodes = (1..10).map { number ->
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

  private fun completedFacts(
    seasons: Int,
    episodes: Map<Int, Int>,
    status: String = "Ended"
  ) = SeriesCompletionFacts(
    seasonsCount = seasons,
    seasonEpisodeCounts = episodes,
    status = status
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
