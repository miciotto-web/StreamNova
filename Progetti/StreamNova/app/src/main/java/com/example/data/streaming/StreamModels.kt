package com.example.data.streaming

/**
 * Sorgente video singola derivata da un provider di streaming HTTP diretto
 * (senza servizi Debrid).
 *
 * @param url URL diretto del flusso: playlist HLS `.m3u8` (consigliata, supporta
 *        adattiva/audio/sottotitoli) oppure file `.mp4` HTTP.
 * @param quality etichetta qualità ("1080p", "720p", "Auto" per master adattivo).
 * @param serverName nome del server/origine che serve il flusso (es. "VixSrc").
 * @param headers header obbligatori per la riproduzione (Referer, User-Agent...).
 *        Senza di essi molti CDN rispondono 403 Forbidden.
 */
data class StreamSource(
  val url: String,
  val quality: String = "Auto",
  val serverName: String,
  val headers: Map<String, String> = emptyMap(),
  val declaredQuality: String = quality,
  val verifiedHeight: Int? = null,
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
