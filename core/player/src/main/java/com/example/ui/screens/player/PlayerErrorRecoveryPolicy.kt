package com.example.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/**
 * Classificazione pura degli errori di riproduzione, condivisa tra TV e mobile.
 *
 * Non contiene testi utente: i messaggi localizzati restano nel layer UI della
 * singola piattaforma, che può delegare qui la sola decisione tecnica.
 */
@UnstableApi
object PlayerErrorRecoveryPolicy {

  /** True se l'errore è transitorio e vale la pena ritentare la stessa sorgente. */
  fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
      PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
      PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
      PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> true

      PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
        val httpCause = error.findCause<HttpDataSource.InvalidResponseCodeException>()
        if (httpCause != null) {
          val code = httpCause.responseCode
          !(code == 400 || code == 401 || code == 403 || code == 404 || code == 410)
        } else {
          true
        }
      }

      PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
      PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
      PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
      PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> true

      PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
      PlaybackException.ERROR_CODE_DECODING_FAILED,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

      PlaybackException.ERROR_CODE_UNSPECIFIED -> {
        val cause = error.cause
        cause is IllegalStateException || cause is NullPointerException
      }

      else -> false
    }
  }

  /** Errori audio recuperabili dalla scala di fallback (safe-audio / PCM). */
  fun isAudioTrackFailure(errorCode: Int, combinedMessage: String = ""): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true) ||
      combinedMessage.contains("audiotrack write failed", ignoreCase = true)
  }

  /** Riconosce un fallimento del decoder Dolby Vision dai codici e dal messaggio. */
  fun isDolbyVisionDecoderFailure(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
      error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    ) {
      return false
    }
    val message = buildString {
      append(error.message.orEmpty())
      append(" ")
      append(error.cause?.message.orEmpty())
    }.lowercase()
    return message.contains("dolby") ||
      message.contains("dvhe") ||
      message.contains("dvh1") ||
      message.contains("video/dolby-vision")
  }

  private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
      if (current is T) return current
      current = current.cause
    }
    return null
  }
}
