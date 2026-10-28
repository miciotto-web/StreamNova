package com.aistudio.streamnova.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Episode
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.toEpisode
import com.example.data.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MobileDetailUiState(
  val media: MediaItem? = null,
  val isFavorite: Boolean = false,
  val seasons: List<Int> = emptyList(),
  val selectedSeason: Int = 1,
  val episodes: List<Episode> = emptyList(),
  val episodesLoading: Boolean = false,
  val error: String? = null
)

/**
 * ViewModel mobile per la scheda dettaglio: carica stagioni/episodi dal core
 * condiviso e mantiene lo stato dei preferiti.
 */
class MobileDetailViewModel : ViewModel() {

  private val _state = MutableStateFlow(MobileDetailUiState())
  val state: StateFlow<MobileDetailUiState> = _state.asStateFlow()

  private var loadedMediaId: String? = null

  init {
    // La scheda dettaglio resta nel back stack mentre il player aggiorna il
    // progresso: senza questo collector il pulsante mostrerebbe ancora "Guarda"
    // al ritorno dalla riproduzione.
    viewModelScope.launch {
      MediaRepository.mediaList.collect { list ->
        val current = _state.value.media ?: return@collect
        val updated = list.firstOrNull { it.id == current.id } ?: return@collect
        _state.update { state ->
          if (state.media?.id != updated.id) {
            state
          } else {
            state.copy(
              media = state.media?.copy(
                currentProgressMs = updated.currentProgressMs,
                totalDurationMs = updated.totalDurationMs,
                lastWatchedSeason = updated.lastWatchedSeason,
                lastWatchedEpisode = updated.lastWatchedEpisode,
                isFavorite = updated.isFavorite
              ),
              isFavorite = updated.isFavorite,
              episodes = state.episodes.map { episode ->
                val progress = MediaRepository.getEpisodeProgress(
                  updated.id,
                  episode.seasonNumber,
                  episode.episodeNumber
                )
                if (progress != episode.currentProgressMs) {
                  episode.copy(currentProgressMs = progress)
                } else {
                  episode
                }
              }
            )
          }
        }
      }
    }
  }

  fun load(mediaId: String) {
    if (loadedMediaId == mediaId) return
    loadedMediaId = mediaId

    val media = MediaRepository.getMediaById(mediaId)
      ?: MediaRepository.mediaList.value.firstOrNull { it.id == mediaId }

    if (media == null) {
      _state.value = MobileDetailUiState(error = "Titolo non trovato")
      return
    }

    val seasons = when {
      media.type != MediaType.SERIE_TV -> emptyList()
      media.episodes.isNotEmpty() -> media.episodes.map { it.seasonNumber }.distinct().sorted()
      else -> (1..(media.seasonsCount ?: 1)).toList()
    }

    _state.value = MobileDetailUiState(
      media = media,
      isFavorite = media.isFavorite,
      seasons = seasons,
      selectedSeason = seasons.firstOrNull() ?: 1
    )

    if (media.type == MediaType.SERIE_TV) {
      loadSeason(media, seasons.firstOrNull() ?: 1)
    }

    refreshDetail(media)
  }

  private fun refreshDetail(media: MediaItem) {
    val tmdbId = media.tmdbId
    if (tmdbId == null || tmdbId <= 0) return
    viewModelScope.launch {
      runCatching {
        MediaRepository.loadMediaDetails(
          tmdbId = tmdbId,
          isTv = media.type == MediaType.SERIE_TV,
          baseMedia = media
        )
      }
      val detailed = (MediaRepository.detailUiState.value as? MediaDetailUiState.Success)?.media
      if (detailed != null && detailed.id == media.id) {
        _state.update { current ->
          current.copy(media = detailed, isFavorite = detailed.isFavorite)
        }
      }
    }
  }

  fun selectSeason(season: Int) {
    val media = _state.value.media ?: return
    if (media.type != MediaType.SERIE_TV) return
    _state.update { it.copy(selectedSeason = season) }
    loadSeason(media, season)
  }

  private fun loadSeason(media: MediaItem, season: Int) {
    val seeded = media.episodes.filter { it.seasonNumber == season }
    if (seeded.isNotEmpty()) {
      _state.update { it.copy(episodes = seeded, episodesLoading = false) }
      return
    }

    val tmdbId = media.tmdbId
    if (tmdbId == null || tmdbId <= 0) {
      _state.update { it.copy(episodes = emptyList(), episodesLoading = false) }
      return
    }

    _state.update { it.copy(episodesLoading = true) }
    viewModelScope.launch {
      runCatching { MediaRepository.getSeasonDetails(tmdbId, season, media) }
        .onSuccess { seasonItem ->
          val episodes = seasonItem.episodes.map { item ->
            item.toEpisode(
              seriesId = media.id,
              progressMs = MediaRepository.getEpisodeProgress(
                media.id,
                item.seasonNumber,
                item.episodeNumber
              ),
              seriesImdbId = media.imdbId
            )
          }
          _state.update { current ->
            if (current.selectedSeason == season) {
              current.copy(episodes = episodes, episodesLoading = false)
            } else current
          }
        }
        .onFailure {
          _state.update { current ->
            if (current.selectedSeason == season) {
              current.copy(episodes = emptyList(), episodesLoading = false)
            } else current
          }
        }
    }
  }

  fun toggleFavorite() {
    val media = _state.value.media ?: return
    MediaRepository.toggleFavorite(media.id)
    _state.update { current ->
      val newFavorite = !current.isFavorite
      current.copy(
        isFavorite = newFavorite,
        media = current.media?.copy(isFavorite = newFavorite)
      )
    }
  }

  /** Durata nota dell'episodio, usata dal player come fallback prima del play. */
  fun episodeProgress(episode: Episode): Long =
    MediaRepository.getEpisodeProgress(
      _state.value.media?.id.orEmpty(),
      episode.seasonNumber,
      episode.episodeNumber
    )
}
