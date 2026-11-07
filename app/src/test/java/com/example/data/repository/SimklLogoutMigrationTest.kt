package com.example.data.repository

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.StreamNovaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migrazione v9 -> v10 (colonne di provenienza): eseguita su un database SQLite creato
 * con lo schema v9 REALE, con dentro righe storiche. Verifica che:
 *  - le quattro colonne di provenienza esistano dopo la migrazione, con i default
 *    attesi dalla validazione schema di Room (`NULL` per i TEXT, `0` per il flag);
 *  - i dati storici sopravvivano INTATTI (preferiti, progressi scheda, progressi episodio);
 *  - i record pre-migrazione risultino di provenienza LOCALE (`NULL`/`0`): la loro origine
 *    non è ricostruibile dal database v9 e NON viene inventata — restano di proprietà
 *    locale, quindi MAI rimossi dal logout SIMKL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimklLogoutMigrationTest {

  @Test
  fun laMigrazioneAggiungeLeColonneDiProvenienzaSenzaPerdereDati() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
      .name(null) // database in memoria
      .callback(object : SupportSQLiteOpenHelper.Callback(9) {
        override fun onCreate(db: SupportSQLiteDatabase) {
          db.execSQL(V9_CACHED_MEDIA)
          db.execSQL(V9_EPISODE_PROGRESS)
          // Riga storica: preferito + progresso scheda, prima della tracciatura.
          db.execSQL(
            "INSERT INTO cached_media_items (" +
              "id, tmdbId, title, originalTitle, synopsis, videoUrl, resolution, qualityTags, " +
              "type, year, durationMinutes, rating, genres, \"cast\", isTrending, isTop10, " +
              "isFavorite, currentProgressMs, totalDurationMs, cachedAt) VALUES (" +
              "'tmdb_m_9000', 9000, 'Storico', '', 'sin', '', 'UHD_4K', '4K', 'FILM', 2020, " +
              "100, 8.0, 'Azione', 'Cast', 0, 0, 1, 300000, 6000000, 1)"
          )
          // Progresso episodio storico della stessa serie-visione.
          db.execSQL(
            "INSERT INTO episode_progress " +
              "(mediaId, seasonNumber, episodeNumber, progressMs, durationMs, updatedAt) " +
              "VALUES ('tmdb_m_9000', 1, 1, 120000, 600000, 1)"
          )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
      })
      .build()
    val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
    val db = helper.writableDatabase
    try {
      assertEquals(9, db.version)
      StreamNovaDatabase.MIGRATION_9_10.migrate(db)

      // Colonne di provenienza presenti, con i default richiesti dalla validazione Room.
      val cachedColumns = columnDefaults(db, "cached_media_items")
      assertTrue(cachedColumns.containsKey("favoriteSource"))
      assertTrue(cachedColumns.containsKey("progressSource"))
      assertTrue(cachedColumns.containsKey("importedFromSimkl"))
      assertEquals(null, cachedColumns["favoriteSource"])
      assertEquals(null, cachedColumns["progressSource"])
      assertEquals("0", cachedColumns["importedFromSimkl"])
      val episodeColumns = columnDefaults(db, "episode_progress")
      assertTrue(episodeColumns.containsKey("source"))
      assertEquals(null, episodeColumns["source"])

      // I dati storici sono intatti e di provenienza LOCALE (NULL/0), non "SIMKL".
      db.query(
        "SELECT id, isFavorite, currentProgressMs, favoriteSource, progressSource, importedFromSimkl " +
          "FROM cached_media_items"
      ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("tmdb_m_9000", cursor.getString(0))
        assertEquals(1L, cursor.getLong(1))
        assertEquals(300000L, cursor.getLong(2))
        assertNull(cursor.getString(3))
        assertNull(cursor.getString(4))
        assertEquals(0L, cursor.getLong(5))
        assertFalse(cursor.moveToNext())
      }
      db.query(
        "SELECT mediaId, progressMs, source FROM episode_progress"
      ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("tmdb_m_9000", cursor.getString(0))
        assertEquals(120000L, cursor.getLong(1))
        assertNull(cursor.getString(2))
        assertFalse(cursor.moveToNext())
      }

      // Le nuove colonne sono scrivibili (il percorso normale di provenienza funziona).
      db.execSQL("UPDATE cached_media_items SET favoriteSource = 'simkl' WHERE id = 'tmdb_m_9000'")
      db.query("SELECT favoriteSource FROM cached_media_items").use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("simkl", cursor.getString(0))
      }
    } finally {
      helper.close()
    }
  }

  /** Colonna -> `dflt_value` (`PRAGMA table_info`): "0" per il flag, null per i TEXT. */
  private fun columnDefaults(db: SupportSQLiteDatabase, table: String): Map<String, String?> {
    val result = mutableMapOf<String, String?>()
    db.query("PRAGMA table_info($table)").use { cursor ->
      val nameIndex = cursor.getColumnIndex("name")
      val defaultIndex = cursor.getColumnIndex("dflt_value")
      while (cursor.moveToNext()) {
        val value = if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
        result[cursor.getString(nameIndex)] = value
      }
    }
    return result
  }

  private companion object {
    /** Schema v9 di `cached_media_items` (prima delle colonne di provenienza). */
    const val V9_CACHED_MEDIA =
      "CREATE TABLE IF NOT EXISTS cached_media_items (" +
        "id TEXT NOT NULL PRIMARY KEY, " +
        "tmdbId INTEGER, " +
        "title TEXT NOT NULL, " +
        "originalTitle TEXT NOT NULL, " +
        "synopsis TEXT NOT NULL, " +
        "videoUrl TEXT NOT NULL, " +
        "resolution TEXT NOT NULL, " +
        "qualityTags TEXT NOT NULL, " +
        "backdropUrl TEXT, " +
        "posterUrl TEXT, " +
        "logoUrl TEXT, " +
        "type TEXT NOT NULL, " +
        "year INTEGER NOT NULL, " +
        "durationMinutes INTEGER NOT NULL, " +
        "seasonsCount INTEGER, " +
        "rating REAL NOT NULL, " +
        "genres TEXT NOT NULL, " +
        "director TEXT, " +
        "\"cast\" TEXT NOT NULL, " +
        "provider TEXT, " +
        "isTrending INTEGER NOT NULL, " +
        "isTop10 INTEGER NOT NULL, " +
        "isFavorite INTEGER NOT NULL, " +
        "currentProgressMs INTEGER NOT NULL, " +
        "totalDurationMs INTEGER NOT NULL, " +
        "lastWatchedSeason INTEGER, " +
        "lastWatchedEpisode INTEGER, " +
        "lastWatchedAt INTEGER, " +
        "ageRating INTEGER, " +
        "cachedAt INTEGER NOT NULL)"

    /** Schema v9 di `episode_progress`. */
    const val V9_EPISODE_PROGRESS =
      "CREATE TABLE IF NOT EXISTS episode_progress (" +
        "mediaId TEXT NOT NULL, " +
        "seasonNumber INTEGER NOT NULL, " +
        "episodeNumber INTEGER NOT NULL, " +
        "progressMs INTEGER NOT NULL, " +
        "durationMs INTEGER NOT NULL, " +
        "updatedAt INTEGER NOT NULL, " +
        "PRIMARY KEY(mediaId, seasonNumber, episodeNumber))"
  }
}
