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
class EurostreamingProvider internal constructor(
  private val baseUrls: List<String>,
  private val diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE,
  private val http: EurostreamingHttp
) : StreamProvider {

  /** Costruttore pubblico storico: mirror reali e OkHttp. */
  constructor(diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE) : this(
    BASE_URLS,
    diagnostics,
    EurostreamingHttp { url, referer -> ExtractorHttp.get(url, referer, callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS) }
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
    if (query.isBlank()) throw IOException("Titolo non disponibile per la ricerca Eurostreaming (tmdbId=$tmdbId)")

    val failures = mutableListOf<String>()
    // I mirror redirigono sullo stesso sito: l'articolo si processa una volta,
    // ma solo dopo che il parsing è riuscito. Un errore transitorio consente il
    // retry sul mirror successivo.
    val attemptedArticles = HashSet<String>()
    for (base in baseUrls) {
      currentCoroutineContext().ensureActive()
      val startedAt = System.currentTimeMillis()
      val mirrorId = StreamDiagnostics.hostId(base)
      try {
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
          Log.i(TAG, "sorgenti Eurostreaming su $base: ${sources.size}")
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
    throw IOException("Eurostreaming non risolvibile (${failures.joinToString(" | ")})")
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
    // --- 1) ricerca -------------------------------------------------------
    val searchUrl = "$base/?s=${HosterResolver.encode(query)}"
    Log.i(TAG, "GET RICERCA $searchUrl")
    val searchHtml = http.get(searchUrl, "$base/")
    val articleUrl = pickArticle(searchHtml, title, base, year)
      ?: throw IOException("nessun risultato per '$query'")
    if (articleUrl in attemptedArticles) {
      throw IOException("articolo già processato da un mirror: $articleUrl")
    }
    Log.i(TAG, "pagina articolo: $articleUrl")

    val articleHtml = http.get(articleUrl, "$base/")

    // --- 2/3) selezione dei link ------------------------------------------
    val links: List<Pair<String, String>> = if (isTv) {
      val s = (season ?: 1).coerceAtLeast(1)
      val e = (episode ?: 1).coerceAtLeast(1)
      val segment = episodeSegment(articleHtml, s, e)
        ?: throw IOException("episodio S$s:E$e non trovato nella pagina serie")
      Log.i(TAG, "trovato blocco episodio S${s}xE${e} (${segment.length} caratteri)")
      parseHosterLinks(segment)
    } else {
      parseHosterLinks(articleHtml)
    }

    if (links.isEmpty()) throw IOException("nessun link hoster trovato")
    Log.i(TAG, "link hoster trovati (${links.size}): " + links.joinToString { "${it.first}->${it.second}" })
    // Parsing riuscito: l'articolo è completato e non va ripetuto da un mirror.
    if (!attemptedArticles.add(articleUrl)) {
      throw IOException("articolo già processato da un mirror: $articleUrl")
    }

    // --- 4) risoluzione + estrazione --------------------------------------
    val sources = HosterResolver.extractFromLinks(
      rawUrls = links,
      referer = articleUrl,
      onResolved = { resolved -> Log.i(TAG, "link risolto: $resolved") },
      providerId = PROVIDER_ID,
      mirrorId = StreamDiagnostics.hostId(base),
      diagnostics = diagnostics
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
  internal fun episodeSegment(rawHtml: String, season: Int, episode: Int): String? =
    EpisodeMarkers.segment(rawHtml, season, episode)

  internal fun parseHosterLinks(html: String): List<Pair<String, String>> {
    val out = linkedMapOf<String, String>()
    LINK_REGEX.findAll(html).forEach { m ->
      val url = HtmlEntities.decodeAttribute(m.groupValues[1])
      val label = m.groupValues[2].trim()
      // "DL"/"Download" sono accettati solo verso un hoster/shortener noto:
      // un banner pubblicitario con la stessa etichetta non è una sorgente.
      val accepted = HosterResolver.matchesHosterLabel(label) ||
        (isDownloadLabel(label) && HosterResolver.isSupportedHosterUrl(url))
      if (accepted) out[url] = label
    }
    DATA_SRC_REGEX.findAll(html).forEach { m ->
      val url = HtmlEntities.decodeAttribute(m.groupValues[1])
      if (url.startsWith("http") && HosterResolver.isHosterUrl(url)) out.putIfAbsent(url, "iframe")
    }
    return out.map { (url, label) -> label to url }
  }

  private fun isDownloadLabel(label: String): Boolean =
    label.equals("dl", ignoreCase = true) || label.equals("download", ignoreCase = true)

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
    // Solo una corrispondenza reale sul titolo (evita pagine a caso del tema/sito)
    val scored = candidates.map { it to HosterResolver.matchScore(slugOf(it), title, year) }
    return scored.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
  }

  private fun slugOf(url: String): String = url.trimEnd('/').substringAfterLast('/').lowercase()

  companion object {
    private const val TAG = "EurostreamingProvider"

    /** Identificativo stabile nella catena diagnostica. */
    internal const val PROVIDER_ID = "Eurostreaming"

    /** Budget per singolo mirror; il timeout globale del chiamante resta 15 s. */
    const val MIRROR_BUDGET_MS = 12_000L

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

/** Fetcher HTTP iniettabile per i test offline del retry articoli. */
internal fun interface EurostreamingHttp {
  suspend fun get(url: String, referer: String?): String
}
