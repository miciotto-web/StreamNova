package com.example.ui.screens.splash

/**
 * Pausa mostrata sull'ULTIMO FOTOGRAMMA dopo la fine naturale del video.
 *
 * La sequenza obbligatoria della splash è:
 *   riproduzione integrale del filmato → [SPLASH_POST_VIDEO_PAUSE_MS] di pausa
 *   sull'ultimo frame → Home.
 *
 * Non è un tempo minimo di visibilità durante la riproduzione: la pausa parte
 * solo dopo `Player.STATE_ENDED` e il video non viene mai tagliato o accelerato.
 */
internal const val SPLASH_POST_VIDEO_PAUSE_MS = 4_000L

/**
 * Tempo massimo concesso all'intro per mostrare il PRIMO frame.
 *
 * Serve solo al caso "impossibilità di avviare il video" (decoder che non
 * consegna alcun frame, senza errore esplicito): a quel punto non c'è nulla da
 * riprodurre e la Home viene aperta. Non può interrompere un video che ha già
 * reso almeno un frame, quindi non accorcia mai una riproduzione funzionante.
 */
internal const val SPLASH_VIDEO_START_TIMEOUT_MS = 8_000L

/**
 * Stato terminale dell'intro video di StreamNova.
 *
 * Tre eventi diversi possono chiudere l'intro: il completamento della pausa
 * dopo la fine del video, un errore di decodifica/caricamento e il fallback di
 * avvio. Tutti devono portare alla Home, ma la transizione deve avvenire UNA
 * sola volta: callback duplicate del player o un errore mentre la splash si sta
 * già smontando non devono far ripartire la navigazione né riaprire la schermata.
 *
 * La classe è deliberatamente priva di dipendenze Android: i percorsi e la
 * loro idempotenza sono coperti da test JVM senza decoder reale.
 */
internal class SplashIntroCompletion(
  private val onFinished: () -> Unit
) {

  /** `true` dalla prima transizione terminale in poi. */
  var isFinished: Boolean = false
    private set

  /** Il filmato è finito e la pausa è completa: si entra nella Home. */
  fun onPlaybackEnded(): Boolean = finishOnce()

  /** Video non caricabile/decodificabile: si entra nella Home senza attese. */
  fun onPlaybackError(): Boolean = finishOnce()

  /** Impossibile mostrare il video entro il limite di avvio: si entra nella Home. */
  fun onTimeout(): Boolean = finishOnce()

  /**
   * Conclude l'intro se non è già conclusa.
   *
   * @return `true` solo per la transizione che ha effettivamente chiuso la
   *   splash, `false` per ogni segnale successivo (che va ignorato).
   */
  fun finishOnce(): Boolean {
    if (isFinished) return false
    isFinished = true
    onFinished()
    return true
  }
}
