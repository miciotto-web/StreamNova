package com.example.data.local

import androidx.room.Entity

/**
 * Entità Room per la persistenza dell'ultimo flusso **funzionante** riprodotto
 * (funzione "Riusa l'ultimo link").
 *
 * La chiave primaria composita (mediaId, seasonNumber, episodeNumber) identifica in modo
 * univoco un film oppure un singolo episodio di una serie. Per i film [seasonNumber] e
 * [episodeNumber] valgono [NO_EPISODE] (-1), così la stessa tabella copre entrambi i casi
 * senza colonne nullable nella chiave.
 *
 * @param streamUrl link (o token/id Debrid) che è stato riprodotto con successo.
 * @param streamTitle nome file / etichetta del server del flusso salvato.
 * @param streamQuality qualità dichiarata del flusso (es. "1080p").
 * @param serverName nome del provider/addon di origine.
 * @param headersJson header HTTP (Referer/User-Agent) serializzati in JSON, se presenti.
 * @param isProgressive hint tipo contenuto (true = file progressivo, false = HLS).
 * @param streamMode modalità del motore che ha generato il flusso
 *        ([com.example.data.prefs.StreamingEngineMode.name]): `"HTTP_WEB"` oppure
 *        `"DEBRID_TORBOX"`. Permette a "Continua a guardare" di NON riusare una fonte
 *        appartenente a una modalità diversa da quella attiva. Null per le voci legacy.
 * @param cachedAtTimestamp istante di salvataggio (System.currentTimeMillis()).
 */
@Entity(
  tableName = "last_working_streams",
  primaryKeys = ["mediaId", "seasonNumber", "episodeNumber"]
)
data class LastWorkingStreamEntity(
  val mediaId: String,
  val seasonNumber: Int = NO_EPISODE,
  val episodeNumber: Int = NO_EPISODE,
  val streamUrl: String,
  val streamTitle: String? = null,
  val streamQuality: String? = null,
  val serverName: String? = null,
  val headersJson: String? = null,
  val isProgressive: Boolean? = null,
  val streamMode: String? = null,
  val cachedAtTimestamp: Long = System.currentTimeMillis()
) {
  companion object {
    /** Valore sentinella per film o campi episodio non applicabili. */
    const val NO_EPISODE = -1
  }
}
