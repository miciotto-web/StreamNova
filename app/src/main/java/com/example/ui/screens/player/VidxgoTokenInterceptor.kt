package com.example.ui.screens.player

import android.util.Log
import com.example.data.streaming.providers.Vidxgo
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Rinnova il token HLS dei flussi vidxgo (`*.d2b.you`) prima della scadenza.
 *
 * Il player di Altadefinizione restituisce una master playlist firmata con
 * `?t=<token>&e=<expire_ms>`: il token dura **circa 180 secondi** e, una volta
 * scaduto, sia la playlist sia i segmenti rispondono **403**. Il player web
 * rinnova il token chiamando `GET /t/{id}` (film) o `/t/{id}/{s}/{e}` (serie),
 * che restituisce un nuovo URL con nuova scadenza.
 *
 * Questo interceptor replica lo stesso comportamento lato app:
 *  - agisce **solo** sugli host del CDN vidxgo (`*.d2b.you`): nessun'altra
 *    sorgente viene toccata;
 *  - adotta il token della richiesta se è più recente di quello noto;
 *  - se mancano meno di [REFRESH_MARGIN_MS] alla scadenza, chiama l'endpoint
 *    di refresh e riscrive la richiesta (host + `t`/`e`/`b`, preservando gli
 *    altri parametri come `p=`);
 *  - su 403 forza un refresh e ritenta **una sola volta**.
 *
 * Il refresh avviene sul thread di caricamento di Media3: è un GET JSON molto
 * piccolo e viene eseguito solo ogni ~2,5 minuti, quindi non introduce jank.
 * Un errore di refresh non interrompe la riproduzione: la richiesta originale
 * prosegue e, se il CDN la rifiuta, resta il fallback di sorgente già esistente.
 */
internal class VidxgoTokenInterceptor(
  private val refreshOrigin: String = Vidxgo.ORIGIN,
  private val cdnHostPredicate: (String) -> Boolean = { host ->
    host.equals("d2b.you", ignoreCase = true) ||
      host.endsWith(Vidxgo.CDN_HOST_SUFFIX, ignoreCase = true)
  },
  private val nowMs: () -> Long = { System.currentTimeMillis() },
  refreshClient: OkHttpClient? = null
) : Interceptor {

  private data class VideoKey(
    val id: String,
    val season: Int?,
    val episode: Int?
  )

  private data class TokenState(
    val url: String,
    val expireMs: Long
  )

  private val refreshClient: OkHttpClient = refreshClient ?: sharedRefreshClient

  /** Ultimo token noto per video (in memoria, mai loggato). */
  private val tokens = ConcurrentHashMap<VideoKey, TokenState>()

  /** Lock per video: due richieste concorrenti non duplicano il refresh. */
  private val locks = ConcurrentHashMap<VideoKey, Any>()

  override fun intercept(chain: Interceptor.Chain): Response {
    val original = chain.request()
    val requestUrl = original.url
    if (!cdnHostPredicate(requestUrl.host)) return chain.proceed(original)

    val key = videoKey(requestUrl) ?: return chain.proceed(original)
    adoptRequestToken(key, requestUrl)

    val current = tokens[key]
    if (current == null || nowMs() >= current.expireMs - REFRESH_MARGIN_MS) {
      refresh(key)
    }

    val rewritten = rewrite(original, key)
    val response = chain.proceed(rewritten ?: original)
    if (response.code != HTTP_FORBIDDEN) return response

    // Token scaduto lato CDN (clock skew o richiesta partita al limite):
    // refresh forzato e un solo retry. Il retry avviene anche quando la prima
    // richiesta non era riscrivibile (token identico all'originale): è proprio
    // il caso in cui il 403 segnala che quel token non è più valido.
    response.close()
    refresh(key, force = true)
    val retry = rewrite(original, key)
    return chain.proceed(retry ?: original)
  }

  /** Se la richiesta porta un token più recente di quello noto, lo adotta. */
  private fun adoptRequestToken(key: VideoKey, url: HttpUrl) {
    val expire = url.queryParameter(PARAM_EXPIRE)?.toLongOrNull() ?: return
    val current = tokens[key]
    if (current == null || expire > current.expireMs) {
      tokens[key] = TokenState(url.toString(), expire)
    }
  }

  /**
   * Rinnova il token del video. `force=true` ignora la scadenza nota (usato
   * dopo un 403). Un fallimento viene solo loggato: il chiamante prosegue con
   * la richiesta originale.
   */
  private fun refresh(key: VideoKey, force: Boolean = false) {
    val lock = locks.computeIfAbsent(key) { Any() }
    synchronized(lock) {
      val current = tokens[key]
      if (!force && current != null && nowMs() < current.expireMs - REFRESH_MARGIN_MS) {
        return
      }

      val path = Vidxgo.tokenPath(key.id, key.season, key.episode)
      val request = Request.Builder()
        .url(refreshOrigin.trimEnd('/') + path)
        .header("User-Agent", Vidxgo.BROWSER_USER_AGENT)
        .header("Accept", "application/json")
        .header("Referer", "$refreshOrigin/")
        .header("Sec-Fetch-Dest", "empty")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Site", "same-origin")
        .get()
        .build()

      try {
        refreshClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            Log.w(TAG, "refresh token HTTP ${response.code} per ${key.id}")
            return
          }
          val body = response.body?.string().orEmpty()
          val freshUrl = JSON_URL_REGEX.find(body)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()
          if (freshUrl.isNullOrBlank()) {
            Log.w(TAG, "refresh token senza URL per ${key.id}")
            return
          }
          val expire = JSON_EXPIRE_REGEX.find(body)?.groupValues?.get(1)?.toLongOrNull()
            ?: (nowMs() + FALLBACK_TTL_MS)
          tokens[key] = TokenState(freshUrl, expire)
          Log.i(TAG, "token rinnovato per ${key.id} (expire=$expire)")
        }
      } catch (e: IOException) {
        Log.w(TAG, "refresh token fallito per ${key.id}: ${e.message}")
      }
    }
  }

  /**
   * Riscrive host e token della richiesta usando l'ultimo token noto.
   *
   * Il path viene normalizzato a partire da `/hls/`: le URL del player possono
   * arrivare sia in forma diretta (`/hls/{id}/...`) sia proxata
   * (`/proxy/media-XXX/hls/{id}/...`). Il nuovo token può risiedere su un host
   * diverso (es. `cdn.v1.*` invece di `cdn.v3.*`), quindi l'origin viene
   * sostituito per intero.
   */
  private fun rewrite(request: Request, key: VideoKey): Request? {
    val state = tokens[key] ?: return null
    val fresh = state.url.toHttpUrlOrNull() ?: return null
    val original = request.url
    val hlsIndex = original.encodedPath.indexOf(HLS_PATH_MARKER)
    if (hlsIndex < 0) return null

    val suffixPath = original.encodedPath.substring(hlsIndex)
    val builder = fresh.newBuilder().encodedPath(suffixPath)
    builder.query(null)
    original.queryParameterNames.forEach { name ->
      if (name != PARAM_TOKEN && name != PARAM_EXPIRE && name != PARAM_BITRATE) {
        original.queryParameterValues(name).orEmpty().forEach { value ->
          builder.addQueryParameter(name, value)
        }
      }
    }
    fresh.queryParameter(PARAM_TOKEN)?.let { builder.setQueryParameter(PARAM_TOKEN, it) }
    fresh.queryParameter(PARAM_EXPIRE)?.let { builder.setQueryParameter(PARAM_EXPIRE, it) }
    fresh.queryParameter(PARAM_BITRATE)?.let { builder.setQueryParameter(PARAM_BITRATE, it) }

    val rewritten = builder.build()
    if (rewritten == original) return null
    return request.newBuilder().url(rewritten).build()
  }

  /** Identifica video/stagione/episodio dal path del CDN. */
  private fun videoKey(url: HttpUrl): VideoKey? {
    val path = url.encodedPath
    TV_PATH_REGEX.find(path)?.let { match ->
      val season = match.groupValues[2].toIntOrNull()
      val episode = match.groupValues[3].toIntOrNull()
      if (season != null && episode != null) {
        return VideoKey(match.groupValues[1], season, episode)
      }
    }
    MOVIE_PATH_REGEX.find(path)?.let { match ->
      return VideoKey(match.groupValues[1], null, null)
    }
    return null
  }

  companion object {
    private const val TAG = "VidxgoToken"

    /** Rinnova quando mancano meno di 30 s: copre clock skew e richieste in volo. */
    private const val REFRESH_MARGIN_MS = 30_000L

    /** TTL usato se la risposta non dichiara `expire` (il player reale dura ~180 s). */
    private const val FALLBACK_TTL_MS = 150_000L

    private const val HTTP_FORBIDDEN = 403
    private const val HLS_PATH_MARKER = "/hls/"
    private const val PARAM_TOKEN = "t"
    private const val PARAM_EXPIRE = "e"
    private const val PARAM_BITRATE = "b"

    private val TV_PATH_REGEX = Regex("""(?:^|/)hls/tv/(\d+)/(\d+)/(\d+)(?:/|$)""")
    private val MOVIE_PATH_REGEX = Regex("""(?:^|/)hls/(\d+)(?:/|$)""")
    private val JSON_URL_REGEX = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
    private val JSON_EXPIRE_REGEX = Regex("\"expire\"\\s*:\\s*(\\d+)")

    /** Client dedicato al refresh: niente interceptor (nessuna ricorsione). */
    private val sharedRefreshClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    }
  }
}
