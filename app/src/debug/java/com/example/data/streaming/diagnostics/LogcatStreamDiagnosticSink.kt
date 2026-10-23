package com.example.data.streaming.diagnostics

import android.util.Log

/**
 * Sink diagnostico **solo debug**: scrive una riga di Logcat locale per ogni
 * tentativo. Non apre socket, non persiste nulla e non sospende: [record] e'
 * sincrono e non blocca la coroutine chiamante (Android spegne o tronca le
 * righe di log quando il buffer e' pieno, non attende I/O di rete).
 *
 * Formato (chiave=valore, valori gia' sanificati da [StreamDiagnostics]):
 *
 * ```
 * D SM_DIAG search=12 provider=Cb01Provider mirror=cb01uno.top extractor=MaxStreamExtractor outcome=SUCCESS queueMs=0 runMs=812 msg=HTTP 403 su https://cdn.example
 * ```
 *
 * - `search`: identificativo della ricerca ([StreamAttempt.searchId]);
 * - `mirror`/`extractor`: null quando il tentativo non li prevede;
 * - `queueMs`/`runMs`: null se il chiamante non li conosce (es. tentativo
 *   interno del provider, che vede solo il proprio tempo di esecuzione);
 * - `msg`: messaggio sanificato (solo origin, niente path/query/token).
 */
class LogcatStreamDiagnosticSink : StreamDiagnosticSink {

  override fun record(attempt: StreamAttempt) {
    Log.d(TAG, format(attempt))
  }

  companion object {
    const val TAG = "SM_DIAG"
    private const val NULL = "-"
    private val ROOT_SEARCH_ID = Regex("""\bsearch=\d+\s*""")

    /**
     * Rende la riga senza rischio di nuova riga (log injection) e con il
     * contesto della ricerca sanificato: `msg` puo' provenire da un testo
     * remoto e non deve poter falsificare le chiavi `search=`/`provider=`.
     */
    fun format(attempt: StreamAttempt): String {
      val message = attempt.message?.let { singleLine(it).replace(ROOT_SEARCH_ID, "") }?.takeIf { it.isNotBlank() }
      return buildString {
        append("search=").append(attempt.searchId?.toString() ?: NULL)
        append(" provider=").append(singleLine(attempt.providerId).ifBlank { NULL })
        append(" mirror=").append(attempt.mirrorId?.let { singleLine(it) } ?: NULL)
        append(" extractor=").append(attempt.extractorId?.let { singleLine(it) } ?: NULL)
        append(" outcome=").append(attempt.outcome.name)
        append(" queueMs=").append(attempt.queueMs?.toString() ?: NULL)
        append(" runMs=").append(attempt.runMs?.toString() ?: attempt.durationMs.toString())
        if (message != null) append(" msg=").append(message)
      }
    }

    private fun singleLine(value: String): String =
      value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
  }
}
