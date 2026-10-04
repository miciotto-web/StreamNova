package com.example.ui.screens.player

import android.content.Context
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.R

/**
 * Display resize mode utility functions for AspectRatioFrameLayout.
 * Ported directly from NuvioTV PlayerDisplayModeUtils.kt.
 */
internal object PlayerDisplayModeUtils {
    fun nextResizeMode(currentMode: Int): Int {
        return when (currentMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    /**
     * Tunneled video ignores view scale, so the surface size is the only aspect control.
     * Fill is opt-in for that session; every other playback path stays FIT so the aspect
     * scale modes keep a video-sized frame.
     */
    fun exoSurfaceResizeMode(tunnelingEnabled: Boolean, tunneledSurfaceFill: Boolean): Int {
        return if (tunnelingEnabled && tunneledSurfaceFill) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun resizeModeLabel(mode: Int, context: Context? = null): String {
        return if (context != null) {
            when (mode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> context.getString(R.string.player_resize_fit)
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> context.getString(R.string.player_resize_fill)
                AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> context.getString(R.string.player_resize_fixed_width)
                AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> context.getString(R.string.player_resize_fixed_height)
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> context.getString(R.string.player_resize_zoom)
                else -> context.getString(R.string.player_resize_fit)
            }
        } else {
            when (mode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Adatta allo schermo"
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Allunga (Stretch)"
                AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Larghezza fissa"
                AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "Altezza fissa"
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Ritaglia e riempi (Zoom)"
                else -> "Adatta allo schermo"
            }
        }
    }
}
