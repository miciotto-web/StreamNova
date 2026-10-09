package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
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
import com.example.data.repository.HomeCatalogs
import com.example.data.repository.ProviderCoverage
import com.example.data.repository.StremioCatalogRepository
import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.StremioAddonRepository
import com.example.data.torbox.TorBoxRepository
import com.example.data.update.UpdateCheckState
import com.example.data.update.UpdateDownloadState
import com.example.data.update.UpdateRepository
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamResult
import com.example.data.streaming.StreamSource
import com.example.data.opensubtitles.OpenSubtitlesSubtitleAdapter
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.domain.model.Subtitle
import com.example.ui.components.StreamingProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
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
/** Timeout per l'interrogazione dei cataloghi Stremio durante la ricerca testuale. */
private const val STREMIO_SEARCH_TIMEOUT_MS = 8_000L
private var contentIdCounter = 0

/**
 * Mappatura deterministica TMDB genre ID → nome genere canonico TMDB (elenco ufficiale
 * dei generi TMDB per i film).
 *
 * I nomi NON vengono inventati: sono quelli dichiarati da TMDB. Un valore di questa
 * tabella viene usato come extra `genre` SOLO se un catalogo Stremio attivo dichiara
 * esattamente la stessa opzione nel proprio manifest (cfr.
 * `StreamNovaViewModel.resolveStremioGenre`), altrimenti resta il percorso TMDB.
 */
internal val TMDB_MOVIE_GENRE_NAMES: Map<Int, String> = mapOf(
  28 to "Action",
  12 to "Adventure",
  16 to "Animation",
  35 to "Comedy",
  80 to "Crime",
  99 to "Documentary",
  18 to "Drama",
  10751 to "Family",
  14 to "Fantasy",
  36 to "History",
  27 to "Horror",
  10402 to "Music",
  9648 to "Mystery",
  10749 to "Romance",
  878 to "Science Fiction",
  10770 to "TV Movie",
  53 to "Thriller",
  10752 to "War",
  37 to "Western"
)

/**
 * Mappatura deterministica TMDB genre ID → nome genere canonico TMDB per le serie TV.
 * Stesse regole di [TMDB_MOVIE_GENRE_NAMES].
 */
internal val TMDB_TV_GENRE_NAMES: Map<Int, String> = mapOf(
  10759 to "Action & Adventure",
  16 to "Animation",
  35 to "Comedy",
  80 to "Crime",
  99 to "Documentary",
  18 to "Drama",
  10751 to "Family",
  10765 to "Sci-Fi & Fantasy",
  10762 to "Kids",
  10763 to "News",
  10764 to "Reality",
  10768 to "War & Politics",
  9648 to "Mystery"
)

/**
 * Origine dei contenuti mostrati in una categoria della sezione Ricerca.
 */
enum class SearchCategorySource {
  /** Contenuti restituiti dai cataloghi degli addon (Xperience/Stremio). */
  ADDON,

  /**
   * Fallback ESPPLICITO a TMDB Discover: il catalogo addon non è stato caricato
   * (assente nel manifest, errore o risposta vuota). La UI deve mostrarlo come
   * fallback, mai come caricamento addon riuscito.
   */
  TMDB_FALLBACK
}

/**
 * Stato del caricamento della categoria attiva della sezione Ricerca.
 *
 * @param source null finché il primo caricamento non è terminato (solo spinner).
 * @param addonLabel etichetta del catalogo addon caricato ("catalogo • addon").
 * @param statusMessage motivo del fallback oppure errore riscontrato sugli addon:
 *        mostrato in UI per non mascherare un catalogo non caricato.
 */
data class SearchCategoryState(
  val source: SearchCategorySource? = null,
  val addonLabel: String? = null,
  val statusMessage: String? = null
)

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
  /**
   * Sottotitoli esterni della sorgente attiva, gia' convertiti nel modello interno.
   * Il player li legge per allegare le `SubtitleConfiguration` al Media3 `MediaItem`.
   */
  val subtitles: List<Subtitle> = emptyList(),
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

/**
 * Bersaglio della selezione sorgente TorBox: il media e l'episodio (se serie TV)
 * per cui sono state risolte le [StreamSource]. Serve solo alla UI della schermata
 * di selezione, che ricava poster/backdrop/logo da [media] senza nuove richieste.
 */
data class SourceSelectionTarget(
  val media: MediaItem,
  val episode: Episode? = null
)

class StreamNovaViewModel(
  /**
   * Dispatcher per il lavoro di I/O della risoluzione stream (addon, TorBox,
   * sottotitoli). Iniettabile: nei test si passa un `TestDispatcher` per rendere
   * l'esecuzione asincrona deterministica e attendibile con `advanceUntilIdle()`.
   */
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

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

  /**
   * Media/episodio per cui è in corso la selezione sorgente TorBox. Esposto alla UI
   * per comporre la schermata di selezione (poster, backdrop, logo, SxEx) senza
   * introdurre nuove chiamate di rete: sono gli stessi dati già risolti dal flow.
   */
  private val _sourceSelectionTarget = MutableStateFlow<SourceSelectionTarget?>(null)
  val sourceSelectionTarget: StateFlow<SourceSelectionTarget?> = _sourceSelectionTarget.asStateFlow()
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
      if (categoryId == null) {
        // Uscita dalla categoria: invalida eventuali caricamenti ancora in volo
        // e azzera stato, origine e risultati.
        categoryLoadGeneration++
        activeSearchCategoryId = null
        activeCategoryEntries = emptyList()
        _categoryItems.value = emptyList()
        _categoryState.value = SearchCategoryState()
        _isCategoryLoading.value = false
      }
    }

  // Interrogazione parallela dei provider di streaming HTTP diretto:
  // 1) VixSrc/VixCloud (istantaneo via TMDB ID), 2) CB01 e 3) Eurostreaming
  // (cataloghi italiani, ricerca per titolo/anno) con timeout per ciascuno.
  internal var streamManager = StreamManager()
  internal var openSubtitlesProvider = OpenSubtitlesSubtitleProvider()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _errorMessage = MutableStateFlow<String?>(null)
  val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

  private val _isRefreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

  val allMedia: StateFlow<List<MediaItem>> = MediaRepository.mediaList

  private val _homeCatalogs = MutableStateFlow(
    StremioCatalogRepository.buildTmdbHomeCatalogs(MediaRepository.mediaList.value)
  )
  val homeCatalogs: StateFlow<HomeCatalogs> = _homeCatalogs.asStateFlow()

  private val _stremioProviderMedia = MutableStateFlow<Map<String, List<MediaItem>>>(emptyMap())
  val stremioProviderMedia: StateFlow<Map<String, List<MediaItem>>> = _stremioProviderMedia.asStateFlow()

  /** Stato del catalogo Stremio per provider: decide la fonte dati della schermata Provider. */
  data class StremioProviderCatalogState(
    val hasConfirmedBindings: Boolean = false,
    val itemCount: Int = 0,
    val confirmedCatalogs: Int = 0,
    /** Copertura del catalogo provider: NONE = TMDB, PARTIAL = merge, FULL = solo Stremio. */
    val coverage: ProviderCoverage = ProviderCoverage.NONE
  )

  private val _stremioProviderStates = MutableStateFlow<Map<String, StremioProviderCatalogState>>(emptyMap())
  val stremioProviderStates: StateFlow<Map<String, StremioProviderCatalogState>> = _stremioProviderStates.asStateFlow()

  fun refreshHomeCatalogs(mediaList: List<MediaItem> = allMedia.value) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val resolved = StremioCatalogRepository.resolveHomeCatalogs(mediaList)
        _homeCatalogs.value = resolved
      } catch (e: Exception) {
        Log.w(TAG, "Risoluzione cataloghi Home fallita: ${e.message}")
      }
    }
  }

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
    viewModelScope.launch {
      allMedia.collect { list ->
        refreshHomeCatalogs(list)
      }
    }
    viewModelScope.launch {
      StremioAddonRepository.addons.collect {
        _stremioProviderMedia.value = emptyMap()
        _stremioProviderStates.value = emptyMap()
        StremioCatalogRepository.resetProviderPaging()
        refreshHomeCatalogs(allMedia.value)
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

  // ── CONTROLLO AGGIORNAMENTI (GitHub Releases) ─────────────────────

  val updateCheckState: StateFlow<UpdateCheckState> = UpdateRepository.state

  val updateDownloadState: StateFlow<UpdateDownloadState> = UpdateRepository.downloadState

  fun checkForAppUpdates() {
    UpdateRepository.checkForUpdates(BuildConfig.VERSION_NAME)
  }

  fun downloadUpdate(context: Context) {
    UpdateRepository.downloadUpdate(context)
  }

  fun dismissUpdateCheckResult() {
    UpdateRepository.reset()
  }

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
      combine(
        MediaRepository.searchTmdb(query, filter),
        stremioSearchFlow(query, filter)
      ) { tmdbResults, stremioResults ->
        // Xperience/Stremio disponibile → risultati Stremio in testa; senza risultati
        // Stremio la lista TMDB resta esattamente quella precedente.
        if (stremioResults.isEmpty()) {
          tmdbResults
        } else {
          StremioCatalogRepository.deduplicateCrossSource(stremioResults + tmdbResults)
        }
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /**
   * Ricerca testuale sui cataloghi Stremio/Xperience attivi tramite l'extra `search`.
   *
   * Usa esclusivamente il percorso già adottato dalla Home:
   * `StremioCatalogRepository.searchAddonCatalogs(query, type)` →
   * `StremioCatalogEngine.searchCatalogs` (solo addon attivi, solo cataloghi che
   * dichiarano `search` nel manifest, con rispetto di type e catalogId).
   *
   * Emette subito una lista vuota così i risultati TMDB non vengono ritardati;
   * se l'interrogazione Stremio fallisce o supera il timeout, la ricerca TMDB
   * prosegue invariata.
   */
  private fun stremioSearchFlow(query: String, filter: SearchTypeFilter): Flow<List<MediaItem>> = flow {
    val cleanQuery = query.trim()
    val mediaType = when (filter) {
      SearchTypeFilter.FILM -> MediaType.FILM
      SearchTypeFilter.SERIE_TV -> MediaType.SERIE_TV
      SearchTypeFilter.ALL -> null
    }
    // Stessa soglia minima della ricerca TMDB remota.
    if (cleanQuery.length < 2) {
      emit(emptyList())
      return@flow
    }
    emit(emptyList())
    val results = try {
      withTimeoutOrNull(STREMIO_SEARCH_TIMEOUT_MS) {
        StremioCatalogRepository.searchAddonCatalogs(cleanQuery, mediaType)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Ricerca cataloghi Stremio fallita per \"$cleanQuery\": ${e.message}")
      null
    }
    if (!results.isNullOrEmpty()) {
      emit(results)
    }
  }

  // ── CATEGORIE SEZIONE RICERCA ─────────────────────────────────────
  private val _categoryItems = MutableStateFlow<List<MediaItem>>(emptyList())
  val categoryItems: StateFlow<List<MediaItem>> = _categoryItems.asStateFlow()

  private val _isCategoryLoading = MutableStateFlow(false)
  val isCategoryLoading: StateFlow<Boolean> = _isCategoryLoading.asStateFlow()

  private val _categoryState = MutableStateFlow(SearchCategoryState())
  /** Origine e stato del caricamento della categoria attiva (vedi [SearchCategoryState]). */
  val categoryState: StateFlow<SearchCategoryState> = _categoryState.asStateFlow()

  /** ID della categoria attualmente caricata in memoria (null se nessuna). */
  var activeSearchCategoryId: String? = null
    private set

  /** Keyword (etichette categoria + nomi canonici TMDB) della categoria attiva. */
  private var activeCategoryKeywords: List<String> = emptyList()
  private var activeCategoryMovieGenreId: Int? = null
  private var activeCategoryTvGenreId: Int? = null
  /** Sezioni addon caricate per la categoria attiva (per la paginazione `skip`). */
  private var activeCategoryEntries: List<StremioCatalogRepository.CategoryCatalogEntry> = emptyList()
  /** Contatore generazione: scarta le risposte riferite a una categoria già chiusa. */
  private var categoryLoadGeneration = 0
  private var currentCategoryPage = 1
  private var isCategoryLoadingMore = false

  /**
   * Apre una categoria della sezione Ricerca.
   *
   * I cataloghi degli addon (Xperience/Stremio) NON vengono indicizzati per id
   * codificato in app: [keywords] (etichette della categoria più nomi canonici
   * TMDB del genere) vengono confrontate con id, titoli ed extra `genre`
   * REALMENTE dichiarati dai manifest installati, tramite
   * [StremioCatalogRepository.findCategoryRequests] →
   * [StremioCatalogRepository.loadCategoryCatalogs] (stesso percorso della Home).
   *
   * Prima di ogni nuovo caricamento i risultati della categoria precedente vengono
   * azzerati e non esiste alcun seed da `allMedia`: la categoria mostra solo ciò
   * che l'addon ha restituito oppure un fallback TMDB EXPLICITAMENTE dichiarato in
   * [SearchCategoryState].
   *
   * @param categoryId id della categoria (per diagnostica e test).
   * @param keywords etichette/descrizioni della categoria dalla quale cercare i cataloghi.
   * @param movieGenreId id genere TMDB film (fallback TMDB Discover esplicito).
   * @param tvGenreId id genere TMDB serie (fallback TMDB Discover esplicito).
   */
  fun initCategory(
    categoryId: String,
    keywords: List<String>,
    movieGenreId: Int?,
    tvGenreId: Int?
  ) {
    activeSearchCategoryId = categoryId
    activeCategoryKeywords = keywords +
      listOfNotNull(movieGenreId?.let { TMDB_MOVIE_GENRE_NAMES[it] }) +
      listOfNotNull(tvGenreId?.let { TMDB_TV_GENRE_NAMES[it] })
    activeCategoryMovieGenreId = movieGenreId
    activeCategoryTvGenreId = tvGenreId
    activeCategoryEntries = emptyList()
    currentCategoryPage = 1
    // Generazione nuova: le risposte della categoria precedente vengono ignorate.
    categoryLoadGeneration++
    // I poster della categoria precedente spariscono subito: durante il caricamento
    // la griglia resta vuota con lo spinner attivo.
    _categoryItems.value = emptyList()
    _categoryState.value = SearchCategoryState()
    _isCategoryLoading.value = true
    loadInitialCategoryPage(categoryLoadGeneration)
  }

  /**
   * Prima pagina della categoria: prima i cataloghi addon, poi (solo se l'addon non
   * ha prodotto contenuti) il fallback TMDB Discover dichiarato nello stato.
   */
  private fun loadInitialCategoryPage(generation: Int) {
    viewModelScope.launch {
      try {
        val addonLoad = try {
          StremioCatalogRepository.loadCategoryCatalogs(activeCategoryKeywords)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Caricamento cataloghi categoria fallito: ${e.message}")
          null
        }
        if (generation != categoryLoadGeneration) return@launch

        if (addonLoad != null && addonLoad.items.isNotEmpty()) {
          // Sorgente primaria: SOLO i contenuti restituiti dai cataloghi dell'addon.
          activeCategoryEntries = addonLoad.entries
          _categoryItems.value = addonLoad.items
          _categoryState.value = SearchCategoryState(
            source = SearchCategorySource.ADDON,
            addonLabel = addonLoad.entries
              .firstOrNull { it.section.items.isNotEmpty() }
              ?.section
              ?.title,
            statusMessage = listOfNotNull(
              addonLoad.errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
              missingCatalogTypeNotice(addonLoad),
              emptyCatalogTypeNotice(addonLoad)
            ).joinToString("\n").takeIf { it.isNotBlank() }
          )
          return@launch
        }

        // Nessun contenuto addon: il fallback è dichiarato in modo distinguibile,
        // così la UI non può presentarlo come un caricamento addon riuscito.
        _categoryState.value = SearchCategoryState(
          source = SearchCategorySource.TMDB_FALLBACK,
          statusMessage = listOfNotNull(
            addonFallbackMessage(addonLoad),
            missingCatalogTypeNotice(addonLoad)
          ).joinToString("\n")
        )
        val tmdbItems = try {
          MediaRepository.loadCategoryPage(
            activeCategoryMovieGenreId,
            activeCategoryTvGenreId,
            1
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Fallback TMDB Discover categoria fallito: ${e.message}")
          emptyList()
        }
        if (generation != categoryLoadGeneration) return@launch
        currentCategoryPage = 1
        _categoryItems.value = tmdbItems
      } finally {
        if (generation == categoryLoadGeneration) {
          _isCategoryLoading.value = false
        }
      }
    }
  }

  /** Motivo esplicito del fallback TMDB, mostrato dalla UI come avviso. */
  private fun addonFallbackMessage(
    load: StremioCatalogRepository.CategoryCatalogLoad?
  ): String {
    val reason = when {
      load == null -> "catalogo non interrogabile"
      load.matchedTargets == 0 -> "nessun catalogo dichiarato nel manifest per questa categoria"
      load.errors.isNotEmpty() -> load.errors.joinToString("\n")
      else -> "catalogo vuoto"
    }
    return "Addon Xperience/Stremio non caricato: $reason — fallback TMDB Discover"
  }

  /**
   * Avviso quando la categoria prevede un tipo di contenuto (dal genere TMDB
   * dichiarato per film/serie) ma per quel tipo NON viene richiesto alcun
   * catalogo.
   *
   * I due motivi sono distinti:
   *  - il manifest dichiara cataloghi di quel tipo ma nessuno ha un genere
   *    corrispondente alla categoria → "categoria non supportata per le serie";
   *  - il manifest non dichiara alcun catalogo di quel tipo → "nessun catalogo".
   *
   * La mancanza è dell'addon e non viene camuffata come errore dell'app né
   * compensata con contenuti inventati. Se mancano ENTRAMBI i tipi non serve
   * alcun avviso perché [addonFallbackMessage] dichiara già l'assenza totale.
   */
  private fun missingCatalogTypeNotice(
    load: StremioCatalogRepository.CategoryCatalogLoad?
  ): String? {
    if (load == null || load.matchedTargets == 0) return null
    val notices = buildList {
      if (activeCategoryMovieGenreId != null && load.matchedMovieTargets == 0) {
        add(
          if (load.availableMovieCatalogs > 0) {
            "Categoria non supportata per i film: il manifest non dichiara un genere corrispondente"
          } else {
            "Nessun catalogo film dichiarato nel manifest"
          }
        )
      }
      if (activeCategoryTvGenreId != null && load.matchedSeriesTargets == 0) {
        add(
          if (load.availableSeriesCatalogs > 0) {
            "Categoria non supportata per le serie: il manifest non dichiara un genere corrispondente"
          } else {
            "Nessun catalogo serie dichiarato nel manifest"
          }
        )
      }
    }
    return notices.joinToString("\n").takeIf { it.isNotBlank() }
  }

  /**
   * Avviso quando il catalogo di un tipo è stato REALMENTE interrogato ma ha
   * restituito zero contenuti mentre altre sezioni della categoria hanno
   * risultati: distinto dall'assenza del catalogo ([missingCatalogTypeNotice]).
   */
  private fun emptyCatalogTypeNotice(
    load: StremioCatalogRepository.CategoryCatalogLoad?
  ): String? {
    if (load == null) return null
    val sections = load.entries.map { it.section }
    if (sections.isEmpty() || sections.none { it.items.isNotEmpty() }) return null
    val emptyTypes = buildList {
      if (activeCategoryTvGenreId != null) {
        val series = sections.filter { it.mediaType == MediaType.SERIE_TV }
        if (series.isNotEmpty() && series.all { it.items.isEmpty() }) add("serie TV")
      }
      if (activeCategoryMovieGenreId != null) {
        val movies = sections.filter { it.mediaType == MediaType.FILM }
        if (movies.isNotEmpty() && movies.all { it.items.isEmpty() }) add("film")
      }
    }
    if (emptyTypes.isEmpty()) return null
    return "Catalogo ${emptyTypes.joinToString(" e ")} interrogato ma senza risultati"
  }

  /**
   * Paginazione della categoria attiva: `skip` sui cataloghi addon quando la
   * sorgente è l'addon, TMDB Discover solo quando è dichiarato il fallback.
   */
  fun loadNextCategoryPage() {
    if (isCategoryLoadingMore || _isCategoryLoading.value) return
    when (_categoryState.value.source) {
      SearchCategorySource.ADDON -> loadNextCategoryAddonPage()
      SearchCategorySource.TMDB_FALLBACK -> loadNextCategoryTmdbPage()
      null -> Unit
    }
  }

  /** Pagina i cataloghi addon della categoria con l'extra `skip` (se dichiarato). */
  private fun loadNextCategoryAddonPage() {
    val generation = categoryLoadGeneration
    val previous = activeCategoryEntries
    if (previous.none { it.section.supportsSkip && it.section.items.isNotEmpty() }) return
    isCategoryLoadingMore = true
    viewModelScope.launch {
      try {
        val updated = StremioCatalogRepository.paginateCategoryCatalogs(previous)
        if (generation != categoryLoadGeneration) return@launch
        val fresh = mutableListOf<MediaItem>()
        updated.forEachIndexed { index, entry ->
          fresh += entry.section.items.drop(previous[index].section.items.size)
        }
        activeCategoryEntries = updated
        if (fresh.isNotEmpty()) {
          _categoryItems.value =
            StremioCatalogRepository.deduplicateCrossSource(_categoryItems.value + fresh)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione categoria addon: ${e.message}")
      } finally {
        isCategoryLoadingMore = false
      }
    }
  }

  /** Pagina successiva del fallback TMDB Discover (visibile solo se dichiarato). */
  private fun loadNextCategoryTmdbPage() {
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

  private var currentMoviePage = 1
  private var currentTvPage = 1
  private var isLoadingMoreMovies = false
  private var isLoadingMoreTv = false

  fun loadNextMoviesPage() {
    if (isLoadingMoreMovies) return
    isLoadingMoreMovies = true
    viewModelScope.launch {
      try {
        if (_homeCatalogs.value.isMoviesFromStremio) {
          val currentList = _homeCatalogs.value.allMovies
          val more = StremioCatalogRepository.paginateMovies(currentList.size)
          if (more.isNotEmpty()) {
            val combined = StremioCatalogRepository.deduplicateCrossSource(currentList + more)
            _homeCatalogs.update { it.copy(allMovies = combined) }
          }
        } else {
          currentMoviePage++
          MediaRepository.loadMoreMovies(currentMoviePage)
        }
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
        if (_homeCatalogs.value.isSeriesFromStremio) {
          val currentList = _homeCatalogs.value.allSeries
          val more = StremioCatalogRepository.paginateSeries(currentList.size)
          if (more.isNotEmpty()) {
            val combined = StremioCatalogRepository.deduplicateCrossSource(currentList + more)
            _homeCatalogs.update { it.copy(allSeries = combined) }
          }
        } else {
          currentTvPage++
          MediaRepository.loadMoreTv(currentTvPage)
        }
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
    if (tmdbId <= 0) return
    viewModelScope.launch {
      MediaRepository.loadMediaDetails(tmdbId, isTv, baseMedia)
    }
  }

  fun selectSeason(seriesTmdbId: Int, seasonNumber: Int, fallbackSeries: MediaItem? = null) {
    if (seriesTmdbId <= 0) {
      _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
      return
    }
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
    val isTv = media.type == MediaType.SERIE_TV

    val currentTmdbId = media.tmdbId
    if (currentTmdbId != null && currentTmdbId > 0) {
      loadDetails(currentTmdbId, isTv, media)
      if (isTv) {
        val initialSeason = media.lastWatchedSeason ?: 1
        selectSeason(currentTmdbId, initialSeason, media)
      } else {
        _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
      }
    } else if (media.id.startsWith("tt", ignoreCase = true)) {
      MediaRepository.setDetailLoading(media)
      viewModelScope.launch {
        val resolvedId = MediaRepository.resolveImdbToTmdbId(media.id, isTv)
        if (resolvedId != null && resolvedId > 0) {
          val mediaWithTmdb = media.copy(tmdbId = resolvedId)
          _selectedMedia.value = mediaWithTmdb
          loadDetails(resolvedId, isTv, mediaWithTmdb)
          if (isTv) {
            val initialSeason = mediaWithTmdb.lastWatchedSeason ?: 1
            selectSeason(resolvedId, initialSeason, mediaWithTmdb)
          } else {
            _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
          }
        } else {
          MediaRepository.setDetailError(
            media,
            "Titolo non trovato su TMDB per l'identificatore ${media.id}"
          )
          _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
        }
      }
    } else {
      MediaRepository.setDetailError(
        media,
        "Identificatore TMDB non disponibile per questo contenuto"
      )
      _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
    }
  }

  fun retryLoadDetail() {
    val media = _selectedMedia.value ?: return
    openDetail(media)
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
      streamProgressive = source?.isProgressive,
      subtitles = source?.subtitles ?: emptyList()
    )
    val initialSubtitles = source?.subtitles ?: emptyList()
    if (initialSubtitles.none { it.addonName == OpenSubtitlesSubtitleAdapter.PROVIDER_NAME }) {
      viewModelScope.launch(Dispatchers.IO) {
        val external = openSubtitlesProvider.fetchSubtitles(
          mediaItem = media,
          episode = effectiveEpisode,
          preferredLanguage = SettingsRepository.settings.value.preferredSubtitleLanguage
        )
        if (external.isNotEmpty()) {
          _playbackState.update { current ->
            val merged = (current.subtitles + external).distinctBy { it.id.ifBlank { it.url } }
            current.copy(subtitles = merged)
          }
        }
      }
    }
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

    val currentDetail = detailUiState.value
    val enrichedMedia = if (currentDetail is MediaDetailUiState.Success) {
      val candidate = currentDetail.media
      val matches = (mediaItem.tmdbId != null && candidate.tmdbId == mediaItem.tmdbId) ||
        (mediaItem.id.isNotBlank() && (candidate.id == mediaItem.id || candidate.id.equals(mediaItem.id, ignoreCase = true)))
      if (matches) candidate else null
    } else null

    val baseForResolution = if (enrichedMedia != null) {
      mediaItem.copy(
        id = mediaItem.id.ifBlank { enrichedMedia.id },
        title = mediaItem.title.ifBlank { enrichedMedia.title },
        originalTitle = mediaItem.originalTitle.ifBlank { enrichedMedia.originalTitle },
        synopsis = mediaItem.synopsis.ifBlank { enrichedMedia.synopsis },
        tmdbId = mediaItem.tmdbId ?: enrichedMedia.tmdbId,
        year = if (mediaItem.year > 0) mediaItem.year else enrichedMedia.year,
        provider = mediaItem.provider ?: enrichedMedia.provider,
        genres = if (mediaItem.genres.isNotEmpty()) mediaItem.genres else enrichedMedia.genres,
        episodes = if (mediaItem.episodes.isNotEmpty()) mediaItem.episodes else enrichedMedia.episodes,
        lastWatchedSeason = mediaItem.lastWatchedSeason ?: enrichedMedia.lastWatchedSeason,
        lastWatchedEpisode = mediaItem.lastWatchedEpisode ?: enrichedMedia.lastWatchedEpisode
      )
    } else {
      mediaItem
    }

    val isTv = baseForResolution.type == MediaType.SERIE_TV
    val effectiveEpisode = if (isTv && episode == null) {
      val s = baseForResolution.lastWatchedSeason ?: 1
      val e = baseForResolution.lastWatchedEpisode ?: 1
      baseForResolution.episodes.find { it.seasonNumber == s && it.episodeNumber == e }
        ?: baseForResolution.episodes.firstOrNull()
    } else {
      episode
    }

    val currentJobGeneration = loadStreamGeneration
    streamJob = viewModelScope.launch(ioDispatcher) {
      val mode = AppSettingsRepository.streamingEngineMode.value
      val resolvedMediaItem = if (baseForResolution.tmdbId == null && baseForResolution.id.startsWith("tt", ignoreCase = true)) {
        val resolvedId = MediaRepository.resolveImdbToTmdbId(baseForResolution.id, isTv)
        if (resolvedId != null && resolvedId > 0) baseForResolution.copy(tmdbId = resolvedId) else baseForResolution
      } else {
        baseForResolution
      }
      val tmdbId = resolvedMediaItem.tmdbId
      if (tmdbId == null || tmdbId <= 0) {
        withContext(Dispatchers.Main) {
          if (currentJobGeneration != loadStreamGeneration) return@withContext
          _streamResolutionState.value = StreamResolutionState.Error("Nessuna sorgente disponibile per questo titolo.")
          _streamResult.value = StreamResult.Error("Nessuna sorgente disponibile per questo titolo.")
        }
        return@launch
      }

      val openSubtitlesDeferred = async(ioDispatcher) {
        openSubtitlesProvider.fetchSubtitles(
          mediaItem = resolvedMediaItem,
          episode = effectiveEpisode,
          preferredLanguage = SettingsRepository.settings.value.preferredSubtitleLanguage
        )
      }

      val season = if (isTv) (effectiveEpisode?.seasonNumber ?: resolvedMediaItem.lastWatchedSeason ?: 1) else null
      val epNumber = if (isTv) (effectiveEpisode?.episodeNumber ?: resolvedMediaItem.lastWatchedEpisode ?: 1) else null
      val searchTitle = resolvedMediaItem.title.ifBlank { resolvedMediaItem.originalTitle }

      var hasEmittedAny = false
      var hasOpenedPlayer = false
      var currentBestUrl: String? = null

      try {
        streamManager.resolveFlow(
          tmdbId = tmdbId,
          isTv = isTv,
          season = season,
          episode = epNumber,
          title = searchTitle,
          year = resolvedMediaItem.year,
          originalTitle = resolvedMediaItem.originalTitle.takeIf { it.isNotBlank() },
          providerTag = resolvedMediaItem.provider,
          genres = resolvedMediaItem.genres,
          streamingEngineMode = mode
        ).collect { rawSources ->
          if (rawSources.isEmpty()) return@collect
          hasEmittedAny = true

          @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
          val externalSubs = if (openSubtitlesDeferred.isCompleted) {
            try {
              openSubtitlesDeferred.getCompleted()
            } catch (e: Exception) {
              emptyList()
            }
          } else {
            emptyList()
          }

          val finalSources = rawSources.map { s ->
            val merged = (s.subtitles + externalSubs).distinctBy { it.id.ifBlank { it.url } }
            s.copy(subtitles = merged)
          }

          withContext(Dispatchers.Main) {
            if (currentJobGeneration != loadStreamGeneration) {
              Log.d(TAG, "Job ignorato perché una nuova ricerca o cancellazione è avvenuta")
              return@withContext
            }

            val shouldShowDialog = mode == StreamingEngineMode.DEBRID_TORBOX && !AppSettingsRepository.autoplayEnabled.value
            if (shouldShowDialog) {
              _availableSources.value = finalSources
              _sourceSelectionTarget.value = SourceSelectionTarget(resolvedMediaItem, effectiveEpisode)
              _showSourceDialog.value = true
              _streamResolutionState.value = StreamResolutionState.Idle
              _streamResult.value = StreamResult.Idle
              return@withContext
            }

            val best = finalSources.first()
            val resolvedBest = if (best.infoHash != null && best.streamUrl == null) {
              TorBoxRepository.setRequestMetadata(season, epNumber)
              val directUrl = TorBoxRepository.resolveInfoHash(best.infoHash, best.fileIdx)
              if (directUrl != null) best.copy(streamUrl = directUrl) else null
            } else {
              best
            }

            if (resolvedBest != null && !resolvedBest.streamUrl.isNullOrBlank()) {
              _availableSources.value = finalSources
              if (!hasOpenedPlayer) {
                hasOpenedPlayer = true
                currentBestUrl = resolvedBest.streamUrl
                _streamResolutionState.value = StreamResolutionState.Success(
                  streamUrl = resolvedBest.streamUrl ?: "",
                  mediaItem = resolvedMediaItem,
                  episode = effectiveEpisode,
                  streamHeaders = resolvedBest.headers,
                  quality = resolvedBest.quality,
                  serverName = resolvedBest.serverName,
                  isProgressive = resolvedBest.isProgressive
                )
                _streamResult.value = StreamResult.Success(listOf(resolvedBest))
                openPlayer(resolvedMediaItem, effectiveEpisode, resolvedBest)
              } else if (resolvedBest.streamUrl != currentBestUrl) {
                currentBestUrl = resolvedBest.streamUrl
                upgradeStreamSource(resolvedBest)
              }
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Errore durante la risoluzione streaming: ${e.message}")
      }

      withContext(Dispatchers.Main) {
        if (currentJobGeneration != loadStreamGeneration) return@withContext
        if (!hasEmittedAny) {
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
      streamProgressive = source.isProgressive,
      subtitles = (source.subtitles + current.subtitles).distinctBy { it.id.ifBlank { it.url } }
    )
  }

  fun selectSource(source: StreamSource) {
    loadStreamJob?.cancel()
    loadStreamJob = null
    viewModelScope.launch {
      val target = _sourceSelectionTarget.value ?: return@launch
      val media = target.media
      val episode = target.episode

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
      _sourceSelectionTarget.value = null
      _isLoading.value = false

      Log.i(TAG, "Source selection: ${resolvedSource.serverName} (${resolvedSource.quality})")
      Log.d("BACK_TRACE", "STREAMNOVAVM: chiamata a openPlayer()")
      _streamResolutionState.value = StreamResolutionState.Success(
        streamUrl = resolvedSource.url,
        mediaItem = media,
        episode = episode,
        streamHeaders = resolvedSource.headers,
        quality = resolvedSource.quality,
        serverName = resolvedSource.serverName,
        isProgressive = resolvedSource.isProgressive
      )
      _streamResult.value = StreamResult.Success(listOf(resolvedSource))
      openPlayer(media, episode, resolvedSource)
    }
  }

  fun dismissSourceDialog() {
    resetStreamState()
    _showSourceDialog.value = false
    _availableSources.value = emptyList()
    _sourceSelectionTarget.value = null
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

  /**
   * Caricamento iniziale del catalogo Stremio del provider aperto. La paginazione lo
   * attende: senza questo ordinamento la pagina successiva, lanciata subito dopo
   * [openProvider], puo' completare per prima e sovrascrivere la prima pagina.
   */
  private var providerInitialLoad: Job? = null

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
    val currentProvider = _selectedProvider.value
    if (currentProvider != null && currentProvider.tmdbProviderId == providerId) {
      val stremioState = _stremioProviderStates.value[currentProvider.id]
      // Copertura del provider: il ramo Stremio esiste solo se NON è NONE.
      // Ogni catalogo mantiene il proprio offset: niente più skip condiviso.
      val coverage = stremioState?.coverage
        ?: StremioCatalogRepository.providerCoverage(currentProvider)
      if (coverage != ProviderCoverage.NONE) {
        val mediaType = if (isTv) MediaType.SERIE_TV else MediaType.FILM
        viewModelScope.launch {
          try {
            // Attende la prima pagina del provider: la paginazione non deve sovrascriverla.
            providerInitialLoad?.join()
            val stremioItems = _stremioProviderMedia.value[currentProvider.id].orEmpty()
            val more = StremioCatalogRepository.paginateProviderCatalog(currentProvider, mediaType)
            if (more.isNotEmpty()) {
              val combined = StremioCatalogRepository.deduplicateCrossSource(stremioItems + more)
              _stremioProviderMedia.update { it + (currentProvider.id to combined) }
              _stremioProviderStates.update {
                val base = it[currentProvider.id] ?: StremioProviderCatalogState()
                it + (currentProvider.id to base.copy(
                  hasConfirmedBindings = true,
                  itemCount = combined.size,
                  coverage = coverage
                ))
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Errore paginazione provider Stremio: ${e.message}")
          }
        }
        // FULL: solo Stremio (return). PARTIAL: nessun return anticipato, la
        // paginazione TMDB prosegue sotto come completamento del catalogo.
        if (coverage == ProviderCoverage.FULL) return
      }
    }

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
    // Ogni apertura riparte dalla prima pagina di ogni catalogo Stremio.
    StremioCatalogRepository.resetProviderPaging()

    val initialLoad = viewModelScope.launch {
      try {
        val page = StremioCatalogRepository.loadProviderCatalogForProvider(provider)
        val planned = StremioCatalogRepository.providerCoverage(provider)
        // FULL vale SOLO se il fetch effettivo produce item: senza titoli la
        // copertura degrada a PARTIAL e il catalogo TMDB resta disponibile.
        val coverage = when {
          planned == ProviderCoverage.NONE -> ProviderCoverage.NONE
          page.items.isEmpty() -> ProviderCoverage.PARTIAL
          else -> planned
        }
        if (coverage != ProviderCoverage.NONE) {
          // Copertura Stremio attiva: Stremio è la fonte primaria del provider.
          _stremioProviderMedia.update { it + (provider.id to page.items) }
          _stremioProviderStates.update {
            it + (provider.id to StremioProviderCatalogState(
              hasConfirmedBindings = page.hasConfirmedBindings,
              itemCount = page.items.size,
              confirmedCatalogs = page.targets.size,
              coverage = coverage
            ))
          }
          Log.d(
            "PROVIDER_CATALOG",
            "Stremio provider=${provider.id}: coverage=$coverage, " +
              "${page.targets.size} cataloghi confermati, ${page.items.size} titoli"
          )
        } else {
          // Nessun binding confermato: si mantiene il catalogo nativo TMDB.
          _stremioProviderMedia.update { it - provider.id }
          _stremioProviderStates.update { it - provider.id }
          Log.d(
            "PROVIDER_CATALOG",
            "Nessun binding Stremio confermato per ${provider.id}: coverage=NONE, usa catalogo TMDB"
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "Stremio provider catalog error: ${e.message}")
        // Fetch fallito con binding dichiarati: non si può considerare FULL,
        // la schermata deve poter completare con il catalogo TMDB.
        if (StremioCatalogRepository.providerCoverage(provider) != ProviderCoverage.NONE) {
          _stremioProviderStates.update {
            it + (provider.id to StremioProviderCatalogState(coverage = ProviderCoverage.PARTIAL))
          }
        }
      }
    }
    providerInitialLoad = initialLoad
    initialLoad.invokeOnCompletion { if (providerInitialLoad === initialLoad) providerInitialLoad = null }

    // Crunchyroll (283): catalogo primariamente TV/anime -> prima le serie TV
    if (provider.tmdbProviderId == 283) {
      loadNextProviderPage(provider.tmdbProviderId, isTv = true)
      loadNextProviderPage(provider.tmdbProviderId, isTv = false)
    } else {
      loadNextProviderPage(provider.tmdbProviderId, isTv = false)
      loadNextProviderPage(provider.tmdbProviderId, isTv = true)
    }
  }

  fun ensureProviderLoaded(provider: StreamingProvider) {
    if (_selectedProvider.value?.id != provider.id) {
      openProvider(provider)
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
