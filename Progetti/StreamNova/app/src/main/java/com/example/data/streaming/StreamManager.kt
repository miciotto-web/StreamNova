package com.example.data.streaming

import android.util.Log
import com.example.data.streaming.providers.Cb01Provider
import com.example.data.streaming.providers.EurostreamingProvider
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Collections

/**
 * Gestore dell'interrogazione dei provider di streaming con supporto a **Fast-Start Immediato**.
 *
 * Ordine di preferenza dei provider:
 *  1. [VixSrcProvider]        - istantaneo (~300ms), risolve via TMDB ID (HLS adattivo);
 *  2. [Cb01Provider]          - catalogo italiano, ricerca per titolo/anno;
 *  3. [EurostreamingProvider] - catalogo italiano, specializzato in Serie TV.
 *
 * Tramite [resolveFlow] il player riceve le sorgenti non appena il primo provider risponde,
 * avviando la riproduzione senza attendere i timeout dei siti più lenti.
 */
class StreamManager(
  private val providers: List<StreamProvider> = listOf(
    VixSrcProvider(),
    Cb01Provider(),
    EurostreamingProvider()
  ),
  private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

  /** Esito singolo per provider: utile per log e diagnostica. */
  data class Outcome(val provider: String, val sources: Int, val error: String?)

  /**
   * Fast-Start Immediato: emette non appena il primo provider risponde con successo,
   * continuando l'arricchimento in background con gli altri provider.
   */
  fun resolveFlow(
    tmdbId: Int,
    isTv: Boolean,
    season: Int? = null,
    episode: Int? = null,
    title: String? = null,
    year: Int? = null
  ): Flow<List<StreamSource>> = channelFlow {
    val collected = Collections.synchronizedList(mutableListOf<StreamSource>())
    val jobs = providers.map { provider ->
      launch {
        val name = provider.javaClass.simpleName
        try {
          val sources = withTimeout(timeoutMs) {
            provider.getStreams(tmdbId, isTv, season, episode, title, year)
          }
          if (sources.isNotEmpty()) {
            Log.i(TAG, "$name -> ${sources.size} sorgente/i trovate (Fast-Start)")
            synchronized(collected) {
              if (provider is VixSrcProvider) {
                // VixSrc prioritario in cima
                val others = collected.filterNot { it.serverName.startsWith("VixCloud") }
                collected.clear()
                collected.addAll(sources)
                collected.addAll(others)
              } else {
                sources.forEach { s ->
                  if (collected.none { it.url == s.url }) {
                    collected.add(s)
                  }
                }
              }
            }
            send(synchronized(collected) { collected.toList() })
          }
        } catch (e: TimeoutCancellationException) {
          Log.w(TAG, "$name -> timeout dopo ${timeoutMs}ms")
        } catch (e: Exception) {
          Log.w(TAG, "$name -> ${e.message}")
        }
      }
    }
    jobs.joinAll()
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
  }
}
