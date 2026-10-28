package com.example.ui.screens.player

import androidx.media3.exoplayer.DefaultLoadControl
import com.example.data.prefs.PlaybackSettings

/**
 * Costruisce il [DefaultLoadControl] del player a partire dalle impostazioni di riproduzione.
 *
 * Estratta dal composable del player (stesso criterio di
 * [resolvePlaybackMediaSourceFactory] e di `resolveVideoFocusTarget`) perché il cablaggio
 * delle impostazioni sul LoadControl è verificabile in test JVM: `initialBuffer` ("Buffer
 * iniziale": Avvio Rapido 1,5 s / Standard 2,5 s / Stabile 3,5 s) era l'unica impostazione
 * della sezione RIPRODUZIONE senza alcun effetto sul player, perché il valore restava su
 * DataStore e il LoadControl usava sempre la costante hard-coded di 2,5 s.
 *
 * @param playbackSettings impostazioni correnti del player.
 */
internal fun buildPlayerLoadControl(playbackSettings: PlaybackSettings): DefaultLoadControl =
  NuvioExoPlayerPerformanceHelper.buildLoadControl(
    targetBufferSizeMbOverride = playbackSettings.targetBuffer.sizeMb,
    backBufferMsOverride = playbackSettings.backBuffer.durationMs,
    bufferForPlaybackMsOverride = playbackSettings.initialBuffer.durationMs
  )
