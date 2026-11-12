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
    EpisodeProgressEntity::class,
    LastWorkingStreamEntity::class
  ],
  version = 12,
  exportSchema = false
)
abstract class StreamNovaDatabase : RoomDatabase() {

  abstract fun tmdbResponseCacheDao(): TmdbResponseCacheDao
  abstract fun cachedMediaDao(): CachedMediaDao
  abstract fun catalogProgressDao(): CatalogProgressDao
  abstract fun episodeProgressDao(): EpisodeProgressDao
  abstract fun lastWorkingStreamDao(): LastWorkingStreamDao

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

    /**
     * v6 -> v7: aggiunta della tabella `last_working_streams`, cache locale dell'ultimo
     * flusso funzionante per film/episodio (funzione "Riusa l'ultimo link").
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
      override fun migrate(db: SupportSQLiteDatabase) {
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
            "`cachedAtTimestamp` INTEGER NOT NULL, " +
            "PRIMARY KEY(`mediaId`, `seasonNumber`, `episodeNumber`))"
        )
      }
    }

    /**
     * v7 -> v8: aggiunta di `streamMode` in `last_working_streams`. Traccia la modalità
     * del motore (HTTP vs Debrid) che ha generato il flusso salvato: "Continua a guardare"
     * non deve riusare una fonte appartenente a una modalità diversa da quella attiva.
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE last_working_streams ADD COLUMN streamMode TEXT")
      }
    }

    /**
     * v8 -> v9: identità della sorgente Debrid in `last_working_streams`
     * (`infoHash`, `fileIdx`, `addonName`). Servono al passaggio all'episodio
     * successivo: senza infoHash il torrent di stagione dell'episodio corrente non è
     * più riutilizzabile quando la sessione riparte dalla cache "Riusa l'ultimo link".
     */
    internal val MIGRATION_8_9 = object : Migration(8, 9) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE last_working_streams ADD COLUMN infoHash TEXT")
        db.execSQL("ALTER TABLE last_working_streams ADD COLUMN fileIdx INTEGER")
        db.execSQL("ALTER TABLE last_working_streams ADD COLUMN addonName TEXT")
      }
    }

    /**
     * v9 -> v10: colonne di PROVENIENZA per preferiti, progressi e schede importate
     * (`favoriteSource`, `progressSource`, `importedFromSimkl` in `cached_media_items`;
     * `source` in `episode_progress`). `NULL`/`0` = dato locale, `"simkl"` = adottato
     * dalla sincronizzazione SIMKL: è la base della pulizia al logout, che rimuove SOLO
     * i dati di provenienza SIMKL e PRESERVA tutti quelli locali.
     *
     * I record storici (scritti prima della migrazione) nascono `NULL`/`0`, cioè di
     * proprietà LOCALE: la loro provenienza reale non è ricostruibile dal database v9,
     * quindi vengono conservati (mai cancellati indiscriminatamente) — al primo logout
     * un preferito/progresso importato da SIMKL in una versione precedente può restare
     * visibile; la rimozione manuale dalla UI resta disponibile e sicura.
     */
    internal val MIGRATION_9_10 = object : Migration(9, 10) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cached_media_items ADD COLUMN favoriteSource TEXT")
        db.execSQL("ALTER TABLE cached_media_items ADD COLUMN progressSource TEXT")
        db.execSQL("ALTER TABLE cached_media_items ADD COLUMN importedFromSimkl INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE episode_progress ADD COLUMN source TEXT")
      }
    }

    /**
     * v10 -> v11: stato NON distruttivo di «Continua a guardare» e completamento.
     *
     * - `inContinueWatching` nasce `1` come default di colonna (coerente con l'entità), ma
     *   viene poi riallineato al comportamento storico: `1` solo per i titoli con un
     *   progresso reale (`currentProgressMs > 0`). I record a progresso 0 — in particolare
     *   le serie concluse che la vecchia versione azzerava — restano fuori dalla riga,
     *   esattamente come prima. Nessun record, progresso o riga episodio viene cancellato.
     * - `completedAt` nasce `NULL`: nessun record storico viene dichiarato concluso per
     *   inferenza. Il valore viene scritto solo da una conclusione verificata (player o
     *   `status=completed` di SIMKL).
     */
    internal val MIGRATION_10_11 = object : Migration(10, 11) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE cached_media_items ADD COLUMN inContinueWatching INTEGER NOT NULL DEFAULT 1"
        )
        db.execSQL("ALTER TABLE cached_media_items ADD COLUMN completedAt INTEGER")
        // Comportamento storico: in «Continua a guardare» c'erano solo i titoli con un
        // progresso > 0. Le righe a 0 (serie concluse azzerate dalla vecchia versione)
        // restano fuori, senza perdere nulla.
        db.execSQL(
          "UPDATE cached_media_items SET inContinueWatching = " +
            "CASE WHEN currentProgressMs > 0 THEN 1 ELSE 0 END"
        )
      }
    }

    /**
     * v11 -> v12: riallineamento del flag «Continua a guardare» al nuovo default Kotlin.
     *
     * Lo schema NON cambia (il DEFAULT di colonna resta `1` per compatibilità con la
     * 10→11; Room lega sempre il valore della proprietà, quindi il default Kotlin
     * `false` governa ogni insert). Cambia solo il significato del flag, che da questo
     * momento vale SOLO per uno stato di ripresa reale:
     *
     * - `1` per i titoli con una posizione reale (`currentProgressMs > 0`: sessione di
     *   pausa SIMKL o del player) o con un puntatore «prossimo episodio» noto
     *   (`lastWatchedSeason` valorizzato) — è la riga che il sito SIMKL mostra;
     * - `0` per tutto il resto, cioè le righe ri-cacheate da TMDB che il default storico
     *   `true` aveva accese senza motivo (su un catalogo reale era quasi tutta la
     *   tabella) e i titoli mai avviati.
     *
     * Dato non distruttivo: nessun record, progresso, puntatore di ripresa o riga
     * episodio viene toccato; le righe esplicitamente nascoste con un progresso reale
     * tornano visibili, come già accadeva nella 10→11.
     */
    internal val MIGRATION_11_12 = object : Migration(11, 12) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "UPDATE cached_media_items SET inContinueWatching = " +
            "CASE WHEN currentProgressMs > 0 OR lastWatchedSeason IS NOT NULL THEN 1 ELSE 0 END"
        )
      }
    }

    @Volatile
    private var INSTANCE: StreamNovaDatabase? = null

    /** Nome storico del database: usato dal profilo principale. */
    const val DEFAULT_DATABASE_NAME = "streamnova_database.db"

    /**
     * Nome del database aperto dal processo.
     *
     * Con i profili ogni profilo ha il proprio file Room; il profilo principale usa il
     * nome storico, quindi i dati esistenti restano al loro posto. Il nome viene scelto
     * dall'Application PRIMA di questa init e non cambia durante la vita del processo:
     * il cambio profilo riavvia l'app (vedi ProfilesRepository).
     */
    @Volatile
    private var openDatabaseName: String? = null

    fun init(
      context: Context,
      databaseName: String = DEFAULT_DATABASE_NAME
    ): StreamNovaDatabase {
      val existing = INSTANCE
      // `openDatabaseName == null` significa istanza iniettata dai test: va restituita
      // come prima, qualunque sia il nome richiesto.
      if (existing != null && (openDatabaseName == null || openDatabaseName == databaseName)) {
        return existing
      }
      return synchronized(this) {
        INSTANCE?.takeIf { openDatabaseName == null || openDatabaseName == databaseName }
          ?: Room.databaseBuilder(
            context.applicationContext,
            StreamNovaDatabase::class.java,
            databaseName
          )
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
          .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
          .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
          .build()
          .also {
            INSTANCE = it
            openDatabaseName = databaseName
          }
      }
    }

    fun getInstance(context: Context? = null): StreamNovaDatabase? {
      if (INSTANCE != null) return INSTANCE
      if (context != null) return init(context)
      return null
    }

    fun setInstanceForTesting(db: StreamNovaDatabase?) {
      INSTANCE = db
      openDatabaseName = null
    }
  }
}
