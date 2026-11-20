package com.example.data.realdebrid

import android.util.Log
import com.example.data.debrid.DebridCacheEntry
import com.example.data.debrid.DebridCacheGateway
import com.example.data.debrid.DebridEpisodeMatcher
import com.example.data.debrid.DebridFile
import com.example.data.prefs.AppSettingsRepository
import com.example.data.util.AsyncSingleFlight
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Repository operativo su **Real-Debrid**: verifica account, cache istantanea
 * degli infoHash e sblocco del link CDN diretto per ExoPlayer.
 *
 * Flusso "Instant Debrid" per un infoHash (modello Real-Debrid):
 *  1. `GET /torrents/instantAvailability/{hash}` → il torrent è disponibile
 *     istantaneamente? (`rd` non vuoto);
 *  2. se sì: `POST /torrents/addMagnet` → torrent id;
 *  3. `GET /torrents/info/{id}` → file reali (`files[].id/path`);
 *  4. selezione del file (episodio richiesto, `fileIdx` o video maggiore) e
 *     `POST /torrents/selectFiles/{id}` con l'id scelto;
 *  5. `GET /torrents/info/{id}` → link real-debrid (`links[]`);
 *  6. `POST /unrestrict/link` → URL CDN diretto passato ad ExoPlayer.
 *
 * I link risolti vengono memorizzati in memoria (TTL 15 minuti) e le risoluzioni
 * concorrenti dello stesso hash sono deduplicate "single-flight". Struttura e
 * semantica sono identiche a [com.example.data.torbox.TorBoxRepository] per
 * rendere i due servizi accostabili e intercambiabili.
 */
object RealDebridRepository : DebridCacheGateway {

  private const val TAG = "RealDebridRepository"
  private const val LINK_TTL_MS = 15 * 60 * 1000L

  /** Esito della verifica dell'account (`GET /user`). */
  sealed interface AccountCheck {
    /** Nessuna chiave configurata o Instant Debrid disabilitato. */
    object NotConfigured : AccountCheck

    /** Chiave valida: contiene i dati dell'account. */
    data class Valid(val user: RealDebridUserDto) : AccountCheck

    /** Chiave non valida o errore di rete. */
    data class Invalid(val message: String) : AccountCheck
  }

  private data class CachedLink(val at: Long, val url: String)

  private val linkCache = ConcurrentHashMap<String, CachedLink>()
  private val resolveFlight = AsyncSingleFlight<String?>()

  /** Chiave API corrente salvata nelle Impostazioni. */
  fun apiKey(): String? =
    AppSettingsRepository.realDebridApiKey.value?.trim()?.takeIf { it.isNotBlank() }

  /** Toggle "Usa Real-Debrid Instant Debrid". */
  fun isInstantDebridEnabled(): Boolean = AppSettingsRepository.realDebridInstantDebridEnabled.value

  /** true se Real-Debrid può essere usato (toggle ON + chiave presente). */
  override fun isConfigured(): Boolean = isInstantDebridEnabled() && apiKey() != null

  /** Normalizza un infoHash (trim + lowercase) come chiave di cache interna. */
  fun normalizeHash(infoHash: String): String = infoHash.trim().lowercase()

  /** Chiave della cache dei link: hash + episodio (gli episodi di uno stesso torrent di stagione hanno link diversi). */
  internal fun linkCacheKey(infoHash: String, season: Int?, episode: Int?): String {
    val hash = normalizeHash(infoHash)
    return if (season != null && season > 0 && episode != null && episode > 0) {
      "$hash:s${season}e$episode"
    } else {
      hash
    }
  }

  // ── 1. Verifica account ────────────────────────────────────────────────

  /** `GET /user`: verifica la validità della chiave API. */
  suspend fun verifyAccount(): AccountCheck {
    if (!isInstantDebridEnabled()) return AccountCheck.NotConfigured
    val key = apiKey() ?: return AccountCheck.NotConfigured
    return withContext(Dispatchers.IO) {
      try {
        val response = RealDebridApiClient.api.user()
        val user = response.body()
        if (response.isSuccessful && user != null) {
          AccountCheck.Valid(user)
        } else {
          AccountCheck.Invalid(invalidAccountMessage(response.code()))
        }
      } catch (e: HttpException) {
        AccountCheck.Invalid(invalidAccountMessage(e.code()))
      } catch (e: Exception) {
        Log.w(TAG, "verifyAccount fallito: ${e.message}")
        AccountCheck.Invalid("Errore di rete: ${e.message ?: "sconosciuto"}")
      }
    }
  }

  private fun invalidAccountMessage(code: Int): String = when (code) {
    401, 403 -> "Chiave API non valida (HTTP $code)"
    else -> "Errore Real-Debrid (HTTP $code)"
  }

  // ── 2. instantAvailability ─────────────────────────────────────────────

  /**
   * Verifica la cache istantanea Real-Debrid per gli hash richiesti.
   *
   * L'endpoint `instantAvailability` è per-hash (nessun batching possibile):
   * gli hash vengono interrogati con un limite di concorrenza per non superare
   * i rate limit del servizio. Un hash il cui controllo fallisce NON viene
   * dichiarato "non in cache": resta assente dalla mappa.
   */
  override suspend fun checkCached(hashes: List<String>): Map<String, DebridCacheEntry> {
    if (hashes.isEmpty() || apiKey() == null) return emptyMap()
    val normalized = hashes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    if (normalized.isEmpty()) return emptyMap()
    return withContext(Dispatchers.IO) {
      Log.i(TAG, "instantAvailability: ${normalized.size} hash richiesti")
      val merged = HashMap<String, DebridCacheEntry>(normalized.size)
      normalized.forEach { hash ->
        try {
          val availability = RealDebridApiClient.api.instantAvailability(hash)
          val entry = RealDebridParsing.parseEntry(availability[hash])
          merged[hash] = entry
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "instantAvailability $hash fallito: ${e.message}")
        }
      }
      Log.i(TAG, "instantAvailability: ${merged.size}/${normalized.size} verificati, " +
        "${merged.count { it.value.cached }} in cache")
      merged
    }
  }

  // ── 3. Sblocco ─────────────────────────────────────────────────────────

  /**
   * Sblocca un hash **già verificato in cache** (`addMagnet` + `selectFiles` +
   * `unrestrict/link`), con selezione del file per stagione/episodio.
   *
   * Le richieste concorrenti sullo stesso hash per lo stesso episodio condividono
   * un'unica sequenza di sblocco (single-flight, stesso identico URL finale).
   *
   * @return URL CDN diretto oppure null se lo sblocco fallisce.
   */
  suspend fun unlockCached(
    infoHash: String,
    fileIdx: Int?,
    entry: DebridCacheEntry,
    season: Int? = null,
    episode: Int? = null
  ): String? {
    val hash = normalizeHash(infoHash)
    if (hash.isEmpty() || !isConfigured() || !entry.cached) return null
    val key = linkCacheKey(hash, season, episode)
    return resolveFlight.run(key) { unlockInternal(hash, fileIdx, season, episode) }
  }

  private suspend fun unlockInternal(hash: String, fileIdx: Int?, season: Int?, episode: Int?): String? {
    val key = linkCacheKey(hash, season, episode)
    linkCache[key]?.let { cached ->
      if (System.currentTimeMillis() - cached.at < LINK_TTL_MS) return cached.url
      linkCache.remove(key)
    }
    if (apiKey() == null) return null
    return withContext(Dispatchers.IO) {
      try {
        // 1) addMagnet: il torrent è già in cache, la creazione è istantanea.
        val added = RealDebridApiClient.api.addMagnet(RealDebridParsing.magnetForHash(hash))
        val torrentId = added.id?.trim()?.takeIf { it.isNotBlank() }
        if (torrentId == null) {
          Log.w(TAG, "addMagnet: id assente per ${hash.take(8)}...")
          return@withContext null
        }

        // 2) info: file reali del torrent con i veri id Real-Debrid.
        val info = RealDebridApiClient.api.torrentInfo(torrentId)
        val files = info.files.orEmpty()
        if (files.isEmpty()) {
          Log.w(TAG, "torrentInfo: nessun file per ${hash.take(8)}...")
          return@withContext null
        }

        // 3) Selezione del file: episodio richiesto → fileIdx → video maggiore.
        val chosen = selectFile(files, fileIdx, season, episode)
        if (chosen == null) {
          Log.w(TAG, "selectFiles: nessun file video valido per ${hash.take(8)}...")
          return@withContext null
        }
        val selectResponse = RealDebridApiClient.api.selectFiles(torrentId, chosen.id.toString())
        if (!selectResponse.isSuccessful) {
          Log.w(TAG, "selectFiles fallito (HTTP ${selectResponse.code()})")
          return@withContext null
        }

        // 4) info: i link appaiono dopo la selezione (uno per file selezionato).
        val updated = RealDebridApiClient.api.torrentInfo(torrentId)
        val restrictedLink = updated.links.orEmpty().firstOrNull()
        if (restrictedLink.isNullOrBlank()) {
          Log.w(TAG, "torrentInfo: nessun link dopo la selezione per ${hash.take(8)}...")
          return@withContext null
        }

        // 5) unrestrict/link: URL CDN diretto riproducibile.
        val unrestricted = RealDebridApiClient.api.unrestrictLink(restrictedLink)
        val download = unrestricted.download?.trim()?.takeIf { it.startsWith("http") }
        if (download == null) {
          Log.w(TAG, "unrestrict/link: URL download assente per ${hash.take(8)}...")
          return@withContext null
        }
        Log.i(TAG, "Sblocco ok (${hash.take(8)}...): file ${chosen.path ?: chosen.id}")
        linkCache[key] = CachedLink(System.currentTimeMillis(), download)
        download
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Sblocco errore (${hash.take(8)}...): ${e.message}")
        null
      }
    }
  }

  /**
   * Selezione del file dentro il torrent: match SxxExx per gli episodi
   * (stessi pattern di TorBox), altrimenti `fileIdx` dichiarato dalla sorgente,
   * altrimenti il video con dimensione maggiore.
   */
  private fun selectFile(
    files: List<RealDebridFileDto>,
    fileIdx: Int?,
    season: Int?,
    episode: Int?
  ): RealDebridFileDto? {
    val debridFiles = files.map { it.toDebridFile() }
    if (season != null && season > 0 && episode != null && episode > 0) {
      val matched = DebridEpisodeMatcher.findEpisodeFile(debridFiles, season, episode)
      if (matched != null) {
        return files.firstOrNull { it.toDebridFile().name == matched.name }
      }
      Log.d(TAG, "Nessun match S${season}E${episode}, fallback su fileIdx/size")
    }
    if (fileIdx != null && fileIdx in files.indices) {
      val candidate = files[fileIdx]
      if (candidate.toDebridFile().videoScore >= 0) return candidate
    }
    val best = DebridEpisodeMatcher.selectBestVideo(debridFiles) ?: return null
    return files.firstOrNull { it.toDebridFile().name == best.name && it.toDebridFile().size == best.size }
      ?: files.getOrNull(debridFiles.indexOf(best))
  }

  private fun RealDebridFileDto.toDebridFile(): DebridFile = DebridFile(
    name = path?.trimStart('/')?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() },
    size = resolvedSize
  )

  // ── 4. Risoluzione end-to-end di un infoHash ───────────────────────────

  /**
   * Risolve un infoHash "grezzo" (addon Stremio o scraper) in un URL CDN
   * diretto, se il torrent è in cache istantanea Real-Debrid.
   *
   * @return URL HTTPS del CDN oppure null (non in cache / servizio non configurato).
   */
  suspend fun resolveInfoHash(
    infoHash: String,
    fileIdx: Int?,
    season: Int? = null,
    episode: Int? = null
  ): String? {
    val hash = normalizeHash(infoHash)
    if (hash.isEmpty() || !isConfigured()) return null
    val key = linkCacheKey(hash, season, episode)
    linkCache[key]?.let { cached ->
      if (System.currentTimeMillis() - cached.at < LINK_TTL_MS) return cached.url
      linkCache.remove(key)
    }
    // Single-flight: richieste concorrenti dello stesso hash PER LO STESSO
    // EPISODIO condividono un'unica sequenza di sblocco. DENTRO il flight non
    // va mai chiamato [unlockCached] (stessa chiave di flight: deadlock):
    // qui si passa direttamente a [unlockInternal].
    return resolveFlight.run(key) {
      val entry = checkCached(listOf(hash))[hash] ?: return@run null
      if (!entry.cached) {
        Log.d(TAG, "Hash $hash non in cache istantanea: nessuno sblocco")
        return@run null
      }
      unlockInternal(hash, fileIdx, season, episode)
    }
  }

  /**
   * Sblocco del file dell'episodio richiesto dentro un torrent di stagione
   * (fast-path bingewatching). Real-Debrid non richiede un percorso dedicato:
   * la sequenza standard (`instantAvailability` + sblocco) è già completa,
   * con selezione per stagione/episodio.
   */
  suspend fun resolveSeasonPackEpisode(infoHash: String, season: Int, episode: Int): String? =
    try {
      resolveInfoHash(infoHash, fileIdx = null, season = season, episode = episode)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Season pack errore ${hashPrefix(infoHash)} S${season}E${episode}: ${e.message}")
      null
    }

  private fun hashPrefix(infoHash: String): String = "${normalizeHash(infoHash).take(8)}..."

  /** Svuota la cache locale dei link (test / cambio chiave). */
  fun clearCache() {
    linkCache.clear()
  }

  /**
   * Rimuove il link memorizzato per `infoHash` + episodio: chiamato quando un
   * link appena fallito non deve essere riproposto fino allo scadere del TTL.
   */
  fun invalidateResolvedLink(infoHash: String, season: Int?, episode: Int?) {
    val hash = normalizeHash(infoHash)
    if (hash.isEmpty()) return
    linkCache.remove(linkCacheKey(hash, season, episode))
  }
}
