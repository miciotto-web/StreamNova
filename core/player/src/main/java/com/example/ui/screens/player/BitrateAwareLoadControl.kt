package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * DefaultLoadControl with a byte target tied to the device memory budget, and a back
 * buffer / budget that can be adjusted at runtime once we know whether the stream is
 * really DV7.
 *
 * Ported directly from NuvioTV BitrateAwareLoadControl.kt.
 */
@UnstableApi
class BitrateAwareLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    prioritizeTimeOverSizeThresholds: Boolean,
    backBufferDurationMs: Int,
    retainBackBufferFromKeyframe: Boolean,
    /** Memory ceiling in bytes. */
    private val budgetBytes: Long,
    allocator: DefaultAllocator = DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE, 64)
) : DefaultLoadControl(
    allocator,
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    /* targetBufferBytes= */ C.LENGTH_UNSET,
    prioritizeTimeOverSizeThresholds,
    backBufferDurationMs,
    retainBackBufferFromKeyframe
) {

    // Effective back buffer (μs) when >= 0, else the constructed value.
    @Volatile
    private var backBufferOverrideUs: Long = -1L

    /** Set the back buffer at runtime; negative restores the constructed value. */
    fun setBackBufferDurationOverrideMs(ms: Int) {
        backBufferOverrideUs = if (ms < 0) -1L else ms.toLong() * 1000L
    }

    // Effective byte budget when >= 0, else the constructed budget.
    @Volatile
    private var budgetBytesOverride: Long = -1L

    /** Set the byte budget at runtime; negative restores the constructed budget. */
    fun setBudgetBytesOverride(bytes: Long) {
        budgetBytesOverride = if (bytes < 0L) -1L else bytes
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long {
        val override = backBufferOverrideUs
        return if (override >= 0L) override else super.getBackBufferDurationUs(playerId)
    }

    override fun calculateTargetBufferBytes(
        trackSelectionArray: Array<out ExoTrackSelection?>
    ): Int {
        val effectiveBudget = if (budgetBytesOverride >= 0L) budgetBytesOverride else budgetBytes
        return effectiveBudget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
