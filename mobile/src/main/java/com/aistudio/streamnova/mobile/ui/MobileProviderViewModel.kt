package com.aistudio.streamnova.mobile.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.MediaItem
import com.example.data.prefs.AppSettingsRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.ProviderCoverage
import com.example.data.repository.StremioCatalogRepository
import com.example.ui.components.StreamingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Stato della schermata Provider mobile.
 *
 * [items] contiene la prima pagina realmente restituita dalle sorgenti del
 * provider (Stremio e/o TMDB). Non esiste paginazione in questa fase: nessun
 * contenuto viene inventato per riempire la griglia.
 */
data class MobileProviderUiState(
  val items: List<MediaItem> = emptyList(),
  val isLoading: Boolean = true,
  val error: Boolean = false,
  val parentalControlActive: Boolean = false
)

/**
 * ViewModel della destinazione Provider mobile.
 *
 * Riusa lo stesso layer dati della TV: `providerCoverage`,
 * `loadProviderCatalogForProvider` per Stremio e `MediaRepository` per il
 * catalogo nativo TMDB. La UI non conosce né la copertura né le sorgenti:
 * riceve una lista già composta e filtrata.
 */
class MobileProviderViewModel : ViewModel() {

  private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
  private val _isLoading = MutableStateFlow(true)
  private val _error = MutableStateFlow(false)

  private var loadJob: Job? = null
  private var loadedProviderId: String? = null

  val state: StateFlow<MobileProviderUiState> = combine(
    _items,
    _isLoading,
    _error,
    AppSettingsRepository.parentalControlEnabled,
    AppSettingsRepository.parentalControlLevel
  ) { items, loading, error, parentalEnabled, parentalLevel ->
    MobileProviderUiState(
      items = items.filterByParental(parentalEnabled, parentalLevel),
      isLoading = loading,
      error = error,
      parentalControlActive = parentalEnabled
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = MobileProviderUiState()
  )

  /** Carica una sola volta il provider aperto; [force] serve al retry esplicito. */
  fun load(provider: StreamingProvider, force: Boolean = false) {
    if (!force && loadedProviderId == provider.id) return
    loadedProviderId = provider.id
    loadJob?.cancel()
    loadJob = viewModelScope.launch {
      _isLoading.value = true
      _error.value = false
      try {
        _items.value = loadProviderItems(provider)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Catalogo provider ${provider.id} non disponibile: ${e.message}")
        _error.value = true
        _items.value = emptyList()
      } finally {
        _isLoading.value = false
      }
    }
  }

  fun retry(provider: StreamingProvider) = load(provider, force = true)

  private suspend fun loadProviderItems(provider: StreamingProvider): List<MediaItem> {
    val plannedCoverage = StremioCatalogRepository.providerCoverage(provider)
    val stremioPage = if (plannedCoverage != ProviderCoverage.NONE) {
      try {
        StremioCatalogRepository.sanitizeProviderCatalogPage(
          StremioCatalogRepository.loadProviderCatalogForProvider(provider)
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Come la TV: un errore Stremio con binding dichiarati degrada a
        // PARTIAL, così il catalogo TMDB può comunque popolare la griglia.
        Log.w(TAG, "Catalogo Stremio provider ${provider.id} fallito: ${e.message}")
        null
      }
    } else {
      null
    }

    // Come la TV: FULL vale solo se il fetch Stremio produce davvero item,
    // altrimenti degrada a PARTIAL e il catalogo TMDB completa la griglia.
    val coverage = when {
      plannedCoverage == ProviderCoverage.NONE -> ProviderCoverage.NONE
      stremioPage == null || stremioPage.items.isEmpty() -> ProviderCoverage.PARTIAL
      else -> plannedCoverage
    }

    val stremioItems = StremioCatalogRepository.stabilizeProviderOrder(
      stremioPage?.items.orEmpty()
    )
    val tmdbItems = if (coverage == ProviderCoverage.FULL) {
      emptyList()
    } else {
      loadTmdbProviderItems(provider)
    }

    return mergeMobileProviderCatalog(
      stremioItems = stremioItems,
      tmdbItems = tmdbItems,
      coverage = coverage,
      fallbackToDefault = stremioPage?.fallbackToDefault == true
    )
  }

  /**
   * Cataloghi nativi TMDB della prima pagina.
   *
   * Allineato alla TV 1.0.5: HBO non ha più un percorso dedicato (network ID e
   * watch_region=US producevano un catalogo incompleto e non rappresentativo del
   * catalogo italiano). Tutti i provider usano la stessa query
   * (`with_watch_providers` + `watch_region=IT`), vedi [MediaRepository.providerTmdbQuery].
   */
  private suspend fun loadTmdbProviderItems(provider: StreamingProvider): List<MediaItem> {
    if (provider.tmdbProviderId <= 0) return emptyList()
    return try {
      val movies = MediaRepository.loadProviderCatalog(
        providerId = provider.tmdbProviderId,
        isTv = false,
        page = 1
      ).first().items
      val series = MediaRepository.loadProviderCatalog(
        providerId = provider.tmdbProviderId,
        isTv = true,
        page = 1
      ).first().items
      movies + series
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Catalogo TMDB provider ${provider.id} fallito: ${e.message}")
      emptyList()
    }
  }

  private companion object {
    private const val TAG = "MobileProviderVM"
  }
}
