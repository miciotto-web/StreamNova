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
 *    di refresh e riscrive **solo `t` ed `e`** della richiesta, come il player
 *    web;
 *  - su 403 forza un refresh e ritenta **una sola volta**.
 *
 * ## Scadenze locali, non orologio di parete
 *
 * `expire` è un epoch **del server**, mentre [nowMs] è l'orologio del
 * dispositivo. Confrontarli direttamente è fragile: su un box con l'orologio
 * avanti di più di [REFRESH_MARGIN_MS] ogni richiesta CDN risulterebbe "in
 * scadenza" e farebbe un refresh sincrono prima di ogni segmento, con il
 * caricamento che si ferma ogni volta (buffering continuo) e l'endpoint token
 * martellato fino al rate-limit. Il refresh viene quindi pianificato con una
 * **deadline locale**: subito dopo un refresh il token è appena stato emesso e
 * vale [REFRESH_INTERVAL_MS]; un token adottato da una richiesta usa la
 * scadenza dichiarata solo se è plausibile, altrimenti ricade sull'intervallo
 * locale. Un `expire` nel passato o non allineato non può più trasformare ogni
 * segmento in un refresh.
 *
 * ## Riscrittura come il player web: solo `t` ed `e`
 *
 * Il player web originale (`signUrl` nel suo script) intercetta ogni
 * URL che contiene `/hls/` e sostituisce **soltanto** i parametri `t` ed `e`
 * con il token e la scadenza appena ottenuti. Host, porta, path (compreso
 * l'eventuale prefisso `/proxy/media-XXX/`), `b`, `p` e ogni altro parametro
 * restano quelli della richiesta. Il player web non usa né l'host né il path
 * né il `b` restituiti da `/t/...`: li usa solo per leggere il nuovo `t/e`.
 *
 * Questo interceptor fa lo stesso. Sostituire l'host/path con quelli della
 * risposta token (comportamento precedente) spostava le richieste su un edge
 * o un path diverso dopo il primo rinnovo — es. perdendo `/proxy/media-402/`
 * o cambiando `b` per la rendition in riproduzione — e il CDN rispondeva
 * 403/404: la riproduzione si fermava e andava in buffering continuo proprio
 * dopo ~2,5 minuti. `p` è validato dal CDN (nei flussi che lo usano, es.
 * `cdn.*.media-619`) e va conservato insieme alla richiesta firmata.
 *
 * Il refresh avviene sul thread di caricamento di Media3: è un GET JSON molto
 * piccolo e viene eseguito solo ogni ~2,5 minuti, quindi non introduce jank.
 * Un errore di refresh non interrompe la riproduzione: la richiesta originale
 * prosegue e, se il CDN la rifiuta, resta il fallback di sorgente già esistente.
 * Un refresh fallito non viene ripetuto a ogni segmento per
 * [REFRESH_FAILURE_BACKOFF_MS].
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
    /** Valore del parametro `t` da applicare alle richieste `/hls/`. */
    val token: String,
    /** Valore del parametro `e` (epoch server), usato anche per scegliere il più recente. */
    val expireMs: Long,
    /** Istante locale (stesso clock di [nowMs]) in cui richiedere il rinnovo. */
    val refreshAtMs: Long
  )

  private val refreshClient: OkHttpClient = refreshClient ?: sharedRefreshClient

  /** Ultimo token noto per video (in memoria, mai loggato). */
  private val tokens = ConcurrentHashMap<VideoKey, TokenState>()

  /** Lock per video: due richieste concorrenti non duplicano il refresh. */
  private val locks = ConcurrentHashMap<VideoKey, Any>()

  /**
   * Dopo un refresh fallito non si ritenta prima di questo istante locale.
   * Evita che un endpoint token lento/irraggiungibile blocchi OGNI segmento
   * con un nuovo tentativo sincrono.
   */
  private val refreshNotBeforeMs = ConcurrentHashMap<VideoKey, Long>()

  override fun intercept(chain: Interceptor.Chain): Response {
    val original = chain.request()
    val requestUrl = original.url
    if (!cdnHostPredicate(requestUrl.host)) return chain.proceed(original)

    val key = videoKey(requestUrl) ?: return chain.proceed(original)
    adoptRequestToken(key, requestUrl)

    val current = tokens[key]
    if (current == null || nowMs() >= current.refreshAtMs) {
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
    val token = url.queryParameter(PARAM_TOKEN)?.takeIf { it.isNotBlank() } ?: return
    val expire = url.queryParameter(PARAM_EXPIRE)?.toLongOrNull() ?: return
    val current = tokens[key]
    if (current == null || expire > current.expireMs) {
      val now = nowMs()
      tokens[key] = TokenState(
        token = token,
        expireMs = expire,
        refreshAtMs = refreshDeadlineForAdoptedToken(expire, now)
      )
    }
  }

  /**
   * Pianifica il rinnovo di un token ricevuto dentro una richiesta.
   *
   * La scadenza è confrontata con l'orologio locale solo quando la differenza è
   * plausibile; un valore nel passato (token già scaduto o clock avanti), o
   * troppo lontano (clock indietro), ricade sull'intervallo locale del player.
   * In nessun caso un token appena emesso produce un refresh a ogni richiesta.
   */
  private fun refreshDeadlineForAdoptedToken(expireMs: Long, now: Long): Long {
    val remaining = expireMs - now
    if (remaining <= 0L) return now
    // Oltre la durata dichiarata il valore non è attendibile (orologio indietro):
    // non si aspetta più di un ciclo locale. Sotto, si rinnova 30 s prima.
    val boundedRemaining = remaining.coerceAtMost(DEFAULT_TOKEN_TTL_MS)
    return now + (boundedRemaining - REFRESH_MARGIN_MS).coerceAtLeast(0L)
  }

  /**
   * Rinnova il token del video. `force=true` ignora la deadline nota (usato
   * dopo un 403). Un fallimento viene solo loggato e impone un breve backoff:
   * il chiamante prosegue con la richiesta originale, senza bloccare ogni
   * segmento su un nuovo tentativo.
   */
  private fun refresh(key: VideoKey, force: Boolean = false) {
    val lock = locks.computeIfAbsent(key) { Any() }
    synchronized(lock) {
      val now = nowMs()
      if (!force) {
        val current = tokens[key]
        if (current != null && now < current.refreshAtMs) {
          return
        }
        if (now < (refreshNotBeforeMs[key] ?: 0L)) {
          return
        }
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
            markRefreshFailure(key)
            return
          }
          val body = response.body?.string().orEmpty()
          val freshUrl = JSON_URL_REGEX.find(body)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()
          val fresh = freshUrl?.toHttpUrlOrNull()
          val freshToken = fresh?.queryParameter(PARAM_TOKEN)?.takeIf { it.isNotBlank() }
          val freshExpire = JSON_EXPIRE_REGEX.find(body)?.groupValues?.get(1)?.toLongOrNull()
            ?: fresh?.queryParameter(PARAM_EXPIRE)?.toLongOrNull()
          if (freshToken == null || freshExpire == null) {
            Log.w(TAG, "refresh token senza t/e validi per ${key.id}")
            markRefreshFailure(key)
            return
          }
          // Il token è appena stato emesso dal server: il rinnovo va pianificato
          // sull'orologio LOCALE. Il resto della URL restituita (host, path, b, p)
          // NON viene usato: come il player web, si ruotano solo t ed e.
          tokens[key] = TokenState(
            token = freshToken,
            expireMs = freshExpire,
            refreshAtMs = nowMs() + REFRESH_INTERVAL_MS
          )
          refreshNotBeforeMs.remove(key)
          Log.i(TAG, "token rinnovato per ${key.id} (expire=$freshExpire)")
        }
      } catch (e: IOException) {
        Log.w(TAG, "refresh token fallito per ${key.id}: ${e.message}")
        markRefreshFailure(key)
      }
    }
  }

  /** Blocca per [REFRESH_FAILURE_BACKOFF_MS] i tentativi non forzati. */
  private fun markRefreshFailure(key: VideoKey) {
    refreshNotBeforeMs[key] = nowMs() + REFRESH_FAILURE_BACKOFF_MS
  }

  /**
   * Applica l'ultimo token alla richiesta, come il `signUrl` del player web:
   * sostituisce **solo** `t` ed `e`.
   *
   * Host, porta, path (compreso un eventuale prefisso `/proxy/media-XXX/`),
   * `b`, `p` e ogni altro parametro restano quelli della richiesta originale.
   * La URL restituita da `/t/...` serve unicamente a leggere il nuovo `t/e`:
   * seguirne host/path/`b` significherebbe cambiare edge o rendition proprio
   * durante la riproduzione, con 403/404 e buffering al primo rinnovo.
   */
  private fun rewrite(request: Request, key: VideoKey): Request? {
    val state = tokens[key] ?: return null
    val original = request.url
    if (!original.encodedPath.contains(HLS_PATH_MARKER)) return null

    val rewritten = original.newBuilder()
      .setQueryParameter(PARAM_TOKEN, state.token)
      .setQueryParameter(PARAM_EXPIRE, state.expireMs.toString())
      .build()
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

    /** Durata dichiarata del token vidxgo (il player reale dura ~180 s). */
    private const val DEFAULT_TOKEN_TTL_MS = 180_000L

    /** Rinnova quando mancano meno di 30 s: copre richieste in volo e latenza. */
    private const val REFRESH_MARGIN_MS = 30_000L

    /** Deadline LOCALE dopo un refresh riuscito: 180 s meno il margine. */
    private const val REFRESH_INTERVAL_MS = DEFAULT_TOKEN_TTL_MS - REFRESH_MARGIN_MS

    /** Dopo un refresh fallito non si ritenta prima di questo intervallo. */
    private const val REFRESH_FAILURE_BACKOFF_MS = 10_000L

    private const val HTTP_FORBIDDEN = 403
    private const val HLS_PATH_MARKER = "/hls/"
    private const val PARAM_TOKEN = "t"
    private const val PARAM_EXPIRE = "e"

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
