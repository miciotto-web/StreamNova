package com.example.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Methods for changing audio and subtitle track selections on ExoPlayer.
 * Ported directly from NuvioTV PlayerRuntimeControllerTrackSelection.kt.
 */
@UnstableApi
object PlayerRuntimeControllerTrackSelection {

    private const val TAG = "PlayerTrackSelection"

    fun selectAudioTrack(player: ExoPlayer, trackIndex: Int): Boolean {
        val tracks = player.currentTracks
        var currentAudioIndex = 0

        tracks.groups.forEach { trackGroup ->
            if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until trackGroup.length) {
                    if (currentAudioIndex == trackIndex) {
                        val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, i)
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .build()
                        Log.i(TAG, "Audio track selected: index=$trackIndex")
                        return true
                    }
                    currentAudioIndex++
                }
            }
        }
        return false
    }

    fun selectSubtitleTrack(player: ExoPlayer, trackIndex: Int): Boolean {
        val tracks = player.currentTracks
        var currentSubIndex = 0

        tracks.groups.forEach { trackGroup ->
            if (trackGroup.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until trackGroup.length) {
                    if (currentSubIndex == trackIndex) {
                        val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, i)
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                        Log.i(TAG, "Subtitle track selected: index=$trackIndex")
                        return true
                    }
                    currentSubIndex++
                }
            }
        }
        return false
    }

    fun disableSubtitles(player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        Log.i(TAG, "Subtitles disabled")
    }
}
