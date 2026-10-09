package com.example.data.streaming

import com.example.domain.model.Subtitle

/**
 * Stato della cache TorBox di una sorgente torrent.
 *
 *  - [Cached]: confermata disponibile in cache istantanea (`checkcached` = true).
 *  - [NotCached]: verificata e NON in cache.
 *  - [Unknown]: stato non noto. NON va mai considerato "cached": in UI viene
 *    rappresentato in modo neutro.
 */
enum class CacheState { Cached, NotCached, Unknown }

/**
 * Sorgente video singola per streaming Debrid (TorBox Instant).
 *
 * NOTA: `streamUrl` e' NULL fino al click dell'utente. Il flusso viene sbloccato
 * su richiesta (on-demand) al momento della selezione.
 *
 * @param addonName nome dell'addon Stremio di origine (es. "IlCorsaroViola IT").
 * @param instantTag tag istantanea (es. "1080p TB Instant").
 * @param releaseTitle nome del file rilascio (es. "See.S01E01.1080p.ITA-ENG.mkv").
 * @param details dettagli size/peer (es. "2.18 GB | 👤 474").
 * @param infoHash hash torrent per lo sblocco TorBox.
 * @param resolution risoluzione video ("4K", "1080p", "720p", "480p").
 * @param isItalian true se la sorgente e' in italiano.
 * @param codec codec video ("H264", "HEVC", "AV1").
 * @param streamUrl URL diretto NULL fino al click dell'utente! Sbloccato on-demand.
 * @param subtitles sottotitoli esterni gia' risolti dal bridge per questa sorgente.
 */
data class StreamSource(
  val streamUrl: String? = null,
  val quality: String = "Auto",
  val serverName: String,
  val headers: Map<String, String> = emptyMap(),
  val declaredQuality: String = quality,
  val verifiedHeight: Int? = null,
  val isProgressive: Boolean? = null,
  val isItalian: Boolean = false,
  val addonName: String? = null,
  val instantTag: String? = null,
  val releaseTitle: String? = null,
  val details: String? = null,
  val infoHash: String? = null,
  val fileIdx: Int? = null,
  val codec: String? = null,
  val isCached: Boolean = false,
  /**
   * Stato cache esplicito. Per compatibilità, se non specificato deriva da
   * [isCached]: `true` → [CacheState.Cached], `false` → [CacheState.Unknown]
   * (uno stato mancante NON viene mai classificato come cached).
   */
  val cacheState: CacheState = if (isCached) CacheState.Cached else CacheState.Unknown,
  val releaseType: String? = null,
  /** Sottotitoli esterni associati a questa sorgente (addon Stremio). */
  val subtitles: List<Subtitle> = emptyList(),
) {
  val effectiveHeight: Int
    get() = verifiedHeight ?: when {
      quality.contains("4k", ignoreCase = true) || quality.contains("2160") -> 2160
      quality.contains("1080") -> 1080
      quality.contains("720") -> 720
      quality.contains("480") -> 480
      else -> 0
    }

  val isVerifiedFhdOrHigher: Boolean
    get() = (verifiedHeight ?: 0) >= 1080

  val resolution: String
    get() = when {
      effectiveHeight >= 2160 -> "4K"
      effectiveHeight >= 1080 -> "1080p"
      effectiveHeight >= 720 -> "720p"
      effectiveHeight >= 480 -> "480p"
      else -> "Auto"
    }

  /**
   * Lingue audio riconosciute nel testo della release/nome file quando l'addon
   * le dichiara in modo esplicito (es. `ITA-ENG`, `MULTI`). Lista vuota se non
   * deducibili: nessun metadato inventato.
   */
  val detectedAudioLanguages: List<String>
    get() = parseAudioLanguages(
      listOfNotNull(releaseTitle, serverName, details, addonName, instantTag).joinToString(" ")
    )

  @Deprecated("Use streamUrl instead", ReplaceWith("streamUrl"))
  val url: String
    get() = streamUrl ?: ""

  companion object {
    private val ITALIAN_KEYWORDS = listOf("ita", "italian", "italiano", "ilcorsaroviola", "corsaro")

    fun isItalianSource(serverName: String): Boolean {
      val lower = serverName.lowercase()
      return ITALIAN_KEYWORDS.any { lower.contains(it) }
    }

    /** Token lingua audio riconosciuti nei nomi release (word-boundary, case-insensitive). */
    private val AUDIO_LANGUAGE_TOKENS: List<Pair<Regex, String>> = listOf(
      "Italiano" to listOf("ita", "italian", "italiano", "italiana"),
      "English" to listOf("eng", "english", "inglese"),
      "Español" to listOf("spa", "esp", "spanish", "spagnolo"),
      "Français" to listOf("fre", "fra", "french", "francese"),
      "Deutsch" to listOf("ger", "deu", "german", "tedesco"),
      "Português" to listOf("por", "portuguese", "portoghese"),
      "日本語" to listOf("jpn", "jap", "japanese", "giapponese"),
      "한국어" to listOf("kor", "korean", "coreano"),
      "中文" to listOf("chi", "zho", "chinese", "cinese"),
      "Русский" to listOf("rus", "russian", "russo"),
      "Polski" to listOf("pol", "polish", "polacco"),
      "Nederlands" to listOf("dut", "nld", "dutch", "olandese"),
    ).map { (label, tokens) ->
      Regex("\\b(${tokens.joinToString("|")})\\b", RegexOption.IGNORE_CASE) to label
    }

    /**
     * Estrae le lingue audio riconosciute dal testo, in ordine stabile.
     * `MULTI`/`DUAL` vengono mostrati solo se non è stata riconosciuta alcuna
     * lingua concreta, come descrittore neutro (mai una lingua inventata).
     */
    fun parseAudioLanguages(text: String): List<String> {
      if (text.isBlank()) return emptyList()
      val found = LinkedHashSet<String>()
      AUDIO_LANGUAGE_TOKENS.forEach { (regex, label) ->
        if (regex.containsMatchIn(text)) found += label
      }
      if (found.isEmpty()) {
        val lower = text.lowercase()
        if (Regex("\\bmulti\\b").containsMatchIn(lower)) found += "Multi"
        else if (Regex("\\bdual\\b").containsMatchIn(lower)) found += "Dual"
      }
      return found.toList()
    }

    fun parseCodec(text: String): String? {
      val lower = text.lowercase()
      return when {
        lower.contains("hevc") || lower.contains("x265") || lower.contains("hvc1") || lower.contains("hdr10") || lower.contains("hdr") -> "HEVC"
        lower.contains("av1") || lower.contains("vp9") -> "AV1/VP9"
        lower.contains("h264") || lower.contains("x264") || lower.contains("avc") -> "H264"
        else -> null
      }
    }

    fun parseReleaseType(text: String): String? {
      val lower = text.lowercase()
      return when {
        lower.contains("web-dl") || lower.contains("webdl") || lower.contains("web rip") -> "WEB-DL"
        lower.contains("bluray") || lower.contains("blu-ray") || lower.contains("bdrip") -> "BluRay"
        lower.contains("hdtv") || lower.contains("tv rip") -> "HDTV"
        lower.contains("dvdrip") || lower.contains("dvdr") -> "DVD"
        lower.contains("hdr") -> "HDR"
        lower.contains("dv") || lower.contains("dolby vision") -> "Dolby Vision"
        else -> null
      }
    }
  }
}

/**
 * Stato del ciclo di ricerca delle sorgenti (estrazione provider -> player).
 */
sealed interface StreamResult {
  /** Nessuna ricerca in corso. */
  object Idle : StreamResult

  /** Estrazione in corso: la UI mostra il feedback di caricamento. */
  data class Loading(val message: String) : StreamResult

  /** Estrazione riuscita: la prima sorgente è quella riprodotta. */
  data class Success(val sources: List<StreamSource>) : StreamResult

  /** Estrazione fallita: la UI può mostrare il motivo (fallback demo). */
  data class Error(val message: String) : StreamResult
}

/**
 * Ordinamento deterministico dei risultati TorBox mostrati nella schermata di
 * selezione sorgente. Funzione pura e verificabile, applicata prima di mostrare
 * la lista.
 *
 * Criteri, in ordine di priorità:
 *  1. risultati **cached** prima di quelli non cached; lo stato **sconosciuto**
 *     non viene mai trattato come cached (stessa fascia dei non cached, senza
 *     penalizzazione basata su un dato mancante);
 *  2. risoluzione più alta prima (2160p/4K → 1080p → 720p → 480p → Auto);
 *  3. a parità di risoluzione: qualità video e completezza dei metadati
 *     (codec HEVC/AV1 > H264, indicatori HDR, numero di campi valorizzati);
 *  4. a parità dei criteri precedenti: dimensione file maggiore prima;
 *  5. criterio stabile finale (nome file, server, infoHash) per un ordine
 *     riproducibile.
 */
object TorBoxSourceOrdering {

  fun sort(sources: List<StreamSource>): List<StreamSource> = sources.sortedWith(
    compareBy<StreamSource> { cacheTier(it) }
      .thenByDescending { resolutionHeight(it) }
      .thenByDescending { videoQualityScore(it) }
      .thenByDescending { sizeBytesOf(it) }
      .thenBy { stableKey(it) }
  )

  /** 0 = cached, 1 = non cached o stato sconosciuto (mai classificato cached). */
  internal fun cacheTier(source: StreamSource): Int =
    if (source.cacheState == CacheState.Cached) 0 else 1

  /** Altezza effettiva stimata dalla risoluzione dichiarata (0 = ignota). */
  internal fun resolutionHeight(source: StreamSource): Int {
    source.verifiedHeight?.let { return it }
    val q = "${source.quality} ${source.declaredQuality}".lowercase()
    return when {
      q.contains("4k") || q.contains("2160") || q.contains("uhd") -> 2160
      q.contains("1080") || q.contains("fhd") -> 1080
      q.contains("720") -> 720
      q.contains("480") || q.contains("sd") -> 480
      else -> 0
    }
  }

  /** Punteggio combinato qualità video + completezza metadati (solo campi presenti). */
  internal fun videoQualityScore(source: StreamSource): Int {
    val codec = source.codec?.lowercase().orEmpty()
    val codecScore = when {
      codec.contains("hevc") || codec.contains("h265") || codec.contains("x265") ||
        codec.contains("av1") || codec.contains("vp9") -> 3
      codec.contains("h264") || codec.contains("x264") || codec.contains("avc") -> 1
      else -> 0
    }
    val hdrScore = if (hdrLabel(source) != null) 2 else 0
    val completeness = listOf(
      source.releaseTitle,
      source.details,
      source.codec,
      source.releaseType,
      source.instantTag,
      source.addonName,
    ).count { !it.isNullOrBlank() }
    return codecScore + hdrScore + completeness
  }

  /** Etichetta HDR derivata dai metadati testuali già disponibili, o null. */
  internal fun hdrLabel(source: StreamSource): String? {
    val text = listOfNotNull(source.releaseType, source.releaseTitle, source.details, source.instantTag)
      .joinToString(" ")
      .lowercase()
    return when {
      text.contains("dolby vision") || text.contains("dovi") -> "Dolby Vision"
      text.contains("hdr10+") -> "HDR10+"
      text.contains("hdr10") -> "HDR10"
      text.contains("hdr") || text.contains("hlg") -> "HDR"
      else -> null
    }
  }

  /** Dimensione in byte dedotta da `details`/nome file (0 se ignota: dato neutro). */
  internal fun sizeBytesOf(source: StreamSource): Long =
    parseSizeBytes(source.details)
      ?: parseSizeBytes(source.releaseTitle)
      ?: 0L

  /**
   * Converte una dimensione testuale ("2.18 GB", "700 MB") in byte.
   * Restituisce null se non riconosciuta: nessun valore inventato.
   */
  internal fun parseSizeBytes(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    val match = SIZE_REGEX.find(text) ?: return null
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].uppercase().firstOrNull()) {
      'T' -> 1024.0 * 1024 * 1024 * 1024
      'G' -> 1024.0 * 1024 * 1024
      'M' -> 1024.0 * 1024
      'K' -> 1024.0
      else -> 1.0
    }
    return (value * multiplier).toLong()
  }

  /** Chiave stabile finale (nome file → server → infoHash → qualità), lowercase. */
  internal fun stableKey(source: StreamSource): String = listOfNotNull(
    source.releaseTitle?.takeIf { it.isNotBlank() },
    source.serverName.takeIf { it.isNotBlank() },
    source.infoHash?.takeIf { it.isNotBlank() },
    source.quality,
  ).joinToString("|").lowercase()

  private val SIZE_REGEX =
    Regex("(\\d+(?:[.,]\\d+)?)\\s*(TB|TiB|GB|GiB|MB|MiB|KB|KiB|B)\\b", RegexOption.IGNORE_CASE)
}
