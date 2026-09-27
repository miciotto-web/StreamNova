package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AudioTrack
import com.example.data.model.Episode
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SeasonEpisodesUiState
import com.example.data.model.SeasonItem
import com.example.data.model.SubtitleTrack
import com.example.data.model.VideoResolution
import com.example.data.repository.MediaRepository
import com.example.ui.components.StreamingProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class SidebarSection(val title: String) {
  HOME("Home"),
  FILM("Film"),
  SERIE_TV("Serie TV"),
  I_MIEI_CONTENUTI("I miei contenuti"),
  CERCA("Cerca"),
  IMPOSTAZIONI("Impostazioni")
}

enum class ScreenState {
  BROWSING,
  DETAIL,
  PLAYER,
  PROVIDER
}

data class PlayerPlaybackState(
  val media: MediaItem? = null,
  val currentEpisode: Episode? = null,
  val isPlaying: Boolean = true,
  val currentPositionMs: Long = 0L,
  val durationMs: Long = 0L,
  val bufferedPositionMs: Long = 0L,
  val selectedAudio: AudioTrack = MediaRepository.audioTracks[0],
  val selectedSubtitle: SubtitleTrack = MediaRepository.subtitleTracks[0],
  val selectedResolution: VideoResolution = VideoResolution.UHD_4K,
  val areControlsVisible: Boolean = true,
)

class StreamNovaViewModel : ViewModel() {

  private val _currentSection = MutableStateFlow(SidebarSection.HOME)
  val currentSection: StateFlow<SidebarSection> = _currentSection.asStateFlow()

  private val _screenState = MutableStateFlow(ScreenState.BROWSING)
  val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

  private val _selectedMedia = MutableStateFlow<MediaItem?>(null)
  val selectedMedia: StateFlow<MediaItem?> = _selectedMedia.asStateFlow()

  val detailUiState: StateFlow<MediaDetailUiState> = MediaRepository.detailUiState

  private val _selectedSeasonNumber = MutableStateFlow(1)
  val selectedSeasonNumber: StateFlow<Int> = _selectedSeasonNumber.asStateFlow()

  private val _seasonEpisodesUiState = MutableStateFlow<SeasonEpisodesUiState>(SeasonEpisodesUiState.Idle)
  val seasonEpisodesUiState: StateFlow<SeasonEpisodesUiState> = _seasonEpisodesUiState.asStateFlow()

  private val _selectedProvider = MutableStateFlow<StreamingProvider?>(null)
  val selectedProvider: StateFlow<StreamingProvider?> = _selectedProvider.asStateFlow()

  private val _playbackState = MutableStateFlow(PlayerPlaybackState())
  val playbackState: StateFlow<PlayerPlaybackState> = _playbackState.asStateFlow()

  private val _isRefreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

  init {
    viewModelScope.launch {
      MediaRepository.loadFromCache()
      refreshCatalog()
    }
  }

  fun refreshCatalog() {
    viewModelScope.launch {
      _isRefreshing.value = true
      try {
        MediaRepository.refreshTmdbData()
      } finally {
        _isRefreshing.value = false
      }
    }
  }

  fun clearCacheAndRefresh() {
    viewModelScope.launch {
      _isRefreshing.value = true
      try {
        MediaRepository.clearCache()
        MediaRepository.refreshTmdbData()
      } finally {
        _isRefreshing.value = false
      }
    }
  }

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  val allMedia: StateFlow<List<MediaItem>> = MediaRepository.mediaList

  val filteredSearchResults: StateFlow<List<MediaItem>> = combine(
    allMedia,
    _searchQuery
  ) { mediaList, query ->
    if (query.isBlank()) {
      mediaList
    } else {
      val q = query.trim().lowercase()
      mediaList.filter {
        it.title.lowercase().contains(q) ||
          it.originalTitle.lowercase().contains(q) ||
          it.synopsis.lowercase().contains(q) ||
          it.genres.any { g -> g.lowercase().contains(q) } ||
          it.cast.any { c -> c.lowercase().contains(q) }
      }
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  fun setSection(section: SidebarSection) {
    _currentSection.value = section
    _screenState.value = ScreenState.BROWSING
  }

  fun loadDetails(tmdbId: Int, isTv: Boolean, baseMedia: MediaItem? = null) {
    viewModelScope.launch {
      MediaRepository.loadMediaDetails(tmdbId, isTv, baseMedia)
    }
  }

  fun selectSeason(seriesTmdbId: Int, seasonNumber: Int, fallbackSeries: MediaItem? = null) {
    _selectedSeasonNumber.value = seasonNumber
    _seasonEpisodesUiState.value = SeasonEpisodesUiState.Loading(seasonNumber)
    viewModelScope.launch {
      try {
        val season = MediaRepository.getSeasonDetails(seriesTmdbId, seasonNumber, fallbackSeries)
        _seasonEpisodesUiState.value = SeasonEpisodesUiState.Success(seasonNumber, season)
      } catch (e: Exception) {
        _seasonEpisodesUiState.value = SeasonEpisodesUiState.Error(
          seasonNumber,
          e.localizedMessage ?: "Errore nel caricamento degli episodi della stagione $seasonNumber"
        )
      }
    }
  }

  fun openDetail(media: MediaItem) {
    _selectedMedia.value = media
    _screenState.value = ScreenState.DETAIL
    val tmdbId = media.tmdbId ?: 0
    val isTv = media.type == MediaType.SERIE_TV
    loadDetails(tmdbId, isTv, media)
    if (isTv && tmdbId > 0) {
      val initialSeason = media.lastWatchedSeason ?: 1
      selectSeason(tmdbId, initialSeason, media)
    } else {
      _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
    }
  }

  fun retryLoadDetail() {
    val media = _selectedMedia.value ?: return
    val tmdbId = media.tmdbId ?: 0
    val isTv = media.type == MediaType.SERIE_TV
    loadDetails(tmdbId, isTv, media)
    if (isTv && tmdbId > 0) {
      selectSeason(tmdbId, _selectedSeasonNumber.value, media)
    }
  }

  fun openPlayer(media: MediaItem, episode: Episode? = null) {
    val initialPos = episode?.currentProgressMs ?: media.currentProgressMs
    val totalDur = episode?.totalDurationMs ?: media.totalDurationMs

    _playbackState.value = PlayerPlaybackState(
      media = media,
      currentEpisode = episode,
      isPlaying = true,
      currentPositionMs = initialPos,
      durationMs = totalDur,
      selectedResolution = media.resolution,
      areControlsVisible = true
    )
    _screenState.value = ScreenState.PLAYER
  }

  fun closePlayer() {
    val current = _playbackState.value
    if (current.media != null) {
      MediaRepository.updateProgress(current.media.id, current.currentPositionMs)
    }
    _screenState.value = if (_selectedMedia.value != null) ScreenState.DETAIL else ScreenState.BROWSING
  }

  fun backToBrowsing() {
    _selectedProvider.value = null
    _selectedMedia.value = null
    _screenState.value = ScreenState.BROWSING
  }

  fun backFromDetail() {
    _selectedMedia.value = null
    if (_selectedProvider.value != null) {
      _screenState.value = ScreenState.PROVIDER
    } else {
      _screenState.value = ScreenState.BROWSING
    }
  }

  fun openProvider(provider: StreamingProvider) {
    _selectedProvider.value = provider
    _screenState.value = ScreenState.PROVIDER
  }

  fun closeProvider() {
    _selectedProvider.value = null
    _screenState.value = ScreenState.BROWSING
  }

  fun toggleFavorite(id: String) {
    MediaRepository.toggleFavorite(id)
    if (_selectedMedia.value?.id == id) {
      _selectedMedia.update { it?.copy(isFavorite = !it.isFavorite) }
    }
  }

  fun updateSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun setControlsVisible(visible: Boolean) {
    _playbackState.update { it.copy(areControlsVisible = visible) }
  }

  fun togglePlayPause() {
    _playbackState.update { it.copy(isPlaying = !it.isPlaying) }
  }

  fun updatePlaybackPosition(positionMs: Long, durationMs: Long, bufferedMs: Long) {
    _playbackState.update {
      it.copy(
        currentPositionMs = positionMs,
        durationMs = if (durationMs > 0) durationMs else it.durationMs,
        bufferedPositionMs = bufferedMs
      )
    }
  }

  fun seekBy(deltaSeconds: Int) {
    _playbackState.update {
      val newPos = (it.currentPositionMs + deltaSeconds * 1000L).coerceIn(0L, it.durationMs)
      it.copy(currentPositionMs = newPos)
    }
  }

  fun setAudioTrack(track: AudioTrack) {
    _playbackState.update { it.copy(selectedAudio = track) }
  }

  fun setSubtitleTrack(track: SubtitleTrack) {
    _playbackState.update { it.copy(selectedSubtitle = track) }
  }

  fun setResolution(res: VideoResolution) {
    _playbackState.update { it.copy(selectedResolution = res) }
  }
}
