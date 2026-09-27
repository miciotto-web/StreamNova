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
 * Provider ad alta risoluzione orientato al **Full HD 1080p**: **MultiEmbed** (multiembed.mov / superembed.stream).
 *
 * Supporta:
 *  - Film: `/movie/{id}` con parametro `?video_id={id}&tmdb=1`
 *  - Serie TV: `/tv/{id}/{season}/{episode}` con parametri `?video_id={id}&tmdb=1&s={season}&e={episode}`
 *
 * Flusso di estrazione:
 *  1. Interrogazione endpoint speculari (multiembed.mov, superembed.stream, getsuperembed.link);
 *  2. Estrazione playlist HLS (.m3u8) o stream MP4 diretto a 1080p Full HD;
 *  3. Validazione playlist HLS e marcatura esplicita della qualità a 1080p;
 *  4. Se nessun mirror risponde con sorgenti valide, restituisce lista vuota senza flussi fittizi.
 */
class MultiEmbedProvider : StreamProvider {

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
          Log.i(TAG, "sorgenti $name 1080p: ${sources.size}")
          return@withContext sources
        }
        failures += "$name -> nessuna sorgente"
      } catch (ex: Exception) {
        Log.w(TAG, "$name: ${ex.message}")
        failures += "$name -> ${ex.message}"
      }
    }

    // Se nessun mirror ha restituito sorgenti reali, restituisce lista vuota pulita
    Log.d(TAG, "MultiEmbed: nessuna sorgente reale trovata per TMDB $tmdbId (s=$s, e=$e): ${failures.joinToString(" | ")}")
    emptyList()
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
        "$base/?video_id=$tmdbId&tmdb=1&s=$s&e=$e",
        "$base/se_player.php?video_id=$tmdbId&tmdb=1&s=$s&e=$e",
        "https://getsuperembed.link/?video_id=$tmdbId&tmdb=1&season=$s&episode=$e"
      )
    } else {
      listOf(
        "$base/?video_id=$tmdbId&tmdb=1",
        "$base/se_player.php?video_id=$tmdbId&tmdb=1",
        "https://getsuperembed.link/?video_id=$tmdbId&tmdb=1"
      )
    }

    var page: String? = null
    var usedUrl = ""
    for (candidate in candidates) {
      try {
        val resp = ExtractorHttp.get(candidate, referer = "$base/")
        if (resp.isNotBlank() && !resp.contains("Not Found!", ignoreCase = true)) {
          page = resp
          usedUrl = candidate
          break
        }
      } catch (ex: Exception) {
        Log.d(TAG, "$name endpoint non disponibile: $candidate (${ex.message})")
      }
    }

    val html = page ?: throw IOException("nessuna pagina player per $name TMDB $tmdbId")
    val normalized = html.replace("\\/", "/")

    // 1) Cerca URL diretti .m3u8 o .mp4
    val direct = MEDIA_REGEX.findAll(normalized)
      .map { it.groupValues[1] }
      .distinct()
      .toList()

    if (direct.isNotEmpty()) {
      val url = direct.first()
      val quality = probeQuality(url, base)
      Log.i(TAG, "$name sorgente diretta trovata: $url (qualità $quality)")
      return listOf(
        StreamSource(
          url = url,
          quality = quality,
          serverName = "$SERVER_NAME 1080p",
          headers = mapOf(
            "Referer" to "$base/",
            "User-Agent" to ExtractorHttp.USER_AGENT
          )
        )
      )
    }

    // 2) Tag <video> o <source>
    val srcTag = SOURCE_REGEX.find(normalized)?.groupValues?.get(1)
    if (srcTag != null && srcTag.startsWith("http")) {
      val quality = probeQuality(srcTag, base)
      Log.i(TAG, "$name sorgente da <source>: $srcTag (qualità $quality)")
      return listOf(
        StreamSource(
          url = srcTag,
          quality = quality,
          serverName = "$SERVER_NAME 1080p",
          headers = mapOf(
            "Referer" to "$base/",
            "User-Agent" to ExtractorHttp.USER_AGENT
          )
        )
      )
    }

    // 3) Iframe annidati (massimo 2 livelli)
    val iframes = IFRAME_REGEX.findAll(normalized)
      .map { it.groupValues[1] }
      .filter { !it.contains("youtube") && !it.contains("histats") && !it.contains("google") }
      .toList()

    for (iframe in iframes) {
      val iframeUrl = when {
        iframe.startsWith("http") -> iframe
        iframe.startsWith("//") -> "https:$iframe"
        else -> originOf(usedUrl) + iframe
      }
      try {
        val nested = ExtractorHttp.get(iframeUrl, referer = usedUrl).replace("\\/", "/")
        val nestedDirect = MEDIA_REGEX.findAll(nested).map { it.groupValues[1] }.distinct().toList()
        if (nestedDirect.isNotEmpty()) {
          val url = nestedDirect.first()
          val quality = probeQuality(url, originOf(iframeUrl))
          return listOf(
            StreamSource(
              url = url,
              quality = quality,
              serverName = "$SERVER_NAME 1080p",
              headers = mapOf(
                "Referer" to iframeUrl,
                "User-Agent" to ExtractorHttp.USER_AGENT
              )
            )
          )
        }
      } catch (ex: Exception) {
        Log.d(TAG, "Iframe nested $iframeUrl non risolto: ${ex.message}")
      }
    }

    // 4) Se rilevato gate Turnstile o nessun link diretto estratto, solleva eccezione per mirror successivo
    if (CAPTCHA_GATE_REGEX.containsMatchIn(normalized)) {
      throw IOException("$name protetto da captcha Cloudflare Turnstile")
    }
    throw IOException("$name senza sorgenti dirette leggibili")
  }

  private fun probeQuality(url: String, referer: String): String {
    if (url.endsWith(".mp4", ignoreCase = true)) return "1080p"
    return try {
      val playlist = ExtractorHttp.get(url, referer = "$referer/")
      if (playlist.contains("1080") || playlist.contains("1920x1080")) {
        "1080p"
      } else if (playlist.contains("720") || playlist.contains("1280x720")) {
        "720p"
      } else {
        "1080p"
      }
    } catch (e: Exception) {
      "1080p"
    }
  }

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}"
  } catch (e: Exception) {
    ""
  }

  companion object {
    private const val TAG = "MultiEmbedProvider"
    const val SERVER_NAME = "MultiEmbed"

    /** Mirror principali MultiEmbed e SuperEmbed */
    private val BASE_URLS = linkedMapOf(
      "MultiEmbed" to "https://multiembed.mov",
      "SuperEmbed" to "https://superembed.stream"
    )

    private val MEDIA_REGEX = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
    private val IFRAME_REGEX = Regex("""<iframe[^>]{0,400}?src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val SOURCE_REGEX = Regex("""<(?:video|source)[^>]*\ssrc\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val CAPTCHA_GATE_REGEX = Regex("""captcha-gate|challenges\.cloudflare\.com/turnstile""", RegexOption.IGNORE_CASE)
  }
}
