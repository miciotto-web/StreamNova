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

  @Query("SELECT COUNT(*) FROM cached_media_items")
  suspend fun count(): Int

  @Query("DELETE FROM cached_media_items")
  suspend fun clearAll()
}
