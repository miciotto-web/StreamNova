package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for caching raw TMDB API endpoint JSON responses.
 * Prevents redundant HTTP calls and enables offline availability.
 */
@Entity(tableName = "tmdb_response_cache")
data class TmdbResponseCacheEntity(
  @PrimaryKey val endpointKey: String,
  val jsonResponse: String,
  val cachedAt: Long = System.currentTimeMillis(),
  val ttlMillis: Long = 12 * 60 * 60 * 1000L // 12 hours TTL by default
) {
  fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
    return (currentTime - cachedAt) > ttlMillis
  }
}
