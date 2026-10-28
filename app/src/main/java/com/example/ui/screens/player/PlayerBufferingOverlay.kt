package com.example.ui.screens.player

/**
 * Decisione di visibilità dell'overlay "Caricamento" del player, con isteresi temporale.
 *
 * ExoPlayer entra in `STATE_BUFFERING` per due motivi molto diversi:
 *
 * 1. uno stallo VERO (il buffer si è svuotato e il player aspetta rete/decoder): l'utente
 *    deve vedere che sta caricando;
 * 2. una transizione BREVE che non ferma il video per più di qualche decimo di secondo:
 *    la riconfigurazione del decoder a ogni cambio di rendition (non adattivo su questi
 *    flussi HLS) e l'istantanea di un seek.
 *
 * Mostrare l'overlay per il caso 2 fa apparire e sparire lo spinner a ripetizione — la
 * "buffering continuo" percepita — anche quando il video è sempre andato avanti: nei log
 * del Fire TV del 24-25/10/2026 una riproduzione normale cambiava rendition 7-8 volte al
 * minuto. L'overlay viene quindi mostrato solo se lo stato di caricamento sopravvive a
 * [REVEAL_DELAY_MS]; sotto quella soglia la riproduzione non viene coperta.
 *
 * Questa classe contiene SOLO la decisione (verificabile in test JVM); il ritardo è
 * applicato dall'effetto Compose in [com.example.ui.screens.PlayerScreen].
 */
internal object PlayerBufferingOverlay {

  /**
   * Ritardo minimo (ms) di permanenza in caricamento prima di mostrare l'overlay.
   *
   * Valore scelto per assorbire i cambi di rendition (nell'ordine delle centinaia di ms
   * sui log di device) restando comunque impercettibile su uno stallo reale: uno spinner
   * che appare dopo 0,6 s viene letto come immediato, mentre un buffering più breve non
   * merita di coprire il video.
   */
  const val REVEAL_DELAY_MS = 600L

  /**
   * `true` se lo stato corrente merita l'overlay (una volta trascorso [REVEAL_DELAY_MS]).
   *
   * Durante lo scrubbing l'overlay resta fuori schermo perché l'unico elemento a video è
   * l'anteprima della posizione; in presenza di un messaggio di errore comanda l'overlay
   * di errore, non lo spinner.
   */
  fun isCandidate(
    isBuffering: Boolean,
    isScrubbing: Boolean,
    hasErrorMessage: Boolean
  ): Boolean = isBuffering && !isScrubbing && !hasErrorMessage
}
