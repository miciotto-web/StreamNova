package com.example.data.streaming

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.example.data.prefs.StreamingEngineMode
import com.example.data.provider.AnimeStreamProvider
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.StremioStreamCandidate
import com.example.data.stremio.StremioSubtitleAdapter
import com.example.data.stremio.StremioSubtitleBridge
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.torbox.TorBoxRepository
import com.example.data.streaming.providers.Cb01Provider
import com.example.data.streaming.providers.EurostreamingProvider
import com.example.data.streaming.providers.MultiEmbedProvider
import com.example.data.streaming.providers.StreamingCommunityProvider
import com.example.data.streaming.providers.SuperEmbedProvider
import com.example.data.streaming.providers.TwoEmbedProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gestore dell'interrogazione dei provider di streaming con supporto a **Priorità Qualitativa Intelligente**
 * e **Fast-Start**.
 *
 * ARCHITETTURA DELLE SORGENTI:
 *  - **Addon Stremio** ([StremioAddonRepository]): ogni addon attivo con risorsa `stream` viene
 *    interrogato in parallelo su `$baseUrl/stream/$type/$id.json` (id IMDb `tt...` oppure `tmdb:...`,
 *    formato serie `tt...:S:E`). Gli stream con `url` **diretto** (addon già configurato con un
 *    debrid/CDN) diventano subito sorgenti ad alta qualità.
 *  - **TorBox Instant Debrid** ([TorBoxStreamProvider], primo provider in lista): gli stessi addon
 *    (e gli scraper) restituiscono anche `infoHash` **grezzi**: se nelle Impostazioni è configurata
 *    la chiave TorBox, l'hash viene verificato in cache (`checkcached`) e sbloccato (`requestdl`),
 *    restituendo a ExoPlayer un flusso CDN diretto con badge **[TorBox Instant 1080p/4K]**.
 *  - **Provider HTTP gratuiti** (VixSrc, AnimeStreamProvider...): restano il fallback trasparente
 *    se TorBox o gli addon non trovano sorgenti o falliscono.
 *
 * Tutte le sorgenti confluiscono in un'unica lista ordinata per qualità:
 * **4K > 1080p > 720p > provider HTTP gratuiti** (a parità di qualità vincono le sorgenti Debrid).
 *
 * Logica di prioritizzazione:
 *  0. Se il contenuto è **anime** (provider "crunchyroll"/283 oppure genere anime dedicato,
 *     esclusi i generi cinematografici generici Animazione/Animation), [AnimeStreamProvider]
 *     viene lanciato per primo con un head-start rispetto ai provider generici.
 *  1. Se un provider restituisce una sorgente Full HD (>= 1080p), questa viene emessa **subito**
 *     e posta in cima come prioritaria per il player.
 *  2. Se entro 1.2 secondi sono disponibili solo flussi a 720p o inferiori (es. VixSrc per alcune serie TV),
 *     il flusso a 720p viene avviato come fallback per garantire il Fast-Start senza bloccare la riproduzione.
 *  3. L'arricchimento continua in background per fornire tutte le sorgenti al selettore di risoluzione.
 */
class StreamManager(
  private val providers: List<StreamProvider> = listOf(
    // Debrid: risolve gli infoHash degli addon Stremio/scraper con TorBox Instant Debrid.
    // E' il primo elemento perché le sorgenti 4K/1080p debrid hanno priorità massima.
    TorBoxStreamProvider(),
    // Provider dedicato agli anime: per i contenuti animati viene prioritizzato
    // (vedi [isAnimeContent] in resolveFlow) e lanciato per primo.
    AnimeStreamProvider(),
    MultiEmbedProvider(),
    VixSrcProvider(),
    TwoEmbedProvider(),
    SuperEmbedProvider(),
    StreamingCommunityProvider(),
    Cb01Provider(),
    EurostreamingProvider()
  ),
  private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  /** Repository degli addon Stremio installati (interrogato in parallelo ai provider). */
  private val addonRepository: StremioAddonRepository = StremioAddonRepository
) {

  /** Esito singolo per provider: utile per log e diagnostica. */
  data class Outcome(val provider: String, val sources: Int, val error: String?)

  /**
   * Risoluzione concorrente reattiva via Flow con priorità 1080p e finestra di sicurezza 1.2s.
   */
  fun resolveFlow(
    tmdbId: Int,
    isTv: Boolean,
    season: Int? = null,
    episode: Int? = null,
    title: String? = null,
    year: Int? = null,
    originalTitle: String? = null,
    providerTag: String? = null,
    genres: List<String> = emptyList(),
    streamingEngineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB
  ): Flow<List<StreamSource>> = channelFlow {
    val flowId = ++searchIdCounter
    Log.i(HTTP_TRACE, "[SM] resolveFlow START searchId=$flowId tmdbId=$tmdbId isTv=$isTv S${season}E${episode}")
    val collected = mutableListOf<StreamSource>()
    val mutex = Mutex()
    var emittedFirst = false
    var fastStartWindowPassed = false
    var currentSelectedUrl: String? = null

    suspend fun logAndSendSelected(sources: List<StreamSource>) {
      if (sources.isNotEmpty()) {
        val best = sources.first()
        val bestUrl = best.streamUrl ?: ""
        Log.i(HTTP_TRACE, "[SM] send() BEFORE sources=${sources.size} searchId=$flowId first=${best.serverName} quality=${best.quality}")
        if (bestUrl != currentSelectedUrl) {
          currentSelectedUrl = bestUrl
          Log.i(TAG, "[StreamManager] Flusso selezionato: ${best.serverName} con qualità ${best.quality} e URL $bestUrl")
        }
        send(sources)
        Log.i(HTTP_TRACE, "[SM] send() AFTER searchId=$flowId")
      }
    }

    // Fallback timer: se entro 1.2 secondi nessun provider restituisce >= 1080p,
    // emetti il miglior flusso disponibile finora (es. 720p) come Fast-Start.
    val fallbackTimerJob = launch {
      delay(FAST_START_FALLBACK_DELAY_MS)
      mutex.withLock {
        fastStartWindowPassed = true
        if (!emittedFirst && collected.isNotEmpty()) {
          emittedFirst = true
          logAndSendSelected(collected.toList())
        }
      }
    }

    // Merge comune a TUTTE le sorgenti (addon Stremio, TorBox, provider HTTP):
    // deduplica per URL (provider HTTP) o per infoHash+fileIdx (TorBox torrent).
    suspend fun mergeSources(origin: String, sources: List<StreamSource>) {
      if (sources.isEmpty()) {
        Log.i(DIAG_TAG, "DIAG-C origin=$origin incoming=0 (nessuna sorgente da unire)")
        return
      }
      Log.i(TAG, "$origin -> ${sources.size} sorgente/i trovate")
      var added = 0
      var dropped = 0
      mutex.withLock {
        sources.forEach { s ->
          val isTorBoxSource = s.infoHash != null
          val dedupKey = if (isTorBoxSource) {
            "${s.infoHash}_${s.fileIdx ?: -1}"
          } else {
            s.streamUrl ?: ""
          }
          if (collected.none { other ->
            val otherKey = if (other.infoHash != null) "${other.infoHash}_${other.fileIdx ?: -1}" else other.streamUrl ?: ""
            otherKey == dedupKey
          }) {
            collected.add(s)
            added++
          } else {
            dropped++
          }
        }
        // DIAG TEMPORANEO (C): deduplicazione delle sorgenti dirette addon (infoHash == null).
        if (origin == "StremioAddons") {
          Log.i(
            DIAG_TAG,
            "DIAG-C origin=$origin incoming=${sources.size} added=$added dropped_dup=$dropped collected=${collected.size}"
          )
        }
        collected.sortWith(
          compareByDescending<StreamSource> { qualityScore(it) }
            .thenByDescending { if (it.serverName.contains("TorBox", ignoreCase = true)) 1 else 0 }
        )

        if (collected.any { isFullHdOrHigher(it) }) {
          // Sorgente Full HD/4K reale presente: il fallback timer non e'
          // piu' necessario, la partenza prioritaria e' immediata.
          fallbackTimerJob.cancel()
        }
        // Fast-Start: la PRIMA sorgente valida viene emessa immediatamente,
        // a qualsiasi qualita' (anche 720p/Auto/SD): la riproduzione non
        // attende l'arrivo di 1080p/4K. Il ranking e' mantenuto e le
        // sorgenti migliori arrivate successivamente aggiornano il flusso.
        emittedFirst = true
        logAndSendSelected(collected.toList())
      }
    }

    // --- Priorita' anime: provider dedicato lanciato per primo -----------------
    val isAnime = isAnimeContent(providerTag, genres)
    val orderedProviders = providers
      // Fuori dagli anime il provider dedicato NON viene eseguito: i titoli non
      // animati rischierebbero match errati nel catalogo anime.
      .filterNot { !isAnime && it is AnimeStreamProvider }
      .let { list ->
        if (isAnime) list.sortedByDescending { it is AnimeStreamProvider } else list
      }
    
    // Se in modalità HTTP_WEB, escludi i provider Debrid e TorBox;
    // in DEBRID_TORBOX esegui esclusivamente TorBoxStreamProvider.
    val filteredProviders = if (streamingEngineMode == StreamingEngineMode.HTTP_WEB) {
      orderedProviders.filterNot { it is TorBoxStreamProvider }
    } else {
      orderedProviders.filter { it is TorBoxStreamProvider }
    }

    orderedProviders.forEach { p ->
      val pName = p.javaClass.simpleName
      val active = filteredProviders.contains(p)
      if (active) {
        Log.i(TAG, "Provider $pName [ATTIVO] per modalità $streamingEngineMode (tmdbId=$tmdbId isTv=$isTv S=$season E=$episode)")
      } else {
        Log.d(TAG, "Provider $pName [SALTATO] per modalità $streamingEngineMode (escluso dalla configurazione)")
      }
    }
    
    if (isAnime) {
      Log.d(
        "ANIME_STREAM",
        "Anime detected (provider=$providerTag genres=$genres) -> AnimeStreamProvider prioritizzato"
      )
    }

    // --- Ricerca globale strutturata (supervisorScope) -------------------
    // Provider e addon Stremio girano in parallelo all'interno di uno scope
    // supervisionato: un provider che fallisce o viene cancellato NON puo'
    // compromettere gli altri (nessuna cancellazione a cascata). Quando scade
    // il timeout globale tutti i job ancora attivi vengono cancellati e la loro
    // effettiva terminazione viene attesa (join): il channelFlow puo' chiudere
    // il channel solo a giochi conclusi, quindi la terminazione del flow e'
    // strutturalmente deterministica e nessuna child coroutine resta zombie.
    supervisorScope {
      val jobs = mutableListOf<Job>()

      // --- 1) ADDON STREMIO (risorsa "stream") — SOLO in DEBRID_TORBOX ---
      // In HTTP_WEB gli addon Stremio NON vengono interrogati: la modalita'
      // usa esclusivamente gli scraper web gratuiti, quindi nessun job addon
      // puo' rallentare o bloccare la ricerca HTTP_WEB.
      if (streamingEngineMode == StreamingEngineMode.DEBRID_TORBOX) {
        // Interroga in parallelo tutti gli addon attivi: gli stream con "url" diretto
        // (es. addon pre-configurato con TorBox/Debrid) diventano sorgenti dirette ad
        // alta qualita'. Gli stream con "infoHash" grezzo vengono risolti dal provider
        // [TorBoxStreamProvider] che gira in parallelo nello stesso launch (le chiamate
        // agli addon/condividono la cache single-flight di StremioAddonRepository).
        val stremioAddonJob = launch {
          if (isAnime) delay(ANIME_HEAD_START_MS)
          try {
            Log.i(HTTP_TRACE, "[SM] StremioAddon source START searchId=$flowId tmdbId=$tmdbId")
            val sources = withTimeout(timeoutMs) {
              resolveStremioAddonSources(tmdbId, isTv, season, episode)
            }
            Log.i(HTTP_TRACE, "[SM] StremioAddon source END searchId=$flowId sources=${sources.size}")
            mergeSources("StremioAddons", sources)
          } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "StremioAddons -> timeout dopo ${timeoutMs}ms")
          } catch (e: Exception) {
            Log.w(TAG, "StremioAddons -> ${e.message}")
          }
        }
        jobs += stremioAddonJob
      }

      jobs += filteredProviders.map { provider ->
        launch {
          if (isAnime && provider !is AnimeStreamProvider) {
            // Head-start: i provider generici partono dopo, cosi l'anime viene servito per primo
            delay(ANIME_HEAD_START_MS)
          }
          val name = provider.javaClass.simpleName
          val isPittDebug = tmdbId == 318508 && isTv
          if (isPittDebug) {
            Log.d("THE_PITT_STREAM", "[StreamManager] $name resolving TMDB=$tmdbId isTv=$isTv season=$season episode=$episode")
          }
          val startTime = System.currentTimeMillis()
          try {
            Log.i(HTTP_TRACE, "[SM] Provider source START name=$name searchId=$flowId tmdbId=$tmdbId")
            val sources = withTimeout(timeoutMs) {
              if (provider is AnimeStreamProvider && originalTitle != null) {
                provider.getStreams(tmdbId, isTv, season, episode, title, originalTitle, year)
              } else {
                provider.getStreams(tmdbId, isTv, season, episode, title, year)
              }
            }
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(HTTP_TRACE, "[SM] Provider source END name=$name searchId=$flowId duration=${elapsed}ms sources=${sources?.size ?: 0}")
            if (sources.isNotEmpty()) {
              Log.i(TAG, "$name -> ${sources.size} sorgente/i trovate in ${elapsed}ms")
              if (isPittDebug) {
                Log.d("THE_PITT_STREAM", "[StreamManager] $name SUCCESS: ${sources.size} sources, first URL: ${sources.first().streamUrl}")
              }
              mergeSources(name, sources)
            } else {
              Log.d(TAG, "$name -> nessuna sorgente prodotta in ${elapsed}ms")
            }
          } catch (e: TimeoutCancellationException) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "$name -> timeout dopo ${elapsed}ms")
            if (tmdbId == 318508 && isTv) {
              Log.d("THE_PITT_STREAM", "[StreamManager] $name TIMEOUT after ${elapsed}ms")
            }
          } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "$name -> eccezione dopo ${elapsed}ms: ${e.message}")
            if (tmdbId == 318508 && isTv) {
              Log.d("THE_PITT_STREAM", "[StreamManager] $name ERROR: ${e.message}")
            }
          }
        }
      }

      // Timeout globale: alla scadenza cancella tutti i provider ancora attivi
      // e attende la loro terminazione effettiva prima di chiudere il flow.
      val completedInTime = withTimeoutOrNull(timeoutMs + 1_000L) {
        jobs.joinAll()
      }
      if (completedInTime == null) {
        Log.w(TAG, "Timeout perimetrale di ${timeoutMs + 1_000L}ms raggiunto: cancello i job ancora attivi")
        jobs.forEach { if (it.isActive) it.cancel() }
        // Attende la terminazione dei provider cancellati: nessun job puo'
        // sopravvivere al channelFlow, quindi il channel si chiude in modo
        // deterministico (niente coroutine zombie). Se un job resta bloccato
        // in I/O non cancellabile, NON si attende oltre: dopo il timeout il
        // channelFlow puo' terminare normalmente (il collector del ViewModel
        // non deve restare infinitamente su StreamResult.Loading).
        withTimeoutOrNull(JOIN_TIMEOUT_MS) {
          jobs.joinAll()
        }
      }
    }
    fallbackTimerJob.cancel()
    Log.i(HTTP_TRACE, "[SM] resolveFlow COMPLETE searchId=$flowId collected=${collected.size}")

    mutex.withLock {
      if (!emittedFirst && collected.isNotEmpty()) {
        emittedFirst = true
        logAndSendSelected(collected.toList())
      }
    }
    // DIAG TEMPORANEO (E): conteggi aggregati finali. "stream-name:" e' il testo `name`
    // della singola sorgente (payload addon), NON l'addon interrogato; "serverName:" per le
    // sorgenti dirette e' il displayTitle dell'addon interrogato. Nessuna attribuzione dal titolo.
    val diagFinalCounts = collected.groupingBy { diagFinalLabel(it) }.eachCount()
    Log.i(
      DIAG_TAG,
      "DIAG-E resolveFlow COMPLETE searchId=$flowId total=${collected.size} byLabel=$diagFinalCounts"
    )
  }

  /**
   * Interroga gli addon Stremio attivi e converte in [StreamSource] gli stream
   * che contengono gia' un `url` diretto (es. addon configurato con un proprio
   * debrid/CDN). Gli stream con `infoHash` grezzo vengono gestiti da
   * [TorBoxStreamProvider] (registrato tra i provider), che li risolve con
   * [com.example.data.torbox.TorBoxRepository] (checkcached + requestdl).
   */
  private suspend fun resolveStremioAddonSources(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?
  ): List<StreamSource> {
    val candidates = addonRepository.fetchStreams(tmdbId, isTv, season, episode)
    if (candidates.isEmpty()) {
      Log.i(DIAG_TAG, "DIAG-C origin=StremioAddons result=EMPTY candidates=0")
      return emptyList()
    }
    // Stream "già sbloccati" (Debrid addon come Comet/ElfHosted): hanno `url`
    // diretto e `infoHash == null`. Vengono convertiti direttamente in sorgenti
    // giocabili e marcati cached; i placeholder di errore vengono scartati.
    val directSources = candidates.mapNotNull { directStreamSource(it) }
    // DIAG TEMPORANEO (C): URL diretti validi per addon INTERROGATO, prima della dedup di mergeSources.
    candidates.groupBy { it.baseUrl }.forEach { (baseUrl, items) ->
      val validDirect = items.count { it.item.url?.isNotBlank() == true }
      Log.i(
        DIAG_TAG,
        "DIAG-C addon=${diagHostLabel(baseUrl)} candidates=${items.size} direct_urls=$validDirect"
      )
    }
    Log.i(
      DIAG_TAG,
      "DIAG-C origin=StremioAddons total_candidates=${candidates.size} direct_sources=${directSources.size}"
    )
    return directSources
  }

  /** DIAG TEMPORANEO: etichetta finale che distingue il payload sorgente dall'addon interrogato. */
  private fun diagFinalLabel(source: StreamSource): String {
    val streamName = source.addonName?.takeIf { it.isNotBlank() }
    if (streamName != null) return "stream-name:$streamName"
    return "serverName:${source.serverName.substringBefore(" 🧲").trim()}"
  }

  /** DIAG TEMPORANEO: solo hostname, mai path/query/credenziali. */
  private fun diagHostLabel(baseUrl: String): String =
    baseUrl.trimEnd('/').toHttpUrlOrNull()?.host ?: "unknown-host"

  /**
   * Risolve le sorgenti disponibili restituendo il primo risultato non vuoto disponibile (Fast-Start).
   */
  suspend fun resolve(
    tmdbId: Int,
    isTv: Boolean,
    season: Int? = null,
    episode: Int? = null,
    title: String? = null,
    year: Int? = null,
    originalTitle: String? = null,
    providerTag: String? = null,
    genres: List<String> = emptyList(),
    streamingEngineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB
  ): List<StreamSource> {
    return resolveFlow(tmdbId, isTv, season, episode, title, year, originalTitle, providerTag, genres, streamingEngineMode)
      .firstOrNull { it.isNotEmpty() }
      ?: emptyList()
  }

  /**
   * Risolve il miglior flusso disponibile per il MediaItem specificato.
   * Restituisce il miglior [StreamSource] con streamUrl valido o null se nessuna sorgente è disponibile.
   */
  suspend fun resolveBestStream(
    media: MediaItem,
    episode: Episode? = null,
    streamingEngineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB
  ): StreamSource? {
    val tmdbId = media.tmdbId ?: return null
    val isTv = media.type == MediaType.SERIE_TV
    val season = episode?.seasonNumber ?: media.lastWatchedSeason ?: 1
    val epNumber = episode?.episodeNumber ?: media.lastWatchedEpisode ?: 1
    val searchTitle = media.title.ifBlank { media.originalTitle }

    val sources = resolve(
      tmdbId = tmdbId,
      isTv = isTv,
      season = if (isTv) season else null,
      episode = if (isTv) epNumber else null,
      title = searchTitle,
      year = media.year,
      originalTitle = media.originalTitle.takeIf { it.isNotBlank() },
      providerTag = media.provider,
      genres = media.genres,
      streamingEngineMode = streamingEngineMode
    )

    val best = sources.firstOrNull() ?: return null
    if (best.infoHash != null && best.streamUrl == null) {
      TorBoxRepository.setRequestMetadata(episode?.seasonNumber, episode?.episodeNumber)
      val directUrl = TorBoxRepository.resolveInfoHash(best.infoHash, best.fileIdx)
      return if (directUrl != null) {
        best.copy(streamUrl = directUrl)
      } else {
        null
      }
    }
    return if (!best.streamUrl.isNullOrBlank()) best else null
  }

  companion object {
    private const val TAG = "StreamManager"
    private const val HTTP_TRACE = "HTTP_TRACE"
    // DIAG TEMPORANEO (solo logging).
    private const val DIAG_TAG = "COMET_DIAG"
    var searchIdCounter = 0
    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val FAST_START_FALLBACK_DELAY_MS = 1200L

    /** Timeout (ms) per l'attesa finale dei job cancellati prima di chiudere il channelFlow. */
    const val JOIN_TIMEOUT_MS = 3_000L

    /** Head-start (ms) concesso ad [AnimeStreamProvider] sugli altri provider per i contenuti anime. */
    const val ANIME_HEAD_START_MS = 700L

    /**
     * Converte un candidato Stremio con `url` **diretto** in una [StreamSource]
     * giocabile e già marcata cached. È il caso degli addon Debrid pre-risolti
     * (es. "Comet | ElfHosted"): generano stream con link video nel campo `url`
     * e `infoHash == null`.
     *
     * Restituisce `null` — cioè lo scarta — quando:
     *  - esiste un `infoHash`: quello stream è di competenza di
     *    [TorBoxStreamProvider], che lo risolve con `checkcached` + unlock;
     *  - manca un `url` giocabile (schema non HTTP, risorsa non video);
     *  - `name`/`title` sono placeholder di errore dell'addon (es. "No streams
     *    found", "Invalid Debrid API key").
     *
     * I metadati (titolo file, qualità, codec, tipo rilascio, sottotitoli) sono
     * quelli già estratti dal candidato, così la card mostra il file reale e non
     * l'etichetta dell'addon.
     */
    internal fun directStreamSource(candidate: StremioStreamCandidate): StreamSource? {
      val item = candidate.item
      val url = item.url?.trim()?.takeIf { it.isNotBlank() } ?: return null
      // Gli infoHash restano a TorBox: qui solo stream Debrid già sbloccati.
      if (!item.infoHash.isNullOrBlank()) return null
      if (!isPlayableDirectUrl(url)) return null
      if (isErrorPlaceholder(item.name, item.title)) return null

      val quality = candidate.quality
      val releaseTitle = candidate.releaseTitle.takeIf { it.isNotBlank() }
      val details = candidate.sizeAndPeers.takeIf { it.isNotBlank() }
      val instantTag = candidate.instantTag.takeIf { it.isNotBlank() }
      val isItalian = StreamSource.isItalianSource(
        listOfNotNull(candidate.addonNameFromStream, releaseTitle).joinToString(" ")
      )
      val subtitles = StremioSubtitleAdapter.toSubtitles(
        StremioSubtitleBridge.fromStream(item),
        addonName = candidate.addonName
      )

      return StreamSource(
        streamUrl = url,
        quality = quality,
        serverName = candidate.addonName,
        // Header richiesti dallo stream (behaviorHints.proxyHeaders.request):
        // devono arrivare al player per evitare 403 su stream protetti.
        headers = item.proxyHeaders,
        declaredQuality = quality,
        isProgressive = true,
        isItalian = isItalian,
        addonName = candidate.addonName,
        instantTag = instantTag,
        releaseTitle = releaseTitle,
        details = details,
        codec = candidate.codec,
        // Link Debrid diretto già pronto alla riproduzione.
        isCached = true,
        releaseType = candidate.releaseType,
        subtitles = subtitles
      )
    }

    /** true se l'URL è un link HTTP(S) riproducibile (non un manifest/JSON). */
    internal fun isPlayableDirectUrl(url: String): Boolean {
      val u = url.trim().lowercase()
      if (!u.startsWith("http://") && !u.startsWith("https://")) return false
      if (u.endsWith(".json") || u.contains("/manifest.json")) return false
      return true
    }

    /**
     * Marker di errore/placeholder che gli addon Debrid (es. Comet) usano come
     * finto stream quando la risoluzione fallisce. Frasi specifiche: un titolo
     * di un film reale non le contiene, quindi nessun falso positivo sui nomi file.
     */
    private val ERROR_STREAM_MARKERS = listOf(
      "no streams found",
      "no stream found",
      "no streams",
      "no results",
      "no torrents",
      "invalid debrid",
      "invalid api key",
      "invalid token",
      "debrid api key",
      "debrid not configured",
      "not configured",
      "configuration required",
      "unauthorized",
      "expired token",
      "expired api",
      "service unavailable",
      "rate limit",
      "try again later",
      "comet error"
    )

    /** true se `name`/`title` descrivono un errore/placeholder dell'addon. */
    internal fun isErrorPlaceholder(name: String?, title: String?): Boolean {
      val text = listOfNotNull(name, title).joinToString(" ").lowercase()
      if (text.isBlank()) return false
      return ERROR_STREAM_MARKERS.any { text.contains(it) }
    }

    /**
     * Un contenuto e' considerato **anime** se appartiene a Crunchyroll (provider 283 /
     * tag "crunchyroll") oppure se tra i generi compare esplicitamente un genere anime
     * (escludendo categoricamente i generi cinematografici generici "Animazione" / "Animation").
     */
    fun isAnimeContent(providerTag: String?, genres: List<String>): Boolean {
      val tag = providerTag?.lowercase().orEmpty()
      if (tag.contains("crunchyroll") || tag.contains("283") || tag.contains("anime")) return true
      return genres.any { genre ->
        val g = genre.trim().lowercase()
        // Esclude categoricamente i generi cinematografici generici "Animazione" e "Animation"
        if (g == "animazione" || g == "animation") return@any false
        g == "anime" || g.contains("anime") || (g.contains("anim") && (g.contains("giappon") || g.contains("japan")))
      }
    }

    fun isFullHdOrHigher(source: StreamSource): Boolean {
      val vHeight = source.verifiedHeight
      if (vHeight != null) {
        return vHeight >= 1080
      }
      return isFullHdOrHigher(source.quality)
    }

    fun isFullHdOrHigher(quality: String): Boolean {
      val q = quality.lowercase()
      if (q == "auto") return false
      return q.contains("1080") || q.contains("4k") || q.contains("2160") || q.contains("fhd") || q.contains("uhd")
    }

    fun qualityScore(source: StreamSource): Int {
      val vHeight = source.verifiedHeight
      if (vHeight != null) {
        return when {
          vHeight >= 2160 -> 250 // 4K verificato
          vHeight >= 1080 -> 200 // 1080p FHD verificato
          vHeight >= 720  -> 100 // 720p HD verificato
          vHeight >= 480  -> 50  // 480p SD verificato
          else            -> 20
        }
      }
      return qualityScore(source.quality)
    }

    fun qualityScore(quality: String): Int {
      val q = quality.lowercase()
      return when {
        q.contains("4k") || q.contains("2160") || q.contains("uhd") -> 180
        q.contains("1080") || q.contains("fhd") -> 150
        q.contains("720") || q.contains("hd") -> 80
        q.contains("auto") -> 40
        q.contains("480") || q.contains("sd") -> 30
        else -> 10
      }
    }
  }
}
