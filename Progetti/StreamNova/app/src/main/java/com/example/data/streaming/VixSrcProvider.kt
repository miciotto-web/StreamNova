package com.example.data.streaming

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.example.data.streaming.extractors.awaitResponse

/**
 * Estrattore del provider **VixSrc / VixCloud** (streaming HTTP diretto, no Debrid).
 *
 * Catena di risoluzione verificata:
 *  1. `GET {base}/api/movie/{tmdb}` oppure `GET {base}/api/tv/{tmdb}/{stagione}/{episodio}`
 *     -> JSON `{"src": "/embed/{id}?token=...&expires=...&canPlayFHD=1"}`.
 *  2. `GET {base}{src}` (pagina embed) -> HTML con `window.streams` (Server1/Server2),
 *     `window.masterPlaylist.params` (token/expires) e `window.canPlayFHD`.
 *  3. Costruzione dell'URL della **master playlist HLS**: `streams[i].url` + i params
 *     non vuoti + `h=1` se il titolo supporta l'FHD. I params vuoti (es. `asn`) NON
 *     vanno inviati: la presenza di un parametro vuoto fa rispondere 403 Forbidden.
 *  4. Validazione della playlist (deve iniziare con `#EXTM3U`) usando esattamente gli
 *     header che poi userà ExoPlayer, così un problema di 403 viene scoperto qui.
 *
 * Mirror di fallback (in ordine): vixsrc.to (primario), vixcloud.to, vixcloud.cc,
 * vixcloud.co. Se il primario non risponde si passa al mirror successivo.
 *
 * Gli header `Referer` + `User-Agent` sono OBBLIGATORI: senza Referer il CDN degli
 * HLS segmenti (vix-content.net / edge) risponde 403 Forbidden.
 */
class VixSrcProvider(
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
) : StreamProvider {

  private data class EmbedStream(val name: String, val url: String)

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    if (tmdbId <= 0) throw IOException("TMDB ID non valido: $tmdbId")

    Log.d("THE_PITT_STREAM", "VixSrc resolving TMDB=$tmdbId isTv=$isTv season=$season episode=$episode")

    val failures = mutableListOf<String>()
    for (base in BASE_URLS) {
      currentCoroutineContext().ensureActive()
      try {
        val sources = resolveOnBase(base, tmdbId, isTv, season, episode)
        if (sources.isNotEmpty()) {
          Log.i(TAG, "Estrazione riuscita su $base: ${sources.size} sorgente/i")
          return@withContext sources
        }
        failures += "$base -> nessuna sorgente"
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        Log.w(TAG, "Fallback mirror $base: ${e.message}")
        failures += "$base -> ${e.message}"
      }
    }
    throw IOException("Nessun mirror VixSrc disponibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnBase(
    base: String,
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?
  ): List<StreamSource> {
    // --- 1) API di risoluzione titolo -> embed -----------------------------
    val apiPath = if (isTv) {
      "/api/tv/$tmdbId/${(season ?: 1).coerceAtLeast(1)}/${(episode ?: 1).coerceAtLeast(1)}"
    } else {
      "/api/movie/$tmdbId"
    }
    val apiUrl = if ('?' in apiPath) "$base$apiPath&canPlayFHD=1&h=1" else "$base$apiPath?canPlayFHD=1&h=1"
    Log.i(TAG, "GET API  $apiUrl")
    val apiBody = get(apiUrl, referer = "$base/")
    val src = JSONObject(apiBody).optString("src", "")?.trim().orEmpty()
    if (src.isEmpty()) throw IOException("Risposta API senza campo 'src': ${apiBody.take(160)}")

    val embedPath = if (src.startsWith("http")) {
      try {
        val uri = java.net.URI(src)
        uri.rawPath + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
      } catch (e: Exception) {
        "/" + src.substringAfter("://").substringAfter("/")
      }
    } else {
      src
    }

    // --- 2) Pagina embed -> streams, params, canPlayFHD --------------------
    // Prova prima la base corrente e, se fallisce (es. HTTP 410), tenta con lo stesso src sui mirror di fallback
    val candidateBases = listOf(base) + BASE_URLS.filter { it != base }
    var embedHtml: String? = null
    var activeBase = base
    var lastEmbedError: Exception? = null

    for (candidateBase in candidateBases) {
      currentCoroutineContext().ensureActive()
      val rawEmbed = if (embedPath.startsWith("/")) "$candidateBase$embedPath" else "$candidateBase/$embedPath"
      val embedUrl = buildString {
        append(rawEmbed)
        if (!rawEmbed.contains("canPlayFHD=")) {
          append(if ('?' in rawEmbed) "&canPlayFHD=1" else "?canPlayFHD=1")
        }
        if (!rawEmbed.contains("h=")) {
          append(if ('?' in this) "&h=1" else "?h=1")
        }
      }
      Log.i(TAG, "GET EMBED $embedUrl")
      try {
        val html = get(embedUrl, referer = "$candidateBase/")
        if (html.contains("window.streams")) {
          embedHtml = html
          activeBase = candidateBase
          break
        } else {
          Log.w(TAG, "Embed su $candidateBase senza window.streams")
        }
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        Log.w(TAG, "Fallback embed mirror $candidateBase: ${e.message}")
        lastEmbedError = e
      }
    }

    val page = embedHtml ?: throw (lastEmbedError ?: IOException("Nessun mirror embed valido per $src"))
    val streamsJson = extractBlock(page, "window.streams", '[', ']')
      ?: throw IOException("window.streams non trovato nell'embed")
    val streamsArray = JSONArray(streamsJson)
    val masterBlock = extractBlock(page, "window.masterPlaylist", '{', ';')
      ?: throw IOException("window.masterPlaylist non trovato nell'embed")

    val masterUrl = Regex("""url\s*:\s*'([^']+)'""").find(masterBlock)?.groupValues?.get(1)
      ?: throw IOException("masterPlaylist.url non trovato")
    // NOTA: niente regex con graffe (Android/ICU rigetta `\{`): si estrae il
    // blocco params con le posizioni e si parsano le singole coppie.
    val paramsBlock = substringBetween(masterBlock, "params", '{', '}')
    val params = PAIR_REGEX.findAll(paramsBlock)
      .associate { it.groupValues[1] to it.groupValues[2] }

    val canPlayFhd = Regex("""canPlayFHD\s*=\s*(true|false)""").find(page)
      ?.groupValues?.get(1) != "false"
    Log.i(TAG, "masterPlaylist=$masterUrl params=$params canPlayFHD=$canPlayFhd")

    // --- 3) Costruzione URL playlist per ogni server (Priorità 1080p FHD) ---
    val effectiveReferer = "$activeBase/"
    val headers = mapOf(
      "Referer" to effectiveReferer,
      "User-Agent" to USER_AGENT
    )
    val sources = mutableListOf<StreamSource>()
    for (i in 0 until streamsArray.length()) {
      val entry = streamsArray.getJSONObject(i)
      if (!entry.optBoolean("active", true)) continue
      val rawUrl = entry.optString("url").trim()
      if (rawUrl.isEmpty()) continue
      val serverName = if (i == 0) SERVER_NAME else "$SERVER_NAME ${entry.optString("name", "Mirror")}"

      val playlistUrlFhd = buildString {
        append(rawUrl)
        params.forEach { (key, value) ->
          if (value.isNotEmpty()) append(if ('?' in rawUrl || '?' in this) '&' else '?')
            .append(key).append('=').append(value)
        }
        append(if ('?' in rawUrl || '?' in this) '&' else '?').append("h=1")
        append("&canPlayFHD=1")
      }

      val playlistUrlStandard = buildString {
        append(rawUrl)
        params.forEach { (key, value) ->
          if (value.isNotEmpty()) append(if ('?' in rawUrl || '?' in this) '&' else '?')
            .append(key).append('=').append(value)
        }
      }

      // Tentativo primario Full HD (h=1), con fallback su standard
      var chosenUrl = playlistUrlFhd
      var playlistBody = try {
        get(chosenUrl, referer = effectiveReferer)
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        null
      }
      if (playlistBody == null || !playlistBody.startsWith("#EXTM3U")) {
        chosenUrl = playlistUrlStandard
        playlistBody = get(chosenUrl, referer = effectiveReferer)
      }
      if (!playlistBody.startsWith("#EXTM3U")) {
        throw IOException("Playlist non HLS su $serverName: ${playlistBody.take(120)}")
      }
      val renditions = parseRenditions(playlistBody)
      val verifiedHeight = when {
        renditions.any { it.contains("1080", ignoreCase = true) } -> 1080
        renditions.any { it.contains("720", ignoreCase = true) } -> 720
        renditions.any { it.contains("480", ignoreCase = true) } -> 480
        else -> null
      }
      val detectedQuality = when (verifiedHeight) {
        1080 -> "1080p"
        720 -> "720p"
        480 -> "480p"
        else -> "Auto"
      }
      val declared = if (chosenUrl.contains("h=1")) "1080p" else "Auto"
      Log.i(TAG, "OK $serverName (declared=$declared, verifiedHeight=$verifiedHeight, renditions=$renditions) url=$chosenUrl")

      sources += StreamSource(
        streamUrl = chosenUrl,
        quality = detectedQuality,
        serverName = serverName,
        headers = headers,
        declaredQuality = declared,
        verifiedHeight = verifiedHeight
      )
    }
    if (sources.isEmpty()) throw IOException("Nessun server attivo nell'embed")
    return sources
  }

  /** GET con header da browser; lancia [IOException] su status non 2xx. */
  private suspend fun get(url: String, referer: String?): String {
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", "*/*")
      .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
      .get()
    if (!referer.isNullOrBlank()) request.header("Referer", referer)

    val response = http.newCall(request.build()).awaitResponse()
    response.use { resp ->
      val body = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) {
        throw IOException("HTTP ${resp.code} su $url")
      }
      return body
    }
  }

  /**
   * Estrae il blocco che inizia dal primo [open] successivo a [marker] e finisce
   * al primo [close] (compreso): es. `window.streams = [ ... ]` -> `[ ... ]`.
   */
  private fun extractBlock(html: String, marker: String, open: Char, close: Char): String? {
    val markerIndex = html.indexOf(marker).takeIf { it >= 0 } ?: return null
    val openIndex = html.indexOf(open, markerIndex + marker.length).takeIf { it >= 0 } ?: return null
    val closeIndex = html.indexOf(close, openIndex + 1).takeIf { it >= 0 } ?: return null
    return html.substring(openIndex, closeIndex + 1)
  }

  /**
   * Estrae il testo tra il primo [open] successivo a [key] e il primo [close]
   * (blocchi annidati non attesi nell'HTML dell'embed).
   * Usato al posto di una regex con graffe: Android/ICU rigetta `\{`.
   */
  private fun substringBetween(source: String, key: String, open: Char, close: Char): String {
    val keyIndex = source.indexOf(key).takeIf { it >= 0 } ?: return ""
    val openIndex = source.indexOf(open, keyIndex + key.length).takeIf { it >= 0 } ?: return ""
    val closeIndex = source.indexOf(close, openIndex + 1).takeIf { it >= 0 } ?: return ""
    return source.substring(openIndex + 1, closeIndex)
  }

  /** Ritorna le risoluzioni dichiarate nella master playlist (uso solo log/telemetria). */
  private fun parseRenditions(masterPlaylist: String): List<String> {
    return Regex("""RESOLUTION=(\d+)x(\d+)""").findAll(masterPlaylist)
      .map { "${it.groupValues[2]}p" }
      .distinct()
      .toList()
  }

  companion object {
    private const val TAG = "VixSrcProvider"

    /** Coppie `'chiave': 'valore'` del blocco `params` della master playlist. */
    private val PAIR_REGEX = Regex("""['"]([^'"]+)['"]\s*:\s*['"]([^'"]*)['"]""")

    /** User-Agent desktop coerente tra estrazione e riproduzione. */
    const val USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Referer obbligatorio: senza di esso il CDN risponde 403 Forbidden. */
    const val REFERER = "https://vixsrc.to/"

    const val SERVER_NAME = "VixSrc"

    /** Endpoint primario + mirror di fallback (in ordine di tentativo). */
    private val BASE_URLS = listOf(
      "https://vixsrc.to",
      "https://vixcloud.co",
      "https://vixcloud.to",
      "https://vixcloud.cc"
    )
  }
}
