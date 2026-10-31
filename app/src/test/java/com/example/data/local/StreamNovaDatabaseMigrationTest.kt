package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migrazione Room 8 -> 9: aggiunta dell'identità Debrid
 * (`infoHash`/`fileIdx`/`addonName`) a `last_working_streams`.
 *
 * La migrazione deve essere ESPLICITA e NON distruttiva: le voci già salvate
 * (link, modalità, headers) restano intatte e le nuove colonne partono da NULL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamNovaDatabaseMigrationTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val dbName = "streamnova_migration_8_9_test.db"
  private lateinit var helper: SupportSQLiteOpenHelper
  private lateinit var db: SupportSQLiteDatabase

  @Before
  fun setUp() {
    context.deleteDatabase(dbName)
    helper = FrameworkSQLiteOpenHelperFactory().create(
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(dbName)
        .callback(object : SupportSQLiteOpenHelper.Callback(8) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            // Schema v8: identico a MIGRATION_6_7 + MIGRATION_7_8.
            db.execSQL(
              "CREATE TABLE IF NOT EXISTS `last_working_streams` (" +
                "`mediaId` TEXT NOT NULL, " +
                "`seasonNumber` INTEGER NOT NULL, " +
                "`episodeNumber` INTEGER NOT NULL, " +
                "`streamUrl` TEXT NOT NULL, " +
                "`streamTitle` TEXT, " +
                "`streamQuality` TEXT, " +
                "`serverName` TEXT, " +
                "`headersJson` TEXT, " +
                "`isProgressive` INTEGER, " +
                "`streamMode` TEXT, " +
                "`cachedAtTimestamp` INTEGER NOT NULL, " +
                "PRIMARY KEY(`mediaId`, `seasonNumber`, `episodeNumber`))"
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        })
        .build()
    )
    db = helper.writableDatabase
    db.execSQL(
      "INSERT INTO `last_working_streams` " +
        "(`mediaId`,`seasonNumber`,`episodeNumber`,`streamUrl`,`streamTitle`,`streamQuality`," +
        "`serverName`,`headersJson`,`isProgressive`,`streamMode`,`cachedAtTimestamp`) " +
        "VALUES ('tt0944947', 1, 3, 'https://cdn.example.com/s1e3.mkv', 'Show.S01.1080p.mkv', " +
        "'1080p', 'Torrentio', NULL, 1, 'DEBRID_TORBOX', 1234)"
    )
  }

  @After
  fun tearDown() {
    helper.close()
    context.deleteDatabase(dbName)
  }

  @Test
  fun databaseVersionIs9() {
    // Room applica `version = 9` al database creato: è la versione che la migrazione
    // esplicita 8->9 deve raggiungere.
    val roomDb = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      assertEquals(
        "La migrazione esplicita 8->9 richiede la versione 9",
        9,
        roomDb.openHelper.readableDatabase.version
      )
    } finally {
      roomDb.close()
    }
  }

  @Test
  fun migrationAddsDebridIdentityWithoutLosingExistingRows() {
    StreamNovaDatabase.MIGRATION_8_9.migrate(db)

    val columns = columnNames()
    assertTrue("infoHash mancante", "infoHash" in columns)
    assertTrue("fileIdx mancante", "fileIdx" in columns)
    assertTrue("addonName mancante", "addonName" in columns)

    db.query(
      "SELECT streamUrl, streamTitle, streamQuality, serverName, isProgressive, streamMode, " +
        "infoHash, fileIdx, addonName FROM last_working_streams " +
        "WHERE mediaId = 'tt0944947' AND seasonNumber = 1 AND episodeNumber = 3"
    ).use { cursor ->
      assertTrue("la voce v8 deve sopravvivere alla migrazione", cursor.moveToFirst())
      assertEquals("https://cdn.example.com/s1e3.mkv", cursor.getString(0))
      assertEquals("Show.S01.1080p.mkv", cursor.getString(1))
      assertEquals("1080p", cursor.getString(2))
      assertEquals("Torrentio", cursor.getString(3))
      assertEquals(1, cursor.getInt(4))
      assertEquals("DEBRID_TORBOX", cursor.getString(5))
      assertNull("le voci legacy non hanno identità Debrid", cursor.getString(6))
      assertTrue("fileIdx nullo per le voci legacy", cursor.isNull(7))
      assertNull("addonName nullo per le voci legacy", cursor.getString(8))
    }
  }

  @Test
  fun migratedColumnsAcceptDebridIdentity() {
    StreamNovaDatabase.MIGRATION_8_9.migrate(db)

    db.execSQL(
      "UPDATE last_working_streams SET infoHash = 'abcdefabcdefabcdefabcdefabcdefabcdefabcd', " +
        "fileIdx = 2, addonName = 'Torrentio' " +
        "WHERE mediaId = 'tt0944947' AND seasonNumber = 1 AND episodeNumber = 3"
    )
    db.query(
      "SELECT infoHash, fileIdx, addonName FROM last_working_streams " +
        "WHERE mediaId = 'tt0944947' AND seasonNumber = 1 AND episodeNumber = 3"
    ).use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("abcdefabcdefabcdefabcdefabcdefabcdefabcd", cursor.getString(0))
      assertEquals(2, cursor.getInt(1))
      assertEquals("Torrentio", cursor.getString(2))
    }
  }

  private fun columnNames(): Set<String> {
    val names = mutableSetOf<String>()
    db.query("PRAGMA table_info(`last_working_streams`)").use { cursor ->
      val nameIndex = cursor.getColumnIndexOrThrow("name")
      while (cursor.moveToNext()) names += cursor.getString(nameIndex)
    }
    return names
  }
}
