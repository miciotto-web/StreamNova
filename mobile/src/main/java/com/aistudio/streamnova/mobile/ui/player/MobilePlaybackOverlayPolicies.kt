package com.aistudio.streamnova.mobile.ui.player

/**
 * Logica PURA delle opzioni "Overlay e avvisi di riproduzione" (sezione Riproduzione),
 * specchio mobile delle classi della TV (`PlayerLoadingStatus`, `PauseOverlayPolicy`):
 * stesse regole, stesse costanti, nessuna dipendenza da Compose o ExoPlayer.
 *
 * Ogni fase deriva ESCLUSIVAMENTE da eventi reali già disponibili nel player
 * (ExoPlayer `playbackState`) e nel resolver delle sorgenti: nessuna percentuale,
 * progresso o tempo di caricamento simulato.
 */

/** Fasi del caricamento mostrate dall'impostazione "Mostra stato di caricamento". */
enum class MobilePlayerLoadingPhase {
  /** Risoluzione della sorgente in corso (provider/debrid non hanno ancora un URL). */
  PREPARING_SOURCE,
  /** URL pronto, media item impostato ma il player non è ancora entrato in BUFFERING. */
  CONNECTING,
  /** Primo caricamento del contenuto: player in BUFFERING prima del primo READY. */
  LOADING_CONTENT,
  /** Buffering durante la riproduzione (player già READY in passato). */
  BUFFERING,
  /** Player in STATE_READY e non in buffering. */
  READY
}

/** Risoluzione PURA della fase di caricamento corrente (verificabile in test JVM). */
object MobilePlayerLoadingStatus {

  /**
   * @param hasPlaybackError errore di riproduzione/risoluzione attivo (nessun overlay
   *   di stato: vale l'overlay di errore esistente, quindi la fase non è usata).
   * @param hasSourceUrl `true` quando un URL di flusso è disponibile per il player;
   *   `false` copre la risoluzione in corso e l'attesa di una sorgente.
   * @param isPlayerReady `true` dopo il primo `Player.STATE_READY` del caricamento
   *   corrente.
   * @param isBuffering `true` quando il player è in `Player.STATE_BUFFERING`.
   */
  fun resolvePhase(
    hasPlaybackError: Boolean,
    hasSourceUrl: Boolean,
    isPlayerReady: Boolean,
    isBuffering: Boolean
  ): MobilePlayerLoadingPhase = when {
    hasPlaybackError -> MobilePlayerLoadingPhase.READY
    !hasSourceUrl -> MobilePlayerLoadingPhase.PREPARING_SOURCE
    !isPlayerReady -> if (isBuffering) MobilePlayerLoadingPhase.LOADING_CONTENT
      else MobilePlayerLoadingPhase.CONNECTING
    isBuffering -> MobilePlayerLoadingPhase.BUFFERING
    else -> MobilePlayerLoadingPhase.READY
  }
}

/**
 * Politica PURA dell'overlay in pausa (opzione "Overlay in pausa"), identica alla TV:
 *
 *  - la pausa dura almeno [REVEAL_DELAY_MS] → l'overlay compare;
 *  - la ripresa (o un cambio contenuto/episodio, un errore, il buffering) prima dei
 *    5 secondi ANNULLA il timer: l'overlay non è mai comparso;
 *  - la ripresa mentre è visibile lo nasconde IMMEDIATAMENTE.
 *
 * Il timer vive in un solo `LaunchedEffect` del player: il riavvio dell'effetto su
 * ogni cambio dei criteri implementa sia l'annullamento sia l'occultamento immediato.
 */
object MobilePauseOverlayPolicy {

  /** Millisecondi di pausa prima della comparsa dell'overlay (5 s, come sulla TV). */
  const val REVEAL_DELAY_MS = 5_000L

  /**
   * `true` quando il timer di comparsa deve essere avviato (o lasciato correre) in
   * questo istante. Il chiamante passa SOLO stati reali già tracciati dal player.
   */
  fun shouldStartTimer(
    enabled: Boolean,
    isPaused: Boolean,
    isPlayerReady: Boolean,
    isBuffering: Boolean,
    hasPlaybackError: Boolean,
    playbackEnded: Boolean,
    isScrubbing: Boolean
  ): Boolean = enabled && isPaused && isPlayerReady && !isBuffering &&
    !hasPlaybackError && !playbackEnded && !isScrubbing
}
