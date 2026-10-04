package com.example.ui.screens.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * Centralizes all Nuvio ExoPlayer performance enhancements behind a single toggle.
 * Ported directly from NuvioTV NuvioExoPlayerPerformanceHelper.kt.
 */
@androidx.media3.common.util.UnstableApi
object NuvioExoPlayerPerformanceHelper {

    /** Whether Nuvio performance enhancements are active. */
    @Volatile
    var enabled: Boolean = true
        set(value) {
            val supported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            val newValue = value && supported
            field = newValue
            applyEngineConfig(newValue)
        }

    val sharedConnectionPool: okhttp3.ConnectionPool = okhttp3.ConnectionPool(
        NUVIO_SHARED_POOL_MAX_IDLE,
        3,
        java.util.concurrent.TimeUnit.MINUTES
    )

    // Constants
    const val DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE = 64 * 1024        // 64 KB
    const val NATIVE_ARENA_CHUNK_SIZE = 64 * 1024
    const val NATIVE_ARENA_POOL_BYTES = 512 * NATIVE_ARENA_CHUNK_SIZE
    const val DEFAULT_NUVIO_TARGET_BUFFER_MB = 175
    const val DEFAULT_NUVIO_TARGET_BUFFER_BYTES = DEFAULT_NUVIO_TARGET_BUFFER_MB * 1024 * 1024
    const val DEFAULT_NUVIO_MIN_BUFFER_MS = 15_000
    const val DEFAULT_NUVIO_MAX_BUFFER_MS = 45_000
    const val DEFAULT_NUVIO_BACK_BUFFER_MS = 15_000
    const val DEFAULT_NUVIO_INITIAL_BITRATE_ESTIMATE = 50_000_000L     // 50 Mbps
    const val NUVIO_SHARED_POOL_MAX_IDLE = 32
    private const val BACK_BUFFER_TARGET_SHARE_NUM = 1L
    private const val BACK_BUFFER_TARGET_SHARE_DEN = 2L
    const val MIN_BUFFER_MB = 25

    // Customization Variables
    @Volatile
    var minBufferMs: Int = DEFAULT_NUVIO_MIN_BUFFER_MS

    @Volatile
    var maxBufferMs: Int = DEFAULT_NUVIO_MAX_BUFFER_MS

    @Volatile
    var bufferForPlaybackMs: Int = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS

    @Volatile
    var bufferForPlaybackAfterRebufferMs: Int = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS

    @Volatile
    var backBufferMs: Int = DEFAULT_NUVIO_BACK_BUFFER_MS

    @Volatile
    var targetBufferSizeMb: Int = DEFAULT_NUVIO_TARGET_BUFFER_MB

    @Volatile
    var enableHttp2: Boolean = true

    @Volatile
    var liveAllocator: DefaultAllocator? = null

    private const val SEEK_BACK_BUFFER_THRESHOLD_MS = 1_000L
    private const val SEEK_BACKWARD_TOLERANCE_MS = 500L

    init {
        val supported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        if (supported && enabled) {
            applyEngineConfig(true)
        }
    }

    private fun effectiveBackBufferMs(): Int {
        if (backBufferMs <= 0) return 0
        val ceiling = (minBufferMs.toLong() * BACK_BUFFER_TARGET_SHARE_NUM / BACK_BUFFER_TARGET_SHARE_DEN).toInt()
        return backBufferMs.coerceAtMost(ceiling)
    }

    /**
     * Builds a [DefaultLoadControl] tuned for Nuvio performance when enabled,
     * or a standard ExoPlayer [DefaultLoadControl] when disabled.
     */
    fun buildLoadControl(
        context: Context? = null,
        chunkOverheadMb: Int = 0,
        targetBufferSizeMbOverride: Int? = null,
        bufferForPlaybackMsOverride: Int? = null,
        backBufferMsOverride: Int? = null
    ): DefaultLoadControl {
        val effectiveTargetMb = targetBufferSizeMbOverride ?: targetBufferSizeMb
        val effectiveBufferForPlaybackMs = bufferForPlaybackMsOverride ?: bufferForPlaybackMs
        val effectiveBackMs = backBufferMsOverride ?: backBufferMs

        return if (enabled) {
            val effectiveTargetBufferMb = (effectiveTargetMb - chunkOverheadMb)
                .coerceAtLeast(MIN_BUFFER_MB)
            val targetBufferBytes = (effectiveTargetBufferMb.toLong() * 1024L * 1024L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            if (DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE != NATIVE_ARENA_CHUNK_SIZE) {
                android.util.Log.w(
                    "NuvioExoPerf",
                    "Allocator segment $DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE does not match the " +
                        "native arena chunk $NATIVE_ARENA_CHUNK_SIZE; native pooling is disabled"
                )
            }
            val allocator = DefaultAllocator(true, DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE, 64, enabled)
            liveAllocator = allocator

            val backBufferToUse = if (effectiveBackMs <= 0) 0 else {
                val ceiling = (minBufferMs.toLong() * BACK_BUFFER_TARGET_SHARE_NUM / BACK_BUFFER_TARGET_SHARE_DEN).toInt()
                effectiveBackMs.coerceAtMost(ceiling)
            }

            android.util.Log.i(
                "ExoPerformance",
                "buildLoadControl: targetBufferSizeMb=$effectiveTargetMb, chunkOverheadMb=$chunkOverheadMb, targetBytes=$targetBufferBytes, initialBufferMs=$effectiveBufferForPlaybackMs, backBufferMs=$backBufferToUse"
            )
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setTargetBufferBytes(targetBufferBytes)
                .setBufferDurationsMs(
                    minBufferMs,
                    maxBufferMs,
                    effectiveBufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs
                )
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(backBufferToUse, true)
                .build()
        } else {
            val targetBytes = (effectiveTargetMb.toLong() * 1024L * 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            DefaultLoadControl.Builder()
                .setTargetBufferBytes(targetBytes)
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    70_000,
                    effectiveBufferForPlaybackMs,
                    5_000
                )
                .setBackBuffer(effectiveBackMs.coerceAtLeast(0), true)
                .build()
        }
    }

    /**
     * Builds a [DefaultBandwidthMeter] with an aggressive initial estimate when
     * enabled, or the platform default when disabled.
     */
    fun buildBandwidthMeter(context: Context): DefaultBandwidthMeter {
        return if (enabled) {
            DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate(DEFAULT_NUVIO_INITIAL_BITRATE_ESTIMATE)
                .build()
        } else {
            DefaultBandwidthMeter.Builder(context).build()
        }
    }

    /**
     * Returns [ScrubbingModeParameters] that disable audio/metadata decoding and
     * boost codec operating rate for the fastest possible seek, or `null` when
     * performance mode is off.
     */
    fun buildScrubbingParams(): ScrubbingModeParameters? {
        if (!enabled) return null
        return ScrubbingModeParameters.Builder()
            .setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA))
            .setShouldIncreaseCodecOperatingRate(true)
            .setAllowSkippingMediaCodecFlush(true)
            .setShouldEnableDynamicScheduling(true)
            .build()
    }

    /**
     * Returns `true` when the seek target [positionMs] falls within the player's
     * already-buffered window.
     */
    fun isSeekInBuffer(player: ExoPlayer, positionMs: Long): Boolean {
        if (!enabled) return false
        val bufferedPos = player.bufferedPosition
        val currentPos = player.currentPosition
        val backBufferStart = (currentPos - SEEK_BACK_BUFFER_THRESHOLD_MS - SEEK_BACKWARD_TOLERANCE_MS)
            .coerceAtLeast(0L)
        return positionMs in backBufferStart..bufferedPos
    }

    fun shouldSuppressBufferingUi(
        suppressBufferingUiForSeek: Boolean,
        seekBufferingUiDeferred: Boolean,
        isBuffering: Boolean
    ): Boolean {
        if (!enabled) return false
        return (suppressBufferingUiForSeek && isBuffering) ||
            (seekBufferingUiDeferred && isBuffering)
    }

    fun applyNetworkOptimizations(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder {
        val withPool = builder.connectionPool(sharedConnectionPool)
        return if (enableHttp2) {
            withPool.protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        } else {
            withPool.protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        }
    }

    fun shouldBypassForNonPcmFormat(): Boolean = enabled
    fun shouldLogMemoryFootprint(): Boolean = enabled
    fun shouldGuardTrackRebuild(): Boolean = enabled

    private fun applyEngineConfig(performanceModeEnabled: Boolean) {
        if (performanceModeEnabled) {
            NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode())
        } else {
            NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
        }
    }
}
