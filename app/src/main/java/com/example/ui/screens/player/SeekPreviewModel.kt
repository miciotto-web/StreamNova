package com.example.ui.screens.player

import java.util.Locale

/**
 * Formattazione del timestamp dell'anteprima e posizionamento dell'overlay sulla timeline.
 *
 * Logica pura, senza player e senza Compose: è l'unico punto in cui si decide *cosa*
 * scrivere sopra il fotogramma e *dove* disegnarlo. Il componente `SeekPreview`
 * (PlayerScreen.kt) si limita a disegnare i valori restituiti qui.
 *
 * Il timestamp è sempre quello della posizione *proposta* dallo scrubbing
 * ([PlayerScrubUiState.positionMs]), mai quello corrente del player: durante lo scrubbing
 * il player resta fermo al punto di partenza.
 */
internal object SeekPreviewModel {

  /** Margine orizzontale riservato ai bordi: l'overlay non esce mai dallo schermo. */
  private const val EDGE_MARGIN_FRACTION = 0.02f

  /**
   * Timestamp leggibile per la posizione di anteprima.
   *
   * - "0:07" / "12:45" sotto l'ora, "1:02:03" sopra (stesso stile compatto dello HUD);
   * - la durata è accodata solo quando è nota, altrimenti resta "/ --:--";
   * - le posizioni negative (o durate negative di sorgenti malformate) valgono zero:
   *   l'anteprima non deve mai mostrare un tempo impossibile.
   *
   * @param positionMs posizione proposta dallo scrubbing.
   * @param durationMs durata del contenuto; un valore non positivo indica durata ignota.
   */
  fun formatTimestamp(positionMs: Long, durationMs: Long): String {
    val position = positionMs.coerceAtLeast(0L)
    val positionText = formatClock(position)
    return if (durationMs > 0L) {
      "$positionText / ${formatClock(durationMs)}"
    } else {
      "$positionText / --:--"
    }
  }

  /** Orologio compatto senza ore quando non servono: "0:07", "12:45", "1:02:03". */
  fun formatClock(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
      String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
  }

  /**
   * Frazione della timeline occupata dalla posizione di anteprima (0..1).
   * Con durata ignota non esiste una posizione relativa: 0.
   */
  fun timelineFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
  }

  /**
   * Centro ideale dell'overlay, espresso come frazione della larghezza dello schermo:
   * segue la posizione di anteprima come il cursore di YouTube.
   */
  fun preferredCenterFraction(positionMs: Long, durationMs: Long): Float =
    timelineFraction(positionMs, durationMs)

  /**
   * Centro corretto per non uscire dai bordi: il riquadro di larghezza
   * `previewWidthFraction` viene tenuto dentro `[EDGE_MARGIN_FRACTION, 1 - EDGE_MARGIN_FRACTION]`.
   * Se l'anteprima è più larga dello spazio utile, resta centrata.
   */
  fun clampedCenterFraction(
    preferredCenterFraction: Float,
    previewWidthFraction: Float
  ): Float {
    val half = (previewWidthFraction / 2f).coerceAtLeast(0f)
    val minCenter = EDGE_MARGIN_FRACTION + half
    val maxCenter = 1f - EDGE_MARGIN_FRACTION - half
    if (minCenter > maxCenter) return 0.5f
    return preferredCenterFraction.coerceIn(minCenter, maxCenter)
  }

  /**
   * Fascia di posizioni che vale la pena prefetchare durante lo scrubbing: la posizione
   * corrente (priorità massima) e, dentro il contenuto, i due step adiacenti.
   *
   * Restituisce posizioni distinte in ordine di priorità: la prima è quella da mostrare
   * subito, le altre sono i candidati della prossima pressione (<</>>). Così unsolo
   * fotogramma recuperato copre sia il passo successivo sia quello precedente.
   */
  fun prefetchPositions(positionMs: Long, durationMs: Long, stepMs: Long): List<Long> {
    val current = PlayerScrub.clampPosition(positionMs, durationMs)
    val forward = PlayerScrub.clampPosition(current + stepMs, durationMs)
    val backward = PlayerScrub.clampPosition(current - stepMs, durationMs)
    val candidates = listOf(current, forward, backward)
    val ordered = LinkedHashSet<Long>()
    for (candidate in candidates) {
      if (canRequestPreview(candidate, durationMs)) ordered.add(candidate)
    }
    return ordered.toList()
  }

  /**
   * Un fotogramma ha senso solo per una posizione dentro il contenuto: niente anteprima
   * per durata ignota (live / flussi senza seek) né per l'ultimo istante, che nella
   * pratica non contiene frame decodificabili.
   */
  fun canRequestPreview(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L) return false
    if (positionMs < 0L) return false
    return positionMs < durationMs
  }

  /**
   * Chiave di cache del fotogramma: la arrotonda a step interi partendo dall'inizio del
   * contenuto, così pressioni consecutive sulla stessa posizione (o cambi di verso senza
   * spostamenti reali) riusano la stessa immagine. Lo scarto introdotto è al massimo
   * uno [stepMs], cioè esattamente il passo dello scrubbing.
   */
  fun bucketKey(positionMs: Long, stepMs: Long): Long {
    if (stepMs <= 0L) return positionMs.coerceAtLeast(0L)
    return (positionMs.coerceAtLeast(0L) / stepMs) * stepMs
  }
}
