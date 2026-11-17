package com.example.data.streaming.extractors

import android.util.Log
import com.example.data.streaming.StreamSource
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeBase64

/**
 * Estrattore per **Voe** (`voe.sx` e i domini alias ruotati dall'hoster).
 *
 * Flusso (allineato al player pubblico Voe):
 *  1. `GET {embedUrl}` con `Referer` dell'embed e `X-Requested-With`;
 *  2. la pagina contiene un `<script type="application/json">` con un payload
 *     offuscato; la decodifica è **puramente testuale** (rot13, sostituzione di
 *     pattern, rimozione underscore, Base64, shift dei caratteri, reverse,
 *     Base64) e non esegue JavaScript;
 *  3. dal JSON decodificato si legge la chiave `source` (manifest HLS o MP4);
 *  4. la sorgente viene restituita con `Referer`/`Origin`/`User-Agent`.
 *
 * I sottotitoli `captions` del player non vengono esposti in questa versione:
 * la riproduzione della sorgente non ne dipende. Se il payload non è
 * decodificabile (challenge o markup cambiato) l'estrazione fallisce con un
 * messaggio esplicito.
 */
class VoeExtractor(
  private val httpGet: suspend (url: String, referer: String?) -> String =
    { url, referer ->
      ExtractorHttp.get(
        url,
        referer,
        extraHeaders = mapOf(
          "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          "X-Requested-With" to "XMLHttpRequest"
        ),
        callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
      )
    }
) : VideoExtractor {

  override suspend fun extract(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
    val origin = originOf(embedUrl)
      ?: throw IOException("URL Voe non valido: $embedUrl")

    Log.i(TAG, "GET embed $embedUrl")
    val html = httpGet(embedUrl, embedUrl)
    if (html.isBlank()) throw IOException("Pagina embed Voe vuota: $embedUrl")

    val encoded = findEncodedPayload(html)
      ?: throw IOException(
        "Payload Voe non trovato (challenge, captcha o markup cambiato)"
      )
    val decoded = try {
      decryptPayload(encoded)
    } catch (e: Exception) {
      throw IOException("Decodifica payload Voe fallita (challenge o formato cambiato)", e)
    }

    val sourceUrl = SOURCE_REGEX.find(decoded)?.groupValues?.get(1)
      ?.replace("\\/", "/")
      ?.trim()
      ?.takeIf { it.startsWith("http") }
      ?: throw IOException("Chiave 'source' assente nel payload Voe decodificato")

    Log.i(TAG, "Sorgente Voe estratta: ${sourceUrl.substringBefore('?').take(160)}")

    listOf(
      StreamSource(
        streamUrl = sourceUrl,
        quality = "Auto",
        serverName = SERVER_NAME,
        headers = mapOf(
          "Referer" to embedUrl,
          "Origin" to origin,
          "User-Agent" to ExtractorHttp.USER_AGENT
        ),
        isProgressive = !sourceUrl.substringBefore('?').contains(".m3u8", ignoreCase = true)
      )
    )
  }

  /** Payload JSON offuscato contenuto nel tag `<script type="application/json">`. */
  internal fun findEncodedPayload(html: String): String? =
    ENCODED_SCRIPT_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

  /**
   * Decodifica il payload Voe.
   *
   * La sequenza è quella del player pubblico; è volutamente **senza
   * esecuzione di JavaScript** e senza dipendenze Android-specifiche (Okio
   * Base64), così è verificabile anche nei test JVM.
   */
  internal fun decryptPayload(encoded: String): String {
    val rot13 = encoded.map { c ->
      when (c) {
        in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
        in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
        else -> c
      }
    }.joinToString("")

    val replaced = PATTERNS.fold(rot13) { value, pattern -> value.replace(pattern, "_") }
    val compact = replaced.replace("_", "")

    val firstPass = compact.decodeBase64()?.utf8()
      ?: throw IOException("Base64 Voe (primo passaggio) non valido")
    val shifted = firstPass.map { (it.code - 3).toChar() }.joinToString("")
    val reversed = shifted.reversed()
    return reversed.decodeBase64()?.utf8()
      ?: throw IOException("Base64 Voe (secondo passaggio) non valido")
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

  companion object {
    private const val TAG = "VoeExtractor"
    const val SERVER_NAME = "Voe"

    private val ENCODED_SCRIPT_REGEX = Regex(
      """<script\s+type\s*=\s*["']application/json["'][^>]*>(.*?)</script>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val SOURCE_REGEX = Regex(
      """"source"\s*:\s*"([^"]+)"""",
      RegexOption.IGNORE_CASE
    )

    private val PATTERNS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
  }
}
