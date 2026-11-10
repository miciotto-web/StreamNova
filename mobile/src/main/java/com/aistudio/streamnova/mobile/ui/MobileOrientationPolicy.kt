package com.aistudio.streamnova.mobile.ui

/**
 * Form factor del dispositivo, indipendente dalla finestra corrente.
 *
 * Non va confuso con i breakpoint di layout (`LocalWindowWidthExpanded`, 840 dp):
 * quelli misurano la larghezza disponibile per la composizione e cambiano in
 * split-screen/freeform. Qui interessa la classe del dispositivo, misurata sul
 * display fisico (vedi [smallestDisplayWidthDp] e [resolveMobileFormFactor]).
 */
enum class MobileFormFactor {
  PHONE,
  TABLET
}

/**
 * Orientamento richiesto all'Activity.
 *
 * I valori corrispondono alle costanti `ActivityInfo.SCREEN_ORIENTATION_*`
 * tramite la mappatura nell'helper Android, così la policy resta pura e
 * verificabile con i test JVM.
 */
enum class MobileOrientationTarget {
  PORTRAIT,
  SENSOR_LANDSCAPE,
  UNSPECIFIED
}

/**
 * Soglia standard Android per le "large screen": `sw600dp`. Un display con lato
 * corto >= 600 dp è trattato come tablet, come i qualifier `values-sw600dp`.
 *
 * Un pieghevole aperto che supera la soglia segue la policy tablet: è la stessa
 * classificazione usata dal sistema per i layout large screen.
 */
const val MOBILE_TABLET_MIN_SMALLEST_WIDTH_DP = 600

/**
 * Lato corto del display in dp, arrotondato per difetto come la misura
 * `smallestScreenWidthDp` della piattaforma.
 *
 * Accetta i pixel del **display** (non della finestra): in split-screen la
 * finestra può essere più stretta del device e non deve declassare un tablet.
 *
 * @return null se le misure non sono valide, così il chiamante può usare il
 * fallback documentato in [resolveMobileFormFactor].
 */
fun smallestDisplayWidthDp(widthPx: Int, heightPx: Int, densityDpi: Int): Int? {
  if (widthPx <= 0 || heightPx <= 0 || densityDpi <= 0) return null
  return minOf(widthPx, heightPx) * 160 / densityDpi
}

/** Classifica il display in base al lato corto in dp (soglia 599/600 dp). */
fun mobileFormFactor(smallestWidthDp: Int): MobileFormFactor =
  if (smallestWidthDp >= MOBILE_TABLET_MIN_SMALLEST_WIDTH_DP) {
    MobileFormFactor.TABLET
  } else {
    MobileFormFactor.PHONE
  }

/**
 * Risolve il form factor con il fallback approvato dal report Fase 8.1:
 * misura del display se disponibile, altrimenti `Configuration.smallestScreenWidthDp`.
 *
 * Non si usa mai `maxWidth`/`maxHeight` della finestra: quella è una misura di
 * layout che in multi-window non descrive il dispositivo.
 */
fun resolveMobileFormFactor(
  measuredDisplayWidthDp: Int?,
  configurationSmallestScreenWidthDp: Int
): MobileFormFactor =
  mobileFormFactor(measuredDisplayWidthDp ?: configurationSmallestScreenWidthDp)

/**
 * Policy unica di orientamento (Fase 9: libertà totale di orientamento sui
 * tablet, esperienza telefono invariata).
 *
 * | Device  | Schermata            | Riproduzione        | Multi-window | Target           |
 * |---------|----------------------|---------------------|--------------|------------------|
 * | Phone   | non-player           | —                   | no           | PORTRAIT         |
 * | Phone   | player               | attiva              | no           | SENSOR_LANDSCAPE |
 * | Phone   | player               | loading/errore/picker | no         | PORTRAIT         |
 * | Tablet  | qualsiasi            | qualsiasi           | no           | UNSPECIFIED      |
 * | Qualsiasi | qualsiasi          | qualsiasi           | sì           | UNSPECIFIED      |
 *
 * Il tablet non ha alcun orientamento imposto: navigazione e player seguono
 * l'orientamento fisico scelto dall'utente e la rotazione di sistema. Nessuna
 * rotazione forzata all'avvio della riproduzione: se il tablet è verticale il
 * player si apre in verticale e si adatta a ogni rotazione successiva. Il
 * playback non viene mai interrotto dalla policy: il manifest gestisce
 * `orientation|screenSize|...` senza ricreare l'Activity.
 *
 * I layout (griglie adattive, hero, controlli player) seguono i vincoli della
 * finestra (`BoxWithConstraints`, `LocalWindowWidthExpanded`) e si riorganizzano
 * da soli a ogni rotazione, sia in navigazione sia durante la riproduzione.
 *
 * In multi-window non viene imposto nulla: il sistema gestisce la finestra e il
 * layout adattivo segue i suoi vincoli.
 *
 * @param isPlayerScreen true quando la destinazione corrente è il player, anche
 *   se la sorgente non è ancora pronta (`playbackActive = false` in loading,
 *   errore e source picker). Non influenza il target dei tablet.
 * @param playbackActive true solo quando una sorgente sta effettivamente
 *   riproducendo (`MobilePlayerUiState.source != null`). Non influenza il
 *   target dei tablet.
 * @param isMultiWindow true quando l'Activity è in split-screen/freeform.
 */
fun mobileOrientationTarget(
  formFactor: MobileFormFactor,
  isPlayerScreen: Boolean,
  playbackActive: Boolean,
  isMultiWindow: Boolean
): MobileOrientationTarget = when {
  // Tablet e multi-window: nessuna forzatura, segue l'orientamento fisico
  // scelto dall'utente e gestito da Android.
  isMultiWindow || formFactor == MobileFormFactor.TABLET ->
    MobileOrientationTarget.UNSPECIFIED
  // Telefono: portrait in navigazione e negli stati intermedi del player,
  // landscape con sensore solo durante la riproduzione effettiva.
  !isPlayerScreen -> MobileOrientationTarget.PORTRAIT
  playbackActive -> MobileOrientationTarget.SENSOR_LANDSCAPE
  else -> MobileOrientationTarget.PORTRAIT
}

/**
 * Orientamento del trailer (Fase 8.4, adattata alla Fase 9).
 *
 * Il trailer è un overlay della scheda Dettaglio: su telefono abilita il
 * landscape finché l'overlay è aperto e alla chiusura la policy base riporta il
 * portrait. Sul tablet il trailer segue l'orientamento corrente del device:
 * nessuna rotazione forzata all'apertura.
 *
 * Non tocca la policy del player principale: [mobileOrientationTarget] resta
 * invariata e il trailer ha un ingresso separato nel controller.
 */
fun mobileTrailerOrientationTarget(
  formFactor: MobileFormFactor,
  isMultiWindow: Boolean
): MobileOrientationTarget = when {
  isMultiWindow || formFactor == MobileFormFactor.TABLET ->
    MobileOrientationTarget.UNSPECIFIED
  else -> MobileOrientationTarget.SENSOR_LANDSCAPE
}
