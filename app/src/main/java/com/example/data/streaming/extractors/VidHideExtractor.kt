package com.example.data.streaming.extractors

import android.util.Log
import com.example.data.streaming.StreamSource
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estrattore per gli hoster della famiglia **VidHide** (`vidhide.com`,
 * `vidhide.fun`, `vidhidepre.com`, `vidhidevip.com`, `filelions.*`).
 *
 * Flusso (allineato agli embed pubblici della famiglia):
 *  1. normalizzazione dell'URL: il primo segmento del path (`f`, `d`, `s`,
 *     `embed`, `download`, `file`) è un alias della pagina player `/v/`; `/e/`
 *     resta valido;
 *  2. `GET` della pagina embed con `Referer`/`Origin` dell'hoster e User-Agent
 *     desktop;
 *  3. ricerca della sorgente negli script: `sources: [{file: "..."}]` oppure
 *     blocco packed `eval(function(p,a,c,k,e,d){...})` (decodificato da
 *     [JsPacked], senza eseguire JavaScript);
 *  4. restituzione dell'URL HLS/MP4 con gli header necessari alla CDN.
 *
 * Limite noto: se l'hoster serve una pagina di challenge JavaScript o richiede
 * un captcha, l'estrazione fallisce con un errore esplicito. Non viene
 * implementato alcun bypass.
 */
class VidHideExtractor : VideoExtractor {

  override suspend fun extract(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
    val target = toEmbedUrl(embedUrl)
    val referer = originOf(target) + "/"
    Log.i(TAG, "GET embed $target")

    val (finalUrl, html) = ExtractorHttp.getWithFinalUrl(target, referer = referer)
    if (html.isBlank()) throw IOException("Pagina embed VidHide vuota: $embedUrl")

    val media = PlayerSourceParser.findMediaUrls(html, finalUrl).firstOrNull()
      ?: throw IOException(
        "Nessuna sorgente VidHide nella pagina embed (player protetto, challenge o markup cambiato)"
      )
    Log.i(TAG, "Sorgente VidHide estratta: $media")

    val origin = originOf(finalUrl).ifBlank { originOf(target) }
    listOf(
      StreamSource(
        streamUrl = media,
        quality = "Auto",
        serverName = SERVER_NAME,
        headers = mapOf(
          "Referer" to "$origin/",
          "Origin" to origin,
          "User-Agent" to ExtractorHttp.USER_AGENT
        )
      )
    )
  }

  /**
   * Normalizza l'URL della pagina player.
   *
   * Gli alias del player `/v/` sono i **primi segmenti** del path: `f`, `d`,
   * `s`, `embed`, `download` e `file`. Viene riscritto solo quel segmento:
   * schema, authority, segmenti successivi, query string e fragment restano
   * invariati byte per byte. Così un alias contenuto in `?next=/f/x` o in
   * `#/f/x` non viene corrotto, e `/e/` (non alias) resta valido.
   *
   * Un URL malformato (senza schema o authority, oppure vuoto) viene restituito
   * invariato.
   */
  internal fun toEmbedUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return url

    // Fine dell'authority: primo '/', '?' o '#' dopo lo schema.
    val authorityStart = schemeEnd + 3
    var authorityEnd = authorityStart
    while (authorityEnd < url.length &&
      url[authorityEnd] != '/' && url[authorityEnd] != '?' && url[authorityEnd] != '#'
    ) {
      authorityEnd++
    }
    if (authorityEnd == authorityStart) return url
    if (authorityEnd >= url.length || url[authorityEnd] != '/') return url

    // Path: dal primo '/' fino alla query string o al fragment.
    val pathStart = authorityEnd
    val pathEnd = url.indexOfAny(charArrayOf('?', '#'), pathStart).let { if (it < 0) url.length else it }

    // Primo segmento: tra il primo '/' del path e il '/' successivo (o la fine).
    val segmentEnd = url.indexOf('/', pathStart + 1).let { if (it < 0 || it > pathEnd) pathEnd else it }
    val segment = url.substring(pathStart + 1, segmentEnd)
    if (segment !in EMBED_ALIASES) return url

    return url.substring(0, pathStart + 1) + "v" + url.substring(segmentEnd)
  }

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    val scheme = uri.scheme
    val host = uri.host
    if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
      ""
    } else {
      val port = uri.port
      val default = (scheme.equals("http", true) && port == 80) ||
        (scheme.equals("https", true) && port == 443)
      if (port <= 0 || default) "$scheme://$host" else "$scheme://$host:$port"
    }
  } catch (e: Exception) {
    ""
  }

  companion object {
    private const val TAG = "VidHideExtractor"
    const val SERVER_NAME = "VidHide"

    /** Nomi dei segmenti che sono alias della pagina player `/v/`. */
    private val EMBED_ALIASES = setOf("f", "d", "s", "embed", "download", "file")
  }
}
