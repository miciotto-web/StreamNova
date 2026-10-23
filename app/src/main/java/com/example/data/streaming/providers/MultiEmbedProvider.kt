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
 * Provider ad alta risoluzione orientato al **Full HD 1080p**: **MultiEmbed** (multiembed.mov / superembed.stream).
 *
 * Supporta:
 *  - Film: `/movie/{id}` con parametro `?video_id={id}&tmdb=1`
 *  - Serie TV: `/tv/{id}/{season}/{episode}` con parametri `?video_id={id}&tmdb=1&s={season}&e={episode}`
 *
 * Flusso di estrazione:
 *  1. Interrogazione endpoint speculari (multiembed.mov, superembed.stream, getsuperembed.link);
 *  2. Estrazione playlist HLS (.m3u8) o stream MP4 diretto a 1080p Full HD;
 *  3. Validazione playlist HLS e marcatura esplicita della qualità a 1080p;
 *  4. Se nessun mirror risponde con sorgenti valide, restituisce lista vuota senza flussi fittizi.
 */
class MultiEmbedProvider(
  private val diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE,
  /**
   * Fetcher iniettabile: i test offline simulano endpoint con/senza gate
   * Turnstile senza rete. In produzione resta [ExtractorHttp].
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
          Log.i(TAG, "sorgenti $name 1080p: ${sources.size}")
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

    // Nessun mirror ha restituito sorgenti reali: il provider è indisponibile.
    // Un'eccezione esplicita (invece di una lista vuota) permette a
    // StreamManager di classificare il tentativo come challenge/errore e di
    // mostrarlo nella diagnostica per provider.
    Log.d(TAG, "MultiEmbed: nessuna sorgente reale trovata per TMDB $tmdbId (s=$s, e=$e): ${failures.joinToString(" | ")}")
    throw IOException("MultiEmbed non risolvibile (${failures.joinToString(" | ")})")
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
        "$base/?video_id=$tmdbId&tmdb=1&s=$s&e=$e",
        "$base/se_player.php?video_id=$tmdbId&tmdb=1&s=$s&e=$e",
        "https://getsuperembed.link/?video_id=$tmdbId&tmdb=1&season=$s&episode=$e"
      )
    } else {
      listOf(
        "$base/?video_id=$tmdbId&tmdb=1",
        "$base/se_player.php?video_id=$tmdbId&tmdb=1",
        "https://getsuperembed.link/?video_id=$tmdbId&tmdb=1"
      )
    }

    Log.d("THE_PITT_STREAM", "MultiEmbed resolving TMDB=$tmdbId isTv=$isTv S=$s E=$e")
    candidates.forEach { url ->
      Log.d("THE_PITT_STREAM", "  Trying URL: $url")
    }

    // Una pagina con il gate Turnstile non è un errore definitivo del provider:
    // lo stesso player può esporre un endpoint senza gate (`se_player.php`,
    // `getsuperembed.link`). Si prova ogni endpoint documentato e si registra
    // la challenge solo se NESSUNO restituisce sorgenti.
    var challenge: String? = null
    for (candidate in candidates) {
      currentCoroutineContext().ensureActive()
      val html = try {
        http(candidate, originOf(candidate).ifBlank { base } + "/")
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.d(TAG, "$name endpoint non disponibile: $candidate (${ex.message})")
        continue
      }
      if (html.isBlank() || html.contains("Not Found!", ignoreCase = true)) continue

      val sources = sourcesFromPage(html, base, candidate)
      if (sources.isNotEmpty()) return sources
      if (CAPTCHA_GATE_REGEX.containsMatchIn(html)) {
        challenge = "$name protetto da captcha Cloudflare Turnstile"
        Log.i(TAG, "$name: gate Turnstile su $candidate, provo il candidato successivo")
      } else {
        Log.d(TAG, "$name: nessuna sorgente in $candidate")
      }
    }

    throw IOException(challenge ?: "$name senza sorgenti dirette leggibili")
  }

  /** Sorgenti leggibili dal markup/script di una singola pagina player. */
  private suspend fun sourcesFromPage(html: String, base: String, usedUrl: String): List<StreamSource> {
    val normalized = html.replace("\\/", "/")

    // 1) Cerca URL diretti .m3u8 o .mp4
    val direct = MEDIA_REGEX.findAll(normalized)
      .map { it.groupValues[1] }
      .distinct()
      .toList()

    if (direct.isNotEmpty()) {
      val url = direct.first()
      val qualityInfo = probeQuality(url, base)
      Log.i(TAG, "sorgente diretta trovata: $url (qualità ${qualityInfo.label}, verifiedHeight=${qualityInfo.verifiedHeight})")
      return listOf(
        StreamSource(
          streamUrl = url,
          quality = qualityInfo.label,
          serverName = SERVER_NAME,
          headers = mapOf(
            "Referer" to "$base/",
            "User-Agent" to ExtractorHttp.USER_AGENT
          ),
          declaredQuality = qualityInfo.label,
          verifiedHeight = qualityInfo.verifiedHeight
        )
      )
    }

    // 2) Tag <video> o <source>
    val srcTag = SOURCE_REGEX.find(normalized)?.groupValues?.get(1)
    if (srcTag != null && srcTag.startsWith("http")) {
      val qualityInfo = probeQuality(srcTag, base)
      Log.i(TAG, "sorgente da <source>: $srcTag (qualità ${qualityInfo.label})")
      return listOf(
        StreamSource(
          streamUrl = srcTag,
          quality = qualityInfo.label,
          serverName = SERVER_NAME,
          headers = mapOf(
            "Referer" to "$base/",
            "User-Agent" to ExtractorHttp.USER_AGENT
          ),
          declaredQuality = qualityInfo.label,
          verifiedHeight = qualityInfo.verifiedHeight
        )
      )
    }

    // 3) Iframe annidati (massimo 2 livelli)
    val iframes = IFRAME_REGEX.findAll(normalized)
      .map { it.groupValues[1] }
      .filter { !it.contains("youtube") && !it.contains("histats") && !it.contains("google") }
      .toList()

    for (iframe in iframes) {
      currentCoroutineContext().ensureActive()
      val iframeUrl = when {
        iframe.startsWith("http") -> iframe
        iframe.startsWith("//") -> "https:$iframe"
        else -> originOf(usedUrl) + iframe
      }
      try {
        val nested = http(iframeUrl, usedUrl).replace("\\/", "/")
        val nestedDirect = MEDIA_REGEX.findAll(nested).map { it.groupValues[1] }.distinct().toList()
        if (nestedDirect.isNotEmpty()) {
          val url = nestedDirect.first()
          val qualityInfo = probeQuality(url, originOf(iframeUrl))
          return listOf(
            StreamSource(
              streamUrl = url,
              quality = qualityInfo.label,
              serverName = SERVER_NAME,
              headers = mapOf(
                "Referer" to iframeUrl,
                "User-Agent" to ExtractorHttp.USER_AGENT
              ),
              declaredQuality = qualityInfo.label,
              verifiedHeight = qualityInfo.verifiedHeight
            )
          )
        }
      } catch (ex: CancellationException) {
        throw ex
      } catch (ex: Exception) {
        Log.d(TAG, "Iframe nested $iframeUrl non risolto: ${ex.message}")
      }
    }

    return emptyList()
  }

  data class QualityInfo(val label: String, val verifiedHeight: Int?)

  private suspend fun probeQuality(url: String, referer: String): QualityInfo {
    if (url.endsWith(".mp4", ignoreCase = true)) {
      val h = when {
        url.contains("1080", ignoreCase = true) -> 1080
        url.contains("720", ignoreCase = true) -> 720
        url.contains("480", ignoreCase = true) -> 480
        else -> null
      }
      return QualityInfo(if (h != null) "${h}p" else "Auto", h)
    }
    return try {
      val playlist = http(url, "$referer/")
      if (!playlist.startsWith("#EXTM3U")) {
        QualityInfo("Auto", null)
      } else if (playlist.contains("1080") || playlist.contains("1920x1080")) {
        QualityInfo("1080p", 1080)
      } else if (playlist.contains("720") || playlist.contains("1280x720")) {
        QualityInfo("720p", 720)
      } else if (playlist.contains("480") || playlist.contains("854x480")) {
        QualityInfo("480p", 480)
      } else {
        QualityInfo("Auto", null)
      }
    } catch (e: Exception) {
      QualityInfo("Auto", null)
    }
  }

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}"
  } catch (e: Exception) {
    ""
  }

  companion object {
    private const val TAG = "MultiEmbedProvider"
    private const val PROVIDER_ID = "MultiEmbedProvider"
    const val SERVER_NAME = "MultiEmbed"

    /** Mirror principali MultiEmbed e SuperEmbed */
    private val BASE_URLS = linkedMapOf(
      "MultiEmbed" to "https://multiembed.mov",
      "SuperEmbed" to "https://superembed.stream"
    )

    private val MEDIA_REGEX = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
    private val IFRAME_REGEX = Regex("""<iframe[^>]{0,400}?src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val SOURCE_REGEX = Regex("""<(?:video|source)[^>]*\ssrc\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val CAPTCHA_GATE_REGEX = Regex("""captcha-gate|challenges\.cloudflare\.com/turnstile""", RegexOption.IGNORE_CASE)
  }
}
