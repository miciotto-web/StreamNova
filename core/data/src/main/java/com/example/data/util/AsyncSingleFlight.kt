package com.example.data.util

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Deduplicazione ("single-flight") delle chiamate sospese: due richieste
 * concorrenti con la *stessa* chiave condividono un'unica esecuzione e
 * ottengono lo stesso risultato.
 *
 * Serve a evitare che il percorso "addon Stremio" e il percorso
 * "TorBoxStreamProvider" di [com.example.data.streaming.StreamManager] — che
 * girano in parallelo — interrogino due volte la stessa API.
 */
internal class AsyncSingleFlight<T> {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val inFlight = ConcurrentHashMap<String, Deferred<T>>()

  suspend fun run(key: String, block: suspend () -> T): T {
    // Creazione LAZY: se un altro task ha già registrato la stessa chiave, la
    // nostra Deferred non viene mai avviata e viene semplicemente scartata.
    val candidate = scope.async(start = CoroutineStart.LAZY) { block() }
    val existing = inFlight.putIfAbsent(key, candidate)
    val active = existing ?: candidate
    if (existing != null) {
      candidate.cancel()
    } else {
      active.start()
    }
    return try {
      active.await()
    } finally {
      inFlight.remove(key, active)
    }
  }
}
