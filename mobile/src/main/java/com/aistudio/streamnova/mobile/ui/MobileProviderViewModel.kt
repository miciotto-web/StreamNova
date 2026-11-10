package com.aistudio.streamnova.mobile.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Stato della schermata Provider mobile.
 *
 * [items] contiene le pagine realmente restituite dalle sorgenti del provider
 * (Stremio e/o TMDB), accodate dall'infinite scroll con le stesse regole della
 * schermata TV 1.0.5. Nessun contenuto viene inventato per riempire la griglia.
 */
data class MobileProviderUiState(
  val items: List<MediaItem> = emptyList(),
  val isLoading: Boolean = true,
  /** true mentre una pagina successiva (TMDB o Stremio) è in caricamento. */
  val isLoadingMore: Boolean = false,
  val error: Boolean = false,
  val parentalControlActive: Boolean = false
)

/**
 * ViewModel della destinazione Provider mobile.
 *
 * Riusa lo stesso layer dati della TV: `providerCoverage`, i cataloghi Stremio
 * confermati (`skip`) e la paginazione TMDB nativa (`with_watch_providers`) per
 * coppia (provider, tipo). La paginazione replica la TV:
 *  - copertura FULL → solo Stremio finché consegna titoli nuovi; al suo esaurimento
 *    la copertura degrada a PARTIAL e TMDB completa il catalogo;
 *  - copertura PARTIAL → Stremio primario e TMDB in parallelo, sorgenti
 *    indipendenti con esaurimento e cooldown propri;
 *  - copertura NONE → solo TMDB.
 *
 * La UI non conosce né la copertura né le sorgenti: riceve una lista già composta
 * e filtrata ([mergeMobileProviderCatalog]) più i flag di caricamento.
 */
class MobileProviderViewModel : ViewModel() {

  /** Titoli Stremio (addon utente con binding, o fallback Cinemeta legacy). */
  private val _stremioItems = MutableStateFlow<List<MediaItem>>(emptyList())

  /** Titoli del catalogo nativo TMDB (`with_watch_providers`), per pagine. */
  private val _tmdbItems = MutableStateFlow<List<MediaItem>>(emptyList())

  private val _coverage = MutableStateFlow(ProviderCoverage.NONE)
  private val _fallbackToDefault = MutableStateFlow(false)

  private val _isLoading = MutableStateFlow(true)
  private val _error = MutableStateFlow(false)
  private val _isLoadingMore = MutableStateFlow(false)

  // ── Stato paginazione per coppia (providerId, isTv) ─────────────────────────────

  /** Ultima pagina TMDB caricata in sessione per coppia: assente = nessuna. */
  private val tmdbPages = mutableMapOf<Pair<Int, Boolean>, Int>()

  /** hasMore TMDB noto per coppia: chiave assente = non ancora interrogata (true). */
  private val _tmdbHasMorePages = MutableStateFlow<Map<Pair<Int, Boolean>, Boolean>>(emptyMap())

  /** hasMore Stremio noto per coppia: chiave assente = non ancora interrogata (true). */
  private val _stremioHasMorePages = MutableStateFlow<Map<Pair<Int, Boolean>, Boolean>>(emptyMap())

  /** Coppie (providerId, isTv) con una fetch in corso (TMDB o Stremio). */
  private val _loadingKeys = MutableStateFlow<Set<Pair<Int, Boolean>>>(emptySet())

  /** Cooldown anti-raffica della paginazione TMDB dopo una pagina fallita. */
  private val tmdbRetryAfter = mutableMapOf<Pair<Int, Boolean>, Long>()

  /** Cooldown anti-raffica della paginazione Stremio dopo un errore. */
  private val stremioRetryAfter = mutableMapOf<Pair<Int, Boolean>, Long>()

  /** Caricamento iniziale Stremio del provider aperto: la paginazione lo attende. */
  private var initialLoad: Job? = null

  private var loadedProviderId: String? = null
  private var loadedProvider: StreamingProvider? = null

  private data class ProviderSourceState(
    val stremioItems: List<MediaItem>,
    val tmdbItems: List<MediaItem>,
    val coverage: ProviderCoverage,
    val fallbackToDefault: Boolean
  )

  private val sourceState = combine(
    _stremioItems,
    _tmdbItems,
    _coverage,
    _fallbackToDefault
  ) { stremio, tmdb, coverage, fallback ->
    ProviderSourceState(stremio, tmdb, coverage, fallback)
  }

  private val parentalState = combine(
    AppSettingsRepository.parentalControlEnabled,
    AppSettingsRepository.parentalControlLevel
  ) { enabled, level -> enabled to level }

  val state: StateFlow<MobileProviderUiState> = combine(
    sourceState,
    _isLoading,
    _isLoadingMore,
    _error,
    parentalState
  ) { source, loading, loadingMore, error, (parentalEnabled, parentalLevel) ->
    val items = mergeMobileProviderCatalog(
      stremioItems = source.stremioItems,
      tmdbItems = source.tmdbItems,
      coverage = source.coverage,
      fallbackToDefault = source.fallbackToDefault
    )
    MobileProviderUiState(
      items = items.filterByParental(parentalEnabled, parentalLevel),
      // Il caricamento iniziale non termina finché la PRIMA pagina di una sorgente
      // non è a schermo: con un provider solo-TMDB il flag manuale si spegnerebbe
      // prima dell'arrivo dei titoli e la griglia lampeggerebbe sullo stato vuoto.
      isLoading = loading || (items.isEmpty() && loadingMore),
      isLoadingMore = loadingMore,
      error = error,
      parentalControlActive = parentalEnabled
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = MobileProviderUiState()
  )

  /**
   * Coppie (providerId, isTv) con ENTRAMBE le sorgenti esaurite. Una chiave assente
   * non è esaurita: le sorgenti non ancora interrogate restano paginabili (default
   * `true`, come sulla TV). È la condizione usata dal trigger di infinite scroll.
   */
  val exhaustedKeys: StateFlow<Set<Pair<Int, Boolean>>> = combine(
    _tmdbHasMorePages,
    _stremioHasMorePages,
    _coverage
  ) { tmdbMore, stremioMore, coverage ->
    val tmdbExhausted = tmdbMore.filterValues { !it }.keys
    if (coverage == ProviderCoverage.NONE) {
      tmdbExhausted.toSet()
    } else {
      // Copertura Stremio attiva: il tipo è esaurito solo quando ANCHE il catalogo
      // addon ha dichiarato fine (o non ha alcun catalogo paginabile).
      tmdbExhausted.intersect(stremioMore.filterValues { !it }.keys)
    }
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = emptySet()
  )

  /** Carica una sola volta il provider aperto; [force] serve al retry esplicito. */
  fun load(provider: StreamingProvider, force: Boolean = false) {
    if (!force && loadedProviderId == provider.id) return
    loadedProviderId = provider.id
    loadedProvider = provider
    initialLoad?.cancel()
    initialLoad = null

    // Reset completo dello stato di paginazione del provider precedente.
    tmdbPages.clear()
    tmdbRetryAfter.clear()
    stremioRetryAfter.clear()
    _tmdbHasMorePages.value = emptyMap()
    _stremioHasMorePages.value = emptyMap()
    _loadingKeys.value = emptySet()
    _stremioItems.value = emptyList()
    _tmdbItems.value = emptyList()
    _coverage.value = ProviderCoverage.NONE
    _fallbackToDefault.value = false
    _isLoading.value = true
    _error.value = false
    _isLoadingMore.value = false

    val plannedCoverage = StremioCatalogRepository.providerCoverage(provider)

    val initial = viewModelScope.launch {
      try {
        if (plannedCoverage == ProviderCoverage.NONE) return@launch
        try {
          val page = StremioCatalogRepository.sanitizeProviderCatalogPage(
            StremioCatalogRepository.loadProviderCatalogForProvider(provider)
          )
          applyStremioFirstPage(provider, plannedCoverage, page)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Come la TV: un errore Stremio con binding dichiarati degrada a PARTIAL,
          // così il catalogo TMDB può comunque popolare la griglia.
          Log.w(TAG, "Catalogo Stremio provider ${provider.id} fallito: ${e.message}")
          _coverage.value = ProviderCoverage.PARTIAL
        }
      } finally {
        _isLoading.value = false
      }
    }
    initialLoad = initial

    // Prima pagina di ogni tipo: il ViewModel ignora le chiamate duplicate o già
    // esaurite, quindi può essere invocato senza rischio sia qui sia dallo scrolling.
    // Crunchyroll (283): catalogo primariamente TV/anime → prima le serie TV.
    if (provider.tmdbProviderId == CRUNCHYROLL_TMDB_ID) {
      loadNextProviderPage(provider.tmdbProviderId, isTv = true)
      loadNextProviderPage(provider.tmdbProviderId, isTv = false)
    } else {
      loadNextProviderPage(provider.tmdbProviderId, isTv = false)
      loadNextProviderPage(provider.tmdbProviderId, isTv = true)
    }
  }

  fun retry(provider: StreamingProvider) = load(provider, force = true)

  /** true se per la coppia (provider, tipo) esiste ancora una sorgente paginabile. */
  fun hasMoreFor(providerId: Int, isTv: Boolean): Boolean =
    Pair(providerId, isTv) !in exhaustedKeys.value

  /**
   * Carica la pagina successiva del provider per il tipo richiesto. Stessa semantica
   * della TV: Stremio per primo quando la copertura è attiva (con attesa del
   * caricamento iniziale), TMDB come completamento o fonte unica.
   */
  fun loadNextProviderPage(providerId: Int, isTv: Boolean) {
    val key = Pair(providerId, isTv)
    val provider = loadedProvider?.takeIf { it.tmdbProviderId == providerId } ?: return
    val coverage = _coverage.value

    if (coverage != ProviderCoverage.NONE) {
      val stremioHasMore = _stremioHasMorePages.value[key] ?: true
      val stremioBlocked = System.currentTimeMillis() < (stremioRetryAfter[key] ?: 0L)
      if (!stremioHasMore && coverage == ProviderCoverage.FULL) {
        // Esaurito in una richiesta precedente: il catalogo TMDB completa quello
        // dell'addon e la UI deve poterli unire.
        markStremioCatalogExhausted()
      }
      if (stremioHasMore && !stremioBlocked && key !in _loadingKeys.value) {
        val mediaType = if (isTv) MediaType.SERIE_TV else MediaType.FILM
        setLoading(key, true)
        viewModelScope.launch {
          try {
            // Attende la prima pagina del provider: la paginazione non deve sovrascriverla.
            initialLoad?.join()
            if (loadedProvider?.id != provider.id) return@launch
            if ((_stremioHasMorePages.value[key] ?: true) == false) return@launch

            val more = StremioCatalogRepository.paginateProviderCatalog(provider, mediaType)
            if (loadedProvider?.id != provider.id) return@launch

            // Read-modify-write ATOMICO: Film e Serie paginano in parallelo.
            var added = false
            if (more.isNotEmpty()) {
              _stremioItems.update { current ->
                val combined = StremioCatalogRepository.deduplicateCrossSource(current + more)
                added = combined.size > current.size
                combined
              }
            }

            if (added) {
              // Una risposta piena con titoli nuovi NON azzera hasMore.
              _stremioHasMorePages.update { it + (key to true) }
              stremioRetryAfter.remove(key)
            } else {
              // Nessun titolo nuovo: pagina vuota oppure l'addon ignora `skip` e
              // riconsegna duplicati. È l'UNICO punto che ferma la paginazione Stremio.
              _stremioHasMorePages.update { it + (key to false) }
              if (_coverage.value == ProviderCoverage.FULL) {
                markStremioCatalogExhausted()
                loadNextProviderTmdbPage(providerId, isTv)
              }
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Errore paginazione provider Stremio: ${e.message}")
            // Un errore non può bloccare il catalogo del provider: Stremio resta
            // ritentabile ai trigger successivi (dopo il cooldown) e, con copertura
            // FULL, TMDB porta avanti il catalogo nel frattempo.
            stremioRetryAfter[key] = System.currentTimeMillis() + PROVIDER_PAGE_RETRY_COOLDOWN_MS
            if (_coverage.value == ProviderCoverage.FULL) {
              markStremioCatalogExhausted()
              loadNextProviderTmdbPage(providerId, isTv)
            }
          } finally {
            setLoading(key, false)
          }
        }
        // FULL: solo Stremio finché ha pagine. PARTIAL: nessun return anticipato,
        // la paginazione TMDB prosegue sotto come completamento del catalogo.
        if (_coverage.value == ProviderCoverage.FULL) return
      } else if (_coverage.value == ProviderCoverage.FULL) {
        if (stremioBlocked) {
          // Errore recente della sorgente addon: non si rilancia subito la fetch
          // (anti-raffica). Si degrada a PARTIAL e TMDB porta avanti il catalogo.
          markStremioCatalogExhausted()
        } else {
          // Fetch Stremio già in corso: si attende il suo esito.
          return
        }
      }
    }

    loadNextProviderTmdbPage(providerId, isTv)
  }

  /**
   * Prima pagina Stremio del provider: determina la copertura EFFETTIVA (FULL vale
   * solo se il fetch produce davvero item) e pubblica i titoli con ordine stabile.
   */
  private fun applyStremioFirstPage(
    provider: StreamingProvider,
    plannedCoverage: ProviderCoverage,
    page: StremioCatalogRepository.ProviderCatalogPage
  ) {
    val coverage = when {
      plannedCoverage == ProviderCoverage.NONE -> ProviderCoverage.NONE
      page.items.isEmpty() -> ProviderCoverage.PARTIAL
      else -> plannedCoverage
    }
    _coverage.value = coverage
    if (coverage == ProviderCoverage.NONE) {
      // Nessun binding confermato: si mantiene il catalogo nativo TMDB.
      _stremioItems.value = emptyList()
      return
    }
    _stremioItems.value = StremioCatalogRepository.stabilizeProviderOrder(page.items)
    _fallbackToDefault.value = page.fallbackToDefault
  }

  /**
   * Paginazione TMDB della coppia (provider, tipo), robusta agli errori.
   *
   * Il cursore parte sempre dalla pagina 1 della sessione (come il caricamento
   * mobile precedente) e avanza SOLO dopo una risposta riuscita: una pagina fallita
   * non viene saltata né marca il catalogo come esaurito, quindi il trigger
   * successivo (scroll o riapertura della schermata) ritenta la STESSA pagina.
   */
  private fun loadNextProviderTmdbPage(providerId: Int, isTv: Boolean) {
    val key = Pair(providerId, isTv)
    if (key in _loadingKeys.value) return
    if ((_tmdbHasMorePages.value[key] ?: true) == false) return
    // Anti-raffica: una pagina fallita poco fa non viene richiesta di nuovo finché
    // non è trascorso il cooldown. Il catalogo NON è dichiarato esaurito.
    if (System.currentTimeMillis() < (tmdbRetryAfter[key] ?: 0L)) return
    setLoading(key, true)

    viewModelScope.launch {
      try {
        val nextPage = (tmdbPages[key] ?: 0) + 1
        if (nextPage > MediaRepository.MAX_PROVIDER_PAGES) {
          _tmdbHasMorePages.update { it + (key to false) }
          return@launch
        }

        MediaRepository.loadProviderCatalog(providerId, isTv, nextPage).collect { page ->
          if (loadedProvider?.tmdbProviderId != providerId) return@collect
          if (page.requestSucceeded) {
            tmdbPages[key] = page.page
            tmdbRetryAfter.remove(key)
            _tmdbHasMorePages.update { it + (key to page.hasMore) }
            if (page.items.isNotEmpty()) {
              _tmdbItems.update { current ->
                StremioCatalogRepository.deduplicateCrossSource(current + page.items)
              }
            }
          } else {
            // Errore di rete/HTTP: la pagina NON esiste, quindi il cursore resta
            // fermo e il catalogo NON viene dichiarato esaurito. Al prossimo trigger
            // (dopo il cooldown) si ritenta la stessa pagina.
            tmdbRetryAfter[key] = System.currentTimeMillis() + PROVIDER_PAGE_RETRY_COOLDOWN_MS
            _tmdbHasMorePages.update { it + (key to true) }
            Log.w(
              TAG,
              "Pagina provider $providerId (isTv=$isTv, page=$nextPage) non riuscita: " +
                "progressione invariata, nuova richiesta al prossimo trigger"
            )
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        tmdbRetryAfter[key] = System.currentTimeMillis() + PROVIDER_PAGE_RETRY_COOLDOWN_MS
        // Come il caricamento precedente: se il provider non ha prodotto NESSUN
        // titolo (né Stremio né TMDB) lo stato è errore, non "catalogo vuoto".
        if (_stremioItems.value.isEmpty() && _tmdbItems.value.isEmpty()) {
          _error.value = true
        }
        Log.w(TAG, "Errore caricamento pagina provider $providerId (isTv=$isTv): ${e.message}")
      } finally {
        setLoading(key, false)
      }
    }
  }

  /**
   * Il catalogo dell'addon è terminato: se la copertura era FULL non può più
   * rappresentare da solo l'intero catalogo del provider. Si degrada a PARTIAL,
   * così la schermata unisce anche i titoli TMDB e la paginazione TMDB porta il
   * catalogo fino all'ultimo risultato disponibile.
   */
  private fun markStremioCatalogExhausted() {
    if (_coverage.value == ProviderCoverage.FULL) {
      _coverage.value = ProviderCoverage.PARTIAL
    }
  }

  private fun setLoading(key: Pair<Int, Boolean>, loading: Boolean) {
    _loadingKeys.update { current ->
      if (loading) current + key else current - key
    }
    _isLoadingMore.value = _loadingKeys.value.isNotEmpty()
  }

  private companion object {
    private const val TAG = "MobileProviderVM"
    private const val CRUNCHYROLL_TMDB_ID = 283

    /** Cooldown anti-raffica dopo una pagina fallita (stesso valore della TV). */
    private const val PROVIDER_PAGE_RETRY_COOLDOWN_MS = 2_000L
  }
}
