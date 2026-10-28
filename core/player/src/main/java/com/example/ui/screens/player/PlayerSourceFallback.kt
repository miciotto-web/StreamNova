package com.example.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamSource

/**
 * Fallback automatico della sorgente video: contratto puro e deterministico
 * (nessuna dipendenza da Compose o dall'istanza di ExoPlayer) usato dal player
 * per decidere se, dopo un errore **definitivo** della sorgente attiva, esiste
 * un'alternativa gia' risolta da avviare.
 *
 * Il selettore manuale ([PlayerSourceSelector]) resta l'equivalente esplicito di
 * questo percorso: qui la stessa lista di candidati viene consultata in modo
 * automatico, ma solo quando l'errore dice che la sorgente corrente **non e'
 * recuperabile** (vedi [classify]).
 *
 * REGOLE (nessuna euristica aggressiva):
 *  - un errore transitorio (rete instabile, timeout, 5xx) NON fa cambiare
 *    sorgente: la sorgente corrente resta valida;
 *  - il solo stato `Player.STATE_BUFFERING` non e' un errore e non entra mai in
 *    questo percorso (Media3 non invoca `onPlayerError` per il buffering);
 *  - gli errori di una traccia sottotitoli o di un codec/del decoder non si
 *    risolvono cambiando sorgente e sono quindi di competenza dei rami che li
 *    gestiscono gia' (sottotitoli, fallback codec 720p).
 */
object PlayerSourceFallback {

  /**
   * Esito della classificazione di un errore Media3 rispetto alla sorgente in
   * riproduzione.
   */
  enum class Verdict {
    /** Errore definitivo della sorgente: ha senso provare un'alternativa. */
    SOURCE_DEFINITIVE,

    /** Errore transitorio (rete, risposta temporanea del server): nessun cambio. */
    TRANSIENT,

    /** Errore non attribuibile alla sorgente (sottotitoli, codec/driver): nessun cambio. */
    NOT_SOURCE_RELATED
  }

  /**
   * Classifica l'errore di riproduzione riportato da Media3.
   *
   * Sono **definitivi per la sorgente** solo gli errori che descrivono la risorsa
   * come non utilizzabile: risposta HTTP non riproducibile (403/404/410 tipici
   * dei link scaduti o protetti), file mancante o non permesso, contenuto non
   * video (pagina HTML al posto del flusso), URL in chiaro non consentito e
   * manifest/container illeggibile o non supportato.
   *
   * Restano **transitori** gli errori di rete (connessione fallita o in timeout,
   * `ERROR_CODE_TIMEOUT`) e quelli di un server che risponde 408/425/429/5xx
   * (condizione temporanea lato server): in questi casi la sorgente non e'
   * dichiarata irrecuperabile e non si cambia flusso.
   *
   * Restano infine **non attribuibili alla sorgente** gli errori di traccia
   * sottotitoli e quelli di decodifica/codec audio-video: sono gestiti dai rami
   * dedicati del player (ripresa senza la traccia problematica, fallback codec
   * 720p) e un cambio di sorgente non li risolverebbe.
   *
   * Per gli errori non riconosciuti (codici nuovi o `ERROR_CODE_UNSPECIFIED`) la
   * classificazione prudente e' [Verdict.TRANSIENT]: nessun cambio automatico.
   */
  fun classify(error: PlaybackException): Verdict {
    // Sottotitoli/testo: la traccia viene scartata e il video prosegue.
    if (PlayerCodecErrorClassifier.isSubtitleTrackError(error)) return Verdict.NOT_SOURCE_RELATED
    // Codec/decoder o uscita audio: competenza del fallback codec e della
    // configurazione audio, non della sorgente.
    if (isDecoderOrOutputError(error)) return Verdict.NOT_SOURCE_RELATED

    return when (error.errorCode) {
      PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
      PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
      PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
      PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
      PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
      PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
      PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
      PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
      PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        if (isTransientHttpStatus(error)) Verdict.TRANSIENT else Verdict.SOURCE_DEFINITIVE

      // Rete e condizioni temporanee: la sorgente resta quella in riproduzione.
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
      PlaybackException.ERROR_CODE_TIMEOUT,
      PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
      PlaybackException.ERROR_CODE_UNSPECIFIED,
      PlaybackException.ERROR_CODE_REMOTE_ERROR,
      PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> Verdict.TRANSIENT

      // Codici non previsti (incluso DRM): nessun cambio automatico.
      else -> Verdict.TRANSIENT
    }
  }

  /**
   * Chiave di identita' logica di una sorgente.
   *
   * Per gli stream Debrid l'identita' e' il torrent ([StreamSource.infoHash] +
   * indice file), per le sorgenti dirette l'URL: due voci della lista che
   * puntano allo stesso flusso non vengono mai tentate due volte nello stesso
   * ciclo di riproduzione.
   */
  fun keyOf(source: StreamSource): String {
    source.infoHash?.trim()?.takeIf { it.isNotBlank() }?.let { hash ->
      return "hash:$hash/${source.fileIdx ?: -1}"
    }
    source.streamUrl?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
      return keyForUrl(url)
    }
    // Torrent non ancora sbloccato (nessun URL): identita' dai metadati dichiarati.
    return "meta:${source.serverName}|${source.quality}|${source.releaseTitle.orEmpty()}"
  }

  /** Chiave logica di una sorgente identificata dal solo URL (sorgente attiva). */
  fun keyForUrl(url: String): String = "url:${url.trim()}"

  /**
   * Prossima sorgente alternativa da provare, oppure `null` se non ne esiste una.
   *
   * Vengono scartate:
   *  - la sorgente che ha appena fallito ([currentKey]);
   *  - tutte le sorgenti gia' tentate in questo ciclo di riproduzione
   *    ([attemptedKeys]), cosi' non esistono cicli ne' ping-pong tra due flussi;
   *  - le voci non riproducibili senza un passaggio aggiuntivo (torrent non
   *    ancora sbloccato, manifest JSON, URL non HTTP): restano al selettore
   *    manuale, che le risolve on-demand.
   *
   * L'ordine di [candidates] e' quello del ranking di [StreamManager] (qualita'
   * migliore prima): l'alternativa scelta e' quindi la migliore rimasta, senza
   * introdurre un nuovo criterio di ordinamento.
   */
  fun selectAlternative(
    candidates: List<StreamSource>,
    currentKey: String?,
    attemptedKeys: Set<String>
  ): StreamSource? = candidates.firstOrNull { candidate ->
    val key = keyOf(candidate)
    key != currentKey &&
      key !in attemptedKeys &&
      StreamManager.isPlayableDirectUrl(candidate.streamUrl.orEmpty())
  }

  /** Codici HTTP temporanei (lato server): la sorgente non e' dichiarata irrecuperabile. */
  private val TRANSIENT_HTTP_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

  /**
   * `true` se Media3 ha fallito con un codice HTTP temporaneo (5xx, 429, 408).
   * In quel caso un cambio di sorgente sarebbe una reazione a una condizione
   * passeggera del server, non a una sorgente irraggiungibile.
   */
  private fun isTransientHttpStatus(error: PlaybackException): Boolean {
    val responseCode = generateSequence<Throwable>(error) { it.cause }
      .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
      .firstOrNull()
      ?.responseCode
      ?: return false
    return responseCode in TRANSIENT_HTTP_STATUS_CODES
  }

  /**
   * `true` per gli errori di decoder/decodifica (inizializzazione, query,
   * decodifica o formato oltre le capacita' del dispositivo) e per quelli della
   * traccia audio in uscita: sono problemi del dispositivo o del formato, non
   * dell'indirizzo da cui arriva il flusso.
   */
  private fun isDecoderOrOutputError(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> true

    else -> false
  }
}
