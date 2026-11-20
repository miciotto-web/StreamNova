package com.example.data.debrid

/**
 * Servizio Debrid selezionato nella sezione Impostazioni → "Motore di riproduzione".
 *
 * Il motore Debrid dell'app (Stremio Addon + Instant Debrid) parla con UN solo
 * servizio alla volta, scelto dall'utente: i tre servizi sono accostati e
 * intercambiabili, tutti accessibili con login tramite chiave API.
 *
 * @param storage valore stabile persistito su DataStore (chiave `debrid_service`).
 */
enum class DebridService(val storage: String) {
  /** TorBox Instant Debrid (api.torbox.app) — servizio storico dell'app. */
  TORBOX("torbox"),

  /** Real-Debrid (api.real-debrid.com) — aggiunto per accostamento a TorBox. */
  REALDEBRID("realdebrid"),

  /** Premiumize (www.premiumize.me) — aggiunto per accostamento a TorBox. */
  PREMIUMIZE("premiumize");

  companion object {
    /**
     * Converte il valore salvato su disco. Un valore sconosciuto (servizio
     * rimosso in una versione futura) o assente torna su TORBOX: nessuna
     * configurazione deve lasciare l'app in uno stato non valido.
     */
    fun fromStorage(value: String?): DebridService =
      entries.firstOrNull { it.storage == value?.trim()?.lowercase() } ?: TORBOX
  }
}
