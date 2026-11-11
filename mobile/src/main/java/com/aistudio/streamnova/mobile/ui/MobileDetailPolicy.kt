package com.aistudio.streamnova.mobile.ui

import androidx.annotation.StringRes
import com.example.data.model.CastMember
import com.example.data.model.Episode
import com.example.data.model.ExtendedMediaDetails
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SeasonItem
import com.example.data.model.toEpisode

/**
 * Stato del trailer interno alla scheda dettaglio.
 *
 * Come sulla TV il trailer non apre mai un'Activity esterna: [Playing] porta la
 * chiave YouTube reale ottenuta da TMDB, [Error] è il percorso "trailer non
 * disponibile" e viene mostrato un overlay dedicato.
 */
sealed interface MobileTrailerState {
  data object Idle : MobileTrailerState
  data object Loading : MobileTrailerState
  data class Playing(val youtubeKey: String) : MobileTrailerState
  data object Error : MobileTrailerState
}

/**
 * Metadati della scheda già normalizzati e privi di valori di comodo.
 *
 * Ogni campo è `null` quando la sorgente non lo fornisce: la UI omette il badge
 * invece di mostrare uno zero o un valore inventato.
 */
data class MobileDetailMetadata(
  val year: Int? = null,
  val durationMinutes: Int? = null,
  val seasonsCount: Int? = null,
  val episodesCount: Int? = null,
  val ageRating: Int? = null,
  val rating: Float? = null,
  val genres: List<String> = emptyList(),
  val tagline: String? = null
)

/**
 * Stato della schermata Dettaglio Mobile.
 *
 * [media] è sempre la copia arricchita dal dettaglio TMDB quando disponibile, con
 * lo stato utente (preferiti, progresso titolo, ultima visione) sovrapposto dalla
 * lista condivisa. [details] contiene esclusivamente il risultato reale di TMDB;
 * quando manca, la UI resta sulla scheda base e mostra l'errore parziale.
 */
data class MobileDetailUiState(
  val media: MediaItem? = null,
  val details: ExtendedMediaDetails? = null,
  val isLoadingDetails: Boolean = false,
  @StringRes val detailsError: Int? = null,
  val isFavorite: Boolean = false,
  val seasons: List<Int> = emptyList(),
  val selectedSeason: Int? = null,
  val season: SeasonItem? = null,
  val episodes: List<Episode> = emptyList(),
  val episodesLoading: Boolean = false,
  @StringRes val episodesError: Int? = null,
  val simklWatchedEpisodes: Set<Int> = emptySet(),
  val trailer: MobileTrailerState = MobileTrailerState.Idle,
  val notFound: Boolean = false
) {
  val isSeries: Boolean
    get() = media?.type == MediaType.SERIE_TV

  /** Come la TV: "visto" è lo stato del titolo (progresso >= 90% della durata). */
  val isWatched: Boolean
    get() = media?.isWatched == true

  val logoUrl: String?
    get() = MobileDetailPolicy.logoUrl(media, details)

  val metadata: MobileDetailMetadata
    get() = MobileDetailPolicy.metadata(media, details)

  val cast: List<CastMember>
    get() = MobileDetailPolicy.cast(media, details)

  val hasEpisodeContent: Boolean
    get() = episodes.isNotEmpty()
}

/**
 * Regole pure della scheda Dettaglio Mobile, riusate da ViewModel e test JVM.
 *
 * Non contengono dipendenze Android: tutta la logica di mapping, fallback e
 * stato è verificabile senza emulatore.
 */
object MobileDetailPolicy {

  fun logoUrl(media: MediaItem?, details: ExtendedMediaDetails?): String? =
    details?.logoUrl?.takeIf { it.isNotBlank() }
      ?: media?.logoUrl?.takeIf { it.isNotBlank() }

  fun metadata(media: MediaItem?, details: ExtendedMediaDetails?): MobileDetailMetadata {
    if (media == null && details == null) return MobileDetailMetadata()
    val isSeries = (details?.isTv == true) || media?.type == MediaType.SERIE_TV
    val duration = if (isSeries) {
      null
    } else {
      details?.durationMinutes?.takeIf { it > 0 }
        ?: media?.durationMinutes?.takeIf { it > 0 }
    }
    return MobileDetailMetadata(
      year = details?.releaseYear?.takeIf { it > 0 }
        ?: media?.year?.takeIf { it > 0 },
      durationMinutes = duration,
      seasonsCount = if (isSeries) {
        details?.seasonsCount?.takeIf { it > 0 }
          ?: media?.seasonsCount?.takeIf { it > 0 }
      } else null,
      episodesCount = if (isSeries) details?.episodesCount?.takeIf { it > 0 } else null,
      ageRating = details?.ageRating ?: media?.ageRating,
      rating = details?.rating?.takeIf { it > 0f }
        ?: media?.tmdbRating?.takeIf { it > 0f },
      genres = details?.genres?.takeIf { it.isNotEmpty() }
        ?: media?.genres.orEmpty(),
      tagline = details?.tagline?.takeIf { it.isNotBlank() }
    )
  }

  /**
   * Stesso ordine della TV: regista/creatore in testa, poi il cast TMDB. Se il
   * dettaglio esteso non è ancora disponibile si usa il cast della scheda base,
   * senza inventare ruoli oltre al generico "Cast principale" previsto dalla TV.
   */
  fun cast(media: MediaItem?, details: ExtendedMediaDetails?): List<CastMember> {
    if (media == null && details == null) return emptyList()
    val director = details?.directorMember
      ?: media?.director?.takeIf { it.isNotBlank() }?.let { name ->
        CastMember(
          id = -1,
          name = name,
          character = if (media?.type == MediaType.SERIE_TV) "Creatore" else "Regista",
          profileUrl = null
        )
      }
    val rawCast = details?.cast?.takeIf { it.isNotEmpty() }
      ?: media?.cast.orEmpty().mapIndexed { index, name ->
        CastMember(
          id = index + 1000,
          name = name,
          character = "Cast principale",
          profileUrl = null
        )
      }
    return if (director != null) {
      listOf(director) + rawCast.filter {
        it.id != director.id && !it.name.equals(director.name, ignoreCase = true)
      }
    } else {
      rawCast
    }
  }

  /**
   * Stagioni realmente note: conteggio dichiarato da TMDB, mappe episodi per
   * stagione ed episodi già caricati. Nessun intervallo viene inventato quando
   * TMDB non dichiara il numero di stagioni: in quel caso si mostrano solo le
   * stagioni reali già note (es. quelle di `seasonEpisodesCount`).
   */
  fun seasons(
    media: MediaItem?,
    details: ExtendedMediaDetails?,
    selectedSeason: Int? = null
  ): List<Int> {
    val known = buildSet {
      media?.episodes?.forEach { if (it.seasonNumber >= 0) add(it.seasonNumber) }
      media?.seasonEpisodesCount?.keys?.filter { it >= 0 }?.let(::addAll)
      details?.seasonEpisodesCount?.keys?.filter { it >= 0 }?.let(::addAll)
      media?.lastWatchedSeason?.takeIf { it >= 0 }?.let(::add)
    }
    val declaredCount = listOfNotNull(details?.seasonsCount, media?.seasonsCount)
      .filter { it > 0 }
      .maxOrNull()
    val maxKnown = known.filter { it > 0 }.maxOrNull() ?: 0
    val numbered = if (declaredCount != null) {
      (1..maxOf(declaredCount, maxKnown)).toList()
    } else {
      known.filter { it > 0 }.sorted()
    }
    val includeZero = 0 in known || selectedSeason == 0
    return if (includeZero) listOf(0) + numbered else numbered
  }

  /** Ultima stagione vista se ancora disponibile, altrimenti la prima reale. */
  fun initialSeason(media: MediaItem?, seasons: List<Int>): Int? =
    media?.lastWatchedSeason?.takeIf { it in seasons }
      ?: seasons.firstOrNull { it > 0 }
      ?: seasons.firstOrNull()

  /**
   * Converte gli episodi TMDB nella lista UI. La posizione salvata e la durata
   * realmente misurata dal player hanno priorità sul runtime dichiarato.
   */
  fun mapEpisodes(
    media: MediaItem,
    seasonItem: SeasonItem,
    progressOf: (season: Int, episode: Int) -> Long,
    durationOf: (season: Int, episode: Int) -> Long
  ): List<Episode> = withProgress(
    media = media,
    episodes = seasonItem.episodes.map { item ->
      // `EpisodeItem.toEpisode` storico impone un minimo di 15 minuti. In modalità
      // strict quel minimo sarebbe una durata inventata: qui si conserva il valore
      // reale (anche 0) e si lascia a `withProgress` l'eventuale durata misurata
      // dal player.
      item.toEpisode(
        seriesId = media.id,
        progressMs = 0L,
        seriesImdbId = media.imdbId
      ).copy(
        durationMinutes = item.durationMinutes,
        totalDurationMs = (item.durationMinutes.coerceAtLeast(0) * 60_000L)
      )
    },
    progressOf = progressOf,
    durationOf = durationOf
  )

  /**
   * Applica progresso e durata reali a episodi già noti (cache catalogo o
   * stagione già caricata), senza generarne di nuovi.
   */
  fun withProgress(
    media: MediaItem,
    episodes: List<Episode>,
    progressOf: (season: Int, episode: Int) -> Long,
    durationOf: (season: Int, episode: Int) -> Long
  ): List<Episode> = episodes.map { episode ->
    val progress = progressOf(episode.seasonNumber, episode.episodeNumber).coerceAtLeast(0L)
    val measuredDuration = durationOf(episode.seasonNumber, episode.episodeNumber)
    val title = episode.title.takeIf { it.isNotBlank() } ?: ""
    episode.copy(
      title = title,
      seriesImdbId = episode.seriesImdbId?.takeIf { it.isNotBlank() } ?: media.effectiveImdbId,
      currentProgressMs = progress,
      totalDurationMs = if (measuredDuration > 0L) measuredDuration else episode.totalDurationMs,
      rating = episode.rating?.takeIf { it > 0f }
    )
  }

  /** Episodi già presenti nella scheda condivisa per la stagione richiesta. */
  fun cachedEpisodes(media: MediaItem, season: Int): List<Episode> =
    media.episodes.filter { it.seasonNumber == season }

  /**
   * Sovrappone alla scheda arricchita lo stato utente emesso dalla lista
   * condivisa, lasciando intatti i metadati estesi già caricati.
   */
  fun mergeRepositoryMedia(
    state: MobileDetailUiState,
    updated: MediaItem,
    progressOf: (season: Int, episode: Int) -> Long,
    durationOf: (season: Int, episode: Int) -> Long
  ): MobileDetailUiState {
    val current = state.media ?: return state
    if (current.id != updated.id) return state
    val merged = current.copy(
      currentProgressMs = updated.currentProgressMs,
      totalDurationMs = updated.totalDurationMs,
      lastWatchedSeason = updated.lastWatchedSeason,
      lastWatchedEpisode = updated.lastWatchedEpisode,
      lastWatchedAt = updated.lastWatchedAt,
      isFavorite = updated.isFavorite
    )
    return state.copy(
      media = merged,
      isFavorite = updated.isFavorite,
      episodes = withProgress(state.media, state.episodes, progressOf, durationOf)
    )
  }

  /**
   * Target del toggle "visto", identico alla TV: state del titolo (progresso al
   * 100%) oppure azzeramento se già visto. Per le serie è lo stato del titolo,
   * non la somma degli episodi.
   */
  fun watchedTargetProgress(media: MediaItem): Long =
    if (media.isWatched) 0L else media.totalDurationMs.coerceAtLeast(1L)

  /**
   * La selezione di una nuova stagione invalida subito gli episodi precedenti:
   * la UI mostra il loading della stagione richiesta, mai dati della precedente.
   */
  fun selectSeason(state: MobileDetailUiState, season: Int): MobileDetailUiState {
    if (state.media?.type != MediaType.SERIE_TV) return state
    return state.copy(
      selectedSeason = season,
      season = null,
      episodes = emptyList(),
      episodesLoading = true,
      episodesError = null,
      simklWatchedEpisodes = emptySet()
    )
  }

  /**
   * true quando il pulsante principale di una serie può mostrare una coppia
   * SxEy reale: esiste una puntata caricata oppure una posizione salvata.
   * Per le serie mai avviate e senza episodi la UI resta sul generico "Guarda".
   */
  fun hasResolvableSeriesTarget(state: MobileDetailUiState): Boolean {
    if (state.media?.type != MediaType.SERIE_TV) return false
    return state.episodes.isNotEmpty() ||
      (state.media?.lastWatchedSeason != null && state.media?.lastWatchedEpisode != null)
  }

  /**
   * Sfocatura anti-spoiler della miniatura episodio nella scheda di una serie.
   *
   * Usa la STESSA preferenza della riga «Continua a guardare» (`blurUnwatched`), ma
   * con una regola per episodio: si sfocano solo le puntate non ancora viste, cioè
   * senza progresso e non concluse. Un episodio appena iniziato (in corso) o già
   * guardato resta nitido, così la sfocatura non nasconde il punto di ripresa; un
   * episodio marcato visto su SIMKL è trattato come visto anche se il progresso
   * locale non è ancora stato scritto.
   *
   * La preferenza spenta lascia sempre la miniatura nitida: nessun comportamento
   * viene dedotto da altri flag (miniature nel Continua a guardare incluse).
   */
  fun shouldBlurEpisodeThumbnail(
    episode: Episode?,
    blurUnwatched: Boolean,
    isSimklWatched: Boolean
  ): Boolean {
    if (episode == null || !blurUnwatched || isSimklWatched) return false
    return !episode.isCompleted && episode.currentProgressMs <= 0L
  }

  /** Conteggio episodi reale della stagione, oppure `null` se non ancora noto. */
  fun seasonEpisodeCount(
    media: MediaItem?,
    details: ExtendedMediaDetails?,
    season: Int,
    loadedEpisodes: List<Episode> = emptyList(),
    loadedSeason: Int? = null,
    episodesLoaded: Boolean = false
  ): Int? {
    details?.seasonEpisodesCount?.get(season)?.let { return it }
    media?.seasonEpisodesCount?.get(season)?.let { return it }
    if (!episodesLoaded) return null
    if (loadedSeason != null && loadedSeason != season) return null
    return loadedEpisodes.size
  }

  /**
   * Mappatura del risultato del recupero trailer: una chiave reale apre il
   * player interno, l'assenza di chiave produce lo stato di errore della TV.
   */
  fun trailerStateForKey(key: String?): MobileTrailerState =
    if (key.isNullOrBlank()) MobileTrailerState.Error else MobileTrailerState.Playing(key)
}
