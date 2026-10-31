package com.example.ui.screens.splash

import android.os.SystemClock

/**
 * Sequenza temporale esplicita della splash video:
 *
 * ```
 * PLAYING_VIDEO → VIDEO_ENDED → POST_VIDEO_PAUSE (4 s) → SHOW_HOME
 * ```
 *
 * Regole:
 *  - il filmato viene riprodotto per intero: nessun timer lo taglia;
 *  - la pausa di [SPLASH_POST_VIDEO_PAUSE_MS] parte SOLO dopo `STATE_ENDED`
 *    (fine naturale), mai durante la riproduzione;
 *  - il player resta vivo e non viene rilasciato durante la pausa: l'ultimo
 *    frame continua a essere mostrato dalla SurfaceView;
 *  - il cronometro della pausa parte una sola volta, non viene azzerato dalle
 *    ricomposizioni (lo stato vive in questa istanza) e ignora le callback
 *    duplicate di fine video;
 *  - il tempo trascorso in background non viene conteggiato: il conteggio si
 *    ferma in `ON_STOP` e riprende in `ON_START`.
 *
 * Errore del player e fallback di avvio chiudono subito l'intro: sono percorsi
 * d'emergenza che non interferiscono con una riproduzione regolare.
 *
 * Tutti i metodi vanno chiamati dal thread principale (callback del player e
 * del lifecycle). L'orologio è iniettabile per i test JVM.
 */
internal class SplashIntroTimeline(
  private val postVideoPauseMs: Long = SPLASH_POST_VIDEO_PAUSE_MS,
  foreground: Boolean,
  private val now: () -> Long = SystemClock::elapsedRealtime,
  onFinished: () -> Unit
) {

  private val completion = SplashIntroCompletion(onFinished)

  private var hasFirstFrame = false
  private var isPlaybackEnded = false
  private var isForeground = foreground
  private var pauseAccumulatedMs = 0L
  private var pauseRunningSinceMs: Long? = null

  /** Istante monotonic del primo frame renderizzato, per i log diagnostici. */
  var firstFrameRenderedAtMs: Long? = null
    private set

  /** Istante monotonic in cui è partita la riproduzione (`play()`). */
  var playbackStartedAtMs: Long? = null
    private set

  /** Istante monotonic in cui il player ha confermato la fine del video. */
  var videoEndedAtMs: Long? = null
    private set

  /** `true` dalla prima transizione terminale: la splash è già stata chiusa. */
  val isFinished: Boolean get() = completion.isFinished

  /** `true` se il renderer ha già prodotto il primo frame del filmato. */
  val hasRenderedFirstFrame: Boolean get() = hasFirstFrame

  /** `true` se il player ha confermato `STATE_ENDED`. */
  val hasPlaybackEnded: Boolean get() = isPlaybackEnded

  /** Primo frame renderizzato: da qui si misura la durata reale del video. */
  fun onFirstFrameRendered() {
    if (hasFirstFrame) return
    hasFirstFrame = true
    firstFrameRenderedAtMs = now()
  }

  /**
   * La riproduzione è partita (`play()` chiamato con finestra e surface visibili).
   * Da qui si misura la durata effettiva del filmato nei log.
   */
  fun onPlaybackStarted() {
    if (playbackStartedAtMs != null) return
    playbackStartedAtMs = now()
  }

  /** Rientro/uscita dal primo piano: solo la pausa post-video è cronometrata. */
  fun setForeground(foreground: Boolean) {
    if (isForeground == foreground) return
    isForeground = foreground
    if (!isPlaybackEnded) return
    if (foreground) {
      startPauseClock()
    } else {
      stopPauseClock()
    }
  }

  /**
   * Fine naturale del video (`STATE_ENDED`): avvia la pausa sull'ultimo frame.
   *
   * Non chiude la splash. Le callback duplicate non azzerano il cronometro.
   */
  fun onPlaybackEnded() {
    if (isPlaybackEnded) return
    isPlaybackEnded = true
    videoEndedAtMs = now()
    if (isForeground) startPauseClock()
  }

  /**
   * Pausa post-video completata: solo ora la Home può comparire.
   *
   * La verifica sul tempo rende la transizione impossibile anche se un
   * chiamante anticipato prova a chiudere la splash.
   */
  fun onPostVideoPauseElapsed() {
    if (!isPlaybackEnded) return
    if (remainingPostVideoPauseMs() > 0L) return
    completion.onPlaybackEnded()
  }

  /** Errore reale di riproduzione: la Home si apre subito, senza pausa. */
  fun onPlaybackError() {
    completion.onPlaybackError()
  }

  /**
   * Scaduto il limite di avvio senza che sia mai comparso un frame: il video
   * non è avviabile, quindi la Home può aprirsi. Se un frame è già stato
   * mostrato questa chiamata non fa nulla.
   */
  fun onNoFirstFrameTimeout() {
    if (!hasFirstFrame) completion.onTimeout()
  }

  /** Millisecondi di pausa post-video effettivamente trascorsi in primo piano. */
  fun postVideoPauseElapsedMs(): Long {
    if (!isPlaybackEnded) return 0L
    val running = pauseRunningSinceMs?.let { (now() - it).coerceAtLeast(0L) } ?: 0L
    return pauseAccumulatedMs + running
  }

  /** Tempo mancante al termine della pausa (0 quando è già completata). */
  fun remainingPostVideoPauseMs(): Long {
    if (!isPlaybackEnded) return postVideoPauseMs
    return (postVideoPauseMs - postVideoPauseElapsedMs()).coerceAtLeast(0L)
  }

  /** Durata effettiva della riproduzione (avvio → fine video), per i log. */
  fun videoPlaybackDurationMs(): Long? {
    val end = videoEndedAtMs ?: return null
    val start = playbackStartedAtMs ?: firstFrameRenderedAtMs ?: return null
    return (end - start).coerceAtLeast(0L)
  }

  private fun startPauseClock() {
    if (pauseRunningSinceMs == null) pauseRunningSinceMs = now()
  }

  private fun stopPauseClock() {
    pauseRunningSinceMs?.let { pauseAccumulatedMs += (now() - it).coerceAtLeast(0L) }
    pauseRunningSinceMs = null
  }
}
