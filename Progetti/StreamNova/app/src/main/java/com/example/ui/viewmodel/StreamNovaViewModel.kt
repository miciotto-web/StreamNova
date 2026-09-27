package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AudioTrack
import com.example.data.model.Episode
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SearchTypeFilter
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
  /** URL del provider streaming: se null si usa quello del catalogo. */
  val streamUrl: String? = null,
  /** Header obbligatori del flusso (Referer/User-Agent): senza essi = 403. */
  val streamHeaders: Map<String, String> = emptyMap(),
  /** Qualità dichiarata del flusso (es. "1080p", "720p"). */
  val streamQuality: String? = null,
  /** Nome del server/provider del flusso. */
  val streamServer: String? = null,
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

  private val _searchFilter = MutableStateFlow(SearchTypeFilter.ALL)
  val searchFilter: StateFlow<SearchTypeFilter> = _searchFilter.asStateFlow()

  fun setSearchFilter(filter: SearchTypeFilter) {
    _searchFilter.value = filter
  }

  val allMedia: StateFlow<List<MediaItem>> = MediaRepository.mediaList

  @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
  val filteredSearchResults: StateFlow<List<MediaItem>> = combine(_searchQuery, _searchFilter) { query, filter ->
    query to filter
  }
    .debounce { (query, _) -> if (query.isBlank()) 0L else 400L }
    .distinctUntilChanged()
    .flatMapLatest { (query, filter) ->
      MediaRepository.searchTmdb(query, filter)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  // Paginazione remota generi / categorie
  private val _categoryItems = MutableStateFlow<List<MediaItem>>(emptyList())
  val categoryItems: StateFlow<List<MediaItem>> = _categoryItems.asStateFlow()

  private val _isCategoryLoading = MutableStateFlow(false)
  val isCategoryLoading: StateFlow<Boolean> = _isCategoryLoading.asStateFlow()

  private var activeCategoryMovieGenreId: Int? = null
  private var activeCategoryTvGenreId: Int? = null
  private var currentCategoryPage = 1
  private var isCategoryLoadingMore = false

  fun initCategory(movieGenreId: Int?, tvGenreId: Int?, initialLocalItems: List<MediaItem>) {
    activeCategoryMovieGenreId = movieGenreId
    activeCategoryTvGenreId = tvGenreId
    currentCategoryPage = 1
    _categoryItems.value = initialLocalItems
    loadCategoryPage(page = 1, isInitial = true)
  }

  fun loadNextCategoryPage() {
    if (isCategoryLoadingMore || _isCategoryLoading.value) return
    isCategoryLoadingMore = true
    viewModelScope.launch {
      val nextPage = currentCategoryPage + 1
      try {
        val newItems = MediaRepository.loadCategoryPage(
          activeCategoryMovieGenreId,
          activeCategoryTvGenreId,
          nextPage
        )
        if (newItems.isNotEmpty()) {
          currentCategoryPage = nextPage
          val current = _categoryItems.value.toMutableList()
          newItems.forEach { item ->
            if (current.none { it.id == item.id }) {
              current.add(item)
            }
          }
          _categoryItems.value = current
        }
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione categoria pagina $nextPage: ${e.message}")
      } finally {
        isCategoryLoadingMore = false
      }
    }
  }

  private fun loadCategoryPage(page: Int, isInitial: Boolean) {
    viewModelScope.launch {
      if (isInitial) _isCategoryLoading.value = true
      try {
        val newItems = MediaRepository.loadCategoryPage(
          activeCategoryMovieGenreId,
          activeCategoryTvGenreId,
          page
        )
        if (newItems.isNotEmpty()) {
          val current = _categoryItems.value.toMutableList()
          newItems.forEach { item ->
            if (current.none { it.id == item.id }) {
              current.add(item)
            }
          }
          _categoryItems.value = current
        }
      } catch (e: Exception) {
        Log.w(TAG, "Errore caricamento iniziale categoria: ${e.message}")
      } finally {
        if (isInitial) _isCategoryLoading.value = false
      }
    }
  }

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
      streamHeaders = source?.headers ?: emptyMap(),
      streamQuality = source?.quality,
      streamServer = source?.serverName
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
        Log.i(TAG, "Ricerca sorgenti Fast-Start: tmdbId=$tmdbId isTv=$isTv S$season:E$episodeNumber \"$searchTitle\" (${media.year})")

        var activePlayingSource: StreamSource? = null

        streamManager.resolveFlow(
          tmdbId = tmdbId,
          isTv = isTv,
          season = if (isTv) season else null,
          episode = if (isTv) episodeNumber else null,
          title = searchTitle,
          year = media.year
        ).collect { sources ->
          if (sources.isNotEmpty()) {
            _streamResult.value = StreamResult.Success(sources)
            val best = sources.first()

            if (activePlayingSource == null) {
              // Fast-Start: primo flusso valido disponibile
              activePlayingSource = best
              Log.i(TAG, "Fast-Start immediato: avvio con ${best.serverName} (${best.quality}) -> ${best.url}")
              openPlayer(media, episode, best)
            } else {
              // Valutazione sorgente tardiva: verifica se rappresenta un effettivo miglioramento qualitativo
              val currentScore = StreamManager.qualityScore(activePlayingSource!!)
              val newScore = StreamManager.qualityScore(best)
              val isDifferentUrl = best.url != activePlayingSource!!.url

              if (isDifferentUrl && newScore > currentScore) {
                Log.i(TAG, "Upgrade qualitativo sorgente tardiva: da ${activePlayingSource!!.serverName} (${activePlayingSource!!.quality}, score=$currentScore) a ${best.serverName} (${best.quality}, score=$newScore)")
                activePlayingSource = best
                upgradeStreamSource(best)
              } else {
                Log.d(TAG, "Sorgenti aggiornate (${sources.size}), ma la qualità non migliora (score: attuale=$currentScore, nuova=$newScore). Nessun reload.")
              }
            }
          }
        }

        if (activePlayingSource == null) {
          throw IllegalStateException("Nessuna sorgente disponibile dai provider")
        }
      } catch (e: Exception) {
        Log.w(TAG, "Estrazione stream fallita: ${e.message}")
        _streamResult.value = StreamResult.Error(e.message ?: "Impossibile estrarre lo stream")
        openPlayer(media, episode)
      }
    }
  }

  fun upgradeStreamSource(source: StreamSource) {
    val current = _playbackState.value
    if (current.streamUrl == source.url) return
    _playbackState.value = current.copy(
      streamUrl = source.url,
      streamHeaders = source.headers,
      streamQuality = source.quality,
      streamServer = source.serverName
    )
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
