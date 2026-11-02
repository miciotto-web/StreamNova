package com.example

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Tracciamento TEMPORALE dell'avvio dell'app.
 *
 * Serve a distinguere il tempo di bootstrap (processo/Application/MainActivity) dal
 * tempo dello splash video, che è un percorso separato: senza questa distinzione
 * un ritardo nella prima schermata viene attribuito "al video" solo perché è la
 * prima cosa visibile.
 *
 * Ogni riga riporta due misure monotone:
 *  - `since_process_start_ms`: tempo dall'avvio del processo Android (base
 *    `Process.getStartElapsedRealtime()`, disponibile da API 24);
 *  - `elapsed_ms`: `SystemClock.elapsedRealtime()`, per correlare gli eventi tra
 *    di loro e con `adb logcat`.
 *
 * Il tag dedicato è filtrabile con `adb logcat -s StartupTrace`. Le righe sono
 * poche e a bassa frequenza: restano attive anche in release, così la misura è
 * ripetibile sul dispositivo reale (Fire TV) e non solo in debug.
 */
object StartupTrace {

  const val TAG: String = "StartupTrace"

  private val processStartElapsedMs: Long =
    runCatching { Process.getStartElapsedRealtime() }.getOrDefault(0L)

  /** Evento puntuale dell'avvio. */
  fun mark(event: String) {
    Log.i(TAG, "$event ${measurements()}")
  }

  /** Durata di un blocco sincrono eseguito sul thread chiamante. */
  inline fun <T> trace(label: String, block: () -> T): T {
    val startedAt = SystemClock.elapsedRealtime()
    try {
      return block()
    } finally {
      Log.i(TAG, "$label duration_ms=${SystemClock.elapsedRealtime() - startedAt} ${measurements()}")
    }
  }

  @PublishedApi
  internal fun measurements(): String {
    val now = SystemClock.elapsedRealtime()
    val sinceProcessStart = if (processStartElapsedMs > 0L) now - processStartElapsedMs else -1L
    return "since_process_start_ms=$sinceProcessStart elapsed_ms=$now"
  }
}
