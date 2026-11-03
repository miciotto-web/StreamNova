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
 * Policy unica di orientamento (Fase 8.1).
 *
 * | Device  | Schermata            | Riproduzione        | Multi-window | Target           |
 * |---------|----------------------|---------------------|--------------|------------------|
 * | Phone   | non-player           | —                   | no           | PORTRAIT         |
 * | Phone   | player               | attiva              | no           | SENSOR_LANDSCAPE |
 * | Phone   | player               | loading/errore/picker | no         | PORTRAIT         |
 * | Tablet  | non-player           | —                   | no           | SENSOR_LANDSCAPE |
 * | Tablet  | player               | attiva              | no           | SENSOR_LANDSCAPE |
 * | Tablet  | player               | loading/errore/picker | no         | SENSOR_LANDSCAPE |
 * | Qualsiasi | qualsiasi          | qualsiasi           | sì           | UNSPECIFIED      |
 *
 * In multi-window non viene imposto nulla: il sistema gestisce la finestra e il
 * layout adattivo (`BoxWithConstraints`, rail laterale) segue i suoi vincoli.
 *
 * @param isPlayerScreen true quando la destinazione corrente è il player, anche
 *   se la sorgente non è ancora pronta (`playbackActive = false` in loading,
 *   errore e source picker).
 * @param playbackActive true solo quando una sorgente sta effettivamente
 *   riproducendo (`MobilePlayerUiState.source != null`).
 * @param isMultiWindow true quando l'Activity è in split-screen/freeform.
 */
fun mobileOrientationTarget(
  formFactor: MobileFormFactor,
  isPlayerScreen: Boolean,
  playbackActive: Boolean,
  isMultiWindow: Boolean
): MobileOrientationTarget = when {
  isMultiWindow -> MobileOrientationTarget.UNSPECIFIED
  formFactor == MobileFormFactor.TABLET -> MobileOrientationTarget.SENSOR_LANDSCAPE
  !isPlayerScreen -> MobileOrientationTarget.PORTRAIT
  playbackActive -> MobileOrientationTarget.SENSOR_LANDSCAPE
  else -> MobileOrientationTarget.PORTRAIT
}

/**
 * Orientamento del trailer (Fase 8.4).
 *
 * Il trailer è un overlay della scheda Dettaglio: su telefono deve poter ruotare
 * in landscape e adattarsi alla rotazione fisica; alla chiusura la policy base
 * riporta il portrait. Sul tablet il landscape è già la policy del device.
 *
 * Non tocca la policy del player principale: [mobileOrientationTarget] resta
 * invariata e il trailer ha un ingresso separato nel controller.
 */
fun mobileTrailerOrientationTarget(
  formFactor: MobileFormFactor,
  isMultiWindow: Boolean
): MobileOrientationTarget = when {
  isMultiWindow -> MobileOrientationTarget.UNSPECIFIED
  formFactor == MobileFormFactor.TABLET -> MobileOrientationTarget.SENSOR_LANDSCAPE
  else -> MobileOrientationTarget.SENSOR_LANDSCAPE
}
