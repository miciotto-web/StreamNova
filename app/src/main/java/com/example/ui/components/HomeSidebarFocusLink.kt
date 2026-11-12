package com.example.ui.components

import androidx.compose.runtime.Stable

/**
 * Provenienza del focus nella Home nel momento in cui l'utente esce verso la sidebar.
 *
 * Perché non basta la geometria: la sidebar è disegnata come overlay a sinistra del contenuto
 * e le sue voci sono in cima alla colonna. Il RIGHT dalla sidebar, lasciato alla ricerca
 * geometrica di Compose, sceglie il primo nodo focalizzabile a destra della VOCE DELLA
 * SIDEBAR (tipicamente l'Hero), non l'elemento dal quale l'utente era partito. La destinazione
 * va quindi ricordata come DATI e non dedotta dalla posizione corrente dei nodi.
 *
 * Identità usate, in ordine di autorevolezza:
 * - [rowKey] = riga/sezione della Home di provenienza (identità stabile della riga);
 * - [identity] = identità stabile dell'elemento (id del [com.example.data.model.MediaItem] per
 *   le righe media, id del provider per l'hub delle piattaforme, `null` per l'Hero che è una
 *   superficie unica);
 * - [cardKey] = key Compose della card (solo diagnostica e riallineamento quando disponibile);
 * - [colIndex] = posizione al momento dell'uscita, usata SOLO se l'identità non è più
 *   risolvibile (fallback posizionale clampato dentro la stessa riga);
 * - [rowOrdinal] + [listFirstVisibleIndex]/[listFirstVisibleOffset] = posizione verticale
 *   della Home (ordine delle righe e viewport), necessaria per un ripristino deterministico
 *   quando la riga di provenienza non esiste più.
 */
data class HomeFocusOrigin(
  val rowKey: String,
  val colIndex: Int,
  val identity: String?,
  val cardKey: String?,
  val rowOrdinal: Int,
  val listFirstVisibleIndex: Int,
  val listFirstVisibleOffset: Int
)

/** Destinazione concreta del ritorno dalla sidebar: riga + colonna risolte sui dati correnti. */
data class HomeSidebarReturnTarget(val rowKey: String, val colIndex: Int)

/**
 * Risolve la destinazione del ritorno dalla sidebar in modo DETERMINISTICO.
 *
 * Regole, in ordine:
 * 1. riga di provenienza ancora disegnata e popolata: l'elemento si ritrova per IDENTITÀ
 *    (un riordino del catalogo non cambia la card, solo la sua colonna); se l'identità non è
 *    più in riga si usa la colonna di provenienza clampata al conteggio corrente;
 * 2. riga sparita ma contenuto ancora presente in un'altra riga: quella riga/colonna
 *    (stesso contenuto, mai un elemento arbitrario);
 * 3. nessuna traccia del contenuto: la riga DISEGNATA più vicina alla posizione di provenienza
 *    nell'ordine verticale (prima sotto, poi sopra), colonna 0.
 *
 * L'Hero non è mai una scelta "di ripiego": entra in gioco solo se è la riga di provenienza o
 * se è l'unica riga superstite.
 *
 * @return `null` solo quando la Home non ha alcuna riga con contenuti da focalizzare.
 */
fun resolveHomeSidebarReturnTarget(
  origin: HomeFocusOrigin,
  activeRowKeys: List<String>,
  rowItemCounts: Map<String, Int>,
  rowIdentities: Map<String, List<String>>
): HomeSidebarReturnTarget? {
  if (activeRowKeys.isEmpty()) return null

  fun itemCount(rowKey: String): Int = rowItemCounts[rowKey] ?: 0
  fun isUsable(rowKey: String): Boolean = rowKey in activeRowKeys && itemCount(rowKey) > 0

  // 1. Riga di provenienza: identità stabile dell'elemento, poi posizione clampata.
  if (isUsable(origin.rowKey)) {
    val count = itemCount(origin.rowKey)
    val identityIndex = origin.identity
      ?.let { id -> rowIdentities[origin.rowKey]?.indexOf(id) }
      ?.takeIf { it >= 0 }
    val column = (identityIndex ?: origin.colIndex).coerceIn(0, count - 1)
    return HomeSidebarReturnTarget(origin.rowKey, column)
  }

  // 2. Stesso contenuto in un'altra riga disegnata: si segue l'identità, mai un indice.
  val identity = origin.identity
  if (identity != null) {
    for (rowKey in activeRowKeys) {
      if (!isUsable(rowKey)) continue
      val index = rowIdentities[rowKey]?.indexOf(identity) ?: -1
      if (index >= 0) {
        return HomeSidebarReturnTarget(rowKey, index.coerceIn(0, itemCount(rowKey) - 1))
      }
    }
  }

  // 3. Contenuto sparito: riga disegnata più vicina alla posizione di provenienza.
  val start = origin.rowOrdinal.coerceIn(0, activeRowKeys.lastIndex)
  for (index in start..activeRowKeys.lastIndex) {
    val rowKey = activeRowKeys[index]
    if (isUsable(rowKey)) return HomeSidebarReturnTarget(rowKey, 0)
  }
  for (index in start - 1 downTo 0) {
    val rowKey = activeRowKeys[index]
    if (isUsable(rowKey)) return HomeSidebarReturnTarget(rowKey, 0)
  }
  return null
}

/**
 * Ponte tra la Home e la sidebar per il focus (MainActivity possiede entrambe).
 *
 * La Home installa due callback quando è composta:
 * - [captureOrigin]: legge dal coordinatore il punto di partenza REALE in quel momento;
 * - [requestReturn]: registra il ripristino sul percorso autorevole della Home (richiesta
 *   pendente + verifica `onFocusChanged`, lo stesso usato da rientri dal Dettaglio e
 *   atterraggi del fast-scroll).
 *
 * Il ponte è un oggetto `remember` posseduto da MainActivity: nessuno stato globale condiviso
 * tra vite diverse della Home, e la sidebar non conosce il coordinatore.
 */
@Stable
class HomeSidebarFocusLink {

  private var originProvider: (() -> HomeFocusOrigin?)? = null
  private var returnHandler: ((HomeFocusOrigin) -> Boolean)? = null

  /** Installata dalla Home ad ogni ricomposizione (lambda sempre aggiornate). */
  fun attach(
    originProvider: () -> HomeFocusOrigin?,
    returnHandler: (HomeFocusOrigin) -> Boolean
  ) {
    this.originProvider = originProvider
    this.returnHandler = returnHandler
  }

  /** La Home è uscita di scena (cambio sezione): nessun'origine da ricordare. */
  fun detach() {
    originProvider = null
    returnHandler = null
  }

  /**
   * Origine del focus nella Home in questo istante, `null` se la Home non è composta, se
   * nessuna cella ha un focus confermato o se il coordinatore non ha una posizione nota.
   * Va chiamata PRIMA di muovere il focus verso la sidebar.
   */
  fun captureOrigin(): HomeFocusOrigin? = originProvider?.invoke()

  /**
   * Ripristina il focus sull'elemento di [origin].
   *
   * @return `true` se la Home ha accettato il ripristino (destinazione risolta); `false` se la
   *   Home non è composta o non ha alcuna cella su cui tornare — in quel caso il chiamante usa
   *   il ripiego storico.
   */
  fun requestReturn(origin: HomeFocusOrigin): Boolean =
    returnHandler?.invoke(origin) == true
}
