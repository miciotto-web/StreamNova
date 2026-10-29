package com.example.data.repository

/**
 * Informazioni TMDB necessarie per stabilire se una serie è davvero conclusa.
 *
 * Non bastano il numero di stagioni e la stagione dell'ultima visione: serve il
 * conteggio degli episodi della stagione finale e lo stato della serie. Quando un
 * dato manca la serie NON viene considerata conclusa (nessuna rimozione basata su
 * supposizioni).
 */
data class SeriesCompletionFacts(
  /** `number_of_seasons` dichiarato da TMDB (può includere stagioni annunciate). */
  val seasonsCount: Int? = null,
  /** `seasons[].episode_count` di TMDB: stagione → episodi previsti (inclusi i futuri). */
  val seasonEpisodeCounts: Map<Int, Int> = emptyMap(),
  /** `status` TMDB: `Ended`, `Canceled`, `Returning Series`, ... */
  val status: String? = null
)

/** Esito della verifica di conclusione di una serie. */
sealed interface SeriesCompletionDecision {
  /** La serie è conclusa: l'ultimo episodio previsto è stato visto. */
  data object Completed : SeriesCompletionDecision

  /** La serie NON è conclusa (o non è verificabile), con il motivo diagnostico. */
  data class Incomplete(val reason: String) : SeriesCompletionDecision
}

/**
 * Decide se una serie può essere rimossa da «Continua a guardare» perché davvero
 * completata.
 *
 * Regole (tutte necessarie):
 *  1. la stagione/episodio visti sono l'ULTIMA coppia prevista da TMDB;
 *  2. non esiste una stagione successiva (né con episodi, né solo dichiarata);
 *  3. lo stato della serie è terminale (`Ended`/`Canceled`): una serie ancora in
 *     corso può avere episodi futuri, quindi resta in «Continua a guardare»;
 *  4. ogni dato è noto e coerente. Se il completamento non è verificabile si
 *     conserva la serie.
 *
 * Funzione pura, verificabile nei test JVM.
 */
object SeriesCompletionPolicy {

  /** Stati TMDB che indicano che non arriveranno altri episodi. */
  val TERMINAL_STATUSES = setOf("ended", "canceled", "cancelled")

  fun decide(
    seasonNumber: Int?,
    episodeNumber: Int?,
    facts: SeriesCompletionFacts?
  ): SeriesCompletionDecision {
    if (seasonNumber == null || seasonNumber <= 0) {
      return SeriesCompletionDecision.Incomplete("nessuna stagione salvata")
    }
    if (episodeNumber == null || episodeNumber <= 0) {
      return SeriesCompletionDecision.Incomplete("nessun episodio salvato")
    }
    if (facts == null) {
      return SeriesCompletionDecision.Incomplete("metadati non disponibili")
    }

    val counts = facts.seasonEpisodeCounts.filter { (season, count) -> season > 0 && count > 0 }
    val declaredSeasons = facts.seasonsCount?.takeIf { it > 0 }
    if (counts.isEmpty() && declaredSeasons == null) {
      return SeriesCompletionDecision.Incomplete("conteggi stagioni non disponibili")
    }

    // La stagione finale è la più alta dichiarata da TMDB. Se `number_of_seasons`
    // supera le stagioni con episodi significa che esiste un'altra stagione
    // (annunciata): la serie non è conclusa.
    val lastSeasonWithEpisodes = counts.keys.maxOrNull() ?: 0
    val lastSeason = maxOf(lastSeasonWithEpisodes, declaredSeasons ?: 0)
    if (seasonNumber < lastSeason) {
      return SeriesCompletionDecision.Incomplete("esiste una stagione successiva")
    }
    if (seasonNumber > lastSeason) {
      return SeriesCompletionDecision.Incomplete("posizione oltre le stagioni note")
    }
    val lastEpisodeCount = counts[lastSeason]
      ?: return SeriesCompletionDecision.Incomplete("episodi della stagione finale non noti")
    if (episodeNumber < lastEpisodeCount) {
      return SeriesCompletionDecision.Incomplete("esistono episodi successivi")
    }
    if (episodeNumber > lastEpisodeCount) {
      return SeriesCompletionDecision.Incomplete("posizione oltre gli episodi noti")
    }

    val status = facts.status?.trim()?.lowercase().orEmpty()
    if (status !in TERMINAL_STATUSES) {
      return SeriesCompletionDecision.Incomplete("serie non conclusa (stato: ${status.ifBlank { "sconosciuto" }})")
    }
    return SeriesCompletionDecision.Completed
  }
}
