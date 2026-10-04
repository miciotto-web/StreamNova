package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.api.TmdbApiService
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
import android.content.Context
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.BackBufferOption
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.InitialBufferOption
import com.example.data.prefs.TargetBufferOption
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitlePosition
import com.example.data.prefs.SubtitleSize
import com.example.data.repository.MediaRepository
import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.StremioAddonRepository
import com.example.data.torbox.TorBoxRepository
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamResult
import com.example.data.streaming.StreamSource
import com.example.ui.components.StreamingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
private const val HTTP_TRACE = "HTTP_TRACE"
private var contentIdCounter = 0

enum class SidebarSection(val title: String) {
  HOME("Home"),
  FILM("Film"),
  SERIE_TV("Serie TV"),
  I_MIEI_CONTENUTI("I miei contenuti"),
  CERCA("Cerca"),
  ADDON("Addon"),
  IMPOSTAZIONI("Impostazioni")
}

enum class ScreenState {
  BROWSING,
  DETAIL,
  PLAYER,
  PROVIDER
}

/**
 * Stato UI della verifica dell'account TorBox (`GET /user/me`):
 * - [Unknown]: nessuna verifica eseguita in questa sessione.
 * - [Checking]: richiesta in corso.
 * - [Connected]: chiave valida → indicatore verde "Collegato".
 * - [Error]: chiave non valida / errore di rete.
 * - [NotConfigured]: nessuna chiave o toggle Instant Debrid disabilitato.
 */
sealed interface TorBoxAccountState {
  object Unknown : TorBoxAccountState
  object Checking : TorBoxAccountState
  object NotConfigured : TorBoxAccountState
  data class Connected(val email: String?, val plan: String?) : TorBoxAccountState
  data class Error(val message: String) : TorBoxAccountState
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
  /**
   * Hint tipo contenuto del flusso: `true` = file progressivo (mp4/mkv, es. link
   * Debrid CDN), `false` = playlist HLS, `null` = inferire dall'estensione URL.
   */
  val streamProgressive: Boolean? = null,
)

/**
 * Stato della risoluzione dello stream per il titolo selezionato.
 */
sealed interface StreamResolutionState {
  object Idle : StreamResolutionState
  object Loading : StreamResolutionState
  data class Success(
    val streamUrl: String,
    val mediaItem: MediaItem,
    val episode: Episode? = null,
    val streamHeaders: Map<String, String> = emptyMap(),
    val quality: String? = null,
    val serverName: String? = null,
    val isProgressive: Boolean? = null
  ) : StreamResolutionState
  data class Error(val message: String) : StreamResolutionState
}

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

  // Stato della risoluzione dello stream con gestione rigida del timeout e lifecycle
  private val _streamResolutionState = MutableStateFlow<StreamResolutionState>(StreamResolutionState.Idle)
  val streamResolutionState: StateFlow<StreamResolutionState> = _streamResolutionState.asStateFlow()

  private var streamJob: Job? = null

  /**
   * Resetta immediatamente lo stato di risoluzione dello stream a Idle
   * e cancella ogni Job di ricerca attivo.
   */
  fun resetStreamState() {
    Log.d(TAG, "resetStreamState: reimpostazione a Idle e cancellazione Job")
    streamJob?.cancel()
    streamJob = null
    loadStreamJob?.cancel()
    loadStreamGeneration++
    loadStreamJob = null
    _streamResolutionState.value = StreamResolutionState.Idle
    _streamResult.value = StreamResult.Idle
  }

  // Stato del ciclo di ricerca sorgenti (estrazione provider -> player)
  private val _streamResult = MutableStateFlow<StreamResult>(StreamResult.Idle)
  val streamResult: StateFlow<StreamResult> = _streamResult.asStateFlow()

  // Modalità del motore di streaming (Debrid TorBox vs HTTP Web)
  private val _streamingEngineMode = MutableStateFlow(AppSettingsRepository.streamingEngineMode.value)
  val streamingEngineMode: StateFlow<StreamingEngineMode> = _streamingEngineMode.asStateFlow()

  // Stato del dialog di selezione sorgente (autoplay disabilitato)
  private val _showSourceDialog = MutableStateFlow(false)
  val showSourceDialog: StateFlow<Boolean> = _showSourceDialog.asStateFlow()

  private val _availableSources = MutableStateFlow<List<StreamSource>>(emptyList())
  val availableSources: StateFlow<List<StreamSource>> = _availableSources.asStateFlow()

  private var pendingMediaForSourceSelection: MediaItem? = null
  private var pendingEpisodeForSourceSelection: Episode? = null
  private var loadStreamJob: Job? = null

  /**
   * Generazione del loadStream corrente: incrementata a ogni nuova chiamata e
   * invalidata quando il job corrente viene cancellato (closePlayer/dismissSourceDialog).
   * Nel catch si usa questo contatore (e non _screenState) per distinguere un job
   * vecchio da uno corrente.
   */
  private var loadStreamGeneration = 0

  /** Stato schermata attivo PRIMA dell'apertura del Player: usato per ripristinare l'origine al BACK. */
  private var screenBeforePlayer: ScreenState? = null
  private var sectionBeforePlayer: SidebarSection = SidebarSection.HOME

  /** ID della categoria selezionata in Cerca (preserva al BACK dal Player). */
  private val _selectedSearchCategoryId = MutableStateFlow<String?>(null)
  val selectedSearchCategoryId: StateFlow<String?> = _selectedSearchCategoryId.asStateFlow()

  fun setSelectedSearchCategoryId(categoryId: String?) {
    _selectedSearchCategoryId.value = categoryId
  }

  // Interrogazione parallela dei provider di streaming HTTP diretto:
  // 1) VixSrc/VixCloud (istantaneo via TMDB ID), 2) CB01 e 3) Eurostreaming
  // (cataloghi italiani, ricerca per titolo/anno) con timeout per ciascuno.
  private val streamManager = StreamManager()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _errorMessage = MutableStateFlow<String?>(null)
  val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

  private val _isRefreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

  init {
    viewModelScope.launch {
      MediaRepository.loadFromCache()
      refreshCatalog()
      
      // Osserva i cambiamenti della modalità del motore di streaming
      AppSettingsRepository.streamingEngineMode.collect { mode ->
        _streamingEngineMode.value = mode
      }
    }
    viewModelScope.launch {
      _screenState.collect { state ->
        Log.d("BACK_TRACE", "STREAMNOVAVM: modifica _screenState a $state")
      }
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

  // Impostazioni RIPRODUZIONE persistenti (DataStore Preferences).
  val playbackSettings: StateFlow<PlaybackSettings> = SettingsRepository.settings

  fun setPreferredResolution(resolution: PreferredResolution) =
    SettingsRepository.setPreferredResolution(resolution)

  fun setAutoPlayNextEpisode(enabled: Boolean) =
    SettingsRepository.setAutoPlayNextEpisode(enabled)

  fun setAutoResume(enabled: Boolean) =
    SettingsRepository.setAutoResume(enabled)

  fun setPreferredAudioLanguage(language: String) =
    SettingsRepository.setPreferredAudioLanguage(language)

  fun setSubtitlesEnabled(enabled: Boolean) =
    SettingsRepository.setSubtitlesEnabled(enabled)

  fun setForcedSubtitlesEnabled(enabled: Boolean) =
    SettingsRepository.setForcedSubtitlesEnabled(enabled)

  fun setPreferredSubtitleLanguage(language: String) =
    SettingsRepository.setPreferredSubtitleLanguage(language)

  fun setSubtitleSize(size: SubtitleSize) =
    SettingsRepository.setSubtitleSize(size)

  fun setSubtitleBackground(background: SubtitleBackground) =
    SettingsRepository.setSubtitleBackground(background)

  fun setSubtitlePosition(position: SubtitlePosition) =
    SettingsRepository.setSubtitlePosition(position)

  // ── IMPOSTAZIONI AVANZATE RIPRODUZIONE ─────────────────────────────

  fun setTargetBuffer(option: TargetBufferOption) =
    SettingsRepository.setTargetBuffer(option)

  fun setInitialBuffer(option: InitialBufferOption) =
    SettingsRepository.setInitialBuffer(option)

  fun setBackBuffer(option: BackBufferOption) =
    SettingsRepository.setBackBuffer(option)

  fun setAudioPassthroughEnabled(enabled: Boolean) =
    SettingsRepository.setAudioPassthroughEnabled(enabled)

  fun setAudioTunnelingEnabled(enabled: Boolean) =
    SettingsRepository.setAudioTunnelingEnabled(enabled)

  fun setDecoderFallbackMode(mode: DecoderFallbackMode) =
    SettingsRepository.setDecoderFallbackMode(mode)

  fun setAutoFrameRateMatching(enabled: Boolean) =
    SettingsRepository.setAutoFrameRateMatching(enabled)

  fun setDolbyVisionFallbackEnabled(enabled: Boolean) =
    SettingsRepository.setDolbyVisionFallbackEnabled(enabled)

  fun setDebugOverlayEnabled(enabled: Boolean) =
    SettingsRepository.setDebugOverlayEnabled(enabled)

  @OptIn(coil.annotation.ExperimentalCoilApi::class)
  fun clearAppCache(context: Context, onComplete: (Boolean) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // 1. Svuota la cache immagini Coil (memoria e disco)
        coil.Coil.imageLoader(context).apply {
          memoryCache?.clear()
          diskCache?.clear()
        }
        // 2. Svuota il database locale TMDB preservando preferiti e progressi
        val db = StreamNovaDatabase.getInstance(context)
        db?.tmdbResponseCacheDao()?.clearAll()
        db?.cachedMediaDao()?.clearNonFavorites()

        // 3. Svuota la directory cache applicativa
        context.cacheDir?.deleteRecursively()
        context.externalCacheDir?.deleteRecursively()

        withContext(Dispatchers.Main) {
          onComplete(true)
        }
      } catch (e: Exception) {
        Log.e("StreamNovaViewModel", "Clear cache error: ${e.message}", e)
        withContext(Dispatchers.Main) {
          onComplete(false)
        }
      }
    }
  }

  // ── DEBRID / TORBOX ───────────────────────────────────────────────

  /** Chiave API TorBox salvata nelle Impostazioni (null = non configurata). */
  val torBoxApiKey: StateFlow<String?> = AppSettingsRepository.torBoxApiKey

  /** Toggle "Usa TorBox Instant Debrid". */
  val torBoxInstantDebridEnabled: StateFlow<Boolean> =
    AppSettingsRepository.torBoxInstantDebridEnabled

  /** Esito dell'ultima verifica dell'account (`GET /user/me`). */
  private val _torBoxAccountState = MutableStateFlow<TorBoxAccountState>(TorBoxAccountState.Unknown)
  val torBoxAccountState: StateFlow<TorBoxAccountState> = _torBoxAccountState.asStateFlow()

  /** Salva (o rimuove) la chiave API TorBox e invalida lo stato di verifica. */
  fun setTorBoxApiKey(key: String?) {
    AppSettingsRepository.setTorBoxApiKey(key)
    _torBoxAccountState.value = TorBoxAccountState.Unknown
  }

  fun setTorBoxInstantDebridEnabled(enabled: Boolean) =
    AppSettingsRepository.setTorBoxInstantDebridEnabled(enabled)

  fun setStreamingEngineMode(mode: StreamingEngineMode) {
    AppSettingsRepository.setStreamingEngineMode(mode)
  }

  val autoplayEnabled: StateFlow<Boolean> = AppSettingsRepository.autoplayEnabled

  fun setAutoplayEnabled(enabled: Boolean) {
    AppSettingsRepository.setAutoplayEnabled(enabled)
  }

  val appLanguage: StateFlow<String> = AppSettingsRepository.appLanguage

  fun setAppLanguage(language: String) {
    if (AppSettingsRepository.appLanguage.value != language) {
      AppSettingsRepository.setAppLanguage(language)
      clearCacheAndRefresh()
    }
  }

  val parentalControlEnabled: StateFlow<Boolean> = AppSettingsRepository.parentalControlEnabled
  val parentalControlLevel: StateFlow<String> = AppSettingsRepository.parentalControlLevel

  fun hasParentalControlPin(): Boolean = AppSettingsRepository.hasParentalControlPin()

  fun setParentalControlEnabled(enabled: Boolean) {
    AppSettingsRepository.setParentalControlEnabled(enabled)
  }

  fun setParentalControlLevel(level: String) {
    AppSettingsRepository.setParentalControlLevel(level)
  }

  fun setParentalControlPin(pin: String) {
    AppSettingsRepository.setParentalControlPin(pin)
  }

  fun verifyParentalControlPin(pin: String): Boolean {
    return AppSettingsRepository.verifyParentalControlPin(pin)
  }

  data class PendingPlayback(
    val media: MediaItem,
    val episode: Episode? = null,
    val source: StreamSource? = null
  )

  private val _showParentalPinDialog = MutableStateFlow(false)
  val showParentalPinDialog: StateFlow<Boolean> = _showParentalPinDialog.asStateFlow()

  private val _parentalPinErrorResId = MutableStateFlow<Int?>(null)
  val parentalPinErrorResId: StateFlow<Int?> = _parentalPinErrorResId.asStateFlow()

  private var pendingPlayback: PendingPlayback? = null
  private var currentlyUnlockedMediaId: String? = null

  fun shouldBlockForParentalControl(media: MediaItem): Boolean {
    if (!parentalControlEnabled.value) return false
    if (!hasParentalControlPin()) return false
    if (currentlyUnlockedMediaId == media.id) return false
    val rating = getEffectiveAgeRating(media) ?: return false
    val threshold = parentalControlLevel.value.toIntOrNull() ?: 18
    return rating >= threshold
  }

  fun getEffectiveAgeRating(media: MediaItem): Int? {
    return media.ageRating
      ?: _selectedMedia.value?.takeIf { it.id == media.id || (it.tmdbId != null && it.tmdbId == media.tmdbId && it.type == media.type) }?.ageRating
      ?: MediaRepository.mediaList.value.find { it.id == media.id || (it.tmdbId != null && it.tmdbId == media.tmdbId && it.type == media.type) }?.ageRating
  }

  fun verifyParentalPinForPlayback(pin: String): Boolean {
    val isValid = AppSettingsRepository.verifyParentalControlPin(pin)
    if (isValid) {
      _showParentalPinDialog.value = false
      _parentalPinErrorResId.value = null
      val pending = pendingPlayback
      pendingPlayback = null
      if (pending != null) {
        currentlyUnlockedMediaId = pending.media.id
        executeOpenPlayer(pending.media, pending.episode, pending.source)
      }
      return true
    } else {
      _parentalPinErrorResId.value = R.string.parental_playback_pin_incorrect
      return false
    }
  }

  fun dismissParentalPinDialog() {
    _showParentalPinDialog.value = false
    _parentalPinErrorResId.value = null
    pendingPlayback = null
    resetStreamState()
  }

  /** Interroga `GET https://api.torbox.app/v1/api/user/me` per verificare la chiave. */
  fun verifyTorBoxAccount() {
    if (_torBoxAccountState.value is TorBoxAccountState.Checking) return
    viewModelScope.launch {
      _torBoxAccountState.value = TorBoxAccountState.Checking
      _torBoxAccountState.value = when (val result = TorBoxRepository.verifyAccount()) {
        is TorBoxRepository.AccountCheck.Valid -> TorBoxAccountState.Connected(
          email = result.user.email,
          plan = result.user.plan
        )
        is TorBoxRepository.AccountCheck.Invalid -> TorBoxAccountState.Error(result.message)
        TorBoxRepository.AccountCheck.NotConfigured -> TorBoxAccountState.NotConfigured
      }
    }
  }

  // ── ADDON STREMIO ─────────────────────────────────────────────────

  /** Lista degli addon Stremio installati (persistita in JSON). */
  val installedAddons: StateFlow<List<InstalledAddon>> = StremioAddonRepository.addons

  private val _addonInstalling = MutableStateFlow(false)
  val addonInstalling: StateFlow<Boolean> = _addonInstalling.asStateFlow()

  private val _addonMessage = MutableStateFlow<String?>(null)
  /** Feedback operazioni install/rimozione mostrato nella schermata Addon. */
  val addonMessage: StateFlow<String?> = _addonMessage.asStateFlow()

  /**
   * Installa un addon dall'URL del manifest (normalizzato, scaricato e validato).
   * Il risultato (ok/errore) viene pubblicato su [addonMessage].
   */
  fun installAddon(rawUrl: String) {
    if (_addonInstalling.value) return
    viewModelScope.launch {
      _addonInstalling.value = true
      _addonMessage.value = null
      try {
        val addon = StremioAddonRepository.installAddon(rawUrl)
        _addonMessage.value = "Addon installato: ${addon.manifest.displayTitle}"
      } catch (e: Exception) {
        _addonMessage.value = "Installazione fallita: ${e.message ?: "errore sconosciuto"}"
      } finally {
        _addonInstalling.value = false
      }
    }
  }

  fun removeAddon(id: String) {
    StremioAddonRepository.removeAddon(id)
    _addonMessage.value = "Addon rimosso"
  }

  fun setAddonEnabled(id: String, enabled: Boolean) {
    StremioAddonRepository.setAddonEnabled(id, enabled)
  }

  fun clearAddonMessage() {
    _addonMessage.value = null
  }

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
    Log.d("BACK_TRACE", "STREAMNOVAVM: openPlayer() entry")
    if (shouldBlockForParentalControl(media)) {
      pendingPlayback = PendingPlayback(media, episode, source)
      _parentalPinErrorResId.value = null
      _showParentalPinDialog.value = true
      return
    }
    executeOpenPlayer(media, episode, source)
  }

  private fun executeOpenPlayer(media: MediaItem, episode: Episode? = null, source: StreamSource? = null) {
    val isTv = media.type == MediaType.SERIE_TV
    val effectiveEpisode = if (isTv && episode == null) {
      val s = media.lastWatchedSeason ?: 1
      val e = media.lastWatchedEpisode ?: 1
      media.episodes.find { it.seasonNumber == s && it.episodeNumber == e }
        ?: media.episodes.firstOrNull()
        ?: Episode(
            id = "${media.id}_s${s}e${e}",
            seasonNumber = s,
            episodeNumber = e,
            title = "Episodio $e",
            synopsis = "",
            durationMinutes = media.durationMinutes.takeIf { it > 0 } ?: 55,
            videoUrl = media.videoUrl
          )
    } else {
      episode
    }

    val savedInitialPos = if (isTv && effectiveEpisode != null) {
      val saved = MediaRepository.getEpisodeProgress(media.id, effectiveEpisode.seasonNumber, effectiveEpisode.episodeNumber)
      if (saved > 0L) saved else effectiveEpisode.currentProgressMs
    } else {
      media.currentProgressMs
    }
    val totalDur = effectiveEpisode?.totalDurationMs ?: media.totalDurationMs
    // "Riprendi automaticamente la riproduzione": se OFF si parte da 0:00 SENZA
    // cancellare il progresso salvato (il valore resta in Room).
    // Se l'episodio è già concluso (>= 90% o <15s alla fine), si riparte da 0:00
    // per non far scattare istantaneamente il fine riproduzione e l'auto-advance.
    val isAlreadyFinished = totalDur > 0L && (savedInitialPos >= (totalDur * 0.90f) || (totalDur - savedInitialPos) <= 15_000L)
    val initialPos = if (SettingsRepository.settings.value.autoResume && !isAlreadyFinished) savedInitialPos else 0L

    _playbackState.value = PlayerPlaybackState(
      media = media,
      currentEpisode = effectiveEpisode?.copy(currentProgressMs = initialPos),
      isPlaying = true,
      currentPositionMs = initialPos,
      durationMs = totalDur,
      selectedResolution = media.resolution,
      areControlsVisible = true,
      streamUrl = source?.streamUrl,
      streamHeaders = source?.headers ?: emptyMap(),
      streamQuality = source?.quality,
      streamServer = source?.serverName,
      streamProgressive = source?.isProgressive
    )
    // Memorizza l'origine reale (DETAIL / PROVIDER / BROWSING) per un ritorno corretto al BACK.
    screenBeforePlayer = _screenState.value
    sectionBeforePlayer = _currentSection.value
    _screenState.value = ScreenState.PLAYER
  }

  /**
   * Salva il progresso della sessione corrente di riproduzione:
   * se serie TV, lo associa specificamente alla coppia (stagione, episodio) in Room;
   * se film, lo associa al mediaId del film.
   */
  fun saveCurrentPlaybackProgress(finalPos: Long) {
    val current = _playbackState.value
    val media = current.media ?: return
    if (finalPos <= 0L) return

    if (media.type == MediaType.SERIE_TV) {
      val ep = current.currentEpisode
      val s = ep?.seasonNumber ?: media.lastWatchedSeason ?: 1
      val e = ep?.episodeNumber ?: media.lastWatchedEpisode ?: 1
      MediaRepository.updateEpisodeProgress(media.id, s, e, finalPos, current.durationMs)
    } else {
      MediaRepository.updateProgress(media.id, finalPos)
    }
  }

  /**
   * Passaggio manuale all'episodio successivo:
   * 1. Salva la posizione attuale dell'episodio corrente.
   * 2. Recupera la posizione persistita del nuovo episodio (0L se mai iniziato).
   * 3. Avvia la riproduzione del nuovo episodio dalla sua posizione specifica.
   */
  fun playNextEpisode(nextEpisode: Episode) {
    val current = _playbackState.value
    val media = current.media ?: return
    if (current.currentPositionMs > 0L) {
      saveCurrentPlaybackProgress(current.currentPositionMs)
    }

    val nextSavedRaw = MediaRepository.getEpisodeProgress(
      media.id,
      nextEpisode.seasonNumber,
      nextEpisode.episodeNumber
    )
    // Il nuovo episodio usa il proprio progresso (seasonNumber/episodeNumber) e,
    // se "Riprendi automaticamente" è OFF o se l'episodio era già completato, parte da 0.
    val isNextFinished = nextEpisode.totalDurationMs > 0L && (nextSavedRaw >= (nextEpisode.totalDurationMs * 0.90f) || (nextEpisode.totalDurationMs - nextSavedRaw) <= 15_000L)
    val nextSavedPos = if (SettingsRepository.settings.value.autoResume && !isNextFinished) nextSavedRaw else 0L
    val enrichedNext = nextEpisode.copy(currentProgressMs = nextSavedPos)

    _playbackState.value = current.copy(
      currentEpisode = enrichedNext,
      currentPositionMs = nextSavedPos,
      durationMs = enrichedNext.totalDurationMs,
      streamUrl = null
    )

    loadStream(media, enrichedNext)
  }

  /**
   * Punto d'ingresso per l'avvio della riproduzione di un MediaItem (e relativo episodio se serie TV).
   * - Cancella tassativamente il job precedente.
   * - Imposta immediatamente lo stato su Loading.
   * - Esegue la ricerca con timeout rigido di 7 secondi.
   * - Al successo imposta lo stato su Success e avvia il player.
   * - Al fallimento o timeout imposta lo stato su Error.
   */
  fun openStreamForMedia(mediaItem: MediaItem, episode: Episode? = null) {
    Log.d(TAG, "openStreamForMedia START mediaId=${mediaItem.id} tmdbId=${mediaItem.tmdbId}")

    if (shouldBlockForParentalControl(mediaItem)) {
      pendingPlayback = PendingPlayback(mediaItem, episode, null)
      _parentalPinErrorResId.value = null
      _showParentalPinDialog.value = true
      return
    }

    // Cancella tassativamente il Job precedente
    streamJob?.cancel()
    loadStreamJob?.cancel()
    loadStreamGeneration++
    _playbackState.value = _playbackState.value.copy(streamUrl = null)
    _availableSources.value = emptyList()

    // Imposta subito lo stato su Loading
    _streamResolutionState.value = StreamResolutionState.Loading
    _streamResult.value = StreamResult.Loading("Ricerca sorgenti in corso...")

    val isTv = mediaItem.type == MediaType.SERIE_TV
    val effectiveEpisode = if (isTv && episode == null) {
      val s = mediaItem.lastWatchedSeason ?: 1
      val e = mediaItem.lastWatchedEpisode ?: 1
      mediaItem.episodes.find { it.seasonNumber == s && it.episodeNumber == e }
        ?: mediaItem.episodes.firstOrNull()
    } else {
      episode
    }

    val currentJobGeneration = loadStreamGeneration
    streamJob = viewModelScope.launch(Dispatchers.IO) {
      val mode = AppSettingsRepository.streamingEngineMode.value
      val result = withTimeoutOrNull(7000L) {
        streamManager.resolveBestStream(mediaItem, effectiveEpisode, mode)
      }
      withContext(Dispatchers.Main) {
        if (currentJobGeneration != loadStreamGeneration) {
          Log.d(TAG, "Job ignorato perché una nuova ricerca o cancellazione è avvenuta")
          return@withContext
        }
        if (result != null && !result.streamUrl.isNullOrBlank()) {
          _streamResolutionState.value = StreamResolutionState.Success(
            streamUrl = result.url,
            mediaItem = mediaItem,
            episode = effectiveEpisode,
            streamHeaders = result.headers,
            quality = result.quality,
            serverName = result.serverName,
            isProgressive = result.isProgressive
          )
          _streamResult.value = StreamResult.Success(listOf(result))

          val shouldShowDialog = mode == StreamingEngineMode.DEBRID_TORBOX && !AppSettingsRepository.autoplayEnabled.value
          if (shouldShowDialog) {
            _availableSources.value = listOf(result)
            pendingMediaForSourceSelection = mediaItem
            pendingEpisodeForSourceSelection = effectiveEpisode
            _showSourceDialog.value = true
            _streamResolutionState.value = StreamResolutionState.Idle
            return@withContext
          }

          openPlayer(mediaItem, effectiveEpisode, result)
        } else {
          _streamResolutionState.value = StreamResolutionState.Error("Nessuna sorgente disponibile per questo titolo.")
          _streamResult.value = StreamResult.Error("Nessuna sorgente disponibile per questo titolo.")
        }
      }
    }
  }

  fun loadStream(media: MediaItem, episode: Episode? = null) {
    openStreamForMedia(media, episode)
  }

  fun upgradeStreamSource(source: StreamSource) {
    val current = _playbackState.value
    val sourceUrl = source.streamUrl ?: return
    if (current.streamUrl == sourceUrl) return
    _playbackState.value = current.copy(
      streamUrl = sourceUrl,
      streamHeaders = source.headers,
      streamQuality = source.quality,
      streamServer = source.serverName,
      streamProgressive = source.isProgressive
    )
  }

  fun selectSource(source: StreamSource) {
    loadStreamJob?.cancel()
    loadStreamJob = null
    viewModelScope.launch {
      val media = pendingMediaForSourceSelection ?: return@launch
      val episode = pendingEpisodeForSourceSelection

      _isLoading.value = true
      _errorMessage.value = null

      val resolvedSource = if (source.infoHash != null && source.streamUrl == null) {
        Log.i(TAG, "TorBox: sblocco on-demand per ${source.infoHash}")
        Log.i(
          "[StreamNova-TorBox-Debug]",
          "1. DATI STREAM DALL'ADDON [Manuale] -> server='${source.serverName}', title='${source.releaseTitle}', infoHash=${source.infoHash}, quality/resolution='${source.declaredQuality ?: source.quality}', details='${source.details}'"
        )
        TorBoxRepository.setRequestMetadata(episode?.seasonNumber, episode?.episodeNumber)
        val directUrl = TorBoxRepository.resolveInfoHash(source.infoHash, source.fileIdx)
        if (directUrl != null) {
          Log.i(TAG, "TorBox: flusso sbloccato -> $directUrl")
          source.copy(streamUrl = directUrl)
        } else {
          Log.w(TAG, "TorBox: impossibile sbloccare ${source.infoHash}")
          _errorMessage.value = "Impossibile sbloccare questo flusso su TorBox"
          _isLoading.value = false
          return@launch
        }
      } else {
        source
      }

      _showSourceDialog.value = false
      _availableSources.value = emptyList()
      pendingMediaForSourceSelection = null
      pendingEpisodeForSourceSelection = null
      _isLoading.value = false

      Log.i(TAG, "Source selection: ${resolvedSource.serverName} (${resolvedSource.quality})")
      Log.d("BACK_TRACE", "STREAMNOVAVM: chiamata a openPlayer()")
      openPlayer(media, episode, resolvedSource)
    }
  }

  fun dismissSourceDialog() {
    resetStreamState()
    _showSourceDialog.value = false
    _availableSources.value = emptyList()
    pendingMediaForSourceSelection = null
    pendingEpisodeForSourceSelection = null
  }

  fun closePlayer() {
    Log.d("BACK_TRACE", "STREAMNOVAVM: ingresso in closePlayer()")
    Log.i(HTTP_TRACE, "[VM] closePlayer INVOKE")
    Log.d("BACK_TRACE", "STREAMNOVAVM: valore di _screenState prima: ${_screenState.value}")
    resetStreamState()
    currentlyUnlockedMediaId = null
    val current = _playbackState.value
    if (current.currentPositionMs > 0L) {
      saveCurrentPlaybackProgress(current.currentPositionMs)
    }
    _playbackState.value = _playbackState.value.copy(
      streamUrl = null,
      isPlaying = false
    )
    _availableSources.value = emptyList()
    val origin = screenBeforePlayer
    val previousSection = sectionBeforePlayer
    screenBeforePlayer = null
    sectionBeforePlayer = SidebarSection.HOME
    _currentSection.value = previousSection
    _screenState.value = origin
      ?: if (_selectedMedia.value != null) ScreenState.DETAIL else ScreenState.BROWSING
    Log.d("BACK_TRACE", "STREAMNOVAVM: valore di _screenState dopo: ${_screenState.value}")
    Log.i(HTTP_TRACE, "[VM] closePlayer COMPLETE")
    Log.d("BACK_TRACE", "STREAMNOVAVM: uscita da closePlayer()")
  }

  /** True se il Player è stato aperto partendo dal Dettaglio (catalogo -> Detail -> Player). */
  fun playerOpenedFromDetail(): Boolean = screenBeforePlayer == ScreenState.DETAIL

  fun backToBrowsing() {
    resetStreamState()
    _selectedProvider.value = null
    _selectedMedia.value = null
    _screenState.value = ScreenState.BROWSING
  }

  fun backFromDetail() {
    resetStreamState()
    _selectedMedia.value = null
    if (_selectedProvider.value != null) {
      _screenState.value = ScreenState.PROVIDER
    } else {
      _screenState.value = ScreenState.BROWSING
      Log.d("BACK_TRACE", "STREAMNOVAVM: modifica _screenState a ${_screenState.value}")
    }
  }

  // Paginazione Remota Cataloghi Provider (Netflix, Prime Video, Disney+, Apple TV+, HBO Max)
  //
  // Lo stato è INDEPENDENTE per coppia (providerId, tipo Film/Serie): non esiste
  // alcun flag globale "hasMore", quindi la paginazione di Netflix Film non può
  // bloccare Netflix Serie TV, né tantomeno gli altri provider.
  private val _currentProviderPage = MutableStateFlow(1)
  val currentProviderPageFlow: StateFlow<Int> = _currentProviderPage.asStateFlow()
  var currentProviderPage: Int
    get() = _currentProviderPage.value
    private set(value) {
      _currentProviderPage.value = value
    }

  private val _isProviderLoading = MutableStateFlow(false)
  val isProviderLoading: StateFlow<Boolean> = _isProviderLoading.asStateFlow()

  /** Stato "ci sono altre pagine" per OGNI coppia (providerId, tipo). */
  private val _providerHasMorePages = MutableStateFlow<Map<Pair<Int, Boolean>, Boolean>>(emptyMap())
  val providerHasMorePages: StateFlow<Map<Pair<Int, Boolean>, Boolean>> = _providerHasMorePages.asStateFlow()

  /** Ultima pagina caricata in sessione per coppia (providerId, tipo): 0 = nessuna. */
  private val providerPages = mutableMapOf<Pair<Int, Boolean>, Int>()
  private val loadingProviderKeys = mutableSetOf<Pair<Int, Boolean>>()

  /** Interrogazione puntuale dello stato hasMore della singola coppia (provider, tipo). */
  fun hasMoreProviderPages(providerId: Int, isTv: Boolean): Boolean =
    _providerHasMorePages.value[Pair(providerId, isTv)] ?: true

  /** Reset della paginazione di UN provider (entrambi i tipi, indipendentemente dagli altri). */
  fun resetProviderPagination(providerId: Int) {
    listOf(true, false).forEach { isTv ->
      val key = Pair(providerId, isTv)
      providerPages.remove(key)
      _providerHasMorePages.update { it - key }
    }
    _isProviderLoading.value = loadingProviderKeys.isNotEmpty()
  }

  fun loadNextProviderPage(providerId: Int, isTv: Boolean) {
    val key = Pair(providerId, isTv)
    if (loadingProviderKeys.contains(key)) return
    if (!hasMoreProviderPages(providerId, isTv)) return
    loadingProviderKeys.add(key)
    _isProviderLoading.value = true

    viewModelScope.launch {
      try {
        val loadedPage = providerPages[key] ?: 0
        val nextPage = if (loadedPage > 0) {
          loadedPage + 1
        } else {
          // Ripresa dalla progressione persistita (Room) di QUESTA coppia
          val persisted = MediaRepository.getProviderPaginationState(providerId, isTv)
          if (!persisted.hasMore) {
            _providerHasMorePages.update { it + (key to false) }
            return@launch
          }
          persisted.nextPage
        }
        if (nextPage > MediaRepository.MAX_PROVIDER_PAGES) {
          _providerHasMorePages.update { it + (key to false) }
          return@launch
        }

        MediaRepository.loadProviderCatalog(providerId, isTv, nextPage).collect { page ->
          Log.d(
            "PROVIDER_CATALOG",
            "results provider=$providerId isTv=$isTv page=${page.page} -> " +
              "${page.items.size} titoli (total_pages=${page.totalPages})"
          )
          providerPages[key] = page.page
          _providerHasMorePages.update { it + (key to page.hasMore) }
          if (_selectedProvider.value?.tmdbProviderId == providerId) {
            currentProviderPage = page.page
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Errore caricamento pagina provider $providerId (isTv=$isTv): ${e.message}")
      } finally {
        loadingProviderKeys.remove(key)
        _isProviderLoading.value = loadingProviderKeys.isNotEmpty()
      }
    }
  }

  fun openProvider(provider: StreamingProvider) {
    Log.d("PROVIDER_CATALOG", "Fetching catalog for provider=${provider.name} (tmdbId=${provider.tmdbProviderId})")
    _selectedProvider.value = provider
    _screenState.value = ScreenState.PROVIDER
    resetProviderPagination(provider.tmdbProviderId)
    // Crunchyroll (283): catalogo primariamente TV/anime -> prima le serie TV
    if (provider.tmdbProviderId == 283) {
      loadNextProviderPage(provider.tmdbProviderId, isTv = true)
      loadNextProviderPage(provider.tmdbProviderId, isTv = false)
    } else {
      loadNextProviderPage(provider.tmdbProviderId, isTv = false)
      loadNextProviderPage(provider.tmdbProviderId, isTv = true)
    }
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

  fun removeFromContinueWatching(mediaId: String) {
    Log.d("REMOVE_CW", "ViewModel.removeFromContinueWatching called with mediaId=$mediaId")
    viewModelScope.launch {
      MediaRepository.removeFromContinueWatching(mediaId)
      Log.d("REMOVE_CW", "ViewModel: MediaRepository.removeFromContinueWatching completed")
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
