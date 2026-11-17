package com.example.data.streaming.extractors

import android.util.Log
import com.example.data.streaming.StreamSource
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estrattore per **Uqload** (`uqload.cx`, `uqload.is`, `uqload.vc`).
 *
 * Flusso verificato su un embed reale di GuardaPlay/GuardaSerie:
 *  1. l'URL del player è `/e/{code}` (alcuni cataloghi usano `/embed-{code}.html`);
 *  2. per `/e/{code}` si esegue `POST {origin}/dl` con
 *     `op=embed&file_code={code}&auto=1&referer={origin}` (la pagina restituita
 *     contiene il player e il blocco JavaScript packed);
 *  3. il blocco `eval(function(p,a,c,k,e,d){...})` viene decodificato da
 *     [JsPacked] **senza eseguire JavaScript**; al suo interno la chiave
 *     `file:` contiene l'URL HLS/MP4;
 *  4. la sorgente viene restituita con `Referer`/`User-Agent` coerenti con
 *     l'hoster (la CDN di Uqload rifiuta richieste senza Referer).
 *
 * Limite noto: se Uqload serve una challenge JavaScript/anti-bot o un captcha,
 * l'estrazione fallisce con un errore esplicito (il messaggio contiene
 * "challenge"), così la diagnostica la classifica come
 * `BLOCKED_BY_CHALLENGE`. Non viene implementato alcun bypass.
 */
class UqloadExtractor(
  private val httpGet: suspend (url: String, referer: String?, headers: Map<String, String>) -> String =
    { url, referer, headers ->
      ExtractorHttp.get(
        url,
        referer,
        extraHeaders = headers,
        callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
      )
    },
  private val httpPost: suspend (
    url: String,
    referer: String?,
    form: Map<String, String>,
    headers: Map<String, String>
  ) -> String = { url, referer, form, headers ->
    ExtractorHttp.postForm(
      url,
      referer,
      form,
      extraHeaders = headers,
      callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
    )
  }
) : VideoExtractor {

  override suspend fun extract(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
    val origin = originOf(embedUrl)
      ?: throw IOException("URL Uqload non valido: $embedUrl")
    val referer = "$origin/"
    val clean = embedUrl.substringBefore('?').substringBefore('#').trimEnd('/')

    Log.i(TAG, "GET embed $clean")
    val html = if (clean.contains("/embed-", ignoreCase = true)) {
      httpGet(embedUrl, referer, EMPTY_HEADERS)
    } else {
      val code = fileCode(clean)
        ?: throw IOException("Codice file Uqload assente in $embedUrl")
      // Il player reale è servito da /dl: un GET diretto su /e/{code} può
      // restituire solo lo stub anti-bot, senza il blocco packed.
      httpPost(
        "$origin/dl",
        referer,
        mapOf(
          "op" to "embed",
          "file_code" to code,
          "auto" to "1",
          "referer" to referer
        ),
        EMPTY_HEADERS
      )
    }

    if (html.isBlank()) throw IOException("Pagina embed Uqload vuota: $embedUrl")

    val sourceUrl = findSourceUrl(html)
      ?: throw IOException(
        "Nessuna sorgente Uqload nella pagina embed (challenge, captcha o markup cambiato)"
      )
    Log.i(TAG, "Sorgente Uqload estratta: ${sourceUrl.substringBefore('?').take(160)}")

    val mediaOrigin = originOf(sourceUrl) ?: origin
    listOf(
      StreamSource(
        streamUrl = sourceUrl,
        quality = "Auto",
        serverName = SERVER_NAME,
        headers = mapOf(
          "Referer" to "$mediaOrigin/",
          "Origin" to mediaOrigin,
          "User-Agent" to ExtractorHttp.USER_AGENT
        ),
        isProgressive = !isHls(sourceUrl)
      )
    )
  }

  /**
   * Estrae l'URL media dal markup/script Uqload.
   *
   * L'ordine è deliberato: prima i blocchi packed decodificati (forma normale
   * del player), poi il markup normalizzato come fallback per i deployment più
   * vecchi che espongono già `file:` in chiaro.
   *
   * `internal` per rendere il parsing verificabile con HTML sintetico nei test
   * JVM, senza rete.
   */
  internal fun findSourceUrl(html: String): String? {
    val normalized = html.replace("\\/", "/")

    JsPacked.unpackAll(normalized).forEach { unpacked ->
      FILE_REGEX.find(unpacked)?.groupValues?.get(1)?.let { raw ->
        normalizeMediaUrl(raw)?.let { return it }
      }
    }

    return FILE_REGEX.find(normalized)?.groupValues?.get(1)?.let { normalizeMediaUrl(it) }
  }

  /** Codice del file da `/e/{code}` oppure da `/embed-{code}.html`. */
  internal fun fileCode(url: String): String? {
    val uri = try {
      URI(url)
    } catch (e: Exception) {
      return null
    }
    val scheme = uri.scheme?.lowercase()
    if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) return null
    val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    val embed = segments.firstOrNull { it.startsWith("embed-", ignoreCase = true) }
    if (embed != null) {
      return embed.removePrefix("embed-").substringBefore('.').takeIf { it.isNotBlank() }
    }
    val eIndex = segments.indexOfFirst { it == "e" }
    if (eIndex >= 0) {
      return segments.getOrNull(eIndex + 1)?.substringBefore('.')?.takeIf { it.isNotBlank() }
    }
    return segments.lastOrNull()?.substringBefore('.')?.takeIf { it.isNotBlank() }
  }

  private fun normalizeMediaUrl(raw: String): String? {
    val value = raw.trim().replace("\\/", "/")
    return when {
      value.startsWith("http://") || value.startsWith("https://") -> value
      value.startsWith("//") -> "https:$value"
      else -> null
    }
  }

  private fun isHls(url: String): Boolean =
    url.substringBefore('?').contains(".m3u8", ignoreCase = true)

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

  companion object {
    private const val TAG = "UqloadExtractor"
    const val SERVER_NAME = "Uqload"

    private val FILE_REGEX = Regex(
      """(?<![A-Za-z0-9_])file\s*:\s*["']([^"']+)["']""",
      RegexOption.IGNORE_CASE
    )
    private val EMPTY_HEADERS = emptyMap<String, String>()
  }
}
