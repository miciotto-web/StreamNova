package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.ExtractorHttp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Provider del catalogo italiano **CB01** (`cb01uno.skin` -> redirect sul mirror
 * attivo `cb01uno.top`, con fallback `cb01.stream`).
 *
 * Flusso (ogni passaggio è loggato su Logcat con tag `Cb01Provider`):
 *  1. **Ricerca per titolo/anno**: `GET {base}/?s={titolo} {anno}` (WordPress),
 *     poi scelta del risultato con punteggio di corrispondenza più alto.
 *  2. **Parsing dell'articolo**: estrae i link degli hoster dalle tabelle
 *     (`<a ...>Maxstream</a>`, `<a ...>Mixdrop</a>`) e dagli iframe player
 *     (`data-src="https://stayonline.pro/e/{code}"`). Sono riconosciuti anche
 *     Turbovid/DeltaBit (loggati come "non supportato" finché non si aggiunge
 *     il relativo estrattore).
 *  3. **Risoluzione shortener**: `stayonline.pro/l|e/{code}` viene risolto con
 *     `POST /ajax/linkView.php` (o `linkEmbedView.php`) parametro `id`;
 *     `uprot.net/...` è protetto da captcha e viene saltato con log esplicito.
 *  4. **Delega agli estrattori** MaxStream/Mixdrop e raccolta delle sorgenti.
 */
class Cb01Provider : StreamProvider {

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    val query = HosterResolver.buildQuery(title, year)
    if (query.isBlank()) {
      throw IOException("Titolo non disponibile per la ricerca CB01 (tmdbId=$tmdbId)")
    }

    val failures = mutableListOf<String>()
    // Mirror diversi possono redigere allo stesso sito (es. cb01uno.skin ->
    // cb01uno.top): l'articolo viene processato una sola volta.
    val attemptedArticles = HashSet<String>()
    for (base in BASE_URLS) {
      currentCoroutineContext().ensureActive()
      try {
        val sources = resolveOnBase(base, query, title, attemptedArticles)
        if (sources.isNotEmpty()) {
          Log.i(TAG, "sorgenti CB01 su $base: ${sources.size}")
          return@withContext sources
        }
        failures += "$base -> nessuna sorgente"
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        Log.w(TAG, "fallback mirror $base: ${e.message}")
        failures += "$base -> ${e.message}"
      }
    }
    throw IOException("CB01 non risolvibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnBase(
    base: String,
    query: String,
    title: String?,
    attemptedArticles: MutableSet<String>
  ): List<StreamSource> {
    // --- 1) ricerca per titolo -------------------------------------------
    val searchUrl = "$base/?s=${HosterResolver.encode(query)}"
    Log.i(TAG, "GET RICERCA $searchUrl")
    val searchHtml = ExtractorHttp.get(searchUrl, referer = "$base/")
    val articleUrl = pickArticle(searchHtml, title, base)
      ?: throw IOException("nessun risultato per '$query'")
    if (!attemptedArticles.add(articleUrl)) {
      throw IOException("articolo già processato da un mirror: $articleUrl")
    }
    Log.i(TAG, "pagina articolo: $articleUrl")

    // --- 2) parsing link hoster ------------------------------------------
    val articleHtml = ExtractorHttp.get(articleUrl, referer = "$base/")
    val links = parseHosterLinks(articleHtml)
    if (links.isEmpty()) throw IOException("nessun link hoster nell'articolo")
    Log.i(TAG, "link hoster trovati (${links.size}): " + links.joinToString { "${it.first}->${it.second}" })

    // --- 3) + 4) risoluzione shortener e delega agli estrattori -----------
    val sources = HosterResolver.extractFromLinks(
      rawUrls = links,
      referer = articleUrl,
      onResolved = { resolved -> Log.i(TAG, "link risolto: $resolved") }
    )
    if (sources.isEmpty()) {
      throw IOException("nessuna sorgente estratta dagli hoster CB01 (protetti o non supportati)")
    }
    return sources
  }

  /** Sceglie il risultato di ricerca con la corrispondenza effettiva sul titolo. */
  internal fun pickArticle(searchHtml: String, title: String?, baseUrl: String): String? {
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
    // Solo una corrispondenza reale sul titolo: evita di aprire pagine a caso
    // (temi, categorie, altri film) quando il titolo non è nel catalogo.
    val scored = candidates.map { it to HosterResolver.matchScore(slugOf(it), title) }
    return scored.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
  }

  /** Estrae i link hoster: etichette riconosciute + data-src degli iframe player. */
  internal fun parseHosterLinks(html: String): List<Pair<String, String>> {
    val out = linkedMapOf<String, String>()

    LINK_REGEX.findAll(html).forEach { m ->
      val url = m.groupValues[1]
      val label = m.groupValues[2].trim()
      if (HosterResolver.matchesHosterLabel(label)) out[url] = label
    }
    DATA_SRC_REGEX.findAll(html).forEach { m ->
      val url = m.groupValues[1]
      // Solo iframe di shortener/hoster noti: il trailer YouTube va ignorato
      if (url.startsWith("http") && HosterResolver.isHosterUrl(url)) out.putIfAbsent(url, "iframe")
    }
    return out.map { (url, label) -> label to url }
  }

  private fun slugOf(url: String): String =
    url.trimEnd('/').substringAfterLast('/').lowercase()

  companion object {
    private const val TAG = "Cb01Provider"

    /** Dominio primario (redirect) + mirror noti, in ordine di tentativo. */
    private val BASE_URLS = listOf(
      "https://cb01uno.skin",
      "https://cb01uno.top",
      "https://cb01.stream"
    )

    private val EXCLUDED_SLUGS = setOf(
      "feed", "login", "register", "lostpassword", "author", "category", "tag",
      "page", "film", "serie-tv", "serietv", "genere", "popolari", "i-piu-votati",
      "search", "privacy", "dmca", "imdb", "contatto", "comment", "wp-admin",
      "wp-content", "wp-includes", "wp-json", "xmlrpc", "account", "episodi",
      // cartelle di asset/navigazione: non sono mai pagine articolo
      "fonts", "css", "js", "images", "img", "assets", "static", "uploads"
    )

    private val ARTICLE_REGEX = Regex("""href="((?:https?://[^"]+)?/[a-z0-9\-]{3,80}/)""", RegexOption.IGNORE_CASE)
    private val NON_ARTICLE_REGEX = Regex(
      """ /(wp-content|wp-includes|fonts|css|js|images|img|assets|static|uploads|category|tag|page|feed|comment|login|register|author|search)(/|$)""".trim(),
      RegexOption.IGNORE_CASE
    )
    private val LINK_REGEX = Regex("""<a[^>]+href="(https?://[^"]+)"[^>]*>\s*([^<]{1,30}?)\s*</a>""", RegexOption.IGNORE_CASE)
    private val DATA_SRC_REGEX = Regex("""data-src="(https?://[^"]+)"""", RegexOption.IGNORE_CASE)
  }
}
