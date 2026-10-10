package com.example.ui.screens.player

import android.os.Bundle
import androidx.media3.common.MediaItem

/**
 * Logica di risoluzione e visibilità del pulsante "Salta Intro".
 *
 * Supporta unicamente timestamp reali:
 * 1. Timestamp reali da extras del [MediaItem.mediaMetadata] (es. stream/Cinemeta/provider o capitoli).
 * 2. Timestamp reali restituiti da IntroDB tramite IntroSkipRepository.
 *
 * È esplicitamente vietato qualsiasi fallback o stima euristica hardcoded (es. 30s..150s o 90s..150s):
 * se nessuna fonte certificata fornisce timestamp reali, [IntroWindow] è null e il pulsante
 * non viene mai mostrato.
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
   * Verifica se il titolo di un capitolo corrisponde a una sigla / intro.
   * Riconosce parole chiave come "intro", "sigla", "opening".
   */
  fun isChapterIntroTitle(title: String?): Boolean {
    if (title.isNullOrBlank()) return false
    val lower = title.lowercase()
    return lower.contains("intro") || lower.contains("sigla") || lower.contains("opening")
  }

  /**
   * Estrae una [IntroWindow] da un capitolo con titolo matching intro.
   */
  fun windowFromChapter(title: String?, startMs: Long?, endMs: Long?): IntroWindow? {
    if (!isChapterIntroTitle(title)) return null
    return window(startMs, endMs)
  }

  /**
   * Verifica se l'elemento è identificabile come serie TV in modo resiliente:
   * considera il flag booleano, seasonNumber, episodeNumber, o mediaType ("series", "tv", etc.).
   */
  fun isTvShow(
    isTvShow: Boolean = false,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    mediaType: String? = null
  ): Boolean {
    if (isTvShow) return true
    if (seasonNumber != null || episodeNumber != null) return true
    if (mediaType != null) {
      if (mediaType.equals("series", ignoreCase = true) ||
        mediaType.equals("tv", ignoreCase = true) ||
        mediaType.equals("serie_tv", ignoreCase = true) ||
        mediaType.contains("serie", ignoreCase = true)
      ) {
        return true
      }
    }
    return false
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

  /**
   * Risolve la finestra intro considerando priorità:
   * 1. Metadata reali del MediaItem (se presenti ed extras validi).
   * 2. Timestamp reali da IntroDB ([introDbWindow]).
   *
   * Se nessuna fonte certificata fornisce timestamp reali, restituisce `null`.
   * Nessun fallback euristico o intervallo fittizio.
   */
  fun resolveIntroWindow(
    mediaItem: MediaItem?,
    introDbWindow: IntroWindow? = null
  ): IntroWindow? {
    val realFromMedia = windowFromMediaItem(mediaItem)
    if (realFromMedia != null && realFromMedia.isValid) {
      return realFromMedia
    }
    if (introDbWindow != null && introDbWindow.isValid) {
      return introDbWindow
    }
    return null
  }

  private fun Bundle.readLongOrNull(key: String): Long? =
    (get(key) as? Number)?.toLong()?.takeIf { it >= 0L }
}
