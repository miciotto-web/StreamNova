package com.example.data.streaming.diagnostics

/**
 * Punto unico di scelta del sink diagnostico.
 *
 * In `debug` restituisce [LogcatStreamDiagnosticSink]: i tentativi vengono
 * scritti sul solo Logcat locale sotto il tag [LogcatStreamDiagnosticSink.TAG].
 * In `release` restituisce [StreamDiagnosticSink.NONE], quindi nessun costo e
 * nessuna emissione di log: il comportamento della build release e' identico a
 * quello di un resolver senza diagnostica.
 */
object StreamDiagnosticSinkFactory {

  val DEFAULT: StreamDiagnosticSink = StreamDiagnosticSink.NONE
}
