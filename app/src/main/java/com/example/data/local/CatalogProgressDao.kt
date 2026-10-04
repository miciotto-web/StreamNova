package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Storage del progresso di paginazione provider (provider × tipo).
 * Nessun limite sul numero di righe: la progressione dipende solo da
 * `total_pages` di TMDB e da MAX_PROVIDER_PAGES.
 */
@Dao
interface CatalogProgressDao {

  @Query("SELECT * FROM catalog_progress")
  suspend fun getAll(): List<CatalogProgressEntity>

  @Query("SELECT * FROM catalog_progress WHERE providerId = :providerId AND mediaType = :mediaType LIMIT 1")
  suspend fun get(providerId: Int, mediaType: String): CatalogProgressEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entry: CatalogProgressEntity)

  @Query("DELETE FROM catalog_progress")
  suspend fun clearAll()
}
