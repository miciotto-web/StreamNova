package com.example.data.streaming.diagnostics.playback

/**
 * Registro delle sessioni diagnostica di riproduzione del processo.
 *
 * Scopo (FASE 7 del piano): rendere i dati DISPONIBILI per usi successivi —
 * distinguer provider temporaneamente instabili, migliorare i retry, valutare
 * i mirror, alimentare un eventuale circuit breaker — SENZA usare nessuno di
 * questi dati per decidere qualcosa adesso. La selezione dei provider resta
 * gestita da [com.example.data.streaming.StreamManager] e da
 * [com.example.data.streaming.AutoSourcePriority]: nessun circuit breaker è
 * implementato in questa fase, nessun criterio di ranking cambia.
 *
 * Le sessioni sono poche (LRU): il report è costruito su richiesta, la memoria
 * resta limitata. Nessuna persistenza.
 */
object PlaybackDiagnosticsRegistry {

  private val lock = Any()
  private val sessions = LinkedHashMap<Long, HlsPlaybackSession>()

  /** Aggiunge (o sostituisce) una sessione nel registro. */
  fun register(session: HlsPlaybackSession) {
    synchronized(lock) {
      sessions[session.sessionId] = session
      while (sessions.size > MAX_SESSIONS) {
        val eldest = sessions.keys.firstOrNull() ?: break
        sessions.remove(eldest)
      }
    }
  }

  /** Sessione con l'identificativo dato, se ancora presente. */
  fun sessionById(sessionId: Long): HlsPlaybackSession? =
    synchronized(lock) { sessions[sessionId] }

  /** Sessione più recente registrata, se esiste. */
  fun latestSession(): HlsPlaybackSession? =
    synchronized(lock) { sessions.values.lastOrNull() }

  /** Rimuove una sessione (fine riproduzione o pulizia). */
  fun remove(sessionId: Long) {
    synchronized(lock) { sessions.remove(sessionId) }
  }

  /** Svuota il registro (per test e per pulizia esplicita). */
  fun clear() {
    synchronized(lock) { sessions.clear() }
  }

  /** Numero di sessioni attualmente registrate. */
  val size: Int
    get() = synchronized(lock) { sessions.size }

  /**
   * Statistiche aggregate per provider, **solo osservative**.
   *
   * Predisposizione per gli usi futuri (retry mirati, valutazione mirror,
   * circuit breaker): qui non viene presa NESSUNA decisione. I provider
   * senza sessioni registrate non compaiono nel risultato.
   */
  fun providerStats(): List<ProviderReliabilityStats> {
    val byProvider = LinkedHashMap<String, MutableList<HlsDiagnosticsReport>>()
    val reports = synchronized(lock) {
      sessions.values.map { it.buildReport() }
    }
    for (report in reports) {
      val id = report.providerId?.takeIf { it.isNotBlank() } ?: continue
      byProvider.getOrPut(id) { mutableListOf() }.add(report)
    }
    return byProvider.map { (providerId, providerReports) ->
      ProviderReliabilityStats(
        providerId = providerId,
        sessionCount = providerReports.size,
        sessionsWithDefinitiveFailure = providerReports.count { it.finalDiagnosis?.temporary == false },
        sessionsWithTransientProblem = providerReports.count { it.hasTransientProblem },
        sessionsWithoutIssues = providerReports.count { it.finalDiagnosis == null },
        rebufferTotalMs = providerReports.sumOf { it.rebufferTotalMs },
        byCategory = providerReports
          .mapNotNull { it.finalCategory }
          .groupingBy { it }
          .eachCount()
      )
    }
  }

  /**
   * Affidabilità osservata di un provider: **non** un verdetto. Le sessioni
   * con diagnosi definitiva sono conteggiate a parte da quelle temporanee,
   * perché un timeout singolo non è mai una prova di indisponibilità.
   */
  data class ProviderReliabilityStats(
    val providerId: String,
    val sessionCount: Int,
    val sessionsWithDefinitiveFailure: Int,
    val sessionsWithTransientProblem: Int,
    val sessionsWithoutIssues: Int,
    val rebufferTotalMs: Long,
    val byCategory: Map<HlsErrorCategory, Int>
  )

  private const val MAX_SESSIONS = 8
}
