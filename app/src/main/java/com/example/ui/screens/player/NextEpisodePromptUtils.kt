package com.example.ui.screens.player

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
  const val COUNTDOWN_SECONDS = 10

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
   */
  fun shouldShow(
    positionMs: Long,
    durationMs: Long,
    playbackEnded: Boolean,
    isTvShow: Boolean,
    hasNextEpisode: Boolean,
    dismissed: Boolean,
    playbackFailed: Boolean
  ): Boolean {
    if (!isTvShow || !hasNextEpisode || dismissed || playbackFailed) return false
    if (playbackEnded) return true
    // Finestra valida solo se l'episodio dura più della finestra stessa (evita
    // overlay fin dall'inizio su clip brevi); il trigger è position >= duration - 35s.
    if (durationMs <= PROMPT_WINDOW_MS) return false
    return positionMs >= durationMs - PROMPT_WINDOW_MS
  }
}
