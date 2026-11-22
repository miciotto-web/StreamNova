package com.example.data.addonhealth

import android.util.Log
import com.example.data.prefs.AppSettingsRepository
import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.StremioAddonRepository
import com.example.data.streaming.AddonTrust
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Codec JSON dei report di salute (Moshi, lista tipizzata).
 *
 * Un JSON corrotto non deve mai impedire l'apertura della schermata Addon: il
 * decode degrada a lista vuota, esattamente come i DataStore dei sottotitoli.
 */
object AddonHealthCodec {

  private val adapter = Moshi.Builder().build().adapter<List<AddonHealthReport>>(
    Types.newParameterizedType(List::class.java, AddonHealthReport::class.java)
  )

  fun encode(reports: List<AddonHealthReport>): String = try {
    adapter.toJson(reports)
  } catch (e: Exception) {
    "[]"
  }

  fun decode(json: String?): List<AddonHealthReport> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
      adapter.fromJson(json)
        ?.filter { it.installId.isNotBlank() }
        ?: emptyList()
    } catch (e: Exception) {
      emptyList()
    }
  }
}

/**
 * Addon Health Monitor.
 *
 * Esegue un health-check REALE di ogni addon installato (`GET $baseUrl/manifest.json`,
 * lo stesso endpoint usato dal repository per installare/aggiornare l'addon) e
 * produce un report per installazione con stato, latenza, versione del manifest e
 * motivo leggibile. I report:
 *
 *  - sono calcolati in **parallelo**, con un timeout per addon ([PROBE_TIMEOUT_MS])
 *    così un addon morto non blocca gli altri;
 *  - vengono persistiti in [AppSettingsRepository] e ripristinati all'avvio;
 *  - non espongono mai URL completi nei dettagli: i messaggi di errore sono
 *    sanificati (un baseUrl di configurazione può contenere chiavi Debrid);
 *  - NON modificano addon, manifest o cache: il monitor è pura osservazione.
 *
 * Il repository è un singleton inizializzato dall'Application ([init]) e usato
 * dalla schermata Addon e dal ranking delle sorgenti (affidabilità dell'addon).
 */
object AddonHealthRepository {

  private const val TAG = "AddonHealthRepo"

  /** Timeout per singolo addon: oltre, lo stato è UNREACHABLE (timeout). */
  const val PROBE_TIMEOUT_MS = 12_000L

  private val _reports = MutableStateFlow<List<AddonHealthReport>>(emptyList())

  /** Ultimo report per addon (installId), ordinato per nome. */
  val reports: StateFlow<List<AddonHealthReport>> = _reports.asStateFlow()

  private val _isChecking = MutableStateFlow(false)

  /** `true` mentre un health-check è in corso (la UI mostra lo spinner). */
  val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

  private val _lastCheckedAt = MutableStateFlow(0L)

  /** Timestamp dell'ultimo controllo completato (0 = mai). */
  val lastCheckedAt: StateFlow<Long> = _lastCheckedAt.asStateFlow()

  private var initialized = false

  private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Ripristina i report persistiti. Idempotente.
   *
   * Il DataStore di [AppSettingsRepository] carica in modo asincrono: ci si
   * aggancia al suo StateFlow invece di leggere una sola volta un valore che al
   * momento dell'init potrebbe essere ancora il default "[]".
   */
  fun init() {
    if (initialized) return
    initialized = true
    initScope.launch {
      AppSettingsRepository.addonHealthJson.collect { json ->
        if (_isChecking.value) return@collect
        val loaded = AddonHealthCodec.decode(json)
        if (loaded != _reports.value) {
          _reports.value = loaded
          if (loaded.isNotEmpty()) {
            _lastCheckedAt.value = loaded.maxOfOrNull { it.checkedAtEpochMs } ?: 0L
          }
        }
      }
    }
  }

  /**
   * Controlla tutti gli addon installati (anche quelli disabilitati, che vengono
   * riportati come [AddonHealthStatus.DISABLED] senza interrogare la rete).
   *
   * @return report aggiornati, nello stesso ordine alfabetico usato dalla UI.
   */
  suspend fun checkAll(): List<AddonHealthReport> {
    if (_isChecking.value) return _reports.value
    _isChecking.value = true
    try {
      val addons = StremioAddonRepository.getInstalledAddons()
      val results = coroutineScope {
        addons.map { addon ->
          async(Dispatchers.IO) { probe(addon) }
        }.awaitAll()
      }.sortedWith(
        compareBy<AddonHealthReport> { it.name.lowercase(Locale.ROOT) }
          .thenBy { it.installId }
      )
      _reports.value = results
      _lastCheckedAt.value = System.currentTimeMillis()
      AppSettingsRepository.setAddonHealthJson(AddonHealthCodec.encode(results))
      Log.i(
        TAG,
        "Health-check completato: ${results.size} addon, " +
          "${results.count { it.status == AddonHealthStatus.HEALTHY }} sani, " +
          "${results.count { it.isProblem }} con problemi"
      )
      return results
    } finally {
      _isChecking.value = false
    }
  }

  /** Report salvato per una specifica installazione, se esiste. */
  fun reportFor(installId: String): AddonHealthReport? =
    _reports.value.firstOrNull { it.installId == installId }

  /**
   * Mappa di affidabilità per la Smart Source Selection: chiavi multiple
   * (installId, manifest id, nome) per tollerare le diverse identità presenti
   * sulle sorgenti.
   */
  fun trustByAddonIdentity(): Map<String, AddonTrust> {
    if (_reports.value.isEmpty()) return emptyMap()
    val addons = StremioAddonRepository.getInstalledAddons()
    val byInstall = addons.associateBy { it.installId }
    val result = LinkedHashMap<String, AddonTrust>()
    _reports.value.forEach { report ->
      val trust = when (report.status) {
        AddonHealthStatus.HEALTHY -> AddonTrust.HEALTHY
        AddonHealthStatus.DEGRADED -> AddonTrust.DEGRADED
        AddonHealthStatus.UNREACHABLE,
        AddonHealthStatus.INVALID_MANIFEST -> AddonTrust.UNREACHABLE
        AddonHealthStatus.DISABLED,
        AddonHealthStatus.UNKNOWN -> AddonTrust.UNKNOWN
      }
      if (trust == AddonTrust.UNKNOWN) return@forEach
      result[report.installId] = trust
      val addon = byInstall[report.installId]
      addon?.manifest?.id?.takeIf { it.isNotBlank() }?.let { result[it] = trust }
      addon?.manifest?.displayTitle?.takeIf { it.isNotBlank() }?.let { result[it] = trust }
    }
    return result
  }

  /** Verifica un singolo addon (usato dai test e da un'eventuale azione puntuale). */
  suspend fun probe(addon: InstalledAddon): AddonHealthReport {
    val now = System.currentTimeMillis()
    if (!addon.isEnabled) {
      return AddonHealthReport(
        installId = addon.installId,
        name = addon.manifest.displayTitle,
        baseUrl = addon.baseUrl,
        status = AddonHealthStatus.DISABLED,
        checkedAtEpochMs = now,
        detail = "Addon disattivato: nessuna verifica di rete"
      )
    }

    val startedAt = System.nanoTime()
    val outcome = try {
      val manifest = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        StremioAddonRepository.fetchManifest(addon.baseUrl)
      }
      val latency = elapsedMs(startedAt)
      if (manifest == null) {
        AddonProbeOutcome(
          success = false,
          latencyMs = latency,
          errorKind = AddonProbeErrorKind.TIMEOUT,
          message = "Tempo scaduto (${PROBE_TIMEOUT_MS} ms)"
        )
      } else {
        AddonProbeOutcome(
          success = true,
          latencyMs = latency,
          manifestVersion = manifest.version
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val message = e.message
      AddonProbeOutcome(
        success = false,
        latencyMs = elapsedMs(startedAt),
        httpCode = AddonHealthClassifier.httpCodeFromMessage(message),
        errorKind = AddonHealthClassifier.classifyFailureMessage(message),
        message = sanitizeDetail(message)
      )
    }

    val (status, detail) = AddonHealthClassifier.classify(outcome)
    return AddonHealthReport(
      installId = addon.installId,
      name = addon.manifest.displayTitle,
      baseUrl = addon.baseUrl,
      status = status,
      latencyMs = outcome.latencyMs,
      checkedAtEpochMs = now,
      detail = detail,
      manifestVersion = outcome.manifestVersion
    )
  }

  /**
   * Rimuove gli URL completi dai messaggi: un baseUrl di addon configurato può
   * contenere un payload Base64 con credenziali Debrid. Restano host e codice HTTP.
   */
  internal fun sanitizeDetail(message: String?): String? {
    if (message.isNullOrBlank()) return null
    return message.replace(Regex("""https?://[^\s)]+""")) { match ->
      val host = runCatching { java.net.URI(match.value).host }.getOrNull()
      host ?: "server"
    }.take(300)
  }

  private fun elapsedMs(startedAtNanos: Long): Long =
    ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
}
