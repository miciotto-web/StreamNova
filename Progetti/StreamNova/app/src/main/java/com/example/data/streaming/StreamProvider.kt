package com.example.data.streaming

/**
 * Contratto di ogni provider/estrattore di streaming HTTP diretto.
 *
 * L'implementazione è responsabile della risoluzione dell'URL di riproduzione
 * (estrazione da pagine/API/embed) e del ritorno degli header necessari per
 * non essere bloccati dal CDN (403 Forbidden).
 */
interface StreamProvider {

  /**
   * Recupera le sorgenti disponibili per un titolo.
   *
   * @param tmdbId identificativo TMDB del titolo.
   * @param isTv true per le serie TV (endpoint stagione/episodio).
   * @param season numero stagione (serie TV), opzionale.
   * @param episode numero episodio (serie TV), opzionale.
   * @return lista di sorgenti ordinata per preferenza (la prima viene riprodotta).
   * @throws java.io.IOException se nessun mirror/endpoint risolve il titolo.
   */
  suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int? = null,
    episode: Int? = null
  ): List<StreamSource>
}
