package com.example.data.addonhealth

import com.squareup.moshi.JsonClass

/**
 * Stato di salute di un addon Stremio, derivato da un health-check reale del
 * manifest (`GET $baseUrl/manifest.json`).
 *
 * Ogni stato corrisponde a un'evidenza verificata, mai a una supposizione:
 *  - [HEALTHY]: manifest raggiungibile e valido, risposta entro [AddonHealthClassifier.HEALTHY_MAX_LATENCY_MS].
 *  - [DEGRADED]: manifest valido ma lento, oppure server in errore temporaneo (5xx).
 *  - [UNREACHABLE]: nessuna risposta (timeout, rete assente, TLS fallito).
 *  - [INVALID_MANIFEST]: il server risponde ma il manifest è assente/invalido (4xx, JSON rotto).
 *  - [DISABLED]: addon disattivato dall'utente: non viene interrogato.
 *  - [UNKNOWN]: nessun controllo ancora eseguito.
 */
enum class AddonHealthStatus { HEALTHY, DEGRADED, UNREACHABLE, INVALID_MANIFEST, DISABLED, UNKNOWN }

/** Causa tecnica di un controllo fallito. */
enum class AddonProbeErrorKind { TIMEOUT, NETWORK, TLS, HTTP, INVALID_MANIFEST, UNKNOWN }

/**
 * Esito grezzo di una singola interrogazione del manifest.
 *
 * È un modello puro (nessun tipo Android/OkHttp): il repository lo produce dai
 * propri errori e [AddonHealthClassifier] lo traduce nello stato utente. Questo
 * rende la classificazione verificabile senza rete.
 */
data class AddonProbeOutcome(
  val success: Boolean,
  val latencyMs: Long,
  val httpCode: Int? = null,
  val errorKind: AddonProbeErrorKind? = null,
  val message: String? = null,
  val manifestVersion: String? = null
)

/**
 * Report di salute di un addon, persistito in JSON e mostrato nella UI.
 *
 * @param installId identità dell'installazione (`baseUrl`): due configurazioni
 *        dello stesso manifest hanno report distinti.
 */
@JsonClass(generateAdapter = true)
data class AddonHealthReport(
  val installId: String,
  val name: String,
  val baseUrl: String,
  val status: AddonHealthStatus,
  val latencyMs: Long = 0L,
  val checkedAtEpochMs: Long = 0L,
  val detail: String? = null,
  val manifestVersion: String? = null
) {
  val isProblem: Boolean
    get() = status == AddonHealthStatus.UNREACHABLE || status == AddonHealthStatus.INVALID_MANIFEST
}

/** Riepilogo aggregato mostrato nell'intestazione del monitor. */
data class AddonHealthSummary(
  val total: Int,
  val healthy: Int,
  val degraded: Int,
  val unreachable: Int,
  val invalid: Int,
  val disabled: Int,
  val averageLatencyMs: Long?
) {
  val checked: Int get() = healthy + degraded + unreachable + invalid
  val hasProblems: Boolean get() = unreachable > 0 || invalid > 0
}

/**
 * Classificazione deterministica dell'esito di un probe.
 *
 * Soglie dichiarate e verificabili:
 *  - <= [HEALTHY_MAX_LATENCY_MS] → HEALTHY;
 *  - <= [DEGRADED_MAX_LATENCY_MS] → DEGRADED ("risposta lenta");
 *  - oltre → DEGRADED ("molto lento") ma NON irraggiungibile: il manifest è arrivato.
 */
object AddonHealthClassifier {

  const val HEALTHY_MAX_LATENCY_MS = 1_500L
  const val DEGRADED_MAX_LATENCY_MS = 4_000L

  /** Errore HTTP 4xx: il manifest non esiste o l'URL è sbagliato. */
  const val HTTP_CLIENT_ERROR_MIN = 400
  const val HTTP_SERVER_ERROR_MIN = 500

  fun classify(outcome: AddonProbeOutcome): Pair<AddonHealthStatus, String?> {
    if (outcome.success) {
      return when {
        outcome.latencyMs <= HEALTHY_MAX_LATENCY_MS -> AddonHealthStatus.HEALTHY to null
        outcome.latencyMs <= DEGRADED_MAX_LATENCY_MS ->
          AddonHealthStatus.DEGRADED to "Risposta lenta (${outcome.latencyMs} ms)"
        else ->
          AddonHealthStatus.DEGRADED to "Risposta molto lenta (${outcome.latencyMs} ms)"
      }
    }

    val code = outcome.httpCode
    if (code != null && code >= HTTP_CLIENT_ERROR_MIN && code < HTTP_SERVER_ERROR_MIN) {
      return AddonHealthStatus.INVALID_MANIFEST to
        "Manifest non disponibile (HTTP $code)"
    }
    if (code != null && code >= HTTP_SERVER_ERROR_MIN) {
      return AddonHealthStatus.DEGRADED to
        "Server in errore (HTTP $code): riprova più tardi"
    }

    return when (outcome.errorKind) {
      AddonProbeErrorKind.TIMEOUT ->
        AddonHealthStatus.UNREACHABLE to "Tempo scaduto: nessuna risposta dal server"
      AddonProbeErrorKind.NETWORK ->
        AddonHealthStatus.UNREACHABLE to (outcome.message ?: "Errore di rete")
      AddonProbeErrorKind.TLS ->
        AddonHealthStatus.UNREACHABLE to (outcome.message ?: "Errore TLS/certificato")
      AddonProbeErrorKind.HTTP ->
        AddonHealthStatus.INVALID_MANIFEST to (outcome.message ?: "Risposta HTTP non valida")
      AddonProbeErrorKind.INVALID_MANIFEST ->
        AddonHealthStatus.INVALID_MANIFEST to (outcome.message ?: "Manifest non valido")
      AddonProbeErrorKind.UNKNOWN, null ->
        AddonHealthStatus.UNREACHABLE to (outcome.message ?: "Controllo fallito")
    }
  }

  /** Classifica un'eccezione del fetch manifest in base al suo messaggio. */
  fun classifyFailureMessage(message: String?): AddonProbeErrorKind {
    val text = message?.lowercase().orEmpty()
    return when {
      text.isBlank() -> AddonProbeErrorKind.UNKNOWN
      text.contains("timeout") || text.contains("tempo scaduto") -> AddonProbeErrorKind.TIMEOUT
      text.contains("http 4") || text.contains("http 5") -> AddonProbeErrorKind.HTTP
      text.contains("certificat") || text.contains("ssl") || text.contains("tls") ||
        text.contains("trust anchor") -> AddonProbeErrorKind.TLS
      text.contains("json") || text.contains("manifest privo") ||
        text.contains("manifest vuoto") -> AddonProbeErrorKind.INVALID_MANIFEST
      else -> AddonProbeErrorKind.NETWORK
    }
  }

  /** Estrae il codice HTTP dal messaggio di errore di [StremioAddonRepository.fetchManifest]. */
  fun httpCodeFromMessage(message: String?): Int? =
    Regex("""HTTP (\d{3})""").find(message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

  /** Riepilogo dei report, esclusi quelli mai controllati ([AddonHealthStatus.UNKNOWN]). */
  fun summarize(reports: List<AddonHealthReport>): AddonHealthSummary {
    val known = reports.filter { it.status != AddonHealthStatus.UNKNOWN }
    val latencies = known
      .filter { it.status == AddonHealthStatus.HEALTHY || it.status == AddonHealthStatus.DEGRADED }
      .map { it.latencyMs }
      .filter { it > 0 }
    return AddonHealthSummary(
      total = known.size,
      healthy = known.count { it.status == AddonHealthStatus.HEALTHY },
      degraded = known.count { it.status == AddonHealthStatus.DEGRADED },
      unreachable = known.count { it.status == AddonHealthStatus.UNREACHABLE },
      invalid = known.count { it.status == AddonHealthStatus.INVALID_MANIFEST },
      disabled = known.count { it.status == AddonHealthStatus.DISABLED },
      averageLatencyMs = latencies.takeIf { it.isNotEmpty() }?.average()?.toLong()
    )
  }
}
