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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Provider aggregatore **SuperEmbed / MultiEmbed** (famiglia "Movie Streaming API").
 *
 * Entrambi i server espongono lo stesso player via parametro `video_id`:
 *  - `https://superembed.stream/se_player.php?video_id={tmdbId}` (documentato:
 *    "Our player script se_player.php...", con `&s=&e=` per le serie)
 *  - `https://multiembed.mov/?video_id={tmdbId}` (senza parametri risponde
 *    `Missing video_id`)
 *
 * La pagina player (≈650 KB) contiene sorgenti codificate e, sui mirror attivi,
 * un **gate Cloudflare Turnstile** (`.captcha-gate` + `challenges.cloudflare.com`):
 * quando non è presente alcuna sorgente direttamente leggibile, l'estrazione viene
 * interrotta con un'IOException esplicita che riporta il gate rilevato (utile in
 * Logcat), invece di restituire un URL non valido al player.
 *
 * La ricerca delle sorgenti copre comunque i casi "aperti": URL `.m3u8`/`.mp4`
 * nel markup, tag `<video>/<source>`, iframe annidati (massimo 2 livelli).
 */
class SuperEmbedProvider(
  private val diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE,
  /**
   * Fetcher iniettabile: i test offline simulano la pagina con gate Turnstile e
   * l'endpoint alternativo senza rete.
   */
  private val http: suspend (url: String, referer: String?) -> String = { url, referer ->
    ExtractorHttp.get(url, referer = referer)
  }
) : StreamProvider {

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    if (tmdbId <= 0) throw IOException("TMDB ID non valido: $tmdbId")
    val s = (season ?: 1).coerceAtLeast(1)
    val e = (episode ?: 1).coerceAtLeast(1)

    val failures = mutableListOf<String>()
    for ((name, base) in BASE_URLS) {
      currentCoroutineContext().ensureActive()
      val startedAt = System.currentTimeMillis()
      val mirrorId = StreamDiagnostics.hostId(base)
      try {
        val sources = resolveOnBase(name, base, tmdbId, isTv, s, e)
        if (sources.isNotEmpty()) {
          Log.i(TAG, "sorgenti $name: ${sources.size}")
          diagnostics.record(
            providerId = PROVIDER_ID,
            mirrorId = mirrorId,
            outcome = StreamAttemptOutcome.SUCCESS,
            durationMs = System.currentTimeMillis() - startedAt
          )
          return@withContext sources
        }
        failures += "$name -> nessuna sorgente"
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
        Log.w(TAG, "$name: ${ex.message}")
        failures += "$name -> ${ex.message}"
        diagnostics.record(
          providerId = PROVIDER_ID,
          mirrorId = mirrorId,
          outcome = StreamDiagnostics.classify(ex),
          durationMs = System.currentTimeMillis() - startedAt,
          message = ex.message
        )
      }
    }
    throw IOException("SuperEmbed/MultiEmbed non risolvibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnBase(
    name: String,
    base: String,
    tmdbId: Int,
    isTv: Boolean,
    s: Int,
    e: Int
  ): List<StreamSource> {
    val candidates = if (isTv) {
      listOf(
        "$base/se_player.php?video_id=$tmdbId&s=$s&e=$e",
        "$base/?video_id=$tmdbId&s=$s&e=$e"
      )
    } else {
      listOf(
        "$base/se_player.php?video_id=$tmdbId",
        "$base/?video_id=$tmdbId"
      )
    }

    // La challenge su un endpoint non è definitiva: il secondo endpoint
    // documentato può servire la stessa API senza gate. Si prova ognuno e si
    // segnala la challenge solo se nessuno produce sorgenti.
    var challenge: String? = null
    for (candidate in candidates) {
      currentCoroutineContext().ensureActive()
      val html = try {
        http(candidate, "$base/")
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.i(TAG, "$name endpoint non disponibile: $candidate (${ex.message})")
        continue
      }
      if (html.isBlank()) continue
      Log.i(TAG, "GET PLAYER $candidate (${html.length} byte)")

      val sources = sourcesFromPage(name, html, base, candidate)
      if (sources.isNotEmpty()) return sources
      if (CAPTCHA_GATE_REGEX.containsMatchIn(html)) {
        challenge = "$name protetto da captcha Cloudflare Turnstile (gate del player)"
        Log.i(TAG, "$name: gate Turnstile su $candidate, provo il candidato successivo")
      }
    }

    throw IOException(challenge ?: "$name senza sorgenti leggibili (player offuscato o file non disponibile)")
  }

  /** Sorgenti leggibili dal markup/script di una singola pagina player. */
  private suspend fun sourcesFromPage(name: String, html: String, base: String, used: String): List<StreamSource> {
    val normalized = html.replace("\\/", "/")

    // --- 1) sorgenti dirette nel markup -----------------------------------
    val direct = MEDIA_REGEX.findAll(normalized).map { it.groupValues[1] }.distinct().toList()
    if (direct.isNotEmpty()) {
      val url = direct.first()
      Log.i(TAG, "sorgente diretta: $url")
      return listOf(
        StreamSource(
          streamUrl = url,
          // La risoluzione MP4 non è verificata (nessun probe): non dichiarare "1080p".
          quality = "Auto",
          serverName = name,
          headers = mapOf("Referer" to "$base/", "User-Agent" to ExtractorHttp.USER_AGENT)
        )
      )
    }

    // --- 2) tag video/source ----------------------------------------------
    val srcTag = SOURCE_REGEX.find(normalized)?.groupValues?.get(1)
    if (srcTag != null && srcTag.startsWith("http")) {
      Log.i(TAG, "sorgente da <source>: $srcTag")
      return listOf(
        StreamSource(
          streamUrl = srcTag,
          quality = "Auto",
          serverName = name,
          headers = mapOf("Referer" to "$base/", "User-Agent" to ExtractorHttp.USER_AGENT)
        )
      )
    }

    // --- 3) iframe annidati ------------------------------------------------
    val iframes = IFRAME_REGEX.findAll(normalized)
      .map { it.groupValues[1] }
      .filter { !it.contains("youtube") && !it.contains("googletag") && !it.contains("histats") }
      .toList()
    for (iframe in iframes) {
      currentCoroutineContext().ensureActive()
      val iframeUrl = when {
        iframe.startsWith("http") -> iframe
        iframe.startsWith("//") -> "https:$iframe"
        else -> originOf(used) + iframe
      }
      Log.i(TAG, "iframe annidato: $iframeUrl")
      val nested = try {
        http(iframeUrl, used).replace("\\/", "/")
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.i(TAG, "iframe $iframeUrl non raggiungibile: ${ex.message}")
        continue
      }
      val nestedMedia = MEDIA_REGEX.findAll(nested).map { it.groupValues[1] }.distinct().toList()
      if (nestedMedia.isNotEmpty()) {
        val url = nestedMedia.first()
        return listOf(
          StreamSource(
            streamUrl = url,
            // La risoluzione MP4 non è verificata (nessun probe): non dichiarare "1080p".
            quality = "Auto",
            serverName = name,
            headers = mapOf("Referer" to iframeUrl, "User-Agent" to ExtractorHttp.USER_AGENT)
          )
        )
      }
    }

    return emptyList()
  }

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}"
  } catch (e: Exception) {
    ""
  }

  companion object {
    private const val TAG = "SuperEmbedProvider"
    private const val PROVIDER_ID = "SuperEmbedProvider"

    /** Nome -> base URL (stessa famiglia di player, parametri identici). */
    private val BASE_URLS = linkedMapOf(
      "SuperEmbed" to "https://superembed.stream",
      "MultiEmbed" to "https://multiembed.mov"
    )

    private val MEDIA_REGEX = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
    private val IFRAME_REGEX = Regex("""<iframe[^>]{0,400}?src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val SOURCE_REGEX = Regex("""<(?:video|source)[^>]*\ssrc\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val CAPTCHA_GATE_REGEX = Regex("""captcha-gate|challenges\.cloudflare\.com/turnstile""", RegexOption.IGNORE_CASE)
  }
}
