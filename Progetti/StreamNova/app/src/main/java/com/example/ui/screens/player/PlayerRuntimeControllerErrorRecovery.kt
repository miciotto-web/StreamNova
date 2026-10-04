package com.example.ui.screens.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.example.R

/**
 * Diagnostics and recovery classification for playback exceptions.
 * Ported directly from NuvioTV PlayerRuntimeControllerErrorRecovery.kt.
 */
@UnstableApi
object PlayerRuntimeControllerErrorRecovery {

    const val MAX_STARTUP_AUTO_RETRIES = 2
    const val RETRY_DELAY_MS = 1_500L
    const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L

    /**
     * Determines whether the given [PlaybackException] is transient and worth retrying.
     */
    fun isRetryablePlaybackError(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            // Source / IO errors
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

            // Decoder errors (often transient after pause/resume on some hardware)
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

    /**
     * Audio-track failures that the safe-audio / pcm fallback ladder can recover from.
     */
    fun isAudioTrackFailure(errorCode: Int, combinedMessage: String = ""): Boolean {
        if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
        if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
        return combinedMessage.contains("audiotrack init failed", ignoreCase = true) ||
            combinedMessage.contains("audiotrack write failed", ignoreCase = true)
    }

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

    fun httpStatusExplanation(code: Int, context: Context? = null): String {
        return if (context != null) {
            when (code) {
                401, 410 -> context.getString(R.string.player_http_expired)
                404 -> context.getString(R.string.player_http_not_found)
                429 -> context.getString(R.string.player_http_rate_limit)
                in 500..599 -> context.getString(R.string.player_http_server_unavailable, code)
                in 400..499 -> context.getString(R.string.player_http_access_blocked, code)
                else -> ""
            }
        } else {
            when (code) {
                401, 410 -> "Stream scaduto (401/410)"
                404 -> "Stream non trovato / rimosso (404)"
                429 -> "Troppe richieste (Rate limit 429)"
                in 500..599 -> "Server stream non disponibile ($code)"
                in 400..499 -> "Accesso allo stream bloccato ($code)"
                else -> ""
            }
        }
    }

    fun toDisplayMessage(error: PlaybackException, context: Context? = null): String {
        val responseException = error.findCause<HttpDataSource.InvalidResponseCodeException>()
        if (responseException != null) {
            val code = responseException.responseCode
            val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
            val providerHint = httpStatusExplanation(code, context)
            return buildString {
                append("HTTP $code")
                statusText?.let { append(" $it") }
                append(" [${error.errorCodeName}]")
                if (providerHint.isNotBlank()) append(" - $providerHint")
            }
        }
        return error.message ?: error.errorCodeName
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
