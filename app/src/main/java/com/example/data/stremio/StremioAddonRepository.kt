package com.example.data.stremio

import android.util.Log
import com.example.data.api.TmdbApiClient
import com.example.data.prefs.AppSettingsRepository
import com.example.data.repository.MediaRepository
import com.example.data.util.AsyncSingleFlight
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Candidato stream restituito da uno addon Stremio installato.
 *
 * @param addonName nome display dell'addon (per il badge sorgente).
 * @param baseUrl base normalizzata dell'addon.
 * @param item stream grezzo restituito dall'addon.
 */
data class StremioStreamCandidate(
  val addonName: String,
  val baseUrl: String,
  val item: StremioStreamItem
) {
  val addonNameFromStream: String
    get() = item.name?.lines()?.firstOrNull()?.trim() ?: addonName

  val instantTag: String
    get() = item.name?.lines()?.getOrNull(1)?.trim() ?: ""

  val releaseTitle: String
    get() = item.title?.lines()?.firstOrNull()?.trim() ?: ""

  val sizeAndPeers: String
    get() = item.title?.lines()?.drop(1)?.joinToString(" " )?.trim() ?: ""

  val quality: String
    get() = item.name?.lines()?.getOrNull(1)?.let { line ->
      val lower = line.lowercase()
      when {
        lower.contains("4k") || lower.contains("2160") -> "4K"
        lower.contains("1080") -> "1080p"
        lower.contains("720") -> "720p"
        lower.contains("480") -> "480p"
        else -> "Auto"
      }
    } ?: "Auto"

  val codec: String?
    get() {
      val text = "${item.name} ${item.title}".lowercase()
      return when {
        text.contains("hevc") || text.contains("x265") || text.contains("hvc1") || text.contains("hdr10") || text.contains("hdr") -> "HEVC"
        text.contains("av1") || text.contains("vp9") -> "AV1/VP9"
        text.contains("h264") || text.contains("x264") || text.contains("avc") -> "H264"
        else -> null
      }
    }

  val releaseType: String?
    get() {
      val text = "${item.name} ${item.title}".lowercase()
      return when {
        text.contains("web-dl") || text.contains("webdl") || text.contains("web rip") -> "WEB-DL"
        text.contains("bluray") || text.contains("blu-ray") || text.contains("bdrip") -> "BluRay"
        text.contains("hdtv") || text.contains("tv rip") -> "HDTV"
        text.contains("hdr") -> "HDR"
        else -> null
      }
    }
}

/**
 * Gestore degli addon Stremio installati + interrogazione della risorsa `stream`.
 *
 * RESPONSABILITÀ:
 *  1. **Installazione**: normalizza l'URL incollato (`stremio://` → `https://`,
 *     rimozione trailing slash e suffisso `/manifest.json`), scarica e valida
 *     `$cleanUrl/manifest.json`, salva addon + manifest (JSON su DataStore via
 *     [AppSettingsRepository]).
 *  2. **Gestione**: elenco installati, abilita/disabilita, rimozione.
 *  3. **Interrogazione**: per ogni addon attivo con risorsa `stream` viene fatto
 *     `GET $baseUrl/stream/$type/$id.json` (id IMDb `tt...` oppure `tmdb:...`
 *     a seconda dei `idPrefixes` dichiarati; per le serie `tt...:S:E`).
 *
 * Le risposte sono messe in cache (TTL breve) e deduplicate "single-flight",
 * così il percorso addon e il percorso TorBox di
 * [com.example.data.streaming.StreamManager] condividono un'unica chiamata HTTP.
 */
object StremioAddonRepository {

  private const val TAG = "StremioAddonRepo"
  private const val CACHE_TTL_MS = 60_000L
  private const val CONNECT_TIMEOUT_S = 10L
  private const val READ_TIMEOUT_S = 15L
  private const val SUBTITLES_TIMEOUT_MS = 15_000L

  private val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val addonListAdapter: com.squareup.moshi.JsonAdapter<List<InstalledAddon>> = moshi.adapter(
    Types.newParameterizedType(List::class.java, InstalledAddon::class.java)
  )
  private val manifestAdapter = moshi.adapter(StremioManifest::class.java)
  private val streamResponseAdapter = moshi.adapter(StremioStreamResponse::class.java)
  private val catalogResponseAdapter = moshi.adapter(StremioCatalogResponse::class.java)

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
    .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
    .addInterceptor(
      HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
    )
    .build()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _addons = MutableStateFlow<List<InstalledAddon>>(emptyList())
  /** Lista degli addon installati (reattiva: la UI la osserva direttamente). */
  val addons: StateFlow<List<InstalledAddon>> = _addons.asStateFlow()

  private val initialized = AtomicBoolean(false)

  // Dopo la prima scrittura locale la lista in memoria è la sorgente di verità:
  // i read-back asincroni di DataStore vengono ignorati (evita che un'operazione
  // appena completata venga sovrascritta da un valore più vecchio).
  @Volatile private var localWrites = false

  private val streamFetchFlight = AsyncSingleFlight<List<StremioStreamItem>>()
  private val streamCache = ConcurrentHashMap<String, CachedStreamResponse>()
  private val imdbCache = ConcurrentHashMap<String, String>()

  private data class CachedStreamResponse(val at: Long, val items: List<StremioStreamItem>)

  /**
   * Collega il repository allo store persistente ([AppSettingsRepository]).
   * Idempotente, senza bisogno di [android.content.Context].
   */
  fun init() {
    if (!initialized.compareAndSet(false, true)) return
    scope.launch {
      AppSettingsRepository.installedAddonsJson.collect { json ->
        if (!localWrites) {
          _addons.value = parse(json)
        }
      }
    }
  }

  // ── URL ────────────────────────────────────────────────────────────────

  /**
   * Normalizza l'URL di un addon:
   *  - `stremio://` → `https://`
   *  - aggiunge lo schema mancante
   *  - rimuove trailing slash e il suffisso `/manifest.json` (con eventuale
   *    segmento di percorso prima di esso, es. `/50540a7325788/manifest.json`)
   *
   * @throws IllegalArgumentException se l'URL non è valido.
   */
  fun normalizeUrl(rawUrl: String): String {
    var url = rawUrl.trim()
    if (url.isBlank()) throw IllegalArgumentException("Inserisci l'URL dell'addon")

    if (url.startsWith("stremio://", ignoreCase = true)) {
      url = "https://" + url.substring("stremio://".length)
    }
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
      url = "https://$url"
    }

    val manifestIdx = url.indexOf("/manifest.json")
    if (manifestIdx > 0) {
      url = url.substring(0, manifestIdx)
    }
    url = url.trimEnd('/')
    if (url.isBlank()) throw IllegalArgumentException("URL non valido: $rawUrl")

    val httpUrl = url.toHttpUrlOrNull()
      ?: throw IllegalArgumentException("URL non valido: $rawUrl")
    return httpUrl.toString().trimEnd('/')
  }

  // ── CRUD addon ─────────────────────────────────────────────────────────

  /** Snapshot sincrono degli addon installati. */
  fun getInstalledAddons(): List<InstalledAddon> = _addons.value

  /** Snapshot sincrono degli addon abilitati. */
  fun getActiveAddons(): List<InstalledAddon> = _addons.value.filter { it.isEnabled }

  /**
   * Installa un addon: normalizza l'URL, scarica e valida il manifest e salva
   * l'addon in persistenza.
   *
   * @throws IllegalArgumentException URL non valido.
   * @throws IOException manifest non raggiungibile o non valido.
   */
  suspend fun installAddon(rawUrl: String): InstalledAddon = withContext(Dispatchers.IO) {
    // Attende il caricamento iniziale dalla persistenza (se ancora in corso), così
    // eventuali addon già installati in precedenza non vengono persi.
    if (!localWrites) {
      AppSettingsRepository.awaitLoaded()
    }
    val cleanUrl = normalizeUrl(rawUrl)
    val manifest = fetchManifest(cleanUrl)
    val addon = InstalledAddon(baseUrl = cleanUrl, manifest = manifest, isEnabled = true)

    val current = _addons.value.filterNot { it.baseUrl.equals(cleanUrl, ignoreCase = true) }
    persist(current + addon)
    Log.i(TAG, "Addon installato: ${manifest.displayTitle} ($cleanUrl)")
    addon
  }

  /** Rimuove l'addon con l'id [id] (manifest.id oppure baseUrl). */
  fun removeAddon(id: String) {
    val remaining = _addons.value.filterNot { it.id == id || it.baseUrl == id }
    if (remaining.size != _addons.value.size) {
      persist(remaining)
      Log.i(TAG, "Addon rimosso: $id")
    }
  }

  /** Abilita/disabilita un addon. */
  fun setAddonEnabled(id: String, enabled: Boolean) {
    val updated = _addons.value.map {
      if (it.id == id || it.baseUrl == id) it.copy(isEnabled = enabled) else it
    }
    if (updated != _addons.value) {
      persist(updated)
      Log.i(TAG, "Addon $id -> enabled=$enabled")
    }
  }

  private fun persist(list: List<InstalledAddon>) {
    val json = try {
      addonListAdapter.toJson(list)
    } catch (e: Exception) {
      Log.e(TAG, "Serializzazione addon fallita: ${e.message}")
      return
    }
    localWrites = true
    _addons.value = list
    AppSettingsRepository.setInstalledAddonsJson(json)
  }

  private fun parse(json: String): List<InstalledAddon> = try {
    if (json.isBlank() || json.trim() == "[]") emptyList()
    else addonListAdapter.fromJson(json).orEmpty()
  } catch (e: Exception) {
    Log.e(TAG, "Parsing addon salvati fallito: ${e.message}")
    emptyList()
  }

  // ── Manifest ───────────────────────────────────────────────────────────

  /** Scarica e valida `$baseUrl/manifest.json`. */
  suspend fun fetchManifest(baseUrl: String): StremioManifest = withContext(Dispatchers.IO) {
    val url = "${baseUrl.trimEnd('/')}/manifest.json"
    val request = Request.Builder().url(url).header("Accept", "application/json").build()
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("Manifest non trovato (HTTP ${response.code}) per $url")
      }
      val body = response.body?.string()
        ?: throw IOException("Manifest vuoto per $url")
      val manifest = try {
        manifestAdapter.fromJson(body)
      } catch (e: Exception) {
        throw IOException("Manifest JSON non valido per $url: ${e.message}")
      } ?: throw IOException("Manifest JSON non valido per $url")

      if (manifest.id.isNullOrBlank()) throw IOException("Manifest privo di campo 'id'")
      if (manifest.name.isNullOrBlank()) throw IOException("Manifest privo di campo 'name'")
      manifest
    }
  }

  // ── Interrogazione stream ──────────────────────────────────────────────

  /**
   * Interroga **in parallelo** tutti gli addon attivi con risorsa `stream`.
   *
   * @param tmdbId id TMDB del titolo.
   * @param isTv true per le serie TV.
   * @param season stagione (serie TV).
   * @param episode episodio (serie TV).
   * @return candidati stream di tutti gli addon attivi (cache condivisa/single-flight).
   */
  suspend fun fetchStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int? = null,
    episode: Int? = null
  ): List<StremioStreamCandidate> {
    val type = if (isTv) TYPE_SERIES else TYPE_MOVIE
    val active = _addons.value.filter {
      it.isEnabled && it.manifest.supportsStreamForType(type)
    }
    if (active.isEmpty() || tmdbId <= 0) return emptyList()

    val imdbId = resolveImdbId(tmdbId, isTv)

    return coroutineScope {
      active.map { addon ->
        async {
          val id = stremioIdFor(addon, tmdbId, imdbId)
          if (id == null) {
            Log.d(
              TAG,
              "Addon ${addon.manifest.displayTitle}: nessun id compatibile (prefix=${addon.manifest.idPrefixesForResource(RESOURCE_STREAM)})"
            )
            return@async emptyList()
          }
          val path = buildStreamPath(type, id, if (isTv) season else null, if (isTv) episode else null)
          try {
            withTimeout(10_000L) {
              fetchStreamItems(addon, path).map { item ->
                StremioStreamCandidate(
                  addonName = addon.manifest.displayTitle,
                  baseUrl = addon.baseUrl,
                  item = item
                )
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Addon ${addon.manifest.displayTitle} -> ${e.message}")
            emptyList()
          }
        }
      }.awaitAll().flatten()
    }
  }

  /** Costruisce il path `series/tt...:S:E.json` / `movie/tt....json`. */
  fun buildStreamPath(type: String, id: String, season: Int?, episode: Int?): String {
    val suffix = if (type == "series" && season != null && episode != null) ":$season:$episode" else ""
    return "$type/$id$suffix.json"
  }

  /**
   * Sceglie l'id da usare a seconda dei `idPrefixes` dichiarati dall'addon:
   *  - "tt" (o prefissi assenti) → id IMDb (`tt1234567`)
   *  - "tmdb" → `tmdb:12345`
   */
  private fun stremioIdFor(addon: InstalledAddon, tmdbId: Int, imdbId: String?): String? {
    val prefixes = addon.manifest.idPrefixesForResource(RESOURCE_STREAM)
      ?.filter { it.isNotBlank() }
      ?.map { it.lowercase() }
    return when {
      prefixes.isNullOrEmpty() -> imdbId ?: "tmdb:$tmdbId"
      prefixes.contains("tt") -> imdbId ?: if (prefixes.contains("tmdb")) "tmdb:$tmdbId" else null
      prefixes.contains("tmdb") -> "tmdb:$tmdbId"
      else -> imdbId
    }
  }

  private suspend fun fetchStreamItems(addon: InstalledAddon, path: String): List<StremioStreamItem> {
    val baseUrl = addon.baseUrl.trimEnd('/')
    val cacheKey = "$baseUrl|$path"
    streamCache[cacheKey]?.let { cached ->
      if (System.currentTimeMillis() - cached.at < CACHE_TTL_MS) return cached.items
      streamCache.remove(cacheKey)
    }
    val items = streamFetchFlight.run(cacheKey) {
      withContext(Dispatchers.IO) {
        val url = "$baseUrl/stream/$path"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("HTTP ${response.code} su $url")
          val body = response.body?.string() ?: throw IOException("Risposta vuota da $url")
          val parsed = streamResponseAdapter.fromJson(body)
            ?: throw IOException("Risposta non valida da $url")
          parsed.streams.orEmpty()
        }
      }
    }
    streamCache[cacheKey] = CachedStreamResponse(System.currentTimeMillis(), items)
    return items
  }

  // ── ID IMDb (TMDB external_ids) ────────────────────────────────────────

  /**
   * Risolve l'id IMDb del titolo (cache in memoria) necessario agli addon che
   * dichiarano il prefisso `tt` (formato Stremio `tt0944947`, per le serie
   * `tt0944947:1:2`).
   */
  private suspend fun resolveImdbId(tmdbId: Int, isTv: Boolean): String? {
    val key = (if (isTv) "tv" else "movie") + ":" + tmdbId
    imdbCache[key]?.let { return it.ifBlank { null } }
    val imdb = try {
      val dto = if (isTv) {
        TmdbApiClient.service.getTvExternalIds(tvId = tmdbId, apiKey = MediaRepository.getEffectiveApiKey())
      } else {
        TmdbApiClient.service.getMovieExternalIds(movieId = tmdbId, apiKey = MediaRepository.getEffectiveApiKey())
      }
      dto.imdbId?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
      Log.w(TAG, "external_ids TMDB per $key fallito: ${e.message}")
      null
    }
    imdbCache[key] = imdb ?: ""
    return imdb
  }

  // ── Cataloghi ──────────────────────────────────────────────────────────

  /**
   * Costruisce il path relativo del catalogo secondo il protocollo Stremio:
   *  - Senza parametri extra: `catalog/$type/$id.json`
   *  - Con parametri extra: `catalog/$type/$id/$extraArgs.json`
   *    dove extraArgs è una sequenza di `key=value` separati da `&`.
   *
   * @param type tipo del contenuto ("movie", "series", ecc.)
   * @param id identificativo del catalogo dichiarato nel manifest
   * @param extra parametri extra opzionali (search, genre, skip...)
   * @param isAddonCatalog true se si tratta di un addonCatalog (path `addon-catalog/...`)
   */
  fun buildCatalogPath(
    type: String,
    id: String,
    extra: Map<String, String> = emptyMap(),
    isAddonCatalog: Boolean = false
  ): String {
    val root = if (isAddonCatalog) "addon-catalog" else "catalog"
    val cleanType = type.trim()
    val cleanId = id.trim()
    val validExtras = extra.entries
      .filter { it.key.isNotBlank() && it.value.isNotBlank() }
      .sortedBy { it.key }

    if (validExtras.isEmpty()) {
      return "$root/$cleanType/$cleanId.json"
    }

    val extraFormatted = validExtras.joinToString("&") { (k, v) ->
      val encoded = java.net.URLEncoder.encode(v.trim(), "UTF-8")
        .replace("+", "%20")
      "${k.trim()}=$encoded"
    }
    return "$root/$cleanType/$cleanId/$extraFormatted.json"
  }

  /**
   * Costruisce l'URL assoluto completo dell'endpoint catalogo per un addon.
   */
  fun buildCatalogUrl(
    baseUrl: String,
    type: String,
    id: String,
    extra: Map<String, String> = emptyMap(),
    isAddonCatalog: Boolean = false
  ): String {
    val cleanBase = baseUrl.trimEnd('/')
    val path = buildCatalogPath(type, id, extra, isAddonCatalog)
    return "$cleanBase/$path"
  }

  /**
   * Restituisce tutti i cataloghi esposti dagli addon attivi per un dato [type] ("movie", "series", ecc.),
   * rispettando sia la dichiarazione del catalogo che i vincoli resource-level.
   */
  fun getActiveCatalogsForType(type: String): List<Pair<InstalledAddon, StremioCatalogDefinition>> {
    return getActiveAddons().flatMap { addon ->
      if (!addon.manifest.supportsCatalogForType(type)) return@flatMap emptyList()
      addon.manifest.catalogsForType(type).map { catalog -> addon to catalog }
    }
  }

  /**
   * Scarica gli elementi di un catalogo da un addon.
   */
  suspend fun fetchCatalog(
    addon: InstalledAddon,
    type: String,
    id: String,
    extra: Map<String, String> = emptyMap(),
    isAddonCatalog: Boolean = false
  ): List<StremioMetaItem> = withContext(Dispatchers.IO) {
    val path = buildCatalogPath(type, id, extra, isAddonCatalog)
    val baseUrl = addon.baseUrl.trimEnd('/')
    val url = "$baseUrl/$path"
    val request = Request.Builder().url(url).header("Accept", "application/json").build()
    try {
      withTimeout(15_000L) {
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            Log.w(TAG, "Catalogo $url fallito: HTTP ${response.code}")
            return@withTimeout emptyList()
          }
          val body = response.body?.string() ?: return@withTimeout emptyList()
          val parsed = catalogResponseAdapter.fromJson(body)
          parsed?.metas.orEmpty()
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Errore caricamento catalogo $url: ${e.message}")
      emptyList()
    }
  }

  /**
   * Overload per scaricare un catalogo passando direttamente [StremioCatalogDefinition].
   */
  suspend fun fetchCatalog(
    addon: InstalledAddon,
    catalog: StremioCatalogDefinition,
    extra: Map<String, String> = emptyMap()
  ): List<StremioMetaItem> {
    val type = catalog.type ?: return emptyList()
    val id = catalog.id ?: return emptyList()
    return fetchCatalog(addon, type, id, extra)
  }

  /**
   * Costruisce il path relativo dell'endpoint sottotitoli secondo il protocollo Stremio:
   *  - Senza parametri extra: `subtitles/$type/$id.json`
   *  - Con parametri extra: `subtitles/$type/$id/$extraArgs.json`, dove `extraArgs` e'
   *    una sequenza di `key=value` separati da `&` (chiavi ordinate: output stabile).
   *
   * @param type tipo del contenuto ("movie", "series")
   * @param id id Stremio del contenuto (`tt...`, `tt...:S:E` oppure `tmdb:...`)
   * @param extra parametri extra opzionali (videoHash, videoSize, filename)
   */
  fun buildSubtitlesPath(
    type: String,
    id: String,
    extra: Map<String, String> = emptyMap()
  ): String {
    val cleanType = type.trim()
    val cleanId = id.trim()
    val validExtras = extra.entries
      .filter { it.key.isNotBlank() && it.value.isNotBlank() }
      .sortedBy { it.key }

    if (validExtras.isEmpty()) {
      return "subtitles/$cleanType/$cleanId.json"
    }

    val extraFormatted = validExtras.joinToString("&") { (k, v) ->
      val encoded = java.net.URLEncoder.encode(v.trim(), "UTF-8")
        .replace("+", "%20")
      "${k.trim()}=$encoded"
    }
    return "subtitles/$cleanType/$cleanId/$extraFormatted.json"
  }

  /** Costruisce l'URL assoluto dell'endpoint sottotitoli di un addon. */
  fun buildSubtitlesUrl(
    baseUrl: String,
    type: String,
    id: String,
    extra: Map<String, String> = emptyMap()
  ): String {
    val cleanBase = baseUrl.trimEnd('/')
    return "$cleanBase/${buildSubtitlesPath(type, id, extra)}"
  }

  /**
   * Scarica i sottotitoli esterni per un contenuto da un addon che espone la risorsa
   * `subtitles`.
   *
   * @param addon addon da interrogare.
   * @param type tipo del contenuto ("movie", "series").
   * @param id id Stremio (`tt...`, `tt...:S:E` oppure `tmdb:...`).
   * @param options parametri extra ufficiali disponibili (videoHash, videoSize, filename).
   *
   * @return lista di sottotitoli utilizzabili; **lista vuota** se l'addon non dichiara
   *         la risorsa per quel tipo, se la risposta HTTP fallisce, se il JSON non e'
   *         valido o se gli elementi sono incompleti. Nessuna eccezione propagata.
   */
  suspend fun fetchSubtitles(
    addon: InstalledAddon,
    type: String,
    id: String,
    options: StremioSubtitleOptions = StremioSubtitleOptions()
  ): List<StremioSubtitle> = withContext(Dispatchers.IO) {
    val cleanType = type.trim()
    val cleanId = id.trim()
    if (cleanType.isEmpty() || cleanId.isEmpty()) return@withContext emptyList()
    if (!addon.manifest.supportsSubtitlesForType(cleanType)) {
      Log.d(TAG, "Addon ${addon.manifest.displayTitle}: risorsa subtitles non supportata per $cleanType")
      return@withContext emptyList()
    }

    val url = buildSubtitlesUrl(addon.baseUrl, cleanType, cleanId, options.toExtra())
    try {
      withTimeout(SUBTITLES_TIMEOUT_MS) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            Log.w(TAG, "Sottotitoli $url falliti: HTTP ${response.code}")
            return@withTimeout emptyList()
          }
          StremioSubtitleParser.parse(response.body?.string())
        }
      }
    } catch (e: Exception) {
      // Errore di rete o timeout: lista vuota, il chiamante non deve fallire.
      Log.w(TAG, "Errore caricamento sottotitoli $url: ${e.message}")
      emptyList()
    }
  }

  /** Svuota le cache in memoria (test / cambio account). */
  fun clearCaches() {
    streamCache.clear()
    imdbCache.clear()
  }
}
