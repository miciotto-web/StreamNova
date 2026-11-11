package com.aistudio.streamnova.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.streamnova.mobile.R
import com.example.data.model.Episode
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.MediaRepository
import com.example.data.repository.SimklMediaKind
import com.example.data.repository.SimklMediaPayload
import com.example.data.repository.SimklRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel della scheda Dettaglio Mobile.
 *
 * Usa le stesse sorgenti della TV 1.0.4:
 * - `MediaRepository.loadMediaDetails` per metadati estesi, logo, cast e case di
 *   produzione;
 * - `MediaRepository.getSeasonDetails` per stagioni/episodi reali da TMDB (con
 *   cache Room condivisa);
 * - `MediaRepository.mediaList` / `getEpisodeProgress` per progressi e preferiti;
 * - `SimklRepository.getWatchedEpisodes` per il badge "Visto" degli episodi;
 * - `MediaRepository.fetchTrailerKey` per il trailer interno.
 *
 * Le regole pure di mapping/stato vivono in [MobileDetailPolicy] e sono coperte
 * dai test JVM: qui restano orchestrazione, cancellazione delle richieste in
 * volo e sincronizzazione con il repository.
 */
class MobileDetailViewModel : ViewModel() {

  private val _state = MutableStateFlow(MobileDetailUiState())
  val state: StateFlow<MobileDetailUiState> = _state.asStateFlow()

  private var loadedMediaId: String? = null
  private var detailsJob: Job? = null
  private var seasonJob: Job? = null
  private var simklJob: Job? = null
  private var trailerJob: Job? = null
  private var seasonRequestId = 0

  init {
    // La scheda resta nel back stack mentre il player aggiorna il progresso:
    // senza questo collector il pulsante mostrerebbe ancora lo stato precedente
    // al ritorno dalla riproduzione.
    viewModelScope.launch {
      MediaRepository.mediaList.collect { list ->
        val current = _state.value.media ?: return@collect
        val updated = list.firstOrNull { it.id == current.id } ?: return@collect
        _state.update { state ->
          MobileDetailPolicy.mergeRepositoryMedia(
            state = state,
            updated = updated,
            progressOf = ::episodeProgress,
            durationOf = ::episodeDuration
          )
        }
      }
    }
    viewModelScope.launch {
      SimklRepository.accountState.collect { refreshSimklWatched() }
    }
    viewModelScope.launch {
      SimklRepository.watchedRevision.collect { refreshSimklWatched() }
    }
  }

  /**
   * Carica la scheda per [mediaId]. Se il titolo non è nel catalogo condiviso lo
   * stato è "non trovato": nessun contenuto demo viene costruito per riempire la
   * schermata.
   */
  fun load(mediaId: String) {
    if (loadedMediaId == mediaId && !_state.value.notFound) return
    loadedMediaId = mediaId

    val media = MediaRepository.getMediaById(mediaId)
      ?: MediaRepository.mediaList.value.firstOrNull { it.id == mediaId }
    if (media == null) {
      _state.value = MobileDetailUiState(notFound = true)
      return
    }

    val seasons = MobileDetailPolicy.seasons(media, details = null)
    val initialSeason = MobileDetailPolicy.initialSeason(media, seasons)
    _state.value = MobileDetailUiState(
      media = media,
      isFavorite = media.isFavorite,
      seasons = seasons,
      selectedSeason = initialSeason,
      episodesLoading = initialSeason != null && media.tmdbId != null
    )

    if (initialSeason != null) loadSeason(media, initialSeason)
    refreshDetails(media)
  }

  /** Riprova dopo lo stato "titolo non trovato" (es. cache ancora in caricamento). */
  fun retryLoad() {
    val mediaId = loadedMediaId ?: return
    loadedMediaId = null
    load(mediaId)
  }

  /** Riprova il recupero dei dettagli estesi TMDB. */
  fun retryDetails() {
    val media = _state.value.media ?: return
    refreshDetails(media)
  }

  /** Riprova la stagione selezionata (errore di rete o refresh manuale). */
  fun retrySeason() {
    val media = _state.value.media ?: return
    val season = _state.value.selectedSeason ?: return
    loadSeason(media, season)
  }

  /**
   * Seleziona una stagione reale. La lista della stagione precedente viene
   * svuotata subito, così non può essere mostrata sotto il nuovo titolo.
   */
  fun selectSeason(season: Int) {
    val current = _state.value
    val media = current.media ?: return
    if (media.type != MediaType.SERIE_TV) return
    if (season !in current.seasons) return
    _state.update { MobileDetailPolicy.selectSeason(it, season) }
    loadSeason(media, season)
  }

  private fun refreshDetails(media: MediaItem) {
    detailsJob?.cancel()
    _state.update { it.copy(isLoadingDetails = true, detailsError = null) }
    detailsJob = viewModelScope.launch {
      val isTv = media.type == MediaType.SERIE_TV
      try {
        // Le card Cinemeta/addon possono avere solo l'ID IMDb: come la TV si
        // risolve in TMDB prima di chiedere metadati, cast e stagioni.
        val tmdbId = media.tmdbId?.takeIf { it > 0 }
          ?: MediaRepository.resolveImdbToTmdbId(media.effectiveImdbId, isTv)
        if (tmdbId == null || tmdbId <= 0) {
          _state.update {
            it.copy(
              isLoadingDetails = false,
              detailsError = R.string.detail_details_error
            )
          }
          return@launch
        }

        val base = _state.value.media?.copy(tmdbId = tmdbId)
          ?: media.copy(tmdbId = tmdbId)
        if (_state.value.media?.id == media.id && _state.value.media?.tmdbId != tmdbId) {
          _state.update { it.copy(media = base) }
        }
        MediaRepository.loadMediaDetails(
          tmdbId = tmdbId,
          isTv = isTv,
          baseMedia = base
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (_state.value.media?.id == media.id) {
          _state.update {
            it.copy(
              isLoadingDetails = false,
              detailsError = R.string.detail_details_error
            )
          }
        }
        return@launch
      }

      if (_state.value.media?.id != media.id) return@launch
      when (val result = MediaRepository.detailUiState.value) {
        is MediaDetailUiState.Success -> {
          if (result.media.id == media.id) {
            applyDetails(result)
          } else {
            _state.update { it.copy(isLoadingDetails = false) }
          }
        }
        is MediaDetailUiState.Error -> {
          val baseMedia = result.baseMedia
          if (baseMedia == null || baseMedia.id == media.id) {
            _state.update {
              it.copy(
                isLoadingDetails = false,
                detailsError = R.string.detail_details_error
              )
            }
          } else {
            _state.update { it.copy(isLoadingDetails = false) }
          }
        }
        else -> _state.update { it.copy(isLoadingDetails = false) }
      }
    }
  }

  private fun applyDetails(result: MediaDetailUiState.Success) {
    val current = _state.value
    val detailed = result.media
    val details = result.details
    val seasons = MobileDetailPolicy.seasons(detailed, details, current.selectedSeason)
    val selected = current.selectedSeason?.takeIf { it in seasons }
      ?: MobileDetailPolicy.initialSeason(detailed, seasons)
    val selectedChanged = selected != current.selectedSeason
    val hadTmdbId = current.media?.tmdbId?.let { it > 0 } == true
    // Se il TMDB ID è stato risolto solo ora, l'eventuale errore "identificatore
    // mancante" prodotto dal primo tentativo di stagione va scartato e la stagione
    // reale deve essere ricaricata.
    val needsSeasonReload = selected != null && (selectedChanged || !hadTmdbId)

    _state.update { state ->
      state.copy(
        media = detailed,
        details = details,
        isFavorite = detailed.isFavorite,
        seasons = seasons,
        selectedSeason = selected,
        isLoadingDetails = false,
        detailsError = null,
        season = if (needsSeasonReload) null else state.season,
        episodes = if (needsSeasonReload) emptyList() else state.episodes,
        episodesLoading = if (needsSeasonReload) detailed.tmdbId != null else state.episodesLoading,
        episodesError = if (needsSeasonReload) null else state.episodesError,
        simklWatchedEpisodes = if (needsSeasonReload) emptySet() else state.simklWatchedEpisodes
      )
    }

    if (needsSeasonReload && selected != null) {
      loadSeason(detailed, selected)
    } else {
      refreshSimklWatched()
    }
  }

  private fun loadSeason(media: MediaItem, season: Int) {
    seasonJob?.cancel()
    val requestId = ++seasonRequestId
    val tmdbId = media.tmdbId?.takeIf { it > 0 }
    val cached = MobileDetailPolicy.cachedEpisodes(media, season)
    val mappedCached = MobileDetailPolicy.withProgress(
      media = media,
      episodes = cached,
      progressOf = ::episodeProgress,
      durationOf = ::episodeDuration
    )

    _state.update { state ->
      if (state.selectedSeason != season) {
        state
      } else {
        state.copy(
          season = null,
          episodes = mappedCached,
          episodesLoading = tmdbId != null,
          episodesError = null,
          simklWatchedEpisodes = emptySet()
        )
      }
    }
    refreshSimklWatched()

    if (tmdbId == null) {
      _state.update { state ->
        if (state.selectedSeason != season) {
          state
        } else {
          state.copy(
            episodesLoading = false,
            episodesError = R.string.detail_episodes_error
          )
        }
      }
      return
    }

    seasonJob = viewModelScope.launch {
      try {
        val seasonItem = MediaRepository.getSeasonDetails(tmdbId, season, media)
        if (requestId != seasonRequestId || _state.value.selectedSeason != season) return@launch
        val episodes = MobileDetailPolicy.mapEpisodes(
          media = media,
          seasonItem = seasonItem,
          progressOf = ::episodeProgress,
          durationOf = ::episodeDuration
        )
        _state.update { state ->
          if (state.selectedSeason != season) {
            state
          } else {
            state.copy(
              season = seasonItem,
              episodes = episodes,
              episodesLoading = false,
              episodesError = null
            )
          }
        }
        refreshSimklWatched()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (requestId != seasonRequestId || _state.value.selectedSeason != season) return@launch
        _state.update { state ->
          if (state.selectedSeason != season) {
            state
          } else {
            state.copy(
              episodesLoading = false,
              episodesError = R.string.detail_episodes_error
            )
          }
        }
      }
    }
  }

  /**
   * Preferiti: la persistenza reale è in [MediaRepository.toggleFavorite]. Lo
   * stato locale viene letto subito dal repository (la lista condivisa emette
   * in modo sincrono), quindi il feedback è immediato anche prima del round-trip
   * su Room.
   */
  fun toggleFavorite() {
    val media = _state.value.media ?: return
    MediaRepository.toggleFavorite(media.id)
    val favorite = MediaRepository.getMediaById(media.id)?.isFavorite
      ?: !_state.value.isFavorite
    _state.update { state ->
      state.copy(
        isFavorite = favorite,
        media = state.media?.copy(isFavorite = favorite)
      )
    }
  }

  /**
   * Guardato / Da guardare: identico alla TV, aggiorna il progresso del titolo
   * (100% se non visto, 0 se già visto). Per le serie è lo stato del titolo,
   * non la somma degli episodi, come nella scheda TV 1.0.4.
   *
   * "Visto" è una CONCLUSIONE (TV 1.0.7): oltre a portare il progresso alla durata
   * totale, toglie la card da «Continua a guardare» (`completedAt`) e — tramite il
   * sink dei completamenti locali — dichiara il titolo concluso anche su SIMKL
   * (`status=completed` per le serie, cronologia vista per i film). "Non visto"
   * ritira il completamento in locale e su SIMKL (`history/remove`).
   */
  fun toggleWatched() {
    val media = _state.value.media ?: return
    val current = MediaRepository.getMediaById(media.id) ?: media
    val target = MobileDetailPolicy.watchedTargetProgress(current)
    val becomingWatched = !current.isWatched
    MediaRepository.updateProgress(current.id, target)

    val updated = MediaRepository.getMediaById(current.id)
    if (updated != null) {
      _state.update { state ->
        MobileDetailPolicy.mergeRepositoryMedia(
          state = state,
          updated = updated,
          progressOf = ::episodeProgress,
          durationOf = ::episodeDuration
        )
      }
    } else {
      _state.update { state ->
        state.copy(media = state.media?.copy(currentProgressMs = target))
      }
    }
    viewModelScope.launch {
      if (becomingWatched) {
        MediaRepository.markWatchedManually(current.id)
      } else {
        MediaRepository.clearCompletedManually(current.id)
        broadcastUnwatchToSimkl(current)
      }
    }
  }

  /**
   * Trasmette a SIMKL il RITIRO della marcatura «visto» (`/sync/history/remove`):
   * film e serie intera. La MARCATURA invece viaggia tramite
   * [MediaRepository.markWatchedManually], che notifica SIMKL dal sink dei
   * completamenti locali. Il fallimento non tocca lo stato locale, già scritto.
   */
  private suspend fun broadcastUnwatchToSimkl(media: MediaItem) {
    val hasIdentifiers = (media.tmdbId ?: 0) > 0 || !media.effectiveImdbId.isNullOrBlank()
    if (!hasIdentifiers) return
    val payload = SimklMediaPayload(
      title = media.title,
      year = media.year,
      tmdbId = media.tmdbId,
      imdbId = media.effectiveImdbId,
      episode = null,
      kind = if (media.type == MediaType.SERIE_TV) SimklMediaKind.SERIES else SimklMediaKind.MOVIE,
      mediaId = media.id
    )
    val delivered = if (media.type == MediaType.SERIE_TV) {
      // Serie intera: la stessa forma show-level della marcatura, senza `status`.
      SimklRepository.removeSeriesHistory(payload)
    } else {
      SimklRepository.unmarkWatched(payload)
    }
    if (delivered) {
      android.util.Log.i("SimklWatched", "Marcatura «da vedere» confermata da SIMKL: ${media.title}.")
    }
  }

  /**
   * Trailer interno, stesso comportamento della TV: risolve l'ID TMDB (anche da
   * IMDb), chiede la chiave YouTube reale e apre l'overlay solo se esiste.
   */
  fun playTrailer() {
    val media = _state.value.media ?: return
    trailerJob?.cancel()
    _state.update { it.copy(trailer = MobileTrailerState.Loading) }

    trailerJob = viewModelScope.launch {
      val isTv = media.type == MediaType.SERIE_TV
      try {
        val directId = media.tmdbId ?: 0
        val resolvedId = if (directId > 0) {
          directId
        } else {
          MediaRepository.resolveImdbToTmdbId(media.effectiveImdbId, isTv)
        }
        val key = resolvedId
          ?.takeIf { it > 0 }
          ?.let { MediaRepository.fetchTrailerKey(it, isTv) }
        val trailer = MobileDetailPolicy.trailerStateForKey(key)
        if (_state.value.media?.id == media.id) {
          _state.update { it.copy(trailer = trailer) }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (_state.value.media?.id == media.id) {
          _state.update { it.copy(trailer = MobileTrailerState.Error) }
        }
      }
    }
  }

  /** Chiude il trailer e annulla l'eventuale recupero della chiave in corso. */
  fun closeTrailer() {
    trailerJob?.cancel()
    trailerJob = null
    _state.update { it.copy(trailer = MobileTrailerState.Idle) }
  }

  /**
   * Badge "Visto" SIMKL per la stagione corrente, con la stessa fonte della TV.
   * Una revisione della libreria SIMKL invalida la cache e aggiorna i badge
   * senza riaprire la scheda.
   */
  private fun refreshSimklWatched() {
    val current = _state.value
    val media = current.media ?: return
    val season = current.selectedSeason ?: return
    val tmdbId = media.tmdbId?.takeIf { it > 0 }
    if (tmdbId == null || !SimklRepository.accountState.value.isLoggedIn) {
      if (current.simklWatchedEpisodes.isNotEmpty()) {
        _state.update { it.copy(simklWatchedEpisodes = emptySet()) }
      }
      return
    }

    simklJob?.cancel()
    simklJob = viewModelScope.launch {
      val watched = runCatching {
        SimklRepository.getWatchedEpisodes(tmdbId, season)
      }.getOrDefault(emptySet())
      val latest = _state.value
      if (latest.media?.tmdbId == tmdbId && latest.selectedSeason == season) {
        _state.update { it.copy(simklWatchedEpisodes = watched) }
      }
    }
  }

  private fun episodeProgress(season: Int, episode: Int): Long =
    MediaRepository.getEpisodeProgress(
      _state.value.media?.id.orEmpty(),
      season,
      episode
    )

  private fun episodeDuration(season: Int, episode: Int): Long =
    MediaRepository.getEpisodeDurationMs(
      _state.value.media?.id.orEmpty(),
      season,
      episode
    )

  /** Durata nota dell'episodio, usata dal player come fallback prima del play. */
  fun episodeProgress(episode: Episode): Long =
    MediaRepository.getEpisodeProgress(
      _state.value.media?.id.orEmpty(),
      episode.seasonNumber,
      episode.episodeNumber
    )
}
