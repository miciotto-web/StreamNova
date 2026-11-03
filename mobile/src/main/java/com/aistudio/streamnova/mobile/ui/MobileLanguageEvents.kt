package com.aistudio.streamnova.mobile.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Evento di cambio lingua richiesto dall'utente nelle Impostazioni.
 *
 * Serve a distinguere un cambio ESPLICITO dalla lingua letta da DataStore all'avvio:
 * solo il primo deve svuotare la cache TMDB e ricaricare il catalogo. Un semplice
 * `collect` dello StateFlow, infatti, tratterebbe il caricamento iniziale come un
 * cambio e rifarebbe il refresh a ogni avvio.
 */
object MobileLanguageEvents {
  private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 1)
  val changes: SharedFlow<String> = _changes.asSharedFlow()

  fun notifyChanged(language: String) {
    _changes.tryEmit(language)
  }
}
