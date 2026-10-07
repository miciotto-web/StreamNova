package com.example.data.streaming

import com.example.domain.model.Subtitle

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

  @Deprecated("Use streamUrl instead", ReplaceWith("streamUrl"))
  val url: String
    get() = streamUrl ?: ""

  companion object {
    private val ITALIAN_KEYWORDS = listOf("ita", "italian", "italiano", "ilcorsaroviola", "corsaro")

    fun isItalianSource(serverName: String): Boolean {
      val lower = serverName.lowercase()
      return ITALIAN_KEYWORDS.any { lower.contains(it) }
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
