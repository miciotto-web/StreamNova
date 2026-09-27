package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.ExtractorHttp
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provider aggregatore **SuperEmbed / MultiEmbed** (famiglia "Movie Streaming API").
 *
 * Entrambi i server espongono lo stesso player via parametro `video_id`:
 *  - `https://superembed.stream/se_player.php?video_id={tmdbId}` (documentato:
 *    "Our player script se_player.php...", con `&s=&e=` per le serie)
 *  - `https://multiembed.mov/?video_id={tmdbId}` (senza parametri risponde
 *    `Missing video_id`)
 *
 * La pagina player (≈650 KB) contiene sorgenti codificate e, sui mirror attivi,
 * un **gate Cloudflare Turnstile** (`.captcha-gate` + `challenges.cloudflare.com`):
 * quando non è presente alcuna sorgente direttamente leggibile, l'estrazione viene
 * interrotta con un'IOException esplicita che riporta il gate rilevato (utile in
 * Logcat), invece di restituire un URL non valido al player.
 *
 * La ricerca delle sorgenti copre comunque i casi "aperti": URL `.m3u8`/`.mp4`
 * nel markup, tag `<video>/<source>`, iframe annidati (massimo 2 livelli).
 */
class SuperEmbedProvider : StreamProvider {

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
    for ((name, base) in BASE_URLS) {
      try {
        val sources = resolveOnBase(name, base, tmdbId, isTv, s, e)
        if (sources.isNotEmpty()) {
          Log.i(TAG, "sorgenti $name: ${sources.size}")
          return@withContext sources
        }
        failures += "$name -> nessuna sorgente"
      } catch (ex: Exception) {
        Log.w(TAG, "$name: ${ex.message}")
        failures += "$name -> ${ex.message}"
      }
    }
    throw IOException("SuperEmbed/MultiEmbed non risolvibile (${failures.joinToString(" | ")})")
  }

  private fun resolveOnBase(
    name: String,
    base: String,
    tmdbId: Int,
    isTv: Boolean,
    s: Int,
    e: Int
  ): List<StreamSource> {
    val candidates = if (isTv) {
      listOf(
        "$base/se_player.php?video_id=$tmdbId&s=$s&e=$e",
        "$base/?video_id=$tmdbId&s=$s&e=$e"
      )
    } else {
      listOf(
        "$base/se_player.php?video_id=$tmdbId",
        "$base/?video_id=$tmdbId"
      )
    }

    var page: String? = null
    var used = ""
    for (candidate in candidates) {
      try {
        page = ExtractorHttp.get(candidate, referer = "$base/")
        used = candidate
        break
      } catch (ex: Exception) {
        Log.i(TAG, "$name endpoint non disponibile: $candidate (${ex.message})")
      }
    }
    val html = page ?: throw IOException("nessuna pagina player $name per TMDB $tmdbId")
    Log.i(TAG, "GET PLAYER $used (${html.length} byte)")
    val normalized = html.replace("\\/", "/")

    // --- 1) sorgenti dirette nel markup -----------------------------------
    val direct = MEDIA_REGEX.findAll(normalized).map { it.groupValues[1] }.distinct().toList()
    if (direct.isNotEmpty()) {
      val url = direct.first()
      Log.i(TAG, "$name sorgente diretta: $url")
      return listOf(
        StreamSource(
          url = url,
          quality = if (url.contains(".m3u8", ignoreCase = true)) "Auto" else "1080p",
          serverName = name,
          headers = mapOf("Referer" to "$base/", "User-Agent" to ExtractorHttp.USER_AGENT)
        )
      )
    }

    // --- 2) tag video/source ----------------------------------------------
    val srcTag = SOURCE_REGEX.find(normalized)?.groupValues?.get(1)
    if (srcTag != null && srcTag.startsWith("http")) {
      Log.i(TAG, "$name sorgente da <source>: $srcTag")
      return listOf(
        StreamSource(
          url = srcTag,
          quality = "Auto",
          serverName = name,
          headers = mapOf("Referer" to "$base/", "User-Agent" to ExtractorHttp.USER_AGENT)
        )
      )
    }

    // --- 3) iframe annidati (massimo 2 livelli) ----------------------------
    val iframe = IFRAME_REGEX.findAll(normalized)
      .map { it.groupValues[1] }
      .firstOrNull { !it.contains("youtube") && !it.contains("googletag") && !it.contains("histats") }
    if (iframe != null) {
      val iframeUrl = when {
        iframe.startsWith("http") -> iframe
        iframe.startsWith("//") -> "https:$iframe"
        else -> originOf(used) + iframe
      }
      Log.i(TAG, "$name iframe annidato: $iframeUrl")
      val nested = try {
        ExtractorHttp.get(iframeUrl, referer = used).replace("\\/", "/")
      } catch (ex: Exception) {
        throw IOException("iframe $name non raggiungibile: ${ex.message}")
      }
      val nestedMedia = MEDIA_REGEX.findAll(nested).map { it.groupValues[1] }.distinct().toList()
      if (nestedMedia.isNotEmpty()) {
        return listOf(
          StreamSource(
            url = nestedMedia.first(),
            quality = "Auto",
            serverName = name,
            headers = mapOf("Referer" to iframeUrl, "User-Agent" to ExtractorHttp.USER_AGENT)
          )
        )
      }
    }

    // --- 4) gate rilevato ---------------------------------------------------
    if (CAPTCHA_GATE_REGEX.containsMatchIn(normalized)) {
      throw IOException("$name protetto da captcha Cloudflare Turnstile (gate del player)")
    }
    throw IOException("$name senza sorgenti leggibili (player offuscato o file non disponibile)")
  }

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}"
  } catch (e: Exception) {
    ""
  }

  companion object {
    private const val TAG = "SuperEmbedProvider"

    /** Nome -> base URL (stessa famiglia di player, parametri identici). */
    private val BASE_URLS = linkedMapOf(
      "SuperEmbed" to "https://superembed.stream",
      "MultiEmbed" to "https://multiembed.mov"
    )

    private val MEDIA_REGEX = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
    private val IFRAME_REGEX = Regex("""<iframe[^>]{0,400}?src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val SOURCE_REGEX = Regex("""<(?:video|source)[^>]*\ssrc\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val CAPTCHA_GATE_REGEX = Regex("""captcha-gate|challenges\.cloudflare\.com/turnstile""", RegexOption.IGNORE_CASE)
  }
}
