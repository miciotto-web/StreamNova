package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    TmdbResponseCacheEntity::class,
    CachedMediaItemEntity::class
  ],
  version = 2,
  exportSchema = false
)
abstract class StreamNovaDatabase : RoomDatabase() {

  abstract fun tmdbResponseCacheDao(): TmdbResponseCacheDao
  abstract fun cachedMediaDao(): CachedMediaDao

  companion object {

    /**
     * v1 -> v2: aggiunta delle colonne che preservano lo stato di visione utente
     * (stagione/episodio visti) senza perdere preferiti e progressi esistenti.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cached_media_items ADD COLUMN lastWatchedSeason INTEGER")
        db.execSQL("ALTER TABLE cached_media_items ADD COLUMN lastWatchedEpisode INTEGER")
      }
    }

    @Volatile
    private var INSTANCE: StreamNovaDatabase? = null

    fun init(context: Context): StreamNovaDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          StreamNovaDatabase::class.java,
          "streamnova_database.db"
        )
          .addMigrations(MIGRATION_1_2)
          .fallbackToDestructiveMigration(dropAllTables = true)
          .build()
          .also { INSTANCE = it }
      }
    }

    fun getInstance(context: Context? = null): StreamNovaDatabase? {
      if (INSTANCE != null) return INSTANCE
      if (context != null) return init(context)
      return null
    }

    fun setInstanceForTesting(db: StreamNovaDatabase?) {
      INSTANCE = db
    }
  }
}
