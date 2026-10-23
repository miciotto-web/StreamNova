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
import com.example.data.streaming.diagnostics.AttemptConcurrencyCounter
import com.example.data.streaming.diagnostics.DiagnosticClock
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.data.streaming.diagnostics.StreamSearchMetrics
import com.example.data.streaming.diagnostics.StreamSearchMetricsCollector
import com.example.data.streaming.diagnostics.toLogLine
import com.example.data.streaming.diagnostics.record
import com.example.data.streaming.providers.Cb01Provider
import com.example.data.streaming.providers.EurostreamingProvider
import com.example.data.streaming.providers.MultiEmbedProvider
import com.example.data.streaming.providers.StreamingCommunityProvider
import com.example.data.streaming.providers.SuperEmbedProvider
import com.example.data.streaming.providers.TwoEmbedProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

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
  /**
   * Destinazione dei tentativi strutturati. Default [StreamDiagnosticSink.NONE]:
   * nessuna persistenza e nessun costo quando la diagnostica non e' richiesta.
   * I provider catalogo creati di default ricevono lo stesso sink, cosi' la
   * catena provider -> mirror -> extractor resta ricostruibile.
   */
  private val diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE,
  private val providers: List<StreamProvider> = defaultProviders(diagnostics),
  private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  /**
   * Limite esplicito di provider interrogati contemporaneamente. Il valore e'
   * configurabile dal chiamante; 1 rende la ricerca sequenziale nell'ordine
   * della lista. La cancellazione resta strutturata (i job in attesa di slot
   * vengono annullati con lo scope).
   */
  private val maxConcurrentProviders: Int = DEFAULT_MAX_CONCURRENT_PROVIDERS,
  /** Recupera gli stream degli addon Stremio; iniettabile per testare l'isolamento tra modalità. */
  private val addonStreamsFetcher: suspend (Int, Boolean, Int?, Int?, String?) -> List<StremioStreamCandidate> =
    { tmdbId, isTv, season, episode, imdbId ->
      StremioAddonRepository.fetchStreams(tmdbId, isTv, season, episode, imdbId)
    },
  /**
   * Clock monotono per le metriche (queueMs/runMs/firstSourceMs). Iniettabile
   * nei test: la produzione usa [DiagnosticClock.SYSTEM].
   */
  private val clock: DiagnosticClock = DiagnosticClock.SYSTEM,
  /** Riceve le metriche aggregate a fine ricerca. Default: nessun costo. */
  private val onSearchMetrics: (StreamSearchMetrics) -> Unit = {}
) {

  /** Esito singolo per provider: utile per log e diagnostica. */
  data class Outcome(val provider: String, val sources: Int, val error: String?)

  /**
   * Risoluzione concorrente reattiva via Flow con priorità 1080p e finestra di sicurezza 1.2s.
   *
   * Le metriche di ricerca ([onSearchMetrics]) vengono emesse in un `finally`
   * che gira **fuori** dalla coroutine del channel: restano disponibili anche
   * quando il collector va via prima (ricerca annullata dall'utente), senza
   * dipendere da un `send` sul channel gia' chiuso.
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
    streamingEngineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB,
    imdbId: String? = null
  ): Flow<List<StreamSource>> {
    var metricsToPublish: StreamSearchMetrics? = null
    var cancelledMetricsSink: (() -> StreamSearchMetrics?)? = null
    val flow = channelFlow {
      try {
        resolveFlowBody(
          metricsSink = { metricsToPublish = it },
          cancelledMetricsSink = { cancelledMetricsSink = it },
          tmdbId = tmdbId,
          isTv = isTv,
          season = season,
          episode = episode,
          title = title,
          year = year,
          originalTitle = originalTitle,
          providerTag = providerTag,
          genres = genres,
          streamingEngineMode = streamingEngineMode,
          imdbId = imdbId
        )
      } finally {
        // Il producer puo' essere gia' cancellato (collector andato via): la
        // pubblicazione non deve dipendere dallo stato della coroutine, quindi
        // gira in NonCancellable. E' sincrona, senza sospensioni e senza I/O.
        // Se il corpo e' stato cancellato prima di emettere le metriche, qui
        // ne viene pubblicata comunque la versione parziale gia' nota.
        withContext(NonCancellable) {
          val metrics = metricsToPublish ?: cancelledMetricsSink?.invoke()
          if (metrics != null) {
            Log.i(DIAG_TAG, "DIAG-M ${metrics.toLogLine()}")
            onSearchMetrics(metrics)
          }
        }
      }
    }
    return flow
  }

  @Suppress("LongParameterList")
  private suspend fun kotlinx.coroutines.channels.ProducerScope<List<StreamSource>>.resolveFlowBody(
    metricsSink: (StreamSearchMetrics) -> Unit,
    cancelledMetricsSink: (() -> StreamSearchMetrics?) -> Unit,
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?,
    originalTitle: String?,
    providerTag: String?,
    genres: List<String>,
    streamingEngineMode: StreamingEngineMode,
    imdbId: String?
  ) {
    val flowId = NEXT_SEARCH_ID.incrementAndGet()
    searchIdCounter = flowId.toInt()
    Log.i(HTTP_TRACE, "[SM] resolveFlow START searchId=$flowId tmdbId=$tmdbId isTv=$isTv S${season}E${episode}")

    // Senza TMDB ID, IMDb ID e titolo non esiste alcuna chiave di ricerca: nessun
    // provider o addon può rispondere, quindi si chiude subito con zero sorgenti.
    val imdbIdClean = imdbId?.trim()?.takeIf { it.isNotBlank() }?.substringBefore(":")
    if (tmdbId <= 0 && imdbIdClean.isNullOrBlank() && title.isNullOrBlank()) {
      Log.w(TAG, "resolveFlow: nessuna chiave di ricerca disponibile (tmdbId=$tmdbId, imdbId=$imdbId, titolo vuoto)")
      return@resolveFlowBody
    }

    // --- Metriche locali di QUESTA ricerca -----------------------------------
    // Tutti i tempi sono presi da un clock monotono (mai orologio di parete).
    // Il contatore di concorrenza viene incrementato DENTRO il permit e
    // decrementato in un finally: nessun percorso (errore, timeout, cancella-
    // zione, eccezione non-cancellazione) puo' lasciarlo gonfiato.
    val metrics = StreamSearchMetricsCollector(flowId, clock)
    val concurrency = AttemptConcurrencyCounter()
    val slotLimit = maxConcurrentProviders.coerceAtLeast(1)
    // Snapshot per l'uscita anticipata: se la ricerca viene cancellata prima
    // di completare il corpo, il chiamante pubblica comunque le metriche
    // parziali raccolte fino a quel punto (mai valori inventati).
    cancelledMetricsSink { metrics.build(peakConcurrent = concurrency.peakCount) }

    val collected = mutableListOf<StreamSource>()
    val mutex = Mutex()
    // Limite per QUESTA ricerca: ricerche concorrenti (es. una annullata e una
    // nuova) non si contendono gli slot.
    val providerSlots = Semaphore(slotLimit)
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

    /**
     * Una sorgente e' candidata alla riproduzione solo se ha un URL HTTP(S)
     * riproducibile o un infoHash risolvibile da TorBox. Un provider che
     * restituisce risultati non riproducibili NON deve far partire il timer
     * della prima sorgente.
     */
    fun isPlayableCandidate(source: StreamSource): Boolean =
      isPlayableDirectUrl(source.streamUrl.orEmpty()) || !source.infoHash.isNullOrBlank()

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
        // La deduplica distingue lo stesso URL con header di autorizzazione o
        // riproduzione differenti (es. token Debrid diversi): sono sorgenti
        // realmente diverse e non vanno collassate in una sola.
        val before = collected.size
        addDistinctSources(collected, sources)
        added = collected.size - before
        dropped = sources.size - added
        // Contatori per provider, non solo per addon: almeno una delle
        // sorgenti aggiunte deve essere riproducibile per far partire il
        // cronometro della prima sorgente valida.
        metrics.onDuplicates(dropped)
        metrics.onValidSources(added)
        if (added > 0 && collected.any { isPlayableCandidate(it) }) {
          metrics.onFirstPlayableSource()
        }
        Log.i(
          DIAG_TAG,
          "DIAG-C origin=$origin incoming=${sources.size} added=$added dropped_dup=$dropped collected=${collected.size}"
        )
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
    val modeProviders = if (streamingEngineMode == StreamingEngineMode.HTTP_WEB) {
      orderedProviders.filterNot { it is TorBoxStreamProvider }
    } else {
      orderedProviders.filter { it is TorBoxStreamProvider }
    }

    // L'ID TMDB arricchisce la ricerca ma non è un prerequisito per riprodurre:
    // gli addon Stremio e TorBox interrogano per ID IMDb (`tt...`) e i provider
    // italiani cercano per titolo. Quando manca si escludono solo i provider che
    // parlano esclusivamente con le API TMDB, invece di invocarli per farli
    // fallire su "TMDB ID non valido" e lasciare l'utente senza sorgenti.
    val missingTmdbId = tmdbId <= 0
    val filteredProviders = if (missingTmdbId) {
      modeProviders.filterNot { it.requiresTmdbId() }
    } else {
      modeProviders
    }

    orderedProviders.forEach { p ->
      val pName = p.javaClass.simpleName
      val active = filteredProviders.contains(p)
      if (active) {
        Log.i(TAG, "Provider $pName [ATTIVO] per modalità $streamingEngineMode (tmdbId=$tmdbId isTv=$isTv S=$season E=$episode)")
      } else if (missingTmdbId && modeProviders.contains(p) && p.requiresTmdbId()) {
        Log.i(TAG, "Provider $pName [SALTATO] richiede un ID TMDB non disponibile (si prosegue con imdbId=$imdbId)")
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

      // --- 1) ADDON STREMIO (risorsa "stream") ----------------------------
      // Gli addon Stremio vengono interrogati esclusivamente in DEBRID_TORBOX.
      // HTTP_WEB usa soltanto i provider HTTP integrati in StreamNova.
      if (streamingEngineMode == StreamingEngineMode.DEBRID_TORBOX) {
        // Interroga in parallelo tutti gli addon attivi: gli stream con "url" diretto
        // (es. addon pre-configurato con TorBox/Debrid) diventano sorgenti dirette ad
        // alta qualita'. Gli stream con "infoHash" grezzo vengono risolti dal provider
        // [TorBoxStreamProvider] che gira in parallelo nello stesso launch (le chiamate
        // agli addon/condividono la cache single-flight di StremioAddonRepository).
        val stremioAddonJob = launch {
          metrics.onProviderStarted()
          if (isAnime) delay(ANIME_HEAD_START_MS)
          val enqueuedAt = clock.nowMs()
          var runStart = enqueuedAt
          var outcome = StreamAttemptOutcome.NO_RESULTS
          var failureMessage: String? = null
          var produced = 0
          try {
            Log.i(HTTP_TRACE, "[SM] StremioAddon source START searchId=$flowId tmdbId=$tmdbId")
            val sources = providerSlots.withPermit {
              runStart = clock.nowMs()
              concurrency.increment()
              try {
                withTimeout(timeoutMs) {
                  resolveStremioAddonSources(tmdbId, isTv, season, episode, imdbId)
                }
              } finally {
                concurrency.decrement()
              }
            }
            Log.i(HTTP_TRACE, "[SM] StremioAddon source END searchId=$flowId sources=${sources.size}")
            produced = sources.size
            if (sources.isNotEmpty()) {
              mergeSources("StremioAddons", sources)
              outcome = StreamAttemptOutcome.SUCCESS
            }
          } catch (e: TimeoutCancellationException) {
            outcome = StreamAttemptOutcome.TIMEOUT
            failureMessage = e.message
            Log.w(TAG, "StremioAddons -> timeout dopo ${timeoutMs}ms")
          } catch (e: CancellationException) {
            outcome = StreamAttemptOutcome.CANCELLED
            failureMessage = "ricerca annullata"
            throw e
          } catch (e: Exception) {
            outcome = StreamDiagnostics.classify(e)
            failureMessage = e.message
            Log.w(TAG, "StremioAddons -> ${e.message}")
          } finally {
            val finishedAt = clock.nowMs()
            metrics.onAttemptOutcome(outcome)
            metrics.onProviderCompleted()
            Log.i(
              DIAG_TAG,
              "DIAG-P provider=StremioAddons request='${(title ?: "").take(80)}' isTv=$isTv " +
                "S=${season ?: "-"} E=${episode ?: "-"} outcome=$outcome " +
                "durationMs=${finishedAt - enqueuedAt} sources=$produced " +
                "error=${StreamDiagnostics.sanitize(failureMessage) ?: "-"}"
            )
            diagnostics.record(
              providerId = "StremioAddons",
              outcome = outcome,
              durationMs = finishedAt - enqueuedAt,
              message = failureMessage,
              searchId = flowId,
              queueMs = runStart - enqueuedAt,
              runMs = finishedAt - runStart
            )
          }
        }
        jobs += stremioAddonJob
      }

      jobs += filteredProviders.map { provider ->
        launch {
          metrics.onProviderStarted()
          if (isAnime && provider !is AnimeStreamProvider) {
            // Head-start: i provider generici partono dopo, cosi l'anime viene servito per primo
            delay(ANIME_HEAD_START_MS)
          }
          val name = provider.providerId
          val isPittDebug = tmdbId == 318508 && isTv
          if (isPittDebug) {
            Log.d("THE_PITT_STREAM", "[StreamManager] $name resolving TMDB=$tmdbId isTv=$isTv season=$season episode=$episode")
          }
          // Candidato in coda da qui: queueMs e' l'attesa del semaforo,
          // runMs l'esecuzione effettiva sotto slot. L'attesa in coda non
          // consuma il budget del tentativo (withTimeout e' dentro il permit).
          val enqueuedAt = clock.nowMs()
          var runStart = enqueuedAt
          var outcome = StreamAttemptOutcome.NO_RESULTS
          var failureMessage: String? = null
          var produced = 0
          try {
            Log.i(HTTP_TRACE, "[SM] Provider source START name=$name searchId=$flowId tmdbId=$tmdbId")
            // Lo slot viene acquisito PRIMA del timeout: l'attesa in coda non
            // consuma il budget del tentativo. withPermit e' cancellabile,
            // quindi l'annullamento della ricerca libera anche i job in attesa.
            val sources = providerSlots.withPermit {
              runStart = clock.nowMs()
              concurrency.increment()
              try {
                withTimeout(timeoutMs) {
                  if (provider is AnimeStreamProvider && originalTitle != null) {
                    provider.getStreams(tmdbId, isTv, season, episode, title, originalTitle, year)
                  } else if (provider is TorBoxStreamProvider) {
                    provider.getStreams(tmdbId, isTv, season, episode, title, year, imdbId)
                  } else if (provider is TwoEmbedProvider) {
                    // IMDb ID disponibile: fallback per `var Q` quando il player
                    // espone solo un ID numerico o nessun identificativo.
                    provider.getStreams(tmdbId, isTv, season, episode, title, year, imdbId)
                  } else {
                    provider.getStreams(tmdbId, isTv, season, episode, title, year)
                  }
                }
              } finally {
                concurrency.decrement()
              }
            }
            val elapsed = clock.nowMs() - runStart
            Log.i(HTTP_TRACE, "[SM] Provider source END name=$name searchId=$flowId duration=${elapsed}ms sources=${sources?.size ?: 0}")
            produced = sources?.size ?: 0
            if (sources.isNotEmpty()) {
              Log.i(TAG, "$name -> ${sources.size} sorgente/i trovate in ${elapsed}ms")
              if (isPittDebug) {
                Log.d("THE_PITT_STREAM", "[StreamManager] $name SUCCESS: ${sources.size} sources, first URL: ${sources.first().streamUrl}")
              }
              mergeSources(name, sources)
              outcome = StreamAttemptOutcome.SUCCESS
            } else {
              Log.d(TAG, "$name -> nessuna sorgente prodotta in ${elapsed}ms")
            }
          } catch (e: TimeoutCancellationException) {
            outcome = StreamAttemptOutcome.TIMEOUT
            failureMessage = e.message
            val elapsed = clock.nowMs() - runStart
            Log.w(TAG, "$name -> timeout dopo ${elapsed}ms")
            if (tmdbId == 318508 && isTv) {
              Log.d("THE_PITT_STREAM", "[StreamManager] $name TIMEOUT after ${elapsed}ms")
            }
          } catch (e: CancellationException) {
            // Cancellazione strutturata: non e' un errore del provider e non va
            // soppressa. Il tentativo viene comunque registrato per diagnostica.
            outcome = StreamAttemptOutcome.CANCELLED
            failureMessage = "ricerca annullata"
            throw e
          } catch (e: Exception) {
            val elapsed = clock.nowMs() - runStart
            outcome = StreamDiagnostics.classify(e)
            failureMessage = e.message
            Log.w(TAG, "$name -> eccezione dopo ${elapsed}ms: ${e.message}")
            if (tmdbId == 318508 && isTv) {
              Log.d("THE_PITT_STREAM", "[StreamManager] $name ERROR: ${e.message}")
            }
          } finally {
            val finishedAt = clock.nowMs()
            metrics.onAttemptOutcome(outcome)
            metrics.onProviderCompleted()
            // Diagnostica per provider richiesta: titolo/episodio, esito,
            // durata, sorgenti prodotte ed errore finale. Nessun header,
            // cookie o token viene registrato.
            Log.i(
              DIAG_TAG,
              "DIAG-P provider=$name request='${(title ?: "").take(80)}' isTv=$isTv " +
                "S=${season ?: "-"} E=${episode ?: "-"} outcome=$outcome " +
                "durationMs=${finishedAt - enqueuedAt} sources=$produced " +
                "error=${StreamDiagnostics.sanitize(failureMessage) ?: "-"}"
            )
            diagnostics.record(
              providerId = name,
              outcome = outcome,
              durationMs = finishedAt - enqueuedAt,
              message = failureMessage,
              searchId = flowId,
              queueMs = runStart - enqueuedAt,
              runMs = finishedAt - runStart
            )
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
    // Emissione finale best-effort: se il collector e' gia' andato via (ricerca
    // annullata) il send fallisce con CancellationException; le metriche sono
    // comunque pubblicate dal finally non-suspending di resolveFlow.
    try {
      mutex.withLock {
        if (!emittedFirst && collected.isNotEmpty()) {
          emittedFirst = true
          logAndSendSelected(collected.toList())
        }
      }
    } catch (e: CancellationException) {
      Log.i(HTTP_TRACE, "[SM] collector assente su send finale searchId=$flowId")
    }
    // DIAG TEMPORANEO (E): conteggi aggregati finali. "stream-name:" e' il testo `name`
    // della singola sorgente (payload addon), NON l'addon interrogato; "serverName:" per le
    // sorgenti dirette e' il displayTitle dell'addon interrogato. Nessuna attribuzione dal titolo.
    val diagFinalCounts = collected.groupingBy { diagFinalLabel(it) }.eachCount()
    Log.i(
      DIAG_TAG,
      "DIAG-E resolveFlow COMPLETE searchId=$flowId total=${collected.size} byLabel=$diagFinalCounts"
    )

    // Metriche aggregate finali (anche parziali, se la ricerca e' stata
    // cancellata o ha superato il budget). La scrittura sul log e la callback
    // avvengono fuori da qui, nel finally di resolveFlow.
    metricsSink(metrics.build(peakConcurrent = concurrency.peakCount))
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
    episode: Int?,
    imdbId: String? = null
  ): List<StreamSource> {
    val candidates = addonStreamsFetcher(tmdbId, isTv, season, episode, imdbId)
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
   * Provider che interrogano **esclusivamente** le API TMDB e non hanno alcun
   * percorso alternativo per ID IMDb o titolo: senza un TMDB ID valido la loro
   * unica risposta possibile è `TMDB ID non valido`. Vengono disattivati quando
   * l'ID TMDB manca, mentre Stremio, TorBox e i provider che cercano per titolo
   * continuano a lavorare.
   */
  private fun StreamProvider.requiresTmdbId(): Boolean = providerRequiresTmdbId(this)

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
    streamingEngineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB,
    imdbId: String? = null
  ): List<StreamSource> {
    return resolveFlow(tmdbId, isTv, season, episode, title, year, originalTitle, providerTag, genres, streamingEngineMode, imdbId)
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
    // Un ID TMDB mancante NON impedisce la risoluzione: Stremio e TorBox usano
    // l'ID IMDb e i provider italiani cercano per titolo. Si prosegue con 0.
    val tmdbId = media.tmdbId?.takeIf { it > 0 } ?: 0
    val isTv = media.type == MediaType.SERIE_TV
    val season = episode?.seasonNumber ?: media.lastWatchedSeason ?: 1
    val epNumber = episode?.episodeNumber ?: media.lastWatchedEpisode ?: 1
    val searchTitle = media.title.ifBlank { media.originalTitle }
    val effectiveImdb = media.imdbId?.takeIf { it.isNotBlank() }
      ?: episode?.seriesImdbId?.takeIf { it.isNotBlank() }
      ?: media.id.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(":")

    if (tmdbId <= 0 && effectiveImdb.isNullOrBlank() && searchTitle.isBlank()) {
      Log.w(TAG, "resolveBestStream: nessuna chiave di ricerca per ${media.id}")
      return null
    }

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
      streamingEngineMode = streamingEngineMode,
      imdbId = effectiveImdb
    )

    for (candidate in sources) {
      if (!candidate.streamUrl.isNullOrBlank()) {
        return candidate
      }
      if (candidate.infoHash != null) {
        TorBoxRepository.setRequestMetadata(episode?.seasonNumber, episode?.episodeNumber)
        val directUrl = TorBoxRepository.resolveInfoHash(candidate.infoHash, candidate.fileIdx)
        if (directUrl != null) {
          return candidate.copy(streamUrl = directUrl)
        }
      }
    }
    return null
  }

  companion object {
    private const val TAG = "StreamManager"
    private const val HTTP_TRACE = "HTTP_TRACE"
    // DIAG TEMPORANEO (solo logging).
    private const val DIAG_TAG = "COMET_DIAG"
    var searchIdCounter = 0

    /** Sorgente monotona degli ID di ricerca (nessun perdita con ricerche concorrenti). */
    private val NEXT_SEARCH_ID = AtomicLong(0L)
    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val FAST_START_FALLBACK_DELAY_MS = 1200L

    /** Provider interrogati contemporaneamente quando il chiamante non specifica un limite. */
    const val DEFAULT_MAX_CONCURRENT_PROVIDERS = 3

    /**
     * Provider predefiniti. I provider catalogo condividono il sink diagnostico
     * per registrare i tentativi per mirror ed extractor con lo stesso contesto.
     */
    private fun defaultProviders(diagnostics: StreamDiagnosticSink): List<StreamProvider> = listOf(
      // Debrid: risolve gli infoHash degli addon Stremio/scraper con TorBox Instant Debrid.
      // E' il primo elemento perché le sorgenti 4K/1080p debrid hanno priorità massima.
      TorBoxStreamProvider(),
      // Provider dedicato agli anime: per i contenuti animati viene prioritizzato
      // (vedi [isAnimeContent] in resolveFlow) e lanciato per primo.
      AnimeStreamProvider(),
      MultiEmbedProvider(diagnostics = diagnostics),
      VixSrcProvider(diagnostics = diagnostics),
      // 2Embed: il sink alimenta i record diagnostici per-mirror (come i cataloghi).
      TwoEmbedProvider(diagnostics = diagnostics),
      SuperEmbedProvider(diagnostics = diagnostics),
      StreamingCommunityProvider(diagnostics = diagnostics),
      Cb01Provider(diagnostics = diagnostics),
      EurostreamingProvider(diagnostics = diagnostics)
    )

    /** Timeout (ms) per l'attesa finale dei job cancellati prima di chiudere il channelFlow. */
    const val JOIN_TIMEOUT_MS = 3_000L

    /**
     * True se il provider **pretende** un TMDB ID valido (e quindi va escluso quando
     * manca): VixSrc, 2Embed, SuperEmbed e MultiEmbed costruiscono l'URL del player
     * solo da `tmdbId` e non hanno un percorso per ID IMDb o titolo.
     *
     * Gli altri provider restano utilizzabili senza TMDB: gli addon Stremio e TorBox
     * interrogano la risorsa `stream` con l'ID IMDb `tt...`, mentre
     * StreamingCommunity, CB01, Eurostreaming e AnimeStreamProvider cercano per titolo.
     */
    fun providerRequiresTmdbId(provider: StreamProvider): Boolean =
      provider is VixSrcProvider ||
        provider is MultiEmbedProvider ||
        provider is TwoEmbedProvider ||
        provider is SuperEmbedProvider

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

      // DIAG: attribuzione diagnostica della sorgente diretta. Solo host, presenza di
      // header e hint HLS: niente URL completo, path, query o token nei log.
      val diagHost = url.toHttpUrlOrNull()?.host ?: "unknown-host"
      Log.i(
        DIAG_TAG,
        "DIAG-S direct host=$diagHost headers=${item.proxyHeaders.isNotEmpty()} " +
          "hlsHint=${url.lowercase().contains(".m3u8")}"
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

    /**
     * Chiave di deduplica di una sorgente.
     *
     *  - torrent/TorBox: `infoHash` + indice file (comportamento storico);
     *  - HTTP: URL **più** gli header che possono cambiare autorizzazione o
     *    riproduzione. Nomi normalizzati in lowercase, valori con spazi esterni
     *    rimossi e ordine deterministico.
     *
     * I valori degli header non vengono mai loggati: la chiave è solo per il
     * confronto in memoria.
     */
    internal fun sourceDedupKey(source: StreamSource): String {
      if (source.infoHash != null) {
        return "torrent:${source.infoHash}_${source.fileIdx ?: -1}"
      }
      val url = source.streamUrl.orEmpty()
      val relevantHeaders = source.headers.entries
        .map { it.key.trim().lowercase() to it.value.trim() }
        .filter { (name, _) -> name in PLAYBACK_HEADERS || name.startsWith("x-") }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .joinToString("\u001f") { (name, value) -> "$name=$value" }
      return "url:$url\u001e$relevantHeaders"
    }

    /** Aggiunge a [target] solo le sorgenti con chiave non ancora presente. */
    internal fun addDistinctSources(target: MutableList<StreamSource>, incoming: List<StreamSource>): Int {
      if (incoming.isEmpty()) return 0
      val keys = target.mapTo(HashSet(target.size + incoming.size)) { sourceDedupKey(it) }
      var added = 0
      incoming.forEach { source ->
        if (keys.add(sourceDedupKey(source))) {
          target.add(source)
          added++
        }
      }
      return added
    }

    /**
     * Header che influenzano l'autorizzazione o il contesto della richiesta
     * (Referer/Origin sono richiesti da molti CDN). Altri header `X-*` sono
     * inclusi perché spesso trasportano token di sessione.
     */
    private val PLAYBACK_HEADERS = setOf(
      "authorization",
      "cookie",
      "referer",
      "origin",
      "user-agent"
    )

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







