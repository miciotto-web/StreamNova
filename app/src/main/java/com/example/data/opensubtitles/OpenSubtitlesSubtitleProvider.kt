package com.example.data.opensubtitles

import android.util.Log
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provider esterno di sottotitoli basato su OpenSubtitles API v3.
 *
 * Supporta la ricerca per film e serie TV, utilizzando IMDb/TMDB e stagione/episodio
 * quando disponibili. Esegue il mapping nel modello [Subtitle] e gestisce in modo sicuro
 * errori HTTP, risposte vuote, timeout e risultati senza URL di download valido.
 */
class OpenSubtitlesSubtitleProvider(
  private val api: OpenSubtitlesApi = OpenSubtitlesApiClient.api,
  private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

  /**
   * Cerca e restituisce i sottotitoli disponibili da OpenSubtitles v3 per un media specifico.
   */
  suspend fun fetchSubtitles(
    imdbId: String? = null,
    tmdbId: Int? = null,
    title: String? = null,
    isTv: Boolean = false,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    languages: List<String> = DEFAULT_LANGUAGES
  ): List<Subtitle> = withContext(Dispatchers.IO) {
    try {
      withTimeoutOrNull(timeoutMs) {
        val numericImdbId = parseImdbId(imdbId)
        val validTmdbId = tmdbId?.takeIf { it > 0 }
        val searchType = if (isTv) TYPE_EPISODE else TYPE_MOVIE
        val languagesParam = languages.filter { it.isNotBlank() }.joinToString(",").ifBlank { null }
        val queryParam = if (numericImdbId == null && validTmdbId == null) title?.takeIf { it.isNotBlank() } else null

        val response = try {
          api.searchSubtitles(
            imdbId = numericImdbId,
            tmdbId = validTmdbId,
            type = searchType,
            seasonNumber = if (isTv) seasonNumber else null,
            episodeNumber = if (isTv) episodeNumber else null,
            languages = languagesParam,
            query = queryParam
          )
        } catch (e: Exception) {
          Log.w(TAG, "Errore di rete durante la chiamata a OpenSubtitles: ${e.message}")
          return@withTimeoutOrNull emptyList()
        }

        if (!response.isSuccessful) {
          Log.w(TAG, "OpenSubtitles API error: HTTP ${response.code()} ${response.message()}")
          return@withTimeoutOrNull emptyList()
        }

        val body = response.body()
        val items = body?.data
        if (items.isNullOrEmpty()) {
          Log.d(TAG, "OpenSubtitles: nessun sottotitolo trovato")
          return@withTimeoutOrNull emptyList()
        }

        val resultList = mutableListOf<Subtitle>()
        for (item in items) {
          // Se l'elemento ha già un URL valido (diretto o dal file), mappalo subito
          val directSubtitle = OpenSubtitlesSubtitleAdapter.toSubtitle(item)
          if (directSubtitle != null) {
            resultList.add(directSubtitle)
            continue
          }

          // Se non ha URL diretto ma ha un file_id, tenta il recupero del link di download
          val fileId = item.attributes?.files?.firstOrNull()?.fileId
          if (fileId != null && resultList.size < MAX_ON_DEMAND_DOWNLOAD_RESOLUTIONS) {
            try {
              val downloadResp = api.requestDownloadLink(OpenSubtitlesDownloadRequest(fileId))
              if (downloadResp.isSuccessful) {
                val link = downloadResp.body()?.link
                val subtitleWithResolvedUrl = OpenSubtitlesSubtitleAdapter.toSubtitle(
                  item = item,
                  downloadUrlOverride = link
                )
                if (subtitleWithResolvedUrl != null) {
                  resultList.add(subtitleWithResolvedUrl)
                }
              }
            } catch (e: Exception) {
              Log.w(TAG, "Impossibile recuperare download link per fileId $fileId: ${e.message}")
            }
          }
        }

        resultList
      } ?: run {
        Log.w(TAG, "Timeout scaduto ($timeoutMs ms) per la richiesta a OpenSubtitles")
        emptyList()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Eccezione generica durante la risoluzione OpenSubtitles: ${e.message}")
      emptyList()
    }
  }

  /**
   * Overload comodo per invocazione a partire da un [MediaItem] e relativo [Episode].
   */
  suspend fun fetchSubtitles(
    mediaItem: MediaItem,
    episode: Episode? = null,
    preferredLanguage: String? = null
  ): List<Subtitle> {
    val isTv = mediaItem.type == MediaType.SERIE_TV
    val season = if (isTv) episode?.seasonNumber ?: mediaItem.lastWatchedSeason ?: 1 else null
    val ep = if (isTv) episode?.episodeNumber ?: mediaItem.lastWatchedEpisode ?: 1 else null

    val rawImdb = if (mediaItem.id.startsWith("tt", ignoreCase = true)) {
      mediaItem.id
    } else {
      episode?.id?.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(':')
    }

    val langs = if (!preferredLanguage.isNullOrBlank()) {
      listOf(preferredLanguage, "it", "en").distinct()
    } else {
      DEFAULT_LANGUAGES
    }

    return fetchSubtitles(
      imdbId = rawImdb,
      tmdbId = mediaItem.tmdbId,
      title = mediaItem.title,
      isTv = isTv,
      seasonNumber = season,
      episodeNumber = ep,
      languages = langs
    )
  }

  companion object {
    private const val TAG = "OpenSubtitlesProvider"
    private const val DEFAULT_TIMEOUT_MS = 10_000L
    private const val TYPE_MOVIE = "movie"
    private const val TYPE_EPISODE = "episode"
    private const val MAX_ON_DEMAND_DOWNLOAD_RESOLUTIONS = 3

    val DEFAULT_LANGUAGES = listOf("it", "en")

    fun parseImdbId(raw: String?): Int? {
      if (raw.isNullOrBlank()) return null
      val digits = raw.trim().removePrefix("tt").removePrefix("TT")
      return digits.toIntOrNull()
    }
  }
}
