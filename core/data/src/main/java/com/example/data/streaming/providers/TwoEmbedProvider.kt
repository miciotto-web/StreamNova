package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.data.streaming.diagnostics.record
import com.example.data.streaming.extractors.ExtractorHttp
import com.example.data.streaming.extractors.PlayerSourceParser
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provider aggregatore **2Embed** (2embed.cc / 2embed.stream).
 *
 * Catena verificata su un titolo reale (Inception, TMDB 27205):
 *  1. `GET {base}/embed/{tmdb}` (film) o `/embed/tv/{tmdb}/{s}/{e}` (serie) —
 *     2embed.cc contiene l'iframe del partner `vidsrc.buzz`;
 *  2. `GET https://vidsrc.buzz/embed/movie/{tmdb}` → variabile JS `var Q = {...}`
 *     con `type`, `id` (IMDB), `t` (token) e `ssr.servers`;
 *  3. `GET {origin}/pl/api.php?a=sources&type=&id=&s=&e=&t=` → JSON con i server
 *     (`ref` + `name`) — il percorso è relativo al tag `<base href="/pl/">`;
 *  4. per ogni server `GET {origin}/pl/api.php?a=play&ref=...&stesso qs`
 *     → `{"url":"/_stream?id=...","type":"hls"}` (alcuni server rispondono
 *     `unavailable`: si provano tutti in ordine);
 *  5. la master playlist viene validata (`#EXTM3U`) e se ne legge la risoluzione
 *     massima per etichettare la qualità.
 *
 * Header: `Referer` della pagina embed **finale** (dopo redirect) + `Origin`
 * dello stesso origin + User-Agent desktop.
 *
 * Budget: ogni richiesta HTTP ha un `callTimeout` di 5 s; ogni tentativo
 * indipendente (candidato embed, server, playlist) ha lo stesso limite. Il
 * tentativo complessivo su un mirror è limitato a 12 s e resta sotto il budget
 * globale di 15 s di [com.example.data.streaming.StreamManager]. Non è una
 * promessa che più mirror da 5 s completino tutti entro 15 s: il timeout
 * globale del chiamante resta l'autorità finale.
 *
 * Diagnostica: ogni tentativo per mirror viene registrato nel [StreamDiagnosticSink]
 * (come per i cataloghi CB01/Eurostreaming): solo l'host del mirror, mai URL
 * completi, token o credenziali (il messaggio è sanificato dall'estensione `record`).
 */
class TwoEmbedProvider internal constructor(
  private val baseUrls: List<String>,
  private val http: TwoEmbedHttp,
  private val diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE
) : StreamProvider {

  /** Costruttore pubblico storico: usa i mirror reali e OkHttp. */
  constructor(diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE) : this(
    BASE_URLS,
    TwoEmbedHttp { url, referer, timeoutMs ->
      ExtractorHttp.getWithFinalUrl(url, referer, callTimeoutMs = timeoutMs)
    },
    diagnostics
  )

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = getStreams(tmdbId, isTv, season, episode, title, year, imdbId = null)

  /**
   * Variante con IMDb ID: usato come fallback quando `var Q` non contiene un
   * identificativo `tt...` (o contiene solo un ID numerico). La firma a sei
   * parametri resta quella del contratto [StreamProvider].
   */
  suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?,
    imdbId: String?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    if (tmdbId <= 0) throw IOException("TMDB ID non valido: $tmdbId")
    val s = (season ?: 1).coerceAtLeast(1)
    val e = (episode ?: 1).coerceAtLeast(1)
    val fallbackImdb = imdbId?.trim()?.takeIf { TT_ID_REGEX.matches(it) }

    val failures = mutableListOf<String>()
    for (base in baseUrls) {
      currentCoroutineContext().ensureActive()
      val startedAt = System.currentTimeMillis()
      val mirrorId = StreamDiagnostics.hostId(base)
      try {
        val sources = withTimeoutOrNull(MIRROR_BUDGET_MS) {
          resolveOnBase(base, tmdbId, isTv, s, e, fallbackImdb)
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
          Log.i(TAG, "sorgenti 2Embed su $base: ${sources.size}")
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
      } catch (ex: Exception) {
        Log.w(TAG, "fallback mirror $base: ${ex.message}")
        failures += "$base -> ${ex.message}"
        diagnostics.record(
          providerId = PROVIDER_ID,
          mirrorId = mirrorId,
          outcome = StreamDiagnostics.classify(ex),
          durationMs = System.currentTimeMillis() - startedAt,
          message = ex.message
        )
      }
    }
    throw IOException("2Embed non risolvibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnBase(
    base: String,
    tmdbId: Int,
    isTv: Boolean,
    s: Int,
    e: Int,
    fallbackImdb: String?
  ): List<StreamSource> {
    // --- 1) pagina embed (i pattern variano tra mirror) -------------------
    val embedCandidates = if (isTv) {
      // `embedtv` è l'endpoint TV documentato e verificato: espone iframe con
      // `embed/tv/{tmdb}/{s}/{e}`. Alcuni mirror rispondono a
      // `/embed/tv/{tmdb}/{s}/{e}` con un player **movie** e un id concatenato
      // (es. 1399+1+1 = 139911), che riproduce un titolo diverso: quel path
      // resta solo come ultimo candidato e la pagina viene validata.
      listOf(
        "$base/embedtv/$tmdbId&s=$s&e=$e",
        "$base/embedtv/$tmdbId?s=$s&e=$e",
        "$base/embed/tv/$tmdbId/$s/$e"
      )
    } else {
      listOf("$base/embed/movie/$tmdbId", "$base/embed/$tmdbId")
    }

    var embedUrl = ""
    var embedFinalUrl = ""
    var embedHtml: String? = null
    for (candidate in embedCandidates) {
      currentCoroutineContext().ensureActive()
      val fetched = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
          http.get(candidate, "$base/", ExtractorHttp.MIRROR_CALL_TIMEOUT_MS)
        }
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.i(TAG, "embed non disponibile: $candidate (${ex.message})")
        null
      }
      if (fetched == null) {
        Log.i(TAG, "embed timeout: $candidate")
        continue
      }
      // Una HTTP 200 non basta: senza un player/iframe riconoscibile la pagina
      // è un errore/placeholder e si prova il candidato successivo.
      if (!isPlayerPage(fetched.second)) {
        Log.i(TAG, "embed senza player riconoscibile: $candidate")
        continue
      }
      // Per le serie il path `/embed/tv/...` può restituire il player movie con
      // l'id concatenato (contenuto sbagliato): si scarta e si prova l'endpoint
      // TV successivo invece di consegnare al player un episodio diverso.
      if (isTv && isBrokenConcatenatedTvPage(fetched.second, tmdbId, s, e)) {
        Log.i(TAG, "embed TV con id movie concatenato (scartato): $candidate")
        continue
      }
      if (isTv && !pageMatchesRequestedEpisode(fetched.second, s, e)) {
        Log.i(TAG, "embed TV di un episodio diverso da S${s}E$e (scartato): $candidate")
        continue
      }
      embedUrl = candidate
      embedFinalUrl = fetched.first
      embedHtml = fetched.second
      break
    }
    val page = embedHtml ?: throw IOException("nessuna pagina embed 2Embed per TMDB $tmdbId (S=$s E=$e)")
    Log.i(TAG, "GET EMBED $embedUrl (finale $embedFinalUrl)")

    // --- 2) iframe del partner VidSrc ------------------------------------
    // La pagina contiene più iframe: uno autoreferenziale del mirror, un
    // placeholder `src="about:blank"` con il player reale in `data-src` (lazy
    // load) e il partner VidSrc. Si preferisce VidSrc, che espone l'API
    // `/pl/api.php` verificata; se la sua catena fallisce si provano gli altri
    // iframe già estratti e validati.
    val iframeCandidates = playerIframeUrls(page, embedFinalUrl)
      .take(MAX_IFRAME_ATTEMPTS)
    if (iframeCandidates.isEmpty()) {
      // Pagina player valida senza iframe: alcuni mirror espongono il media
      // direttamente nel markup/script.
      val direct = directSources(page, embedFinalUrl, embedFinalUrl)
      if (direct.isNotEmpty()) return direct
      throw IOException("nessun iframe player nella pagina 2Embed")
    }

    val failures = mutableListOf<String>()
    for (playerUrl in iframeCandidates) {
      currentCoroutineContext().ensureActive()
      Log.i(TAG, "iframe player: $playerUrl")
      try {
        val sources = if (isVidSrcUrl(playerUrl)) {
          vidSrcSources(playerUrl, isTv, s, e, fallbackImdb)
        } else {
          sourcesFromIframe(playerUrl, embedFinalUrl)
        }
        if (sources.isNotEmpty()) return sources
        failures += "$playerUrl -> nessuna sorgente"
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.w(TAG, "iframe fallito ($playerUrl): ${ex.message}")
        failures += "$playerUrl -> ${ex.message}"
      }
    }
    // Ultima risorsa: sorgenti dirette nella pagina embed validata.
    val direct = directSources(page, embedFinalUrl, embedFinalUrl)
    if (direct.isNotEmpty()) return direct
    throw IOException("nessun iframe 2Embed risolvibile (${failures.joinToString(" | ")})")
  }

  /**
   * Iframe player della pagina embed: VidSrc per primo, poi gli altri.
   *
   * Per le serie vengono scartati i riferimenti con l'id movie concatenato
   * (`/embed/movie/139911`) generati dal path TV rotto di alcuni mirror: sono
   * contenuti diversi dall'episodio richiesto.
   */
  internal fun playerIframeUrls(page: String, baseUrl: String): List<String> =
    (IFRAME_REGEX.findAll(page).map { it.groupValues[1] } +
      // data-src: solo URL che sembrano player/embed (evita immagini lazy)
      DATA_SRC_REGEX.findAll(page).map { it.groupValues[1] }.filter { PLAYERISH_REGEX.containsMatchIn(it) })
      .map { it.replace("&amp;", "&") }
      .filter { !isNonPlayerUrl(it) }
      // Scarta i placeholder (about:blank, javascript:, data:...)
      .filter { it.startsWith("http") || it.startsWith("//") || it.startsWith("/") }
      .mapNotNull { absolutize(it, baseUrl) }
      .distinct()
      .sortedByDescending { isVidSrcUrl(it) }
      .toList()

  /**
   * Fallback generico: URL media diretti nella pagina dell'iframe, seguendo
   * anche gli iframe annidati (es. `streamsrcs.2embed.cc/vcr?...` che contiene
   * un iframe relativo al player reale).
   */
  private suspend fun sourcesFromIframe(playerUrl: String, embedFinalUrl: String): List<StreamSource> {
    val sources = collectFromPlayerPage(
      url = playerUrl,
      referer = embedFinalUrl,
      visited = HashSet(),
      depth = 0
    )
    if (sources.isEmpty()) throw IOException("iframe 2Embed senza sorgenti dirette: $playerUrl")
    return sources
  }

  /**
   * Visita una pagina player e, se non contiene media diretti, segue i suoi
   * iframe annidati fino a [MAX_PLAYER_DEPTH]. Il ciclo è limitato sia in
   * profondità sia da [visited]: nessuna ricorsione infinita.
   */
  private suspend fun collectFromPlayerPage(
    url: String,
    referer: String,
    visited: MutableSet<String>,
    depth: Int
  ): List<StreamSource> {
    if (depth > MAX_PLAYER_DEPTH || !visited.add(url)) return emptyList()
    val fetched = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
      http.get(url, referer, ExtractorHttp.MIRROR_CALL_TIMEOUT_MS)
    } ?: throw IOException("timeout iframe $url")
    val finalUrl = fetched.first
    val html = fetched.second
    if (html.isBlank()) throw IOException("iframe 2Embed vuoto: $url")

    val direct = directSources(html, finalUrl, finalUrl)
    if (direct.isNotEmpty()) return direct

    for (nested in nestedIframeUrls(html, finalUrl).take(MAX_NESTED_IFRAMES)) {
      currentCoroutineContext().ensureActive()
      val sources = try {
        collectFromPlayerPage(nested, finalUrl, visited, depth + 1)
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.i(TAG, "iframe annidato non risolto ($nested): ${ex.message}")
        emptyList()
      }
      if (sources.isNotEmpty()) return sources
    }
    return emptyList()
  }

  /** Iframe interni a una pagina player (anche relativi, es. `139911?autoplay=true`). */
  private fun nestedIframeUrls(page: String, baseUrl: String): List<String> =
    (IFRAME_REGEX.findAll(page).map { it.groupValues[1] } +
      DATA_SRC_REGEX.findAll(page).map { it.groupValues[1] })
      .map { it.replace("&amp;", "&") }
      .filter { !it.isBlank() && !isNonPlayerUrl(it) && !IMAGE_EXT_REGEX.containsMatchIn(it) }
      .mapNotNull { absolutize(it, baseUrl) }
      .distinct()
      .toList()

  private fun isNonPlayerUrl(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("about:") ||
      lower.startsWith("javascript:") ||
      lower.startsWith("data:") ||
      lower.contains("youtube") ||
      lower.contains("googletag") ||
      lower.contains("histats") ||
      lower.contains("amung") ||
      lower.contains("doubleclick") ||
      lower.contains("adplox")
  }

  /**
   * Vero se la pagina TV è il player "movie" con id concatenato che alcuni
   * mirror restituiscono per il path `/embed/tv/{tmdb}/{s}/{e}`:
   * `1399` + `1` + `1` -> `/embed/movie/139911`. Quella pagina non contiene
   * l'episodio richiesto e non deve essere usata.
   */
  internal fun isBrokenConcatenatedTvPage(
    page: String,
    tmdbId: Int,
    season: Int,
    episode: Int
  ): Boolean {
    val refs = (IFRAME_REGEX.findAll(page).map { it.groupValues[1] } +
      DATA_SRC_REGEX.findAll(page).map { it.groupValues[1] })
      .map { it.replace("&amp;", "&") }
      .filter { it.startsWith("http") || it.startsWith("//") || it.startsWith("/") }
      .toList()
    if (refs.isEmpty()) return false
    // Un iframe TV valido rende la pagina utilizzabile anche se convive con
    // riferimenti movie (es. elenco server del mirror).
    if (refs.any { TV_PLAYER_REGEX.containsMatchIn(it) }) return false
    val brokenId = "$tmdbId$season$episode"
    return refs.any { ref ->
      MOVIE_EMBED_ID_REGEX.find(ref)?.groupValues?.get(1) == brokenId ||
        TMDB_QUERY_REGEX.find(ref)?.groupValues?.get(1) == brokenId
    }
  }

  /**
   * Vero se la pagina non dichiara un episodio diverso da quello richiesto.
   * Una pagina senza marker `SxxExx` resta valida (player generici): il filtro
   * serve solo a non accettare un episodio esplicitamente sbagliato.
   */
  internal fun pageMatchesRequestedEpisode(page: String, season: Int, episode: Int): Boolean {
    val markers = EPISODE_MARKER_REGEX.findAll(page)
      .map { it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull() }
      .filter { it.first != null && it.second != null }
      .toList()
    if (markers.isEmpty()) return true
    return markers.any { it.first == season && it.second == episode }
  }

  /**
   * Sorgenti media dirette dal markup/script, con header dell'embed effettivo.
   * Se la pagina espone sia HLS sia MP4 viene preferito l'HLS (adattivo), senza
   * scartare un MP4 valido quando è l'unico media presente.
   */
  private fun directSources(
    html: String,
    pageUrl: String,
    embedFinalUrl: String
  ): List<StreamSource> {
    val media = PlayerSourceParser.findMediaUrls(html, pageUrl)
    val chosen = media.firstOrNull { it.contains(".m3u8", ignoreCase = true) }
      ?: media.firstOrNull { it.contains(".mp4", ignoreCase = true) }
      ?: media.firstOrNull()
      ?: return emptyList()
    return listOf(
      StreamSource(
        streamUrl = chosen,
        quality = "Auto",
        serverName = SERVER_NAME,
        headers = headersFor(embedFinalUrl)
      )
    )
  }

  /**
   * Catena VidSrc: `var Q` -> `pl/api.php?a=sources` -> `pl/api.php?a=play` per
   * ogni server -> URL relativo della master playlist (risolto sull'origin).
   */
  private suspend fun vidSrcSources(
    embedUrl: String,
    isTv: Boolean,
    s: Int,
    e: Int,
    fallbackImdb: String?
  ): List<StreamSource> {
    val fetchedEmbed = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
      http.get(embedUrl, originOf(embedUrl) + "/", ExtractorHttp.MIRROR_CALL_TIMEOUT_MS)
    } ?: throw IOException("timeout pagina VidSrc $embedUrl")
    val finalEmbedUrl = fetchedEmbed.first
    val page = fetchedEmbed.second
    val q = parseVarQ(page, isTv, fallbackImdb)
      ?: throw IOException("variabile Q assente o non interpretabile nella pagina VidSrc")

    // Stagione/episodio vanno presi da Q: il token è vincolato a quei valori
    // (per i film sono 0/0, passarli diversi fa rispondere 403).
    val sParam = q.season ?: if (isTv) s else 0
    val eParam = q.episode ?: if (isTv) e else 0

    val qs = "type=${q.type}&id=${enc(q.imdbId)}&s=$sParam&e=$eParam&t=${enc(q.token)}"
    val origin = originOf(finalEmbedUrl).ifBlank { originOf(embedUrl) }

    val sourcesFetched = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
      http.get("$origin/pl/api.php?a=sources&$qs", finalEmbedUrl, ExtractorHttp.MIRROR_CALL_TIMEOUT_MS)
    } ?: throw IOException("timeout elenco server VidSrc")
    val servers = parseServers(sourcesFetched.second)
    if (servers.isEmpty()) throw IOException("nessun server disponibile da VidSrc")
    Log.i(TAG, "server VidSrc (${servers.size}): " + servers.joinToString { it.second })

    val out = mutableListOf<StreamSource>()
    for ((ref, name) in servers) {
      currentCoroutineContext().ensureActive()
      val playFetched = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
          http.get(
            "$origin/pl/api.php?a=play&ref=${enc(ref)}&$qs",
            finalEmbedUrl,
            ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
          )
        }
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.i(TAG, "server $name: ${ex.message}")
        null
      }
      if (playFetched == null) {
        Log.i(TAG, "server $name: timeout")
        continue
      }
      val play = parsePlayResponse(playFetched.second) ?: continue
      if (play.url.isBlank()) continue
      val url = absolutize(play.url, origin + "/") ?: continue

      // Validazione + lettura risoluzione massima dalla master playlist
      val headers = headersFor(finalEmbedUrl)
      val probe = probePlaylist(url, headers, play.type)
      if (probe == null) {
        Log.i(TAG, "server $name: playlist non valida ($url)")
        continue
      }
      Log.i(TAG, "server $name OK: ${probe.quality} (height ${probe.height ?: "?"}) -> ${probe.finalUrl}")
      out += StreamSource(
        streamUrl = probe.finalUrl,
        quality = probe.quality,
        serverName = "$SERVER_NAME $name",
        headers = headers,
        verifiedHeight = probe.height
      )
      // Il primo server funzionante basta: la lista resta ordinata per preferenza
      break
    }
    if (out.isEmpty()) throw IOException("tutti i server VidSrc hanno risposto unavailable")
    return out
  }

  /**
   * Ritorna la playlist validata (con URL finale dopo redirect e altezza
   * verificata) oppure `null` se non è HLS. I file MP4 non vengono scaricati:
   * qualità `Auto`, nessuna altezza dichiarata.
   */
  private suspend fun probePlaylist(
    url: String,
    headers: Map<String, String>,
    playType: String
  ): ProbedPlaylist? {
    if (!playType.contains("hls", ignoreCase = true) && !url.contains(".m3u8", ignoreCase = true)) {
      return ProbedPlaylist(finalUrl = url, quality = "Auto", height = null)
    }
    return try {
      val fetched = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        http.get(url, headers["Referer"], ExtractorHttp.MIRROR_CALL_TIMEOUT_MS)
      } ?: return null
      val body = fetched.second
      if (!body.startsWith("#EXTM3U")) return null
      val height = maxResolutionHeight(body)
      ProbedPlaylist(
        finalUrl = fetched.first,
        quality = qualityForHeight(height),
        height = height
      )
    } catch (ex: CancellationException) {
      // Una cancellazione del chiamante non deve diventare "playlist invalida".
      throw ex
    } catch (ex: Exception) {
      Log.i(TAG, "probe playlist fallito: ${ex.message}")
      null
    }
  }

  internal fun headersFor(embedUrl: String): Map<String, String> {
    val origin = originOf(embedUrl)
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

  internal fun absolutize(raw: String, baseUrl: String): String? {
    val value = raw.trim().replace("&amp;", "&")
    if (value.isEmpty()) return null
    return when {
      value.startsWith("http", ignoreCase = true) -> value
      value.startsWith("//") -> "https:$value"
      value.startsWith("/") -> originOf(baseUrl).ifBlank { return null } + value
      else -> {
        val origin = originOf(baseUrl).ifBlank { return null }
        origin + "/" + value.trimStart('/')
      }
    }
  }

  internal fun originOf(url: String): String = try {
    val uri = URI(url)
    val scheme = uri.scheme
    val host = uri.host
    if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
      ""
    } else {
      val port = uri.port
      val default = (scheme.equals("http", true) && port == 80) ||
        (scheme.equals("https", true) && port == 443)
      if (port <= 0 || default) "$scheme://$host" else "$scheme://$host:$port"
    }
  } catch (e: Exception) {
    ""
  }

  private fun hostOf(url: String): String = try {
    URI(url).host.orEmpty().removePrefix("www.")
  } catch (e: Exception) {
    ""
  }

  private fun isVidSrcUrl(url: String): Boolean = hostOf(url).contains("vidsrc", ignoreCase = true)

  private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

  private data class ProbedPlaylist(val finalUrl: String, val quality: String, val height: Int?)

  /** Informazioni ricavate da `var Q`. */
  internal data class VarQ(
    val type: String,
    val imdbId: String,
    val token: String,
    val season: Int?,
    val episode: Int?
  )

  internal data class PlayInfo(val url: String, val type: String)

  companion object {
    private const val TAG = "TwoEmbedProvider"
    const val SERVER_NAME = "2Embed"

    /** Identificativo del provider nella diagnostica per-mirror. */
    internal const val PROVIDER_ID = "TwoEmbed"

    /** Budget complessivo di un mirror; il chiamante ha un budget globale di 15 s. */
    const val MIRROR_BUDGET_MS = 12_000L

    /** Limite per una singola richiesta/tentativo indipendente. */
    const val REQUEST_TIMEOUT_MS = 5_000L

    private const val MAX_IFRAME_ATTEMPTS = 3

    /** Profondità massima di iframe annidati seguiti dentro una pagina player. */
    private const val MAX_PLAYER_DEPTH = 2

    /** Numero massimo di iframe annidati provati per pagina. */
    private const val MAX_NESTED_IFRAMES = 2

    /** Mirror 2Embed (il primo è quello che espone l'iframe VidSrc). */
    private val BASE_URLS = listOf(
      "https://www.2embed.cc",
      "https://www.2embed.stream"
    )

    private val TT_ID_REGEX = Regex("""tt\d{5,}""", RegexOption.IGNORE_CASE)
    private val IFRAME_REGEX = Regex("""<iframe[^>]{0,400}?src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val DATA_SRC_REGEX = Regex("""data-(?:src|url)="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val PLAYERISH_REGEX = Regex("""(vidsrc|videm|embed|player|play|video|\.php)""", RegexOption.IGNORE_CASE)
    private val PLAYER_ELEMENT_REGEX = Regex(
      """<(?:video|source)\b|jwplayer|video\.?js|/embed/|/player|var\s+Q\s*=""",
      RegexOption.IGNORE_CASE
    )
    private val Q_PREFIX_REGEX = Regex("""var\s+Q\s*=\s*""")
    private val SERVER_OBJECT_REGEX = Regex("""\{[^{}]*\}""")
    private val REF_FIELD_REGEX = Regex(""""ref"\s*:\s*"([^"]+)"""")
    private val NAME_FIELD_REGEX = Regex(""""name"\s*:\s*"([^"]+)"""")
    private val PLAY_URL_REGEX = Regex(""""url"\s*:\s*"([^"]+)"""")
    private val PLAY_TYPE_REGEX = Regex(""""type"\s*:\s*"([^"]+)"""")
    private val JSON_TYPE_REGEX = Regex(""""type"\s*:\s*"([^"]+)"""")
    private val JSON_ID_REGEX = Regex(""""id"\s*:\s*"([^"]+)"""")
    private val JSON_TOKEN_REGEX = Regex(""""t"\s*:\s*"([^"]+)"""")
    private val JSON_SEASON_REGEX = Regex(""""s"\s*:\s*(\d+)""")
    private val JSON_EPISODE_REGEX = Regex(""""e"\s*:\s*(\d+)""")
    private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)

    /** Player TV valido: path `/embed/tv/` o endpoint `embedtv` con stagione/episodio. */
    private val TV_PLAYER_REGEX = Regex(
      """(?:/embed/tv/|embedtv|[?&](?:s|season)=\d+[^"']*[?&](?:e|episode)=\d+)""",
      RegexOption.IGNORE_CASE
    )
    /** Id movie usato dal path TV rotto di 2embed (`/embed/movie/139911`). */
    private val MOVIE_EMBED_ID_REGEX = Regex("""/embed/movie/(\d+)""", RegexOption.IGNORE_CASE)
    /** Id passato al player VCR (`?tmdb=139911`), stessa concatenazione. */
    private val TMDB_QUERY_REGEX = Regex("""[?&]tmdb=(\d+)""", RegexOption.IGNORE_CASE)
    /** Marker SxxExx usato per validare che la pagina sia dell'episodio richiesto. */
    private val EPISODE_MARKER_REGEX = Regex(
      """\bS0*(\d{1,3})[\s._-]*E0*(\d{1,4})\b""",
      RegexOption.IGNORE_CASE
    )
    private val IMAGE_EXT_REGEX = Regex("""\.(?:png|jpe?g|gif|webp|svg|ico)(?:\?|$)""", RegexOption.IGNORE_CASE)

    private val jsonAdapter: JsonAdapter<Any> by lazy {
      Moshi.Builder().build().adapter(Any::class.java)
    }

    /**
     * Vero se il documento contiene un player, un iframe o un elemento
     * equivalente riconosciuto. Una pagina HTML generica (errore, login,
     * placeholder) non è un player valido.
     */
    internal fun isPlayerPage(html: String): Boolean {
      if (html.isBlank()) return false
      return IFRAME_REGEX.containsMatchIn(html) ||
        PLAYER_ELEMENT_REGEX.containsMatchIn(html) ||
        Q_PREFIX_REGEX.containsMatchIn(html)
    }

    /**
     * Interpreta `var Q` preferendo il parsing JSON strutturato. Solo se il
     * blocco non è JSON valido usa regex circoscritte allo stesso blocco, mai
     * all'intero documento.
     *
     * @param fallbackImdb ID IMDb da usare quando `Q.id` è assente, numerico o
     *   non interpretabile.
     */
    internal fun parseVarQ(page: String, isTv: Boolean, fallbackImdb: String?): VarQ? {
      val block = extractVarQBlock(page) ?: return null
      val map = runCatching { jsonAdapter.fromJson(block) as? Map<*, *> }.getOrNull()

      val type = map?.string("type")?.takeIf { it.isNotBlank() }
        ?: JSON_TYPE_REGEX.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        ?: if (isTv) "tv" else "movie"
      val rawId = map?.string("id")
        ?: JSON_ID_REGEX.find(block)?.groupValues?.get(1)
      val imdb = rawId?.trim()?.takeIf { TT_ID_REGEX.matches(it) }
        ?: fallbackImdb?.trim()?.takeIf { TT_ID_REGEX.matches(it) }
        ?: return null
      val token = map?.string("t")?.takeIf { it.isNotBlank() }
        ?: JSON_TOKEN_REGEX.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        ?: return null
      val season = map?.int("s") ?: JSON_SEASON_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull()
      val episode = map?.int("e") ?: JSON_EPISODE_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull()
      return VarQ(type = type, imdbId = imdb, token = token, season = season, episode = episode)
    }

    /**
     * Elenco server (`ref` + `name`) dal JSON di `a=sources`.
     *
     * Il parsing strutturato è preferito; il fallback considera un oggetto alla
     * volta, così `ref` e `name` di oggetti diversi non possono essere associati.
     */
    internal fun parseServers(json: String): List<Pair<String, String>> {
      val out = mutableListOf<Pair<String, String>>()
      val parsed = runCatching { jsonAdapter.fromJson(json) }.getOrNull()
      collectServerEntries(parsed, out)
      if (out.isNotEmpty()) return out.distinct()

      SERVER_OBJECT_REGEX.findAll(json).forEach { objectMatch ->
        val block = objectMatch.value
        val ref = REF_FIELD_REGEX.find(block)?.groupValues?.get(1) ?: return@forEach
        val name = NAME_FIELD_REGEX.find(block)?.groupValues?.get(1) ?: return@forEach
        if (ref.isNotBlank() && name.isNotBlank()) out += ref to name
      }
      return out.distinct()
    }

    /** Risposta di `a=play`, strutturata quando possibile. */
    internal fun parsePlayResponse(json: String): PlayInfo? {
      val map = runCatching { jsonAdapter.fromJson(json) as? Map<*, *> }.getOrNull()
      val url = map?.string("url")?.takeIf { it.isNotBlank() }
        ?: PLAY_URL_REGEX.find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        ?: return null
      val type = map?.string("type")?.takeIf { it.isNotBlank() }
        ?: PLAY_TYPE_REGEX.find(json)?.groupValues?.get(1)
        ?: "hls"
      return PlayInfo(url = url.replace("\\/", "/"), type = type)
    }

    /** Altezza massima dichiarata dai tag `RESOLUTION=WxH` di una master HLS. */
    internal fun maxResolutionHeight(playlist: String): Int? =
      RESOLUTION_REGEX.findAll(playlist)
        .mapNotNull { it.groupValues.getOrNull(2)?.toIntOrNull() }
        .maxOrNull()

    /** Etichetta qualità coerente con l'altezza verificata; `Auto` se ignota. */
    internal fun qualityForHeight(height: Int?): String = when {
      height == null -> "Auto"
      height >= 2000 -> "4K"
      height >= 1000 -> "1080p"
      height >= 700 -> "720p"
      height >= 480 -> "480p"
      else -> "Auto"
    }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String

    private fun Map<*, *>.int(key: String): Int? = when (val value = this[key]) {
      is Number -> value.toInt()
      is String -> value.toIntOrNull()
      else -> null
    }

    private fun collectServerEntries(node: Any?, out: MutableList<Pair<String, String>>) {
      when (node) {
        is Map<*, *> -> {
          val ref = node.string("ref")
          val name = node.string("name")
          if (!ref.isNullOrBlank() && !name.isNullOrBlank()) out += ref to name
          node.values.forEach { collectServerEntries(it, out) }
        }
        is List<*> -> node.forEach { collectServerEntries(it, out) }
      }
    }

    /**
     * Isola l'oggetto bilanciato che segue `var Q =`: le graffe dentro le
     * stringhe JSON non contano. Restituisce `null` se il blocco non è chiuso.
     */
    internal fun extractVarQBlock(page: String): String? {
      val prefix = Q_PREFIX_REGEX.find(page) ?: return null
      val start = page.indexOf('{', prefix.range.last + 1)
      if (start < 0) return null
      var depth = 0
      var inString = false
      var quote = ' '
      var escaped = false
      for (i in start until page.length) {
        val c = page[i]
        if (inString) {
          if (escaped) {
            escaped = false
          } else if (c == '\\') {
            escaped = true
          } else if (c == quote) {
            inString = false
          }
        } else {
          when (c) {
            '"', '\'' -> {
              inString = true
              quote = c
            }
            '{' -> depth++
            '}' -> {
              depth--
              if (depth == 0) return page.substring(start, i + 1)
            }
          }
        }
      }
      return null
    }
  }
}

/** Fetcher HTTP iniettabile: nei test restituisce fixture senza rete. */
internal fun interface TwoEmbedHttp {
  suspend fun get(url: String, referer: String?, callTimeoutMs: Long?): Pair<String, String>
}
