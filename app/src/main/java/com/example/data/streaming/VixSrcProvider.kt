package com.example.data.streaming

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.data.streaming.diagnostics.record
import com.example.data.streaming.extractors.awaitResponse

/**
 * Estrattore del provider **VixSrc / VixCloud** (streaming HTTP diretto, no Debrid).
 *
 * Catena di risoluzione verificata:
 *  1. `GET {base}/api/movie/{tmdb}` oppure `GET {base}/api/tv/{tmdb}/{stagione}/{episodio}`
 *     -> JSON `{"src": "/embed/{id}?token=...&expires=...&canPlayFHD=1"}`.
 *  2. `GET {base}{src}` (pagina embed) -> HTML con `window.streams` (Server1/Server2),
 *     `window.masterPlaylist.params` (token/expires) e `window.canPlayFHD`.
 *  3. Costruzione dell'URL della **master playlist HLS**: `streams[i].url` + i params
 *     non vuoti + `h=1` se il titolo supporta l'FHD. I params vuoti (es. `asn`) NON
 *     vanno inviati: la presenza di un parametro vuoto fa rispondere 403 Forbidden.
 *  4. Validazione della playlist (deve iniziare con `#EXTM3U`) usando esattamente gli
 *     header che poi userà ExoPlayer, così un problema di 403 viene scoperto qui.
 *
 * Mirror di fallback (in ordine): vixsrc.to (primario), vixcloud.to, vixcloud.cc,
 * vixcloud.co. Se il primario non risponde si passa al mirror successivo.
 *
 * Se la master playlist dichiara più tracce audio, il provider espone due
 * sorgenti distinte per film ed episodi: **VixCloud ITA** (doppiaggio italiano)
 * e **VixCloud SUB ITA** (audio originale + sottotitoli italiani). La scelta
 * viene trasmessa al player tramite i metadati della [StreamSource].
 *
 * Gli header `Referer` + `User-Agent` sono OBBLIGATORI: senza Referer il CDN degli
 * HLS segmenti (vix-content.net / edge) risponde 403 Forbidden.
 */
class VixSrcProvider(
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build(),
  private val diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE
) : StreamProvider {

  private data class EmbedStream(val name: String, val url: String)

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    if (tmdbId <= 0) throw IOException("TMDB ID non valido: $tmdbId")

    Log.i(TAG, "VixSrc invocato per ${if (isTv) "Serie TV" else "Film"} (TMDB=$tmdbId, S=$season, E=$episode, titolo='$title', anno=$year)")

    val failures = mutableListOf<String>()
    for (base in BASE_URLS) {
      currentCoroutineContext().ensureActive()
      val startedAt = System.currentTimeMillis()
      val mirrorId = StreamDiagnostics.hostId(base)
      try {
        val sources = resolveOnBase(base, tmdbId, isTv, season, episode)
        if (sources.isNotEmpty()) {
          Log.i(TAG, "Estrazione riuscita su $base: ${sources.size} sorgente/i prodotte e consegnate alla pipeline")
          diagnostics.record(
            providerId = PROVIDER_ID,
            mirrorId = mirrorId,
            outcome = StreamAttemptOutcome.SUCCESS,
            durationMs = System.currentTimeMillis() - startedAt
          )
          return@withContext sources
        }
        failures += "$base -> nessuna sorgente prodotta"
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
        Log.w(TAG, "Fallback mirror $base fallito: ${e.message}")
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
    throw IOException("Nessun mirror VixSrc disponibile (${failures.joinToString(" | ")})")
  }

  private suspend fun resolveOnBase(
    base: String,
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?
  ): List<StreamSource> {
    // --- 1) API di risoluzione titolo -> embed -----------------------------
    val apiPath = if (isTv) {
      "/api/tv/$tmdbId/${(season ?: 1).coerceAtLeast(1)}/${(episode ?: 1).coerceAtLeast(1)}"
    } else {
      "/api/movie/$tmdbId"
    }
    val apiUrl = if ('?' in apiPath) "$base$apiPath&canPlayFHD=1&h=1" else "$base$apiPath?canPlayFHD=1&h=1"
    Log.i(TAG, "GET API  ${sanitizeForLog(apiUrl)}")
    val apiBody = get(apiUrl, referer = "$base/")
    val src = JSONObject(apiBody).optString("src", "")?.trim().orEmpty()
    if (src.isEmpty()) throw IOException("Risposta API senza campo 'src': ${apiBody.take(160)}")

    val embedPath = if (src.startsWith("http")) {
      try {
        val uri = java.net.URI(src)
        uri.rawPath + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
      } catch (e: Exception) {
        "/" + src.substringAfter("://").substringAfter("/")
      }
    } else {
      src
    }

    // --- 2) Pagina embed -> streams, params, canPlayFHD --------------------
    // Prova prima la base corrente e, se fallisce (es. HTTP 410), tenta con lo stesso src sui mirror di fallback
    val candidateBases = listOf(base) + BASE_URLS.filter { it != base }
    var embedHtml: String? = null
    var activeBase = base
    var lastEmbedError: Exception? = null

    for (candidateBase in candidateBases) {
      currentCoroutineContext().ensureActive()
      val rawEmbed = if (embedPath.startsWith("/")) "$candidateBase$embedPath" else "$candidateBase/$embedPath"
      val embedUrl = buildString {
        append(rawEmbed)
        if (!rawEmbed.contains("canPlayFHD=")) {
          append(if ('?' in rawEmbed) "&canPlayFHD=1" else "?canPlayFHD=1")
        }
        if (!rawEmbed.contains("h=")) {
          append(if ('?' in this) "&h=1" else "?h=1")
        }
      }
      Log.i(TAG, "GET EMBED ${sanitizeForLog(embedUrl)}")
      try {
        val html = get(embedUrl, referer = "$candidateBase/")
        if (html.contains("window.streams")) {
          embedHtml = html
          activeBase = candidateBase
          break
        } else {
          Log.w(TAG, "Embed su $candidateBase senza window.streams")
        }
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        Log.w(TAG, "Fallback embed mirror $candidateBase: ${e.message}")
        lastEmbedError = e
      }
    }

    val page = embedHtml ?: throw (lastEmbedError ?: IOException("Nessun mirror embed valido per $src"))
    val streamsJson = extractBlock(page, "window.streams", '[', ']')
      ?: throw IOException("window.streams non trovato nell'embed")
    val streamsArray = JSONArray(streamsJson)
    val masterBlock = extractBlockBalanced(page, "window.masterPlaylist")
      ?: extractBlock(page, "window.masterPlaylist", '{', ';')
      ?: throw IOException("window.masterPlaylist non trovato nell'embed")

    val masterUrl = Regex("""url\s*:\s*['"]([^'"]+)['"]""").find(masterBlock)?.groupValues?.get(1)
      ?: throw IOException("masterPlaylist.url non trovato")
    val paramsBlock = substringBetween(masterBlock, "params", '{', '}')
    val params = PAIR_REGEX.findAll(paramsBlock)
      .associate { it.groupValues[1] to it.groupValues[2] }

    val canPlayFhd = Regex("""canPlayFHD\s*=\s*(true|false)""").find(page)
      ?.groupValues?.get(1) != "false"
    Log.i(TAG, "masterPlaylist=${sanitizeForLog(masterUrl)} params=${params.keys} canPlayFHD=$canPlayFhd")

    // --- 3) Costruzione URL playlist per ogni server (Priorità 1080p FHD) ---
    val effectiveReferer = "$activeBase/"
    val headers = mapOf(
      "Referer" to effectiveReferer,
      "User-Agent" to USER_AGENT
    )
    val sources = mutableListOf<StreamSource>()
    for (i in 0 until streamsArray.length()) {
      val entry = streamsArray.optJSONObject(i) ?: continue
      if (!entry.optBoolean("active", true)) continue
      val rawUrl = entry.optString("url", "").trim()
      if (rawUrl.isEmpty()) continue
      val entryName = entry.optString("name", "Mirror")
      val serverName = if (i == 0) SERVER_NAME else "$SERVER_NAME $entryName"

      val playlistUrlFhd = buildString {
        append(rawUrl)
        params.forEach { (key, value) ->
          if (value.isNotEmpty()) {
            append(if ('?' in rawUrl || '?' in this) '&' else '?')
            append(key).append('=').append(value)
          }
        }
        append(if ('?' in rawUrl || '?' in this) '&' else '?').append("h=1")
        append("&canPlayFHD=1")
      }

      val playlistUrlStandard = buildString {
        append(rawUrl)
        params.forEach { (key, value) ->
          if (value.isNotEmpty()) {
            append(if ('?' in rawUrl || '?' in this) '&' else '?')
            append(key).append('=').append(value)
          }
        }
      }

      // Tentativo primario Full HD (h=1), con fallback su standard
      var chosenUrl = playlistUrlFhd
      var playlistBody = try {
        get(chosenUrl, referer = effectiveReferer)
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        Log.d(TAG, "Tentativo playlist FHD non riuscito su $serverName: ${e.message}")
        null
      }
      if (playlistBody == null || !playlistBody.startsWith("#EXTM3U")) {
        chosenUrl = playlistUrlStandard
        playlistBody = try {
          get(chosenUrl, referer = effectiveReferer)
        } catch (ex: CancellationException) {
          throw ex
        } catch (e: Exception) {
          Log.d(TAG, "Tentativo playlist standard non riuscito su $serverName: ${e.message}")
          null
        }
      }
      if (playlistBody == null || !playlistBody.startsWith("#EXTM3U")) {
        Log.w(TAG, "Playlist non HLS o non valida su $serverName (scartata): ${playlistBody?.take(80)}")
        continue
      }
      val renditions = parseRenditions(playlistBody)
      val (detectedQuality, verifiedHeight) = qualityOf(playlistBody)
      val declared = if (chosenUrl.contains("h=1")) "1080p" else "Auto"
      Log.i(TAG, "OK $serverName (declared=$declared, verifiedHeight=$verifiedHeight, renditions=$renditions) url=${sanitizeForLog(chosenUrl)}")

      // Manifest con più tracce audio: VixCloud ITA (audio italiano) e
      // VixCloud SUB ITA (audio originale + sottotitoli italiani). Se il
      // manifest non le dichiara si mantiene la sorgente singola storica.
      val multiAudioAdded = addMultiAudioSources(
        target = sources,
        playlistBody = playlistBody,
        playlistUrl = chosenUrl,
        serverLabel = if (i == 0) MULTI_AUDIO_SERVER_NAME else "$MULTI_AUDIO_SERVER_NAME $entryName",
        quality = detectedQuality,
        declaredQuality = declared,
        fallbackHeight = verifiedHeight,
        headers = headers,
        referer = effectiveReferer
      )
      if (multiAudioAdded) continue

      sources += StreamSource(
        streamUrl = chosenUrl,
        quality = detectedQuality,
        serverName = serverName,
        headers = headers,
        declaredQuality = declared,
        verifiedHeight = verifiedHeight,
        // A-04: il testo della playlist è già in mano al resolver — la sintesi
        // osservata accompagna la sorgente come METADATO diagnostico per il
        // report di riproduzione (nessuna richiesta aggiuntiva, nessun effetto
        // sulla selezione).
        hlsPlaylistSummary = HlsPlaylistSummaryParser.analyze(playlistBody, chosenUrl),
        // La playlist è stata scaricata e validata (`#EXTM3U`) con gli stessi
        // header usati dal player: la sorgente è già verificata dal resolver,
        // non serve una seconda richiesta nella selezione automatica.
        availability = SourceAvailability.Verified
      )
    }
    if (sources.isEmpty()) throw IOException("Nessun server attivo nell'embed per $base")
    return sources
  }

  // --- supporto multi-audio (VixCloud ITA / SUB ITA) ----------------------- //

  private data class AudioRendition(
    val name: String,
    val language: String,
    val isItalian: Boolean
  )

  private data class MultiAudioVariant(
    val label: String,
    val langParam: String,
    val preferredAudioLanguage: String,
    val preferredSubtitleLanguage: String?,
    val subtitlesEnabled: Boolean?,
    val isItalian: Boolean
  )

  /**
   * Aggiunge le varianti **VixCloud ITA** e **VixCloud SUB ITA** dichiarate
   * dalla master playlist.
   *
   * VixCloud espone le tracce nel master HLS:
   *  - `#EXT-X-MEDIA:TYPE=AUDIO,NAME="Italian",LANGUAGE="ita"` per il doppiaggio;
   *  - una traccia audio non italiana (originale);
   *  - `#EXT-X-MEDIA:TYPE=SUBTITLES,...,LANGUAGE="ita"` per i sottotitoli.
   *
   * La variante ITA forza `lang=it`; la SUB ITA forza `lang=<originale>` e
   * chiede al player i sottotitoli italiani tramite i metadati della sorgente.
   * Ogni variante viene scaricata e validata con gli stessi header del player,
   * così [SourceAvailability.Verified] resta un dato reale.
   *
   * @return `true` se almeno una variante è stata aggiunta; `false` quando il
   *         manifest non dichiara tracce multi-audio (si usa la sorgente
   *         singola storica).
   */
  private suspend fun addMultiAudioSources(
    target: MutableList<StreamSource>,
    playlistBody: String,
    playlistUrl: String,
    serverLabel: String,
    quality: String,
    declaredQuality: String,
    fallbackHeight: Int?,
    headers: Map<String, String>,
    referer: String
  ): Boolean {
    val audioTracks = parseAudioRenditions(playlistBody)
    if (audioTracks.isEmpty()) return false

    val italianAudio = audioTracks.firstOrNull { it.isItalian }
    val originalAudio = audioTracks.firstOrNull { !it.isItalian }
    val hasItalianSubtitles = hasItalianSubtitleTrack(playlistBody)

    val variants = mutableListOf<MultiAudioVariant>()
    if (italianAudio != null) {
      variants += MultiAudioVariant(
        label = "ITA",
        langParam = "it",
        preferredAudioLanguage = "it",
        preferredSubtitleLanguage = null,
        subtitlesEnabled = null,
        isItalian = true
      )
    }
    if (originalAudio != null && hasItalianSubtitles) {
      variants += MultiAudioVariant(
        label = "SUB ITA",
        langParam = originalAudio.language,
        preferredAudioLanguage = originalAudio.language,
        preferredSubtitleLanguage = "it",
        subtitlesEnabled = true,
        isItalian = false
      )
    }
    if (variants.isEmpty()) return false

    var added = 0
    for (variant in variants) {
      val variantUrl = withQueryParam(playlistUrl, "lang", variant.langParam)
      val variantBody = try {
        get(variantUrl, referer = referer)
      } catch (ex: CancellationException) {
        throw ex
      } catch (e: Exception) {
        Log.w(TAG, "Variante ${variant.label} non disponibile su $serverLabel: ${e.message}")
        continue
      }
      if (!variantBody.startsWith("#EXTM3U")) {
        Log.w(TAG, "Variante ${variant.label} non HLS su $serverLabel")
        continue
      }
      val (variantQuality, variantHeight) = qualityOf(variantBody)
      target += StreamSource(
        streamUrl = variantUrl,
        quality = if (variantQuality == "Auto") quality else variantQuality,
        serverName = "$serverLabel ${variant.label}",
        headers = headers,
        declaredQuality = declaredQuality,
        verifiedHeight = variantHeight ?: fallbackHeight,
        isItalian = variant.isItalian,
        preferredAudioLanguage = variant.preferredAudioLanguage,
        preferredSubtitleLanguage = variant.preferredSubtitleLanguage,
        subtitlesEnabled = variant.subtitlesEnabled,
        // A-04: sintesi osservativa della playlist di variante (già in mano al
        // resolver), solo per il report di riproduzione.
        hlsPlaylistSummary = HlsPlaylistSummaryParser.analyze(variantBody, variantUrl),
        availability = SourceAvailability.Verified
      )
      added++
    }
    return added > 0
  }

  /** Tracce audio dichiarate da `#EXT-X-MEDIA:TYPE=AUDIO`. */
  private fun parseAudioRenditions(playlist: String): List<AudioRendition> =
    mediaRenditions(playlist, "AUDIO")
      .mapNotNull { attributes ->
        val name = attributes["NAME"].orEmpty()
        val language = attributes["LANGUAGE"].orEmpty()
        val code = languageCode(language, name) ?: return@mapNotNull null
        AudioRendition(name, code, isItalianLanguage(language, name))
      }
      .distinctBy { it.language }

  /** True se il manifest dichiara almeno un sottotitolo italiano. */
  private fun hasItalianSubtitleTrack(playlist: String): Boolean =
    mediaRenditions(playlist, "SUBTITLES").any { attributes ->
      isItalianLanguage(attributes["LANGUAGE"], attributes["NAME"])
    }

  /** Attributi delle righe `#EXT-X-MEDIA` del tipo richiesto. */
  private fun mediaRenditions(playlist: String, type: String): List<Map<String, String>> =
    playlist.lineSequence()
      .map { it.trim() }
      .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
      .map { line ->
        MEDIA_ATTRIBUTE_REGEX.findAll(line.substringAfter(':'))
          .associate { match ->
            match.groupValues[1].uppercase() to match.groupValues[2].removeSurrounding("\"")
          }
      }
      .filter { it["TYPE"].equals(type, ignoreCase = true) }
      .toList()

  private fun isItalianLanguage(language: String?, name: String?): Boolean {
    val lang = language?.trim()?.lowercase().orEmpty()
    val label = name?.trim()?.lowercase().orEmpty()
    return lang == "it" || lang == "ita" ||
      lang.startsWith("it-") || lang.startsWith("it_") ||
      label.contains("italian") || label.contains("italiano")
  }

  /**
   * Codice ISO 639-1 della traccia (es. `ita` -> `it`, `eng` -> `en`), oppure
   * dedotto dal nome quando il manifest usa `LANGUAGE=und`.
   */
  private fun languageCode(language: String?, name: String?): String? {
    val raw = language?.trim()?.lowercase()
      ?.substringBefore('-')
      ?.substringBefore('_')
    if (!raw.isNullOrBlank() && raw != "und") {
      return LANGUAGE_CODES[raw] ?: raw.takeIf { it.length == 2 }
    }
    val label = name?.trim()?.lowercase().orEmpty()
    return when {
      label.contains("italian") || label.contains("italiano") -> "it"
      label.contains("english") || label.contains("inglese") -> "en"
      label.contains("japanese") || label.contains("giapponese") -> "ja"
      label.contains("spanish") || label.contains("spagnolo") -> "es"
      label.contains("french") || label.contains("francese") -> "fr"
      label.contains("german") || label.contains("tedesco") -> "de"
      label.contains("portuguese") || label.contains("portoghese") -> "pt"
      label.contains("korean") || label.contains("coreano") -> "ko"
      label.contains("chinese") || label.contains("cinese") -> "zh"
      else -> null
    }
  }

  /** Qualità dichiarata dalla playlist (stessa logica storica del provider). */
  private fun qualityOf(playlist: String): Pair<String, Int?> {
    val renditions = parseRenditions(playlist)
    val height = when {
      renditions.any { it.contains("1080", ignoreCase = true) } -> 1080
      renditions.any { it.contains("720", ignoreCase = true) } -> 720
      renditions.any { it.contains("480", ignoreCase = true) } -> 480
      else -> null
    }
    val quality = when (height) {
      1080 -> "1080p"
      720 -> "720p"
      480 -> "480p"
      else -> "Auto"
    }
    return quality to height
  }

  private fun withQueryParam(url: String, name: String, value: String): String =
    url + (if ('?' in url) "&" else "?") + name + "=" + value

  /** GET con header da browser; lancia [IOException] su status non 2xx. */
  private suspend fun get(url: String, referer: String?): String {
    val startMs = System.currentTimeMillis()
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", "*/*")
      .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
      .get()
    if (!referer.isNullOrBlank()) request.header("Referer", referer)

    val response = http.newCall(request.build()).awaitResponse()
    response.use { resp ->
      val durationMs = System.currentTimeMillis() - startMs
      val body = resp.body?.string().orEmpty()
      Log.d(TAG, "HTTP ${resp.code} in ${durationMs}ms per ${sanitizeForLog(url)}")
      if (!resp.isSuccessful) {
        throw IOException("HTTP ${resp.code} su ${sanitizeForLog(url)}")
      }
      return body
    }
  }

  /**
   * Estrae il blocco JSON/JS bilanciando le parentesi graffe a partire da [marker].
   * Evita di dipendere dalla presenza accidentale di un punto e virgola ';' a fine istruzione.
   */
  private fun extractBlockBalanced(html: String, marker: String): String? {
    val markerIndex = html.indexOf(marker).takeIf { it >= 0 } ?: return null
    val openIndex = html.indexOf('{', markerIndex + marker.length).takeIf { it >= 0 } ?: return null
    var depth = 0
    var inString = false
    var quoteChar = ' '
    for (i in openIndex until html.length) {
      val c = html[i]
      if (inString) {
        if (c == quoteChar && html.getOrNull(i - 1) != '\\') {
          inString = false
        }
      } else {
        when (c) {
          '\'', '"' -> {
            inString = true
            quoteChar = c
          }
          '{' -> depth++
          '}' -> {
            depth--
            if (depth == 0) {
              return html.substring(openIndex, i + 1)
            }
          }
        }
      }
    }
    return null
  }

  /**
   * Estrae il blocco che inizia dal primo [open] successivo a [marker] e finisce
   * al primo [close] (compreso): es. `window.streams = [ ... ]` -> `[ ... ]`.
   */
  private fun extractBlock(html: String, marker: String, open: Char, close: Char): String? {
    val markerIndex = html.indexOf(marker).takeIf { it >= 0 } ?: return null
    val openIndex = html.indexOf(open, markerIndex + marker.length).takeIf { it >= 0 } ?: return null
    val closeIndex = html.indexOf(close, openIndex + 1).takeIf { it >= 0 } ?: return null
    return html.substring(openIndex, closeIndex + 1)
  }

  /**
   * Estrae il testo tra il primo [open] successivo a [key] e il primo [close]
   * (blocchi annidati non attesi nell'HTML dell'embed).
   * Usato al posto di una regex con graffe: Android/ICU rigetta `\{`.
   */
  private fun substringBetween(source: String, key: String, open: Char, close: Char): String {
    val keyIndex = source.indexOf(key).takeIf { it >= 0 } ?: return ""
    val openIndex = source.indexOf(open, keyIndex + key.length).takeIf { it >= 0 } ?: return ""
    val closeIndex = source.indexOf(close, openIndex + 1).takeIf { it >= 0 } ?: return ""
    return source.substring(openIndex + 1, closeIndex)
  }

  /** Ritorna le risoluzioni dichiarate nella master playlist (uso solo log/telemetria). */
  private fun parseRenditions(masterPlaylist: String): List<String> {
    return Regex("""RESOLUTION=(\d+)x(\d+)""").findAll(masterPlaylist)
      .map { "${it.groupValues[2]}p" }
      .distinct()
      .toList()
  }

  companion object {
    private const val TAG = "VixSrcProvider"
    private const val PROVIDER_ID = "VixSrcProvider"

    /**
     * Redige parametri sensibili (es. token) negli URL prima della registrazione nei log.
     */
    fun sanitizeForLog(url: String): String =
      url.replace(Regex("""(?i)(token=)[^&]+"""), "$1REDACTED")

    /** Coppie `'chiave': 'valore'` del blocco `params` della master playlist (con o senza apici per valori numerici). */
    private val PAIR_REGEX = Regex("""['"]?([a-zA-Z0-9_-]+)['"]?\s*:\s*['"]?([^'",\s}]+)['"]?""")

    /** User-Agent desktop coerente tra estrazione e riproduzione. */
    const val USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Referer obbligatorio: senza di esso il CDN risponde 403 Forbidden. */
    const val REFERER = "https://vixsrc.to/"

    const val SERVER_NAME = "VixSrc"

    /** Etichetta base delle varianti multi-audio (VixCloud ITA / VixCloud SUB ITA). */
    private const val MULTI_AUDIO_SERVER_NAME = "VixCloud"

    /** Attributi delle direttive `#EXT-X-MEDIA` (TYPE, NAME, LANGUAGE, ...). */
    private val MEDIA_ATTRIBUTE_REGEX = Regex("""([A-Za-z0-9-]+)=("[^"]*"|[^,]*)""")

    /** ISO 639-2/3 -> ISO 639-1 per `lang` e per le preferenze ExoPlayer. */
    private val LANGUAGE_CODES = mapOf(
      "ita" to "it",
      "eng" to "en",
      "jpn" to "ja",
      "spa" to "es",
      "fra" to "fr",
      "fre" to "fr",
      "deu" to "de",
      "ger" to "de",
      "por" to "pt",
      "kor" to "ko",
      "zho" to "zh",
      "chi" to "zh",
      "rus" to "ru",
      "ukr" to "uk",
      "nld" to "nl",
      "dut" to "nl",
      "pol" to "pl"
    )

    /** Endpoint primario + mirror di fallback (in ordine di tentativo). */
    private val BASE_URLS = listOf(
      "https://vixsrc.to",
      "https://vixcloud.co",
      "https://vixcloud.to",
      "https://vixcloud.cc"
    )
  }
}
