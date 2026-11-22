package com.example.ui.screens.player

import com.example.data.model.MediaItem

/**
 * Artwork delle schermate di caricamento cinematografiche del player (percorsi Debrid e
 * HTTP), ricavato **solo** dai metadati già presenti nel [MediaItem] della sessione
 * (nessuna richiesta di rete aggiuntiva).
 *
 * @param backgroundUrl immagine a tutto schermo: backdrop (formato panoramico, adatto
 *        alla TV) con fallback al poster. `null` se il titolo non ha alcuna immagine:
 *        la schermata resta comunque leggibile su sfondo pieno.
 * @param logoUrl clear logo originale del titolo (`images.logos` TMDB già scelto con la
 *        priorità di lingua dell'app). `null` = TMDB non espone il logo.
 * @param fallbackTitle titolo mostrato al posto del logo quando manca o non è
 *        caricabile: il titolo ORIGINALE se disponibile, altrimenti quello localizzato.
 */
internal data class PlayerLoadingArtwork(
  val backgroundUrl: String?,
  val logoUrl: String?,
  val fallbackTitle: String
)

/**
 * Risolve l'artwork di caricamento dai metadati del titolo, senza richieste.
 *
 * Preferenze:
 *  - sfondo: `backdropUrl` (panoramico) e solo in sua assenza `posterUrl`;
 *  - logo: `logoUrl`; nessun titolo testuale viene usato come sostituto finché il logo
 *    esiste (il fallback è esplicito e avviene solo se il logo manca o non si carica);
 *  - fallback: `originalTitle` (titolo originale), poi `title`.
 */
internal fun playerLoadingArtworkOf(media: MediaItem): PlayerLoadingArtwork = PlayerLoadingArtwork(
  backgroundUrl = media.backdropUrl?.takeIf { it.isNotBlank() }
    ?: media.posterUrl?.takeIf { it.isNotBlank() },
  logoUrl = media.logoUrl?.takeIf { it.isNotBlank() },
  fallbackTitle = media.originalTitle.takeIf { it.isNotBlank() } ?: media.title
)
