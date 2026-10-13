package com.example.ui.screens.player

import com.example.data.repository.SimklMediaPayload
import com.example.data.repository.SimklRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Adatta gli eventi utente del player al protocollo SIMKL Scrobble. */
class SimklScrobbler(
  private val scope: CoroutineScope,
  private val media: SimklMediaPayload
) {
  private var active = false
  private var started = false
  private var paused = false

  fun onPlay(progressPercent: Double) {
    if (!active) {
      active = true
      started = false
    }
    if (!started || paused) {
      started = true
      paused = false
      dispatch { SimklRepository.startScrobble(media, progressPercent) }
    }
  }

  fun onPause(progressPercent: Double) {
    if (active && started && !paused) {
      paused = true
      dispatch { SimklRepository.pauseScrobble(media, progressPercent) }
    }
  }

  fun onStop(progressPercent: Double) {
    if (!active) return
    active = false
    if (started) dispatch { SimklRepository.stopScrobble(media, progressPercent) }
    started = false
    paused = false
  }

  fun onEnded() {
    if (!active) return
    active = false
    if (!started) return
    started = false
    paused = false
    dispatch { SimklRepository.stopScrobble(media, 100.0) }
  }

  private fun dispatch(block: suspend () -> Unit) {
    scope.launch(Dispatchers.IO) { runCatching { block() } }
  }
}
