package com.aistudio.streamnova.mobile.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * true su tablet/desktop (larghezza >= 840dp): la shell usa la navigation rail
 * e le griglie si adattano con più colonne. Il telefono resta su bottom bar.
 */
val LocalWindowWidthExpanded = staticCompositionLocalOf { false }
