package com.example.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException

/**
 * Attribuzione di un errore di riproduzione alla sorgente che lo ha prodotto.
 *
 * Il player deve distinguere due situazioni opposte:
 *
 *  - l'errore riguarda **una sola traccia sottotitoli esterna** (file OpenSubtitles
 *    scaricato in cache oppure sidecar Stremio): il video NON va interrotto, la sorgente
 *    video NON va cambiata e la traccia problematica va rimossa;
 *  - l'errore riguarda **la sorgente video**: va gestito come prima (fallback codec
 *    720p, cambio sorgente, errore definitivo).
 *
 * L'attribuzione usa l'errore e il contesto del renderer/sorgente, mai il solo fatto che
 * un sottotitolo fosse stato selezionato:
 *
 *  1. **renderer**: un errore del renderer di testo con MIME type sottotitoli è per
 *     definizione un errore della traccia testo;
 *  2. **sorgente**: l'URI fallito, recuperato dalla catena delle cause, viene confrontato
 *     con gli URI delle tracce sottotitoli allegate al `MediaItem` e con l'URI del video:
 *     la corrispondenza decide a chi appartiene l'errore;
 *  3. **MIME type rifiutato**: quando il renderer rifiuta il formato di una traccia
 *     ("legacy decoding is disabled, can't handle application/x-subrip samples"), il
 *     MIME type citato nel messaggio dice a cosa si riferisce l'errore.
 *
 * Il risultato è [Attribution.UNKNOWN] quando l'errore non è attribuibile: in quel caso
 * la gestione resta quella attuale (l'errore viene considerato della sorgente video).
 */
internal object PlayerExternalSubtitleAttribution {

  /** A chi appartiene l'errore di riproduzione. */
  enum class Attribution {
    /** L'errore riguarda una traccia sottotitoli esterna: il video deve continuare. */
    EXTERNAL_SUBTITLE,

    /** L'errore riguarda la sorgente video: va gestito normalmente. */
    VIDEO_SOURCE,

    /** L'errore non è attribuibile: gestione normale (nessuna eccezione). */
    UNKNOWN
  }

  /**
   * Esito dell'attribuzione.
   *
   * @param attribution a chi appartiene l'errore.
   * @param failedSubtitleUri URI della traccia sottotitoli fallita, quando identificabile:
   *   permette di rimuovere **solo** quella traccia e di lasciare intatte le altre.
   */
  data class Verdict(
    val attribution: Attribution,
    val failedSubtitleUri: String? = null
  ) {
    val isExternalSubtitle: Boolean get() = attribution == Attribution.EXTERNAL_SUBTITLE
  }

  /**
   * Attribuisce [error] alla traccia che lo ha prodotto.
   *
   * @param videoUri URI della sorgente video in riproduzione.
   * @param externalSubtitleUris URI delle tracce sottotitoli esterne allegate al
   *   `MediaItem` in riproduzione (non le tracce embedded del contenitore video).
   */
  fun attribute(
    error: PlaybackException,
    videoUri: String?,
    externalSubtitleUris: List<String>
  ): Verdict {
    // 1. Errore del renderer di testo: la traccia sottotitoli è per definizione la causa.
    if (PlayerCodecErrorClassifier.isSubtitleTrackError(error)) {
      return Verdict(Attribution.EXTERNAL_SUBTITLE)
    }

    val messages = messagesOf(error)

    // 2a. URI fallito dichiarato dalla sorgente (errore HTTP con DataSpec).
    val failedUri = failingUri(error)
    if (failedUri != null) {
      externalSubtitleUris.firstOrNull { sameUri(it, failedUri) }?.let { matched ->
        return Verdict(Attribution.EXTERNAL_SUBTITLE, matched)
      }
      if (videoUri != null && sameUri(videoUri, failedUri)) {
        return Verdict(Attribution.VIDEO_SOURCE)
      }
      // La sorgente fallita NON e' il video: il merge del player contiene solo la
      // sorgente video e le tracce sottotitoli esterne, quindi e' una di queste.
      if (videoUri != null && externalSubtitleUris.isNotEmpty()) {
        return Verdict(Attribution.EXTERNAL_SUBTITLE)
      }
    }

    // 2b. Errore su file locale: il percorso del file compare nel messaggio
    //     (FileNotFoundException/FileDataSourceException) senza un DataSpec.
    matchByPath(messages, externalSubtitleUris)?.let { matched ->
      return Verdict(Attribution.EXTERNAL_SUBTITLE, matched)
    }
    if (videoUri != null && matchByPath(messages, listOf(videoUri)) != null) {
      return Verdict(Attribution.VIDEO_SOURCE)
    }

    // 3. Rifiuto del formato della traccia da parte del renderer: il MIME type citato
    //    dice di quale traccia si tratta.
    val rejectedMime = rejectedSampleMimeType(messages)
    if (rejectedMime != null && PlayerCodecErrorClassifier.isSubtitleMimeType(rejectedMime)) {
      return Verdict(Attribution.EXTERNAL_SUBTITLE)
    }

    return Verdict(Attribution.UNKNOWN)
  }

  /** Messaggi di tutti gli errori della catena delle cause. */
  private fun messagesOf(error: PlaybackException): List<String> =
    generateSequence<Throwable>(error) { it.cause }
      .mapNotNull { it.message }
      .filter { it.isNotBlank() }
      .toList()

  /** URI dichiarato dalla sorgente HTTP che ha fallito, se presente. */
  fun failingUriOf(error: PlaybackException): String? = failingUri(error)

  private fun failingUri(error: PlaybackException): String? =
    generateSequence<Throwable>(error) { it.cause }
      .filterIsInstance<HttpDataSource.HttpDataSourceException>()
      .firstOrNull()
      ?.dataSpec
      ?.uri
      ?.toString()

  /**
   * Cerca una corrispondenza fra un URI e i messaggi della catena delle cause.
   *
   * Serve per gli errori su file locale (`file://`), dove Media3 non espone alcun
   * `DataSpec` ma il messaggio contiene il percorso assoluto del file.
   */
  private fun matchByPath(messages: List<String>, uris: List<String>): String? {
    for (uri in uris) {
      val path = pathOf(uri)
      if (path.length < MIN_PATH_LENGTH) continue
      if (messages.any { it.contains(path) }) return uri
    }
    return null
  }

  /**
   * MIME type rifiutato dal renderer, se il messaggio lo dichiara.
   *
   * Messaggio osservato nella versione Media3 installata:
   * `Legacy decoding is disabled, can't handle application/x-subrip samples
   * (expected application/x-media3-cues).`
   */
  private fun rejectedSampleMimeType(messages: List<String>): String? {
    for (message in messages) {
      REJECTED_SAMPLE_MIME.find(message)?.let { return it.groupValues[1] }
      UNSUPPORTED_MIME_TYPE.find(message)?.let { return it.groupValues[1] }
    }
    return null
  }

  /** Confronto tra URI: query string e frammento non contano, lo schema no. */
  private fun sameUri(a: String, b: String): Boolean = normalizeUri(a) == normalizeUri(b)

  private fun normalizeUri(raw: String): String {
    val trimmed = raw.trim().substringBefore('#').substringBefore('?')
    val schemeEnd = trimmed.indexOf("://")
    return if (schemeEnd > 0) {
      trimmed.substring(0, schemeEnd).lowercase() + trimmed.substring(schemeEnd)
    } else {
      trimmed
    }
  }

  /** Percorso (o percorso + query) di un URI, usato per il confronto con i messaggi. */
  private fun pathOf(raw: String): String {
    val withoutScheme = raw.substringAfter("://", raw)
    return withoutScheme.substringBefore('#')
  }

  private val REJECTED_SAMPLE_MIME =
    Regex("can't handle (\\S+) samples", RegexOption.IGNORE_CASE)

  private val UNSUPPORTED_MIME_TYPE =
    Regex("unsupported mime type[:\\s]+(\\S+)", RegexOption.IGNORE_CASE)

  /** Soglia minima per considerare un percorso abbastanza specifico da essere confrontato. */
  private const val MIN_PATH_LENGTH = 8
}
