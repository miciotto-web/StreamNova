package com.example.data.library

import android.content.Context
import android.util.Log
import com.example.data.api.TmdbApiClient
import com.example.data.local.CachedMediaItemEntity
import com.example.data.local.StreamNovaDatabase
import com.example.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Repository del New Episodes Center.
 *
 * Sorgente delle serie seguite: righe Room con stato utente (preferito, progresso,
 * "Continua a guardare", completamento). Per ognuna legge da TMDB i dettagli
 * della/e stagione/i rilevanti — al massimo due, per contenere le richieste — e
 * applica [NewEpisodesEngine].
 *
 * Nessuna scrittura: il centro è in sola lettura e non modifica progressi,
 * preferiti o cache. Le richieste sono limitate da un semaforo e da un timeout
 * per serie; un errore su una serie non interrompe la scansione delle altre.
 */
object NewEpisodesRepository {

  private const val TAG = "NewEpisodesRepo"

  /** Serie massime analizzate per scansione (le più recenti per ultima visione). */
  const val MAX_SERIES = 15

  /** Richieste TMDB concorrenti massime. */
  const val MAX_CONCURRENT_REQUESTS = 4

  /** Timeout per singola serie (tutte le stagioni richieste). */
  const val SERIES_TIMEOUT_MS = 12_000L

  suspend fun load(context: Context): NewEpisodesResult = withContext(Dispatchers.IO) {
    val db = StreamNovaDatabase.getInstance(context)
      ?: return@withContext NewEpisodesResult(hasTmdbAccess = MediaRepository.isTmdbConfigured())
    val rows = try {
      db.cachedMediaDao().getAllCachedMedia()
    } catch (e: Exception) {
      Log.w(TAG, "Lettura libreria fallita: ${e.message}")
      return@withContext NewEpisodesResult(hasTmdbAccess = MediaRepository.isTmdbConfigured())
    }

    val tracked = rows
      .filter { it.type.equals("SERIE_TV", ignoreCase = true) }
      .filter {
        it.isFavorite || it.lastWatchedAt != null || it.currentProgressMs > 0L ||
          it.lastWatchedSeason != null || it.completedAt != null
      }
      .sortedByDescending { it.lastWatchedAt ?: 0L }
      .take(MAX_SERIES)

    if (tracked.isEmpty()) {
      return@withContext NewEpisodesResult(hasTmdbAccess = MediaRepository.isTmdbConfigured())
    }

    val apiKey = MediaRepository.getEffectiveApiKey()
    if (apiKey.isBlank()) {
      // Senza TMDB non possiamo conoscere gli episodi andati in onda: si dichiara
      // il limite invece di mostrare un falso "nessun nuovo episodio".
      return@withContext NewEpisodesResult(
        updates = tracked.map { series ->
          SeriesEpisodeUpdate(series = series.toTrackedSeries())
        },
        scanned = tracked.size,
        hasTmdbAccess = false
      )
    }

    val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    val failed = java.util.concurrent.atomic.AtomicInteger(0)
    val updates = coroutineScope {
      tracked.map { row ->
        async {
          semaphore.withPermit {
            try {
              val result = withTimeoutOrNull(SERIES_TIMEOUT_MS) {
                evaluateSeries(row.toTrackedSeries(), apiKey)
              }
              if (result == null) {
                failed.incrementAndGet()
                SeriesEpisodeUpdate(series = row.toTrackedSeries())
              } else {
                result
              }
            } catch (e: Exception) {
              Log.w(TAG, "Analisi serie '${row.title}' fallita: ${e.message}")
              failed.incrementAndGet()
              SeriesEpisodeUpdate(series = row.toTrackedSeries())
            }
          }
        }
      }.awaitAll()
    }

    val ordered = updates.sortedWith(
      compareBy<SeriesEpisodeUpdate> { statusPriority(it.status) }
        .thenByDescending { it.latestAired?.let { ep -> NewEpisodesEngine.parseAirDateMillis(ep.airDate) } ?: 0L }
        .thenBy { it.series.title.lowercase() }
    )
    Log.i(
      TAG,
      "Scan completata: ${ordered.size} serie, ${ordered.count { it.hasNewEpisodes }} con nuovi episodi, ${failed.get()} errori"
    )
    NewEpisodesResult(
      updates = ordered,
      scanned = tracked.size,
      failed = failed.get(),
      hasTmdbAccess = true
    )
  }

  /**
   * Recupera le stagioni rilevanti e applica il motore.
   *
   * Stagioni richieste: l'ultima dichiarata ([CachedMediaItemEntity.seasonsCount]),
   * l'ultima vista (per coprire serie a metà stagione) e la prima (fallback per
   * dati storici incompleti). Massimo due richieste per serie.
   */
  private suspend fun evaluateSeries(
    series: TrackedSeries,
    apiKey: String
  ): SeriesEpisodeUpdate {
    val tmdbId = series.tmdbId
      ?: return SeriesEpisodeUpdate(series = series)

    val candidateSeasons = buildList {
      series.lastWatchedSeason?.let { add(it) }
      series.seasonsCount?.let { add(it) }
      add(1)
    }.filter { it > 0 }.distinct().sortedDescending().take(2).sorted()

    val episodes = mutableListOf<AiredEpisodeInfo>()
    candidateSeasons.forEach { season ->
      val detail = TmdbApiClient.service.getSeasonDetails(
        seriesId = tmdbId,
        seasonNumber = season,
        apiKey = apiKey
      )
      detail.episodes.orEmpty().forEach { episode ->
        episodes += AiredEpisodeInfo(
          season = episode.seasonNumber,
          episode = episode.episodeNumber,
          name = episode.name,
          airDate = episode.airDate,
          runtimeMinutes = episode.runtime
        )
      }
    }
    return NewEpisodesEngine.evaluate(series, episodes)
  }

  /** Ordine di presentazione: nuovi episodi prima, poi serie non ancora iniziate. */
  private fun statusPriority(status: NewEpisodesStatus): Int = when (status) {
    NewEpisodesStatus.NEW_EPISODES -> 0
    NewEpisodesStatus.NOT_STARTED -> 1
    NewEpisodesStatus.UP_TO_DATE -> 2
    NewEpisodesStatus.NO_DATA -> 3
  }

  /** Riga Room → modello del motore. Nessun campo inventato. */
  internal fun CachedMediaItemEntity.toTrackedSeries(): TrackedSeries = TrackedSeries(
    mediaId = id,
    title = title,
    tmdbId = tmdbId,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    lastWatchedSeason = lastWatchedSeason,
    lastWatchedEpisode = lastWatchedEpisode,
    lastWatchedAt = lastWatchedAt,
    completedAt = completedAt,
    seasonsCount = seasonsCount
  )
}
