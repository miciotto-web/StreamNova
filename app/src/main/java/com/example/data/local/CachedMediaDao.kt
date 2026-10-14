package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedMediaDao {

  @Query("SELECT * FROM cached_media_items ORDER BY rating DESC")
  fun getAllCachedMediaFlow(): Flow<List<CachedMediaItemEntity>>

  @Query("SELECT * FROM cached_media_items ORDER BY rating DESC")
  suspend fun getAllCachedMedia(): List<CachedMediaItemEntity>

  @Query("SELECT * FROM cached_media_items WHERE id = :id LIMIT 1")
  suspend fun getMediaById(id: String): CachedMediaItemEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(items: List<CachedMediaItemEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(item: CachedMediaItemEntity)

  @Query("UPDATE cached_media_items SET isFavorite = :isFavorite WHERE id = :id")
  suspend fun updateFavorite(id: String, isFavorite: Boolean)

  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs WHERE id = :id")
  suspend fun updateProgress(id: String, progressMs: Long)

  @Query("UPDATE cached_media_items SET lastWatchedSeason = :season, lastWatchedEpisode = :episode WHERE id = :id")
  suspend fun updateLastWatched(id: String, season: Int, episode: Int)

  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, lastWatchedSeason = :season, lastWatchedEpisode = :episode WHERE id = :id")
  suspend fun updateTvProgress(id: String, progressMs: Long, season: Int, episode: Int)

  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, lastWatchedAt = :lastWatchedAt WHERE id = :id")
  suspend fun updateProgressWithTimestamp(id: String, progressMs: Long, lastWatchedAt: Long)

  /**
   * Aggiorna posizione **e** durata reale (misurata dal player) di un film.
   * La durata e' gia' "mergiata" a monte (mai `<= 0`): vedi `WatchProgress.resolveDuration`.
   */
  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, totalDurationMs = :totalDurationMs, lastWatchedAt = :lastWatchedAt WHERE id = :id")
  suspend fun updateProgressWithDuration(id: String, progressMs: Long, totalDurationMs: Long, lastWatchedAt: Long)

  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, lastWatchedSeason = :season, lastWatchedEpisode = :episode, lastWatchedAt = :lastWatchedAt WHERE id = :id")
  suspend fun updateTvProgressWithTimestamp(id: String, progressMs: Long, season: Int, episode: Int, lastWatchedAt: Long)

  /**
   * Aggiorna posizione, stagione/episodio corrente, timestamp e durata dell'**episodio
   * corrente**: cosi' la card "Continua a guardare" mostra i minuti reali dell'episodio
   * in corso (mai la durata della serie o di un episodio precedente).
   */
  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, totalDurationMs = :totalDurationMs, lastWatchedSeason = :season, lastWatchedEpisode = :episode, lastWatchedAt = :lastWatchedAt WHERE id = :id")
  suspend fun updateTvProgressWithDuration(id: String, progressMs: Long, totalDurationMs: Long, season: Int, episode: Int, lastWatchedAt: Long)

  @Query("SELECT COUNT(*) FROM cached_media_items")
  suspend fun count(): Int

  @Query("DELETE FROM cached_media_items")
  suspend fun clearAll()

  @Query("DELETE FROM cached_media_items WHERE id IN (:ids) OR tmdbId IN (:rawIds)")
  suspend fun deleteByIds(ids: List<String>, rawIds: List<Int>)

  @Query("DELETE FROM cached_media_items WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM cached_media_items WHERE isFavorite = 0 AND (currentProgressMs IS NULL OR currentProgressMs <= 0)")
  suspend fun clearNonFavorites()
}
