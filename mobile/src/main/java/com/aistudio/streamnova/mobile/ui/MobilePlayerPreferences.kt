package com.aistudio.streamnova.mobile.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferenze del player Mobile non coperte da `SettingsRepository`/`AppSettingsRepository`.
 *
 * Sono opzioni introdotte dalla Fase 8.4 e specifiche del player touch (gesture,
 * schermo acceso in pausa, buffering extra). Restano nel modulo `mobile/` per non
 * toccare il core condiviso con la TV: la TV non le legge e non cambia comportamento.
 *
 * La lettura avviene in modo sincrono da SharedPreferences (file piccolo, accesso
 * già in memoria dopo il primo caricamento); la scrittura usa `apply()`.
 */
object MobilePlayerPreferences {

  private const val FILE_NAME = "streamnova_mobile_player"
  private const val KEY_GESTURES_ENABLED = "player_gestures_enabled"
  private const val KEY_KEEP_SCREEN_ON_IN_PAUSE = "player_keep_screen_on_in_pause"
  private const val KEY_EXTRA_BUFFERING = "player_extra_buffering"

  @Volatile
  private var preferences: SharedPreferences? = null

  /** Chiamata da `MobileApplication.onCreate` prima di comporre qualsiasi schermata. */
  fun init(context: Context) {
    if (preferences != null) return
    synchronized(this) {
      if (preferences == null) {
        preferences = context.applicationContext
          .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
      }
    }
  }

  fun gesturesEnabled(): Boolean = readBoolean(KEY_GESTURES_ENABLED, default = true)

  fun setGesturesEnabled(enabled: Boolean) = writeBoolean(KEY_GESTURES_ENABLED, enabled)

  fun keepScreenOnInPause(): Boolean =
    readBoolean(KEY_KEEP_SCREEN_ON_IN_PAUSE, default = false)

  fun setKeepScreenOnInPause(enabled: Boolean) =
    writeBoolean(KEY_KEEP_SCREEN_ON_IN_PAUSE, enabled)

  fun extraBufferingEnabled(): Boolean = readBoolean(KEY_EXTRA_BUFFERING, default = false)

  fun setExtraBufferingEnabled(enabled: Boolean) =
    writeBoolean(KEY_EXTRA_BUFFERING, enabled)

  private fun readBoolean(key: String, default: Boolean): Boolean =
    preferences?.getBoolean(key, default) ?: default

  private fun writeBoolean(key: String, value: Boolean) {
    preferences?.edit()?.putBoolean(key, value)?.apply()
  }
}
