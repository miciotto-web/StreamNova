package com.example.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo

/**
 * Logging diagnostico temporaneo per il problema "4K: audio + schermo nero".
 *
 * NON modifica nessun comportamento del player: qui dentro c'e' solo `Log.*`.
 * Scopo: distinguere le categorie A..I del bug (DV Profile 7/8, HEVC Main10,
 * capabilities, maxVideoSize, decoder assente, traccia non selezionata,
 * decoder non inizializzato, altro HDR/codec) leggendo il FORMAT REALE
 * della sorgente 4K.
 *
 * Tag unico: [TAG].
 */
@UnstableApi
object StreamNova4KDiag {

    const val TAG = "StreamNova4KDiag"

    fun line(message: String) {
        Log.i(TAG, message)
    }

    fun logError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    // ---------------------------------------------------------------------
    // 1) FORMAT della traccia video
    // ---------------------------------------------------------------------

    fun formatDetails(format: Format?): String {
        if (format == null) return "format=null"
        return try {
            buildString {
                append("id=").append(format.id ?: "-")
                append(" label=").append(format.label ?: "-")
                append(" lang=").append(format.language ?: "-")
                append(" mime=").append(format.sampleMimeType ?: "-")
                append(" container=").append(format.containerMimeType ?: "-")
                append(" codecs=").append(format.codecs ?: "-")
                append(" size=").append(format.width).append('x').append(format.height)
                append(" decoded=").append(format.decodedWidth).append('x').append(format.decodedHeight)
                append(" bitrate=").append(format.bitrate)
                append(" avgBitrate=").append(format.averageBitrate)
                append(" peakBitrate=").append(format.peakBitrate)
                append(" fps=").append(format.frameRate)
                append(" par=").append(format.pixelWidthHeightRatio)
                append(" rotation=").append(format.rotationDegrees)
                append(" | COLOR ").append(colorDetails(format.colorInfo))
                append(" | HDR=").append(hdrLabel(format))
                append(" | DV=").append(dolbyVisionLabel(format))
                append(" | HEVC=").append(hevcProfileLabel(format))
                append(" | drm=").append(if (format.drmInitData != null) "presente(cryptoType=${format.cryptoType})" else "nessuno")
                append(" | metadata=").append(format.metadata?.toString() ?: "-")
            }
        } catch (t: Throwable) {
            "formatDetails error: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun colorDetails(colorInfo: ColorInfo?): String {
        if (colorInfo == null) return "colorInfo=null"
        return try {
            "colorSpace=${colorSpaceName(colorInfo.colorSpace)}(${colorInfo.colorSpace})" +
                " colorRange=${colorRangeName(colorInfo.colorRange)}(${colorInfo.colorRange})" +
                " colorTransfer=${colorTransferName(colorInfo.colorTransfer)}(${colorInfo.colorTransfer})" +
                " lumaBitdepth=${colorInfo.lumaBitdepth} chromaBitdepth=${colorInfo.chromaBitdepth}" +
                " bitdepthValid=${colorInfo.isBitdepthValid}" +
                " hdrStaticInfo=${colorInfo.hdrStaticInfo?.size ?: 0}B" +
                " hdr10Plus=non-esposto-dal-ColorInfo-del-fork"
        } catch (t: Throwable) {
            "colorInfo error: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun colorSpaceName(value: Int): String = when (value) {
        C.COLOR_SPACE_BT601 -> "BT601"
        C.COLOR_SPACE_BT709 -> "BT709"
        C.COLOR_SPACE_BT2020 -> "BT2020"
        else -> "sconosciuto"
    }

    private fun colorRangeName(value: Int): String = when (value) {
        C.COLOR_RANGE_LIMITED -> "LIMITED"
        C.COLOR_RANGE_FULL -> "FULL"
        else -> "sconosciuto"
    }

    private fun colorTransferName(value: Int): String = when (value) {
        C.COLOR_TRANSFER_LINEAR -> "LINEAR"
        C.COLOR_TRANSFER_SDR -> "SDR"
        C.COLOR_TRANSFER_SRGB -> "SRGB"
        C.COLOR_TRANSFER_GAMMA_2_2 -> "GAMMA_2_2"
        C.COLOR_TRANSFER_ST2084 -> "ST2084(PQ)"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "sconosciuto"
    }

    /** Classifica HDR/SDR senza assumere nulla: legge solo colorTransfer/colorSpace/mime. */
    fun hdrLabel(format: Format): String {
        val colorInfo = format.colorInfo
        val codecs = format.codecs.orEmpty()
        val isDv = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
            codecs.startsWith("dv", ignoreCase = true)
        val transfer = colorInfo?.colorTransfer
        val space = colorInfo?.colorSpace
        return when {
            isDv -> "DolbyVision(mime/codecs) -> vedi DV="
            colorInfo == null -> "nessun colorInfo (SDR presunto)"
            transfer == C.COLOR_TRANSFER_ST2084 -> "HDR10/HDR10+ (PQ ST2084)"
            transfer == C.COLOR_TRANSFER_HLG -> "HLG"
            space == C.COLOR_SPACE_BT2020 -> "BT.2020 senza transfer HDR dichiarato"
            else -> "SDR/non-HDR dichiarato"
        }
    }

    /** DV profile/level derivati dalla stringa codecs (es. "dvhe.08.06", "dvh1.07.06"). */
    fun dolbyVisionLabel(format: Format): String {
        val codecs = format.codecs.orEmpty()
        val mime = format.sampleMimeType
        val present = mime == MimeTypes.VIDEO_DOLBY_VISION ||
            codecs.startsWith("dv", ignoreCase = true)
        if (!present) return "no"
        val match = Regex("""(dv[a-z0-9]+)\.(\d{2})(?:\.(\d{2}))?""").find(codecs)
        val profile = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
        val level = match?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }
        val profileName = when (profile) {
            "05" -> "Profile 5 (single-layer IPT-PQ)"
            "07" -> "Profile 7 (AVC base + enhancement / 3D)"
            "08" -> "Profile 8 (single-layer backward-compatible)"
            "09" -> "Profile 9"
            "10" -> "Profile 10"
            null -> "profilo non derivabile da codecs"
            else -> "Profile $profile (non mappato)"
        }
        return "SI mime=$mime codecs=$codecs profile=$profile level=$level -> $profileName"
    }

    /** Profilo HEVC ricavato dalla stringa codecs ("hvc1.2.4.L120.90"). */
    fun hevcProfileLabel(format: Format): String {
        val codecs = format.codecs.orEmpty()
        if (!codecs.startsWith("hvc1", ignoreCase = true) && !codecs.startsWith("hev1", ignoreCase = true)) {
            return "-"
        }
        val parts = codecs.split('.')
        val profileIdc = parts.getOrNull(1) ?: "?"
        val levelPart = parts.getOrNull(2) ?: "?"
        val profileName = when (profileIdc) {
            "1" -> "Main"
            "2" -> "Main10"
            "3" -> "Main Still Picture"
            "4" -> "Format Range Extensions"
            else -> "profileIdc=$profileIdc"
        }
        return "SI codecs=$codecs profileIdc=$profileIdc($profileName) level=$levelPart"
    }

    // ---------------------------------------------------------------------
    // 2) Track selection: RendererCapabilities / formatSupport
    // ---------------------------------------------------------------------

    fun supportLabel(support: Int): String {
        val formatSupport = RendererCapabilities.getFormatSupport(support)
        val formatName = when (formatSupport) {
            C.FORMAT_HANDLED -> "FORMAT_HANDLED"
            C.FORMAT_EXCEEDS_CAPABILITIES -> "FORMAT_EXCEEDS_CAPABILITIES"
            C.FORMAT_UNSUPPORTED_DRM -> "FORMAT_UNSUPPORTED_DRM"
            C.FORMAT_UNSUPPORTED_SUBTYPE -> "FORMAT_UNSUPPORTED_SUBTYPE"
            C.FORMAT_UNSUPPORTED_TYPE -> "FORMAT_UNSUPPORTED_TYPE"
            else -> "FORMAT_SCONOSCIUTO($formatSupport)"
        }
        val adaptive = when (RendererCapabilities.getAdaptiveSupport(support)) {
            RendererCapabilities.ADAPTIVE_SEAMLESS -> "SEAMLESS"
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS -> "NOT_SEAMLESS"
            RendererCapabilities.ADAPTIVE_NOT_SUPPORTED -> "NOT_SUPPORTED"
            else -> "?"
        }
        val tunneling = if (RendererCapabilities.getTunnelingSupport(support) == RendererCapabilities.TUNNELING_SUPPORTED) {
            "SUPPORTED"
        } else {
            "NOT_SUPPORTED"
        }
        val hw = if (RendererCapabilities.getHardwareAccelerationSupport(support) == RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED) {
            "HW"
        } else {
            "SW/NO"
        }
        val decoder = when (RendererCapabilities.getDecoderSupport(support)) {
            RendererCapabilities.DECODER_SUPPORT_PRIMARY -> "PRIMARY"
            RendererCapabilities.DECODER_SUPPORT_FALLBACK -> "FALLBACK"
            RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE -> "FALLBACK_MIMETYPE"
            else -> "?"
        }
        return "support=$formatName adaptive=$adaptive tunneling=$tunneling hw=$hw decoder=$decoder"
    }

    /** True se la traccia e' astrattamente selezionabile (serve exceedRendererCapabilities per gli EXCEEDS). */
    fun isTrackSelectable(support: Int): Boolean = when (RendererCapabilities.getFormatSupport(support)) {
        C.FORMAT_HANDLED -> true
        C.FORMAT_EXCEEDS_CAPABILITIES -> true
        else -> false
    }

    /** Ogni traccia video candidata con il proprio risultato RendererCapabilities. */
    fun logVideoCandidates(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<out Array<out IntArray>>
    ) {
        try {
            for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_VIDEO) continue
                val rendererName = mappedTrackInfo.getRendererName(rendererIndex)
                val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                for (groupIndex in 0 until trackGroups.length) {
                    val group = trackGroups[groupIndex]
                    for (trackIndex in 0 until group.length) {
                        val format = group.getFormat(trackIndex)
                        val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                        line(
                            "CANDIDATE renderer[$rendererIndex:$rendererName] group[$groupIndex] track[$trackIndex] " +
                                "raw=$support ${supportLabel(support)} " +
                                "selezionabile=${isTrackSelectable(support)} | ${formatDetails(format)}"
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            logError("logVideoCandidates failed", t)
        }
    }

    /** Esito della selezione: quali Definition sono state prodotte (e quali tracce contengono). */
    fun logSelection(
        mappedTrackInfo: MappedTrackInfo,
        definitions: Array<ExoTrackSelection.Definition?>
    ) {
        try {
            var videoSelected = false
            for (rendererIndex in definitions.indices) {
                val definition = definitions[rendererIndex] ?: continue
                val rendererName = mappedTrackInfo.getRendererName(rendererIndex)
                val rendererType = mappedTrackInfo.getRendererType(rendererIndex)
                if (rendererType == C.TRACK_TYPE_VIDEO) videoSelected = true
                val details = definition.tracks.joinToString(" + ") { trackIndex ->
                    formatDetails(definition.group.getFormat(trackIndex))
                }
                line(
                    "SELECTED renderer[$rendererIndex:$rendererName] type=$rendererType " +
                        "groupLen=${definition.group.length} tracks=${definition.tracks.toList()} | $details"
                )
            }
            if (!videoSelected) {
                line("SELECTED NESSUNA traccia VIDEO selezionata (renderer video senza Definition)")
            }
        } catch (t: Throwable) {
            logError("logSelection failed", t)
        }
    }

    // ---------------------------------------------------------------------
    // 3) Track selector parameters
    // ---------------------------------------------------------------------

    fun paramsLabel(params: DefaultTrackSelector.Parameters): String = try {
        buildString {
            append("maxVideoSize=").append(params.maxVideoWidth).append('x').append(params.maxVideoHeight)
            append(" minVideoSize=").append(params.minVideoWidth).append('x').append(params.minVideoHeight)
            append(" maxVideoBitrate=").append(params.maxVideoBitrate)
            append(" maxVideoFrameRate=").append(params.maxVideoFrameRate)
            append(" viewport=").append(params.viewportWidth).append('x').append(params.viewportHeight)
            append(" exceedRendererCapabilitiesIfNecessary=").append(params.exceedRendererCapabilitiesIfNecessary)
            append(" exceedVideoConstraintsIfNecessary=").append(params.exceedVideoConstraintsIfNecessary)
            append(" forceHighestSupportedBitrate=").append(params.forceHighestSupportedBitrate)
            append(" forceLowestBitrate=").append(params.forceLowestBitrate)
            append(" tunnelingEnabled=").append(params.tunnelingEnabled)
            append(" disabledTrackTypes=").append(params.disabledTrackTypes)
            append(" preferredVideoMimeTypes=").append(params.preferredVideoMimeTypes)
            append(" selectionOverrides=").append(params.overrides.size)
        }
    } catch (t: Throwable) {
        "paramsLabel error: ${t.javaClass.simpleName}: ${t.message}"
    }

    fun logParams(where: String, params: DefaultTrackSelector.Parameters) {
        line("PARAMS[$where] ${paramsLabel(params)}")
    }

    // ---------------------------------------------------------------------
    // 4) Video renderer / decoder
    // ---------------------------------------------------------------------

    fun logDecoderAvailability(format: Format?) {
        if (format == null || format.sampleMimeType.isNullOrBlank()) {
            line("DECODER nessun formato video (format=null): nessuna query decoder")
            return
        }
        val mime = format.sampleMimeType ?: return
        val decoderInfos = try {
            MediaCodecUtil.getDecoderInfos(mime, false, false)
        } catch (t: Throwable) {
            logError("DECODER query fallita per mime=$mime", t)
            return
        }
        line("DECODER disponibili per mime=$mime -> ${decoderInfos.size}")
        if (decoderInfos.isEmpty()) {
            line("DECODER NESSUN decoder per mime=$mime (categoria F)")
        }
        for (info in decoderInfos) {
            val formatSupported = try {
                info.isFormatSupported(format)
            } catch (t: Throwable) {
                null
            }
            line(
                "DECODER info: name=${info.name} codecMimeType=${info.codecMimeType} " +
                    "hw=${info.hardwareAccelerated} sw=${info.softwareOnly} vendor=${info.vendor} " +
                    "adaptive=${info.adaptive} tunneling=${info.tunneling} secure=${info.secure} " +
                    "isFormatSupported=$formatSupported | $info"
            )
        }
        if (decoderInfos.isEmpty() && mime == MimeTypes.VIDEO_DOLBY_VISION) {
            val hevcDecoders = try {
                MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
            } catch (t: Throwable) {
                emptyList()
            }
            line(
                "DECODER riferimento HEVC/H.265 (SOLO informativo, nessuna modifica): " +
                    "${hevcDecoders.size} -> ${hevcDecoders.joinToString { it.name }}"
            )
        }
    }

    // ---------------------------------------------------------------------
    // 5) Stato del player a STATE_READY
    // ---------------------------------------------------------------------

    fun logPlayerState(
        videoFormat: Format?,
        audioFormat: Format?,
        tracks: Tracks,
        params: DefaultTrackSelector.Parameters?
    ) {
        line("STATE_READY videoFormat=${formatDetails(videoFormat)}")
        line("STATE_READY audioFormat=${formatDetails(audioFormat)}")
        params?.let { line("STATE_READY params ${paramsLabel(it)}") }
        try {
            var anyVideoGroup = false
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_VIDEO) continue
                anyVideoGroup = true
                for (trackIndex in 0 until group.length) {
                    val support = group.getTrackSupport(trackIndex)
                    line(
                        "STATE_READY videoTrack[$trackIndex] groupLen=${group.length} " +
                            "selected=${group.isTrackSelected(trackIndex)} " +
                            "supported=${group.isTrackSupported(trackIndex)} " +
                            "raw=$support ${supportLabel(support)} | ${formatDetails(group.getTrackFormat(trackIndex))}"
                    )
                }
            }
            if (!anyVideoGroup) {
                line("STATE_READY NESSUN gruppo tracce video presente nei Tracks")
            }
        } catch (t: Throwable) {
            logError("logPlayerState tracks failed", t)
        }
        logDecoderAvailability(videoFormat)
    }

    // ---------------------------------------------------------------------
    // Errori decoder / playback (solo log)
    // ---------------------------------------------------------------------

    fun logPlaybackError(error: PlaybackException) {
        val exo = error as? ExoPlaybackException
        val causeChain = generateSequence<Throwable>(error) { it.cause }
            .take(8)
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message ?: "-"}" }
        logError(
            "PLAYER ERROR class=${error.javaClass.name} errorCode=${error.errorCode}(${error.errorCodeName}) " +
                "exoType=${exo?.type} rendererName=${exo?.rendererName} rendererIndex=${exo?.rendererIndex} " +
                "rendererFormat=${formatDetails(exo?.rendererFormat)} " +
                "rendererFormatSupport=${exo?.rendererFormatSupport?.let { supportLabel(it) } ?: "-"} " +
                "causeChain=[$causeChain]",
            error
        )
    }

    /**
     * AnalyticsListener di pura osservazione: nome decoder video, formato in ingresso
     * al decoder, rilascio, errore codec, primo frame renderizzato, cambio parametri
     * di selezione. Nessun intervento sul comportamento.
     */
    fun createAnalyticsListener(): AnalyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            line("DECODER VIDEO inizializzato: name=$decoderName initDurationMs=$initializationDurationMs")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            val reuse = decoderReuseEvaluation?.let {
                "decoderName=${it.decoderName} result=${reuseResultLabel(it.result)} discardReasons=${it.discardReasons}"
            } ?: "null"
            line("DECODER VIDEO inputFormatChanged: ${formatDetails(format)} | reuse=$reuse")
        }

        override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            line("DECODER VIDEO rilasciato: name=$decoderName")
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, exception: Exception) {
            logError(
                "DECODER VIDEO codecError: ${exception.javaClass.name}: ${exception.message}",
                exception
            )
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, exception: Exception) {
            logError(
                "DECODER AUDIO codecError: ${exception.javaClass.name}: ${exception.message}",
                exception
            )
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            line(
                "RENDERED first frame: renderTimeMs=$renderTimeMs " +
                    "output=${output.javaClass.simpleName} posMs=${eventTime.currentPlaybackPositionMs}"
            )
        }

        override fun onTrackSelectionParametersChanged(
            eventTime: AnalyticsListener.EventTime,
            trackSelectionParameters: TrackSelectionParameters
        ) {
            val params = trackSelectionParameters as? DefaultTrackSelector.Parameters
            if (params != null) {
                line("PARAMS[analytics] ${paramsLabel(params)}")
            } else {
                line("PARAMS[analytics] non-DefaultTrackSelector.Parameters: $trackSelectionParameters")
            }
        }
    }

    private fun reuseResultLabel(result: Int): String = when (result) {
        DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION -> "YES_WITHOUT_RECONFIGURATION"
        DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION -> "YES_WITH_RECONFIGURATION"
        DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH -> "YES_WITH_FLUSH"
        DecoderReuseEvaluation.REUSE_RESULT_NO -> "NO"
        else -> "UNKNOWN($result)"
    }
}
