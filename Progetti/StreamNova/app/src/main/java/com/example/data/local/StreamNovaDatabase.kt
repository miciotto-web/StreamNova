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
    CachedMediaItemEntity::class,
    CatalogProgressEntity::class,
    EpisodeProgressEntity::class
  ],
  version = 6,
  exportSchema = false
)
abstract class StreamNovaDatabase : RoomDatabase() {

  abstract fun tmdbResponseCacheDao(): TmdbResponseCacheDao
  abstract fun cachedMediaDao(): CachedMediaDao
  abstract fun catalogProgressDao(): CatalogProgressDao
  abstract fun episodeProgressDao(): EpisodeProgressDao

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

    /**
     * v2 -> v3: aggiunta della tabella `catalog_progress`, che tiene traccia della
     * prossima pagina da scaricare per OGNI coppia (provider, tipo) e del
     * `total_pages` dichiarato da TMDB. La migrazione è esplicita (non distruttiva)
     * così preferiti e progressi di visione restano intatti.
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `catalog_progress` (" +
            "`providerId` INTEGER NOT NULL, " +
            "`mediaType` TEXT NOT NULL, " +
            "`nextPage` INTEGER NOT NULL, " +
            "`totalPages` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`providerId`, `mediaType`))"
        )
      }
    }

    /**
     * v3 -> v4: aggiunta della tabella `episode_progress` per la persistenza del
     * progresso di visione individuale di ciascun episodio di una serie.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `episode_progress` (" +
            "`mediaId` TEXT NOT NULL, " +
            "`seasonNumber` INTEGER NOT NULL, " +
            "`episodeNumber` INTEGER NOT NULL, " +
            "`progressMs` INTEGER NOT NULL, " +
            "`durationMs` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`mediaId`, `seasonNumber`, `episodeNumber`))"
        )
      }
    }

    /**
     * v4 -> v5: aggiunta di `lastWatchedAt` per ordinare "Continua a guardare"
     * in ordine cronologico decrescente (più recente → meno recente).
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cached_media_items ADD COLUMN lastWatchedAt INTEGER")
      }
    }

    /**
     * v5 -> v6: aggiunta di `ageRating` in `cached_media_items` per il
     * controllo genitori (classificazione d'età normalizzata TMDB).
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cached_media_items ADD COLUMN ageRating INTEGER")
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
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
