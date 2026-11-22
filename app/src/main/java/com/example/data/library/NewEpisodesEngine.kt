package com.example.data.library

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Episodio con data di messa in onda, come arriva dal dettaglio stagione TMDB.
 * `airDate` è la stringa ISO `yyyy-MM-dd`; `null` = TMDB non la dichiara.
 */
data class AiredEpisodeInfo(
  val season: Int,
  val episode: Int,
  val name: String? = null,
  val airDate: String? = null,
  val runtimeMinutes: Int? = null
) {
  /** Etichetta "S1E5" (o "S1E5 · Titolo" quando il titolo è noto). */
  val label: String
    get() = buildString {
      append("S${season}E$episode")
      name?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
    }
}

/**
 * Serie seguita (preferita o con progresso) con l'ultima posizione nota.
 *
 * I campi arrivano dalla riga Room: nessuna informazione viene dedotta da titoli
 * o da euristiche sul nome del file.
 */
data class TrackedSeries(
  val mediaId: String,
  val title: String,
  val tmdbId: Int?,
  val posterUrl: String? = null,
  val backdropUrl: String? = null,
  val lastWatchedSeason: Int? = null,
  val lastWatchedEpisode: Int? = null,
  val lastWatchedAt: Long? = null,
  val completedAt: Long? = null,
  val seasonsCount: Int? = null
) {
  val hasProgress: Boolean
    get() = lastWatchedSeason != null && lastWatchedEpisode != null

  val lastWatchedLabel: String?
    get() = if (hasProgress) "S$lastWatchedSeason" + "E$lastWatchedEpisode" else null
}

/** Stato di aggiornamento di una serie seguita. */
enum class NewEpisodesStatus {
  /** Ci sono episodi già andati in onda dopo l'ultimo visto. */
  NEW_EPISODES,

  /** Nessun episodio nuovo rispetto all'ultimo visto. */
  UP_TO_DATE,

  /** Serie seguita ma mai iniziata (nessuna posizione salvata). */
  NOT_STARTED,

  /** Dati episodi non disponibili (TMDB non configurato o errore). */
  NO_DATA
}

/** Esito dell'analisi di UNA serie. */
data class SeriesEpisodeUpdate(
  val series: TrackedSeries,
  val latestAired: AiredEpisodeInfo? = null,
  /** Primo episodio non visto già andato in onda, se esiste. */
  val newestUnwatched: AiredEpisodeInfo? = null,
  val newEpisodeCount: Int = 0,
  val status: NewEpisodesStatus = NewEpisodesStatus.NO_DATA
) {
  val hasNewEpisodes: Boolean get() = status == NewEpisodesStatus.NEW_EPISODES
}

/**
 * New Episodes Engine (puro e deterministico).
 *
 * Confronta la posizione salvata (stagione/episodio) con gli episodi REALMENTE
 * andati in onda. Regole:
 *  - un episodio con `airDate` futura o assente NON è "nuovo" (nessuna data
 *    inventata: TMDB la omette per gli episodi non ancora annunciati);
 *  - il confronto è per posizione (stagione, episodio), non per data: un
 *    rewatch di un episodio vecchio non crea falsi "nuovi episodi";
 *  - una serie senza posizione salvata è NOT_STARTED, mai NEW_EPISODES;
 *  - nessun episodio disponibile = NO_DATA (la UI non mostra un falso "0 nuovi").
 */
object NewEpisodesEngine {

  private val ISO_DAY: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
      isLenient = false
    }
  }

  fun evaluate(
    series: TrackedSeries,
    episodes: List<AiredEpisodeInfo>,
    nowEpochMs: Long = System.currentTimeMillis()
  ): SeriesEpisodeUpdate {
    val aired = episodes
      .filter { it.season > 0 && it.episode > 0 }
      .filter { episode -> parseAirDateMillis(episode.airDate)?.let { it <= nowEpochMs } == true }
      .distinctBy { it.season to it.episode }
      .sortedWith(compareBy<AiredEpisodeInfo> { it.season }.thenBy { it.episode })

    if (aired.isEmpty()) {
      // Nessun episodio con data verificabile: non si dichiara "aggiornata"
      // (sarebbe un falso negativo), si dichiara l'assenza del dato.
      return SeriesEpisodeUpdate(
        series = series,
        status = NewEpisodesStatus.NO_DATA
      )
    }

    val latestAired = aired.last()
    if (!series.hasProgress) {
      return SeriesEpisodeUpdate(
        series = series,
        latestAired = latestAired,
        status = NewEpisodesStatus.NOT_STARTED
      )
    }

    val lastSeason = series.lastWatchedSeason ?: 0
    val lastEpisode = series.lastWatchedEpisode ?: 0
    val newEpisodes = aired.filter { comparePositions(it, lastSeason, lastEpisode) > 0 }
    return if (newEpisodes.isEmpty()) {
      SeriesEpisodeUpdate(
        series = series,
        latestAired = latestAired,
        status = NewEpisodesStatus.UP_TO_DATE
      )
    } else {
      SeriesEpisodeUpdate(
        series = series,
        latestAired = latestAired,
        newestUnwatched = newEpisodes.first(),
        newEpisodeCount = newEpisodes.size,
        status = NewEpisodesStatus.NEW_EPISODES
      )
    }
  }

  /** >0 se l'episodio è DOPO la posizione (stagione, episodio) indicata. */
  fun comparePositions(episode: AiredEpisodeInfo, season: Int, number: Int): Int =
    when {
      episode.season != season -> episode.season.compareTo(season)
      else -> episode.episode.compareTo(number)
    }

  /** Data ISO → epoch UTC (mezzanotte); null se assente o non valida. */
  fun parseAirDateMillis(iso: String?): Long? {
    val value = iso?.trim().orEmpty()
    if (value.length < 10) return null
    return try {
      ISO_DAY.get()!!.parse(value.substring(0, 10))?.time
    } catch (e: Exception) {
      null
    }
  }
}

/** Risultato complessivo della scansione. */
data class NewEpisodesResult(
  val updates: List<SeriesEpisodeUpdate> = emptyList(),
  val scanned: Int = 0,
  val failed: Int = 0,
  /** `false` quando TMDB non è configurato: nessun dato episodi recuperabile. */
  val hasTmdbAccess: Boolean = false
) {
  val newEpisodes: List<SeriesEpisodeUpdate>
    get() = updates.filter { it.hasNewEpisodes }

  val totalNewEpisodes: Int
    get() = newEpisodes.sumOf { it.newEpisodeCount }
}
