package com.example.ui.viewmodel

import android.util.Log
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
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamResult
import com.example.data.streaming.StreamSource
import com.example.ui.components.StreamingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Tag di log del ViewModel (ricerca sorgenti + playback). */
private const val TAG = "StreamNovaVM"

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
  /** URL del provider streaming (VixSrc): se null si usa quello del catalogo. */
  val streamUrl: String? = null,
  /** Header obbligatori del flusso (Referer/User-Agent): senza essi = 403. */
  val streamHeaders: Map<String, String> = emptyMap(),
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

  // Stato del ciclo di ricerca sorgenti (estrazione provider -> player)
  private val _streamResult = MutableStateFlow<StreamResult>(StreamResult.Idle)
  val streamResult: StateFlow<StreamResult> = _streamResult.asStateFlow()

  // Interrogazione parallela dei provider di streaming HTTP diretto:
  // 1) VixSrc/VixCloud (istantaneo via TMDB ID), 2) CB01 e 3) Eurostreaming
  // (cataloghi italiani, ricerca per titolo/anno) con timeout per ciascuno.
  private val streamManager = StreamManager()

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

  @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
  val filteredSearchResults: StateFlow<List<MediaItem>> = _searchQuery
    .debounce { query -> if (query.isBlank()) 0L else 400L }
    .distinctUntilChanged()
    .flatMapLatest { query ->
      MediaRepository.searchTmdb(query)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private var currentMoviePage = 1
  private var currentTvPage = 1
  private var isLoadingMoreMovies = false
  private var isLoadingMoreTv = false

  fun loadNextMoviesPage() {
    if (isLoadingMoreMovies) return
    isLoadingMoreMovies = true
    viewModelScope.launch {
      try {
        currentMoviePage++
        MediaRepository.loadMoreMovies(currentMoviePage)
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione film: ${e.message}")
      } finally {
        isLoadingMoreMovies = false
      }
    }
  }

  fun loadNextTvPage() {
    if (isLoadingMoreTv) return
    isLoadingMoreTv = true
    viewModelScope.launch {
      try {
        currentTvPage++
        MediaRepository.loadMoreTv(currentTvPage)
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione serie TV: ${e.message}")
      } finally {
        isLoadingMoreTv = false
      }
    }
  }

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

  fun openPlayer(media: MediaItem, episode: Episode? = null, source: StreamSource? = null) {
    val initialPos = episode?.currentProgressMs ?: media.currentProgressMs
    val totalDur = episode?.totalDurationMs ?: media.totalDurationMs

    _playbackState.value = PlayerPlaybackState(
      media = media,
      currentEpisode = episode,
      isPlaying = true,
      currentPositionMs = initialPos,
      durationMs = totalDur,
      selectedResolution = media.resolution,
      areControlsVisible = true,
      streamUrl = source?.url,
      streamHeaders = source?.headers ?: emptyMap()
    )
    _screenState.value = ScreenState.PLAYER
  }

  /**
   * Punto d'ingresso dei pulsanti "Riproduci" (film) e delle card episodio (serie TV).
   *
   * 1) emette [StreamResult.Loading] per il feedback UI,
   * 2) interroga [StreamManager], che interrogano IN PARALLELO VixSrc (TMDB ID),
   *    CB01 ed Eurostreaming (titolo/anno), ciascuno con timeout dedicato,
   * 3) unisce i risultati e al successo apre il player sul primo [StreamSource]
   *    passando URL e header (Referer/User-Agent) a [openPlayer], così ExoPlayer
   *    non riceve 403.
   *
   * In caso di errore viene pubblicato [StreamResult.Error] e il player si apre
   * comunque sul flusso demo del catalogo, così il pulsante non resta morto.
   */
  fun loadStream(media: MediaItem, episode: Episode? = null) {
    viewModelScope.launch {
      _streamResult.value = StreamResult.Loading("Ricerca sorgenti in corso...")
      val isTv = media.type == MediaType.SERIE_TV
      try {
        val tmdbId = media.tmdbId
          ?: throw IllegalStateException("Titolo non collegato a TMDB (tmdbId assente)")
        val season = episode?.seasonNumber ?: media.lastWatchedSeason ?: 1
        val episodeNumber = episode?.episodeNumber ?: media.lastWatchedEpisode ?: 1
        val searchTitle = media.title.ifBlank { media.originalTitle }
        Log.i(TAG, "Ricerca sorgenti: tmdbId=$tmdbId isTv=$isTv S$season:E$episodeNumber \"$searchTitle\" (${media.year})")

        val sources = streamManager.resolve(
          tmdbId = tmdbId,
          isTv = isTv,
          season = if (isTv) season else null,
          episode = if (isTv) episodeNumber else null,
          title = searchTitle,
          year = media.year
        )
        if (sources.isEmpty()) throw IllegalStateException("Nessuna sorgente disponibile dai provider")

        val best = sources.first()
        Log.i(TAG, "Sorgente scelta: ${best.serverName} ${best.quality} -> ${best.url}")
        _streamResult.value = StreamResult.Success(sources)
        openPlayer(media, episode, best)
      } catch (e: Exception) {
        Log.w(TAG, "Estrazione stream fallita: ${e.message}")
        _streamResult.value = StreamResult.Error(e.message ?: "Impossibile estrarre lo stream")
        openPlayer(media, episode)
      }
    }
  }

  fun closePlayer() {
    val current = _playbackState.value
    if (current.media != null) {
      MediaRepository.updateProgress(current.media.id, current.currentPositionMs)
    }
    _streamResult.value = StreamResult.Idle
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
