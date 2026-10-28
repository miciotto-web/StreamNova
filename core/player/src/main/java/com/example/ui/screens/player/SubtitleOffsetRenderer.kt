package com.example.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream

/**
 * Delegating [Renderer] for text/subtitles that applies a dynamic time offset (delay) in real time.
 *
 * Offset conventions:
 * - Positive offset (+ms): delays subtitles so they appear later (renders at playback position - offset).
 * - Negative offset (-ms): advances subtitles so they appear earlier (renders at playback position - offset).
 *
 * When the offset changes dynamically during playback, [resetPosition] is called on the delegate
 * renderer with the new adjusted position to immediately flush and reload cues from that timestamp,
 * without restarting or reloading the video or audio streams.
 */
@UnstableApi
class SubtitleOffsetRenderer(
    private val delegate: Renderer,
    private val offsetMsProvider: () -> Long
) : Renderer by delegate {

    private var lastAppliedOffsetUs: Long = 0L

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val offsetUs = offsetMsProvider() * 1000L
        val adjustedPositionUs = (positionUs - offsetUs).coerceAtLeast(0L)
        if (offsetUs != lastAppliedOffsetUs) {
            try {
                delegate.resetPosition(adjustedPositionUs)
            } catch (_: Exception) {
                // Ignore if delegate cannot reset position at this exact instant
            }
            lastAppliedOffsetUs = offsetUs
        }
        delegate.render(adjustedPositionUs, elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long) {
        val offsetUs = offsetMsProvider() * 1000L
        lastAppliedOffsetUs = offsetUs
        val adjustedPositionUs = (positionUs - offsetUs).coerceAtLeast(0L)
        delegate.resetPosition(adjustedPositionUs)
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long {
        val offsetUs = offsetMsProvider() * 1000L
        val adjustedPositionUs = (positionUs - offsetUs).coerceAtLeast(0L)
        return delegate.getDurationToProgressUs(adjustedPositionUs, elapsedRealtimeUs)
    }

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        val subOffsetUs = offsetMsProvider() * 1000L
        lastAppliedOffsetUs = subOffsetUs
        val adjPositionUs = (positionUs - subOffsetUs).coerceAtLeast(0L)
        val adjStartPositionUs = (startPositionUs - subOffsetUs).coerceAtLeast(0L)
        delegate.enable(
            configuration,
            formats,
            stream,
            adjPositionUs,
            joining,
            mayRenderStartOfStream,
            adjStartPositionUs,
            offsetUs,
            mediaPeriodId
        )
    }
}
