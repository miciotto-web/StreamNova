package com.example.data.repository

import android.util.Log
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.stremio.CinemetaAddon
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.StremioCatalogEngine
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.StremioCatalogMapper
import com.example.data.stremio.StremioCatalogResult
import com.example.data.stremio.StremioCatalogTarget
import com.example.data.stremio.StremioMetaItem
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES
import com.example.data.stremio.EXTRA_GENRE
import com.example.data.stremio.StremioLiveChannelClassifier
import com.example.data.stremio.EXTRA_SEARCH
import com.example.data.stremio.provider.CatalogEntryResult
import com.example.data.stremio.provider.CatalogKey
import com.example.data.stremio.provider.CatalogProvenance
import com.example.data.stremio.provider.ProviderCatalogAssembler
import com.example.data.stremio.provider.ProviderCatalogPaging
import com.example.data.stremio.provider.ProviderCatalogPlan
import com.example.data.stremio.provider.ProviderCatalogPlanner
import com.example.data.stremio.provider.ProviderCatalogResolver
import com.example.data.stremio.provider.ProviderCatalogResolvers
import com.example.data.stremio.provider.ProviderCatalogSourcePolicy
import com.example.data.stremio.provider.ResolvedProviderTarget
import com.example.data.stremio.provider.asAliasProvider
import com.example.ui.components.ProviderConstants
import com.example.ui.components.StreamingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
 * - [NONE]     nessun binding confermato: si usa il catalogo nativo di default
 *              (Cinemeta).
 * - [PARTIAL]  binding presenti ma limitati (es. soli cataloghi Top10):
 *              Stremio resta la fonte primaria e il default completa la lista.
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
 *
 * SORGENTE PREDEFINITA: **Cinemeta**. Le righe principali
 * (`popularX`, `top10X`, `trendingX`) arrivano dai cataloghi di default dell'addon
 * di sistema Cinemeta (Popolari / Più votati / Nuovi); i feed nativi TMDB non
 * vengono più usati come base della Home.
 *
 * Gli addon utente (Xperience, Easy Catalog, ...) compaiono come [extraSections] e
 * sostituiscono le righe stock Cinemeta SOLO per il tipo che riescono realmente a
 * popolare ([hasCustomCatalogMovies]/[hasCustomCatalogSeries]): un addon solo-film
 * non sopprime le righe Serie TV e viceversa.
 *
 * `allMovies`/`allSeries` alimentano le schermate categoria: contenuti Cinemeta
 * quando disponibili ([moviesFromDefaultCatalog]/[seriesFromDefaultCatalog]), altrimenti
 * la cache locale come fallback offline.
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
  /** Cataloghi addon personalizzati attivi con contenuti FILM: righe Cinemeta film escluse. */
  val hasCustomCatalogMovies: Boolean = false,
  /** Cataloghi addon personalizzati attivi con contenuti SERIE TV: righe Cinemeta serie escluse. */
  val hasCustomCatalogSeries: Boolean = false,
  /** `allMovies` proviene dal catalogo di default (Cinemeta): paginazione via Stremio. */
  val moviesFromDefaultCatalog: Boolean = false,
  /** `allSeries` proviene dal catalogo di default (Cinemeta): paginazione via Stremio. */
  val seriesFromDefaultCatalog: Boolean = false,
  val allMovies: List<MediaItem> = emptyList(),
  val allSeries: List<MediaItem> = emptyList()
) {
  /** true se almeno un tipo ha cataloghi di addon personalizzati attivi. */
  val hasCustomCatalogAddons: Boolean get() = hasCustomCatalogMovies || hasCustomCatalogSeries

  /**
   * I film della schermata categoria arrivano da cataloghi Stremio: o di un addon
   * personalizzato (Xperience, Easy Catalog, ...) o dal catalogo di default Cinemeta. In entrambi i casi la
   * paginazione deve usare il percorso Stremio, non i feed TMDB.
   */
  val isMoviesFromStremio: Boolean get() = hasCustomCatalogMovies || moviesFromDefaultCatalog

  /** Come [isMoviesFromStremio], per le Serie TV. */
  val isSeriesFromStremio: Boolean get() = hasCustomCatalogSeries || seriesFromDefaultCatalog
}

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
   * Limite RIGIDO della riga "Top 10" della Home: il catalogo di default
   * (Cinemeta) restituisce l'intera pagina (~49-50 elementi), quindi le righe
   * Top 10 vanno troncate ai primi 10 titoli in classifica.
   */
  const val HOME_TOP_10_SIZE = 10

  /**
   * Paginazione per singolo catalogo: ogni target mantiene il proprio offset `skip`.
   * L'offset è un concetto del singolo endpoint `catalog/{type}/{id}`, non del provider.
   */
  private val providerPaging = ProviderCatalogPaging()

  /**
   * Ordina le risoluzioni concorrenti della Home: una risoluzione iniziata prima
   * non può sovrascrivere una più recente già applicata. Chi arriva in ritardo
   * riceve l'ultimo risultato applicato, così l'assegnazione del chiamante resta
   * idempotente senza modificare il modello di stato esistente.
   */
  internal class HomeResolutionGate {
    private var lastRequestId = 0L
    private var lastAppliedRequestId = 0L
    private var lastAppliedResult: HomeCatalogs? = null

    @Synchronized
    fun nextRequest(): Long = ++lastRequestId

    @Synchronized
    fun complete(requestId: Long, result: HomeCatalogs): HomeCatalogs {
      val applied = lastAppliedResult
      if (applied != null && requestId < lastAppliedRequestId) return applied
      lastAppliedRequestId = requestId
      lastAppliedResult = result
      return result
    }
  }

  private val homeResolutionGate = HomeResolutionGate()

  /** Separatore dei segmenti alfanumerici usato per confrontare keyword e manifest. */
  private val NON_ALNUM = Regex("[^a-z0-9]+")

  init {
    StremioCatalogMapper.posterResolver = ::cachedPosterFor
  }

  /**
   * Locandina recuperabile dalla cache TMDB locale per id Stremio (`tmdb:` / numerico /
   * `tt...`): se il catalogo non ha inviato un poster valido, il titolo già noto a TMDB
   * non deve cadere sull'placeholder generico.
   */
  private fun cachedPosterFor(rawId: String, isTv: Boolean): String? = runCatching {
    val id = rawId.trim()
    if (id.isEmpty()) return@runCatching null
    val items = MediaRepository.mediaList.value
    val match = when {
      id.startsWith("tmdb:", ignoreCase = true) -> {
        val tmdbId = id.substringAfter(":").trim().toIntOrNull() ?: return@runCatching null
        items.firstOrNull { it.tmdbId == tmdbId && (it.type == MediaType.SERIE_TV) == isTv }
          ?: items.firstOrNull { it.tmdbId == tmdbId }
      }
      id.startsWith("tt", ignoreCase = true) -> {
        val imdbId = id.substringBefore(":")
        items.firstOrNull { it.effectiveImdbId.equals(imdbId, ignoreCase = true) }
      }
      else -> {
        val tmdbId = id.toIntOrNull() ?: return@runCatching null
        items.firstOrNull { it.tmdbId == tmdbId && (it.type == MediaType.SERIE_TV) == isTv }
          ?: items.firstOrNull { it.tmdbId == tmdbId }
      }
    }
    match?.posterUrl?.takeIf { it.isNotBlank() }
  }.getOrNull()

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
    TYPE_SERIES, "tv", "show", "tvshow" -> MediaType.SERIE_TV
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
   * Risolve tutti i target di catalogo disponibili dagli addon Stremio attivi.
   * Non effettua chiamate di rete: legge i manifest già installati e validati.
   */
  fun getAvailableTargets(mediaType: MediaType? = null): List<StremioCatalogTarget> {
    val stremioType = toStremioType(mediaType)
    return StremioCatalogEngine.getAvailableCatalogs(stremioType)
  }

  /** Catalogo (popolato da [StremioCatalogItemIndex]) che esponeva un canale. */
  private val channelCatalogIndex = ConcurrentHashMap<String, String>()

  /**
   * Registra, per ogni elemento non cinematografico (canale LIVE) restituito da un
   * catalogo, il catalogo che lo ha prodotto. È l'unico modo per conservare il
   * tipo ORIGINALE (`tv`) nelle richieste `meta`/`stream`: la card Home porta solo
   * l'id, non il catalogo.
   */
  internal fun rememberChannelItems(
    addonId: String,
    catalogId: String,
    items: List<StremioMetaItem>
  ) {
    items.forEach { item ->
      val itemId = item.id?.trim().orEmpty()
      if (itemId.isEmpty()) return@forEach
      if (!StremioLiveChannelClassifier.isChannelStyleId(itemId)) return@forEach
      channelCatalogIndex["${addonId.trim()}|${itemId.lowercase()}"] = catalogId.trim()
    }
  }

  /**
   * Catalogo dell'addon [addonId] che espone il contenuto [mediaId], oppure null.
   * Accetta sia l'id Stremio nativo sia la chiave di navigazione del canale.
   */
  fun channelCatalogIdFor(addonId: String, mediaId: String?): String? {
    val id = StremioLiveChannelClassifier.parseChannelKey(mediaId)?.second
      ?: mediaId?.trim().orEmpty()
    if (id.isEmpty()) return null
    return channelCatalogIndex["${addonId.trim()}|${id.lowercase()}"]
  }

  internal fun clearChannelItemIndex() {
    channelCatalogIndex.clear()
  }

  /**
   * Target dei cataloghi di DEFAULT Cinemeta per [mediaType], senza rete.
   *
   * Sono tenuti separati da [getAvailableTargets] (che legge solo gli addon utente)
   * per non contaminare i percorsi generici con la sorgente di default: alimentano
   * la Home e la sua paginazione. Categorie e ricerca usano i rispettivi helper
   * dedicati ([defaultCinemetaGenreTargets] / [defaultCinemetaSearchTargets]).
   * Sono esclusi i cataloghi con extra obbligatori (es. "Nuovi" richiede `genre`)
   * perché la paginazione passa solo `skip`.
   */
  internal fun defaultCinemetaTargets(mediaType: MediaType): List<StremioCatalogTarget> =
    CinemetaAddon.homeCatalogPlans(mediaType)
      .map { it.catalog }
      .filter { it.canLoadWithoutArgs() }
      .map { catalog ->
        StremioCatalogTarget(
          addonId = CinemetaAddon.MANIFEST_ID,
          addonName = CinemetaAddon.DISPLAY_NAME,
          baseUrl = CinemetaAddon.BASE_URL,
          catalog = catalog
        )
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
    buildCatalogSection(target, result)
  }

  /**
   * Converte il risultato grezzo del Catalog Engine in una [CatalogSection].
   *
   * Un catalogo dichiarato dalla registry o dall'utente appartiene a un provider:
   * l'item riporta il BRAND ("Netflix", "Disney+", "HBO Max", ...) invece del nome
   * dell'addon, così i filtri per brand della schermata categoria lo trovano.
   * Senza binding resta il comportamento precedente: provider = nome dell'addon.
   *
   * Usato sia dal percorso Home ([loadCatalogSection]) sia dal percorso categorie
   * della sezione Ricerca ([loadCategoryCatalogs]) per non duplicare la logica di
   * conversione.
   */
  /** Rileva l'eventuale brand di streaming platform a partire dal catalogId, dal titolo o dal nome del catalogo. */
  internal fun detectBrandFromTarget(target: StremioCatalogTarget): String? {
    val searchStr = "${target.catalogId} ${target.title} ${target.catalog.name.orEmpty()}".lowercase()
    return when {
      searchStr.contains("netflix") -> "Netflix"
      searchStr.contains("disney") -> "Disney+"
      searchStr.contains("hbo") || searchStr.contains("max") -> "HBO Max"
      searchStr.contains("apple") -> "Apple TV+"
      searchStr.contains("prime") || searchStr.contains("amazon") -> "Prime Video"
      else -> null
    }
  }

  private fun buildCatalogSection(
    target: StremioCatalogTarget,
    result: StremioCatalogResult
  ): CatalogSection {
    val binding = resolver().resolve(
      CatalogKey(target.addonId, target.type, target.catalogId)
    )
    val brandName = binding?.providerId?.let { providerId ->
      ProviderConstants.ALL.firstOrNull { it.id == providerId }?.name
    } ?: detectBrandFromTarget(target)

    val isTop10 = target.catalogId.contains("top10", ignoreCase = true) ||
      target.catalogId.contains("top 10", ignoreCase = true) ||
      target.catalogId.contains("top100", ignoreCase = true) ||
      target.title.contains("top 10", ignoreCase = true) ||
      target.title.contains("top 100", ignoreCase = true) ||
      (target.catalog.name?.contains("top 10", ignoreCase = true) == true)

    val extraGenres = mutableListOf<String>()
    if (isTop10) extraGenres += "Top 10"
    if (brandName != null) extraGenres += brandName

    // Canali live: la card porta solo l'id, quindi il catalogo d'origine viene
    // memorizzato qui per conservare il tipo ORIGINALE (`tv`) nelle richieste stream.
    rememberChannelItems(target.addonId, target.catalogId, result.items)

    val mediaItems = StremioCatalogMapper.toMediaItemList(
      result.items,
      brandName ?: target.addonName,
      toMediaType(target.type),
      extraGenres = extraGenres
    )

    return CatalogSection(
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

  // ── Categorie della sezione Ricerca ───────────────────────────────────────────

  /**
   * Richiesta di catalogo derivata dai manifest per una categoria della sezione Ricerca.
   *
   * @param target catalogo REALMENTE dichiarato nel manifest di un addon attivo
   *        (nessun id è codificato nell'app).
   * @param extra extra da inviare: solo opzioni `genre` dichiarate dal catalogo.
   */
  data class CategoryCatalogRequest(
    val target: StremioCatalogTarget,
    val extra: Map<String, String> = emptyMap()
  ) {
    /** true se la richiesta invia realmente il filtro per genere all'addon. */
    val isGenreFiltered: Boolean
      get() = extra.keys.any { it.equals(EXTRA_GENRE, ignoreCase = true) }
  }

  /**
   * Sezione caricata per una categoria, con gli extra usati nella chiamata:
   * servono per la paginazione successiva (`skip` + stessi extra).
   */
  data class CategoryCatalogEntry(
    val section: CatalogSection,
    val extra: Map<String, String> = emptyMap()
  )

  /**
   * Esito del caricamento dei cataloghi di una categoria della sezione Ricerca.
   *
   * @param entries sezioni realmente restituite dall'addon (anche vuote).
   * @param items contenuti deduplicati di tutte le sezioni, nell'ordine delle sezioni.
   * @param matchedTargets numero di cataloghi dichiarati dal manifest e selezionati per la categoria.
   * @param errors errore per singolo catalogo che non è stato caricato (vuoto se nessuno fallisce).
   */
  data class CategoryCatalogLoad(
    val entries: List<CategoryCatalogEntry> = emptyList(),
    val items: List<MediaItem> = emptyList(),
    val matchedTargets: Int = 0,
    /** Cataloghi film matchati dai keyword della categoria. */
    val matchedMovieTargets: Int = 0,
    /** Cataloghi serie matchati dai keyword della categoria. */
    val matchedSeriesTargets: Int = 0,
    /** Cataloghi FILM dichiarati dai manifest degli addon attivi (a prescindere dalla categoria). */
    val availableMovieCatalogs: Int = 0,
    /** Cataloghi SERIE dichiarati dai manifest degli addon attivi (a prescindere dalla categoria). */
    val availableSeriesCatalogs: Int = 0,
    /**
     * Film interrogati e falliti (errore/tipo non supportato): il tipo NON è "vuoto",
     * semplicemente non ha prodotto dati validi ([matchedMovieTargets] - [failedMovieTargets]
     * cataloghi riusciti).
     */
    val failedMovieTargets: Int = 0,
    /** Serie interrogate e fallite (stesso criterio di [failedMovieTargets]). */
    val failedSeriesTargets: Int = 0,
    /** Numero di richieste che hanno realmente inviato il filtro `genre`. */
    val genreFilteredRequests: Int = 0,
    val errors: List<String> = emptyList()
  ) {
    /**
     * Stato del tipo [mediaType] nella categoria: fonte assente, incompatibile,
     * interrogata-vuota, fallita oppure riuscita. Nessun valore è inferito da un
     * ALTRO tipo, così un errore dei film non viene mai attribuito alle serie (e
     * viceversa).
     */
    fun outcomeFor(mediaType: MediaType): CategoryTypeOutcome {
      val matched =
        if (mediaType == MediaType.SERIE_TV) matchedSeriesTargets else matchedMovieTargets
      val failed =
        if (mediaType == MediaType.SERIE_TV) failedSeriesTargets else failedMovieTargets
      val available =
        if (mediaType == MediaType.SERIE_TV) availableSeriesCatalogs else availableMovieCatalogs
      return when {
        matched == 0 ->
          if (available > 0) CategoryTypeOutcome.NOT_SUPPORTED else CategoryTypeOutcome.NO_CATALOG
        failed >= matched -> CategoryTypeOutcome.FAILED
        entries.any { it.section.mediaType == mediaType && it.section.items.isNotEmpty() } ->
          CategoryTypeOutcome.OK
        else -> CategoryTypeOutcome.EMPTY
      }
    }
  }

  /**
   * Stato di UN tipo di contenuto nel caricamento di una categoria della sezione
   * Ricerca. Distingue le quattro situazioni che la UI non deve mai confondere:
   * catalogo assente, incompatibile, interrogato-vuoto, fallito.
   */
  enum class CategoryTypeOutcome {
    /** Nessun catalogo di questo tipo nel manifest → manca la fonte. */
    NO_CATALOG,

    /** Cataloghi dichiarati ma nessuno applica il filtro per genere → incompatibile. */
    NOT_SUPPORTED,

    /** Almeno un catalogo è stato caricato con successo ma senza contenuti. */
    EMPTY,

    /** Tutti i cataloghi selezionati per il tipo sono falliti (rete/HTTP/JSON). */
    FAILED,

    /** Almeno un catalogo ha restituito contenuti. */
    OK
  }

  /**
   * Selezione dei target di catalogo per una categoria della sezione Ricerca.
   *
   * Regole (nessun id di catalogo inventato):
   *  1. si leggono SOLO i cataloghi dichiarati nei manifest degli addon attivi;
   *  2. i keyword della categoria (etichette localizzate + nomi canonici TMDB del
   *     genere, calcolati dal ViewModel) devono corrispondere a un segmento dell'
   *     `id` del catalogo, a una parola intera del titolo dichiarato oppure a
   *     un'opzione realmente dichiarata per l'extra `genre`;
   *  3. se il match avviene su un'opzione `genre`, quella opzione (esatta) viene
   *     inviata come extra: mai un genere non dichiarato;
   *  4. i cataloghi con extra obbligatori non soddisfatti (es. i cataloghi di sola
   *     ricerca `search`) vengono esclusi;
   *  5. se nessun catalogo corrisponde → lista vuota: la categoria dichiara il
   *     fallback invece di fingere un caricamento addon riuscito.
   *
   * Il filtro per genere viene inviato anche quando il catalogo è COMPATIBILE ma
   * non elenca opzioni statiche per `genre`: vedi [resolveGenreExtra].
   */
  fun findCategoryRequests(keywords: List<String>): List<CategoryCatalogRequest> {
    val terms = keywords
      .mapNotNull { keyword -> termSegments(keyword).takeIf { it.isNotEmpty() } }
      .distinct()
    if (terms.isEmpty()) return emptyList()

    // 1. Priorità agli addon utente: se almeno un catalogo dichiarato copre la
    //    categoria, la fonte resta l'addon (nessuna sovrapposizione con Cinemeta).
    val userRequests = matchCategoryRequests(terms, getAvailableTargets())
    if (userRequests.isNotEmpty()) return userRequests

    // 2. Gli addon utente DICHIARANO cataloghi ma nessuno copre il genere: la categoria
    //    NON deve rimandare a TMDB fingendo che l'addon la copra. I cataloghi esistenti
    //    sono la risposta dell'addon (lo stato della categoria lo dichiara).
    val userTargets = getAvailableTargets().filter { it.isMovie || it.isSeries }
    if (userTargets.isNotEmpty()) return emptyList()

    // 3. Nessun addon utente installato: Cinemeta, addon di sistema, è la fonte di
    //    default e copre il genere sui cataloghi movie/series tramite `genre={NomeGenere}`.
    return matchCategoryRequests(terms, defaultCinemetaGenreTargets())
  }

  /**
   * true se il catalogo [target] può restituire la lista di uno dei generi [keywords]
   * senza inventare dati.
   *
   * È la STESSA regola usata da [matchCategoryRequests], estratta qui per rispondere
   * alla domanda "esiste un catalogo film/serie configurato che copre questo genere?".
   * La risposta dipende dal TIPO del catalogo: un catalogo film non copre mai la
   * ricerca delle serie, e viceversa. Serve a decidere se il fallback TMDB è
   * necessario, senza dedurlo dal comportamento di un singolo addon né da un errore.
   */
  internal fun catalogCoversGenre(target: StremioCatalogTarget, keywords: List<String>): Boolean {
    // Regola UNICA con la ricerca reale: si riusa il matcher delle categorie su questo
    // solo target, così la decisione "esiste un catalogo che copre il genere?" non può
    // divergere da ciò che la categoria interroga davvero.
    val terms = keywords
      .mapNotNull { keyword -> termSegments(keyword).takeIf { it.isNotEmpty() } }
      .distinct()
    if (terms.isEmpty()) return false
    return matchCategoryRequests(terms, listOf(target)).isNotEmpty()
  }

  /**
   * true se [targets] (i cataloghi realmente configurati: addon installati e attivi,
   * più i cataloghi di sistema) contengono almeno un catalogo del tipo [mediaType] che
   * copre il genere [keywords].
   *
   * Un `false` significa "nessun catalogo configurato per questo tipo applica il
   * filtro per genere": è la sola condizione che autorizza il fallback TMDB.
   */
  internal fun hasConfiguredCatalogCoveringGenre(
    keywords: List<String>,
    mediaType: MediaType,
    targets: List<StremioCatalogTarget> = getAvailableTargets()
  ): Boolean = targets.any { target ->
    val targetType = when {
      target.isMovie -> MediaType.FILM
      target.isSeries -> MediaType.SERIE_TV
      else -> null
    }
    targetType == mediaType && catalogCoversGenre(target, keywords)
  }

  /**
   * Come [hasConfiguredCatalogCoveringGenre], ma sui SOLI cataloghi degli addon
   * dell'utente (installati e attivi), senza i cataloghi di sistema come Cinemeta.
   *
   * Serve a decidere il fallback TMDB: se nessun addon dell'utente copre la
   * combinazione tipo+genere, la fonte principale della categoria (Cinemeta, che
   * dichiara `genre` per entrambi i tipi) non produce contenuti coerenti con il
   * genere richiesto e la categoria deve procedere con TMDB.
   */
  internal fun hasUserCatalogCoveringGenre(
    keywords: List<String>,
    mediaType: MediaType
  ): Boolean = hasConfiguredCatalogCoveringGenre(
    keywords = keywords,
    mediaType = mediaType,
    targets = getAvailableTargets().filterNot { isSystemAddon(it.addonId) }
  )

  /**
   * Associa i [terms] della categoria ai [targets] disponibili applicando le regole
   * dichiarative (match su id/titolo, opzione `genre` realmente dichiarata oppure
   * filtro `genre` supportato senza elenco statico di opzioni).
   */
  private fun matchCategoryRequests(
    terms: List<List<String>>,
    targets: List<StremioCatalogTarget>
  ): List<CategoryCatalogRequest> {
    return targets.mapNotNull { target ->
      // Solo i tipi che l'app sa mostrare: altrove `toMediaType` tratterebbe un
      // tipo esotico come film.
      if (!target.isMovie && !target.isSeries) return@mapNotNull null

      val idSegments = termSegments(target.catalogId)
      val titleSegments = termSegments(target.title)
      val matchesById = terms.any { keyword -> termMatches(keyword, idSegments) }
      val matchesByTitle = terms.any { keyword -> termMatches(keyword, titleSegments) }

      // Extra `genre` realmente applicabile (esatta opzione dichiarata oppure valore
      // accettato dal catalogo che non elenca opzioni statiche).
      val genreExtra = resolveGenreExtra(target, terms)

      // Un catalogo è pertinente se:
      //  - il filtro per genere è applicabile (la lista restituita è del genere);
      //  - oppure è un catalogo DEL GENERE identificato dall'id ("genre_*") e
      //    supporta `genre` (lista curata dall'addon per quel genere);
      //  - oppure è un catalogo tematico riconosciuto sia dall'id sia dal titolo.
      // Un catalogo riconosciuto SOLO dal titolo, senza filtro `genre`, non è mai la
      // risposta: mostrerebbe la sua lista generica come se fosse del genere scelto.
      val isRelevant = genreExtra != null ||
        (matchesById && target.supportsGenre) ||
        (matchesById && matchesByTitle)
      if (!isRelevant) return@mapNotNull null

      val extra = genreExtra.orEmpty()
      val provided = extra.keys.map { it.lowercase() }
      if (target.catalog.requiredExtraNames().any { it !in provided }) return@mapNotNull null

      CategoryCatalogRequest(target, extra)
    }
  }

  /**
   * Extra `genre` realmente applicabile al catalogo per i [terms] della categoria.
   *
   * @return `mapOf("genre" to valore)` oppure `null` se il catalogo non supporta il
   *         filtro o non ha un genere corrispondente.
   */
  private fun resolveGenreExtra(
    target: StremioCatalogTarget,
    terms: List<List<String>>
  ): Map<String, String>? {
    if (!target.supportsGenre) return null
    val options = target.genreOptions
    if (options.isNotEmpty()) {
      // Solo un'opzione DICHIARATA può essere inviata (per l'app l'ordine delle
      // keyword della categoria conta: etichette localizzate, poi nomi TMDB).
      val option = terms.firstNotNullOfOrNull { keyword ->
        options.firstOrNull { option -> termMatches(keyword, termSegments(option)) }
      } ?: return null
      return mapOf(EXTRA_GENRE to option)
    }
    // Nessuna opzione dichiarata: il valore inviato è il nome canonico della
    // categoria (alias dichiarato dall'app), mai un valore inventato. I termini sono
    // già normalizzati per il confronto, quindi il valore viene ricostruito con la
    // forma capitalized ("horror" → "Horror", "science fiction" → "Science Fiction").
    val keyword = terms.firstOrNull() ?: return null
    return mapOf(EXTRA_GENRE to keyword.joinToString(" ") { part ->
      part.replaceFirstChar { it.uppercaseChar() }
    })
  }

  /** Costruisce un target Stremio per un catalogo dell'addon di sistema Cinemeta. */
  private fun cinemetaTarget(catalog: StremioCatalogDefinition): StremioCatalogTarget =
    StremioCatalogTarget(
      addonId = CinemetaAddon.MANIFEST_ID,
      addonName = CinemetaAddon.DISPLAY_NAME,
      baseUrl = CinemetaAddon.BASE_URL,
      catalog = catalog
    )

  /**
   * Target Cinemeta di DEFAULT per le CATEGORIE della sezione Ricerca: il catalogo
   * Popolari (`top`), che dichiara il filtro `genre`, per Film e Serie TV.
   * Nessuna rete: deriva dal manifest statico [CinemetaAddon].
   */
  internal fun defaultCinemetaGenreTargets(
    mediaType: MediaType? = null
  ): List<StremioCatalogTarget> =
    CinemetaAddon.genreCatalogs(mediaType).map { cinemetaTarget(it) }

  /**
   * Target Cinemeta di DEFAULT per la RICERCA testuale globale (extra `search`)
   * quando nessun addon utente copre la query. Nessuna rete.
   */
  internal fun defaultCinemetaSearchTargets(
    mediaType: MediaType? = null
  ): List<StremioCatalogTarget> =
    CinemetaAddon.searchCatalogs(mediaType).map { cinemetaTarget(it) }

  /** Split di un testo nei segmenti alfanumerici minuscoli ("Sci-Fi" → ["sci", "fi"]). */
  private fun termSegments(value: String): List<String> =
    value.lowercase().split(NON_ALNUM).filter { it.isNotBlank() }

  /**
   * true se [keyword] (segmenti della chiave di ricerca) corrisponde a [candidates]
   * (segmenti del catalogo): corrispondenza sull'intera parola compattata
   * ("sci-fi" ≈ "scifi") oppure presenza di TUTTI i segmenti della keyword.
   */
  private fun termMatches(keyword: List<String>, candidates: List<String>): Boolean {
    if (keyword.isEmpty() || candidates.isEmpty()) return false
    return candidates.contains(keyword.joinToString("")) ||
      keyword.all { candidates.contains(it) }
  }

  /**
   * Carica i cataloghi di una categoria della sezione Ricerca usando lo stesso
   * percorso della Home: [StremioCatalogEngine.fetchCatalog] +
   * [buildCatalogSection].
   *
   * Gli errori sono isolati per singolo catalogo e restituiti in
   * [CategoryCatalogLoad.errors] così lo stato della UI può dichiarare il fallback
   * invece di presentare un caricamento riuscito.
   */
  suspend fun loadCategoryCatalogs(keywords: List<String>): CategoryCatalogLoad = coroutineScope {
    val requests = findCategoryRequests(keywords)
    // Cataloghi dichiarati dai manifest attivi, per distinguere "nessun catalogo
    // di questo tipo esiste" da "esiste ma non applica il filtro per genere".
    val availableTargets = getAvailableTargets().filter { it.isMovie || it.isSeries }
    val availableMovie = availableTargets.count { it.isMovie }
    val availableSeries = availableTargets.count { it.isSeries }
    if (requests.isEmpty()) {
      return@coroutineScope CategoryCatalogLoad(
        matchedTargets = 0,
        availableMovieCatalogs = availableMovie,
        availableSeriesCatalogs = availableSeries
      )
    }

    val outcomes = requests.map { request ->
      async {
        try {
          val result = StremioCatalogEngine.fetchCatalog(request.target, request.extra)
          if (result.isSuccess) {
            buildCatalogSection(request.target, result) to request.extra
          } else {
            null to (result.errorMessage ?: "errore sconosciuto")
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          null to (e.message ?: e.javaClass.simpleName)
        }
      }
    }.awaitAll()

    val entries = mutableListOf<CategoryCatalogEntry>()
    val errors = mutableListOf<String>()
    var failedMovies = 0
    var failedSeries = 0
    outcomes.forEachIndexed { index, outcome ->
      val (section, errorMessage) = outcome
      if (section != null) {
        entries += CategoryCatalogEntry(section, requests[index].extra)
      } else {
        val target = requests[index].target
        if (target.isMovie) failedMovies++ else failedSeries++
        errors += "${target.title} • ${target.addonName}: $errorMessage"
      }
    }

    CategoryCatalogLoad(
      entries = entries,
      items = deduplicateItems(entries.flatMap { it.section.items }),
      matchedTargets = requests.size,
      matchedMovieTargets = requests.count { it.target.isMovie },
      matchedSeriesTargets = requests.count { it.target.isSeries },
      availableMovieCatalogs = availableMovie,
      availableSeriesCatalogs = availableSeries,
      failedMovieTargets = failedMovies,
      failedSeriesTargets = failedSeries,
      genreFilteredRequests = requests.count { it.isGenreFiltered },
      errors = errors
    )
  }

  /**
   * Pagina i cataloghi di una categoria della sezione Ricerca tramite l'extra
   * `skip`, mantenendo gli extra usati al primo caricamento (es. `genre`).
   *
   * Restituisce le voci AGGIORNATE: ogni sezione contiene la pagina già vista più
   * quella nuova, così la chiamata successiva usa un `skip` coerente e non
   * rilegge sempre la stessa pagina.
   */
  suspend fun paginateCategoryCatalogs(
    entries: List<CategoryCatalogEntry>
  ): List<CategoryCatalogEntry> = coroutineScope {
    if (entries.none { it.section.supportsSkip && it.section.items.isNotEmpty() }) {
      return@coroutineScope entries
    }
    // Include anche i cataloghi di sistema Cinemeta: le sezioni servite da Cinemeta
    // quando nessun addon utente copre la categoria devono poter essere paginate.
    val targets = getAvailableTargets() + defaultCinemetaGenreTargets()

    entries.map { entry ->
      async {
        val section = entry.section
        if (!section.supportsSkip || section.items.isEmpty()) return@async entry
        try {
          val target = targets.firstOrNull {
            it.addonId == section.addonId &&
              it.catalogId == section.catalogId &&
              toMediaType(it.type) == section.mediaType
          } ?: return@async entry
          val result = StremioCatalogEngine.paginateCatalog(
            target = target,
            skip = section.items.size,
            baseExtra = entry.extra
          )
          if (!result.isSuccess || result.items.isEmpty()) return@async entry
          val delta = buildCatalogSection(target, result)
          entry.copy(
            section = section.copy(
              items = section.items + delta.items,
              rawItems = section.rawItems + delta.rawItems
            )
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Paginazione categoria fallita per ${section.id}: ${e.message}")
          entry
        }
      }
    }.awaitAll()
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
    // 1. Addon utente (Xperience/Stremio): mantengono la precedenza quando producono risultati.
    val userResults = StremioCatalogEngine.searchCatalogs(query, stremioType)
    val userItems = userResults.flatMap { result ->
      StremioCatalogMapper.toMediaItemList(result.items, result.addonName)
    }.distinctBy { it.id }
    if (userItems.isNotEmpty()) return@withContext userItems

    // 2. Nessun addon utente copre la query: Cinemeta è il provider di ricerca di
    //    default (cataloghi `movie`/`series` con extra `search`).
    searchDefaultCinemeta(query, mediaType)
  }

  /**
   * Ricerca testuale sull'addon di sistema Cinemeta: provider di default quando
   * nessun addon utente copre la richiesta. Isola gli errori per catalogo.
   */
  private suspend fun searchDefaultCinemeta(
    query: String,
    mediaType: MediaType?
  ): List<MediaItem> = coroutineScope {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return@coroutineScope emptyList()
    val targets = defaultCinemetaSearchTargets(mediaType)
    if (targets.isEmpty()) return@coroutineScope emptyList()

    val extra = mapOf(EXTRA_SEARCH to cleanQuery)
    targets.map { target ->
      async {
        try {
          val result = StremioCatalogEngine.fetchCatalog(target, extra)
          if (result.isSuccess) {
            StremioCatalogMapper.toMediaItemList(result.items, target.addonName, toMediaType(target.type))
          } else {
            emptyList()
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Ricerca Cinemeta fallita per \"$cleanQuery\": ${e.message}")
          emptyList()
        }
      }
    }.awaitAll().flatten().distinctBy { it.id }
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
   * Costruisce lo stato iniziale (sincrono) dei cataloghi Home.
   *
   * NON espone più alcun feed nativo TMDB: le righe Popolari / Più votati / Nuovi
   * arrivano esclusivamente dai cataloghi di default (Cinemeta) risolti in modo
   * asincrono da [resolveHomeCatalogs]. Qui restano solo le liste cache
   * `allMovies` / `allSeries`, usate dalle schermate categoria come fallback offline
   * quando la rete (Cinemeta) non è disponibile.
   */
  fun buildDefaultHomeCatalogs(allMedia: List<MediaItem>): HomeCatalogs {
    // Nessun contenuto addon è ancora stato caricato: la sorgente per tipo viene
    // decisa solo da [resolveHomeCatalogs], quando si conosce cosa è caricabile.
    return HomeCatalogs(
      heroItems = emptyList(),
      top10Movies = emptyList(),
      top10Series = emptyList(),
      trendingMovies = emptyList(),
      trendingSeries = emptyList(),
      forYouMovies = emptyList(),
      forYouSeries = emptyList(),
      popularMovies = emptyList(),
      popularSeries = emptyList(),
      extraSections = emptyList(),
      hasCustomCatalogMovies = false,
      hasCustomCatalogSeries = false,
      allMovies = allMedia.filter { it.type == MediaType.FILM },
      allSeries = allMedia.filter { it.type == MediaType.SERIE_TV }
    )
  }

  /**
   * Deduplica una lista di [MediaItem] preservando l'ordine originale, basandosi su id univoco stabile.
   * Quando un elemento compare più volte, preserva il provider specifico (es. Netflix, Disney+)
   * e fonde i generi (es. tag "Top 10").
   */
  fun deduplicateItems(items: List<MediaItem>): List<MediaItem> {
    val seen = mutableMapOf<String, MediaItem>()
    val order = mutableListOf<String>()
    for (item in items) {
      val base = item.id.trim().lowercase()
        .ifBlank { "${item.title.trim().lowercase()}_${item.year}" }
      val key = "${item.type.name.lowercase()}|$base"
      val existing = seen[key]
      if (existing == null) {
        seen[key] = item
        order.add(key)
      } else {
        val isExistingGeneric = existing.provider.isNullOrBlank() || existing.provider.equals("Xperience", ignoreCase = true)
        val isNewSpecific = !item.provider.isNullOrBlank() && !item.provider.equals("Xperience", ignoreCase = true)
        val bestProvider = if (isExistingGeneric && isNewSpecific) item.provider else existing.provider
        val mergedGenres = (existing.genres + item.genres).distinct()
        val mergedTmdbId = existing.tmdbId ?: item.tmdbId
        seen[key] = existing.copy(
          provider = bestProvider,
          genres = mergedGenres,
          tmdbId = mergedTmdbId
        )
      }
    }
    return order.mapNotNull { seen[it] }
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
   * Ordine STABILE e immutabile della lista piatta del provider.
   *
   * I titoli tengono l'ordine dei cataloghi d'origine (piano del manifest); l'unico
   * assestamento ammesso raggruppa i FILM prima delle SERIE senza mai rimescolare
   * l'ordine interno a ogni gruppo (sortedBy è uno sort stabile). In questo modo
   * l'arrivo sfasato delle chiamate di rete (pagine Film e Serie concorrenti) non
   * può rimescolare le posizioni già a schermo: a parità di pagine caricate la lista
   * risultante è identica indipendentemente da quale risposta arrivi per prima.
   */
  fun stabilizeProviderOrder(items: List<MediaItem>): List<MediaItem> =
    items.sortedBy { if (it.type == MediaType.FILM) 0 else 1 }

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
   * Risolve i cataloghi per la schermata Home.
   *
   * SORGENTE PREDEFINITA: **Cinemeta** ([CinemetaAddon]), registrato come addon
   * di sistema. I feed nativi TMDB sono stati rimossi dalla Home:
   * 1. Le righe principali per tipo arrivano dai cataloghi di default Cinemeta:
   *    - Popolari (`top`) → `popularMovies` / `popularSeries`;
   *    - Più votati (`imdbRating`) → `top10Movies` / `top10Series`;
   *    - Nuovi (`year`, con `genre` = anno corrente) → `trendingMovies` / `trendingSeries`.
   * 2. La sorgente è decisa PER TIPO: i cataloghi addon (Xperience, Easy Catalog, ...)
   *    sostituiscono le righe stock Cinemeta solo per il tipo che riescono davvero a
   *    popolare e compaiono come [HomeCatalogs.extraSections]. Se un addon non fornisce
   *    alcun catalogo caricabile per un tipo (manifest senza cataloghi utili, extra
   *    obbligatori, risposta vuota o errore), Cinemeta resta la sorgente di quel tipo.
   *    Un addon solo-film non sopprime quindi le righe Serie TV e viceversa.
   * 3. `allMovies` / `allSeries` alimentano le schermate categoria: contenuti Cinemeta
   *    quando disponibili, altrimenti la cache locale (`tmdbMedia`) come fallback offline.
   * 4. Se Cinemeta non è raggiungibile le righe restano vuote: nessun feed TMDB viene
   *    sostituito silenziosamente.
   * 5. Gli elementi sono deduplicati stabilmente.
   */
  /** true se l'addon Xperience è installato ed abilitato. */
  fun isXperienceEnabled(): Boolean = StremioAddonRepository.isXperienceEnabled()

  /**
   * true se almeno un addon con cataloghi personalizzati è installato ed abilitato.
   * Criterio generalizzato usato da Provider/Categorie: NON decide la sorgente della
   * Home, che valuta separatamente film e serie TV sui cataloghi realmente caricabili
   * (vedi [resolveHomeCatalogs]).
   */
  fun hasCustomCatalogAddons(): Boolean = StremioAddonRepository.hasCustomCatalogAddons()

  suspend fun resolveHomeCatalogs(tmdbMedia: List<MediaItem>): HomeCatalogs =
    resolveHomeCatalogs(tmdbMedia) { mediaTypes -> loadDefaultCinemetaSectionsSafely(mediaTypes) }

  /**
   * Implementazione con loader Cinemeta iniettabile: i test possono verificare la
   * decisione di sorgente per tipo senza dipendere dalla rete.
   */
  internal suspend fun resolveHomeCatalogs(
    tmdbMedia: List<MediaItem>,
    defaultSectionsLoader: suspend (List<MediaType>) -> List<CatalogSection>
  ): HomeCatalogs = withContext(Dispatchers.IO) {
    val requestId = homeResolutionGate.nextRequest()

    // Il caricamento addon viene eseguito PRIMA della decisione: un catalogo è
    // "utilizzabile" solo se ha realmente restituito contenuti per il tipo.
    val userSections = loadHomeSectionsSafely(MediaType.FILM) +
      loadHomeSectionsSafely(MediaType.SERIE_TV)

    // Cinemeta resta attivo solo per i tipi che nessun addon riesce a popolare.
    val defaultMediaTypes = defaultCinemetaMediaTypes(userSections)
    val defaultSections = if (defaultMediaTypes.isEmpty()) {
      emptyList()
    } else {
      defaultSectionsLoader(defaultMediaTypes)
    }

    val resolved = buildHomeCatalogs(
      defaultSections = defaultSections,
      userSections = userSections,
      allMedia = tmdbMedia,
      heroSynopsisLocalizer = HeroSynopsisLocalizer::localizeAll
    )
    homeResolutionGate.complete(requestId, resolved)
  }

  /**
   * Tipi per cui la Home deve ancora usare Cinemeta: quelli che nessuna sezione
   * addon popolata copre. La decisione è per tipo, non globale.
   */
  internal fun defaultCinemetaMediaTypes(userSections: List<CatalogSection>): List<MediaType> =
    listOf(MediaType.FILM, MediaType.SERIE_TV).filter { mediaType ->
      userSections.none { it.mediaType == mediaType && it.items.isNotEmpty() }
    }

  /**
   * Costruisce i [HomeCatalogs] a partire dalle sezioni Cinemeta di default, dalle
   * sezioni degli addon utente e dalla cache locale. Funzione PURA (nessuna rete):
   * la logica di mapping è così verificabile nei test unitari.
   *
   * Per ogni tipo vale UNA sola sorgente: se le sezioni addon del tipo hanno
   * contenuti, le righe stock Cinemeta dello stesso tipo restano vuote; altrimenti
   * valgono le righe Cinemeta (con la cache locale come fallback delle categorie).
   */
  internal fun buildHomeCatalogs(
    defaultSections: List<CatalogSection>,
    userSections: List<CatalogSection>,
    allMedia: List<MediaItem>
  ): HomeCatalogs {
    val extraSections = userSections
      .filter { it.items.isNotEmpty() }
      .distinctBy { it.id }

    val userMovies = deduplicateItems(
      userSections.filter { it.mediaType == MediaType.FILM }.flatMap { it.items }
    )
    val userSeries = deduplicateItems(
      userSections.filter { it.mediaType == MediaType.SERIE_TV }.flatMap { it.items }
    )

    val moviesFromAddon = userMovies.isNotEmpty()
    val seriesFromAddon = userSeries.isNotEmpty()

    fun defaultItems(catalogId: String, mediaType: MediaType): List<MediaItem> =
      defaultSections.firstOrNull { it.catalogId == catalogId && it.mediaType == mediaType }?.items
        ?: emptyList()

    val popularMovies = defaultItems(CinemetaAddon.CATALOG_POPULAR, MediaType.FILM)
    val popularSeries = defaultItems(CinemetaAddon.CATALOG_POPULAR, MediaType.SERIE_TV)
    val topRatedMovies = defaultItems(CinemetaAddon.CATALOG_TOP_RATED, MediaType.FILM)
    val topRatedSeries = defaultItems(CinemetaAddon.CATALOG_TOP_RATED, MediaType.SERIE_TV)
    val newMovies = defaultItems(CinemetaAddon.CATALOG_NEW, MediaType.FILM)
    val newSeries = defaultItems(CinemetaAddon.CATALOG_NEW, MediaType.SERIE_TV)

    val allDefaultMovies = deduplicateItems(popularMovies + topRatedMovies + newMovies)
    val allDefaultSeries = deduplicateItems(popularSeries + topRatedSeries + newSeries)

    val homeMovies = if (moviesFromAddon) userMovies else allDefaultMovies
    val homeSeries = if (seriesFromAddon) userSeries else allDefaultSeries

    val hero = buildHeroItems(homeMovies, homeSeries, fallback = allMedia)

    return HomeCatalogs(
      heroItems = hero,
      // Classifica 1..10: troncamento rigido, indipendente dalla dimensione pagina del catalogo.
      top10Movies = if (moviesFromAddon) emptyList() else topRatedMovies.take(HOME_TOP_10_SIZE),
      top10Series = if (seriesFromAddon) emptyList() else topRatedSeries.take(HOME_TOP_10_SIZE),
      trendingMovies = if (moviesFromAddon) emptyList() else newMovies,
      trendingSeries = if (seriesFromAddon) emptyList() else newSeries,
      forYouMovies = emptyList(),
      forYouSeries = emptyList(),
      popularMovies = if (moviesFromAddon) emptyList() else popularMovies,
      popularSeries = if (seriesFromAddon) emptyList() else popularSeries,
      extraSections = extraSections,
      hasCustomCatalogMovies = moviesFromAddon,
      hasCustomCatalogSeries = seriesFromAddon,
      moviesFromDefaultCatalog = !moviesFromAddon && allDefaultMovies.isNotEmpty(),
      seriesFromDefaultCatalog = !seriesFromAddon && allDefaultSeries.isNotEmpty(),
      allMovies = homeMovies.ifEmpty { allMedia.filter { it.type == MediaType.FILM } },
      allSeries = homeSeries.ifEmpty { allMedia.filter { it.type == MediaType.SERIE_TV } }
    )
  }

  /**
   * Variante con localizzazione della sinossi dell'Hero Banner.
   *
   * I cataloghi Stremio (Cinemeta in primis) espongono la sinossi solo in inglese e
   * non supportano alcun parametro di lingua: [heroSynopsisLocalizer] sostituisce il
   * testo SOLO per gli item dell'Hero, lasciando invariati poster, titoli, ordine,
   * id, provider e tutte le altre righe della Home. Un errore del localizer non
   * interrompe il caricamento: si mantengono le sinossi originali.
   */
  internal suspend fun buildHomeCatalogs(
    defaultSections: List<CatalogSection>,
    userSections: List<CatalogSection>,
    allMedia: List<MediaItem>,
    heroSynopsisLocalizer: (suspend (List<MediaItem>) -> List<MediaItem>)?
  ): HomeCatalogs {
    val base = buildHomeCatalogs(
      defaultSections = defaultSections,
      userSections = userSections,
      allMedia = allMedia
    )
    if (heroSynopsisLocalizer == null || base.heroItems.isEmpty()) return base
    val localizedHero = try {
      heroSynopsisLocalizer(base.heroItems)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Localizzazione sinossi Hero fallita, testo originale mantenuto: ${e.message}")
      base.heroItems
    }
    return if (localizedHero == base.heroItems) base else base.copy(heroItems = localizedHero)
  }

  /**
   * Carica le sezioni Cinemeta che alimentano le righe principali della Home.
   * Isola gli errori: un singolo catalogo che fallisce non azzera le altre righe.
   */
  private suspend fun loadDefaultCinemetaSectionsSafely(
    mediaTypes: List<MediaType>
  ): List<CatalogSection> = try {
    loadDefaultCinemetaSections(mediaTypes)
  } catch (e: Exception) {
    Log.w(TAG, "Cinemeta non raggiungibile, righe Home vuote: ${e.message}")
    emptyList()
  }

  /**
   * Interroga i cataloghi di default Cinemeta per i [mediaTypes] richiesti usando lo
   * stesso motore dei cataloghi addon ([StremioCatalogEngine.fetchCatalog]).
   */
  suspend fun loadDefaultCinemetaSections(
    mediaTypes: List<MediaType> = listOf(MediaType.FILM, MediaType.SERIE_TV)
  ): List<CatalogSection> = coroutineScope {
    val plans = mediaTypes.flatMap { CinemetaAddon.homeCatalogPlans(it) }
    if (plans.isEmpty()) return@coroutineScope emptyList()

    plans.map { plan ->
      async {
        val target = StremioCatalogTarget(
          addonId = CinemetaAddon.MANIFEST_ID,
          addonName = CinemetaAddon.DISPLAY_NAME,
          baseUrl = CinemetaAddon.BASE_URL,
          catalog = plan.catalog
        )
        try {
          val result = StremioCatalogEngine.fetchCatalog(target, plan.extra)
          if (result.isSuccess) buildCatalogSection(target, result) else null
        } catch (e: Exception) {
          Log.w(TAG, "Catalogo Cinemeta ${plan.catalog.effectiveId} fallito: ${e.message}")
          null
        }
      }
    }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
  }

  /** true se [addonId] identifica un addon di sistema (Cinemeta). */
  fun isSystemAddon(addonId: String?): Boolean = CinemetaAddon.isSystemAddon(addonId)

  /**
   * Carica le sezioni Stremio di un tipo per la Home isolando gli errori: un addon
   * che fallisce (o un manifest che esplode) non blocca l'altro tipo né le righe
   * di default Cinemeta.
   */
  private suspend fun loadHomeSectionsSafely(mediaType: MediaType): List<CatalogSection> =
    try {
      loadAllCatalogSections(mediaType)
    } catch (e: Exception) {
      Log.w(TAG, "Tentativo addon $mediaType fallito: ${e.message}")
      emptyList()
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
   * @param fallbackToDefault true se i target provengono tutti dal FALLBACK
   *        Cinemeta (solo senza addon di catalogo personalizzati attivi).
   */
  data class ProviderCatalogPage(
    val mediaType: MediaType? = null,
    val items: List<MediaItem> = emptyList(),
    val provenance: Map<String, List<CatalogProvenance>> = emptyMap(),
    val hasConfirmedBindings: Boolean = false,
    val targets: List<ResolvedProviderTarget> = emptyList(),
    val fallbackToDefault: Boolean = false
  )

  /**
   * Piano di caricamento per provider, calcolato dai soli manifest (nessuna rete).
   * Con [mediaType] non nullo contiene esclusivamente cataloghi di quel tipo: la
   * separazione Film/Serie è applicata prima di qualsiasi lookup.
   *
   * SORGENTE DEI CATALOGHI (vedi [ProviderCatalogSourcePolicy]):
   *  - con addon di catalogo personalizzati attivi (Xperience / Easy Catalog / ...)
   *    i target Cinemeta vengono ESCLUSI e i cataloghi degli addon sono associati
   *    al provider tramite match su `catalog.id` / `catalog.name` / nome addon /
   *    extra `genre`-`provider`-`platform` dichiarati nel manifest;
   *  - senza addon personalizzati vale la precedenza dichiarata (USER → REGISTRY) e,
   *    se il provider non ha alcun binding, il FALLBACK Cinemeta (marcato
   *    [ProviderCatalogPlan.fallbackToDefault]).
   */
  fun providerCatalogPlan(
    provider: StreamingProvider,
    mediaType: MediaType? = null
  ): ProviderCatalogPlan {
    val customCatalogsActive = hasCustomCatalogAddons()
    val aliasProvider = provider.asAliasProvider()

    // 1. Esclusione radicale di Cinemeta quando gli addon personalizzati sono attivi.
    val targets = ProviderCatalogSourcePolicy.selectTargets(
      targets = StremioCatalogEngine.getAvailableCatalogs(toStremioType(mediaType)),
      hasCustomCatalogAddons = customCatalogsActive
    )

    val catalogIdsByAddon = if (customCatalogsActive) {
      StremioCatalogEngine.getAvailableCatalogs(null).groupBy({ it.addonId }, { it.catalogId })
    } else {
      emptyMap()
    }

    val plan = ProviderCatalogPlanner.plan(
      providerId = provider.id,
      targets = targets,
      resolver = resolver(),
      mediaType = mediaType,
      onBindMissing = { target ->
        if (customCatalogsActive) {
          ProviderCatalogSourcePolicy.customAddonBinding(
            provider = aliasProvider,
            target = target,
            addonCatalogIds = catalogIdsByAddon[target.addonId].orEmpty()
          )
        } else {
          null
        }
      },
      platformExtra = { target ->
        ProviderCatalogSourcePolicy.platformExtraFor(aliasProvider, target)
      }
    )
    if (customCatalogsActive || plan.hasConfirmedBindings) return plan

    // Nessun catalogo dichiarato o addon personalizzato attivo per questo provider (es. Cinemeta):
    // Ritorna il piano vuoto così la richiesta ricade sul client TMDB Discover (with_watch_providers).
    return plan
  }

  /**
   * Verifica se l'addon attivo possiede un catalogo corrispondente ai token del provider.
   */
  fun hasProviderCatalog(providerId: String, mediaType: MediaType? = null): Boolean =
    StremioAddonRepository.hasProviderCatalog(providerId, toStremioType(mediaType))

  /**
   * Target Cinemeta di DEFAULT per il fallback dei provider: cataloghi di sistema
   * (`top` / `imdbRating` / `year`) per il tipo richiesto (entrambi i tipi se
   * [mediaType] è `null`). Nessuna rete.
   */
  private fun systemFallbackTargets(mediaType: MediaType?): List<StremioCatalogTarget> =
    when (mediaType) {
      null -> defaultCinemetaTargets(MediaType.FILM) + defaultCinemetaTargets(MediaType.SERIE_TV)
      else -> defaultCinemetaTargets(mediaType)
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
   * 4. Il piano di FALLBACK Cinemeta (solo senza addon di catalogo personalizzati)
   *    è sempre [ProviderCoverage.PARTIAL]: è una fonte di completamento, non il
   *    catalogo specifico della piattaforma.
   *
   * NOTA: è una classificazione basata sul manifest. La validità di [FULL]
   * va comunque confermata dal fetch effettivo (vedi `StreamNovaViewModel`:
   * se il caricamento non produce item, FULL degrada a PARTIAL).
   */
  fun providerCoverage(provider: StreamingProvider): ProviderCoverage {
    val moviePlan = providerCatalogPlan(provider, MediaType.FILM)
    val seriesPlan = providerCatalogPlan(provider, MediaType.SERIE_TV)
    val movieTargets = moviePlan.resolved
    val seriesTargets = seriesPlan.resolved

    if (movieTargets.isEmpty() && seriesTargets.isEmpty()) return ProviderCoverage.NONE

    // Il FALLBACK Cinemeta non è il catalogo del provider: non può mai valere
    // "solo Stremio" (FULL). Resta PARTIAL, così il catalogo specifico del
    // provider (TMDB) continua a completare la schermata.
    if (moviePlan.fallbackToDefault || seriesPlan.fallbackToDefault) {
      return ProviderCoverage.PARTIAL
    }

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
          val baseExtra = ProviderCatalogPlanner.extraFor(resolved)
          // Riprende dall'offset GIÀ raggiunto dal target: alla prima apertura è 0
          // (fetch senza `skip`), su un eventuale ricaricamento parte dall'ultima pagina.
          var skip = providerPaging.nextOffset(resolved.targetId)
          val result = if (skip > 0 && resolved.supportsSkip) {
            StremioCatalogEngine.paginateCatalog(
              target = resolved.target,
              skip = skip,
              baseExtra = baseExtra
            )
          } else {
            StremioCatalogEngine.fetchCatalog(
              target = resolved.target,
              extra = baseExtra
            )
          }
          if (result.isSuccess) {
            val accumulated = mutableListOf<StremioMetaItem>()
            accumulated.addAll(result.items)
            providerPaging.advance(resolved.targetId, result.items.size)

            val seenIds = accumulated.mapNotNull { it.id?.trim()?.lowercase() }.toMutableSet()
            val minTargetItems = 20
            val maxTargetItems = 30
            var keepFetching = result.items.isNotEmpty() &&
              accumulated.size < minTargetItems &&
              resolved.supportsSkip

            var consecutiveEmptyOrDupe = 0
            while (keepFetching && consecutiveEmptyOrDupe < 2) {
              skip = providerPaging.nextOffset(resolved.targetId)
              val nextResult = StremioCatalogEngine.paginateCatalog(
                target = resolved.target,
                skip = skip,
                baseExtra = baseExtra
              )
              if (nextResult.isSuccess && nextResult.items.isNotEmpty()) {
                val newItems = nextResult.items.filter { item ->
                  val id = item.id?.trim()?.lowercase()
                  id == null || seenIds.add(id)
                }
                if (newItems.isNotEmpty()) {
                  accumulated.addAll(newItems)
                  providerPaging.advance(resolved.targetId, nextResult.items.size)
                  consecutiveEmptyOrDupe = 0
                } else {
                  consecutiveEmptyOrDupe++
                  providerPaging.advance(resolved.targetId, nextResult.items.size)
                }
                if (accumulated.size >= maxTargetItems || nextResult.items.isEmpty()) {
                  keepFetching = false
                }
              } else {
                keepFetching = false
              }
            }
            resolved to accumulated
          } else {
            Log.w(
              TAG,
              "Catalogo provider fallito per ${resolved.targetId}: ${result.errorMessage}"
            )
            null
          }
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
          var skip = providerPaging.nextOffset(resolved.targetId)
          val baseExtra = ProviderCatalogPlanner.extraFor(resolved)
          val result = StremioCatalogEngine.paginateCatalog(
            target = resolved.target,
            skip = skip,
            baseExtra = baseExtra
          )
          if (!result.isSuccess) return@async null

          val accumulated = mutableListOf<StremioMetaItem>()
          accumulated.addAll(result.items)
          providerPaging.advance(resolved.targetId, result.items.size)

          val seenIds = accumulated.mapNotNull { it.id?.trim()?.lowercase() }.toMutableSet()
          val minTargetItems = 20
          var keepFetching = result.items.isNotEmpty() &&
            accumulated.size < minTargetItems &&
            resolved.supportsSkip

          var consecutiveEmptyOrDupe = 0
          while (keepFetching && consecutiveEmptyOrDupe < 2) {
            skip = providerPaging.nextOffset(resolved.targetId)
            val nextResult = StremioCatalogEngine.paginateCatalog(
              target = resolved.target,
              skip = skip,
              baseExtra = baseExtra
            )
            if (nextResult.isSuccess && nextResult.items.isNotEmpty()) {
              val newItems = nextResult.items.filter { item ->
                val id = item.id?.trim()?.lowercase()
                id == null || seenIds.add(id)
              }
              if (newItems.isNotEmpty()) {
                accumulated.addAll(newItems)
                providerPaging.advance(resolved.targetId, nextResult.items.size)
                consecutiveEmptyOrDupe = 0
              } else {
                consecutiveEmptyOrDupe++
                providerPaging.advance(resolved.targetId, nextResult.items.size)
              }
              if (accumulated.size >= minTargetItems || nextResult.items.isEmpty()) {
                keepFetching = false
              }
            } else {
              keepFetching = false
            }
          }
          resolved to accumulated
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
    // ESCLUSIONE RADICALE (difesa in profondità): con addon di catalogo
    // personalizzati attivi nessun target Cinemeta può produrre righe nella
    // schermata provider. I target sono già stati esclusi a monte in
    // [providerCatalogPlan]; qui si filtra di nuovo per chiunque costruisca
    // la pagina seguendo un altro percorso.
    val sources = if (hasCustomCatalogAddons()) {
      fetched.filterNot { ProviderCatalogSourcePolicy.isCinemetaTarget(it.first.target) }
    } else {
      fetched
    }
    val assembled = ProviderCatalogAssembler.assemble(providerName, sources.map { pair ->
      CatalogEntryResult(pair.first, pair.second)
    })
    val deduped: List<MediaItem> = assembled.items
    val provenance = assembled.provenance
    return ProviderCatalogPage(
      mediaType = mediaType,
      items = deduped,
      provenance = provenance,
      hasConfirmedBindings = sources.isNotEmpty(),
      targets = sources.map { it.first },
      fallbackToDefault = sources.any { ProviderCatalogSourcePolicy.isCinemetaTarget(it.first.target) }
    )
  }

  /**
   * Esclusione radicale di Cinemeta da una pagina provider quando gli addon di
   * catalogo personalizzati sono attivi (Xperience / Easy Catalog / ...).
   *
   * Vengono rimossi ogni target Cinemeta e ogni titolo la cui provenienza è
   * ESCLUSIVAMENTE Cinemeta: un titolo che arriva anche da un addon personalizzato
   * resta. Con `hasCustomCatalogAddons()` false la pagina è restituita invariata
   * (Cinemeta resta il fallback autorizzato).
   *
   * Da usare nella UI come difesa in profondità dopo il caricamento della pagina
   * (vede `StreamNovaViewModel.openProvider`).
   */
  fun sanitizeProviderCatalogPage(page: ProviderCatalogPage): ProviderCatalogPage {
    if (!hasCustomCatalogAddons()) return page

    val targets = page.targets.filterNot { ProviderCatalogSourcePolicy.isCinemetaTarget(it.target) }
    val provenance = page.provenance.mapValues { (_, origins) ->
      origins.filterNot { ProviderCatalogSourcePolicy.isCinemeta(it.addonManifestId) }
    }
    val items = page.items.filter { item ->
      val origins = provenance[item.id]
      origins == null || origins.isNotEmpty()
    }
    val keptIds = items.mapTo(mutableSetOf()) { it.id }
    return page.copy(
      items = items,
      provenance = provenance.filterKeys { it in keptIds },
      targets = targets
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
      targets = (movies.targets + series.targets).distinctBy { it.targetId },
      fallbackToDefault = movies.fallbackToDefault || series.fallbackToDefault
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
    // Con addon personalizzati attivi la schermata Film pagina SOLO i cataloghi degli
    // addon: le sorgenti Cinemeta di default vengono escluse per evitare duplicati e
    // dipendenza esclusiva da Cinemeta.
    val defaultTargets = if (hasCustomCatalogAddons()) emptyList() else defaultCinemetaTargets(MediaType.FILM)
    val targets = (getAvailableTargets(MediaType.FILM) + defaultTargets)
      .filter { it.supportsSkip }
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
    // Come [paginateMovies]: con addon personalizzati attivi si paginano solo i loro cataloghi.
    val defaultTargets = if (hasCustomCatalogAddons()) emptyList() else defaultCinemetaTargets(MediaType.SERIE_TV)
    val targets = (getAvailableTargets(MediaType.SERIE_TV) + defaultTargets)
      .filter { it.supportsSkip }
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
