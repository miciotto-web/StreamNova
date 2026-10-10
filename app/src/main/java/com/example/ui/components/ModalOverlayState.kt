package com.example.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Stato globale minimale che segnala se un overlay modale "in-window" (es. il popup
 * della chiave API [TextInputDialog]) è attualmente a schermo.
 *
 * Serve a [com.example.MainActivity] per NON intercettare il tasto LEFT mentre un
 * modale è aperto: quella gesture, pensata per il bordo sinistro della Home, mentre il
 * popup è visibile sposterebbe il focus sulla sidebar / sull'interfaccia sottostante,
 * facendolo "fuggire" dal dialog.
 *
 * Il conteggio (invece di un booleano) rende lo stato robusto a composizioni multiple
 * o annidate. Il valore è letto fuori dalla composizione (nel key handler), quindi non
 * forza ricomposizioni: legge sempre il valore corrente.
 */
object ModalOverlayState {
  private var openCount by mutableStateOf(0)

  val isAnyOpen: Boolean
    get() = openCount > 0

  fun onShown() {
    openCount++
  }

  fun onHidden() {
    if (openCount > 0) openCount--
  }
}
