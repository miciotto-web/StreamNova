package com.example.data.premiumize

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Repository operativo su **Premiumize**: verifica account, cache istantanea
 * degli infoHash e sblocco del link di streaming diretto per ExoPlayer.
 *
 * Flusso "Instant Debrid" per un infoHash (modello Premiumize):
 *  1. `POST /cache/check` con i magnet → torrent in cache istantanea?
 *     (gli hash vengono convertiti in magnet `magnet:?xt=urn:btih:...`, il
 *     formato accettato dall'endpoint);
 *  2. se sì: `POST /transfer/create` con lo stesso magnet → transfer id
 *     (per un torrent in cache la creazione è istantanea, nessun download);
 *  3. `GET /item/details?id=<transfer_id>` → file del container con i veri
 *     `stream_link` HTTPS (breve polling: la lista file può impiegare qualche
 *     istante anche per un torrent cached);
 *  4. selezione del file (episodio richiesto, `fileIdx` o video maggiore) →
 *     `stream_link` diretto passato ad ExoPlayer.
 *
 * I link risolti vengono memorizzati in memoria (TTL 15 minuti) e le risoluzioni
 * concorrenti dello stesso hash sono deduplicate "single-flight". Struttura e
 * semantica sono identiche a [com.example.data.torbox.TorBoxRepository] per
 * rendere i servizi accostabili e intercambiabili.
 */
object PremiumizeRepository : DebridCacheGateway {

  private const val TAG = "PremiumizeRepository"
  private const val LINK_TTL_MS = 15 * 60 * 1000L

  /** Attesa massima della lista file di un transfer in cache (Premiumize la rende disponibile in pochi istanti). */
  private const val ITEM_DETAILS_POLL_TIMEOUT_MS = 8_000L
  private const val ITEM_DETAILS_POLL_INTERVAL_MS = 600L

  /** Esito della verifica dell'account (`GET /account/info`). */
  sealed interface AccountCheck {
    /** Nessuna chiave configurata o Instant Debrid disabilitato. */
    object NotConfigured : AccountCheck

    /** Chiave valida: contiene i dati dell'account. */
    data class Valid(val account: PremiumizeAccountInfoDto) : AccountCheck

    /** Chiave non valida o errore di rete. */
    data class Invalid(val message: String) : AccountCheck
  }

  private data class CachedLink(val at: Long, val url: String)

  private val linkCache = ConcurrentHashMap<String, CachedLink>()
  private val resolveFlight = AsyncSingleFlight<String?>()

  /** Chiave API corrente salvata nelle Impostazioni. */
  fun apiKey(): String? =
    AppSettingsRepository.premiumizeApiKey.value?.trim()?.takeIf { it.isNotBlank() }

  /** Toggle "Usa Premiumize Instant Debrid". */
  fun isInstantDebridEnabled(): Boolean =
    AppSettingsRepository.premiumizeInstantDebridEnabled.value

  /** true se Premiumize può essere usato (toggle ON + chiave presente). */
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

  /** Magnet per un infoHash: formato accettato da `cache/check` e `transfer/create`. */
  internal fun magnetForHash(infoHash: String): String =
    "magnet:?xt=urn:btih:${normalizeHash(infoHash)}"

  // ── 1. Verifica account ────────────────────────────────────────────────

  /** `GET /account/info`: verifica la validità della chiave API. */
  suspend fun verifyAccount(): AccountCheck {
    if (!isInstantDebridEnabled()) return AccountCheck.NotConfigured
    val key = apiKey() ?: return AccountCheck.NotConfigured
    return withContext(Dispatchers.IO) {
      try {
        val account = PremiumizeApiClient.api.accountInfo()
        if (account.status == "success") {
          AccountCheck.Valid(account)
        } else {
          AccountCheck.Invalid(account.message ?: "Chiave API non valida")
        }
      } catch (e: HttpException) {
        val code = e.code()
        AccountCheck.Invalid(
          when (code) {
            401, 403 -> "Chiave API non valida (HTTP $code)"
            else -> "Errore Premiumize (HTTP $code)"
          }
        )
      } catch (e: Exception) {
        Log.w(TAG, "verifyAccount fallito: ${e.message}")
        AccountCheck.Invalid("Errore di rete: ${e.message ?: "sconosciuto"}")
      }
    }
  }

  // ── 2. cache/check ─────────────────────────────────────────────────────

  /**
   * `POST /cache/check` con `items[]` = magnet.
   *
   * La risposta ordina gli esiti booleani come gli elementi inviati: un elemento
   * con `response[i] = true` è disponibile in cache istantanea. Un errore dell'
   * intera richiesta non dichiara alcun hash "non in cache": la mappa resta
   * semplicemente vuota.
   */
  override suspend fun checkCached(hashes: List<String>): Map<String, DebridCacheEntry> {
    if (hashes.isEmpty() || apiKey() == null) return emptyMap()
    val normalized = hashes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    if (normalized.isEmpty()) return emptyMap()
    return withContext(Dispatchers.IO) {
      try {
        val response = PremiumizeApiClient.api.cacheCheck(normalized.map { magnetForHash(it) })
        if (response.status != "success") {
          Log.w(TAG, "cache/check non riuscito: ${response.message}")
          return@withContext emptyMap()
        }
        val booleans = response.response.orEmpty()
        val merged = HashMap<String, DebridCacheEntry>(normalized.size)
        normalized.forEachIndexed { index, hash ->
          val cached = booleans.getOrNull(index) == true
          if (cached) {
            // Lo stato file di Premiumize è noto solo dopo il transfer:
            // qui l'entry segnala solo la disponibilità istantanea.
            merged[hash] = DebridCacheEntry(cached = true, files = emptyList())
          }
        }
        Log.i(TAG, "cache/check: ${normalized.size} hash, ${merged.size} in cache")
        merged
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "cache/check errore: ${e.message}")
        emptyMap()
      }
    }
  }

  // ── 3. Sblocco (transfer + item/details) ───────────────────────────────

  /**
   * Sblocca un hash **già verificato in cache** (`transfer/create` +
   * `item/details`), con selezione del file per stagione/episodio.
   *
   * Le richieste concorrenti sullo stesso hash per lo stesso episodio condividono
   * un'unica sequenza di sblocco (single-flight, stesso identico URL finale).
   *
   * @return URL di streaming diretto oppure null se lo sblocco fallisce.
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
        // 1) transfer/create: per un torrent cached la creazione è immediata.
        val created = PremiumizeApiClient.api.transferCreate(magnetForHash(hash))
        if (created.status != "success") {
          Log.w(TAG, "transfer/create non riuscito: ${created.message}")
          return@withContext null
        }
        val transferId = created.id?.trim()?.takeIf { it.isNotBlank() }
        if (transferId == null) {
          Log.w(TAG, "transfer/create: id assente per ${hash.take(8)}...")
          return@withContext null
        }

        // 2) item/details con breve polling: la lista file può impiegare
        //    qualche istante anche per un transfer in cache.
        val files = pollItemFiles(transferId)
        if (files.isEmpty()) {
          Log.w(TAG, "item/details: nessun file per ${hash.take(8)}...")
          return@withContext null
        }

        // 3) Selezione del file: episodio richiesto → fileIdx → video maggiore.
        val chosen = selectFile(files, fileIdx, season, episode)
        if (chosen == null) {
          Log.w(TAG, "Nessun file video valido per ${hash.take(8)}...")
          return@withContext null
        }
        val url = chosen.streamLink?.trim()?.takeIf { it.startsWith("http") }
          ?: chosen.link?.trim()?.takeIf { it.startsWith("http") }
        if (url == null) {
          Log.w(TAG, "File scelto senza link di streaming per ${hash.take(8)}...")
          return@withContext null
        }
        Log.i(TAG, "Sblocco ok (${hash.take(8)}...): file ${chosen.name ?: chosen.id}")
        linkCache[key] = CachedLink(System.currentTimeMillis(), url)
        url
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Sblocco errore (${hash.take(8)}...): ${e.message}")
        null
      }
    }
  }

  /** Interroga `item/details` fino alla comparsa dei file o fino al timeout. */
  private suspend fun pollItemFiles(transferId: String): List<PremiumizeItemFileDto> {
    val deadline = System.currentTimeMillis() + ITEM_DETAILS_POLL_TIMEOUT_MS
    while (true) {
      val details = PremiumizeApiClient.api.itemDetails(transferId)
      if (details.status == "success") {
        val files = details.files.orEmpty()
        if (files.isNotEmpty()) return files
      }
      if (System.currentTimeMillis() >= deadline) return emptyList()
      delay(ITEM_DETAILS_POLL_INTERVAL_MS)
    }
  }

  /**
   * Selezione del file dentro il container: match SxxExx per gli episodi
   * (stessi pattern di TorBox), altrimenti `fileIdx` dichiarato dalla sorgente,
   * altrimenti il video con dimensione maggiore.
   */
  private fun selectFile(
    files: List<PremiumizeItemFileDto>,
    fileIdx: Int?,
    season: Int?,
    episode: Int?
  ): PremiumizeItemFileDto? {
    val debridFiles = files.map { it.toDebridFile() }
    if (season != null && season > 0 && episode != null && episode > 0) {
      val matched = DebridEpisodeMatcher.findEpisodeFile(debridFiles, season, episode)
      if (matched != null) {
        val index = debridFiles.indexOf(matched)
        return files.getOrNull(index)
      }
      Log.d(TAG, "Nessun match S${season}E${episode}, fallback su fileIdx/size")
    }
    if (fileIdx != null && fileIdx in files.indices) {
      val candidate = files[fileIdx]
      if (candidate.toDebridFile().videoScore >= 0) return candidate
    }
    val best = DebridEpisodeMatcher.selectBestVideo(debridFiles) ?: return null
    return files.getOrNull(debridFiles.indexOf(best))
  }

  private fun PremiumizeItemFileDto.toDebridFile(): DebridFile = DebridFile(
    name = name?.trim()?.takeIf { it.isNotBlank() },
    size = size
  )

  // ── 4. Risoluzione end-to-end di un infoHash ───────────────────────────

  /**
   * Risolve un infoHash "grezzo" (addon Stremio o scraper) in un URL di
   * streaming diretto, se il torrent è in cache istantanea Premiumize.
   *
   * @return URL HTTPS diretto oppure null (non in cache / servizio non configurato).
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
   * (fast-path bingewatching). Premiumize non richiede un percorso dedicato:
   * la sequenza standard (`cache/check` + transfer + `item/details`) è già
   * completa, con selezione per stagione/episodio.
   */
  suspend fun resolveSeasonPackEpisode(infoHash: String, season: Int, episode: Int): String? =
    try {
      resolveInfoHash(infoHash, fileIdx = null, season = season, episode = episode)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Season pack errore ${normalizeHash(infoHash).take(8)}... S${season}E${episode}: ${e.message}")
      null
    }

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
