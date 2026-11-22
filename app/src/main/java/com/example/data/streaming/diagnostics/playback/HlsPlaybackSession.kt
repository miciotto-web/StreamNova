package com.example.data.streaming.diagnostics.playback

import com.example.data.streaming.StreamResolution
import com.example.data.streaming.diagnostics.DiagnosticClock
import com.example.data.streaming.diagnostics.StreamDiagnostics
import java.util.concurrent.atomic.AtomicLong

/**
 * Sintesi di una traccia osservata dal player (video, audio o sottotitoli).
 *
 * Tutti i campi arrivano da Media3 (`Format`/`onTracksChanged`) o dal listener
 * analytics: nessun valore è dedotto o inventato. I campi non disponibili restano
 * `null` e la UI mostra "—".
 */
data class ObservedTrackFormat(
  val kind: String,
  val codecs: String? = null,
  val sampleMimeType: String? = null,
  val language: String? = null,
  val label: String? = null,
  val width: Int = 0,
  val height: Int = 0,
  val frameRate: Float? = null,
  val bitrateBps: Long? = null,
  val channelCount: Int? = null,
  val sampleRateHz: Int? = null
) {
  /** `true` quando la traccia ha almeno un dato tecnico utile. */
  val hasTechnicalData: Boolean
    get() = codecs != null || sampleMimeType != null || bitrateBps != null ||
      width > 0 || height > 0 || channelCount != null || sampleRateHz != null
}

/**
 * Collettore diagnostico di UNA sessione di riproduzione.
 *
 * Collectore **puro e thread-safe**: non dipende da Media3 né da Android. Il
 * listener del player ([com.example.ui.screens.player.PlaybackDiagnosticsListener])
 * traduce gli eventi Media3 in chiamate a questa classe; i test JVM li producono
 * direttamente, in modo deterministico.
 *
 * Punto unico di registrazione (niente duplicazioni): ogni evento entra una
 * sola volta, gli errori simili vengono aggregati nei contatori
 * [PlaybackDiagnosis.attemptCount] invece di moltiplicare le righe.
 *
 * La sessione NON decide nulla: non seleziona provider, non modifica retry né
 * circuit breaker. Produce [HlsDiagnosticsReport] per osservazione e uso
 * successivo.
 */
class HlsPlaybackSession(
  val sessionId: Long,
  /** Provider della sorgente (`sourceProviderId`), se noto al chiamante. */
  val providerId: String?,
  /** Etichetta neutra del contenuto (titolo), opzionale e mai personale. */
  val contentLabel: String? = null,
  private val clock: DiagnosticClock = DiagnosticClock.SYSTEM,
  /** Ora di parete della creazione sessione (iniettabile nei test). */
  private val wallClockEpochMs: () -> Long = { System.currentTimeMillis() },
  /** Soglia di buffering continuo oltre cui la sessione segnala BUFFERING_TIMEOUT. */
  private val bufferingTimeoutThresholdMs: Long = DEFAULT_BUFFERING_TIMEOUT_MS
) {

  /** Inizio monotono della sessione. */
  val startedAtMs: Long = clock.nowMs()

  @Volatile var sourceKind: SourceKind = SourceKind.UNKNOWN
    private set

  @Volatile var playlistKind: HlsDiagnosticsReport.PlaylistKind = HlsDiagnosticsReport.PlaylistKind.UNKNOWN
    private set
  @Volatile var declaredResolution: String? = null
    private set
  @Volatile var selectedResolution: String? = null
    private set
  @Volatile var codec: String? = null
    private set

  private val lock = Any()
  private val httpEvents = ArrayDeque<SessionHttpEvent>()
  private val diagnoses = mutableListOf<PlaybackDiagnosis>()
  private val bufferingEvents = mutableListOf<SessionBufferingEvent>()
  private val errorCounts = HashMap<String, Int>()
  private val segmentCompletions = object {
    var count = 0
    var bytes = 0L
    var durationMs = 0L
  }

  @Volatile private var state = PlaybackStateKind.IDLE
  @Volatile private var bufferingStartedAtMs: Long? = null
  /** Episodio di buffering (indice) per cui è già stata registrata BUFFERING_TIMEOUT. */
  @Volatile private var bufferingTimeoutRecordedEpisode: Int = -1
  @Volatile private var firstFrameElapsedMs: Long? = null
  @Volatile private var detectedWidth = -1
  @Volatile private var detectedHeight = -1
  @Volatile private var lastSuccessfulEvent: String? = null
  @Volatile private var lastCompletedPhase: PlaybackPhase = PlaybackPhase.UNKNOWN
  @Volatile private var lastActivityElapsedMs: Long = 0L
  @Volatile private var sessionEpochMs: Long = wallClockEpochMs()
  @Volatile private var lastPositionMsValue: Long = -1L
  @Volatile private var lastBufferedDurationMsValue: Long = -1L

  // ── Metriche live del Nova Playback Inspector ───────────────────────────────
  @Volatile private var bufferedPositionMsValue: Long = -1L
  @Volatile private var durationMsValue: Long = -1L
  @Volatile private var bandwidthEstimateBpsValue: Long = -1L
  @Volatile private var droppedFramesValue: Long = 0L
  @Volatile private var videoTrack: ObservedTrackFormat? = null
  @Volatile private var audioTrack: ObservedTrackFormat? = null
  @Volatile private var subtitleTrack: ObservedTrackFormat? = null

  // --------------------------------------------------------------------------
  // Informazioni sulla sorgente
  // --------------------------------------------------------------------------

  /** Imposta il tipo di sorgente e il protocollo osservato dall'URL attiva. */
  fun setSource(url: String, kind: SourceKind) {
    synchronized(lock) {
      sourceKind = kind
      // Il protocollo è l'unica informazione letta dall'URL: l'host entra negli
      // eventi solo come hostId sanificato, mai l'URL completa.
      lastScheme = when {
        url.startsWith("https://", ignoreCase = true) -> StreamProtocol.HTTPS
        url.startsWith("http://", ignoreCase = true) -> StreamProtocol.HTTP
        else -> StreamProtocol.UNKNOWN
      }
    }
  }

  @Volatile private var lastScheme: StreamProtocol = StreamProtocol.UNKNOWN

  val protocol: StreamProtocol
    get() = lastScheme

  /** Aggiorna il tipo di playlist dichiarata (dal parser HLS). */
  fun setPlaylistKind(kind: HlsDiagnosticsReport.PlaylistKind) {
    synchronized(lock) { playlistKind = kind }
  }

  /** Qualità dichiarata dal provider/playlist (etichetta già normalizzata). */
  fun setDeclaredResolution(label: String?) {
    synchronized(lock) { declaredResolution = label?.takeIf { it.isNotBlank() } }
  }

  /** Altezza dichiarata convertita in etichetta canonica (null se ignota). */
  fun setDeclaredHeight(height: Int?) {
    setDeclaredResolution(
      height?.takeIf { it > 0 }?.let { StreamResolution.labelForHeight(it) }
    )
  }

  /** Codec dichiarato (da playlist o traccia attiva). */
  fun setCodec(value: String?) {
    synchronized(lock) { codec = value?.takeIf { it.isNotBlank() } }
  }

  /** Etichetta della variante/traccia selezionata (da menu Qualità o track selector). */
  fun setSelectedResolution(label: String?) {
    synchronized(lock) { selectedResolution = label?.takeIf { it.isNotBlank() } }
  }

  // --------------------------------------------------------------------------
  // Eventi HTTP (manifest / variante / segmenti)
  // --------------------------------------------------------------------------

  /**
   * Registra una richiesta HTTP completata (con successo o meno).
   *
   * [url] viene ridotta all'host qui: nel modello non entra mai l'URL completa.
   * [redirectCount] conta i salti osservati dal chiamante (0 = nessuno).
   */
  fun onHttpCompleted(
    url: String,
    phase: PlaybackPhase,
    httpStatus: Int?,
    durationMs: Long,
    contentType: String? = null,
    contentLengthBytes: Long? = null,
    bytesLoaded: Long = 0L,
    redirectCount: Int = 0,
    isError: Boolean = false
  ) {
    val hostId = StreamDiagnostics.hostId(url)
    val event = SessionHttpEvent(
      phase = phase,
      hostId = hostId,
      httpStatus = httpStatus,
      durationMs = durationMs.coerceAtLeast(0L),
      contentType = contentType?.takeIf { it.isNotBlank() },
      contentLengthBytes = contentLengthBytes?.takeIf { it >= 0 },
      bytesLoaded = bytesLoaded.coerceAtLeast(0L),
      redirectCount = redirectCount.coerceAtLeast(0),
      timestampMs = clock.nowMs(),
      isError = isError
    )
    synchronized(lock) {
      if (httpEvents.size >= MAX_HTTP_EVENTS) httpEvents.removeFirst()
      httpEvents.addLast(event)
      lastActivityElapsedMs = event.timestampMs - startedAtMs
      if (!isError) {
        lastCompletedPhase = phase
        lastSuccessfulEvent = "${phase.name.lowercase()}:${hostId ?: "unknown"}"
        if (phase == PlaybackPhase.MEDIA_SEGMENT || phase == PlaybackPhase.INIT_SEGMENT) {
          segmentCompletions.count++
          segmentCompletions.bytes += event.bytesLoaded
          segmentCompletions.durationMs += event.durationMs
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Stato del player e buffering
  // --------------------------------------------------------------------------

  /** Aggiorna lo stato osservato del player e conta i rebuffer. */
  fun onPlayerStateChanged(newState: PlaybackStateKind, positionMs: Long = -1L, bufferedDurationMs: Long = -1L) {
    synchronized(lock) {
      val now = clock.nowMs()
      if (newState == PlaybackStateKind.BUFFERING && state != PlaybackStateKind.BUFFERING) {
        // Rebuffer: BUFFERING raggiunto da READY/ENDED (IDLE iniziale escluso).
        if (state == PlaybackStateKind.READY || state == PlaybackStateKind.ENDED) {
          bufferingEvents += SessionBufferingEvent(
            startElapsedMs = (now - startedAtMs).coerceAtLeast(0L),
            durationMs = 0L
          )
        }
        bufferingStartedAtMs = now
      } else if (newState != PlaybackStateKind.BUFFERING && state == PlaybackStateKind.BUFFERING) {
        closeLastBufferingEvent(now)
      }
      state = newState
      if (positionMs >= 0) lastPositionMsValue = positionMs
      if (bufferedDurationMs >= 0) lastBufferedDurationMsValue = bufferedDurationMs
      lastActivityElapsedMs = (now - startedAtMs).coerceAtLeast(0L)
    }
  }

  /** Ultima posizione di riproduzione osservata (-1 se mai aggiornata). */
  val lastPositionMs: Long get() = lastPositionMsValue

  /** Ultima durata di buffer osservata in ms (-1 se mai aggiornata). */
  val lastBufferedDurationMs: Long get() = lastBufferedDurationMsValue

  private fun closeLastBufferingEvent(nowMs: Long) {
    val started = bufferingStartedAtMs ?: return
    bufferingStartedAtMs = null
    val duration = (nowMs - started).coerceAtLeast(0L)
    val last = bufferingEvents.lastOrNull() ?: return
    if (last.durationMs == 0L) {
      bufferingEvents[bufferingEvents.size - 1] = last.copy(durationMs = duration)
    }
  }

  /** Primo frame renderizzato: misura il tempo di avvio. */
  fun onFirstFrameRendered() {
    synchronized(lock) {
      if (firstFrameElapsedMs == null) {
        firstFrameElapsedMs = (clock.nowMs() - startedAtMs).coerceAtLeast(0L)
      }
    }
  }

  /** Dimensioni video REALI osservate dal player (onVideoSizeChanged). */
  fun onVideoSizeChanged(width: Int, height: Int) {
    synchronized(lock) {
      detectedWidth = width
      detectedHeight = height
    }
  }

  val detectedResolution: String?
    get() = if (detectedWidth <= 0 && detectedHeight <= 0) {
      null
    } else {
      StreamResolution.labelForMeasured(detectedWidth, detectedHeight)
    }

  /** Codec osservato dalla traccia attiva (aggiorna quello dichiarato se presente). */
  fun onActiveTrackCodec(sampleMimeType: String?, codecsString: String?) {
    setCodec(codecsString ?: sampleMimeType)
  }

  // --------------------------------------------------------------------------
  // Nova Playback Inspector: metriche live
  // --------------------------------------------------------------------------

  /**
   * Aggiorna la fotografia live della riproduzione (posizione, buffer, durata).
   *
   * Chiamata dal tick osservativo del player (1 s): O(1), nessun effetto sul
   * playback. Valori negativi (player non ancora pronto) vengono ignorati per il
   * singolo campo, così una durata ancora sconosciuta non cancella l'ultima nota.
   */
  fun updateLivePlayback(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    bufferedDurationMs: Long = -1L
  ) {
    synchronized(lock) {
      if (positionMs >= 0) lastPositionMsValue = positionMs
      if (bufferedPositionMs >= 0) bufferedPositionMsValue = bufferedPositionMs
      if (durationMs > 0) durationMsValue = durationMs
      if (bufferedDurationMs >= 0) lastBufferedDurationMsValue = bufferedDurationMs
      lastActivityElapsedMs = (clock.nowMs() - startedAtMs).coerceAtLeast(0L)
    }
  }

  /** Frame video scartati dal decoder dall'inizio della sessione (cumulativo). */
  fun onDroppedVideoFrames(droppedFrames: Int) {
    synchronized(lock) {
      droppedFramesValue += droppedFrames.coerceAtLeast(0).toLong()
    }
  }

  /** Stima della banda misurata da Media3 (bit/s), quando disponibile. */
  fun onBandwidthEstimate(bitrateEstimateBps: Long) {
    synchronized(lock) {
      bandwidthEstimateBpsValue = bitrateEstimateBps.coerceAtLeast(0L)
    }
  }

  /**
   * Traccia attiva osservata. `null` per un tipo significa "nessuna traccia
   * selezionata di quel tipo", che è un'informazione reale (es. nessun sottotitolo).
   */
  fun onObservedTracks(
    video: ObservedTrackFormat? = null,
    audio: ObservedTrackFormat? = null,
    subtitle: ObservedTrackFormat? = null
  ) {
    synchronized(lock) {
      if (video != null) videoTrack = video
      if (audio != null) audioTrack = audio
      subtitleTrack = subtitle
    }
  }

  val observedVideoTrack: ObservedTrackFormat? get() = videoTrack
  val observedAudioTrack: ObservedTrackFormat? get() = audioTrack
  val observedSubtitleTrack: ObservedTrackFormat? get() = subtitleTrack
  val lastBufferedPositionMs: Long get() = bufferedPositionMsValue
  val lastDurationMs: Long get() = durationMsValue
  val bandwidthEstimateBps: Long get() = bandwidthEstimateBpsValue
  val droppedVideoFrames: Long get() = droppedFramesValue

  /**
   * Throughput medio effettivo dei segmenti media (byte/s), calcolato SOLO dai
   * caricamenti riusciti già registrati: `null` quando non c'è ancora campione.
   */
  internal fun throughputBpsLocked(): Long? {
    var bytes = 0L
    var durationMs = 0L
    httpEvents.forEach { event ->
      if (!event.isError &&
        (event.phase == PlaybackPhase.MEDIA_SEGMENT || event.phase == PlaybackPhase.INIT_SEGMENT) &&
        event.bytesLoaded > 0
      ) {
        bytes += event.bytesLoaded
        durationMs += event.durationMs.coerceAtLeast(1L)
      }
    }
    if (bytes <= 0 || durationMs <= 0) return null
    return (bytes * 1000L) / durationMs
  }

  // --------------------------------------------------------------------------
  // Errori
  // --------------------------------------------------------------------------

  /**
   * Registra e classifica un errore di riproduzione.
   *
   * Un evento cancellato ([PlaybackErrorInput.canceled]) non è un errore e non
   * viene registrato. Gli errori con la stessa firma (categoria + fase + codice
   * HTTP) incrementano il contatore [PlaybackDiagnosis.attemptCount] senza
   * duplicare le righe.
   */
  fun onError(input: PlaybackErrorInput, phase: PlaybackPhase = input.phase): PlaybackDiagnosis? {
    val spec = PlaybackErrorClassifier.classify(input) ?: return null
    return recordDiagnosis(
      category = spec.category,
      phase = phase,
      httpCode = spec.httpCode,
      confidence = spec.confidence,
      temporary = spec.temporary,
      message = spec.technicalMessage
    )
  }

  /** Registra una diagnosi classificata, aggregando le firme identiche. */
  private fun recordDiagnosis(
    category: HlsErrorCategory,
    phase: PlaybackPhase,
    httpCode: Int?,
    confidence: DiagnosisConfidence,
    temporary: Boolean,
    message: String?
  ): PlaybackDiagnosis {
    val now = clock.nowMs()
    val sanitized = StreamDiagnostics.sanitize(message)
    synchronized(lock) {
      val key = "$category|${phase.name}|${httpCode ?: "-"}"
      val attempts = (errorCounts[key] ?: 0) + 1
      errorCounts[key] = attempts
      // Il ripetersi della stessa firma rafforza la confidenza dell'evidenza.
      val effectiveConfidence = if (attempts >= REPEATED_ERROR_CONFIRMATIONS) {
        DiagnosisConfidence.HIGH
      } else {
        confidence
      }
      val index = diagnoses.indexOfLast { "${it.category}|${it.phase.name}|${it.httpCode ?: "-"}" == key }
      val diagnosis = if (index >= 0) {
        // Aggiorna la riga esistente: stessa firma = stesso evento logico,
        // niente righe duplicate nel report.
        diagnoses[index].copy(
          timestampMs = now,
          attemptCount = attempts,
          confidence = effectiveConfidence,
          technicalMessage = sanitized ?: diagnoses[index].technicalMessage
        ).also { diagnoses[index] = it }
      } else {
        PlaybackDiagnosis(
          category = category,
          providerId = providerId,
          phase = phase,
          httpCode = httpCode,
          technicalMessage = sanitized,
          timestampMs = now,
          attemptCount = attempts,
          confidence = effectiveConfidence,
          temporary = temporary
        ).also { diagnoses += it }
      }
      lastActivityElapsedMs = (now - startedAtMs).coerceAtLeast(0L)
      return diagnosis
    }
  }

  // --------------------------------------------------------------------------
  // Valutazione buffering prolungato
  // --------------------------------------------------------------------------

  /**
   * `true` se la sessione sta attraversando un timeout di buffering: il player
   * è in BUFFERING **adesso** e la condizione dura da almeno
   * [bufferingTimeoutThresholdMs] oppure è già almeno la [REPEATED_BUFFERING_MIN]-a
   * riempitura consecutiva della sessione. Sempre temporanea: mai una
   * dichiarazione di sorgente irrecuperabile.
   */
  fun isBufferingTimeout(): Boolean {
    synchronized(lock) {
      val started = bufferingStartedAtMs ?: return false
      val continuous = clock.nowMs() - started
      if (continuous >= bufferingTimeoutThresholdMs) return true
      // L'evento corrente è già nell'elenco (aggiunto all'inizio del rebuffer).
      return bufferingEvents.size >= REPEATED_BUFFERING_MIN
    }
  }

  /**
   * Diagnosi BUFFERING_TIMEOUT, se la condizione corrente la giustifica.
   *
   * Il chiamante è un tick osservativo del player (nessun controllo sul
   * playback) che può interrogarla ripetutamente: la diagnosi è registrata UNA
   * sola volta per episodio di buffering — i tick successivi della stessa
   * condizione non gonfiano il contatore; un rebuffer successivo (nuovo
   * episodio, indice di [bufferingEvents] cresciuto) può produrre una nuova
   * occorrenza. Restituisce `null` quando la condizione non sussiste o è già
   * stata registrata per l'episodio corrente.
   */
  fun bufferingTimeoutDiagnosis(): PlaybackDiagnosis? {
    if (!isBufferingTimeout()) return null
    synchronized(lock) {
      if (bufferingTimeoutRecordedEpisode == bufferingEvents.size) return null
      bufferingTimeoutRecordedEpisode = bufferingEvents.size
    }
    return recordDiagnosis(
      category = HlsErrorCategory.BUFFERING_TIMEOUT,
      phase = PlaybackPhase.PLAYBACK,
      httpCode = null,
      confidence = DiagnosisConfidence.HIGH,
      temporary = true,
      message = "buffering prolungato senza progressi"
    )
  }

  // --------------------------------------------------------------------------
  // Report
  // --------------------------------------------------------------------------

  /**
   * Costruisce il report della sessione.
   *
   * La diagnosi finale è la prima diagnosi NON temporanea più recente se
   * esiste (evidenza definitiva), altrimenti l'ultima temporanea, altrimenti
   * null (nessun problema segnalato). Una sola sessione non dichiara mai da
   * sola una sorgente "definitivamente non disponibile" su evidenza temporanea.
   */
  fun buildReport(): HlsDiagnosticsReport {
    synchronized(lock) {
      // Chiude l'eventuale buffering ancora aperto senza alterare lo stato.
      val now = clock.nowMs()
      if (bufferingStartedAtMs != null) closeLastBufferingEvent(now)

      val definitive = diagnoses.lastOrNull { !it.temporary }
      val transient = diagnoses.lastOrNull()
      val finalDiagnosis = definitive ?: transient

      val httpErrors = diagnoses.filter {
        it.category == HlsErrorCategory.HTTP_ERROR || it.category == HlsErrorCategory.AUTHORIZATION_ERROR
      }
      val hlsErrors = diagnoses.filter {
        it.category == HlsErrorCategory.MANIFEST_ERROR ||
          it.category == HlsErrorCategory.SEGMENT_ERROR ||
          it.category == HlsErrorCategory.SOURCE_UNAVAILABLE ||
          it.category == HlsErrorCategory.FORMAT_UNSUPPORTED
      }
      val decoderErrors = diagnoses.filter {
        it.category == HlsErrorCategory.DECODER_ERROR || it.category == HlsErrorCategory.BUFFERING_TIMEOUT
      }

      return HlsDiagnosticsReport(
        sessionId = sessionId,
        providerId = providerId,
        contentLabel = contentLabel,
        sourceKind = sourceKind,
        protocol = lastScheme,
        playlistKind = playlistKind,
        declaredResolution = declaredResolution,
        selectedResolution = selectedResolution,
        detectedResolution = detectedResolution,
        codec = codec,
        playbackState = state,
        startupMs = firstFrameElapsedMs,
        rebufferCount = bufferingEvents.size,
        rebufferTotalMs = bufferingEvents.sumOf { it.durationMs },
        bufferingEvents = bufferingEvents.toList(),
        httpEvents = httpEvents.toList(),
        mediaSegmentCount = segmentCompletions.count,
        mediaSegmentBytesTotal = segmentCompletions.bytes,
        httpErrors = httpErrors,
        hlsErrors = hlsErrors,
        decoderErrors = decoderErrors,
        diagnoses = diagnoses.toList(),
        lastSuccessfulEvent = lastSuccessfulEvent,
        totalDurationMs = (now - startedAtMs).coerceAtLeast(0L),
        finalDiagnosis = finalDiagnosis,
        positionMs = lastPositionMsValue,
        bufferedPositionMs = bufferedPositionMsValue,
        durationMs = durationMsValue,
        bufferAheadMs = if (bufferedPositionMsValue >= 0 && lastPositionMsValue >= 0) {
          (bufferedPositionMsValue - lastPositionMsValue).coerceAtLeast(0L)
        } else {
          // Fallback sul buffer relativo osservato da Media3 (eventTime).
          lastBufferedDurationMsValue.takeIf { it >= 0 }
        },
        bandwidthEstimateBps = bandwidthEstimateBpsValue,
        throughputBps = throughputBpsLocked(),
        droppedVideoFrames = droppedFramesValue,
        videoTrack = videoTrack,
        audioTrack = audioTrack,
        subtitleTrack = subtitleTrack
      )
    }
  }

  companion object {
    /** Limite della coda circolare degli eventi HTTP (nessuna crescita infinita). */
    const val MAX_HTTP_EVENTS = 200

    /** Ripetizioni della stessa firma che elevano la confidenza a HIGH. */
    const val REPEATED_ERROR_CONFIRMATIONS = 2

    /** Minimo numero di rebuffer per la condizione "buffering ripetuto". */
    const val REPEATED_BUFFERING_MIN = 3

    /** Soglia predefinita di buffering continuo (ms). */
    const val DEFAULT_BUFFERING_TIMEOUT_MS = 15_000L

    private val SESSION_COUNTER = AtomicLong(0)

    /** Identificativo progressivo della sessione nel processo. */
    fun nextSessionId(): Long = SESSION_COUNTER.incrementAndGet()
  }
}
