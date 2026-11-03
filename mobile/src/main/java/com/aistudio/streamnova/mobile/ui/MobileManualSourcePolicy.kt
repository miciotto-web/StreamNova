package com.aistudio.streamnova.mobile.ui

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamSource

/**
 * Bersaglio della selezione manuale delle sorgenti: titolo e puntata da cercare.
 *
 * Stessa semantica del pulsante Riproduci: per i film non esiste episodio, per
 * le serie la puntata è quella di ripresa (o quella selezionata nella scheda).
 */
data class ManualSourceTarget(
  val mediaId: String,
  val season: Int? = null,
  val episode: Int? = null
)

/**
 * Regole pure della selezione manuale delle sorgenti, allineate alla TV 1.0.4.
 *
 * La TV espone «Seleziona fonte» nella barra azioni della scheda SOLO in
 * modalità Debrid e apre il selettore delle sorgenti SENZA avviare alcuna
 * riproduzione: l'utente sceglie la sorgente e solo allora parte il player.
 * Queste funzioni raccolgono le decisioni (visibilità del pulsante, riuso del
 * collegamento, apertura del selettore, bersaglio della ricerca) in modo che
 * siano verificabili nei test JVM, senza Compose, senza rete e senza Android.
 */
object MobileManualSourcePolicy {

  /**
   * Il pulsante «Seleziona fonte» esiste SOLO in modalità Debrid: in modalità
   * HTTP/direct il motore non espone un elenco di sorgenti negoziabile e la TV
   * non mostra il pulsante (stessa regola, stesso criterio).
   */
  fun showsSelectSourceAction(engineMode: StreamingEngineMode): Boolean =
    engineMode == StreamingEngineMode.DEBRID_TORBOX

  /**
   * La selezione manuale non riusa mai l'ultimo link: il riuso avvia direttamente
   * la riproduzione e bypasserebbe la scelta dell'utente (stessa regola TV).
   */
  fun shouldReuseLastWorkingStream(manualSelection: Boolean, reuseEnabled: Boolean): Boolean =
    reuseEnabled && !manualSelection

  /**
   * Apertura del selettore delle sorgenti.
   *
   * - con selezione manuale il selettore si apre SEMPRE: anche con autoplay
   *   attivo (la scelta manuale è indipendente dall'impostazione Autoplay) e
   *   anche con zero risultati (l'utente deve poter chiudere e riprovare senza
   *   che parta alcuna riproduzione);
   * - senza selezione manuale vale la regola storica: autoplay OFF in modalità
   *   Debrid.
   */
  fun shouldShowSourcePicker(
    manualSelection: Boolean,
    autoplayEnabled: Boolean,
    engineMode: StreamingEngineMode,
    sourceCount: Int
  ): Boolean = manualSelection ||
    (!autoplayEnabled && engineMode == StreamingEngineMode.DEBRID_TORBOX && sourceCount > 0)

  /**
   * Bersaglio della ricerca manuale: lo STESSO episodio del pulsante Riproduci.
   *
   * Per i film non c'è puntata; per le serie stagione ed episodio sono quelli
   * di ripresa, così il selettore mostra il contesto SxE corretto e la
   * riproduzione parte esattamente sull'episodio scelto.
   */
  fun searchTarget(media: MediaItem, resumeEpisode: Episode?): ManualSourceTarget =
    if (media.type == MediaType.SERIE_TV && resumeEpisode != null) {
      ManualSourceTarget(
        mediaId = media.id,
        season = resumeEpisode.seasonNumber,
        episode = resumeEpisode.episodeNumber
      )
    } else {
      ManualSourceTarget(mediaId = media.id)
    }

  /**
   * Stato del selettore dopo una ricerca manuale senza risultati.
   *
   * Il selettore resta APERTO e vuoto: nessuna riproduzione parte e nessun
   * errore viene mostrato, così l'utente può chiudere il selettore (tornando
   * alla scheda) o riprovare la ricerca.
   */
  fun stateAfterEmptyManualSearch(current: MobilePlayerUiState): MobilePlayerUiState =
    current.copy(
      loading = false,
      awaitingSourceSelection = true,
      manualSelection = true,
      sources = emptyList(),
      error = null
    )

  /**
   * Stato all'inizio dello sblocco di una sorgente scelta.
   *
   * Lo sblocco Debrid on-demand può durare qualche secondo: il selettore resta
   * visibile ma mostra il caricamento, così il tap dell'utente non sembra
   * perduto.
   */
  fun stateAfterSelectionStart(current: MobilePlayerUiState): MobilePlayerUiState =
    current.copy(loading = true)

  /**
   * Stato dopo la scelta di una sorgente.
   *
   * Con [playable] non null il player parte da quella sorgente; con null
   * (sblocco Debrid fallito) il selettore resta aperto con il messaggio
   * d'errore e l'elenco invariato, così l'utente può sceglierne un'altra.
   */
  fun stateAfterSelection(
    current: MobilePlayerUiState,
    playable: StreamSource?
  ): MobilePlayerUiState = if (playable == null) {
    current.copy(
      loading = false,
      awaitingSourceSelection = true,
      error = "Sorgente non riproducibile"
    )
  } else {
    current.copy(
      source = playable,
      loading = false,
      awaitingSourceSelection = false,
      error = null
    )
  }

  /**
   * Stato dopo l'annullamento del selettore: nessuna riproduzione, elenco
   * svuotato, nessun errore residuo.
   */
  fun stateAfterDismiss(current: MobilePlayerUiState): MobilePlayerUiState =
    current.copy(
      loading = false,
      awaitingSourceSelection = false,
      sources = emptyList(),
      error = null
    )
}
