package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AudioTrack
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.SubtitleTrack
import com.example.data.model.VideoResolution
import com.example.data.repository.MediaRepository
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
  PLAYER
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

  private val _playbackState = MutableStateFlow(PlayerPlaybackState())
  val playbackState: StateFlow<PlayerPlaybackState> = _playbackState.asStateFlow()

  init {
    viewModelScope.launch {
      MediaRepository.refreshTmdbData()
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

  fun openDetail(media: MediaItem) {
    _selectedMedia.value = media
    _screenState.value = ScreenState.DETAIL
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
