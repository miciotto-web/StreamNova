package com.aistudio.streamnova.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.toEpisode
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.repository.EpisodeResumeResolver
import com.example.data.repository.IntroSkipRepository
import com.example.data.repository.LastWorkingStreamRepository
import com.example.data.repository.MediaRepository
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxSourceOrdering
import com.example.data.torbox.TorBoxRepository
import com.example.domain.model.IntroWindow
import com.example.domain.model.Subtitle
import com.example.ui.screens.player.NextEpisodePromptUtils
import com.example.ui.screens.player.PlayerSourceFallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class MobilePlayerUiState(
  val loading: Boolean = true,
  val media: MediaItem? = null,
  val episode: Episode? = null,
  val source: StreamSource? = null,
  /** Sorgenti disponibili quando l'autoplay è OFF (modalità Debrid). */
  val sources: List<StreamSource> = emptyList(),
  val awaitingSourceSelection: Boolean = false,
  /**
   * true quando il selettore è stato aperto dalla selezione manuale della
   * scheda Dettaglio: in questo caso nessuna sorgente parte automaticamente,
   * indipendentemente dall'impostazione Autoplay.
   */
  val manualSelection: Boolean = false,
  /** Finestra reale della sigla da IntroDB; null = pulsante «Salta intro» assente. */
  val introWindow: IntroWindow? = null,
  /** Sottotitoli OpenSubtitles aggiunti alla sessione corrente. */
  val externalSubtitleCount: Int = 0,
  /** true se la chiave OpenSubtitles è configurata (il player non la mostra mai). */
  val openSubtitlesConfigured: Boolean = false,
  val error: String? = null
)

/**
 * ViewModel mobile del player: risolve la sorgente con il motore condiviso
 * (`:core:player` + `:core:data`) e persiste il progresso di visione.
 *
 * Le impostazioni della sezione RIPRODUZIONE sono applicate qui:
 *  - "Riusa l'ultimo link" consulta la cache Room prima della risoluzione live;
 *  - "Privilegia tracce italiane" e "Risoluzione preferita" ordinano le sorgenti
 *    con lo stesso criterio della TV;
 *  - "Avvio automatico (Autoplay)" OFF apre la scelta sorgente in modalità Debrid.
 *
 * I controlli touch restano nella UI mobile.
 */
class MobilePlayerViewModel : ViewModel() {

  private val streamManager = StreamManager()

  /**
   * Provider OpenSubtitles v3: legge la chiave salvata tramite
   * `AppSettingsRepository` a ogni richiesta (nessuna chiave in memoria nel
   * ViewModel e nessuna credenziale hardcoded).
   */
  private val openSubtitlesProvider = OpenSubtitlesSubtitleProvider()

  /** Evita di riscrivere la cache "ultimo flusso" a ogni aggiornamento di progresso. */
  private var workingStreamSaved = false

  /** Chiavi delle sorgenti già tentate: impedisce cicli nel fallback automatico. */
  private val attemptedSourceKeys = mutableSetOf<String>()

  /**
   * Ricerca delle sorgenti in corso.
   *
   * Una nuova ricerca annulla la precedente: due ricerche simultanee sulla
   * stessa scheda produrrebbero due elenchi in competizione (stessa regola TV,
   * dove il job precedente viene cancellato tassativamente).
   */
  private var searchJob: Job? = null

  /**
   * Generazione della ricerca in corso.
   *
   * Una risposta tardiva di una ricerca superata non può più scrivere lo stato:
   * il generatore è confrontato prima di ogni pubblicazione (stessa protezione
   * della TV con `loadStreamGeneration`).
   */
  private var searchGeneration = 0

  private val _state = MutableStateFlow(MobilePlayerUiState())
  val state: StateFlow<MobilePlayerUiState> = _state.asStateFlow()

  /**
   * Avvia la ricerca delle sorgenti per il contenuto indicato.
   *
   * @param manualSelection `true` quando la ricerca parte dal pulsante
   *   «Seleziona fonte» della scheda Dettaglio: il «riusa l'ultimo link» viene
   *   disattivato (bypasserebbe la scelta dell'utente) e il selettore delle
   *   sorgenti viene mostrato comunque, senza avviare alcuna riproduzione.
   */
  fun prepare(
    mediaId: String,
    season: Int?,
    episode: Int?,
    autoStart: Boolean = false,
    manualSelection: Boolean = false
  ) {
    workingStreamSaved = false
    attemptedSourceKeys.clear()
    val generation = ++searchGeneration
    searchJob?.cancel()
    _state.value = MobilePlayerUiState(loading = true, manualSelection = manualSelection)
    searchJob = viewModelScope.launch {
      // Pubblica lo stato solo se la ricerca è ancora quella corrente.
      fun publish(next: MobilePlayerUiState) {
        if (generation == searchGeneration) _state.value = next
      }

      val media = MediaRepository.getMediaById(mediaId)
        ?: MediaRepository.mediaList.value.firstOrNull { it.id == mediaId }
      if (media == null) {
        publish(MobilePlayerUiState(loading = false, error = "Titolo non trovato"))
        return@launch
      }

      val isTv = media.type == MediaType.SERIE_TV
      // La posizione salvata (o l'episodio scelto nella scheda) è autorevole: se la
      // lista locale non contiene la puntata viene ricostruita con gli stessi numeri.
      val resolvedEpisode = if (isTv) {
        EpisodeResumeResolver.resolve(
          media = media,
          requestedSeason = season,
          requestedEpisode = episode
        )
      } else null

      // Sottotitoli OpenSubtitles e finestra intro: partono in parallelo alla
      // risoluzione delle sorgenti e hanno un tetto di tempo. Non devono mai
      // bloccare l'avvio oltre 2,5 s né impedire la riproduzione se falliscono.
      val externalSubtitlesDeferred = async { fetchExternalSubtitles(media, resolvedEpisode) }
      val introWindowDeferred = async { fetchIntroWindow(media, resolvedEpisode) }

      val engineMode = AppSettingsRepository.streamingEngineMode.value
      val cacheSeason = resolvedEpisode?.seasonNumber ?: LastWorkingStreamRepository.NO_EPISODE
      val cacheEpisode = resolvedEpisode?.episodeNumber ?: LastWorkingStreamRepository.NO_EPISODE
      val openSubtitlesConfigured =
        !AppSettingsRepository.openSubtitlesApiKey.value.isNullOrBlank()

      // "Riusa l'ultimo link": un flusso ancora valido evita la risoluzione live.
      // La modalità della voce deve coincidere con quella attiva: un link Debrid non
      // viene mai riprodotto in HTTP e viceversa.
      // La selezione MANUALE non riusa mai: il riuso avvia direttamente il player
      // bypassando la scelta dell'utente (stessa regola TV).
      val settings = SettingsRepository.settings.value
      if (MobileManualSourcePolicy.shouldReuseLastWorkingStream(
          manualSelection = manualSelection,
          reuseEnabled = settings.reuseLastWorkingStreamEnabled
        )
      ) {
        val cached = runCatching {
          LastWorkingStreamRepository.getValidStream(
            mediaId = media.id,
            seasonNumber = cacheSeason,
            episodeNumber = cacheEpisode,
            durationHours = settings.lastWorkingStreamCacheDurationHours
          )
        }.getOrNull()
        if (cached != null &&
          !cached.streamUrl.isNullOrBlank() &&
          LastWorkingStreamRepository.resolveEngineMode(cached) == engineMode
        ) {
          // Con la sorgente in cache non c'è risoluzione da attendere: i due
          // recuperi paralleli (OpenSubtitles/IntroDB) hanno il loro tetto di tempo.
          val externalSubtitles = externalSubtitlesDeferred.await()
          val introWindow = introWindowDeferred.await()
          val cachedSource = cached.toStreamSource().withExternalSubtitles(externalSubtitles)
          publish(MobilePlayerUiState(
            loading = false,
            media = media,
            episode = resolvedEpisode,
            source = cachedSource,
            sources = listOf(cachedSource),
            introWindow = introWindow,
            externalSubtitleCount = externalSubtitles.size,
            openSubtitlesConfigured = openSubtitlesConfigured
          ))
          return@launch
        }
      }

      // Nessun fallback demo: la sorgente deve arrivare dai provider reali.
      val sources = runCatching {
        streamManager.resolve(
          tmdbId = media.tmdbId?.takeIf { it > 0 } ?: 0,
          isTv = isTv,
          season = if (isTv) resolvedEpisode?.seasonNumber ?: 1 else null,
          episode = if (isTv) resolvedEpisode?.episodeNumber ?: 1 else null,
          title = media.title.ifBlank { media.originalTitle },
          year = media.year,
          originalTitle = media.originalTitle.takeIf { it.isNotBlank() },
          providerTag = media.provider,
          genres = media.genres,
          streamingEngineMode = engineMode,
          imdbId = media.imdbId?.takeIf { it.isNotBlank() }
            ?: resolvedEpisode?.seriesImdbId?.takeIf { it.isNotBlank() }
        )
      }.getOrElse { emptyList() }

      // I recuperi paralleli di sottotitoli e finestra intro si chiudono qui:
      // durante la risoluzione live hanno già avuto modo di completare.
      val externalSubtitles = externalSubtitlesDeferred.await()
      val introWindow = introWindowDeferred.await()

      // Stesso ordinamento della TV: cached, italiani (se richiesto), risoluzione
      // preferita, qualità. La prima sorgente riproducibile è quella dell'autoplay.
      // I sottotitoli OpenSubtitles sono allegati a OGNI sorgente, così il cambio
      // server dal pannello impostazioni non perde le tracce esterne.
      val ordered = TorBoxSourceOrdering.sort(sources, settings)
        .filter { !it.streamUrl.isNullOrBlank() || !it.infoHash.isNullOrBlank() }
        .withExternalSubtitles(externalSubtitles)

      // Base comune degli esiti: titolo, puntata e metadati di sessione.
      val baseState = MobilePlayerUiState(
        loading = false,
        media = media,
        episode = resolvedEpisode,
        introWindow = introWindow,
        externalSubtitleCount = externalSubtitles.size,
        openSubtitlesConfigured = openSubtitlesConfigured
      )

      if (ordered.isEmpty()) {
        if (manualSelection) {
          // Selezione manuale senza risultati: il selettore resta aperto con
          // l'elenco VUOTO (nessuna riproduzione parte), così l'utente può
          // chiudere o riprovare la ricerca senza tornare alla scheda.
          publish(MobileManualSourcePolicy.stateAfterEmptyManualSearch(baseState))
          return@launch
        }
        publish(baseState.copy(error = "Nessuna sorgente riproducibile"))
        return@launch
      }

      if (MobileSettingsPolicy.shouldShowSourcePicker(
          // L'avanzamento automatico al prossimo episodio ha già una scelta
          // esplicita dell'utente: parte senza picker anche se l'Autoplay
          // sorgente è OFF. Gli avvii manuali restano invariati.
          autoplayEnabled = AppSettingsRepository.autoplayEnabled.value || autoStart,
          engineMode = engineMode,
          sourceCount = ordered.size,
          // La selezione manuale forza il selettore: nessuna sorgente parte
          // in riproduzione senza la conferma dell'utente.
          manualSelection = manualSelection
        )
      ) {
        publish(baseState.copy(
          sources = ordered,
          awaitingSourceSelection = true,
          manualSelection = manualSelection
        ))
        return@launch
      }

      val chosen = ordered.firstNotNullOfOrNull {
        resolvePlayable(
          source = it,
          season = resolvedEpisode?.seasonNumber,
          episode = resolvedEpisode?.episodeNumber
        )
      }
      if (chosen == null) {
        publish(baseState.copy(
          sources = ordered,
          error = "Nessuna sorgente riproducibile"
        ))
        return@launch
      }

      publish(baseState.copy(source = chosen, sources = ordered))
    }
  }

  /**
   * Scelta manuale della sorgente: avvia la riproduzione del flusso scelto.
   *
   * Lo sblocco Debrid (on-demand) può richiedere qualche secondo: durante
   * l'attesa lo stato passa a «caricamento» e il selettore resta aperto, così
   * l'utente non crede che il tap sia andato perso. Se lo sblocco fallisce il
   * selettore resta aperto con il messaggio d'errore e l'elenco invariato.
   */
  fun selectSource(source: StreamSource) {
    val current = _state.value
    if (current.loading) return
    viewModelScope.launch {
      // Sblocco Debrid in corso: il selettore resta aperto ma mostra il
      // caricamento, cosi' il tap dell'utente non sembra perso.
      _state.value = MobileManualSourcePolicy.stateAfterSelectionStart(current)
      // Stagione/episodio della puntata correntemente in riproduzione: vincolano
      // lo sblocco al file dell'episodio scelto (stesso criterio della TV).
      val playable = resolvePlayable(
        source = source,
        season = current.episode?.seasonNumber,
        episode = current.episode?.episodeNumber
      )
      _state.value = MobileManualSourcePolicy.stateAfterSelection(current, playable)
    }
  }

  /**
   * Chiusura del selettore senza avviare la riproduzione.
   *
   * Usata dall'annullamento esplicito: nessuna sorgente parte, l'elenco viene
   * svuotato e l'utente torna alla scheda Dettaglio.
   */
  fun dismissSourceSelection() {
    val current = _state.value
    if (!current.awaitingSourceSelection) return
    _state.value = MobileManualSourcePolicy.stateAfterDismiss(current)
  }

  /**
   * Sottotitoli OpenSubtitles per la sessione corrente.
   *
   * Il provider viene interrogato SOLO se l'utente ha salvato una chiave: senza
   * chiave l'API risponderebbe con un errore e non ha senso consumare rete.
   * La ricerca risolve al massimo pochi link (quota download) e restituisce liste
   * vuote in caso di errore/timeout, senza bloccare la riproduzione.
   */
  private suspend fun fetchExternalSubtitles(
    media: MediaItem,
    episode: Episode?
  ): List<Subtitle> {
    if (AppSettingsRepository.openSubtitlesApiKey.value.isNullOrBlank()) return emptyList()
    return try {
      withTimeoutOrNull(OPEN_SUBTITLES_TIMEOUT_MS) {
        openSubtitlesProvider.fetchSubtitles(
          mediaItem = media,
          episode = episode,
          preferredLanguage = SettingsRepository.settings.value.preferredSubtitleLanguage,
          year = media.year,
          resolveLinksOnDemand = true
        )
      } ?: emptyList()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Nessun dettaglio tecnico con la chiave: si registra solo il tipo di errore.
      android.util.Log.w(OPEN_SUBTITLES_LOG_TAG, "Recupero sottotitoli non riuscito: ${e.javaClass.simpleName}")
      emptyList()
    }
  }

  /**
   * Finestra reale della sigla da IntroDB. Solo serie TV con stagione/episodio
   * validi: nessuna stima quando il servizio non risponde.
   */
  private suspend fun fetchIntroWindow(
    media: MediaItem,
    episode: Episode?
  ): IntroWindow? {
    if (media.type != MediaType.SERIE_TV || episode == null) return null
    return try {
      withTimeoutOrNull(INTRO_WINDOW_TIMEOUT_MS) {
        IntroSkipRepository.getIntroWindow(
          imdbId = media.effectiveImdbId,
          tmdbId = media.tmdbId,
          season = episode.seasonNumber,
          episode = episode.episodeNumber
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
  }

  /** Aggiunge le tracce esterne senza duplicare quelle già dichiarate dalla sorgente. */
  private fun mergeSubtitles(
    existing: List<Subtitle>,
    external: List<Subtitle>
  ): List<Subtitle> {
    if (external.isEmpty()) return existing
    return (existing + external).distinctBy { it.id.ifBlank { it.url } }
  }

  private fun StreamSource.withExternalSubtitles(external: List<Subtitle>): StreamSource =
    if (external.isEmpty()) this else copy(subtitles = mergeSubtitles(subtitles, external))

  private fun List<StreamSource>.withExternalSubtitles(
    external: List<Subtitle>
  ): List<StreamSource> =
    if (external.isEmpty()) this else map { it.withExternalSubtitles(external) }

  /**
   * Prossimo episodio valido per l'autoplay di fine episodio.
   *
   * Prima usa gli episodi già noti al titolo; se manca, carica da TMDB (cache-first)
   * la stagione corrente e la successiva, con la stessa risoluzione della TV.
   * Restituisce `null` per film, episodi senza successore o serie senza TMDB id.
   */
  suspend fun resolveNextEpisode(): Episode? {
    val media = _state.value.media ?: return null
    val current = _state.value.episode ?: return null
    if (media.type != MediaType.SERIE_TV) return null

    val direct = NextEpisodePromptUtils.resolveNextEpisode(media.episodes, current)
    if (direct != null) return direct

    val tmdbId = media.tmdbId?.takeIf { it > 0 } ?: return null
    val loaded = mutableListOf<Episode>()
    for (seasonNumber in listOf(current.seasonNumber, current.seasonNumber + 1)) {
      try {
        val details = MediaRepository.getSeasonDetails(tmdbId, seasonNumber, media)
        loaded += details.episodes.map {
          it.toEpisode(media.id, seriesImdbId = media.effectiveImdbId)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Una stagione non disponibile non deve impedire la risoluzione dell'altra.
        android.util.Log.w(OPEN_SUBTITLES_LOG_TAG, "Stagione $seasonNumber non caricata: ${e.javaClass.simpleName}")
      }
    }
    if (loaded.isEmpty()) return null
    val merged = (media.episodes + loaded).distinctBy { it.id }
    return NextEpisodePromptUtils.resolveNextEpisode(merged, current)
  }

  /**
   * Sblocca un candidato Debrid e restituisce la sorgente riproducibile.
   *
   * Stagione ed episodio arrivano ESPLICITAMENTE dallo stato del player (stessa
   * regola della TV): lo sblocco deve riguardare il file di QUESTO episodio e la
   * cache dei link resta per-episodio; senza questi parametri un torrent di
   * stagione sbloccava il primo file (risoluzione/episodio arbitrari) e riusava
   * il link di un altro episodio per tutto il TTL.
   */
  private suspend fun resolvePlayable(source: StreamSource, season: Int?, episode: Int?): StreamSource? {
    if (!source.streamUrl.isNullOrBlank()) return source
    val infoHash = source.infoHash?.takeIf { it.isNotBlank() } ?: return null
    val unlocked = runCatching {
      TorBoxRepository.resolveInfoHash(
        infoHash = infoHash,
        fileIdx = source.fileIdx,
        season = season,
        episode = episode
      )
    }.getOrNull()
    return if (unlocked.isNullOrBlank()) {
      null
    } else {
      source.copy(
        streamUrl = unlocked,
        isCached = true,
        cacheState = com.example.data.streaming.CacheState.Cached
      )
    }
  }

  /** "Riusa l'ultimo link": salva il flusso appena scelto per il titolo/puntata. */
  private fun persistWorkingStream(
    media: MediaItem,
    episode: Episode?,
    source: StreamSource,
    engineMode: StreamingEngineMode
  ) {
    val url = source.streamUrl?.takeIf { it.isNotBlank() } ?: return
    viewModelScope.launch {
      LastWorkingStreamRepository.saveStream(
        mediaId = media.id,
        seasonNumber = episode?.seasonNumber ?: LastWorkingStreamRepository.NO_EPISODE,
        episodeNumber = episode?.episodeNumber ?: LastWorkingStreamRepository.NO_EPISODE,
        streamUrl = url,
        streamTitle = source.releaseTitle ?: source.serverName,
        streamQuality = source.quality,
        serverName = source.serverName,
        headers = source.headers,
        isProgressive = source.isProgressive,
        streamMode = engineMode.name,
        infoHash = source.infoHash,
        fileIdx = source.fileIdx,
        addonName = source.addonName
      )
    }
  }

  /**
   * Errore di riproduzione: la voce "Riusa l'ultimo link" per questo titolo non è
   * più affidabile. Va rimossa, altrimenti un retry riproverebbe lo stesso link
   * rotto all'infinito (stessa invalidazione della TV su fallimento del flusso).
   */
  fun invalidateWorkingStream() {
    val media = _state.value.media ?: return
    val episode = _state.value.episode
    viewModelScope.launch {
      LastWorkingStreamRepository.invalidate(
        mediaId = media.id,
        seasonNumber = episode?.seasonNumber ?: LastWorkingStreamRepository.NO_EPISODE,
        episodeNumber = episode?.episodeNumber ?: LastWorkingStreamRepository.NO_EPISODE
      )
    }
  }

  /**
   * Errore di riproduzione riportato dal player.
   *
   * Comportamento:
   *  - la voce "Riusa l'ultimo link" del titolo viene invalidata;
   *  - un errore **definitivo per la sorgente** (403/404/410, container non
   *    leggibile, ...) con un'alternativa già risolta passa automaticamente alla
   *    migliore rimasta: la sorgente fallita e quelle già tentate non vengono mai
   *    ritentate nello stesso ciclo di riproduzione;
   *  - gli errori transitori (rete, 5xx) restano sulla sorgente corrente, come la TV.
   *
   * @param failedSourceKey chiave logica della sorgente del player che ha fallito:
   *   se non coincide più con la sorgente corrente l'evento arriva da un player già
   *   sostituito e viene ignorato (nessun cambio a catena).
   * @return `true` se il player è stato commutato su un'altra sorgente.
   */
  fun handlePlaybackError(error: PlaybackException, failedSourceKey: String): Boolean {
    val current = _state.value
    val failed = current.source ?: return false
    val failedKey = PlayerSourceFallback.keyOf(failed)
    if (failedSourceKey != failedKey) return false

    invalidateWorkingStream()
    attemptedSourceKeys += failedKey

    if (PlayerSourceFallback.classify(error) != PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE) {
      return false
    }
    val alternative = PlayerSourceFallback.selectAlternative(
      candidates = current.sources,
      currentKey = failedKey,
      attemptedKeys = attemptedSourceKeys
    ) ?: return false

    attemptedSourceKeys += PlayerSourceFallback.keyOf(alternative)
    // La nuova sorgente deve poter diventare il prossimo "ultimo link valido".
    workingStreamSaved = false
    _state.value = current.copy(source = alternative)
    return true
  }

  /** Salva il progresso reale misurato dal player sul record film o episodio. */
  fun saveProgress(positionMs: Long, durationMs: Long) {    val media = _state.value.media ?: return
    val episode = _state.value.episode
    // Primo progresso positivo = flusso realmente partito: solo ora ha senso
    // registrarlo come "ultimo flusso funzionante" (la TV fa lo stesso su READY).
    if (!workingStreamSaved && positionMs > 0L) {
      _state.value.source?.let { source ->
        workingStreamSaved = true
        persistWorkingStream(
          media = media,
          episode = episode,
          source = source,
          engineMode = AppSettingsRepository.streamingEngineMode.value
        )
      }
    }
    if (episode != null) {
      MediaRepository.updateEpisodeProgress(
        mediaId = media.id,
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        progressMs = positionMs,
        durationMs = durationMs
      )
      // Durata reale del player + posizione a fine episodio: verifica se la serie è
      // davvero conclusa. La rimozione avviene solo con metadati completi (fine
      // stagione ≠ fine serie) ed è persistente.
      MediaRepository.maybeCompleteSeries(
        mediaId = media.id,
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        positionMs = positionMs,
        durationMs = durationMs
      )
    } else {
      MediaRepository.updateProgress(
        id = media.id,
        progressMs = positionMs,
        durationMs = durationMs
      )
      // Conclusione di un FILM (TV 1.0.7): posizione alla fine della durata MISURATA.
      // Non cancella nulla: registra `completedAt`, toglie la card da «Continua a
      // guardare» e annuncia il completamento a SIMKL tramite il sink locale.
      val measuredDuration = if (durationMs > 0L) durationMs else media.totalDurationMs
      MediaRepository.maybeCompleteMovie(media.id, positionMs, measuredDuration)
    }
  }

  private companion object {
    /** Tetto di attesa per i sottotitoli: non deve ritardare l'avvio del video. */
    const val OPEN_SUBTITLES_TIMEOUT_MS = 2_500L

    /** Tetto di attesa per IntroDB (il repository ha già un timeout di 2,5 s). */
    const val INTRO_WINDOW_TIMEOUT_MS = 2_500L

    const val OPEN_SUBTITLES_LOG_TAG = "MobilePlayer"
  }
}
