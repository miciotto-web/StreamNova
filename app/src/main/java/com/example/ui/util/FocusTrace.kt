package com.example.ui.util

import android.os.SystemClock
import android.util.Log
import com.example.BuildConfig

/**
 * Diagnostica del ritorno Player -> Dettaglio, attiva SOLO nelle build DEBUG.
 *
 * Il difetto corretto era una doppia consegna dello stesso tasto BACK: il KEY_DOWN chiudeva
 * il player e il KEY_UP, rimasto senza gestore, veniva interpretato dall'Activity come un
 * secondo BACK e faceva POPpare la destinazione Dettaglio. Per ricostruire questo tipo di
 * corsa servono l'ordine temporale (timestamp MONOTONICO), il tasto reale (DOWN/UP), il
 * BackHandler che consuma l'evento, la route corrente e il focus realmente atterrato.
 *
 * Il tag è dedicato ([TAG]) e filtrabile con `adb logcat -s NOVA_FOCUS_TRACE`. In release
 * [log] è un no-op: nessun costo e nessun rumore nei log di produzione.
 */
object FocusTrace {

  const val TAG: String = "NOVA_FOCUS_TRACE"

  private val enabled: Boolean = BuildConfig.DEBUG

  fun log(area: String, event: String) {
    if (!enabled) return
    Log.d(TAG, "t=${SystemClock.elapsedRealtime()} [$area] $event")
  }
}
