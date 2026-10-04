package com.example.ui.screens.player

import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo

/**
 * Finds Dolby Vision hardware decoders that exist on the device but don't
 * advertise Profile 8 in their profileLevels array.
 * Ported directly from NuvioTV DolbyVisionCodecFallback.kt.
 */
@UnstableApi
object DolbyVisionCodecFallback {

    private const val TAG = "DvCodecFallback"

    fun findDvDecodersIgnoringProfile(): List<MediaCodecInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()

        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val results = mutableListOf<MediaCodecInfo>()

            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue

                val supportsDv = info.supportedTypes.any { type ->
                    type.equals(DV_MIME, ignoreCase = true)
                }
                if (!supportsDv) continue

                val caps = runCatching {
                    info.getCapabilitiesForType(DV_MIME)
                }.getOrNull()
                if (caps == null) {
                    Log.w(TAG, "Skipping ${info.name}: getCapabilitiesForType returned null")
                    continue
                }

                if (!probeCodecInstantiation(info.name)) {
                    Log.w(TAG, "Skipping ${info.name}: probe instantiation failed")
                    continue
                }

                val isHardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google.", ignoreCase = true)
                }

                val isSoftwareOnly = if (Build.VERSION.SDK_INT >= 29) {
                    info.isSoftwareOnly
                } else {
                    info.name.startsWith("OMX.google.", ignoreCase = true)
                }

                val isVendor = if (Build.VERSION.SDK_INT >= 29) {
                    info.isVendor
                } else {
                    !info.name.startsWith("OMX.google.", ignoreCase = true)
                }

                val media3Info = MediaCodecInfo.newInstance(
                    /* name= */ info.name,
                    /* mimeType= */ DV_MIME,
                    /* codecMimeType= */ DV_MIME,
                    /* capabilities= */ caps,
                    /* hardwareAccelerated= */ isHardwareAccelerated,
                    /* softwareOnly= */ isSoftwareOnly,
                    /* vendor= */ isVendor,
                    /* forceDisableAdaptive= */ false,
                    /* forceSecure= */ false
                )

                Log.i(TAG, "Found hidden DV decoder: ${info.name} (hw=$isHardwareAccelerated)")
                results.add(media3Info)
            }
            results
        }.getOrElse { e ->
            Log.e(TAG, "Error probing DV decoders", e)
            emptyList()
        }
    }

    private fun probeCodecInstantiation(componentName: String): Boolean {
        return runCatching {
            val codec = MediaCodec.createByCodecName(componentName)
            codec.release()
            true
        }.getOrElse { e ->
            Log.w(TAG, "Codec probe failed for $componentName: ${e.message}")
            false
        }
    }

    private const val DV_MIME = MimeTypes.VIDEO_DOLBY_VISION
}
