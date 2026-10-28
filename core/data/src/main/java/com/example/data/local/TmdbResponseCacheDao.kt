package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TmdbResponseCacheDao {

  @Query("SELECT * FROM tmdb_response_cache WHERE endpointKey = :key LIMIT 1")
  suspend fun getCache(key: String): TmdbResponseCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertCache(entry: TmdbResponseCacheEntity)

  @Query("DELETE FROM tmdb_response_cache WHERE endpointKey = :key")
  suspend fun deleteCache(key: String)

  @Query("DELETE FROM tmdb_response_cache WHERE (:currentTime - cachedAt) > ttlMillis")
  suspend fun deleteExpired(currentTime: Long = System.currentTimeMillis()): Int

  @Query("DELETE FROM tmdb_response_cache")
  suspend fun clearAll()
}
