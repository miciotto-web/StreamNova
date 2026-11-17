package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.data.streaming.diagnostics.record
import com.example.data.streaming.extractors.ExtractorHttp
import com.example.data.streaming.extractors.VideoExtractor
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provider del catalogo italiano **GuardaPlay** (`guardaplay.autos`, già
 * `guardaplay.cam`): film in streaming, con player incorporati tramite il
 * parametro WordPress `?trembed=N&trid=...&trtype=1`.
 *
 * Flusso verificato sul sito reale:
 *  1. **Ricerca**: `GET {base}/?s={titolo} {anno}`; i risultati sono `<article>`
 *     con `h2.entry-title`, `span.year` e `a.lnk-blk` verso `/movies/...`.
 *     La ricerca viene limitata alla sezione `#aa-movies` per non pescare la
 *     sidebar.
 *  2. **Pagina film**: si estraggono gli iframe `trembed=...` (uno per player).
 *  3. **Pagina trembed**: può contenere
 *     - un iframe verso Uqload/Streamtape/Voe/Doodstream (estrattore dedicato),
 *     - un iframe VixSrc (`vixsrc.to/movie/...`): **saltato**, perché VixSrc
 *       ha già un provider dedicato basato su TMDB,
 *     - un iframe `loadm.cam/#hash`: risolto con
 *       `GET /api/public/resolve?file1={hash}` → URL finale,
 *     - un URL hoster diretto nello script (senza iframe).
 *  4. **Delega**: gli URL degli hoster vengono passati a [HosterResolver];
 *     gli URL media diretti diventano una [StreamSource] progressiva/HLS.
 *
 * Il provider è **solo film**: per le serie TV solleva IOException, così
 * StreamManager può passare agli altri provider senza richieste inutili.
 */
class GuardaPlayProvider internal constructor(
  private val baseUrls: List<String>,
  private val diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE,
  private val http: GuardaPlayHttp,
  private val hosterExtractor: (String) -> VideoExtractor? = { url -> HosterResolver.extractorFor(url) }
) : StreamProvider {

  /** Costruttore pubblico: mirror reali e OkHttp. */
  constructor(diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE) : this(
    BASE_URLS,
    diagnostics,
    GuardaPlayHttp { url, referer ->
      ExtractorHttp.getWithFinalUrl(
        url,
        referer,
        callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
      )
    }
  )

  override val providerId: String get() = PROVIDER_ID

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    if (isTv) throw IOException("GuardaPlay supporta solo film (tmdbId=$tmdbId)")

    val query = HosterResolver.buildQuery(title, year)
    if (query.isBlank()) {
      throw IOException("Titolo non disponibile per la ricerca GuardaPlay (tmdbId=$tmdbId)")
    }

    val failures = mutableListOf<String>()
    for (base in baseUrls) {
      currentCoroutineContext().ensureActive()
      val startedAt = System.currentTimeMillis()
      val mirrorId = StreamDiagnostics.hostId(base)
      try {
        val sources = withTimeoutOrNull(MIRROR_BUDGET_MS) {
          resolveOnBase(base, title, year)
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
          Log.i(TAG, "sorgenti GuardaPlay su $base: ${sources.size}")
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
    throw IOException("GuardaPlay non risolvibile (${failures.take(4).joinToString(" | ")})")
  }

  // --------------------------------------------------------------------- //
  //  Ricerca -> articolo
  // --------------------------------------------------------------------- //

  private suspend fun resolveOnBase(
    base: String,
    title: String?,
    year: Int?
  ): List<StreamSource> {
    val queries = buildSearchQueries(title, year)
    if (queries.isEmpty()) return emptyList()

    val collected = LinkedHashMap<String, GuardaPlayEntry>()
    val attempted = HashSet<String>()
    val failures = mutableListOf<String>()

    for (query in queries) {
      currentCoroutineContext().ensureActive()
      val searchUrl = "$base/?s=${HosterResolver.encode(query)}"
      val (searchFinalUrl, searchHtml) = http.get(searchUrl, "$base/")
      parseSearch(searchHtml, searchFinalUrl).forEach { entry ->
        collected.putIfAbsent(entry.url, entry)
      }

      val ranked = rankEntries(collected.values.toList(), title, year)
      if (ranked.isEmpty()) {
        Log.i(TAG, "nessun risultato GuardaPlay per '$query'")
        continue
      }

      for (entry in ranked) {
        if (!attempted.add(entry.url)) continue
        currentCoroutineContext().ensureActive()
        try {
          val sources = resolveMovie(entry, base)
          if (sources.isNotEmpty()) return sources
          failures += "${entry.title}: nessuna sorgente"
        } catch (ex: CancellationException) {
          throw ex
        } catch (e: Exception) {
          failures += "${entry.title}: ${e.message}"
        }
      }
    }
    if (failures.isNotEmpty()) Log.w(TAG, "GuardaPlay: ${failures.take(3).joinToString(" | ")}")
    return emptyList()
  }

  /**
   * Query di ricerca in ordine di priorità.
   *
   * La ricerca WordPress di GuardaPlay è **stretta**: "Titolo 2026" può
   * restituire zero risultati anche se il film esiste. Si parte quindi dal
   * solo titolo; l'anno resta nel ranking (bonus/penalità), non nella query.
   * Le varianti senza sottotitolo ("Titolo - Sottotitolo") sono un'ultima
   * spiaggia per i titoli lunghi.
   */
  internal fun buildSearchQueries(title: String?, year: Int?): List<String> {
    val baseTitle = title?.trim().orEmpty()
    if (baseTitle.isBlank()) return emptyList()
    val queries = LinkedHashSet<String>()
    queries += baseTitle
    if (year != null && year > 1900) queries += "$baseTitle $year"
    listOf(
      baseTitle.substringBefore(" - "),
      baseTitle.substringBefore(':')
    ).forEach { variant ->
      val clean = variant.trim()
      if (clean.length >= 3 && clean != baseTitle) queries += clean
    }
    return queries.take(MAX_SEARCH_QUERIES).toList()
  }

  /**
   * Estrae i risultati di ricerca dalla sezione `#aa-movies`.
   *
   * `internal` per i test JVM con HTML sintetico, senza rete.
   */
  internal fun parseSearch(html: String, baseUrl: String): List<GuardaPlayEntry> {
    val sectionStart = html.indexOf("id=\"aa-movies\"")
    val sectionEnd = if (sectionStart >= 0) html.indexOf("<aside", sectionStart) else -1
    val scoped = when {
      sectionStart < 0 -> html
      sectionEnd > sectionStart -> html.substring(sectionStart, sectionEnd)
      else -> html.substring(sectionStart)
    }

    val entries = LinkedHashMap<String, GuardaPlayEntry>()
    ARTICLE_REGEX.findAll(scoped).forEach { articleMatch ->
      val article = articleMatch.groupValues[1]
      val title = ENTRY_TITLE_REGEX.find(article)?.groupValues?.get(1)
        ?.replace(TAG_REGEX, "")
        ?.let(::decodeHtml)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return@forEach

      val href = LNK_BLK_TAG_REGEX.find(article)
        ?.groupValues?.get(1)
        ?.let { HREF_REGEX.find(it)?.groupValues?.get(1) }
        ?: return@forEach
      val url = normalizeUrl(href, baseUrl)?.takeIf { it.contains("/movies/") } ?: return@forEach
      val year = YEAR_REGEX.find(article)?.groupValues?.get(1)?.toIntOrNull()

      entries.putIfAbsent(
        url,
        GuardaPlayEntry(url = url, title = title, year = year)
      )
    }
    return entries.values.toList()
  }

  /** Ordina per corrispondenza titolo/anno e tiene i primi [MAX_RESULTS]. */
  internal fun rankEntries(
    entries: List<GuardaPlayEntry>,
    title: String?,
    year: Int?
  ): List<GuardaPlayEntry> {
    val query = normalize(title.orEmpty())
    if (query.isEmpty()) return entries.take(MAX_RESULTS)
    return entries
      .map { entry -> entry to score(entry, query, year) }
      .filter { (_, score) -> score > 0 }
      .sortedByDescending { (_, score) -> score }
      .take(MAX_RESULTS)
      .map { (entry, _) -> entry }
  }

  private fun score(entry: GuardaPlayEntry, query: String, year: Int?): Int {
    val title = normalize(entry.title)
    if (title.isEmpty()) return 0
    var score = when {
      title == query -> 100
      title.startsWith("$query ") || query.startsWith("$title ") -> 85
      title.contains(query) || query.contains(title) -> 80
      else -> {
        val et = title.split(' ').toSet()
        val qt = query.split(' ').toSet()
        val common = et.intersect(qt).size
        if (common == 0) 0 else (60.0 * common / minOf(et.size, qt.size)).toInt()
      }
    }
    if (score == 0) return 0
    if (year != null) {
      val entryYear = entry.year
      if (entryYear == year) score += YEAR_BONUS
      else if (entryYear != null) score -= YEAR_MISMATCH_PENALTY
    }
    return score
  }

  private fun normalize(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
      .replace(Regex("\\p{M}"), "")
      .lowercase(Locale.ROOT)
      .replace(Regex("[^a-z0-9]+"), " ")
      .trim()

  // --------------------------------------------------------------------- //
  //  Articolo -> player
  // --------------------------------------------------------------------- //

  private suspend fun resolveMovie(entry: GuardaPlayEntry, base: String): List<StreamSource> {
    val (finalUrl, html) = http.get(entry.url, "$base/")
    val players = parseTrembedFrames(html, finalUrl)
    if (players.isEmpty()) throw IOException("nessun player trembed nell'articolo")

    val failures = mutableListOf<String>()
    for (player in players) {
      currentCoroutineContext().ensureActive()
      try {
        val sources = resolveTrembed(player, finalUrl)
        if (sources.isNotEmpty()) return sources
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        failures += "${StreamDiagnostics.hostId(player) ?: "player"}: ${e.message}"
      }
    }
    throw IOException(
      "nessun player risolvibile (${failures.take(3).joinToString(" | ").ifBlank { "player vuoti" }})"
    )
  }

  /** Iframe `trembed=...` della pagina film, normalizzati e deduplicati. */
  internal fun parseTrembedFrames(html: String, baseUrl: String): List<String> =
    TREMBED_IFRAME_REGEX.findAll(html)
      .mapNotNull { normalizeUrl(it.groupValues[1], baseUrl) }
      .filter { it.contains("trembed=") }
      .distinct()
      .toList()

  private suspend fun resolveTrembed(trembedUrl: String, movieUrl: String): List<StreamSource> {
    val (finalUrl, html) = http.get(trembedUrl, movieUrl)
    val candidates = parsePlayerCandidates(html, finalUrl)
    if (candidates.isEmpty()) throw IOException("player embed senza iframe o URL hoster")

    val failures = mutableListOf<String>()
    for (candidate in candidates) {
      currentCoroutineContext().ensureActive()
      try {
        val sources = resolveCandidate(candidate, finalUrl, depth = 0)
        if (sources.isNotEmpty()) return sources
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        failures += "${StreamDiagnostics.hostId(candidate) ?: "host"}: ${e.message}"
      }
    }
    throw IOException(
      "nessuna sorgente dal player (${failures.take(3).joinToString(" | ").ifBlank { "candidati non supportati" }})"
    )
  }

  /**
   * Candidati di un player `trembed`: iframe (forma normale) oppure URL
   * hoster/media in chiaro nello script (forma usata da Uqload senza iframe).
   *
   * `internal` per i test JVM con HTML sintetico, senza rete.
   */
  internal fun parsePlayerCandidates(html: String, baseUrl: String): List<String> {
    val out = LinkedHashSet<String>()
    IFRAME_SRC_REGEX.findAll(html).forEach { match ->
      normalizeUrl(match.groupValues[1], baseUrl)
        ?.takeIf(::isUsefulCandidate)
        ?.let { out += it }
    }
    // Alcuni player (Uqload) non usano un iframe: l'URL hoster è nello script.
    // Si aggiungono sempre, non solo quando mancano iframe: una pagina può
    // contenere sia un iframe VixSrc sia un URL Uqload in chiaro.
    ABS_URL_REGEX.findAll(html).forEach { match ->
      normalizeUrl(match.value, baseUrl)
        ?.takeIf(::isUsefulCandidate)
        ?.let { out += it }
    }
    return out.toList()
  }

  private fun isUsefulCandidate(url: String): Boolean {
    val host = hostOf(url) ?: return false
    if (host in EXCLUDED_HOSTS) return false
    if (host.endsWith(".guardaplay.autos") || host == "guardaplay.autos" ||
      host.endsWith(".guardaplay.cam") || host == "guardaplay.cam"
    ) {
      return false
    }
    return isVixSrcHost(host) ||
      isLoadmHost(host) ||
      isDirectMediaUrl(url) ||
      HosterResolver.isHosterUrl(url)
  }

  private suspend fun resolveCandidate(
    candidate: String,
    referer: String,
    depth: Int
  ): List<StreamSource> {
    if (depth > MAX_DEPTH) return emptyList()
    val host = hostOf(candidate) ?: return emptyList()

    if (isVixSrcHost(host)) {
      Log.i(TAG, "player VixSrc saltato: già coperto da VixSrcProvider")
      return emptyList()
    }

    if (isLoadmHost(host)) {
      val resolved = resolveLoadm(candidate) ?: return emptyList()
      return resolveCandidate(resolved, candidate, depth + 1)
    }

    if (isDirectMediaUrl(candidate)) {
      return listOf(directSource(candidate))
    }

    val extractor = hosterExtractor(candidate)
    if (extractor == null) {
      Log.i(TAG, "hoster non supportato: ${StreamDiagnostics.hostId(candidate) ?: "host sconosciuto"}")
      return emptyList()
    }
    return extractor.extract(candidate)
  }

  /**
   * `loadm.cam` espone `GET /api/public/resolve?file1={hash}` che restituisce
   * `{"ok":true,"url":"..."}`. L'hash è nel fragment (`#abc`).
   *
   * `internal` per i test JVM.
   */
  internal suspend fun resolveLoadm(playerUrl: String): String? {
    val hash = extractLoadmHash(playerUrl) ?: return null
    val origin = originOf(playerUrl) ?: return null
    val apiUrl = "$origin/api/public/resolve?file1=${URLEncoder.encode(hash, "UTF-8")}"
    val (_, body) = http.get(apiUrl, playerUrl)
    return JSON_URL_REGEX.find(body)?.groupValues?.get(1)
      ?.replace("\\/", "/")
      ?.replace("&amp;", "&")
      ?.trim()
      ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
  }

  internal fun extractLoadmHash(playerUrl: String): String? = try {
    val uri = URI(playerUrl)
    val scheme = uri.scheme?.lowercase()
    if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
      null
    } else {
      uri.fragment?.trim()?.takeIf { it.isNotBlank() }
        ?: uri.path.orEmpty().split('/').lastOrNull { it.isNotBlank() }
    }
  } catch (e: Exception) {
    null
  }

  private fun directSource(url: String): StreamSource {
    val origin = originOf(url)
    return StreamSource(
      streamUrl = url,
      quality = "Auto",
      serverName = SERVER_NAME,
      headers = mapOf(
        "Referer" to (origin?.let { "$it/" } ?: ""),
        "User-Agent" to ExtractorHttp.USER_AGENT
      ).filterValues { it.isNotBlank() },
      isProgressive = !url.substringBefore('?').contains(".m3u8", ignoreCase = true),
      isItalian = true
    )
  }

  // --------------------------------------------------------------------- //
  //  Parsing helpers
  // --------------------------------------------------------------------- //

  private fun normalizeUrl(raw: String, baseUrl: String): String? {
    val value = raw.trim()
      .replace("&#038;", "&")
      .replace("&amp;", "&")
      .trimEnd('\'', '"', ';', ',', ')', ']', '}')
    if (value.isBlank()) return null
    return when {
      value.startsWith("http://") || value.startsWith("https://") -> value
      value.startsWith("//") -> "https:$value"
      value.startsWith("/") -> originOf(baseUrl)?.let { "$it$value" }
      else -> try {
        URI(baseUrl).resolve(value).toString()
      } catch (e: Exception) {
        null
      }
    }
  }

  private fun hostOf(url: String): String? = try {
    URI(url).host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() }
  } catch (e: Exception) {
    null
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

  private fun isDirectMediaUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    return MEDIA_EXTENSIONS.any { path.endsWith(it) }
  }

  private fun isVixSrcHost(host: String): Boolean =
    host == "vixsrc.to" || host.endsWith(".vixsrc.to") ||
      host == "vixcloud.co" || host.endsWith(".vixcloud.co") ||
      host == "vixcloud.to" || host.endsWith(".vixcloud.to") ||
      host == "vixcloud.cc" || host.endsWith(".vixcloud.cc") ||
      host.endsWith(".vix-content.net")

  private fun isLoadmHost(host: String): Boolean =
    host == "loadm.cam" || host.startsWith("loadm.")

  private fun decodeHtml(value: String): String =
    ENTITY_REGEX.replace(value) { match ->
      when (match.groupValues[1].lowercase(Locale.ROOT)) {
        "amp" -> "&"
        "quot" -> "\""
        "apos" -> "'"
        "lt" -> "<"
        "gt" -> ">"
        "nbsp" -> " "
        "#39", "#039", "#x27" -> "'"
        "#8211" -> "–"
        "#8217" -> "’"
        else -> match.value
      }
    }

  companion object {
    private const val TAG = "GuardaPlayProvider"
    internal const val PROVIDER_ID = "GuardaPlay"
    private const val SERVER_NAME = "GuardaPlay"

    /** Budget per singolo mirror; il timeout globale del provider resta 15 s. */
    const val MIRROR_BUDGET_MS = 12_000L

    private const val MAX_RESULTS = 3
    private const val MAX_SEARCH_QUERIES = 4
    private const val MAX_DEPTH = 3
    private const val YEAR_BONUS = 10
    private const val YEAR_MISMATCH_PENALTY = 15

    private val BASE_URLS = listOf(
      "https://guardaplay.autos",
      "https://guardaplay.cam"
    )

    private val EXCLUDED_HOSTS = setOf(
      "youtube.com", "www.youtube.com", "youtu.be",
      "googletagmanager.com", "www.googletagmanager.com",
      "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
      "histats.com", "sstatic1.histats.com",
      "cloudflare.com", "challenges.cloudflare.com"
    )

    private val ARTICLE_REGEX = Regex(
      """<article\b[^>]*>(.*?)</article>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val ENTRY_TITLE_REGEX = Regex(
      """<h[1-6][^>]*class\s*=\s*["'][^"']*\bentry-title\b[^"']*["'][^>]*>(.*?)</h[1-6]>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val LNK_BLK_TAG_REGEX = Regex(
      """<a\b([^>]*\bclass\s*=\s*["'][^"']*\blnk-blk\b[^"']*["'][^>]*)>""",
      RegexOption.IGNORE_CASE
    )
    private val HREF_REGEX = Regex(
      """\bhref\s*=\s*["']([^"']+)["']""",
      RegexOption.IGNORE_CASE
    )
    private val YEAR_REGEX = Regex(
      """<span[^>]*class\s*=\s*["'][^"']*\byear\b[^"']*["'][^>]*>(\d{4})</span>""",
      RegexOption.IGNORE_CASE
    )
    private val TAG_REGEX = Regex("""<[^>]+>""")

    private val TREMBED_IFRAME_REGEX = Regex(
      """<iframe\b[^>]*\b(?:data-src|src)\s*=\s*["']([^"']*trembed=[^"']*)["']""",
      RegexOption.IGNORE_CASE
    )
    private val IFRAME_SRC_REGEX = Regex(
      """<iframe\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
      RegexOption.IGNORE_CASE
    )
    private val ABS_URL_REGEX = Regex(
      """https?://[^\s"'<>\\]+""",
      RegexOption.IGNORE_CASE
    )
    private val JSON_URL_REGEX = Regex(
      """"url"\s*:\s*"([^"]+)"""",
      RegexOption.IGNORE_CASE
    )

    private val ENTITY_REGEX = Regex(
      """&(amp|quot|apos|lt|gt|nbsp|#0*39|#x0*27|#8211|#8217);""",
      RegexOption.IGNORE_CASE
    )

    private val MEDIA_EXTENSIONS = listOf(
      ".mp4", ".m3u8", ".mkv", ".webm", ".ts", ".mov", ".avi"
    )
  }
}

/** Risultato di ricerca GuardaPlay: titolo, anno e URL dell'articolo. */
internal data class GuardaPlayEntry(
  val url: String,
  val title: String,
  val year: Int?
)

/** GET che restituisce (URL finale dopo redirect, corpo). Iniettabile nei test. */
internal fun interface GuardaPlayHttp {
  suspend fun get(url: String, referer: String?): Pair<String, String>
}
