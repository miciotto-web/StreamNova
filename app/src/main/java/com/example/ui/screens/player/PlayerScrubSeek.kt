package com.example.ui.screens.player

import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Scrubbing a schermo intero con le frecce del telecomando (◀ / ▶ a HUD nascosto).
 *
 * Logica in tre pezzi indipendenti dal resto del player: [PlayerScrub] (costanti,
 * mappatura dei tasti, limiti del contenuto), [PlayerScrubSession] (stato puro della
 * sessione) e [PlayerScrubController] (l'unico punto che parla con il player).
 * [PlayerScreen] traduce gli eventi D-pad in chiamate al controller, che a sua volta
 * pubblica lo stato di anteprima da mostrare nell'overlay.
 *
 * Con l'HUD a schermo le frecce NON arrivano qui: restano a Compose, che sposta il focus
 * fra i controlli a video. Lo scrubbing esiste solo quando i controlli sono nascosti.
 */
internal object PlayerScrub {

  /** Passo di anteprima applicato da ogni pressione (o ripetizione) di ◀ / ▶. */
  const val STEP_MS = 10_000L

  /**
   * Silenzio che conferma lo scrubbing: senza nuove pressioni per questo tempo la
   * posizione di anteprima diventa una seek reale e la riproduzione riprende.
   */
  const val COMMIT_DELAY_MS = 900L

  /** Verso dello scrubbing: indietro. */
  const val REWIND = -1

  /** Verso dello scrubbing: avanti. */
  const val FORWARD = 1

  /** Direzione associata a un tasto: 0 per i tasti che non governano lo scrubbing. */
  fun directionForKeyCode(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> REWIND
    KeyEvent.KEYCODE_DPAD_RIGHT -> FORWARD
    else -> 0
  }

  /**
   * Vincola la posizione fra inizio del contenuto e fine. La durata è vincolante solo
   * quando nota: con `C.TIME_UNSET` (o 0) resta il solo limite inferiore, come per i
   * flussi il cui limite superiore lo conosce solo il player.
   */
  fun clampPosition(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)
}

/**
 * Stato puro di una sessione di scrubbing: da dove è partita, dove si trova l'anteprima e
 * se la riproduzione era attiva. Non conosce il player e non tiene thread: la seek reale
 * avviene solo alla conferma, quindi [positionMs] è una posizione *proposta*.
 */
internal class PlayerScrubSession(
  val originPositionMs: Long,
  val durationMs: Long,
  val wasPlaying: Boolean,
  startDirection: Int
) {

  /**
   * Posizione di anteprima. La pressione che apre lo scrubbing vale già un passo
   * ([PlayerScrub.STEP_MS]) in avanti o indietro: una pressione breve resta il classico
   * salto di 10 secondi, e le pressioni successive si sommano a partire da lì.
   */
  var positionMs: Long = PlayerScrub.clampPosition(
    originPositionMs + startDirection * PlayerScrub.STEP_MS,
    durationMs
  )
    private set

  var direction: Int = startDirection
    private set

  /** Sposta l'anteprima di un passo nel verso indicato, senza uscire dal contenuto. */
  fun step(direction: Int) {
    this.direction = direction
    positionMs = PlayerScrub.clampPosition(positionMs + direction * PlayerScrub.STEP_MS, durationMs)
  }

  /** Distanza fra anteprima e punto di partenza: negativa all'indietro, 0 ai limiti. */
  val deltaMs: Long get() = positionMs - originPositionMs
}

/** Stato pubblicato all'interfaccia mentre lo scrubbing è in corso (overlay di anteprima). */
internal data class PlayerScrubUiState(
  val positionMs: Long,
  val durationMs: Long,
  val originPositionMs: Long,
  val wasPlayingBeforeScrub: Boolean
) {
  /** Distanza fra anteprima e punto di partenza: negativa all'indietro, 0 ai limiti. */
  val deltaMs: Long get() = positionMs - originPositionMs
}

/**
 * Guida una [PlayerScrubSession] sul player esistente: nessun secondo player e nessuna
 * copia dello stato di riproduzione. Il player non viene mai spostato durante lo
 * scrubbing: la posizione di anteprima diventa una seek solo alla conferma, così le
 * pressioni ravvicinate non producono seek intermedie.
 *
 * @param scope scope della schermata player: alla sua cancellazione non resta alcun job.
 * @param player istanza già in uso (pausa, seek e ripresa passano solo da qui).
 * @param onScrubStateChanged nuovo stato di anteprima, oppure null a scrubbing concluso.
 */
internal class PlayerScrubController(
  private val scope: CoroutineScope,
  private val player: Player,
  private val onScrubStateChanged: (PlayerScrubUiState?) -> Unit = {}
) {

  private var session: PlayerScrubSession? = null
  private var commitJob: Job? = null

  /** true mentre una sessione di scrubbing è aperta. */
  val isScrubbing: Boolean get() = session != null

  /**
   * Pressione (o ripetizione) di ◀ / ▶ a controlli nascosti. Alla prima pressione mette in
   * pausa (registrando se la riproduzione era attiva), apre l'anteprima a un passo dal
   * punto corrente e arma il timer di conferma; le pressioni successive spostano
   * l'anteprima e riarmano il timer. Su contenuti non ricercabili (live o flussi senza
   * seek) non fa nulla.
   */
  fun onArrowPressed(direction: Int) {
    val active = session
    if (active != null) {
      active.step(direction)
      Log.d(TAG, "Anteprima ${directionLabel(direction)}: ${active.positionMs}ms (delta=${active.deltaMs}ms)")
      publish(active)
      armCommitTimer()
      return
    }

    if (!player.isCurrentMediaItemSeekable) {
      Log.i(TAG, "Scrubbing non avviato: flusso non ricercabile (live o senza seek)")
      return
    }

    val originMs = player.currentPosition.coerceAtLeast(0L)
    val started = PlayerScrubSession(
      originPositionMs = originMs,
      durationMs = player.duration,
      wasPlaying = player.isPlaying || player.playWhenReady,
      startDirection = direction
    )
    session = started
    // Il video si ferma sull'anteprima: durante lo scrubbing la posizione mostrata è
    // quella scelta dall'utente, non quella del player (che resta al punto di partenza).
    player.pause()
    Log.i(
      TAG,
      "Scrubbing avviato: direction=$direction, da=${originMs}ms, durata=${player.duration}ms, " +
        "anteprima=${started.positionMs}ms, riproduzione da ripristinare=${started.wasPlaying}"
    )
    publish(started)
    armCommitTimer()
  }

  /**
   * Conferma lo scrubbing: la posizione di anteprima diventa una seek reale e, se il video
   * era in riproduzione, la riproduzione riprende. Idempotente: senza sessione non fa nulla.
   */
  fun commit() {
    commitJob?.cancel()
    commitJob = null
    finish(applyPreview = true)
  }

  /**
   * Annulla lo scrubbing: nessuna seek (il player non è mai stato spostato, quindi è
   * ancora al punto di partenza) e riproduzione ripristinata se era attiva.
   */
  fun cancel() {
    commitJob?.cancel()
    commitJob = null
    finish(applyPreview = false)
  }

  /**
   * Chiusura senza effetti sul player (uscita dal player o ricreazione del player):
   * nessuna seek, nessuna ripresa, nessun job residuo. Lo stato di anteprima viene
   * comunque chiuso, altrimenti l'overlay resterebbe a schermo su un player nuovo.
   */
  fun release() {
    commitJob?.cancel()
    commitJob = null
    val wasActive = session != null
    session = null
    if (wasActive) onScrubStateChanged(null)
  }

  private fun armCommitTimer() {
    commitJob?.cancel()
    commitJob = scope.launch {
      delay(PlayerScrub.COMMIT_DELAY_MS)
      commitJob = null
      finish(applyPreview = true)
    }
  }

  private fun finish(applyPreview: Boolean) {
    val active = session ?: return
    session = null
    if (applyPreview) {
      player.seekTo(active.positionMs)
      if (active.wasPlaying) player.play()
      Log.i(
        TAG,
        "Scrubbing confermato: seek a ${active.positionMs}ms (da ${active.originPositionMs}ms, " +
          "delta=${active.deltaMs}ms), ripresa=${active.wasPlaying}"
      )
    } else {
      if (active.wasPlaying) player.play()
      Log.i(
        TAG,
        "Scrubbing annullato: nessuna seek, riproduzione al punto di partenza " +
          "${active.originPositionMs}ms (ripresa=${active.wasPlaying})"
      )
    }
    onScrubStateChanged(null)
  }

  private fun publish(active: PlayerScrubSession) {
    onScrubStateChanged(
      PlayerScrubUiState(
        positionMs = active.positionMs,
        durationMs = active.durationMs,
        originPositionMs = active.originPositionMs,
        wasPlayingBeforeScrub = active.wasPlaying
      )
    )
  }

  private fun directionLabel(direction: Int): String =
    if (direction == PlayerScrub.FORWARD) "avanti" else "indietro"

  private companion object {
    private const val TAG = "ScrubSeek"
  }
}
