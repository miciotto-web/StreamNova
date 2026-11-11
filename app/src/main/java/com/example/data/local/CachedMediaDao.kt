package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedMediaDao {

  @Query("SELECT * FROM cached_media_items ORDER BY rating DESC")
  fun getAllCachedMediaFlow(): Flow<List<CachedMediaItemEntity>>

  @Query("SELECT * FROM cached_media_items ORDER BY rating DESC")
  suspend fun getAllCachedMedia(): List<CachedMediaItemEntity>

  @Query("SELECT * FROM cached_media_items WHERE id = :id LIMIT 1")
  suspend fun getMediaById(id: String): CachedMediaItemEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(items: List<CachedMediaItemEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(item: CachedMediaItemEntity)

  @Query("UPDATE cached_media_items SET isFavorite = :isFavorite WHERE id = :id")
  suspend fun updateFavorite(id: String, isFavorite: Boolean)

  /** Preferito CON provenienza: chi ha scritto lo stato per ultimo ne resta proprietario. */
  @Query("UPDATE cached_media_items SET isFavorite = :isFavorite, favoriteSource = :favoriteSource WHERE id = :id")
  suspend fun updateFavoriteWithSource(id: String, isFavorite: Boolean, favoriteSource: String?)

  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateProgress(id: String, progressMs: Long)

  @Query("UPDATE cached_media_items SET lastWatchedSeason = :season, lastWatchedEpisode = :episode, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateLastWatched(id: String, season: Int, episode: Int)

  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, lastWatchedSeason = :season, lastWatchedEpisode = :episode, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateTvProgress(id: String, progressMs: Long, season: Int, episode: Int)

  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, lastWatchedAt = :lastWatchedAt, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateProgressWithTimestamp(id: String, progressMs: Long, lastWatchedAt: Long)

  /**
   * Aggiorna posizione **e** durata reale (misurata dal player) di un film.
   * La durata e' gia' "mergiata" a monte (mai `<= 0`): vedi `WatchProgress.resolveDuration`.
   */
  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, totalDurationMs = :totalDurationMs, lastWatchedAt = :lastWatchedAt, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateProgressWithDuration(id: String, progressMs: Long, totalDurationMs: Long, lastWatchedAt: Long)

  /**
   * Posizione del film CON provenienza (ultimo scrittore possessore). Una nuova visione
   * riporta sempre il titolo in «Continua a guardare» (`inContinueWatching = 1`): la
   * rimozione manuale non è definitiva e non cancella nulla.
   */
  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, totalDurationMs = :totalDurationMs, lastWatchedAt = :lastWatchedAt, progressSource = :progressSource, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateProgressWithDurationAndSource(id: String, progressMs: Long, totalDurationMs: Long, lastWatchedAt: Long, progressSource: String?)

  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, lastWatchedSeason = :season, lastWatchedEpisode = :episode, lastWatchedAt = :lastWatchedAt, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateTvProgressWithTimestamp(id: String, progressMs: Long, season: Int, episode: Int, lastWatchedAt: Long)

  /**
   * Aggiorna posizione, stagione/episodio corrente, timestamp e durata dell'**episodio
   * corrente**: cosi' la card "Continua a guardare" mostra i minuti reali dell'episodio
   * in corso (mai la durata della serie o di un episodio precedente).
   */
  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, totalDurationMs = :totalDurationMs, lastWatchedSeason = :season, lastWatchedEpisode = :episode, lastWatchedAt = :lastWatchedAt, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateTvProgressWithDuration(id: String, progressMs: Long, totalDurationMs: Long, season: Int, episode: Int, lastWatchedAt: Long)

  /**
   * Posizione dell'episodio corrente CON provenienza (ultimo scrittore possessore).
   * `inContinueWatching = 1`: una sessione di pausa o una posizione reale riportano la
   * scheda in «Continua a guardare». Il `next_to_watch` di SIMKL NON passa da qui: ha la
   * sua scrittura dedicata [updateNextUp], che fissa l'episodio successivo a posizione 0.
   */
  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs, totalDurationMs = :totalDurationMs, lastWatchedSeason = :season, lastWatchedEpisode = :episode, lastWatchedAt = :lastWatchedAt, progressSource = :progressSource, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateTvProgressWithDurationAndSource(id: String, progressMs: Long, totalDurationMs: Long, season: Int, episode: Int, lastWatchedAt: Long, progressSource: String?)

  /**
   * Punto di ripresa «prossimo episodio» adottato da SIMKL (`next_to_watch`): aggiorna
   * stagione/episodio corrente, data e provenienza, azzera la posizione del prossimo
   * episodio e riporta la scheda in «Continua a guardare» (`inContinueWatching = 1`).
   *
   * È ciò che il sito SIMKL mostra nella sua riga "Continua a guardare": le serie
   * `watching` ferme al prossimo episodio. La posizione dell'episodio precedente resta
   * nella sua riga `episode_progress`; la card mostra l'episodio successivo a 0.
   */
  @Query("UPDATE cached_media_items SET currentProgressMs = 0, lastWatchedSeason = :season, lastWatchedEpisode = :episode, lastWatchedAt = :lastWatchedAt, progressSource = :progressSource, inContinueWatching = 1 WHERE id = :id")
  suspend fun updateNextUp(id: String, season: Int, episode: Int, lastWatchedAt: Long, progressSource: String?)

  /**
   * Rimozione NON distruttiva da «Continua a guardare»: la scheda resta in catalogo con
   * progressi, stagione/episodio e storico episodi intatti. Usata sia dal menu contestuale
   * della card sia dai completamenti verificati.
   */
  @Query("UPDATE cached_media_items SET inContinueWatching = 0 WHERE id = :id")
  suspend fun hideFromContinueWatching(id: String)

  /**
   * Conclusione verificata di un titolo: lo toglie dalla riga ma conserva tutto lo stato
   * (posizione, episodio corrente, durata, righe `episode_progress`).
   */
  @Query("UPDATE cached_media_items SET inContinueWatching = 0, completedAt = :completedAt WHERE id = :id")
  suspend fun markCompleted(id: String, completedAt: Long)

  /**
   * Conclusione di un film: oltre al flag di completamento porta la posizione alla durata
   * nota, così badge e scheda riflettono lo stato "visto" senza cancellare nulla.
   */
  @Query("UPDATE cached_media_items SET inContinueWatching = 0, completedAt = :completedAt, currentProgressMs = :progressMs WHERE id = :id")
  suspend fun markMovieCompleted(id: String, completedAt: Long, progressMs: Long)

  /**
   * Ritiro del "visto" da parte dell'utente: annulla il completamento locale. Nulla
   * tocca la posizione né il flag «Continua a guardare»: la scheda rientra se ha uno
   * stato di ripresa.
   */
  @Query("UPDATE cached_media_items SET completedAt = NULL WHERE id = :id")
  suspend fun clearCompleted(id: String)

  /**
   * Azzera lo stato di visione di una serie («Serie non vista»): nessun episodio visto,
   * nessun punto di ripresa, nessun completamento. La scheda resta in catalogo (titolo,
   * preferito e metadati intatti) ma esce da «Continua a guardare».
   */
  @Query(
    "UPDATE cached_media_items SET currentProgressMs = 0, lastWatchedSeason = NULL, " +
      "lastWatchedEpisode = NULL, lastWatchedAt = NULL, completedAt = NULL, " +
      "inContinueWatching = 0 WHERE id = :id"
  )
  suspend fun clearSeriesWatchState(id: String)

  /**
   * Aggiorna SOLO la posizione corrente del titolo, senza toccare
   * `inContinueWatching`/`completedAt`: usata dalla marcatura «Serie già vista», che
   * persiste il progresso completo ma lascia la conclusione a [markCompleted].
   */
  @Query("UPDATE cached_media_items SET currentProgressMs = :progressMs WHERE id = :id")
  suspend fun updateCurrentProgressOnly(id: String, progressMs: Long)

  @Query("SELECT COUNT(*) FROM cached_media_items")
  suspend fun count(): Int

  @Query("DELETE FROM cached_media_items")
  suspend fun clearAll()

  @Query("DELETE FROM cached_media_items WHERE id IN (:ids) OR tmdbId IN (:rawIds)")
  suspend fun deleteByIds(ids: List<String>, rawIds: List<Int>)

  @Query("DELETE FROM cached_media_items WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM cached_media_items WHERE isFavorite = 0 AND (currentProgressMs IS NULL OR currentProgressMs <= 0)")
  suspend fun clearNonFavorites()
}
