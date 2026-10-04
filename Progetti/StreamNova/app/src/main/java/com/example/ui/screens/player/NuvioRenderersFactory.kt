package com.example.ui.screens.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Custom RenderersFactory wiring PlaybackSpeedAwareAudioSink, PlaybackSpeedAwareAudioRenderer,
 * FfmpegAudioRenderer, and NextLib DV7 to HEVC base layer mapping.
 * Ported directly from NuvioTV SubtitleOffsetRenderersFactory in PlayerRuntimeControllerInitialization.kt.
 */
@UnstableApi
class NuvioRenderersFactory(
    private val context: Context,
    private val bluetoothForcePcm: Boolean = false,
    private val playbackSpeedProvider: () -> Float = { 1.0f },
    private val initialForcePcm: Boolean = false,
    private val preferSoftwareAudioOnly: Boolean = false,
    private val onPlaybackSpeedAwareAudioSinkCreated: ((PlaybackSpeedAwareAudioSink) -> Unit)? = null,
    private val onFfmpegAudioRendererChanged: ((FfmpegAudioRenderer?) -> Unit)? = null
) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
        applyMapDv7ToHevcIfSupported(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val videoExtensionMode = when {
            !preferSoftwareAudioOnly -> extensionRendererMode
            extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER -> EXTENSION_RENDERER_MODE_ON
            else -> extensionRendererMode
        }
        super.buildVideoRenderers(
            context,
            videoExtensionMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val builder = if (bluetoothForcePcm) {
            DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioOutputRouteDetector.bluetoothPcmOnlyCapabilities())
        } else {
            DefaultAudioSink.Builder(context)
        }
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)

        val baseAudioSink = builder.build()
        val speedAwareSink = PlaybackSpeedAwareAudioSink(
            sink = baseAudioSink,
            initialForcePcm = initialForcePcm,
            forcePcmForBluetooth = bluetoothForcePcm
        )
        speedAwareSink.setInitialPlaybackSpeed(playbackSpeedProvider())
        onPlaybackSpeedAwareAudioSinkCreated?.invoke(speedAwareSink)
        return speedAwareSink
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val playbackAwareSink = audioSink as? PlaybackSpeedAwareAudioSink
        val startIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        if (playbackAwareSink != null && out.size > startIndex) {
            val mediaCodecAudioRendererIndex = (startIndex until out.size)
                .firstOrNull { index -> out[index] is MediaCodecAudioRenderer }
                ?: startIndex
            out[mediaCodecAudioRendererIndex] = PlaybackSpeedAwareAudioRenderer(
                rendererContext = context,
                codecAdapterFactory = getCodecAdapterFactory(),
                mediaCodecSelector = mediaCodecSelector,
                enableDecoderFallback = enableDecoderFallback,
                eventHandler = eventHandler,
                eventListener = eventListener,
                playbackSpeedAwareAudioSink = playbackAwareSink
            )
        }

        val ffmpegRenderers = out.filterIsInstance<FfmpegAudioRenderer>()
        onFfmpegAudioRendererChanged?.invoke(ffmpegRenderers.firstOrNull())
    }

    private fun DefaultRenderersFactory.applyMapDv7ToHevcIfSupported(enabled: Boolean): DefaultRenderersFactory {
        return runCatching {
            val method = javaClass.getMethod("setMapDV7ToHevc", Boolean::class.javaPrimitiveType)
            method.invoke(this, enabled)
            this
        }.getOrElse { this }
    }
}
