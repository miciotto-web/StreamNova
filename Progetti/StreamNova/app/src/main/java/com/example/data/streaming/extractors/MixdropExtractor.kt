package com.example.data.streaming.extractors

import android.util.Log
import com.example.data.streaming.StreamSource
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estrattore per gli hoster **MixDrop**.
 *
 * Domini riscontrati: `mixdrop.co` / `mixdrop.to` / `mixdrop.ch` (declarazioni
 * storiche) più i domini backend attivi `mxdrop.top` e `m1xdrop.net` (302 → mxdrop.top).
 *
 * Strategie di estrazione, in ordine:
 *  1. tag `<video src>` / `<source src>` (file MP4 diretti);
 *  2. variabili inline del player `MDCore.file`, `MDCore.vfile`, `MDCore.geturl`,
 *     `MDCore.wurl` (forme `MDCore.x = "..."` e `MDCore['x'] = "..."`);
 *  3. decodifica di eventuali script "packed" `eval(function(p,a,c,k,e,d){...})`
 *     (formato Dean Edwards usato dagli embed più vecchi) e ricerca di HLS/MP4
 *     nel codice decodificato;
 *  4. ultima risorsa: `POST` alla stessa pagina embed con `csrf` (meta tag) e
 *     `a=show` come fa il player. Sui mirror attivi questo endpoint richiede un
 *     **token reCAPTCHA v3**: se il server risponde `Invalid token` viene sollevata
 *     un'IOException esplicita (l'hoster è protetto, non è un errore di parsing).
 */
class MixdropExtractor : VideoExtractor {

  override suspend fun extract(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
    // Gli shortener restituiscono la pagina file `/f/{code}` (404 sui mirror attivi):
    // la pagina effettiva del player è l'embed `/e/{code}`.
    val resolvedUrl = if (embedUrl.contains("/f/")) embedUrl.replace("/f/", "/e/") else embedUrl
    if (resolvedUrl != embedUrl) Log.i(TAG, "normalizzo file page -> embed: $resolvedUrl")

    val initialReferer = originOf(resolvedUrl) + "/"
    Log.i(TAG, "GET embed $resolvedUrl (referer=$initialReferer)")
    // L'URL finale è essenziale: i mirror rispondono 302 e il POST dell'endpoint
    // del player deve partire dalla pagina definitiva (altrimenti diventa GET).
    val (finalUrl, html) = ExtractorHttp.getWithFinalUrl(resolvedUrl, referer = initialReferer)
    if (finalUrl != resolvedUrl) Log.i(TAG, "redirect su $finalUrl")
    val referer = originOf(finalUrl) + "/"
    if (html.isBlank()) throw IOException("Pagina embed MixDrop vuota: $embedUrl")

    val normalized = html.replace("\\/", "/")
    val candidates = LinkedHashSet<String>()

    // 1) tag video/source
    SOURCE_REGEX.findAll(normalized).forEach { m ->
      toAbsolute(m.groupValues[1], embedUrl)?.let { candidates += it }
    }

    // 2) variabili MDCore inline
    MDCORE_DOT_REGEX.findAll(normalized).forEach { m -> toAbsolute(m.groupValues[2], embedUrl)?.let { candidates += it } }
    MDCORE_BRACKET_REGEX.findAll(normalized).forEach { m -> toAbsolute(m.groupValues[2], embedUrl)?.let { candidates += it } }

    // 3) script packed (Dean Edwards) -> decodifica + ricerca sorgenti
    PACKED_REGEX.findAll(normalized).forEach { m ->
      unpackPackedJs(m.value)?.let { unpacked ->
        URL_REGEX.findAll(unpacked).forEach { candidates += it.groupValues[1] }
      }
    }

    // 3b) ricerca diretta di URL media nella pagina
    URL_REGEX.findAll(normalized).forEach { candidates += it.groupValues[1] }

    candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }?.let { hls ->
      Log.i(TAG, "Sorgente MixDrop estratta (HLS): $hls")
      return@withContext listOf(source(hls, referer))
    }
    candidates.firstOrNull { it.contains(".mp4", ignoreCase = true) }?.let { mp4 ->
      Log.i(TAG, "Sorgente MixDrop estratta (MP4): $mp4")
      return@withContext listOf(source(mp4, referer))
    }

    // 4) endpoint del player (csrf + a=show): su mirror attivi serve il token reCAPTCHA v3
    val csrf = CSRF_REGEX.find(normalized)?.groupValues?.get(1)
    if (csrf != null) {
      Log.i(TAG, "Nessuna sorgente inline: provo l'endpoint del player con csrf")
      val response = ExtractorHttp.postForm(
        url = finalUrl,
        referer = referer,
        form = mapOf("csrf" to csrf, "server" to "", "file" to "", "a" to "show")
      )
      // Parsing con regex (funziona anche nei test JVM dove org.json è stub)
      val directUrl = JSON_URL_REGEX.find(response)?.groupValues?.get(1).orEmpty()
        .replace("\\/", "/")
      if (directUrl.startsWith("http")) {
        Log.i(TAG, "Sorgente MixDrop estratta (endpoint): $directUrl")
        return@withContext listOf(source(directUrl, referer))
      }
      val msg = JSON_MSG_REGEX.find(response)?.groupValues?.get(1).orEmpty()
      if (msg.contains("token", ignoreCase = true)) {
        throw IOException("MixDrop protetto da reCAPTCHA v3: il file richiede un token di verifica")
      }
      if (msg.isNotBlank()) Log.w(TAG, "endpoint MixDrop: $msg")
    }

    throw IOException("Nessuna sorgente MixDrop trovata nell'embed (hoster protetto o file rimosso)")
  }

  private fun source(url: String, referer: String) = StreamSource(
    url = url,
    quality = "Auto",
    serverName = SERVER_NAME,
    headers = mapOf("Referer" to referer, "User-Agent" to ExtractorHttp.USER_AGENT)
  )

  private fun toAbsolute(raw: String, baseUrl: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return when {
      value.startsWith("http") -> value
      value.startsWith("//") -> "https:$value"
      value.startsWith("/") -> originOf(baseUrl) + value
      else -> null
    }
  }

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}"
  } catch (e: Exception) {
    "https://mxdrop.top"
  }

  /**
   * Decodificatore Dean Edwards (formato
   * `eval(function(p,a,c,k,e,d){...}('payload',62,123,'w1|w2'.split('|'),0,{}))`).
   * Restituisce il codice decodificato oppure null se il formato non è riconosciuto.
   */
  private fun unpackPackedJs(packed: String): String? {
    val m = PACKED_ARGS_REGEX.find(packed) ?: return null
    val payload = m.groupValues[1]
      .replace("\\'", "'")
      .replace("\\\"", "\"")
      .replace("\\\\", "\\")
    val radix = m.groupValues[2].toIntOrNull() ?: return null
    val count = m.groupValues[3].toIntOrNull() ?: return null
    val delimiter = m.groupValues[4]
    if (radix < 2 || count <= 0 || delimiter.isEmpty()) return null

    val words = payload.split(delimiter)
    if (words.size < 2) return null

    // Mappa con e senza padding a 3 cifre: le due varianti del packer sono in uso
    val map = HashMap<String, String>()
    for (i in 0 until count) {
      val raw = words.getOrNull(i) ?: continue
      val key = i.toString(radix)
      val value = if (raw.isEmpty()) key else raw
      map[key] = value
      if (key.length < 3) map["0".repeat(3 - key.length) + key] = value
    }

    return TOKEN_REGEX.replace(payload) { hit -> map[hit.value] ?: hit.value }
  }

  companion object {
    private const val TAG = "MixdropExtractor"
    const val SERVER_NAME = "Mixdrop"

    private val SOURCE_REGEX = Regex("""<(?:video|source)[^>]*\ssrc\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val MDCORE_DOT_REGEX = Regex("""MDCore\s*\.\s*(?:file|vfile|geturl|wurl|url)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val MDCORE_BRACKET_REGEX = Regex("""MDCore\s*\[\s*['"](file|vfile|geturl|wurl)['"]\s*\]\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CSRF_REGEX = Regex("""<meta\s+name="csrf"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val JSON_URL_REGEX = Regex(""""url"\s*:\s*"([^"]+)"""")
    private val JSON_MSG_REGEX = Regex(""""msg"\s*:\s*"([^"]+)"""")
    private val PACKED_REGEX = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]{0,20000}?\)\)""", RegexOption.IGNORE_CASE)
    private val PACKED_ARGS_REGEX = Regex("""\}\('([\s\S]*?)',\s*(\d+),\s*(\d+),\s*'[\s\S]*?'\.split\(\s*['"]([^'"]+)['"]\s*\)""")
    private val TOKEN_REGEX = Regex("""[0-9a-zA-Z]+""")
    private val URL_REGEX = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
  }
}
