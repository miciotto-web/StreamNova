package com.example.ui.screens.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

data class ParsedMediaTracks(
    val audioTracks: List<TrackInfo>,
    val subtitleTracks: List<TrackInfo>,
    val selectedAudioIndex: Int,
    val selectedSubtitleIndex: Int,
    val videoFormat: Format?,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val videoBitrate: Int?,
    val videoCodec: String?,
    val detectedFrameRate: Float,
    val detectedFrameRateRaw: Float
)

/**
 * Parses and extracts audio, subtitle, and video metadata from ExoPlayer Tracks.
 * Ported directly from NuvioTV PlayerRuntimeControllerTracks.kt.
 */
@UnstableApi
object PlayerRuntimeControllerTracks {

    fun parseTracks(
        tracks: Tracks,
        context: Context? = null
    ): ParsedMediaTracks {
        val audioTracks = mutableListOf<TrackInfo>()
        val subtitleTracks = mutableListOf<TrackInfo>()
        var selectedAudioIndex = -1
        var selectedSubtitleIndex = -1

        var selectedVideoFormat: Format? = null
        var detectedFrameRateRaw = 0f
        var detectedFrameRate = 0f

        tracks.groups.forEach { trackGroup ->
            when (trackGroup.type) {
                C.TRACK_TYPE_VIDEO -> {
                    for (i in 0 until trackGroup.length) {
                        if (trackGroup.isTrackSelected(i)) {
                            val format = trackGroup.getTrackFormat(i)
                            val currentSelected = selectedVideoFormat
                            if (currentSelected == null || format.width > currentSelected.width ||
                                (format.width == currentSelected.width && format.bitrate > currentSelected.bitrate)
                            ) {
                                selectedVideoFormat = format
                            }
                        }
                    }

                    selectedVideoFormat?.let { format ->
                        if (format.frameRate > 0f) {
                            detectedFrameRateRaw = format.frameRate
                            detectedFrameRate = FrameRateUtils.snapToStandardRate(format.frameRate)
                        }
                    }
                }

                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until trackGroup.length) {
                        val format = trackGroup.getTrackFormat(i)
                        val isSelected = trackGroup.isTrackSelected(i)
                        if (isSelected) selectedAudioIndex = audioTracks.size

                        val codecName = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
                        val channelLayout = CustomDefaultTrackNameProvider.getChannelLayoutName(format.channelCount)
                        val langDisplay = format.language?.takeIf { it != "und" }
                        val baseName = format.label ?: langDisplay ?: "Traccia Audio ${audioTracks.size + 1}"
                        val suffix = listOfNotNull(codecName, channelLayout).joinToString(" ")
                        val displayName = if (suffix.isNotEmpty()) "$baseName ($suffix)" else baseName

                        audioTracks.add(
                            TrackInfo(
                                index = audioTracks.size,
                                name = displayName,
                                language = format.language,
                                trackId = format.id,
                                codec = codecName,
                                channelCount = format.channelCount.takeIf { it > 0 },
                                isSelected = isSelected,
                                sampleRate = format.sampleRate.takeIf { it > 0 }
                            )
                        )
                    }
                }

                C.TRACK_TYPE_TEXT -> {
                    for (i in 0 until trackGroup.length) {
                        val format = trackGroup.getTrackFormat(i)
                        val isSelected = trackGroup.isTrackSelected(i)
                        if (isSelected) selectedSubtitleIndex = subtitleTracks.size

                        val hasForcedFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                        val trackTexts = listOfNotNull(format.label, format.language, format.id)
                        val nameHintForced = trackTexts.any { it.contains("forced", ignoreCase = true) || it.contains("forzat", ignoreCase = true) }

                        val langDisplay = format.language?.takeIf { it != "und" }
                        val baseName = format.label ?: langDisplay ?: "Sottotitolo ${subtitleTracks.size + 1}"
                        val isForced = hasForcedFlag || nameHintForced
                        val forcedSuffix = if (isForced && !baseName.contains("forzat", ignoreCase = true) && !baseName.contains("forced", ignoreCase = true)) " (Forzato)" else ""

                        subtitleTracks.add(
                            TrackInfo(
                                index = subtitleTracks.size,
                                name = "$baseName$forcedSuffix",
                                language = format.language,
                                trackId = format.id,
                                isForced = isForced,
                                isSelected = isSelected
                            )
                        )
                    }
                }
            }
        }

        val videoWidth = selectedVideoFormat?.width?.takeIf { it > 0 }
        val videoHeight = selectedVideoFormat?.height?.takeIf { it > 0 }
        val videoBitrate = selectedVideoFormat?.bitrate?.takeIf { it > 0 }
        val videoCodec = selectedVideoFormat?.let {
            CustomDefaultTrackNameProvider.formatNameFromMime(it.sampleMimeType)
                ?: CustomDefaultTrackNameProvider.formatNameFromMime(it.codecs)
        }

        return ParsedMediaTracks(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            selectedAudioIndex = selectedAudioIndex,
            selectedSubtitleIndex = selectedSubtitleIndex,
            videoFormat = selectedVideoFormat,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            videoBitrate = videoBitrate,
            videoCodec = videoCodec,
            detectedFrameRate = detectedFrameRate,
            detectedFrameRateRaw = detectedFrameRateRaw
        )
    }
}
