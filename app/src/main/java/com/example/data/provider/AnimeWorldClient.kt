package com.example.data.provider

import android.util.Log
import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.ExtractorHttp
import com.example.data.streaming.extractors.VideoExtractor
import com.example.data.streaming.providers.HosterResolver
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Risoluzione delle sorgenti dal catalogo italiano **AnimeWorld**
 * (`animeworld.ac`).
 *
 * Il client è volutamente **separato** da [AnimeStreamProvider]: il provider
 * resta il punto di ingresso per i contenuti anime e può interrogare in
 * parallelo AnimeSaturn e AnimeWorld, fondendo le sorgenti.
 *
 * Catena verificata su pagine reali:
 *  1. `GET {base}/search?keyword={query}` -> markup con
 *     `<a href="/play/{slug}" class="name">Titolo</a>`;
 *  2. `GET {base}/play/{slug}` -> elenco episodi con
 *     `data-episode-num`, `data-id` (id server) e `data-episode-id`;
 *  3. `GET {base}/api/episode/info?id={data-id}&alt=0` -> JSON
 *     `{"grabber":"https://.../file.mp4"}`; per il server Streamtape il
 *     `grabber` è la pagina del player e viene delegato a [HosterResolver];
 *  4. gli URL diretti sono MP4 progressivi con supporto `Range`.
 *
 * Il client non conosce TMDB: riceve le query già costruite e i candidati
 * episodio (relativo e assoluto) dal provider.
 */
internal class AnimeWorldClient(
  private val baseUrls: List<String> = DEFAULT_BASE_URLS,
  private val http: AnimeWorldHttpGet = { url, referer, headers ->
    ExtractorHttp.get(
      url,
      referer,
      extraHeaders = headers,
      callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
    )
  },
  private val hosterExtractor: (String) -> VideoExtractor? = { url -> HosterResolver.extractorFor(url) }
) : AnimeWorldSource {

  override suspend fun resolve(
    queries: List<String>,
    episodeCandidates: List<Int>,
    year: Int?,
    isTv: Boolean
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    if (queries.isEmpty()) throw IOException("AnimeWorld: nessuna query disponibile")
    Log.d(TAG, "resolve queries=$queries year=$year isTv=$isTv")

    val failures = mutableListOf<String>()
    for (base in baseUrls) {
      try {
        val entries = search(base, queries)
        if (entries.isEmpty()) {
          failures += "$base -> nessun risultato"
          continue
        }
        val ranked = rankEntries(entries, queries)
        if (ranked.isEmpty()) {
          failures += "$base -> nessun risultato pertinente"
          continue
        }

        val merged = LinkedHashMap<String, StreamSource>()
        var resolvedEntries = 0
        for (entry in ranked.take(MAX_ENTRIES)) {
          try {
            val sources = resolveEntry(base, entry, episodeCandidates, isTv)
            sources.forEach { source ->
              val key = source.streamUrl?.takeIf { it.isNotBlank() } ?: return@forEach
              merged.putIfAbsent(key, source)
            }
            if (sources.isNotEmpty()) resolvedEntries++
            // ITA e SUB ITA sono entry distinte: due entry risolte coprono
            // entrambe le tracce audio senza moltiplicare le richieste.
            if (resolvedEntries >= MAX_RESOLVED_ENTRIES) break
          } catch (ex: CancellationException) {
            throw ex
          } catch (e: Exception) {
            failures += "${entry.title}: ${e.message}"
          }
        }
        if (merged.isNotEmpty()) return@withContext merged.values.toList()
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        failures += "$base -> ${e.message}"
      }
    }
    throw IOException("AnimeWorld non risolvibile (${failures.take(4).joinToString(" | ")})")
  }

  // --------------------------------------------------------------------- //
  //  Ricerca
  // --------------------------------------------------------------------- //

  private suspend fun search(base: String, queries: List<String>): List<AnimeWorldEntry> {
    val collected = LinkedHashMap<String, AnimeWorldEntry>()
    // Le query sono ordinate per rilevanza (titolo, titolo originale, stagione,
    // parola chiave). Si interrompe appena una query produce un match forte.
    for (query in queries.take(3)) {
      val url = "$base/search?keyword=${URLEncoder.encode(query, "UTF-8")}&page=1"
      val html = http(url, "$base/", HTML_HEADERS)
      val entries = parseSearch(html)
      entries.forEach { collected.putIfAbsent(it.slug, it) }
      if (entries.any { relevance(it, query) >= STRONG_MATCH }) break
    }
    return collected.values.toList()
  }

  private fun rankEntries(entries: List<AnimeWorldEntry>, queries: List<String>): List<AnimeWorldEntry> =
    entries
      .map { entry -> entry to (queries.maxOfOrNull { relevance(entry, it) } ?: 0) }
      .filter { (_, score) -> score >= MIN_RELEVANCE }
      .sortedByDescending { (_, score) -> score }
      .map { (entry, _) -> entry }

  /**
   * Estrae i risultati di ricerca.
   *
   * `internal` per i test JVM con HTML sintetico, senza rete.
   */
  internal fun parseSearch(html: String, baseUrl: String = "https://www.animeworld.ac"): List<AnimeWorldEntry> {
    val entries = LinkedHashMap<String, AnimeWorldEntry>()
    ANCHOR_REGEX.findAll(html).forEach { match ->
      val attrs = match.groupValues[1]
      val href = attr(HREF_REGEX, attrs) ?: return@forEach
      if (!href.startsWith("/play/")) return@forEach
      if (!hasClass(attr(CLASS_REGEX, attrs), "name")) return@forEach

      val title = decodeHtml(match.groupValues[2].replace(TAG_REGEX, "")).trim()
      if (title.isBlank()) return@forEach
      val slug = href.removePrefix("/play/").substringBefore('?').trim('/')
      if (slug.isBlank()) return@forEach
      val originalTitle = attr(JTITLE_REGEX, attrs)?.let(::decodeHtml)?.takeIf { it.isNotBlank() }
      val italian = isItalianTitle(title) || isItalianSlug(slug)
      entries.putIfAbsent(
        slug,
        AnimeWorldEntry(
          slug = slug,
          title = title,
          originalTitle = originalTitle,
          italian = italian,
          pageUrl = "$baseUrl/play/$slug"
        )
      )
    }
    return entries.values.toList()
  }

  /**
   * Punteggio di corrispondenza fra un risultato e una query.
   *
   * Il confronto "contains" ingenuo è troppo permissivo: per la query "Naruto"
   * farebbe passare anche "Boruto: Naruto Next Generations" o "Naruto
   * Shippuden". Qui il titolo deve coincidere (o differire solo per il marker
   * di lingua ITA/SUB ITA); un titolo con token extra viene penalizzato e,
   * sotto [MIN_RELEVANCE], scartato.
   */
  private fun relevance(entry: AnimeWorldEntry, query: String): Int {
    val q = normalize(query)
    if (q.isEmpty()) return 0
    val qTokens = q.split(' ').toSet()
    return listOfNotNull(entry.title, entry.originalTitle).maxOfOrNull { title ->
      val e = normalizeForMatch(title)
      when {
        e.isEmpty() -> 0
        e == q -> 100
        else -> {
          val eTokens = e.split(' ').toSet()
          val common = eTokens.intersect(qTokens).size
          when {
            common == 0 -> 0
            // La query copre il titolo e il titolo non ha token extra.
            common == qTokens.size && eTokens.size == common -> 90
            // Titolo più lungo (sequel/spin-off): non è la stessa opera.
            common == qTokens.size -> 40
            // La query contiene il titolo (es. "Naruto Season 2"): match parziale.
            common == eTokens.size -> 50
            else -> (60.0 * common / minOf(eTokens.size, qTokens.size)).toInt()
          }
        }
      }
    } ?: 0
  }

  /** Normalizza per il matching rimuovendo i marker di lingua (`ITA`/`SUB ITA`). */
  private fun normalizeForMatch(value: String): String =
    normalize(LANGUAGE_SUFFIX_REGEX.replace(value, " "))

  private fun normalize(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
      .replace(Regex("\\p{M}"), "")
      .lowercase(Locale.ROOT)
      .replace(Regex("[^a-z0-9]+"), " ")
      .trim()

  // --------------------------------------------------------------------- //
  //  Pagina serie/film -> episodi
  // --------------------------------------------------------------------- //

  private suspend fun resolveEntry(
    base: String,
    entry: AnimeWorldEntry,
    episodeCandidates: List<Int>,
    isTv: Boolean
  ): List<StreamSource> {
    val pageUrl = "$base/play/${entry.slug}"
    val html = http(pageUrl, "$base/", HTML_HEADERS)
    val page = parsePlayPage(html)

    // Un film richiesto come tale non deve risolvere una serie con lo stesso
    // titolo: la pagina con più episodi è una serie.
    if (!isTv && page.episodes.size > 1) {
      throw IOException("entry serie, richiesto un film")
    }

    val ordered = orderCandidates(episodeCandidates, page.episodes.keys.maxOrNull() ?: 0)
    for (number in ordered) {
      val ids = page.episodes[number] ?: continue
      val sources = resolveIds(base, pageUrl, ids, entry)
      if (sources.isNotEmpty()) return sources
    }

    // Film/special senza anchor episodio: il player espone l'id della sorgente.
    if (!isTv) {
      page.playerId?.let { id ->
        val sources = resolveIds(base, pageUrl, listOf(id), entry)
        if (sources.isNotEmpty()) return sources
      }
    }

    // Pagina con un solo episodio: è comunque l'episodio richiesto.
    if (page.episodes.size == 1) {
      val only = page.episodes.values.first()
      val sources = resolveIds(base, pageUrl, only, entry)
      if (sources.isNotEmpty()) return sources
    }

    throw IOException("episodio non trovato (S?E? candidates=$episodeCandidates)")
  }

  /**
   * Estrae gli episodi dalla pagina `/play/{slug}`.
   *
   * `internal` per i test JVM con HTML sintetico, senza rete.
   */
  internal fun parsePlayPage(html: String): AnimeWorldPage {
    val episodes = LinkedHashMap<Int, LinkedHashSet<String>>()
    EPISODE_ANCHOR_REGEX.findAll(html).forEach { match ->
      val attrs = match.groupValues[1]
      val number = match.groupValues[2].toIntOrNull() ?: return@forEach
      val id = attr(DATA_ID_REGEX, attrs)?.takeIf { it.isNotBlank() } ?: return@forEach
      episodes.getOrPut(number) { LinkedHashSet() }.add(id)
    }

    val playerTag = PLAYER_TAG_REGEX.find(html)?.value
    val playerId = playerTag?.let { attr(DATA_ID_REGEX, it) }?.takeIf { it.isNotBlank() }

    return AnimeWorldPage(
      episodes = episodes.mapValues { (_, ids) -> ids.toList() },
      playerId = playerId
    )
  }

  private fun orderCandidates(candidates: List<Int>, maxEpisode: Int): List<Int> {
    val unique = candidates.distinct()
    if (unique.size < 2) return unique
    val relative = unique[0]
    val absolute = unique[1]
    val continuous = maxEpisode >= absolute
    return if (continuous) listOf(absolute, relative) else listOf(relative, absolute)
  }

  // --------------------------------------------------------------------- //
  //  API player -> URL media
  // --------------------------------------------------------------------- //

  private suspend fun resolveIds(
    base: String,
    pageUrl: String,
    ids: List<String>,
    entry: AnimeWorldEntry
  ): List<StreamSource> {
    val sources = LinkedHashMap<String, StreamSource>()
    var lastError: Exception? = null
    for (id in ids.distinct()) {
      try {
        val apiUrl = "$base/api/episode/info?id=${URLEncoder.encode(id, "UTF-8")}&alt=0"
        val body = http(apiUrl, pageUrl, API_HEADERS)
        val grabber = GRABBER_REGEX.find(body)?.groupValues?.get(1)
          ?.replace("\\/", "/")
          ?.replace("&amp;", "&")
          ?.trim()
        if (grabber.isNullOrBlank() || !grabber.startsWith("http")) {
          throw IOException("risposta senza grabber")
        }
        Log.i(TAG, "grabber AnimeWorld: ${grabber.substringBefore('?').take(160)}")

        // Un file media diretto non passa dagli estrattori, anche se l'host
        // appartiene a un hoster noto (es. un MP4 servito da un mirror Dood).
        if (isDirectMediaUrl(grabber)) {
          sources.putIfAbsent(
            grabber,
            StreamSource(
              streamUrl = grabber,
              quality = "Auto",
              serverName = if (entry.italian) SERVER_NAME_ITA else SERVER_NAME,
              headers = mapOf(
                "Referer" to "$base/",
                "User-Agent" to ExtractorHttp.USER_AGENT
              ),
              isProgressive = !grabber.substringBefore('?').contains(".m3u8", ignoreCase = true),
              isItalian = entry.italian
            )
          )
          continue
        }

        // Un grabber che non è un file media e non ha un estrattore dedicato
        // (pagina HTML di un hoster sconosciuto) non va consegnato al player:
        // produrrebbe un errore di riproduzione invece di provare il server
        // successivo.
        val extractor = hosterExtractor(grabber)
          ?: throw IOException("grabber non media e hoster non supportato")
        val extracted = extractor.extract(grabber)
        if (extracted.isEmpty()) {
          throw IOException("hoster ${extractor.javaClass.simpleName} senza sorgenti")
        }
        extracted.forEach { source ->
          val url = source.streamUrl?.takeIf { it.isNotBlank() } ?: return@forEach
          sources.putIfAbsent(
            url,
            source.copy(
              serverName = if (entry.italian) "${source.serverName} (ITA)" else source.serverName,
              isItalian = entry.italian || source.isItalian
            )
          )
        }
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        lastError = e
      }
    }
    if (sources.isEmpty()) {
      throw IOException("nessuna sorgente AnimeWorld (${lastError?.message ?: "id non risolto"})")
    }
    return sources.values.toList()
  }

  // --------------------------------------------------------------------- //
  //  Parsing helpers
  // --------------------------------------------------------------------- //

  /** True se l'URL ha un'estensione media riconoscibile (nessun hoster da risolvere). */
  private fun isDirectMediaUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    return MEDIA_EXTENSIONS.any { path.endsWith(it) }
  }

  private fun attr(regex: Regex, attrs: String): String? =
    regex.find(attrs)?.groupValues?.getOrNull(1)?.trim()

  private fun hasClass(classes: String?, expected: String): Boolean =
    classes.orEmpty().split(Regex("\\s+")).any { it == expected }

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
        else -> match.value
      }
    }

  private fun isItalianTitle(title: String): Boolean {
    if (SUB_ITA_REGEX.containsMatchIn(title)) return false
    return ITA_TITLE_REGEX.containsMatchIn(title)
  }

  private fun isItalianSlug(slug: String): Boolean =
    ITA_SLUG_REGEX.containsMatchIn(slug)

  companion object {
    private const val TAG = "AnimeWorldClient"
    private const val SERVER_NAME = "AnimeWorld"
    private const val SERVER_NAME_ITA = "AnimeWorld ITA"
    private const val MAX_ENTRIES = 4
    private const val MAX_RESOLVED_ENTRIES = 2
    private const val STRONG_MATCH = 80

    /**
     * Soglia minima per considerare un risultato la stessa opera. Esclude
     * sequel/spin-off che contengono il titolo cercato come sottostringa
     * (es. "Boruto: Naruto Next Generations" per la query "Naruto").
     */
    private const val MIN_RELEVANCE = 80

    /**
     * Marker di lingua nei titoli AnimeWorld: "(ITA)", "ITA", "SUB ITA".
     * Il confine di parola evita di toccare titoli come "Itaewon Class".
     */
    private val LANGUAGE_SUFFIX_REGEX = Regex(
      """\(?\s*\b(?:sub\s*)?ita\b\s*\)?""",
      RegexOption.IGNORE_CASE
    )

    /** Estensioni accettate per un grabber già pronto per il player. */
    private val MEDIA_EXTENSIONS = listOf(
      ".mp4", ".m3u8", ".mkv", ".webm", ".ts", ".mov", ".avi"
    )

    private val DEFAULT_BASE_URLS = listOf(
      "https://www.animeworld.ac",
      "https://animeworld.ac"
    )

    internal val HTML_HEADERS = mapOf(
      "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    internal val API_HEADERS = mapOf(
      "Accept" to "application/json",
      "X-Requested-With" to "XMLHttpRequest"
    )

    private val ANCHOR_REGEX = Regex(
      """<a\b([^>]*)>(.*?)</a>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val HREF_REGEX = Regex("""\bhref\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val CLASS_REGEX = Regex("""\bclass\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val JTITLE_REGEX = Regex("""\bdata-jtitle\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("""<[^>]+>""")

    private val EPISODE_ANCHOR_REGEX = Regex(
      """<a\b([^>]*\bdata-episode-num\s*=\s*"(\d+)"[^>]*)>""",
      RegexOption.IGNORE_CASE
    )
    private val DATA_ID_REGEX = Regex("""\bdata-id\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val PLAYER_TAG_REGEX = Regex(
      """<[^>]*\bid\s*=\s*"player"[^>]*>""",
      RegexOption.IGNORE_CASE
    )

    private val GRABBER_REGEX = Regex(
      """"grabber"\s*:\s*"([^"]+)"""",
      RegexOption.IGNORE_CASE
    )

    private val ITA_TITLE_REGEX = Regex("""(?:\(|\b)ITA(?:\)|\b)""", RegexOption.IGNORE_CASE)
    private val SUB_ITA_REGEX = Regex("""\bSUB\s*ITA\b|\bSUBITA\b""", RegexOption.IGNORE_CASE)
    private val ITA_SLUG_REGEX = Regex("""(?:^|-)ita(?:$|[.-])""", RegexOption.IGNORE_CASE)

    private val ENTITY_REGEX = Regex(
      """&(amp|quot|apos|lt|gt|nbsp|#0*39|#x0*27);""",
      RegexOption.IGNORE_CASE
    )
  }
}

/** Contratto minimo usato da [AnimeStreamProvider] per fondere le sorgenti. */
internal interface AnimeWorldSource {
  suspend fun resolve(
    queries: List<String>,
    episodeCandidates: List<Int>,
    year: Int?,
    isTv: Boolean
  ): List<StreamSource>
}

internal data class AnimeWorldEntry(
  val slug: String,
  val title: String,
  val originalTitle: String?,
  val italian: Boolean,
  val pageUrl: String
)

internal data class AnimeWorldPage(
  val episodes: Map<Int, List<String>>,
  val playerId: String?
)

internal typealias AnimeWorldHttpGet =
  suspend (url: String, referer: String?, headers: Map<String, String>) -> String
