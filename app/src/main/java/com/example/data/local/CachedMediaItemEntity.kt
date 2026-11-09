package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Provenienza di un dato utente (preferito, progresso, scheda importata): `null` significa
 * scritto in LOCALE (player, azione dell'utente, dati storici), `"simkl"` significa
 * adottato dalla sincronizzazione SIMKL. È la base della regola "ultimo scrittore
 * possessore": la disconnessione da SIMKL rimuove solo ciò che la sync ha scritto per
 * ultimo, lasciando intatti i dati locali e quelli di altri servizi.
 */
const val MEDIA_SOURCE_SIMKL = "simkl"

/**
 * Provenienza del punto di ripresa «prossimo episodio» (`next_to_watch` di SIMKL):
 * stagione/episodio corrente adottati dalla libreria SIMKL **senza** posizione
 * (non esiste nessuna sessione di pausa). È la card che il sito SIMKL mostra nella sua
 * riga "Continua a guardare", ferma sull'episodio successivo a 0; serve anche al
 * pulsante «Riprendi» del Dettaglio. Al logout viene ripulito come `"simkl"`.
 */
const val MEDIA_SOURCE_SIMKL_NEXT_UP = "simkl_nextup"

/**
 * Room Entity persisting TMDB Media items.
 * Allows instant cold-start loading, zero-latency scrolling through large lists,
 * and preserves user favorite state and playback progress.
 */
@Entity(tableName = "cached_media_items")
data class CachedMediaItemEntity(
  @PrimaryKey val id: String,
  val tmdbId: Int?,
  val title: String,
  val originalTitle: String,
  val synopsis: String,
  val videoUrl: String,
  val resolution: String, // UHD_4K, FHD_1080P, HD_720P
  val qualityTags: String, // Comma-separated (e.g., "4K,Dolby Atmos,16+")
  val backdropUrl: String?,
  val posterUrl: String?,
  val logoUrl: String?,
  val type: String, // FILM, SERIE_TV
  val year: Int,
  val durationMinutes: Int,
  val seasonsCount: Int?,
  val rating: Float,
  val genres: String, // Comma-separated (e.g., "Fantascienza,Azione")
  val director: String?,
  val cast: String, // Comma-separated
  val provider: String?, // netflix, hbo, disney, prime
  val isTrending: Boolean = false,
  val isTop10: Boolean = false,
  val isFavorite: Boolean = false,
  val currentProgressMs: Long = 0L,
  val totalDurationMs: Long = 0L,
  /** Stagione/episodio visti per ultimo: preservato nei refresh TMDB (mai azzerato). */
  val lastWatchedSeason: Int? = null,
  val lastWatchedEpisode: Int? = null,
  /** Timestamp dell'ultima visione: usato per ordinare "Continua a guardare". */
  val lastWatchedAt: Long? = null,
  /**
   * true se il titolo appartiene alla riga «Continua a guardare». La rimozione manuale
   * (menu contestuale della card, azione «Rimuovi») NON cancella la scheda né i progressi:
   * imposta questo flag a `false`. Una nuova visione lo riporta a `true`.
   *
   * Il DEFAULT SCHEMA resta `1` (retrocompatibilità migrazione 10→11, mai modificato per
   * non ricreare la tabella): Room lega sempre il valore della proprietà, quindi il
   * default Kotlin `false` vale per ogni insert. Il valore storico `true` scriveva nelle
   * righe ri-cacheate da TMDB, che così rientravano nella riga senza stato di ripresa.
   */
  @ColumnInfo(defaultValue = "1") val inContinueWatching: Boolean = false,
  /**
   * Istante in cui il titolo è stato riconosciuto come concluso (film finito, serie
   * arrivata all'ultimo episodio, oppure `status=completed` su SIMKL). Non sostituisce
   * `lastWatchedAt`: è un dato di completamento, non una data di visione, e non cancella
   * né i progressi né lo storico degli episodi.
   */
  val completedAt: Long? = null,
  val ageRating: Int? = null,
  /**
   * Provenienza dello stato "preferito" (`MEDIA_SOURCE_SIMKL` quando è la watchlist SIMKL
   * a portarlo nei Preferiti): `null` = preferito locale o storico, MAI rimosso dal logout.
   * Nessun DEFAULT dichiarato: la migrazione aggiunge la colonna TEXT senza default
   * (coerenza richiesta dalla validazione schema Room dopo la migrazione).
   */
  val favoriteSource: String? = null,
  /**
   * Provenienza del progresso corrente della scheda: `null` = scritto dal locale (player o
   * dati storici), `MEDIA_SOURCE_SIMKL` = ultima posizione adottata dalla sincronizzazione.
   */
  val progressSource: String? = null,
  /**
   * true se la scheda è stata CREATA dall'importazione SIMKL (watchlist o punto di
   * ripresa): al logout una scheda sim può essere eliminata, ma solo se non ha acquisito
   * alcun stato locale (preferito o progresso scritti dall'utente/player).
   */
  @ColumnInfo(defaultValue = "0") val importedFromSimkl: Boolean = false,
  val cachedAt: Long = System.currentTimeMillis()
)
