package com.example.ui.screens.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * Decides what to do with a Dolby Vision stream at the codec selector.
 * Ported directly from NuvioTV DolbyVisionBaseLayerPolicy.kt.
 */
object DolbyVisionBaseLayerPolicy {

    enum class Decision {
        NATIVE_DV7,
        CONVERT_TO_DV81,
        STRIP_TO_HDR10,
        STRIP_BEST_EFFORT,
        STRIP_AND_TONEMAP
    }

    data class Result(
        val decision: Decision,
        val hdrCapsKnown: Boolean,
        val displayDv: Boolean,
        val displayHdr10: Boolean,
        val displayHdr10Plus: Boolean,
        val displayHlg: Boolean,
        val codecSupportsDvheDtb: Boolean,
        val codecSupportsDvheStn: Boolean,
        val codecSupportsDvheSt: Boolean,
        val isAmazonFireTv: Boolean,
        val isSamsung: Boolean,
        val isXiaomi: Boolean,
        val bridgeReady: Boolean,
        val apiLevel: Int
    ) {
        val divertsFromNativeDv7: Boolean
            get() = decision != Decision.NATIVE_DV7

        val mapToHevc: Boolean
            get() = when (decision) {
                Decision.STRIP_TO_HDR10,
                Decision.STRIP_BEST_EFFORT,
                Decision.STRIP_AND_TONEMAP -> true
                else -> false
            }
    }

    fun resolveFromCapabilities(
        hdrCapsKnown: Boolean,
        displayDv: Boolean,
        displayHdr10: Boolean,
        displayHdr10Plus: Boolean,
        displayHlg: Boolean,
        codecSupportsDvheDtb: Boolean,
        codecSupportsDvheStn: Boolean,
        codecSupportsDvheSt: Boolean,
        isAmazonFireTv: Boolean,
        isSamsung: Boolean,
        isXiaomi: Boolean,
        bridgeReady: Boolean,
        apiLevel: Int
    ): Result {
        val displayHdr10Family = displayHdr10 || displayHdr10Plus

        val decision = when {
            !hdrCapsKnown -> Decision.STRIP_BEST_EFFORT

            displayDv && codecSupportsDvheDtb -> Decision.NATIVE_DV7

            displayDv && bridgeReady && codecSupportsDvheSt ->
                Decision.CONVERT_TO_DV81

            displayDv && isXiaomi && bridgeReady -> Decision.CONVERT_TO_DV81

            displayDv -> Decision.NATIVE_DV7

            displayHdr10Family && bridgeReady && codecSupportsDvheSt && (isSamsung || isAmazonFireTv) ->
                Decision.CONVERT_TO_DV81

            displayHdr10Family && isXiaomi && bridgeReady -> Decision.CONVERT_TO_DV81

            displayHdr10Family -> Decision.STRIP_TO_HDR10

            else -> Decision.STRIP_AND_TONEMAP
        }

        return Result(
            decision = decision,
            hdrCapsKnown = hdrCapsKnown,
            displayDv = displayDv,
            displayHdr10 = displayHdr10,
            displayHdr10Plus = displayHdr10Plus,
            displayHlg = displayHlg,
            codecSupportsDvheDtb = codecSupportsDvheDtb,
            codecSupportsDvheStn = codecSupportsDvheStn,
            codecSupportsDvheSt = codecSupportsDvheSt,
            isAmazonFireTv = isAmazonFireTv,
            isSamsung = isSamsung,
            isXiaomi = isXiaomi,
            bridgeReady = bridgeReady,
            apiLevel = apiLevel
        )
    }

    fun resolve(context: Context, bridgeReady: Boolean): Result {
        val apiLevel = Build.VERSION.SDK_INT
        val manufacturer = Build.MANUFACTURER
        val isAmazonFireTv = manufacturer.equals("Amazon", ignoreCase = true)
        val isSamsung = manufacturer.equals("Samsung", ignoreCase = true)
        val isXiaomi = manufacturer.equals("Xiaomi", ignoreCase = true)

        if (apiLevel < Build.VERSION_CODES.N) {
            return resolveFromCapabilities(
                hdrCapsKnown = false,
                displayDv = false,
                displayHdr10 = false,
                displayHdr10Plus = false,
                displayHlg = false,
                codecSupportsDvheDtb = false,
                codecSupportsDvheStn = false,
                codecSupportsDvheSt = false,
                isAmazonFireTv = isAmazonFireTv,
                isSamsung = isSamsung,
                isXiaomi = isXiaomi,
                bridgeReady = bridgeReady,
                apiLevel = apiLevel
            )
        }

        @Suppress("DEPRECATION")
        val hdrTypes: IntArray? = runCatching {
            val dm = context.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.hdrCapabilities?.supportedHdrTypes
        }.getOrNull()

        val hdrCapsKnown = hdrTypes != null
        val displayDv = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
        val displayHdr10 = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
        val displayHdr10Plus =
            hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) == true
        val displayHlg = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HLG) == true

        val decoderProfiles = queryDvDecoderProfileSupport()

        return resolveFromCapabilities(
            hdrCapsKnown = hdrCapsKnown,
            displayDv = displayDv,
            displayHdr10 = displayHdr10,
            displayHdr10Plus = displayHdr10Plus,
            displayHlg = displayHlg,
            codecSupportsDvheDtb = decoderProfiles.dvheDtb,
            codecSupportsDvheStn = decoderProfiles.dvheStn,
            codecSupportsDvheSt = decoderProfiles.dvheSt,
            isAmazonFireTv = isAmazonFireTv,
            isSamsung = isSamsung,
            isXiaomi = isXiaomi,
            bridgeReady = bridgeReady,
            apiLevel = apiLevel
        )
    }

    private data class DvDecoderProfileSupport(
        val dvheDtb: Boolean,   // P7
        val dvheStn: Boolean,   // P5
        val dvheSt: Boolean     // P8
    )

    private fun queryDvDecoderProfileSupport(): DvDecoderProfileSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return DvDecoderProfileSupport(false, false, false)
        }
        return runCatching {
            var dvheDtb = false
            var dvheStn = false
            var dvheSt = false
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                val supportsDvMime = info.supportedTypes.any { type ->
                    type.equals(DOLBY_VISION_MIME, ignoreCase = true)
                }
                if (!supportsDvMime) continue
                val caps: MediaCodecInfo.CodecCapabilities = runCatching {
                    info.getCapabilitiesForType(DOLBY_VISION_MIME)
                }.getOrNull() ?: continue
                val profileLevels = caps.profileLevels ?: continue
                for (profileLevel in profileLevels) {
                    when (profileLevel.profile) {
                        DvheDtbProfile -> dvheDtb = true
                        DvheStnProfile -> dvheStn = true
                        DvheStProfile -> dvheSt = true
                    }
                }
            }
            DvDecoderProfileSupport(dvheDtb, dvheStn, dvheSt)
        }.getOrDefault(DvDecoderProfileSupport(false, false, false))
    }

    private const val DOLBY_VISION_MIME = "video/dolby-vision"
    private const val DvheDtbProfile = CodecProfileLevel.DolbyVisionProfileDvheDtb
    private const val DvheStnProfile = CodecProfileLevel.DolbyVisionProfileDvheStn
    private const val DvheStProfile = CodecProfileLevel.DolbyVisionProfileDvheSt
}
