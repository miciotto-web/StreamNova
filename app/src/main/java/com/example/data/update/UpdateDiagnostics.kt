package com.example.data.update

import android.util.Log

/**
 * Diagnostica leggera di un download APK: una sola riga strutturata su Logcat
 * (tag `UpdateDiagnostics`) con i dati minimi per ricostruire dove e perché il
 * download è fallito — codice HTTP, host iniziale e finale (redirect inclusi),
 * durata, fase, tipo di eccezione, byte ricevuti ed esito della verifica
 * SHA-256 — senza esporre nulla di sensibile:
 *
 *  - nessun token, password o dato personale;
 *  - gli URL compaiono solo come `protocollo://hostname`, mai con path o query
 *    string (gli host arrivano da `HttpUrl.host`, quindi senza firma né
 *    credenziali: stessa garanzia di [safeHostForLog]);
 *  - nessun contenuto delle risposte.
 *
 * L'oggetto viene creato da [UpdateRepository.downloadUpdate] con il contesto
 * di versione e passato ad [ApkDownloader.download], che lo completa con i
 * dati di trasporto ed emette la riga una sola volta: `logSuccess` al termine
 * verificato, `logFailure` su qualsiasi errore, `logCancelled` se annullato
 * dall'utente.
 */
class DownloadDiagnostics(
  /** Versione installata al momento del download (es. "1.0.4"). */
  val installedVersion: String? = null,
  /** Versione richiesta, cioè quella dell'APK selezionato (es. "1.0.5"). */
  val requestedVersion: String? = null
) {

  /** Nome dell'asset APK selezionato. */
  var assetName: String? = null

  /** Dimensione dichiarata dall'asset su GitHub (byte), quando nota. */
  var declaredSizeBytes: Long? = null

  /** Codice HTTP della risposta finale. */
  var httpCode: Int? = null

  /** Endpoint iniziale (`protocollo://host`) della catena di richieste. */
  var initialEndpoint: String? = null

  /** Endpoint finale (`protocollo://host`) dopo gli eventuali redirect. */
  var finalEndpoint: String? = null

  /** Numero di redirect seguiti per arrivare alla risposta finale. */
  var redirectCount: Int? = null

  /** Durata complessiva del download (ms), dal via alla conclusione. */
  var durationMs: Long? = null

  /** Fase in cui il download si è interrotto; null se concluso. */
  var failedPhase: String? = null

  /** Nome semplice della classe dell'eccezione che ha interrotto il download. */
  var exceptionType: String? = null

  /** Byte effettivamente ricevuti e scritti su file. */
  var downloadedBytes: Long? = null

  /** Esito della verifica SHA-256: [CHECKSUM_OK], [CHECKSUM_MISMATCH] o [CHECKSUM_SKIPPED]. */
  var checksum: String? = null

  /** Motivo tipizzato dell'ultimo fallimento registrato; null se mai fallito. */
  var failureReason: DownloadErrorReason? = null

  /** Download concluso con file verificato: l'utente può installare. */
  fun logSuccess() {
    Log.i(TAG, formatLine(esito = ESITO_OK, motivo = null))
  }

  /** Download annullato dall'utente: non è un malfunzionamento, traccia informativa. */
  fun logCancelled(phase: String) {
    failedPhase = phase
    exceptionType = "CancellationException"
    Log.i(TAG, formatLine(esito = ESITO_ANNULLATO, motivo = null))
  }

  /** Download fallito: la riga porta il motivo tipizzato mostrato all'utente. */
  fun logFailure(reason: DownloadErrorReason) {
    failureReason = reason
    Log.w(TAG, formatLine(esito = ESITO_FALLITO, motivo = reason.name))
  }

  /**
   * Riga di log in forma `chiave=valore`: i campi non noti vengono omessi, così
   * la riga resta compatta e stabile da filtrare.
   */
  private fun formatLine(esito: String, motivo: String?): String =
    listOfNotNull(
      "esito=$esito",
      motivo?.let { "motivo=$it" },
      installedVersion?.let { "installata=$it" },
      requestedVersion?.let { "richiesta=$it" },
      assetName?.let { "asset=$it" },
      declaredSizeBytes?.let { "dimensione_dichiarata=$it" },
      httpCode?.let { "http=$it" },
      initialEndpoint?.let { "host_iniziale=$it" },
      finalEndpoint?.let { "host_finale=$it" },
      redirectCount?.let { "redirect=$it" },
      durationMs?.let { "durata_ms=$it" },
      failedPhase?.let { "fase=$it" },
      exceptionType?.let { "eccezione=$it" },
      downloadedBytes?.let { "byte_scaricati=$it" },
      checksum?.let { "sha256=$it" }
    ).joinToString(" ")

  companion object {
    const val TAG = "UpdateDiagnostics"

    const val ESITO_OK = "OK"
    const val ESITO_FALLITO = "FALLITO"
    const val ESITO_ANNULLATO = "ANNULLATO"

    const val CHECKSUM_OK = "OK"
    const val CHECKSUM_MISMATCH = "MISMATCH"

    /** Nessun digest dichiarato dall'asset: la verifica non era possibile. */
    const val CHECKSUM_SKIPPED = "SALTATO"

    /** Preparazione: validazione URL, creazione directory, controllo spazio. */
    const val PHASE_PREPARAZIONE = "PREPARAZIONE"

    /** Richiesta HTTP in corso (connessione, redirect, ricezione header). */
    const val PHASE_RICHIESTA = "RICHIESTA"

    /** Risposta HTTP non riuscita: codice non positivo. */
    const val PHASE_RISPOSTA_HTTP = "RISPOSTA_HTTP"

    /** Lettura del corpo e scrittura del file. */
    const val PHASE_LETTURA = "LETTURA_CORPO"

    /** Verifica finale: dimensione dichiarata e digest SHA-256. */
    const val PHASE_VERIFICA = "VERIFICA"
  }
}
