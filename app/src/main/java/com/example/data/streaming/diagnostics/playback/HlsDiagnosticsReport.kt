package com.example.data.streaming.diagnostics.playback

/**
 * Protocollo di trasporto della sorgente osservata dalla sessione.
 */
enum class StreamProtocol { HTTPS, HTTP, UNKNOWN }

/** Tipo di sorgente: playlist HLS o file progressivo. */
enum class SourceKind { HLS, PROGRESSIVE, UNKNOWN }

/**
 * Evento HTTP registrato durante una sessione di riproduzione.
 *
 * Nessun URL completo: solo [hostId] (host sanificato da
 * [com.example.data.streaming.diagnostics.StreamDiagnostics.hostId]) e i metadati
 * della risposta. [redirectCount] è il numero di salti osservati (0 se nessuno);
 * [contentLengthBytes] è `null` quando l'header non è presente o non valido.
 */
data class SessionHttpEvent(
  val phase: PlaybackPhase,
  val hostId: String?,
  val httpStatus: Int?,
  val durationMs: Long,
  val contentType: String?,
  val contentLengthBytes: Long?,
  val bytesLoaded: Long,
  val redirectCount: Int,
  val timestampMs: Long,
  val isError: Boolean
)

/** Evento di buffering (rebuffer): inizio relativo alla sessione e durata. */
data class SessionBufferingEvent(
  val startElapsedMs: Long,
  val durationMs: Long
)

/**
 * Report strutturato di UNA sessione di riproduzione.
 *
 * Contenuti NON ammessi per costruzione (verificati dai test): token, cookie,
 * API key, URL con query string credenziali, dati personali dell'utente. Gli
 * URL sono ridotti all'host; i messaggi passano dalla sanificazione di
 * [com.example.data.streaming.diagnostics.StreamDiagnostics].
 *
 * Le tre qualità sono tenute DISTINTE di proposito:
 *  - [declaredResolution]: dichiarata dal provider/playlist (può essere sbagliata);
 *  - [selectedResolution]: etichetta della variante/traccia selezionata;
 *  - [detectedResolution]: etichetta delle dimensioni video REALI renderizzate.
 */
data class HlsDiagnosticsReport(
  /** Identificativo della sessione (progressivo per processo). */
  val sessionId: Long,
  /** Provider della sorgente (`StreamProvider.providerId` o serverName), se noto. */
  val providerId: String?,
  /** Etichetta neutra del contenuto (titolo del media), opzionale. */
  val contentLabel: String?,
  val sourceKind: SourceKind,
  val protocol: StreamProtocol,
  /** Tipo di playlist rilevato (master/media/ignoto) per le sorgenti HLS. */
  val playlistKind: PlaylistKind,
  /** Altezza dichiarata dalla playlist/provider, se nota. */
  val declaredResolution: String?,
  /** Etichetta della variante/traccia selezionata, se nota. */
  val selectedResolution: String?,
  /** Etichetta delle dimensioni video reali rilevate dal player. */
  val detectedResolution: String?,
  /** Codec dichiarati dal flusso (playlist o traccia attiva), senza inventarli. */
  val codec: String?,
  /** Stato del player al momento del report. */
  val playbackState: PlaybackStateKind,
  /** Tempo di avvio (primo frame renderizzato) dall'inizio sessione, se raggiunto. */
  val startupMs: Long?,
  /** Numero di rebuffer osservati. */
  val rebufferCount: Int,
  /** Durata cumulativa dei rebuffer. */
  val rebufferTotalMs: Long,
  /** Eventi di buffering con inizio e durata. */
  val bufferingEvents: List<SessionBufferingEvent>,
  /** Richieste HTTP della sessione (limitate: vedi [HlsPlaybackSession.MAX_HTTP_EVENTS]). */
  val httpEvents: List<SessionHttpEvent>,
  /** Segmenti media completati con successo. */
  val mediaSegmentCount: Int,
  /** Byte totali dei segmenti media completati. */
  val mediaSegmentBytesTotal: Long,
  /** Errori HTTP classificati (categoria HTTP_ERROR o AUTHORIZATION_ERROR). */
  val httpErrors: List<PlaybackDiagnosis>,
  /** Errori HLS (manifest/segmento), esclusi gli HTTP puri. */
  val hlsErrors: List<PlaybackDiagnosis>,
  /** Errori decoder/formato. */
  val decoderErrors: List<PlaybackDiagnosis>,
  /** Tutte le diagnosi registrate, in ordine cronologico. */
  val diagnoses: List<PlaybackDiagnosis>,
  /** Descrizione dell'ultimo evento riuscito (fase + host), se esiste. */
  val lastSuccessfulEvent: String?,
  /** Durata totale osservata della sessione. */
  val totalDurationMs: Long,
  /** Diagnosi finale della sessione: null se la riproduzione non ha segnalato problemi. */
  val finalDiagnosis: PlaybackDiagnosis?,

  // ── Nova Playback Inspector (metriche live, tutte opzionali) ─────────────
  /** Posizione corrente di riproduzione in ms (-1 = mai osservata). */
  val positionMs: Long = -1L,
  /** Posizione bufferizzata in ms (-1 = mai osservata). */
  val bufferedPositionMs: Long = -1L,
  /** Durata del contenuto in ms (-1 = sconosciuta). */
  val durationMs: Long = -1L,
  /** Secondi di buffer davanti alla posizione corrente, se calcolabili. */
  val bufferAheadMs: Long? = null,
  /** Stima della banda misurata dal player (bit/s, -1 = non disponibile). */
  val bandwidthEstimateBps: Long = -1L,
  /** Throughput medio dei segmenti riusciti (byte/s), null = nessun campione. */
  val throughputBps: Long? = null,
  /** Frame video scartati dal decoder nella sessione. */
  val droppedVideoFrames: Long = 0L,
  /** Formato della traccia video attiva, se osservato. */
  val videoTrack: ObservedTrackFormat? = null,
  /** Formato della traccia audio attiva, se osservata. */
  val audioTrack: ObservedTrackFormat? = null,
  /** Formato della traccia sottotitoli attiva, se osservata. */
  val subtitleTrack: ObservedTrackFormat? = null
) {
  /** Tipo di playlist osservato dal parser HLS. */
  enum class PlaylistKind { MASTER, MEDIA, UNKNOWN, NOT_HLS }

  /** `true` se la diagnosi finale esiste ed è una condizione temporanea. */
  val hasTransientProblem: Boolean
    get() = finalDiagnosis?.temporary == true

  /** Categoria della diagnosi finale, se presente. */
  val finalCategory: HlsErrorCategory?
    get() = finalDiagnosis?.category
}
