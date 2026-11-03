package com.aistudio.streamnova.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Unico punto che scrive `Activity.requestedOrientation` per l'app Mobile.
 *
 * Tiene lo stato della destinazione player (visibile / in riproduzione) così la
 * policy può essere rivalutata ai cambi di configurazione e di multi-window
 * senza due fonti di verità: `MainActivity` e `MobilePlayerScreen` passano
 * entrambe da qui.
 *
 * Regola anti-loop: il valore viene scritto solo se diverso da quello già
 * richiesto. La rivalutazione è idempotente e sicura anche durante
 * `onConfigurationChanged` (il manifest gestisce `orientation|screenSize|...`
 * senza ricreare l'Activity).
 */
class MobileOrientationController(private val activity: Activity) {

  private var playerScreenVisible = false
  private var playbackActive = false
  private var trailerVisible = false

  /** Policy base: usata all'avvio e per le schermate non-player. */
  fun applyBasePolicy() = apply()

  /**
   * Aggiorna lo stato del player. Chiamato all'ingresso, a ogni cambio di
   * [isPlaybackActive] (loading/errore/picker → riproduzione) e alla chiusura.
   */
  fun setPlayerScreen(visible: Boolean, isPlaybackActive: Boolean) {
    playerScreenVisible = visible
    playbackActive = visible && isPlaybackActive
    apply()
  }

  /** Uscita dal player: la policy del device riprende immediatamente. */
  fun clearPlayerScreen() = setPlayerScreen(visible = false, isPlaybackActive = false)

  /**
   * Apertura/chiusura del trailer nella scheda Dettaglio: su telefono abilita il
   * landscape finché il trailer è aperto, alla chiusura riapplica la policy base
   * (portrait). Non modifica lo stato del player principale.
   */
  fun setTrailerVisible(visible: Boolean) {
    trailerVisible = visible
    apply()
  }

  fun clearTrailer() = setTrailerVisible(visible = false)

  /** Rotazione fisica, ridimensionamento, fold/unfold o cambio display. */
  fun onConfigurationChanged() = apply()

  /** Ingresso/uscita da split-screen o freeform. */
  fun onMultiWindowModeChanged() = apply()

  private fun apply() {
    // Durante la distruzione (es. recreate per cambio lingua) non ha senso
    // richiedere un nuovo orientamento: l'istanza sta per sparire.
    if (activity.isFinishing || activity.isDestroyed) return
    val formFactor = activity.mobileFormFactor()
    val target = if (trailerVisible && !playerScreenVisible) {
      mobileTrailerOrientationTarget(
        formFactor = formFactor,
        isMultiWindow = activity.isInMultiWindowMode
      )
    } else {
      mobileOrientationTarget(
        formFactor = formFactor,
        isPlayerScreen = playerScreenVisible,
        playbackActive = playbackActive,
        isMultiWindow = activity.isInMultiWindowMode
      )
    }
    val requested = target.toRequestedOrientation()
    if (activity.requestedOrientation != requested) {
      activity.requestedOrientation = requested
    }
  }
}

/**
 * Form factor del device misurato sul display fisico, con il fallback
 * documentato a `Configuration.smallestScreenWidthDp` quando la misura non è
 * disponibile. Non usa mai la larghezza della finestra corrente.
 */
internal fun Context.mobileFormFactor(): MobileFormFactor =
  resolveMobileFormFactor(
    measuredDisplayWidthDp = displaySmallestWidthDp(),
    configurationSmallestScreenWidthDp = resources.configuration.smallestScreenWidthDp
  )

/**
 * Lato corto del display in dp: `WindowManager.maximumWindowMetrics` su API 30+,
 * `Display.getRealMetrics` sulle versioni precedenti (minSdk 24).
 */
private fun Context.displaySmallestWidthDp(): Int? {
  val windowManager = getSystemService(WindowManager::class.java) ?: return null
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    val bounds = windowManager.maximumWindowMetrics.bounds
    return smallestDisplayWidthDp(
      widthPx = bounds.width(),
      heightPx = bounds.height(),
      densityDpi = resources.displayMetrics.densityDpi
    )
  }
  @Suppress("DEPRECATION")
  val displayMetrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
  return smallestDisplayWidthDp(
    widthPx = displayMetrics.widthPixels,
    heightPx = displayMetrics.heightPixels,
    densityDpi = displayMetrics.densityDpi
  )
}

private fun MobileOrientationTarget.toRequestedOrientation(): Int = when (this) {
  MobileOrientationTarget.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  MobileOrientationTarget.SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  MobileOrientationTarget.UNSPECIFIED -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
