package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.ExtractorHttp
import java.io.IOException
import java.net.URI
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
 * Per ogni mirror vivo il flusso è:
 *  1. **ricerca per titolo/anno** su uno dei tre pattern noti:
 *     `/?s={q}` (WordPress), `/search?q={q}`, `/api/search?name={q}` (JSON);
 *  2. apertura della pagina titolo e **estrazione delle sorgenti**:
 *     URL `.m3u8`/`.mp4` diretti, array `sources`/`file` negli script del player,
 *     iframe/data-src degli embed → delegati a [HosterResolver] per gli hoster
 *     supportati (MaxStream/Mixdrop);
 *  3. le sorgenti restituite dichiarano `Referer` = origin del mirror (molti CDN
 *     italiani rispondono 403 senza).
 *
 * Se nessun mirror risponde, viene lanciata un'IOException con l'elenco dei
 * tentativi: in Logcat si vede esattamente quale dominio ha fallito e perché.
 */
class StreamingCommunityProvider : StreamProvider {

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
    for (mirror in MIRRORS) {
      // FIX TIMEOUT: le chiamate HTTP qui sotto sono BLOCCANTI (OkHttp execute, nessun
      // punto di sospensione), quindi withTimeout(15s) di StreamManager non interrompe
      // il loop. ensureActive() all'inizio di ogni mirror garantisce che, dopo la
      // cancellazione, nessun mirror aggiuntivo venga interrogato.
      currentCoroutineContext().ensureActive()
      try {
        val sources = resolveOnMirror(mirror, query, title, isTv, season ?: 1, episode ?: 1)
        if (sources.isNotEmpty()) {
          Log.i(TAG, "sorgenti StreamingCommunity su $mirror: ${sources.size}")
          return@withContext sources
        }
        failures += "$mirror -> nessuna sorgente"
      } catch (ex: CancellationException) {
        // FIX TIMEOUT: la cancellazione (incluso TimeoutCancellationException di
        // withTimeout) NON è un errore recuperabile: va rilanciata immediatamente,
        // altrimenti il provider continua a iterare i mirror oltre il timeout.
        throw ex
      } catch (ex: Exception) {
        Log.w(TAG, "mirror $mirror: ${ex.message}")
        failures += "$mirror -> ${ex.message}"
      }
    }
    throw IOException("StreamingCommunity non risolvibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnMirror(
    mirror: String,
    query: String,
    title: String?,
    isTv: Boolean,
    season: Int,
    episode: Int
  ): List<StreamSource> {
    // --- 1) ricerca ---------------------------------------------------------
    val searchPatterns = listOf(
      "$mirror/?s=${enc(query)}",
      "$mirror/search?q=${enc(query)}",
      "$mirror/api/search?name=${enc(query)}"
    )
    var pageUrl = ""
    var pageHtml = ""
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
          pageUrl = if (url.startsWith("http")) url else mirror + url
          pageHtml = try {
            ExtractorHttp.get(pageUrl, referer = "$mirror/")
          } catch (ex: CancellationException) {
            throw ex
          } catch (e: Exception) {
            ""
          }
          if (pageHtml.isNotEmpty()) break
        }
        continue
      }
      // HTML: sceglie il risultato con il miglior punteggio sul titolo
      val candidate = pickArticle(body, title, mirror) ?: continue
      pageUrl = candidate
      pageHtml = try {
        ExtractorHttp.get(candidate, referer = "$mirror/")
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        ""
      }
      if (pageHtml.isNotEmpty()) break
    }
    if (pageHtml.isEmpty()) throw IOException("nessuna pagina titolo per '$query'")
    Log.i(TAG, "GET TITOLO $pageUrl")

    // --- 2) estrazione sorgenti -------------------------------------------
    val normalized = pageHtml.replace("\\/", "/")
    val headers = mapOf("Referer" to (originOf(pageUrl) + "/"), "User-Agent" to ExtractorHttp.USER_AGENT)
    val out = linkedMapOf<String, StreamSource>()

    // URL media diretti (script del player o markup)
    MEDIA_REGEX.findAll(normalized).forEach { m ->
      val url = m.groupValues[1]
      out.putIfAbsent(
        url,
        StreamSource(
          streamUrl = url,
          quality = if (url.contains(".m3u8", ignoreCase = true)) "Auto" else "1080p",
          serverName = SERVER_NAME,
          headers = headers
        )
      )
    }

    // Iframe/data-src degli embed → delegati agli estrattori supportati
    val embedUrls = (IFRAME_REGEX.findAll(normalized).map { it.groupValues[1] } +
      DATA_SRC_REGEX.findAll(normalized).map { it.groupValues[1] })
      .map { raw -> if (raw.startsWith("http")) raw else originOf(pageUrl) + raw }
      .filter { HosterResolver.isHosterUrl(it) }
      .distinct()
      .toList()

    if (embedUrls.isNotEmpty() && out.isEmpty()) {
      Log.i(TAG, "embed trovati (${embedUrls.size}), delego agli estrattori")
      val extracted = HosterResolver.extractFromLinks(
        rawUrls = embedUrls.map { "embed" to it },
        referer = pageUrl,
        onResolved = { resolved -> Log.i(TAG, "embed risolto: $resolved") }
      )
      extracted.forEach { source -> source.streamUrl?.let { out.putIfAbsent(it, source) } }
    }

    if (out.isEmpty()) throw IOException("pagina titolo senza sorgenti leggibili")
    return out.values.toList()
  }

  /** Sceglie il risultato di ricerca corrispondente al titolo. */
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

  private fun slugOf(url: String): String = url.trimEnd('/').substringAfterLast('/').lowercase()

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}"
  } catch (e: Exception) {
    ""
  }

  private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

  companion object {
    private const val TAG = "StreamingCommunityProvider"
    const val SERVER_NAME = "StreamingCommunity"

    /**
     * Domini provati in ordine (il catalogo cambia TLD molto spesso): ogni
     * dominio non raggiungibile viene saltato e loggato, senza bloccare gli
     * altri provider dello StreamManager.
     */
    private val MIRRORS = listOf(
      "https://streamingcommunity.click",
      "https://streamingcommunity.buzz",
      "https://streamingcommunity.org",
      "https://streamingcommunity.top",
      "https://streamingcommunity.zone",
      "https://streamingcommunity.bz",
      "https://streamingcommunity.lol",
      "https://streamingcommunity.network"
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
  }
}
