package com.example.data.repository

import android.util.Log
import com.example.data.local.MEDIA_SOURCE_SIMKL
import com.example.data.local.MEDIA_SOURCE_SIMKL_NEXT_UP
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.remote.simkl.SimklActivitiesSnapshot
import com.example.data.remote.simkl.SimklAuthException
import com.example.data.remote.simkl.SimklCompletedKind
import com.example.data.remote.simkl.SimklCompletedTitle
import com.example.data.remote.simkl.SimklDecisionReason
import com.example.data.remote.simkl.SimklLocalProgress
import com.example.data.remote.simkl.SimklPlaybackEntry
import com.example.data.remote.simkl.SimklPlaybackKind
import com.example.data.remote.simkl.SimklPlaybackParser
import com.example.data.remote.simkl.SimklPendingPlayback
import com.example.data.remote.simkl.SimklPendingPlaybackStore
import com.example.data.remote.simkl.SimklProgressMergePolicy
import com.example.data.remote.simkl.SimklSeriesState
import com.example.data.remote.simkl.SimklSyncResult
import com.example.data.remote.simkl.SimklSyncSection
import com.example.data.remote.simkl.SimklSyncStatus
import com.example.data.remote.simkl.SimklWatchlistEntry
import com.example.data.remote.simkl.SimklWatchlistKind
import com.example.data.remote.simkl.SimklWatchedLibrary
import com.example.data.remote.simkl.SimklWatchedLibraryData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sincronizzazione account SIMKL → StreamNova.
 *
 * Quattro letture (playback, watchlist, libreria serie+anime, completati film), tutte
 * gate su `GET /sync/activities` (mai polling a timer, come richiesto dalla
 * documentazione ufficiale):
 *
 * 1. **Continua a guardare** — `GET /sync/playback` sono i *punti di pausa salvati*: la
 *    fonte documentata del "Continue Watching". La cronologia NON è usata per la posizione.
 * 2. **Watchlist / "Plan to Watch"** — `GET /sync/all-items/{type}/{status}` con
 *    `status=plantowatch`: film, serie e anime che l'utente vuole guardare, portati nei
 *    **Preferiti** (l'archivio che la sezione legge davvero). È un insieme distinto sia
 *    dalla cronologia sia dai punti di pausa.
 * 3. **Libreria/history** — `GET /sync/all-items/shows` e `/sync/all-items/anime`:
 *    alimentano la cache dei badge "Visto su SIMKL" nel Dettaglio (nessuna chiamata di
 *    rete per stagione), forniscono il **prossimo episodio** delle serie in visione
 *    (`next_to_watch`, non l'ultimo già finito) e i titoli `completed`. Gli anime sono
 *    letti con `extended=full_anime_seasons` per mappare `E2` sulla coordinata TVDB/TMDB.
 *    Senza questa lettura i progressi degli episodi non comparirebbero mai, perché
 *    `/sync/playback` contiene solo le sessioni di pausa ancora aperte.
 *
 * Invarianti:
 * - ogni sezione ha il proprio esito: il fallimento di una NON cancella i dati dell'altra e
 *   NON viene mai presentato come sincronizzazione riuscita (esito `PARTIAL` + sezione);
 * - lo snapshot delle attività si salva solo dopo che **tutte** le letture richieste sono
 *   riuscite, così la volta successiva viene ripreso ciò che è andato perso;
 * - una voce di playback non applicabile *adesso* (durata reale non ancora nota, titolo non
 *   ancora risolvibile, episodio non identificato) viene **rimandata**, non persa: è
 *   memorizzata a parte dallo snapshot delle attività e riprovata al giro successivo. Il
 *   checkpoint dice cosa è cambiato su SIMKL, non cosa non siamo riusciti ad applicare:
 *   confondere i due rendeva quelle voci irrecuperabili;
 * - la politica di merge non cancella mai dati, non regredisce un progresso locale più
 *   recente e non converte percentuali in millisecondi senza una durata reale;
 * - il tempo di visione scritto è quello di SIMKL (`paused_at`), non l'ora della
 *   sincronizzazione: altrimenti ogni import successivo risulterebbe "più vecchio" del
 *   locale e non verrebbe mai adottato.
 */
object SimklSyncManager {

  private const val TAG = "SimklSync"

  /** Debounce del sync leggero al ritorno in primo piano. */
  internal const val FOREGROUND_SYNC_DEBOUNCE_MS = 5L * 60L * 1000L

  /**
   * Una sola sincronizzazione per volta: due sync concorrenti (avvio, ritorno in primo
   * piano, pulsante manuale) leggerebbero le stesse attività e riscriverebbero gli stessi
   * dati, con il rischio di applicare due volte la stessa voce o di sovrascriversi.
   */
  private val syncMutex = Mutex()

  /** Guardia del debounce: un solo tentativo di sync foreground per volta. */
  private val foregroundCheckInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Sync leggero al ritorno in primo piano: parte solo se sono passati almeno 5 minuti
   * dall'ultima sincronizzazione riuscita (o tentata). Il gate economico resta
   * `GET /sync/activities`: se nulla è cambiato, il pull delle liste non avviene.
   */
  suspend fun syncOnForeground(now: Long = System.currentTimeMillis()): SimklSyncResult? {
    val account = SimklRepository.accountState.value
    if (!account.isLoggedIn || account.sessionExpired) return null
    val last = maxOf(
      SimklRepository.lastSyncAtEpochMs() ?: 0L,
      lastForegroundSyncAt
    )
    if (now - last < FOREGROUND_SYNC_DEBOUNCE_MS) return null
    if (!foregroundCheckInFlight.compareAndSet(false, true)) return null
    return try {
      lastForegroundSyncAt = now
      // Il primo onResume può precedere il caricamento del catalogo da Room: una sync
      // eseguita sul catalogo vuoto reimporta titoli già presenti. Si attende (con tetto)
      // che MediaRepository abbia pubblicato lo stato locale.
      if (MediaRepository.mediaList.value.isEmpty()) {
        withTimeoutOrNull(15_000L) { MediaRepository.mediaList.first { it.isNotEmpty() } }
      }
      sync(now = System.currentTimeMillis())
    } finally {
      foregroundCheckInFlight.set(false)
    }
  }

  @Volatile private var lastForegroundSyncAt: Long = 0L

  /**
   * Motivi che possono risolversi senza un intervento: la durata reale arriva (prima
   * riproduzione dell'episodio, risposta di TMDB), il titolo entra in catalogo, l'episodio
   * viene identificato. Gli altri motivi non hanno nulla da recuperare: una percentuale
   * fuori scala o un locale più recente non cambiano riprovando.
   */
  private val RECOVERABLE_REASONS = setOf(
    SimklDecisionReason.UNKNOWN_DURATION,
    SimklDecisionReason.UNRESOLVED_TITLE,
    SimklDecisionReason.INCOMPLETE_SESSION
  )

  suspend fun sync(
    force: Boolean = false,
    now: Long = System.currentTimeMillis()
  ): SimklSyncResult = syncMutex.withLock {
    syncLocked(force, now)
  }

  private suspend fun syncLocked(
    force: Boolean,
    now: Long
  ): SimklSyncResult {
    val account = SimklRepository.accountState.value
    if (!account.isLoggedIn) {
      return SimklSyncResult(SimklSyncStatus.NOT_LOGGED_IN, "Account SIMKL non collegato.", finishedAtEpochMs = now)
    }
    if (account.sessionExpired) {
      return SimklSyncResult(
        SimklSyncStatus.SESSION_EXPIRED,
        "Sessione SIMKL scaduta: ricollega l'account per riprendere la sincronizzazione.",
        finishedAtEpochMs = now
      )
    }
    // Epoca di sessione catturata all'ingresso: una disconnessione avvenuta DOPO questo
    // punto invalida ogni scrittura successiva di questa sincronizzazione (vedi i gate).
    val epoch = SimklRepository.currentSessionEpoch()
    SimklRepository.setSyncing(true)
    return try {
      runSync(force, now, epoch)
    } catch (e: SimklAuthException) {
      // Il refresh può aver marcato la sessione come scaduta: il messaggio lo riflette.
      val expired = SimklRepository.accountState.value.sessionExpired
      val result = SimklSyncResult(
        status = if (expired) SimklSyncStatus.SESSION_EXPIRED else SimklSyncStatus.FAILED,
        message = e.localizedMessage ?: "Sincronizzazione SIMKL non riuscita.",
        finishedAtEpochMs = now
      )
      gatedWrite({ SimklRepository.currentSessionEpoch() == epoch }) {
        SimklRepository.saveLastSync(result)
      }
      Log.w(TAG, "Sincronizzazione SIMKL non riuscita: ${e.message}")
      result
    } catch (e: CancellationException) {
      // La cancellazione della coroutine non è un guasto da registrare come risultato:
      // deve propagare e fermare davvero il lavoro (inclusi i pull in corso).
      throw e
    } catch (e: Exception) {
      val result = SimklSyncResult(
        SimklSyncStatus.FAILED,
        "Sincronizzazione SIMKL non riuscita: ${e.localizedMessage ?: e.javaClass.simpleName}",
        finishedAtEpochMs = now
      )
      gatedWrite({ SimklRepository.currentSessionEpoch() == epoch }) {
        SimklRepository.saveLastSync(result)
      }
      Log.w(TAG, "Sincronizzazione SIMKL non riuscita", e)
      result
    } finally {
      SimklRepository.setSyncing(false)
    }
  }

  /**
   * Verifica l'epoca di sessione DENTRO il gate di [SimklRepository] ed esegue la
   * scrittura solo se la sessione è ancora quella catturata all'ingresso: una scrittura
   * di questa sincronizzazione finisce o interamente PRIMA della pulizia del logout
   * (e ne viene rimossa) o dopo un controllo fallito (e non avviene mai). Restituisce
   * `false` quando la sessione è stata disconnessa: il chiamante abortisce.
   */
  private suspend fun gatedWrite(sessionValid: () -> Boolean, block: suspend () -> Unit): Boolean =
    SimklRepository.withSessionGate {
      if (sessionValid()) {
        block()
        true
      } else {
        false
      }
    }

  /**
   * Esito di una sincronizzazione abortita dalla disconnessione: nessun dato pubblicato
   * dopo il logout (le scritture precedenti sono state rimosse dalla pulizia) e nessun
   * checkpoint salvato. `NOT_LOGGED_IN` perché l'account non è più collegato.
   */
  private fun abortedSync(now: Long) = SimklSyncResult(
    SimklSyncStatus.NOT_LOGGED_IN,
    "Sincronizzazione annullata: account SIMKL scollegato.",
    finishedAtEpochMs = now
  )

  private suspend fun runSync(force: Boolean, now: Long, epoch: Int): SimklSyncResult {
    val sessionValid: () -> Boolean = { SimklRepository.currentSessionEpoch() == epoch }
    val previous = SimklRepository.loadActivitiesSnapshot()
    val activities = SimklRepository.fetchActivities()
    // La disconnessione può essere avvenuta durante la lettura delle attività: prima di
    // toccare qualsiasi cosa, la sincronizzazione si ridichiara autorizzata.
    if (!sessionValid()) return abortedSync(now)
    val pending = SimklRepository.loadPendingPlaybacks()
    val pendingAttempts = pending.associate { it.entry.resumeKey to it.attempts }
    // Una voce rimandata va riprovata anche se le attività non si sono mosse: il checkpoint
    // racconta cosa è cambiato su SIMKL, non cosa non siamo riusciti ad applicare.
    val playbackChanged = force || pending.isNotEmpty() ||
      activities.movedSince(previous, SimklActivitiesSnapshot.PLAYBACK_KEYS)
    val libraryChanged = force || activities.movedSince(previous, SimklActivitiesSnapshot.WATCHLIST_KEYS)

    if (!playbackChanged && !libraryChanged) {
      val upToDate = SimklSyncResult(
        SimklSyncStatus.UP_TO_DATE,
        "Nessuna modifica da sincronizzare.",
        finishedAtEpochMs = now
      )
      gatedWrite(sessionValid) {
        SimklRepository.saveActivitiesSnapshot(activities)
        SimklRepository.saveLastSync(upToDate)
      }
      Log.i(TAG, "Sincronizzazione: ${upToDate.message}")
      return upToDate
    }

    // ── PULL: ogni sezione è indipendente. Si scarica TUTTO prima di scrivere qualcosa,
    //    e il fallimento di una lettura non interrompe le altre.
    val failed = mutableListOf<SimklSyncSection>()
    var playbacks: List<SimklPlaybackEntry>? = null
    var watchlist: List<SimklWatchlistEntry>? = null
    var watchedShows: SimklWatchedLibraryData? = null
    var completedMovies: List<SimklCompletedTitle>? = null

    if (playbackChanged) {
      // Nessun filtro a monte: la classificazione (comprese le sessioni incomplete da
      // segnalare) è compito di [applyPlaybacks], che ne riporta la motivazione.
      pull("Continua a guardare") { SimklRepository.fetchPlaybackSessions() }
        .onSuccess { playbacks = it }
        .onFailure { failed += SimklSyncSection.PLAYBACK }
    }
    if (libraryChanged) {
      pull("watchlist") { SimklRepository.fetchWatchlist() }
        .onSuccess { watchlist = it }
        .onFailure { failed += SimklSyncSection.WATCHLIST }
      pull("libreria vista") { SimklRepository.fetchWatchedLibrary() }
        .onSuccess { watchedShows = it }
        .onFailure { failed += SimklSyncSection.WATCHED_LIBRARY }
      // I completamenti dei film sono una verifica a sé: un film può essere stato
      // marcato `completed` su SIMKL senza avere playback né watchlist.
      pull("libreria film") { SimklRepository.fetchCompletedMovies() }
        .onSuccess { completedMovies = it }
        .onFailure { failed += SimklSyncSection.MOVIES_LIBRARY }
    }

    // ── APPLICAZIONE: solo le sezioni effettivamente scaricate, sempre in modo additivo.
    //    Le voci rimandate si riprovano INSIEME a quelle appena lette (la durata o i
    //    metadati che mancavano possono essere disponibili adesso); una voce già presente
    //    nella risposta fresca non viene duplicata. Dalla libreria arrivano i punti di
    //    ripresa degli episodi visti (le serie `watching`): passano per la stessa catena,
    //    così valgono la stessa politica di merge, lo stesso rinvio e lo stesso report.
    val freshPlaybacks = playbacks
    val pendingEntries = pending.map { it.entry }
    val freshKeys = freshPlaybacks?.map { it.resumeKey }?.toSet() ?: emptySet()
    // La documentazione SIMKL è esplicita: se una serie ha una sessione di pausa, quella
    // riga vince sul `next_to_watch`. Applicare entrambe poteva spostare la card
    // sull'episodio sbagliato (o addirittura indietro) a seconda dell'ordine.
    val playbackTitleKeys = (freshPlaybacks.orEmpty() + pendingEntries)
      .filter { it.origin == com.example.data.remote.simkl.SimklResumeOrigin.PLAYBACK }
      .mapNotNull { resumeTitleKey(it) }
      .toSet()
    val libraryEntries = watchedShows?.watchingResumes
      ?.map { SimklWatchedLibrary.resumeEntry(it) }
      ?.filter { it.resumeKey !in freshKeys && resumeTitleKey(it) !in playbackTitleKeys }
      .orEmpty()
    val pendingToApply = pendingEntries.filter { entry ->
      if (entry.resumeKey in freshKeys) return@filter false
      // Una voce di libreria rimandata non deve sovrascrivere una sessione di pausa
      // fresca per lo stesso titolo: la pausa è la posizione effettivamente aperta.
      !(entry.origin == com.example.data.remote.simkl.SimklResumeOrigin.LIBRARY &&
        resumeTitleKey(entry) in playbackTitleKeys)
    }
    val entriesToApply = freshPlaybacks.orEmpty() + pendingToApply + libraryEntries
    val playOutcome = if (freshPlaybacks != null || libraryEntries.isNotEmpty() || pendingEntries.isNotEmpty()) {
      // `null` = la disconnessione è avvenuta durante l'applicazione: nessun dato viene
      // pubblicato, la sincronizzazione si ferma qui (la pulizia del logout è già passata
      // o passerà — in entrambi i casi nessun dato SIMKL resta visibile per sua colpa).
      applyPlaybacks(entriesToApply, pendingAttempts, libraryEntries.map { it.resumeKey }.toSet(), sessionValid)
        ?: return abortedSync(now)
    } else {
      ApplyOutcome.EMPTY
    }
    val watchOutcome: WatchlistOutcome
    if (watchlist != null) {
      watchOutcome = applyWatchlist(watchlist, sessionValid) ?: return abortedSync(now)
    } else {
      watchOutcome = WatchlistOutcome.EMPTY
    }

    // ── COMPLETAMENTI verificati su SIMKL ─────────────────────────────────────
    // Un titolo può essere stato marcato `completed` sul sito o da un altro client:
    // registriamo `completedAt` e togliamo la card dalla riga, senza cancellare progressi
    // o storico. Se la stessa serie/anime ha ancora una cour `watching`, non la
    // dichiariamo conclusa: l'utente sta guardando altro della stessa opera TMDB.
    val completedFromLibrary = watchedShows?.completedTitles.orEmpty()
    val allCompleted = completedMovies.orEmpty() + completedFromLibrary
    val watchingTmdbIds = watchedShows?.watchingTmdbIds.orEmpty()
    val completedMarked = if (allCompleted.isNotEmpty()) {
      applyCompletions(allCompleted, watchingTmdbIds, sessionValid) ?: return abortedSync(now)
    } else {
      0
    }

    // L'elenco rimandato si riscrive solo se abbiamo davvero potuto riprovare TUTTE le voci:
    // con il pull del playback fallito, un insieme vuoto significherebbe "cancella tutto".
    // La libreria conta come fonte completa quando è stata scaricata (i suoi punti di
    // ripresa sono riprovabili in quel momento).
    if (freshPlaybacks != null || watchedShows != null) {
      if (!gatedWrite(sessionValid) { SimklRepository.savePendingPlaybacks(playOutcome.pending) }) {
        return abortedSync(now)
      }
    }

    val complete = failed.isEmpty()
    if (complete) {
      if (!gatedWrite(sessionValid) { SimklRepository.saveActivitiesSnapshot(activities) }) {
        return abortedSync(now)
      }
    }

    val unresolved = playOutcome.unresolvedTitles + watchOutcome.unresolved
    val status = when {
      !complete -> SimklSyncStatus.PARTIAL
      unresolved.isNotEmpty() -> SimklSyncStatus.PARTIAL
      // In attesa di dati completi: non è un guasto, ma non è nemmeno "tutto adottato".
      playOutcome.pending.isNotEmpty() -> SimklSyncStatus.PARTIAL
      else -> SimklSyncStatus.SYNCED
    }
    val result = SimklSyncResult(
      status = status,
      message = buildMessage(
        playOutcome, watchOutcome, watchedShows != null, libraryChanged, failed,
        completedMarked = completedMarked
      ),
      adoptedMovies = playOutcome.movies,
      adoptedEpisodes = playOutcome.episodes,
      adoptedLibraryEpisodes = playOutcome.libraryEpisodes,
      completedTitlesMarked = completedMarked,
      watchedShowsUpdated = watchedShows?.watchedEpisodesByShow?.size ?: 0,
      watchlistImported = watchOutcome.imported,
      watchlistAlreadyFavorite = watchOutcome.alreadyFavorite,
      watchlistUnresolved = watchOutcome.unresolved,
      failedSections = failed,
      skipped = playOutcome.skipped,
      unresolvedTitles = playOutcome.unresolvedTitles,
      deferredForRetry = playOutcome.pending.size,
      abandonedRecoveries = playOutcome.abandoned,
      finishedAtEpochMs = now
    )
    if (!gatedWrite(sessionValid) { SimklRepository.saveLastSync(result) }) return abortedSync(now)
    Log.i(TAG, "Sincronizzazione completata: ${result.message}")
    Log.i(
      TAG,
      "Dettaglio: adottati=${result.adoptedTotal} inAttesa=${result.deferredForRetry} " +
        "abbandonati=${result.abandonedRecoveries} " +
        "salti=${playOutcome.skipped.entries.joinToString(", ") { "${it.key.name}x${it.value}" }} " +
        "sezioniFallite=${failed.map { it.name }}"
    )
    return result
  }

  /**
   * Lettura di una sezione: il fallimento viene registrato e **non** propaga, così le altre
   * sezioni restano indipendenti. Nessun dato locale è toccato da un errore di rete.
   *
   * Il log nomina la classe dell'eccezione: distingue un errore HTTP (messaggio con il
   * codice), un errore di parsing (eccezione di deserializzazione) e un guasto di rete
   * (`IOException`) — senza questa riga tutti e tre sarebbero indistinguibili.
   */
  private suspend fun <T> pull(section: String, block: suspend () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (e: CancellationException) {
      // `runCatching` cattura anche CancellationException: usarlo qui rendeva la
      // cancellazione una "sezione non scaricata" e il lavoro continuava.
      throw e
    } catch (error: Exception) {
      Log.w(
        TAG,
        "Sezione '$section' non scaricata: ${error.javaClass.simpleName} — " +
          (error.localizedMessage ?: "nessun dettaglio")
      )
      Result.failure(error)
    }

  private class ApplyOutcome(
    val movies: Int,
    val episodes: Int,
    val skipped: Map<SimklDecisionReason, Int>,
    val unresolvedTitles: List<String>,
    /** Posizioni episodiche prese dalla libreria (`status=watching`), contate a parte. */
    val libraryEpisodes: Int = 0,
    /** Voci non applicabili adesso, conservate per il prossimo tentativo. */
    val pending: List<SimklPendingPlayback> = emptyList(),
    /** Voci il cui recupero è stato abbandonato (tentativi esauriti). */
    val abandoned: Int = 0
  ) {
    companion object {
      val EMPTY = ApplyOutcome(0, 0, emptyMap(), emptyList())
    }
  }

  private class WatchlistOutcome(
    val imported: Int,
    val alreadyFavorite: Int,
    val unresolved: List<String>
  ) {
    companion object {
      val EMPTY = WatchlistOutcome(0, 0, emptyList())
    }
  }

  // ── Continua a guardare ────────────────────────────────────────────────────

  /**
   * Applica le sessioni di pausa (e i punti di ripresa letti dalla libreria) al catalogo
   * locale e decide cosa **rimandare**.
   *
   * @param previousAttempts tentativi già fatti per ciascuna voce rimandata, per chiave
   *        ([SimklPlaybackEntry.resumeKey]): il recupero è limitato, non un ciclo infinito.
   * @param libraryKeys chiavi delle voci che arrivano dalla libreria SIMKL: contate a parte
   *        nel report, perché non sono sessioni di pausa ma episodi visti.
   * @param sessionValid verifica dell'epoca di sessione, eseguita dentro il gate prima di
   *        ogni scrittura: se la disconnessione è avvenuta nel frattempo l'applicazione
   *        si abortisce (ritorno `null`) senza pubblicare altro.
   * @return l'esito dell'applicazione, o `null` se la sessione è stata disconnessa.
   */
  private suspend fun applyPlaybacks(
    entries: List<SimklPlaybackEntry>,
    previousAttempts: Map<String, Int> = emptyMap(),
    libraryKeys: Set<String> = emptySet(),
    sessionValid: () -> Boolean
  ): ApplyOutcome? {
    var movies = 0
    var episodes = 0
    var libraryEpisodes = 0
    var abandoned = 0
    val skipped = LinkedHashMap<SimklDecisionReason, Int>()
    val unresolved = mutableListOf<String>()
    val pending = mutableListOf<SimklPendingPlayback>()
    fun bump(reason: SimklDecisionReason?) {
      if (reason == null) return
      skipped[reason] = (skipped[reason] ?: 0) + 1
    }

    // Dal più vecchio al più recente: se più voci insistono sullo stesso titolo,
    // l'ultima applicata è quella con la pausa più recente.
    val ordered = entries.sortedBy { it.pausedAtEpochMs ?: 0L }
    val seen = mutableSetOf<String>()
    for (entry in ordered) {
      // Una voce rimandata può comparire anche nella risposta fresca: si applica una volta.
      if (!seen.add(entry.resumeKey)) continue
      var reason: SimklDecisionReason? = null
      if (!SimklPlaybackParser.isResumable(entry)) {
        reason = SimklDecisionReason.INCOMPLETE_SESSION
      } else {
        val target = resolveTarget(entry, sessionValid)
        if (target == null) {
          // Il titolo può essere `null` perché non risolvibile oppure perché la sessione è
          // stata disconnessa a metà risoluzione: il secondo caso abortisce TUTTO.
          if (!sessionValid()) return null
          reason = SimklDecisionReason.UNRESOLVED_TITLE
          unresolved.add(entryLabel(entry))
        } else if (target.completedAt != null) {
          // Concluso in LOCALE (fine riproduzione verificata o toggle dell'utente) e già
          // notificato a SIMKL: le voci di playback e `next_to_watch` non devono
          // resuscitare la card dalla riga. Se SIMKL conferma lo stato `completed`, la
          // riconciliazione dei completamenti lo ribadisce senza riaccendere nulla.
          reason = SimklDecisionReason.LOCALLY_COMPLETED
        } else {
          val local = localProgressFor(entry, target)
          val decision = SimklProgressMergePolicy.decide(
            entry = entry,
            local = local,
            series = SimklSeriesState(
              season = target.lastWatchedSeason,
              episode = target.lastWatchedEpisode,
              // Anche una serie ferma sull'episodio successivo (progresso 0) ha una data
              // di visione: è quella che protegge dalla sessione obsoleta.
              lastWatchedAtMs = target.lastWatchedAt
            )
          )
          if (!decision.adopt) {
            reason = decision.reason
          } else {
            // Il tempo di visione è quello di SIMKL: è la sua data a decidere chi è più recente.
            val watchedAt = entry.pausedAtEpochMs ?: local.lastWatchedAtMs ?: System.currentTimeMillis()
            val season = entry.season
            val episode = entry.episode
            when {
              entry.kind == SimklPlaybackKind.MOVIE -> {
                val adopted = gatedWrite(sessionValid) {
                  MediaRepository.updateProgress(
                    target.id, decision.progressMs, local.totalDurationMs, watchedAt,
                    source = MEDIA_SOURCE_SIMKL
                  )
                  movies++
                }
                if (!adopted) return null
              }

              season == null || episode == null -> reason = SimklDecisionReason.INCOMPLETE_SESSION

              else -> {
                val isLibraryEntry = entry.resumeKey in libraryKeys
                val adopted = gatedWrite(sessionValid) {
                  if (entry.isNextToWatch) {
                    // `next_to_watch` di SIMKL: puntatore al PROSSIMO episodio, non una
                    // posizione raggiunta. È ciò che il sito SIMKL mostra nella sua riga
                    // "Continua a guardare": la card va in riga sull'episodio successivo
                    // a posizione 0 (e serve anche a «Riproduci S…E…» nel Dettaglio).
                    MediaRepository.updateNextUpFromSimkl(
                      mediaId = target.id,
                      seasonNumber = season,
                      episodeNumber = episode,
                      lastWatchedAtMs = watchedAt,
                      source = MEDIA_SOURCE_SIMKL_NEXT_UP
                    )
                  } else {
                    MediaRepository.updateEpisodeProgress(
                      mediaId = target.id,
                      seasonNumber = season,
                      episodeNumber = episode,
                      progressMs = decision.progressMs,
                      durationMs = local.totalDurationMs,
                      lastWatchedAtMs = watchedAt,
                      source = MEDIA_SOURCE_SIMKL
                    )
                  }
                  if (isLibraryEntry) libraryEpisodes++ else episodes++
                }
                if (!adopted) return null
              }
            }
          }
        }
      }
      reason?.let { motivo ->
        bump(motivo)
        if (motivo in RECOVERABLE_REASONS) {
          val attempts = (previousAttempts[entry.resumeKey] ?: 0) + 1
          if (attempts < SimklPendingPlaybackStore.MAX_ATTEMPTS) {
            pending.add(SimklPendingPlayback(entry, attempts))
          } else {
            // Recupero abbandonato: la voce non forza più il pull del playback, altrimenti la
            // sincronizzazione resterebbe "da fare" per sempre senza mai poterla applicare.
            bump(SimklDecisionReason.RECOVERY_EXHAUSTED)
            abandoned++
            Log.i(
              TAG,
              "Recupero abbandonato dopo $attempts tentativi: ${entryLabel(entry)} (${motivo.label})."
            )
          }
        }
      }
    }
    return ApplyOutcome(movies, episodes, skipped, unresolved, libraryEpisodes, pending, abandoned)
  }

  /** Titolo locale corrispondente alla sessione SIMKL, importandolo se non è in catalogo. */
  private suspend fun resolveTarget(entry: SimklPlaybackEntry, sessionValid: () -> Boolean): MediaItem? {
    val matched = SimklLocalMatcher.find(MediaRepository.mediaList.value, entry)
      ?: run {
        val isTv = entry.kind == SimklPlaybackKind.EPISODE
        val tmdbId = entry.tmdbId?.takeIf { it > 0 }
          ?: entry.imdbId?.let { MediaRepository.resolveImdbToTmdbId(it, isTv) }
          ?: entry.tvdbId?.let { MediaRepository.resolveTvdbToTmdbId(it, isTv) }
          ?: return null
        // La rete (risoluzione IMDb, dettagli TMDB) resta FUORI dal gate: la disconnessione
        // non deve restare in attesa di un titolo. Solo la scrittura dell'import è gateata.
        val built = MediaRepository.buildExternalItem(tmdbId, isTv, entry.season) ?: return null
        var imported: MediaItem? = null
        val written = gatedWrite(sessionValid) {
          imported = MediaRepository.upsertExternalItem(built, source = MEDIA_SOURCE_SIMKL)
        }
        if (!written) return null
        imported
      }
      ?: return null
    // La posizione va sulla scheda con lo stato utente più recente del gruppo: è quella che
    // «Continua a guardare» mostra (vedi `MediaIdentity`), quindi la scrittura resta visibile
    // anche quando il catalogo ha due schede dello stesso titolo.
    return MediaRepository.newestWatchingItem(matched.id) ?: matched
  }

  /** Durata reale e progresso noto in locale per il film o l'episodio della sessione. */
  private suspend fun localProgressFor(entry: SimklPlaybackEntry, target: MediaItem): SimklLocalProgress {
    if (entry.isNextToWatch) {
      // Il prossimo episodio non ha una percentuale da convertire: non serve una durata,
      // quindi non si interroga TMDB per una voce che è già applicabile.
      val season = entry.season ?: 0
      val episode = entry.episode ?: 0
      val sameEpisode = target.lastWatchedSeason == season && target.lastWatchedEpisode == episode
      val progress = MediaRepository.getEpisodeProgress(target.id, season, episode)
      return SimklLocalProgress(
        mediaId = target.id,
        totalDurationMs = 0L,
        progressMs = if (sameEpisode) progress else 0L,
        lastWatchedAtMs = if (sameEpisode) target.lastWatchedAt else null
      )
    }
    if (entry.kind == SimklPlaybackKind.MOVIE) {
      // Durata del film: quella già nota dalla scheda, altrimenti quella REALE di TMDB. Mai
      // stimata: senza una durata vera la percentuale non diventa millisecondi, e la voce
      // viene rimandata (non persa) invece di essere convertita a caso.
      val duration = target.totalDurationMs.takeIf { it > 0L }
        ?: MediaRepository.resolveMovieDurationMs(target.tmdbId ?: 0)
        ?: 0L
      return SimklLocalProgress(
        mediaId = target.id,
        totalDurationMs = duration,
        progressMs = target.currentProgressMs,
        lastWatchedAtMs = if (target.currentProgressMs > 0L) target.lastWatchedAt else null
      )
    }
    val season = entry.season ?: 0
    val episode = entry.episode ?: 0
    // La durata dell'EPISODIO: prima quella realmente misurata dal player, poi quella nota
    // dell'episodio (TMDB), e — solo se nessuna delle due esiste — quella chiesta a TMDB.
    // Mai la durata della serie, e mai una durata inventata: se TMDB non risponde la voce
    // viene rimandata e conteggiata, non convertita a caso.
    val measured = MediaRepository.getMeasuredEpisodeDurationMs(target.id, season, episode)
    val known = target.episodes
      .firstOrNull { it.seasonNumber == season && it.episodeNumber == episode }
      ?.totalDurationMs
      ?: 0L
    val duration = measured.takeIf { it > 0L }
      ?: known.takeIf { it > 0L }
      ?: MediaRepository.resolveEpisodeDurationMs(target.tmdbId ?: 0, season, episode)
      ?: 0L
    // Il progresso locale vale solo se riguarda proprio questo episodio.
    val sameEpisode = target.lastWatchedSeason == season && target.lastWatchedEpisode == episode
    val progress = MediaRepository.getEpisodeProgress(target.id, season, episode)
    return SimklLocalProgress(
      mediaId = target.id,
      totalDurationMs = duration,
      progressMs = if (sameEpisode) progress else 0L,
      // Anche a progresso 0 (episodio appena avviato o next_to_watch già applicato) la
      // data appartiene a questo episodio e serve all'idempotenza.
      lastWatchedAtMs = if (sameEpisode) target.lastWatchedAt else null
    )
  }

  /** Chiave del TITOLO per decidere quale fonte di ripresa vince (playback o libreria). */
  private fun resumeTitleKey(entry: SimklPlaybackEntry): String? {
    if (entry.kind != SimklPlaybackKind.EPISODE) return null
    entry.tmdbId?.takeIf { it > 0 }?.let { return "tmdb:$it" }
    entry.imdbId?.takeIf { it.isNotBlank() }?.let { return "imdb:${it.lowercase()}" }
    return "title:${MediaIdentity.normalizeTitle(entry.title)}:${entry.year ?: 0}"
  }

  private fun entryLabel(entry: SimklPlaybackEntry): String = when {
    entry.kind == SimklPlaybackKind.MOVIE -> entry.title
    entry.season != null && entry.episode != null -> "${entry.title} S${entry.season}E${entry.episode}"
    else -> entry.title
  }

  // ── Watchlist → Preferiti ──────────────────────────────────────────────────

  /**
   * Importa "Plan to Watch" nei **Preferiti**: stesso archivio (`isFavorite` sulla scheda),
   * quindi la sezione Preferiti li mostra senza un secondo elenco parallelo.
   *
   * Additiva e idempotente: un titolo già in catalogo viene solo reso preferito, uno
   * sconosciuto viene importato da TMDB. Un titolo non risolvibile viene **registrato** e
   * saltato — mai inventato. La sincronizzazione non sfavorisce mai un titolo locale:
   * l'assenza di una voce dalla watchlist non toglie nulla.
   *
   * @return l'esito dell'importazione, o `null` se la sessione è stata disconnessa
   *         durante l'applicazione (nessuna altra voce viene pubblicata).
   */
  private suspend fun applyWatchlist(
    entries: List<SimklWatchlistEntry>,
    sessionValid: () -> Boolean
  ): WatchlistOutcome? {
    var imported = 0
    var alreadyFavorite = 0
    val unresolved = mutableListOf<String>()
    for (entry in entries) {
      val targetId = resolveWatchlistTarget(entry, sessionValid)
      if (targetId == null) {
        if (!sessionValid()) return null
        unresolved.add(entry.title)
        continue
      }
      val written = gatedWrite(sessionValid) {
        if (MediaRepository.markFavorite(targetId, source = MEDIA_SOURCE_SIMKL)) imported++ else alreadyFavorite++
      }
      if (!written) return null
    }
    if (unresolved.isNotEmpty()) {
      Log.i(TAG, "Watchlist: ${unresolved.size} titoli non riconosciuti, saltati.")
    }
    return WatchlistOutcome(imported, alreadyFavorite, unresolved)
  }

  /** Id locale del titolo in watchlist, importandolo da TMDB quando non è in catalogo. */
  private suspend fun resolveWatchlistTarget(entry: SimklWatchlistEntry, sessionValid: () -> Boolean): String? {
    SimklLocalMatcher.find(MediaRepository.mediaList.value, entry)?.let { return it.id }
    val isTv = entry.kind == SimklWatchlistKind.SHOW
    val tmdbId = entry.tmdbId?.takeIf { it > 0 }
      ?: entry.imdbId?.let { MediaRepository.resolveImdbToTmdbId(it, isTv) }
      ?: entry.tvdbId?.let { MediaRepository.resolveTvdbToTmdbId(it, isTv) }
      ?: return null
    val built = MediaRepository.buildExternalItem(tmdbId, isTv) ?: return null
    var imported: MediaItem? = null
    val written = gatedWrite(sessionValid) {
      imported = MediaRepository.upsertExternalItem(built, markFavorite = true, source = MEDIA_SOURCE_SIMKL)
    }
    if (!written) return null
    return imported?.id
  }

  // ── Completamenti verificati su SIMKL ─────────────────────────────────────

  /**
   * Applica i titoli che SIMKL dichiara `completed`.
   *
   * Regole:
   * - il titolo deve essere già in catalogo: un completamento non giustifica l'importazione
   *   di una scheda (e non deve inventare una posizione);
   * - un dato locale **più recente** (una nuova visione in corso) non viene scavalcato;
   * - un anime per cour non dichiara conclusa l'intera serie TMDB se un'altra cour è
   *   ancora `watching`;
   * - la scrittura è non distruttiva: `completedAt` + `inContinueWatching = false`, con
   *   progressi e righe episodio intatti.
   *
   * @return il numero di titoli marcati, o `null` se la sessione è stata disconnessa.
   */
  private suspend fun applyCompletions(
    titles: List<SimklCompletedTitle>,
    watchingTmdbIds: Set<Int>,
    sessionValid: () -> Boolean
  ): Int? {
    var marked = 0
    for (title in titles) {
      if (title.isAnime && title.kind == SimklCompletedKind.SHOW && title.tmdbId in watchingTmdbIds) {
        // Una cour anime conclusa non significa che l'opera TMDB sia finita.
        continue
      }
      val matched = SimklLocalMatcher.find(MediaRepository.mediaList.value, title) ?: continue
      if (matched.completedAt != null && !matched.inContinueWatching) {
        // Già marcato: nessuna nuova scrittura né conteggio a ogni sincronizzazione.
        continue
      }
      val completedAt = title.completedAtEpochMs ?: System.currentTimeMillis()
      val localAt = matched.lastWatchedAt
      if (localAt != null && localAt > completedAt && matched.inContinueWatching) {
        // C'è una visione locale più recente: il `completed` remoto è vecchio.
        continue
      }
      val written = gatedWrite(sessionValid) {
        if (title.kind == SimklCompletedKind.MOVIE) {
          MediaRepository.markCompletedFromSimkl(
            mediaId = matched.id,
            completedAtMs = completedAt,
            setProgressToEnd = true
          )
        } else {
          MediaRepository.markSeriesCompletedFromSimkl(
            mediaId = matched.id,
            completedAtMs = completedAt
          )
        }
      }
      if (!written) return null
      marked++
    }
    if (marked > 0) {
      Log.i(TAG, "Completamenti SIMKL verificati: $marked titoli marcati (storico preservato).")
    }
    return marked
  }

  private fun buildMessage(
    outcome: ApplyOutcome,
    watchlist: WatchlistOutcome,
    watchedPulled: Boolean,
    libraryChanged: Boolean,
    failed: List<SimklSyncSection>,
    completedMarked: Int = 0
  ): String {
    val parts = mutableListOf<String>()
    val adopted = outcome.movies + outcome.episodes
    parts.add(
      when {
        adopted > 0 -> "Aggiornati ${outcome.movies} film e ${outcome.episodes} episodi in Continua a guardare."
        // Nessun punto di pausa non significa "niente da importare": gli episodi visti
        // vivono nella libreria, e la riga seguente dice quanti ne sono stati allineati.
        outcome.libraryEpisodes > 0 -> "Nessun punto di pausa da importare."
        else -> "Nessun nuovo punto di ripresa."
      }
    )
    if (outcome.libraryEpisodes > 0) {
      parts.add("Allineate ${outcome.libraryEpisodes} serie al prossimo episodio da guardare su SIMKL.")
    }
    parts.add(
      when {
        watchlist.imported > 0 -> "Aggiunti ${watchlist.imported} titoli ai Preferiti dalla watchlist SIMKL."
        watchlist.unresolved.isNotEmpty() -> "Watchlist SIMKL letta."
        watchlist.alreadyFavorite > 0 -> "Watchlist SIMKL già presente nei Preferiti."
        else -> "Nessun titolo nella watchlist SIMKL."
      }
    )
    if (completedMarked > 0) {
      parts.add("Verificati $completedMarked completamenti su SIMKL (storico conservato).")
    }
    if (watchedPulled) parts.add("Libreria SIMKL aggiornata.")
    else if (!libraryChanged) parts.add("Libreria SIMKL invariata.")
    // Un elemento non applicato deve dire PERCHÉ: senza il motivo, un dato mancante è
    // indistinguibile da un guasto e l'utente non ha modo di sapere se riprovare.
    if (outcome.skipped.isNotEmpty()) {
      parts.add(
        "Voci non applicate: " +
          outcome.skipped.entries.joinToString(", ") { "${it.value}× ${it.key.label}" } + "."
      )
    }
    if (outcome.pending.isNotEmpty()) {
      parts.add("${outcome.pending.size} in attesa di dati completi: verranno riprovate.")
    }
    if (outcome.abandoned > 0) {
      parts.add("${outcome.abandoned} voci non recuperabili dopo i tentativi previsti.")
    }
    val unresolved = outcome.unresolvedTitles + watchlist.unresolved
    if (unresolved.isNotEmpty()) {
      parts.add("${unresolved.size} titoli non riconosciuti: ${unresolved.take(3).joinToString(", ")}.")
    }
    if (failed.isNotEmpty()) {
      // Un fallimento parziale non deve mai passare per una sincronizzazione completa.
      parts.add(
        "Non scaricato (${failed.joinToString(", ") { it.label }}): i dati locali non sono stati modificati, riprovare."
      )
    }
    return parts.joinToString(" ")
  }
}

/**
 * Corrispondenza tra una voce SIMKL (sessione di playback o titolo in watchlist) e un titolo
 * del catalogo locale.
 *
 * Ordine: `tmdb` (chiave forte, la stessa che l'app usa verso SIMKL) → `imdb` → titolo
 * normalizzato + anno + tipo. Nessun match plausibile ⇒ `null`: il chiamante importa il
 * titolo da TMDB oppure lo segnala come non riconosciuto, senza inventare nulla.
 *
 * Il confronto dei titoli guarda sia il titolo localizzato sia l'`originalTitle`: SIMKL
 * risponde con il titolo originale, mentre il catalogo locale è costruito da TMDB con
 * `language=it-IT`, quindi per molti titoli i due testi non coincidono.
 */
object SimklLocalMatcher {

  fun find(candidates: List<MediaItem>, entry: SimklPlaybackEntry): MediaItem? = findByIdentity(
    candidates = candidates,
    title = entry.title,
    year = entry.year,
    tmdbId = entry.tmdbId,
    imdbId = entry.imdbId,
    type = if (entry.kind == SimklPlaybackKind.EPISODE) MediaType.SERIE_TV else MediaType.FILM
  )

  fun find(candidates: List<MediaItem>, entry: SimklWatchlistEntry): MediaItem? = findByIdentity(
    candidates = candidates,
    title = entry.title,
    year = entry.year,
    tmdbId = entry.tmdbId,
    imdbId = entry.imdbId,
    type = if (entry.kind == SimklWatchlistKind.SHOW) MediaType.SERIE_TV else MediaType.FILM
  )

  fun find(candidates: List<MediaItem>, entry: SimklCompletedTitle): MediaItem? = findByIdentity(
    candidates = candidates,
    title = entry.title,
    year = entry.year,
    tmdbId = entry.tmdbId,
    imdbId = entry.imdbId,
    type = if (entry.kind == SimklCompletedKind.SHOW) MediaType.SERIE_TV else MediaType.FILM
  )

  private fun findByIdentity(
    candidates: List<MediaItem>,
    title: String,
    year: Int?,
    tmdbId: Int?,
    imdbId: String?,
    type: MediaType
  ): MediaItem? {
    if (tmdbId != null && tmdbId > 0) {
      candidates.firstOrNull { it.tmdbId == tmdbId && it.type == type }?.let { return it }
    }
    val cleanImdb = imdbId?.takeIf { it.isNotBlank() }
    if (cleanImdb != null) {
      candidates.firstOrNull {
        it.type == type && it.imdbId?.equals(cleanImdb, ignoreCase = true) == true
      }?.let { return it }
    }
    val normalizedTitle = normalizeTitle(title)
    if (normalizedTitle.isEmpty()) return null
    return candidates.firstOrNull { candidate ->
      candidate.type == type &&
        yearMatches(year, candidate.year) &&
        (normalizeTitle(candidate.title) == normalizedTitle ||
          normalizeTitle(candidate.originalTitle) == normalizedTitle)
    }
  }

  /** Anno ignoto da una delle due parti non impedisce il match; due anni noti devono coincidere. */
  private fun yearMatches(expected: Int?, candidate: Int): Boolean =
    expected == null || expected <= 0 || candidate <= 0 || candidate == expected

  /** Minuscole, senza punteggiatura, spazi compattati: "Stranger Things" == "stranger  things". */
  internal fun normalizeTitle(raw: String): String = MediaIdentity.normalizeTitle(raw)
}
