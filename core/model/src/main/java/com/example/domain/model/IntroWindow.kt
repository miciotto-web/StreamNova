package com.example.domain.model

/**
 * Intervallo della sigla iniziale, espresso in millisecondi dall'inizio del media.
 *
 * [isValid] richiede un inizio non negativo e una fine strettamente successiva
 * all'inizio: nessuna stima o default viene inventato quando i timestamp mancano.
 */
data class IntroWindow(val startMs: Long, val endMs: Long) {
  val isValid: Boolean get() = startMs >= 0L && endMs > startMs
}
