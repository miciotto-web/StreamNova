package com.example.data.model

import androidx.compose.runtime.Immutable
import kotlin.math.ceil

enum class MediaType(val labelItalian: String) {
  FILM("Film"),
  SERIE_TV("Serie TV"),
  DOCUMENTARIO("Documentario")
}

enum class VideoResolution(val label: String, val badge: String) {
  HD_720P("HD 720p", "HD"),
  FULL_HD_1080P("Full HD 1080p", "1080p"),
  UHD_4K("Ultra HD 4K HDR", "4K HDR")
}

enum class SearchTypeFilter(val label: String) {
  ALL("Tutti"),
  FILM("Film"),
  SERIE_TV("Serie TV")
}

/**
 * Filtro che alimenta una categoria della sezione Ricerca.
 *
 * [GENRE] è il percorso storico: i cataloghi degli addon (o Cinemeta) vengono
 * interrogati per genere; solo quando nessuno copre la combinazione tipo+genere si
 * usa il fallback TMDB Discover.
 *
 * [TMDB_TV_GENRE] è una categoria servita NATIVAMENTE da TMDB tramite un genere TV
 * ufficiale (es. "Talk", id 10767). Il percorso non consulta cataloghi di addon,
 * non richiede la modalità Debrid e non dipende dalle sorgenti di riproduzione:
 * `discover/tv` con `with_genres` è la fonte primaria, non un fallback.
 */
enum class SearchCategoryFilter {
  GENRE,
  TMDB_TV_GENRE
}

data class AudioTrack(
  val id: String,
  val language: String,
  val format: String,
)

data class SubtitleTrack(
  val id: String,
  val language: String,
  val isClosedCaption: Boolean = false,
)

data class Episode(
  val id: String,
  val seasonNumber: Int,
  val episodeNumber: Int,
  val title: String,
  val synopsis: String,
  val durationMinutes: Int,
  val thumbnailRes: Int? = null,
  val thumbnailUrl: String? = null,
  val videoUrl: String,
  val currentProgressMs: Long = 0L,
  val totalDurationMs: Long = (durationMinutes * 60 * 1000).toLong(),
  val rating: Float? = null,
  val airDate: String? = null,
  val seriesImdbId: String? = null,
) {
  val season: Int get() = seasonNumber
  val episode: Int get() = episodeNumber

  val effectiveImdbId: String?
    get() = seriesImdbId?.takeIf { it.isNotBlank() }
      ?: id.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(":")

  val isCompleted: Boolean
    get() = totalDurationMs > 0 && currentProgressMs >= (totalDurationMs * 0.9)

  val progressFraction: Float
    get() = if (totalDurationMs > 0) (currentProgressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f

  val remainingMinutesText: String
    get() {
      val mins = WatchProgress.remainingMinutes(totalDurationMs, currentProgressMs, durationMinutes)
        ?: return ""
      return "${mins}m rimanenti"
    }
}

/**
 * Prossima puntata annunciata da TMDB (`next_episode_to_air`), così come restituita:
 * `airDate` resta la stringa `yyyy-MM-dd` originale perché la validazione (data non
 * valida, già trascorsa, ecc.) avviene in un unico punto in [com.example.ui.util.NextEpisodeBadgeRules].
 *
 * Non viene MAI derivata dalla periodicità degli episodi né dall'ultimo episodio andato
 * in onda: se TMDB non la espone, il badge non esiste.
 */
data class NextEpisodeInfo(
  val seasonNumber: Int,
  val episodeNumber: Int,
  val airDate: String,
)

/**
 * Punto più avanzato della visione di una serie: episodio e progresso registrati per
 * quella puntata (letti dalla tabella `episode_progress`).
 *
 * [progressMs] è la posizione **dell'episodio** (non quella aggregata del titolo) e
 * [durationMs] è la durata reale misurata dal player per lo stesso episodio (`0` se
 * sconosciuta). Serve a «Prossimo episodio dall'ultimo raggiunto» per distinguere una
 * puntata in corso da una conclusa senza inventare durate.
 */
data class WatchedEpisodePoint(
  val seasonNumber: Int,
  val episodeNumber: Int,
  val progressMs: Long,
  val durationMs: Long
)

/**
 * Modello universale di un contenuto, usato direttamente dalle card e dalle liste Compose.
 *
 * [Immutable] è una PROMESSA sul contratto del tipo, verificata sul codice: tutte le
 * proprietà sono `val` e nessun punto dell'app modifica in-place le collezioni
 * (`qualityTags`, `genres`, `cast`, `episodes`, `seasonEpisodesCount`). Il data layer
 * produce sempre istanze nuove tramite il costruttore o `copy(...)` con liste ricostruite
 * (`map`, `filter`, `+`), mai mutando quelle già pubblicate in uno `StateFlow`.
 *
 * Perché serve: senza l'annotazione il compiler Compose considera il tipo **unstable**
 * (le `List<T>`/`Map` sono interfacce e l'immutabilità non è dimostrabile), quindi nelle
 * card un `media` nuovo ma identico viene confrontato per IDENTITÀ e forza ricomposizione.
 * Con `@Immutable` il confronto è per `equals()` (data class): una card con gli stessi dati
 * viene saltata anche se l'istanza è nuova.
 *
 * Vincolo per il futuro: chi costruisce un [MediaItem] non deve passare una collezione che
 * verrà mutata dopo. Violare questa regola produce una card "congelata" (salto di
 * ricomposizione nonostante il contenuto sia cambiato).
 */
@Immutable
data class MediaItem(
  val id: String,
  val title: String,
  val originalTitle: String = "",
  val synopsis: String,
  val videoUrl: String,
  val resolution: VideoResolution = VideoResolution.UHD_4K,
  val qualityTags: List<String> = listOf("4K", "HDR", "Dolby Atmos", "16+"),
  val backdropRes: Int? = null,
  val backdropUrl: String? = null,
  val posterRes: Int? = null,
  val posterUrl: String? = null,
  val logoUrl: String? = null,
  val tmdbId: Int? = null,
  val imdbId: String? = null,
  val type: MediaType = MediaType.FILM,
  val year: Int = 2024,
  /**
   * Data di uscita completa (`yyyy-MM-dd`) quando la sorgente la espone in modo
   * affidabile: TMDB `release_date` per i film, `first_air_date` per le serie TV.
   *
   * `null` = data sconosciuta o non affidabile (cataloghi addon che dichiarano solo
   * l'anno, date malformate, cache locali prive del dato): il filtro "Nascondi
   * contenuti non rilasciati" lascia il contenuto visibile.
   */
  val releaseDate: String? = null,
  val durationMinutes: Int = 120,
  val seasonsCount: Int? = null,
  val rating: Float = 8.5f,
  val genres: List<String> = emptyList(),
  val director: String = "",
  val cast: List<String> = emptyList(),
  val currentProgressMs: Long = 0L,
  val totalDurationMs: Long = (durationMinutes * 60 * 1000).toLong(),
  val lastWatchedSeason: Int? = null,
  val lastWatchedEpisode: Int? = null,
  val lastWatchedAt: Long? = null,
  val isFavorite: Boolean = false,
  /**
   * Provenienza dello stato "preferito": `null` = scritto dal locale (utente o dati
   * storici), [com.example.data.local.MEDIA_SOURCE_SIMKL] = importato dalla watchlist
   * SIMKL. La disconnessione da SIMKL rimuove solo i preferiti di provenienza SIMKL.
   */
  val favoriteSource: String? = null,
  /**
   * Provenienza del progresso corrente della scheda: `null` = scritto dal locale (player o
   * dati storici), [com.example.data.local.MEDIA_SOURCE_SIMKL] = adottato dalla
   * sincronizzazione SIMKL (ultimo scrittore possessore).
   */
  val progressSource: String? = null,
  /**
   * true se la scheda è stata CREATA dall'importazione SIMKL: al logout può essere
   * eliminata, ma solo se non ha acquisito stato locale (preferito o progresso propri).
   */
  val importedFromSimkl: Boolean = false,
  val provider: String? = null,
  val isTop10: Boolean = false,
  val isTrending: Boolean = false,
  val episodes: List<Episode> = emptyList(),
  val seasonEpisodesCount: Map<Int, Int> = emptyMap(),
  /**
   * Voti esterni.
   * `null` = dato non disponibile per il titolo -> la relativa pillola NON viene mostrata.
   * [tmdbRating] corrisponde al vote_average di TMDB (deriva da [rating] se non
   * valorizzato esplicitamente).
   */
  val tmdbRating: Float? = rating.takeIf { it > 0f },
  val mdbListScore: Int? = null,
  val rottenTomatoesScore: Int? = null,
  val ageRating: Int? = null,
  /**
   * Stato TMDB della serie (`Returning Series`, `Ended`, `Canceled`, …).
   * `null` = stato sconosciuto: il badge prossima puntata NON viene mostrato.
   */
  val tvStatus: String? = null,
  /**
   * Prossima puntata annunciata da TMDB. `null` = nessuna informazione verificabile
   * (serie conclusa, in pausa, dato non ancora recuperato): badge assente.
   */
  val nextEpisode: NextEpisodeInfo? = null
) {
  val progressFraction: Float
    get() = if (totalDurationMs > 0) (currentProgressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f

  /** true se il titolo e' considerato "visto" (progresso >= 90% della durata totale). */
  val isWatched: Boolean
    get() = totalDurationMs > 0 && currentProgressMs >= (totalDurationMs * 0.9)

  /** true se e' in corso di visione: avviato ma non ancora completato. */
  val isInProgress: Boolean
    get() = currentProgressMs > 0L && !isWatched

  val effectiveImdbId: String?
    get() = imdbId?.takeIf { it.isNotBlank() }
      ?: id.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(":")

  /** Anno da mostrare a schermo: stringa vuota quando non disponibile (mai "0"). */
  val displayYear: String
    get() = if (year > 0) year.toString() else ""

  val formattedDuration: String
    get() {
      val hours = durationMinutes / 60
      val mins = durationMinutes % 60
      return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

  val formattedRemainingOrProgress: String
    get() {
      if (currentProgressMs <= 0L) return formattedDuration
      val remainingMins = WatchProgress.remainingMinutes(totalDurationMs, currentProgressMs, durationMinutes)
        ?: return formattedDuration
      val totalDuration = WatchProgress.totalDurationWithFallback(totalDurationMs, durationMinutes)
      val watchedHours = (currentProgressMs / 3600000).toInt()
      val watchedMins = ((currentProgressMs % 3600000) / 60000).toInt()
      val totalHours = (totalDuration / 3600000).toInt()
      val totalMins = ((totalDuration % 3600000) / 60000).toInt()

      return if (remainingMins < 60) {
        "${remainingMins}m rimanenti"
      } else {
        "${watchedHours}h ${watchedMins}m / ${totalHours}h ${totalMins}m"
      }
    }
}

data class CastMember(
  val id: Int,
  val name: String,
  val character: String,
  val profileUrl: String? = null
)

data class CrewMember(
  val id: Int,
  val name: String,
  val job: String,
  val department: String? = null
)

data class ProductionCompany(
  val id: Int,
  val name: String,
  val logoUrl: String? = null,
  val originCountry: String? = null
)

data class PersonDetails(
  val id: Int,
  val name: String,
  val biography: String = "",
  val birthday: String? = null,
  val deathday: String? = null,
  val placeOfBirth: String? = null,
  val profileUrl: String? = null,
  val knownForDepartment: String? = null,
  val popularity: Float? = null
)

data class ExtendedMediaDetails(
  val tmdbId: Int,
  val title: String,
  val originalTitle: String,
  val overview: String,
  val backdropUrl: String?,
  val posterUrl: String?,
  /**
   * Clear logo originale (immagine trasparente) estratto da `images.logos` con priorita'
   * lingua italiana, poi inglese, poi il miglior disponibile. `null` se TMDB non ha logo:
   * in quel caso la UI mostra il titolo testuale in stile display.
   */
  val logoUrl: String? = null,
  val releaseYear: Int,
  /**
   * Data di uscita (`yyyy-MM-dd`) restituita da TMDB: `release_date` per i film,
   * `first_air_date` per le serie TV. `null` = TMDB non espone una data valida.
   *
   * A differenza di [releaseYear] non contiene fallback: la sezione «Dettagli Film»
   * mostra l'anno solo da qui (o da [MediaItem.releaseDate]), mai da un valore di
   * comodo.
   */
  val releaseDate: String? = null,
  val rating: Float,
  val voteCount: Int = 0,
  val durationMinutes: Int = 120,
  /**
   * Durata dichiarata da TMDB senza fallback (`runtime` per i film, primo valore di
   * `episode_run_time` per le serie). `null` = dato assente: la riga «Durata» non
   * viene mostrata.
   */
  val runtimeMinutes: Int? = null,
  val seasonsCount: Int? = null,
  val episodesCount: Int? = null,
  val genres: List<String> = emptyList(),
  val tagline: String? = null,
  val status: String? = null,
  val cast: List<CastMember> = emptyList(),
  val director: String = "",
  val directorMember: CastMember? = null,
  val similarItems: List<MediaItem> = emptyList(),
  val isTv: Boolean = false,
  val seasonEpisodesCount: Map<Int, Int> = emptyMap(),
  val ageRating: Int? = null,
  /**
   * Certificazione TMDB grezza (es. `PG-13`, `T`, `VM14`) selezionata con la stessa
   * priorità di [com.example.data.util.AgeRatingClassifier] (IT, poi fallback).
   * `null` = nessuna certificazione valida: la riga non viene mostrata.
   */
  val certification: String? = null,
  val productionCompanies: List<ProductionCompany> = emptyList(),
  /**
   * Nome leggibile del paese d'origine (`production_countries`/`origin_country`).
   * `null` = TMDB non lo espone: la riga non viene mostrata.
   */
  val originCountry: String? = null,
  /** Prossima puntata annunciata da TMDB (`next_episode_to_air`), `null` se assente. */
  val nextEpisode: NextEpisodeInfo? = null
)

sealed interface MediaDetailUiState {
  object Idle : MediaDetailUiState
  data class Loading(val baseMedia: MediaItem? = null) : MediaDetailUiState
  data class Success(
    val media: MediaItem,
    val details: ExtendedMediaDetails
  ) : MediaDetailUiState
  data class Error(
    val baseMedia: MediaItem? = null,
    val message: String = "Impossibile recuperare i dettagli estesi da TMDB"
  ) : MediaDetailUiState
}

data class EpisodeItem(
  val id: String,
  val tmdbId: Int,
  val episodeNumber: Int,
  val seasonNumber: Int,
  val title: String,
  val overview: String,
  val stillUrl: String? = null,
  val durationMinutes: Int = 50,
  val rating: Float = 8.0f,
  val airDate: String? = null,
  val videoUrl: String = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"
) {
  val formattedDuration: String
    get() = if (durationMinutes > 0) "${durationMinutes} min" else "45 min"

  val episodeBadge: String
    get() = "E%02d".format(episodeNumber)
}

fun EpisodeItem.toEpisode(seriesId: String, progressMs: Long = 0L, seriesImdbId: String? = null): Episode {
  val cleanSeriesImdb = seriesImdbId?.takeIf { it.isNotBlank() }
    ?: if (seriesId.startsWith("tt", ignoreCase = true)) seriesId.substringBefore(":") else null
  return Episode(
    id = "${seriesId}_s${seasonNumber}e${episodeNumber}",
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    synopsis = overview,
    durationMinutes = durationMinutes.coerceAtLeast(15),
    thumbnailUrl = stillUrl,
    videoUrl = videoUrl,
    currentProgressMs = progressMs,
    rating = rating,
    airDate = airDate,
    seriesImdbId = cleanSeriesImdb
  )
}

data class SeasonItem(
  val id: Int,
  val seasonNumber: Int,
  val name: String,
  val overview: String = "",
  val airDate: String? = null,
  val episodes: List<EpisodeItem> = emptyList()
)

sealed interface SeasonEpisodesUiState {
  object Idle : SeasonEpisodesUiState
  data class Loading(val seasonNumber: Int, val seriesId: String? = null) : SeasonEpisodesUiState
  data class Success(val seasonNumber: Int, val season: SeasonItem, val seriesId: String? = null) : SeasonEpisodesUiState
  data class Error(val seasonNumber: Int, val message: String, val seriesId: String? = null) : SeasonEpisodesUiState
}

/**
 * Calcolo dei "minuti rimanenti" per le card di "Continua a guardare" e badge episodio.
 *
 * I millisecondi **reali** misurati dal player ([resumePositionMs]/[totalDurationMs]) hanno
 * priorita' assoluta; il runtime TMDB ([MediaItem.durationMinutes], spesso assente o
 * impreciso per le serie TV) viene usato **solo** come fallback per i record legacy privi
 * di durata in millisecondi.
 */
object WatchProgress {

  /**
   * true se una durata dichiarata dal player e' utilizzabile.
   *
   * Esclude i valori che ExoPlayer restituisce prima di `STATE_READY` o durante uno
   * switch: `C.TIME_UNSET` (`Long.MIN_VALUE + 1`, negativo) e `0L`.
   */
  fun isValidDuration(durationMs: Long): Boolean = durationMs > 0L

  /**
   * Durata effettiva da usare nella card: millisecondi reali se noti, altrimenti il
   * runtime TMDB convertito. `0L` se nessuna delle due fonti e' disponibile.
   */
  fun totalDurationWithFallback(totalDurationMs: Long, runtimeMinutes: Int): Long =
    if (isValidDuration(totalDurationMs)) totalDurationMs
    else runtimeMinutes.coerceAtLeast(0).toLong() * 60_000L

  /**
   * Merge di una durata dichiarata dal player con quella gia' nota per lo stesso
   * titolo/episodio.
   *
   * Una durata non valida (`<= 0`, quindi anche `C.TIME_UNSET`) NON sovrascrive **mai**
   * la durata valida gia' conosciuta: si conserva [existingDurationMs].
   */
  fun resolveDuration(existingDurationMs: Long, reportedDurationMs: Long): Long =
    if (isValidDuration(reportedDurationMs)) reportedDurationMs
    else existingDurationMs.coerceAtLeast(0L)

  /** Millisecondi mancanti; `null` quando la durata totale non e' nota in alcuna forma. */
  fun remainingMs(totalDurationMs: Long, resumePositionMs: Long, runtimeMinutes: Int = 0): Long? {
    val total = totalDurationWithFallback(totalDurationMs, runtimeMinutes)
    if (total <= 0L) return null
    return (total - resumePositionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
  }

  /**
   * Minuti mancanti arrotondati per **eccesso** (ceil): il tempo realmente ancora da
   * guardare e' sempre >= al troncamento, coerente con il countdown del player.
   * `null` quando la durata totale non e' nota.
   */
  fun remainingMinutes(totalDurationMs: Long, resumePositionMs: Long, runtimeMinutes: Int = 0): Int? =
    remainingMs(totalDurationMs, resumePositionMs, runtimeMinutes)
      ?.let { ms -> ceil(ms / 60_000.0).toInt() }

  /** Tolleranza usata dal player per considerare concluso un episodio (ultimi 15 s). */
  const val END_TOLERANCE_MS = 15_000L

  /**
   * true se la posizione ha raggiunto la fine del contenuto entro [END_TOLERANCE_MS].
   *
   * Usata per verificare la conclusione di un episodio con la durata **reale** misurata
   * dal player: una durata non valida (`<= 0`) non prova nulla e restituisce `false`.
   */
  fun isAtEnd(positionMs: Long, durationMs: Long, toleranceMs: Long = END_TOLERANCE_MS): Boolean =
    isValidDuration(durationMs) &&
      positionMs > 0L &&
      positionMs >= durationMs - toleranceMs.coerceAtLeast(0L)

  /**
   * true solo quando la posizione salvata coincide con la durata nota (nessuna
   * tolleranza). È il criterio conservativo usato per la riconciliazione all'avvio:
   * una posizione "quasi alla fine" basata su una durata stimata non deve mai
   * rimuovere una serie.
   */
  fun hasReachedEnd(positionMs: Long, durationMs: Long): Boolean =
    isValidDuration(durationMs) && positionMs >= durationMs
}


