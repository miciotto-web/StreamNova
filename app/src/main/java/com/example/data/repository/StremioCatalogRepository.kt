package com.example.data.repository

import android.util.Log
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.stremio.StremioCatalogEngine
import com.example.data.stremio.StremioCatalogMapper
import com.example.data.stremio.StremioCatalogTarget
import com.example.data.stremio.StremioMetaItem
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES
import com.example.data.stremio.EXTRA_GENRE
import com.example.data.stremio.provider.CatalogEntryResult
import com.example.data.stremio.provider.CatalogKey
import com.example.data.stremio.provider.CatalogProvenance
import com.example.data.stremio.provider.ProviderCatalogAssembler
import com.example.data.stremio.provider.ProviderCatalogPaging
import com.example.data.stremio.provider.ProviderCatalogPlan
import com.example.data.stremio.provider.ProviderCatalogPlanner
import com.example.data.stremio.provider.ProviderCatalogResolver
import com.example.data.stremio.provider.ProviderCatalogResolvers
import com.example.data.stremio.provider.ResolvedProviderTarget
import com.example.ui.components.ProviderConstants
import com.example.ui.components.StreamingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Origine di provenienza di un catalogo o contenuto nel data layer.
 */
enum class CatalogSourceOrigin {
  TMDB_NATIVE,
  STREMIO_ADDON
}

/**
 * Copertura del catalogo Provider da parte di Stremio.
 *
 * - [NONE]     nessun binding confermato: solo catalogo nativo TMDB.
 * - [PARTIAL]  binding presenti ma limitati (es. soli cataloghi Top10):
 *              Stremio resta la fonte primaria e TMDB completa la lista.
 * - [FULL]     target confermati per movie E series con almeno un catalogo
 *              non-Top10 per ciascun tipo: solo Stremio.
 */
enum class ProviderCoverage {
  NONE,
  PARTIAL,
  FULL
}

/**
 * Sezione di catalogo restituita dal data layer, pronta per la futura integrazione UI.
 *
 * @param id identificatore univoco della sezione.
 * @param title titolo descrittivo della sezione (es. "Top Movies • Cinemeta").
 * @param addonId ID dell'addon Stremio sorgente (null se nativo TMDB).
 * @param addonName nome visualizzato dell'addon sorgente (null se nativo TMDB).
 * @param catalogId ID del catalogo Stremio (es. "top").
 * @param mediaType tipo di contenuto (FILM o SERIE_TV).
 * @param origin sorgente di provenienza (TMDB_NATIVE o STREMIO_ADDON).
 * @param items contenuti convertiti nel modello universale [MediaItem].
 * @param rawItems metadati originali [StremioMetaItem] restituiti dall'addon.
 * @param supportsSearch indica se la sezione supporta ricerca testuale.
 * @param supportsGenre indica se la sezione supporta filtro per genere.
 * @param genreOptions opzioni di genere supportate.
 * @param supportsSkip indica se la sezione supporta la paginazione con skip.
 */
data class CatalogSection(
  val id: String,
  val title: String,
  val addonId: String? = null,
  val addonName: String? = null,
  val catalogId: String? = null,
  val mediaType: MediaType = MediaType.FILM,
  val origin: CatalogSourceOrigin = CatalogSourceOrigin.STREMIO_ADDON,
  val items: List<MediaItem> = emptyList(),
  val rawItems: List<StremioMetaItem> = emptyList(),
  val supportsSearch: Boolean = false,
  val supportsGenre: Boolean = false,
  val genreOptions: List<String> = emptyList(),
  val supportsSkip: Boolean = false
) {
  val isEmpty: Boolean
    get() = items.isEmpty()
}

/**
 * Raggruppamento dei cataloghi per la schermata Home di StreamNova.
 */
data class HomeCatalogs(
  val heroItems: List<MediaItem> = emptyList(),
  val top10Movies: List<MediaItem> = emptyList(),
  val top10Series: List<MediaItem> = emptyList(),
  val trendingMovies: List<MediaItem> = emptyList(),
  val trendingSeries: List<MediaItem> = emptyList(),
  val forYouMovies: List<MediaItem> = emptyList(),
  val forYouSeries: List<MediaItem> = emptyList(),
  val popularMovies: List<MediaItem> = emptyList(),
  val popularSeries: List<MediaItem> = emptyList(),
  val extraSections: List<CatalogSection> = emptyList(),
  val isMoviesFromStremio: Boolean = false,
  val isSeriesFromStremio: Boolean = false,
  val allMovies: List<MediaItem> = emptyList(),
  val allSeries: List<MediaItem> = emptyList()
)

/**
 * Repository del data layer per i cataloghi degli addon Stremio.
 *
 * RESPONSABILITÀ:
 *  - Espone i cataloghi degli addon Stremio come sorgente aggiuntiva rispetto a TMDB;
 *  - Mantiene rigorosamente separata la provenienza (TMDB_NATIVE vs STREMIO_ADDON);
 *  - Supporta più addon simultaneamente con esecuzione parallela e isolamento degli errori;
 *  - Indipendente da TorBox, Debrid e StreamingEngineMode: opera puramente sui manifest degli addon attivi;
 *  - Fornisce sezioni e contenuti formattati in [MediaItem] tramite [StremioCatalogMapper].
 */
object StremioCatalogRepository {

  private const val TAG = "StremioCatalogRepo"

  /**
   * Paginazione per singolo catalogo: ogni target mantiene il proprio offset `skip`.
   * L'offset è un concetto del singolo endpoint `catalog/{type}/{id}`, non del provider.
   */
  private val providerPaging = ProviderCatalogPaging()

  /**
   * Converte un [MediaType] dell'app nel tipo Stremio corrispondente ("movie", "series").
   */
  fun toStremioType(mediaType: MediaType?): String? = when (mediaType) {
    MediaType.FILM -> TYPE_MOVIE
    MediaType.SERIE_TV -> TYPE_SERIES
    else -> null
  }

  /**
   * Converte un tipo Stremio ("movie", "series") nel [MediaType] dell'app.
   */
  fun toMediaType(stremioType: String): MediaType = when (stremioType.lowercase().trim()) {
    TYPE_SERIES -> MediaType.SERIE_TV
    else -> MediaType.FILM
  }

  // ── Provider Catalog Resolver (Fase C2) ─────────────────────────────────────────

  /**
   * Resolver dichiarativo con precedenza USER → REGISTRY → `null`.
   * Se non è stato inizializzato, viene costruito con la registry bundled
   * e i binding utente già presenti su DataStore.
   */
  fun resolver(): ProviderCatalogResolver = ProviderCatalogResolvers.get()

  /**
   * Restituisce tutti i target di catalogo disponibili dagli addon Stremio attivi.
   * Non effettua chiamate di rete: legge i manifest già installati e validati.
   */
  fun getAvailableTargets(mediaType: MediaType? = null): List<StremioCatalogTarget> {
    val stremioType = toStremioType(mediaType)
    return StremioCatalogEngine.getAvailableCatalogs(stremioType)
  }

  /**
   * Carica una singola sezione di catalogo Stremio a partire da un [StremioCatalogTarget].
   *
   * @param target target del catalogo (addon + definizione catalogo).
   * @param extra parametri extra opzionali (search, genre, skip, ecc.).
   * @param skipCache se true forza il bypass della cache in memoria.
   */
  suspend fun loadCatalogSection(
    target: StremioCatalogTarget,
    extra: Map<String, String> = emptyMap(),
    skipCache: Boolean = false
  ): CatalogSection = withContext(Dispatchers.IO) {
    val result = StremioCatalogEngine.fetchCatalog(target, extra, skipCache)

    // Un catalogo dichiarato dalla registry o dall'utente appartiene a un provider:
    // l'item riporta il BRAND ("Netflix", "Disney+", "HBO Max", ...) invece del nome
    // dell'addon, così i filtri per brand della schermata categoria lo trovano.
    // Senza binding resta il comportamento precedente: provider = nome dell'addon.
    val binding = resolver().resolve(
      CatalogKey(target.addonId, target.type, target.catalogId)
    )
    val brandName = binding?.providerId?.let { providerId ->
      ProviderConstants.ALL.firstOrNull { it.id == providerId }?.name
    }

    val mediaItems = StremioCatalogMapper.toMediaItemList(
      result.items,
      brandName ?: target.addonName
    )

    CatalogSection(
      id = "stremio_${target.addonId}_${target.type}_${target.catalogId}",
      title = "${target.title} • ${target.addonName}",
      addonId = target.addonId,
      addonName = target.addonName,
      catalogId = target.catalogId,
      mediaType = toMediaType(target.type),
      origin = CatalogSourceOrigin.STREMIO_ADDON,
      items = mediaItems,
      rawItems = result.items,
      supportsSearch = target.supportsSearch,
      supportsGenre = target.supportsGenre,
      genreOptions = target.genreOptions,
      supportsSkip = target.supportsSkip
    )
  }

  /**
   * Carica in parallelo tutte le sezioni di catalogo Stremio disponibili per un dato [MediaType].
   * Isola gli errori: l'eventuale errore di un addon non impedisce il caricamento delle altre sezioni.
   */
  suspend fun loadAllCatalogSections(
    mediaType: MediaType? = null,
    extra: Map<String, String> = emptyMap()
  ): List<CatalogSection> = coroutineScope {
    val stremioType = toStremioType(mediaType)
    val targets = StremioCatalogEngine.getAvailableCatalogs(stremioType)
      .filter { it.catalog.canLoadWithoutArgs() || extra.isNotEmpty() }

    if (targets.isEmpty()) return@coroutineScope emptyList()

    targets.map { target ->
      async {
        try {
          loadCatalogSection(target, extra)
        } catch (e: Exception) {
          Log.w(TAG, "Caricamento fallito per ${target.id}: ${e.message}")
          null
        }
      }
    }.awaitAll()
      .filterNotNull()
      .filterNot { it.isEmpty }
  }

  /**
   * Esegue una ricerca testuale su tutti i cataloghi Stremio attivi che supportano il parametro `search`.
   * Restituisce gli elementi convertiti in [MediaItem], deduplicati per id.
   */
  suspend fun searchAddonCatalogs(
    query: String,
    mediaType: MediaType? = null
  ): List<MediaItem> = withContext(Dispatchers.IO) {
    val stremioType = toStremioType(mediaType)
    val results = StremioCatalogEngine.searchCatalogs(query, stremioType)
    results.flatMap { result ->
      StremioCatalogMapper.toMediaItemList(result.items, result.addonName)
    }.distinctBy { it.id }
  }

  /**
   * Filtra i cataloghi Stremio attivi per genere (parametro `genre`).
   * Restituisce gli elementi convertiti in [MediaItem], deduplicati per id.
   */
  suspend fun loadAddonCatalogByGenre(
    genre: String,
    mediaType: MediaType? = null
  ): List<MediaItem> = withContext(Dispatchers.IO) {
    val stremioType = toStremioType(mediaType)
    val results = StremioCatalogEngine.fetchByGenre(genre, stremioType)
    results.flatMap { result ->
      StremioCatalogMapper.toMediaItemList(result.items, result.addonName)
    }.distinctBy { it.id }
  }

  /**
   * Richiede la pagina successiva di una sezione tramite paginazione offset `skip`.
   */
  suspend fun paginateSection(
    section: CatalogSection,
    skip: Int,
    baseExtra: Map<String, String> = emptyMap()
  ): CatalogSection? = withContext(Dispatchers.IO) {
    val addonId = section.addonId ?: return@withContext null
    val catalogId = section.catalogId ?: return@withContext null
    val target = getAvailableTargets().firstOrNull {
      it.addonId == addonId && it.catalogId == catalogId
    } ?: return@withContext null

    val result = StremioCatalogEngine.paginateCatalog(target, skip, baseExtra)
    if (!result.isSuccess || result.isEmpty) return@withContext null

    val newMediaItems = StremioCatalogMapper.toMediaItemList(result.items, section.addonName)
    section.copy(
      items = section.items + newMediaItems,
      rawItems = section.rawItems + result.items
    )
  }

  // ── Home Catalog Resolution & Fallback ───────────────────────────────

  /**
   * Costruisce lo stato dei cataloghi Home usando esclusivamente la sorgente nativa TMDB.
   */
  fun buildTmdbHomeCatalogs(tmdbMedia: List<MediaItem>): HomeCatalogs {
    val top10M = MediaRepository.getTop10Movies()
    val top10S = MediaRepository.getTop10Series()
    val trendM = MediaRepository.getTrendingMovies()
    val trendS = MediaRepository.getTrendingSeries()
    val forYouM = MediaRepository.getForYouMovies()
    val forYouS = MediaRepository.getForYouSeries()
    val popM = MediaRepository.getPopularMovies()
    val popS = MediaRepository.getPopularSeries()
    val hero = buildHeroItems(
      popM.ifEmpty { tmdbMedia.filter { it.type == MediaType.FILM } },
      popS.ifEmpty { tmdbMedia.filter { it.type == MediaType.SERIE_TV } },
      fallback = tmdbMedia
    )
    return HomeCatalogs(
      heroItems = hero,
      top10Movies = top10M,
      top10Series = top10S,
      trendingMovies = trendM,
      trendingSeries = trendS,
      forYouMovies = forYouM,
      forYouSeries = forYouS,
      popularMovies = popM,
      popularSeries = popS,
      extraSections = emptyList(),
      isMoviesFromStremio = false,
      isSeriesFromStremio = false,
      allMovies = tmdbMedia.filter { it.type == MediaType.FILM },
      allSeries = tmdbMedia.filter { it.type == MediaType.SERIE_TV }
    )
  }

  /**
   * Deduplica una lista di [MediaItem] preservando l'ordine originale, basandosi su id univoco stabile.
   */
  fun deduplicateItems(items: List<MediaItem>): List<MediaItem> {
    val seen = mutableSetOf<String>()
    return items.filter { item ->
      val key = item.id.trim().lowercase().ifBlank { "${item.title.trim().lowercase()}_${item.year}" }
      seen.add(key)
    }
  }

  /**
   * Chiavi candidato di un titolo per la deduplicazione cross-sorgente
   * (Stremio ↔ TMDB). Un item è duplicato se UNA qualsiasi chiave è già vista:
   * Stremio usa id IMDb/`tmdb:` grezzi, TMDB usa `tmdb_m_<id>`/`tmdb_tv_<id>`,
   * quindi il solo `item.id` non li collega mai.
   */
  private fun crossSourceKeys(item: MediaItem): Set<String> {
    val type = item.type.name.lowercase()
    val keys = mutableSetOf<String>()
    item.tmdbId?.let { keys += "$type|tmdb:$it" }
    val rawId = item.id.trim()
    if (rawId.startsWith("tt", ignoreCase = true)) {
      keys += "$type|imdb:${rawId.lowercase()}"
    }
    keys += "$type|${item.title.trim().lowercase()}_${item.year}"
    return keys
  }

  /**
   * Merge/dedup cross-sorgente per gli elenchi che uniscono Stremio e TMDB.
   *
   * L'ordine d'ingresso è preservato (chi arriva prima resta davanti). Quando lo
   * stesso contenuto è presente in entrambe le sorgenti, l'item TENUTO resta quello
   * già inserito (visivamente Stremio, se era in testa): se però non ha `tmdbId`
   * e il duplicato TMDB sì, il `tmdbId` viene copiato sull'item tenuto, così la
   * navigazione Detail (`MainActivity`: `media.tmdbId ?: 0`) continua a funzionare
   * senza cambiare la sorgente visiva.
   */
  fun deduplicateCrossSource(items: List<MediaItem>): List<MediaItem> {
    val seen = mutableSetOf<String>()
    val result = ArrayList<MediaItem>(items.size)
    // posizione nell'output per ogni chiave già tenuta: serve per l'arricchimento.
    val positionByKey = HashMap<String, Int>()

    items.forEach { item ->
      val keys = crossSourceKeys(item)
      // posizione di un item già tenuto che può essere arricchito con il tmdbId.
      val enrichTargetIndex = keys.firstNotNullOfOrNull { key ->
        positionByKey[key]?.takeIf { index -> result[index].tmdbId == null && item.tmdbId != null }
      }
      val firstSeenKey = keys.firstOrNull { it in seen }

      if (firstSeenKey == null) {
        seen += keys
        val index = result.size
        result += item
        keys.forEach { key -> if (positionByKey[key] == null) positionByKey[key] = index }
        return@forEach
      }

      // Duplicato: se l'item tenuto non ha tmdbId e questo sì, arricchisci il
      // dato TMDB mantenendo item, id e provider della sorgente già visualizzata.
      if (enrichTargetIndex != null) {
        result[enrichTargetIndex] = result[enrichTargetIndex].copy(tmdbId = item.tmdbId)
      }
      seen += keys
    }
    return result
  }

  /**
   * Seleziona fino a 9 titoli alternando film e serie per l'Hero Banner.
   */
  fun buildHeroItems(
    movies: List<MediaItem>,
    series: List<MediaItem>,
    fallback: List<MediaItem> = emptyList()
  ): List<MediaItem> {
    val mWithBackdrop = movies.filter { !it.backdropUrl.isNullOrBlank() || it.backdropRes != null }
    val sWithBackdrop = series.filter { !it.backdropUrl.isNullOrBlank() || it.backdropRes != null }
    val combined = mutableListOf<MediaItem>()
    val maxLen = maxOf(mWithBackdrop.size, sWithBackdrop.size)
    for (i in 0 until maxLen) {
      if (i < mWithBackdrop.size && combined.size < 9 && combined.none { it.id == mWithBackdrop[i].id }) {
        combined.add(mWithBackdrop[i])
      }
      if (i < sWithBackdrop.size && combined.size < 9 && combined.none { it.id == sWithBackdrop[i].id }) {
        combined.add(sWithBackdrop[i])
      }
    }
    return when {
      combined.isNotEmpty() -> combined
      fallback.isNotEmpty() -> fallback.take(9)
      else -> (movies + series).take(9)
    }
  }

  /**
   * Risolve i cataloghi per la schermata Home:
   * 1. Se ci sono addon Stremio attivi con cataloghi per film/serie, prova a scaricarli.
   * 2. Se Stremio ha successo, usa i cataloghi dell'addon.
   * 3. Se nessun addon è compatibile o il download fallisce/va in timeout, ripiega su TMDB in modo trasparente.
   * 4. Film e serie sono gestiti separatamente.
   * 5. Gli elementi sono deduplicati stabilmente.
   */
  suspend fun resolveHomeCatalogs(tmdbMedia: List<MediaItem>): HomeCatalogs = withContext(Dispatchers.IO) {
    // 1. Film
    val movieTargets = getAvailableTargets(MediaType.FILM)
    val stremioMovieSections = if (movieTargets.isNotEmpty()) {
      try {
        loadAllCatalogSections(MediaType.FILM)
      } catch (e: Exception) {
        Log.w(TAG, "Tentativo Stremio film fallito, fallback TMDB: ${e.message}")
        emptyList()
      }
    } else {
      emptyList()
    }

    val hasStremioMovies = stremioMovieSections.isNotEmpty()
    val allStremioMovies = if (hasStremioMovies) {
      deduplicateItems(stremioMovieSections.flatMap { it.items })
    } else {
      emptyList()
    }

    val top10M: List<MediaItem>
    val trendingM: List<MediaItem>
    val popularM: List<MediaItem>
    val forYouM: List<MediaItem>

    if (hasStremioMovies && allStremioMovies.isNotEmpty()) {
      val topSec = stremioMovieSections.firstOrNull { it.catalogId?.contains("top", ignoreCase = true) == true || it.title.contains("top", ignoreCase = true) }
      val trendSec = stremioMovieSections.firstOrNull { it.catalogId?.contains("trend", ignoreCase = true) == true || it.title.contains("trend", ignoreCase = true) }
      val popSec = stremioMovieSections.firstOrNull { it.catalogId?.contains("pop", ignoreCase = true) == true || it.title.contains("pop", ignoreCase = true) }

      top10M = (topSec?.items ?: allStremioMovies).take(10)
      trendingM = trendSec?.items ?: allStremioMovies.drop(3).take(20).ifEmpty { allStremioMovies }
      popularM = popSec?.items ?: allStremioMovies
      forYouM = allStremioMovies.reversed().take(20).ifEmpty { allStremioMovies }
    } else {
      // Fallback TMDB
      top10M = MediaRepository.getTop10Movies()
      trendingM = MediaRepository.getTrendingMovies()
      popularM = MediaRepository.getPopularMovies()
      forYouM = MediaRepository.getForYouMovies()
    }

    // 2. Serie TV
    val seriesTargets = getAvailableTargets(MediaType.SERIE_TV)
    val stremioSeriesSections = if (seriesTargets.isNotEmpty()) {
      try {
        loadAllCatalogSections(MediaType.SERIE_TV)
      } catch (e: Exception) {
        Log.w(TAG, "Tentativo Stremio serie fallito, fallback TMDB: ${e.message}")
        emptyList()
      }
    } else {
      emptyList()
    }

    val hasStremioSeries = stremioSeriesSections.isNotEmpty()
    val allStremioSeries = if (hasStremioSeries) {
      deduplicateItems(stremioSeriesSections.flatMap { it.items })
    } else {
      emptyList()
    }

    val top10S: List<MediaItem>
    val trendingS: List<MediaItem>
    val popularS: List<MediaItem>
    val forYouS: List<MediaItem>

    if (hasStremioSeries && allStremioSeries.isNotEmpty()) {
      val topSec = stremioSeriesSections.firstOrNull { it.catalogId?.contains("top", ignoreCase = true) == true || it.title.contains("top", ignoreCase = true) }
      val trendSec = stremioSeriesSections.firstOrNull { it.catalogId?.contains("trend", ignoreCase = true) == true || it.title.contains("trend", ignoreCase = true) }
      val popSec = stremioSeriesSections.firstOrNull { it.catalogId?.contains("pop", ignoreCase = true) == true || it.title.contains("pop", ignoreCase = true) }

      top10S = (topSec?.items ?: allStremioSeries).take(10)
      trendingS = trendSec?.items ?: allStremioSeries.drop(3).take(20).ifEmpty { allStremioSeries }
      popularS = popSec?.items ?: allStremioSeries
      forYouS = allStremioSeries.reversed().take(20).ifEmpty { allStremioSeries }
    } else {
      // Fallback TMDB
      top10S = MediaRepository.getTop10Series()
      trendingS = MediaRepository.getTrendingSeries()
      popularS = MediaRepository.getPopularSeries()
      forYouS = MediaRepository.getForYouSeries()
    }

    // 3. Hero items
    val heroMovies = if (hasStremioMovies && allStremioMovies.isNotEmpty()) allStremioMovies else tmdbMedia.filter { it.type == MediaType.FILM }
    val heroSeries = if (hasStremioSeries && allStremioSeries.isNotEmpty()) allStremioSeries else tmdbMedia.filter { it.type == MediaType.SERIE_TV }
    val hero = buildHeroItems(heroMovies, heroSeries, fallback = tmdbMedia)

    // 4. Sezioni addon extra (eventuali cataloghi ulteriori esposti da addon)
    val extraSections = (stremioMovieSections + stremioSeriesSections)
      .filter { it.items.isNotEmpty() }
      .distinctBy { it.id }

    // Stremio resta in testa, TMDB completa la lista: la deduplicazione è
    // cross-source perché gli id delle due sorgenti non coincidono mai.
    val finalMovies = if (hasStremioMovies && allStremioMovies.isNotEmpty()) {
      deduplicateCrossSource(allStremioMovies + tmdbMedia.filter { it.type == MediaType.FILM })
    } else {
      tmdbMedia.filter { it.type == MediaType.FILM }
    }
    val finalSeries = if (hasStremioSeries && allStremioSeries.isNotEmpty()) {
      deduplicateCrossSource(allStremioSeries + tmdbMedia.filter { it.type == MediaType.SERIE_TV })
    } else {
      tmdbMedia.filter { it.type == MediaType.SERIE_TV }
    }

    HomeCatalogs(
      heroItems = hero,
      top10Movies = top10M,
      top10Series = top10S,
      trendingMovies = trendingM,
      trendingSeries = trendingS,
      forYouMovies = forYouM,
      forYouSeries = forYouS,
      popularMovies = popularM,
      popularSeries = popularS,
      extraSections = extraSections,
      isMoviesFromStremio = hasStremioMovies && allStremioMovies.isNotEmpty(),
      isSeriesFromStremio = hasStremioSeries && allStremioSeries.isNotEmpty(),
      allMovies = finalMovies,
      allSeries = finalSeries
    )
  }

  // ── Provider Catalogs ────────────────────────────────────────────────

  /**
   * Pagina di catalogo Provider pronta per la UI.
   *
   * @param mediaType tipo richiesto, `null` per entrambi.
   * @param items titoli deduplicati per id.
   * @param provenance origini addon+catalogo di ogni titolo: conservate anche quando
   *        lo stesso titolo arriva da più addon o più cataloghi.
   * @param hasConfirmedBindings true se esiste almeno un binding USER/REGISTRY: in tal
   *        caso il catalogo Stremio SOSTITUISCE il catalogo nativo TMDB.
   * @param targets cataloghi confermati effettivamente interrogati.
   */
  data class ProviderCatalogPage(
    val mediaType: MediaType? = null,
    val items: List<MediaItem> = emptyList(),
    val provenance: Map<String, List<CatalogProvenance>> = emptyMap(),
    val hasConfirmedBindings: Boolean = false,
    val targets: List<ResolvedProviderTarget> = emptyList()
  )

  /**
   * Piano di caricamento per provider, calcolato dai soli manifest (nessuna rete).
   * Con [mediaType] non nullo contiene esclusivamente cataloghi di quel tipo: la
   * separazione Film/Serie è applicata prima di qualsiasi lookup.
   */
  fun providerCatalogPlan(
    provider: StreamingProvider,
    mediaType: MediaType? = null
  ): ProviderCatalogPlan {
    val stremioType = toStremioType(mediaType)
    return ProviderCatalogPlanner.plan(
      providerId = provider.id,
      targets = StremioCatalogEngine.getAvailableCatalogs(stremioType),
      resolver = resolver(),
      mediaType = mediaType
    )
  }

  /**
   * Cataloghi Stremio **confermati** per un provider, con binding già risolto.
   * Un binding `movie` non può comparire per le serie e viceversa.
   */
  fun findConfirmedTargetsForProvider(
    provider: StreamingProvider,
    mediaType: MediaType? = null
  ): List<ResolvedProviderTarget> = providerCatalogPlan(provider, mediaType).resolved

  /**
   * true se il provider ha almeno un binding dichiarato: la UI deve allora usare il
   * catalogo Stremio al posto di quello nativo TMDB.
   */
  fun providerHasConfirmedBindings(
    provider: StreamingProvider,
    mediaType: MediaType? = null
  ): Boolean = providerCatalogPlan(provider, mediaType).hasConfirmedBindings

  /**
   * Copertura Stremio del provider, calcolata SOLO dai [ProviderCatalogPlan] già
   * esistenti (manifest + binding dichiarati, nessuna rete, nessun catalogId nuovo).
   *
   * Regole:
   * 1. [ProviderCoverage.NONE] se non esistono target confermati per alcun tipo.
   * 2. [ProviderCoverage.FULL] solo se esistono target per movie E per series E
   *    almeno un catalogo non-Top10 per ciascun tipo.
   * 3. [ProviderCoverage.PARTIAL] in ogni altro caso con binding presenti
   *    (es. Netflix: solo cataloghi Top10 confermati).
   *
   * NOTA: è una classificazione basata sul manifest. La validità di [FULL]
   * va comunque confermata dal fetch effettivo (vedi `StreamNovaViewModel`:
   * se il caricamento non produce item, FULL degrada a PARTIAL).
   */
  fun providerCoverage(provider: StreamingProvider): ProviderCoverage {
    val movieTargets = providerCatalogPlan(provider, MediaType.FILM).resolved
    val seriesTargets = providerCatalogPlan(provider, MediaType.SERIE_TV).resolved

    if (movieTargets.isEmpty() && seriesTargets.isEmpty()) return ProviderCoverage.NONE

    val hasFullMovieCatalog = movieTargets.any { !isTop10Target(it) }
    val hasFullSeriesCatalog = seriesTargets.any { !isTop10Target(it) }

    return if (hasFullMovieCatalog && hasFullSeriesCatalog) {
      ProviderCoverage.FULL
    } else {
      ProviderCoverage.PARTIAL
    }
  }

  /**
   * true se il catalogo dichiarato è un elenco Top10 (id o titolo del manifest).
   * Nessun catalogo viene inventato: si legge solo ciò che l'addon espone.
   */
  private fun isTop10Target(target: ResolvedProviderTarget): Boolean {
    val haystack = "${target.target.catalogId} ${target.target.title}".lowercase()
    return haystack.contains("top10") ||
      haystack.contains("top_10") ||
      haystack.contains("top-10") ||
      haystack.contains("top 10")
  }

  /** Azzera la paginazione per catalogo (da chiamare all'apertura di un provider). */
  fun resetProviderPaging() = providerPaging.reset()

  /** Snapshot degli offset per catalogo, per diagnostica. */
  fun providerPagingSnapshot(): Map<String, Int> = providerPaging.snapshot()

  /**
   * Carica i cataloghi Stremio confermati di un provider, separando Film e Serie.
   * Deduplica per id conservando la provenienza addon+catalogo.
   */
  suspend fun loadProviderCatalogPage(
    provider: StreamingProvider,
    mediaType: MediaType? = null
  ): ProviderCatalogPage = coroutineScope {
    val plan = providerCatalogPlan(provider, mediaType)
    if (!plan.hasConfirmedBindings) {
      return@coroutineScope ProviderCatalogPage(mediaType = mediaType)
    }

    val fetched = plan.resolved.map { resolved ->
      async {
        try {
          val result = StremioCatalogEngine.fetchCatalog(
            target = resolved.target,
            extra = ProviderCatalogPlanner.extraFor(resolved)
          )
          if (result.isSuccess) resolved to result.items else null
        } catch (e: Exception) {
          Log.w(TAG, "Catalogo provider fallito per ${resolved.targetId}: ${e.message}")
          null
        }
      }
    }.awaitAll().filterNotNull()

    collectProviderPage(mediaType, provider.name, fetched)
  }

  /**
   * Catalogo Provider da sorgente Stremio per entrambi i tipi. La UI continua a
   * ricevere una lista piatta di [MediaItem]: Film e Serie restano separati nel piano,
   * nella provenienza e nella paginazione.
   */
  suspend fun loadProviderCatalogForProvider(provider: StreamingProvider): ProviderCatalogPage {
    val movies = loadProviderCatalogPage(provider, MediaType.FILM)
    val series = loadProviderCatalogPage(provider, MediaType.SERIE_TV)
    return mergeProviderPages(movies, series)
  }

  /**
   * Pagina il catalogo Provider Stremio. Ogni catalogo confermato mantiene il PROPRIO
   * offset `skip`: il medesimo `skip` non viene più applicato indistintamente a tutti
   * i target. Con [mediaType] si pagina un solo tipo per volta.
   */
  suspend fun paginateProviderCatalog(
    provider: StreamingProvider,
    mediaType: MediaType? = null
  ): List<MediaItem> = coroutineScope {
    val targets = findConfirmedTargetsForProvider(provider, mediaType).filter { it.supportsSkip }
    if (targets.isEmpty()) return@coroutineScope emptyList()

    val fetched = targets.map { resolved ->
      async {
        try {
          val skip = providerPaging.nextOffset(resolved.targetId)
          val result = StremioCatalogEngine.paginateCatalog(
            target = resolved.target,
            skip = skip,
            baseExtra = ProviderCatalogPlanner.extraFor(resolved)
          )
          if (!result.isSuccess) return@async null
          // L'offset avanza SOLO di ciò che il catalogo ha consegnato.
          providerPaging.advance(resolved.targetId, result.items.size)
          resolved to result.items
        } catch (e: Exception) {
          Log.w(TAG, "Paginazione fallita per ${resolved.targetId}: ${e.message}")
          null
        }
      }
    }.awaitAll().filterNotNull()

    collectProviderPage(mediaType, provider.name, fetched).items
  }

  /**
   * Converte i risultati grezzi dei target in [ProviderCatalogPage], unendo per id e
   * conservando tutte le origini: un titolo presente in due addon mantiene due voci
   * di provenienza invece di essere scartato.
   */
  private fun collectProviderPage(
    mediaType: MediaType?,
    providerName: String,
    fetched: List<Pair<ResolvedProviderTarget, List<StremioMetaItem>>>
  ): ProviderCatalogPage {
    val assembled = ProviderCatalogAssembler.assemble(providerName, fetched.map { pair ->
      CatalogEntryResult(pair.first, pair.second)
    })
    val deduped: List<MediaItem> = assembled.items
    val provenance = assembled.provenance
    return ProviderCatalogPage(
      mediaType = mediaType,
      items = deduped,
      provenance = provenance,
      hasConfirmedBindings = fetched.isNotEmpty(),
      targets = fetched.map { it.first }
    )
  }

  /** Unisce le pagine Film e Serie di un provider, conservando la provenienza. */
  private fun mergeProviderPages(
    movies: ProviderCatalogPage,
    series: ProviderCatalogPage
  ): ProviderCatalogPage {
    if (!movies.hasConfirmedBindings && !series.hasConfirmedBindings) {
      return ProviderCatalogPage(mediaType = null)
    }
    val mergedProvenance = LinkedHashMap<String, MutableList<CatalogProvenance>>()
    (movies.provenance + series.provenance).forEach { (id, origins) ->
      val bucket = mergedProvenance.getOrPut(id) { mutableListOf() }
      origins.forEach { if (it !in bucket) bucket += it }
    }
    val items = deduplicateItems(movies.items + series.items)
    mergedProvenance.keys.retainAll(items.mapTo(mutableSetOf()) { it.id })
    return ProviderCatalogPage(
      mediaType = null,
      items = items,
      provenance = mergedProvenance.mapValues { (_, v) -> v.toList() },
      hasConfirmedBindings = true,
      targets = (movies.targets + series.targets).distinctBy { it.targetId }
    )
  }

  @Deprecated(
    "Le euristiche per keyword non associano più i cataloghi: usa i binding dichiarativi",
    ReplaceWith("findConfirmedTargetsForProvider(provider)")
  )
  fun getProviderKeywords(provider: StreamingProvider): List<String> = when (provider.id.lowercase().trim()) {
    "netflix" -> listOf("netflix")
    "hbo" -> listOf("hbo", "max")
    "disney" -> listOf("disney", "disney+")
    "prime" -> listOf("prime", "amazon")
    "apple" -> listOf("apple", "apple tv", "appletv")
    "paramount" -> listOf("paramount", "paramount+")
    "crunchyroll" -> listOf("crunchyroll")
    else -> listOf(provider.id.lowercase().trim(), provider.name.lowercase().trim())
  }

  /**
   * Individua i target di catalogo Stremio compatibili con uno specifico [StreamingProvider].
   * Cerca match diretti per catalogId, title, addonName o opzioni del parametro extra `genre`.
   */
  @Deprecated(
    "Match per keyword: non distingue addon, tipi né origini. Usa findConfirmedTargetsForProvider()",
    ReplaceWith("findConfirmedTargetsForProvider(provider, mediaType)")
  )
  fun findTargetsForProvider(provider: StreamingProvider): List<Pair<StremioCatalogTarget, Map<String, String>>> {
    val keywords = getProviderKeywords(provider)
    val allTargets = getAvailableTargets()
    val matched = mutableListOf<Pair<StremioCatalogTarget, Map<String, String>>>()

    for (target in allTargets) {
      val directMatch = keywords.any { kw ->
        target.catalogId.contains(kw, ignoreCase = true) ||
          target.title.contains(kw, ignoreCase = true) ||
          target.addonName.contains(kw, ignoreCase = true) ||
          target.addonId.contains(kw, ignoreCase = true)
      }
      if (directMatch) {
        matched.add(target to emptyMap())
        continue
      }

      if (target.supportsGenre) {
        val matchedGenre = target.genreOptions.firstOrNull { opt ->
          keywords.any { kw -> opt.contains(kw, ignoreCase = true) }
        }
        if (matchedGenre != null) {
          matched.add(target to mapOf(EXTRA_GENRE to matchedGenre))
        }
      }
    }
    return matched
  }

  /**
   * Carica i contenuti dei cataloghi Stremio **confermati** per il [StreamingProvider] richiesto,
   * separando Film e Serie. Se non esiste alcun binding confermato restituisce una lista
   * vuota: in quel caso la UI mantiene il catalogo nativo TMDB.
   */
  suspend fun loadProviderCatalog(provider: StreamingProvider): List<MediaItem> = coroutineScope {
    loadProviderCatalogForProvider(provider).items
  }

  /**
   * Overload legacy con offset esterno: deprecato perché un `skip` condiviso tra cataloghi
   * diversi produce offset incoerenti. Usare [paginateProviderCatalog] con [MediaType].
   */
  @Deprecated(
    "L'offset skip è per catalogo, non per provider: usa paginateProviderCatalog(provider, mediaType)",
    ReplaceWith("paginateProviderCatalog(provider, mediaType)")
  )
  suspend fun paginateProviderCatalogLegacy(provider: StreamingProvider, skip: Int): List<MediaItem> = coroutineScope {
    val targets = findConfirmedTargetsForProvider(provider).filter { it.supportsSkip }
    if (targets.isEmpty()) return@coroutineScope emptyList()

    val fetched = targets.map { resolved ->
      async {
        try {
          val res = StremioCatalogEngine.paginateCatalog(
            target = resolved.target,
            skip = skip,
            baseExtra = ProviderCatalogPlanner.extraFor(resolved)
          )
          if (res.isSuccess) resolved to res.items else null
        } catch (e: Exception) {
          null
        }
      }
    }.awaitAll().filterNotNull()

    collectProviderPage(null, provider.name, fetched).items
  }

  /**
   * Pagina i cataloghi Stremio di tipo Film tramite offset `skip`.
   */
  suspend fun paginateMovies(skip: Int): List<MediaItem> = coroutineScope {
    val targets = getAvailableTargets(MediaType.FILM).filter { it.supportsSkip }
    if (targets.isEmpty()) return@coroutineScope emptyList()

    val results = targets.map { target ->
      async {
        try {
          val res = StremioCatalogEngine.paginateCatalog(target, skip)
          if (res.isSuccess && res.items.isNotEmpty()) {
            StremioCatalogMapper.toMediaItemList(res.items, target.addonName)
          } else {
            emptyList()
          }
        } catch (e: Exception) {
          emptyList()
        }
      }
    }.awaitAll().flatten()

    deduplicateItems(results)
  }

  /**
   * Pagina i cataloghi Stremio di tipo Serie TV tramite offset `skip`.
   */
  suspend fun paginateSeries(skip: Int): List<MediaItem> = coroutineScope {
    val targets = getAvailableTargets(MediaType.SERIE_TV).filter { it.supportsSkip }
    if (targets.isEmpty()) return@coroutineScope emptyList()

    val results = targets.map { target ->
      async {
        try {
          val res = StremioCatalogEngine.paginateCatalog(target, skip)
          if (res.isSuccess && res.items.isNotEmpty()) {
            StremioCatalogMapper.toMediaItemList(res.items, target.addonName)
          } else {
            emptyList()
          }
        } catch (e: Exception) {
          emptyList()
        }
      }
    }.awaitAll().flatten()

    deduplicateItems(results)
  }

  /** Svuota la cache in memoria. */
  fun clearCache() {
    StremioCatalogEngine.clearCache()
  }
}
