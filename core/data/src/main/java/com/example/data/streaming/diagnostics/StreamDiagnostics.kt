package com.example.data.streaming.diagnostics

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.ranges.until

/** Sorgente di tempo monotono per le metriche (sovrascrivibile nei test). */
fun interface DiagnosticClock {
  fun nowMs(): Long

  companion object {
    val SYSTEM: DiagnosticClock = DiagnosticClock { System.nanoTime() / 1_000_000L }
  }
}

/**
 * Esito di un singolo tentativo della catena di risoluzione streaming.
 *
 * I valori distinguono i casi che richiedono una reazione diversa:
 *  - [NO_RESULTS] e' un esito legittimo (il provider/mirror ha risposto ma non
 *    ha trovato nulla), non un errore di riproduzione;
 *  - [TIMEOUT] e [NETWORK_ERROR] sono condizioni transitorie;
 *  - [HTTP_ERROR] descrive una risposta HTTP non riuscita;
 *  - [BLOCKED_BY_CHALLENGE] indica CAPTCHA/Cloudflare: nessun bypass viene
 *    tentato, il tentativo viene solo classificato;
 *  - [EXTRACTION_FAILED] e [INVALID_STREAM_URL] riguardano la pagina hoster;
 *  - [CANCELLED] e' la cancellazione strutturata della ricerca.
 */
enum class StreamAttemptOutcome {
  SUCCESS,
  NO_RESULTS,
  TIMEOUT,
  NETWORK_ERROR,
  HTTP_ERROR,
  BLOCKED_BY_CHALLENGE,
  EXTRACTION_FAILED,
  INVALID_STREAM_URL,
  CANCELLED
}

/**
 * Risultato strutturato di un tentativo (provider, mirror o extractor).
 *
 * Tempi:
 *  - [queueMs]: attesa del candidato prima di ottenere uno slot di concorrenza.
 *    Misurato da chi gestisce il semaforo (StreamManager); `null` per i
 *    tentativi interni al provider che non attraversano la coda;
 *  - [runMs]: esecuzione effettiva del tentativo (dopo l'acquisizione dello
 *    slot, con l'eccezione delle operazioni che si trovano fuori da una coda);
 *    `null` quando coincide con [durationMs];
 *  - [durationMs]: compatibilita' storica, e' la durata totale osservata.
 *
 * [message] e' sempre un testo gia' sanificato: non deve contenere URL firmati
 * completi, token, cookie o header di autenticazione.
 */
data class StreamAttempt(
  val providerId: String,
  val mirrorId: String? = null,
  val extractorId: String? = null,
  val outcome: StreamAttemptOutcome,
  val durationMs: Long,
  val message: String? = null,
  val searchId: Long? = null,
  val queueMs: Long? = null,
  val runMs: Long? = null
) {
  val isSuccess: Boolean get() = outcome == StreamAttemptOutcome.SUCCESS
}

/**
 * Destinazione dei tentativi strutturati.
 *
 * La produzione usa [NONE] (nessun costo e nessuna persistenza); i test e una
 * eventuale diagnostica in-app possono iniettare un sink che raccoglie la
 * catena. Il sink non decide nulla: non modifica il flusso di risoluzione.
 */
fun interface StreamDiagnosticSink {
  fun record(attempt: StreamAttempt)

  companion object {
    val NONE: StreamDiagnosticSink = StreamDiagnosticSink { }
  }
}

/** Registra un tentativo sanificando il messaggio in un unico punto. */
fun StreamDiagnosticSink.record(
  providerId: String,
  outcome: StreamAttemptOutcome,
  durationMs: Long,
  mirrorId: String? = null,
  extractorId: String? = null,
  message: String? = null,
  searchId: Long? = null,
  queueMs: Long? = null,
  runMs: Long? = null
) {
  val total = durationMs.coerceAtLeast(0L)
  record(
    StreamAttempt(
      providerId = providerId,
      mirrorId = mirrorId,
      extractorId = extractorId,
      outcome = outcome,
      durationMs = total,
      message = StreamDiagnostics.sanitize(message),
      searchId = searchId,
      queueMs = queueMs?.coerceIn(0L, total),
      runMs = runMs?.coerceIn(0L, total)
    )
  )
}

/** Sink che non registra nulla: la diagnostica e' esplicitamente disattivata. */
val NO_DIAGNOSTICS: StreamDiagnosticSink = StreamDiagnosticSink.NONE

/**
 * Contatore di concurrency dei tentativi.
 *
 * [increment] va chiamato **appena** il candidato entra nel semaforo e
 * [decrement] sempre in un `finally`, cosi' un'eccezione o una cancellazione non
 * lasciano mai il contatore gonfiato. Il picco e' calcolato massimizzando il
 * valore letto subito dopo l'incremento.
 */
class AttemptConcurrencyCounter {

  private val active = AtomicInteger(0)
  private val peak = AtomicInteger(0)

  val activeCount: Int get() = active.get()
  val peakCount: Int get() = peak.get()

  fun increment(): Int {
    val now = active.incrementAndGet()
    peak.updateAndGet { current -> maxOf(current, now) }
    return now
  }

  fun decrement() {
    active.decrementAndGet()
  }
}

/**
 * Contatori di deduplicazione delle sorgenti.
 *
 * [received] conta tutte le sorgenti restituite dai provider/addon, [added] le
 * righe inserite nella ricerca e [duplicates] quelle scartate perche' gia'
 * presenti (stessa chiave logica). `added + duplicates == received` e'
 * verificabile offline; non contiene testo delle sorgenti.
 */
data class StreamDedupStats(
  val received: Int = 0,
  val added: Int = 0,
  val duplicates: Int = 0
) {
  operator fun plus(other: StreamDedupStats) = StreamDedupStats(
    received = received + other.received,
    added = added + other.added,
    duplicates = duplicates + other.duplicates
  )

  /** True se i contatori restano coerenti con se stessi. */
  val isConsistent: Boolean get() = received == added + duplicates
}

/**
 * Metriche aggregate di **una** ricerca.
 *
 * Tutti i tempi sono riferiti all'istante di partenza della ricerca e misurati
 * con un clock monotono: non sono orari di parete.
 *
 * Limiti noti (documentati, non nascosti):
 *  - `peakConcurrent` e' il picco **dei tentativi entrati nel semaforo**,
 *    osservato dal contatore: e' deterministico e non puo' superare il limite;
 *  - [duplicates] conta solo le duplicazioni rilevate dal merge di StreamManager
 *    (chiave URL+header o infoHash+fileIdx). Le duplicazioni scartate dentro un
 *    singolo provider (shortener/estrattori) sono visibili solo come tentativi
 *    diagnostici, non in questo aggregato;
 *  - [networkErrors]/[httpErrors]/[challenges]/[extractionFailed] riflettono
 *    l'esito del **provider come unita'**, non dei singoli mirror interni.
 */
data class StreamSearchMetrics(
  val searchId: Long,
  val firstSourceMs: Long?,
  val totalMs: Long,
  val providersStarted: Int,
  val providersCompleted: Int,
  val neverStarted: Int,
  val success: Int,
  val timeout: Int,
  val networkErrors: Int,
  val httpErrors: Int,
  val challenges: Int,
  val extractionFailed: Int,
  val noResults: Int,
  val cancelled: Int,
  val validSources: Int,
  val duplicates: Int,
  val peakConcurrent: Int
)

/**
 * Riga compatta delle metriche per Logcat: chiave=valore, nessun dato di
 * contenuto (niente titoli o URL). `neverStarted` e' derivato
 * (`providersStarted - providersCompleted`), quindi non viene duplicato.
 */
fun StreamSearchMetrics.toLogLine(): String = buildString {
  append("searchId=").append(searchId)
  append(" firstSourceMs=").append(firstSourceMs)
  append(" totalMs=").append(totalMs)
  append(" providersStarted=").append(providersStarted)
  append(" providersCompleted=").append(providersCompleted)
  append(" neverStarted=").append(neverStarted)
  append(" success=").append(success)
  append(" timeout=").append(timeout)
  append(" networkErrors=").append(networkErrors)
  append(" httpErrors=").append(httpErrors)
  append(" challenges=").append(challenges)
  append(" extractionFailed=").append(extractionFailed)
  append(" noResults=").append(noResults)
  append(" cancelled=").append(cancelled)
  append(" validSources=").append(validSources)
  append(" duplicates=").append(duplicates)
  append(" peakConcurrent=").append(peakConcurrent)
}

/** Accumulatore thread-safe delle metriche di una singola ricerca. */
class StreamSearchMetricsCollector(
  val searchId: Long,
  private val clock: DiagnosticClock = DiagnosticClock.SYSTEM
) {

  private val startedAt = clock.nowMs()
  private val lock = Any()
  private val dedup = AtomicInteger(0)

  @Volatile private var firstSourceMs: Long? = null
  @Volatile private var providersStarted = 0
  @Volatile private var providersCompleted = 0
  private var success = 0
  private var timeout = 0
  private var networkErrors = 0
  private var httpErrors = 0
  private var challenges = 0
  private var extractionFailed = 0
  private var noResults = 0
  private var cancelled = 0
  private var validSources = 0

  fun startTime(): Long = startedAt

  fun elapsed(): Long = (clock.nowMs() - startedAt).coerceAtLeast(0L)

  /** Un provider ha superato deliberatamente i filtri ed e' stato lanciato. */
  fun onProviderStarted() {
    synchronized(lock) { providersStarted++ }
  }

  /** Il tentativo di un provider si e' concluso con [outcome]. */
  fun onAttemptOutcome(outcome: StreamAttemptOutcome) {
    synchronized(lock) {
      when (outcome) {
        StreamAttemptOutcome.SUCCESS -> success++
        StreamAttemptOutcome.TIMEOUT -> timeout++
        StreamAttemptOutcome.NETWORK_ERROR -> networkErrors++
        StreamAttemptOutcome.HTTP_ERROR -> httpErrors++
        StreamAttemptOutcome.BLOCKED_BY_CHALLENGE -> challenges++
        StreamAttemptOutcome.EXTRACTION_FAILED,
        StreamAttemptOutcome.INVALID_STREAM_URL -> extractionFailed++
        StreamAttemptOutcome.NO_RESULTS -> noResults++
        StreamAttemptOutcome.CANCELLED -> cancelled++
      }
    }
  }

  fun onProviderCompleted() {
    synchronized(lock) { providersCompleted++ }
  }

  /**
   * Prima sorgente **effettivamente riproducibile** aggiunta alla ricerca:
   * registra il tempo dal via. Le chiamate successive non cambiano il valore.
   */
  fun onFirstPlayableSource() {
    synchronized(lock) {
      if (firstSourceMs == null) firstSourceMs = elapsed()
    }
  }

  /** Sorgenti valide (non duplicate) aggiunte alla fine della ricerca. */
  fun onValidSources(count: Int) {
    if (count <= 0) return
    synchronized(lock) { validSources += count }
  }

  fun onDuplicates(count: Int) {
    if (count <= 0) return
    dedup.addAndGet(count)
  }

  fun build(peakConcurrent: Int): StreamSearchMetrics {
    val total = elapsed()
    return synchronized(lock) {
      StreamSearchMetrics(
        searchId = searchId,
        firstSourceMs = firstSourceMs,
        totalMs = total,
        providersStarted = providersStarted,
        providersCompleted = providersCompleted,
        neverStarted = (providersStarted - providersCompleted).coerceAtLeast(0),
        success = success,
        timeout = timeout,
        networkErrors = networkErrors,
        httpErrors = httpErrors,
        challenges = challenges,
        extractionFailed = extractionFailed,
        noResults = noResults,
        cancelled = cancelled,
        validSources = validSources,
        duplicates = dedup.get(),
        peakConcurrent = peakConcurrent
      )
    }
  }
}

/** Classificazione e sanificazione pure, verificabili senza rete. */
object StreamDiagnostics {

  /**
   * Categoria dell'errore di un tentativo.
   *
   * L'ordine dei controlli e' significativo: un timeout (anche di socket) non e'
   * un errore di rete generico e una challenge non e' un semplice HTTP 403.
   */
  fun classify(error: Throwable): StreamAttemptOutcome = when {
    error is TimeoutCancellationException -> StreamAttemptOutcome.TIMEOUT
    error is SocketTimeoutException -> StreamAttemptOutcome.TIMEOUT
    error is java.io.InterruptedIOException &&
      error.message.orEmpty().contains("timeout", ignoreCase = true) -> StreamAttemptOutcome.TIMEOUT
    error is CancellationException -> StreamAttemptOutcome.CANCELLED
    isChallengeMessage(error.message) -> StreamAttemptOutcome.BLOCKED_BY_CHALLENGE
    error is IOException && HTTP_STATUS_REGEX.containsMatchIn(error.message.orEmpty()) ->
      StreamAttemptOutcome.HTTP_ERROR
    error is IOException && isNetworkFailure(error) -> StreamAttemptOutcome.NETWORK_ERROR
    // Un IOException "semantico" (es. "nessun link hoster", "episodio non
    // trovato") non e' un errore di rete: e' un'estrazione fallita.
    error is IOException -> StreamAttemptOutcome.EXTRACTION_FAILED
    else -> StreamAttemptOutcome.EXTRACTION_FAILED
  }

  /** True per le eccezioni di trasporto e i messaggi tipici di rete. */
  private fun isNetworkFailure(error: IOException): Boolean {
    if (error is java.net.UnknownHostException ||
      error is java.net.ConnectException ||
      error is java.net.SocketException ||
      error is javax.net.ssl.SSLException
    ) {
      return true
    }
    val message = error.message.orEmpty().lowercase()
    return NETWORK_MARKERS.any { message.contains(it) }
  }

  /** True se il testo descrive CAPTCHA/Cloudflare/anti-bot (nessun bypass). */
  fun isChallengeMessage(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val lower = message.lowercase()
    return CHALLENGE_MARKERS.any { lower.contains(it) }
  }

  /**
   * Sanifica un messaggio diagnostico.
   *
   * - gli URL diventano `schema://host[:porta]` (path, query e frammento
   *   rimossi: possono contenere token firmati);
   * - credenziali Bearer/Basic, cookie e parametri con nomi sensibili vengono
   *   sostituiti con `[redacted]`;
   * - il testo viene limitato in lunghezza per non invadere i log.
   */
  fun sanitize(message: String?): String? {
    if (message.isNullOrBlank()) return null
    var out = URL_REGEX.replace(message) { match -> sanitizeUrl(match.value) }
    out = BEARER_REGEX.replace(out) { match -> "${match.groupValues[1]} [redacted]" }
    out = COOKIE_HEADER_REGEX.replace(out, "cookie=[redacted]")
    out = SECRET_PARAM_REGEX.replace(out) { match -> "${match.groupValues[1]}=[redacted]" }
    return out.trim().take(MAX_MESSAGE_LENGTH).takeIf { it.isNotBlank() }
  }

  /**
   * Identificativo sicuro di un mirror: il solo host, mai path o query.
   * Usato per legare i tentativi allo stesso mirror senza salvare URL firmati.
   */
  fun hostId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
      val uri = URI(url)
      uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
      null
    }
  }

  private fun sanitizeUrl(raw: String): String {
    val uri = try {
      URI(raw)
    } catch (e: Exception) {
      return raw.substringBefore('?').substringBefore('#')
    }
    val scheme = uri.scheme?.lowercase() ?: return "[url]"
    val host = uri.host ?: return "[url]"
    if (scheme != "http" && scheme != "https") return "[url]"
    val port = if (uri.port > 0) ":${uri.port}" else ""
    // Solo l'origin: anche un token nel path di un URL firmato non deve essere
    // registrato. L'host e' sufficiente a identificare il mirror.
    return "$scheme://$host$port"
  }

  private val URL_REGEX = Regex("""https?://[^\s"'<>\)\]]+""", RegexOption.IGNORE_CASE)
  private val BEARER_REGEX = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
  private val COOKIE_HEADER_REGEX = Regex("""(?i)\bcookie\s*[:=]\s*[^;\s]+""")
  private val SECRET_PARAM_REGEX = Regex(
    """(?i)\b(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|token|authorization|password|passwd|pwd|secret|signature|sig|expires|key)\s*=\s*([^&\s,;]+)"""
  )
  private val HTTP_STATUS_REGEX = Regex("""\bHTTP\s+[1-5]\d{2}\b""", RegexOption.IGNORE_CASE)
  private const val MAX_MESSAGE_LENGTH = 240

  /** Marker dei messaggi di trasporto (OkHttp/Java) che indicano un problema di rete. */
  private val NETWORK_MARKERS = listOf(
    "failed to connect",
    "connection",
    "connect",
    "resolve host",
    "unknown host",
    "network",
    "socket",
    "unreachable",
    "ssl",
    "tls",
    "broken pipe",
    "end of stream",
    "stream was reset",
    "no route to host",
    "host is unresolved"
  )

  /**
   * Marker specifici di una challenge. "access denied" da solo NON basta:
   * un 403 ordinario resta [StreamAttemptOutcome.HTTP_ERROR].
   */
  private val CHALLENGE_MARKERS = listOf(
    "captcha",
    "cloudflare",
    "challenge",
    "just a moment",
    "cf-chl",
    "checking your browser",
    "verify you are human",
    "ddos protection",
    "attention required"
  )
}
