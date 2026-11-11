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
 * Migrazione Room 11 → 12: riallineamento del flag «Continua a guardare».
 *
 * Dalla v12 il flag vale SOLO per uno stato di ripresa reale: `1` con posizione
 * (`currentProgressMs > 0`) o puntatore «prossimo episodio» noto (`lastWatchedSeason`),
 * `0` altrimenti (righe ri-cacheate da TMDB e titoli mai avviati restano fuori). Lo
 * schema NON cambia (il DEFAULT di colonna resta `1`, Room lega il valore della
 * proprietà): la migrazione è un riallineamento dei dati. Nessun record, progresso,
 * puntatore o storico viene cancellato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamNovaDatabaseMigration11To12Test {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val dbName = "streamnova_migration_11_12_test.db"
  private lateinit var helper: SupportSQLiteOpenHelper
  private lateinit var db: SupportSQLiteDatabase

  @Before
  fun setUp() {
    context.deleteDatabase(dbName)
    helper = FrameworkSQLiteOpenHelperFactory().create(
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(dbName)
        .callback(object : SupportSQLiteOpenHelper.Callback(11) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            // Schema v11: è lo schema v10 più le due colonne aggiunte dalla 10→11
            // (`inContinueWatching` con DEFAULT 1 storico, `completedAt`).
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
                "`inContinueWatching` INTEGER NOT NULL DEFAULT 1, " +
                "`completedAt` INTEGER, " +
                "`cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        })
        .build()
    )
    db = helper.writableDatabase

    fun inserisci(id: String, titolo: String, progresso: Long, stagione: Int?, flag: Int) {
      db.execSQL(
        "INSERT INTO cached_media_items " +
          "(id,title,currentProgressMs,lastWatchedSeason,lastWatchedEpisode,lastWatchedAt," +
          "favoriteSource,progressSource,importedFromSimkl,inContinueWatching,completedAt,cachedAt) VALUES " +
          "('$id','$titolo',$progresso,$stagione,${stagione?.plus(10) ?: "NULL"},1700000000000," +
          "NULL,NULL,0,$flag,NULL,1700000000000)"
      )
    }

    // Stato di ripresa reale: progresso > 0.
    inserisci("m_1", "Film in corso", 600_000L, null, flag = 1)
    // Sessione di pausa SIMKL: posizione 0 ma episodio corrente noto.
    inserisci("tv_2", "Serie in pausa", 0L, 2, flag = 1)
    // Righe che il DEFAULT `true` della v11 aveva accese senza motivo: niente progresso,
    // nessun punto di ripresa. Sono la maggioranza reale del catalogo (550+ righe).
    inserisci("tv_3", "Mai avviata", 0L, null, flag = 1)
    // Righe esplicitamente nascoste con progresso reale: tornano visibili, come già
    // accadeva nella 10→11 (comportamento documentato del riallineamento).
    inserisci("m_4", "Nascosta con progresso", 300_000L, null, flag = 0)
  }

  @After
  fun tearDown() {
    helper.close()
    context.deleteDatabase(dbName)
  }

  private fun flagDi(id: String): Int =
    db.query("SELECT inContinueWatching FROM cached_media_items WHERE id='$id'").use { cursor ->
      assertTrue("riga $id assente", cursor.moveToFirst())
      cursor.getInt(0)
    }

  @Test
  fun ilFlagRimaneAccesoSoloDoveEsisteUnoStatoDiRipresaReale() {
    StreamNovaDatabase.MIGRATION_11_12.migrate(db)

    assertEquals(1, flagDi("m_1"))
    assertEquals(
      "posizione 0 ma episodio corrente noto: è il «prossimo episodio» di SIMKL, " +
        "la stessa riga che il sito SIMKL mostra",
      1,
      flagDi("tv_2")
    )
    assertEquals(
      "nessuno stato di ripresa: il default `true` storico viene corretto",
      0,
      flagDi("tv_3")
    )
    assertEquals("nascosta con progresso: il riallineamento non è selettivo", 1, flagDi("m_4"))
  }

  @Test
  fun laMigrazioneNonToccaNessunAltroDato() {
    StreamNovaDatabase.MIGRATION_11_12.migrate(db)

    db.query(
      "SELECT title,currentProgressMs,lastWatchedSeason,lastWatchedEpisode,lastWatchedAt," +
        "favoriteSource,progressSource,importedFromSimkl,completedAt " +
        "FROM cached_media_items WHERE id='tv_2'"
    ).use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("Serie in pausa", cursor.getString(0))
      assertEquals(0L, cursor.getLong(1))
      assertEquals(2, cursor.getInt(2))
      assertEquals(12, cursor.getInt(3))
      assertEquals("data di visione intatta", 1700000000000L, cursor.getLong(4))
      assertTrue("provenienza preferito intatta", cursor.isNull(5))
      assertTrue("provenienza progresso intatta", cursor.isNull(6))
      assertEquals("flag import intatto", 0, cursor.getInt(7))
      assertTrue("nessun completamento inventato", cursor.isNull(8))
    }
  }
}
