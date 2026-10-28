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
    fun isRetryablePlaybackError(error: PlaybackException): Boolean =
        PlayerErrorRecoveryPolicy.isRetryablePlaybackError(error)

    /**
     * Audio-track failures that the safe-audio / pcm fallback ladder can recover from.
     */
    fun isAudioTrackFailure(errorCode: Int, combinedMessage: String = ""): Boolean =
        PlayerErrorRecoveryPolicy.isAudioTrackFailure(errorCode, combinedMessage)

    fun isDolbyVisionDecoderFailure(error: PlaybackException): Boolean =
        PlayerErrorRecoveryPolicy.isDolbyVisionDecoderFailure(error)

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
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return context?.getString(R.string.player_http_timeout)
                ?: "Stream connection timed out. Check the source and try again."
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        ) {
            return context?.getString(R.string.player_invalid_stream)
                ?: "The provider returned an invalid or unsupported stream."
        }
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
