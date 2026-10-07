package com.example.data.stremio.provider

/**
 * Stato di paginazione **per catalogo** di un provider.
 *
 * PROBLEMA RISOLTO
 * L'offset `skip` del protocollo Stremio è un offset del singolo endpoint
 * `catalog/{type}/{id}`, non del provider. Applicare lo stesso `skip` a tutti i
 * cataloghi associati produceva richieste incoerenti (e duplicati): ogni target
 * deve avanzare del numero di elementi che ha effettivamente consegnato.
 *
 * Esempio con due cataloghi: A → 0, 100, 200… e B → 0, 100, 200… restano indipendenti.
 *
 * Classe pura e sincronizzata: nessuna rete, testabile in JVM.
 */
class ProviderCatalogPaging {

  /** Offset successivo per target, indicizzato da [ResolvedProviderTarget.targetId]. */
  private val offsets = HashMap<String, Int>()

  /** Offset corrente del target: `0` alla prima pagina. */
  @Synchronized
  fun nextOffset(targetId: String): Int = offsets[targetId] ?: 0

  /**
   * Avanza l'offset del target della quantità consegnata.
   * Un target che non restituisce nulla non avanza: evita cicli infiniti su offset fermi.
   */
  @Synchronized
  fun advance(targetId: String, delivered: Int) {
    if (delivered <= 0) return
    offsets[targetId] = nextOffset(targetId) + delivered
  }

  /** Azzera tutti gli offset: da usare all'apertura di un provider. */
  @Synchronized
  fun reset() {
    offsets.clear()
  }

  /** true se nessun target ha ancora avanzato: siamo alla prima pagina. */
  @Synchronized
  fun isAtFirstPage(): Boolean = offsets.isEmpty()

  /** Snapshot degli offset correnti, per diagnostica. */
  @Synchronized
  fun snapshot(): Map<String, Int> = LinkedHashMap(offsets)

  /** Offset massimo raggiunto da un target: usato come segnale di "fine catalogo". */
  @Synchronized
  fun maxOffset(): Int = offsets.values.maxOrNull() ?: 0
}