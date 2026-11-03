package com.aistudio.streamnova.mobile.ui

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.aistudio.streamnova.mobile.R

/**
 * Messaggio utente per un errore di riproduzione Media3.
 *
 * La UI mostra sempre un testo localizzato e comprensibile; il dettaglio tecnico
 * (`errorCodeName` e messaggio Media3) resta su logcat e, solo con
 * l'overlay debug attivo, sotto il messaggio principale.
 */
object MobilePlaybackErrorPolicy {

  /** Messaggio per l'errore completo; legge anche lo stato HTTP dalla catena di cause. */
  fun messageResId(error: PlaybackException): Int =
    messageResId(error.errorCode, error.httpResponseCode())

  /** Mapping puro su codice errore Media3 e stato HTTP (se presente). */
  fun messageResId(errorCode: Int, httpStatusCode: Int? = null): Int = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT -> R.string.player_playback_error_connection

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (httpStatusCode) {
      401, 403, 404, 410 -> R.string.player_playback_error_unavailable
      408, 425, 429, 500, 502, 503, 504 -> R.string.player_playback_error_connection
      else -> R.string.player_playback_error_generic
    }

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> R.string.player_playback_error_unavailable

    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> R.string.player_playback_error_format

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> R.string.player_playback_error_decoder

    else -> R.string.player_playback_error_generic
  }

  private fun PlaybackException.httpResponseCode(): Int? {
    var cause: Throwable? = this
    while (cause != null) {
      if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
      cause = cause.cause
    }
    return null
  }
}
