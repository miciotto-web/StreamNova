package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.CachedMediaItemEntity
import com.example.data.local.EpisodeProgressEntity
import com.example.data.local.MediaCacheMapper.toMediaItem
import com.example.data.local.StreamNovaDatabase
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Persistenza Room del progresso per episodio.
 *
 * La chiave è composita (mediaId, stagione, episodio): due puntate della stessa serie
 * conservano posizioni indipendenti e un riavvio dell'app le recupera entrambe.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EpisodeProgressPersistenceTest {

  private lateinit var database: StreamNovaDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    MediaRepository.setDatabaseForTesting(database)
  }

  @After
  fun tearDown() {
    MediaRepository.setDatabaseForTesting(null)
    database.close()
  }

  @Test
  fun `S05E06 e S01E01 mantengono progressi indipendenti dopo il riavvio`() = runBlocking {
    database.cachedMediaDao().insertOrUpdate(tbbtEntity())

    MediaRepository.updateEpisodeProgress(MEDIA_ID, 1, 1, S1E1_POSITION_MS, EPISODE_DURATION_MS)
    MediaRepository.updateEpisodeProgress(MEDIA_ID, 5, 6, S5E6_POSITION_MS, EPISODE_DURATION_MS)

    awaitEpisodeProgress(1, 1, S1E1_POSITION_MS)
    awaitEpisodeProgress(5, 6, S5E6_POSITION_MS)

    // Simula il riavvio del processo: la memoria viene ricostruita SOLO da Room.
    MediaRepository.reloadEpisodeProgressForTesting()

    assertEquals(S1E1_POSITION_MS, MediaRepository.getEpisodeProgress(MEDIA_ID, 1, 1))
    assertEquals(S5E6_POSITION_MS, MediaRepository.getEpisodeProgress(MEDIA_ID, 5, 6))
    assertEquals(EPISODE_DURATION_MS, MediaRepository.getEpisodeDurationMs(MEDIA_ID, 5, 6))
    assertEquals(
      "la posizione di S1E1 non viene contaminata da S5E6",
      S1E1_POSITION_MS,
      database.episodeProgressDao().getEpisodeProgress(MEDIA_ID, 1, 1)?.progressMs
    )
  }

  @Test
  fun `la riga titolo conserva episodio e posizione dell'ultima puntata vista`() = runBlocking {
    database.cachedMediaDao().insertOrUpdate(tbbtEntity())

    MediaRepository.updateEpisodeProgress(MEDIA_ID, 5, 6, S5E6_POSITION_MS, EPISODE_DURATION_MS)
    awaitEpisodeProgress(5, 6, S5E6_POSITION_MS)
    awaitTvRow(5, 6, S5E6_POSITION_MS)

    val row = database.cachedMediaDao().getMediaById(MEDIA_ID)
    assertNotNull(row)
    assertEquals(5, row?.lastWatchedSeason)
    assertEquals(6, row?.lastWatchedEpisode)
    assertEquals(S5E6_POSITION_MS, row?.currentProgressMs)
    assertEquals(EPISODE_DURATION_MS, row?.totalDurationMs)
  }

  @Test
  fun `dati Room incompleti non impediscono la ripresa dell'episodio corretto`() = runBlocking {
    // Riga titolo presente, tabella episode_progress vuota (record precedente alla v4).
    database.cachedMediaDao().insertOrUpdate(
      tbbtEntity(
        currentProgressMs = S5E6_POSITION_MS,
        totalDurationMs = EPISODE_DURATION_MS,
        lastWatchedSeason = 5,
        lastWatchedEpisode = 6
      )
    )

    MediaRepository.reloadEpisodeProgressForTesting()
    assertEquals("nessun progresso per episodio persistito", 0L, MediaRepository.getEpisodeProgress(MEDIA_ID, 5, 6))

    val media = tbbtMediaFromRow()
    val resolved = EpisodeResumeResolver.resolveWithLookup(
      media = media,
      progressOf = { season, episode -> MediaRepository.getEpisodeProgress(media.id, season, episode) },
      durationOf = { season, episode -> MediaRepository.getEpisodeDurationMs(media.id, season, episode) }
    )

    assertEquals(5, resolved?.seasonNumber)
    assertEquals(6, resolved?.episodeNumber)
    assertEquals(
      "record legacy: la posizione aggregata appartiene all'ultima puntata vista",
      S5E6_POSITION_MS,
      resolved?.currentProgressMs
    )
    assertEquals(EPISODE_DURATION_MS, resolved?.totalDurationMs)
  }

  @Test
  fun `una posizione non valida non azzera quella gia persistita`() = runBlocking {
    database.cachedMediaDao().insertOrUpdate(tbbtEntity())
    MediaRepository.updateEpisodeProgress(MEDIA_ID, 5, 6, S5E6_POSITION_MS, EPISODE_DURATION_MS)
    awaitEpisodeProgress(5, 6, S5E6_POSITION_MS)

    // Durata non ancora nota dal player (0): non deve cancellare quella reale.
    MediaRepository.updateEpisodeProgress(MEDIA_ID, 5, 6, S5E6_POSITION_MS + 60_000L, 0L)
    awaitEpisodeProgress(5, 6, S5E6_POSITION_MS + 60_000L)

    assertEquals(
      "la durata nota resta quella misurata",
      EPISODE_DURATION_MS,
      database.episodeProgressDao().getEpisodeProgress(MEDIA_ID, 5, 6)?.durationMs
    )
  }

  @Test
  fun `la migrazione Room preserva i progressi esistenti`() = runBlocking {
    // La tabella nasce con la migrazione 3->4 e la chiave composita: inserimenti
    // indipendenti restano separati anche per stagioni molto alte.
    database.episodeProgressDao().saveEpisodeProgress(
      EpisodeProgressEntity(MEDIA_ID, 18, 5, 7 * 60_000L, EPISODE_DURATION_MS)
    )
    database.episodeProgressDao().saveEpisodeProgress(
      EpisodeProgressEntity(MEDIA_ID, 1, 1, 12 * 60_000L, EPISODE_DURATION_MS)
    )

    MediaRepository.reloadEpisodeProgressForTesting()

    assertEquals(7 * 60_000L, MediaRepository.getEpisodeProgress(MEDIA_ID, 18, 5))
    assertEquals(12 * 60_000L, MediaRepository.getEpisodeProgress(MEDIA_ID, 1, 1))
  }

  @Test
  fun `dal record Room alla card la ripresa resta S05E06`() = runBlocking {
    database.cachedMediaDao().insertOrUpdate(
      tbbtEntity(
        currentProgressMs = S5E6_POSITION_MS,
        totalDurationMs = EPISODE_DURATION_MS,
        lastWatchedSeason = 5,
        lastWatchedEpisode = 6
      )
    )
    database.episodeProgressDao().saveEpisodeProgress(
      EpisodeProgressEntity(MEDIA_ID, 5, 6, S5E6_POSITION_MS, EPISODE_DURATION_MS)
    )
    MediaRepository.reloadEpisodeProgressForTesting()

    // Stessa conversione usata da mapEntitiesToUiState per la card di «Continua a guardare».
    val media = database.cachedMediaDao().getMediaById(MEDIA_ID)!!.toMediaItem()
    assertEquals(5, media.lastWatchedSeason)
    assertEquals(6, media.lastWatchedEpisode)
    assert(media.episodes.none { it.seasonNumber == 5 && it.episodeNumber == 6 }) {
      "la lista generata è troncata: S5E6 non deve esserci"
    }

    val resolved = EpisodeResumeResolver.resolveWithLookup(
      media = media,
      progressOf = { season, episode -> MediaRepository.getEpisodeProgress(media.id, season, episode) },
      durationOf = { season, episode -> MediaRepository.getEpisodeDurationMs(media.id, season, episode) }
    )

    assertEquals(5, resolved?.seasonNumber)
    assertEquals(6, resolved?.episodeNumber)
    assertEquals(S5E6_POSITION_MS, resolved?.currentProgressMs)
    assertEquals(EPISODE_DURATION_MS, resolved?.totalDurationMs)
  }

  // ── Helper ───────────────────────────────────────────────────────────────

  private suspend fun awaitEpisodeProgress(season: Int, episode: Int, expected: Long) {
    withTimeout(5_000L) {
      while (database.episodeProgressDao().getEpisodeProgress(MEDIA_ID, season, episode)?.progressMs != expected) {
        delay(20L)
      }
    }
  }

  private suspend fun awaitTvRow(season: Int, episode: Int, expected: Long) {
    withTimeout(5_000L) {
      while (true) {
        val row = database.cachedMediaDao().getMediaById(MEDIA_ID)
        if (row?.lastWatchedSeason == season &&
          row.lastWatchedEpisode == episode &&
          row.currentProgressMs == expected
        ) {
          return@withTimeout
        }
        delay(20L)
      }
    }
  }

  private fun tbbtEntity(
    currentProgressMs: Long = 0L,
    totalDurationMs: Long = 0L,
    lastWatchedSeason: Int? = null,
    lastWatchedEpisode: Int? = null
  ): CachedMediaItemEntity = CachedMediaItemEntity(
    id = MEDIA_ID,
    tmdbId = 1418,
    title = "The Big Bang Theory",
    originalTitle = "The Big Bang Theory",
    synopsis = "",
    videoUrl = "",
    resolution = "UHD_4K",
    qualityTags = "4K,HDR",
    backdropUrl = null,
    posterUrl = null,
    logoUrl = null,
    type = MediaType.SERIE_TV.name,
    year = 2007,
    durationMinutes = 22,
    seasonsCount = 12,
    rating = 8.2f,
    genres = "Commedia",
    director = null,
    cast = "",
    provider = "hbo",
    currentProgressMs = currentProgressMs,
    totalDurationMs = totalDurationMs,
    lastWatchedSeason = lastWatchedSeason,
    lastWatchedEpisode = lastWatchedEpisode
  )

  private suspend fun tbbtMediaFromRow(): MediaItem {
    val row = database.cachedMediaDao().getMediaById(MEDIA_ID)!!
    return MediaItem(
      id = row.id,
      tmdbId = row.tmdbId,
      title = row.title,
      synopsis = row.synopsis,
      videoUrl = row.videoUrl,
      type = MediaType.SERIE_TV,
      seasonsCount = row.seasonsCount,
      durationMinutes = row.durationMinutes,
      currentProgressMs = row.currentProgressMs,
      totalDurationMs = row.totalDurationMs,
      lastWatchedSeason = row.lastWatchedSeason,
      lastWatchedEpisode = row.lastWatchedEpisode,
      // Lista troncata: S5E6 NON è presente (come nel catalogo reale).
      episodes = MediaRepository.generateEpisodesForTv(1418, row.title, null, null, 12)
    )
  }

  private companion object {
    const val MEDIA_ID = "tmdb_tv_1418"
    const val S1E1_POSITION_MS = 12 * 60_000L
    const val S5E6_POSITION_MS = 17 * 60_000L + 32_000L
    const val EPISODE_DURATION_MS = 22 * 60_000L
  }
}
