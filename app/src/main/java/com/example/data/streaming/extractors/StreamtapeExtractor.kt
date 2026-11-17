package com.example.data.streaming.extractors

import android.util.Log
import com.example.data.streaming.StreamSource
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Estrattore per **Streamtape** (`streamtape.com`, `streamtape.net`,
 * `streamtape.to`, `streamtape.site`).
 *
 * Flusso verificato sul player pubblico:
 *  1. `GET {embedUrl}` (pagina `/e/{code}` o `/v/{code}`) con User-Agent
 *     desktop: il markup contiene l'assegnazione JavaScript del link `botlink`;
 *  2. il link viene ricomposto **senza eseguire JavaScript**: si concatenano la
 *     stringa base e la parte ottenuta con `.substring(N)`, poi si leggono i
 *     parametri `id`, `expires`, `ip` e `token`;
 *  3. si chiama `{origin}/get_video?id=...&expires=...&ip=...&token=...&stream=1`
 *     con una richiesta `Range: bytes=0-0`: la risposta è un redirect verso la
 *     CDN, quindi si usa l'**URL finale** come sorgente senza scaricare il file;
 *  4. la sorgente viene restituita con `Referer`/`User-Agent` coerenti.
 *
 * Limite noto: se Streamtape serve una challenge JavaScript o un captcha,
 * l'estrazione fallisce con un errore esplicito (il messaggio contiene
 * "challenge"). Non viene implementato alcun bypass e non si scarica il video.
 */
class StreamtapeExtractor(
  private val httpGet: suspend (url: String, referer: String?) -> String =
    { url, referer ->
      ExtractorHttp.get(
        url,
        referer,
        callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
      )
    },
  /**
   * Risolve l'URL finale del flusso **senza leggere il corpo**: apre la call,
   * legge `response.request.url` e chiude la risposta. Iniettabile nei test.
   */
  private val resolveFinalUrl: suspend (url: String, referer: String) -> String =
    { url, referer ->
      val request = Request.Builder()
        .url(url)
        .header("User-Agent", ExtractorHttp.USER_AGENT)
        .header("Referer", referer)
        .header("Accept", "*/*")
        .header("Range", "bytes=0-0")
        .get()
        .build()
      ExtractorHttp.client.newCall(request).awaitResponse().use { response ->
        if (!response.isSuccessful) {
          throw IOException("HTTP ${response.code} su Streamtape get_video")
        }
        response.request.url.toString()
      }
    }
) : VideoExtractor {

  override suspend fun extract(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
    val origin = originOf(embedUrl)
      ?: throw IOException("URL Streamtape non valido: $embedUrl")
    val referer = "$origin/"

    Log.i(TAG, "GET embed $embedUrl")
    val html = httpGet(embedUrl, referer)
    if (html.isBlank()) throw IOException("Pagina embed Streamtape vuota: $embedUrl")

    val videoUrl = findVideoUrl(html, origin)
      ?: throw IOException(
        "Nessun link get_video Streamtape nella pagina embed (challenge, captcha o markup cambiato)"
      )
    Log.i(TAG, "Link get_video Streamtape ricostruito")

    val finalUrl = try {
      resolveFinalUrl(videoUrl, referer)
    } catch (ex: CancellationException) {
      throw ex
    } catch (e: IOException) {
      throw e
    } catch (e: Exception) {
      throw IOException("Streamtape: risoluzione URL finale fallita: ${e.message}", e)
    }
    Log.i(TAG, "Sorgente Streamtape estratta: ${sanitizeForLog(finalUrl)}")

    listOf(
      StreamSource(
        streamUrl = finalUrl,
        quality = "Auto",
        serverName = SERVER_NAME,
        headers = mapOf(
          "Referer" to referer,
          "User-Agent" to ExtractorHttp.USER_AGENT
        ),
        // /get_video restituisce un file progressivo (MP4); se il CDN
        // risponde con un manifest HLS il player lo riconosce comunque.
        isProgressive = !finalUrl.substringBefore('?').contains(".m3u8", ignoreCase = true)
      )
    )
  }

  /**
   * Ricostruisce l'URL `get_video` dal markup Streamtape.
   *
   * Sono gestite entrambe le varianti osservate:
   *  - `base + ('params').substring(n)` (forma del player attuale);
   *  - link completo già presente in chiaro.
   *
   * `internal` per i test JVM con HTML sintetico, senza rete.
   */
  internal fun findVideoUrl(html: String, origin: String): String? {
    val normalized = html.replace("\\/", "/")

    BOTLINK_REGEX.find(normalized)?.let { match ->
      val base = match.groupValues[1]
      val suffix = match.groupValues[2]
      val index = match.groupValues[3].toIntOrNull() ?: 0
      val cleanSuffix = suffix.substring(index.coerceIn(0, suffix.length))
      parseVideoUrl(base, cleanSuffix, origin)?.let { return it }
    }

    // Fallback: link completo già presente (o in un blocco JSON).
    GET_VIDEO_REGEX.find(normalized)?.let { match ->
      parseVideoUrl(match.value, "", origin)?.let { return it }
    }
    return null
  }

  /**
   * Ricostruisce l'URL `get_video` da [base] e [suffix].
   *
   * Il player pubblico è stato osservato in due forme:
   *  - base completa (con query) + suffix che aggiunge `&stream=1`;
   *  - base troncata a `?` + suffix che contiene la query (dopo `.substring(N)`).
   *
   * I parametri vengono letti prima dalla base: se contiene già `id`, `token`
   * ed `expires` sono quelli autorevoli e il suffix non può corromperli.
   * Altrimenti si usa la concatenazione base+suffix.
   */
  private fun parseVideoUrl(base: String, suffix: String, origin: String): String? {
    val combined = (base + suffix).trim().replace("&amp;", "&")
    if (!combined.contains("get_video", ignoreCase = true)) return null

    // Se il link contiene già l'authority usa quella (la CDN può stare su un
    // host diverso dall'embed); altrimenti resta l'origin della pagina.
    val resolvedOrigin = when {
      combined.startsWith("http://") || combined.startsWith("https://") -> originOf(combined)
      combined.startsWith("//") -> originOf("https:$combined")
      else -> null
    } ?: origin

    val baseParams = queryParams(base)
    val params = if (hasVideoParams(baseParams)) baseParams else queryParams(combined)

    val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
    val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
    val expires = params["expires"]?.takeIf { it.isNotBlank() } ?: return null
    val ip = params["ip"]?.takeIf { it.isNotBlank() }

    return buildString {
      append(resolvedOrigin)
      append("/get_video?id=").append(id)
      append("&expires=").append(expires)
      if (ip != null) append("&ip=").append(ip)
      append("&token=").append(token)
      append("&stream=1")
    }
  }

  private fun hasVideoParams(params: Map<String, String>): Boolean =
    !params["id"].isNullOrBlank() &&
      !params["token"].isNullOrBlank() &&
      !params["expires"].isNullOrBlank()

  /** Query string di un URL (o di un frammento `id=...&token=...`). */
  private fun queryParams(raw: String): Map<String, String> {
    val query = if ('?' in raw) raw.substringAfter('?') else raw
    return query.split('&')
      .mapNotNull { part ->
        val key = part.substringBefore('=', "").trim()
        if (key.isBlank() || !part.contains('=')) return@mapNotNull null
        val rawValue = part.substringAfter('=', "")
        key to runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
      }
      .toMap()
  }

  private fun originOf(url: String): String? = try {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
      null
    } else {
      val port = if (uri.port > 0) ":${uri.port}" else ""
      "$scheme://$host$port"
    }
  } catch (e: Exception) {
    null
  }

  /** Evita di scrivere token firmati nei log. */
  private fun sanitizeForLog(url: String): String =
    url.substringBefore('?').take(160)

  companion object {
    private const val TAG = "StreamtapeExtractor"
    const val SERVER_NAME = "Streamtape"

    /**
     * `document.getElementById('botlink').innerHTML = 'BASE' + ('SUFFIX').substring(N)`
     *
     * Il gruppo 2 può contenere apici escapati; il player attuale non li usa,
     * ma la regex resta tollerante.
     */
    private val BOTLINK_REGEX = Regex(
      """document\s*\.\s*getElementById\s*\(\s*['"]botlink['"]\s*\)\s*\.\s*innerHTML\s*=\s*['"]([^'"]*)['"]\s*\+\s*\(\s*['"]([^'"]*)['"]\s*\)\s*\.\s*substring\s*\(\s*(\d+)\s*\)""",
      RegexOption.IGNORE_CASE
    )

    private val GET_VIDEO_REGEX = Regex(
      """(?:https?:)?//[^\s"'<>\\]+/get_video\?[^\s"'<>\\]+""",
      RegexOption.IGNORE_CASE
    )
  }
}
