package com.example.data.repository

import android.util.Log
import com.example.data.api.TmdbApiClient
import com.example.ui.screens.player.PlayerIntroUtils.IntroWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Repository per il recupero dei timestamp della sigla iniziale da IntroDB (https://introdb.app).
 *
 * IntroDB accetta **solo** `imdb_id` (vedi la spec `https://api.introdb.app/openapi.json`):
 * l'endpoint `/intro` risponde `400 Invalid query params` a qualunque query con `tmdb_id`.
 * Per i titoli identificati dal solo id TMDB (catalogo TMDB dell'app: `MediaItem.id`
 * `tmdb_tv_<id>`) l'id IMDb viene quindi risolto prima della query tramite
 * [tmdbImdbResolver]; senza quella risoluzione la finestra intro non e' mai disponibile
 * e il pulsante "Salta intro" non puo' comparire.
 */
internal object IntroSkipRepository {

  private const val TAG = "IntroSkipRepository"
  private const val BASE_URL = "https://api.introdb.app/intro"
  private const val TIMEOUT_MS = 2500L

  @Volatile
  private var httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .build()

  /** Cache in memoria: chiave "${imdbId ?: tmdbId}_${season}_${episode}". */
  private val memoryCache = ConcurrentHashMap<String, IntroWindow>()

  /** Permette di sostituire il client nei test unitari. */
  fun setClientForTesting(client: OkHttpClient) {
    httpClient = client
  }

  fun resetClient() {
    httpClient = OkHttpClient.Builder()
      .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .build()
  }

  /**
   * Risoluzione dell'id IMDb a partire dall'id TMDB di una serie.
   *
   * Usa la stessa chiamata TMDB `external_ids` gia' impiegata per gli stream
   * (`StremioAddonRepository.resolveImdbId`), con lo stesso tetto di tempo dell'IntroDB:
   * la risoluzione non deve mai allungare l'attesa della finestra intro.
   * Sostituibile nei test unitari (nessuna rete reale).
   */
  @Volatile
  internal var tmdbImdbResolver: suspend (Int) -> String? = ::fetchImdbIdFromTmdb

  /** Ripristina la risoluzione reale dell'id IMDb (usata dai test). */
  fun resetTmdbImdbResolver() {
    tmdbImdbResolver = ::fetchImdbIdFromTmdb
  }

  private suspend fun fetchImdbIdFromTmdb(tmdbId: Int): String? = try {
    withTimeout(TIMEOUT_MS) {
      TmdbApiClient.service
        .getTvExternalIds(tvId = tmdbId, apiKey = MediaRepository.getEffectiveApiKey())
        .imdbId
    }?.takeIf { it.startsWith("tt", ignoreCase = true) }
  } catch (e: Exception) {
    Log.w(TAG, "Risoluzione id IMDb dal TMDB id $tmdbId fallita: ${e.message}")
    null
  }

  /**
   * Recupera la finestra intro per un episodio di una serie TV da IntroDB.
   *
   * @param imdbId ID IMDb della serie (es. "tt0944947"); se assente viene risolto da [tmdbId].
   * @param tmdbId ID TMDb della serie (es. 1399): usato solo per risolvere l'id IMDb
   *   (IntroDB non accetta query per id TMDB).
   * @param season Numero della stagione (1-indexed)
   * @param episode Numero dell'episodio (1-indexed)
   * @return [IntroWindow] con startMs ed endMs se presenti e validi, altrimenti `null`.
   */
  suspend fun getIntroWindow(
    imdbId: String?,
    tmdbId: Int?,
    season: Int,
    episode: Int
  ): IntroWindow? = withContext(Dispatchers.IO) {
    if (season <= 0 || episode <= 0) return@withContext null

    val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
      ?: imdbId?.trim()?.takeIf { it.isNotBlank() && !it.contains(":") }

    // Solo l'id TMDB (serie del catalogo TMDB: `MediaItem.id` = "tmdb_tv_<id>"):
    // IntroDB non accetta `tmdb_id`, quindi l'id IMDb va risolto prima della query.
    val effectiveImdb = cleanImdb?.takeIf { it.isNotBlank() }
      ?: tmdbId?.takeIf { it > 0 }?.let { tmdbImdbResolver(it) }

    val cacheKey = when {
      !effectiveImdb.isNullOrBlank() -> "${effectiveImdb}_${season}_${episode}"
      tmdbId != null && tmdbId > 0 -> "tmdb_${tmdbId}_${season}_${episode}"
      else -> return@withContext null
    }

    memoryCache[cacheKey]?.let {
      Log.d(TAG, "Cache hit per $cacheKey: $it")
      return@withContext it
    }

    var window: IntroWindow? = null
    if (!effectiveImdb.isNullOrBlank()) {
      val url = "$BASE_URL?imdb_id=$effectiveImdb&season=$season&episode=$episode"
      window = fetchFromIntroDb(url)
    }

    if (window != null && window.isValid) {
      memoryCache[cacheKey] = window
      Log.i(TAG, "Timestamp intro trovati per $cacheKey: $window")
      return@withContext window
    }

    Log.d(TAG, "Nessun timestamp intro disponibile per $cacheKey")
    null
  }

  private fun fetchFromIntroDb(url: String): IntroWindow? {
    try {
      Log.d(TAG, "GET IntroDB: $url")
      val request = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", "StreamNova/1.0")
        .build()

      httpClient.newCall(request).execute().use { response ->
        if (response.code != 200) {
          Log.d(TAG, "IntroDB HTTP ${response.code} per $url")
          return null
        }
        val body = response.body?.string() ?: return null
        val json = JSONObject(body)

        val startMs = when {
          json.has("start_ms") -> json.optLong("start_ms", -1L)
          json.has("startMs") -> json.optLong("startMs", -1L)
          json.has("start") -> (json.optDouble("start", -1.0) * 1000).toLong()
          else -> -1L
        }

        val endMs = when {
          json.has("end_ms") -> json.optLong("end_ms", -1L)
          json.has("endMs") -> json.optLong("endMs", -1L)
          json.has("end") -> (json.optDouble("end", -1.0) * 1000).toLong()
          else -> -1L
        }

        if (startMs >= 0L && endMs > startMs) {
          return IntroWindow(startMs = startMs, endMs = endMs)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Errore rete/timeout IntroDB ($url): ${e.message}")
    }
    return null
  }

  fun clearCache() {
    memoryCache.clear()
  }
}
