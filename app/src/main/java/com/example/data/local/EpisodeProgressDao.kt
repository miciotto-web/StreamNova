package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EpisodeProgressDao {

  @Query("SELECT * FROM episode_progress WHERE mediaId = :mediaId")
  suspend fun getProgressForMedia(mediaId: String): List<EpisodeProgressEntity>

  @Query("SELECT * FROM episode_progress")
  suspend fun getAllProgress(): List<EpisodeProgressEntity>

  @Query("SELECT * FROM episode_progress WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber AND episodeNumber = :episodeNumber LIMIT 1")
  suspend fun getEpisodeProgress(mediaId: String, seasonNumber: Int, episodeNumber: Int): EpisodeProgressEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveEpisodeProgress(progress: EpisodeProgressEntity)

  @Query("DELETE FROM episode_progress WHERE mediaId = :mediaId")
  suspend fun clearProgressForMedia(mediaId: String)
}
