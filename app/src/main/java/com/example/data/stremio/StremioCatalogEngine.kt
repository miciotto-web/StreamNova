package com.example.data.stremio

import android.util.Log
import com.example.data.util.AsyncSingleFlight
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Target di interrogazione per un singolo catalogo esposto da uno specifico addon.
 */
data class StremioCatalogTarget(
  val addonId: String,
  val addonName: String,
  val baseUrl: String,
  val catalog: StremioCatalogDefinition
) {
  val id: String
    get() = "$addonId:${catalog.effectiveType}:${catalog.effectiveId}"

  val type: String
    get() = catalog.effectiveType

  val catalogId: String
    get() = catalog.effectiveId

  val title: String
    get() = catalog.displayTitle

  val isMovie: Boolean
    get() = catalog.isMovie

  val isSeries: Boolean
    get() = catalog.isSeries

  val supportsSearch: Boolean
    get() = catalog.supportsSearch()

  val requiresSearch: Boolean
    get() = catalog.requiresSearch()

  val supportsGenre: Boolean
    get() = catalog.supportsGenre()

  val supportsSkip: Boolean
    get() = catalog.supportsSkip()

  val genreOptions: List<String>
    get() = catalog.genreOptions()
}

/**
 * Risultato restituito dal Catalog Engine per un catalogo Stremio.
 */
data class StremioCatalogResult(
  val addonId: String,
  val addonName: String,
  val catalogId: String,
  val catalogName: String,
  val type: String,
  val items: List<StremioMetaItem> = emptyList(),
  val extraApplied: Map<String, String> = emptyMap(),
  val isSuccess: Boolean = true,
  val errorMessage: String? = null
) {
  val isEmpty: Boolean
    get() = items.isEmpty()
}

/**
 * Engine per l'interrogazione dei cataloghi Stremio dichiarati dagli addon installati.
 *
 * CARATTERISTICHE:
 *  - Individua tutti i cataloghi esposti dagli addon attivi;
 *  - Costruisce l'endpoint `/catalog/{type}/{id}[/{extra}].json`;
 *  - Rispetta types e idPrefixes dichiarati a livello di catalog o resource;
 *  - Invia SOLO i parametri extra esplicitamente supportati dal catalogo;
 *  - Esegue richieste in parallelo su addon multipli isolando gli errori dei singoli addon;
 *  - Cache in memoria a breve termine con deduplicazione single-flight.
 */
object StremioCatalogEngine {

  private const val TAG = "StremioCatalogEngine"
  private const val CACHE_TTL_MS = 180_000L // 3 minuti
  private const val TIMEOUT_SECONDS = 15L

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .addInterceptor(
      HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
    )
    .build()

  private val moshi = com.squareup.moshi.Moshi.Builder()
    .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
    .build()

  private val catalogResponseAdapter = moshi.adapter(StremioCatalogResponse::class.java)

  private val singleFlight = AsyncSingleFlight<StremioCatalogResult>()
  private val cache = ConcurrentHashMap<String, CachedResult>()

  private data class CachedResult(
    val timestamp: Long,
    val result: StremioCatalogResult
  )

  /**
   * Restituisce tutti i target catalogo disponibili dagli addon attivi.
   *
   * @param type tipo opzionale ("movie", "series") per filtrare i cataloghi.
   */
  fun getAvailableCatalogs(type: String? = null): List<StremioCatalogTarget> {
    val activeAddons = StremioAddonRepository.getActiveAddons()
    return activeAddons.flatMap { addon ->
      val manifest = addon.manifest
      if (type != null && !manifest.supportsCatalogForType(type)) {
        return@flatMap emptyList()
      }
      val catalogs = if (type != null) {
        manifest.catalogsForType(type)
      } else {
        manifest.validCatalogs()
      }
      catalogs.map { catalog ->
        StremioCatalogTarget(
          addonId = addon.id,
          addonName = manifest.displayTitle,
          baseUrl = addon.baseUrl,
          catalog = catalog
        )
      }
    }
  }

  /**
   * Filtra la mappa di parametri extra forniti, mantenendo SOLO quelli supportati
   * dal catalogo specifico, evitando hardcoding o parametri non dichiarati.
   */
  fun sanitizeExtra(
    catalog: StremioCatalogDefinition,
    requestedExtra: Map<String, String>
  ): Map<String, String> {
    if (requestedExtra.isEmpty()) return emptyMap()
    return requestedExtra.filter { (key, value) ->
      key.isNotBlank() && value.isNotBlank() && catalog.supportsExtra(key)
    }
  }

  /**
   * Scarica i contenuti di un singolo catalogo.
   *
   * @param target target del catalogo (addon + catalog definition).
   * @param extra parametri extra opzionali (search, genre, skip, ecc.).
   * @param skipCache se true ignora la cache in memoria.
   */
  suspend fun fetchCatalog(
    target: StremioCatalogTarget,
    extra: Map<String, String> = emptyMap(),
    skipCache: Boolean = false
  ): StremioCatalogResult {
    val catalog = target.catalog

    // Se il catalogo richiede parametri obbligatori (es. search) e mancano, non tentare la chiamata
    if (catalog.requiresSearch() && !extra.any { it.key.equals(EXTRA_SEARCH, ignoreCase = true) && it.value.isNotBlank() }) {
      return StremioCatalogResult(
        addonId = target.addonId,
        addonName = target.addonName,
        catalogId = target.catalogId,
        catalogName = target.title,
        type = target.type,
        isSuccess = false,
        errorMessage = "Il catalogo '${target.title}' richiede il parametro search"
      )
    }

    val sanitizedExtra = sanitizeExtra(catalog, extra)
    val cacheKey = "${target.baseUrl}|${target.type}|${target.catalogId}|${sanitizedExtra.toSortedMap()}"

    if (!skipCache) {
      cache[cacheKey]?.let { cached ->
        if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
          return cached.result
        }
        cache.remove(cacheKey)
      }
    }

    val result = singleFlight.run(cacheKey) {
      executeCatalogFetch(target, sanitizedExtra)
    }

    if (result.isSuccess) {
      cache[cacheKey] = CachedResult(System.currentTimeMillis(), result)
    }
    return result
  }

  /**
   * Interroga in parallelo tutti i cataloghi disponibili per un determinato [type] ("movie", "series").
   * Isola gli errori per singolo addon: se un addon fallisce o va in timeout, gli altri continuano regolarmente.
   */
  suspend fun fetchAllCatalogsForType(
    type: String,
    extra: Map<String, String> = emptyMap()
  ): List<StremioCatalogResult> = coroutineScope {
    val targets = getAvailableCatalogs(type)
      .filter { it.catalog.canLoadWithoutArgs() || extra.isNotEmpty() }

    if (targets.isEmpty()) return@coroutineScope emptyList()

    targets.map { target ->
      async {
        try {
          fetchCatalog(target, extra)
        } catch (e: Exception) {
          Log.w(TAG, "Errore caricamento catalogo ${target.id}: ${e.message}")
          StremioCatalogResult(
            addonId = target.addonId,
            addonName = target.addonName,
            catalogId = target.catalogId,
            catalogName = target.title,
            type = target.type,
            isSuccess = false,
            errorMessage = e.message
          )
        }
      }
    }.awaitAll()
  }

  /**
   * Esegue una ricerca testuale su tutti i cataloghi attivi che supportano il parametro `search`.
   */
  suspend fun searchCatalogs(
    query: String,
    type: String? = null
  ): List<StremioCatalogResult> = coroutineScope {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return@coroutineScope emptyList()

    val targets = getAvailableCatalogs(type).filter { it.supportsSearch }
    if (targets.isEmpty()) return@coroutineScope emptyList()

    val searchExtra = mapOf(EXTRA_SEARCH to cleanQuery)
    targets.map { target ->
      async {
        try {
          fetchCatalog(target, searchExtra)
        } catch (e: Exception) {
          Log.w(TAG, "Errore ricerca catalogo ${target.id}: ${e.message}")
          StremioCatalogResult(
            addonId = target.addonId,
            addonName = target.addonName,
            catalogId = target.catalogId,
            catalogName = target.title,
            type = target.type,
            isSuccess = false,
            errorMessage = e.message
          )
        }
      }
    }.awaitAll()
  }

  /**
   * Filtra i cataloghi attivi per genere (parametro `genre`).
   */
  suspend fun fetchByGenre(
    genre: String,
    type: String? = null
  ): List<StremioCatalogResult> = coroutineScope {
    val cleanGenre = genre.trim()
    if (cleanGenre.isBlank()) return@coroutineScope emptyList()

    val targets = getAvailableCatalogs(type).filter { target ->
      target.supportsGenre && (target.genreOptions.isEmpty() || target.genreOptions.any { it.equals(cleanGenre, ignoreCase = true) })
    }
    if (targets.isEmpty()) return@coroutineScope emptyList()

    val genreExtra = mapOf(EXTRA_GENRE to cleanGenre)
    targets.map { target ->
      async {
        try {
          fetchCatalog(target, genreExtra)
        } catch (e: Exception) {
          Log.w(TAG, "Errore filtro genere ${target.id}: ${e.message}")
          StremioCatalogResult(
            addonId = target.addonId,
            addonName = target.addonName,
            catalogId = target.catalogId,
            catalogName = target.title,
            type = target.type,
            isSuccess = false,
            errorMessage = e.message
          )
        }
      }
    }.awaitAll()
  }

  /**
   * Pagina un catalogo tramite il parametro `skip` (se supportato dal catalogo).
   */
  suspend fun paginateCatalog(
    target: StremioCatalogTarget,
    skip: Int,
    baseExtra: Map<String, String> = emptyMap()
  ): StremioCatalogResult {
    if (!target.supportsSkip) {
      return StremioCatalogResult(
        addonId = target.addonId,
        addonName = target.addonName,
        catalogId = target.catalogId,
        catalogName = target.title,
        type = target.type,
        isSuccess = false,
        errorMessage = "Paginazione non supportata da questo catalogo"
      )
    }
    val paginationExtra = baseExtra + (EXTRA_SKIP to skip.toString())
    return fetchCatalog(target, paginationExtra)
  }

  /** Svuota la cache in memoria del Catalog Engine. */
  fun clearCache() {
    cache.clear()
  }

  private suspend fun executeCatalogFetch(
    target: StremioCatalogTarget,
    extra: Map<String, String>
  ): StremioCatalogResult = withContext(Dispatchers.IO) {
    val url = StremioAddonRepository.buildCatalogUrl(
      baseUrl = target.baseUrl,
      type = target.type,
      id = target.catalogId,
      extra = extra
    )
    val request = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .build()

    try {
      withTimeout(TIMEOUT_SECONDS * 1000L) {
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            return@withTimeout StremioCatalogResult(
              addonId = target.addonId,
              addonName = target.addonName,
              catalogId = target.catalogId,
              catalogName = target.title,
              type = target.type,
              extraApplied = extra,
              isSuccess = false,
              errorMessage = "HTTP ${response.code} per $url"
            )
          }

          val body = response.body?.string()
          if (body.isNullOrBlank()) {
            return@withTimeout StremioCatalogResult(
              addonId = target.addonId,
              addonName = target.addonName,
              catalogId = target.catalogId,
              catalogName = target.title,
              type = target.type,
              extraApplied = extra,
              isSuccess = true,
              items = emptyList()
            )
          }

          val parsed = try {
            catalogResponseAdapter.fromJson(body)
          } catch (e: Exception) {
            Log.w(TAG, "Parsing JSON catalogo fallito per $url: ${e.message}")
            return@withTimeout StremioCatalogResult(
              addonId = target.addonId,
              addonName = target.addonName,
              catalogId = target.catalogId,
              catalogName = target.title,
              type = target.type,
              extraApplied = extra,
              isSuccess = false,
              errorMessage = "JSON non valido: ${e.message}"
            )
          }

          val rawItems = parsed?.metas.orEmpty()

          // Rispetta gli idPrefixes dichiarati dall'addon per la risorsa catalog (se specificati)
          val addon = StremioAddonRepository.getInstalledAddons().firstOrNull { it.id == target.addonId }
          val allowedPrefixes = addon?.manifest?.idPrefixesForResource(RESOURCE_CATALOG)
            ?.filter { it.isNotBlank() }
            ?.map { it.lowercase() }

          val filteredItems = if (!allowedPrefixes.isNullOrEmpty()) {
            rawItems.filter { item ->
              val itemId = item.id?.lowercase()
              itemId == null || allowedPrefixes.any { prefix -> itemId.startsWith(prefix) }
            }
          } else {
            rawItems
          }

          StremioCatalogResult(
            addonId = target.addonId,
            addonName = target.addonName,
            catalogId = target.catalogId,
            catalogName = target.title,
            type = target.type,
            items = filteredItems,
            extraApplied = extra,
            isSuccess = true
          )
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Errore connessione per $url: ${e.message}")
      StremioCatalogResult(
        addonId = target.addonId,
        addonName = target.addonName,
        catalogId = target.catalogId,
        catalogName = target.title,
        type = target.type,
        extraApplied = extra,
        isSuccess = false,
        errorMessage = e.message ?: "Errore di rete sconosciuto"
      )
    }
  }
}
