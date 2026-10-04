package com.example.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.media3.common.MimeTypes
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Auto frame rate matching and rate normalization utility.
 * Ported directly from NuvioTV FrameRateUtils.kt.
 */
object FrameRateUtils {

    private const val TAG = "FrameRateUtils"
    private const val SWITCH_TIMEOUT_MS = 4000L
    private const val REFRESH_MATCH_MIN_TOLERANCE_HZ = 0.08f
    private const val NTSC_FILM_FPS = 24000f / 1001f
    private const val CINEMA_24_FPS = 24f
    private const val MIN_VALID_VIDEO_FPS = 10f
    private const val MAX_VALID_VIDEO_FPS = 120f
    private val NEXTLIB_HTTP_SCHEMES = setOf("http", "https")
    private val LIVE_STREAM_EXTENSIONS = listOf(".mpd", ".ism/manifest")
    private const val MKV_EXTENSION = ".mkv"

    data class FrameRateDetection(
        val raw: Float,
        val snapped: Float,
        val videoWidth: Int? = null,
        val videoHeight: Int? = null
    )

    data class DisplayModeSwitchResult(
        val appliedMode: Display.Mode
    )

    private val frameRateCache = object : LinkedHashMap<String, FrameRateDetection>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FrameRateDetection>?): Boolean {
            return size > 64
        }
    }

    fun snapToStandardRate(formatFrameRate: Float): Float {
        if (formatFrameRate <= 0f) return formatFrameRate
        return when {
            formatFrameRate in 23.90f..23.988f -> NTSC_FILM_FPS
            formatFrameRate in 23.988f..24.1f -> CINEMA_24_FPS
            formatFrameRate in 24.9f..25.1f -> 25f
            formatFrameRate in 29.90f..29.985f -> 30000f / 1001f
            formatFrameRate in 29.985f..30.1f -> 30f
            formatFrameRate in 49.9f..50.1f -> 50f
            formatFrameRate in 59.9f..59.97f -> 60000f / 1001f
            formatFrameRate in 59.97f..60.1f -> 60f
            else -> formatFrameRate
        }
    }

    private fun snapProbeRateByFrameDuration(measuredFps: Float, averageFrameDurationUs: Float): Float {
        if (measuredFps in 23.5f..24.5f) {
            val frameUs23976 = 1_000_000f / NTSC_FILM_FPS
            val frameUs24 = 1_000_000f / CINEMA_24_FPS
            val diff23976 = abs(averageFrameDurationUs - frameUs23976)
            val diff24 = abs(averageFrameDurationUs - frameUs24)
            return if (diff23976 <= diff24) NTSC_FILM_FPS else CINEMA_24_FPS
        }
        return snapToStandardRate(measuredFps)
    }

    private fun isValidVideoFrameRate(fps: Float): Boolean {
        return fps in MIN_VALID_VIDEO_FPS..MAX_VALID_VIDEO_FPS
    }

    suspend fun probeVideoFrameRate(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        filename: String? = null
    ): FrameRateDetection? = withContext(Dispatchers.IO) {
        val cached = synchronized(frameRateCache) { frameRateCache[sourceUrl] }
        if (cached != null) return@withContext cached

        var result: FrameRateDetection? = null

        if (shouldUseNextLibProbe(sourceUrl, headers, mimeType, filename)) {
            result = probeWithNextLibMediaInfo(context, sourceUrl, headers)
        }

        if (result == null) {
            result = probeWithMediaExtractor(context, sourceUrl, headers)
        }

        if (result != null) {
            synchronized(frameRateCache) { frameRateCache[sourceUrl] = result }
        }
        result
    }

    private fun shouldUseNextLibProbe(
        sourceUrl: String,
        headers: Map<String, String>,
        mimeType: String? = null,
        filename: String? = null
    ): Boolean {
        if (sourceUrl.isBlank()) return false
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        if (LIVE_STREAM_EXTENSIONS.any { normalized.endsWith(it) }) return false
        if (headers.any { it.key.equals("Authorization", ignoreCase = true) }) return false
        if (mimeType?.contains("matroska", ignoreCase = true) == true || filename?.endsWith(MKV_EXTENSION, ignoreCase = true) == true) return true
        val scheme = Uri.parse(sourceUrl).scheme?.lowercase(Locale.ROOT)
        return scheme in NEXTLIB_HTTP_SCHEMES || scheme == "file" || scheme == "content"
    }

    private fun probeWithNextLibMediaInfo(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String>
    ): FrameRateDetection? {
        return try {
            val mediaInfo = MediaInfoBuilder().from(context, Uri.parse(sourceUrl)).build()
            val videoTrack = mediaInfo?.videoStream ?: return null
            val rawFps = videoTrack.frameRate.toFloat()
            if (rawFps <= 0f || !isValidVideoFrameRate(rawFps)) return null
            FrameRateDetection(
                raw = rawFps,
                snapped = snapToStandardRate(rawFps),
                videoWidth = videoTrack.frameWidth.takeIf { it > 0 },
                videoHeight = videoTrack.frameHeight.takeIf { it > 0 }
            )
        } catch (e: Exception) {
            Log.w(TAG, "NextLib probe failed: ${e.message}")
            null
        }
    }

    private fun probeWithMediaExtractor(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String>
    ): FrameRateDetection? {
        val extractor = MediaExtractor()
        return try {
            val uri = Uri.parse(sourceUrl)
            when (uri.scheme?.lowercase(Locale.ROOT)) {
                "http", "https" -> extractor.setDataSource(sourceUrl, headers)
                else -> extractor.setDataSource(context, uri, headers)
            }
            var videoTrackIndex = -1
            var videoFormat: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null) return null

            val width = if (videoFormat.containsKey(android.media.MediaFormat.KEY_WIDTH)) videoFormat.getInteger(android.media.MediaFormat.KEY_WIDTH) else null
            val height = if (videoFormat.containsKey(android.media.MediaFormat.KEY_HEIGHT)) videoFormat.getInteger(android.media.MediaFormat.KEY_HEIGHT) else null
            val declaredFps = if (videoFormat.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) videoFormat.getFloat(android.media.MediaFormat.KEY_FRAME_RATE) else null

            if (declaredFps != null && isValidVideoFrameRate(declaredFps)) {
                return FrameRateDetection(
                    raw = declaredFps,
                    snapped = snapToStandardRate(declaredFps),
                    videoWidth = width,
                    videoHeight = height
                )
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor probe failed: ${e.message}")
            null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
