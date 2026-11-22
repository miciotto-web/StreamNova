package com.example.ui.components

/**
 * Ponte tra la schermata di sezione attiva e l'uscita dalla sidebar verso il contenuto
 * (RIGHT su una voce della sidebar).
 *
 * Perché non basta la ricerca geometrica di Compose: nelle sezioni il cui contenuto inizia
 * SOTTO l'overlay della sidebar (es. Impostazioni) nessuna riga è candidata «strict-right»
 * della voce a fuoco, quindi `moveFocus(Right)` non trova destinazioni e il ripiego 2D
 * atterra su un'altra voce della sidebar stessa (difetto: RIGHT da «Impostazioni»
 * focalizzava «Non so cosa guardare» invece di entrare nel menu).
 *
 * La schermata di sezione registra, mentre è composta, un handler che porta il focus nel
 * PROPRIO contenuto in modo deterministico ([attach] in `DisposableEffect`, [detach] in
 * `onDispose`); il chiamante della sidebar lo preferisce alla ricerca geometrica quando
 * esiste. L'handler gestisce anche il caso «target scaricato dallo scroll» riportandolo
 * in vista prima di focalizzarlo.
 *
 * Come [ModalOverlayState], è un holder globale minimale: letto/scritto fuori dalla
 * composizione, senza ricomposizioni, coerente con l'ultima sezione composta.
 */
object SidebarContentExitLink {
  private var handler: (() -> Boolean)? = null

  /** Handler della sezione attiva; `null` se la sezione non ha registrato un target. */
  val exitHandler: (() -> Boolean)?
    get() = handler

  fun attach(target: () -> Boolean) {
    handler = target
  }

  fun detach(target: () -> Boolean) {
    if (handler === target) handler = null
  }
}
