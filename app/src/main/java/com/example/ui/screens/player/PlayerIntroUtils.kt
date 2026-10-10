package com.example.ui.screens.player

import android.os.Bundle
import androidx.media3.common.MediaItem

/**
 * Logica di risoluzione e visibilità del pulsante "Salta Intro".
 *
 * L'intervallo della sigla iniziale è un dato REALE: arriva dagli extras del
 * [MediaItem.mediaMetadata] corrente, popolati dai metadata dello stream
 * (Stremio / Cinemeta / provider). Se i timestamp non sono presenti o non sono
 * coerenti, [window] restituisce `null` e il pulsante NON deve mai comparire:
 * è esplicitamente vietato ripiegare su un intervallo fittizio (es. 0 → 90s).
 */
internal object PlayerIntroUtils {

  /** Il pulsante scompare poco prima della fine della sigla, per non "tagliarla". */
  const val INTRO_BUTTON_END_MARGIN_MS = 2_500L

  /** Chiave extras: inizio sigla iniziale (ms dall'inizio del media). */
  const val EXTRA_INTRO_START_MS = "streamnova.intro.startMs"

  /** Chiave extras: fine sigla iniziale (ms dall'inizio del media). */
  const val EXTRA_INTRO_END_MS = "streamnova.intro.endMs"

  /**
   * Intervallo della sigla iniziale. [isValid] richiede un inizio non negativo e
   * una fine strettamente successiva all'inizio.
   */
  data class IntroWindow(val startMs: Long, val endMs: Long) {
    val isValid: Boolean get() = startMs >= 0L && endMs > startMs
  }

  /**
   * Costruisce una [IntroWindow] SOLO quando entrambi i timestamp sono presenti e
   * coerenti. In ogni altro caso restituisce `null`: nessun default, nessuna stima.
   */
  fun window(startMs: Long?, endMs: Long?): IntroWindow? {
    if (startMs == null || endMs == null) return null
    val candidate = IntroWindow(startMs, endMs)
    return candidate.takeIf { it.isValid }
  }

  /**
   * `true` SOLO mentre [positionMs] cade dentro la sigla, con chiusura anticipata di
   * [INTRO_BUTTON_END_MARGIN_MS] prima della fine.
   *
   * REGOLA CRITICA: con finestra nulla o non valida il pulsante è sempre nascosto.
   */
  fun isButtonVisible(positionMs: Long, window: IntroWindow?): Boolean {
    if (window == null || !window.isValid) return false
    return positionMs >= window.startMs &&
      positionMs < (window.endMs - INTRO_BUTTON_END_MARGIN_MS)
  }

  /**
   * Estrae l'intervallo intro dai metadata REALI del [MediaItem] corrente
   * (`mediaMetadata.extras`). Restituisce `null` se assenti o incoerenti.
   */
  fun windowFromMediaItem(mediaItem: MediaItem?): IntroWindow? {
    val extras = mediaItem?.mediaMetadata?.extras ?: return null
    return window(
      startMs = extras.readLongOrNull(EXTRA_INTRO_START_MS),
      endMs = extras.readLongOrNull(EXTRA_INTRO_END_MS)
    )
  }

  private fun Bundle.readLongOrNull(key: String): Long? =
    (get(key) as? Number)?.toLong()?.takeIf { it >= 0L }
}
