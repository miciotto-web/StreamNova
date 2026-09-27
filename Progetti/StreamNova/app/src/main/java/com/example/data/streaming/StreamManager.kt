package com.example.data.streaming

import android.util.Log
import com.example.data.streaming.providers.Cb01Provider
import com.example.data.streaming.providers.EurostreamingProvider
import com.example.data.streaming.providers.MultiEmbedProvider
import com.example.data.streaming.providers.StreamingCommunityProvider
import com.example.data.streaming.providers.SuperEmbedProvider
import com.example.data.streaming.providers.TwoEmbedProvider
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Gestore dell'interrogazione dei provider di streaming con supporto a **Priorità Qualitativa Intelligente**
 * e **Fast-Start**.
 *
 * Logica di prioritizzazione:
 *  1. Se un provider restituisce una sorgente Full HD (>= 1080p), questa viene emessa **subito**
 *     e posta in cima come prioritaria per il player.
 *  2. Se entro 1.2 secondi sono disponibili solo flussi a 720p o inferiori (es. VixSrc per alcune serie TV),
 *     il flusso a 720p viene avviato come fallback per garantire il Fast-Start senza bloccare la riproduzione.
 *  3. L'arricchimento continua in background per fornire tutte le sorgenti al selettore di risoluzione.
 */
class StreamManager(
  private val providers: List<StreamProvider> = listOf(
    MultiEmbedProvider(),
    VixSrcProvider(),
    TwoEmbedProvider(),
    SuperEmbedProvider(),
    StreamingCommunityProvider(),
    Cb01Provider(),
    EurostreamingProvider()
  ),
  private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

  /** Esito singolo per provider: utile per log e diagnostica. */
  data class Outcome(val provider: String, val sources: Int, val error: String?)

  /**
   * Risoluzione concorrente reattiva via Flow con priorità 1080p e finestra di sicurezza 1.2s.
   */
  fun resolveFlow(
    tmdbId: Int,
    isTv: Boolean,
    season: Int? = null,
    episode: Int? = null,
    title: String? = null,
    year: Int? = null
  ): Flow<List<StreamSource>> = channelFlow {
    val collected = mutableListOf<StreamSource>()
    val mutex = Mutex()
    var emittedFirst = false
    var currentSelectedUrl: String? = null

    suspend fun logAndSendSelected(sources: List<StreamSource>) {
      if (sources.isNotEmpty()) {
        val best = sources.first()
        if (best.url != currentSelectedUrl) {
          currentSelectedUrl = best.url
          Log.i(TAG, "[StreamManager] Flusso selezionato: ${best.serverName} con qualità ${best.quality} e URL ${best.url}")
        }
        send(sources)
      }
    }

    // Fallback timer: se entro 1.2 secondi nessun provider restituisce >= 1080p,
    // emetti il miglior flusso disponibile finora (es. 720p) come Fast-Start.
    val fallbackTimerJob = launch {
      delay(FAST_START_FALLBACK_DELAY_MS)
      mutex.withLock {
        if (!emittedFirst && collected.isNotEmpty()) {
          emittedFirst = true
          logAndSendSelected(collected.toList())
        }
      }
    }

    val jobs = providers.map { provider ->
      launch {
        val name = provider.javaClass.simpleName
        try {
          val sources = withTimeout(timeoutMs) {
            provider.getStreams(tmdbId, isTv, season, episode, title, year)
          }
          if (sources.isNotEmpty()) {
            Log.i(TAG, "$name -> ${sources.size} sorgente/i trovate")
            mutex.withLock {
              sources.forEach { s ->
                if (collected.none { it.url == s.url }) {
                  collected.add(s)
                }
              }
              // Riordina la lista con priorità qualitativa decrescente
              collected.sortWith(compareByDescending { qualityScore(it.quality) })

              val has1080p = collected.any { isFullHdOrHigher(it.quality) }
              if (has1080p) {
                // Flusso Full HD 1080p presente: cancellazione immediata del fallback timer ed emissione prioritaria
                fallbackTimerJob.cancel()
                emittedFirst = true
                logAndSendSelected(collected.toList())
              } else if (emittedFirst) {
                // Se è già stato avviato il playback (fallback 720p o altro), aggiorna la lista per il selettore
                logAndSendSelected(collected.toList())
              }
            }
          }
        } catch (e: TimeoutCancellationException) {
          Log.w(TAG, "$name -> timeout dopo ${timeoutMs}ms")
        } catch (e: Exception) {
          Log.w(TAG, "$name -> ${e.message}")
        }
      }
    }

    jobs.joinAll()
    fallbackTimerJob.cancel()

    mutex.withLock {
      if (!emittedFirst && collected.isNotEmpty()) {
        emittedFirst = true
        logAndSendSelected(collected.toList())
      }
    }
  }

  /**
   * Risolve le sorgenti disponibili restituendo il primo risultato non vuoto disponibile (Fast-Start).
   */
  suspend fun resolve(
    tmdbId: Int,
    isTv: Boolean,
    season: Int? = null,
    episode: Int? = null,
    title: String? = null,
    year: Int? = null
  ): List<StreamSource> {
    return resolveFlow(tmdbId, isTv, season, episode, title, year).firstOrNull { it.isNotEmpty() }
      ?: emptyList()
  }

  companion object {
    private const val TAG = "StreamManager"
    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val FAST_START_FALLBACK_DELAY_MS = 1200L

    fun isFullHdOrHigher(quality: String): Boolean {
      val q = quality.lowercase()
      return q.contains("1080") || q.contains("4k") || q.contains("2160") || q.contains("fhd") || q.contains("uhd")
    }

    fun qualityScore(quality: String): Int {
      val q = quality.lowercase()
      return when {
        q.contains("4k") || q.contains("2160") || q.contains("uhd") -> 200
        q.contains("1080") || q.contains("fhd") -> 100
        q.contains("720") || q.contains("hd") -> 50
        q.contains("auto") -> 30
        q.contains("480") || q.contains("sd") -> 20
        else -> 10
      }
    }
  }
}
