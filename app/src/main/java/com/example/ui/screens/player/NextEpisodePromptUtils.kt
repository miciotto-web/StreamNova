package com.example.ui.screens.player

import com.example.data.model.Episode
import com.example.ui.viewmodel.NextEpisodeAutoplayState

/**
 * Logica di attivazione dell'overlay "Prossimo Episodio" nel player.
 *
 * L'overlay viene mostrato quando la riproduzione entra negli ultimi
 * [PROMPT_WINDOW_MS] millisecondi dell'episodio corrente oppure quando ExoPlayer
 * raggiunge STATE_ENDED, ma SOLO se:
 * - il titolo è una serie TV con un episodio successivo disponibile;
 * - la sessione corrente non è fallita (errore/stream assente);
 * - l'utente non ha chiuso esplicitamente l'overlay (BACK), nel qual caso resta
 *   nascosto fino alla fine del video per consentire la visione dei titoli di coda.
 *
 * Logica pura (nessun riferimento ad Android/Compose): verificabile nei test JVM.
 */
internal object NextEpisodePromptUtils {

  /** La finestra di attivazione copre gli ultimi 35 secondi dell'episodio. */
  const val PROMPT_WINDOW_MS = 35_000L

  /** Secondi del countdown prima del passaggio automatico all'episodio successivo. */
  const val COUNTDOWN_SECONDS = 29

  /**
   * Secondi da mostrare all'apertura del countdown, oppure 0 quando il countdown non
   * deve partire affatto.
   *
   * Il countdown è attivo solo con popup visibile, opzione "Riproduci automaticamente
   * il prossimo episodio" ON e autoplay non bloccato. Quando il popup viene chiuso
   * (BACK / "Guarda ora") o l'autoplay è in ricerca/fallito, il valore è 0: il timer
   * non parte e non può riarmarsi.
   */
  fun countdownStartSeconds(
    promptVisible: Boolean,
    autoPlayNextEpisodeEnabled: Boolean,
    autoplayBlocked: Boolean
  ): Int =
    if (promptVisible && autoPlayNextEpisodeEnabled && !autoplayBlocked) COUNTDOWN_SECONDS else 0

  /** Vero solo quando il countdown è scaduto e l'avanzamento automatico può partire. */
  fun isCountdownExpired(remainingSeconds: Int): Boolean = remainingSeconds <= 0

  /** Tick di un secondo: secondi rimanenti dopo il tick, mai negativi. */
  fun countdownAfterTick(remainingSeconds: Int): Int = (remainingSeconds - 1).coerceAtLeast(0)

  /**
   * Decide se l'overlay "Prossimo Episodio" deve essere visibile.
   *
   * @param positionMs posizione corrente di riproduzione (ms).
   * @param durationMs durata totale dell'episodio (ms); <= 0 = sconosciuta.
   * @param playbackEnded true quando ExoPlayer ha raggiunto STATE_ENDED.
   * @param isTvShow true solo per serie TV.
   * @param hasNextEpisode true se esiste un episodio successivo (nella stagione o nella successiva).
   * @param dismissed true se l'utente ha chiuso esplicitamente l'overlay per questo episodio.
   * @param playbackFailed true se la sessione corrente è fallita (errore/stream assente).
   * @param hasActiveStream true se il player ha DAVVERO uno stream per l'episodio corrente.
   *   Durante il cambio puntata `videoUrl` è null mentre ExoPlayer mostra ancora l'ultimo
   *   frame dell'episodio precedente: senza questo flag la posizione (ferma a fine video)
   *   riattiverebbe il popup per l'episodio sbagliato.
   * @param autoplayBlocked true mentre è in corso la ricerca della stessa fonte Debrid o
   *   dopo un fallimento terminale: il countdown non deve (ri)partire.
   * @param sourceSelectionActive true quando la schermata di scelta delle sorgenti è
   *   aperta: nessun popup/countdown sopra la selezione manuale.
   */
  fun shouldShow(
    positionMs: Long,
    durationMs: Long,
    playbackEnded: Boolean,
    isTvShow: Boolean,
    hasNextEpisode: Boolean,
    dismissed: Boolean,
    playbackFailed: Boolean,
    hasActiveStream: Boolean = true,
    autoplayBlocked: Boolean = false,
    sourceSelectionActive: Boolean = false
  ): Boolean {
    if (!isTvShow || !hasNextEpisode || dismissed || playbackFailed) return false
    // Nessuno stream caricato per QUESTO episodio: la posizione del clock appartiene
    // all'episodio precedente e non è una prova che il video corrente sia finito.
    if (!hasActiveStream) return false
    // Ricerca in corso o autoplay già terminato: il fallimento è uno stato terminale,
    // non un nuovo punto di partenza per il countdown.
    if (autoplayBlocked) return false
    if (sourceSelectionActive) return false
    if (playbackEnded) return true
    // Finestra valida solo se l'episodio dura più della finestra stessa (evita
    // overlay fin dall'inizio su clip brevi); il trigger è position >= duration - 35s.
    if (durationMs <= PROMPT_WINDOW_MS) return false
    return positionMs >= durationMs - PROMPT_WINDOW_MS
  }

  /**
   * Decide se mostrare il messaggio "Nessuna fonte disponibile per il prossimo episodio".
   *
   * Compare SOLO negli stati terminali dell'autoplay ([NextEpisodeAutoplayState.SOURCE_UNAVAILABLE]
   * e [NextEpisodeAutoplayState.MANUAL_SELECTION_REQUIRED]). Resta nascosto durante una
   * nuova ricerca manuale (spinner) e quando la schermata di scelta delle sorgenti è
   * aperta, così non si sovrappone ai controlli che hanno il focus.
   */
  fun shouldShowUnavailableCard(
    autoplayState: NextEpisodeAutoplayState,
    sourceSelectionActive: Boolean,
    resolutionLoading: Boolean
  ): Boolean {
    if (autoplayState != NextEpisodeAutoplayState.SOURCE_UNAVAILABLE &&
      autoplayState != NextEpisodeAutoplayState.MANUAL_SELECTION_REQUIRED
    ) {
      return false
    }
    return !sourceSelectionActive && !resolutionLoading
  }

  /**
   * Risoluzione deterministica dell'episodio successivo:
   * 1. stessa stagione, episodio N+1;
   * 2. se l'episodio corrente è l'ultimo della stagione, il primo episodio della
   *    stagione successiva (E1; in sua assenza l'episodio più basso disponibile).
   *
   * Funziona su liste non ordinate (pool ottenute da merge/concat).
   */
  fun resolveNextEpisode(episodes: List<Episode>, current: Episode?): Episode? {
    if (current == null || episodes.isEmpty()) return null
    episodes
      .firstOrNull { it.seasonNumber == current.seasonNumber && it.episodeNumber == current.episodeNumber + 1 }
      ?.let { return it }
    val nextSeason = episodes.filter { it.seasonNumber == current.seasonNumber + 1 }
    if (nextSeason.isEmpty()) return null
    return nextSeason.firstOrNull { it.episodeNumber == 1 } ?: nextSeason.minByOrNull { it.episodeNumber }
  }
}
