package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.MediaLoadData
import java.net.SocketTimeoutException

/**
 * Custom LoadErrorHandlingPolicy handling HLS/DASH media segment 404 track exclusion
 * and retry backoff.
 * Ported directly from NuvioTV PlayerMediaSourceFactory.kt.
 *
 * Aggiunta: il caricamento di una traccia sottotitoli esterna (sidecar OpenSubtitles/Stremio)
 * non deve MAI rendere fatale la riproduzione: se fallisce, la traccia viene scartata
 * (`C.TIME_UNSET` → Media3 la chiude come end-of-stream / la esclude) e il video prosegue
 * senza di essa. La gestione delle tracce video/audio resta invariata.
 */
class PlayerLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(6) {

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        if (isSubtitleLoad(loadErrorInfo.mediaLoadData)) {
            if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)) {
                return LoadErrorHandlingPolicy.FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                    DefaultLoadErrorHandlingPolicy.DEFAULT_TRACK_EXCLUSION_MS
                )
            }
        }
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
        // Traccia sottotitoli esterna: nessun retry, così la sola traccia viene scartata
        // mentre la riproduzione video continua senza quella traccia.
        if (isSubtitleLoad(loadErrorInfo.mediaLoadData)) {
            return C.TIME_UNSET
        }
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

    companion object {
        /**
         * `true` se il caricamento riguarda una traccia sottotitoli/testo.
         *
         * Copre sia le tracce TEXT dichiarate (HLS/DASH) sia i sidecar esterni, che Media3
         * segnala con `trackType == TRACK_TYPE_UNKNOWN` e con il MIME type del sottotitolo
         * nel `trackFormat` (es. `application/x-subrip`).
         */
        fun isSubtitleLoad(trackType: Int, sampleMimeType: String?): Boolean =
            trackType == C.TRACK_TYPE_TEXT || PlayerCodecErrorClassifier.isSubtitleMimeType(sampleMimeType)

        /** Overload su [MediaLoadData] usato dagli override della policy. */
        fun isSubtitleLoad(mediaLoadData: MediaLoadData): Boolean =
            isSubtitleLoad(mediaLoadData.trackType, mediaLoadData.trackFormat?.sampleMimeType)
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
