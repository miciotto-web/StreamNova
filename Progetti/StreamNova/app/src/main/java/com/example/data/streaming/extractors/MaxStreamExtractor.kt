package com.example.data.streaming.extractors

import android.util.Log
import com.example.data.streaming.StreamSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estrattore per gli hoster **MaxStream** (`maxstream.video`, varianti incluse).
 *
 * Flusso verificato su una scheda reale:
 *  1. `GET {embedUrl}` con User-Agent desktop e `Referer: https://maxstream.video/`
 *     (la pagina contiene il tag `<video id="vjsplayer">` di video.js e lo script
 *     di setup del player).
 *  2. Nel markup/negli script compare l'URL **assoluto** della master playlist HLS
 *     (`.urlset/master.m3u8`) oppure, per alcuni file, un MP4 diretto.
 *  3. L'URL viene restituito con gli header obbligatori (Referer + UA): la CDN
 *     `host-cdn.net` risponde 403 senza Referer coerente.
 *
 * Sono supportati anche URL relativi/protocol-relative (`//host/...` o `/path`)
 * dichiarati in `<source src=...>` o nel player setup.
 */
class MaxStreamExtractor : VideoExtractor {

  override suspend fun extract(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
    Log.i(TAG, "GET embed $embedUrl")
    val html = ExtractorHttp.get(embedUrl, referer = REFERER)
    if (html.isBlank()) throw IOException("Pagina embed MaxStream vuota: $embedUrl")

    // I JSON negli script usano gli slash escapati: li normalizziamo prima del parsing
    val normalized = html.replace("\\/", "/")

    val candidates = LinkedHashSet<String>()

    // 1) URL assoluti HLS/MP4 presenti in markup o script
    URL_REGEX.findAll(normalized).forEach { candidates += it.groupValues[1] }

    // 2) Tag <source src="..."> / <video src="...">
    SOURCE_REGEX.findAll(normalized).forEach { raw ->
      val src = raw.groupValues[1]
      when {
        src.startsWith("http") -> candidates += src
        src.startsWith("//") -> candidates += "https:$src"
        src.startsWith("/") -> candidates += baseOrigin(embedUrl) + src
      }
    }

    if (candidates.isEmpty()) {
      throw IOException("Nessuna sorgente .m3u8/.mp4 nella pagina MaxStream (hoster protetto o file rimosso)")
    }

    // Preferenza HLS (adattivo) sui file progressivi
    val best = candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) } ?: candidates.first()
    Log.i(TAG, "Sorgente MaxStream estratta: $best")

    listOf(
      StreamSource(
        streamUrl = best,
        quality = "Auto",
        serverName = SERVER_NAME,
        headers = mapOf("Referer" to REFERER, "User-Agent" to ExtractorHttp.USER_AGENT)
      )
    )
  }

  private fun baseOrigin(url: String): String {
    val m = ORIGIN_REGEX.find(url) ?: return "https://maxstream.video"
    return m.value
  }

  companion object {
    private const val TAG = "MaxStreamExtractor"
    const val SERVER_NAME = "MaxStream"
    const val REFERER = "https://maxstream.video/"

    private val URL_REGEX = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
    private val SOURCE_REGEX = Regex("""<(?:video|source)[^>]*\ssrc\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val ORIGIN_REGEX = Regex("""^https?://[^/]+""", RegexOption.IGNORE_CASE)
  }
}
