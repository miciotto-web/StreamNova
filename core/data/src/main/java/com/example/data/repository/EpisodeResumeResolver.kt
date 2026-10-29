package com.example.data.repository

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType

/**
 * Risoluzione dell'episodio da riprodurre partendo dallo stato salvato del titolo
 * (`lastWatchedSeason` / `lastWatchedEpisode`).
 *
 * La posizione salvata è **autorevole**: se l'episodio non è presente nella lista
 * locale — che per le serie di catalogo è spesso generata e troncata alle prime
 * stagioni, oppure non è ancora stata caricata da TMDB — NON si ripiega sul primo
 * episodio disponibile. Si costruisce un [Episode] con gli stessi numeri di
 * stagione/episodio, così riprendere una serie da S5E2 non può più avviare S1E1.
 *
 * Il ripiego al primo episodio resta solo per i titoli **mai avviati** (nessuna
 * posizione salvata), dove qualunque puntata va bene.
 *
 * Logica pura: nessuna dipendenza da Android/Room, verificabile nei test JVM.
 */
object EpisodeResumeResolver {

  /**
   * @param media titolo (serie TV) di cui risolvere l'episodio.
   * @param requestedSeason stagione esplicitamente richiesta (click su una puntata).
   * @param requestedEpisode episodio esplicitamente richiesto (click su una puntata).
   *        Hanno precedenza sullo stato salvato solo quando ENTRAMBI sono validi.
   * @param extraEpisodes episodi aggiuntivi già caricati (es. stagione selezionata
   *        nella scheda dettaglio) uniti a `media.episodes` per la ricerca esatta.
   * @param savedProgressMs posizione salvata dell'episodio risolto (0 se ignorata).
   * @return l'episodio da riprodurre, oppure `null` se [media] non è una serie TV.
   */
  fun resolve(
    media: MediaItem,
    requestedSeason: Int? = null,
    requestedEpisode: Int? = null,
    extraEpisodes: List<Episode> = emptyList(),
    savedProgressMs: Long = 0L
  ): Episode? {
    if (media.type != MediaType.SERIE_TV) return null

    val pool = (media.episodes + extraEpisodes)
      .filter { it.seasonNumber >= 0 && it.episodeNumber > 0 }
      .distinctBy { it.seasonNumber to it.episodeNumber }
      .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

    val explicit = validExplicitPosition(requestedSeason, requestedEpisode)
    val saved = validSavedPosition(media.lastWatchedSeason, media.lastWatchedEpisode)
    val target = explicit ?: saved

    // Mai avviata: si parte dalla prima puntata disponibile (o da S1E1).
    if (target == null) {
      return pool.firstOrNull()?.withSeriesIdentity(media)
        ?: syntheticEpisode(media, season = 1, episode = 1, totalDurationMs = 0L)
    }

    val found = pool.firstOrNull {
      it.seasonNumber == target.first && it.episodeNumber == target.second
    }

    // La durata nota del titolo appartiene all'episodio dell'ultima visione: usarla
    // SOLO per quello. Per un episodio diverso (click esplicito) resta quella
    // dell'episodio stesso, o 0 se sconosciuta: mai una durata inventata.
    val durationHint = if (target == saved && media.totalDurationMs > 0L) {
      media.totalDurationMs
    } else {
      0L
    }

    val base = found ?: syntheticEpisode(
      media = media,
      season = target.first,
      episode = target.second,
      totalDurationMs = durationHint
    )

    return base.copy(
      currentProgressMs = savedProgressMs.coerceAtLeast(0L),
      totalDurationMs = if (durationHint > 0L) durationHint else base.totalDurationMs,
      seriesImdbId = base.seriesImdbId?.takeIf { it.isNotBlank() } ?: media.effectiveImdbId
    )
  }

  /**
   * L'episodio sintetico conserva l'identità del titolo e la numerazione richiesta.
   * `totalDurationMs` resta 0 quando la durata reale non è nota: una durata stimata
   * farebbe scattare il riavvio da 0:00 come se l'episodio fosse già concluso.
   */
  private fun syntheticEpisode(
    media: MediaItem,
    season: Int,
    episode: Int,
    totalDurationMs: Long
  ): Episode = Episode(
    id = "${media.id}_s${season}e${episode}",
    seasonNumber = season,
    episodeNumber = episode,
    title = "Episodio $episode",
    synopsis = "",
    durationMinutes = media.durationMinutes.takeIf { it > 0 } ?: 55,
    videoUrl = media.videoUrl,
    currentProgressMs = 0L,
    totalDurationMs = totalDurationMs,
    seriesImdbId = media.effectiveImdbId
  )

  private fun Episode.withSeriesIdentity(media: MediaItem): Episode =
    if (seriesImdbId.isNullOrBlank() && !media.effectiveImdbId.isNullOrBlank()) {
      copy(seriesImdbId = media.effectiveImdbId)
    } else {
      this
    }

  private fun validExplicitPosition(season: Int?, episode: Int?): Pair<Int, Int>? {
    val s = season?.takeIf { it >= 0 } ?: return null
    val e = episode?.takeIf { it > 0 } ?: return null
    return s to e
  }

  /**
   * Una posizione salvata valida ha episodio > 0 e stagione >= 0: la stagione 0 di
   * TMDB contiene gli speciali ed è una destinazione reale. Il valore sentinella per
   * "nessun episodio" è `null` (o -1 nelle cache legacy), mai 0.
   */
  private fun validSavedPosition(season: Int?, episode: Int?): Pair<Int, Int>? {
    val s = season?.takeIf { it >= 0 } ?: return null
    val e = episode?.takeIf { it > 0 } ?: return null
    return s to e
  }
}
