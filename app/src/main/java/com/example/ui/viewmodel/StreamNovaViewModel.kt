package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.R
import com.example.data.api.TmdbApiService
import com.example.data.api.TmdbApiClient
import com.example.data.model.AudioTrack
import com.example.data.model.Episode
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.PersonDetails
import com.example.data.model.ProductionCompany
import com.example.data.model.SearchTypeFilter
import com.example.data.model.SeasonEpisodesUiState
import com.example.data.model.SeasonItem
import com.example.data.model.SubtitleTrack
import com.example.data.model.VideoResolution
import com.example.data.model.toEpisode
import android.content.Context
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.BackBufferOption
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.InitialBufferOption
import com.example.data.prefs.TargetBufferOption
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitlePosition
import com.example.data.prefs.SubtitleSize
import com.example.data.repository.MediaRepository
import com.example.data.repository.HomeCatalogs
import com.example.data.repository.LastWorkingStreamRepository
import com.example.data.repository.ProviderCoverage
import com.example.data.repository.StremioCatalogRepository
import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.StremioLiveChannelClassifier
import com.example.data.torbox.TorBoxRepository
import com.example.data.update.UpdateCheckState
import com.example.data.update.UpdateDownloadState
import com.example.data.update.UpdateRepository
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamResult
import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamDiagnosticSinkFactory
import com.example.data.opensubtitles.OpenSubtitlesConnectionCheck
import com.example.data.opensubtitles.OpenSubtitlesConnectionVerifier
import com.example.data.opensubtitles.OpenSubtitlesDownloadError
import com.example.data.opensubtitles.OpenSubtitlesVerifyError
import com.example.data.opensubtitles.OpenSubtitlesSubtitleAdapter
import com.example.data.opensubtitles.OpenSubtitlesSubtitleDownloader
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.domain.model.Subtitle
import com.example.ui.components.StreamingProvider
import com.example.ui.components.ProviderConstants
import com.example.ui.components.FOCUS_ROW_ANY
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import com.example.ui.screens.player.NextEpisodePromptUtils
import com.example.ui.screens.player.PlayerSourceFallback
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.example.ui.screens.player.PlayerChapter
import com.example.ui.screens.player.PlayerChapterUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Tag di log del ViewModel (ricerca sorgenti + playback). */
private const val TAG = "StreamNovaVM"
private const val HTTP_TRACE = "HTTP_TRACE"
/** Messaggio diagnostico quando il trailer non e' recuperabile da TMDB. */
private const val TRAILER_UNAVAILABLE_MESSAGE = "Trailer non disponibile per questo titolo"
/** Timeout per l'interrogazione dei cataloghi Stremio durante la ricerca testuale. */
private const val STREMIO_SEARCH_TIMEOUT_MS = 8_000L
private var contentIdCounter = 0

/**
 * Mappatura deterministica TMDB genre ID → nome genere canonico TMDB (elenco ufficiale
 * dei generi TMDB per i film).
 *
 * I nomi NON vengono inventati: sono quelli dichiarati da TMDB. Un valore di questa
 * tabella viene usato come extra `genre` SOLO se un catalogo Stremio attivo dichiara
 * esattamente la stessa opzione nel proprio manifest (cfr.
 * `StreamNovaViewModel.resolveStremioGenre`), altrimenti resta il percorso TMDB.
 */
internal val TMDB_MOVIE_GENRE_NAMES: Map<Int, String> = mapOf(
  28 to "Action",
  12 to "Adventure",
  16 to "Animation",
  35 to "Comedy",
  80 to "Crime",
  99 to "Documentary",
  18 to "Drama",
  10751 to "Family",
  14 to "Fantasy",
  36 to "History",
  27 to "Horror",
  10402 to "Music",
  9648 to "Mystery",
  10749 to "Romance",
  878 to "Science Fiction",
  10770 to "TV Movie",
  53 to "Thriller",
  10752 to "War",
  37 to "Western"
)

/**
 * Mappatura deterministica TMDB genre ID → nome genere canonico TMDB per le serie TV.
 * Stesse regole di [TMDB_MOVIE_GENRE_NAMES].
 */
internal val TMDB_TV_GENRE_NAMES: Map<Int, String> = mapOf(
  10759 to "Action & Adventure",
  16 to "Animation",
  35 to "Comedy",
  80 to "Crime",
  99 to "Documentary",
  18 to "Drama",
  10751 to "Family",
  10765 to "Sci-Fi & Fantasy",
  10762 to "Kids",
  10763 to "News",
  10764 to "Reality",
  10768 to "War & Politics",
  9648 to "Mystery"
)

/**
 * Auto-pick universale per la selezione automatica dello stream (usato dal
 * passaggio automatico al prossimo episodio e, in autoplay, dall'apertura
 * diretta di un titolo).
 *
 * - **Caso A (Debrid attiva e account configurato)** — [StreamingEngineMode.DEBRID_TORBOX]
 *   con `isDebridConfigured = true`: tenta di sbloccare il primo flusso Debrid valido
 *   (infoHash → URL CDN via [TorBoxRepository.resolveInfoHash]); gli stream addon già
 *   sbloccati con URL diretto vengono accettati così come sono.
 * - **Caso B (HTTP diretta o Debrid disattivato/assente)**: filtra le sorgenti alla
 *   ricerca del primo stream con link HTTP/HTTPS diretto riproducibile (mai torrent
 *   grezzi, magnet o manifest JSON).
 * - **Caso C (nessuno stream riproducibile automaticamente)**: restituisce `null`;
 *   il chiamante NON deve marcare la sessione come fallita ma aprire il dialog di
 *   selezione manuale delle sorgenti.
 *
 * @return lo stream selezionato con `streamUrl` valorizzato e riproducibile,
 *         oppure `null` se nessuna sorgente è selezionabile automaticamente.
 */
internal suspend fun resolveAutoPickSource(
  sources: List<StreamSource>,
  streamingEngineMode: StreamingEngineMode,
  isDebridConfigured: Boolean,
  season: Int?,
  episode: Int?
): StreamSource? {
  val useDebridUnlock = streamingEngineMode == StreamingEngineMode.DEBRID_TORBOX && isDebridConfigured
  for (candidate in sources) {
    val playable = if (useDebridUnlock && candidate.infoHash != null && candidate.streamUrl == null) {
      // Caso A: Debrid attiva e configurata -> sblocco del primo flusso Debrid.
      TorBoxRepository.setRequestMetadata(season, episode)
      val directUrl = TorBoxRepository.resolveInfoHash(candidate.infoHash, candidate.fileIdx)
      if (directUrl != null) candidate.copy(streamUrl = directUrl) else null
    } else {
      candidate
    }
    // Caso B: solo link HTTP(S) diretti riproducibili ("non torrent grezzo").
    if (playable != null && StreamManager.isPlayableDirectUrl(playable.streamUrl.orEmpty())) {
      return playable
    }
  }
  return null
}

/** Tag di log del fast-path "stessa fonte" del prossimo episodio. */
private const val SAME_SOURCE_TAG = "NextEpisodeSameSource"

/**
 * Timeout breve (4s) per il fast-path "stesso torrent di stagione": se il file
 * del nuovo episodio non si sblocca in tempo si passa alla risoluzione normale
 * (che termina comunque sul dialog di selezione, mai in un loop infinito).
 */
internal const val SAME_SOURCE_TIMEOUT_MS = 4_000L

/**
 * Timeout rigido (3.5s) imposto al fast-path bingewatching nel chiamante
 * ([StreamNovaViewModel.openStreamForMedia]): se la risoluzione dello stesso
 * torrent Debrid non termina entro questa finestra si prosegue IMMEDIATAMENTE
 * con la ricerca normale delle sorgenti. Non esiste alcun percorso che possa
 * restare sospeso oltre questo limite.
 */
internal const val NEXT_EPISODE_FAST_PATH_TIMEOUT_MS = 3_500L

/**
 * Timeout globale (10s) dell'intera risoluzione stream per il prossimo episodio:
 * allo scadere, se nessuno stream è stato avviato, si azzera `_isSwitchingEpisode`
 * e si mostra il dialog di selezione manuale delle sorgenti. Mai spinner infinito.
 */
internal const val NEXT_EPISODE_RESOLUTION_TIMEOUT_MS = 10_000L

/**
 * Finestra di assestamento (ms) dell'auto-pick.
 *
 * I provider HTTP rispondono in tempi molto diversi: il primo arrivo puo'
 * essere una sorgente a qualita' bassa, mentre quella migliore arriva piu'
 * tardi. Dopo la prima emissione si attende questo breve lasso di tempo per
 * raccogliere le sorgenti migliori e POI il player viene avviato una volta
 * sola con la migliore disponibile.
 *
 * La finestra e' volutamente breve (stesso ordine di grandezza del fast-start
 * di [com.example.data.streaming.StreamManager]): la riproduzione non resta
 * sospesa indefinitamente e il download non viene mai ricaricato a meta'.
 */
internal const val AUTO_PICK_SETTLE_WINDOW_MS = 1_200L

/**
 * Priorita' assoluta del bingewatching: risolve il nuovo episodio DALLA STESSA
 * fonte Debrid dell'episodio corrente quando questa era un torrent di stagione.
 *
 * Verifica all'interno dello stesso torrent/container Debrid se esiste il file
 * corrispondente al nuovo (season, episode): se presente lo avvia DIRETTAMENTE
 * (nessuna interrogazione degli addon). La URL arriva da
 * [TorBoxRepository.resolveSeasonPackEpisode] (iniettabile nei test).
 *
 * @return lo stream dell'episodio successivo (stesso addon/metadati della fonte
 *         corrente, nuovo URL CDN) oppure null se la fonte non era un torrent di
 *         stagione, TorBox non e' configurato o lo sblocco non riesce nel tempo
 *         massimo [SAME_SOURCE_TIMEOUT_MS].
 */
internal suspend fun tryResolveNextEpisodeFromSameTorrent(
  pinned: StreamSource,
  season: Int,
  episode: Int,
  seasonPackUrlResolver: suspend (String, Int, Int) -> String? = TorBoxRepository::resolveSeasonPackEpisode
): StreamSource? {
  val hash = pinned.infoHash?.trim()?.takeIf { it.isNotBlank() } ?: return null
  if (!TorBoxRepository.isConfigured()) return null
  val url = withTimeoutOrNull(SAME_SOURCE_TIMEOUT_MS) {
    seasonPackUrlResolver(hash, season, episode)
  } ?: return null
  if (url.isBlank()) return null
  Log.i(SAME_SOURCE_TAG, "S${season}E${episode}: sbloccato dallo stesso torrent ${hash.take(8)}...")
  return pinned.copy(streamUrl = url)
}

/**
 * Riorganizza le sorgenti del prossimo episodio dando massima precedenza a
 * quelle con maggiore **affinita' di fonte** con l'episodio corrente ([pinned]):
 *
 *  1. stesso torrent (infoHash) — il caso "season pack" piu' forte;
 *  2. stesso addon Debrid (addonName);
 *  3. stesso provider HTTP (serverName);
 *  4. firma di release simile (qualita', codec, gruppo).
 *
 * Ordinamento stabile: a parita' di punteggio resta l'ordine originale del
 * resolver. Funzione pura, verificabile nei test.
 */
internal fun rankBySameSourceAffinity(
  sources: List<StreamSource>,
  pinned: StreamSource
): List<StreamSource> {
  if (sources.size <= 1) return sources
  val pinnedHash = pinned.infoHash?.trim()?.lowercase()
  val pinnedAddon = pinned.addonName?.trim()?.lowercase()
  val pinnedServer = pinned.serverName.trim().lowercase().takeIf { it.isNotBlank() }
  val pinnedQuality = sameSourceQualityKey(pinned)
  val pinnedCodec = pinned.codec?.trim()?.lowercase()
  val pinnedGroup = sameSourceReleaseGroup(pinned.releaseTitle)

  fun affinityScore(source: StreamSource): Int {
    var score = 0
    // Priorita' assoluta: stesso torrent (season pack) sopra ogni altra firma.
    if (pinnedHash != null && source.infoHash?.trim()?.lowercase() == pinnedHash) score += 500
    if (pinnedAddon != null && source.addonName?.trim()?.lowercase() == pinnedAddon) score += 200
    if (pinnedServer != null && source.serverName.trim().lowercase() == pinnedServer) score += 150
    if (pinnedQuality != null && sameSourceQualityKey(source) == pinnedQuality) score += 60
    if (pinnedCodec != null && source.codec?.trim()?.lowercase() == pinnedCodec) score += 40
    if (pinnedGroup != null && sameSourceReleaseGroup(source.releaseTitle) == pinnedGroup) score += 30
    return score
  }

  return sources.withIndex()
    .sortedWith(
      compareByDescending<IndexedValue<StreamSource>> { affinityScore(it.value) }
        .thenBy { it.index }
    )
    .map { it.value }
}

/** Chiave qualita' della release (altezza effettiva) per il match di firma. */
private fun sameSourceQualityKey(source: StreamSource): String? {
  val height = source.effectiveHeight
  return if (height > 0) height.toString() else null
}

/**
 * Nome "gruppo" della release (es. "AMZN", "WEB", "NF") estratto dal titolo:
 * primo token interamente maiuscolo di lunghezza 2-6, prima di qualita'/codec.
 */
private fun sameSourceReleaseGroup(releaseTitle: String?): String? {
  if (releaseTitle.isNullOrBlank()) return null
  val tokens = releaseTitle.replace(Regex("[._]+"), " ").trim().split(' ')
  return tokens.firstOrNull { token ->
    token.length in 2..6 && token == token.uppercase() && token.any { c -> c in 'A'..'Z' }
  }
}

/**
 * Origine dei contenuti mostrati in una categoria della sezione Ricerca.
 */
enum class SearchCategorySource {
  /** Contenuti restituiti dai cataloghi degli addon (Xperience/Stremio). */
  ADDON,

  /**
   * Fallback ESPPLICITO a TMDB Discover: il catalogo addon non è stato caricato
   * (assente nel manifest, errore o risposta vuota). La UI deve mostrarlo come
   * fallback, mai come caricamento addon riuscito.
   */
  TMDB_FALLBACK
}

/**
 * Stato del caricamento della categoria attiva della sezione Ricerca.
 *
 * @param source null finché il primo caricamento non è terminato (solo spinner).
 * @param addonLabel etichetta del catalogo addon caricato ("catalogo • addon").
 * @param statusMessage motivo del fallback oppure errore riscontrato sugli addon:
 *        mostrato in UI per non mascherare un catalogo non caricato.
 * @param genresCatalogsCopied true quando i cataloghi configurati possono coprire TUTTI
 *        i tipi previsti dalla categoria (film e/o serie): la fonte sono gli addon e il
 *        fallback TMDB non è stato usato.
 * @param genresTmdbSkipped true quando TMDB è stato usato come fallback per i generi MA
 *        lo switch "Usa TMDB come fallback per i generi" è disattivato: nessuna chiamata
 *        TMDB per questa categoria, solo lo stato vuoto dichiarato.
 */
data class SearchCategoryState(
  val source: SearchCategorySource? = null,
  val addonLabel: String? = null,
  val statusMessage: String? = null,
  val genresCatalogsCopied: Boolean = false,
  val genresTmdbSkipped: Boolean = false
)

/**
 * Esito del caricamento per genere di UN tipo di contenuto, registrato da
 * [StreamNovaViewModel.initCategory].
 *
 * Serve a dimostrare in modo verificabile che TMDB è stato interrogato solo quando
 * nessun catalogo realmente configurato copriva quella combinazione tipo+genere:
 * ogni motivo corrisponde a un caso che la UI deve poter distinguere.
 *
 * @param mediaType tipo di contenuto selezionato (film o serie TV).
 * @param reason motivo per cui TMDB è stato usato (o non lo è stato).
 * @param tmdbPages pagine TMDB richieste per questo tipo (0 = nessuna chiamata).
 * @param tmdbItems risultati TMDB grezzi, prima della deduplicazione.
 * @param catalogWasEmpty true quando almeno un catalogo compatibile era stato
 *        interrogato con successo ma ha risposto HTTP 200 senza metas: il fallback
 *        TMDB è partito DOPO un catalogo vuoto, non per mancanza di copertura.
 */
data class GenreLoadRecord(
  val mediaType: MediaType,
  val reason: GenreLoadReason,
  val tmdbPages: Int = 0,
  val tmdbItems: Int = 0,
  val catalogWasEmpty: Boolean = false
)

/**
 * Motivo dell'esito di una combinazione tipo+genere della sezione Ricerca.
 */
enum class GenreLoadReason {
  /** Almeno un catalogo configurato applica il filtro per genere: fonte = addon. */
  SUPPORTED_BY_CATALOG,

  /** Catalogo compatibile interrogato ma senza risultati, con il fallback TMDB disattivato. */
  SUPPORTED_BUT_EMPTY,

  /** Cataloghi compatibili falliti (rete/HTTP): il fallback TMDB NON deve mascherarli. */
  FAILED,

  /** Nessun catalogo configurato applica il filtro per genere: risultati da TMDB. */
  TMDB_FALLBACK,

  /** Nessun catalogo compatibile e switch disattivato: nessuna chiamata TMDB. */
  TMDB_DISABLED,

  /** Nessun catalogo compatibile e TMDB non configurato/non raggiungibile. */
  TMDB_UNAVAILABLE
}

enum class SidebarSection(val title: String) {
  HOME("Home"),
  FILM("Film"),
  SERIE_TV("Serie TV"),
  I_MIEI_CONTENUTI("I miei contenuti"),
  CERCA("Cerca"),
  ADDON("Addon"),
  IMPOSTAZIONI("Impostazioni")
}

enum class ScreenState {
  BROWSING,
  DETAIL,
  PLAYER,
  PROVIDER
}

/**
 * Stato UI della verifica dell'account TorBox (`GET /user/me`):
 * - [Unknown]: nessuna verifica eseguita in questa sessione.
 * - [Checking]: richiesta in corso.
 * - [Connected]: chiave valida → indicatore verde "Collegato".
 * - [Error]: chiave non valida / errore di rete.
 * - [NotConfigured]: nessuna chiave o toggle Instant Debrid disabilitato.
 */
sealed interface TorBoxAccountState {
  object Unknown : TorBoxAccountState
  object Checking : TorBoxAccountState
  object NotConfigured : TorBoxAccountState
  data class Connected(val email: String?, val plan: String?) : TorBoxAccountState
  data class Error(val message: String) : TorBoxAccountState
}

/**
 * Stato UI della verifica della connessione OpenSubtitles (`GET /api/v1/subtitles`):
 * - [Unknown]: nessuna verifica eseguita in questa sessione.
 * - [Checking]: richiesta in corso.
 * - [Connected]: chiave accettata dal servizio ufficiale.
 * - [NotConfigured]: nessuna chiave salvata (sorgente addon Stremio resta disponibile).
 * - [Error]: esito negativo tipizzato ([OpenSubtitlesVerifyError]); la UI lo converte
 *   in un messaggio localizzato che NON contiene la chiave.
 */
sealed interface OpenSubtitlesConnectionState {
  object Unknown : OpenSubtitlesConnectionState
  object Checking : OpenSubtitlesConnectionState
  object NotConfigured : OpenSubtitlesConnectionState
  object Connected : OpenSubtitlesConnectionState
  data class Error(val error: OpenSubtitlesVerifyError) : OpenSubtitlesConnectionState
}

data class PlayerPlaybackState(
  val media: MediaItem? = null,
  val currentEpisode: Episode? = null,
  val isPlaying: Boolean = true,
  val currentPositionMs: Long = 0L,
  val durationMs: Long = 0L,
  val bufferedPositionMs: Long = 0L,
  val selectedAudio: AudioTrack = MediaRepository.audioTracks[0],
  val selectedSubtitle: SubtitleTrack = MediaRepository.subtitleTracks[0],
  val selectedResolution: VideoResolution = VideoResolution.UHD_4K,
  val areControlsVisible: Boolean = true,
  /** URL del provider streaming: se null si usa quello del catalogo. */
  val streamUrl: String? = null,
  /** Header obbligatori del flusso (Referer/User-Agent): senza essi = 403. */
  val streamHeaders: Map<String, String> = emptyMap(),
  /** Qualità dichiarata del flusso (es. "1080p", "720p"). */
  val streamQuality: String? = null,
  /** Nome del server/provider del flusso. */
  val streamServer: String? = null,
  /**
   * Hint tipo contenuto del flusso: `true` = file progressivo (mp4/mkv, es. link
   * Debrid CDN), `false` = playlist HLS, `null` = inferire dall'estensione URL.
   */
  val streamProgressive: Boolean? = null,
  /**
   * Sottotitoli esterni della sorgente attiva, gia' convertiti nel modello interno.
   * Il player li legge per allegare le `SubtitleConfiguration` al Media3 `MediaItem`.
   */
  val subtitles: List<Subtitle> = emptyList(),
  /**
   * True se lo stream corrente proviene dalla cache "Riusa l'ultimo link".
   * In caso di errore il ViewModel lo invalida e riapre la risoluzione classica.
   */
  val isReusedStream: Boolean = false,
  /**
   * StreamSource dell'episodio corrente (infoHash, addon, provider HTTP, firma
   * della release). E' la base dell'**affinita' di fonte** del prossimo episodio:
   * stesso torrent di stagione, stesso addon/provider e firma di release vicina.
   */
  val currentSource: StreamSource? = null,
  val subtitleOffsetMs: Long = 0L,
)

/**
 * Stato della risoluzione dello stream per il titolo selezionato.
 */
sealed interface StreamResolutionState {
  object Idle : StreamResolutionState
  object Loading : StreamResolutionState
  data class Success(
    val streamUrl: String,
    val mediaItem: MediaItem,
    val episode: Episode? = null,
    val streamHeaders: Map<String, String> = emptyMap(),
    val quality: String? = null,
    val serverName: String? = null,
    val isProgressive: Boolean? = null
  ) : StreamResolutionState
  data class Error(val message: String) : StreamResolutionState
}

/**
 * Bersaglio della selezione sorgente TorBox: il media e l'episodio (se serie TV)
 * per cui sono state risolte le [StreamSource]. Serve solo alla UI della schermata
 * di selezione, che ricava poster/backdrop/logo da [media] senza nuove richieste.
 */
data class SourceDialogTarget(
  val media: MediaItem,
  val episode: Episode? = null
)
typealias SourceSelectionTarget = SourceDialogTarget

/**
 * Stato della riproduzione del trailer **interna** a StreamNova.
 *
 * Il trailer viene mostrato con un player embedded (WebView sull'iframe YouTube) dentro
 * l'app: nessun Intent viene mai inviato all'app esterna di YouTube.
 */
sealed interface TrailerPlaybackState {
  object Idle : TrailerPlaybackState
  object Loading : TrailerPlaybackState
  data class Playing(val youtubeKey: String) : TrailerPlaybackState
  data class Error(val message: String) : TrailerPlaybackState
}

sealed interface PersonDetailUiState {
  object Idle : PersonDetailUiState
  data class Loading(val personId: Int) : PersonDetailUiState
  data class Success(
    val person: PersonDetails,
    val credits: List<MediaItem>
  ) : PersonDetailUiState
  data class Error(val personId: Int, val message: String) : PersonDetailUiState
}

sealed interface CompanyDetailUiState {
  object Idle : CompanyDetailUiState
  data class Loading(val companyId: Int) : CompanyDetailUiState
  data class Success(
    val companyId: Int,
    val companyName: String,
    val logoUrl: String?,
    val movies: List<MediaItem>,
    val tvSeries: List<MediaItem>,
    val topRatedMovies: List<MediaItem> = emptyList(),
    val topRatedTvSeries: List<MediaItem> = emptyList(),
    val originCountry: String? = null,
    val headquarters: String? = null
  ) : CompanyDetailUiState
  data class Error(val companyId: Int, val message: String) : CompanyDetailUiState
}

/**
 * Pacchetto atomico e immutabile della sessione di riproduzione aperta.
 *
 * Contiene **esclusivamente** i dati del media e dell'episodio effettivamente cliccati
 * dall'utente. Viene usato per scrivere in Room il record di "Continua a guardare"
 * senza dipendere da variabili globali mutevoli del ViewModel, prevenendo sovrascritture
 * incrociate tra schede aperte in momenti diversi.
 */
data class PlaybackSession(
  val mediaId: String,
  val mediaTitle: String,
  val posterUrl: String?,
  val isSeries: Boolean,
  val seasonNumber: Int?,
  val episodeNumber: Int?
)

/**
 * Rientro nella scheda Dettaglio dopo la chiusura del player.
 *
 * La riproduzione avviata da una card di riga (es. «Continua a guardare») non passa dal
 * Dettaglio: al BACK dal player la destinazione è la scheda Dettaglio del contenuto
 * effettivamente riprodotto, con l'EPISODIO appena visto come punto di ripresa ([media]).
 *
 * L'identità è quella del media riprodotto (id/TMDB del contenuto davvero in riproduzione),
 * mai quella di una scheda gemella dello stesso titolo con un identificatore diverso: è la
 * stessa scheda che il player sta usando, quindi non esiste ambiguità su quale film o serie
 * si sta riaprendo.
 *
 * È un EVENTO, non uno stato persistente: la destinazione viene preparata dal ViewModel
 * ([openDetail]) e consumata da MainActivity, che la traduce nella navigazione del NavHost
 * ([consumePlayerDetailReturn]).
 */
data class PlayerDetailReturn(val media: MediaItem)

/**
 * Stato del download di un sottotitolo della sezione "Sottotitoli Online (OpenSubtitles)".
 *
 * La selezione NON passa più l'URL remoto direttamente al player: il file viene scaricato
 * sul disco (gestendo ZIP/GZIP), salvato in cache e solo il file locale viene allegato a
 * ExoPlayer come traccia esterna. [Error] è recuperabile con un nuovo tentativo.
 */
sealed interface OnlineSubtitleDownloadState {
  /** Nessun download in corso. */
  object Idle : OnlineSubtitleDownloadState

  /** Download in corso: la UI mostra un feedback e disabilita i doppi click. */
  object Downloading : OnlineSubtitleDownloadState

  /**
   * File scaricato e salvato localmente: [localSubtitle] ha `url` che punta al file
   * (uri `file://`) pronto per essere allegato al player e attivato.
   */
  data class Ready(val localSubtitle: Subtitle) : OnlineSubtitleDownloadState

  /** Download fallito: la UI mostra il motivo e consente di riprovare. */
  data class Error(val error: OpenSubtitlesDownloadError) : OnlineSubtitleDownloadState
}

/** Sottocartella della cache app dedicata ai file sottotitolo scaricati da OpenSubtitles. */
private const val ONLINE_SUBTITLES_CACHE_DIR = "opensubtitles"

class StreamNovaViewModel(
  /**
   * Dispatcher per il lavoro di I/O della risoluzione stream (addon, TorBox,
   * sottotitoli). Iniettabile: nei test si passa un `TestDispatcher` per rendere
   * l'esecuzione asincrona deterministica e attendibile con `advanceUntilIdle()`.
   */
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  /**
   * Verificatore della connessione OpenSubtitles: sostituibile nei test con un fake
   * che non tocca la rete né richiede la chiave personale reale.
   */
  private val openSubtitlesVerifier: OpenSubtitlesConnectionVerifier = OpenSubtitlesConnectionVerifier()
) : ViewModel() {

  private val _currentSection = MutableStateFlow(SidebarSection.HOME)
  val currentSection: StateFlow<SidebarSection> = _currentSection.asStateFlow()

  /**
   * Contesto dell'applicazione per i messaggi che il ViewModel deve localizzare
   * (sezione Ricerca). È assegnato al primo avvio della UI; se assente, i messaggi
   * ricadono sulla stringa vuota invece di far fallire il caricamento.
   */
  @Volatile
  private var appContext: Context? = null

  /** Collega il contesto applicativo (idempotente, nessun leak: è il contesto app). */
  fun attachContext(context: Context) {
    appContext = context.applicationContext
  }

  private val _homeScrollToTopEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val homeScrollToTopEvent: SharedFlow<Unit> = _homeScrollToTopEvent.asSharedFlow()

  fun requestHomeScrollToTop() {
    _homeScrollToTopEvent.tryEmit(Unit)
  }

  private val _screenState = MutableStateFlow(ScreenState.BROWSING)
  val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

  private val _selectedMedia = MutableStateFlow<MediaItem?>(null)
  val selectedMedia: StateFlow<MediaItem?> = _selectedMedia.asStateFlow()

  private val detailMediaStack = mutableListOf<MediaItem>()

  // Target di focus quando si torna indietro nella catena "Altro come questo".
  private val _chainRestoreMediaId = MutableStateFlow<String?>(null)
  val chainRestoreMediaId: StateFlow<String?> = _chainRestoreMediaId.asStateFlow()

  fun clearChainRestoreMedia() {
    _chainRestoreMediaId.value = null
  }

  // Focus retention TV (Punto 3: memorizzazione dell'ultima locandina aperta per ripristino al BACK).
  // Il target è la COPPIA (rowKey, mediaId): quando lo stesso titolo compare in più righe
  // (es. Popolari e Trending) solo la riga d'origine può ripristinare il focus. Il rowKey
  // [FOCUS_ROW_ANY] identifica i salvataggi senza riga esplicita (griglie/schermate singole).
  private val _lastFocusedTarget = MutableStateFlow<Pair<String, String>?>(null)
  val lastFocusedTarget: StateFlow<Pair<String, String>?> = _lastFocusedTarget.asStateFlow()

  // Vista legacy del target: solo il mediaId (griglie, SubScreens, segnali di ripristino).
  private val _lastFocusedMediaId = MutableStateFlow<String?>(null)
  val lastFocusedMediaId: StateFlow<String?> = _lastFocusedMediaId.asStateFlow()

  private val _focusRestoreSignal = MutableSharedFlow<String>(extraBufferCapacity = 1)
  val focusRestoreSignal: SharedFlow<String> = _focusRestoreSignal.asSharedFlow()

  // Restore token: cambia ad ogni tentativo di ripristino per forzare l'attivazione nelle card
  private val _focusRestoreToken = MutableStateFlow<Int>(0)
  val focusRestoreToken: StateFlow<Int> = _focusRestoreToken.asStateFlow()

  /**
   * Salva il target di ripristino ROW-SCOPED: alla Back dalla scheda Dettaglio solo le
   * card della riga [rowKey] possono ripristinare il focus su [mediaId].
   */
  fun saveLastFocusedMedia(rowKey: String, mediaId: String) {
    _lastFocusedTarget.value = rowKey to mediaId
    _lastFocusedMediaId.value = mediaId
    // Mutua esclusione: un salvataggio media invalida un target provider rimasto appeso.
    _lastFocusedProviderId.value = null
  }

  /**
   * Compatibilità: salvataggio senza riga esplicita (retrocompatibilità con le chiamate
   * storiche). NON sovrascrive un target già salvato per lo stesso media con una riga
   * specifica: il click di una riga salva prima il target row-scoped e questa chiamata
   * (emessa dal navigatore centrale) non deve degradarlo a "ogni riga".
   */
  fun saveLastFocusedMedia(mediaId: String) {
    val current = _lastFocusedTarget.value
    if (current == null || current.second != mediaId) {
      _lastFocusedTarget.value = FOCUS_ROW_ANY to mediaId
    }
    _lastFocusedMediaId.value = mediaId
    // Mutua esclusione: un salvataggio media invalida un target provider rimasto appeso.
    _lastFocusedProviderId.value = null
  }

  /** Consuma il target di ripristino non appena il focus è stato richiesto con successo. */
  fun clearLastFocusedMedia() {
    _lastFocusedTarget.value = null
    _lastFocusedMediaId.value = null
  }

  /**
   * Target di ripristino per le CARD PROVIDER dell'hub della Home (Netflix, HBO Max, …).
   *
   * Canale DEDICATO: le card provider non sono [com.example.data.model.MediaItem] e la scheda
   * Provider CONSUMA e RILASCIA il target media ([clearLastFocusedMedia]) al primo ingresso,
   * quindi condividerlo azzererebbe il target proprio mentre la scheda è aperta e al BACK il
   * focus non avrebbe nulla da ripristinare (difetto "ritorno dalla scheda provider sull'Hero").
   * Salvato al click della card provider, consumato quando la card riceve DAVVERO il focus.
   */
  private val _lastFocusedProviderId = MutableStateFlow<String?>(null)
  val lastFocusedProviderId: StateFlow<String?> = _lastFocusedProviderId.asStateFlow()

  fun saveLastFocusedProvider(providerId: String) {
    _lastFocusedProviderId.value = providerId
    // Mutua esclusione col target media: la provenienza è la card PROVIDER, non una card media.
    _lastFocusedTarget.value = null
    _lastFocusedMediaId.value = null
  }

  fun clearLastFocusedProvider() {
    _lastFocusedProviderId.value = null
  }

  fun signalFocusRestore(mediaId: String) {
    // Incrementa il token per forzare l'attivazione nelle card
    _focusRestoreToken.value = _focusRestoreToken.value + 1
    _focusRestoreSignal.tryEmit(mediaId)
  }

  /**
   * Rientro nella scheda Dettaglio richiesto alla chiusura del player (vedi
   * [PlayerDetailReturn]): `null` quando il rientro non passa dal Dettaglio. La destinazione
   * è già preparata ([openDetail]); MainActivity la traduce in navigazione e CONSUMA la
   * richiesta, che non sopravvive al rientro che l'ha generata.
   */
  private val _playerDetailReturn = MutableStateFlow<PlayerDetailReturn?>(null)
  val playerDetailReturn: StateFlow<PlayerDetailReturn?> = _playerDetailReturn.asStateFlow()

  /** Consuma la richiesta di rientro nel Dettaglio (vedi [PlayerDetailReturn]). */
  fun consumePlayerDetailReturn() {
    _playerDetailReturn.value = null
  }

  // Focus retention TV per Cast (Punto 5, 7)
  private val _lastFocusedCastMemberId = MutableStateFlow<Int?>(null)
  val lastFocusedCastMemberId: StateFlow<Int?> = _lastFocusedCastMemberId.asStateFlow()

  fun saveLastFocusedCastMember(id: Int) {
    _lastFocusedCastMemberId.value = id
  }

  fun clearLastFocusedCastMember() {
    _lastFocusedCastMemberId.value = null
  }

  // Focus retention TV per Produzione (Punto 6)
  private val _lastFocusedCompanyId = MutableStateFlow<Int?>(null)
  val lastFocusedCompanyId: StateFlow<Int?> = _lastFocusedCompanyId.asStateFlow()

  fun saveLastFocusedCompany(id: Int) {
    _lastFocusedCompanyId.value = id
  }

  fun clearLastFocusedCompany() {
    _lastFocusedCompanyId.value = null
  }

  // Scheda Persona (Punto 7)
  private val _personDetailUiState = MutableStateFlow<PersonDetailUiState>(PersonDetailUiState.Idle)
  val personDetailUiState: StateFlow<PersonDetailUiState> = _personDetailUiState.asStateFlow()

  fun loadPersonDetails(personId: Int, personName: String? = null) {
    viewModelScope.launch(ioDispatcher) {
      _personDetailUiState.value = PersonDetailUiState.Loading(personId)
      try {
        val resolvedId = if (personId <= 0 && !personName.isNullOrBlank()) {
          MediaRepository.searchPersonByName(personName) ?: personId
        } else {
          personId
        }
        val person = MediaRepository.fetchPersonDetails(resolvedId)
        val credits = MediaRepository.fetchPersonCombinedCredits(resolvedId)


        if (person != null) {
          _personDetailUiState.value = PersonDetailUiState.Success(person, credits)
        } else {
          _personDetailUiState.value = PersonDetailUiState.Error(personId, "Dati della persona non disponibili")
        }
      } catch (e: Exception) {
        _personDetailUiState.value = PersonDetailUiState.Error(personId, e.message ?: "Errore caricamento dettagli persona")
      }
    }
  }

  // Scheda Studio / Produzione (Punto 6)
  private val _companyDetailUiState = MutableStateFlow<CompanyDetailUiState>(CompanyDetailUiState.Idle)
  val companyDetailUiState: StateFlow<CompanyDetailUiState> = _companyDetailUiState.asStateFlow()

  fun loadCompanyDetails(companyId: Int, companyName: String? = null, logoUrl: String? = null) {
    viewModelScope.launch(ioDispatcher) {
      _companyDetailUiState.value = CompanyDetailUiState.Loading(companyId)
      try {
        // Catalogo dello studio: popolari e più votati (film e serie) più i dati
        // anagrafici dello studio, recuperati in parallelo per non allungare l'attesa.
        val companyDtoDeferred = async { MediaRepository.fetchCompanyDetails(companyId) }
        val moviesDeferred = async { MediaRepository.fetchMoviesByCompany(companyId) }
        val topRatedMoviesDeferred = async {
          MediaRepository.fetchMoviesByCompany(companyId, sortBy = "vote_average.desc", minVoteCount = 50)
        }
        val tvSeriesDeferred = async { MediaRepository.fetchTvByCompany(companyId) }
        val topRatedSeriesDeferred = async {
          MediaRepository.fetchTvByCompany(companyId, sortBy = "vote_average.desc", minVoteCount = 50)
        }

        val companyDto = companyDtoDeferred.await()
        val movies = moviesDeferred.await()
        val topRatedMovies = topRatedMoviesDeferred.await()
        val tvSeries = tvSeriesDeferred.await()
        val topRatedTvSeries = topRatedSeriesDeferred.await()

        val effectiveName = companyName?.takeIf { it.isNotBlank() } ?: companyDto?.name ?: "Studio di Produzione"
        val effectiveLogo = logoUrl?.takeIf { it.isNotBlank() } ?: companyDto?.logoPath?.let { TmdbApiClient.logoUrl(it) }

        _companyDetailUiState.value = CompanyDetailUiState.Success(
          companyId = companyId,
          companyName = effectiveName,
          logoUrl = effectiveLogo,
          movies = movies,
          tvSeries = tvSeries,
          topRatedMovies = topRatedMovies,
          topRatedTvSeries = topRatedTvSeries,
          originCountry = companyDto?.originCountry,
          headquarters = companyDto?.headquarters
        )
      } catch (e: Exception) {
        _companyDetailUiState.value = CompanyDetailUiState.Error(companyId, e.message ?: "Errore caricamento studio")
      }
    }
  }

  val detailUiState: StateFlow<MediaDetailUiState> = MediaRepository.detailUiState

  private val _selectedSeasonNumber = MutableStateFlow(1)
  val selectedSeasonNumber: StateFlow<Int> = _selectedSeasonNumber.asStateFlow()

  private val _seasonEpisodesUiState = MutableStateFlow<SeasonEpisodesUiState>(SeasonEpisodesUiState.Idle)
  val seasonEpisodesUiState: StateFlow<SeasonEpisodesUiState> = _seasonEpisodesUiState.asStateFlow()

  private val _selectedProvider = MutableStateFlow<StreamingProvider?>(null)
  val selectedProvider: StateFlow<StreamingProvider?> = _selectedProvider.asStateFlow()

  private val _playbackState = MutableStateFlow(PlayerPlaybackState())
  val playbackState: StateFlow<PlayerPlaybackState> = _playbackState.asStateFlow()

  // Scrubbing virtuale disaccoppiato con debounce, accelerazione a step e navigazione capitoli
  private val _uiSeekPosition = MutableStateFlow<Long?>(null)
  val uiSeekPosition: StateFlow<Long?> = _uiSeekPosition.asStateFlow()

  private val _isScrubbing = MutableStateFlow(false)
  val isScrubbing: StateFlow<Boolean> = _isScrubbing.asStateFlow()

  private val _seekStepDelta = MutableStateFlow(0L)
  val seekStepDelta: StateFlow<Long> = _seekStepDelta.asStateFlow()

  private val _chapters = MutableStateFlow<List<PlayerChapter>>(emptyList())
  val chapters: StateFlow<List<PlayerChapter>> = _chapters.asStateFlow()

  private val _currentStepMs = MutableStateFlow(10_000L)
  val currentStepMs: StateFlow<Long> = _currentStepMs.asStateFlow()

  private var scrubDebounceJob: Job? = null
  private var lastSeekPressTimestampMs: Long = 0L
  private var consecutiveSeekPressCount: Int = 0
  private var scrubOriginPositionMs: Long = 0L
  private var attachedPlayer: Player? = null
  internal var currentTimeProvider: () -> Long = { System.currentTimeMillis() }

  private val _onlineSubtitles = MutableStateFlow<List<Subtitle>>(emptyList())
  val onlineSubtitles: StateFlow<List<Subtitle>> = _onlineSubtitles.asStateFlow()

  private val _isOnlineSubtitlesLoading = MutableStateFlow(false)
  val isOnlineSubtitlesLoading: StateFlow<Boolean> = _isOnlineSubtitlesLoading.asStateFlow()

  // ── Download del sottotitolo online selezionato ("Sottotitoli Online" OpenSubtitles) ──
  private val _onlineSubtitleDownload = MutableStateFlow<OnlineSubtitleDownloadState>(
    OnlineSubtitleDownloadState.Idle
  )
  val onlineSubtitleDownload: StateFlow<OnlineSubtitleDownloadState> =
    _onlineSubtitleDownload.asStateFlow()

  /** Downloader OpenSubtitles: iniettabile nei test (api/client/dispatcher fittizi). */
  internal var onlineSubtitleDownloader = OpenSubtitlesSubtitleDownloader(ioDispatcher = ioDispatcher)

  /**
   * Directory cache dove salvare i file sottotitolo scaricati: risolta alla richiesta con
   * il contesto collegato dall'UI ([attachContext] o fallback reflection). In errorne
   * (contesto assente) il download fallisce in modo controllato.
   */
  internal var onlineSubtitleCacheDirProvider: () -> File? = {
    val context = appContext ?: currentApplicationContext()
    context?.let { File(it.cacheDir, ONLINE_SUBTITLES_CACHE_DIR) }
  }

  private var onlineSubtitlesSearchJob: Job? = null
  private var onlineSubtitleDownloadJob: Job? = null

  /** Ultimo sottotitolo online richiesto: alla base dell'azione "Riprova". */
  private var lastSelectedOnlineSubtitle: Subtitle? = null

  // Stato della risoluzione dello stream con gestione rigida del timeout e lifecycle
  private val _streamResolutionState = MutableStateFlow<StreamResolutionState>(StreamResolutionState.Idle)
  val streamResolutionState: StateFlow<StreamResolutionState> = _streamResolutionState.asStateFlow()

  /**
   * True durante il passaggio al prossimo episodio: la risoluzione delle sorgenti
   * per la nuova puntata è in corso e il player NON deve mai mostrare l'errore
   * "Stream non disponibile" (né azzerare la sessione) finché il flag è attivo.
   */
  private val _isSwitchingEpisode = MutableStateFlow(false)
  val isSwitchingEpisode: StateFlow<Boolean> = _isSwitchingEpisode.asStateFlow()

  private var streamJob: Job? = null
  /**
   * Job del fetch di dettaglio/metadati corrente. Cancellato all'apertura di un nuovo
   * media per impedire che una risposta HTTP in ritardo (scheda aperta prima) sovrascriva
   * i metadati del media realmente in riproduzione.
   */
  private var currentMediaFetchJob: Job? = null
  private var selectSeasonJob: Job? = null

  /**
   * Sessione di riproduzione corrente (pacchetto atomico e immutabile). Impostata in
   * [executeOpenPlayer] con i soli dati del media/episodio cliccati e azzerata in
   * [closePlayer]. Il salvataggio "Continua a guardare" usa questi campi, mai
   * variabili globali mutevoli.
   */
  private var currentSession: PlaybackSession? = null

  /**
   * Resetta immediatamente lo stato di risoluzione dello stream a Idle
   * e cancella ogni Job di ricerca attivo.
   */
  fun resetStreamState() {
    Log.d(TAG, "resetStreamState: reimpostazione a Idle e cancellazione Job")
    currentMediaFetchJob?.cancel()
    currentMediaFetchJob = null
    streamJob?.cancel()
    streamJob = null
    loadStreamGeneration++
    _showSourceDialog.value = false
    _availableSources.value = emptyList()
    // NOTA: [_sourceCandidates] (lista del selettore interno al player) NON viene
    // azzerata qui: resetStreamState viene invocato anche dall'onDispose di
    // ExoPlayer durante una ricreazione del player, a sessione in corso. La lista
    // dei candidati resta valida finche' il player non viene chiuso (closePlayer).
    _sourceDialogMedia.value = null
    _streamResolutionState.value = StreamResolutionState.Idle
    _streamResult.value = StreamResult.Idle
    _isSwitchingEpisode.value = false
    // NOTA: _nextEpisode e playerSeasonEpisodes NON vengono azzerati qui:
    // resetStreamState viene invocato anche dall'onDispose di ExoPlayer durante la
    // ricreazione del player (cambio impostazioni), a sessione in corso. Il valore
    // viene comunque ricalcolato da refreshNextEpisodeForPlayback a ogni apertura player.
  }

  // ── "Prossimo Episodio" ────────────────────────────────────────────────
  // Stato osservato dalla UI del player: episodio successivo risolto in modo
  // deterministico (stessa stagione E+1, poi stagione successiva E1).
  private val _nextEpisode = MutableStateFlow<Episode?>(null)
  val nextEpisode: StateFlow<Episode?> = _nextEpisode.asStateFlow()

  /**
   * Episodi caricati in background per (mediaId, stagione) quando `media.episodes`
   * è vuota al momento del play (item da catalogo/Cinemeta senza episodi inline).
   */
  private val playerSeasonEpisodes = mutableMapOf<Pair<String, Int>, List<Episode>>()

  /**
   * Ricalcola l'episodio successivo per il player e lo espone in [nextEpisode].
   *
   * Pool = `media.episodes` + stagioni già caricate in background. Se il prossimo
   * non è determinabile e la serie ha un TMDB id, carica in background la stagione
   * corrente e la successiva (cache-first Room + TMDB) e ricalcola. Log con tag
   * `[NextEpisode]` per la diagnostica su dispositivo.
   */
  fun refreshNextEpisodeForPlayback(media: MediaItem, currentEpisode: Episode?) {
    if (media.type != MediaType.SERIE_TV || currentEpisode == null) {
      _nextEpisode.value = null
      return
    }
    val effectiveImdb = media.effectiveImdbId
      ?: currentEpisode.effectiveImdbId
      ?: media.id.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(":")

    val pool = mergePlayerEpisodes(media)
    val next = NextEpisodePromptUtils.resolveNextEpisode(pool, currentEpisode)
    val enrichedNext = if (next != null && next.seriesImdbId.isNullOrBlank() && !effectiveImdb.isNullOrBlank()) {
      next.copy(seriesImdbId = effectiveImdb)
    } else {
      next
    }
    _nextEpisode.value = enrichedNext
    Log.i(
      "NextEpisode",
      "Current: S${currentEpisode.seasonNumber}E${currentEpisode.episodeNumber}, " +
        "Next: ${enrichedNext?.let { "S${it.seasonNumber}E${it.episodeNumber} ${it.title}" } ?: "NON TROVATO"}, " +
        "TotalEpisodes: ${pool.size}"
    )
    if (enrichedNext != null) return
    val tmdbId = media.tmdbId
    if (tmdbId == null || tmdbId <= 0) return
    val seasonsToLoad = listOf(currentEpisode.seasonNumber, currentEpisode.seasonNumber + 1)
      .filter { season -> playerSeasonEpisodes[media.id to season].isNullOrEmpty() }
    if (seasonsToLoad.isEmpty()) return
    viewModelScope.launch {
      var updated = false
      for (season in seasonsToLoad) {
        try {
          val details = MediaRepository.getSeasonDetails(tmdbId, season, media)
          val loaded = details.episodes.map { it.toEpisode(media.id, seriesImdbId = effectiveImdb) }
          if (loaded.isNotEmpty()) {
            playerSeasonEpisodes[media.id to season] = loaded
            updated = true
            Log.i("NextEpisode", "Caricati ${loaded.size} episodi S$season per ${media.id} (background)")
          }
        } catch (e: Exception) {
          Log.w("NextEpisode", "Caricamento episodi S$season fallito: ${e.message}")
        }
      }
      if (updated) {
        val merged = mergePlayerEpisodes(media)
        val resolved = NextEpisodePromptUtils.resolveNextEpisode(merged, currentEpisode)
        val enrichedResolved = if (resolved != null && resolved.seriesImdbId.isNullOrBlank() && !effectiveImdb.isNullOrBlank()) {
          resolved.copy(seriesImdbId = effectiveImdb)
        } else {
          resolved
        }
        _nextEpisode.value = enrichedResolved
        Log.i(
          "NextEpisode",
          "Ricalcolo dopo caricamento: Current: S${currentEpisode.seasonNumber}E${currentEpisode.episodeNumber}, " +
            "Next: ${enrichedResolved?.let { "S${it.seasonNumber}E${it.episodeNumber} ${it.title}" } ?: "NON TROVATO"}, " +
            "TotalEpisodes: ${merged.size}"
        )
      }
    }
  }

  private fun mergePlayerEpisodes(media: MediaItem): List<Episode> =
    (media.episodes + playerSeasonEpisodes.filterKeys { it.first == media.id }.values.flatten())
      .distinctBy { it.id }

  // Stato del ciclo di ricerca sorgenti (estrazione provider -> player)
  private val _streamResult = MutableStateFlow<StreamResult>(StreamResult.Idle)
  val streamResult: StateFlow<StreamResult> = _streamResult.asStateFlow()

  // Modalità del motore di streaming (Debrid TorBox vs HTTP Web)
  private val _streamingEngineMode = MutableStateFlow(AppSettingsRepository.streamingEngineMode.value)
  val streamingEngineMode: StateFlow<StreamingEngineMode> = _streamingEngineMode.asStateFlow()

  // Stato del dialog di selezione sorgente (autoplay disabilitato)
  private val _showSourceDialog = MutableStateFlow(false)
  val showSourceDialog: StateFlow<Boolean> = _showSourceDialog.asStateFlow()

  private val _availableSources = MutableStateFlow<List<StreamSource>>(emptyList())
  val availableSources: StateFlow<List<StreamSource>> = _availableSources.asStateFlow()

  /**
   * Sorgenti valide disponibili per il titolo corrente, mostrate dal selettore
   * "Sorgente video" interno al player.
   *
   * Diversamente da [_availableSources] (che viene azzerata alla scelta, per
   * chiudere il dialog di scelta iniziale), questa lista SOPRAVVIVE alla scelta
   * manuale: se la sorgente scelta fallisce l'utente deve poter riaprire il
   * selettore e provarne un'altra senza riavviare la risoluzione.
   *
   * L'aggiornamento e' progressivo: ogni provider che risponde arricchisce la
   * lista, senza mai riavviare la sorgente attualmente in riproduzione.
   */
  private val _sourceCandidates = MutableStateFlow<List<StreamSource>>(emptyList())
  val sourceCandidates: StateFlow<List<StreamSource>> = _sourceCandidates.asStateFlow()

  /**
   * Visibilita' del selettore sorgenti interno al player.
   *
   * E' indipendente dal dialog di scelta iniziale ([_showSourceDialog]): il
   * selettore del player resta apribile durante la riproduzione, il buffering
   * e lo stato di errore, cosi' l'utente puo' sempre provare un'altra sorgente.
   */
  private val _showSourceSelector = MutableStateFlow(false)
  val showSourceSelector: StateFlow<Boolean> = _showSourceSelector.asStateFlow()

  /**
   * Token incrementato a ogni cambio della sorgente in riproduzione.
   *
   * Due percorsi lo incrementano: la scelta **manuale** dell'utente
   * ([selectSource]) e il **fallback automatico** su errore definitivo
   * ([handleSourcePlaybackFailure]). In entrambi i casi il player deve ricaricare
   * il flusso anche quando l'URL non cambia (a parita' di URL cambiano header e
   * metadati) e lo fa sulla STESSA istanza di ExoPlayer, senza duplicazioni ne'
   * avvii concorrenti.
   */
  private val _manualSourceSelectionToken = MutableStateFlow(0)
  val manualSourceSelectionToken: StateFlow<Int> = _manualSourceSelectionToken.asStateFlow()

  /**
   * Chiavi logiche ([PlayerSourceFallback.keyOf]) delle sorgenti gia' tentate nel
   * ciclo di riproduzione corrente.
   *
   * Una sorgente che ha gia' fallito non viene ritentata automaticamente nello
   * stesso ciclo: il fallback non puo' quindi entrare in un ciclo infinito ne'
   * alternarsi continuamente tra due sorgenti. Il ciclo riparte (insieme vuoto) a
   * ogni nuova risoluzione ([openStreamForMedia]), a ogni scelta manuale
   * ([selectSource], che e' autorevole e puo' ritentare anche una sorgente
   * fallita) e alla chiusura del player ([closePlayer]).
   *
   * Letto e scritto solo dal thread principale, come il resto dello stato.
   */
  private val attemptedSourceKeys = mutableSetOf<String>()

  /**
   * True mentre una scelta manuale della sorgente e' in corso di applicazione
   * (risoluzione TorBox on-demand inclusa).
   *
   * In quella finestra il fallback automatico non deve inserirsi tra la richiesta
   * dell'utente e il suo avvio: la scelta manuale resta autorevole.
   */
  @Volatile
  private var manualSourceSelectionInFlight = false

  /**
   * Pubblica le sorgenti appena risolte.
   *
   * Alimenta sia la lista del dialog di scelta iniziale ([_availableSources]) sia
   * quella del selettore interno al player ([_sourceCandidates]). L'aggiornamento
   * e' progressivo: le emissioni successive dei provider arricchiscono la lista
   * senza toccare il flusso in riproduzione.
   */
  private fun publishResolvedSources(sources: List<StreamSource>) {
    _availableSources.value = sources
    _sourceCandidates.value = sources
  }

  /**
   * Media/episodio per cui è in corso la selezione sorgente TorBox. Esposto alla UI
   * per comporre la schermata di selezione (poster, backdrop, logo, SxEx) senza
   * introdurre nuove chiamate di rete: sono gli stessi dati già risolti dal flow.
   */
  private val _sourceDialogMedia = MutableStateFlow<SourceDialogTarget?>(null)
  val sourceDialogMedia: StateFlow<SourceDialogTarget?> = _sourceDialogMedia.asStateFlow()
  val sourceSelectionTarget: StateFlow<SourceDialogTarget?> = _sourceDialogMedia.asStateFlow()

  /**
   * Stato del trailer interno: `Idle` quando nessun trailer e' richiesto, `Loading` durante
   * la ricerca della chiave YouTube su TMDB, `Playing` con la chiave da embeddare in-app.
   */
  private val _trailerState = MutableStateFlow<TrailerPlaybackState>(TrailerPlaybackState.Idle)
  val trailerState: StateFlow<TrailerPlaybackState> = _trailerState.asStateFlow()
  private var loadTrailerJob: Job? = null

  /**
   * Generazione del loadStream corrente: incrementata a ogni nuova chiamata e
   * invalidata quando il job corrente viene cancellato (closePlayer/dismissSourceDialog).
   * Nel catch si usa questo contatore (e non _screenState) per distinguere un job
   * vecchio da uno corrente.
   */
  private var loadStreamGeneration = 0

  /** Stato schermata attivo PRIMA dell'apertura del Player: usato per ripristinare l'origine al BACK. */
  private var screenBeforePlayer: ScreenState? = null
  private var sectionBeforePlayer: SidebarSection = SidebarSection.HOME

  /**
   * Provenienza della riproduzione avviata da una card di riga: coppia (rowKey, mediaId)
   * registrata al click della card (es. «Continua a guardare»).
   *
   * La riproduzione avviata direttamente da una card NON passa dal Dettaglio: la Home resta
   * composta sotto l'overlay del player e al ritorno nessun segnale riportava il focus dentro
   * il contenuto (il target di [saveLastFocusedMedia] è del percorso Dettaglio/Provider),
   * quindi il focus restava alla prima superficie focalizzabile (la sidebar). Al BACK dal
   * player la destinazione è ora la SCHEDA DETTAGLIO del contenuto appena riprodotto (vedi
   * [PlayerDetailReturn]); la provenienza viene consumata UNA SOLA VOLTA alla chiusura del
   * player (vedi [publishPlayerReturnTarget]).
   *
   * Non è il target di ripristino della Home: finché il player è aperto la Home è ancora
   * composta e un target attivo verrebbe consumato dal proprio ciclo di vita (gate `canFocus`
   * + rilascio da `onFocusChanged`) prima che il player si chiuda.
   */
  private var playbackOriginTarget: Pair<String, String>? = null

  /**
   * Rientro nella scheda Dettaglio in attesa che MainActivity applichi la navigazione
   * ([deferCloseForDetailReturn] -> [completePlayerClose]).
   *
   * Finché è valorizzato il player NON è stato smontato: resta composto a tutto schermo e copre
   * la destinazione sottostante (la Home), quindi il passaggio dal player alla scheda Dettaglio
   * non mostra mai la Home — nemmeno per una cornice. La navigazione avviene sotto l'overlay e
   * la chiusura vera e propria viene conclusa con la destinazione ormai corrente.
   */
  private var pendingDetailReturnClose: PlayerDetailReturn? = null

  /**
   * Registra la PROVENIENZA della riproduzione avviata dalla card ([rowKey], [mediaId]) di una
   * riga della Home (es. «Continua a guardare»): al BACK dal player si rientra nei Dettagli
   * del contenuto riprodotto (vedi [PlayerDetailReturn]).
   */
  fun savePlaybackFocusOrigin(rowKey: String, mediaId: String) {
    playbackOriginTarget = rowKey to mediaId
  }

  /** ID della categoria selezionata in Cerca (preserva al BACK dal Player). */
  private val _selectedSearchCategoryId = MutableStateFlow<String?>(null)
  val selectedSearchCategoryId: StateFlow<String?> = _selectedSearchCategoryId.asStateFlow()

    fun setSelectedSearchCategoryId(categoryId: String?) {
      _selectedSearchCategoryId.value = categoryId
      if (categoryId == null) {
        // Uscita dalla categoria: invalida eventuali caricamenti ancora in volo
        // e azzera stato, origine e risultati.
        categoryLoadGeneration++
        activeSearchCategoryId = null
        activeCategoryEntries = emptyList()
        _categoryItems.value = emptyList()
        _categoryState.value = SearchCategoryState()
        _isCategoryLoading.value = false
      }
    }

  // Interrogazione parallela dei provider di streaming HTTP diretto:
  // 1) VixSrc/VixCloud (istantaneo via TMDB ID), 2) CB01 e 3) Eurostreaming
  // (cataloghi italiani, ricerca per titolo/anno) con timeout per ciascuno.
  internal var streamManager = StreamManager(diagnostics = StreamDiagnosticSinkFactory.DEFAULT)
  internal var openSubtitlesProvider = OpenSubtitlesSubtitleProvider(ioDispatcher = ioDispatcher)

  /**
   * Fast-path bingewatching "stesso torrent di stagione" per il prossimo episodio.
   * Overridable nei test (default: sblocco TorBox del file dell'episodio richiesto).
   */
  internal var seasonPackEpisodeResolver:
    suspend (pinned: StreamSource, season: Int, episode: Int) -> StreamSource? =
    { pinned, season, episode -> tryResolveNextEpisodeFromSameTorrent(pinned, season, episode) }

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _errorMessage = MutableStateFlow<String?>(null)
  val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

  private val _isRefreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

  val allMedia: StateFlow<List<MediaItem>> = MediaRepository.mediaList

  private val _homeCatalogs = MutableStateFlow(
    StremioCatalogRepository.buildDefaultHomeCatalogs(MediaRepository.mediaList.value)
  )
  val homeCatalogs: StateFlow<HomeCatalogs> = _homeCatalogs.asStateFlow()

  private val _stremioProviderMedia = MutableStateFlow<Map<String, List<MediaItem>>>(emptyMap())
  val stremioProviderMedia: StateFlow<Map<String, List<MediaItem>>> = _stremioProviderMedia.asStateFlow()

  // Cache in-memory del catalogo per provider (id -> lista STABILITA). Congela l'ORDINE dei
  // poster: alla riapertura la lista salvata viene emessa SUBITO e nessuna nuova richiesta di
  // rete parte a rimescolare i dati. Si ricarica da rete solo se la lista è vuota (cache miss)
  // o se l'utente esegue un refresh esplicito delle fonti (cambio/installazione addon).
  private val providerCatalogCache = mutableMapOf<String, List<MediaItem>>()

  /** Stato del catalogo Stremio per provider: decide la fonte dati della schermata Provider. */
  data class StremioProviderCatalogState(
    val hasConfirmedBindings: Boolean = false,
    val itemCount: Int = 0,
    val confirmedCatalogs: Int = 0,
    /** Copertura del catalogo provider: NONE = TMDB, PARTIAL = merge, FULL = solo Stremio. */
    val coverage: ProviderCoverage = ProviderCoverage.NONE,
    /**
     * true se i cataloghi Stremio provengono dal FALLBACK Cinemeta (solo senza
     * addon di catalogo personalizzati): in merge il catalogo specifico del
     * provider va PRIMA del fallback.
     */
    val fallbackToDefault: Boolean = false
  )

  private val _stremioProviderStates = MutableStateFlow<Map<String, StremioProviderCatalogState>>(emptyMap())
  val stremioProviderStates: StateFlow<Map<String, StremioProviderCatalogState>> = _stremioProviderStates.asStateFlow()

  private val _categoryProviderMedia = MutableStateFlow<Map<Pair<String, Boolean>, List<MediaItem>>>(emptyMap())
  val categoryProviderMedia: StateFlow<Map<Pair<String, Boolean>, List<MediaItem>>> = _categoryProviderMedia.asStateFlow()

  private val categoryProviderLoadingKeys = ConcurrentHashMap<Pair<String, Boolean>, Boolean>()
  private val categoryProviderPages = ConcurrentHashMap<Pair<String, Boolean>, Int>()
  private val categoryProviderHasMore = ConcurrentHashMap<Pair<String, Boolean>, Boolean>()

  /**
   * Ultimo refresh dei cataloghi Home. Le emissioni di [allMedia] durante la
   * sincronizzazione TMDB sono multiple: senza cancellare il tentativo
   * precedente si accumulavano risoluzioni Cinemeta/addon concorrenti (rete e
   * CPU) mentre la UI era già in fase di composizione.
   */
  private var homeCatalogsRefreshJob: Job? = null

  fun refreshHomeCatalogs(mediaList: List<MediaItem> = allMedia.value) {
    homeCatalogsRefreshJob?.cancel()
    homeCatalogsRefreshJob = viewModelScope.launch(Dispatchers.IO) {
      try {
        val resolved = StremioCatalogRepository.resolveHomeCatalogs(mediaList)
        // Una categoria in caricamento (sezione Ricerca) ha la precedenza: Home e Ricerca
        // condividono la cache dei cataloghi, e pubblicare qui i valori di un refresh
        // avviato prima invaliderebbe la raccolta dei target della categoria corrente.
        if (_isCategoryLoading.value) return@launch
        _homeCatalogs.value = resolved
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Risoluzione cataloghi Home fallita: ${e.message}")
      }
    }
  }

  init {
    viewModelScope.launch {
      MediaRepository.loadFromCache()
      refreshCatalog()
      
      // Osserva i cambiamenti della modalità del motore di streaming
      AppSettingsRepository.streamingEngineMode.collect { mode ->
        _streamingEngineMode.value = mode
        fallbackFromUnavailableSection()
      }
    }
    viewModelScope.launch {
      _screenState.collect { state ->
        Log.d("BACK_TRACE", "STREAMNOVAVM: modifica _screenState a $state")
      }
    }
    viewModelScope.launch {
      allMedia.collect { list ->
        refreshHomeCatalogs(list)
      }
    }
    viewModelScope.launch {
      StremioAddonRepository.addons.collect {
        _stremioProviderMedia.value = emptyMap()
        _stremioProviderStates.value = emptyMap()
        // Refresh esplicito delle fonti: la cache ordine provider va svuotata insieme ai dati.
        providerCatalogCache.clear()
        StremioCatalogRepository.clearCache()
        StremioCatalogRepository.resetProviderPaging()
        _homeCatalogs.value = StremioCatalogRepository.buildDefaultHomeCatalogs(allMedia.value)
        refreshHomeCatalogs(allMedia.value)
      }
    }
  }

  fun refreshCatalog() {
    viewModelScope.launch {
      _isRefreshing.value = true
      try {
        MediaRepository.refreshTmdbData()
      } finally {
        _isRefreshing.value = false
      }
    }
  }

  fun clearCacheAndRefresh() {
    viewModelScope.launch {
      _isRefreshing.value = true
      try {
        MediaRepository.clearCache()
        MediaRepository.refreshTmdbData()
      } finally {
        _isRefreshing.value = false
      }
    }
  }

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _searchFilter = MutableStateFlow(SearchTypeFilter.ALL)
  val searchFilter: StateFlow<SearchTypeFilter> = _searchFilter.asStateFlow()

  fun setSearchFilter(filter: SearchTypeFilter) {
    _searchFilter.value = filter
  }

  // Impostazioni RIPRODUZIONE persistenti (DataStore Preferences).
  val playbackSettings: StateFlow<PlaybackSettings> = SettingsRepository.settings

  fun setPreferredResolution(resolution: PreferredResolution) =
    SettingsRepository.setPreferredResolution(resolution)

  fun setAutoPlayNextEpisode(enabled: Boolean) =
    SettingsRepository.setAutoPlayNextEpisode(enabled)

  fun setAutoResume(enabled: Boolean) =
    SettingsRepository.setAutoResume(enabled)

  fun setReuseLastWorkingStreamEnabled(enabled: Boolean) =
    SettingsRepository.setReuseLastWorkingStreamEnabled(enabled)

  fun setLastWorkingStreamCacheDurationHours(hours: Int) =
    SettingsRepository.setLastWorkingStreamCacheDurationHours(hours)

  fun setPrioritizeItalianAudio(enabled: Boolean) =
    SettingsRepository.setPrioritizeItalianAudio(enabled)

  fun setPreferredAudioLanguage(language: String) =
    SettingsRepository.setPreferredAudioLanguage(language)

  fun setSubtitlesEnabled(enabled: Boolean) =
    SettingsRepository.setSubtitlesEnabled(enabled)

  fun setForcedSubtitlesEnabled(enabled: Boolean) =
    SettingsRepository.setForcedSubtitlesEnabled(enabled)

  fun setPreferredSubtitleLanguage(language: String) =
    SettingsRepository.setPreferredSubtitleLanguage(language)

  fun setSubtitleSize(size: SubtitleSize) =
    SettingsRepository.setSubtitleSize(size)

  fun setSubtitleBackground(background: SubtitleBackground) =
    SettingsRepository.setSubtitleBackground(background)

  fun setSubtitlePosition(position: SubtitlePosition) =
    SettingsRepository.setSubtitlePosition(position)

  // ── IMPOSTAZIONI AVANZATE RIPRODUZIONE ─────────────────────────────

  fun setTargetBuffer(option: TargetBufferOption) =
    SettingsRepository.setTargetBuffer(option)

  fun setInitialBuffer(option: InitialBufferOption) =
    SettingsRepository.setInitialBuffer(option)

  fun setBackBuffer(option: BackBufferOption) =
    SettingsRepository.setBackBuffer(option)

  fun setAudioPassthroughEnabled(enabled: Boolean) =
    SettingsRepository.setAudioPassthroughEnabled(enabled)

  fun setAudioTunnelingEnabled(enabled: Boolean) =
    SettingsRepository.setAudioTunnelingEnabled(enabled)

  fun setDecoderFallbackMode(mode: DecoderFallbackMode) =
    SettingsRepository.setDecoderFallbackMode(mode)

  fun setAutoFrameRateMatching(enabled: Boolean) =
    SettingsRepository.setAutoFrameRateMatching(enabled)

  fun setDolbyVisionFallbackEnabled(enabled: Boolean) =
    SettingsRepository.setDolbyVisionFallbackEnabled(enabled)

  fun setDebugOverlayEnabled(enabled: Boolean) =
    SettingsRepository.setDebugOverlayEnabled(enabled)

  // ── CONTROLLO AGGIORNAMENTI (GitHub Releases) ─────────────────────

  val updateCheckState: StateFlow<UpdateCheckState> = UpdateRepository.state

  val updateDownloadState: StateFlow<UpdateDownloadState> = UpdateRepository.downloadState

  /**
   * Aggiornamento rilevato dal controllo AUTOMATICO di avvio: non null solo quando
   * c'è qualcosa da proporre e l'utente non ha ancora risposto in questa sessione.
   * Il popup vive a livello di app (MainActivity), non dentro una schermata.
   */
  val startupUpdatePrompt: StateFlow<UpdateCheckState.UpdateAvailable?> =
    UpdateRepository.startupPrompt

  fun checkForAppUpdates() {
    UpdateRepository.checkForUpdates(BuildConfig.VERSION_NAME)
  }

  /** L'utente ha scelto «Più tardi»: il popup non torna in questa sessione. */
  fun dismissStartupUpdatePrompt() {
    UpdateRepository.dismissStartupPrompt()
  }

  fun downloadUpdate(context: Context) {
    UpdateRepository.downloadUpdate(context)
  }

  fun dismissUpdateCheckResult() {
    UpdateRepository.reset()
  }

  @OptIn(coil.annotation.ExperimentalCoilApi::class)
  fun clearAppCache(context: Context, onComplete: (Boolean) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // 1. Svuota la cache immagini Coil (memoria e disco)
        coil.Coil.imageLoader(context).apply {
          memoryCache?.clear()
          diskCache?.clear()
        }
        // 2. Svuota il database locale TMDB preservando preferiti e progressi
        val db = StreamNovaDatabase.getInstance(context)
        db?.tmdbResponseCacheDao()?.clearAll()
        db?.cachedMediaDao()?.clearNonFavorites()
        db?.lastWorkingStreamDao()?.clearAll()

        // 3. Svuota la directory cache applicativa
        context.cacheDir?.deleteRecursively()
        context.externalCacheDir?.deleteRecursively()

        withContext(Dispatchers.Main) {
          onComplete(true)
        }
      } catch (e: Exception) {
        Log.e("StreamNovaViewModel", "Clear cache error: ${e.message}", e)
        withContext(Dispatchers.Main) {
          onComplete(false)
        }
      }
    }
  }

  // ── DEBRID / TORBOX ───────────────────────────────────────────────

  /** Chiave API TorBox salvata nelle Impostazioni (null = non configurata). */
  val torBoxApiKey: StateFlow<String?> = AppSettingsRepository.torBoxApiKey

  /** Toggle "Usa TorBox Instant Debrid". */
  val torBoxInstantDebridEnabled: StateFlow<Boolean> =
    AppSettingsRepository.torBoxInstantDebridEnabled

  /** Esito dell'ultima verifica dell'account (`GET /user/me`). */
  private val _torBoxAccountState = MutableStateFlow<TorBoxAccountState>(TorBoxAccountState.Unknown)
  val torBoxAccountState: StateFlow<TorBoxAccountState> = _torBoxAccountState.asStateFlow()

  /** Salva (o rimuove) la chiave API TorBox e invalida lo stato di verifica. */
  fun setTorBoxApiKey(key: String?) {
    AppSettingsRepository.setTorBoxApiKey(key)
    _torBoxAccountState.value = TorBoxAccountState.Unknown
  }

  fun setTorBoxInstantDebridEnabled(enabled: Boolean) =
    AppSettingsRepository.setTorBoxInstantDebridEnabled(enabled)

  fun setStreamingEngineMode(mode: StreamingEngineMode) {
    val changed = AppSettingsRepository.streamingEngineMode.value != mode
    AppSettingsRepository.setStreamingEngineMode(mode)
    _streamingEngineMode.value = mode
    if (changed) {
      // Cambio modalità HTTP <-> Debrid: ogni stream risolto in memoria appartiene alla
      // modalità precedente e non è più valido. Va invalidato subito, così la prossima
      // apertura (es. da "Continua a guardare") risolve da zero con i provider corretti.
      invalidateInMemoryStreamCaches()
      // La separazione delle sorgenti per tipo (Home/Ricerca) è calcolata in base alla
      // modalità: va ricalcolata, altrimenti resterebbe quella della modalità precedente.
      refreshAddonSourceAvailability()
    }
  }

  /**
   * Invalida le cache **in memoria** degli stream risolti (sorgenti disponibili, dialog di
   * selezione, stato/risultato di risoluzione, flusso corrente nel player). La cache
   * persistente per titolo ("Riusa l'ultimo link") non viene toccata: la sua modalità è
   * tracciata e viene scartata al momento del riuso in caso di mismatch.
   */
  private fun invalidateInMemoryStreamCaches() {
    Log.i(TAG, "Cambio modalità streaming: invalidazione cache stream in memoria")
    resetStreamState()
    _playbackState.value = _playbackState.value.copy(
      streamUrl = null,
      streamHeaders = emptyMap(),
      streamQuality = null,
      streamServer = null,
      streamProgressive = null,
      isReusedStream = false,
      currentSource = null
    )
  }

  val autoplayEnabled: StateFlow<Boolean> = AppSettingsRepository.autoplayEnabled

  fun setAutoplayEnabled(enabled: Boolean) {
    AppSettingsRepository.setAutoplayEnabled(enabled)
  }

  val appLanguage: StateFlow<String> = AppSettingsRepository.appLanguage

  fun setAppLanguage(language: String) {
    if (AppSettingsRepository.appLanguage.value != language) {
      AppSettingsRepository.setAppLanguage(language)
      clearCacheAndRefresh()
    }
  }

  /**
   * Switch "Usa TMDB come fallback per i generi" (sezione Ricerca), persistito in
   * [AppSettingsRepository].
   *
   * Default ON: se nessun catalogo configurato copre la combinazione tipo+genere, la
   * categoria mostra i titoli TMDB invece di restare vuota. Con lo switch OFF le
   * categorie restano quelle degli addon (stato vuoto dichiarato) e TMDB non viene
   * interrogato per il fallback: le altre funzionalità TMDB non cambiano.
   */
  val tmdbGenreFallbackEnabled: StateFlow<Boolean> = AppSettingsRepository.tmdbGenreFallbackEnabled

  /**
   * Aggiorna lo switch e ricalcola SUBITO la categoria aperta: l'utente vede l'effetto
   * senza riavviare l'app e senza perdere la categoria che sta guardando.
   */
  fun setTmdbGenreFallbackEnabled(enabled: Boolean) {
    AppSettingsRepository.setTmdbGenreFallbackEnabled(enabled)
    val categoryId = activeSearchCategoryId ?: return
    reloadActiveCategory(categoryId)
  }

  val parentalControlEnabled: StateFlow<Boolean> = AppSettingsRepository.parentalControlEnabled
  val parentalControlLevel: StateFlow<String> = AppSettingsRepository.parentalControlLevel

  fun hasParentalControlPin(): Boolean = AppSettingsRepository.hasParentalControlPin()

  fun setParentalControlEnabled(enabled: Boolean) {
    AppSettingsRepository.setParentalControlEnabled(enabled)
  }

  fun setParentalControlLevel(level: String) {
    AppSettingsRepository.setParentalControlLevel(level)
  }

  fun setParentalControlPin(pin: String) {
    AppSettingsRepository.setParentalControlPin(pin)
  }

  fun verifyParentalControlPin(pin: String): Boolean {
    return AppSettingsRepository.verifyParentalControlPin(pin)
  }

  data class PendingPlayback(
    val media: MediaItem,
    val episode: Episode? = null,
    val source: StreamSource? = null
  )

  private val _showParentalPinDialog = MutableStateFlow(false)
  val showParentalPinDialog: StateFlow<Boolean> = _showParentalPinDialog.asStateFlow()

  private val _parentalPinErrorResId = MutableStateFlow<Int?>(null)
  val parentalPinErrorResId: StateFlow<Int?> = _parentalPinErrorResId.asStateFlow()

  private var pendingPlayback: PendingPlayback? = null
  private var currentlyUnlockedMediaId: String? = null

  fun shouldBlockForParentalControl(media: MediaItem): Boolean {
    if (!parentalControlEnabled.value) return false
    if (!hasParentalControlPin()) return false
    if (currentlyUnlockedMediaId == media.id) return false
    val rating = getEffectiveAgeRating(media) ?: return false
    val threshold = parentalControlLevel.value.toIntOrNull() ?: 18
    return rating >= threshold
  }

  fun getEffectiveAgeRating(media: MediaItem): Int? {
    return media.ageRating
      ?: _selectedMedia.value?.takeIf { it.id == media.id || (it.tmdbId != null && it.tmdbId == media.tmdbId && it.type == media.type) }?.ageRating
      ?: MediaRepository.mediaList.value.find { it.id == media.id || (it.tmdbId != null && it.tmdbId == media.tmdbId && it.type == media.type) }?.ageRating
  }

  fun verifyParentalPinForPlayback(pin: String): Boolean {
    val isValid = AppSettingsRepository.verifyParentalControlPin(pin)
    if (isValid) {
      _showParentalPinDialog.value = false
      _parentalPinErrorResId.value = null
      val pending = pendingPlayback
      pendingPlayback = null
      if (pending != null) {
        currentlyUnlockedMediaId = pending.media.id
        executeOpenPlayer(pending.media, pending.episode, pending.source)
      }
      return true
    } else {
      _parentalPinErrorResId.value = R.string.parental_playback_pin_incorrect
      return false
    }
  }

  fun dismissParentalPinDialog() {
    _showParentalPinDialog.value = false
    _parentalPinErrorResId.value = null
    pendingPlayback = null
    resetStreamState()
  }

  /** Interroga `GET https://api.torbox.app/v1/api/user/me` per verificare la chiave. */
  fun verifyTorBoxAccount() {
    if (_torBoxAccountState.value is TorBoxAccountState.Checking) return
    viewModelScope.launch {
      _torBoxAccountState.value = TorBoxAccountState.Checking
      _torBoxAccountState.value = when (val result = TorBoxRepository.verifyAccount()) {
        is TorBoxRepository.AccountCheck.Valid -> TorBoxAccountState.Connected(
          email = result.user.email,
          plan = result.user.plan
        )
        is TorBoxRepository.AccountCheck.Invalid -> TorBoxAccountState.Error(result.message)
        TorBoxRepository.AccountCheck.NotConfigured -> TorBoxAccountState.NotConfigured
      }
    }
  }

  // ── OPENSUBTITLES (configurazione) ────────────────────────────────

  /** Chiave API OpenSubtitles salvata nelle Impostazioni (null = non configurata). */
  val openSubtitlesApiKey: StateFlow<String?> = AppSettingsRepository.openSubtitlesApiKey

  /** Esito dell'ultima verifica della connessione OpenSubtitles. */
  private val _openSubtitlesConnectionState =
    MutableStateFlow<OpenSubtitlesConnectionState>(OpenSubtitlesConnectionState.Unknown)
  val openSubtitlesConnectionState: StateFlow<OpenSubtitlesConnectionState> =
    _openSubtitlesConnectionState.asStateFlow()

  /**
   * Salva (o rimuove) la chiave API OpenSubtitles e invalida lo stato di verifica.
   * Il client già usato da ricerca/download rilegge il valore a ogni richiesta
   * (interceptor): la nuova chiave è attiva immediatamente, senza ricreare il client.
   */
  fun setOpenSubtitlesApiKey(key: String?) {
    AppSettingsRepository.setOpenSubtitlesApiKey(key)
    _openSubtitlesConnectionState.value = OpenSubtitlesConnectionState.Unknown
  }

  /** Interroga `GET /api/v1/subtitles` (endpoint ufficiale già in uso) per verificare la chiave. */
  fun verifyOpenSubtitlesConnection() {
    if (_openSubtitlesConnectionState.value is OpenSubtitlesConnectionState.Checking) return
    if (AppSettingsRepository.openSubtitlesApiKey.value.isNullOrBlank()) {
      _openSubtitlesConnectionState.value = OpenSubtitlesConnectionState.NotConfigured
      return
    }
    viewModelScope.launch {
      _openSubtitlesConnectionState.value = OpenSubtitlesConnectionState.Checking
      val result = openSubtitlesVerifier.check(AppSettingsRepository.openSubtitlesApiKey.value)
      _openSubtitlesConnectionState.value = when (result) {
        is OpenSubtitlesConnectionCheck.Valid -> OpenSubtitlesConnectionState.Connected
        is OpenSubtitlesConnectionCheck.NotConfigured -> OpenSubtitlesConnectionState.NotConfigured
        is OpenSubtitlesConnectionCheck.Failed -> OpenSubtitlesConnectionState.Error(result.error)
      }
    }
  }

  // ── ADDON STREMIO ─────────────────────────────────────────────────

  /** Lista degli addon Stremio installati (persistita in JSON). */
  val installedAddons: StateFlow<List<InstalledAddon>> = StremioAddonRepository.addons

  private val _addonInstalling = MutableStateFlow(false)
  val addonInstalling: StateFlow<Boolean> = _addonInstalling.asStateFlow()

  private val _addonMessage = MutableStateFlow<String?>(null)
  /** Feedback operazioni install/rimozione mostrato nella schermata Addon. */
  val addonMessage: StateFlow<String?> = _addonMessage.asStateFlow()

  /**
   * Installa un addon dall'URL del manifest (normalizzato, scaricato e validato).
   * Il risultato (ok/errore) viene pubblicato su [addonMessage].
   */
  fun installAddon(rawUrl: String) {
    if (_addonInstalling.value) return
    viewModelScope.launch {
      _addonInstalling.value = true
      _addonMessage.value = null
      try {
        val addon = StremioAddonRepository.installAddon(rawUrl)
        StremioCatalogRepository.clearCache()
        _homeCatalogs.value = StremioCatalogRepository.buildDefaultHomeCatalogs(allMedia.value)
        refreshHomeCatalogs()
        _addonMessage.value = "Addon installato: ${addon.manifest.displayTitle}"
      } catch (e: Exception) {
        _addonMessage.value = "Installazione fallita: ${e.message ?: "errore sconosciuto"}"
      } finally {
        _addonInstalling.value = false
      }
    }
  }

  fun removeAddon(id: String) {
    StremioAddonRepository.removeAddon(id)
    StremioCatalogRepository.clearCache()
    _homeCatalogs.value = StremioCatalogRepository.buildDefaultHomeCatalogs(allMedia.value)
    refreshHomeCatalogs()
    _addonMessage.value = "Addon rimosso"
  }

  fun setAddonEnabled(id: String, enabled: Boolean) {
    StremioAddonRepository.setAddonEnabled(id, enabled)
    StremioCatalogRepository.clearCache()
    _homeCatalogs.value = StremioCatalogRepository.buildDefaultHomeCatalogs(allMedia.value)
    refreshHomeCatalogs()
  }

  fun clearAddonMessage() {
    _addonMessage.value = null
  }

  @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
  val filteredSearchResults: StateFlow<List<MediaItem>> = combine(_searchQuery, _searchFilter) { query, filter ->
    query to filter
  }
    .debounce { (query, _) -> if (query.isBlank()) 0L else 400L }
    .distinctUntilChanged()
    .flatMapLatest { (query, filter) ->
      combine(
        MediaRepository.searchTmdb(query, filter),
        stremioSearchFlow(query, filter)
      ) { tmdbResults, stremioResults ->
        // Xperience/Stremio disponibile → risultati Stremio in testa; senza risultati
        // Stremio la lista TMDB resta esattamente quella precedente.
        if (stremioResults.isEmpty()) {
          tmdbResults
        } else {
          StremioCatalogRepository.deduplicateCrossSource(stremioResults + tmdbResults)
        }
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /**
   * Ricerca testuale sui cataloghi Stremio/Xperience attivi tramite l'extra `search`.
   *
   * Usa esclusivamente il percorso già adottato dalla Home:
   * `StremioCatalogRepository.searchAddonCatalogs(query, type)` →
   * `StremioCatalogEngine.searchCatalogs` (solo addon attivi, solo cataloghi che
   * dichiarano `search` nel manifest, con rispetto di type e catalogId).
   *
   * Emette subito una lista vuota così i risultati TMDB non vengono ritardati;
   * se l'interrogazione Stremio fallisce o supera il timeout, la ricerca TMDB
   * prosegue invariata.
   */
  private fun stremioSearchFlow(query: String, filter: SearchTypeFilter): Flow<List<MediaItem>> = flow {
    val cleanQuery = query.trim()
    val mediaType = when (filter) {
      SearchTypeFilter.FILM -> MediaType.FILM
      SearchTypeFilter.SERIE_TV -> MediaType.SERIE_TV
      SearchTypeFilter.ALL -> null
    }
    // Stessa soglia minima della ricerca TMDB remota.
    if (cleanQuery.length < 2) {
      emit(emptyList())
      return@flow
    }
    emit(emptyList())
    val results = try {
      withTimeoutOrNull(STREMIO_SEARCH_TIMEOUT_MS) {
        StremioCatalogRepository.searchAddonCatalogs(cleanQuery, mediaType)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Ricerca cataloghi Stremio fallita per \"$cleanQuery\": ${e.message}")
      null
    }
    if (!results.isNullOrEmpty()) {
      emit(results)
    }
  }

  // ── CATEGORIE SEZIONE RICERCA ─────────────────────────────────────
  private val _categoryItems = MutableStateFlow<List<MediaItem>>(emptyList())
  val categoryItems: StateFlow<List<MediaItem>> = _categoryItems.asStateFlow()

  private val _isCategoryLoading = MutableStateFlow(false)
  val isCategoryLoading: StateFlow<Boolean> = _isCategoryLoading.asStateFlow()

  private val _categoryState = MutableStateFlow(SearchCategoryState())
  /** Origine e stato del caricamento della categoria attiva (vedi [SearchCategoryState]). */
  val categoryState: StateFlow<SearchCategoryState> = _categoryState.asStateFlow()

  /** ID della categoria attualmente caricata in memoria (null se nessuna). */
  var activeSearchCategoryId: String? = null
    private set

  /** Keyword (etichette categoria + nomi canonici TMDB) della categoria attiva. */
  private var activeCategoryKeywords: List<String> = emptyList()
  /** Etichetta della categoria attiva (prima keyword): usata nei messaggi localizzati. */
  private var activeCategoryLabel: String = ""
  private var activeCategoryMovieGenreId: Int? = null
  private var activeCategoryTvGenreId: Int? = null

  /**
   * Esito dell'ultimo caricamento per tipo della categoria attiva. Vuoto finché il
   * caricamento non è terminato.
   */
  private val _lastGenreLoads = MutableStateFlow<List<GenreLoadRecord>>(emptyList())
  val lastGenreLoads: StateFlow<List<GenreLoadRecord>> = _lastGenreLoads.asStateFlow()

  /** true se la categoria attiva ha una sorgente TMDB usata come fallback. */
  val isCategoryTmdbFallback: Boolean
    get() = _categoryState.value.source == SearchCategorySource.TMDB_FALLBACK
  /** Sezioni addon caricate per la categoria attiva (per la paginazione `skip`). */
  private var activeCategoryEntries: List<StremioCatalogRepository.CategoryCatalogEntry> = emptyList()
  /** Contatore generazione: scarta le risposte riferite a una categoria già chiusa. */
  private var categoryLoadGeneration = 0
  private var currentCategoryPage = 1
  private var isCategoryLoadingMore = false

  /**
   * Apre una categoria della sezione Ricerca.
   *
   * I cataloghi degli addon (Xperience/Stremio) NON vengono indicizzati per id
   * codificato in app: [keywords] (etichette della categoria più nomi canonici
   * TMDB del genere) vengono confrontate con id, titoli ed extra `genre`
   * REALMENTE dichiarati dai manifest installati, tramite
   * [StremioCatalogRepository.findCategoryRequests] →
   * [StremioCatalogRepository.loadCategoryCatalogs] (stesso percorso della Home).
   *
   * Prima di ogni nuovo caricamento i risultati della categoria precedente vengono
   * azzerati e non esiste alcun seed da `allMedia`: la categoria mostra solo ciò
   * che l'addon ha restituito oppure un fallback TMDB EXPLICITAMENTE dichiarato in
   * [SearchCategoryState].
   *
   * @param categoryId id della categoria (per diagnostica e test).
   * @param keywords etichette/descrizioni della categoria dalla quale cercare i cataloghi.
   * @param movieGenreId id genere TMDB film (fallback TMDB Discover esplicito).
   * @param tvGenreId id genere TMDB serie (fallback TMDB Discover esplicito).
   */
  fun initCategory(
    categoryId: String,
    keywords: List<String>,
    movieGenreId: Int?,
    tvGenreId: Int?
  ) {
    activeSearchCategoryId = categoryId
    activeCategoryLabel = keywords.firstOrNull().orEmpty()
    activeCategoryKeywords = keywords +
      listOfNotNull(movieGenreId?.let { TMDB_MOVIE_GENRE_NAMES[it] }) +
      listOfNotNull(tvGenreId?.let { TMDB_TV_GENRE_NAMES[it] })
    activeCategoryMovieGenreId = movieGenreId
    activeCategoryTvGenreId = tvGenreId
    activeCategoryEntries = emptyList()
    currentCategoryPage = 1
    // Generazione nuova: le risposte della categoria precedente vengono ignorate.
    categoryLoadGeneration++
    // I poster della categoria precedente spariscono subito: durante il caricamento
    // la griglia resta vuota con lo spinner attivo.
    _categoryItems.value = emptyList()
    _categoryState.value = SearchCategoryState()
    _isCategoryLoading.value = true
    loadInitialCategoryPage(categoryLoadGeneration)
  }

  /**
   * Ricalcola la categoria attiva mantenendo id e genere già noti (es. dopo il cambio
   * dello switch di fallback TMDB). Usa lo stesso contatore di generazione delle
   * risposte di [initCategory]: un ricalcolo non può sovrascrivere un caricamento più
   * recente, quindi non esistono risultati obsoleti che scavalcano quello corrente.
   */
  internal fun reloadActiveCategory(categoryId: String) {
    activeSearchCategoryId = categoryId
    activeCategoryEntries = emptyList()
    currentCategoryPage = 1
    categoryLoadGeneration++
    _categoryItems.value = emptyList()
    _categoryState.value = SearchCategoryState()
    _isCategoryLoading.value = true
    loadInitialCategoryPage(categoryLoadGeneration)
  }

  /**
   * Prima pagina della categoria.
   *
   * Regole per OGNI tipo previsto dalla categoria (film e/o serie):
   *  1. un catalogo dell'utente applica il filtro per genere E restituisce risultati →
   *     fonte catalogo, TMDB non viene interrogato;
   *  2. un catalogo compatibile è interrogato con successo ma risponde HTTP 200 vuoto,
   *     oppure nessun catalogo dell'utente copre la combinazione tipo+genere → fallback
   *     TMDB Discover, solo con lo switch "Usa TMDB come fallback per i generi" attivo;
   *  3. switch disattivato → nessuna chiamata TMDB, stato vuoto dichiarato;
   *  4. tutti i cataloghi compatibili del tipo sono falliti (rete/HTTP) → errore
   *     dichiarato: il fallback TMDB non deve mascherare un malfunzionamento.
   *
   * Il fallback riguarda la SINGOLA combinazione: Serie TV → Horror con Film → Horror
   * coperto mostra film dai cataloghi e serie da TMDB nella stessa categoria.
   */
  private fun loadInitialCategoryPage(generation: Int) {
    viewModelScope.launch {
      try {
        val addonLoad = try {
          StremioCatalogRepository.loadCategoryCatalogs(activeCategoryKeywords)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Caricamento cataloghi categoria fallito: ${e.message}")
          null
        }
        if (generation != categoryLoadGeneration) return@launch

        val entries = addonLoad?.entries.orEmpty()

        val movieOutcome = activeCategoryMovieGenreId?.let { genreId ->
          resolveGenreOutcome(
            mediaType = MediaType.FILM,
            genreId = genreId,
            addonEntries = entries.filter { it.section.mediaType == MediaType.FILM },
            addonItems = addonLoad?.items?.filter { it.type == MediaType.FILM }.orEmpty(),
            addonMatched = addonLoad?.matchedMovieTargets ?: 0,
            addonFailed = addonLoad?.failedMovieTargets ?: 0,
            addonErrors = addonLoad?.errors.orEmpty()
          )
        }
        if (generation != categoryLoadGeneration) return@launch

        val seriesOutcome = activeCategoryTvGenreId?.let { genreId ->
          resolveGenreOutcome(
            mediaType = MediaType.SERIE_TV,
            genreId = genreId,
            addonEntries = entries.filter { it.section.mediaType == MediaType.SERIE_TV },
            addonItems = addonLoad?.items?.filter { it.type == MediaType.SERIE_TV }.orEmpty(),
            addonMatched = addonLoad?.matchedSeriesTargets ?: 0,
            addonFailed = addonLoad?.failedSeriesTargets ?: 0,
            addonErrors = addonLoad?.errors.orEmpty()
          )
        }
        if (generation != categoryLoadGeneration) return@launch

        val outcomes = listOfNotNull(movieOutcome, seriesOutcome)
        _lastGenreLoads.value = outcomes.map { it.record }

        val addonItems = outcomes.flatMap { it.addonItems }
        val tmdbItems = outcomes.flatMap { it.tmdbItems }
        val usedTmdb = outcomes.any { it.usedTmdb }
        // Se un tipo della categoria non ha NESSUNA fonte (catalogo assente e TMDB
        // disattivato), la categoria non deve diventare una lista TMDB: si mostrano i
        // contenuti reali degli altri tipi e lo stato dichiara la mancanza.
        val coverageMissing = outcomes.any { !it.hasAnySource } && addonItems.isNotEmpty()
        val routedToTmdb = outcomes.any { it.usedTmdb }

        activeCategoryEntries = addonLoad?.entries.orEmpty()
        _categoryState.value = buildCategoryState(
          addonLoad = addonLoad,
          outcomes = outcomes,
          usedTmdb = usedTmdb,
          routedToTmdb = routedToTmdb,
          coverageMissing = coverageMissing
        )
        // Merge cross-sorgente sulle identità TMDB/IMDb già note: un titolo restituito sia
        // dal catalogo sia da TMDB compare una sola volta.
        _categoryItems.value = StremioCatalogRepository.deduplicateCrossSource(
          if (usedTmdb) addonItems + tmdbItems else addonItems
        )
        currentCategoryPage = 1
      } finally {
        if (generation == categoryLoadGeneration) {
          _isCategoryLoading.value = false
        }
      }
    }
  }

  /**
   * Esito del caricamento per UN tipo di contenuto di una categoria.
   *
   * @param record motivo del routing (per diagnostica e test).
   * @param addonItems contenuti reali del tipo restituiti dai cataloghi configurati.
   * @param tmdbItems contenuti TMDB del tipo (vuoto se TMDB non è stato usato).
   * @param usedTmdb true se TMDB è stato interrogato ed è stato caricato.
   */
  private data class GenreOutcome(
    val record: GenreLoadRecord,
    val addonItems: List<MediaItem> = emptyList(),
    val tmdbItems: List<MediaItem> = emptyList(),
    val usedTmdb: Boolean = false
  ) {
    /** true se il tipo ha una fonte (un catalogo interrogato o TMDB). */
    val hasAnySource: Boolean
      get() = usedTmdb ||
        record.reason == GenreLoadReason.SUPPORTED_BY_CATALOG ||
        record.reason == GenreLoadReason.SUPPORTED_BUT_EMPTY ||
        record.reason == GenreLoadReason.FAILED
  }

  /**
   * Decide la fonte per UNA combinazione tipo+genere. Regole, in ordine:
   *
   *  1. almeno un catalogo configurato del tipo applica il filtro per genere e ha
   *     restituito risultati → fonte addon, TMDB non viene interrogato ("non attivare
   *     inutilmente TMDB");
   *  2. tutti i cataloghi compatibili del tipo sono falliti (rete/HTTP/JSON) → errore
   *     dichiarato, TMDB non maschera un malfunzionamento;
   *  3. un catalogo compatibile è stato interrogato con successo ma ha risposto HTTP 200
   *     vuoto (catalogo vuoto: non è incompatibilità e non è un errore) OPPURE nessun
   *     catalogo configurato copre la combinazione → fallback TMDB con l'endpoint del
   *     tipo (`discover/movie` o `discover/tv`) e il suo id di genere;
   *  4. switch disattivato → nessuna chiamata TMDB, stato vuoto dichiarato.
   *
   * Il fallback è deciso sulla COMBINAZIONE tipo+genere e sull'insieme dei cataloghi
   * realmente configurati (addon attivi + sistema): se un altro addon copre il genere E
   * produce risultati, la ricerca resta dell'addon e TMDB non viene chiamato.
   */
  private suspend fun resolveGenreOutcome(
    mediaType: MediaType,
    genreId: Int,
    addonEntries: List<StremioCatalogRepository.CategoryCatalogEntry>,
    addonItems: List<MediaItem>,
    addonMatched: Int,
    addonFailed: Int,
    addonErrors: List<String>
  ): GenreOutcome {
    val catalogCovers = StremioCatalogRepository.hasUserCatalogCoveringGenre(
      keywords = activeCategoryKeywords,
      mediaType = mediaType
    )
    val anyLoaded = addonEntries.any { it.section.items.isNotEmpty() }
    val allFailed = addonMatched > 0 && addonFailed >= addonMatched

    // 1. Risultati validi dagli addon: la categoria resta dell'addon, TMDB non serve.
    if (anyLoaded) {
      return GenreOutcome(
        record = GenreLoadRecord(mediaType, GenreLoadReason.SUPPORTED_BY_CATALOG),
        addonItems = addonItems
      )
    }

    // 2. Errore di rete/servizio su TUTTI i cataloghi compatibili: dichiarato, non mascherato.
    if (allFailed) {
      Log.w(TAG, "Catalogo $mediaType fallito: nessun fallback TMDB ($addonErrors)")
      return GenreOutcome(record = GenreLoadRecord(mediaType, GenreLoadReason.FAILED), addonItems = addonItems)
    }

    // 3. Nessun risultato utile dagli addon: catalogo compatibile vuoto (HTTP 200 senza
    //    metas) oppure nessun catalogo compatibile per la combinazione → fallback TMDB.
    val catalogWasEmpty = catalogCovers || addonMatched > 0

    // 4. Switch disattivato: nessuna chiamata TMDB, il caso resta dichiarato.
    if (!AppSettingsRepository.tmdbGenreFallbackEnabled.value) {
      val reason = if (catalogWasEmpty) GenreLoadReason.SUPPORTED_BUT_EMPTY else GenreLoadReason.TMDB_DISABLED
      return GenreOutcome(record = GenreLoadRecord(mediaType, reason), addonItems = addonItems)
    }

    val tmdbItems = MediaRepository.discoverByGenre(mediaType, genreId, page = 1)
    if (tmdbItems == null) {
      return GenreOutcome(
        record = GenreLoadRecord(
          mediaType = mediaType,
          reason = GenreLoadReason.TMDB_UNAVAILABLE,
          catalogWasEmpty = catalogWasEmpty
        ),
        addonItems = addonItems
      )
    }
    return GenreOutcome(
      record = GenreLoadRecord(
        mediaType = mediaType,
        reason = GenreLoadReason.TMDB_FALLBACK,
        tmdbPages = 1,
        tmdbItems = tmdbItems.size,
        catalogWasEmpty = catalogWasEmpty
      ),
      addonItems = addonItems,
      tmdbItems = tmdbItems,
      usedTmdb = true
    )
  }

  /**
   * Stato della categoria: origine REALE dei contenuti, avvisi e uso di TMDB.
   *
   * I messaggi sono risorse localizzate ([R.string]) risolte con [appContext]. Ogni
   * messaggio descrive il SINGOLO tipo (film / serie TV): nessuno attribuisce a un addon
   * specifico un limite globale, perché se un altro addon copre il genere la categoria
   * resta dell'addon e nessun titolo TMDB viene aggiunto.
   */
  private fun buildCategoryState(
    addonLoad: StremioCatalogRepository.CategoryCatalogLoad?,
    outcomes: List<GenreOutcome>,
    usedTmdb: Boolean,
    routedToTmdb: Boolean,
    coverageMissing: Boolean
  ): SearchCategoryState {
    val addonLabel = addonLoad?.entries?.firstOrNull { it.section.items.isNotEmpty() }?.section?.title
    val tmdbSkipped = outcomes.any { it.record.reason == GenreLoadReason.TMDB_DISABLED }
    val notices = outcomes.mapNotNull { noticeForOutcome(it) }
    val onlySkipped = tmdbSkipped && outcomes.all { !it.hasAnySource }
    return SearchCategoryState(
      source = if (usedTmdb || onlySkipped) SearchCategorySource.TMDB_FALLBACK else SearchCategorySource.ADDON,
      addonLabel = addonLabel,
      statusMessage = notices.joinToString("\n").takeIf { it.isNotBlank() },
      genresCatalogsCopied = routedToTmdb || coverageMissing,
      genresTmdbSkipped = tmdbSkipped
    )
  }

  /** Messaggio localizzato per l'esito di un tipo, oppure `null` se non serve. */
  private fun noticeForOutcome(outcome: GenreOutcome): String? {
    val type = typeLabel(outcome.record.mediaType)
    return when (outcome.record.reason) {
      GenreLoadReason.SUPPORTED_BY_CATALOG -> null
      GenreLoadReason.SUPPORTED_BUT_EMPTY ->
        localized(R.string.search_category_empty_type, type)
      GenreLoadReason.FAILED ->
        localized(R.string.search_category_failed_type, type)
      GenreLoadReason.TMDB_FALLBACK ->
        if (outcome.record.tmdbItems == 0) {
          localized(R.string.search_category_tmdb_no_results, type)
        } else if (outcome.record.catalogWasEmpty) {
          localized(
            if (outcome.record.mediaType == MediaType.SERIE_TV) {
              R.string.search_category_tmdb_after_empty_series
            } else {
              R.string.search_category_tmdb_after_empty_movies
            }
          )
        } else {
          localized(R.string.search_category_tmdb_fallback_reason)
        }
      GenreLoadReason.TMDB_DISABLED ->
        localized(
          R.string.search_category_tmdb_disabled,
          activeCategoryLabel.takeIf { it.isNotBlank() } ?: type
        )
      // Catalogo interrogato e vuoto e TMDB non raggiungibile: entrambi i fatti dichiarati.
      GenreLoadReason.TMDB_UNAVAILABLE ->
        if (outcome.record.catalogWasEmpty) {
          listOf(
            localized(R.string.search_category_empty_type, type),
            localized(R.string.search_category_tmdb_unavailable)
          ).joinToString("\n")
        } else {
          localized(R.string.search_category_tmdb_unavailable)
        }
    }
  }

  /** Etichetta localizzata del tipo ("Film" / "Serie TV"), usata nei messaggi. */
  private fun typeLabel(mediaType: MediaType): String = localized(
    when (mediaType) {
      MediaType.SERIE_TV -> R.string.search_category_type_series
      else -> R.string.search_category_type_movies
    }
  )

  /** Risolve una risorsa stringa (nessuna eccezione se il contesto non è disponibile). */
  private fun localized(resId: Int, vararg args: Any): String {
    // Contesto: quello collegato dalla UI; se manca (es. il ViewModel è usato senza
    // schermata) si usa il contesto dell'applicazione, sempre disponibile nel processo.
    val context = appContext ?: currentApplicationContext() ?: return ""
    return runCatching { context.getString(resId, *args) }.getOrDefault("")
  }

  /**
   * Contesto applicativo del processo, ricavato dalla riflessione su `ActivityThread`:
   * evita di dipendere dall'ordine in cui la UI collega il proprio contesto.
   */
  private fun currentApplicationContext(): Context? = runCatching {
    val activityThread = Class.forName("android.app.ActivityThread")
    val app = activityThread.getMethod("currentApplication").invoke(null) as? Context
    app?.applicationContext
  }.getOrNull()

  /**
   * Paginazione della categoria attiva.
   *
   * I risultati possono avere DUE sorgenti, anche insieme (film dagli addon e serie da
   * TMDB, o viceversa):
   *  - la pagina successiva dei cataloghi addon usa `skip`, con gli stessi extra;
   *  - la pagina successiva TMDB esiste solo per i tipi davvero serviti da TMDB, con
   *    l'endpoint del tipo e il genere della categoria.
   * Con il fallback disattivato non viene mai richiesta una pagina TMDB.
   */
  fun loadNextCategoryPage() {
    if (isCategoryLoadingMore || _isCategoryLoading.value) return
    val addonEntries = activeCategoryEntries.filter { it.section.supportsSkip && it.section.items.isNotEmpty() }
    val tmdbTypes = tmdbFallbackTypes()
    if (addonEntries.isEmpty() && tmdbTypes.isEmpty()) return
    isCategoryLoadingMore = true
    val generation = categoryLoadGeneration
    viewModelScope.launch {
      try {
        if (addonEntries.isNotEmpty()) {
          loadNextCategoryAddonPage(addonEntries, generation)
        }
        if (generation != categoryLoadGeneration) return@launch
        if (tmdbTypes.isNotEmpty()) {
          loadNextCategoryTmdbPage(tmdbTypes, generation)
        }
      } finally {
        isCategoryLoadingMore = false
      }
    }
  }

  /** Tipi serviti da TMDB nell'ultimo caricamento della categoria (vuoto = nessuno). */
  private fun tmdbFallbackTypes(): List<MediaType> {
    if (!AppSettingsRepository.tmdbGenreFallbackEnabled.value) return emptyList()
    return _lastGenreLoads.value
      .filter { it.reason == GenreLoadReason.TMDB_FALLBACK }
      .map { it.mediaType }
  }

  /**
   * Pagina i cataloghi addon della categoria con l'extra `skip` (se dichiarato).
   * Eseguita dentro il job di [loadNextCategoryPage], che gestisce il flag di caricamento.
   */
  private suspend fun loadNextCategoryAddonPage(
    previous: List<StremioCatalogRepository.CategoryCatalogEntry>,
    generation: Int
  ) {
    try {
      val updated = StremioCatalogRepository.paginateCategoryCatalogs(previous)
      if (generation != categoryLoadGeneration) return
      val fresh = mutableListOf<MediaItem>()
      updated.forEachIndexed { index, entry ->
        fresh += entry.section.items.drop(previous[index].section.items.size)
      }
      activeCategoryEntries = updated
      if (fresh.isNotEmpty()) {
        _categoryItems.value =
          StremioCatalogRepository.deduplicateCrossSource(_categoryItems.value + fresh)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Errore paginazione categoria addon: ${e.message}")
    }
  }

  /**
   * Pagina successiva del fallback TMDB, SOLO per i tipi che TMDB ha davvero fornito
   * ([tmdbTypes]). Ogni tipo usa il proprio endpoint e il proprio id di genere; i
   * risultati vengono uniti senza duplicati (identità TMDB/IMDb). La generazione viene
   * ricontrollata dopo la risposta: un cambio di categoria/switch non può essere
   * sovrascritto da una pagina obsoleta.
   */
  private suspend fun loadNextCategoryTmdbPage(tmdbTypes: List<MediaType>, generation: Int) {
    val nextPage = currentCategoryPage + 1
    try {
      val newItems = mutableListOf<MediaItem>()
      if (tmdbTypes.contains(MediaType.FILM)) {
        activeCategoryMovieGenreId?.let { genreId ->
          MediaRepository.discoverByGenre(MediaType.FILM, genreId, nextPage)?.let(newItems::addAll)
        }
      }
      if (tmdbTypes.contains(MediaType.SERIE_TV)) {
        activeCategoryTvGenreId?.let { genreId ->
          MediaRepository.discoverByGenre(MediaType.SERIE_TV, genreId, nextPage)?.let(newItems::addAll)
        }
      }
      // Categoria cambiata o fallback disattivato durante la richiesta: scarta la pagina.
      if (generation != categoryLoadGeneration) return
      if (newItems.isNotEmpty()) {
        currentCategoryPage = nextPage
        _categoryItems.value = StremioCatalogRepository.deduplicateCrossSource(
          _categoryItems.value + newItems
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Errore paginazione categoria pagina $nextPage: ${e.message}")
    }
  }

  private var currentMoviePage = 1
  private var currentTvPage = 1
  private var isLoadingMoreMovies = false
  private var isLoadingMoreTv = false

  fun loadNextMoviesPage() {
    if (isLoadingMoreMovies) return
    isLoadingMoreMovies = true
    viewModelScope.launch {
      try {
        if (_homeCatalogs.value.isMoviesFromStremio) {
          val currentList = _homeCatalogs.value.allMovies
          val more = StremioCatalogRepository.paginateMovies(currentList.size)
          if (more.isNotEmpty()) {
            val combined = StremioCatalogRepository.deduplicateCrossSource(currentList + more)
            _homeCatalogs.update { it.copy(allMovies = combined) }
          }
        } else {
          currentMoviePage++
          MediaRepository.loadMoreMovies(currentMoviePage)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione film: ${e.message}")
      } finally {
        isLoadingMoreMovies = false
      }
    }
  }

  fun loadNextTvPage() {
    if (isLoadingMoreTv) return
    isLoadingMoreTv = true
    viewModelScope.launch {
      try {
        if (_homeCatalogs.value.isSeriesFromStremio) {
          val currentList = _homeCatalogs.value.allSeries
          val more = StremioCatalogRepository.paginateSeries(currentList.size)
          if (more.isNotEmpty()) {
            val combined = StremioCatalogRepository.deduplicateCrossSource(currentList + more)
            _homeCatalogs.update { it.copy(allSeries = combined) }
          }
        } else {
          currentTvPage++
          MediaRepository.loadMoreTv(currentTvPage)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Errore paginazione serie TV: ${e.message}")
      } finally {
        isLoadingMoreTv = false
      }
    }
  }

  fun setSection(section: SidebarSection) {
    _currentSection.value = section
    _screenState.value = ScreenState.BROWSING
  }

  /**
   * Ricalcola da zero i cataloghi guidati dagli addon (Home e Ricerca) dopo un cambio
   * che ne modifica la disponibilità delle fonti (es. modalità HTTP <-> Debrid).
   *
   * La raccolta dei target dipende dalla modalità del motore di streaming: la cache dei
   * cataloghi va invalidata PRIMA che Home/Ricerca chiedano i dati, altrimenti le
   * sezioni resterebbero quelle della modalità precedente.
   */
  private fun refreshAddonSourceAvailability() {
    _stremioProviderMedia.value = emptyMap()
    _stremioProviderStates.value = emptyMap()
    providerCatalogCache.clear()
    StremioCatalogRepository.clearCache()
    StremioCatalogRepository.resetProviderPaging()
    _homeCatalogs.value = StremioCatalogRepository.buildDefaultHomeCatalogs(allMedia.value)
    refreshHomeCatalogs(allMedia.value)
  }

  /**
   * Riporta la navigazione alla Home se la sezione corrente non è più offerta dalla sidebar.
   *
   * L'area "Addon" esiste solo in modalità Debrid TorBox: se la modalità passa a HTTP Web
   * mentre la sezione è Addon (o se quella sezione viene ripristinata al termine di una
   * riproduzione), restare lì mostrerebbe una schermata senza voce di menu corrispondente.
   */
  private fun fallbackFromUnavailableSection() {
    if (_currentSection.value == SidebarSection.ADDON &&
      !_streamingEngineMode.value.supportsAddonsSection
    ) {
      _currentSection.value = SidebarSection.HOME
    }
  }

  fun loadDetails(tmdbId: Int, isTv: Boolean, baseMedia: MediaItem? = null) {
    if (tmdbId <= 0) return
    // Cancella il fetch di dettaglio precedente: una risposta in ritardo per un media
    // aperto prima non deve sovrascrivere i metadati correnti.
    currentMediaFetchJob?.cancel()
    currentMediaFetchJob = viewModelScope.launch {
      MediaRepository.loadMediaDetails(tmdbId, isTv, baseMedia)
    }
  }

  fun selectSeason(seriesTmdbId: Int, seasonNumber: Int, fallbackSeries: MediaItem? = null) {
    if (seriesTmdbId <= 0) {
      _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
      return
    }
    val mediaId = fallbackSeries?.id ?: "tmdb_tv_$seriesTmdbId"
    _selectedSeasonNumber.value = seasonNumber
    _seasonEpisodesUiState.value = SeasonEpisodesUiState.Loading(seasonNumber, mediaId)
    selectSeasonJob?.cancel()
    selectSeasonJob = viewModelScope.launch {
      try {
        val season = MediaRepository.getSeasonDetails(seriesTmdbId, seasonNumber, fallbackSeries)
        _seasonEpisodesUiState.value = SeasonEpisodesUiState.Success(seasonNumber, season, mediaId)
      } catch (e: Exception) {
        _seasonEpisodesUiState.value = SeasonEpisodesUiState.Error(
          seasonNumber,
          e.localizedMessage ?: "Errore nel caricamento degli episodi della stagione $seasonNumber",
          mediaId
        )
      }
    }
  }

  fun loadEpisodesForSeason(mediaId: String, seasonNumber: Int, fallbackSeries: MediaItem? = null) {
    val numericId = mediaId.removePrefix("tmdb_tv_").toIntOrNull()
      ?: fallbackSeries?.tmdbId
    if (numericId != null && numericId > 0) {
      selectSeason(numericId, seasonNumber, fallbackSeries)
    } else if (mediaId.startsWith("tt", ignoreCase = true) || fallbackSeries?.id?.startsWith("tt", ignoreCase = true) == true) {
      val imdb = if (mediaId.startsWith("tt", ignoreCase = true)) mediaId else fallbackSeries?.id ?: ""
      _selectedSeasonNumber.value = seasonNumber
      _seasonEpisodesUiState.value = SeasonEpisodesUiState.Loading(seasonNumber, mediaId)
      selectSeasonJob?.cancel()
      selectSeasonJob = viewModelScope.launch {
        try {
          val resolvedId = MediaRepository.resolveImdbToTmdbId(imdb, isTv = true)
          if (resolvedId != null && resolvedId > 0) {
            val updatedFallback = fallbackSeries?.copy(tmdbId = resolvedId)
            selectSeason(resolvedId, seasonNumber, updatedFallback)
          } else {
            _seasonEpisodesUiState.value = SeasonEpisodesUiState.Error(
              seasonNumber,
              "Identificatore TMDB non trovato per $imdb",
              mediaId
            )
          }
        } catch (e: Exception) {
          _seasonEpisodesUiState.value = SeasonEpisodesUiState.Error(
            seasonNumber,
            e.localizedMessage ?: "Errore nel caricamento degli episodi",
            mediaId
          )
        }
      }
    } else {
      _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
    }
  }

  fun loadEpisodesForSeason(media: MediaItem, seasonNumber: Int) {
    loadEpisodesForSeason(media.id, seasonNumber, media)
  }

  fun openDetail(media: MediaItem) {
    if (_screenState.value != ScreenState.DETAIL) {
      detailMediaStack.clear()
      // Una nuova catena non deve ereditare il target di focus precedente.
      _chainRestoreMediaId.value = null
    }
    if (detailMediaStack.isEmpty() || detailMediaStack.last().id != media.id) {
      detailMediaStack.add(media)
    }
    selectSeasonJob?.cancel()
    selectSeasonJob = null
    _selectedMedia.value = media
    _screenState.value = ScreenState.DETAIL
    val isTv = media.type == MediaType.SERIE_TV

    val currentTmdbId = media.tmdbId
      ?: media.id.removePrefix("tmdb_tv_").removePrefix("tmdb_movie_").toIntOrNull()
    if (currentTmdbId != null && currentTmdbId > 0) {
      val mediaWithTmdb = if (media.tmdbId == null) media.copy(tmdbId = currentTmdbId) else media
      _selectedMedia.value = mediaWithTmdb
      loadDetails(currentTmdbId, isTv, mediaWithTmdb)
      if (isTv) {
        val initialSeason = mediaWithTmdb.lastWatchedSeason ?: 1
        selectSeason(currentTmdbId, initialSeason, mediaWithTmdb)
      } else {
        _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
      }
    } else if (media.id.startsWith("tt", ignoreCase = true)) {
      MediaRepository.setDetailLoading(media)
      if (isTv) {
        val initialSeason = media.lastWatchedSeason ?: 1
        _seasonEpisodesUiState.value = SeasonEpisodesUiState.Loading(initialSeason, media.id)
      }
      viewModelScope.launch {
        val resolvedId = MediaRepository.resolveImdbToTmdbId(media.id, isTv)
        if (resolvedId != null && resolvedId > 0) {
          val mediaWithTmdb = media.copy(tmdbId = resolvedId)
          _selectedMedia.value = mediaWithTmdb
          loadDetails(resolvedId, isTv, mediaWithTmdb)
          if (isTv) {
            val initialSeason = mediaWithTmdb.lastWatchedSeason ?: 1
            selectSeason(resolvedId, initialSeason, mediaWithTmdb)
          } else {
            _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
          }
        } else {
          // Metadati assenti ≠ contenuto non riproducibile. Il titolo resta apribile e
          // avviabile tramite il suo ID IMDb (`${media.id}`): il messaggio lo dice
          // esplicitamente invece di far sembrare l'errore un blocco.
          val tmdbConfigured = MediaRepository.isTmdbConfigured()
          val message = if (!tmdbConfigured) {
            "Metadati TMDB non disponibili (chiave API non configurata). La riproduzione resta attiva tramite l'identificatore ${media.id}."
          } else {
            "Nessun dettaglio TMDB per l'identificatore ${media.id}. La riproduzione resta attiva tramite l'ID IMDb."
          }
          Log.w(TAG, "openDetail: risoluzione IMDb->TMDB fallita per ${media.id} (tmdbConfigurato=$tmdbConfigured)")
          MediaRepository.setDetailError(media, message)
          _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
        }
      }
    } else {
      // Nessun TMDB ID e nessun ID IMDb: i dettagli estesi non sono recuperabili,
      // ma la ricerca per titolo resta disponibile, quindi la riproduzione non è esclusa.
      MediaRepository.setDetailError(
        media,
        "Dettagli TMDB non disponibili per questo contenuto: la ricerca delle sorgenti prosegue per titolo."
      )
      _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
    }
  }

  fun retryLoadDetail() {
    val media = _selectedMedia.value ?: return
    if (isLiveChannel(media.id)) {
      openLiveChannel(media)
      return
    }
    openDetail(media)
  }

  // ── Canali LIVE (addon Stremio, es. TvVoo) ──────────────────────────────────

  /** Addon installato che espone [mediaId] in uno dei propri cataloghi. */
  private fun addonForChannel(mediaId: String): InstalledAddon? {
    val parsed = StremioLiveChannelClassifier.parseChannelKey(mediaId)
    if (parsed != null) {
      return StremioAddonRepository.getInstalledAddons()
        .firstOrNull { it.id.equals(parsed.first, ignoreCase = true) }
    }
    return StremioAddonRepository.getInstalledAddons()
      .firstOrNull { StremioCatalogRepository.channelCatalogIdFor(it.id, mediaId) != null }
  }

  /**
   * true se [mediaId] identifica un canale LIVE di un addon installato.
   * Il dettaglio tradizionale (stagioni/episodi, cast, trailer) resta invariato
   * per film, serie e tutti i contenuti IMDb/TMDB.
   */
  fun isLiveChannel(mediaId: String?): Boolean {
    val id = mediaId?.trim().orEmpty()
    if (id.isEmpty()) return false
    if (!StremioLiveChannelClassifier.isChannelStyleId(id)) return false
    val key = StremioLiveChannelClassifier.channelKeyFor(
      id,
      StremioLiveChannelClassifier.AddonResolver(::addonForChannel)
    ) ?: return false
    return StremioLiveChannelClassifier.isLiveChannel(
      key,
      StremioAddonRepository.getInstalledAddons()
    )
  }

  /**
   * Apre un canale LIVE: nessun caricamento di stagioni/episodi (il canale non ne
   * ha). Il media resta aperto come dettaglio corrente e le sorgenti vengono
   * raccolte dall'addon con il tipo ORIGINALE del catalogo (`tv` per TvVoo).
   */
  fun openLiveChannel(media: MediaItem) {
    selectSeasonJob?.cancel()
    selectSeasonJob = null
    _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
    _selectedMedia.value = media
    _screenState.value = ScreenState.DETAIL
    detailMediaStack.clear()
    detailMediaStack.add(media)
    resolveLiveChannelSources(media)
  }

  /**
   * Raccolta delle sorgenti del canale tramite l'addon che lo dichiara.
   *
   * Riusa il flusso esistente: le sorgenti diventano [StreamSource] con la stessa
   * conversione usata da [StreamManager] e confluiscono in `_availableSources`,
   * così la schermata di selezione e il player restano quelli di sempre.
   */
  fun resolveLiveChannelSources(media: MediaItem) {
    val addon = addonForChannel(media.id)
    if (addon == null) {
      _availableSources.value = emptyList()
      _sourceCandidates.value = emptyList()
      _streamResolutionState.value = StreamResolutionState.Error(
        "Canale non disponibile: nessun addon installato lo espone"
      )
      return
    }
    val mediaId = StremioLiveChannelClassifier.parseChannelKey(media.id)?.second ?: media.id
    val catalogId = StremioCatalogRepository.channelCatalogIdFor(addon.id, mediaId)

    currentMediaFetchJob?.cancel()
    streamJob?.cancel()
    loadStreamGeneration++
    val generation = loadStreamGeneration
    _availableSources.value = emptyList()
    _sourceCandidates.value = emptyList()
    _showSourceDialog.value = false
    _streamResolutionState.value = StreamResolutionState.Loading
    _streamResult.value = StreamResult.Loading("Ricerca sorgenti canale in corso...")
    _sourceDialogMedia.value = SourceDialogTarget(media, null)

    streamJob = viewModelScope.launch(ioDispatcher) {
      val sources = try {
        StremioAddonRepository.fetchChannelStreams(addon, catalogId, mediaId)
          .mapNotNull { StreamManager.directStreamSource(it) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Canale $mediaId: risoluzione fallita (${e.javaClass.simpleName})")
        emptyList()
      }
      withContext(Dispatchers.Main) {
        if (generation != loadStreamGeneration) return@withContext
        _isSwitchingEpisode.value = false
        if (sources.isEmpty()) {
          _streamResolutionState.value = StreamResolutionState.Error(
            "Nessuna sorgente disponibile per questo canale"
          )
          _streamResult.value = StreamResult.Error("Nessuna sorgente disponibile per questo canale")
          return@withContext
        }
        publishResolvedSources(sources)
        _sourceDialogMedia.value = SourceDialogTarget(media, null)
        _streamResolutionState.value = StreamResolutionState.Idle
        _streamResult.value = StreamResult.Idle
        _showSourceDialog.value = true
        Log.i(
          SAME_SOURCE_TAG,
          "Canale ${mediaId}: ${sources.size} sorgenti (addon=${addon.manifest.displayTitle})"
        )
      }
    }
  }

  fun openPlayer(media: MediaItem, episode: Episode? = null, source: StreamSource? = null) {
    Log.d("BACK_TRACE", "STREAMNOVAVM: openPlayer() entry")
    if (shouldBlockForParentalControl(media)) {
      pendingPlayback = PendingPlayback(media, episode, source)
      _parentalPinErrorResId.value = null
      _showParentalPinDialog.value = true
      return
    }
    executeOpenPlayer(media, episode, source)
  }

  private fun executeOpenPlayer(
    media: MediaItem,
    episode: Episode? = null,
    source: StreamSource? = null,
    isReusedStream: Boolean = false
  ) {
    // Un canale LIVE è tipizzato SERIE_TV (così lo mostra il catalogo `tv`), ma non
    // ha episodi: non va mai creato un episodio sintetico S1E1.
    val isTv = media.type == MediaType.SERIE_TV && !isLiveChannel(media.id)
    val effectiveEpisode = if (isTv && episode == null) {
      val s = media.lastWatchedSeason ?: 1
      val e = media.lastWatchedEpisode ?: 1
      media.episodes.find { it.seasonNumber == s && it.episodeNumber == e }
        ?: media.episodes.firstOrNull()
        ?: Episode(
            id = "${media.id}_s${s}e${e}",
            seasonNumber = s,
            episodeNumber = e,
            title = "Episodio $e",
            synopsis = "",
            durationMinutes = media.durationMinutes.takeIf { it > 0 } ?: 55,
            videoUrl = media.videoUrl,
            seriesImdbId = media.effectiveImdbId
          )
    } else {
      episode
    }

    val effectiveSeriesImdb = media.effectiveImdbId
      ?: effectiveEpisode?.effectiveImdbId
      ?: media.id.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(":")

    val finalEpisode = if (effectiveEpisode != null && effectiveEpisode.seriesImdbId.isNullOrBlank() && !effectiveSeriesImdb.isNullOrBlank()) {
      effectiveEpisode.copy(seriesImdbId = effectiveSeriesImdb)
    } else {
      effectiveEpisode
    }

    val finalMedia = if (media.imdbId.isNullOrBlank() && !effectiveSeriesImdb.isNullOrBlank()) {
      media.copy(imdbId = effectiveSeriesImdb)
    } else {
      media
    }

    val savedInitialPos = if (isTv && finalEpisode != null) {
      val saved = MediaRepository.getEpisodeProgress(finalMedia.id, finalEpisode.seasonNumber, finalEpisode.episodeNumber)
      if (saved > 0L) saved else finalEpisode.currentProgressMs
    } else {
      finalMedia.currentProgressMs
    }
    val totalDur = finalEpisode?.totalDurationMs ?: finalMedia.totalDurationMs
    // "Riprendi automaticamente la riproduzione": se OFF si parte da 0:00 SENZA
    // cancellare il progresso salvato (il valore resta in Room).
    // Se l'episodio è già concluso (>= 90% o <15s alla fine), si riparte da 0:00
    // per non far scattare istantaneamente il fine riproduzione e l'auto-advance.
    val isAlreadyFinished = totalDur > 0L && (savedInitialPos >= (totalDur * 0.90f) || (totalDur - savedInitialPos) <= 15_000L)
    val initialPos = if (SettingsRepository.settings.value.autoResume && !isAlreadyFinished) savedInitialPos else 0L

    // Pacchetto atomico e immutabile: descrive SOLO il media/episodio cliccati ora.
    // Da qui in avanti ogni salvataggio (Continua a guardare) usa questi campi.
    currentSession = PlaybackSession(
      mediaId = finalMedia.id,
      mediaTitle = finalMedia.title,
      posterUrl = finalMedia.posterUrl,
      isSeries = isTv,
      seasonNumber = finalEpisode?.seasonNumber,
      episodeNumber = finalEpisode?.episodeNumber
    )

    _playbackState.value = PlayerPlaybackState(
      media = finalMedia,
      currentEpisode = finalEpisode?.copy(currentProgressMs = initialPos),
      isPlaying = true,
      currentPositionMs = initialPos,
      durationMs = totalDur,
      selectedResolution = finalMedia.resolution,
      areControlsVisible = true,
      streamUrl = source?.streamUrl,
      streamHeaders = source?.headers ?: emptyMap(),
      streamQuality = source?.quality,
      streamServer = source?.serverName,
      streamProgressive = source?.isProgressive,
      subtitles = source?.subtitles ?: emptyList(),
      isReusedStream = isReusedStream,
      currentSource = source
    )
    // Stream del nuovo episodio ormai disponibile: la transizione è conclusa.
    _isSwitchingEpisode.value = false

    // "Prossimo Episodio": risolve (o carica in background) l'episodio successivo
    // e lo espone a PlayerScreen per overlay e auto-advance.
    refreshNextEpisodeForPlayback(finalMedia, finalEpisode)
    val initialSubtitles = source?.subtitles ?: emptyList()
    if (initialSubtitles.none { it.addonName == OpenSubtitlesSubtitleAdapter.PROVIDER_NAME }) {
      viewModelScope.launch(Dispatchers.IO) {
        val external = openSubtitlesProvider.fetchSubtitles(
          mediaItem = finalMedia,
          episode = finalEpisode,
          preferredLanguage = SettingsRepository.settings.value.preferredSubtitleLanguage
        )
        if (external.isNotEmpty()) {
          _playbackState.update { current ->
            val merged = (current.subtitles + external).distinctBy { it.id.ifBlank { it.url } }
            current.copy(subtitles = merged)
          }
        }
      }
    }
    // Memorizza l'origine reale (DETAIL / PROVIDER / BROWSING) per un ritorno corretto al BACK.
    if (_screenState.value != ScreenState.PLAYER) {
      screenBeforePlayer = _screenState.value
      sectionBeforePlayer = _currentSection.value
    }
    _screenState.value = ScreenState.PLAYER
  }

  /**
   * Chiamato dal player quando lo stream raggiunge lo stato READY **senza** errori HTTP
   * fatali: persiste il flusso corrente come "ultimo funzionante" per il titolo, così la
   * prossima apertura può ripartire direttamente da qui ("Riusa l'ultimo link").
   */
  fun onStreamPlaybackReady() {
    val state = _playbackState.value
    val media = state.media ?: return
    val url = state.streamUrl?.takeIf { it.isNotBlank() } ?: return
    // Nessuna cache per i contenuti demo (URL fittizi, mai riutilizzabili).
    if (MediaRepository.isDemoSeedItem(media.id)) return

    val episode = state.currentEpisode
    val season = episode?.seasonNumber ?: LastWorkingStreamRepository.NO_EPISODE
    val epNumber = episode?.episodeNumber ?: LastWorkingStreamRepository.NO_EPISODE
    // Modalità con cui è stata generata la fonte: registrata insieme al flusso, così
    // "Continua a guardare" può scartare la voce se l'utente cambia modalità (HTTP <-> Debrid).
    val sourceMode = LastWorkingStreamRepository.classifySourceMode(
      source = state.currentSource,
      fallbackEngineMode = AppSettingsRepository.streamingEngineMode.value
    )
    viewModelScope.launch(ioDispatcher) {
      LastWorkingStreamRepository.saveStream(
        mediaId = media.id,
        seasonNumber = season,
        episodeNumber = epNumber,
        streamUrl = url,
        streamTitle = state.streamServer,
        streamQuality = state.streamQuality,
        serverName = state.streamServer,
        headers = state.streamHeaders,
        isProgressive = state.streamProgressive,
        streamMode = sourceMode.name
      )
    }
  }

  /**
   * Fallback difensivo del riuso: lo stream proveniente dalla cache non è più
   * riproducibile (link Debrid scaduto, HTTP 403/404 o errore di caricamento ExoPlayer).
   * Invalida la voce per quel titolo e riapre **immediatamente** la risoluzione/selezione
   * classica delle sorgenti, senza riusare la cache.
   *
   * @return true se lo stream corrente era riutilizzato e il fallback è stato avviato.
   */
  fun handleReusedStreamFailure(): Boolean {
    val state = _playbackState.value
    val media = state.media ?: return false
    if (!state.isReusedStream) return false

    val episode = state.currentEpisode
    val season = episode?.seasonNumber ?: LastWorkingStreamRepository.NO_EPISODE
    val epNumber = episode?.episodeNumber ?: LastWorkingStreamRepository.NO_EPISODE
    // Disattiva subito il flag: evita un secondo fallback mentre la nuova risoluzione parte.
    _playbackState.value = state.copy(isReusedStream = false)

    viewModelScope.launch(Dispatchers.IO) {
      LastWorkingStreamRepository.invalidate(media.id, season, epNumber)
    }
    Log.i(TAG, "Reuse: flusso riutilizzato fallito per ${media.id} S$season E$epNumber, fallback alla risoluzione classica")
    openStreamForMedia(media, episode, allowReuse = false)
    return true
  }

  /**
   * Salva il progresso della sessione corrente di riproduzione:
   * se serie TV, lo associa specificamente alla coppia (stagione, episodio) in Room;
   * se film, lo associa al mediaId del film.
   *
   * Il record è scritto usando il pacchetto atomico [currentSession] (i soli dati del
   * media/episodio cliccati), mai variabili globali mutevoli: se la sessione non
   * corrisponde al media in stato (contaminazione da una scheda precedente), il
   * salvataggio viene scartato.
   */
  fun saveCurrentPlaybackProgress(finalPos: Long) {
    val current = _playbackState.value
    val media = current.media ?: return
    if (finalPos <= 0L) return

    val session = currentSession
    if (session == null || session.mediaId != media.id) {
      Log.w(
        TAG,
        "saveCurrentPlaybackProgress: scartato, sessione ${session?.mediaId} != media ${media.id}"
      )
      return
    }

    if (session.isSeries) {
      val s = session.seasonNumber ?: current.currentEpisode?.seasonNumber ?: media.lastWatchedSeason ?: 1
      val e = session.episodeNumber ?: current.currentEpisode?.episodeNumber ?: media.lastWatchedEpisode ?: 1
      // `current.durationMs` e' la durata REALE misurata da ExoPlayer per questo episodio
      // (tenuta a `> 0` da [updatePlaybackPosition], che ignora `C.TIME_UNSET`). Se il player
      // non ha ancora fornito una durata valida, il repository NON sovrascrive quella gia' nota.
      MediaRepository.updateEpisodeProgress(session.mediaId, s, e, finalPos, current.durationMs)
    } else {
      MediaRepository.updateProgress(session.mediaId, finalPos, current.durationMs)
    }
  }

  /**
   * Passaggio manuale all'episodio successivo:
   * 1. Salva la posizione attuale dell'episodio corrente.
   * 2. Recupera la posizione persistita del nuovo episodio (0L se mai iniziato).
   * 3. Avvia la riproduzione del nuovo episodio dalla sua posizione specifica.
   */
   fun playNextEpisode(nextEpisode: Episode) {
     val current = _playbackState.value
    val media = current.media ?: return
    if (current.currentPositionMs > 0L) {
      saveCurrentPlaybackProgress(current.currentPositionMs)
    }

    val effectiveSeriesImdb = media.effectiveImdbId
      ?: nextEpisode.effectiveImdbId
      ?: media.id.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(":")

    val enrichedMedia = if (media.imdbId.isNullOrBlank() && !effectiveSeriesImdb.isNullOrBlank()) {
      media.copy(imdbId = effectiveSeriesImdb)
    } else {
      media
    }

    val nextSavedRaw = MediaRepository.getEpisodeProgress(
      enrichedMedia.id,
      nextEpisode.seasonNumber,
      nextEpisode.episodeNumber
    )
    // Il nuovo episodio usa il proprio progresso (seasonNumber/episodeNumber) e,
    // se "Riprendi automaticamente" è OFF o se l'episodio era già completato, parte da 0.
    val isNextFinished = nextEpisode.totalDurationMs > 0L && (nextSavedRaw >= (nextEpisode.totalDurationMs * 0.90f) || (nextEpisode.totalDurationMs - nextSavedRaw) <= 15_000L)
    val nextSavedPos = if (SettingsRepository.settings.value.autoResume && !isNextFinished) nextSavedRaw else 0L
    val enrichedNext = nextEpisode.copy(
      currentProgressMs = nextSavedPos,
      seriesImdbId = nextEpisode.seriesImdbId ?: effectiveSeriesImdb
    )

    // === FIX: Pulisci in sicurezza il player vecchio prima di passare all'episodio successivo ===
    // Interrompi la riproduzione corrente e svuota i media item per evitare stato residuo
    _playbackState.value = current.copy(
      media = enrichedMedia,
      currentEpisode = enrichedNext,
      currentPositionMs = nextSavedPos,
      durationMs = enrichedNext.totalDurationMs,
      streamUrl = null
    )

    // "Prossimo Episodio": aggiorna subito il next verso l'episodio successivo a quello
    // appena aperto, così overlay e countdown restano corretti già dalla nuova riproduzione.
    refreshNextEpisodeForPlayback(enrichedMedia, enrichedNext)

    Log.d(
      "NextEpisodeStream",
      "Risoluzione stream per S${enrichedNext.season}E${enrichedNext.episode} - Id: ${enrichedNext.id}, SeriesId: ${enrichedMedia.id}, IMDb: ${enrichedMedia.imdbId}"
    )

    openStreamForMedia(enrichedMedia, enrichedNext, allowReuse = false, isNextEpisode = true)
  }

  /**
   * Tenta la riproduzione rapida dall'ultimo flusso funzionante salvato in cache.
   *
   * Blindatura: il riuso è consentito **solo** se la cache corrisponde esattamente al
   * media richiesto e, per le serie, alla stessa puntata (`season`/`episode`). In caso
   * contrario la cache viene scartata categoricamente: nessun link di un altro titolo
   * o di un'altra puntata può essere riprodotto.
   *
   * @param requestedMediaId id del media effettivamente richiesto (default: [media.id]).
   * @param requestedSeason stagione richiesta per le serie (default: quella di [episode]).
   * @param requestedEpisode episodio richiesto per le serie (default: quello di [episode]).
   * @return true se un flusso valido è stato trovato e aperto nel player (la risoluzione
   *         classica va saltata), false se la cache è assente/scaduta o la funzione è OFF.
   */
  private suspend fun tryOpenReusedStream(
    media: MediaItem,
    episode: Episode?,
    generation: Int,
    requestedMediaId: String? = null,
    requestedSeason: Int? = null,
    requestedEpisode: Int? = null
  ): Boolean {
    val settings = SettingsRepository.settings.value
    if (!settings.reuseLastWorkingStreamEnabled) return false

    val currentMode = AppSettingsRepository.streamingEngineMode.value

    val reqId = requestedMediaId?.takeIf { it.isNotBlank() } ?: media.id
    val season = episode?.seasonNumber ?: LastWorkingStreamRepository.NO_EPISODE
    val epNumber = episode?.episodeNumber ?: LastWorkingStreamRepository.NO_EPISODE
    val reqSeason = requestedSeason ?: season
    val reqEp = requestedEpisode ?: epNumber

    // Blindatura 1: l'id richiesto deve coincidere con quello del media risolto.
    if (reqId != media.id) {
      Log.w(TAG, "Reuse: scartato, id richiesto $reqId != media risolto ${media.id}")
      return false
    }
    // Blindatura 2: per le serie la puntata richiesta deve coincidere esattamente.
    if (episode != null &&
      (episode.seasonNumber != reqSeason || episode.episodeNumber != reqEp)
    ) {
      Log.w(TAG, "Reuse: scartato, puntata richiesta S$reqSeason E$reqEp != S${episode.seasonNumber} E${episode.episodeNumber}")
      return false
    }

    // La lettura della cache (Room) non deve MAI far fallire la ricerca: un errore
    // del DB (schema/migrazione) viene loggato e trattato come "nessuna voce in
    // cache", cosi' [openStreamForMedia] prosegue con la risoluzione live.
    val cached = runCatching {
      LastWorkingStreamRepository.getValidStream(
        mediaId = reqId,
        seasonNumber = reqSeason,
        episodeNumber = reqEp,
        durationHours = settings.lastWorkingStreamCacheDurationHours
      )
    }.onFailure { e ->
      Log.e(
        TAG,
        "Reuse: lettura cache 'ultimo flusso' fallita per $reqId S$reqSeason E$reqEp: ${e.message}",
        e
      )
    }.getOrNull() ?: return false
    if (cached.streamUrl.isBlank()) return false

    // Blindatura 3: la voce di cache deve appartenere esattamente al titolo/puntata richiesti.
    if (cached.mediaId != reqId ||
      cached.seasonNumber != reqSeason ||
      cached.episodeNumber != reqEp
    ) {
      Log.w(
        TAG,
        "Reuse: scartato, cache ${cached.mediaId} S${cached.seasonNumber} E${cached.episodeNumber} " +
          "!= richiesta $reqId S$reqSeason E$reqEp"
      )
      return false
    }

    // SMART SWITCH HTTP <-> DEBRID: la fonte salvata è riusabile SOLO se generata nella
    // stessa modalità attualmente attiva. In caso di cambio modalità (Continua a guardare
    // salvato in HTTP ma app in Debrid, o viceversa) la voce viene ignorata: si esegue
    // una risoluzione completa con i provider della modalità attiva, mantenendo stagione,
    // episodio e minuto di ripresa (gestiti da [openStreamForMedia]).
    val cachedMode = LastWorkingStreamRepository.resolveEngineMode(cached)
    if (cachedMode != currentMode) {
      Log.i(
        TAG,
        "Reuse: scartato, fonte salvata in $cachedMode != modalità attiva $currentMode " +
          "per $reqId S$reqSeason E$reqEp: nuova risoluzione"
      )
      return false
    }

    // La riproduzione rapida da cache è pensata per la modalità Debrid.
    if (currentMode != StreamingEngineMode.DEBRID_TORBOX) return false

    Log.i(TAG, "Reuse: ultimo flusso valido per $reqId S$reqSeason E$reqEp (${cached.serverName})")
    val source = cached.toStreamSource()
    withContext(Dispatchers.Main) {
      if (generation != loadStreamGeneration) return@withContext
      _streamResolutionState.value = StreamResolutionState.Success(
        streamUrl = source.streamUrl ?: "",
        mediaItem = media,
        episode = episode,
        streamHeaders = source.headers,
        quality = source.quality,
        serverName = source.serverName,
        isProgressive = source.isProgressive
      )
      _streamResult.value = StreamResult.Success(listOf(source))
      // Apre il player marcando lo stream come "riutilizzato" per il fallback difensivo.
      executeOpenPlayer(media, episode, source, isReusedStream = true)
    }
    return true
  }

  /**
   * Punto d'ingresso per l'avvio della riproduzione di un MediaItem (e relativo episodio se serie TV).
   * - Cancella tassativamente il job precedente.
   * - Imposta immediatamente lo stato su Loading.
   * - Esegue la ricerca con timeout rigido di 7 secondi.
   * - Al successo imposta lo stato su Success e avvia il player.
   * - Al fallimento o timeout imposta lo stato su Error.
   */
  fun openStreamForMedia(
    mediaItem: MediaItem,
    episode: Episode? = null,
    allowReuse: Boolean = true,
    isNextEpisode: Boolean = false
  ) {
    Log.d(TAG, "openStreamForMedia START mediaId=${mediaItem.id} tmdbId=${mediaItem.tmdbId}")

    // Impostazione atomica immediata del media associato al dialog delle sorgenti:
    // prima di mostrare o raccogliere le sorgenti, elimina residui di titoli precedenti
    // e allinea subito la colonna di sinistra del dialog al target corrente.
    _sourceDialogMedia.value = SourceDialogTarget(mediaItem, episode)

    if (shouldBlockForParentalControl(mediaItem)) {
      _sourceDialogMedia.value = null
      pendingPlayback = PendingPlayback(mediaItem, episode, null)
      _parentalPinErrorResId.value = null
      _showParentalPinDialog.value = true
      return
    }

    // Cancella tassativamente il Job precedente: sia il fetch metadati sia la
    // risoluzione stream in volo vengono annullati, così nessuna risposta in ritardo
    // può contaminare la sessione appena aperta.
    currentMediaFetchJob?.cancel()
    currentMediaFetchJob = null
    streamJob?.cancel()
    loadStreamGeneration++
    // Sessione pulita: azzera lo stream corrente e i puntatori di fallback.
    // Eccezione mirata: nel cambio puntata consecutivo della STESSA serie il
    // `currentSource` viene conservato perché è la base dell'affinità di fonte
    // (stesso torrent di stagione / stesso provider) per l'episodio successivo.
    val sameSeriesNextEpisode = isNextEpisode &&
      _playbackState.value.media?.id == mediaItem.id &&
      _playbackState.value.currentSource != null
    _playbackState.value = _playbackState.value.copy(
      streamUrl = null,
      currentSource = if (sameSeriesNextEpisode) _playbackState.value.currentSource else null,
      isReusedStream = false
    )
    _availableSources.value = emptyList()
    // Nuovo titolo: anche la lista del selettore interno al player riparte vuota.
    _sourceCandidates.value = emptyList()
    // Nuovo ciclo di riproduzione: nessuna sorgente risulta ancora tentata, cosi'
    // il fallback automatico riparte con la lista completa.
    attemptedSourceKeys.clear()
    // Ogni nuova risoluzione riparte senza dialog di selezione (il flag viene
    // riattivato dal flusso solo quando serve la scelta manuale).
    _showSourceDialog.value = false

    // Imposta subito lo stato su Loading. `isSwitchingEpisode` marca la
    // transizione al prossimo episodio: finché è attivo il player resta in
    // caricamento (spinner "Caricamento episodio successivo...") e non fallisce.
    _isSwitchingEpisode.value = isNextEpisode
     _streamResolutionState.value = StreamResolutionState.Loading
     _streamResult.value = StreamResult.Loading("Ricerca sorgenti in corso...")

     val currentDetail = detailUiState.value
    val enrichedMedia = if (currentDetail is MediaDetailUiState.Success) {
      val candidate = currentDetail.media
      val matches = (mediaItem.tmdbId != null && mediaItem.tmdbId > 0 && candidate.tmdbId == mediaItem.tmdbId) ||
        (mediaItem.id.isNotBlank() && (candidate.id == mediaItem.id || candidate.id.equals(mediaItem.id, ignoreCase = true)))
      if (matches) candidate else null
    } else null

    val baseForResolution = if (enrichedMedia != null) {
      mediaItem.copy(
        id = mediaItem.id.ifBlank { enrichedMedia.id },
        title = mediaItem.title.ifBlank { enrichedMedia.title },
        originalTitle = mediaItem.originalTitle.ifBlank { enrichedMedia.originalTitle },
        synopsis = mediaItem.synopsis.ifBlank { enrichedMedia.synopsis },
        tmdbId = mediaItem.tmdbId ?: enrichedMedia.tmdbId,
        year = if (mediaItem.year > 0) mediaItem.year else enrichedMedia.year,
        provider = mediaItem.provider ?: enrichedMedia.provider,
        genres = if (mediaItem.genres.isNotEmpty()) mediaItem.genres else enrichedMedia.genres,
        episodes = if (mediaItem.episodes.isNotEmpty()) mediaItem.episodes else enrichedMedia.episodes,
        lastWatchedSeason = mediaItem.lastWatchedSeason ?: enrichedMedia.lastWatchedSeason,
        lastWatchedEpisode = mediaItem.lastWatchedEpisode ?: enrichedMedia.lastWatchedEpisode,
        imdbId = mediaItem.imdbId ?: enrichedMedia.imdbId
      )
    } else {
      mediaItem
    }

    val isTv = baseForResolution.type == MediaType.SERIE_TV
    val effectiveEpisode = if (isTv && episode == null) {
      val s = baseForResolution.lastWatchedSeason ?: 1
      val e = baseForResolution.lastWatchedEpisode ?: 1
      baseForResolution.episodes.find { it.seasonNumber == s && it.episodeNumber == e }
        ?: baseForResolution.episodes.firstOrNull()
    } else {
      episode
    }

    // Aggiorna atomicamente con il media arricchito e l'episodio calcolato
    _sourceDialogMedia.value = SourceDialogTarget(baseForResolution, effectiveEpisode)

    val currentJobGeneration = loadStreamGeneration
    streamJob = viewModelScope.launch(ioDispatcher) {
      val mode = AppSettingsRepository.streamingEngineMode.value
      val resolvedMediaItem = if (baseForResolution.tmdbId == null && baseForResolution.id.startsWith("tt", ignoreCase = true)) {
        val resolvedId = MediaRepository.resolveImdbToTmdbId(baseForResolution.id, isTv)
        if (resolvedId != null && resolvedId > 0) baseForResolution.copy(tmdbId = resolvedId) else baseForResolution
      } else {
        baseForResolution
      }
      withContext(Dispatchers.Main) {
        if (currentJobGeneration == loadStreamGeneration) {
          _sourceDialogMedia.value = SourceDialogTarget(resolvedMediaItem, effectiveEpisode)
        }
      }
      // Chiavi di ricerca disponibili. L'ID TMDB arricchisce la ricerca ma NON è un
      // prerequisito per riprodurre: gli addon Stremio e i debrid (Real-Debrid, TorBox)
      // usano l'identificatore IMDb nativo (`tt...`) e i provider HTTP italiani cercano
      // per titolo. Un metadata TMDB mancante non deve quindi fermare lo stream.
      val effectiveTmdbId = resolvedMediaItem.tmdbId?.takeIf { it > 0 } ?: 0
      val searchTitle = resolvedMediaItem.title.ifBlank { resolvedMediaItem.originalTitle }
      val effectiveImdb = resolvedMediaItem.effectiveImdbId
        ?: effectiveEpisode?.effectiveImdbId
        ?: resolvedMediaItem.id.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(":")
      if (effectiveTmdbId <= 0) {
        Log.i(TAG, "Risoluzione stream senza ID TMDB (imdb=$effectiveImdb, titolo='$searchTitle'): si prosegue con i motori che usano l'ID nativo")
      }

      // Unico caso in cui la ricerca viene davvero fermata: nessuna chiave utile.
      // Senza TMDB, senza IMDb e senza titolo nessun motore può trovare sorgenti.
      if (effectiveTmdbId <= 0 && effectiveImdb.isNullOrBlank() && searchTitle.isBlank()) {
        withContext(Dispatchers.Main) {
          if (currentJobGeneration != loadStreamGeneration) return@withContext
          _isSwitchingEpisode.value = false
          val message = "Impossibile identificare il titolo: nessun identificatore IMDb/TMDB né titolo disponibile."
          if (isNextEpisode) {
            // Nuova puntata non ricercabile: mai schermata nera,
            // si passa alla selezione manuale delle sorgenti.
            _sourceDialogMedia.value = SourceDialogTarget(baseForResolution, effectiveEpisode)
            _showSourceDialog.value = true
            _streamResolutionState.value = StreamResolutionState.Idle
            _streamResult.value = StreamResult.Idle
          } else {
            _streamResolutionState.value = StreamResolutionState.Error(message)
            _streamResult.value = StreamResult.Error(message)
          }
        }
        return@launch
      }

      // "Riusa l'ultimo link": se esiste un flusso funzionante ancora valido per questo
      // film/episodio, si salta la ricerca e la selezione della lista e si riproduce
      // direttamente lo stream salvato. In caso di fallimento subentra il fallback in
      // [handleReusedStreamFailure], che richiama questo metodo con `allowReuse = false`.
      // NOTA: la cache è utilizzabile solo se corrisponde esattamente al media e alla
      // puntata richiesti (id esplicito del click + stagione/episodio effettivi).
      // Blindatura: qualunque errore del percorso di riuso (accesso DB/schema) non
      // deve far morire questa coroutine prima di interrogare i provider. In caso di
      // errore si procede con la risoluzione live.
      val reused = allowReuse && runCatching {
        tryOpenReusedStream(
          media = resolvedMediaItem,
          episode = effectiveEpisode,
          generation = currentJobGeneration,
          requestedMediaId = mediaItem.id,
          requestedSeason = effectiveEpisode?.seasonNumber,
          requestedEpisode = effectiveEpisode?.episodeNumber
        )
      }.onFailure { e ->
        Log.e(TAG, "Reuse: apertura stream riusato fallita, si prosegue con la ricerca live: ${e.message}", e)
      }.getOrDefault(false)
      if (reused) {
        return@launch
      }

      val season = if (isTv) (effectiveEpisode?.seasonNumber ?: resolvedMediaItem.lastWatchedSeason ?: 1) else null
      val epNumber = if (isTv) (effectiveEpisode?.episodeNumber ?: resolvedMediaItem.lastWatchedEpisode ?: 1) else null

      // Bingewatching - Priorita' assoluta: stessa fonte dell'episodio corrente.
      // Se l'episodio precedente veniva da un torrent di stagione (Debrid), si
      // cerca il file del nuovo episodio DENTRO lo stesso torrent e lo si avvia
      // direttamente, senza interrogare nuovamente gli addon.
      val pinnedSource = if (isNextEpisode) _playbackState.value.currentSource else null
      if (pinnedSource != null && season != null && epNumber != null &&
        mode == StreamingEngineMode.DEBRID_TORBOX && pinnedSource.infoHash != null
      ) {
        val pinnedHash = pinnedSource.infoHash
        Log.d("NextEpisodeDebrid", "Tentativo fast-path torrent: $pinnedHash per S${season}E${epNumber}")
        // Timeout rigido e insuperabile (3.5s): se lo stesso torrent non risponde,
        // non si resta MAI in sospeso. Fallthrough immediato alla ricerca normale.
        val fastPathStream = withTimeoutOrNull(NEXT_EPISODE_FAST_PATH_TIMEOUT_MS) {
          seasonPackEpisodeResolver(pinnedSource, season, epNumber)
        }
        Log.d("NextEpisodeDebrid", "Esito fast-path: ${fastPathStream != null}")
        val samePackSource = fastPathStream
        if (samePackSource != null && !samePackSource.streamUrl.isNullOrBlank()) {
          withContext(Dispatchers.Main) {
            if (currentJobGeneration != loadStreamGeneration) return@withContext
            Log.i(SAME_SOURCE_TAG, "Avvio diretto S$season E$epNumber dallo stesso torrent")
            _availableSources.value = listOf(samePackSource)
            _sourceCandidates.value = listOf(samePackSource)
            _streamResolutionState.value = StreamResolutionState.Success(
              streamUrl = samePackSource.streamUrl ?: "",
              mediaItem = resolvedMediaItem,
              episode = effectiveEpisode,
              streamHeaders = samePackSource.headers,
              quality = samePackSource.quality,
              serverName = samePackSource.serverName,
              isProgressive = samePackSource.isProgressive
            )
            _streamResult.value = StreamResult.Success(listOf(samePackSource))
            openPlayer(resolvedMediaItem, effectiveEpisode, samePackSource)
          }
          return@launch
        } else {
          Log.d("NextEpisodeDebrid", "Fast-path fallito o timeout, procedo con ricerca normale")
        }
      }

      val openSubtitlesDeferred = async(ioDispatcher) {
        openSubtitlesProvider.fetchSubtitles(
          mediaItem = resolvedMediaItem,
          episode = effectiveEpisode,
          preferredLanguage = SettingsRepository.settings.value.preferredSubtitleLanguage
        )
      }

      var hasOpenedPlayer = false
      // Sorgente auto-picked in attesa della finestra di assestamento: e' la
      // sorgente con cui il player verra' avviato, scelta UNA volta sola.
      var pendingAutoPick: StreamSource? = null
      var settleJob: Job? = null

      /**
       * Avvia il player con la sorgente auto-picked.
       *
       * Idempotente e serializzata: gira sempre sul Main dispatcher e la prima
       * chiamata vince. E' l'UNICO punto in cui la risoluzione automatica
       * puo' far partire il player.
       */
      suspend fun openAutoPickedSource(source: StreamSource) {
        withContext(Dispatchers.Main) {
          if (hasOpenedPlayer) return@withContext
          if (currentJobGeneration != loadStreamGeneration) return@withContext
          hasOpenedPlayer = true
          _streamResolutionState.value = StreamResolutionState.Success(
            streamUrl = source.streamUrl ?: "",
            mediaItem = resolvedMediaItem,
            episode = effectiveEpisode,
            streamHeaders = source.headers,
            quality = source.quality,
            serverName = source.serverName,
            isProgressive = source.isProgressive
          )
          _streamResult.value = StreamResult.Success(listOf(source))
          openPlayer(resolvedMediaItem, effectiveEpisode, source)
        }
      }

      val resolutionFlow = streamManager.resolveFlow(
        tmdbId = effectiveTmdbId,
        isTv = isTv,
        season = season,
        episode = epNumber,
        title = searchTitle,
        year = resolvedMediaItem.year,
        originalTitle = resolvedMediaItem.originalTitle.takeIf { it.isNotBlank() },
        providerTag = resolvedMediaItem.provider,
        genres = resolvedMediaItem.genres,
        streamingEngineMode = mode,
        imdbId = effectiveImdb
      )
      val collectBlock: suspend (List<StreamSource>) -> Unit = collect@{ rawSources ->
        // L'auto-pick valuta SOLO liste non vuote: un primo tick senza risultati
        // (provider HTTP/addon ancora in corso) non deve mai scatenare un fallimento.
        if (rawSources.isEmpty()) return@collect

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val externalSubs = if (openSubtitlesDeferred.isCompleted) {
          try {
            openSubtitlesDeferred.getCompleted()
          } catch (e: Exception) {
            emptyList()
          }
        } else {
          emptyList()
        }

        val finalSources = rawSources.map { s ->
          val merged = (s.subtitles + externalSubs).distinctBy { it.id.ifBlank { it.url } }
          s.copy(subtitles = merged)
        }

        // Affinita' con la fonte dell'episodio corrente: stesso addon/provider e
        // firma di release piu' vicina in cima (anche davanti a qualita' grezza).
        val affinitySources = if (pinnedSource != null) {
          rankBySameSourceAffinity(finalSources, pinnedSource)
        } else {
          finalSources
        }

        val shouldShowDialog = mode == StreamingEngineMode.DEBRID_TORBOX && !AppSettingsRepository.autoplayEnabled.value
        if (shouldShowDialog) {
          withContext(Dispatchers.Main) {
            if (currentJobGeneration != loadStreamGeneration) return@withContext
            publishResolvedSources(affinitySources)
            _sourceDialogMedia.value = SourceDialogTarget(resolvedMediaItem, effectiveEpisode)
            _showSourceDialog.value = true
            _streamResolutionState.value = StreamResolutionState.Idle
            _streamResult.value = StreamResult.Idle
            _isSwitchingEpisode.value = false
            if (isNextEpisode) {
              Log.d("NextEpisodeStream", "Flussi trovati: ${affinitySources.size}, Flusso scelto: null (Selezione manuale attiva)")
            }
          }
          return@collect
        }

        // Auto-pick universale: Debrid attiva/configurata -> sblocco infoHash;
        // HTTP diretta o Debrid assente -> primo link HTTP(S) riproducibile.
        // Null (Caso C) -> selezione manuale, mai errore/schermata nera.
        val chosenStream = resolveAutoPickSource(
          sources = affinitySources,
          streamingEngineMode = mode,
          isDebridConfigured = TorBoxRepository.isConfigured(),
          season = season,
          episode = epNumber
        )

        if (isNextEpisode) {
          Log.d("NextEpisodeStream", "Flussi trovati: ${affinitySources.size}, Flusso scelto: ${chosenStream?.title}")
        }

        withContext(Dispatchers.Main) {
          if (currentJobGeneration != loadStreamGeneration) {
            Log.d(TAG, "Job ignorato perché una nuova ricerca o cancellazione è avvenuta")
            return@withContext
          }

          if (chosenStream != null && !chosenStream.streamUrl.isNullOrBlank()) {
            // La lista del selettore si arricchisce progressivamente: il flusso in
            // riproduzione NON viene riavviato (vedi blocco commentato qui sotto).
            publishResolvedSources(affinitySources)
            if (!hasOpenedPlayer) {
              // FIX BUFFERING: la sorgente che avvia il player e' scelta UNA volta
              // sola. Dopo la prima emissione si attende una breve finestra di
              // assestamento (per includere le sorgenti migliori dei provider
              // piu' lenti) e poi il player parte: nessun ricaricamento.
              //
              // Le emissioni successive NON devono piu' sostituire il flusso in
              // riproduzione. Un cambio di URL ricarica il download da capo
              // (nuovo buffering, spesso bloccato) e, se cambiano anche gli
              // headers HTTP, invalida la DataSource.Factory: l'ExoPlayer
              // viene ricreato da zero. L'arricchimento successivo resta
              // disponibile per il selettore sorgenti (_availableSources).
              pendingAutoPick = chosenStream
              if (StreamManager.isFullHdOrHigher(chosenStream)) {
                // Sorgente gia' Full HD/4K: si parte subito, senza attese.
                settleJob?.cancel()
                settleJob = null
                openAutoPickedSource(chosenStream)
              } else if (settleJob == null) {
                // Sorgente sotto 1080p: si attende una breve finestra perche'
                // i provider piu' lenti possono portare una qualita' migliore.
                settleJob = launch {
                  delay(AUTO_PICK_SETTLE_WINDOW_MS)
                  val pending = pendingAutoPick
                  if (pending != null) {
                    Log.i(
                      TAG,
                      "Auto-pick assestato: ${pending.serverName} (${pending.quality}) -> ${pending.streamUrl}"
                    )
                    openAutoPickedSource(pending)
                  }
                }
              }
            } else {
              Log.i(
                TAG,
                "Sorgente aggiuntiva disponibile (${chosenStream.serverName} ${chosenStream.quality}): " +
                  "il flusso in riproduzione non viene sostituito"
              )
            }
          } else {
            publishResolvedSources(affinitySources)
            _sourceDialogMedia.value = SourceDialogTarget(resolvedMediaItem, effectiveEpisode)
            _showSourceDialog.value = true
            _streamResolutionState.value = StreamResolutionState.Idle
            _streamResult.value = StreamResult.Idle
            _isSwitchingEpisode.value = false
          }
        }
      }

      try {
        if (isNextEpisode) {
          Log.d(
            "NextEpisodeDebrid",
            "Avvio ricerca sorgenti con timeout globale ${NEXT_EPISODE_RESOLUTION_TIMEOUT_MS}ms"
          )
          val completed = withTimeoutOrNull(NEXT_EPISODE_RESOLUTION_TIMEOUT_MS) {
            resolutionFlow.collect(collectBlock)
            true
          }
          if (completed != true) {
            Log.d(
              "NextEpisodeDebrid",
              "Timeout globale ${NEXT_EPISODE_RESOLUTION_TIMEOUT_MS}ms senza stream avviato: apertura dialog sorgenti"
            )
          }
        } else {
          resolutionFlow.collect(collectBlock)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Errore durante la risoluzione streaming: ${e.message}")
      }

      // La raccolta delle sorgenti e' terminata: se il player non e' ancora
      // partito ma esiste una sorgente auto-picked in attesa, si avvia subito
      // (non si attende oltre la finestra di assestamento).
      settleJob?.cancel()
      settleJob = null
      pendingAutoPick?.let { openAutoPickedSource(it) }

      withContext(Dispatchers.Main) {
        if (currentJobGeneration != loadStreamGeneration) return@withContext
        if (!hasOpenedPlayer && !_showSourceDialog.value) {
          if (_availableSources.value.isNotEmpty() || isNextEpisode) {
            // Caso C (anche con zero flussi per il prossimo episodio): la sessione
            // resta in attesa di scelta manuale, mai in errore/schermata nera.
            _sourceDialogMedia.value = SourceDialogTarget(resolvedMediaItem, effectiveEpisode)
            _showSourceDialog.value = true
            _streamResolutionState.value = StreamResolutionState.Idle
            _streamResult.value = StreamResult.Idle
            _isSwitchingEpisode.value = false
          } else {
            _streamResolutionState.value = StreamResolutionState.Error("Nessuna sorgente disponibile per questo titolo.")
            _streamResult.value = StreamResult.Error("Nessuna sorgente disponibile per questo titolo.")
            _isSwitchingEpisode.value = false
          }
        }
      }
    }
  }

  fun loadStream(media: MediaItem, episode: Episode? = null) {
    openStreamForMedia(media, episode)
  }

  /**
   * Sostituisce la sorgente in riproduzione con [source].
   *
   * NOTA: la risoluzione automatica NON usa questa funzione. Sostituire il
   * flusso in riproduzione significa un nuovo URL: il download riparte da
   * capo (nuovo buffering, spesso bloccato) e, se cambiano anche gli headers
   * HTTP, la DataSource.Factory cambia e l'ExoPlayer viene ricreato da zero.
   * Le emissioni tardive della risoluzione arricchiscono quindi solo la lista
   * del selettore sorgenti ([availableSources]).
   */
  fun upgradeStreamSource(source: StreamSource) {
    val current = _playbackState.value
    val sourceUrl = source.streamUrl ?: return
    if (current.streamUrl == sourceUrl) return
    _playbackState.value = current.copy(
      streamUrl = sourceUrl,
      streamHeaders = source.headers,
      streamQuality = source.quality,
      streamServer = source.serverName,
      streamProgressive = source.isProgressive,
      subtitles = (source.subtitles + current.subtitles).distinctBy { it.id.ifBlank { it.url } },
      currentSource = source
    )
  }

  /**
   * Apre il selettore delle sorgenti video interno al player.
   *
   * Il selettore e' disponibile in ogni momento della sessione: durante la
   * riproduzione, durante il buffering e anche in stato di errore (in quest'ultimo
   * caso permette di ripartire subito con un'altra sorgente senza riavviare la
   * ricerca).
   */
  fun openSourceSelector() {
    Log.i(
      TAG,
      "Selettore sorgenti aperto dal player (candidati: ${_sourceCandidates.value.size})"
    )
    _showSourceSelector.value = true
  }

  /** Chiude il selettore delle sorgenti interno al player. */
  fun dismissSourceSelector() {
    _showSourceSelector.value = false
  }

  /**
   * Sostituisce la sorgente in riproduzione con [source].
   *
   * La scelta e' esplicitamente MANUALE e quindi AUTOREVOLE:
   *  - la risoluzione ancora in volo viene cancellata e invalidata
   *    ([loadStreamGeneration]), cosi' le emissioni tardive degli altri provider
   *    NON possono sostituire la sorgente scelta dall'utente;
   *  - il selettore interno al player si chiude e la nuova sorgente subentra.
   *
   * @param source sorgente scelta dall'utente.
   */
  fun selectSource(source: StreamSource) {
    // Scelta manuale = nuovo ciclo di riproduzione: le sorgenti gia' tentate non
    // contano piu' (l'utente puo' ritentare esplicitamente anche una sorgente
    // fallita in precedenza).
    attemptedSourceKeys.clear()
    // La scelta manuale e' in corso: il fallback automatico non deve inserirsi
    // finche' non e' stata applicata (il flag viene rilasciato alla fine del job,
    // su qualunque percorso di uscita, inclusa la cancellazione).
    manualSourceSelectionInFlight = true
    // La scelta manuale dell'utente e' AUTOREVOLE: la risoluzione ancora in
    // volo va interrotta e invalidata. Senza questo, l'emissione tardiva
    // dell'auto-pick sovrascriverebbe la sorgente appena scelta dall'utente
    // (e ricaricherebbe il player da capo -> nuovo buffering).
    streamJob?.cancel()
    streamJob = null
    loadStreamGeneration++
    // Il selettore interno al player si chiude: la nuova sorgente subentra.
    _showSourceSelector.value = false
    val selectionJob = viewModelScope.launch {
      // Bersaglio del cambio: il titolo in riproduzione.
      // - [_sourceDialogMedia] e' valorizzato dalla schermata di scelta iniziale;
      // - quando invece la scelta arriva dal selettore interno al player (o da un
      //   retry dopo un errore) si usa lo stato di riproduzione corrente.
      val target = _sourceDialogMedia.value
        ?: playbackState.value.media?.let { media ->
          SourceDialogTarget(media, playbackState.value.currentEpisode)
        }
        ?: return@launch
      val media = target.media
      val episode = target.episode

      _isLoading.value = true
      _errorMessage.value = null

      val resolvedSource = if (source.infoHash != null && source.streamUrl == null) {
        Log.i(TAG, "TorBox: sblocco on-demand per ${source.infoHash}")
        Log.i(
          "[StreamNova-TorBox-Debug]",
          "1. DATI STREAM DALL'ADDON [Manuale] -> server='${source.serverName}', title='${source.releaseTitle}', infoHash=${source.infoHash}, quality/resolution='${source.declaredQuality ?: source.quality}', details='${source.details}'"
        )
        TorBoxRepository.setRequestMetadata(episode?.seasonNumber, episode?.episodeNumber)
        val directUrl = TorBoxRepository.resolveInfoHash(source.infoHash, source.fileIdx)
        if (directUrl != null) {
          Log.i(TAG, "TorBox: flusso sbloccato -> $directUrl")
          source.copy(streamUrl = directUrl)
        } else {
          Log.w(TAG, "TorBox: impossibile sbloccare ${source.infoHash}")
          _errorMessage.value = "Impossibile sbloccare questo flusso su TorBox"
          _isLoading.value = false
          return@launch
        }
      } else {
        source
      }

      _showSourceDialog.value = false
      _availableSources.value = emptyList()
      // NOTA: [_sourceCandidates] NON viene azzerata: se la sorgente scelta
      // fallisce l'utente deve poter riaprire il selettore e provarne un'altra.
      _sourceDialogMedia.value = null
      _isLoading.value = false

      Log.i(TAG, "Source selection: ${resolvedSource.serverName} (${resolvedSource.quality})")
      Log.d("BACK_TRACE", "STREAMNOVAVM: chiamata a openPlayer()")
      _streamResolutionState.value = StreamResolutionState.Success(
        streamUrl = resolvedSource.url,
        mediaItem = media,
        episode = episode,
        streamHeaders = resolvedSource.headers,
        quality = resolvedSource.quality,
        serverName = resolvedSource.serverName,
        isProgressive = resolvedSource.isProgressive
      )
      _streamResult.value = StreamResult.Success(listOf(resolvedSource))
      // Token di cambio sorgente: il player lo usa per ricaricare il flusso sulla
      // STESSA istanza di ExoPlayer (nessuna duplicazione, nessun avvio concorrente).
      _manualSourceSelectionToken.value = _manualSourceSelectionToken.value + 1
      openPlayer(media, episode, resolvedSource)
    }
    // Scelta applicata (o abbandonata): il fallback automatico torna disponibile.
    selectionJob.invokeOnCompletion { manualSourceSelectionInFlight = false }
  }

  /**
   * Fallback automatico della sorgente video dopo un errore di riproduzione.
   *
   * Chiamato dal player da `onPlayerError`. Il cambio di sorgente avviene **solo**
   * quando ricorrono tutte queste condizioni:
   *  1. l'errore e' classificato come definitivo per la sorgente
   *     ([PlayerSourceFallback.classify] ==
   *     [PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE]): un errore transitorio o
   *     di codec/sottotitoli non fa mai cambiare flusso;
   *  2. la callback riguarda la sorgente ATTUALMENTE attiva ([failedStreamUrl]
   *     coincide con lo stream in riproduzione): una callback obsoleta (cambio
   *     sorgente o episodio gia' avvenuto) viene scartata;
   *  3. non c'e' una scelta manuale in corso di applicazione;
   *  4. esiste un'alternativa fra le sorgenti gia' risolte, diversa dalla sorgente
   *     fallita e da quelle gia' tentate in questo ciclo di riproduzione.
   *
   * Quando un'alternativa esiste viene avviata con lo STESSO meccanismo della
   * scelta manuale ([_manualSourceSelectionToken] + [openPlayer]): il player
   * ricarica il flusso sulla stessa istanza di ExoPlayer, preservando la posizione.
   * La lista del selettore resta invariata: la sorgente fallita resta visibile e
   * ritentabile a mano dall'utente.
   *
   * @param failedStreamUrl URL che il player stava riproducendo quando e' scattato
   *   l'errore; serve a scartare le callback obsolete.
   * @param verdict esito di [PlayerSourceFallback.classify] sull'errore Media3.
   * @return true se un'alternativa e' stata trovata e avviata automaticamente.
   *         Con `false` il player mantiene il comportamento esistente (errore
   *         visibile e selettore manuale sempre disponibile).
   */
  fun handleSourcePlaybackFailure(
    failedStreamUrl: String?,
    verdict: PlayerSourceFallback.Verdict
  ): Boolean {
    if (verdict != PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE) {
      Log.d(TAG, "Fallback sorgente: nessun cambio (esito=$verdict)")
      return false
    }
    if (manualSourceSelectionInFlight) {
      Log.i(TAG, "Fallback sorgente: scelta manuale in corso, nessun cambio automatico")
      return false
    }
    val state = _playbackState.value
    val media = state.media ?: return false
    val activeUrl = state.streamUrl?.takeIf { it.isNotBlank() } ?: return false
    if (failedStreamUrl != null && failedStreamUrl != activeUrl) {
      Log.i(
        TAG,
        "Fallback sorgente: errore obsoleto per una sorgente non piu' attiva " +
          "($failedStreamUrl != $activeUrl), nessun cambio"
      )
      return false
    }

    val currentKey = state.currentSource?.let { PlayerSourceFallback.keyOf(it) }
      ?: PlayerSourceFallback.keyForUrl(activeUrl)
    // La sorgente appena fallita entra tra quelle tentate: non puo' essere
    // riselezionata automaticamente in questo ciclo di riproduzione.
    attemptedSourceKeys += currentKey

    val alternative = PlayerSourceFallback.selectAlternative(
      candidates = _sourceCandidates.value,
      currentKey = currentKey,
      attemptedKeys = attemptedSourceKeys
    )
    if (alternative == null) {
      Log.w(
        TAG,
        "Fallback sorgente: nessuna alternativa disponibile " +
          "(tentate=${attemptedSourceKeys.size}, candidati=${_sourceCandidates.value.size})"
      )
      return false
    }

    Log.i(
      TAG,
      "Fallback sorgente: ${state.currentSource?.serverName ?: activeUrl} " +
        "(${state.streamQuality ?: "?"}) -> ${alternative.serverName} (${alternative.quality})"
    )
    _showSourceDialog.value = false
    // Stesso segnale della scelta manuale: il player ricarica il flusso sulla
    // stessa istanza di ExoPlayer, preservando la posizione corrente.
    _manualSourceSelectionToken.value = _manualSourceSelectionToken.value + 1
    openPlayer(media, state.currentEpisode, alternative)
    return true
  }

  fun dismissSourceDialog() {
    currentMediaFetchJob?.cancel()
    currentMediaFetchJob = null
    resetStreamState()
    _showSourceDialog.value = false
    _availableSources.value = emptyList()
    _sourceCandidates.value = emptyList()
    _sourceDialogMedia.value = null
  }

  fun closePlayer() {
    Log.d("BACK_TRACE", "STREAMNOVAVM: ingresso in closePlayer()")
    Log.i(HTTP_TRACE, "[VM] closePlayer INVOKE")
    Log.d("BACK_TRACE", "STREAMNOVAVM: valore di _screenState prima: ${_screenState.value}")
    // Salva il progresso PRIMA di azzerare lo stato, usando il pacchetto atomico della
    // sessione corrente (mai variabili globali mutevoli).
    val current = _playbackState.value
    // Contenuto e episodio DELLA SESSIONE APPENA CHIUSA: servono a decidere la destinazione
    // del rientro e vanno letti PRIMA della pulizia della sessione.
    val playedMedia = current.media
    val playedSession = currentSession
    if (current.currentPositionMs > 0L) {
      saveCurrentPlaybackProgress(current.currentPositionMs)
    }

    // Rientro nella scheda Dettaglio (riproduzione avviata da una card di riga della Home): la
    // destinazione viene decisa QUI, con la sessione ancora integra, ma il player NON viene
    // smontato adesso. Resta composto — a tutto schermo, sopra la destinazione sottostante —
    // finché la scheda non è realmente composta a schermo; solo allora MainActivity conclude la
    // chiusura con [completePlayerClose]. La cornice che smonta il player ha quindi già la
    // scheda come destinazione: la Home non è visibile nemmeno per un frame.
    if (deferCloseForDetailReturn(playedMedia, playedSession)) {
      Log.d("BACK_TRACE", "STREAMNOVAVM: chiusura differita per il rientro nel Dettaglio")
      return
    }

    finishPlayerClose(playedMedia, playedSession)
    Log.d("BACK_TRACE", "STREAMNOVAVM: valore di _screenState dopo: ${_screenState.value}")
    Log.i(HTTP_TRACE, "[VM] closePlayer COMPLETE")
    Log.d("BACK_TRACE", "STREAMNOVAVM: uscita da closePlayer()")
  }

  /**
   * Predispone il rientro nella scheda Dettaglio SENZA smontare il player.
   *
   * Condizioni del rientro nel Dettaglio (le stesse del percorso normale, valutate però con la
   * sessione ancora integra): riproduzione avviata da una card di riga della Home
   * ([savePlaybackFocusOrigin]) e provenienza BROWSING + sezione HOME.
   *
   * Se il rientro è quello, la richiesta viene pubblicata ([PlayerDetailReturn]): MainActivity
   * la traduce nella navigazione verso la scheda, che avviene sotto l'overlay del player (quindi
   * invisibile) e si conclude con [completePlayerClose] quando la scheda è composta. Finché la
   * scheda non è a schermo il player resta esattamente com'è: una seconda pressione di BACK
   * nello stesso istante non lo smonta due volte (la chiusura è già in corso).
   *
   * @return true se la chiusura è differita: la conclude [completePlayerClose].
   */
  private fun deferCloseForDetailReturn(
    playedMedia: MediaItem?,
    playedSession: PlaybackSession?
  ): Boolean {
    if (pendingDetailReturnClose != null) return true
    if (screenBeforePlayer != ScreenState.BROWSING) return false
    if (sectionBeforePlayer != SidebarSection.HOME) return false
    if (playbackOriginTarget == null) return false
    val detailMedia = playedMedia?.let { mediaWithPlayedEpisode(it, playedSession) } ?: return false
    playbackOriginTarget = null
    val request = PlayerDetailReturn(detailMedia)
    pendingDetailReturnClose = request
    _playerDetailReturn.value = request
    return true
  }

  /**
   * Conclude la chiusura differita del player ([deferCloseForDetailReturn]).
   *
   * Va invocata quando la scheda Dettaglio è realmente composta a schermo — dietro l'overlay del
   * player, che la copre per intero (vedi MainActivity). Da quel momento la destinazione corrente
   * è la scheda, quindi smontare il player non scopre mai la Home: la cornice che lo sostituisce
   * disegna il contenuto riprodotto, già preparato qui (media selezionato, stagione ed episodio
   * appena visti), senza stati intermedi visibili.
   */
  fun completePlayerClose() {
    val request = pendingDetailReturnClose ?: return
    pendingDetailReturnClose = null
    // Contenuto e sessione sono ancora integri: la chiusura vera e propria avviene ora.
    finishPlayerClose(_playbackState.value.media, currentSession)
    Log.d("BACK_TRACE", "STREAMNOVAVM: completePlayerClose, screenState=${_screenState.value}")
    val detailMedia = request.media
    if (isLiveChannel(detailMedia.id)) openLiveChannel(detailMedia) else openDetail(detailMedia)
  }

  /**
   * Smontaggio del player e ripristino della schermata di provenienza.
   *
   * È il corpo storico di [closePlayer] (sessione, stream, dialoghi, sezione di partenza e
   * destinazione del rientro): vale sia per la chiusura immediata sia per la conclusione della
   * chiusura differita, dove la richiesta di rientro è già stata pubblicata e consumata
   * ([publishPlayerReturnTarget] non ripubblica nulla).
   */
  private fun finishPlayerClose(playedMedia: MediaItem?, playedSession: PlaybackSession?) {
    // Pulizia totale della sessione: la prossima riproduzione parte da tabula rasa.
    currentMediaFetchJob?.cancel()
    currentMediaFetchJob = null
    resetStreamState()
    detachPlayer()
    currentSession = null
    currentlyUnlockedMediaId = null
    _showSourceDialog.value = false
    _sourceDialogMedia.value = null
    _playbackState.value = _playbackState.value.copy(
      streamUrl = null,
      streamHeaders = emptyMap(),
      streamQuality = null,
      streamServer = null,
      streamProgressive = null,
      subtitles = emptyList(),
      isReusedStream = false,
      currentSource = null,
      subtitleOffsetMs = 0L,
      isPlaying = false
    )
    _onlineSubtitles.value = emptyList()
    _availableSources.value = emptyList()
    // Sessione chiusa: la lista dei candidati del selettore non e' piu' valida.
    _sourceCandidates.value = emptyList()
    // Fine del ciclo di riproduzione: anche lo storico delle sorgenti tentate
    // (fallback automatico) viene azzerato.
    attemptedSourceKeys.clear()
    manualSourceSelectionInFlight = false
    _showSourceSelector.value = false
    val origin = screenBeforePlayer
    val previousSection = sectionBeforePlayer
    screenBeforePlayer = null
    sectionBeforePlayer = SidebarSection.HOME
    _currentSection.value = previousSection
    // La sezione di partenza potrebbe non esistere più (es. Addon dopo un cambio modalità)
    fallbackFromUnavailableSection()
    _screenState.value = origin
      ?: if (_selectedMedia.value != null) ScreenState.DETAIL else ScreenState.BROWSING
    // Destinazione del rientro: la provenienza della riproduzione decide dove si torna.
    publishPlayerReturnTarget(playedMedia, playedSession)
  }

  /**
   * Destinazione del rientro dal player.
   *
   * La provenienza ([playbackOriginTarget]) è consumata UNA SOLA VOLTA, QUALUNQUE sia la
   * destinazione di rientro: una provenienza vecchia non deve sopravvivere alla sessione di
   * riproduzione che l'ha generata.
   *
   * - Riproduzione avviata da una CARD DI RIGA (es. «Continua a guardare»): si rientra nella
   *   SCHEDA DETTAGLIO del contenuto appena riprodotto, con l'episodio appena visto come punto
   *   di ripresa ([mediaWithPlayedEpisode]). Il focus appartiene alla scheda, che lo porta
   *   sulla propria card di riproduzione del contenuto (protocollo della schermata: la
   *   richiesta si ripete finché il focus non viene confermato, quindi nessuna richiesta
   *   prematura). Nessun target per la Home viene pubblicato: il focus non può più tornare
   *   sulla card di «Continua a guardare».
   * - Ogni altro ingresso al player (scheda Dettaglio, Provider, …): comportamento invariato —
   *   il rientro è della schermata che ha aperto il player e il focus resta dove si trova.
   */
  private fun publishPlayerReturnTarget(playedMedia: MediaItem?, playedSession: PlaybackSession?) {
    val origin = playbackOriginTarget
    playbackOriginTarget = null
    // Il contenuto della Home è l'unica destinazione in cui la provenienza da una card di riga
    // conta: gli altri rientri (Dettaglio/Provider/sezioni) restano quelli di sempre.
    if (_screenState.value != ScreenState.BROWSING || _currentSection.value != SidebarSection.HOME) return
    if (origin == null) return
    val detailMedia = playedMedia?.let { mediaWithPlayedEpisode(it, playedSession) }
    if (detailMedia == null) {
      // Nessuna sessione da riaprire (player chiuso prima che il contenuto fosse associato):
      // si conserva il rientro sulla card di origine invece di lasciare la Home senza
      // destinazione di focus.
      saveLastFocusedMedia(origin.first, origin.second)
      signalFocusRestore(origin.second)
      return
    }
    _playerDetailReturn.value = PlayerDetailReturn(detailMedia)
    // Destinazione preparata QUI (schermata + media + metadati): MainActivity deve solo
    // navigare il NavHost sulla scheda Dettaglio (vedi [PlayerDetailReturn]). Un canale LIVE
    // usa la scheda del canale (nessuna stagione/episodio da selezionare), come al click di
    // una card canale da qualunque altra schermata.
    if (isLiveChannel(detailMedia.id)) openLiveChannel(detailMedia) else openDetail(detailMedia)
  }

  /**
   * Media da aprire nella scheda Dettaglio al rientro dal player.
   *
   * L'identità è quella del contenuto EFFETTIVAMENTE riprodotto ([media], lo stesso oggetto
   * che il player ha in sessione): nessuna ricerca per titolo fra le schede del catalogo, dove
   * lo stesso film o serie può comparire più volte con identificatori differenti (scheda TMDB
   * e scheda IMDb/Stremio). L'episodio appena visto ([session]) diventa il punto di ripresa
   * della scheda: nel player si può essere passati all'episodio successivo, quindi la stagione
   * e la puntata da riprendere sono quelle della sessione, non quelle della card di partenza.
   */
  private fun mediaWithPlayedEpisode(media: MediaItem, session: PlaybackSession?): MediaItem {
    val season = session?.seasonNumber
    val episode = session?.episodeNumber
    return if (session?.isSeries == true && season != null && episode != null) {
      media.copy(lastWatchedSeason = season, lastWatchedEpisode = episode)
    } else {
      media
    }
  }

  /** True se il Player è stato aperto partendo dal Dettaglio (catalogo -> Detail -> Player). */
  fun playerOpenedFromDetail(): Boolean = screenBeforePlayer == ScreenState.DETAIL

fun backToBrowsing() {
    detailMediaStack.clear()
    _chainRestoreMediaId.value = null
    resetStreamState()
    _selectedProvider.value = null
    _selectedMedia.value = null
    _screenState.value = ScreenState.BROWSING
  }

/**
   * Gestione gerarchica del ritorno da una scheda di dettaglio.
   * Se lo stack dei dettagli contiene più elementi, risale al media precedente
   * ripristinandone immediatamente lo stato senza mostrare schermate nere.
   * Se lo stack dei dettagli è esaurito, ritorna alla schermata precedente (Home/Provider)
   * e restituisce false.
   */
  fun popDetail(): Boolean {
    resetStreamState()
    selectSeasonJob?.cancel()
    selectSeasonJob = null
    _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
    if (detailMediaStack.size > 1) {
      val closedMedia = detailMediaStack.removeLastOrNull()
      val previousMedia = detailMediaStack.last()
      // Al ritorno, il focus deve puntare alla card appena aperta e poi chiusa.
      _chainRestoreMediaId.value = closedMedia?.id ?: previousMedia.id
      _selectedMedia.value = previousMedia
      _screenState.value = ScreenState.DETAIL
      val isTv = previousMedia.type == MediaType.SERIE_TV
      val currentTmdbId = previousMedia.tmdbId
        ?: previousMedia.id.removePrefix("tmdb_tv_").removePrefix("tmdb_movie_").toIntOrNull()
      if (currentTmdbId != null && currentTmdbId > 0) {
        val mediaWithTmdb = if (previousMedia.tmdbId == null) previousMedia.copy(tmdbId = currentTmdbId) else previousMedia
        _selectedMedia.value = mediaWithTmdb
        loadDetails(currentTmdbId, isTv, mediaWithTmdb)
        if (isTv) {
          val initialSeason = mediaWithTmdb.lastWatchedSeason ?: 1
          selectSeason(currentTmdbId, initialSeason, mediaWithTmdb)
        }
      } else if (previousMedia.id.startsWith("tt", ignoreCase = true)) {
        val tmdb = previousMedia.tmdbId
        if (tmdb != null && tmdb > 0) {
          loadDetails(tmdb, isTv, previousMedia)
        }
      }
      return true
    } else {
      detailMediaStack.clear()
      // La catena è terminata: non lasciare un target di focus pendente.
      _chainRestoreMediaId.value = null
      backFromDetail()
      return false
    }
  }

  fun backFromDetail() {
    detailMediaStack.clear()
    _chainRestoreMediaId.value = null
    resetStreamState()
    selectSeasonJob?.cancel()
    selectSeasonJob = null
    _seasonEpisodesUiState.value = SeasonEpisodesUiState.Idle
    _selectedMedia.value = null
    if (_selectedProvider.value != null) {
      _screenState.value = ScreenState.PROVIDER
    } else {
      _screenState.value = ScreenState.BROWSING
      Log.d("BACK_TRACE", "STREAMNOVAVM: modifica _screenState a ${_screenState.value}")
    }
  }

  // Paginazione Remota Cataloghi Provider (Netflix, Prime Video, Disney+, Apple TV+, HBO Max)
  //
  // Lo stato è INDEPENDENTE per coppia (providerId, tipo Film/Serie): non esiste
  // alcun flag globale "hasMore", quindi la paginazione di Netflix Film non può
  // bloccare Netflix Serie TV, né tantomeno gli altri provider.
  private val _currentProviderPage = MutableStateFlow(1)
  val currentProviderPageFlow: StateFlow<Int> = _currentProviderPage.asStateFlow()
  var currentProviderPage: Int
    get() = _currentProviderPage.value
    private set(value) {
      _currentProviderPage.value = value
    }

  private val _isProviderLoading = MutableStateFlow(false)
  val isProviderLoading: StateFlow<Boolean> = _isProviderLoading.asStateFlow()

  /** Stato "ci sono altre pagine" per OGNI coppia (providerId, tipo). */
  private val _providerHasMorePages = MutableStateFlow<Map<Pair<Int, Boolean>, Boolean>>(emptyMap())
  val providerHasMorePages: StateFlow<Map<Pair<Int, Boolean>, Boolean>> = _providerHasMorePages.asStateFlow()

  /** Ultima pagina caricata in sessione per coppia (providerId, tipo): 0 = nessuna. */
  private val providerPages = mutableMapOf<Pair<Int, Boolean>, Int>()
  private val loadingProviderKeys = mutableSetOf<Pair<Int, Boolean>>()

  // ── Paginazione Stremio (`skip`) dei cataloghi provider ───────────────────────
  // Stati SEPARATI dalla paginazione TMDB: su una copertura PARTIAL le due
  // sorgenti devono potersi esaurire in modo indipendente (il catalogo Stremio può
  // terminare mentre TMDB ha ancora pagine, e viceversa).

  /** true se la sorgente Stremio di UNA coppia (providerId, tipo) ha ancora pagine. */
  private val stremioHasMorePages = mutableMapOf<Pair<Int, Boolean>, Boolean>()

  /** Coppie (providerId, tipo) con fetch Stremio `skip` in corso -> token anti duplicati. */
  private val stremioLoadingKeys = mutableMapOf<Pair<Int, Boolean>, Any>()

  /** Job della paginazione Stremio per coppia: annullato all'apertura del provider. */
  private val stremioPagingJobs = mutableMapOf<Pair<Int, Boolean>, Job>()

  /**
   * Caricamento iniziale del catalogo Stremio del provider aperto. La paginazione lo
   * attende: senza questo ordinamento la pagina successiva, lanciata subito dopo
   * [openProvider], puo' completare per prima e sovrascrivere la prima pagina.
   */
  private var providerInitialLoad: Job? = null

  /** Aggiorna lo spinner "caricamento in corso" considerando entrambe le sorgenti. */
  private fun updateProviderLoadingState() {
    _isProviderLoading.value = loadingProviderKeys.isNotEmpty() || stremioLoadingKeys.isNotEmpty()
  }

  /** Interrogazione puntuale dello stato hasMore della singola coppia (provider, tipo). */
  fun hasMoreProviderPages(providerId: Int, isTv: Boolean): Boolean =
    _providerHasMorePages.value[Pair(providerId, isTv)] ?: true

  /**
   * Interrogazione puntuale del hasMore STREMIO della coppia (provider, tipo).
   * Resta `true` finché l'ultima risposta ha consegnato titoli nuovi: un catalogo
   * che risponde con esattamente una pagina piena (20 item) NON è esaurito.
   */
  fun hasMoreStremioProviderPages(providerId: Int, isTv: Boolean): Boolean =
    stremioHasMorePages[Pair(providerId, isTv)] ?: true

  /** Reset della paginazione di UN provider (entrambi i tipi, indipendentemente dagli altri). */
  fun resetProviderPagination(providerId: Int) {
    listOf(true, false).forEach { isTv ->
      val key = Pair(providerId, isTv)
      stremioPagingJobs.remove(key)?.cancel()
      stremioLoadingKeys.remove(key)
      stremioHasMorePages.remove(key)
      providerPages.remove(key)
      _providerHasMorePages.update { it - key }
    }
    updateProviderLoadingState()
  }

  /**
   * Carica la pagina successiva del provider per il tipo richiesto.
   *
   * Due sorgenti, ognuna con il SUO stato:
   *  - Stremio: extra `skip` sui cataloghi confermati (una fetch per tipo alla volta,
   *    protetta da [stremioLoadingKeys] per evitare richieste duplicate concorrenti);
   *  - TMDB: paginazione nativa, usata come completamento quando la copertura è
   *    PARTIAL (o come fonte unica quando è NONE).
   */
  fun loadNextProviderPage(providerId: Int, isTv: Boolean) {
    val key = Pair(providerId, isTv)
    val currentProvider = _selectedProvider.value
    val coverage: ProviderCoverage? = if (currentProvider != null && currentProvider.tmdbProviderId == providerId) {
      _stremioProviderStates.value[currentProvider.id]?.coverage
        ?: StremioCatalogRepository.providerCoverage(currentProvider)
    } else {
      null
    }

    if (currentProvider != null && coverage != null && coverage != ProviderCoverage.NONE) {
      val provider = currentProvider
      val activeCoverage = coverage
      if (hasMoreStremioProviderPages(providerId, isTv) && !stremioLoadingKeys.containsKey(key)) {
        val token = Any()
        stremioLoadingKeys[key] = token
        updateProviderLoadingState()
        val job = viewModelScope.launch {
          try {
            // Attende la prima pagina del provider: la paginazione non deve sovrascriverla.
            providerInitialLoad?.join()
            if (_selectedProvider.value?.id != provider.id) return@launch
            if (!hasMoreStremioProviderPages(providerId, isTv)) return@launch

            val mediaType = if (isTv) MediaType.SERIE_TV else MediaType.FILM
            val more = StremioCatalogRepository.paginateProviderCatalog(provider, mediaType)
            if (_selectedProvider.value?.id != provider.id) return@launch

            // Read-modify-write ATOMICO: Film e Serie paginano in parallelo sulla stessa
            // entry di mappa, un read prima del fetch perderebbe l'aggiornamento.
            // L'ordine viene riassestato in modo deterministico (Film poi Serie, stabile):
            // l'arrivo sfasato delle due pagine non deve rimescolare le posizioni a schermo.
            var newTotal = -1
            if (more.isNotEmpty()) {
              _stremioProviderMedia.update { map ->
                val current = map[provider.id].orEmpty()
                val combined = StremioCatalogRepository.stabilizeProviderOrder(
                  StremioCatalogRepository.deduplicateCrossSource(current + more)
                )
                if (combined.size > current.size) {
                  newTotal = combined.size
                  // La cache congela l'ordine anche sulle pagine successive: le riaperture
                  // emettono esattamente questa sequenza, senza rimescolamenti.
                  providerCatalogCache[provider.id] = combined
                  map + (provider.id to combined)
                } else {
                  map
                }
              }
            }

            if (newTotal > 0) {
              _stremioProviderStates.update { map ->
                val base = map[provider.id] ?: StremioProviderCatalogState()
                map + (provider.id to base.copy(
                  hasConfirmedBindings = true,
                  itemCount = newTotal,
                  coverage = activeCoverage
                ))
              }
              stremioHasMorePages[key] = true
            } else {
              // Nessun titolo nuovo: pagina vuota oppure l'addon ignora `skip` e
              // riconsegna duplicati. È l'UNICO punto che ferma la paginazione:
              // una risposta piena (20 item) con titoli nuovi NON azzera hasMore.
              stremioHasMorePages[key] = false
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Errore paginazione provider Stremio: ${e.message}")
          } finally {
            // Il token evita che il finally di un job annullato (riapertura del
            // provider) rilasci il lock della NUOVA richiesta partita dopo il reset.
            if (stremioLoadingKeys[key] === token) {
              stremioLoadingKeys.remove(key)
              updateProviderLoadingState()
            }
          }
        }
        stremioPagingJobs[key] = job
      }

      // FULL: solo Stremio (return). PARTIAL: nessun return anticipato, la
      // paginazione TMDB prosegue sotto come completamento del catalogo.
      if (coverage == ProviderCoverage.FULL) return
    }

    if (loadingProviderKeys.contains(key)) return
    if (!hasMoreProviderPages(providerId, isTv)) return
    loadingProviderKeys.add(key)
    updateProviderLoadingState()

    viewModelScope.launch {
      try {
        val loadedPage = providerPages[key] ?: 0
        val nextPage = if (loadedPage > 0) {
          loadedPage + 1
        } else {
          // Ripresa dalla progressione persistita (Room) di QUESTA coppia
          val persisted = MediaRepository.getProviderPaginationState(providerId, isTv)
          if (!persisted.hasMore) {
            _providerHasMorePages.update { it + (key to false) }
            return@launch
          }
          persisted.nextPage
        }
        if (nextPage > MediaRepository.MAX_PROVIDER_PAGES) {
          _providerHasMorePages.update { it + (key to false) }
          return@launch
        }

        MediaRepository.loadProviderCatalog(providerId, isTv, nextPage).collect { page ->
          Log.d(
            "PROVIDER_CATALOG",
            "results provider=$providerId isTv=$isTv page=${page.page} -> " +
              "${page.items.size} titoli (total_pages=${page.totalPages})"
          )
          providerPages[key] = page.page
          _providerHasMorePages.update { it + (key to page.hasMore) }
          if (_selectedProvider.value?.tmdbProviderId == providerId) {
            currentProviderPage = page.page
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Errore caricamento pagina provider $providerId (isTv=$isTv): ${e.message}")
      } finally {
        loadingProviderKeys.remove(key)
        updateProviderLoadingState()
      }
    }
  }

  fun openProvider(provider: StreamingProvider) {
    _selectedProvider.value = provider
    _screenState.value = ScreenState.PROVIDER
    resetProviderPagination(provider.tmdbProviderId)

    val cachedCatalog = providerCatalogCache[provider.id]
    if (!cachedCatalog.isNullOrEmpty()) {
      // CACHE HIT: emetti SUBITO la lista salvata, con l'ORDINE congelato, e NON lanciare
      // una nuova richiesta di rete che rimescoli i poster. La paginazione Stremio NON viene
      // resettata: l'offset riparte da dove era rimasto, quindi le pagine successive si
      // ACCODANO alla lista congelata invece di ricaricare (e rimescolare) la prima pagina.
      Log.d(
        "PROVIDER_CATALOG",
        "Cache hit provider=${provider.id}: ${cachedCatalog.size} titoli (nessuna fetch)"
      )
      _stremioProviderMedia.update { it + (provider.id to cachedCatalog) }
      _stremioProviderStates.update { states ->
        if (states.containsKey(provider.id)) {
          states
        } else {
          states + (provider.id to StremioProviderCatalogState(
            coverage = StremioCatalogRepository.providerCoverage(provider),
            itemCount = cachedCatalog.size
          ))
        }
      }
      providerInitialLoad = null
    } else {
      Log.d("PROVIDER_CATALOG", "Fetching catalog for provider=${provider.name} (tmdbId=${provider.tmdbProviderId})")
      // Solo al cache miss ogni apertura riparte dalla prima pagina di ogni catalogo Stremio.
      StremioCatalogRepository.resetProviderPaging()

      val initialLoad = viewModelScope.launch {
        try {
          // ESCLUSIONE RADICALE di Cinemeta: con addon di catalogo personalizzati attivi
          // (Xperience / Easy Catalog / ...) nessuna riga Cinemeta resta nella pagina del
          // provider, che deve derivare al 100% dai cataloghi degli addon attivi.
          val page = StremioCatalogRepository.sanitizeProviderCatalogPage(
            StremioCatalogRepository.loadProviderCatalogForProvider(provider)
          )
          val planned = StremioCatalogRepository.providerCoverage(provider)
          // FULL vale SOLO se il fetch effettivo produce item: senza titoli la
          // copertura degrada a PARTIAL e il catalogo TMDB resta disponibile.
          val coverage = when {
            planned == ProviderCoverage.NONE -> ProviderCoverage.NONE
            page.items.isEmpty() -> ProviderCoverage.PARTIAL
            else -> planned
          }
          if (coverage != ProviderCoverage.NONE) {
            // Copertura Stremio attiva: Stremio è la fonte primaria del provider.
            // Ordine stabile (Film poi Serie) identico a ogni riapertura: la lista viene
            // congelata in cache e riedita identica alle riaperture successive.
            val stabilized = StremioCatalogRepository.stabilizeProviderOrder(page.items)
            providerCatalogCache[provider.id] = stabilized
            _stremioProviderMedia.update { it + (provider.id to stabilized) }
            _stremioProviderStates.update {
              it + (provider.id to StremioProviderCatalogState(
                hasConfirmedBindings = page.hasConfirmedBindings,
                itemCount = page.items.size,
                confirmedCatalogs = page.targets.size,
                coverage = coverage,
                fallbackToDefault = page.fallbackToDefault
              ))
            }
            Log.d(
              "PROVIDER_CATALOG",
              "Stremio provider=${provider.id}: coverage=$coverage, " +
                "${page.targets.size} cataloghi confermati, ${page.items.size} titoli"
            )
          } else {
            // Nessun binding confermato: si mantiene il catalogo nativo TMDB.
            providerCatalogCache.remove(provider.id)
            _stremioProviderMedia.update { it - provider.id }
            _stremioProviderStates.update { it - provider.id }
            Log.d(
              "PROVIDER_CATALOG",
              "Nessun binding Stremio confermato per ${provider.id}: coverage=NONE, usa catalogo TMDB"
            )
          }
        } catch (e: Exception) {
          Log.w(TAG, "Stremio provider catalog error: ${e.message}")
          // Fetch fallito con binding dichiarati: non si può considerare FULL,
          // la schermata deve poter completare con il catalogo TMDB.
          if (StremioCatalogRepository.providerCoverage(provider) != ProviderCoverage.NONE) {
            _stremioProviderStates.update {
              it + (provider.id to StremioProviderCatalogState(coverage = ProviderCoverage.PARTIAL))
            }
          }
        }
      }
      providerInitialLoad = initialLoad
      initialLoad.invokeOnCompletion { if (providerInitialLoad === initialLoad) providerInitialLoad = null }
    }

    // Crunchyroll (283): catalogo primariamente TV/anime -> prima le serie TV
    if (provider.tmdbProviderId == 283) {
      loadNextProviderPage(provider.tmdbProviderId, isTv = true)
      loadNextProviderPage(provider.tmdbProviderId, isTv = false)
    } else {
      loadNextProviderPage(provider.tmdbProviderId, isTv = false)
      loadNextProviderPage(provider.tmdbProviderId, isTv = true)
    }
  }

  fun ensureProviderLoaded(provider: StreamingProvider) {
    if (_selectedProvider.value?.id != provider.id) {
      openProvider(provider)
    }
  }

  fun closeProvider() {
    _selectedProvider.value = null
    _screenState.value = ScreenState.BROWSING
  }

   /**
    * Carica i contenuti di una sottocategoria provider per Film o Serie TV.
    * Se l'addon attivo non possiede il catalogo (es. Cinemeta), esegue il fallback
    * automatico su TMDB Discover (con logica HBO-specifica: network ID per serie,
    * watch_region=US per film).
    */
   fun loadCategoryProvider(providerId: String, tmdbProviderId: Int, isTv: Boolean) {
     val key: Pair<String, Boolean> = Pair(providerId, isTv)
     if (_categoryProviderMedia.value[key] != null || categoryProviderLoadingKeys[key] == true) {
       return
     }
     categoryProviderLoadingKeys[key] = true

     viewModelScope.launch {
       try {
         val stremioType = if (isTv) "series" else "movie"
         val hasStremio = StremioAddonRepository.hasProviderCatalog(providerId, stremioType)
         val provider = ProviderConstants.ALL.firstOrNull { it.id == providerId }

         if (hasStremio && provider != null) {
           val mediaType = if (isTv) MediaType.SERIE_TV else MediaType.FILM
           val stremioPage = StremioCatalogRepository.loadProviderCatalogPage(provider, mediaType)
           val coverage = StremioCatalogRepository.providerCoverage(provider)

           if (coverage == ProviderCoverage.FULL && stremioPage.items.isNotEmpty()) {
             _categoryProviderMedia.update { it + (key to stremioPage.items) }
             categoryProviderHasMore[key] = true
           } else {
             // Se copertura PARTIAL o catalogo Stremio parziale, completa con TMDB Discover
             var tmdbItems = emptyList<MediaItem>()
             var tmdbHasMore = false
             if (tmdbProviderId > 0) {
               val hboFallback = if (MediaRepository.isHboProvider(providerId)) {
                 if (isTv) {
                   // Serie TV HBO: usa network IDs (49|3186), senza watch_region
                   val items = MediaRepository.discoverTvByNetwork(MediaRepository.HBO_NETWORK_IDS, page = 1)
                   tmdbItems = items
                   tmdbHasMore = items.size > 0
                   true
                 } else {
                   // Film HBO: usa watch_region=US, provider IDs 384|1899|1825
                   val items = MediaRepository.discoverMoviesByProvider(
                     providerId = "384|1899|1825",
                     isTv = false,
                     page = 1,
                     watchRegion = "US"
                   )
                   tmdbItems = items
                   tmdbHasMore = items.size > 0
                   true
                 }
               } else {
                 false
               }
               if (!hboFallback) {
                 MediaRepository.loadProviderCatalog(tmdbProviderId, isTv, page = 1).collect { tmdbPage ->
                   tmdbItems = tmdbPage.items
                   tmdbHasMore = tmdbPage.hasMore
                   categoryProviderPages[key] = tmdbPage.page
                 }
               } else {
                 categoryProviderPages[key] = 1
               }
             }
             val combined = StremioCatalogRepository.deduplicateCrossSource(stremioPage.items + tmdbItems)
             _categoryProviderMedia.update { it + (key to combined) }
             categoryProviderHasMore[key] = tmdbHasMore || stremioPage.items.isNotEmpty()
           }
         } else if (tmdbProviderId > 0) {
           // Fallback TMDB Discover (Cinemeta o addon senza cataloghi provider)
           categoryProviderPages[key] = 1
           val hboFallback = if (MediaRepository.isHboProvider(providerId)) {
             if (isTv) {
               // Serie TV HBO: usa network IDs (49|3186), senza watch_region
               val items = MediaRepository.discoverTvByNetwork(MediaRepository.HBO_NETWORK_IDS, page = 1)
               _categoryProviderMedia.update { it + (key to items) }
               categoryProviderHasMore[key] = items.size > 0
               true
             } else {
               // Film HBO: usa watch_region=US, provider IDs 384|1899|1825
               val items = MediaRepository.discoverMoviesByProvider(
                 providerId = "384|1899|1825",
                 isTv = false,
                 page = 1,
                 watchRegion = "US"
               )
               _categoryProviderMedia.update { it + (key to items) }
               categoryProviderHasMore[key] = items.size > 0
               true
             }
           } else {
             false
           }
           if (!hboFallback) {
             MediaRepository.loadProviderCatalog(tmdbProviderId, isTv, page = 1).collect { tmdbPage ->
               _categoryProviderMedia.update { it + (key to tmdbPage.items) }
               categoryProviderHasMore[key] = tmdbPage.hasMore
               categoryProviderPages[key] = tmdbPage.page
             }
           }
         }
       } catch (e: Exception) {
         Log.w(TAG, "Errore caricamento categoria provider ($providerId, isTv=$isTv): ${e.message}")
       } finally {
         categoryProviderLoadingKeys.remove(key)
       }
     }
   }

   /**
    * Paginazione della sottocategoria provider al raggiungimento della fine della griglia.
    */
   fun loadNextCategoryProviderPage(providerId: String, tmdbProviderId: Int, isTv: Boolean) {
     val key: Pair<String, Boolean> = Pair(providerId, isTv)
     if (categoryProviderLoadingKeys[key] == true || categoryProviderHasMore[key] == false) {
       return
     }
     categoryProviderLoadingKeys[key] = true

     viewModelScope.launch {
       try {
         val stremioType = if (isTv) "series" else "movie"
         val hasStremio = StremioAddonRepository.hasProviderCatalog(providerId, stremioType)
         val provider = ProviderConstants.ALL.firstOrNull { it.id == providerId }

         var gotMore = false
         if (hasStremio && provider != null) {
           val mediaType = if (isTv) MediaType.SERIE_TV else MediaType.FILM
           val more = StremioCatalogRepository.paginateProviderCatalog(provider, mediaType)
           if (more.isNotEmpty()) {
             _categoryProviderMedia.update { map ->
               val current = map[key].orEmpty()
               val combined = StremioCatalogRepository.deduplicateCrossSource(current + more)
               map + (key to combined)
             }
             gotMore = true
           }
         }

         // Se Stremio non ha consegnato nuovi elementi o se il catalogo e' supportato da TMDB Discover, pagina TMDB
         if (!gotMore && tmdbProviderId > 0) {
           val nextPage = (categoryProviderPages[key] ?: 1) + 1
           val hboFallback = if (MediaRepository.isHboProvider(providerId)) {
             if (isTv) {
               // Serie TV HBO: usa network IDs (49|3186), senza watch_region
               val items = MediaRepository.discoverTvByNetwork(MediaRepository.HBO_NETWORK_IDS, page = nextPage)
               if (items.isNotEmpty()) {
                 _categoryProviderMedia.update { map ->
                   val current = map[key].orEmpty()
                   val combined = StremioCatalogRepository.deduplicateCrossSource(current + items)
                   map + (key to combined)
                 }
               }
               categoryProviderHasMore[key] = items.size > 0
               true
             } else {
               // Film HBO: usa watch_region=US, provider IDs 384|1899|1825
               val items = MediaRepository.discoverMoviesByProvider(
                 providerId = "384|1899|1825",
                 isTv = false,
                 page = nextPage,
                 watchRegion = "US"
               )
               if (items.isNotEmpty()) {
                 _categoryProviderMedia.update { map ->
                   val current = map[key].orEmpty()
                   val combined = StremioCatalogRepository.deduplicateCrossSource(current + items)
                   map + (key to combined)
                 }
               }
               categoryProviderHasMore[key] = items.size > 0
               true
             }
           } else {
             false
           }
           if (!hboFallback) {
             MediaRepository.loadProviderCatalog(tmdbProviderId, isTv, nextPage).collect { tmdbPage ->
               categoryProviderPages[key] = tmdbPage.page
               categoryProviderHasMore[key] = tmdbPage.hasMore
               if (tmdbPage.items.isNotEmpty()) {
                 _categoryProviderMedia.update { map ->
                   val current = map[key].orEmpty()
                   val combined = StremioCatalogRepository.deduplicateCrossSource(current + tmdbPage.items)
                   map + (key to combined)
                 }
               }
             }
           }
         }
       } catch (e: Exception) {
         Log.w(TAG, "Errore paginazione categoria provider ($providerId, isTv=$isTv): ${e.message}")
       } finally {
         categoryProviderLoadingKeys.remove(key)
       }
     }
   }

  fun toggleFavorite(id: String) {
    MediaRepository.toggleFavorite(id)
    if (_selectedMedia.value?.id == id) {
      _selectedMedia.update { it?.copy(isFavorite = !it.isFavorite) }
    }
    // La schermata di selezione sorgenti mostra lo stesso titolo: il cuore deve
    // riflettere il nuovo stato anche li', senza ricaricare nulla.
    syncSourceDialogMedia { current ->
      if (current.id == id) {
        current.copy(isFavorite = MediaRepository.getMediaById(id)?.isFavorite ?: !current.isFavorite)
      } else {
        current
      }
    }
  }

  /**
   * Alterna lo stato "visto / non visto" del titolo.
   *
   * "Visto" = progresso >= 90% della durata totale ([MediaItem.isWatched]): il toggle
   * porta il progresso da totale (visto) a zero (non visto), aggiornando in modo
   * coerente lista catalogo, dettaglio corrente e target della selezione sorgenti.
   */
  fun toggleWatched(media: MediaItem) {
    val current = MediaRepository.getMediaById(media.id) ?: media
    val targetProgress = if (current.isWatched) 0L else current.totalDurationMs.coerceAtLeast(1L)
    MediaRepository.updateProgress(current.id, targetProgress)
    if (_selectedMedia.value?.id == current.id) {
      _selectedMedia.update { it?.copy(currentProgressMs = targetProgress) }
    }
    syncSourceDialogMedia { target ->
      if (target.id == current.id) target.copy(currentProgressMs = targetProgress) else target
    }
  }

  /**
   * Avvia la riproduzione del trailer **dentro** StreamNova: recupera la chiave YouTube
   * da TMDB (`videos.results`, priorita' `type == "Trailer"` in italiano, poi inglese) e
   * passa lo stato a [TrailerPlaybackState.Playing]. Nessun Intent esterno a YouTube.
   */
  fun playTrailer(media: MediaItem?) {
    if (media == null) {
      _trailerState.value = TrailerPlaybackState.Error(TRAILER_UNAVAILABLE_MESSAGE)
      return
    }
    loadTrailerJob?.cancel()
    _trailerState.value = TrailerPlaybackState.Loading
    loadTrailerJob = viewModelScope.launch {
      val isTv = media.type == MediaType.SERIE_TV
      val directId = media.tmdbId ?: 0
      // Titoli catalogati solo con IMDb (es. Cinemeta): si risolve in TMDB prima di chiedere i video.
      val resolvedId = if (directId > 0) {
        directId
      } else {
        MediaRepository.resolveImdbToTmdbId(media.effectiveImdbId, isTv)
      }
      val key = resolvedId?.takeIf { it > 0 }?.let { MediaRepository.fetchTrailerKey(it, isTv) }
      _trailerState.value = if (key.isNullOrBlank()) {
        TrailerPlaybackState.Error(TRAILER_UNAVAILABLE_MESSAGE)
      } else {
        TrailerPlaybackState.Playing(key)
      }
    }
  }

  /** Chiude il trailer interno e azzera lo stato (BACK o pulsante chiudi). */
  fun closeTrailer() {
    loadTrailerJob?.cancel()
    loadTrailerJob = null
    _trailerState.value = TrailerPlaybackState.Idle
  }

  /** Applica [transform] al media del target di selezione sorgenti, se presente. */
  private fun syncSourceDialogMedia(transform: (MediaItem) -> MediaItem) {
    val target = _sourceDialogMedia.value ?: return
    val updated = transform(target.media)
    if (updated != target.media) {
      _sourceDialogMedia.value = target.copy(media = updated)
    }
  }

  fun removeFromContinueWatching(mediaId: String) {
    Log.d("REMOVE_CW", "ViewModel.removeFromContinueWatching called with mediaId=$mediaId")
    viewModelScope.launch {
      MediaRepository.removeFromContinueWatching(mediaId)
      Log.d("REMOVE_CW", "ViewModel: MediaRepository.removeFromContinueWatching completed")
    }
  }

  fun updateSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun setControlsVisible(visible: Boolean) {
    _playbackState.update { it.copy(areControlsVisible = visible) }
  }

  fun togglePlayPause() {
    _playbackState.update { it.copy(isPlaying = !it.isPlaying) }
  }

  fun updatePlaybackPosition(positionMs: Long, durationMs: Long, bufferedMs: Long) {
    _playbackState.update {
      it.copy(
        currentPositionMs = positionMs,
        durationMs = if (durationMs > 0) durationMs else it.durationMs,
        bufferedPositionMs = bufferedMs
      )
    }
  }

  fun seekBy(deltaSeconds: Int) {
    _playbackState.update {
      val newPos = (it.currentPositionMs + deltaSeconds * 1000L).coerceIn(0L, it.durationMs)
      it.copy(currentPositionMs = newPos)
    }
  }

  fun setAudioTrack(track: AudioTrack) {
    _playbackState.update { it.copy(selectedAudio = track) }
  }

  fun setSubtitleTrack(track: SubtitleTrack) {
    _playbackState.update { it.copy(selectedSubtitle = track) }
  }

  fun setResolution(res: VideoResolution) {
    _playbackState.update { it.copy(selectedResolution = res) }
  }
  fun setSubtitleOffsetMs(offsetMs: Long) {
    val clamped = offsetMs.coerceIn(-10_000L, 10_000L)
    _playbackState.update { it.copy(subtitleOffsetMs = clamped) }
  }

  fun adjustSubtitleOffsetMs(deltaMs: Long) {
    val current = _playbackState.value.subtitleOffsetMs
    setSubtitleOffsetMs(current + deltaMs)
  }

  fun resetSubtitleOffsetMs() {
    setSubtitleOffsetMs(0L)
  }

  /**
   * Carica i sottotitoli online per il contenuto in riproduzione, interrogando ENTRAMBE
   * le integrazioni esistenti e unendo i risultati:
   * 1. API REST OpenSubtitles v3 (`OpenSubtitlesApi`): identifica il contenuto con
   *    IMDb/TMDB quando attendibili, altrimenti con titolo + anno; type corretto per
   *    film/serie; lingua preferita dell'utente inclusa. I risultati SENZA URL diretto
   *    restano in lista con `file_id` e vengono risolti solo alla selezione, così nessun
   *    risultato valido viene scartato né consuma quota download per errore.
   * 2. Addon Stremio OpenSubtitles v3 (fallback storico): mantiene copertura per gli
   *    identificativi che l'addon risolve e per i casi in cui l'API REST non risponde.
   *
   * Ricalcolo pulito: una nuova apertura della sezione annulla la ricerca precedente
   * (nessuna richiesta "fantasma" può sovrascrivere lo stato) e azzera il download in corso.
   */
  fun loadOnlineSubtitles(
    mediaItem: MediaItem? = _playbackState.value.media,
    episode: Episode? = _playbackState.value.currentEpisode
  ) {
    val media = mediaItem ?: return
    // Nuova ricerca: annulla quella in corso per questo pannello (evita sovrascritture
    // tardive) e resetta lo stato del download del sottotitolo selezionato.
    onlineSubtitlesSearchJob?.cancel()
    onlineSubtitlesSearchJob = viewModelScope.launch(ioDispatcher) {
      val job = coroutineContext[Job]
      _isOnlineSubtitlesLoading.value = true
      _onlineSubtitleDownload.value = OnlineSubtitleDownloadState.Idle
      try {
        val preferredLanguage = SettingsRepository.settings.value.preferredSubtitleLanguage
        // L'addon risponde in parallelo: la ricerca ufficiale è la via principale.
        val addonDeferred = async(ioDispatcher) {
          runCatching { openSubtitlesProvider.fetchFromStremioOpenSubtitles(media, episode) }
            .getOrDefault(emptyList())
        }
        val official = try {
          openSubtitlesProvider.fetchSubtitles(
            mediaItem = media,
            episode = episode,
            preferredLanguage = preferredLanguage,
            resolveLinksOnDemand = false
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w("StreamNovaVM", "Errore ricerca ufficiale OpenSubtitles: ${e.message}")
          emptyList()
        }
        val addon = addonDeferred.await()

        val merged = mergeOnlineSubtitles(official = official, addon = addon)
        _onlineSubtitles.value = merged
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w("StreamNovaVM", "Errore caricamento sottotitoli online: ${e.message}")
    _onlineSubtitles.value = emptyList()
    onlineSubtitlesSearchJob?.cancel()
    onlineSubtitleDownloadJob?.cancel()
    onlineSubtitlesSearchJob = null
    onlineSubtitleDownloadJob = null
    lastSelectedOnlineSubtitle = null
    _onlineSubtitleDownload.value = OnlineSubtitleDownloadState.Idle
      } finally {
        // Reimposta lo spinner solo se questo job è ancora quello attivo: una nuova
        // ricerca potrebbe essere già ripartita e avere lo stato a true per sé stessa.
        if (onlineSubtitlesSearchJob === job) {
          _isOnlineSubtitlesLoading.value = false
        }
      }
    }
  }

  /**
   * Unisce risultati ufficiali e addon, eliminando i duplicati (stesso URL, oppure lo
   * stesso file_id per risultati privi di URL) e ordinandoli per priorità linguistica
   * (italiano, inglese, altre in ordine alfabetico).
   */
  private fun mergeOnlineSubtitles(
    official: List<Subtitle>,
    addon: List<Subtitle>
  ): List<Subtitle> {
    val seen = HashSet<String>()
    val merged = ArrayList<Subtitle>(official.size + addon.size)
    for (sub in official + addon) {
      val key = when {
        sub.url.isNotBlank() -> "url:${sub.url.lowercase()}"
        sub.fileId != null -> "fid:${sub.fileId}:${sub.lang}"
        else -> continue
      }
      if (seen.add(key)) merged.add(sub)
    }
    return OpenSubtitlesSubtitleProvider.sortSubtitlesByPriority(merged)
  }

  /**
   * Selezione di un sottotitolo nella sezione "Sottotitoli Online": scarica il file sul
   * disco (risolvendo `file_id` → URL temporaneo quando serve, gestendo ZIP/GZIP) e
   * pubblica lo stato in [onlineSubtitleDownload]. PlayerScreen reagisce a [Ready]
   * allegando il FILE LOCALE al player (mai gli URL temporanei remoti).
   */
  fun selectOnlineSubtitle(subtitle: Subtitle) {
    if (subtitle.fileId == null && subtitle.url.isBlank()) return
    if (_onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Downloading) return
    lastSelectedOnlineSubtitle = subtitle
    performOnlineSubtitleDownload(subtitle)
  }

  /** Riprova il download dell'ultimo sottotitolo selezionato (feedback errore). */
  fun retryOnlineSubtitleDownload() {
    if (_onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Downloading) return
    lastSelectedOnlineSubtitle?.let { performOnlineSubtitleDownload(it) }
  }

  /** Azzeramento dello stato di download (usato al ritorno Ready applicato dal player). */
  fun clearOnlineSubtitleDownloadState() {
    if (_onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Downloading) return
    _onlineSubtitleDownload.value = OnlineSubtitleDownloadState.Idle
  }

  private fun performOnlineSubtitleDownload(subtitle: Subtitle) {
    onlineSubtitleDownloadJob?.cancel()
    _onlineSubtitleDownload.value = OnlineSubtitleDownloadState.Downloading
    onlineSubtitleDownloadJob = viewModelScope.launch(ioDispatcher) {
      val cacheDir = onlineSubtitleCacheDirProvider()
      if (cacheDir == null) {
        Log.w("StreamNovaVM", "Cache directory non disponibile: download sottotitolo annullato")
        _onlineSubtitleDownload.value = OnlineSubtitleDownloadState.Error(
          OpenSubtitlesDownloadError.INVALID_FILE
        )
        return@launch
      }
      try {
        val file = onlineSubtitleDownloader.download(subtitle, cacheDir)
          .getOrThrow()
        // Il player riceve IL FILE LOCALE come traccia esterna: nessuna dipendenza da URL
        // temporanei dopo il download. Formato "file://" compatibile con Uri.parse del
        // PlayerSubtitleMediaItemBuilder (senza dipendere da android.net.Uri qui, così
        // resta verificabile in unit test JVM).
        val localSubtitle = subtitle.copy(url = "file://${file.absolutePath}")
        injectOnlineSubtitle(localSubtitle)
        _onlineSubtitleDownload.value = OnlineSubtitleDownloadState.Ready(localSubtitle)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val mapped = mapOnlineSubtitleError(e)
        Log.w("StreamNovaVM", "Download sottotitolo fallito ($mapped): ${e.message}")
        _onlineSubtitleDownload.value = OnlineSubtitleDownloadState.Error(mapped)
      }
    }
  }

  private fun mapOnlineSubtitleError(cause: Throwable): OpenSubtitlesDownloadError {
    return when (cause) {
      is com.example.data.opensubtitles.OpenSubtitlesDownloadException -> cause.error
      is SocketTimeoutException -> OpenSubtitlesDownloadError.TIMEOUT
      is java.io.IOException -> OpenSubtitlesDownloadError.NETWORK
      else -> OpenSubtitlesDownloadError.NETWORK
    }
  }

  fun injectOnlineSubtitle(subtitle: Subtitle) {
    _playbackState.update { current ->
      val updated = (current.subtitles + subtitle).distinctBy { it.id.ifBlank { it.url } }
      current.copy(subtitles = updated)
    }
  }

  // ===========================================================================
  // SCRUBBING OTTIMIZZATO, SEEK VIRTUALE E NAVIGAZIONE CAPITOLI
  // ===========================================================================

  fun attachPlayer(player: Player?) {
    this.attachedPlayer = player
    if (player != null) {
      updateChapters(player)
    } else {
      _chapters.value = emptyList()
    }
  }

  fun detachPlayer() {
    this.attachedPlayer = null
    _chapters.value = emptyList()
    cancelScrub()
  }

  fun updateChapters(player: Player) {
    _chapters.value = PlayerChapterUtils.extractChapters(player)
  }

  fun setChapters(chaptersList: List<PlayerChapter>) {
    _chapters.value = chaptersList
  }

  /**
   * Avanzamento temporale con telecomando D-Pad RIGHT.
   */
  fun onSeekForward(
    player: Player? = attachedPlayer,
    isLive: Boolean = false
  ) {
    handleSeekStep(direction = 1, player = player, isLive = isLive)
  }

  /**
   * Arretramento temporale con telecomando D-Pad LEFT.
   */
  fun onSeekBackward(
    player: Player? = attachedPlayer,
    isLive: Boolean = false
  ) {
    handleSeekStep(direction = -1, player = player, isLive = isLive)
  }

  private fun handleSeekStep(
    direction: Int,
    player: Player?,
    isLive: Boolean
  ) {
    val p = player ?: attachedPlayer
    val duration = p?.duration ?: _playbackState.value.durationMs

    // Isolamento flussi live e durate non valide
    if (isLive || duration <= 0L || (p != null && !p.isCurrentMediaItemSeekable)) {
      return
    }

    val now = currentTimeProvider()
    if (now - lastSeekPressTimestampMs < 400L) {
      consecutiveSeekPressCount++
    } else {
      consecutiveSeekPressCount = 1
    }
    lastSeekPressTimestampMs = now

    // Accelerazione Adattiva (Step Curve):
    // Pressioni 1-2: 10s (10_000L)
    // Pressioni 3-5: 30s (30_000L)
    // Pressioni > 5 consecutive: 60s (60_000L)
    val stepSize = when {
      consecutiveSeekPressCount <= 2 -> 10_000L
      consecutiveSeekPressCount <= 5 -> 30_000L
      else -> 60_000L
    }
    _currentStepMs.value = stepSize

    if (!_isScrubbing.value || _uiSeekPosition.value == null) {
      scrubOriginPositionMs = (p?.currentPosition ?: _playbackState.value.currentPositionMs).coerceAtLeast(0L)
      _isScrubbing.value = true
    }

    val currentTarget = _uiSeekPosition.value ?: scrubOriginPositionMs
    val newTarget = (currentTarget + direction * stepSize).coerceIn(0L, duration)

    _uiSeekPosition.value = newTarget
    _seekStepDelta.value = newTarget - scrubOriginPositionMs

    // Debounce a 500ms prima di eseguire la seek reale su ExoPlayer
    scrubDebounceJob?.cancel()
    scrubDebounceJob = viewModelScope.launch {
      delay(500L)
      executeCommitSeek(player = p, targetPosition = newTarget)
    }
  }

  private fun executeCommitSeek(player: Player?, targetPosition: Long) {
    Log.i("SeekSync", "COMMIT_DEBOUNCE: targetPosition=${targetPosition}ms (SeekParameters.EXACT)")
    val p = player ?: attachedPlayer
    (p as? ExoPlayer)?.setSeekParameters(SeekParameters.EXACT)
    p?.seekTo(targetPosition)
    resetScrubbingState()
  }

  /**
   * Commit Immediato con D-Pad OK / Center:
   * Se l'utente preme il tasto centrale del D-Pad mentre isScrubbing == true:
   * 1. Cancella immediatamente il job del debounce.
   * 2. Chiama exoPlayer.seekTo(targetPosition).
   * 3. Resetta lo stato di scrubbing all'istante senza attendere il timeout.
   */
  fun commitSeekImmediately(player: Player? = attachedPlayer) {
    if (!_isScrubbing.value) return
    val target = _uiSeekPosition.value ?: return
    Log.i("SeekSync", "COMMIT_IMMEDIATE: targetPosition=${target}ms (SeekParameters.EXACT)")
    scrubDebounceJob?.cancel()
    scrubDebounceJob = null
    val p = player ?: attachedPlayer
    (p as? ExoPlayer)?.setSeekParameters(SeekParameters.EXACT)
    p?.seekTo(target)
    resetScrubbingState()
  }

  /**
   * Annulla la sessione di scrubbing (es. tasto BACK): nessuna seek reale eseguita.
   */
  fun cancelScrub() {
    resetScrubbingState()
  }

  private fun resetScrubbingState() {
    scrubDebounceJob?.cancel()
    scrubDebounceJob = null
    _isScrubbing.value = false
    _uiSeekPosition.value = null
    _seekStepDelta.value = 0L
    _currentStepMs.value = 10_000L
    consecutiveSeekPressCount = 0
    lastSeekPressTimestampMs = 0L
    scrubOriginPositionMs = 0L
  }

  /**
   * Navigazione Capitoli D-Pad UP:
   * Salta direttamente all'inizio del capitolo successivo ed esegue il seek immediato.
   */
  fun onChapterNext(player: Player? = attachedPlayer) {
    val p = player ?: attachedPlayer ?: return
    val chList = _chapters.value.ifEmpty { PlayerChapterUtils.extractChapters(p) }
    val nextTarget = PlayerChapterUtils.getNextChapterPosition(chList, p.currentPosition)
    if (nextTarget != null) {
      cancelScrub()
      p.seekTo(nextTarget)
    }
  }

  /**
   * Navigazione Capitoli D-Pad DOWN:
   * Salta all'inizio del capitolo corrente (o al precedente se ci si trova nei primi 3 secondi).
   */
  fun onChapterPrevious(player: Player? = attachedPlayer) {
    val p = player ?: attachedPlayer ?: return
    val chList = _chapters.value.ifEmpty { PlayerChapterUtils.extractChapters(p) }
    val prevTarget = PlayerChapterUtils.getPreviousOrCurrentChapterPosition(chList, p.currentPosition)
    if (prevTarget != null) {
      cancelScrub()
      p.seekTo(prevTarget)
    }
  }
}

