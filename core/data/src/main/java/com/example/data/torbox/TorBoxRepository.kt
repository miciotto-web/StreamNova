package com.example.data.torbox

import android.util.Log
import com.example.data.prefs.AppSettingsRepository
import com.example.data.util.AsyncSingleFlight
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * Contratto del gateway TorBox usato da [com.example.data.streaming.TorBoxStreamProvider].
 * Astratto il repository per consentire test con un fake (batch checkcached + unlock).
 */
interface TorBoxGateway {
  /** true se TorBox può essere usato (toggle Instant Debrid ON + chiave presente). */
  fun isConfigured(): Boolean

  /** Normalizza un infoHash (trim + lowercase) come chiave di cache interna. */
  fun normalizeHash(infoHash: String): String

  /** Verifica in batch la cache istantanea di uno o più hash (`checkcached`). */
  suspend fun checkCached(hashes: List<String>): Map<String, TorBoxRepository.TorBoxCacheEntry>

  /** Sblocca un hash già verificato in cache (`createtorrent`/`requestdl`).
   * @param season stagione richiesta (opzionale, per selezione file).
   * @param episode episodio richiesto (opzionale, per selezione file).
   */
  suspend fun unlockCached(
    infoHash: String,
    fileIdx: Int?,
    entry: TorBoxRepository.TorBoxCacheEntry,
    season: Int? = null,
    episode: Int? = null
  ): String?
}

/**
 * Repository operativo su TorBox: verifica account, cache istantanea degli
 * infoHash, aggiunta magnet e sblocco del link CDN diretto per ExoPlayer.
 *
 * Flusso "Instant Debrid" per un infoHash:
 *  1. `checkcached` → il torrent è in cache?
 *  2. se sì: `createtorrent` con `add_only_if_cached=true` (percorso cached-only)
 *     → `torrent_id`, poi `mylist?id&bypass_cache=true` → file con i veri
 *     `file.id`;
 *  3. `requestdl?token=<api_key>&torrent_id=<id>&file_id=<f>&zip=false` →
 *     URL HTTPS CDN che viene passato ad ExoPlayer.
 *
 * I link risolti vengono memorizzati in memoria (TTL 15 minuti) e le risoluzioni
 * concorrenti dello stesso hash sono deduplicate "single-flight".
 */
object TorBoxRepository : TorBoxGateway {

  private const val TAG = "TorBoxRepository"
  private const val LINK_TTL_MS = 15 * 60 * 1000L

  /**
   * Numero massimo di hash per singola richiesta `checkcached`.
   *
   * TorBox ne accetta circa 100: il limite e' legato alla lunghezza massima della query string
   * (`?hash=h1,h2,...`). Superarlo fa fallire l'intera richiesta, azzerando i risultati di tutti
   * gli addon contemporaneamente.
   */
  const val CHECKCACHED_MAX_HASHES = 100

  /** Esito della verifica dell'account (`GET /user/me`). */
  sealed interface AccountCheck {
    /** Nessuna chiave configurata o Instant Debrid disabilitato. */
    object NotConfigured : AccountCheck

    /** Chiave valida: contiene i dati dell'account. */
    data class Valid(val user: TorBoxUserDto) : AccountCheck

    /** Chiave non valida o errore di rete. */
    data class Invalid(val message: String) : AccountCheck
  }

  /** Stato di cache di un singolo infoHash normalizzato. */
  data class TorBoxCacheEntry(
    val cached: Boolean,
    val torrentId: Long? = null,
    val files: List<TorBoxFile> = emptyList()
  )

  /** File di un torrent conosciuto da TorBox. */
  data class TorBoxFile(
    val id: Int,
    val name: String? = null,
    val size: Long? = null
  ) {
    private val isVideo: Boolean
      get() {
        val n = name?.lowercase() ?: return false
        if (n.isBlank()) return false
        if (EXCLUDED_PATTERNS.any { n.contains(it) }) return false
        return VIDEO_EXTENSIONS.any { n.endsWith(it) }
      }

    val videoScore: Long
      get() = if (isVideo) (size ?: 0L) else -1L
  }

  private val VIDEO_EXTENSIONS = listOf(
    ".mkv", ".mp4", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".mpg", ".mpeg", ".ts"
  )

  private val EXCLUDED_PATTERNS = listOf(
    "sample", "trailer", "subtitle", "subtitles", "srt", "nfo",
    "nofile", "poster", "folder", "info", "description"
  )

  private data class CachedLink(val at: Long, val url: String)

  private val linkCache = ConcurrentHashMap<String, CachedLink>()
  private val resolveFlight = AsyncSingleFlight<String?>()

  /**
   * Chiave della cache dei link sbloccati.
   *
   * Un infoHash **non** identifica un singolo file: un torrent di stagione contiene
   * tutti gli episodi e ogni episodio ha il proprio `file.id`, quindi il proprio URL
   * CDN. Memorizzare il link solo per hash faceva sì che il link di S01E01 venisse
   * restituito anche per S01E02 (per tutto il TTL di 15 minuti): il prossimo episodio
   * partiva con il file dell'episodio precedente. La chiave include quindi stagione ed
   * episodio quando sono noti; per film e richieste senza episodio resta l'hash.
   *
   * La chiave include anche [fileIdx]: lo stesso torrent può esporre più card con
   * risoluzioni diverse (es. 720p e 1080p, stesso hash, fileIdx differenti). Senza
   * il fileIdx nella chiave il link sbloccato per la card 720p veniva restituito
   * anche alla selezione della card 1080p (TTL 15 minuti): il player mostrava
   * 720p pur avendo scelto 1080p.
   */
  internal fun linkCacheKey(infoHash: String, season: Int?, episode: Int?, fileIdx: Int? = null): String {
    val hash = normalizeHash(infoHash)
    val episodeKey = if (season != null && season > 0 && episode != null && episode > 0) {
      ":s${season}e${episode}"
    } else {
      ""
    }
    val fileKey = fileIdx?.let { ":f$it" } ?: ""
    return "$hash$episodeKey$fileKey"
  }

  /**
   * Stagione/episodio effettivi di una richiesta di sblocco.
   *
   * I parametri espliciti sono la fonte autoritativa: viaggiano insieme alla richiesta
   * e sopravvivono a qualunque cambio di dispatcher. Il metadata legacy
   * ([setRequestMetadata]) resta come fallback per i chiamanti che non li passano.
   */
  internal fun requestedEpisode(season: Int?, episode: Int?): Pair<Int?, Int?> {
    if (season != null && season > 0 && episode != null && episode > 0) return season to episode
    val legacy = currentSeasonEpisode.get() ?: return null to null
    return legacy.first.takeIf { it > 0 } to legacy.second.takeIf { it > 0 }
  }

  /** Chiave API corrente salvata nelle Impostazioni. */
  fun apiKey(): String? = AppSettingsRepository.torBoxApiKey.value?.trim()?.takeIf { it.isNotBlank() }

  /** Toggle "Usa TorBox Instant Debrid". */
  fun isInstantDebridEnabled(): Boolean = AppSettingsRepository.torBoxInstantDebridEnabled.value

  /** true se TorBox può essere usato (toggle ON + chiave presente). */
  override fun isConfigured(): Boolean = isInstantDebridEnabled() && apiKey() != null

  // ── 1. Verifica account ────────────────────────────────────────────────

  /** `GET /user/me`: verifica la validità della chiave API. */
  suspend fun verifyAccount(): AccountCheck {
    if (!isInstantDebridEnabled()) return AccountCheck.NotConfigured
    val key = apiKey() ?: return AccountCheck.NotConfigured
    return withContext(Dispatchers.IO) {
      try {
        val response = TorBoxApiClient.api.getUserMe()
        when {
          response.success == true && response.data != null -> AccountCheck.Valid(response.data)
          else -> AccountCheck.Invalid(response.error ?: "Chiave API non valida")
        }
      } catch (e: HttpException) {
        val code = e.code()
        AccountCheck.Invalid(
          when (code) {
            401, 403 -> "Chiave API non valida (HTTP $code)"
            else -> "Errore TorBox (HTTP $code)"
          }
        )
      } catch (e: Exception) {
        Log.w(TAG, "verifyAccount fallito: ${e.message}")
        AccountCheck.Invalid("Errore di rete: ${e.message ?: "sconosciuto"}")
      }
    }
  }

  // ── 2. checkcached ─────────────────────────────────────────────────────

  /**
   * `GET /torrents/checkcached?hash=h1,h2&format=object`.
   *
   * Gli hash vengono **suddivisi in batch da massimo [CHECKCACHED_MAX_HASHES] elementi**: TorBox
   * accetta circa 100 hash per richiesta (limite legato alla lunghezza massima della query string),
   * quindi un'unica chiamata con tutti gli hash di piu' addon fallirebbe e azzererebbe TUTTI i
   * risultati. Ogni batch produce una richiesta indipendente e i risultati vengono uniti.
   *
   * Un batch che fallisce **non** contribuisce hash "non in cache": gli hash di quel batch restano
   * semplicemente assenti dalla mappa, cosi' [com.example.data.streaming.TorBoxStreamProvider] li
   * ignora senza dichiararli falsamente non cached.
   */
  override suspend fun checkCached(hashes: List<String>): Map<String, TorBoxCacheEntry> {
    if (hashes.isEmpty() || apiKey() == null) return emptyMap()
    val normalized = hashes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (normalized.isEmpty()) return emptyMap()
    return withContext(Dispatchers.IO) {
      val batches = normalized.distinct().chunked(CHECKCACHED_MAX_HASHES)
      Log.i(
        TAG,
        "checkcached: ${normalized.size} hash richiesti, ${batches.size} batch da max $CHECKCACHED_MAX_HASHES"
      )
      if (batches.size > 1) {
        Log.w(
          TAG,
          "checkcached: lista oltre il limite di $CHECKCACHED_MAX_HASHES hash per richiesta: " +
            "i batch sono stati suddivisi per evitare il fallimento dell'intera verifica"
        )
      }

      val merged = HashMap<String, TorBoxCacheEntry>(normalized.size)
      var okBatches = 0
      batches.forEachIndexed { index, batch ->
        val batchNumber = index + 1
        try {
          val response = TorBoxApiClient.api.checkCached(
            hashes = batch.joinToString(","),
            format = "object"
          )
          if (response.success != true) {
            // Batch non riuscito: nessun hash di questo batch viene dichiarato "non in cache".
            Log.w(
              TAG,
              "checkcached batch $batchNumber/${batches.size} non riuscito (${batch.size} hash): ${response.error}"
            )
            return@forEachIndexed
          }
          val parsed = parseCheckCached(response.data)
          merged.putAll(parsed)
          okBatches++
          Log.d(
            TAG,
            "checkcached batch $batchNumber/${batches.size}: ${batch.size} hash inviati, " +
              "${parsed.count { it.value.cached }} in cache"
          )
        } catch (e: Exception) {
          // Idem: il batch fallito non invalida gli altri e non marca hash come non cached.
          Log.w(
            TAG,
            "checkcached batch $batchNumber/${batches.size} fallito (${batch.size} hash, " +
              "http=${httpCodeOf(e)}): ${e.message}"
          )
        }
      }
      Log.i(
        TAG,
        "checkcached: ${okBatches}/${batches.size} batch riusciti, ${merged.size} hash analizzati, " +
          "${merged.count { it.value.cached }} in cache"
      )
      merged
    }
  }

  /** Codice HTTP estratto da un'eccezione Retrofit/OkHttp, quando disponibile. */
  private fun httpCodeOf(e: Exception): String =
    (e as? HttpException)?.code()?.toString() ?: "n/d"

  /** Normalizza un infoHash (trim + lowercase) come chiave di cache interna. */
  override fun normalizeHash(infoHash: String): String = infoHash.trim().lowercase()

  /**
   * Normalizza la risposta `checkcached` (formato object **o** list).
   *
   * Con `format=object` TorBox restituisce `{ "<hash>": { name, size, hash } }` per gli hash in
   * cache e `{ "<hash>": null }` per quelli non in cache: è la **presenza** dell'oggetto a
   * determinare lo stato, l'API non restituisce alcun campo `cached`.
   */
  internal fun parseCheckCached(data: Any?): Map<String, TorBoxCacheEntry> {
    val out = HashMap<String, TorBoxCacheEntry>()
    when (data) {
      is Map<*, *> -> data.forEach { (key, value) ->
        val hash = key?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@forEach
        when (value) {
          null -> out[hash] = TorBoxCacheEntry(cached = false)
          is Map<*, *> -> {
            // Alcune risposte includono un flag esplicito `cached` dentro l'oggetto:
            // va rispettato, così `{"hash": {"cached": false}}` NON diventa "in cache".
            val explicitCached = coerceBoolean(value["cached"])
            if (explicitCached == false) {
              out[hash] = TorBoxCacheEntry(cached = false)
            } else {
              val torrent = value["torrent"] as? Map<*, *>
              val source = torrent ?: value
              out[hash] = TorBoxCacheEntry(
                cached = true,
                torrentId = ((source["id"] as? Number) ?: (value["torrent_id"] as? Number))?.toLong(),
                files = parseFiles(source["files"])
              )
            }
          }
          is List<*> -> out[hash] = TorBoxCacheEntry(true, null, parseFiles(value))
          else -> out[hash] = TorBoxCacheEntry(cached = value == true)
        }
      }
      is List<*> -> data.forEach { element ->
        if (element is Map<*, *>) {
          val hash = (element["hash"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@forEach
          out[hash] = TorBoxCacheEntry(
            cached = true,
            torrentId = (element["id"] as? Number)?.toLong(),
            files = parseFiles(element["files"])
          )
        }
      }
    }
    return out
  }

  /**
   * Interpreta un flag booleano proveniente dal JSON (`true`/`false`, `"true"`/`"false"`,
   * `0`/`1`). Restituisce `null` se il valore non è interpretabile come booleano.
   */
  private fun coerceBoolean(raw: Any?): Boolean? = when (raw) {
    is Boolean -> raw
    is String -> when (raw.trim().lowercase()) {
      "true" -> true
      "false" -> false
      else -> null
    }
    is Number -> raw.toInt() != 0
    else -> null
  }

  private fun parseFiles(data: Any?): List<TorBoxFile> {
    val list = data as? List<*> ?: return emptyList()
    return list.mapNotNull { raw ->
      when (raw) {
        is Map<*, *> -> {
          val id = (raw["id"] as? Number)?.toInt() ?: return@mapNotNull null
          TorBoxFile(
            id = id,
            name = raw["name"] as? String,
            size = (raw["size"] as? Number)?.toLong()
          )
        }
        is Number -> TorBoxFile(id = raw.toInt())
        else -> null
      }
    }
  }

  // ── 3. createtorrent ───────────────────────────────────────────────────

  /**
   * `POST /torrents/createtorrent`: aggiunge un magnet all'account utente.
   *
   * @param addOnlyIfCached se true TorBox aggiunge il torrent **solo** se già in
   *        cache istantanea (percorso cached-only, modello Nuvio).
   * @return id del torrent oppure null in caso di errore (o non in cache).
   */
  suspend fun createTorrent(magnet: String, addOnlyIfCached: Boolean = false): Long? {
    val key = apiKey() ?: return null
    return withContext(Dispatchers.IO) {
      try {
        val response = TorBoxApiClient.api.createTorrent(
          authorization = "Bearer $key",
          magnet = magnet.toRequestBody("text/plain".toMediaType()),
          addOnlyIfCached = addOnlyIfCached.toString().toRequestBody("text/plain".toMediaType()),
          allowZip = "false".toRequestBody("text/plain".toMediaType())
        )
        val id = response.body()?.data?.resolvedTorrentId()
        if (response.code() != 200 || id == null) {
          Log.w(TAG, "createtorrent fallito: ${response.body()?.error ?: "id assente"}")
          null
        } else {
          Log.i(TAG, "createtorrent ok: id=$id")
          id.toLong()
        }
      } catch (e: Exception) {
        Log.w(TAG, "createtorrent errore: ${e.message}")
        null
      }
    }
  }

  /**
   * Percorso TorBox **cached-only** (modello Nuvio):
   * 1. `createtorrent` con `add_only_if_cached=true` → torrentId (solo se in cache);
   * 2. `mylist?id&bypass_cache=true` → file con i **veri** `file.id` TorBox.
   *
   * @return entry con torrentId + file oppure null (non in cache / errore).
   */
  suspend fun createCachedOnly(magnet: String): TorBoxCacheEntry? {
    val torrentId = createTorrent(magnet, addOnlyIfCached = true) ?: return null
    return TorBoxCacheEntry(cached = true, torrentId = torrentId, files = getTorrentFiles(torrentId))
  }

  /**
   * `GET /torrents/mylist?id=<torrent_id>&bypass_cache=true`: dettagli/files del
   * torrent con bypass della cache lato server (modello Nuvio).
   *
   * @return file del torrent con i reali `file.id` (lista vuota se non trovato).
   */
  suspend fun getTorrentFiles(torrentId: Long): List<TorBoxFile> {
    val key = apiKey() ?: return emptyList()
    return withContext(Dispatchers.IO) {
      try {
        val response = TorBoxApiClient.api.getTorrent(
          authorization = "Bearer $key",
          id = torrentId.toInt(),
          bypassCache = true
        )
        if (response.code() != 200) {
          Log.w(TAG, "mylist(id=$torrentId) errore: ${response.body()?.error}")
          return@withContext emptyList()
        }
        val files = response.body()?.data?.files?.map { dto ->
          TorBoxFile(
            id = dto.id ?: 0,
            name = dto.displayName(),
            size = dto.size
          )
        } ?: emptyList()
        Log.d(TAG, "getTorrentFiles: torrent=$torrentId file=${files.size}")
        files
      } catch (e: Exception) {
        Log.w(TAG, "getTorrentFiles errore: ${e.message}")
        emptyList()
      }
    }
  }

  /** Cerca nell'account il torrent già presente per hash (`torrents/mylist`). */
  suspend fun findTorrentId(infoHash: String): Long? {
    val hash = infoHash.trim().lowercase()
    val key = apiKey() ?: return null
    return withContext(Dispatchers.IO) {
      try {
        val response = TorBoxApiClient.api.getMyList(
          authorization = "Bearer $key",
          hash = hash
        )
        if (response.code() != 200) return@withContext null
        val data = response.body()?.data ?: return@withContext null
        data.firstOrNull { torrent -> torrent.hash?.lowercase()?.contains(hash) == true }?.id?.toLong()
      } catch (e: Exception) {
        Log.w(TAG, "mylist fallito: ${e.message}")
        null
      }
    }
  }

  // ── 4. requestdl ───────────────────────────────────────────────────────

  /**
   * `GET /torrents/requestdl?token=<api_key>&torrent_id=<id>&file_id=<f>&zip=false`
   * @return URL CDN diretto oppure null.
   */
  suspend fun requestDirectLink(torrentId: Long, fileId: Int = -1): String? {
    val key = apiKey() ?: return null
    return withContext(Dispatchers.IO) {
      try {
        val response = TorBoxApiClient.api.requestDownloadLink(
          authorization = "Bearer $key",
          token = key,
          torrentId = torrentId.toInt(),
          fileId = fileId,
          zipLink = false,
          redirect = false,
          appendName = false
        )
        val link = response.body()?.data
        if (response.code() == 200 && !link.isNullOrBlank() && link.startsWith("http")) {
          Log.i(TAG, "requestdl ok: torrent=$torrentId file=$fileId")
          link
        } else {
          Log.w(TAG, "requestdl fallito: ${response.body()?.error ?: response.body()?.data}")
          null
        }
      } catch (e: Exception) {
        Log.w(TAG, "requestdl errore: ${e.message}")
        null
      }
    }
  }

  // ── Risoluzione end-to-end di un infoHash ──────────────────────────────

  /**
   * Risolve un infoHash "grezzo" (proveniente da uno scraper o da uno addon
   * Stremio) in un URL CDN diretto, se il torrent è **in cache istantanea**.
   *
   * @param infoHash hash torrent (40 hex o base32).
   * @param fileIdx indice file dichiarato dall'addon (Stremio `fileIdx`);
   *        se assente viene scelto il file video più grande conosciuto da TorBox.
   * @param season stagione richiesta. Insieme a [episode] vincola lo sblocco al file
   *        dell'episodio: per un torrent di stagione è l'unico modo per non riusare
   *        il link di un altro episodio. Null per film o richieste senza episodio.
   * @param episode episodio richiesto (vedi [season]).
   * @return URL HTTPS del CDN oppure null (non in cache / TorBox non configurato).
   */
  suspend fun resolveInfoHash(
    infoHash: String,
    fileIdx: Int?,
    season: Int? = null,
    episode: Int? = null
  ): String? {
    val hash = infoHash.trim().lowercase()
    if (hash.isEmpty() || !isConfigured()) return null
    val (reqSeason, reqEpisode) = requestedEpisode(season, episode)
    val key = linkCacheKey(hash, reqSeason, reqEpisode, fileIdx)
    linkCache[key]?.let { cached ->
      if (System.currentTimeMillis() - cached.at < LINK_TTL_MS) return cached.url
      linkCache.remove(key)
    }
    // Single-flight: richieste concorrenti dello stesso hash PER LO STESSO EPISODIO
    // (e lo stesso fileIdx) condividono un'unica sequenza checkcached/requestdl.
    // La chiave include l'episodio: due episodi dello stesso torrent non devono
    // condividere il file; include il fileIdx: due card (720p/1080p) dello stesso
    // torrent non devono condividere il link.
    return resolveFlight.run(key) { resolveInternal(hash, fileIdx, reqSeason, reqEpisode) }
  }

  private suspend fun resolveInternal(
    hash: String,
    fileIdx: Int?,
    season: Int?,
    episode: Int?
  ): String? {
    val key = linkCacheKey(hash, season, episode, fileIdx)
    linkCache[key]?.let { cached ->
      if (System.currentTimeMillis() - cached.at < LINK_TTL_MS) return cached.url
      linkCache.remove(key)
    }
    if (apiKey() == null) return null
    val entry = checkCached(listOf(hash))[hash] ?: return null
    if (!entry.cached) {
      Log.d(TAG, "Hash $hash non in cache istantanea: nessuno sblocco")
      return null
    }
    return unlockInternal(hash, fileIdx, entry, season, episode)
  }

  /**
   * Fast-path bingewatching: sblocca il file del nuovo episodio DENTRO lo stesso
   * torrent di stagione gia' in uso per l'episodio corrente, senza interrogare
   * nuovamente gli addon.
   *
   * Percorsi, in ordine:
   *  1. il torrent e' gia' presente nell'account (`mylist`, creato dallo sblocco
   *     dell'episodio precedente) → file reali → match `SxxExx` →
   *     [requestDirectLink] diretto (nessuna ricreazione del torrent);
   *  2. altrimenti [TorBoxResolver.resolve] cached-only con selezione per
   *     stagione/episodio (`filename = null` → solo match per episodio).
   *
   * NON tocca [linkCache]: il link dell'episodio corrente resta associato all'hash,
   * cosi' un rientro sull'episodio precedente continua a usare il proprio link.
   *
   * @return URL CDN del file dell'episodio richiesto, oppure null se il torrent
   *         non contiene l'episodio o lo sblocco fallisce.
   */
  suspend fun resolveSeasonPackEpisode(infoHash: String, season: Int, episode: Int): String? {
    val hash = normalizeHash(infoHash)
    if (hash.isEmpty() || !isConfigured()) return null

    try {
      // 1) Stesso torrent gia' nell'account (creato allo sblocco dell'episodio corrente).
      val torrentId = findTorrentId(hash)
      if (torrentId != null) {
        val files = getTorrentFiles(torrentId)
        if (files.isEmpty()) {
          // Lista file vuota: nessun ulteriore tentativo di rete, fast-path chiuso.
          Log.w(TAG, "Season pack: lista file vuota per ${hash.take(8)}..., nessun ulteriore tentativo")
          return null
        }
        val file = findEpisodeFile(files, season, episode)
        if (file == null) {
          // Episodio non presente nel torrent: il fallback cached-only ripartirebbe
          // dallo stesso identico torrent, quindi si chiude subito senza rete a vuoto.
          Log.w(TAG, "Season pack: S${season}E${episode} non trovato nel torrent ${hash.take(8)}..., nessun ulteriore tentativo")
          return null
        }
        val url = requestDirectLink(torrentId, file.id)
        if (!url.isNullOrBlank()) {
          Log.i(TAG, "Season pack: stesso torrent (${hash.take(8)}...) -> ${file.name}")
          return url
        }
        Log.w(TAG, "Season pack: requestDirectLink fallito per ${file.name}, nessun ulteriore tentativo")
        return null
      }

      // 2) Fallback cached-only con selezione per episodio (ignora comunque linkCache):
      //    usato solo quando il torrent non e' ancora presente nell'account.
      setRequestMetadata(season, episode)
      return when (val result = TorBoxResolver.resolve(hash, filename = null, season = season, episode = episode)) {
        is TorBoxResolver.ResolveResult.Success -> {
          Log.i(TAG, "Season pack: risolto '${result.filename}' per S${season}E${episode}")
          result.url
        }
        else -> null
      }
    } catch (e: CancellationException) {
      // Timeout del caller (fast-path) o cambio episodio: la cancellazione strutturata
      // NON va soppressa. Ingoiarla lasciava la coroutine chiamante "viva" dopo la
      // cancellazione e poteva farla proseguire su una richiesta ormai obsoleta.
      Log.w(TAG, "Season pack: cancellato (timeout fast-path) per ${hash.take(8)}... S${season}E${episode}")
      throw e
    } catch (e: Exception) {
      // Errore di rete/parsing: null immediato, nessuna chiamata a vuoto aggiuntiva.
      Log.w(TAG, "Season pack: errore per ${hash.take(8)}... S${season}E${episode}: ${e.message}")
      return null
    }
  }

  /**
   * Sblocca un hash **già verificato in cache** (chiamato dopo un
   * [checkCached] multi-hash) senza ripetere la verifica, con il percorso
   * cached-only ([createCachedOnly]: `createtorrent` `add_only_if_cached=true` +
   * `mylist` con bypass cache per i veri `file.id`).
   *
   * Le richieste concorrenti sullo stesso hash **e sullo stesso episodio** condividono
   * un'unica sequenza `createtorrent`/`requestdl` (single-flight, stesso identico URL
   * finale). Episodi diversi dello stesso torrent di stagione hanno chiavi distinte.
   *
   * @param entry voce di cache già letta per questo hash.
   * @param season stagione richiesta (opzionale, per selezione file).
   * @param episode episodio richiesto (opzionale, per selezione file).
   * @return URL CDN diretto oppure null se lo sblocco fallisce.
   */
  override suspend fun unlockCached(infoHash: String, fileIdx: Int?, entry: TorBoxCacheEntry, season: Int?, episode: Int?): String? {
    val hash = normalizeHash(infoHash)
    if (hash.isEmpty() || !isConfigured() || !entry.cached) return null
    val (reqSeason, reqEpisode) = requestedEpisode(season, episode)
    val key = linkCacheKey(hash, reqSeason, reqEpisode, fileIdx)
    linkCache[key]?.let { cached ->
      if (System.currentTimeMillis() - cached.at < LINK_TTL_MS) return cached.url
      linkCache.remove(key)
    }
    return resolveFlight.run(key) { unlockInternal(hash, fileIdx, entry, reqSeason, reqEpisode) }
  }

  /**
   * Seleziona il file corretto usando i metadati disponibili.
   *
   * 1. Se [fileIdx] è valido e punta a un file video reale, usa quello.
   * 2. Se season/episode sono disponibili, cerca match case-insensitive
   *    (pattern: S01E01, s01e01, 1x01, 01x01).
   * 3. Se c'è un unico video valido, usalo.
   * 4. Fallback deterministico: video con score più alto (size).
   */
  private fun selectFileId(fileIdx: Int?, files: List<TorBoxFile>): Int {
    val videoFiles = files.filter { it.videoScore >= 0 }
    if (videoFiles.isEmpty()) {
      Log.w(TAG, "selectFileId: nessun file video valido")
      return -1
    }

    if (fileIdx != null && fileIdx in files.indices) {
      val candidate = files[fileIdx]
      if (candidate.videoScore >= 0) {
        Log.d(TAG, "selectFileId: fileIdx=$fileIdx valido -> ${candidate.name}")
        return candidate.id
      }
    }

    val reqSeason = currentSeasonEpisode.get()?.first
    val reqEpisode = currentSeasonEpisode.get()?.second
    if (reqSeason != null && reqEpisode != null) {
      val matched = findEpisodeFile(videoFiles, reqSeason, reqEpisode)
      if (matched != null) {
        Log.d(TAG, "selectFileId: match S${reqSeason}E${reqEpisode} -> ${matched.name}")
        return matched.id
      }
      Log.d(TAG, "selectFileId: nessun match per S${reqSeason}E${reqEpisode}, proseguo con fallback")
    }

    if (videoFiles.size == 1) {
      val only = videoFiles.first()
      Log.d(TAG, "selectFileId: unico video -> ${only.name}")
      return only.id
    }

    val fallback = videoFiles.maxByOrNull { it.videoScore }
    Log.d(TAG, "selectFileId: fallback deterministico (size) -> ${fallback?.name}")
    return fallback?.id ?: -1
  }

  /**
   * Cerca all'interno del torrent il file del nuovo episodio (match case-insensitive
   * sui pattern `SxxExx`, `NxN`, ecc. gia' usati da [buildSeasonEpisodePatterns]).
   *
   * Se piu' file corrispondono (es. qualita' multiple per lo stesso episodio),
   * preferisce quello con piu' token della firma della release corrente
   * ([preferTokens], es. qualità/codec/gruppo), cosi' il prossimo episodio resta
   * sulla stessa qualita' del precedente.
   *
   * @return il file dell'episodio richiesto, oppure null se il torrent non lo contiene.
   */
  internal fun findEpisodeFile(
    files: List<TorBoxFile>,
    season: Int,
    episode: Int,
    preferTokens: List<String> = emptyList()
  ): TorBoxFile? {
    val patterns = buildSeasonEpisodePatterns(season, episode)
    val matches = files.filter { file ->
      val name = file.name?.lowercase() ?: return@filter false
      patterns.any { pattern -> name.contains(pattern) }
    }
    if (matches.isEmpty()) return null
    val tokens = preferTokens.map { it.trim().lowercase() }.filter { it.isNotBlank() }
    if (matches.size == 1 || tokens.isEmpty()) return matches.first()
    return matches.maxByOrNull { file ->
      val name = file.name?.lowercase() ?: ""
      tokens.count { token -> name.contains(token) }
    } ?: matches.first()
  }

  private fun buildSeasonEpisodePatterns(season: Int, episode: Int): List<String> {
    val s = season.toString()
    val e = episode.toString()
    return listOf(
      "s${s.padStart(2, '0')}e${e.padStart(2, '0')}",
      "s${s.padStart(2, '0')}e$e",
      "s$s e$e",
      "${s}x${e.padStart(2, '0')}",
      "${s}x$e",
      "${s}${e.padStart(2, '0')}",
      "[$s.$e]",
      "${s}_$e",
      "Season.$s.Episode.$e",
      "Episode.$e",
    )
  }

  private val currentSeasonEpisode = object : ThreadLocal<Pair<Int, Int>?>() {
    override fun initialValue(): Pair<Int, Int>? = null
  }

  /**
   * Metadata legacy di stagione/episodio per le richieste di sblocco.
   *
   * Resta come **fallback** per i chiamanti che non passano stagione/episodio a
   * [resolveInfoHash]/[unlockCached]. È un ThreadLocal: vale solo se letto sullo
   * stesso thread che lo ha impostato, quindi NON è una fonte affidabile per la
   * selezione del file (vedi [requestedEpisode]).
   */
  fun setRequestMetadata(season: Int?, episode: Int?) {
    if (season != null && episode != null) {
      currentSeasonEpisode.set(season to episode)
    } else {
      currentSeasonEpisode.remove()
    }
  }

  /**
   * Sequenza di sblocco cached-only usando TorBoxResolver (modello Nuvio).
   * Da usare dentro il single-flight.
   *
   * Il flusso Nuvio:
   * 1. createTorrent(magnet, add_only_if_cached=true, allow_zip=false) → torrentId
   * 2. getTorrent(id, bypass_cache=true) → files con REAL file.id
   * 3. fileSelector.selectFile(files, filename, season, episode) → file
   * 4. requestDownloadLink(token, torrentId, fileId, zip=false, redirect=false) → URL CDN
   *
   * [season]/[episode] arrivano ESPLICITAMENTE dal chiamante: leggerli da un
   * ThreadLocal qui non è affidabile, perché questa funzione gira nella coroutine
   * del single-flight ([AsyncSingleFlight] usa un proprio scope su `Dispatchers.IO`)
   * e non necessariamente sul thread che ha impostato il metadata. Con
   * stagione/episodio null il selector sceglierebbe il file video più grande del
   * torrent di stagione, cioè un episodio arbitrario.
   */
  private suspend fun unlockInternal(
    hash: String,
    fileIdx: Int?,
    entry: TorBoxCacheEntry,
    season: Int?,
    episode: Int?
  ): String? {
    val key = linkCacheKey(hash, season, episode, fileIdx)
    linkCache[key]?.let { cached ->
      if (System.currentTimeMillis() - cached.at < LINK_TTL_MS) return cached.url
      linkCache.remove(key)
    }
    if (apiKey() == null) return null

    // Con un episodio richiesto la selezione del file DEVE essere guidata da
    // stagione/episodio. Il nome del primo file del torrent (hint usato per i film
    // e per i torrent a file singolo) non è l'episodio richiesto: passarlo come
    // `filename` faceva vincere quel file prima del match SxxExx.
    // Il fileIdx viene passato al resolver ma è usato SOLO senza episodio (film):
    // per i season pack l'indice dell'addon non coincide con l'ordinamento file di
    // TorBox e prendere l'indice selezionava l'episodio sbagliato.
    val hasEpisode = season != null && episode != null
    val filename = if (hasEpisode) null else entry.files.firstOrNull()?.name
    val result = TorBoxResolver.resolve(
      infoHash = hash,
      filename = filename,
      season = season,
      episode = episode,
      sources = null,
      fileIdx = fileIdx
    )

    return when (result) {
      is TorBoxResolver.ResolveResult.Success -> {
        linkCache[key] = CachedLink(System.currentTimeMillis(), result.url)
        result.url
      }
      else -> null
    }
  }

  private fun toLongOrNull(data: Any?): Long? = when (data) {
    is Number -> data.toLong()
    is String -> data.trim().toLongOrNull()
    else -> null
  }

  /** Estrae l'id torrent dalla risposta `createtorrent`: numero, stringa oppure oggetto. */
  private fun extractTorrentId(data: Any?): Long? = when (data) {
    is Map<*, *> -> toLongOrNull(data["torrent_id"]) ?: toLongOrNull(data["id"])
    else -> toLongOrNull(data)
  }

  /** Svuota la cache locale dei link (test / cambio chiave). */
  fun clearCache() {
    linkCache.clear()
  }

  /**
   * Rimuove i link memorizzati per `infoHash` (+ episodio se noto), per QUALSIASI fileIdx.
   *
   * Chiamato quando un link è appena fallito in riproduzione: senza questa
   * invalidazione la risoluzione successiva riproporrebbe lo stesso URL scaduto
   * per tutto il TTL di 15 minuti. Le altre voci restano intatte. La chiave ora
   * include il fileIdx (720p/1080p dello stesso torrent sono voci distinte): la
   * rimozione a prefisso invalida tutte le varianti di file dello stesso torrent.
   */
  fun invalidateResolvedLink(infoHash: String, season: Int?, episode: Int?) {
    val hash = normalizeHash(infoHash)
    if (hash.isEmpty()) return
    val (reqSeason, reqEpisode) = requestedEpisode(season, episode)
    if (reqSeason != null && reqSeason > 0 && reqEpisode != null && reqEpisode > 0) {
      // Episodio noto: invalida solo le varianti fileIdx di QUESTO episodio.
      val episodeKey = "$hash:s${reqSeason}e${reqEpisode}"
      linkCache.keys.removeAll { it == episodeKey || it.startsWith("$episodeKey:") }
    } else {
      // Senza episodio (film): invalida solo le voci senza episodio dello stesso
      // torrent (esatte o per fileIdx); le chiavi degli episodi restano intatte.
      linkCache.keys.removeAll { it == hash || it.startsWith("$hash:f") }
    }
  }
}
