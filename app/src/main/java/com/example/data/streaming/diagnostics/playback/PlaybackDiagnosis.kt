package com.example.data.streaming.diagnostics.playback

import com.example.data.streaming.diagnostics.StreamDiagnostics

/**
 * Fase della riproduzione in cui si è verificato un evento diagnostico.
 *
 * Le fasi Manifest/Variante/Segmento derivano dal `dataType` di Media3
 * (`C.DATA_TYPE_MANIFEST` / `C.DATA_TYPE_MEDIA`): vedi i limiti dichiarati in
 * [com.example.ui.screens.player.StreamNova4KDiag] — `MANIFEST` copre sia il
 * manifest iniziale sia le playlist di variante, `INIT_SEGMENT`/`MEDIA_SEGMENT`
 * sono univoci.
 */
enum class PlaybackPhase {
  MANIFEST,
  VARIANT_PLAYLIST,
  INIT_SEGMENT,
  MEDIA_SEGMENT,
  DECODE,
  PLAYBACK,
  UNKNOWN
}

/**
 * Categoria diagnostica di un problema di riproduzione.
 *
 * L'elenco è quello del piano HLS Diagnostics Engine: la mappatura Media3 →
 * categoria è centralizzata in [PlaybackErrorClassifier], mai replicata.
 */
enum class HlsErrorCategory {
  NETWORK_ERROR,
  HTTP_ERROR,
  TLS_ERROR,
  MANIFEST_ERROR,
  SEGMENT_ERROR,
  SOURCE_UNAVAILABLE,
  AUTHORIZATION_ERROR,
  DECODER_ERROR,
  FORMAT_UNSUPPORTED,
  BUFFERING_TIMEOUT,
  UNKNOWN_ERROR
}

/** Stato del player osservato dalla sessione diagnostica. */
enum class PlaybackStateKind { IDLE, BUFFERING, READY, ENDED }

/** Confidenza della diagnosi: dall'evidenza disponibile, mai inventata. */
enum class DiagnosisConfidence {
  /** Prova diretta: codice HTTP, codice Media3 specifico o classe eccezione nota. */
  HIGH,

  /** Indizi coerenti ma incompleti (marker testuali, aggregazione di eventi simili). */
  MEDIUM,

  /** Solo segnali generici (errore non specificato, classificazione di ripiego). */
  LOW
}

/**
 * Diagnosi strutturata di un problema di riproduzione, associata alla singola
 * sessione.
 *
 * [temporary] distingue le condizioni transitorie (timeout, 5xx, 429, rete
 * instabile) da quelle con evidenza definitiva (HTTP 401/403, manifest
 * malformato, file inesistente). Un timeout singolo NON è mai definitivo: la
 * categoria BUFFERING_TIMEOUT e i NETWORK_ERROR restano sempre temporanei.
 *
 * [technicalMessage] è già sanificato da [StreamDiagnostics.sanitize]: mai URL
 * completi, token, cookie o header.
 */
data class PlaybackDiagnosis(
  val category: HlsErrorCategory,
  val providerId: String?,
  val phase: PlaybackPhase,
  val httpCode: Int?,
  val technicalMessage: String?,
  val timestampMs: Long,
  val attemptCount: Int,
  val confidence: DiagnosisConfidence,
  val temporary: Boolean
)

/**
 * Input di classificazione in forma pura (nessuna dipendenza da Media3): il
 * listener del player traduce `PlaybackException` in questo modello, i test
 * producono casi deterministici senza Robolectric.
 *
 * [media3ErrorCode] è il valore numerico stabile di
 * `androidx.media3.common.PlaybackException.ERROR_CODE_*` (vedi le costanti
 * specchiate in [PlaybackErrorClassifier]).
 */
data class PlaybackErrorInput(
  val media3ErrorCode: Int? = null,
  val httpStatus: Int? = null,
  /** Catena delle classi delle eccezioni (dall'esterno verso la causa radice). */
  val exceptionClassNames: List<String> = emptyList(),
  val exceptionMessage: String? = null,
  val phase: PlaybackPhase = PlaybackPhase.UNKNOWN,
  val canceled: Boolean = false
)

/**
 * Classificatore **puro** degli errori di riproduzione.
 *
 * Regole generali:
 *  - la fase (manifest/segmento) restringe la categoria: un 404 sul manifest
 *    dichiara la sorgente irraggiungibile, un 404 su un segmento resta un
 *    errore di segmento (la playlist può aggiornarsi);
 *  - i codici temporanei lato server (408/425/429/5xx) e i timeout di rete non
 *    sono mai evidenza definitiva: [PlaybackDiagnosis.temporary] = true;
 *  - l'assenza di evidenze produce [HlsErrorCategory.UNKNOWN_ERROR] con
 *    confidenza bassa, mai una categoria azzardata.
 */
object PlaybackErrorClassifier {

  // Valori stabili di androidx.media3.common.PlaybackException.ERROR_CODE_*.
  // Specchiati per classificare senza dipendere dalle classi Media3 nei test
  // JVM puri; il listener del player passa il codice reale dell'eccezione.
  const val ERROR_CODE_UNSPECIFIED = 1000
  const val ERROR_CODE_REMOTE_ERROR = 1001
  const val ERROR_CODE_BEHIND_LIVE_WINDOW = 1002
  const val ERROR_CODE_TIMEOUT = 1003
  const val ERROR_CODE_FAILED_RUNTIME_CHECK = 1004
  const val ERROR_CODE_IO_UNSPECIFIED = 2000
  const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
  const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
  const val ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE = 2003
  const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004
  const val ERROR_CODE_IO_FILE_NOT_FOUND = 2005
  const val ERROR_CODE_IO_NO_PERMISSION = 2006
  const val ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED = 2007
  const val ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE = 2008
  const val ERROR_CODE_PARSING_MANIFEST_MALFORMED = 3001
  const val ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED = 3002
  const val ERROR_CODE_PARSING_CONTAINER_MALFORMED = 3003
  const val ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED = 3004
  const val ERROR_CODE_DECODER_INIT_FAILED = 4001
  const val ERROR_CODE_DECODER_QUERY_FAILED = 4002
  const val ERROR_CODE_DECODING_FAILED = 4003
  const val ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
  const val ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 4005
  const val ERROR_CODE_AUDIO_TRACK_INIT_FAILED = 5001
  const val ERROR_CODE_AUDIO_TRACK_WRITE_FAILED = 5002

  /** Codici HTTP di condizione temporanea lato server (stessa scala del fallback sorgente). */
  val TRANSIENT_HTTP_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

  /** Codici HTTP di autorizzazione negata. */
  private const val HTTP_UNAUTHORIZED = 401
  private const val HTTP_FORBIDDEN = 403
  private const val HTTP_NOT_FOUND = 404
  private const val HTTP_GONE = 410

  fun classify(input: PlaybackErrorInput): PlaybackDiagnosisSpec? {
    if (input.canceled) return null

    // 1) TLS: le classi SSL hanno priorità sui codici IO generici.
    classNames(input).firstOrNull { it.endsWith("SSLException") || it.endsWith("SSLHandshakeException") || it.endsWith("CertPathValidatorException") }
      ?.let { className ->
        return spec(
          category = HlsErrorCategory.TLS_ERROR,
          httpCode = input.httpStatus,
          confidence = DiagnosisConfidence.HIGH,
          temporary = true,
          message = className
        )
      }

    // 2) Codice HTTP esplicito: l'evidenza più diretta.
    input.httpStatus?.let { status ->
      return classifyHttpStatus(status, input)
    }

    // 3) Codici Media3.
    when (input.media3ErrorCode) {
      ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        return spec(
          HlsErrorCategory.NETWORK_ERROR,
          httpCode = null,
          confidence = DiagnosisConfidence.HIGH,
          temporary = true,
          message = classNames(input).firstOrNull()
        )
      ERROR_CODE_TIMEOUT ->
        return spec(
          HlsErrorCategory.NETWORK_ERROR,
          confidence = DiagnosisConfidence.MEDIUM,
          temporary = true,
          message = classNames(input).firstOrNull()
        )
      ERROR_CODE_IO_BAD_HTTP_STATUS ->
        // Status non estraibile ma confermato dal codice Media3: errore HTTP generico.
        return spec(
          HlsErrorCategory.HTTP_ERROR,
          confidence = DiagnosisConfidence.MEDIUM,
          temporary = false,
          message = classNames(input).firstOrNull()
        )
      ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
      ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
      ERROR_CODE_IO_FILE_NOT_FOUND,
      ERROR_CODE_IO_NO_PERMISSION ->
        // Un file mancante su una richiesta di segmento resta un errore di
        // segmento (la playlist può aggiornarsi): solo manifest/variante
        // mancanti dichiarano la sorgente non disponibile.
        return if (input.phase == PlaybackPhase.MEDIA_SEGMENT || input.phase == PlaybackPhase.INIT_SEGMENT) {
          spec(
            HlsErrorCategory.SEGMENT_ERROR,
            confidence = DiagnosisConfidence.HIGH,
            temporary = true,
            message = classNames(input).firstOrNull()
          )
        } else {
          spec(
            HlsErrorCategory.SOURCE_UNAVAILABLE,
            confidence = DiagnosisConfidence.HIGH,
            temporary = false,
            message = classNames(input).firstOrNull()
          )
        }
      ERROR_CODE_BEHIND_LIVE_WINDOW ->
        return spec(
          HlsErrorCategory.SEGMENT_ERROR,
          confidence = DiagnosisConfidence.HIGH,
          temporary = true,
          message = classNames(input).firstOrNull()
        )
      ERROR_CODE_PARSING_MANIFEST_MALFORMED,
      ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        return spec(
          HlsErrorCategory.MANIFEST_ERROR,
          confidence = DiagnosisConfidence.HIGH,
          temporary = false,
          message = classNames(input).firstOrNull()
        )
      ERROR_CODE_PARSING_CONTAINER_MALFORMED,
      ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
        return spec(
          HlsErrorCategory.FORMAT_UNSUPPORTED,
          confidence = DiagnosisConfidence.HIGH,
          temporary = false,
          message = classNames(input).firstOrNull()
        )
      ERROR_CODE_DECODER_INIT_FAILED,
      ERROR_CODE_DECODER_QUERY_FAILED,
      ERROR_CODE_DECODING_FAILED,
      ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
        return spec(
          HlsErrorCategory.DECODER_ERROR,
          confidence = DiagnosisConfidence.HIGH,
          temporary = true,
          message = classNames(input).firstOrNull()
        )
      ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        return spec(
          HlsErrorCategory.FORMAT_UNSUPPORTED,
          confidence = DiagnosisConfidence.HIGH,
          temporary = false,
          message = classNames(input).firstOrNull()
        )
      ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
      ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
        return spec(
          HlsErrorCategory.DECODER_ERROR,
          confidence = DiagnosisConfidence.MEDIUM,
          temporary = true,
          message = classNames(input).firstOrNull()
        )
    }

    // 4) Classi di eccezione di trasporto (senza codice Media3 riconosciuto).
    classNames(input).firstOrNull { className ->
      className.endsWith("SocketTimeoutException") ||
        className.endsWith("UnknownHostException") ||
        className.endsWith("ConnectException") ||
        className.endsWith("SocketException") ||
        className.endsWith("EOFException") ||
        className.endsWith("StreamResetException")
    }?.let { className ->
      return spec(
        HlsErrorCategory.NETWORK_ERROR,
        confidence = DiagnosisConfidence.HIGH,
        temporary = true,
        message = className
      )
    }

    // 5) Marker testuali come ultima risorsa (confidenza media).
    val message = input.exceptionMessage.orEmpty().lowercase()
    when {
      message.contains("timeout") || message.contains("timed out") ->
        return spec(
          HlsErrorCategory.NETWORK_ERROR,
          confidence = DiagnosisConfidence.MEDIUM,
          temporary = true,
          message = input.exceptionMessage
        )
      message.contains("ssl") || message.contains("handshake") ->
        return spec(
          HlsErrorCategory.TLS_ERROR,
          confidence = DiagnosisConfidence.MEDIUM,
          temporary = true,
          message = input.exceptionMessage
        )
      message.contains("manifest") || message.contains("playlist") ->
        return spec(
          HlsErrorCategory.MANIFEST_ERROR,
          confidence = DiagnosisConfidence.MEDIUM,
          temporary = false,
          message = input.exceptionMessage
        )
      message.contains("segment") ->
        return spec(
          HlsErrorCategory.SEGMENT_ERROR,
          confidence = DiagnosisConfidence.MEDIUM,
          temporary = true,
          message = input.exceptionMessage
        )
    }

    // 6) Ripiego prudente: nessuna categoria dichiarata senza evidenze.
    return spec(
      HlsErrorCategory.UNKNOWN_ERROR,
      confidence = DiagnosisConfidence.LOW,
      temporary = true,
      message = input.exceptionMessage
    )
  }

  /** Classificazione di un codice HTTP noto, dipendente dalla fase. */
  private fun classifyHttpStatus(status: Int, input: PlaybackErrorInput): PlaybackDiagnosisSpec {
    val confidence = DiagnosisConfidence.HIGH
    return when {
      status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN ->
        spec(HlsErrorCategory.AUTHORIZATION_ERROR, status, confidence, temporary = false, message = classNames(input).firstOrNull())

      status == HTTP_NOT_FOUND || status == HTTP_GONE ->
        if (input.phase == PlaybackPhase.MEDIA_SEGMENT || input.phase == PlaybackPhase.INIT_SEGMENT) {
          // Segmento assente: la playlist può essere stata aggiornata (live/DVR).
          spec(HlsErrorCategory.SEGMENT_ERROR, status, confidence, temporary = true, message = classNames(input).firstOrNull())
        } else {
          // Manifest/variante inesistente: la risorsa non c'è più.
          spec(HlsErrorCategory.SOURCE_UNAVAILABLE, status, confidence, temporary = false, message = classNames(input).firstOrNull())
        }

      status in TRANSIENT_HTTP_STATUS_CODES ->
        spec(HlsErrorCategory.HTTP_ERROR, status, confidence, temporary = true, message = classNames(input).firstOrNull())

      status in 200..299 ->
        // Una risposta 2xx non è un errore: non classificata (chiamante decide).
        spec(HlsErrorCategory.UNKNOWN_ERROR, status, DiagnosisConfidence.LOW, temporary = true, message = classNames(input).firstOrNull())

      status >= 400 ->
        spec(HlsErrorCategory.HTTP_ERROR, status, confidence, temporary = false, message = classNames(input).firstOrNull())

      else ->
        spec(HlsErrorCategory.HTTP_ERROR, status, DiagnosisConfidence.MEDIUM, temporary = true, message = classNames(input).firstOrNull())
    }
  }

  private fun classNames(input: PlaybackErrorInput): List<String> = input.exceptionClassNames

  private fun spec(
    category: HlsErrorCategory,
    httpCode: Int? = null,
    confidence: DiagnosisConfidence,
    temporary: Boolean,
    message: String?
  ) = PlaybackDiagnosisSpec(category, httpCode, confidence, temporary, message)
}

/**
 * Esito della classificazione pura: la sessione lo completa con provider,
 * fase, timestamp e contatori.
 */
data class PlaybackDiagnosisSpec(
  val category: HlsErrorCategory,
  val httpCode: Int?,
  val confidence: DiagnosisConfidence,
  val temporary: Boolean,
  val technicalMessage: String?
)
