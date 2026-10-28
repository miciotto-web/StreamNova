package com.example.data.repository

import android.util.Log
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.remote.simkl.SimklActivitiesSnapshot
import com.example.data.remote.simkl.SimklAuthException
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

/**
 * Sincronizzazione account SIMKL → StreamNova.
 *
 * Tre letture, tutte gate su `GET /sync/activities` (mai polling a timer, come richiesto
 * dalla documentazione ufficiale):
 *
 * 1. **Continua a guardare** — `GET /sync/playback` sono i *punti di pausa salvati*: la
 *    fonte documentata del "Continue Watching". La cronologia NON è usata per la posizione.
 * 2. **Watchlist / "Plan to Watch"** — `GET /sync/all-items/{type}/{status}` con
 *    `status=plantowatch`: film, serie e anime che l'utente vuole guardare, portati nei
 *    **Preferiti** (l'archivio che la sezione legge davvero). È un insieme distinto sia
 *    dalla cronologia sia dai punti di pausa.
 * 3. **Libreria/history** — `GET /sync/all-items/shows`: alimenta la cache dei badge
 *    "Visto su SIMKL" nel Dettaglio (nessuna chiamata di rete per stagione) **e** fornisce i
 *    punti di ripresa degli episodi: le serie `status=watching` con `last_watched` /
 *    `last_watched_at` sono posizioni episodiche a tutti gli effetti. Senza questa lettura i
 *    progressi degli episodi non comparirebbero mai, perché `/sync/playback` contiene solo
 *    le sessioni di pausa ancora aperte — un episodio guardato fino in fondo non c'è.
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
    SimklRepository.setSyncing(true)
    return try {
      runSync(force, now)
    } catch (e: SimklAuthException) {
      // Il refresh può aver marcato la sessione come scaduta: il messaggio lo riflette.
      val expired = SimklRepository.accountState.value.sessionExpired
      val result = SimklSyncResult(
        status = if (expired) SimklSyncStatus.SESSION_EXPIRED else SimklSyncStatus.FAILED,
        message = e.localizedMessage ?: "Sincronizzazione SIMKL non riuscita.",
        finishedAtEpochMs = now
      )
      SimklRepository.saveLastSync(result)
      Log.w(TAG, "Sincronizzazione SIMKL non riuscita: ${e.message}")
      result
    } catch (e: Exception) {
      val result = SimklSyncResult(
        SimklSyncStatus.FAILED,
        "Sincronizzazione SIMKL non riuscita: ${e.localizedMessage ?: e.javaClass.simpleName}",
        finishedAtEpochMs = now
      )
      SimklRepository.saveLastSync(result)
      Log.w(TAG, "Sincronizzazione SIMKL non riuscita", e)
      result
    } finally {
      SimklRepository.setSyncing(false)
    }
  }

  private suspend fun runSync(force: Boolean, now: Long): SimklSyncResult {
    val previous = SimklRepository.loadActivitiesSnapshot()
    val activities = SimklRepository.fetchActivities()
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
      SimklRepository.saveActivitiesSnapshot(activities)
      SimklRepository.saveLastSync(upToDate)
      Log.i(TAG, "Sincronizzazione: ${upToDate.message}")
      return upToDate
    }

    // ── PULL: ogni sezione è indipendente. Si scarica TUTTO prima di scrivere qualcosa,
    //    e il fallimento di una lettura non interrompe le altre.
    val failed = mutableListOf<SimklSyncSection>()
    var playbacks: List<SimklPlaybackEntry>? = null
    var watchlist: List<SimklWatchlistEntry>? = null
    var watchedShows: SimklWatchedLibraryData? = null

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
    val libraryEntries = watchedShows?.watchingResumes
      ?.map { SimklWatchedLibrary.resumeEntry(it) }
      ?.filter { it.resumeKey !in freshKeys }
      .orEmpty()
    val entriesToApply = freshPlaybacks.orEmpty() +
      pendingEntries.filter { it.resumeKey !in freshKeys } +
      libraryEntries
    val playOutcome = if (freshPlaybacks != null || libraryEntries.isNotEmpty() || pendingEntries.isNotEmpty()) {
      applyPlaybacks(entriesToApply, pendingAttempts, libraryEntries.map { it.resumeKey }.toSet())
    } else {
      ApplyOutcome.EMPTY
    }
    val watchOutcome = watchlist?.let { applyWatchlist(it) } ?: WatchlistOutcome.EMPTY

    // L'elenco rimandato si riscrive solo se abbiamo davvero potuto riprovare TUTTE le voci:
    // con il pull del playback fallito, un insieme vuoto significherebbe "cancella tutto".
    // La libreria conta come fonte completa quando è stata scaricata (i suoi punti di
    // ripresa sono riprovabili in quel momento).
    if (freshPlaybacks != null || watchedShows != null) SimklRepository.savePendingPlaybacks(playOutcome.pending)

    val complete = failed.isEmpty()
    if (complete) SimklRepository.saveActivitiesSnapshot(activities)

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
      message = buildMessage(playOutcome, watchOutcome, watchedShows != null, libraryChanged, failed),
      adoptedMovies = playOutcome.movies,
      adoptedEpisodes = playOutcome.episodes,
      adoptedLibraryEpisodes = playOutcome.libraryEpisodes,
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
    SimklRepository.saveLastSync(result)
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
    runCatching { block() }.onFailure { error ->
      Log.w(
        TAG,
        "Sezione '$section' non scaricata: ${error.javaClass.simpleName} — " +
          (error.localizedMessage ?: "nessun dettaglio")
      )
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
   */
  private suspend fun applyPlaybacks(
    entries: List<SimklPlaybackEntry>,
    previousAttempts: Map<String, Int> = emptyMap(),
    libraryKeys: Set<String> = emptySet()
  ): ApplyOutcome {
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
        val target = resolveTarget(entry)
        if (target == null) {
          reason = SimklDecisionReason.UNRESOLVED_TITLE
          unresolved.add(entryLabel(entry))
        } else {
          val local = localProgressFor(entry, target)
          val decision = SimklProgressMergePolicy.decide(
            entry = entry,
            local = local,
            series = SimklSeriesState(
              season = target.lastWatchedSeason,
              episode = target.lastWatchedEpisode,
              lastWatchedAtMs = if (target.currentProgressMs > 0L) target.lastWatchedAt else null
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
                MediaRepository.updateProgress(target.id, decision.progressMs, local.totalDurationMs, watchedAt)
                movies++
              }

              season == null || episode == null -> reason = SimklDecisionReason.INCOMPLETE_SESSION

              else -> {
                MediaRepository.updateEpisodeProgress(
                  mediaId = target.id,
                  seasonNumber = season,
                  episodeNumber = episode,
                  progressMs = decision.progressMs,
                  durationMs = local.totalDurationMs,
                  lastWatchedAtMs = watchedAt
                )
                if (entry.resumeKey in libraryKeys) libraryEpisodes++ else episodes++
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
  private suspend fun resolveTarget(entry: SimklPlaybackEntry): MediaItem? {
    val matched = SimklLocalMatcher.find(MediaRepository.mediaList.value, entry)
      ?: run {
        val isTv = entry.kind == SimklPlaybackKind.EPISODE
        val tmdbId = entry.tmdbId?.takeIf { it > 0 }
          ?: entry.imdbId?.let { MediaRepository.resolveImdbToTmdbId(it, isTv) }
          ?: return null
        val built = MediaRepository.buildExternalItem(tmdbId, isTv, entry.season) ?: return null
        MediaRepository.upsertExternalItem(built)
      }
      ?: return null
    // La posizione va sulla scheda con lo stato utente più recente del gruppo: è quella che
    // «Continua a guardare» mostra (vedi `MediaIdentity`), quindi la scrittura resta visibile
    // anche quando il catalogo ha due schede dello stesso titolo.
    return MediaRepository.newestWatchingItem(matched.id) ?: matched
  }

  /** Durata reale e progresso noto in locale per il film o l'episodio della sessione. */
  private suspend fun localProgressFor(entry: SimklPlaybackEntry, target: MediaItem): SimklLocalProgress {
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
      lastWatchedAtMs = if (sameEpisode && progress > 0L) target.lastWatchedAt else null
    )
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
   */
  private suspend fun applyWatchlist(entries: List<SimklWatchlistEntry>): WatchlistOutcome {
    var imported = 0
    var alreadyFavorite = 0
    val unresolved = mutableListOf<String>()
    for (entry in entries) {
      val targetId = resolveWatchlistTarget(entry)
      if (targetId == null) {
        unresolved.add(entry.title)
        continue
      }
      if (MediaRepository.markFavorite(targetId)) imported++ else alreadyFavorite++
    }
    if (unresolved.isNotEmpty()) {
      Log.i(TAG, "Watchlist: ${unresolved.size} titoli non riconosciuti, saltati.")
    }
    return WatchlistOutcome(imported, alreadyFavorite, unresolved)
  }

  /** Id locale del titolo in watchlist, importandolo da TMDB quando non è in catalogo. */
  private suspend fun resolveWatchlistTarget(entry: SimklWatchlistEntry): String? {
    SimklLocalMatcher.find(MediaRepository.mediaList.value, entry)?.let { return it.id }
    val isTv = entry.kind == SimklWatchlistKind.SHOW
    val tmdbId = entry.tmdbId?.takeIf { it > 0 }
      ?: entry.imdbId?.let { MediaRepository.resolveImdbToTmdbId(it, isTv) }
      ?: return null
    val built = MediaRepository.buildExternalItem(tmdbId, isTv) ?: return null
    return MediaRepository.upsertExternalItem(built, markFavorite = true)?.id
  }

  private fun buildMessage(
    outcome: ApplyOutcome,
    watchlist: WatchlistOutcome,
    watchedPulled: Boolean,
    libraryChanged: Boolean,
    failed: List<SimklSyncSection>
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
      parts.add("Allineate ${outcome.libraryEpisodes} serie all'ultimo episodio visto su SIMKL.")
    }
    parts.add(
      when {
        watchlist.imported > 0 -> "Aggiunti ${watchlist.imported} titoli ai Preferiti dalla watchlist SIMKL."
        watchlist.unresolved.isNotEmpty() -> "Watchlist SIMKL letta."
        watchlist.alreadyFavorite > 0 -> "Watchlist SIMKL già presente nei Preferiti."
        else -> "Nessun titolo nella watchlist SIMKL."
      }
    )
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
