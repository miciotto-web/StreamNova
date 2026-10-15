package com.example.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Navigazione pura del carosello Hero: nessuno stato Compose, cosi' la logica di
 * scorrimento (avanti con wrap-around, indietro con stop sul primo poster) resta
 * verificabile con un test JVM senza montare la UI.
 */
object HeroCarouselNavigation {

  /** Poster successivo con wrap-around: dall'ultimo si torna al primo. */
  fun nextIndex(currentIndex: Int, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    return (currentIndex.coerceIn(0, itemCount - 1) + 1) % itemCount
  }

  /** Poster precedente: si ferma al primo, non esiste wrap-around all'indietro. */
  fun previousIndex(currentIndex: Int): Int = if (currentIndex > 0) currentIndex - 1 else 0

  /**
   * true quando il D-pad LEFT deve tornare al poster precedente.
   * Sul primissimo poster (o con un solo poster) e' false: il LEFT resta libero di far
   * uscire il focus dall'Hero verso la Sidebar.
   */
  fun canScrollToPrevious(currentIndex: Int, itemCount: Int): Boolean =
    itemCount > 1 && currentIndex > 0
}

/**
 * Stato condiviso del carosello Hero tra [HeroBanner] (che lo aggiorna) e `MainActivity`
 * (che lo consulta), sullo stesso modello di [ModalOverlayState].
 *
 * Serve perche' i tasti D-pad vengono consegnati PRIMA in fase di preview a `MainActivity`,
 * che con la Sidebar chiusa intercetta qualsiasi LEFT per spostare il focus sulla Sidebar
 * stessa: senza questo stato il LEFT non raggiungerebbe mai l'Hero e non sarebbe possibile
 * tornare al poster precedente.
 *
 * L'Hero e' una superficie unica di focus, quindi basta un flag: nessun tracciamento per
 * singolo pulsante.
 */
object HeroCarouselState {

  private var isHeroFocused by mutableStateOf(false)
  private var carouselIndex by mutableIntStateOf(0)
  private var carouselSize by mutableIntStateOf(0)

  /**
   * true solo quando l'Hero ha il focus E il carosello non e' gia' sul primo poster: in
   * quello stato `MainActivity` deve lasciar passare il LEFT perche' lo consuma l'Hero
   * tornando al poster precedente.
   */
  val consumesLeftKey: Boolean
    get() = isHeroFocused &&
      HeroCarouselNavigation.canScrollToPrevious(carouselIndex, carouselSize)

  fun onHeroFocusChanged(focused: Boolean) {
    isHeroFocused = focused
  }

  fun onCarouselChanged(index: Int, size: Int) {
    carouselIndex = index
    carouselSize = size
  }

  /** Azzera lo stato quando l'Hero esce dalla composizione (navigazione ad altra schermata). */
  fun reset() {
    isHeroFocused = false
    carouselIndex = 0
    carouselSize = 0
  }
}
