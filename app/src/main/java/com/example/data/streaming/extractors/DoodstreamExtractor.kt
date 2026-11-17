package com.example.data.streaming.extractors

import android.util.Log
import com.example.data.streaming.StreamSource
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estrattore per **Doodstream** e i suoi mirror (`dood.la`, `dood.li`,
 * `dood.so`, `dood.wf`, `doodstream.com`, `vide0.net`).
 *
 * Flusso (allineato al player pubblico):
 *  1. normalizzazione `/d/{code}` -> `/e/{code}`;
 *  2. `GET` della pagina embed con `Referer` del catalogo, User-Agent desktop e
 *     gli header browser; il [InMemoryCookieJar] condiviso conserva la sessione;
 *  3. dal markup si estraggono il path `/pass_md5/...` e l'eventuale `token`;
 *  4. `GET` del path `pass_md5` con `Range: bytes=0-` e `Referer` dell'embed:
 *     la risposta è il **prefisso** dell'URL del file;
 *  5. si compone l'URL finale con un suffisso casuale di 10 caratteri e i
 *     parametri `token`/`expiry`, come fa il player;
 *  6. la sorgente viene restituita con `Referer`, `Origin` e User-Agent iOS.
 *     Il cookie di sessione resta nel [InMemoryCookieJar] condiviso: il player
 *     usa lo stesso jar (domain-scoped) e lo invia solo all'host corretto.
 *
 * Il suffisso casuale è iniettabile nei test per rendere l'URL deterministico.
 * Se il path `pass_md5` non è presente (challenge/captcha o markup cambiato),
 * l'estrazione fallisce con un messaggio esplicito.
 */
class DoodstreamExtractor(
  /**
   * GET che restituisce la coppia (URL finale dopo redirect, corpo).
   * Iniettabile nei test per simulare embed e pass_md5 senza rete.
   */
  private val httpGet: suspend (
    url: String,
    referer: String?,
    headers: Map<String, String>
  ) -> Pair<String, String> = { url, referer, headers ->
    ExtractorHttp.getWithFinalUrl(
      url,
      referer,
      extraHeaders = headers,
      callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
    )
  },
  private val randomSuffix: () -> String = { randomHashTable() },
  private val now: () -> Long = System::currentTimeMillis
) : VideoExtractor {

  override suspend fun extract(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
    val normalizedEmbed = toEmbedUrl(embedUrl)
    val initialOrigin = originOf(normalizedEmbed)
      ?: throw IOException("URL Doodstream non valido: $embedUrl")

    Log.i(TAG, "GET embed $normalizedEmbed")
    val (finalUrl, html) = httpGet(
      normalizedEmbed,
      embedUrl,
      browserHeaders(initialOrigin)
    )
    if (html.isBlank()) throw IOException("Pagina embed Doodstream vuota: $embedUrl")

    val finalBase = originOf(finalUrl) ?: initialOrigin
    val md5Path = MD5_PATH_REGEX.find(html)?.value
      ?: throw IOException(
        "Path pass_md5 Doodstream assente (challenge, captcha o markup cambiato)"
      )
    val pageToken = TOKEN_REGEX.find(html)?.groupValues?.get(1)
    val md5Url = finalBase.trimEnd('/') + md5Path

    Log.i(TAG, "GET pass_md5 Doodstream")
    val (_, prefixBody) = httpGet(
      md5Url,
      finalUrl,
      mapOf(
        "User-Agent" to ExtractorHttp.USER_AGENT,
        "Accept" to "*/*",
        "Range" to "bytes=0-",
        "Referer" to finalUrl
      )
    )
    val videoPrefix = prefixBody.trim()
    if (videoPrefix.isEmpty()) {
      throw IOException("Risposta pass_md5 Doodstream vuota")
    }
    if (!videoPrefix.startsWith("http://") && !videoPrefix.startsWith("https://")) {
      throw IOException("Prefisso video Doodstream non valido")
    }

    val token = pageToken ?: md5Url.substringAfterLast('/')
    val suffix = randomSuffix()
    val playbackUrl = buildString {
      append(videoPrefix)
      append(suffix)
      append("?token=").append(token)
      append("&expiry=").append(now())
    }

    val playbackHeaders = linkedMapOf(
      "Referer" to finalUrl,
      "Origin" to finalBase,
      "User-Agent" to PLAYBACK_USER_AGENT,
      "Accept" to "*/*"
    )

    Log.i(TAG, "Sorgente Doodstream estratta: ${videoPrefix.take(160)}")
    listOf(
      StreamSource(
        streamUrl = playbackUrl,
        quality = "Auto",
        serverName = SERVER_NAME,
        headers = playbackHeaders,
        isProgressive = true
      )
    )
  }

  /** `/d/{code}` e `/e/{code}` sono la stessa pagina: il player usa `/e/`. */
  internal fun toEmbedUrl(url: String): String = url.replaceFirst(DOOD_FILE_PATH_REGEX, "$1/e/")

  /** Path `/pass_md5/...` presente nella pagina embed. */
  internal fun parseMd5Path(html: String): String? = MD5_PATH_REGEX.find(html)?.value

  /** Token del player, se dichiarato nell'HTML. */
  internal fun parseToken(html: String): String? = TOKEN_REGEX.find(html)?.groupValues?.get(1)

  private fun browserHeaders(origin: String): Map<String, String> = mapOf(
    "User-Agent" to ExtractorHttp.USER_AGENT,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Referer" to "$origin/"
  )

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
    private const val TAG = "DoodstreamExtractor"
    const val SERVER_NAME = "Doodstream"

    private const val PLAYBACK_USER_AGENT =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 " +
        "Mobile/15E148 Safari/604.1"

    private const val HASH_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private val DOOD_FILE_PATH_REGEX = Regex("""^(https?://[^/]+)/d/""", RegexOption.IGNORE_CASE)

    private val MD5_PATH_REGEX = Regex("""/pass_md5/[^'"\s<]+""", RegexOption.IGNORE_CASE)
    private val TOKEN_REGEX = Regex("""[?&]token=([A-Za-z0-9_-]+)""")

    private val secureRandom = SecureRandom()

    private fun randomHashTable(): String = buildString(10) {
      repeat(10) { append(HASH_ALPHABET[secureRandom.nextInt(HASH_ALPHABET.length)]) }
    }
  }
}
