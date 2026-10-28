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
    val (finalUrl, html) = ExtractorHttp.getWithFinalUrl(resolvedUrl, referer = initialReferer, callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS)
    if (finalUrl != resolvedUrl) Log.i(TAG, "redirect su $finalUrl")
    val referer = originOf(finalUrl) + "/"
    if (html.isBlank()) throw IOException("Pagina embed MixDrop vuota: $embedUrl")

    val normalized = html.replace("\\/", "/")
    val candidates = findMediaUrls(html, finalUrl)

    candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }?.let { hls ->
      Log.i(TAG, "Sorgente MixDrop estratta (HLS): $hls")
      return@withContext listOf(source(hls, referer))
    }
    candidates.firstOrNull { it.contains(".mp4", ignoreCase = true) }?.let { mp4 ->
      Log.i(TAG, "Sorgente MixDrop estratta (MP4): $mp4")
      return@withContext listOf(source(mp4, referer))
    }

    // Pagina "File not found"/"We can't find the video": il file è stato
    // rimosso. Non è una challenge: senza questo controllo si farebbe un POST
    // inutile e l'esito verrebbe registrato come reCAPTCHA, perdendo il vero
    // motivo del mirror non riproducibile.
    if (isFileMissingPage(normalized)) {
      throw IOException("File MixDrop non disponibile (rimosso o inesistente)")
    }

    // 4) endpoint del player (csrf + a=show): su mirror attivi serve il token reCAPTCHA v3
    val csrf = CSRF_REGEX.find(normalized)?.groupValues?.get(1)
    if (csrf != null) {
      Log.i(TAG, "Nessuna sorgente inline: provo l'endpoint del player con csrf")
      val response = ExtractorHttp.postForm(
        url = finalUrl,
        referer = referer,
        form = mapOf("csrf" to csrf, "server" to "", "file" to "", "a" to "show"),
        callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
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

  /**
   * Estrae tutti gli URL media dalla pagina embed, senza eseguire richieste.
   *
   * L'ordine è quello di scoperta: tag `video/source`, variabili `MDCore`
   * (entrambe le sintassi), script packed e infine gli URL assoluti presenti
   * nel markup. La deduplica avviene sull'URL assoluto.
   *
   * `internal` per rendere il parsing verificabile con HTML sintetico nei test
   * JVM, senza rete.
   */
  internal fun findMediaUrls(html: String, baseUrl: String): List<String> {
    val normalized = html.replace("\\/", "/")
    val candidates = LinkedHashSet<String>()

    collectCandidates(normalized, baseUrl, candidates)

    // Script packed (Dean Edwards): il player Mixdrop attuale nasconde qui
    // `MDCore.wurl`/`MDCore.vfile`, spesso con URL protocol-relative
    // (`//cdn.example/video.mp4?...`). Il solo URL_REGEX non basta: va
    // rianalizzato anche il codice decodificato con le stesse regole del markup.
    PACKED_REGEX.findAll(normalized).forEach { m ->
      unpackPackedJs(m.value)?.let { unpacked ->
        collectCandidates(unpacked, baseUrl, candidates)
      }
    }

    return candidates.toList()
  }

  private fun collectCandidates(
    source: String,
    baseUrl: String,
    candidates: MutableSet<String>
  ) {
    // 1) tag video/source
    SOURCE_REGEX.findAll(source).forEach { m ->
      toAbsolute(m.groupValues[1], baseUrl)?.let { candidates += it }
    }

    // 2) variabili MDCore (`MDCore.file = "..."` e `MDCore['file'] = "..."`).
    // Ogni regex espone la URL nel primo gruppo disponibile: l'indice del gruppo
    // è parte del contratto della regex e va verificato insieme ad essa.
    MDCORE_DOT_REGEX.findAll(source).forEach { m ->
      firstQuoted(m, 1, 2)?.let { toAbsolute(it, baseUrl) }?.let { candidates += it }
    }
    MDCORE_BRACKET_REGEX.findAll(source).forEach { m ->
      firstQuoted(m, 2, 3)?.let { toAbsolute(it, baseUrl) }?.let { candidates += it }
    }

    // 3) URL media assoluti o protocol-relative presenti nel markup/script.
    URL_REGEX.findAll(source).forEach { m ->
      toAbsolute(m.groupValues[1], baseUrl)?.let { candidates += it }
    }
  }

  /**
   * True per la pagina di errore di MixDrop quando il file non esiste più.
   * I marker sono specifici della pagina ("File not found", "We can't find the
   * video you are looking for") per non confondere un errore di parsing con un
   * file rimosso.
   */
  internal fun isFileMissingPage(html: String): Boolean {
    val lower = html.lowercase()
    return FILE_MISSING_MARKERS.any { lower.contains(it) }
  }

  /** Valore del primo gruppo di cattura non vuoto fra gli indici indicati. */
  private fun firstQuoted(match: MatchResult, vararg indexes: Int): String? =
    indexes.asSequence()
      .mapNotNull { match.groupValues.getOrNull(it) }
      .firstOrNull { it.isNotEmpty() }

  private fun source(url: String, referer: String) = StreamSource(
    streamUrl = url,
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
    // Il dizionario è il letterale prima di `.split(...)`, NON il payload:
    // confonderli rende sempre vuota la mappa e nessuno script viene decodificato.
    val dictionary = m.groupValues[4]
    val delimiter = m.groupValues[5]
    if (radix < 2 || count <= 0 || delimiter.isEmpty()) return null

    val words = dictionary.split(delimiter)
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
    // URL nel gruppo 1 (virgolette doppie) oppure gruppo 2 (virgolette singole).
    private val MDCORE_DOT_REGEX = Regex(
      """MDCore\s*\.\s*(?:file|vfile|furl|geturl|wurl|url)\s*=\s*(?:"([^"]+)"|'([^']+)')""",
      RegexOption.IGNORE_CASE
    )
    // Gruppo 1 = chiave; URL nel gruppo 2 (doppie) oppure 3 (singole).
    private val MDCORE_BRACKET_REGEX = Regex(
      """MDCore\s*\[\s*['"](file|vfile|furl|geturl|wurl)['"]\s*\]\s*=\s*(?:"([^"]+)"|'([^']+)')""",
      RegexOption.IGNORE_CASE
    )
    private val CSRF_REGEX = Regex("""<meta\s+name="csrf"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val JSON_URL_REGEX = Regex(""""url"\s*:\s*"([^"]+)"""")
    private val JSON_MSG_REGEX = Regex(""""msg"\s*:\s*"([^"]+)"""")
    // Il blocco termina con `...split('|'),0,{}))`: fermarsi al primo `))`
    // tronca lo stub (`String))`) e perde il payload.
    private val PACKED_REGEX = Regex(
      """eval\(function\(p,a,c,k,e,d\)[\s\S]*?\}\('[\s\S]*?',\s*\d+,\s*\d+,\s*'[\s\S]*?'\.split\(\s*['"][^'"]+['"]\s*\),\s*0,\s*\{\}\)\)""",
      RegexOption.IGNORE_CASE
    )
    // Gruppi: 1 payload, 2 radice, 3 conteggio, 4 dizionario, 5 delimitatore.
    private val PACKED_ARGS_REGEX = Regex(
      """\}\('([\s\S]*?)',\s*(\d+),\s*(\d+),\s*'([\s\S]*?)'\.split\(\s*['"]([^'"]+)['"]\s*\)"""
    )
    private val TOKEN_REGEX = Regex("""[0-9a-zA-Z]+""")
    // Accetta anche URL protocol-relative (`//cdn/...`): sono la forma usata
    // da `MDCore.wurl` negli script packed dei mirror attivi.
    private val URL_REGEX = Regex(
      """((?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""",
      RegexOption.IGNORE_CASE
    )

    private val FILE_MISSING_MARKERS = listOf(
      "file not found",
      "we can't find the video",
      "we can’t find the video",
      "video you are looking for",
      "file has been removed",
      "file was removed",
      "file is no longer available",
      "this file was deleted"
    )
  }
}
