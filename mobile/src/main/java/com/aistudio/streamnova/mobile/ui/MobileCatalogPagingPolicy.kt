package com.aistudio.streamnova.mobile.ui

/**
 * Distanza di pre-caricamento delle griglie mobile: numero di card prima della fine
 * della lista che innescano la richiesta della pagina successiva. Stessa semantica
 * della griglia Provider TV (`PROVIDER_PREFETCH_DISTANCE`), adattata alla densità
 * di card di uno schermo touch.
 */
internal const val MOBILE_PAGE_PREFETCH_DISTANCE = 6

/**
 * Politica di caricamento progressivo delle griglie mobile (Film, Serie TV e
 * catalogo Provider): la pagina successiva viene richiesta quando l'utente si
 * avvicina alla fine della lista, come sulla TV 1.0.5. È la controparte di
 * `shouldRequestNextProviderPage` TV, condivisa dalle schermate Catalog e Provider.
 *
 * Funzione PURA: verificabile senza Compose né ViewModel.
 *
 * @param totalItems numero di card della griglia (0 = griglia non ancora popolata).
 * @param lastVisibleItemIndex indice dell'ultima card visibile.
 * @param isLoading true se una paginazione per la sorgente richiesta è già in corso.
 * @param hasMore false quando la sorgente ha esaurito le pagine (o il filtro corrente
 *        non ha sorgenti paginabili): il trigger non deve più invocare il caricamento.
 */
internal fun shouldRequestNextMobilePage(
  totalItems: Int,
  lastVisibleItemIndex: Int,
  isLoading: Boolean,
  hasMore: Boolean = true,
  prefetchDistance: Int = MOBILE_PAGE_PREFETCH_DISTANCE
): Boolean {
  if (isLoading || !hasMore) return false
  // Griglia non ancora composta (catalogo vuoto): la pagina successiva va richiesta,
  // è l'unico modo per riempire la schermata.
  if (totalItems == 0) return true
  return lastVisibleItemIndex >= totalItems - prefetchDistance
}
