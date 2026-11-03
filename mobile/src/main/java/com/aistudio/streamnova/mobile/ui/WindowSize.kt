package com.aistudio.streamnova.mobile.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Breakpoint di **layout**, non di rilevamento device.
 *
 * true quando la larghezza disponibile per la composizione è >= 840dp: le
 * griglie e l'Hero usano più spazio. Il valore dipende dalla finestra corrente e
 * in split-screen/freeform può scendere sotto soglia anche su un tablet: non va
 * usato per decidere se il dispositivo è un telefono o un tablet.
 *
 * Il form factor per l'orientamento è calcolato separatamente da
 * `MobileOrientationPolicy.kt` sul display fisico (soglia 600dp), con fallback
 * documentato a `Configuration.smallestScreenWidthDp`.
 *
 * La posizione della navigazione non dipende da questo valore ma
 * dall'orientamento della finestra (vedi `mobileNavigationPlacement`).
 */
val LocalWindowWidthExpanded = staticCompositionLocalOf { false }
