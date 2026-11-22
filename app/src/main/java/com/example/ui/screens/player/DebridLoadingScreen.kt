package com.example.ui.screens.player

import com.example.data.model.MediaItem
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamSource

/**
 * Decisione di visibilità della schermata di caricamento cinematografica del percorso
 * **Debrid** (poster a tutto schermo + logo pulsante + dicitura di caricamento).
 *
 * Perché non basta `isBuffering`: ExoPlayer entra in `STATE_BUFFERING` anche dopo che la
 * riproduzione è già partita (cambio rendition, seek, stallo di rete). In quei casi deve
 * restare l'overlay "Caricamento" compatto con isteresi ([PlayerBufferingOverlay]), non
 * una schermata a tutto schermo. La scena cinematografica è quindi legata al **primo**
 * caricamento del flusso: da quando il player viene preparato fino a `STATE_READY`
 * ([isPlayerReady]).
 *
 * Regole:
 *  - solo modalità [StreamingEngineMode.DEBRID_TORBOX] (in quella modalità il resolver
 *    esegue esclusivamente addon Stremio + TorBox: ogni sorgente è del percorso Debrid);
 *  - la sorgente deve avere un'identità Debrid (torrent con `infoHash` sbloccato da
 *    TorBox oppure stream restituito da un addon Stremio). Un provider HTTP puro non
 *    attiva mai la schermata;
 *  - visibile solo durante il caricamento INIZIALE: buffering e player non ancora READY;
 *  - errore di riproduzione o di risoluzione → la schermata sparisce e vale il normale
 *    stato di errore già previsto;
 *  - scrubbing → fuori schermo (l'unico elemento a video è l'anteprima della posizione).
 *
 * Funzione PURA, verificabile in test JVM senza Compose né ExoPlayer.
 */
internal object DebridLoadingScreen {

  /**
   * `true` se la sessione corrente appartiene al percorso di avvio Debrid.
   *
   * Il filtro sul motore non è ridondante: in [StreamingEngineMode.HTTP_WEB] gli addon
   * Stremio e TorBox non vengono nemmeno interrogati, quindi una sorgente con queste
   * caratteristiche non può appartenere al percorso HTTP.
   */
  fun isDebridStartup(source: StreamSource?, engineMode: StreamingEngineMode): Boolean {
    if (engineMode != StreamingEngineMode.DEBRID_TORBOX) return false
    if (source == null) return false
    // Torrent sbloccato da TorBox (infoHash) o stream già risolto da un addon Stremio.
    return !source.infoHash.isNullOrBlank() || !source.addonName.isNullOrBlank()
  }

  /**
   * `true` se mostrare la schermata cinematografica in questo istante.
   *
   * [isPlayerReady] diventa `false` all'apertura del player e a ogni nuovo caricamento
   * (episodio, sorgente, URL) e torna `true` al primo `Player.STATE_READY`: è quindi il
   * segnale che distingue il caricamento iniziale dai successivi stalli della
   * riproduzione, senza toccare il flag `isBuffering` usato dal resto del player.
   */
  fun shouldShow(
    isDebridSource: Boolean,
    isBuffering: Boolean,
    isPlayerReady: Boolean,
    hasPlaybackError: Boolean,
    isScrubbing: Boolean
  ): Boolean = isDebridSource && isBuffering && !isPlayerReady && !hasPlaybackError && !isScrubbing

  /**
   * Costruisce l'artwork della schermata dai metadati del titolo, senza richieste.
   * Risoluzione condivisa con il percorso HTTP ([playerLoadingArtworkOf]): backdrop
   * con fallback poster, logo originale, titolo come unico fallback esplicito.
   */
  fun artworkOf(media: MediaItem): PlayerLoadingArtwork = playerLoadingArtworkOf(media)
}
