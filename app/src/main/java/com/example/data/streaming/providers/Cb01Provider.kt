package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.data.streaming.diagnostics.record
import com.example.data.streaming.extractors.ExtractorHttp
import com.example.data.streaming.extractors.HtmlEntities
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
class Cb01Provider internal constructor(
  private val baseUrls: List<String>,
  private val diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE,
  private val http: Cb01Http
) : StreamProvider {

  /** Costruttore pubblico storico: mirror reali e OkHttp. */
  constructor(diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE) : this(
    BASE_URLS,
    diagnostics,
    Cb01Http { url, referer -> ExtractorHttp.get(url, referer, callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS) }
  )

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
    // cb01uno.top): l'articolo viene processato una sola volta, ma solo DOPO
    // che il parsing è riuscito. Un timeout/HTTP transitorio non deve impedire
    // il retry sul mirror successivo.
    val attemptedArticles = HashSet<String>()
    for (base in baseUrls) {
      currentCoroutineContext().ensureActive()
      val startedAt = System.currentTimeMillis()
      val mirrorId = StreamDiagnostics.hostId(base)
      try {
        // Budget per mirror, distinto dal timeout globale di StreamManager:
        // un mirror lento non deve consumare l'intero budget del provider.
        val sources = withTimeoutOrNull(MIRROR_BUDGET_MS) {
          resolveOnBase(base, query, title, year, isTv, season, episode, attemptedArticles)
        }
        if (sources == null) {
          Log.w(TAG, "mirror $base: timeout dopo ${MIRROR_BUDGET_MS}ms")
          failures += "$base -> timeout ${MIRROR_BUDGET_MS}ms"
          diagnostics.record(
            providerId = PROVIDER_ID,
            mirrorId = mirrorId,
            outcome = StreamAttemptOutcome.TIMEOUT,
            durationMs = System.currentTimeMillis() - startedAt,
            message = "timeout mirror ${MIRROR_BUDGET_MS}ms"
          )
          continue
        }
        if (sources.isNotEmpty()) {
          Log.i(TAG, "sorgenti CB01 su $base: ${sources.size}")
          diagnostics.record(
            providerId = PROVIDER_ID,
            mirrorId = mirrorId,
            outcome = StreamAttemptOutcome.SUCCESS,
            durationMs = System.currentTimeMillis() - startedAt
          )
          return@withContext sources
        }
        failures += "$base -> nessuna sorgente"
        diagnostics.record(
          providerId = PROVIDER_ID,
          mirrorId = mirrorId,
          outcome = StreamAttemptOutcome.NO_RESULTS,
          durationMs = System.currentTimeMillis() - startedAt
        )
      } catch (ex: CancellationException) {
        diagnostics.record(
          providerId = PROVIDER_ID,
          mirrorId = mirrorId,
          outcome = StreamAttemptOutcome.CANCELLED,
          durationMs = System.currentTimeMillis() - startedAt,
          message = "ricerca annullata"
        )
        throw ex
      } catch (e: Exception) {
        Log.w(TAG, "fallback mirror $base: ${e.message}")
        failures += "$base -> ${e.message}"
        diagnostics.record(
          providerId = PROVIDER_ID,
          mirrorId = mirrorId,
          outcome = StreamDiagnostics.classify(e),
          durationMs = System.currentTimeMillis() - startedAt,
          message = e.message
        )
      }
    }
    throw IOException("CB01 non risolvibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnBase(
    base: String,
    query: String,
    title: String?,
    year: Int?,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    attemptedArticles: MutableSet<String>
  ): List<StreamSource> {
    // --- 1) ricerca per titolo -------------------------------------------
    val searchUrl = "$base/?s=${HosterResolver.encode(query)}"
    Log.i(TAG, "GET RICERCA $searchUrl")
    val searchHtml = http.get(searchUrl, "$base/")
    val articleUrl = pickArticle(searchHtml, title, base, year)
      ?: throw IOException("nessun risultato per '$query'")
    if (articleUrl in attemptedArticles) {
      throw IOException("articolo già processato da un mirror: $articleUrl")
    }
    Log.i(TAG, "pagina articolo: $articleUrl")

    // --- 2) parsing link hoster ------------------------------------------
    val articleHtml = http.get(articleUrl, "$base/")
    val links = parseHosterLinksForEpisode(articleHtml, isTv, season, episode)
    if (links.isEmpty()) {
      throw IOException(
        if (isTv) "nessun link hoster per l'episodio S${season ?: 1}E${episode ?: 1}"
        else "nessun link hoster nell'articolo"
      )
    }
    Log.i(TAG, "link hoster trovati (${links.size}): " + links.joinToString { "${it.first}->${it.second}" })
    // Parsing riuscito: da qui in poi l'articolo è "completato" e non va
    // ripetuto sulla stessa pagina da un altro mirror.
    if (!attemptedArticles.add(articleUrl)) {
      throw IOException("articolo già processato da un mirror: $articleUrl")
    }

    // --- 3) + 4) risoluzione shortener e delega agli estrattori -----------
    val sources = HosterResolver.extractFromLinks(
      rawUrls = links,
      referer = articleUrl,
      onResolved = { resolved -> Log.i(TAG, "link risolto: $resolved") },
      providerId = PROVIDER_ID,
      mirrorId = StreamDiagnostics.hostId(base),
      diagnostics = diagnostics
    )
    if (sources.isEmpty()) {
      throw IOException("nessuna sorgente estratta dagli hoster CB01 (protetti o non supportati)")
    }
    return sources
  }

  /** Sceglie il risultato di ricerca con la corrispondenza effettiva sul titolo. */
  internal fun pickArticle(searchHtml: String, title: String?, baseUrl: String, year: Int? = null): String? {
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
    val scored = candidates.map { it to HosterResolver.matchScore(slugOf(it), title, year) }
    return scored.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
  }

  /**
   * Link hoster dell'articolo.
   *
   * Per le serie TV viene isolato il segmento dell'episodio richiesto: i link
   * generici o di episodi adiacenti non vengono mai restituiti come target.
   * Per i film resta il comportamento storico (tutti i link dell'articolo).
   */
  internal fun parseHosterLinksForEpisode(
    html: String,
    isTv: Boolean,
    season: Int?,
    episode: Int?
  ): List<Pair<String, String>> {
    if (!isTv) return parseHosterLinks(html)
    val s = (season ?: 1).coerceAtLeast(1)
    val e = (episode ?: 1).coerceAtLeast(1)
    val segment = EpisodeMarkers.segment(html, s, e) ?: return emptyList()
    return parseHosterLinks(segment)
  }

  /** Estrae i link hoster: etichette riconosciute + data-src degli iframe player. */
  internal fun parseHosterLinks(html: String): List<Pair<String, String>> {
    val out = linkedMapOf<String, String>()

    ANCHOR_REGEX.findAll(html).forEach { m ->
      val url = HtmlEntities.decodeAttribute(m.groupValues[1])
      // La label può essere avvolta in <span>/<strong> (es. "Maxstream HD"):
      // va estratto il testo, non confrontato il markup.
      val label = m.groupValues[2]
        .replace(TAG_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
      if (HosterResolver.matchesHosterLabel(label)) out[url] = label
    }
    DATA_SRC_REGEX.findAll(html).forEach { m ->
      val url = HtmlEntities.decodeAttribute(m.groupValues[1])
      // Solo iframe di shortener/hoster noti: il trailer YouTube va ignorato
      if (url.startsWith("http") && HosterResolver.isHosterUrl(url)) out.putIfAbsent(url, "iframe")
    }
    return out.map { (url, label) -> label to url }
  }

  private fun slugOf(url: String): String =
    url.trimEnd('/').substringAfterLast('/').lowercase()

  companion object {
    private const val TAG = "Cb01Provider"

    /** Identificativo stabile nella catena diagnostica. */
    internal const val PROVIDER_ID = "Cb01"

    /** Budget per singolo mirror; il timeout globale del chiamante resta 15 s. */
    const val MIRROR_BUDGET_MS = 12_000L

    /**
     * Dominio attuale per primo (evita un redirect per ogni richiesta), poi i
     * mirror storici che oggi redirigono su di esso.
     */
    private val BASE_URLS = listOf(
      "https://cb01one.com",
      "https://cb01uno.top",
      "https://cb01uno.skin",
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
    private val ANCHOR_REGEX = Regex(
      """<a\b[^>]*?\shref\s*=\s*"([^"]+)"[^>]*>(.{0,160}?)</a>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TAG_REGEX = Regex("""<[^>]+>""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val DATA_SRC_REGEX = Regex(
      """data-src\s*=\s*["'](https?://[^"']+)["']""",
      RegexOption.IGNORE_CASE
    )
  }
}

/** Fetcher HTTP iniettabile per i test offline del retry articoli. */
internal fun interface Cb01Http {
  suspend fun get(url: String, referer: String?): String
}
