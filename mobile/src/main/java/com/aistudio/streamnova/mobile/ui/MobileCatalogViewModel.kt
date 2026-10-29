package com.aistudio.streamnova.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SearchTypeFilter
import com.example.data.prefs.AppSettingsRepository
import com.example.data.repository.MediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Sezioni della Home mobile, calcolate dallo stesso catalogo della TV. */
data class MobileCatalogUiState(
  val hero: MediaItem? = null,
  val continueWatching: List<MediaItem> = emptyList(),
  val popular: List<MediaItem> = emptyList(),
  val top10: List<MediaItem> = emptyList(),
  val movies: List<MediaItem> = emptyList(),
  val series: List<MediaItem> = emptyList(),
  val favorites: List<MediaItem> = emptyList(),
  val forYou: List<MediaItem> = emptyList(),
  val parentalControlActive: Boolean = false
)

/**
 * ViewModel dedicato all'app mobile per Home, cataloghi, ricerca e preferiti.
 *
 * Non riusa il ViewModel TV (che contiene stato di focus D-pad): condivide solo
 * i repository del core. Il filtro età è applicato qui, in un unico punto.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MobileCatalogViewModel : ViewModel() {

  val catalog: StateFlow<MobileCatalogUiState> = combine(
    MediaRepository.mediaList,
    AppSettingsRepository.parentalControlEnabled,
    AppSettingsRepository.parentalControlLevel
  ) { list, parentalEnabled, level ->
    val visible = list.filterByParental(parentalEnabled, level)
    val allowed = visible.mapTo(HashSet()) { it.id }

    fun List<MediaItem>.keepVisible(): List<MediaItem> = filter { it.id in allowed }

    MobileCatalogUiState(
      hero = MediaRepository.getContinueWatching().keepVisible().firstOrNull()
        ?: MediaRepository.getPopular().keepVisible().firstOrNull()
        ?: visible.firstOrNull(),
      continueWatching = MediaRepository.getContinueWatching().keepVisible(),
      popular = MediaRepository.getPopular().keepVisible(),
      top10 = (MediaRepository.getTop10Movies() + MediaRepository.getTop10Series())
        .distinctBy { it.id }
        .keepVisible(),
      movies = MediaRepository.getMovies().keepVisible(),
      series = MediaRepository.getSeries().keepVisible(),
      favorites = MediaRepository.getFavorites().keepVisible(),
      forYou = (MediaRepository.getForYouMovies() + MediaRepository.getForYouSeries())
        .distinctBy { it.id }
        .keepVisible(),
      parentalControlActive = parentalEnabled
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = MobileCatalogUiState()
  )

  private val _query = MutableStateFlow("")
  val query: StateFlow<String> = _query.asStateFlow()

  private val _searchType = MutableStateFlow(SearchTypeFilter.ALL)
  val searchType: StateFlow<SearchTypeFilter> = _searchType.asStateFlow()

  private val remoteSearch = combine(
    _query.debounce(250).distinctUntilChanged(),
    _searchType
  ) { query, type -> query.trim() to type }
    .flatMapLatest { (query, type) ->
      if (query.length < 2) flowOf(emptyList())
      else MediaRepository.searchTmdb(query, type)
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

  fun toggleFavorite(mediaId: String) {
    MediaRepository.toggleFavorite(mediaId)
  }
}

/** Filtro locale usato anche dalle schermate Film/Serie TV/Preferiti. */
fun List<MediaItem>.filterByType(type: MediaType): List<MediaItem> =
  filter { it.type == type }
