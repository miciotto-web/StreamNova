package com.example.ui.screens.player

import androidx.compose.runtime.Immutable

/**
 * Valori effettivamente applicati al `SubtitleView` di Media3 (Fase 6.1).
 *
 * Contiene solo i valori che dipendono dalle preferenze dell'utente e che vengono
 * applicati in `AndroidView.update`: dimensione del testo, colore di sfondo dei cue e
 * posizione verticale. Volutamente primitivi (niente `CaptionStyleCompat`, che non
 * implementa `equals`): l'uguaglianza è per VALORE, quindi riapplicare lo stile solo quando
 * cambia davvero è verificabile e deterministico.
 */
@Immutable
internal data class SubtitleStyleSnapshot(
  /** Frazione dell'altezza vista usata da `SubtitleView.setFractionalTextSize`. */
  val textSizeFraction: Float,
  /**
   * `CaptionStyleCompat.backgroundColor` applicato con `SubtitleView.setStyle`.
   * Include già l'opacità scelta dall'utente: cambiare solo l'opacità produce un
   * valore diverso e quindi una riapplicazione dello stile.
   */
  val backgroundColor: Int,
  /** Frazione di distanza dal bordo inferiore usata da `setBottomPaddingFraction`. */
  val bottomPaddingFraction: Float
)

/**
 * Guardia anti-riapplicazione per lo stile dei sottotitoli (Fase 6.1).
 *
 * `AndroidView.update` viene eseguito ad OGNI ricomposizione della schermata: prima di
 * questa classe `setStyle`, `setFractionalTextSize` e `setBottomPaddingFraction` (e il log
 * `Dimensione sottotitoli:`) venivano rieseguiti ogni volta, anche quando i valori non
 * erano cambiati.
 *
 * Regole:
 * - stessa vista + stessi valori  -> nessuna riapplicazione, nessun log;
 * - valori diversi                -> riapplicazione + una sola emissione diagnostica;
 * - vista diversa                 -> riapplicazione SEMPRE: è il caso della `PlayerView`
 *   ricreata, che nasce con lo stile di default di Media3 e deve ricevere subito le
 *   preferenze correnti (niente flicker, nessun aggiornamento perso).
 *
 * Il chiamante deve invocare [markApplied] SOLO dopo l'applicazione riuscita (es.
 * `subtitleView` non ancora disponibile: si riprova all'update successivo).
 */
internal class SubtitleStyleApplier {

  private var appliedView: Any? = null
  private var appliedSnapshot: SubtitleStyleSnapshot? = null

  /** true se i valori vanno (ri)applicati alla vista indicata. */
  fun shouldApply(view: Any, snapshot: SubtitleStyleSnapshot): Boolean =
    appliedView !== view || appliedSnapshot != snapshot

  /** Registra i valori realmente applicati alla vista. */
  fun markApplied(view: Any, snapshot: SubtitleStyleSnapshot) {
    appliedView = view
    appliedSnapshot = snapshot
  }

  /** Ultimi valori applicati (null se non è ancora stata applicata alcuna preferenza). */
  fun lastApplied(): SubtitleStyleSnapshot? = appliedSnapshot

  /** Dimentica lo stato: la prossima chiamata riapplica sempre. */
  fun reset() {
    appliedView = null
    appliedSnapshot = null
  }
}
