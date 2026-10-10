package com.example.data.repository

import android.util.Log
import com.example.ui.screens.player.PlayerIntroUtils.IntroWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Repository per il recupero dei timestamp della sigla iniziale da IntroDB (https://introdb.app).
 * Supporta query tramite IMDb ID o TMDb ID per episodi di serie TV.
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
   * Recupera la finestra intro per un episodio di una serie TV da IntroDB.
   *
   * @param imdbId ID IMDb della serie (es. "tt0944947")
   * @param tmdbId ID TMDb della serie (es. 1399)
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

    val cacheKey = when {
      !cleanImdb.isNullOrBlank() -> "${cleanImdb}_${season}_${episode}"
      tmdbId != null && tmdbId > 0 -> "tmdb_${tmdbId}_${season}_${episode}"
      else -> return@withContext null
    }

    memoryCache[cacheKey]?.let {
      Log.d(TAG, "Cache hit per $cacheKey: $it")
      return@withContext it
    }

    var window: IntroWindow? = null
    if (!cleanImdb.isNullOrBlank()) {
      val url = "$BASE_URL?imdb_id=$cleanImdb&season=$season&episode=$episode"
      window = fetchFromIntroDb(url)
    }

    if (window == null && tmdbId != null && tmdbId > 0) {
      val url = "$BASE_URL?tmdb_id=$tmdbId&season=$season&episode=$episode"
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
