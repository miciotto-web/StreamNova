package com.example.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
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

    /**
     * Soglia minima di buffer prima di autorizzare un AUMENTO di qualità (isteresi di
     * `AdaptiveTrackSelection`): se il formato ideale ha bitrate più alto di quello in
     * riproduzione e il buffer è più corto di questa soglia, il cambio viene rinviato.
     *
     * Il port di NuvioTV usava 2.000 ms; con una soglia così bassa il selettore può
     * risalire di rendition subito dopo ogni svuotamento del buffer, quindi su CDN lenti
     * la selezione oscilla (up → svuotamento → down → up). Ogni cambio di rendition su
     * questi flussi HLS è NON adattivo (`adaptive=NOT_SUPPORTED` nei log di diagnostica),
     * quindi riconfigura il decoder video: nei log del Fire TV del 24-25/10/2026 una
     * riproduzione normale ha prodotto 7-8 cambi di formato al minuto, con un rilascio e
     * una reinizializzazione completa del decoder in uno dei casi (~1,7 s di fermo).
     *
     * 10.000 ms è il valore stock di Media3: la qualità può risalire solo quando il buffer
     * è di nuovo sano, il che spegne l'oscillazione senza toccare la qualità massima
     * raggiungibile (la selezione iniziale resta guidata dalla stima di banda).
     */
    private const val QUALITY_INCREASE_MIN_BUFFER_MS = 10_000

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
        val adaptiveTrackSelectionFactory = adaptiveTrackSelectionFactory()

        return object : DefaultTrackSelector(context, adaptiveTrackSelectionFactory) {
            override fun selectAllTracks(
                mappedTrackInfo: MappedTrackInfo,
                rendererFormatSupports: Array<out Array<out IntArray>>,
                rendererMixedMimeTypeAdaptationSupports: IntArray,
                params: Parameters
            ): Array<ExoTrackSelection.Definition?> {
                // --- DIAGNOSTICA 4K (solo log, nessun cambio di comportamento) ---
                StreamNova4KDiag.logParams("selectAllTracks", params)
                StreamNova4KDiag.logVideoCandidates(mappedTrackInfo, rendererFormatSupports)

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

                if (forceVc1VideoSelection) {
                    StreamNova4KDiag.logParams("selectAllTracks/override-VC1", selectionParams)
                }

                val definitions = super.selectAllTracks(
                    mappedTrackInfo,
                    rendererFormatSupports,
                    rendererMixedMimeTypeAdaptationSupports,
                    selectionParams
                )
                // --- DIAGNOSTICA 4K (solo log): esito della selezione ---
                StreamNova4KDiag.logSelection(mappedTrackInfo, definitions)
                return definitions
            }
        }.apply {
            val builder = buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                .setExceedRendererCapabilitiesIfNecessary(true)

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
            // --- DIAGNOSTICA 4K (solo log): parametri effettivi dopo la configurazione iniziale ---
            StreamNova4KDiag.logParams("NuvioTrackSelector.create", parameters)
        }
    }

    /**
     * Factory della selezione video adattiva usata dal player (e dai test di regressione
     * sull'oscillazione delle rendition).
     *
     * Il parametro [qualityIncreaseMinBufferMs] è l'isteresi di aumento qualità: il default
     * è [QUALITY_INCREASE_MIN_BUFFER_MS], il valore di produzione. Il parametro è esposto
     * perché i test possano ricostruire il comportamento precedente (2.000 ms) e dimostrare
     * che era proprio quella soglia a lasciare il selettore libero di risalire di rendition.
     */
    internal fun adaptiveTrackSelectionFactory(
        qualityIncreaseMinBufferMs: Int = QUALITY_INCREASE_MIN_BUFFER_MS
    ): AdaptiveTrackSelection.Factory = AdaptiveTrackSelection.Factory(
        qualityIncreaseMinBufferMs,
        AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION
    )

    /** Valore di produzione della soglia di aumento qualità (usato dai test). */
    internal val qualityIncreaseMinBufferMs: Int get() = QUALITY_INCREASE_MIN_BUFFER_MS
}
