package com.example.data.repository

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.WatchedEpisodePoint
import com.example.data.prefs.ContinueWatchingLayout
import com.example.data.prefs.ContinueWatchingPreferences
import com.example.data.prefs.ContinueWatchingSort
import com.example.data.util.ReleaseDateUtils
import java.util.Locale

/**
 * Card pronta per la riga «Continua a guardare».
 *
 * [media] è una COPIA del titolo con stagione/episodio/posizione del punto di ripresa
 * scelto: le card e [com.example.ui.viewmodel.StreamNovaViewModel.playFromContinueWatching]
 * continuano a leggere gli stessi campi di sempre, senza un secondo percorso di
 * risoluzione dell'episodio.
 *
 * [artworkUrl] è l'immagine da mostrare (miniatura episodio, backdrop o locandina),
 * [blurArtwork] indica se applicare la sfocatura anti-spoiler e
 * [targetEpisodeUnwatched] se il punto di ripresa è una puntata mai vista.
 */
data class ContinueWatchingRowItem(
  val media: MediaItem,
  val artworkUrl: String?,
  val blurArtwork: Boolean,
  val targetEpisodeUnwatched: Boolean
)

/**
 * Costruzione UNICA della riga «Continua a guardare» a partire dai dati del catalogo e
 * dalle preferenze della sezione Impostazioni.
 *
 * Perché esiste: la Home non deve replicare regole di visibilità, ripresa, immagine,
 * sfocatura, episodi futuri e ordinamento. Questa classe è pura (nessuna dipendenza da
 * Android/Room/rete) e riceve il punto più avanzato della visione tramite
 * [furthestWatched], così è verificabile nei test JVM e il repository resta l'unica
 * fonte dei progressi per episodio.
 *
 * COMPORTAMENTO PER PREFERENZA
 *  - `showRow = false`: lista vuota (la Home non compone la riga).
 *  - `layout`: non cambia i dati; decide solo l'immagine di base (backdrop per Scheda e
 *    Larga, locandina per Locandina) ed è applicato dalla card.
 *  - `useEpisodeThumbnails`: se il punto di ripresa è un episodio con una miniatura
 *    valida, quella sostituisce l'immagine di base; altrimenti si usa il fallback già
 *    previsto (`backdropUrl`/`posterUrl`). Nessuna immagine inventata.
 *  - `blurUnwatched`: sfoca SOLO le card di serie il cui punto di ripresa non è ancora
 *    stato visto (progresso 0 o episodio non ancora trasmesso). Un episodio in corso o
 *    già visto non viene mai sfocato, e i film restano sempre nitidi.
 *  - `nextEpisodeFromFurthest`: OFF usa l'episodio guardato più di recente (comportamento
 *    storico, adatto ai rewatch). ON usa il punto più avanzato della visione e, se è una
 *    puntata conclusa, passa alla successiva conosciuta.
 *  - `showUnairedEpisodes`: controlla se il punto di ripresa può essere una puntata non
 *    ancora trasmessa. ON la include; OFF torna all'episodio guardato. La regola si
 *    applica SOLO quando esiste una data di messa in onda ISO affidabile: senza data il
 *    titolo non viene mai nascosto né spostato.
 *  - `sort`: applica il criterio scelto alla lista finale. Le modalità esposte
 *    nell'enum sono tutte implementate qui.
 */
object ContinueWatchingRowPolicy {

  /** Soglia con cui un episodio è considerato concluso (coerente con il resto dell'app). */
  private const val COMPLETED_FRACTION = 0.9

  /**
   * @param items catalogo completo della Home (`allMedia`).
   * @param preferences snapshot delle preferenze della sezione Impostazioni.
   * @param furthestWatched punto più avanzato per `mediaId` (in produzione
   *        [MediaRepository.furthestWatchedEpisode]). Default `null`: nessun progresso
   *        per episodio noto, quindi si usa lo stato salvato del titolo.
   * @param todayIso data odierna `yyyy-MM-dd` per il confronto con le date di messa in onda.
   */
  fun build(
    items: List<MediaItem>,
    preferences: ContinueWatchingPreferences,
    furthestWatched: (String) -> WatchedEpisodePoint? = { null },
    todayIso: String = ReleaseDateUtils.todayIso()
  ): List<ContinueWatchingRowItem> {
    if (!preferences.showRow || items.isEmpty()) return emptyList()

    // Una card per TITOLO: la deduplicazione esistente resta il punto unico.
    val deduped = MediaIdentity.dedupeByProgress(items)
    val prepared = deduped.mapNotNull { item ->
      prepareItem(item, items, preferences, furthestWatched, todayIso)
    }
    return sort(prepared, preferences.sort)
  }

  private fun prepareItem(
    item: MediaItem,
    allItems: List<MediaItem>,
    preferences: ContinueWatchingPreferences,
    furthestWatched: (String) -> WatchedEpisodePoint?,
    todayIso: String
  ): ContinueWatchingRowItem? {
    val saved = if (item.type == MediaType.SERIE_TV) savedPoint(item) else null
    // Punto più avanzato della visione (indipendente dall'episodio scelto come target):
    // resta il ripiego naturale quando una puntata futura non va mostrata.
    val furthest = if (item.type == MediaType.SERIE_TV && preferences.nextEpisodeFromFurthest) {
      furthestPoint(item, allItems, furthestWatched)
    } else {
      saved
    }
    val selected = if (item.type == MediaType.SERIE_TV && preferences.nextEpisodeFromFurthest) {
      furthest?.let { reference ->
        if (reference.isCompleted(item)) nextAfter(item, reference) ?: reference else reference
      } ?: saved
    } else {
      saved
    }

    // Film (o serie senza stato di ripresa utilizzabile): la card resta quella di sempre.
    if (selected == null) {
      return ContinueWatchingRowItem(
        media = item,
        artworkUrl = baseArtwork(item, preferences.layout),
        blurArtwork = false,
        targetEpisodeUnwatched = false
      )
    }

    var target = selected
    var episode = findEpisode(item, target.seasonNumber, target.episodeNumber)
    var unreleased = isUnreleased(item, target, episode, todayIso)

    if (unreleased && !preferences.showUnairedEpisodes) {
      // La puntata futura non va mostrata: si torna all'episodio già guardato più
      // avanzato (o a quello salvato), non alla puntata futura. Senza un ripiego
      // verificabile la card sparisce fino alla messa in onda (niente date inventate).
      target = listOfNotNull(furthest, saved)
        .firstOrNull { it.seasonNumber != selected.seasonNumber || it.episodeNumber != selected.episodeNumber }
        ?.takeUnless { candidate ->
          isUnreleased(item, candidate, findEpisode(item, candidate.seasonNumber, candidate.episodeNumber), todayIso)
        }
        ?: return null
      episode = findEpisode(item, target.seasonNumber, target.episodeNumber)
      unreleased = isUnreleased(item, target, episode, todayIso)
      if (unreleased) return null
    }

    val episodeDuration = target.durationMs.takeIf { it > 0L }
      ?: episode?.totalDurationMs?.takeIf { it > 0L }
      ?: item.totalDurationMs
    val media = item.copy(
      lastWatchedSeason = target.seasonNumber,
      lastWatchedEpisode = target.episodeNumber,
      currentProgressMs = target.progressMs.coerceAtLeast(0L),
      totalDurationMs = episodeDuration.coerceAtLeast(0L)
    )
    val unwatched = target.progressMs <= 0L || unreleased
    return ContinueWatchingRowItem(
      media = media,
      artworkUrl = artwork(item, episode, preferences),
      blurArtwork = preferences.blurUnwatched && unwatched,
      targetEpisodeUnwatched = unwatched
    )
  }

  /** Episodio guardato più di recente, con posizione e durata dell'episodio quando note. */
  private fun savedPoint(item: MediaItem): ResumePoint? {
    val season = item.lastWatchedSeason?.takeIf { it >= 0 } ?: return null
    val episode = item.lastWatchedEpisode?.takeIf { it > 0 } ?: return null
    val episodeDuration = findEpisode(item, season, episode)?.totalDurationMs?.takeIf { it > 0L }
    return ResumePoint(
      seasonNumber = season,
      episodeNumber = episode,
      progressMs = item.currentProgressMs.coerceAtLeast(0L),
      durationMs = episodeDuration ?: item.totalDurationMs.coerceAtLeast(0L)
    )
  }

  /**
   * Punto più avanzato della visione per il titolo.
   *
   * Confronta lo stato salvato con il punto più avanzato per episodio (stessa identità
   * forte del titolo: le schede gemelle TMDB/IMDb non perdono progressi). Il chiamante
   * decide poi se passare alla puntata successiva quando questo punto è concluso.
   */
  private fun furthestPoint(
    item: MediaItem,
    allItems: List<MediaItem>,
    furthestWatched: (String) -> WatchedEpisodePoint?
  ): ResumePoint? {
    val saved = savedPoint(item)
    val groupIds = MediaIdentity.strongGroup(allItems, item).map { it.id } + item.id
    val furthest = groupIds.asSequence()
      .distinct()
      .mapNotNull { id -> furthestWatched(id)?.takeIf { it.progressMs > 0L } }
      .maxWithOrNull(compareBy({ it.seasonNumber }, { it.episodeNumber }))
      ?.let { point ->
        ResumePoint(
          seasonNumber = point.seasonNumber,
          episodeNumber = point.episodeNumber,
          progressMs = point.progressMs,
          durationMs = point.durationMs
        )
      }
    return listOfNotNull(saved, furthest)
      .maxWithOrNull(compareBy({ it.seasonNumber }, { it.episodeNumber }))
  }

  /**
   * Puntata successiva al punto [reference], solo da dati disponibili:
   * elenco episodi → numero di episodi noti della stagione → prossima puntata TMDB.
   * `null` quando non esiste un successivo verificabile (si resta sulla puntata corrente).
   */
  private fun nextAfter(item: MediaItem, reference: ResumePoint): ResumePoint? {
    val pool = item.episodes
      .filter { it.seasonNumber >= 0 && it.episodeNumber > 0 }
      .distinctBy { it.seasonNumber to it.episodeNumber }
      .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    val index = pool.indexOfFirst {
      it.seasonNumber == reference.seasonNumber && it.episodeNumber == reference.episodeNumber
    }
    if (index >= 0 && index + 1 < pool.size) {
      val next = pool[index + 1]
      return ResumePoint(
        seasonNumber = next.seasonNumber,
        episodeNumber = next.episodeNumber,
        progressMs = 0L,
        durationMs = next.totalDurationMs.coerceAtLeast(0L)
      )
    }

    val seasonCount = item.seasonEpisodesCount[reference.seasonNumber]
    if (seasonCount != null && reference.episodeNumber < seasonCount) {
      return ResumePoint(
        seasonNumber = reference.seasonNumber,
        episodeNumber = reference.episodeNumber + 1,
        progressMs = 0L,
        durationMs = 0L
      )
    }

    val announced = item.nextEpisode
    if (announced != null &&
      (announced.seasonNumber > reference.seasonNumber ||
        (announced.seasonNumber == reference.seasonNumber && announced.episodeNumber > reference.episodeNumber))
    ) {
      return ResumePoint(
        seasonNumber = announced.seasonNumber,
        episodeNumber = announced.episodeNumber,
        progressMs = 0L,
        durationMs = 0L
      )
    }
    return null
  }

  /**
   * true solo se il punto di ripresa è un episodio con una data di messa in onda
   * `yyyy-MM-dd` valida e successiva a oggi. La data arriva dall'episodio TMDB oppure da
   * `next_episode_to_air` quando coincide esattamente con la puntata scelta.
   */
  private fun isUnreleased(
    item: MediaItem,
    target: ResumePoint,
    episode: Episode?,
    todayIso: String
  ): Boolean {
    val announced = item.nextEpisode
      ?.takeIf { it.seasonNumber == target.seasonNumber && it.episodeNumber == target.episodeNumber }
      ?.airDate
    val rawDate = episode?.airDate?.takeIf { it.isNotBlank() } ?: announced
    val isoDate = ReleaseDateUtils.normalizeIsoDate(rawDate) ?: return false
    return ReleaseDateUtils.isAfter(isoDate, todayIso)
  }

  /** Immagine di base della card quando non si usa la miniatura dell'episodio. */
  private fun baseArtwork(item: MediaItem, layout: ContinueWatchingLayout): String? {
    val poster = item.posterUrl?.takeIf { it.isNotBlank() }
    val backdrop = item.backdropUrl?.takeIf { it.isNotBlank() }
    // La locandina è il formato naturale del layout verticale; gli altri layout usano
    // lo sfondo. Il ripiego resta l'altra immagine del titolo.
    return when (layout) {
      ContinueWatchingLayout.POSTER -> poster ?: backdrop
      ContinueWatchingLayout.CARD,
      ContinueWatchingLayout.WIDE -> backdrop ?: poster
    }
  }

  /**
   * Miniatura dell'episodio (se disponibile) oppure immagine di base. La miniatura è
   * usata solo con la preferenza attiva e solo se è una still SPECIFICA della puntata:
   * i generatori locali e il fallback di TMDB usano il backdrop/poster della serie come
   * segnaposto, quindi quei valori non vanno trattati come miniature (altrimenti il
   * layout Locandina mostrerebbe un backdrop al posto della locandina). Nessun ripiego
   * "inventato" quando TMDB non espone lo still della puntata.
   */
  private fun artwork(
    item: MediaItem,
    episode: Episode?,
    preferences: ContinueWatchingPreferences
  ): String? {
    if (preferences.useEpisodeThumbnails) {
      val still = episode?.thumbnailUrl?.takeIf { it.isNotBlank() }
      val backdrop = item.backdropUrl?.takeIf { it.isNotBlank() }
      val poster = item.posterUrl?.takeIf { it.isNotBlank() }
      if (still != null && still != backdrop && still != poster) return still
    }
    return baseArtwork(item, preferences.layout)
  }

  private fun findEpisode(item: MediaItem, season: Int, episode: Int): Episode? =
    item.episodes.firstOrNull {
      it.seasonNumber == season && it.episodeNumber == episode
    }

  /** Applica l'ordinamento scelto; a parità vince sempre la visione più recente. */
  private fun sort(
    items: List<ContinueWatchingRowItem>,
    sort: ContinueWatchingSort
  ): List<ContinueWatchingRowItem> {
    val byRecent = compareByDescending<ContinueWatchingRowItem> { it.media.lastWatchedAt ?: 0L }
    return when (sort) {
      ContinueWatchingSort.DEFAULT -> items.sortedWith(byRecent)
      ContinueWatchingSort.TITLE -> items.sortedWith(
        compareBy<ContinueWatchingRowItem> { it.media.title.lowercase(Locale.ROOT) }.then(byRecent)
      )
      ContinueWatchingSort.RATING -> items.sortedWith(
        compareByDescending<ContinueWatchingRowItem> { it.media.rating }.then(byRecent)
      )
    }
  }

  private data class ResumePoint(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val progressMs: Long,
    val durationMs: Long
  ) {
    /**
     * Concluso solo con una durata verificabile: `progress >= 90%`. Una durata
     * sconosciuta non prova nulla e non deve far avanzare la puntata.
     */
    fun isCompleted(item: MediaItem): Boolean {
      val known = durationMs.takeIf { it > 0L }
        ?: findEpisode(item, seasonNumber, episodeNumber)?.totalDurationMs?.takeIf { it > 0L }
        ?: return false
      return progressMs >= (known * COMPLETED_FRACTION)
    }
  }
}
