package com.example.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * Utilità per il ripristino asincrono del focus con ritardo di layout.
 *
 * PROBLEMA RISOLTO:
 * Quando si ritorna da una schermata di dettaglio (es. cliccando Back), il focus deve
 * essere ripristinato sulla card precedentemente selezionata. Il ripristino falliva perché
 * requestFocus() veniva chiamato immediatamente dopo scrollToItem(), prima che Compose
 * avesse completato la creazione e la misura fisica dei componenti LazyVerticalGrid/LazyColumn.
 *
 * SOLUZIONE:
 * - Scroll alla posizione salvata
 * - Delay di 150ms per permettere a Compose di completare il layout dei figli
 * - Request focus sull'elemento salvato
 *
 * USO:
 * ```
 * val lastFocusedIndex by viewModel.lastFocusedIndex.collectAsState()
 * val gridState = rememberLazyGridState()
 * val itemRequesters = remember { mutableMapOf<Int, FocusRequester>() }
 *
 * LaunchedEffect(lastFocusedIndex) {
 *   restoreFocusWithDelay(lastFocusedIndex, gridState, itemRequesters)
 * }
 * ```
 */
@Composable
fun LazyListState.restoreFocusWithDelay(
    targetIndex: Int?,
    requesterForIndex: (Int) -> FocusRequester?
) {
    LaunchedEffect(targetIndex) {
        val index = targetIndex ?: return@LaunchedEffect
        // 1. Esegui prima lo scroll alla posizione salvata
        scrollToItem(index)
        
        // 2. Attendi che Compose completi il layout dei figli
        delay(150L)
        
        // 3. Invoca requestFocus sul FocusRequester associato
        requesterForIndex(index)?.requestFocus()
    }
}

@Composable
fun LazyGridState.restoreFocusWithDelay(
    targetIndex: Int?,
    requesterForIndex: (Int) -> FocusRequester?
) {
    LaunchedEffect(targetIndex) {
        val index = targetIndex ?: return@LaunchedEffect
        // 1. Esegui prima lo scroll alla posizione salvata
        scrollToItem(index)
        
        // 2. Attendi che Compose completi il layout dei figli
        delay(150L)
        
        // 3. Invoca requestFocus sul FocusRequester associato
        requesterForIndex(index)?.requestFocus()
    }
}
