package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.data.streaming.diagnostics.record
import com.example.data.streaming.extractors.ExtractorHttp
import java.io.IOException
import java.net.URI
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Provider **StreamingCommunity** (o mirror attivo del catalogo italiano).
 *
 * Il sito ruota continuamente dominio: l'elenco [MIRRORS] viene interrogato in
 * ordine e ogni mirror non raggiungibile (DNS/403/404) viene saltato con log.
 *
 * Il deployment attuale è una SPA **Next.js** (i vecchi mirror WordPress sono
 * morti), quindi la ricerca passa dall'API JSON del sito:
 *  1. `GET /api/v1/web/archive?q={titolo anno}&limit=30&count=1` risponde
 *     `{"ok":true,"items":[{id,slug,title,year,kind,...}]}`. È una risposta
 *     **definitiva**: `items` presente e vuoto significa "titolo assente sul
 *     catalogo", quindi il mirror non viene più interrogato con i pattern HTML
 *     storici (era la causa dei 15 s bruciati per zero risultati);
 *  2. l'URL del titolo si compone dal pattern dichiarato dal sito stesso,
 *     `/titles/{id}-{slug}-streaming`;
 *  3. la pagina del player è `{titolo}/watching?s={stagione}&e={episodio}`, che
 *     espone gli URL media diretti oppure un iframe di player.
 *
 * Se l'API non risponde (deployment precedenti o dominio parcheggiato) si
 * ricade sui pattern storici `/?s=`, `/search?q=`, `/api/search?name=`, così il
 * provider resta compatibile con le installazioni più vecchie.
 *
 * I mirror restituiscono sorgenti con `Referer` = origin del titolo (molti CDN
 * italiani rispondono 403 senza).
 *
 * Se nessun mirror risponde, viene lanciata un'IOException con l'elenco dei
 * tentativi: in Logcat si vede esattamente quale dominio ha fallito e perché.
 */
class StreamingCommunityProvider(
  /**
   * Domini interrogati in ordine. Iniettabile per i test (un server locale al
   * posto del sito reale); in produzione resta [MIRRORS].
   */
  private val mirrors: List<String> = MIRRORS,
  private val diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE
) : StreamProvider {

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    val query = HosterResolver.buildQuery(title, year)
    if (query.isBlank()) throw IOException("Titolo non disponibile per la ricerca StreamingCommunity (tmdbId=$tmdbId)")

    val failures = mutableListOf<String>()
    // I mirror sono alias dello stesso catalogo: gli origin già interrogati non
    // vengono ripetuti (evita di moltiplicare la latenza per ogni alias).
    val attemptedOrigins = mutableSetOf<String>()
    for (mirror in mirrors) {
      // FIX TIMEOUT: le chiamate HTTP qui sotto sono BLOCCANTI (OkHttp execute, nessun
      // punto di sospensione), quindi withTimeout(15s) di StreamManager non interrompe
      // il loop. ensureActive() all'inizio di ogni mirror garantisce che, dopo la
      // cancellazione, nessun mirror aggiuntivo venga interrogato.
      currentCoroutineContext().ensureActive()
      val startedAt = System.currentTimeMillis()
      val mirrorId = StreamDiagnostics.hostId(mirror)
      try {
        val sources = resolveOnMirror(
          mirror = mirror,
          query = query,
          title = title,
          year = year,
          isTv = isTv,
          season = (season ?: 1).coerceAtLeast(1),
          episode = (episode ?: 1).coerceAtLeast(1),
          attemptedOrigins = attemptedOrigins
        )
        if (sources.isNotEmpty()) {
          Log.i(TAG, "sorgenti StreamingCommunity su $mirror: ${sources.size}")
          diagnostics.record(
            providerId = PROVIDER_ID,
            mirrorId = mirrorId,
            outcome = StreamAttemptOutcome.SUCCESS,
            durationMs = System.currentTimeMillis() - startedAt
          )
          return@withContext sources
        }
        failures += "$mirror -> nessuna sorgente"
        diagnostics.record(
          providerId = PROVIDER_ID,
          mirrorId = mirrorId,
          outcome = StreamAttemptOutcome.NO_RESULTS,
          durationMs = System.currentTimeMillis() - startedAt
        )
      } catch (ex: PlayerUnavailableException) {
        // Il player è hostato fuori dal catalogo (es. v.vidxgo.co): un blocco lì
        // non cambia cambiando mirror, e gli altri domini sono alias dello stesso
        // sito. Si interrompe invece di ripetere la stessa catena per ogni alias.
        Log.w(TAG, "player non disponibile su $mirror: ${ex.message}")
        failures += "$mirror -> ${ex.message}"
        diagnostics.record(
          providerId = PROVIDER_ID,
          mirrorId = mirrorId,
          outcome = StreamAttemptOutcome.EXTRACTION_FAILED,
          durationMs = System.currentTimeMillis() - startedAt,
          message = ex.message
        )
        break
      } catch (ex: CancellationException) {
        // FIX TIMEOUT: la cancellazione (incluso TimeoutCancellationException di
        // withTimeout) NON è un errore recuperabile: va rilanciata immediatamente,
        // altrimenti il provider continua a iterare i mirror oltre il timeout.
        diagnostics.record(
          providerId = PROVIDER_ID,
          mirrorId = mirrorId,
          outcome = StreamAttemptOutcome.CANCELLED,
          durationMs = System.currentTimeMillis() - startedAt,
          message = "ricerca annullata"
        )
        throw ex
      } catch (ex: Exception) {
        Log.w(TAG, "mirror $mirror: ${ex.message}")
        failures += "$mirror -> ${ex.message}"
        diagnostics.record(
          providerId = PROVIDER_ID,
          mirrorId = mirrorId,
          outcome = StreamDiagnostics.classify(ex),
          durationMs = System.currentTimeMillis() - startedAt,
          message = ex.message
        )
      }
    }
    throw IOException("StreamingCommunity non risolvibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnMirror(
    mirror: String,
    query: String,
    title: String?,
    year: Int?,
    isTv: Boolean,
    season: Int,
    episode: Int,
    attemptedOrigins: MutableSet<String>
  ): List<StreamSource> {
    // --- 1) ricerca ---------------------------------------------------------
    val pageUrl = when (val outcome = locateTitle(mirror, query, title, year)) {
      is SearchOutcome.Found -> outcome.pageUrl
      // Il deployment è vivo e risponde, ma il titolo non è a catalogo: errore
      // definitivo, i pattern HTML storici non aggiungerebbero nulla.
      SearchOutcome.NoTitle -> throw IOException("titolo assente dal catalogo per '$query'")
      SearchOutcome.NotADeployment -> throw IOException("nessuna pagina titolo per '$query'")
    }
    Log.i(TAG, "GET TITOLO $pageUrl")

    val origin = originOf(pageUrl).ifBlank { mirror.trimEnd('/') }
    if (!attemptedOrigins.add(origin)) {
      throw IOException("alias di $origin, già interrogato")
    }
    val referer = "$origin/"
    // Header anti-hotlinking richiesti dai CDN del catalogo: Referer e Origin
    // dell'origin effettivo (dopo redirect), più lo User-Agent desktop. Vengono
    // propagati a StreamSource e quindi alle richieste Media3 (manifest HLS e
    // segmenti compresi).
    val headers = mapOf(
      "Referer" to referer,
      "Origin" to origin,
      "User-Agent" to ExtractorHttp.USER_AGENT
    )
    val out = linkedMapOf<String, StreamSource>()
    var playerFailure: String? = null

    // --- 2) pagina del player ----------------------------------------------
    // Il player del deployment Next.js sta su {titolo}/watching?s=&e=; per i
    // film non serve la coppia stagione/episodio (il sito usa sempre 1/1).
    val watchUrl = if (isTv) "$pageUrl/watching?s=$season&e=$episode" else "$pageUrl/watching"
    val watchHtml = tryGet(watchUrl, referer)
    if (watchHtml != null) {
      collectDirectMedia(watchHtml, headers, out)
      if (out.isEmpty()) {
        val embedUrl = playerEmbedUrl(watchHtml, watchUrl)
        if (embedUrl != null) {
          Log.i(TAG, "player embed: $embedUrl")
          playerFailure = collectFromPlayerEmbed(embedUrl, referer, out)
        }
      }
    }

    // --- 3) pagina titolo (markup con sorgenti dirette o embed hoster) ------
    if (out.isEmpty()) {
      val pageHtml = tryGet(pageUrl, referer) ?: throw playerUnavailableOr(
        playerFailure,
        "nessuna pagina titolo per '$query'"
      )
      collectDirectMedia(pageHtml, headers, out)

      if (out.isEmpty()) {
        val embedUrls = embedUrlsFrom(pageHtml, origin)
        if (embedUrls.isNotEmpty()) {
          Log.i(TAG, "embed trovati (${embedUrls.size}), delego agli estrattori")
          val extracted = HosterResolver.extractFromLinks(
            rawUrls = embedUrls.map { "embed" to it },
            referer = pageUrl,
            onResolved = { resolved -> Log.i(TAG, "embed risolto: $resolved") },
            providerId = PROVIDER_ID,
            mirrorId = StreamDiagnostics.hostId(mirror),
            diagnostics = diagnostics
          )
          extracted.forEach { source -> source.streamUrl?.let { out.putIfAbsent(it, source) } }
        }
      }
    }

    if (out.isEmpty()) {
      throw playerUnavailableOr(playerFailure, "pagina titolo senza sorgenti leggibili")
    }
    return out.values.toList()
  }

  /** Se il fallimento è del player esterno il mirror non è la causa: errore definitivo. */
  private fun playerUnavailableOr(playerFailure: String?, fallback: String): IOException =
    if (playerFailure != null) PlayerUnavailableException(playerFailure) else IOException(fallback)

  /**
   * Individua la pagina del titolo sul mirror.
   *
   * Prima l'API JSON (una sola richiesta, risposta definitiva anche quando è
   * vuota), poi i pattern HTML dei deployment precedenti.
   */
  private suspend fun locateTitle(
    mirror: String,
    query: String,
    title: String?,
    year: Int?
  ): SearchOutcome {
    val archiveUrl = "$mirror$ARCHIVE_PATH?q=${enc(query)}&limit=$ARCHIVE_LIMIT&count=1"
    // getWithFinalUrl: i mirror alias rispondono 302 verso il dominio canonico e
    // l'origin finale serve per comporre l'URL del titolo e il Referer.
    val archive = try {
      ExtractorHttp.getWithFinalUrl(archiveUrl, referer = "$mirror/")
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      Log.i(TAG, "ricerca archive non disponibile: $archiveUrl (${ex.message})")
      null
    }

    if (archive != null && isArchiveEnvelope(archive.second)) {
      val candidates = parseArchiveItems(archive.second)
      val best = pickBestTitle(candidates, title, year)
      if (best == null) {
        Log.i(TAG, "archive: nessun titolo per '$query'")
        return SearchOutcome.NoTitle
      }
      val origin = originOf(archive.first).ifBlank { mirror.trimEnd('/') }
      return SearchOutcome.Found(titleUrlFor(origin, best))
    }

    return searchWithLegacyPatterns(mirror, query, title)
  }

  /** Pattern di ricerca HTML dei deployment StreamingCommunity precedenti. */
  private suspend fun searchWithLegacyPatterns(
    mirror: String,
    query: String,
    title: String?
  ): SearchOutcome {
    val searchPatterns = listOf(
      "$mirror/?s=${enc(query)}",
      "$mirror/search?q=${enc(query)}",
      "$mirror/api/search?name=${enc(query)}"
    )
    for (pattern in searchPatterns) {
      // FIX TIMEOUT: dopo la cancellazione di withTimeout non vengono provati
      // ulteriori pattern di ricerca.
      currentCoroutineContext().ensureActive()
      val body = try {
        ExtractorHttp.get(pattern, referer = "$mirror/")
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.i(TAG, "ricerca non disponibile: $pattern (${ex.message})")
        continue
      }
      // Risposta JSON: estrae il primo risultato con URL
      if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
        val url = JSON_URL_REGEX.findAll(body)
          .map { it.groupValues[1].replace("\\/", "/") }
          .firstOrNull { it.startsWith("http") || it.startsWith("/") }
        if (url != null) {
          val pageUrl = if (url.startsWith("http")) url else mirror + url
          val pageHtml = tryGet(pageUrl, "$mirror/")
          if (pageHtml != null) return SearchOutcome.Found(pageUrl)
        }
        continue
      }
      // HTML: sceglie il risultato con il miglior punteggio sul titolo
      val candidate = pickArticle(body, title, mirror) ?: continue
      val pageHtml = tryGet(candidate, "$mirror/")
      if (pageHtml != null) return SearchOutcome.Found(candidate)
    }
    return SearchOutcome.NotADeployment
  }

  /** GET tollerante: ritorna null su errore HTTP/DNS (il chiamante decide il fallback). */
  private suspend fun tryGet(url: String, referer: String?): String? = try {
    ExtractorHttp.get(url, referer = referer).takeIf { it.isNotEmpty() }
  } catch (ex: CancellationException) {
    throw ex
  } catch (ex: Exception) {
    Log.i(TAG, "GET fallita: $url (${ex.message})")
    null
  }

  /**
   * Aggiunge a [out] gli URL `.m3u8`/`.mp4` presenti nel markup.
   *
   * Nota: la lista resta vuota se la pagina contiene solo l'embed del player.
   */
  private fun collectDirectMedia(
    html: String,
    headers: Map<String, String>,
    out: MutableMap<String, StreamSource>
  ) {
    val normalized = html.replace("\\/", "/")
    MEDIA_REGEX.findAll(normalized).forEach { m ->
      val url = m.groupValues[1]
      out.putIfAbsent(
        url,
        StreamSource(
          streamUrl = url,
          // La risoluzione MP4 non è verificata (nessun probe): non dichiarare "1080p".
          quality = "Auto",
          serverName = SERVER_NAME,
          headers = headers
        )
      )
    }
  }

  /**
   * Risolve l'embed del player: molte pagine non contengono l'URL media ma solo
   * l'iframe del player, che va aperto per estrarre la sorgente.
   *
   * @return il motivo del fallimento, oppure null se [out] è stato popolato.
   */
  private suspend fun collectFromPlayerEmbed(
    embedUrl: String,
    referer: String,
    out: MutableMap<String, StreamSource>
  ): String? {
    val host = hostOf(embedUrl)
    val body = try {
      ExtractorHttp.get(embedUrl, referer = referer)
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      // Es. HTTP 403: il player di StreamingCommunity (v.vidxgo.co) risponde
      // dietro un gate anti-bot Cloudflare. Il limite viene riportato così com'è
      // osservato: nessun tentativo di aggirare la protezione.
      return "player $host: ${ex.message}"
    }
    // Le sorgenti scoperte nel player appartengono al suo origin, non a quello
    // del catalogo: i CDN del player verificano il Referer della pagina che le
    // ospita. Per le sorgenti del catalogo restano invece gli header dell'origin
    // del titolo (vedi [headers] in [resolveOnMirror]).
    collectDirectMedia(body, antiHotlinkHeaders(embedUrl), out)
    if (out.isNotEmpty()) return null
    return "player $host: nessuna sorgente diretta (${body.length} byte)"
  }

  /** Referer/Origin/User-Agent coerenti con l'origin della pagina indicata. */
  internal fun antiHotlinkHeaders(pageUrl: String): Map<String, String> {
    val origin = originOf(pageUrl)
    return if (origin.isBlank()) {
      mapOf("User-Agent" to ExtractorHttp.USER_AGENT)
    } else {
      mapOf(
        "Referer" to "$origin/",
        "Origin" to origin,
        "User-Agent" to ExtractorHttp.USER_AGENT
      )
    }
  }

  /** Sceglie il risultato di ricerca corrispondente al titolo (pattern storici). */
  private fun pickArticle(searchHtml: String, title: String?, baseUrl: String): String? {
    val candidates = ARTICLE_REGEX.findAll(searchHtml)
      .map { it.groupValues[1] }
      .distinct()
      .filter { !NON_ARTICLE_REGEX.containsMatchIn(it) }
      .map { if (it.startsWith("http")) it else baseUrl.trimEnd('/') + it }
      .toList()
    if (candidates.isEmpty()) return null
    if (title.isNullOrBlank()) return candidates.first()
    val scored = candidates.map { it to HosterResolver.matchScore(slugOf(it), title) }
    return scored.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
  }

  /** Embed degli hoster supportati presenti nella pagina titolo. */
  private fun embedUrlsFrom(html: String, origin: String): List<String> {
    val normalized = html.replace("\\/", "/")
    return (IFRAME_REGEX.findAll(normalized).map { it.groupValues[1] } +
      DATA_SRC_REGEX.findAll(normalized).map { it.groupValues[1] })
      .map { raw -> unescapeHtmlEntities(raw).let { if (it.startsWith("http")) it else origin + it } }
      .filter { HosterResolver.isHosterUrl(it) }
      .distinct()
      .toList()
  }

  private fun slugOf(url: String): String = url.trimEnd('/').substringAfterLast('/').lowercase()

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    val scheme = uri.scheme
    val host = uri.host
    if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
      ""
    } else {
      // La porta va conservata quando non è quella di default dello schema:
      // altrimenti l'URL del titolo e il Referer puntano alla porta sbagliata.
      val port = uri.port
      val isDefaultPort = port == -1 ||
        (scheme.equals("http", true) && port == 80) ||
        (scheme.equals("https", true) && port == 443)
      if (isDefaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }
  } catch (e: Exception) {
    ""
  }

  private fun hostOf(url: String): String = try {
    URI(url).host ?: url
  } catch (e: Exception) {
    url
  }

  private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

  companion object {
    private const val TAG = "StreamingCommunityProvider"

    /** Identificativo stabile nella catena diagnostica. */
    internal const val PROVIDER_ID = "StreamingCommunity"
    const val SERVER_NAME = "StreamingCommunity"

    /** Percorso dell'API di ricerca dell'archive Next.js. */
    private const val ARCHIVE_PATH = "/api/v1/web/archive"
    private const val ARCHIVE_LIMIT = 30

    /**
     * Domini provati in ordine (il catalogo cambia TLD molto spesso): ogni
     * dominio non raggiungibile viene saltato e loggato, senza bloccare gli
     * altri provider dello StreamManager.
     *
     * Verificati il 2026-10-21 con richieste reali: i precedenti
     * `streamingcommunity.{click,buzz,org}` rispondono 200 con pagine di
     * parcheggio dominio ("Buy this domain") e gli altri non risolvono più il
     * DNS, mentre questi servono il catalogo reale (`200`, titolo
     * "Streaming-Community", API archive funzionante). `coffee`, `site` e `win`
     * sono alias che rispondono `302` verso il dominio canonico `farm`.
     */
    private val MIRRORS = listOf(
      "https://streaming-community.farm",
      "https://streaming-community.coffee",
      "https://streaming-community.site",
      "https://streaming-community.win"
    )

    private val ARTICLE_REGEX = Regex("""(?:href|"url")\s*=?\s*["']((?:https?://[^"']+)?/[a-z0-9\-]{3,80}/)["']""", RegexOption.IGNORE_CASE)
    private val NON_ARTICLE_REGEX = Regex(
      """(wp-content|wp-includes|/fonts/|/css/|/js/|/images/|/img/|/assets/|/static/|/uploads/|/category/|/tag/|/page/|/feed/|/comments?/|/login/|/register/|/author/|/search/)""",
      RegexOption.IGNORE_CASE
    )
    private val MEDIA_REGEX = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
    private val IFRAME_REGEX = Regex("""<iframe[^>]{0,400}?src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val DATA_SRC_REGEX = Regex("""data-(?:src|url)="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val JSON_URL_REGEX = Regex(""""(?:url|link|slug)"\s*:\s*"([^"]+)"""")

    /** L'iframe del trailer convive con quello del player: va scartato. */
    private val TRAILER_URL_REGEX = Regex("""content=trailer|player=false""", RegexOption.IGNORE_CASE)

    // --- parsing dell'API archive (regex: nei test JVM org.json è uno stub) ---
    private val ITEMS_KEY_REGEX = Regex(""""items"\s*:""")
    private val ID_REGEX = Regex(""""id"\s*:\s*"?(\d+)""")
    private val SLUG_REGEX = Regex("""(?<![A-Za-z])"slug"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val TITLE_REGEX = Regex("""(?<![A-Za-z])"title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val YEAR_REGEX = Regex("""(?<![A-Za-z])"year"\s*:\s*(\d{4})""")
    private val KIND_REGEX = Regex("""(?<![A-Za-z])"kind"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val IMDB_REGEX = Regex("""(?<![A-Za-z])"imdbId"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /**
     * true se il corpo è la busta JSON dell'archive (`{"ok":true,"items":[...]}`).
     *
     * Distingue una risposta **definitiva** (anche con `items` vuoto) da una
     * pagina HTML di cortesia/parcheggio, per la quale si ricade sui pattern
     * storici invece di dichiarare "titolo assente".
     */
    internal fun isArchiveEnvelope(body: String): Boolean {
      val trimmed = body.trimStart()
      return trimmed.startsWith("{") && ITEMS_KEY_REGEX.containsMatchIn(trimmed)
    }

    /** Voci restituite da `/api/v1/web/archive`. */
    internal fun parseArchiveItems(body: String): List<TitleCandidate> {
      val arrayBody = jsonArrayBody(body, "items") ?: return emptyList()
      return splitJsonObjects(arrayBody).mapNotNull { obj ->
        val id = ID_REGEX.find(obj)?.groupValues?.get(1) ?: return@mapNotNull null
        val slug = SLUG_REGEX.find(obj)?.groupValues?.get(1)?.let(::unescapeJson)
        if (slug.isNullOrBlank()) return@mapNotNull null
        TitleCandidate(
          id = id,
          slug = slug,
          title = TITLE_REGEX.find(obj)?.groupValues?.get(1)?.let(::unescapeJson).orEmpty(),
          year = YEAR_REGEX.find(obj)?.groupValues?.get(1)?.toIntOrNull(),
          kind = KIND_REGEX.find(obj)?.groupValues?.get(1)?.let(::unescapeJson).orEmpty(),
          imdbId = IMDB_REGEX.find(obj)?.groupValues?.get(1)?.let(::unescapeJson).orEmpty()
        )
      }
    }

    /** URL del titolo secondo il pattern dichiarato dal sito: `/titles/{id}-{slug}-streaming`. */
    internal fun titleUrlFor(origin: String, candidate: TitleCandidate): String =
      "${origin.trimEnd('/')}/titles/${candidate.id}-${candidate.slug}-streaming"

    /**
     * Migliore corrispondenza per il titolo richiesto.
     *
     * L'API filtra già per rilevanza (`items` vuoto per una query senza
     * riscontri), quindi il punteggio serve solo a disambiguare: se nessuna voce
     * ha parole in comune si usa la prima, che è la risposta dell'API.
     */
    internal fun pickBestTitle(
      candidates: List<TitleCandidate>,
      title: String?,
      year: Int?
    ): TitleCandidate? {
      if (candidates.isEmpty()) return null
      if (title.isNullOrBlank()) return candidates.first()
      val best = candidates.maxByOrNull { scoreOf(it, title, year) } ?: return null
      return if (scoreOf(best, title, year) > 0) best else candidates.first()
    }

    /** Punteggio 0 = nessuna parola in comune (l'anno non basta da solo). */
    internal fun scoreOf(candidate: TitleCandidate, title: String, year: Int?): Int {
      val base = maxOf(
        HosterResolver.matchScore(candidate.slug, title),
        HosterResolver.matchScore(candidate.title, title)
      )
      if (base <= 0) return 0
      // L'anno di TMDB e quello del catalogo possono differire di 1 (uscita
      // festival vs distribuzione): si premia la vicinanza, non l'uguaglianza.
      val yearBonus = if (year != null && candidate.year != null && abs(candidate.year - year) <= 1) 25 else 0
      return base + yearBonus
    }

    /**
     * Iframe del player della pagina `/watching`.
     *
     * Osservato sul deployment attuale: `/watching` contiene il solo iframe del
     * player, mentre il trailer (`content=trailer&player=false`) sta nella pagina
     * titolo. L'esclusione del trailer è quindi una guardia: evita di trattare
     * come sorgente del titolo il video promozionale.
     */
    internal fun playerEmbedUrl(html: String, baseUrl: String): String? {
      val normalized = html.replace("\\/", "/")
      return IFRAME_REGEX.findAll(normalized)
        .map { unescapeHtmlEntities(it.groupValues[1]) }
        .filterNot { TRAILER_URL_REGEX.containsMatchIn(it) }
        .map { raw -> if (raw.startsWith("http")) raw else resolveAgainst(baseUrl, raw) }
        .firstOrNull { it.startsWith("http") }
    }

    /**
     * Il markup HTML scrive `&amp;` nei parametri degli iframe: senza questo
     * unescape l'URL richiesto al player è malformato.
     */
    internal fun unescapeHtmlEntities(value: String): String {
      if ('&' !in value) return value
      return value
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&#x26;", "&")
    }

    private fun resolveAgainst(baseUrl: String, path: String): String = try {
      URI(baseUrl).resolve(path).toString()
    } catch (e: Exception) {
      baseUrl
    }

    /** Corpo dell'array JSON associato a [key], oppure null se assente. */
    private fun jsonArrayBody(body: String, key: String): String? {
      val keyAt = body.indexOf("\"$key\"")
      if (keyAt < 0) return null
      val open = body.indexOf('[', keyAt)
      if (open < 0) return null
      var depth = 0
      var inString = false
      var escaped = false
      for (i in open until body.length) {
        val c = body[i]
        when {
          escaped -> escaped = false
          c == '\\' -> escaped = true
          c == '"' -> inString = !inString
          inString -> {}
          c == '[' -> depth++
          c == ']' -> {
            depth--
            if (depth == 0) return body.substring(open + 1, i)
          }
        }
      }
      return null
    }

    /** Oggetti di primo livello contenuti in un corpo di array JSON. */
    private fun splitJsonObjects(arrayBody: String): List<String> {
      val out = mutableListOf<String>()
      var depth = 0
      var start = -1
      var inString = false
      var escaped = false
      for (i in arrayBody.indices) {
        val c = arrayBody[i]
        when {
          escaped -> escaped = false
          c == '\\' -> escaped = true
          c == '"' -> inString = !inString
          inString -> {}
          c == '{' -> {
            if (depth == 0) start = i
            depth++
          }
          c == '}' -> {
            depth--
            if (depth == 0 && start >= 0) {
              out += arrayBody.substring(start, i + 1)
              start = -1
            }
          }
        }
      }
      return out
    }

    /** Unescape JSON minimale (necessario per i titoli con virgolette/apostrofi). */
    internal fun unescapeJson(value: String): String {
      if ('\\' !in value) return value
      val sb = StringBuilder(value.length)
      var i = 0
      while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
          when (val next = value[i + 1]) {
            '"' -> { sb.append('"'); i += 2 }
            '\\' -> { sb.append('\\'); i += 2 }
            '/' -> { sb.append('/'); i += 2 }
            'n', 'r', 't' -> { sb.append(' '); i += 2 }
            'u' -> {
              val hex = value.substring(i + 2, minOf(i + 6, value.length))
              val code = hex.toIntOrNull(16)
              if (code != null && hex.length == 4) {
                sb.append(code.toChar())
                i += 6
              } else {
                sb.append(next)
                i += 2
              }
            }
            else -> { sb.append(next); i += 2 }
          }
        } else {
          sb.append(c)
          i++
        }
      }
      return sb.toString()
    }
  }
}

/** Esito della ricerca del titolo su un mirror. */
internal sealed class SearchOutcome {
  class Found(val pageUrl: String) : SearchOutcome()

  /** Il deployment risponde e il titolo non è a catalogo: esito definitivo. */
  data object NoTitle : SearchOutcome()

  /** Nessun pattern di ricerca ha prodotto un risultato: mirror non-SC o markup cambiato. */
  data object NotADeployment : SearchOutcome()
}

/**
 * Il titolo è stato trovato ma il player esterno non ha restituito sorgenti
 * (protetto, irraggiungibile o vuoto).
 *
 * È un esito **indipendente dal mirror**: il player è hostato fuori dal catalogo,
 * quindi non ha senso ripetere la stessa catena sugli alias dello stesso sito.
 */
internal class PlayerUnavailableException(message: String) : IOException(message)

/** Voce dell'API archive di StreamingCommunity. */
internal data class TitleCandidate(
  val id: String,
  val slug: String,
  val title: String,
  val year: Int?,
  val kind: String,
  val imdbId: String
)
