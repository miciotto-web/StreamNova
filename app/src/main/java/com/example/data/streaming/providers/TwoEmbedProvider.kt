package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.ExtractorHttp
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Provider aggregatore **2Embed** (2embed.cc / 2embed.stream).
 *
 * Catena verificata su un titolo reale (Inception, TMDB 27205):
 *  1. `GET {base}/embed/{tmdb}` (film) o `/embed/tv/{tmdb}/{s}/{e}` (serie) —
 *     2embed.cc contiene l'iframe del partner `vidsrc.buzz`;
 *  2. `GET https://vidsrc.buzz/embed/movie/{tmdb}` → variabile JS `var Q = {...}`
 *     con `type`, `id` (IMDB), `t` (token) e `ssr.servers`;
 *  3. `GET {origin}/pl/api.php?a=sources&type=&id=&s=&e=&t=` → JSON con i server
 *     (`ref` + `name`) — il percorso è relativo al tag `<base href="/pl/">`;
 *  4. per ogni server `GET {origin}/pl/api.php?a=play&ref=...&stesso qs`
 *     → `{"url":"/_stream?id=...","type":"hls"}` (alcuni server rispondono
 *     `unavailable`: si provano tutti in ordine);
 *  5. la master playlist viene validata (`#EXTM3U`) e se ne legge la risoluzione
 *     massima per etichettare la qualità (fino a **1080p nativo**, ~4.4 Mbps).
 *
 * Header: `Referer` della pagina embed + User-Agent desktop (altrimenti 403).
 */
class TwoEmbedProvider : StreamProvider {

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    if (tmdbId <= 0) throw IOException("TMDB ID non valido: $tmdbId")
    val s = (season ?: 1).coerceAtLeast(1)
    val e = (episode ?: 1).coerceAtLeast(1)

    val failures = mutableListOf<String>()
    for (base in BASE_URLS) {
      currentCoroutineContext().ensureActive()
      try {
        val sources = resolveOnBase(base, tmdbId, isTv, s, e)
        if (sources.isNotEmpty()) {
          Log.i(TAG, "sorgenti 2Embed su $base: ${sources.size}")
          return@withContext sources
        }
        failures += "$base -> nessuna sorgente"
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.w(TAG, "fallback mirror $base: ${ex.message}")
        failures += "$base -> ${ex.message}"
      }
    }
    throw IOException("2Embed non risolvibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnBase(base: String, tmdbId: Int, isTv: Boolean, s: Int, e: Int): List<StreamSource> {
    // --- 1) pagina embed (i pattern variano tra mirror) -------------------
    val embedCandidates = if (isTv) {
      listOf(
        "$base/embed/tv/$tmdbId/$s/$e",
        "$base/embedtv/$tmdbId&s=$s&e=$e",
        "$base/embedtv/$tmdbId?s=$s&e=$e"
      )
    } else {
      listOf("$base/embed/movie/$tmdbId", "$base/embed/$tmdbId")
    }

    var embedUrl = ""
    var embedHtml: String? = null
    for (candidate in embedCandidates) {
      currentCoroutineContext().ensureActive()
      try {
        embedHtml = ExtractorHttp.get(candidate, referer = "$base/")
        embedUrl = candidate
        break
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.i(TAG, "embed non disponibile: $candidate (${ex.message})")
      }
    }
    val page = embedHtml ?: throw IOException("nessuna pagina embed 2Embed per TMDB $tmdbId")
    Log.i(TAG, "GET EMBED $embedUrl")

    // --- 2) iframe del partner VidSrc ------------------------------------
    // La pagina contiene più iframe: uno autoreferenziale del mirror, un
    // placeholder `src="about:blank"` con il player reale in `data-src` (lazy
    // load) e il partner VidSrc. Si preferisce VidSrc, che espone l'API
    // `/pl/api.php` verificata.
    val iframeCandidates = (
      IFRAME_REGEX.findAll(page).map { it.groupValues[1] } +
        // data-src: solo URL che sembrano player/embed (evita immagini lazy)
        DATA_SRC_REGEX.findAll(page).map { it.groupValues[1] }.filter { PLAYERISH_REGEX.containsMatchIn(it) }
      )
      .filter { !it.contains("youtube") && !it.contains("googletag") && !it.contains("histats") }
      // Scarta i placeholder (about:blank, javascript:, data:...)
      .filter { it.startsWith("http") || it.startsWith("//") || it.startsWith("/") }
      .distinct()
      .toList()
    val iframe = iframeCandidates.firstOrNull { it.contains("vidsrc") }
      ?: iframeCandidates.firstOrNull { !it.contains(hostOf(embedUrl)) }
      ?: iframeCandidates.firstOrNull()
      ?: throw IOException("nessun iframe player nella pagina 2Embed")

    val playerUrl = when {
      iframe.startsWith("http") -> iframe
      iframe.startsWith("//") -> "https:$iframe"
      else -> originOf(embedUrl) + iframe
    }.replace("&amp;", "&")
    Log.i(TAG, "iframe player: $playerUrl")

    // Catena VidSrc (usata sia da 2embed.cc sia dagli embed mirror)
    if (playerUrl.contains("vidsrc")) {
      return vidSrcSources(playerUrl, isTv, s, e)
    }

    // Fallback generico: ricerca di URL media diretti nella pagina dell'iframe
    val html = ExtractorHttp.get(playerUrl, referer = embedUrl)
    val direct = MEDIA_REGEX.findAll(html.replace("\\/", "/")).map { it.groupValues[1] }.distinct().toList()
    if (direct.isEmpty()) throw IOException("iframe 2Embed senza sorgenti dirette: $playerUrl")
    return listOf(
      StreamSource(
        streamUrl = direct.first(),
        quality = "Auto",
        serverName = SERVER_NAME,
        headers = headersFor(playerUrl)
      )
    )
  }

  /**
   * Catena VidSrc: `var Q` -> `pl/api.php?a=sources` -> `pl/api.php?a=play` per
   * ogni server -> URL relativo della master playlist (risolto sull'origin).
   */
  private suspend fun vidSrcSources(embedUrl: String, isTv: Boolean, s: Int, e: Int): List<StreamSource> {
    val page = ExtractorHttp.get(embedUrl, referer = originOf(embedUrl) + "/")
    val qBlock = Q_REGEX.find(page)?.groupValues?.get(1)
      ?: throw IOException("variabile Q assente nella pagina VidSrc")

    val type = JSON_FIELD_REGEX.find(qBlock)?.groupValues?.get(1)
      ?: if (isTv) "tv" else "movie"
    val imdb = JSON_FIELD_REGEX2.find(qBlock)?.groupValues?.get(1)
      ?: throw IOException("id IMDB assente in Q")
    val token = TOKEN_REGEX.find(qBlock)?.groupValues?.get(1)
      ?: throw IOException("token 't' assente in Q")
    // Stagione/episodio vanno presi da Q: il token è vincolato a quei valori
    // (per i film sono 0/0, passarli diversi fa rispondere 403)
    val sParam = SEASON_REGEX.find(qBlock)?.groupValues?.get(1)?.toIntOrNull()
      ?: if (isTv) s else 0
    val eParam = EPISODE_REGEX.find(qBlock)?.groupValues?.get(1)?.toIntOrNull()
      ?: if (isTv) e else 0

    val qs = "type=$type&id=${enc(imdb)}&s=$sParam&e=$eParam&t=${enc(token)}"
    val origin = originOf(embedUrl)

    val sourcesJson = ExtractorHttp.get("$origin/pl/api.php?a=sources&$qs", referer = embedUrl)
    val servers = SERVER_REGEX.findAll(sourcesJson)
      .map { it.groupValues[1] to it.groupValues[2] }
      .toList()
    if (servers.isEmpty()) throw IOException("nessun server disponibile da VidSrc")
    Log.i(TAG, "server VidSrc (${servers.size}): " + servers.joinToString { it.second })

    val out = mutableListOf<StreamSource>()
    for ((ref, name) in servers) {
      currentCoroutineContext().ensureActive()
      val playJson = try {
        ExtractorHttp.get("$origin/pl/api.php?a=play&ref=${enc(ref)}&$qs", referer = embedUrl)
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.i(TAG, "server $name: ${ex.message}")
        continue
      }
      val path = PLAY_URL_REGEX.find(playJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: continue
      val playType = PLAY_TYPE_REGEX.find(playJson)?.groupValues?.get(1) ?: "hls"
      if (path.isBlank()) continue
      val url = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> origin + path
        else -> origin + "/" + path
      }

      // Validazione + lettura risoluzione massima dalla master playlist
      val headers = headersFor(embedUrl)
      val (quality, bandwidth) = probePlaylist(url, headers, playType)
      if (quality == null) {
        Log.i(TAG, "server $name: playlist non valida ($url)")
        continue
      }
      Log.i(TAG, "server $name OK: $quality (bandwidth ${bandwidth ?: "?"} bps) -> $url")
      out += StreamSource(
        streamUrl = url,
        quality = quality,
        serverName = "$SERVER_NAME $name",
        headers = headers
      )
      // Il primo server funzionante basta: la lista resta ordinata per preferenza
      break
    }
    if (out.isEmpty()) throw IOException("tutti i server VidSrc hanno risposto unavailable")
    return out
  }

  /** Ritorna (qualità etichettata, bandwidth max) oppure (null, null) se invalida. */
  private suspend fun probePlaylist(url: String, headers: Map<String, String>, playType: String): Pair<String?, Int?> {
    if (!playType.contains("hls", ignoreCase = true) && !url.contains(".m3u8", ignoreCase = true)) {
      // MP4 diretti: nessuna validazione possibile senza scaricare il file
      return "1080p" to null
    }
    return try {
      val body = ExtractorHttp.get(url, referer = headers["Referer"])
      if (!body.startsWith("#EXTM3U")) return null to null
      val widths = RESOLUTION_REGEX.findAll(body).map { it.groupValues[1].toInt() }.toList()
      val bandwidths = BANDWIDTH_REGEX.findAll(body).map { it.groupValues[1].toInt() }.toList()
      val maxW = widths.maxOrNull()
      val quality = when {
        maxW == null -> "Auto"
        maxW >= 1800 -> "1080p"
        maxW >= 1200 -> "720p"
        maxW >= 900 -> "HD"
        maxW >= 600 -> "480p"
        else -> "Auto"
      }
      quality to bandwidths.maxOrNull()
    } catch (ex: Exception) {
      null to null
    }
  }

  private fun headersFor(embedUrl: String) = mapOf(
    "Referer" to (originOf(embedUrl) + "/"),
    "User-Agent" to ExtractorHttp.USER_AGENT
  )

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}"
  } catch (e: Exception) {
    "https://www.2embed.cc"
  }

  private fun hostOf(url: String): String = try {
    URI(url).host.orEmpty().removePrefix("www.")
  } catch (e: Exception) {
    ""
  }

  private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

  companion object {
    private const val TAG = "TwoEmbedProvider"
    const val SERVER_NAME = "2Embed"

    /** Mirror 2Embed (il primo è quello che espone l'iframe VidSrc). */
    private val BASE_URLS = listOf(
      "https://www.2embed.cc",
      "https://www.2embed.stream"
    )

    private val IFRAME_REGEX = Regex("""<iframe[^>]{0,400}?src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val DATA_SRC_REGEX = Regex("""data-(?:src|url)="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val PLAYERISH_REGEX = Regex("""(vidsrc|videm|embed|player|play|video|\.php)""", RegexOption.IGNORE_CASE)
    private val Q_REGEX = Regex("""var Q = (\{[\s\S]*?\});""")
    private val JSON_FIELD_REGEX = Regex(""""type":"([^"]+)"""")
    private val JSON_FIELD_REGEX2 = Regex(""""id":"(tt\d+)"""")
    private val TOKEN_REGEX = Regex(""""t":"([^"]+)"""")
    private val SEASON_REGEX = Regex(""""s":(\d+)""")
    private val EPISODE_REGEX = Regex(""""e":(\d+)""")
    private val SERVER_REGEX = Regex(""""ref":"([^"]+)"[\s\S]{0,200}?"name":"([^"]+)"""")
    private val PLAY_URL_REGEX = Regex(""""url":"([^"]+)"""")
    private val PLAY_TYPE_REGEX = Regex(""""type":"([^"]+)"""")
    private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
    private val MEDIA_REGEX = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
  }
}
