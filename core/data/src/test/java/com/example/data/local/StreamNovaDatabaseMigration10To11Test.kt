package com.example.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migrazione Room 10 → 11: `inContinueWatching` e `completedAt`.
 *
 * Requisito esplicito: la migrazione deve PRESERVARE tutti i record, i progressi e lo
 * storico. I record esistenti nascono con `inContinueWatching = 1` (comportamento
 * storico invariato) e `completedAt = NULL` (nessun completamento inventato).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamNovaDatabaseMigration10To11Test {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val dbName = "streamnova_migration_10_11_test.db"
  private lateinit var helper: SupportSQLiteOpenHelper
  private lateinit var db: SupportSQLiteDatabase

  @Before
  fun setUp() {
    context.deleteDatabase(dbName)
    helper = FrameworkSQLiteOpenHelperFactory().create(
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(dbName)
        .callback(object : SupportSQLiteOpenHelper.Callback(10) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            // Sottoinsieme v10 sufficiente alla migrazione: la ALTER TABLE non dipende
            // dalle altre colonne, ma i dati qui presenti devono sopravvivere.
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `cached_media_items` (" +
                "`id` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`currentProgressMs` INTEGER NOT NULL, " +
                "`lastWatchedSeason` INTEGER, " +
                "`lastWatchedEpisode` INTEGER, " +
                "`lastWatchedAt` INTEGER, " +
                "`favoriteSource` TEXT, " +
                "`progressSource` TEXT, " +
                "`importedFromSimkl` INTEGER NOT NULL DEFAULT 0, " +
                "`cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
            )
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `episode_progress` (" +
                "`mediaId` TEXT NOT NULL, " +
                "`seasonNumber` INTEGER NOT NULL, " +
                "`episodeNumber` INTEGER NOT NULL, " +
                "`progressMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`source` TEXT, " +
                "PRIMARY KEY(`mediaId`, `seasonNumber`, `episodeNumber`))"
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        })
        .build()
    )
    db = helper.writableDatabase
    db.execSQL(
      "INSERT INTO cached_media_items " +
        "(id,title,currentProgressMs,lastWatchedSeason,lastWatchedEpisode,lastWatchedAt," +
        "favoriteSource,progressSource,importedFromSimkl,cachedAt) VALUES " +
        "('tmdb_tv_42','Serie',123456,2,7,1700000000000,'simkl','simkl',1,1700000000000)"
    )
    db.execSQL(
      "INSERT INTO cached_media_items " +
        "(id,title,currentProgressMs,lastWatchedSeason,lastWatchedEpisode,lastWatchedAt," +
        "favoriteSource,progressSource,importedFromSimkl,cachedAt) VALUES " +
        "('tmdb_tv_43','Serie conclusa vecchia',0,1,10,1700000000000,NULL,NULL,0,1700000000000)"
    )
    db.execSQL(
      "INSERT INTO episode_progress " +
        "(mediaId,seasonNumber,episodeNumber,progressMs,durationMs,updatedAt,source) VALUES " +
        "('tmdb_tv_42',2,7,123456,3000000,1700000000000,'simkl')"
    )
  }

  @After
  fun tearDown() {
    helper.close()
    context.deleteDatabase(dbName)
  }

  @Test
  fun laMigrazionePreservaRecordProgressiEStorico() {
    StreamNovaDatabase.MIGRATION_10_11.migrate(db)

    val columns = columnNames("cached_media_items")
    assertTrue("inContinueWatching mancante", "inContinueWatching" in columns)
    assertTrue("completedAt mancante", "completedAt" in columns)

    db.query(
      "SELECT title,currentProgressMs,lastWatchedSeason,lastWatchedEpisode,lastWatchedAt," +
        "favoriteSource,progressSource,importedFromSimkl,inContinueWatching,completedAt " +
        "FROM cached_media_items WHERE id='tmdb_tv_42'"
    ).use { cursor ->
      assertTrue("il record deve sopravvivere", cursor.moveToFirst())
      assertEquals("Serie", cursor.getString(0))
      assertEquals("progresso intatto", 123456L, cursor.getLong(1))
      assertEquals(2, cursor.getInt(2))
      assertEquals(7, cursor.getInt(3))
      assertEquals("data di visione intatta", 1700000000000L, cursor.getLong(4))
      assertEquals("provenienza intatta", "simkl", cursor.getString(5))
      assertEquals("provenienza progresso intatta", "simkl", cursor.getString(6))
      assertEquals("flag import intatto", 1, cursor.getInt(7))
      assertEquals(
        "i record esistenti restano in Continua a guardare",
        1,
        cursor.getInt(8)
      )
      assertTrue(
        "nessun completamento inventato dalla migrazione",
        cursor.isNull(9)
      )
    }

    // Una serie che la vecchia versione aveva già tolto dalla riga (progresso 0) non
    // deve riapparire dopo l'aggiornamento: il flag viene riallineato al comportamento
    // storico, non impostato a 1 per tutti.
    db.query(
      "SELECT inContinueWatching,lastWatchedEpisode,completedAt FROM cached_media_items " +
        "WHERE id='tmdb_tv_43'"
    ).use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("nessuna ricomparsa dopo la migrazione", 0, cursor.getInt(0))
      assertEquals("lo stato episodio resta", 10, cursor.getInt(1))
      assertTrue("nessun completamento inventato", cursor.isNull(2))
    }

    db.query(
      "SELECT progressMs,durationMs,source FROM episode_progress " +
        "WHERE mediaId='tmdb_tv_42' AND seasonNumber=2 AND episodeNumber=7"
    ).use { cursor ->
      assertTrue("lo storico episodio deve sopravvivere", cursor.moveToFirst())
      assertEquals(123456L, cursor.getLong(0))
      assertEquals(3000000L, cursor.getLong(1))
      assertEquals("simkl", cursor.getString(2))
    }
  }

  private fun columnNames(table: String): Set<String> {
    val names = mutableSetOf<String>()
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
      val nameIndex = cursor.getColumnIndexOrThrow("name")
      while (cursor.moveToNext()) names += cursor.getString(nameIndex)
    }
    return names
  }
}
