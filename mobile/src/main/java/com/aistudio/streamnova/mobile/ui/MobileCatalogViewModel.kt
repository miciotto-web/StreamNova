package com.aistudio.streamnova.mobile.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SearchCategoryFilter
import com.example.data.model.SearchTypeFilter
import com.example.data.prefs.AppSettingsRepository
import com.example.data.repository.CatalogSection
import com.example.data.repository.HomeCatalogs
import com.example.data.repository.MediaRepository
import com.example.data.repository.StremioCatalogRepository
import com.example.data.repository.TmdbHomeCatalogProvider
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.StremioCatalogEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Origine reale dei risultati della sottocategoria attiva. */
enum class MobileSearchCategorySource {
  ADDON,
  TMDB_FALLBACK,
  /**
   * Sorgente NATIVA TMDB (categoria "Talk"): il genere TV ufficiale è servito
   * direttamente da `discover/tv` e nessun catalogo Stremio è coinvolto, quindi
   * TMDB è la fonte primaria e non un fallback.
   */
  TMDB
}

/** Stato della sottocategoria attiva, esposto alla schermata Cerca. */
data class MobileSearchCategoryState(
  val source: MobileSearchCategorySource? = null,
  val addonLabel: String? = null,
  val movieFailed: Boolean = false,
  val seriesFailed: Boolean = false,
  val tmdbSkipped: Boolean = false,
  /** true quando la categoria nativa TMDB non ha risposto (errore dichiarato). */
  val nativeTmdbUnavailable: Boolean = false,
  /** true quando la categoria nativa TMDB ha risposto senza risultati. */
  val nativeTmdbEmpty: Boolean = false
)

/**
 * Errore del percorso cataloghi Mobile.
 *
 * Non esiste un errore "catalogo vuoto": un catalogo reale può essere
 * temporaneamente vuoto, ma se nessuna sorgente ha risposto lo stato è
 * [LOAD_FAILED] e la UI offre un retry. Non viene mai mostrato un contenuto
 * sostitutivo.
 */
enum class MobileCatalogError {
  LOAD_FAILED
}

/**
 * Stato della Home mobile, alimentato dalla stessa pipeline della TV.
 *
 * Le righe restano SEPARATE per tipo, come nella Home TV 1.0.4: nessuna lista
 * unica "Popolari"/"Top 10" che mescoli Film e Serie e introduca duplicati.
 * [heroItems] conserva l'intero carosello (massimo 9 titoli), non solo il primo.
 */
data class MobileCatalogUiState(
  val homeCatalogs: HomeCatalogs = HomeCatalogs(),
  val heroItems: List<MediaItem> = emptyList(),
  val continueWatching: List<MediaItem> = emptyList(),
  val top10Movies: List<MediaItem> = emptyList(),
  val top10Series: List<MediaItem> = emptyList(),
  val trendingMovies: List<MediaItem> = emptyList(),
  val trendingSeries: List<MediaItem> = emptyList(),
  val forYouMovies: List<MediaItem> = emptyList(),
  val forYouSeries: List<MediaItem> = emptyList(),
  val popularMovies: List<MediaItem> = emptyList(),
  val popularSeries: List<MediaItem> = emptyList(),
  val extraSections: List<CatalogSection> = emptyList(),
  /** Catalogo completo delle destinazioni Film/Serie TV (non righe della Home). */
  val movies: List<MediaItem> = emptyList(),
  val series: List<MediaItem> = emptyList(),
  /** true quando Film/Serie mostrano il fallback TMDB (addon vuoti, toggle ON). */
  val moviesTmdbFallback: Boolean = false,
  val seriesTmdbFallback: Boolean = false,
  val favorites: List<MediaItem> = emptyList(),
  val isLoading: Boolean = true,
  val isRefreshing: Boolean = false,
  val error: MobileCatalogError? = null,
  val parentalControlActive: Boolean = false
) {
  /** true quando la Home ha almeno una riga reale da mostrare. */
  val hasHomeContent: Boolean
    get() = heroItems.isNotEmpty() ||
      continueWatching.isNotEmpty() ||
      top10Movies.isNotEmpty() ||
      top10Series.isNotEmpty() ||
      trendingMovies.isNotEmpty() ||
      trendingSeries.isNotEmpty() ||
      forYouMovies.isNotEmpty() ||
      forYouSeries.isNotEmpty() ||
      popularMovies.isNotEmpty() ||
      popularSeries.isNotEmpty() ||
      extraSections.any { it.items.isNotEmpty() }

  /** true quando non c'è alcun contenuto reale (Home o cataloghi). */
  val isEmpty: Boolean
    get() = !hasHomeContent && movies.isEmpty() && series.isEmpty() && favorites.isEmpty()
}

/**
 * ViewModel dedicato all'app mobile per Home, cataloghi, ricerca e preferiti.
 *
 * Usa la STESSA pipeline della TV 1.0.4:
 * - [MediaRepository.refreshTmdbData] per il catalogo TMDB locale;
 * - [StremioCatalogRepository.resolveHomeCatalogs] per le righe Cinemeta e gli
 *   addon Stremio (inclusi i resolver/provider previsti dal core);
 * - [StremioCatalogRepository.searchAddonCatalogs] + `MediaRepository.searchTmdb`
 *   per la ricerca, con la stessa precedenza Stremio → TMDB della TV.
 *
 * Non riusa il ViewModel TV (che contiene stato di focus D-pad) e non crea una
 * pipeline parallela: condivide repository e decisioni di sorgente. Il filtro
 * età è applicato qui, in un unico punto.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MobileCatalogViewModel : ViewModel() {

  private val _homeCatalogs = MutableStateFlow(HomeCatalogs())
  private val _isLoading = MutableStateFlow(true)
  private val _isRefreshing = MutableStateFlow(false)
  private val _loadError = MutableStateFlow<MobileCatalogError?>(null)

  private var homeCatalogsJob: Job? = null
  private var pendingHomeMediaList: List<MediaItem>? = null
  private var homeResolvedOnce = false

  /**
   * Firma dell'ULTIMA risoluzione Home applicata e di quella in corso. Le emissioni
   * di [MediaRepository.mediaList] arrivano da progressi, preferiti e pagine TMDB
   * scaricate da altre schermate: NON cambiano i cataloghi degli addon. Rilanciare
   * l'intera risoluzione a ogni emissione (comportamento precedente) cancellava le
   * pagine già accodate a Film/Serie dall'infinite scroll, riportando le liste alla
   * sola prima pagina. Stessa firma → si aggiornano solo le liste di fallback locale.
   */
  private var homeResolvedSourceSignature: String? = null
  private var inFlightHomeSourceSignature: String? = null

  // ── Paginazione schermate Film / Serie TV (parità con la TV 1.0.5) ─────────────

  private var currentMoviePage = 1
  private var currentTvPage = 1
  private var isLoadingMoreMovies = false
  private var isLoadingMoreTv = false

  /**
   * Esaurimento della paginazione della schermata: l'ultimo trigger non ha prodotto
   * titoli nuovi. Impedisce raffiche di richieste identiche a fine lista; si sblocca
   * alla successiva risoluzione Home (refresh, cambio addon) o quando la lista cresce.
   */
  private var moviesPageExhausted = false
  private var seriesPageExhausted = false

  private val _isPaginatingMovies = MutableStateFlow(false)

  /** true mentre la pagina successiva dei Film è in caricamento (footer della griglia). */
  val isPaginatingMovies: StateFlow<Boolean> = _isPaginatingMovies.asStateFlow()

  private val _isPaginatingSeries = MutableStateFlow(false)

  /** true mentre la pagina successiva delle Serie TV è in caricamento. */
  val isPaginatingSeries: StateFlow<Boolean> = _isPaginatingSeries.asStateFlow()

  private data class LoadUi(
    val home: HomeCatalogs,
    val loading: Boolean,
    val refreshing: Boolean,
    val error: MobileCatalogError?
  )

  private val loadUi = combine(
    _homeCatalogs,
    _isLoading,
    _isRefreshing,
    _loadError
  ) { home, loading, refreshing, error ->
    LoadUi(home, loading, refreshing, error)
  }

  val catalog: StateFlow<MobileCatalogUiState> = combine(
    loadUi,
    MediaRepository.mediaList,
    AppSettingsRepository.parentalControlEnabled,
    AppSettingsRepository.parentalControlLevel,
    AppSettingsRepository.tmdbGenreFallbackEnabled
  ) { load, _, parentalEnabled, level, tmdbFallbackEnabled ->
    buildUiState(load, parentalEnabled, level, tmdbFallbackEnabled)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = MobileCatalogUiState()
  )

  init {
    // Offline-first: la cache locale non contiene più seed per Mobile, quindi
    // parte vuota finché la pipeline reale non risponde.
    viewModelScope.launch {
      MediaRepository.loadFromCache()
      refreshHomeCatalogs()
    }
    // Il refresh TMDB parte in background: le righe Cinemeta sono già visibili e
    // il catalogo cresce quando Room emette i nuovi titoli.
    viewModelScope.launch { refreshCatalog() }
    viewModelScope.launch {
      MediaRepository.mediaList.collect { list ->
        refreshHomeCatalogs(list)
      }
    }
    viewModelScope.launch {
      StremioAddonRepository.addons.collect {
        StremioCatalogRepository.clearCache()
        StremioCatalogRepository.resetProviderPaging()
        refreshHomeCatalogs()
      }
    }
  }

  private fun buildUiState(
    load: LoadUi,
    parentalEnabled: Boolean,
    level: String,
    tmdbFallbackEnabled: Boolean
  ): MobileCatalogUiState = buildMobileCatalogUiState(
    home = load.home,
    continueWatching = MediaRepository.getContinueWatching(),
    favorites = MediaRepository.getFavorites(),
    isLoading = load.loading,
    isRefreshing = load.refreshing,
    error = load.error,
    parentalEnabled = parentalEnabled,
    parentalLevel = level,
    tmdbFallbackEnabled = tmdbFallbackEnabled
  )

  /**
   * Risolve le righe Home con la stessa funzione della TV.
   *
   * Le emissioni di Room (progressi, preferiti, pagine TMDB scaricate altrove) NON
   * rilanciano la risoluzione: con le stesse sorgenti si aggiornano solo le liste
   * di fallback locale ([publishHomeFallbackMedia]), così le pagine già accodate a
   * Film/Serie dall'infinite scroll restano a schermo.
   *
   * Durante il refresh TMDB Room emette il catalogo a blocchi. La TV cancella e
   * rilancia la risoluzione a ogni emissione; su Mobile, che parte senza seed,
   * questo affamava le richieste Cinemeta e la Home restava in caricamento fino
   * alla fine dell'intero crawl. Qui la risoluzione in corso viene portata a
   * termine e le emissioni arrivate nel frattempo vengono accorpate in UNA sola
   * ripetizione con l'ultimo elenco: stessa pipeline, nessuna richiesta parallela.
   *
   * @param force true per un refresh ESPLICITO (retry, cambio lingua): ignora la
   *        firma già applicata e risolve da zero.
   */
  private fun refreshHomeCatalogs(
    mediaList: List<MediaItem> = MediaRepository.mediaList.value,
    force: Boolean = false
  ) {
    val signature = homeCatalogSourceSignature()
    if (!force && signature == homeResolvedSourceSignature && homeResolvedOnce) {
      // Stesse sorgenti: le righe degli addon e le pagine già caricate non si toccano.
      publishHomeFallbackMedia(mediaList)
      return
    }
    if (homeCatalogsJob?.isActive == true) {
      if (signature == inFlightHomeSourceSignature) {
        // La risoluzione delle STESSE sorgenti è già in corso: non la si riavvia
        // (nemmeno con force, che si riferisce alle sorgenti GIÀ applicate, non a
        // quelle in volo: rilanciare affamerebbe le richieste Cinemeta).
        return
      }
      pendingHomeMediaList = mediaList
      return
    }
    inFlightHomeSourceSignature = signature
    homeCatalogsJob = viewModelScope.launch {
      var request = mediaList
      while (true) {
        val activeSignature = inFlightHomeSourceSignature
        try {
          val resolved = StremioCatalogRepository.resolveHomeCatalogs(request)
          _homeCatalogs.value = resolved
          homeResolvedSourceSignature = activeSignature
          // Nuova risoluzione: le liste ripartono dalle pagine base dei cataloghi.
          moviesPageExhausted = false
          seriesPageExhausted = false
          _loadError.value = if (resolved.hasAnyContent()) null else MobileCatalogError.LOAD_FAILED
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Risoluzione cataloghi Home fallita: ${e.message}")
          _loadError.value = MobileCatalogError.LOAD_FAILED
        }
        if (!homeResolvedOnce) {
          homeResolvedOnce = true
          _isLoading.value = false
        }
        val next = pendingHomeMediaList ?: break
        pendingHomeMediaList = null
        val nextSignature = homeCatalogSourceSignature()
        if (nextSignature == homeResolvedSourceSignature) {
          publishHomeFallbackMedia(next)
          break
        }
        inFlightHomeSourceSignature = nextSignature
        request = next
      }
      inFlightHomeSourceSignature = null
    }
  }

  /**
   * Firma delle sorgenti dei cataloghi Home (nessuna rete: legge i manifest già
   * installati). Cambia quando un addon viene installato/rimosso/abilitato o quando
   * il suo manifest dichiara cataloghi diversi. Stessa regola della TV.
   */
  private fun homeCatalogSourceSignature(): String = runCatching {
    StremioCatalogEngine.getAvailableCatalogs()
      .map { "${it.addonId}|${it.type}|${it.catalogId}" }
      .distinct()
      .sorted()
      .joinToString(";")
  }.getOrDefault("")

  /**
   * Aggiorna in place le SOLE liste `allMovies`/`allSeries` quando provengono dalla
   * cache locale (nessun catalogo Stremio/TMDB di quel tipo): un cambiamento della
   * libreria (preferiti, progressi, pagine scaricate) non deve rilanciare la
   * risoluzione dei cataloghi né toccare le liste paginate.
   */
  private fun publishHomeFallbackMedia(mediaList: List<MediaItem>) {
    val movies = mediaList.filter { it.type == MediaType.FILM }
    val series = mediaList.filter { it.type == MediaType.SERIE_TV }
    val current = _homeCatalogs.value
    val moviesUseCache = !current.hasCustomCatalogMovies &&
      !current.moviesFromDefaultCatalog &&
      !current.moviesFromTmdbDefault
    val seriesUseCache = !current.hasCustomCatalogSeries &&
      !current.seriesFromDefaultCatalog &&
      !current.seriesFromTmdbDefault
    val nextMovies = if (moviesUseCache) movies else current.allMovies
    val nextSeries = if (seriesUseCache) series else current.allSeries
    if (nextMovies == current.allMovies && nextSeries == current.allSeries) return
    _homeCatalogs.value = current.copy(allMovies = nextMovies, allSeries = nextSeries)
  }

  /** Refresh esplicito del catalogo reale (TMDB) e delle righe Home. */
  fun refreshCatalog() {
    viewModelScope.launch {
      _isRefreshing.value = true
      try {
        MediaRepository.refreshTmdbData()
      } finally {
        _isRefreshing.value = false
      }
      // Refresh ESPLICITO: le righe addon vengono risolte da zero, ignorando la firma.
      refreshHomeCatalogs(force = true)
    }
  }

  /** Retry richiesto dall'utente dopo uno stato di errore/vuoto. */
  fun retry() {
    homeResolvedOnce = false
    _isLoading.value = true
    _loadError.value = null
    viewModelScope.launch { MediaRepository.loadFromCache() }
    refreshCatalog()
    refreshHomeCatalogs(force = true)
  }

  // ── Paginazione Film / Serie TV (stessa logica della TV 1.0.5) ────────────────

  /**
   * Pagina successiva della schermata Film, invocata dall'infinite scroll della
   * griglia. La sorgente è quella già attiva per la schermata:
   *  - catalogo Stremio (addon utente o Cinemeta di default): paginazione `skip`;
   *  - righe stock native TMDB: pagina successiva del catalogo "Popolari Film";
   *  - fallback legacy: crawl TMDB persistito in Room.
   */
  fun loadNextMoviesPage() {
    if (isLoadingMoreMovies || moviesPageExhausted) return
    isLoadingMoreMovies = true
    _isPaginatingMovies.value = true
    viewModelScope.launch {
      try {
        val catalogs = _homeCatalogs.value
        when {
          catalogs.moviesFromTmdbDefault -> {
            currentMoviePage++
            appendMoviesPage(TmdbHomeCatalogProvider.paginateMovies(currentMoviePage))
          }
          catalogs.isMoviesFromStremio -> {
            val currentList = _homeCatalogs.value.allMovies
            appendMoviesPage(StremioCatalogRepository.paginateMovies(currentList.size))
          }
          else -> {
            currentMoviePage++
            // La pagina cresce dall'emissione di Room: nessun append diretto.
            MediaRepository.loadMoreMovies(currentMoviePage)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione film: ${e.message}")
      } finally {
        isLoadingMoreMovies = false
        _isPaginatingMovies.value = false
      }
    }
  }

  /** Come [loadNextMoviesPage], per la schermata Serie TV. */
  fun loadNextTvPage() {
    if (isLoadingMoreTv || seriesPageExhausted) return
    isLoadingMoreTv = true
    _isPaginatingSeries.value = true
    viewModelScope.launch {
      try {
        val catalogs = _homeCatalogs.value
        when {
          catalogs.seriesFromTmdbDefault -> {
            currentTvPage++
            appendSeriesPage(TmdbHomeCatalogProvider.paginateSeries(currentTvPage))
          }
          catalogs.isSeriesFromStremio -> {
            val currentList = _homeCatalogs.value.allSeries
            appendSeriesPage(StremioCatalogRepository.paginateSeries(currentList.size))
          }
          else -> {
            currentTvPage++
            MediaRepository.loadMoreTv(currentTvPage)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione serie TV: ${e.message}")
      } finally {
        isLoadingMoreTv = false
        _isPaginatingSeries.value = false
      }
    }
  }

  /** Accoda la pagina Film caricata, deduplicata contro la lista già a schermo. */
  private fun appendMoviesPage(more: List<MediaItem>) {
    val current = _homeCatalogs.value
    if (more.isEmpty()) {
      moviesPageExhausted = true
      return
    }
    val combined = StremioCatalogRepository.deduplicateCrossSource(current.allMovies + more)
    if (combined.size > current.allMovies.size) {
      moviesPageExhausted = false
      _homeCatalogs.value = current.copy(allMovies = combined)
    } else {
      // La pagina non ha prodotto titoli nuovi (skip disallineato o catalogo finito):
      // si marca esaurita per non richiederla identica a ogni scroll.
      moviesPageExhausted = true
    }
  }

  /** Accoda la pagina Serie TV caricata, deduplicata contro la lista già a schermo. */
  private fun appendSeriesPage(more: List<MediaItem>) {
    val current = _homeCatalogs.value
    if (more.isEmpty()) {
      seriesPageExhausted = true
      return
    }
    val combined = StremioCatalogRepository.deduplicateCrossSource(current.allSeries + more)
    if (combined.size > current.allSeries.size) {
      seriesPageExhausted = false
      _homeCatalogs.value = current.copy(allSeries = combined)
    } else {
      seriesPageExhausted = true
    }
  }

  /**
   * La lingua dell'app è cambiata: i metadati TMDB in cache sono nella lingua
   * precedente, quindi si svuota la cache e si ricarica il catalogo (stesso
   * comportamento della TV in `clearCacheAndRefresh`).
   */
  fun onAppLanguageChanged() {
    viewModelScope.launch {
      _isRefreshing.value = true
      try {
        MediaRepository.clearCache()
        MediaRepository.refreshTmdbData()
      } finally {
        _isRefreshing.value = false
      }
      // Le sorgenti non cambiano, ma i metadati TMDB sì: la risoluzione riparte da zero.
      refreshHomeCatalogs(force = true)
    }
  }

  // ── Ricerca: stessa composizione TV (Stremio in testa, TMDB come fallback) ──

  private val _query = MutableStateFlow("")
  val query: StateFlow<String> = _query.asStateFlow()

  private val _searchType = MutableStateFlow(SearchTypeFilter.ALL)
  val searchType: StateFlow<SearchTypeFilter> = _searchType.asStateFlow()

  private val remoteSearch = combine(
    _query.debounce(400).distinctUntilChanged(),
    _searchType
  ) { query, type -> query.trim() to type }
    .flatMapLatest { (query, type) ->
      if (query.length < MIN_SEARCH_LENGTH) {
        flowOf(emptyList())
      } else {
        combine(
          MediaRepository.searchTmdb(query, type),
          stremioSearchFlow(query, type)
        ) { tmdbResults, stremioResults ->
          // Xperience/Stremio disponibile → risultati Stremio in testa; senza
          // risultati Stremio la lista TMDB resta invariata.
          if (stremioResults.isEmpty()) {
            tmdbResults
          } else {
            StremioCatalogRepository.deduplicateCrossSource(stremioResults + tmdbResults)
          }
        }
      }
    }

  val searchResults: StateFlow<List<MediaItem>> = combine(
    remoteSearch,
    AppSettingsRepository.parentalControlEnabled,
    AppSettingsRepository.parentalControlLevel
  ) { results, parentalEnabled, level ->
    results.filterByParental(parentalEnabled, level)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = emptyList()
  )

  fun onQueryChange(value: String) {
    _query.value = value
  }

  fun onSearchTypeChange(value: SearchTypeFilter) {
    _searchType.value = value
  }

  // ── Sottocategorie della sezione Ricerca ─────────────────────────────

  private val _selectedSearchCategoryId = MutableStateFlow<String?>(null)
  val selectedSearchCategoryId: StateFlow<String?> = _selectedSearchCategoryId.asStateFlow()

  private val _categoryItems = MutableStateFlow<List<MediaItem>>(emptyList())
  val categoryItems: StateFlow<List<MediaItem>> = _categoryItems.asStateFlow()

  private val _isCategoryLoading = MutableStateFlow(false)
  val isCategoryLoading: StateFlow<Boolean> = _isCategoryLoading.asStateFlow()

  private val _categoryState = MutableStateFlow(MobileSearchCategoryState())
  val categoryState: StateFlow<MobileSearchCategoryState> = _categoryState.asStateFlow()

  private var activeSearchCategory: MobileSearchCategory? = null
  private var activeCategoryEntries: List<StremioCatalogRepository.CategoryCatalogEntry> = emptyList()
  private var activeCategoryTmdbTypes: Set<MediaType> = emptySet()
  private var categoryLoadGeneration = 0
  private var currentCategoryPage = 1
  private var isCategoryLoadingMore = false

  /** Apre una sottocategoria e ne carica i risultati con la stessa logica della TV. */
  fun selectSearchCategory(categoryId: String) {
    val category = MOBILE_SEARCH_CATEGORIES.find { it.id == categoryId } ?: return
    activeSearchCategory = category
    _selectedSearchCategoryId.value = category.id
    activeCategoryEntries = emptyList()
    activeCategoryTmdbTypes = emptySet()
    currentCategoryPage = 1
    categoryLoadGeneration++
    _categoryItems.value = emptyList()
    _categoryState.value = MobileSearchCategoryState()
    _isCategoryLoading.value = true
    // La sorgente è dichiarata dalla categoria: le categorie per genere consultano i
    // cataloghi addon (con fallback TMDB), "Talk" è servita nativamente da TMDB.
    when (category.contentFilter) {
      SearchCategoryFilter.TMDB_TV_GENRE ->
        loadInitialNativeTmdbCategoryPage(categoryLoadGeneration)
      SearchCategoryFilter.GENRE ->
        loadInitialCategoryPage(categoryLoadGeneration)
    }
  }

  /** Torna alla griglia delle sottocategorie e invalida eventuali risposte in volo. */
  fun clearSearchCategory() {
    categoryLoadGeneration++
    activeSearchCategory = null
    activeCategoryEntries = emptyList()
    activeCategoryTmdbTypes = emptySet()
    _selectedSearchCategoryId.value = null
    _categoryItems.value = emptyList()
    _categoryState.value = MobileSearchCategoryState()
    _isCategoryLoading.value = false
  }

  private fun loadInitialCategoryPage(generation: Int) {
    val category = activeSearchCategory ?: return
    viewModelScope.launch {
      try {
        val keywords = category.effectiveKeywords()
        val addonLoad = try {
          StremioCatalogRepository.loadCategoryCatalogs(keywords)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Caricamento cataloghi categoria fallito: ${e.message}")
          null
        }
        if (generation != categoryLoadGeneration) return@launch

        val movieOutcome = category.movieGenreId?.let { genreId ->
          resolveCategoryTypeOutcome(
            mediaType = MediaType.FILM,
            genreId = genreId,
            addonEntries = addonLoad?.entries.orEmpty().filter { it.section.mediaType == MediaType.FILM },
            addonItems = addonLoad?.items.orEmpty().filter { it.type == MediaType.FILM },
            matchedTargets = addonLoad?.matchedMovieTargets ?: 0,
            failedTargets = addonLoad?.failedMovieTargets ?: 0
          )
        }
        if (generation != categoryLoadGeneration) return@launch

        val seriesOutcome = category.tvGenreId?.let { genreId ->
          resolveCategoryTypeOutcome(
            mediaType = MediaType.SERIE_TV,
            genreId = genreId,
            addonEntries = addonLoad?.entries.orEmpty().filter { it.section.mediaType == MediaType.SERIE_TV },
            addonItems = addonLoad?.items.orEmpty().filter { it.type == MediaType.SERIE_TV },
            matchedTargets = addonLoad?.matchedSeriesTargets ?: 0,
            failedTargets = addonLoad?.failedSeriesTargets ?: 0
          )
        }
        if (generation != categoryLoadGeneration) return@launch

        val outcomes = listOfNotNull(movieOutcome, seriesOutcome)
        val addonItems = outcomes.flatMap { it.addonItems }
        val tmdbItems = outcomes.flatMap { it.tmdbItems }
        val tmdbTypes = outcomes.filter { it.usedTmdb }.map { it.mediaType }.toSet()
        val skipped = outcomes.any { it.tmdbSkipped }

        activeCategoryEntries = addonLoad?.entries.orEmpty()
        activeCategoryTmdbTypes = tmdbTypes
        _categoryState.value = MobileSearchCategoryState(
          source = if (tmdbItems.isNotEmpty() || skipped) {
            MobileSearchCategorySource.TMDB_FALLBACK
          } else {
            MobileSearchCategorySource.ADDON
          },
          addonLabel = addonLoad?.entries?.firstOrNull { it.section.items.isNotEmpty() }?.section?.title,
          movieFailed = movieOutcome?.failed == true,
          seriesFailed = seriesOutcome?.failed == true,
          tmdbSkipped = skipped
        )
        _categoryItems.value = StremioCatalogRepository.deduplicateCrossSource(
          if (tmdbItems.isNotEmpty()) addonItems + tmdbItems else addonItems
        )
      } finally {
        if (generation == categoryLoadGeneration) {
          _isCategoryLoading.value = false
        }
      }
    }
  }

  /**
   * Prima pagina di una categoria NATIVA TMDB ("Talk").
   *
   * Stessa semantica della TV: nessun catalogo di addon viene interrogato, nessuna
   * verifica sulle sorgenti di riproduzione e nessuna dipendenza dalla modalità Debrid.
   * La fonte primaria è il genere TV ufficiale dichiarato dalla categoria
   * (`discover/tv?with_genres=10767`), ordinato per popolarità e deduplicato per
   * identità TMDB/IMDb.
   *
   * Errore e stato vuoto sono dichiarati nello stato: una griglia vuota non viene mai
   * presentata come caricamento riuscito.
   */
  private fun loadInitialNativeTmdbCategoryPage(generation: Int) {
    viewModelScope.launch {
      try {
        val genreId = activeSearchCategory?.tvGenreId
        val series = genreId?.let {
          MediaRepository.discoverByGenre(
            MediaType.SERIE_TV,
            it,
            page = 1,
            excludeNonEditorialTv = false
          )
        }
        if (generation != categoryLoadGeneration) return@launch

        val unavailable = series == null
        _categoryState.value = MobileSearchCategoryState(
          source = MobileSearchCategorySource.TMDB,
          nativeTmdbUnavailable = unavailable,
          nativeTmdbEmpty = !unavailable && series.isNullOrEmpty()
        )
        currentCategoryPage = 1
        _categoryItems.value = StremioCatalogRepository.deduplicateCrossSource(series.orEmpty())
      } finally {
        if (generation == categoryLoadGeneration) {
          _isCategoryLoading.value = false
        }
      }
    }
  }

  private suspend fun resolveCategoryTypeOutcome(
    mediaType: MediaType,
    genreId: Int,
    addonEntries: List<StremioCatalogRepository.CategoryCatalogEntry>,
    addonItems: List<MediaItem>,
    matchedTargets: Int,
    failedTargets: Int
  ): CategoryTypeOutcome {
    val anyLoaded = addonEntries.any { it.section.items.isNotEmpty() }
    if (anyLoaded) {
      return CategoryTypeOutcome(mediaType = mediaType, addonItems = addonItems)
    }
    if (matchedTargets > 0 && failedTargets >= matchedTargets) {
      return CategoryTypeOutcome(mediaType = mediaType, addonItems = addonItems, failed = true)
    }
    if (!AppSettingsRepository.tmdbGenreFallbackEnabled.value) {
      return CategoryTypeOutcome(mediaType = mediaType, addonItems = addonItems, tmdbSkipped = true)
    }
    val tmdbItems = MediaRepository.discoverByGenre(mediaType, genreId, page = 1).orEmpty()
    return CategoryTypeOutcome(
      mediaType = mediaType,
      addonItems = addonItems,
      tmdbItems = tmdbItems,
      usedTmdb = true
    )
  }

  /** Carica la pagina successiva della sottocategoria attiva. */
  fun loadNextCategoryPage() {
    if (isCategoryLoadingMore || _isCategoryLoading.value) return
    val generation = categoryLoadGeneration
    val category = activeSearchCategory ?: return
    // Categoria nativa TMDB ("Talk"): nessun catalogo addon da paginare, la pagina
    // successiva è una `discover/tv` con lo stesso genere.
    if (category.contentFilter == SearchCategoryFilter.TMDB_TV_GENRE) {
      loadNextNativeTmdbCategoryPage(generation, category)
      return
    }
    val addonEntries = activeCategoryEntries.filter { it.section.supportsSkip && it.section.items.isNotEmpty() }
    val tmdbTypes = activeCategoryTmdbTypes
    if (addonEntries.isEmpty() && tmdbTypes.isEmpty()) return
    isCategoryLoadingMore = true
    viewModelScope.launch {
      try {
        val fresh = mutableListOf<MediaItem>()
        if (addonEntries.isNotEmpty()) {
          val updated = StremioCatalogRepository.paginateCategoryCatalogs(addonEntries)
          updated.forEachIndexed { index, entry ->
            fresh += entry.section.items.drop(addonEntries[index].section.items.size)
          }
          activeCategoryEntries = updated
        }
        if (generation != categoryLoadGeneration) return@launch
        if (tmdbTypes.isNotEmpty()) {
          val nextPage = currentCategoryPage + 1
          if (tmdbTypes.contains(MediaType.FILM)) {
            category.movieGenreId?.let { genreId ->
              MediaRepository.discoverByGenre(MediaType.FILM, genreId, nextPage)?.let(fresh::addAll)
            }
          }
          if (tmdbTypes.contains(MediaType.SERIE_TV)) {
            category.tvGenreId?.let { genreId ->
              MediaRepository.discoverByGenre(MediaType.SERIE_TV, genreId, nextPage)?.let(fresh::addAll)
            }
          }
          currentCategoryPage = nextPage
        }
        if (generation != categoryLoadGeneration) return@launch
        if (fresh.isNotEmpty()) {
          _categoryItems.value = StremioCatalogRepository.deduplicateCrossSource(
            _categoryItems.value + fresh
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione categoria: ${e.message}")
      } finally {
        isCategoryLoadingMore = false
      }
    }
  }

  /**
   * Pagina successiva di una categoria NATIVA TMDB ("Talk"): una `discover/tv` con
   * lo stesso genere TV e la pagina successiva. I nuovi titoli vengono deduplicati
   * per identità TMDB/IMDb contro quelli già mostrati, così una pagina Discover che
   * ripete un titolo non produce una card doppia.
   *
   * La pagina avanza solo quando TMDB risponde con successo: un errore lascia il
   * contatore invariato e permette di ritentare. La generazione viene ricontrollata
   * dopo la risposta: un cambio di categoria non può essere sovrascritto da una
   * pagina obsoleta.
   */
  private fun loadNextNativeTmdbCategoryPage(
    generation: Int,
    category: MobileSearchCategory
  ) {
    val genreId = category.tvGenreId ?: return
    isCategoryLoadingMore = true
    viewModelScope.launch {
      try {
        val nextPage = currentCategoryPage + 1
        val newItems = MediaRepository.discoverByGenre(
          MediaType.SERIE_TV,
          genreId,
          nextPage,
          excludeNonEditorialTv = false
        ) ?: return@launch
        if (generation != categoryLoadGeneration) return@launch
        currentCategoryPage = nextPage
        if (newItems.isEmpty()) return@launch
        _categoryItems.value = StremioCatalogRepository.deduplicateCrossSource(
          _categoryItems.value + newItems
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione categoria nativa TMDB: ${e.message}")
      } finally {
        isCategoryLoadingMore = false
      }
    }
  }

  private data class CategoryTypeOutcome(
    val mediaType: MediaType,
    val addonItems: List<MediaItem>,
    val tmdbItems: List<MediaItem> = emptyList(),
    val usedTmdb: Boolean = false,
    val failed: Boolean = false,
    val tmdbSkipped: Boolean = false
  )

  fun toggleFavorite(mediaId: String) {
    MediaRepository.toggleFavorite(mediaId)
  }

  /**
   * Rimozione esplicita da «Continua a guardare», come il menu contestuale TV.
   * La card sparisce perché Room riemette il catalogo, senza stato locale
   * parallelo che possa divergere.
   */
  fun removeFromContinueWatching(mediaId: String) {
    viewModelScope.launch {
      MediaRepository.removeFromContinueWatching(mediaId)
    }
  }

  /**
   * Ricerca testuale sui cataloghi Stremio/Xperience attivi, identica alla TV:
   * solo addon attivi con extra `search`, type/catalogId rispettati. Emette
   * subito vuoto così i risultati TMDB non vengono ritardati.
   */
  private fun stremioSearchFlow(
    query: String,
    filter: SearchTypeFilter
  ): Flow<List<MediaItem>> = flow {
    val mediaType = when (filter) {
      SearchTypeFilter.FILM -> MediaType.FILM
      SearchTypeFilter.SERIE_TV -> MediaType.SERIE_TV
      SearchTypeFilter.ALL -> null
    }
    if (query.length < MIN_SEARCH_LENGTH) {
      emit(emptyList())
      return@flow
    }
    emit(emptyList())
    val results = try {
      withTimeoutOrNull(STREMIO_SEARCH_TIMEOUT_MS) {
        StremioCatalogRepository.searchAddonCatalogs(query, mediaType)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Ricerca cataloghi Stremio fallita per \"$query\": ${e.message}")
      null
    }
    if (!results.isNullOrEmpty()) emit(results)
  }

  private fun HomeCatalogs.hasAnyContent(): Boolean =
    heroItems.isNotEmpty() ||
      allMovies.isNotEmpty() ||
      allSeries.isNotEmpty() ||
      popularMovies.isNotEmpty() ||
      popularSeries.isNotEmpty() ||
      top10Movies.isNotEmpty() ||
      top10Series.isNotEmpty() ||
      trendingMovies.isNotEmpty() ||
      trendingSeries.isNotEmpty() ||
      forYouMovies.isNotEmpty() ||
      forYouSeries.isNotEmpty() ||
      extraSections.any { it.items.isNotEmpty() }

  private companion object {
    private const val TAG = "MobileCatalogVM"
    private const val MIN_SEARCH_LENGTH = 2
    private const val STREMIO_SEARCH_TIMEOUT_MS = 8_000L
  }
}

/**
 * Traduce [HomeCatalogs] (pipeline TV) nello stato della Home mobile.
 *
 * Funzione PURA: non legge repository né Compose, così le regole di
 * derivazione/filtro possono essere verificate senza rete o database.
 */
internal fun buildMobileCatalogUiState(
  home: HomeCatalogs,
  continueWatching: List<MediaItem>,
  favorites: List<MediaItem>,
  isLoading: Boolean,
  isRefreshing: Boolean,
  error: MobileCatalogError?,
  parentalEnabled: Boolean,
  parentalLevel: String,
  tmdbFallbackEnabled: Boolean = true
): MobileCatalogUiState {
  fun List<MediaItem>.keepVisible(): List<MediaItem> =
    filterByParental(parentalEnabled, parentalLevel)

  // Le sezioni extra (Novità provider TMDB e cataloghi addon) restano nell'ordine
  // reale della pipeline; una sezione interamente filtrata dal controllo genitori
  // non deve comparire vuota.
  val visibleExtraSections = home.extraSections.mapNotNull { section ->
    val items = section.items.keepVisible()
    if (items.isEmpty()) null else section.copy(items = items)
  }

  // Fallback TMDB delle destinazioni Film/Serie: si applica solo quando nessun
  // catalogo addon/Cinemeta ha popolato il tipo. Con il toggle OFF il catalogo
  // resta vuoto (stato dichiarato), senza risultati sostitutivi.
  val addonMoviesAvailable = home.isMoviesFromStremio
  val addonSeriesAvailable = home.isSeriesFromStremio
  val moviesFallback = MobileCatalogFallbackPolicy.shouldUseTmdbFallback(
    enabled = tmdbFallbackEnabled,
    addonContentAvailable = addonMoviesAvailable
  )
  val seriesFallback = MobileCatalogFallbackPolicy.shouldUseTmdbFallback(
    enabled = tmdbFallbackEnabled,
    addonContentAvailable = addonSeriesAvailable
  )
  val visibleMovies = home.allMovies.keepVisible()
  val visibleSeries = home.allSeries.keepVisible()

  return MobileCatalogUiState(
    homeCatalogs = home,
    heroItems = home.heroItems.keepVisible(),
    continueWatching = continueWatching.keepVisible(),
    top10Movies = home.top10Movies.take(StremioCatalogRepository.HOME_TOP_10_SIZE).keepVisible(),
    top10Series = home.top10Series.take(StremioCatalogRepository.HOME_TOP_10_SIZE).keepVisible(),
    trendingMovies = home.trendingMovies.keepVisible(),
    trendingSeries = home.trendingSeries.keepVisible(),
    forYouMovies = home.forYouMovies.keepVisible(),
    forYouSeries = home.forYouSeries.keepVisible(),
    popularMovies = home.popularMovies.keepVisible(),
    popularSeries = home.popularSeries.keepVisible(),
    extraSections = visibleExtraSections,
    movies = if (addonMoviesAvailable || moviesFallback) visibleMovies else emptyList(),
    series = if (addonSeriesAvailable || seriesFallback) visibleSeries else emptyList(),
    moviesTmdbFallback = moviesFallback && visibleMovies.isNotEmpty(),
    seriesTmdbFallback = seriesFallback && visibleSeries.isNotEmpty(),
    favorites = favorites.keepVisible(),
    isLoading = isLoading,
    isRefreshing = isRefreshing,
    error = error,
    parentalControlActive = parentalEnabled
  )
}

/** Filtro locale usato anche dalle schermate Film/Serie TV/Preferiti. */
fun List<MediaItem>.filterByType(type: MediaType): List<MediaItem> =
  filter { it.type == type }
