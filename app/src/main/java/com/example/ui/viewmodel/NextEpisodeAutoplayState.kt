package com.example.ui.viewmodel

/**
 * Stato esplicito del tentativo di **autoplay del prossimo episodio**.
 *
 * Il passaggio automatico a fine episodio non è un semplice "chiama playNextEpisode":
 * tra il popup e l'avvio reale c'è una ricerca della stessa fonte Debrid. Lo stato
 * distingue in modo univoco ogni fase, così il player può decidere quando mostrare
 * il countdown e quando invece fermarsi.
 *
 * Il fallimento della ricerca è **terminale** per quel tentativo: [SOURCE_UNAVAILABLE]
 * e [MANUAL_SELECTION_REQUIRED] bloccano ogni ulteriore avanzamento automatico finché
 * l'utente non sceglie manualmente una fonte (o chiude la sessione).
 */
enum class NextEpisodeAutoplayState {

  /** Nessun tentativo di autoplay in corso (riproduzione normale o modalità non Debrid). */
  IDLE,

  /** Ricerca della STESSA fonte Debrid dell'episodio corrente in corso. */
  SEARCHING_SAME_SOURCE,

  /** Stessa fonte trovata e riproduzione del nuovo episodio avviata con successo. */
  STARTED,

  /**
   * Stessa fonte non disponibile (assente, vuota, errore o timeout).
   *
   * Stato terminale: l'autoplay NON deve avanzare ad altri episodi né ripetere la
   * ricerca. L'utente deve poter aprire la selezione manuale delle sorgenti.
   */
  SOURCE_UNAVAILABLE,

  /**
   * L'utente sta scegliendo manualmente una fonte per l'episodio.
   *
   * La selezione manuale è l'unico modo per uscire dallo stato di fallimento; al
   * termine (fonte scelta oppure selettore chiuso) lo stato viene aggiornato.
   */
  MANUAL_SELECTION_REQUIRED;

  /** Vero per gli stati in cui un nuovo tentativo automatico deve essere ignorato. */
  val blocksAutomaticAdvance: Boolean
    get() = this == SEARCHING_SAME_SOURCE ||
      this == SOURCE_UNAVAILABLE ||
      this == MANUAL_SELECTION_REQUIRED

  /** Vero per gli stati terminali di un tentativo di autoplay fallito. */
  val isFailure: Boolean
    get() = this == SOURCE_UNAVAILABLE || this == MANUAL_SELECTION_REQUIRED
}
