package com.example.data.util

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.NextEpisodeInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Stato in-memory della prossima puntata TMDB per le card "Continua a guardare".
 *
 * Perché esiste:
 * - la lista media è ri-emessa dal Flow Room a ogni scrittura (progressi, refresh TMDB) e
 *   ricostruisce i [MediaItem] dalle entità: senza questo registro il badge andrebbe perso;
 * - la Home viene ricomposta molte volte (focus D-pad, scroll): le richieste non devono
 *   ripartire a ogni ricomposizione. [pendingTmdbIds] restituisce solo i titoli mai
 *   interrogati o con dato più vecchio di [refreshIntervalMs], quindi il chiamante fa
 *   al massimo una richiesta per serie per intervallo.
 *
 * Il dato NON viene inventato né derivato: [AiringInfo.nextEpisode] è `null` quando TMDB
 * non annuncia una prossima puntata. Il registro è solo in memoria: al riavvio il dato
 * viene riletto dalla cache dettagli TMDB già esistente (`tmdb_response_cache`), senza
 * richieste di rete aggiuntive.
 */
internal class NextEpisodeEnricher(
  private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
  private val clock: () -> Long = System::currentTimeMillis
) {

  /** Stato TMDB della serie + prossima puntata annunciata (entrambi possono essere nulli). */
  data class AiringInfo(
    val status: String?,
    val nextEpisode: NextEpisodeInfo?
  )

  private val infoByTmdbId = ConcurrentHashMap<Int, AiringInfo>()
  private val requestedAtByTmdbId = ConcurrentHashMap<Int, Long>()

  /** Titolo già interrogato in questa sessione, con l'istante dell'ultima richiesta. */
  fun hasFreshInfo(item: MediaItem): Boolean {
    val tmdbId = item.tmdbId?.takeIf { it > 0 } ?: return false
    val requestedAt = requestedAtByTmdbId[tmdbId] ?: return false
    return clock() - requestedAt < refreshIntervalMs
  }

  /**
   * Titoli per cui avviare il recupero dettagli: solo serie TV con `tmdbId` valido, mai
   * interrogate (o scadute). Una sola voce per `tmdbId`, così la stessa serie ripetuta
   * in lista non genera richieste duplicate.
   */
  fun pendingTmdbIds(items: List<MediaItem>): List<Int> {
    val now = clock()
    return items.asSequence()
      .filter { it.type == MediaType.SERIE_TV }
      .mapNotNull { it.tmdbId?.takeIf { id -> id > 0 } }
      .filter { tmdbId ->
        val requestedAt = requestedAtByTmdbId[tmdbId] ?: return@filter true
        now - requestedAt >= refreshIntervalMs
      }
      .distinct()
      .toList()
  }

  /**
   * Registra l'esito TMDB per una serie. Va chiamata anche quando la prossima puntata è
   * assente: evita di ripetere la richiesta a ogni ricomposizione della card.
   */
  fun remember(tmdbId: Int, status: String?, nextEpisode: NextEpisodeInfo?) {
    if (tmdbId <= 0) return
    infoByTmdbId[tmdbId] = AiringInfo(status = status, nextEpisode = nextEpisode)
    requestedAtByTmdbId[tmdbId] = clock()
  }

  /** Marca la serie come "richiesta in corso", così richieste concorrenti non si duplicano. */
  fun markRequested(tmdbId: Int) {
    if (tmdbId <= 0) return
    requestedAtByTmdbId[tmdbId] = clock()
  }

  /** Annulla la marca dopo un errore (es. rete assente): la serie potrà essere ritentata. */
  fun forgetRequest(tmdbId: Int) {
    if (infoByTmdbId[tmdbId] == null) requestedAtByTmdbId.remove(tmdbId)
  }

  /** Applica il dato noto al titolo; i titoli non ancora interrogati restano invariati. */
  fun applyTo(item: MediaItem): MediaItem {
    val tmdbId = item.tmdbId?.takeIf { it > 0 } ?: return item
    val info = infoByTmdbId[tmdbId] ?: return item
    return item.copy(tvStatus = info.status, nextEpisode = info.nextEpisode)
  }

  fun applyTo(items: List<MediaItem>): List<MediaItem> = items.map(::applyTo)

  /** Svuota il registro (usato dalla pulizia cache: il dato va riletto da TMDB). */
  fun clear() {
    infoByTmdbId.clear()
    requestedAtByTmdbId.clear()
  }

  companion object {
    /**
     * Allineato al TTL della cache dettagli TMDB (12h): entro questa finestra il badge
     * riusa il dato già recuperato, oltre viene riletto rispettando la cache esistente.
     */
    const val DEFAULT_REFRESH_INTERVAL_MS: Long = 12 * 60 * 60 * 1000L
  }
}
