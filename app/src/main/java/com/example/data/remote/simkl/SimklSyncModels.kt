package com.example.data.remote.simkl

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ── Continua a guardare: sessioni di playback (`GET /sync/playback`) ───────────
// Fonte: api.simkl.org/api-reference/simkl/get-playback-sessions + schema OpenAPI
// `PlaybackSession`. La risposta è un ARRAY di sessioni; `progress` è una PERCENTUALE
// 0-100 (stessa scala dello scrobble) e `paused_at` un ISO-8601 UTC.

enum class SimklPlaybackKind { MOVIE, EPISODE }

/**
 * Da dove arriva un punto di ripresa:
 * - [PLAYBACK]: una sessione di pausa di `GET /sync/playback` (posizione parziale);
 * - [LIBRARY]: l'ultimo episodio visto di una serie in visione, ricostruito da
 *   `GET /sync/all-items/shows` (`status=watching`, `last_watched`, `last_watched_at`).
 *
 * Le due fonti si comportano allo stesso modo nel resto del percorso (matching, durata,
 * politica di merge, rinvio) ma restano distinguibili: la chiave di ripresa non può
 * collidere fra le due e il report può dire da quale fonte viene una posizione.
 */
enum class SimklResumeOrigin { PLAYBACK, LIBRARY }

/**
 * Conversione difensiva dei valori JSON non tipizzati: SIMKL restituisce gli stessi
 * identificatori come numero o come stringa a seconda dell'endpoint (la doc lo documenta
 * esplicitamente per `tmdb`), quindi non si assume nessuna delle due forme.
 */
private fun Any?.asIntOrNull(): Int? = when (this) {
  is Number -> toInt()
  is String -> trim().toIntOrNull()
  else -> null
}

private fun Any?.asLongOrNull(): Long? = when (this) {
  is Number -> toLong()
  is String -> trim().toLongOrNull()
  else -> null
}

/** Una sessione di pausa salvata: il dato con cui SIMKL ricostruisce "Continua a guardare". */
data class SimklPlaybackEntry(
  val playbackId: Long?,
  val kind: SimklPlaybackKind,
  val progressPercent: Double,
  val pausedAtEpochMs: Long?,
  val title: String,
  val year: Int?,
  val tmdbId: Int?,
  val imdbId: String?,
  val simklId: Long?,
  val season: Int? = null,
  val episode: Int? = null,
  val episodeTitle: String? = null,
  val isAnime: Boolean = false,
  val origin: SimklResumeOrigin = SimklResumeOrigin.PLAYBACK
) {
  val isEpisode: Boolean get() = kind == SimklPlaybackKind.EPISODE && season != null && episode != null

  /**
   * Identità stabile della voce: `playbackId` quando c'è, altrimenti titolo + episodio + id.
   * Serve a non applicare due volte la stessa sessione e a ritrovare, nel giro successivo,
   * una voce rimandata senza duplicarla. L'origine fa parte della chiave: una voce di
   * libreria e una sessione di pausa per lo stesso episodio sono due dati diversi.
   */
  val resumeKey: String
    get() = playbackId?.let { "id:$it" }
      ?: "k:${origin.name.lowercase()}:$kind:${tmdbId ?: 0}:${simklId ?: 0}:${season ?: 0}:${episode ?: 0}:${title.trim().lowercase()}"
}

object SimklPlaybackParser {

  /** Parsa la risposta di `GET /sync/playback` scartando le voci inutilizzabili. */
  fun parse(body: List<Map<String, Any>>?): List<SimklPlaybackEntry> =
    body.orEmpty().mapNotNull { parseSession(it) }

  internal fun parseSession(session: Map<String, Any>): SimklPlaybackEntry? {
    val container = (session["movie"] as? Map<*, *>)
      ?: (session["show"] as? Map<*, *>)
      ?: (session["anime"] as? Map<*, *>)
      ?: return null
    val isAnime = session["anime"] is Map<*, *>
    val ids = container["ids"] as? Map<*, *>
    val title = container["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
    val episodeObject = session["episode"] as? Map<*, *>
    // La doc mostra `episode` come chiave del numero episodio, lo schema OpenAPI usa
    // `number`: si accettano entrambe.
    val episodeNumber = (episodeObject?.get("number") ?: episodeObject?.get("episode")).asIntOrNull()
    val seasonNumber = episodeObject?.get("season").asIntOrNull()
    val hasMovie = session["movie"] is Map<*, *>
    val kind = if (hasMovie) SimklPlaybackKind.MOVIE else SimklPlaybackKind.EPISODE
    return SimklPlaybackEntry(
      playbackId = session["id"].asLongOrNull(),
      kind = kind,
      progressPercent = (session["progress"] as? Number)?.toDouble() ?: 0.0,
      pausedAtEpochMs = parseIsoUtcToEpochMs(session["paused_at"]?.toString()),
      title = title,
      year = container["year"].asIntOrNull(),
      tmdbId = ids?.get("tmdb").asIntOrNull(),
      imdbId = ids?.get("imdb")?.toString()?.takeIf { it.isNotBlank() },
      simklId = ids?.get("simkl").asLongOrNull(),
      season = if (kind == SimklPlaybackKind.EPISODE) seasonNumber else null,
      episode = if (kind == SimklPlaybackKind.EPISODE) episodeNumber else null,
      episodeTitle = episodeObject?.get("title")?.toString()?.takeIf { it.isNotBlank() },
      isAnime = isAnime
    )
  }

  /** true se la voce identifica esattamente il film o l'episodio da riprendere. */
  fun isResumable(entry: SimklPlaybackEntry): Boolean =
    entry.progressPercent > 0.0 &&
      (entry.kind == SimklPlaybackKind.MOVIE || entry.isEpisode)

  /**
   * ISO-8601 UTC → epoch millis. SIMKL restituisce forme come
   * `2024-01-15T10:30:00.000Z` e `2026-04-11T10:36:01Z`: si accettano entrambe e la
   * frazione di secondo viene normalizzata a 3 cifre. Input non riconoscibile ⇒ `null`
   * (la politica di merge non inventa una data).
   */
  fun parseIsoUtcToEpochMs(raw: String?): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val withoutZone = value.removeSuffix("Z").removeSuffix("z")
    val dot = withoutZone.indexOf('.')
    val normalized = if (dot >= 0) {
      val fraction = withoutZone.substring(dot + 1).takeWhile { it.isDigit() }
      if (fraction.isEmpty()) return null
      withoutZone.substring(0, dot) + "." + fraction.padEnd(3, '0').take(3)
    } else {
      withoutZone
    }
    val pattern = if (normalized.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS" else "yyyy-MM-dd'T'HH:mm:ss"
    val format = SimpleDateFormat(pattern, Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
      isLenient = false
    }
    return runCatching { format.parse(normalized)?.time }.getOrNull()
  }
}

// ── Gate di sincronizzazione (`GET /sync/activities`) ──────────────────────────

/**
 * Snapshot appiattito dei timestamp di `GET /sync/activities` (es. `tv_shows.playback`).
 * Va salvato solo dopo che tutti i pull sono riusciti: se un pull fallisce si conserva
 * il vecchio snapshot e il `date_from` successivo recupera ciò che è stato perso.
 */
class SimklActivitiesSnapshot(val timestamps: Map<String, String>) {

  fun value(key: String): String? = timestamps[key]

  /** true se almeno uno dei bucket indicati è cambiato rispetto a [previous]. */
  fun movedSince(previous: SimklActivitiesSnapshot?, keys: Collection<String>): Boolean {
    if (previous == null) return true
    return keys.any { key -> value(key) != previous.value(key) }
  }

  /** Serializzazione stabile per DataStore: una riga `chiave=valore` per bucket. */
  fun toStorageString(): String =
    timestamps.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

  companion object {
    /** Bucket che descrivono i punti di ripresa (Continua a guardare). */
    val PLAYBACK_KEYS = listOf("movies.playback", "tv_shows.playback", "anime.playback")

    /** Bucket che descrivono la libreria/history (badge "visto" per episodio). */
    val WATCHLIST_KEYS = listOf(
      "tv_shows.all", "tv_shows.watching", "tv_shows.completed", "tv_shows.hold",
      "tv_shows.dropped", "tv_shows.plantowatch", "tv_shows.removed_from_list",
      "anime.all", "anime.watching", "anime.completed", "anime.hold",
      "anime.dropped", "anime.plantowatch", "anime.removed_from_list",
      "movies.all", "movies.completed", "movies.dropped", "movies.plantowatch",
      "movies.removed_from_list"
    )

    private const val DOMAIN_MOVIES = "movies"
    private const val DOMAIN_SHOWS = "tv_shows"
    private const val DOMAIN_ANIME = "anime"

    fun fromResponse(body: Map<String, Any>): SimklActivitiesSnapshot {
      val flat = LinkedHashMap<String, String>()
      body["all"]?.toString()?.takeIf { it.isNotBlank() }?.let { flat["all"] = it }
      for (domain in listOf(DOMAIN_MOVIES, DOMAIN_SHOWS, DOMAIN_ANIME)) {
        val block = body[domain] as? Map<*, *> ?: continue
        for ((key, value) in block) {
          val name = key?.toString() ?: continue
          val timestamp = value?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
          flat["$domain.$name"] = timestamp
        }
      }
      (body["settings"] as? Map<*, *>)?.get("all")?.toString()
        ?.takeIf { it.isNotBlank() }?.let { flat["settings.all"] = it }
      return SimklActivitiesSnapshot(flat)
    }

    fun fromStorageString(raw: String?): SimklActivitiesSnapshot? {
      val value = raw?.trim().orEmpty()
      if (value.isEmpty()) return null
      val parsed = LinkedHashMap<String, String>()
      value.lineSequence().forEach { line ->
        val index = line.indexOf('=')
        if (index > 0 && index < line.length - 1) {
          parsed[line.substring(0, index)] = line.substring(index + 1)
        }
      }
      return if (parsed.isEmpty()) null else SimklActivitiesSnapshot(parsed)
    }
  }
}

// ── Voci di playback rimandate (recupero) ─────────────────────────────────────
// Una sessione di pausa può essere INUTILIZZABILE adesso e utilizzabile più tardi: la
// durata reale dell'episodio può non essere ancora nota (il player la misura alla prima
// riproduzione, TMDB può rispondere più tardi) o il titolo può entrare in catalogo dopo.
// Scartarla per sempre — e avanzare il checkpoint delle attività — renderebbe quel punto di
// ripresa irrecuperabile. Queste voci vengono quindi conservate con il numero di tentativi
// già fatti e riprovate alla sincronizzazione successiva.

/** Una voce rimandata: la sessione da riprovare e quante volte è già stata tentata. */
data class SimklPendingPlayback(val entry: SimklPlaybackEntry, val attempts: Int)

/**
 * Serializzazione dipendente da nessuna libreria JSON (le classi `org.json` sono stub nei
 * test JVM): una voce per riga, campi separati da `\u001F`, valori assenti vuoti. È lo
 * stesso spirito di [SimklActivitiesSnapshot.toStorageString]: un dato di servizio su
 * DataStore, non un formato di interscambio.
 */
object SimklPendingPlaybackStore {

  /**
   * Tentativi massimi per voce. Il limite esiste per non trasformare il recupero in un
   * ciclo: dopo l'ultimo tentativo la voce non forza più il pull del playback (e il motivo
   * finisce nel report), invece di far risultare "da sincronizzare" per sempre.
   */
  const val MAX_ATTEMPTS = 5

  private const val FIELD_SEPARATOR = '\u001F'

  fun encode(pending: List<SimklPendingPlayback>): String =
    pending.joinToString("\n") { record ->
      val entry = record.entry
      listOf(
        record.attempts.toString(),
        entry.playbackId?.toString().orEmpty(),
        entry.kind.name,
        entry.progressPercent.toString(),
        entry.pausedAtEpochMs?.toString().orEmpty(),
        entry.title,
        entry.year?.toString().orEmpty(),
        entry.tmdbId?.toString().orEmpty(),
        entry.imdbId.orEmpty(),
        entry.simklId?.toString().orEmpty(),
        entry.season?.toString().orEmpty(),
        entry.episode?.toString().orEmpty(),
        entry.episodeTitle.orEmpty(),
        if (entry.isAnime) "1" else "0",
        entry.origin.name
      ).joinToString(FIELD_SEPARATOR.toString())
    }

  fun decode(raw: String?): List<SimklPendingPlayback> {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return emptyList()
    return value.lineSequence().mapNotNull { parseRecord(it) }.toList()
  }

  private fun parseRecord(line: String): SimklPendingPlayback? {
    if (line.isBlank()) return null
    val fields = line.split(FIELD_SEPARATOR)
    if (fields.size < 14) return null
    val kind = runCatching { SimklPlaybackKind.valueOf(fields[2]) }.getOrNull() ?: return null
    val title = fields[5].takeIf { it.isNotBlank() } ?: return null
    val entry = SimklPlaybackEntry(
      playbackId = fields[1].toLongOrNull(),
      kind = kind,
      progressPercent = fields[3].toDoubleOrNull() ?: 0.0,
      pausedAtEpochMs = fields[4].toLongOrNull(),
      title = title,
      year = fields[6].toIntOrNull(),
      tmdbId = fields[7].toIntOrNull(),
      imdbId = fields[8].takeIf { it.isNotBlank() },
      simklId = fields[9].toLongOrNull(),
      season = fields[10].toIntOrNull(),
      episode = fields[11].toIntOrNull(),
      episodeTitle = fields[12].takeIf { it.isNotBlank() },
      isAnime = fields[13] == "1",
      origin = fields.getOrNull(14)
        ?.let { stored -> runCatching { SimklResumeOrigin.valueOf(stored) }.getOrNull() }
        ?: SimklResumeOrigin.PLAYBACK
    )
    return SimklPendingPlayback(entry, fields[0].toIntOrNull() ?: 0)
  }
}

// ── Politica di merge con il progresso locale ─────────────────────────────────

/**
 * Motivo per cui una voce non è stata applicata. [label] è la forma **per l'utente**: finisce
 * nel messaggio della sincronizzazione, perché un elemento saltato senza spiegazione è
 * indistinguibile da un guasto.
 */
enum class SimklDecisionReason(val label: String) {
  /** Percentuale non utilizzabile (0 o fuori scala): non c'è nulla da riprendere. */
  INVALID_PROGRESS("percentuale non utilizzabile"),
  /** Sessione senza l'episodio identificato (numero di stagione/episodio assente). */
  INCOMPLETE_SESSION("sessione senza episodio"),
  /** Durata reale non nota: la percentuale non è convertibile in millisecondi. */
  UNKNOWN_DURATION("durata non nota"),
  /** Il locale è più recente della pausa SIMKL. */
  LOCAL_NEWER("progresso locale più recente"),
  /** Pausa senza data confrontabile e progresso locale già presente: si conserva il locale. */
  UNKNOWN_TIMESTAMP("data di pausa assente"),
  /** Già allineati: nessuna scrittura necessaria. */
  ALREADY_IN_SYNC("già allineati"),
  /** Nessun titolo locale corrispondente e importazione non riuscita. */
  UNRESOLVED_TITLE("titolo non riconosciuto"),
  /** Tentativi di recupero esauriti: la voce non viene più riprovata. */
  RECOVERY_EXHAUSTED("recupero abbandonato")
}

data class SimklProgressDecision(
  val adopt: Boolean,
  val progressMs: Long = 0L,
  val reason: SimklDecisionReason? = null
)

/**
 * Stato locale del titolo/episodio al momento della decisione.
 *
 * [totalDurationMs] è la durata REALE nota (player o TMDB): se è 0 la percentuale SIMKL
 * non viene convertita — il report non inventa durate.
 */
data class SimklLocalProgress(
  val mediaId: String,
  val totalDurationMs: Long,
  val progressMs: Long,
  val lastWatchedAtMs: Long?
)

/**
 * Stato della SERIE a livello di titolo: su quale episodio è ferma in locale e da quando.
 * Serve alle voci di libreria, che descrivono la serie e non un singolo episodio.
 */
data class SimklSeriesState(
  val season: Int?,
  val episode: Int?,
  val lastWatchedAtMs: Long?
)

object SimklProgressMergePolicy {

  /**
   * Regola: vince il più recente. Un playback SIMKL più nuovo del progresso locale
   * adotta la posizione; un locale più recente non viene mai regredito; non si cancella
   * mai nulla e non si scrive quando il valore è già allineato (idempotenza).
   *
   * @param series stato della serie ([SimklSeriesState]) per le voci di LIBRERIA: una voce
   *        di libreria dice "l'ultimo episodio visto", quindi se in locale la serie è più
   *        avanti nel tempo su un episodio diverso, l'informazione remota è vecchia e non
   *        deve riportare indietro la card. Il confronto è fra date; a parità vale l'ultimo
   *        dato locale.
   */
  fun decide(
    entry: SimklPlaybackEntry,
    local: SimklLocalProgress?,
    series: SimklSeriesState? = null
  ): SimklProgressDecision {
    // Prima la validità della percentuale, poi la completezza della sessione: una
    // percentuale fuori scala è un dato non utilizzabile, non una sessione incompleta.
    val percent = entry.progressPercent
    if (percent <= 0.0 || percent > 100.0) {
      return SimklProgressDecision(adopt = false, reason = SimklDecisionReason.INVALID_PROGRESS)
    }
    if (!SimklPlaybackParser.isResumable(entry)) {
      return SimklProgressDecision(adopt = false, reason = SimklDecisionReason.INCOMPLETE_SESSION)
    }
    if (local == null) {
      return SimklProgressDecision(adopt = false, reason = SimklDecisionReason.UNRESOLVED_TITLE)
    }
    // Prima della durata: un recupero (rinvio) non ha senso per una serie che in locale è
    // già più avanti, e riprovare all'infinito una voce che non va applicata sarebbe un ciclo.
    if (entry.origin == SimklResumeOrigin.LIBRARY && isLocallyAheadOnAnotherEpisode(entry, series)) {
      return SimklProgressDecision(adopt = false, reason = SimklDecisionReason.LOCAL_NEWER)
    }
    if (local.totalDurationMs <= 0L) {
      return SimklProgressDecision(adopt = false, reason = SimklDecisionReason.UNKNOWN_DURATION)
    }
    val pausedAt = entry.pausedAtEpochMs
    if (pausedAt == null) {
      // Senza data non si può stabilire chi è più recente: si conserva il locale se esiste.
      if (local.progressMs > 0L) {
        return SimklProgressDecision(adopt = false, reason = SimklDecisionReason.UNKNOWN_TIMESTAMP)
      }
    } else {
      val localAt = local.lastWatchedAtMs
      if (localAt != null && localAt >= pausedAt) {
        return SimklProgressDecision(adopt = false, reason = SimklDecisionReason.LOCAL_NEWER)
      }
    }
    val target = (local.totalDurationMs * (percent / 100.0)).toLong().coerceAtLeast(1L)
    if (target == local.progressMs) {
      return SimklProgressDecision(adopt = false, reason = SimklDecisionReason.ALREADY_IN_SYNC)
    }
    return SimklProgressDecision(adopt = true, progressMs = target)
  }

  /** true se la serie in locale è ferma più avanti nel tempo, su un episodio diverso. */
  private fun isLocallyAheadOnAnotherEpisode(
    entry: SimklPlaybackEntry,
    series: SimklSeriesState?
  ): Boolean {
    if (series == null) return false
    val localAt = series.lastWatchedAtMs ?: return false
    val entryAt = entry.pausedAtEpochMs ?: return false
    val anotherEpisode = series.season != entry.season || series.episode != entry.episode
    return anotherEpisode && localAt >= entryAt
  }
}

// ── Libreria/history (`GET /sync/all-items/shows`) ────────────────────────────

/**
 * Una serie **in visione** secondo SIMKL, con l'ultimo episodio visto.
 *
 * È il dato che mancava: `/sync/playback` contiene solo le sessioni di pausa, e per gli
 * episodi spesso non ce n'è nessuna (un episodio guardato fino in fondo esce dai punti di
 * pausa e resta nella cronologia). Lo stato episodico dell'account vive qui, nel bucket
 * `watching` di `/sync/all-items/shows`, che l'app scaricava già per i badge "Visto" ma di
 * cui ignorava `status`/`last_watched`/`last_watched_at`.
 */
data class SimklWatchingResume(
  val title: String,
  val year: Int?,
  val tmdbId: Int?,
  val imdbId: String?,
  val simklId: Long?,
  val season: Int,
  val episode: Int,
  /** `last_watched_at` di SIMKL: è la data che decide chi è più recente. */
  val watchedAtEpochMs: Long,
  val isAnime: Boolean = false
)

/** Esito del pull della libreria: badge "visto" più le serie in visione da riposizionare. */
data class SimklWatchedLibraryData(
  val watchedEpisodesByShow: Map<Int, Map<Int, Set<Int>>>,
  val watchingResumes: List<SimklWatchingResume>
) {
  companion object {
    val EMPTY = SimklWatchedLibraryData(emptyMap(), emptyList())
  }
}

object SimklWatchedLibrary {

  /** `S01E07`, `s1e7`, `1x7` → (1, 7). Forme non riconoscibili ⇒ `null`, mai un'ipotesi. */
  private val EPISODE_CODE = Regex("^[Ss]?(\\d+)\\s*[EexX]\\s*(\\d+)$")

  /** Un episodio visto è una posizione al 100%: nessuna durata/posizione viene inventata. */
  const val WATCHED_PROGRESS_PERCENT = 100.0

  /** Libreria completa: mappa per i badge + serie in visione. */
  fun parse(body: Map<String, Any>): SimklWatchedLibraryData = SimklWatchedLibraryData(
    watchedEpisodesByShow = parseWatchedEpisodesByShow(body),
    watchingResumes = parseWatchingResumes(body)
  )

  /**
   * Serie in visione con l'ultimo episodio visto, dai bucket `shows`/`tv_shows` e `anime`.
   *
   * Vengono lette SOLO le voci `status=watching` con `last_watched` e `last_watched_at`
   * valorizzati: una serie conclusa o abbandonata non è un punto di ripresa, e senza data
   * non si può stabilire chi è più recente (niente viene indovinato).
   */
  fun parseWatchingResumes(body: Map<String, Any>): List<SimklWatchingResume> {
    val showsBucket = (body["shows"] as? List<*>) ?: (body["tv_shows"] as? List<*>)
    val result = mutableListOf<SimklWatchingResume>()
    for (bucket in listOfNotNull(showsBucket, body["anime"] as? List<*>)) {
      for (raw in bucket) {
        val entry = raw as? Map<*, *> ?: continue
        if (entry["status"]?.toString()?.trim()?.lowercase() != "watching") continue
        val code = parseEpisodeCode(entry["last_watched"]?.toString()) ?: continue
        val watchedAt = SimklPlaybackParser.parseIsoUtcToEpochMs(entry["last_watched_at"]?.toString()) ?: continue
        val container = (entry["show"] as? Map<*, *>) ?: (entry["anime"] as? Map<*, *>) ?: continue
        val title = container["title"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
        val ids = container["ids"] as? Map<*, *>
        result += SimklWatchingResume(
          title = title,
          year = container["year"].asIntOrNull(),
          tmdbId = ids?.get("tmdb").asIntOrNull(),
          imdbId = ids?.get("imdb")?.toString()?.takeIf { it.isNotBlank() },
          simklId = ids?.get("simkl").asLongOrNull(),
          season = code.first,
          episode = code.second,
          watchedAtEpochMs = watchedAt,
          isAnime = entry["anime"] is Map<*, *>
        )
      }
    }
    return result
  }

  /** `S01E07` → (1, 7); `null` se il codice non è riconoscibile. */
  internal fun parseEpisodeCode(raw: String?): Pair<Int, Int>? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val match = EPISODE_CODE.matchEntire(value) ?: return null
    val season = match.groupValues[1].toIntOrNull() ?: return null
    val episode = match.groupValues[2].toIntOrNull() ?: return null
    return if (season > 0 && episode > 0) season to episode else null
  }

  /**
   * La serie in visione come punto di ripresa, con la stessa forma di una sessione di pausa:
   * così passa per la stessa catena (matching, risoluzione durata, politica di merge, rinvio)
   * e l'app la mostra esattamente come una ripresa locale — l'episodio visto risulta al 100%,
   * cioè "visto", senza inventare una posizione parziale che SIMKL non ha.
   */
  fun resumeEntry(resume: SimklWatchingResume): SimklPlaybackEntry = SimklPlaybackEntry(
    playbackId = null,
    kind = SimklPlaybackKind.EPISODE,
    progressPercent = WATCHED_PROGRESS_PERCENT,
    pausedAtEpochMs = resume.watchedAtEpochMs,
    title = resume.title,
    year = resume.year,
    tmdbId = resume.tmdbId,
    imdbId = resume.imdbId,
    simklId = resume.simklId,
    season = resume.season,
    episode = resume.episode,
    isAnime = resume.isAnime,
    origin = SimklResumeOrigin.LIBRARY
  )

  /**
   * Estrae da `/sync/all-items/shows` la mappa `tmdbId -> stagione -> episodi visti`.
   * Accetta i bucket `shows`/`tv_shows` e `anime`. Gli episodi senza `watched_at` non
   * sono "visti". Non viene inventato nulla: le voci senza id TMDB vengono ignorate.
   */
  fun parseWatchedEpisodesByShow(body: Map<String, Any>): Map<Int, Map<Int, Set<Int>>> {
    val result = LinkedHashMap<Int, MutableMap<Int, MutableSet<Int>>>()
    val showsBucket = (body["shows"] as? List<*>) ?: (body["tv_shows"] as? List<*>)
    val buckets = listOfNotNull(showsBucket, body["anime"] as? List<*>)
    for (bucket in buckets) {
      for (entry in bucket) {
        val entryMap = entry as? Map<*, *> ?: continue
        val show = (entryMap["show"] as? Map<*, *>) ?: (entryMap["anime"] as? Map<*, *>) ?: entryMap
        val tmdbId = (show["ids"] as? Map<*, *>)?.get("tmdb")?.toString()?.toIntOrNull() ?: continue
        val seasons = entryMap["seasons"] as? List<*> ?: continue
        val target = result.getOrPut(tmdbId) { LinkedHashMap() }
        for (seasonEntry in seasons) {
          val seasonMap = seasonEntry as? Map<*, *> ?: continue
          val seasonNumber = seasonMap["number"].asIntOrNull() ?: continue
          val episodes = seasonMap["episodes"] as? List<*> ?: continue
          for (episodeEntry in episodes) {
            val episodeMap = episodeEntry as? Map<*, *> ?: continue
            // Solo un `watched_at` valorizzato conta come "visto".
            episodeMap["watched_at"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
            val number = (episodeMap["number"] ?: episodeMap["episode"]).asIntOrNull() ?: continue
            target.getOrPut(seasonNumber) { LinkedHashSet() }.add(number)
          }
        }
      }
    }
    return result
  }

  /** Vista a stagione singola (usata dal badge "Visto su SIMKL" in Dettaglio). */
  fun parseWatchedEpisodes(body: Map<String, Any>, tmdbId: Int, season: Int): Set<Int> =
    parseWatchedEpisodesByShow(body)[tmdbId]?.get(season)?.toSet() ?: emptySet()
}

// ── Watchlist / "Plan to Watch" (`GET /sync/all-items/{type}/{status}`) ────────
// Fonte: api.simkl.com docs `get-all-items` + esempi OpenAPI. La risposta è un OGGETTO
// con le chiavi `movies`/`shows`/`anime` (solo quelle non vuote); ogni voce ha `status`
// e il contenitore `movie`/`show`/`anime`. È un insieme DISTINTO sia dalla cronologia
// (`watched_at`) sia dai punti di pausa (`/sync/playback`).

enum class SimklWatchlistKind { MOVIE, SHOW }

/** Una voce della watchlist SIMKL: il titolo che l'utente vuole guardare. */
data class SimklWatchlistEntry(
  val kind: SimklWatchlistKind,
  val title: String,
  val year: Int?,
  val tmdbId: Int?,
  val imdbId: String?,
  val simklId: Long?,
  val status: String,
  val isAnime: Boolean = false
)

object SimklWatchlistParser {

  /** Bucket documentati della risposta, in ordine di lettura (`tv_shows` è un alias difensivo). */
  private val BUCKETS = listOf(
    "movies" to SimklWatchlistKind.MOVIE,
    "shows" to SimklWatchlistKind.SHOW,
    "tv_shows" to SimklWatchlistKind.SHOW
  )

  /** Tutte le voci leggibili della risposta, qualsiasi sia il loro stato. */
  fun parse(body: Map<String, Any>?): List<SimklWatchlistEntry> {
    if (body == null) return emptyList()
    val result = mutableListOf<SimklWatchlistEntry>()
    for ((bucket, kind) in BUCKETS) {
      result += parseBucket(body[bucket] as? List<*>, kind, isAnime = false)
    }
    result += parseBucket(body["anime"] as? List<*>, SimklWatchlistKind.SHOW, isAnime = true)
    return result
  }

  /**
   * Solo "Plan to Watch" (`status=plantowatch`): ciò che viene portato nei Preferiti.
   *
   * Lo stato assente viene trattato come `plantowatch` perché è il filtro con cui è stata
   * chiesta la lista (il percorso `/sync/all-items/{type}/plantowatch` è già selettivo): una
   * voce senza stato esplicito non va persa. Una voce con un altro stato invece non è una
   * cosa "da guardare" e viene scartata.
   */
  fun planToWatch(body: Map<String, Any>?): List<SimklWatchlistEntry> =
    parse(body).filter { it.status.equals(SIMKL_STATUS_PLAN_TO_WATCH, ignoreCase = true) }

  private fun parseBucket(
    bucket: List<*>?,
    kind: SimklWatchlistKind,
    isAnime: Boolean
  ): List<SimklWatchlistEntry> {
    if (bucket == null) return emptyList()
    return bucket.mapNotNull { raw -> parseEntry(raw, kind, isAnime) }
  }

  internal fun parseEntry(
    raw: Any?,
    kind: SimklWatchlistKind,
    isAnime: Boolean
  ): SimklWatchlistEntry? {
    val entry = raw as? Map<*, *> ?: return null
    val container = (entry["movie"] as? Map<*, *>)
      ?: (entry["show"] as? Map<*, *>)
      ?: (entry["anime"] as? Map<*, *>)
      ?: return null
    val title = container["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
    val ids = container["ids"] as? Map<*, *>
    return SimklWatchlistEntry(
      kind = kind,
      title = title,
      year = container["year"].asIntOrNull(),
      tmdbId = ids?.get("tmdb").asIntOrNull(),
      imdbId = ids?.get("imdb")?.toString()?.takeIf { it.isNotBlank() },
      simklId = ids?.get("simkl").asLongOrNull(),
      status = entry["status"]?.toString()?.takeIf { it.isNotBlank() }
        ?: SIMKL_STATUS_PLAN_TO_WATCH,
      isAnime = isAnime
    )
  }
}

// ── Esito di una sincronizzazione ─────────────────────────────────────────────

enum class SimklSyncStatus { NOT_LOGGED_IN, UP_TO_DATE, SYNCED, PARTIAL, FAILED, SESSION_EXPIRED }

/**
 * Sezione della sincronizzazione di cui non è stato possibile scaricare i dati. Serve a
 * NON dichiarare riuscita una sincronizzazione in cui una delle due letture è fallita.
 */
enum class SimklSyncSection(val label: String) {
  PLAYBACK("Continua a guardare"),
  WATCHLIST("watchlist"),
  WATCHED_LIBRARY("libreria vista")
}

data class SimklSyncResult(
  val status: SimklSyncStatus,
  val message: String,
  val adoptedMovies: Int = 0,
  val adoptedEpisodes: Int = 0,
  /**
   * Serie riposizionate sull'ultimo episodio visto secondo la libreria SIMKL
   * (`status=watching` in `/sync/all-items/shows`): sono i progressi episodici che non
   * passano dai punti di pausa. Contate a parte da [adoptedEpisodes] (che sono le
   * posizioni parziali delle sessioni di pausa) per rendere leggibile il report.
   */
  val adoptedLibraryEpisodes: Int = 0,
  val watchedShowsUpdated: Int = 0,
  /** Titoli della watchlist SIMKL portati nei Preferiti in questa sincronizzazione. */
  val watchlistImported: Int = 0,
  /** Titoli della watchlist già presenti nei Preferiti: nessuna scrittura. */
  val watchlistAlreadyFavorite: Int = 0,
  /** Voci della watchlist non riconducibili a un titolo (registrate, mai inventate). */
  val watchlistUnresolved: List<String> = emptyList(),
  /** Sezioni di cui il pull è fallito: la sincronizzazione NON è completa. */
  val failedSections: List<SimklSyncSection> = emptyList(),
  val skipped: Map<SimklDecisionReason, Int> = emptyMap(),
  val unresolvedTitles: List<String> = emptyList(),
  /**
   * Voci di playback rimandate al giro successivo (durata o metadati non ancora noti).
   * Non sono perse: restano memorizzate e verranno riprovate.
   */
  val deferredForRetry: Int = 0,
  /** Voci il cui recupero è stato abbandonato dopo [SimklPendingPlaybackStore.MAX_ATTEMPTS]. */
  val abandonedRecoveries: Int = 0,
  val finishedAtEpochMs: Long = 0L
) {
  val adoptedTotal: Int get() = adoptedMovies + adoptedEpisodes + adoptedLibraryEpisodes

  /** true solo se TUTTE le sezioni sono state scaricate: nessun esito "riuscito" parziale. */
  val isComplete: Boolean get() = failedSections.isEmpty()
}
