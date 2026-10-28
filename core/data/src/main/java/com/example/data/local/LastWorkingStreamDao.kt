package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Accesso alla tabella `last_working_streams` (cache degli ultimi flussi funzionanti).
 */
@Dao
interface LastWorkingStreamDao {

  @Query(
    "SELECT * FROM last_working_streams " +
      "WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber AND episodeNumber = :episodeNumber LIMIT 1"
  )
  suspend fun getStream(mediaId: String, seasonNumber: Int, episodeNumber: Int): LastWorkingStreamEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveStream(stream: LastWorkingStreamEntity)

  @Query(
    "DELETE FROM last_working_streams " +
      "WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber AND episodeNumber = :episodeNumber"
  )
  suspend fun deleteStream(mediaId: String, seasonNumber: Int, episodeNumber: Int)

  @Query("DELETE FROM last_working_streams")
  suspend fun clearAll()
}
