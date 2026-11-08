package com.example.ui.screens.player

/**
 * Fasi del caricamento mostrate dall'impostazione "Mostra stato di caricamento".
 *
 * Ogni fase deriva ESCLUSIVAMENTE da eventi reali già disponibili nel player
 * (ExoPlayer `playbackState`) e nel resolver delle sorgenti
 * ([com.example.ui.viewmodel.StreamNovaViewModel.StreamResolutionState]):
 * nessuna percentuale, progresso o tempo di caricamento simulato.
 */
internal enum class PlayerLoadingPhase {
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

/**
 * Risoluzione PURA della fase di caricamento corrente a partire dai flag reattivi
 * già presenti in PlayerScreen. Nessun accesso a ExoPlayer: verificabile in test JVM.
 */
internal object PlayerLoadingStatus {

  /**
   * @param hasPlaybackError errore di riproduzione/risoluzione attivo (nessun overlay
   *   di stato: vale l'overlay di errore esistente, quindi la fase non è usata).
   * @param hasSourceUrl `true` quando un URL di flusso è disponibile per il player;
   *   `false` copre sia la risoluzione in corso (`StreamResolutionState.Loading`) sia
   *   l'attesa di una sorgente: in entrambi i casi la fase REALE è "preparazione".
   * @param isPlayerReady `true` dopo il primo `Player.STATE_READY` del caricamento
   *   corrente (variabile `playerReady` di PlayerScreen).
   * @param isBuffering `true` quando il player è in `Player.STATE_BUFFERING`.
   */
  fun resolvePhase(
    hasPlaybackError: Boolean,
    hasSourceUrl: Boolean,
    isPlayerReady: Boolean,
    isBuffering: Boolean
  ): PlayerLoadingPhase = when {
    // Con un errore attivo l'overlay di stato non è mostrato: fase non significativa.
    hasPlaybackError -> PlayerLoadingPhase.READY
    // Nessun URL ancora: la sorgente è in preparazione (resolver/provider al lavoro),
    // indipendentemente dal fatto che il resolver stia ancora lavorando o sia fermo.
    !hasSourceUrl -> PlayerLoadingPhase.PREPARING_SOURCE
    // URL presente ma player non ancora pronto: primo caricamento del contenuto.
    !isPlayerReady -> if (isBuffering) PlayerLoadingPhase.LOADING_CONTENT
      else PlayerLoadingPhase.CONNECTING
    // Player pronto: buffering reale solo se lo stato transport lo dice.
    isBuffering -> PlayerLoadingPhase.BUFFERING
    else -> PlayerLoadingPhase.READY
  }
}
