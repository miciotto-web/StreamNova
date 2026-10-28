package com.example.ui.screens.player

import com.example.data.repository.SimklMediaPayload
import com.example.data.repository.SimklRepository
import com.example.data.repository.SimklRepository.SimklScrobbleEvent

/**
 * Adatta gli eventi utente del player al protocollo SIMKL Scrobble.
 *
 * **Non ha uno scope proprio.** Le scritture sono accodate in [SimklRepository], che le
 * esegue su una coda di durata di processo: legare la scrittura allo scope della
 * composizione la rendeva annullabile, e la chiusura del player — cioè esattamente il
 * momento in cui serve salvare il punto di ripresa — cancellava la richiesta `scrobble/stop`
 * mentre era in volo.
 *
 * [progressPercent] è `null` quando la durata non è nota: la percentuale non è calcolabile e
 * l'evento viene rifiutato a monte invece di spedire uno zero che non corrisponde a nulla.
 * In quel caso lo stato NON passa a "inviato", così il primo evento successivo con una
 * durata reale (tipicamente l'arrivo di `STATE_READY`) manda il `start` che mancava.
 *
 * [submit] è il punto di iniezione per i test: di default accoda verso SIMKL.
 */
class SimklScrobbler(
  private val media: SimklMediaPayload,
  private val submit: (SimklScrobbleEvent, SimklMediaPayload, Double?) -> Boolean =
    SimklRepository::enqueueScrobble
) {
  private var active = false
  private var started = false
  private var paused = false

  fun onPlay(progressPercent: Double?) {
    if (!active) {
      active = true
      started = false
      paused = false
    }
    // Già avviato e non in pausa: nessun secondo `start` (le callback del player arrivano
    // più volte per lo stesso avvio).
    if (started && !paused) return
    if (submit(SimklScrobbleEvent.START, media, progressPercent)) {
      started = true
      paused = false
    }
  }

  fun onPause(progressPercent: Double?) {
    if (!active || !started || paused) return
    if (submit(SimklScrobbleEvent.PAUSE, media, progressPercent)) paused = true
  }

  fun onStop(progressPercent: Double?) {
    if (!active) return
    active = false
    paused = false
    // Senza `start` accettato non c'è una sessione da chiudere su SIMKL: inviare uno `stop`
    // isolato (o con una percentuale inventata) scriverebbe un punto di ripresa falso.
    if (!started) return
    started = false
    submit(SimklScrobbleEvent.STOP, media, progressPercent)
  }

  /** Fine naturale della riproduzione: la sessione si chiude al 100%. */
  fun onEnded() {
    if (!active) return
    active = false
    paused = false
    if (!started) return
    started = false
    submit(SimklScrobbleEvent.STOP, media, 100.0)
  }
}
