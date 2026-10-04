package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.SocketTimeoutException

/**
 * Custom LoadErrorHandlingPolicy handling HLS/DASH media segment 404 track exclusion
 * and retry backoff.
 * Ported directly from NuvioTV PlayerMediaSourceFactory.kt.
 */
class PlayerLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(6) {

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val responseCode = loadErrorInfo.exception
            .findCause<HttpDataSource.InvalidResponseCodeException>()
            ?.responseCode
        if (
            shouldPreferAlternativeHlsTrack(
                responseCode = responseCode,
                dataType = loadErrorInfo.mediaLoadData.dataType,
                alternativeTrackAvailable = fallbackOptions.isFallbackAvailable(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK
                )
            )
        ) {
            // A media-segment 404 belongs to the selected rendition. Exclude that
            // rendition first so HLS can continue with another compatible track.
            return LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                DefaultLoadErrorHandlingPolicy.DEFAULT_TRACK_EXCLUSION_MS
            )
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val httpException =
            loadErrorInfo.exception.findCause<HttpDataSource.InvalidResponseCodeException>()
        if (httpException != null) {
            val code = httpException.responseCode
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 410) {
                return C.TIME_UNSET
            }
        }
        val timeout = loadErrorInfo.exception.findCause<SocketTimeoutException>() != null
        return if (timeout) {
            when (loadErrorInfo.errorCount) {
                1 -> 750L
                2 -> 1500L
                else -> 3000L
            }
        } else super.getRetryDelayMsFor(loadErrorInfo)
    }

    private fun shouldPreferAlternativeHlsTrack(
        responseCode: Int?,
        dataType: Int,
        alternativeTrackAvailable: Boolean
    ): Boolean =
        responseCode == 404 &&
            dataType == C.DATA_TYPE_MEDIA &&
            alternativeTrackAvailable

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}
