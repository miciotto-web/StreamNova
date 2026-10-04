package com.example.ui.screens.player

/**
 * First-frame watchdog codec recovery policy - DV mode downgrade, then VC-1 fail.
 * Ported directly from NuvioTV PlayerFirstFrameCodecRecoveryPolicy.kt.
 */
internal object PlayerFirstFrameCodecRecoveryPolicy {

    data class Input(
        val playWhenReady: Boolean,
        val isManualDv81Mode2Active: Boolean,
        val dv7Mode1AlreadyForced: Boolean,
        val currentVideoTrackIsLikelyVc1: Boolean,
    )

    sealed class RecoveryAction {
        data object None : RecoveryAction()
        data object RetryDv7Mode1 : RecoveryAction()
        data object FailVc1Unsupported : RecoveryAction()
    }

    fun evaluateAfterWatchdogTimeout(input: Input): RecoveryAction {
        if (!input.playWhenReady) return RecoveryAction.None

        if (input.isManualDv81Mode2Active && !input.dv7Mode1AlreadyForced) {
            return RecoveryAction.RetryDv7Mode1
        }
        if (input.currentVideoTrackIsLikelyVc1) {
            return RecoveryAction.FailVc1Unsupported
        }
        return RecoveryAction.None
    }
}
