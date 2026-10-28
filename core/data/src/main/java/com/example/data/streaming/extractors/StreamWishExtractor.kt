package com.example.data.streaming.extractors

import android.util.Log
import com.example.data.streaming.StreamSource
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estrattore per gli hoster della famiglia **StreamWish** (`streamwish.to`,
 * `streamwish.com`, `streamwish.sx`, `streamwish.xyz`).
 *
 * Flusso (allineato agli embed pubblici della famiglia, pagina player
 * JWPlayer-like come VidHide):
 *  1. `GET` della pagina embed con `Referer`/`Origin` dell'hoster e User-Agent
 *     desktop (i redirect, es. dalla pagina download `/d/{code}` all'embed
 *     `/e/{code}`, vengono seguiti da [ExtractorHttp]);
 *  2. ricerca della sorgente negli script del player: `sources: [{file: "..."}]`,
 *     chiavi composte (`video_src`, `hls_url`, ...) oppure blocco packed
 *     `eval(function(p,a,c,k,e,d){...})` (decodificato da [JsPacked], senza
 *     eseguire JavaScript);
 *  3. restituzione dell'URL HLS/MP4 con gli header necessari alla CDN.
 *
 * Nessuna normalizzazione di percorso (a differenza di [VidHideExtractor]):
 * `/e/` è il percorso embed canonico e la famiglia non usa alias pinned.
 *
 * Limite noto: se l'hoster serve una pagina di challenge JavaScript o richiede
 * un captcha, l'estrazione fallisce con un errore esplicito (il messaggio
 * contiene "challenge", cosi' la diagnostica classifica il tentativo come
 * BLOCKED_BY_CHALLENGE). Non viene implementato alcun bypass.
 */
class StreamWishExtractor : VideoExtractor {

  override suspend fun extract(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
    val referer = originOf(embedUrl) + "/"
    Log.i(TAG, "GET embed $embedUrl")

    val (finalUrl, html) = ExtractorHttp.getWithFinalUrl(embedUrl, referer = referer)
    if (html.isBlank()) throw IOException("Pagina embed StreamWish vuota: $embedUrl")

    val media = PlayerSourceParser.findMediaUrls(html, finalUrl).firstOrNull()
      ?: throw IOException(
        "Nessuna sorgente StreamWish nella pagina embed (player protetto, challenge o markup cambiato)"
      )
    Log.i(TAG, "Sorgente StreamWish estratta: $media")

    val origin = originOf(finalUrl).ifBlank { originOf(embedUrl) }
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
    private const val TAG = "StreamWishExtractor"
    const val SERVER_NAME = "StreamWish"
  }
}
