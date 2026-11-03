package com.aistudio.streamnova.mobile.ui

/**
 * Regole del fallback TMDB lato Mobile (Fase 8.4).
 *
 * Il toggle `AppSettingsRepository.tmdbGenreFallbackEnabled` è condiviso con la
 * TV, dove governa il fallback per genere. La Mobile non ha categorie per genere:
 * qui il fallback ha lo stesso significato di "sorgente sostitutiva" e si applica
 * alle schermate Film e Serie TV quando i cataloghi addon/Cinemeta non hanno
 * restituito alcun contenuto.
 *
 * Condizioni documentate:
 *  - toggle OFF: nessun risultato TMDB viene mostrato al posto degli addon vuoti;
 *  - toggle ON: si usa il catalogo TMDB già in cache (popolato da
 *    `MediaRepository.refreshTmdbData`), senza duplicare le righe addon;
 *  - se gli addon hanno contenuto, TMDB non viene mai usato (nessun doppione).
 */
object MobileCatalogFallbackPolicy {

  /** true solo quando il toggle è attivo E gli addon non hanno contenuto. */
  fun shouldUseTmdbFallback(
    enabled: Boolean,
    addonContentAvailable: Boolean
  ): Boolean = enabled && !addonContentAvailable

  /**
   * Il fallback è "applicato" solo se ci sono davvero elementi TMDB da mostrare:
   * un catalogo TMDB vuoto non deve trasformare uno stato vuoto in un errore.
   */
  fun isFallbackApplied(
    enabled: Boolean,
    addonContentAvailable: Boolean,
    tmdbItemCount: Int
  ): Boolean = shouldUseTmdbFallback(enabled, addonContentAvailable) && tmdbItemCount > 0
}
