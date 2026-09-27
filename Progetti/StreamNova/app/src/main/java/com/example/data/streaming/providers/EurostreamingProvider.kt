package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.ExtractorHttp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provider del catalogo italiano **Eurostreaming** (`eurostream.cfd` -> redirect
 * sul mirror attivo `eurostream.mom`), particolarmente forte sulle Serie TV.
 *
 * Flusso (ogni passaggio è loggato su Logcat con tag `EurostreamingProvider`):
 *  1. **Ricerca**: `GET {base}/?s={titolo} {anno}` e scelta del risultato migliore.
 *  2. **Serie TV**: nella pagina serie gli episodi sono elocati dentro gli spoiler
 *     per stagione con marker `1×01`, `2×05`... (nell'HTML `1&#215;01`). Si isola
 *     il segmento dell'episodio richiesto (`season`/`episode`) e se ne estraggono
 *     i link hoster (`MaxStream`, `DL`, eventuali diretti).
 *  3. **Film**: si raccolgono tutti i link hoster dell'articolo.
 *  4. **Risoluzione + delega agli estrattori** (MaxStream/Mixdrop) tramite
 *     [HosterResolver]: gli shortener `uprot.net` sono protetti da captcha e
 *     vengono saltati con log esplicito.
 */
class EurostreamingProvider : StreamProvider {

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    val query = HosterResolver.buildQuery(title, year)
    if (query.isBlank()) throw IOException("Titolo non disponibile per la ricerca Eurostreaming (tmdbId=$tmdbId)")

    val failures = mutableListOf<String>()
    // I mirror redirigono sullo stesso sito: l'articolo si processa una volta.
    val attemptedArticles = HashSet<String>()
    for (base in BASE_URLS) {
      try {
        val sources = resolveOnBase(base, query, title, isTv, season, episode, attemptedArticles)
        if (sources.isNotEmpty()) {
          Log.i(TAG, "sorgenti Eurostreaming su $base: ${sources.size}")
          return@withContext sources
        }
        failures += "$base -> nessuna sorgente"
      } catch (e: Exception) {
        Log.w(TAG, "fallback mirror $base: ${e.message}")
        failures += "$base -> ${e.message}"
      }
    }
    throw IOException("Eurostreaming non risolvibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnBase(
    base: String,
    query: String,
    title: String?,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    attemptedArticles: MutableSet<String>
  ): List<StreamSource> {
    // --- 1) ricerca -------------------------------------------------------
    val searchUrl = "$base/?s=${HosterResolver.encode(query)}"
    Log.i(TAG, "GET RICERCA $searchUrl")
    val searchHtml = ExtractorHttp.get(searchUrl, referer = "$base/")
    val articleUrl = pickArticle(searchHtml, title, base)
      ?: throw IOException("nessun risultato per '$query'")
    if (!attemptedArticles.add(articleUrl)) {
      throw IOException("articolo già processato da un mirror: $articleUrl")
    }
    Log.i(TAG, "pagina articolo: $articleUrl")

    val articleHtml = ExtractorHttp.get(articleUrl, referer = "$base/")
    val content = decodeEntities(articleHtml)

    // --- 2/3) selezione dei link ------------------------------------------
    val links: List<Pair<String, String>> = if (isTv) {
      val s = (season ?: 1).coerceAtLeast(1)
      val e = (episode ?: 1).coerceAtLeast(1)
      val segment = episodeSegment(articleHtml, s, e)
        ?: throw IOException("episodio S$s:E$e non trovato nella pagina serie")
      Log.i(TAG, "trovato blocco episodio S${s}xE${e} (${segment.length} caratteri)")
      parseHosterLinks(segment)
    } else {
      parseHosterLinks(content)
    }

    if (links.isEmpty()) throw IOException("nessun link hoster trovato")
    Log.i(TAG, "link hoster trovati (${links.size}): " + links.joinToString { "${it.first}->${it.second}" })

    // --- 4) risoluzione + estrazione --------------------------------------
    val sources = HosterResolver.extractFromLinks(
      rawUrls = links,
      referer = articleUrl,
      onResolved = { resolved -> Log.i(TAG, "link risolto: $resolved") }
    )
    if (sources.isEmpty()) throw IOException("nessuna sorgente estratta dagli hoster Eurostreaming (protetti o non supportati)")
    return sources
  }

  /**
   * Isola il testo dell'episodio richiesto: dal marker `{stagione}×{episodio}`
   * fino al marker dell'episodio successivo. Sono accettati anche i formati
   * `S01E05` e `1x05`.
   *
   * `internal` per essere verificabile dai test JVM su una pagina reale.
   */
  internal fun episodeSegment(rawHtml: String, season: Int, episode: Int): String? {
    // Le entità HTML (`1&#215;01`) vengono normalizzate per il parsing degli episodi
    val content = decodeEntities(rawHtml)
    val marker = Regex(
      """(?:\b$season\s*[×xX]\s*0*$episode\b|\bS0*${season}E0*$episode\b)""",
      RegexOption.IGNORE_CASE
    )
    val start = marker.find(content) ?: return null
    val nextMarker = Regex("""\b\d+\s*[×x]\s*\d+\b""").find(content, start.range.last + 1)
    val end = nextMarker?.range?.first ?: content.length
    return content.substring(start.range.first, end)
  }

  internal fun parseHosterLinks(html: String): List<Pair<String, String>> {
    val out = linkedMapOf<String, String>()
    LINK_REGEX.findAll(html).forEach { m ->
      val url = m.groupValues[1]
      val label = m.groupValues[2].trim()
      if (HosterResolver.matchesHosterLabel(label)) out[url] = label
    }
    DATA_SRC_REGEX.findAll(html).forEach { m ->
      val url = m.groupValues[1]
      if (url.startsWith("http") && HosterResolver.isHosterUrl(url)) out.putIfAbsent(url, "iframe")
    }
    return out.map { (url, label) -> label to url }
  }

  private fun pickArticle(searchHtml: String, title: String?, baseUrl: String): String? {
    val candidates = ARTICLE_REGEX.findAll(searchHtml)
      .map { it.groupValues[1] }
      .distinct()
      .filter { url -> !NON_ARTICLE_REGEX.containsMatchIn(url) }
      .filter { url -> EXCLUDED_SLUGS.none { slugOf(url).startsWith(it) } }
      // Gli href possono essere relativi ("/slug/"): vengono assolutizzati sul base URL
      .map { url -> if (url.startsWith("http")) url else baseUrl.trimEnd('/') + url }
      .toList()
    if (candidates.isEmpty()) return null
    if (title.isNullOrBlank()) return candidates.first()
    // Solo una corrispondenza reale sul titolo (evita pagine a caso del tema/sito)
    val scored = candidates.map { it to HosterResolver.matchScore(slugOf(it), title) }
    return scored.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
  }

  /** Decodifica le entità HTML più comuni usate nei post dei cataloghi. */
  private fun decodeEntities(value: String): String = value
    .replace("&#215;", "×")
    .replace("&#10005;", "×")
    .replace("&#8211;", "-")
    .replace("&#8217;", "'")
    .replace("&amp;", "&")
    .replace("&times;", "×")

  private fun slugOf(url: String): String = url.trimEnd('/').substringAfterLast('/').lowercase()

  companion object {
    private const val TAG = "EurostreamingProvider"

    private val BASE_URLS = listOf(
      "https://eurostream.cfd",
      "https://eurostream.mom"
    )

    private val EXCLUDED_SLUGS = setOf(
      "feed", "category", "page", "comment", "comments", "elenco-serie-tv",
      "elenco-anime-cartoni", "guida-cambio-dns-aggiusta-siti-video",
      "nuovi-ep-aggiornamento", "richieste-film-serietv", "film", "login",
      "register", "author", "tag", "search", "wp-admin", "wp-content",
      "wp-includes", "wp-json", "xmlrpc", "fonts", "css", "js", "images",
      "img", "assets", "static", "uploads"
    )

    private val ARTICLE_REGEX = Regex("""href="((?:https?://[^"]+)?/[a-z0-9\-]{3,80}/)""", RegexOption.IGNORE_CASE)
    private val NON_ARTICLE_REGEX = Regex(
      """(wp-content|wp-includes|/fonts/|/css/|/js/|/images/|/img/|/assets/|/static/|/uploads/|/category/|/tag/|/page/|/feed/|/comments?/|/login/|/register/|/author/|/search/)""",
      RegexOption.IGNORE_CASE
    )
    private val LINK_REGEX = Regex("""<a[^>]+href="(https?://[^"]+)"[^>]*>\s*([^<]{1,30}?)\s*</a>""", RegexOption.IGNORE_CASE)
    private val DATA_SRC_REGEX = Regex("""data-src="(https?://[^"]+)"""", RegexOption.IGNORE_CASE)
  }
}
