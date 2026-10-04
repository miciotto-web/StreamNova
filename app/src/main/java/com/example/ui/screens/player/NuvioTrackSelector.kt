package com.example.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo

/**
 * Creates and configures DefaultTrackSelector with Nuvio's track capabilities upgrades
 * for HLS and VC-1.
 * Ported directly from NuvioTV PlayerRuntimeControllerInitialization.kt.
 */
@UnstableApi
object NuvioTrackSelector {

    private const val TAG = "NuvioTrackSelector"
    private const val ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS = 2_000

    fun create(
        context: Context,
        streamMimeTypeProvider: () -> String? = { null },
        streamNameProvider: () -> String? = { null },
        streamUrlProvider: () -> String? = { null },
        tunnelingEnabled: Boolean = false,
        safeAudioMode: Boolean = false,
        preferredAudioLanguages: List<String> = emptyList(),
        subtitlesEnabled: Boolean = false,
        forcedSubtitlesEnabled: Boolean = true
    ): DefaultTrackSelector {
        val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory(
            ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS,
            AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
            AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
            AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION
        )

        return object : DefaultTrackSelector(context, adaptiveTrackSelectionFactory) {
            override fun selectAllTracks(
                mappedTrackInfo: MappedTrackInfo,
                rendererFormatSupports: Array<out Array<out IntArray>>,
                rendererMixedMimeTypeAdaptationSupports: IntArray,
                params: Parameters
            ): Array<ExoTrackSelection.Definition?> {
                val streamMime = streamMimeTypeProvider()
                val isHls = streamMime != null && (
                    streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                    streamMime.lowercase().contains("mpegurl") ||
                    streamMime.lowercase().contains("m3u8")
                )

                if (isHls) {
                    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                        if (mappedTrackInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
                            val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                            for (groupIndex in 0 until trackGroups.length) {
                                val group = trackGroups[groupIndex]
                                for (trackIndex in 0 until group.length) {
                                    val format = group.getFormat(trackIndex)
                                    val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                                    val formatSupport = RendererCapabilities.getFormatSupport(support)
                                    if (formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES) {
                                        val mime = format.sampleMimeType
                                        val isAvcOrHevc = mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265
                                        val isAtMost1080p = format.width <= 1920 && format.height <= 1080
                                        val codecs = format.codecs?.lowercase() ?: ""
                                        val is10Bit = codecs.contains("main10") || codecs.contains("hevc.2") || codecs.contains("hev2")
                                        val isHdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084
                                        val isStandard8Bit = !is10Bit && !isHdr

                                        if (isAvcOrHevc && isAtMost1080p && isStandard8Bit) {
                                            Log.i(TAG, "Upgraded track support to FORMAT_HANDLED for id=${format.id}")
                                            rendererFormatSupports[rendererIndex][groupIndex][trackIndex] =
                                                RendererCapabilities.create(
                                                    C.FORMAT_HANDLED,
                                                    RendererCapabilities.ADAPTIVE_SEAMLESS,
                                                    RendererCapabilities.getTunnelingSupport(support),
                                                    RendererCapabilities.getHardwareAccelerationSupport(support),
                                                    RendererCapabilities.getDecoderSupport(support)
                                                )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                var forceVc1VideoSelection = false
                for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                    if (mappedTrackInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
                        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                        for (groupIndex in 0 until trackGroups.length) {
                            val group = trackGroups[groupIndex]
                            for (trackIndex in 0 until group.length) {
                                val format = group.getFormat(trackIndex)
                                val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                                val formatSupport = RendererCapabilities.getFormatSupport(support)
                                if (Vc1VideoFormatHeuristics.isLikelyVc1(format.sampleMimeType, format.codecs, format.label) &&
                                    formatSupport != C.FORMAT_HANDLED &&
                                    formatSupport != C.FORMAT_UNSUPPORTED_DRM
                                ) {
                                    forceVc1VideoSelection = true
                                    Log.i(TAG, "Upgraded VC-1 track support to FORMAT_HANDLED: id=${format.id}")
                                    rendererFormatSupports[rendererIndex][groupIndex][trackIndex] =
                                        RendererCapabilities.create(
                                            C.FORMAT_HANDLED,
                                            RendererCapabilities.ADAPTIVE_SEAMLESS,
                                            RendererCapabilities.getTunnelingSupport(support),
                                            RendererCapabilities.getHardwareAccelerationSupport(support),
                                            RendererCapabilities.getDecoderSupport(support)
                                        )
                                }
                            }
                        }
                    }
                }

                val selectionParams = if (forceVc1VideoSelection) {
                    params.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                        .setExceedVideoConstraintsIfNecessary(true)
                        .setExceedRendererCapabilitiesIfNecessary(true)
                        .setTunnelingEnabled(false)
                        .build()
                } else {
                    params
                }

                return super.selectAllTracks(
                    mappedTrackInfo,
                    rendererFormatSupports,
                    rendererMixedMimeTypeAdaptationSupports,
                    selectionParams
                )
            }
        }.apply {
            val builder = buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)

            if (tunnelingEnabled && !safeAudioMode) {
                builder.setTunnelingEnabled(true)
            } else if (safeAudioMode) {
                builder.setTunnelingEnabled(false)
                    .setConstrainAudioChannelCountToDeviceCapabilities(true)
            }

            if (Vc1VideoFormatHeuristics.isLikelyVc1Stream(streamNameProvider(), streamUrlProvider())) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setForceHighestSupportedBitrate(true)
            }

            if (preferredAudioLanguages.isNotEmpty()) {
                builder.setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray())
            }

            if (forcedSubtitlesEnabled) {
                builder.setPreferredTextLanguage("it")
                    .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
                    .setIgnoredTextSelectionFlags(0)
                    .setSelectUndeterminedTextLanguage(false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            } else {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            }

            setParameters(builder)
        }
    }
}
