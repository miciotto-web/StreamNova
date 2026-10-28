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
open class OpenSubtitlesSubtitleProvider(
  private val api: OpenSubtitlesApi = OpenSubtitlesApiClient.api,
  private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
  private val httpClient: okhttp3.OkHttpClient = OpenSubtitlesApiClient.createOkHttpClient()
) {

  /**
   * Cerca e restituisce i sottotitoli disponibili da OpenSubtitles v3 per un media specifico.
   *
   * @param year anno del contenuto: inviato SOLO quando mancano IMDb e TMDB (ricerca
   *   testuale), dove serve a disambiguare i titoli omonimi senza rischiare di filtrare
   *   fuori risultati corretti della ricerca per identificativo.
   * @param resolveLinksOnDemand quando true (comportamento storico usato all'avvio della
   *   riproduzione) risolve subito il link di download per i primi risultati privi di URL
   *   diretto, consumando quota download. Quando false (sezione "Sottotitoli Online") i
   *   risultati vengono restituiti tutti con [Subtitle.fileId] valorizzato e il link viene
   *   risolto solo alla selezione dell'utente.
   */
  open suspend fun fetchSubtitles(
    imdbId: String? = null,
    tmdbId: Int? = null,
    title: String? = null,
    isTv: Boolean = false,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    languages: List<String> = DEFAULT_LANGUAGES,
    year: Int? = null,
    resolveLinksOnDemand: Boolean = true
  ): List<Subtitle> = withContext(ioDispatcher) {
    try {
      withTimeoutOrNull(timeoutMs) {
        val numericImdbId = parseImdbId(imdbId)
        val validTmdbId = tmdbId?.takeIf { it > 0 }
        val searchType = if (isTv) TYPE_EPISODE else TYPE_MOVIE
        val languagesParam = languages.filter { it.isNotBlank() }.joinToString(",").ifBlank { null }
        val hasTrustedIdentifier = numericImdbId != null || validTmdbId != null
        val queryParam = if (!hasTrustedIdentifier) title?.takeIf { it.isNotBlank() } else null
        val yearParam = if (!hasTrustedIdentifier) year?.takeIf { it > 0 } else null

        val response = try {
          api.searchSubtitles(
            imdbId = numericImdbId,
            tmdbId = validTmdbId,
            type = searchType,
            seasonNumber = if (isTv) seasonNumber else null,
            episodeNumber = if (isTv) episodeNumber else null,
            languages = languagesParam,
            year = yearParam,
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
        var onDemandResolutions = 0
        for (item in items) {
          // Se l'elemento ha già un URL valido (diretto o dal file), mappalo subito
          val directSubtitle = OpenSubtitlesSubtitleAdapter.toSubtitle(item)
          if (directSubtitle != null && directSubtitle.url.isNotBlank()) {
            resultList.add(directSubtitle)
            continue
          }

          val fileId = item.attributes?.files?.firstOrNull()?.fileId?.takeIf { it > 0 }
          if (fileId == null) continue

          // Rientra comunque nell'elenco (privo di URL di download diretto): verrà
          // risolto con l'endpoint POST /download solo se l'utente lo seleziona.
          val pendingSubtitle = directSubtitle?.copy(url = "", fileId = fileId)
            ?: OpenSubtitlesSubtitleAdapter.toSubtitle(
              item = item,
              downloadUrlOverride = null
            )?.copy(url = "", fileId = fileId)
            ?: Subtitle(
              id = "os-file-$fileId",
              url = "",
              lang = OpenSubtitlesSubtitleAdapter.normalizeLanguage(item.attributes?.language),
              addonName = OpenSubtitlesSubtitleAdapter.PROVIDER_NAME,
              label = item.attributes?.release,
              fileId = fileId
            )
          resultList.add(pendingSubtitle)

          // Risoluzione immediata (quota download): solo se richiesta e per un numero
          // limitato di risultati, come nel comportamento storico all'avvio playback.
          if (resolveLinksOnDemand && onDemandResolutions < MAX_ON_DEMAND_DOWNLOAD_RESOLUTIONS) {
            try {
              val downloadResp = api.requestDownloadLink(OpenSubtitlesDownloadRequest(fileId))
              if (downloadResp.isSuccessful) {
                val link = downloadResp.body()?.link?.trim().orEmpty()
                if (link.startsWith("http")) {
                  val resolved = pendingSubtitle.copy(url = link)
                  resultList[resultList.lastIndex] = resolved
                  onDemandResolutions++
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
  open suspend fun fetchSubtitles(
    mediaItem: MediaItem,
    episode: Episode? = null,
    preferredLanguage: String? = null,
    year: Int? = null,
    resolveLinksOnDemand: Boolean = true
  ): List<Subtitle> {
    val isTv = mediaItem.type == MediaType.SERIE_TV
    val season = if (isTv) episode?.seasonNumber ?: mediaItem.lastWatchedSeason ?: 1 else null
    val ep = if (isTv) episode?.episodeNumber ?: mediaItem.lastWatchedEpisode ?: 1 else null

    val rawImdb = if (mediaItem.id.startsWith("tt", ignoreCase = true)) {
      mediaItem.id
    } else {
      episode?.id?.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(':')
    }
      ?: mediaItem.imdbId?.takeIf { it.isNotBlank() }
      ?: episode?.seriesImdbId?.takeIf { it.isNotBlank() }

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
      languages = langs,
      year = year ?: mediaItem.year,
      resolveLinksOnDemand = resolveLinksOnDemand
    )
  }

  /**
   * Interroga l'addon OpenSubtitles v3 di Stremio:
   * GET https://opensubtitles-v3.strem.io/subtitles/{type}/{id}.json
   * dove {type} è "movie" o "series" e {id} è imdbId (film) o "${imdbId}:${season}:${episode}" (serie).
   *
   * Deserializza la risposta estraendo id, lang, url (.srt / .vtt) e ordina l'elenco con priorità:
   * 1. Italiano (lang = "ita", "it", "Italian")
   * 2. Inglese (lang = "eng", "en", "English")
   * 3. Altre lingue disponibili in ordine alfabetico.
   */
  open suspend fun fetchFromStremioOpenSubtitles(
    type: String,
    id: String
  ): List<Subtitle> = withContext(ioDispatcher) {
    val cleanType = type.trim().lowercase()
    val cleanId = id.trim()
    if (cleanType.isEmpty() || cleanId.isEmpty()) return@withContext emptyList()

    val url = "https://opensubtitles-v3.strem.io/subtitles/$cleanType/$cleanId.json"
    try {
      withTimeoutOrNull(timeoutMs) {
        val request = okhttp3.Request.Builder()
          .url(url)
          .header("User-Agent", OpenSubtitlesApiClient.DEFAULT_USER_AGENT)
          .header("Accept", "application/json")
          .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
          Log.w(TAG, "OpenSubtitles Stremio addon error: HTTP ${response.code} per $url")
          return@withTimeoutOrNull emptyList()
        }

        val bodyString = response.body?.string()
        parseAndSortStremioSubtitles(bodyString)
      } ?: emptyList()
    } catch (e: Exception) {
      Log.w(TAG, "Errore durante fetchFromStremioOpenSubtitles ($url): ${e.message}")
      emptyList()
    }
  }

  /**
   * Overload comodo di [fetchFromStremioOpenSubtitles] per [MediaItem] e relativo [Episode].
   */
  open suspend fun fetchFromStremioOpenSubtitles(
    mediaItem: MediaItem,
    episode: Episode? = null
  ): List<Subtitle> {
    val isTv = mediaItem.type == MediaType.SERIE_TV
    val type = if (isTv) "series" else "movie"
    val imdbId = mediaItem.effectiveImdbId
      ?: episode?.effectiveImdbId
      ?: mediaItem.id.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(':')
      ?: episode?.id?.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(':')

    if (imdbId.isNullOrBlank()) {
      return emptyList()
    }

    val id = if (isTv) {
      val season = episode?.seasonNumber ?: mediaItem.lastWatchedSeason ?: 1
      val ep = episode?.episodeNumber ?: mediaItem.lastWatchedEpisode ?: 1
      "$imdbId:$season:$ep"
    } else {
      imdbId
    }

    return fetchFromStremioOpenSubtitles(type, id)
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

    /**
     * Deserializza il JSON della risposta dell'addon Stremio OpenSubtitles v3 ed estrae
     * i sottotitoli ordinandoli per priorità linguistica (Italiano, Inglese, altre in ordine alfabetico).
     */
    fun parseAndSortStremioSubtitles(json: String?): List<Subtitle> {
      if (json.isNullOrBlank()) return emptyList()
      return try {
        val adapter = OpenSubtitlesApiClient.moshi.adapter(StremioOpenSubtitlesResponse::class.java)
        val parsed = adapter.fromJson(json)
        val items = parsed?.subtitles.orEmpty().filter {
          !it.url.isNullOrBlank() && !it.lang.isNullOrBlank()
        }

        val rawList = items.mapNotNull { item ->
          val rawUrl = item.url?.trim().orEmpty()
          if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
            return@mapNotNull null
          }
          val rawId = item.id?.trim()?.ifBlank { null } ?: rawUrl.hashCode().toString()
          val lang = item.lang?.trim().orEmpty()
          val label = item.subtitleFileName?.trim()?.ifBlank { null }
            ?: item.movieReleaseName?.trim()?.ifBlank { null }
            ?: item.label?.trim()?.ifBlank { null }

          Subtitle(
            id = "os-$rawId",
            url = rawUrl,
            lang = lang,
            addonName = OpenSubtitlesSubtitleAdapter.PROVIDER_NAME,
            label = label,
            isStreamProvided = false
          )
        }
        sortSubtitlesByPriority(rawList)
      } catch (e: Exception) {
        Log.w(TAG, "Errore parsing sottotitoli Stremio OpenSubtitles: ${e.message}")
        emptyList()
      }
    }

    /**
     * Ordina i sottotitoli con priorità:
     * 1. Italiano (lang = "ita", "it", "Italian")
     * 2. Inglese (lang = "eng", "en", "English")
     * 3. Altre lingue disponibili in ordine alfabetico.
     */
    fun sortSubtitlesByPriority(subtitles: List<Subtitle>): List<Subtitle> {
      return subtitles.sortedWith(
        compareBy<Subtitle> { sub ->
          when {
            isItalianLanguage(sub.lang) -> 0
            isEnglishLanguage(sub.lang) -> 1
            else -> 2
          }
        }.thenBy { sub ->
          sub.getDisplayLanguage().lowercase()
        }.thenBy { sub ->
          sub.label.orEmpty().lowercase()
        }
      )
    }

    fun isItalianLanguage(lang: String): Boolean {
      val clean = lang.trim().lowercase()
      return clean == "ita" || clean == "it" || clean == "italian"
    }

    fun isEnglishLanguage(lang: String): Boolean {
      val clean = lang.trim().lowercase()
      return clean == "eng" || clean == "en" || clean == "english"
    }
  }
}

