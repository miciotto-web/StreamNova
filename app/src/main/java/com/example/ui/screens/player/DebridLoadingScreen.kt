package com.example.ui.screens.player

import com.example.data.model.MediaItem
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamSource

/**
 * Artwork della schermata di caricamento Debrid, ricavato **solo** dai metadati già
 * presenti nel [MediaItem] della sessione (nessuna richiesta di rete aggiuntiva).
 *
 * @param backgroundUrl immagine a tutto schermo: backdrop (formato panoramico, adatto
 *        alla TV) con fallback al poster. `null` se il titolo non ha alcuna immagine:
 *        la schermata resta comunque leggibile su sfondo pieno.
 * @param logoUrl clear logo originale del titolo (`images.logos` TMDB già scelto con la
 *        priorità di lingua dell'app). `null` = TMDB non espone il logo.
 * @param fallbackTitle titolo mostrato al posto del logo quando manca o non è
 *        caricabile: il titolo ORIGINALE se disponibile, altrimenti quello localizzato.
 */
internal data class DebridLoadingArtwork(
  val backgroundUrl: String?,
  val logoUrl: String?,
  val fallbackTitle: String
)

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
   *
   * Preferenze:
   *  - sfondo: `backdropUrl` (panoramico) e solo in sua assenza `posterUrl`;
   *  - logo: `logoUrl`; nessun titolo testuale viene usato come sostituto finché il logo
   *    esiste (il fallback è esplicito e avviene solo se il logo manca o non si carica);
   *  - fallback: `originalTitle` (titolo originale), poi `title`.
   */
  fun artworkOf(media: MediaItem): DebridLoadingArtwork = DebridLoadingArtwork(
    backgroundUrl = media.backdropUrl?.takeIf { it.isNotBlank() }
      ?: media.posterUrl?.takeIf { it.isNotBlank() },
    logoUrl = media.logoUrl?.takeIf { it.isNotBlank() },
    fallbackTitle = media.originalTitle.takeIf { it.isNotBlank() } ?: media.title
  )
}
