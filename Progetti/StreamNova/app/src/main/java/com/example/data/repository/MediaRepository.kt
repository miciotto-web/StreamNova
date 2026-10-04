package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.R
import com.example.data.api.TmdbApiClient
import com.example.data.api.TmdbMovieDto
import com.example.data.api.TmdbPaginatedResponse
import com.example.data.api.TmdbTvDto
import com.example.data.api.TmdbMultiSearchResultDto
import com.example.data.api.TmdbMovieDetailDto
import com.example.data.api.TmdbSeasonDetailDto
import com.example.data.api.TmdbTvDetailDto
import com.example.data.local.CachedMediaItemEntity
import com.example.data.local.CatalogProgressEntity
import com.example.data.local.EpisodeProgressEntity
import com.example.data.local.MediaCacheMapper
import com.example.data.local.MediaCacheMapper.toEntity
import com.example.data.local.MediaCacheMapper.toMediaItem
import com.example.data.local.StreamNovaDatabase
import com.example.data.local.TmdbResponseCacheEntity
import java.util.concurrent.ConcurrentHashMap
import com.example.data.model.AudioTrack
import com.example.data.model.CastMember
import com.example.data.model.CrewMember
import com.example.data.model.Episode
import com.example.data.model.EpisodeItem
import com.example.data.model.ExtendedMediaDetails
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SearchTypeFilter
import com.example.data.model.SeasonItem
import com.example.data.model.SubtitleTrack
import com.example.data.model.VideoResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.data.util.AgeRatingClassifier
import com.example.data.util.AsyncSingleFlight
import kotlinx.coroutines.withContext

object MediaRepository {

  private var database: StreamNovaDatabase? = null
  private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val episodeProgressMap = ConcurrentHashMap<String, Long>()
  private val detailsSingleFlight = AsyncSingleFlight<ExtendedMediaDetails>()
  private val ageRatingSingleFlight = AsyncSingleFlight<Int?>()

  private val BLOCKED_TMDB_IDS = setOf(318508) // The Pitt Podcast - not the real TV show

  private fun isBlockedTv(tvId: Int, tvName: String?): Boolean {
    return tvId in BLOCKED_TMDB_IDS || tvName?.contains("Podcast", ignoreCase = true) == true ||
           tvName?.contains("Talk Show", ignoreCase = true) == true ||
           tvName?.contains("After Show", ignoreCase = true) == true ||
           tvName?.contains("Review", ignoreCase = true) == true
  }

  /**
   * Collettore del Flow Room: è l'unica sorgente della lista esposta all'UI.
   * Ogni variazione della tabella `cached_media_items` (seed iniziale, refresh TMDB,
   * preferiti, progressi di visione) viene emessa automaticamente alla UI.
   */
  private var dbObserverJob: Job? = null

  fun init(context: Context, db: StreamNovaDatabase? = null) {
    database = db ?: StreamNovaDatabase.getInstance(context)
    startDatabaseObserver()
    cleanBlockedContentFromCache()
  }

  private fun cleanBlockedContentFromCache() {
    val db = database ?: return
    repoScope.launch {
      try {
        val idsToDelete = listOf(
          "tmdb_tv_318508",
          "318508",
          "tv_318508"
        )
        val rawIdsToDelete = listOf(318508)
        db.cachedMediaDao().deleteByIds(idsToDelete, rawIdsToDelete)
        Log.i("MediaRepository", "Cleaned blocked content (ID 318508) from cache")
      } catch (e: Exception) {
        Log.w("MediaRepository", "Failed to clean blocked content from cache: ${e.message}")
      }
    }
  }

  /**
   * Single Source of Truth: collega lo StateFlow della UI al Flow reattivo del DAO Room.
   * La UI non legge più snapshot in memoria, ma i record persistiti nella tabella
   * `cached_media_items`, garantendo avvio offline-first e coerenza tra schermate.
   */
  private fun startDatabaseObserver() {
    val db = database ?: return
    if (dbObserverJob?.isActive == true) return
    dbObserverJob = repoScope.launch {
      try {
        db.cachedMediaDao().getAllCachedMediaFlow().collect { entities ->
          _mediaList.value = mapEntitiesToUiState(entities)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w("MediaRepository", "Osservatore Flow Room non attivo: ${e.message}")
      }
    }
  }

  // Video streams ad alta definizione stabili (HLS multi-bitrate e MP4 ad alte prestazioni)
  private const val URL_DUNE = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"
  private const val URL_LASTOFUS = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"
  private const val URL_COSMOS = "https://vjs.zencdn.net/v/oceans.mp4"
  private const val URL_OPPENHEIMER = "https://storage.googleapis.com/exoplayer-test-media-1/mp4/frame-counter-one-hour.mp4"
  const val FALLBACK_VIDEO_URL = ""

  /**
   * ID dei contenuti seed/demo del catalogo iniziale (Dune, The Last of Us, Fallout...).
   * Questi elementi possono usare il proprio video demo; i titoli provenienti da TMDB no.
   */
  private val demoSeedIds: Set<String> by lazy { getInitialMedia().map { it.id }.toSet() }

  /** true se l'elemento appartiene al catalogo seed/demo (non un titolo TMDB live). */
  fun isDemoSeedItem(id: String): Boolean = id in demoSeedIds

  val audioTracks = listOf(
    AudioTrack("it_51", "Italiano", "Dolby Digital Plus 5.1"),
    AudioTrack("en_atmos", "Inglese (Originale)", "Dolby Atmos"),
    AudioTrack("it_stereo", "Italiano", "Stereo 2.0"),
    AudioTrack("en_commentary", "Inglese", "Commento del Regista"),
  )

  val subtitleTracks = listOf(
    SubtitleTrack("off", "Disattivati"),
    SubtitleTrack("it", "Italiano"),
    SubtitleTrack("it_cc", "Italiano (Non Udenti)", isClosedCaption = true),
    SubtitleTrack("en", "Inglese"),
  )

  private val theLastOfUsEpisodes = listOf(
    Episode(
      id = "tlou_s1e1",
      seasonNumber = 1,
      episodeNumber = 1,
      title = "Quando sei perso nel buio",
      synopsis = "Venti anni dopo una letale epidemia fungina che ha sconvolto il mondo intero, i superstiti Joel e Tess affrontano una missione che potrebbe riscrivere il loro destino.",
      durationMinutes = 81,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s1e2",
      seasonNumber = 1,
      episodeNumber = 2,
      title = "Infetto",
      synopsis = "Fuggiti dalla Zona di Quarantena di Boston, Joel, Tess ed Ellie attraversano una città abbandonata e decadente piena di pericoli letali.",
      durationMinutes = 53,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s1e3",
      seasonNumber = 1,
      episodeNumber = 3,
      title = "Molto molto tempo",
      synopsis = "Quando uno sconosciuto si imbatte nella sua tenuta protetta, il solitario survivalista Bill trova un legame inaspettato con il viandante Frank.",
      durationMinutes = 75,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s1e4",
      seasonNumber = 1,
      episodeNumber = 4,
      title = "Per favore stringimi la mano",
      synopsis = "Durante il viaggio attraverso il Missouri in furgone, Joel ed Ellie cadono in un'imboscata tesa da una milizia spietata a Kansas City.",
      durationMinutes = 45,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s1e5",
      seasonNumber = 1,
      episodeNumber = 5,
      title = "Resisti e sopravvivi",
      synopsis = "Mentre tentano di eludere i ribelli armati, Joel ed Ellie incontrano Henry e il fratello Sam, unendo le forze per fuggire attraverso tunnel sotterranei infestati.",
      durationMinutes = 59,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s1e6",
      seasonNumber = 1,
      episodeNumber = 6,
      title = "Famiglia",
      synopsis = "Dopo mesi di cammino, Joel ritrova suo fratello Tommy in una comunità pacifica e fortificata nello stato del Wyoming.",
      durationMinutes = 59,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s1e7",
      seasonNumber = 1,
      episodeNumber = 7,
      title = "Abbandonata",
      synopsis = "Mentre Joel lotta tra la vita e la morte per una grave ferita, Ellie ripercorre i ricordi del suo passato con la migliore amica Riley.",
      durationMinutes = 56,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s1e8",
      seasonNumber = 1,
      episodeNumber = 8,
      title = "Quando siamo nel bisogno",
      synopsis = "Ellie si imbatte in un gruppo di sopravvissuti guidati da un carismatico predicatore, la cui ospitalità nasconde un lato orribile.",
      durationMinutes = 51,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s1e9",
      seasonNumber = 1,
      episodeNumber = 9,
      title = "Cerca la luce",
      synopsis = "Joel ed Ellie raggiungono finalmente la base dell'ospedale delle Luci a Salt Lake City, dove il destino dell'umanità richiede una scelta estrema.",
      durationMinutes = 43,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    // Stagione 2
    Episode(
      id = "tlou_s2e1",
      seasonNumber = 2,
      episodeNumber = 1,
      title = "Il giorno dopo",
      synopsis = "Cinque anni dopo gli eventi di Salt Lake City, Joel ed Ellie vivono nella prospera comunità di Jackson, ma ombre del passato si avvicinano minacciosamente.",
      durationMinutes = 60,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s2e2",
      seasonNumber = 2,
      episodeNumber = 2,
      title = "La bufera di neve",
      synopsis = "Una pattuglia di ricognizione viene sorpresa da una tormenta devastante nei boschi fuori Jackson, costringendo Ellie e Dina a rifugiarsi in un avamposto.",
      durationMinutes = 55,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s2e3",
      seasonNumber = 2,
      episodeNumber = 3,
      title = "Seattle - Giorno 1",
      synopsis = "Ellie e Dina giungono a Seattle a cavallo tra le rovine allagate del centro città, cercando indizi sul gruppo armato WLF guidato da Abby.",
      durationMinutes = 58,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s2e4",
      seasonNumber = 2,
      episodeNumber = 4,
      title = "Territorio conteso",
      synopsis = "La guerra tra il WLF e la fazione dei Serafiti esplode nelle strade di Seattle, intrappolando Ellie nel mezzo di una brutale imboscata.",
      durationMinutes = 52,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s2e5",
      seasonNumber = 2,
      episodeNumber = 5,
      title = "L'ospedale sommerso",
      synopsis = "Alla ricerca di Nora, Ellie penetra nell'ospedale centrale controllato dai militari del WLF, affrontando gli orrori nascosti nei piani sotterranei.",
      durationMinutes = 62,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "tlou_s2e6",
      seasonNumber = 2,
      episodeNumber = 6,
      title = "La resa dei conti",
      synopsis = "Tutte le strade convergono al teatro abbandonato di Seattle, dove un confronto inevitabile cambierà per sempre il corso delle loro vite.",
      durationMinutes = 65,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      videoUrl = URL_LASTOFUS,
    ),
  )

  private val strangerThingsEpisodes = listOf(
    // Stagione 1
    Episode(
      id = "st_s1e1",
      seasonNumber = 1,
      episodeNumber = 1,
      title = "Capitolo Uno: La scomparsa di Will Byers",
      synopsis = "Tornando a casa in bici dopo una partita a D&D, il dodicenne Will incontra una creatura terrificante. Nel frattempo una ragazzina dai poteri telecinetici fugge da un laboratorio.",
      durationMinutes = 48,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s1e2",
      seasonNumber = 1,
      episodeNumber = 2,
      title = "Capitolo Due: La stramba di Maple Street",
      synopsis = "Mike nasconde la misteriosa Undici nel seminterrato di casa sua, mentre Joyce riceve una misteriosa telefonata disturbata.",
      durationMinutes = 55,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s1e3",
      seasonNumber = 1,
      episodeNumber = 3,
      title = "Capitolo Tre: Luci natalizie",
      synopsis = "Joyce tappezza la parete del soggiorno di lucine colorate per comunicare con Will. Dustin, Lucas e Mike chiedono a Undici la posizione di Will.",
      durationMinutes = 51,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s1e4",
      seasonNumber = 1,
      episodeNumber = 4,
      title = "Capitolo Quattro: Il corpo",
      synopsis = "Rifiutando di credere che il corpo ritrovato nella cava sia di Will, Joyce cerca disperatamente di entrare in contatto con suo figlio.",
      durationMinutes = 50,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s1e5",
      seasonNumber = 1,
      episodeNumber = 5,
      title = "Capitolo Cinque: La pulce e l'acrobata",
      synopsis = "Hopper riesce a penetrare nei laboratori segreti di Hawkins, mentre i ragazzi interrogano il professor Clarke sul concetto di dimensioni parallele.",
      durationMinutes = 53,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    // Stagione 2
    Episode(
      id = "st_s2e1",
      seasonNumber = 2,
      episodeNumber = 1,
      title = "Capitolo Uno: MadMax",
      synopsis = "Nel 1984, la sala giochi cittadina accoglie una nuova campionessa mentre Will continua ad avere visioni agghiaccianti di una gigantesca ombra nel cielo.",
      durationMinutes = 48,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s2e2",
      seasonNumber = 2,
      episodeNumber = 2,
      title = "Capitolo Due: Dolcetto o scherzetto, matto",
      synopsis = "Durante la notte di Halloween vestiti da Ghostbusters, Will sperimenta una terribile possessione da parte dell'Ombra gigante.",
      durationMinutes = 56,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s2e3",
      seasonNumber = 2,
      episodeNumber = 3,
      title = "Capitolo Tre: Il girino",
      synopsis = "Dustin adotta un insolito animaletto trovato nel bidone della spazzatura, chiamandolo Dart, ignorando la sua vera origine nel Sottosopra.",
      durationMinutes = 51,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s2e4",
      seasonNumber = 2,
      episodeNumber = 4,
      title = "Capitolo Quattro: Will il saggio",
      synopsis = "Joyce tenta di comprendere le intricate mappe disegnate da Will, mentre Hopper scava nei campi di zucche ormai marci della contea.",
      durationMinutes = 46,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    // Stagione 3
    Episode(
      id = "st_s3e1",
      seasonNumber = 3,
      episodeNumber = 1,
      title = "Capitolo Uno: Suzie, mi ricevi?",
      synopsis = "Estate 1985. Il nuovo centro commerciale Starcourt attrae tutti gli abitanti, mentre Dustin intercetta una misteriosa trasmissione radiofonica russa.",
      durationMinutes = 51,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s3e2",
      seasonNumber = 3,
      episodeNumber = 2,
      title = "Capitolo Due: Incubi",
      synopsis = "Steve e Robin aiutano Dustin a decodificare il messaggio segreto, mentre Billy subisce un violento incidente vicino a una vecchia acciaieria.",
      durationMinutes = 50,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s3e3",
      seasonNumber = 3,
      episodeNumber = 3,
      title = "Capitolo Tre: La bagnina scomparsa",
      synopsis = "El e Max intuiscono che qualcosa di inquietante sta accadendo a Billy, mentre i magneti di Joyce continuano a cadere dal frigorifero.",
      durationMinutes = 50,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s3e4",
      seasonNumber = 3,
      episodeNumber = 4,
      title = "Capitolo Quattro: Il test della sauna",
      synopsis = "La squadra progetta una trappola nella sauna comunale per confermare se Billy sia sotto il controllo del Mind Flayer.",
      durationMinutes = 53,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    // Stagione 4
    Episode(
      id = "st_s4e1",
      seasonNumber = 4,
      episodeNumber = 1,
      title = "Capitolo Uno: Hellfire Club",
      synopsis = "Primavera 1986. Mike e Dustin si uniscono al club di D&D condotto da Eddie Munson, mentre in città si consuma una morte brutale e inspiegabile.",
      durationMinutes = 76,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7p.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s4e2",
      seasonNumber = 4,
      episodeNumber = 2,
      title = "Capitolo Due: La maledizione di Vecna",
      synopsis = "I ragazzi indagano sulla sinistra entità extradimensionale chiamata Vecna, mentre in California El affronta gravi difficoltà a scuola.",
      durationMinutes = 77,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s4e3",
      seasonNumber = 4,
      episodeNumber = 3,
      title = "Capitolo Tre: Il mostro e la supereroina",
      synopsis = "Mentre Hopper tenta la fuga da una prigione siberiana ghiacciata, Murray e Joyce volano verso l'Alaska.",
      durationMinutes = 63,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "st_s4e4",
      seasonNumber = 4,
      episodeNumber = 4,
      title = "Capitolo Quattro: Caro Billy",
      synopsis = "Max diventa il nuovo bersaglio della maledizione di Vecna. La musica delle sue cuffie diventa l'unica speranza di salvezza.",
      durationMinutes = 78,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
    ),
  )

  private val shogunEpisodes = listOf(
    Episode(
      id = "shogun_s1e1",
      seasonNumber = 1,
      episodeNumber = 1,
      title = "Capitolo 1: Anjin",
      synopsis = "Una nave mercantile olandese naufraga sulle coste del feudo di Izu. Il pilota inglese John Blackthorne viene fatto prigioniero dai samurai.",
      durationMinutes = 70,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/bwSmgmd90hCWwqOKQYTEraeOZhJ.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "shogun_s1e2",
      seasonNumber = 1,
      episodeNumber = 2,
      title = "Capitolo 2: Servitori di due padroni",
      synopsis = "Blackthorne viene scortato a Osaka al cospetto del potente Lord Toranaga, scoprendo intrighi mortali tra i Reggenti e i sacerdoti gesuiti.",
      durationMinutes = 58,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/bwSmgmd90hCWwqOKQYTEraeOZhJ.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "shogun_s1e3",
      seasonNumber = 1,
      episodeNumber = 3,
      title = "Capitolo 3: Domani è domani",
      synopsis = "Un attentato notturno al castello di Osaka accelera i preparativi di fuga di Toranaga e del suo seguito attraverso vie marittime ad alto rischio.",
      durationMinutes = 56,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/bwSmgmd90hCWwqOKQYTEraeOZhJ.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "shogun_s1e4",
      seasonNumber = 1,
      episodeNumber = 4,
      title = "Capitolo 4: La recinzione",
      synopsis = "Giunti ad Ajiro, Blackthorne insegna ai soldati di Toranaga le tattiche di artiglieria navale europea sotto la supervisione di Lady Mariko.",
      durationMinutes = 61,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/bwSmgmd90hCWwqOKQYTEraeOZhJ.jpg",
      videoUrl = URL_LASTOFUS,
    ),
    Episode(
      id = "shogun_s1e5",
      seasonNumber = 1,
      episodeNumber = 5,
      title = "Capitolo 5: Vigilia di guerra",
      synopsis = "Un devastante terremoto scuote le montagne di Izu, mettendo a repentaglio la vita di Toranaga mentre le armate avversarie si mobilitano.",
      durationMinutes = 58,
      thumbnailRes = R.drawable.banner_lastofus,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/bwSmgmd90hCWwqOKQYTEraeOZhJ.jpg",
      videoUrl = URL_LASTOFUS,
    ),
  )

  private val severanceEpisodes = listOf(
    // Stagione 1
    Episode(
      id = "sev_s1e1",
      seasonNumber = 1,
      episodeNumber = 1,
      title = "Buone notizie sulla colite",
      synopsis = "Mark Scout riceve una promozione a capo della divisione Macrodata Refinement della Lumon Industries, dopo l'improvvisa scomparsa del collega Petey.",
      durationMinutes = 57,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/ixgFmf1X59PUZam2qbAfskx2gQr.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "sev_s1e2",
      seasonNumber = 1,
      episodeNumber = 2,
      title = "Mezza luna",
      synopsis = "La nuova assunta Helly R. cerca disperatamente di inviare un messaggio al suo io esterno, scontrandosi con i severi protocolli di sicurezza.",
      durationMinutes = 53,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/ixgFmf1X59PUZam2qbAfskx2gQr.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "sev_s1e3",
      seasonNumber = 1,
      episodeNumber = 3,
      title = "In perpetuo",
      synopsis = "Mark accompagna il reparto in visita all'ala commemorativa dedicata ai fondatori Egan, mentre fuori dal lavoro incontra segretamente Petey.",
      durationMinutes = 54,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/ixgFmf1X59PUZam2qbAfskx2gQr.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "sev_s1e4",
      seasonNumber = 1,
      episodeNumber = 4,
      title = "Il tuo te crudele",
      synopsis = "Helly riceve la risposta del suo 'outie' a una richiesta di dimissioni. Dylan trova un misterioso disegno ideografico nell'ufficio Ottica e Design.",
      durationMinutes = 53,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/ixgFmf1X59PUZam2qbAfskx2gQr.jpg",
      videoUrl = URL_COSMOS,
    ),
    // Stagione 2
    Episode(
      id = "sev_s2e1",
      seasonNumber = 2,
      episodeNumber = 1,
      title = "Il risveglio nel corridoio",
      synopsis = "Dopo la ribellione dell'Overtime Contingency, i membri del reparto MDR si risvegliano in una struttura Lumon radicalmente trasformata.",
      durationMinutes = 58,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/ixgFmf1X59PUZam2qbAfskx2gQr.jpg",
      videoUrl = URL_COSMOS,
    ),
    Episode(
      id = "sev_s2e2",
      seasonNumber = 2,
      episodeNumber = 2,
      title = "Protocollo Tartaro",
      synopsis = "Milchick impone restrizioni biometriche estreme al piano interrato, mentre Mark scopre nuovi indizi sulla vera identità di Gemma.",
      durationMinutes = 54,
      thumbnailRes = R.drawable.banner_cosmos,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/ixgFmf1X59PUZam2qbAfskx2gQr.jpg",
      videoUrl = URL_COSMOS,
    ),
  )

  private val falloutEpisodes = listOf(
    Episode(
      id = "fo_s1e1",
      seasonNumber = 1,
      episodeNumber = 1,
      title = "La fine",
      synopsis = "Nel 2077 le bombe nucleari distruggono Los Angeles. Oltre due secoli dopo, Lucy MacLean lascia il pacifico Vault 33 per salvare suo padre rapito.",
      durationMinutes = 74,
      thumbnailRes = R.drawable.banner_dune,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/coaPCIqQBPUZsOnJcWZxhaORcDT.jpg",
      videoUrl = URL_OPPENHEIMER,
    ),
    Episode(
      id = "fo_s1e2",
      seasonNumber = 1,
      episodeNumber = 2,
      title = "L'obiettivo",
      synopsis = "Nell'avamposto desolato di Filly, Lucy, lo scudiero Maximus della Confraternita d'Acciaio e il cacciatore di taglie Ghoul convergono sullo stesso scienziato fuggiasco.",
      durationMinutes = 65,
      thumbnailRes = R.drawable.banner_dune,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/coaPCIqQBPUZsOnJcWZxhaORcDT.jpg",
      videoUrl = URL_OPPENHEIMER,
    ),
    Episode(
      id = "fo_s1e3",
      seasonNumber = 1,
      episodeNumber = 3,
      title = "La testa",
      synopsis = "Maximus assume l'identità del cavaliere Titus nella potente armatura atomica T-60. Il Ghoul porta Lucy attraverso le pericolose terre contaminate.",
      durationMinutes = 56,
      thumbnailRes = R.drawable.banner_dune,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/coaPCIqQBPUZsOnJcWZxhaORcDT.jpg",
      videoUrl = URL_OPPENHEIMER,
    ),
    Episode(
      id = "fo_s1e4",
      seasonNumber = 1,
      episodeNumber = 4,
      title = "I ghoul",
      synopsis = "Lucy sperimenta la cruda realtà del commercio di organi nella Zona Contaminata, mentre Norm indaga sugli oscuri segreti del Vault 32.",
      durationMinutes = 48,
      thumbnailRes = R.drawable.banner_dune,
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/coaPCIqQBPUZsOnJcWZxhaORcDT.jpg",
      videoUrl = URL_OPPENHEIMER,
    ),
  )

  private val _mediaList = MutableStateFlow<List<MediaItem>>(getInitialMedia())
  val mediaList: StateFlow<List<MediaItem>> = _mediaList.asStateFlow()

  private val _detailUiState = MutableStateFlow<MediaDetailUiState>(MediaDetailUiState.Idle)
  val detailUiState: StateFlow<MediaDetailUiState> = _detailUiState.asStateFlow()

  val featuredHero: MediaItem
    get() = _mediaList.value.firstOrNull { it.id == "dune_2" } ?: getInitialMedia().first()

  fun getMediaById(id: String): MediaItem? {
    return _mediaList.value.find { it.id == id }
  }

  /**
   * Converte i record Room nella lista esposta alla UI completandoli con i dati non
   * persistiti (risorse drawable locali, episodi seed, logo TMDB) e con lo stato utente.
   * Se la tabella è ancora vuota (primissimo avvio) restituisce il catalogo seed.
   */
  private fun mapEntitiesToUiState(cachedEntities: List<CachedMediaItemEntity>): List<MediaItem> {
    val initialList = getInitialMedia()
    if (cachedEntities.isEmpty()) return initialList

    val initialMap = initialList.associateBy { it.id }
    val episodesById = initialList.filter { it.episodes.isNotEmpty() }.associate { it.id to it.episodes }

    val mappedItems = cachedEntities.map { entity ->
      val initItem = initialMap[entity.id]
      val base = entity.toMediaItem(episodesById)
      val itemWithSeed = if (initItem != null) {
        base.copy(
          backdropRes = initItem.backdropRes,
          posterRes = initItem.posterRes,
          posterUrl = initItem.posterUrl ?: base.posterUrl,
          backdropUrl = initItem.backdropUrl ?: base.backdropUrl,
          logoUrl = initItem.logoUrl ?: base.logoUrl,
          episodes = if (initItem.episodes.isNotEmpty()) initItem.episodes else base.episodes,
          // Stato utente: la riga Room vince sempre sul seed
          isFavorite = entity.isFavorite,
          currentProgressMs = if (entity.currentProgressMs > 0) entity.currentProgressMs else initItem.currentProgressMs,
          lastWatchedSeason = entity.lastWatchedSeason ?: initItem.lastWatchedSeason,
          lastWatchedEpisode = entity.lastWatchedEpisode ?: initItem.lastWatchedEpisode
        )
      } else {
        base
      }
      // Sincronizza il progresso persistito dei singoli episodi
      val enrichedEpisodes = itemWithSeed.episodes.map { ep ->
        val saved = episodeProgressMap["${itemWithSeed.id}_s${ep.seasonNumber}e${ep.episodeNumber}"]
        if (saved != null) ep.copy(currentProgressMs = saved) else ep
      }
      itemWithSeed.copy(episodes = enrichedEpisodes)
    }
    return mappedItems.sortedByDescending { it.rating }
  }

  /**
   * Avvio offline-first:
   * 1) se la tabella è vuota semina il catalogo seed dentro Room;
   * 2) altrimenti garantisce che tutti i titoli seed siano presenti;
   * 3) in entrambi i casi preserva SEMPRE preferiti, progressi ed episodio visto.
   * L'UI si aggiorna da sola tramite il Flow del DAO (nessun assegnazione diretta).
   */
  suspend fun loadFromCache() = withContext(Dispatchers.IO) {
    val db = database ?: return@withContext
    // Carica tutti i progressi degli episodi persistiti in memoria
    try {
      val allEpisodeProgress = db.episodeProgressDao().getAllProgress()
      allEpisodeProgress.forEach { ep ->
        episodeProgressMap["${ep.mediaId}_s${ep.seasonNumber}e${ep.episodeNumber}"] = ep.progressMs
      }
      Log.i("MediaRepository", "Caricati ${allEpisodeProgress.size} progressi episodio da Room")
    } catch (e: Exception) {
      Log.w("MediaRepository", "Errore lettura progressi episodio: ${e.message}")
    }

    startDatabaseObserver()
    try {
      val dao = db.cachedMediaDao()
      val cachedEntities = dao.getAllCachedMedia()
      val initialList = getInitialMedia()

      // 1) Primissimo avvio: seed del catalogo direttamente dentro Room
      if (cachedEntities.isEmpty()) {
        dao.insertOrUpdate(initialList.map { it.toEntity() })
        Log.i("MediaRepository", "Seed iniziale in Room: ${initialList.size} titoli disponibili offline")
        return@withContext
      }

      val initialMap = initialList.associateBy { it.id }

      // Cache con URL obsoleti/corrotti: ripara preservando lo stato utente
      val hasStaleUrls = cachedEntities.any { entity ->
        val initItem = initialMap[entity.id]
        val isDummy = entity.posterUrl?.contains("Z7Vb4Zg2f7Z") == true ||
                      entity.backdropUrl?.contains("Z7Vb4Zg2f7Z") == true
        val isMismatched = initItem != null && (entity.posterUrl != initItem.posterUrl)
        isDummy || isMismatched
      }

      if (hasStaleUrls) {
        Log.i("MediaRepository", "Riparazione cache Room con catalogo seed verificato (stato utente preservato)")
        val existingById = cachedEntities.associateBy { it.id }
        val healedList = initialList.map { item ->
          val prev = existingById[item.id]
          if (prev != null) {
            item.copy(
              currentProgressMs = if (prev.currentProgressMs > 0) prev.currentProgressMs else item.currentProgressMs,
              isFavorite = prev.isFavorite,
              lastWatchedSeason = prev.lastWatchedSeason ?: item.lastWatchedSeason,
              lastWatchedEpisode = prev.lastWatchedEpisode ?: item.lastWatchedEpisode,
              totalDurationMs = if (prev.totalDurationMs > 0) prev.totalDurationMs else item.totalDurationMs
            )
          } else {
            item
          }
        }
        dao.insertOrUpdate(healedList.map { it.toEntity() })
        // Evict delle vecchie risposte TMDB parsate con schema obsoleto
        db.tmdbResponseCacheDao().clearAll()
        return@withContext
      }

      // 2) Completamento: titoli seed eventualmente assenti nella tabella
      val missingSeed = initialList.filter { init -> cachedEntities.none { it.id == init.id } }
      if (missingSeed.isNotEmpty()) {
        dao.insertOrUpdate(missingSeed.map { it.toEntity() })
        Log.i("MediaRepository", "Aggiunti ${missingSeed.size} titoli seed mancanti in Room")
      }

      Log.i("MediaRepository", "Caricati ${cachedEntities.size} titoli dalla cache Room locale (offline-first)")
    } catch (e: Exception) {
      Log.w("MediaRepository", "Errore nel caricamento cache Room: ${e.message}")
    }
  }

  /**
   * Trasferisce lo stato utente (preferito, progresso di visione, episodio visto,
   * durata totale) dalla riga Room preesistente al media aggiornato.
   * Usato durante ogni refresh/remoto e ogni reset: lo stato utente NON viene mai sovrascritto.
   */
  private fun CachedMediaItemEntity.preserveUserStateInto(item: MediaItem): MediaItem {
    return item.copy(
      isFavorite = this.isFavorite,
      currentProgressMs = if (this.currentProgressMs > 0) this.currentProgressMs else item.currentProgressMs,
      totalDurationMs = if (this.totalDurationMs > 0) this.totalDurationMs else item.totalDurationMs,
      lastWatchedSeason = this.lastWatchedSeason ?: item.lastWatchedSeason,
      lastWatchedEpisode = this.lastWatchedEpisode ?: item.lastWatchedEpisode,
      lastWatchedAt = this.lastWatchedAt ?: item.lastWatchedAt,
      ageRating = this.ageRating ?: item.ageRating
    )
  }

  suspend fun clearCache() = withContext(Dispatchers.IO) {
    database?.let { db ->
      try {
        val dao = db.cachedMediaDao()
        // Snapshot dello stato utente prima dello svuotamento
        val userStateById = dao.getAllCachedMedia().associateBy { it.id }

        db.tmdbResponseCacheDao().clearAll()
        // Annulla anche la progressione di paginazione provider: il prossimo
        // refresh riparte dalla pagina 1 per ogni coppia (provider, tipo).
        db.catalogProgressDao().clearAll()
        dao.clearAll()

        // Re-seed immediato: Room resta popolata e la UI (che legge dalla tabella) non resta mai vuota
        val reseeded = getInitialMedia().map { item ->
          userStateById[item.id]?.preserveUserStateInto(item) ?: item
        }
        dao.insertOrUpdate(reseeded.map { it.toEntity() })
        Log.i("MediaRepository", "Cache Room svuotata e rieseminata con ${reseeded.size} titoli seed (stato utente preservato)")
      } catch (e: Exception) {
        Log.w("MediaRepository", "Errore cancellazione cache Room: ${e.message}")
      }
    }
  }

  /**
   * Numero massimo di pagine scaricate per ciascuna coppia (provider, tipo).
   * Il crawl si ferma comunque su `total_pages` dichiarato da TMDB o su una pagina vuota:
   * non esiste alcun limite fisso del tipo `for (pg in 1..2)`.
   */
  const val MAX_PROVIDER_PAGES = 25

  /** TMDB ID di Crunchyroll: fallback watch_region (IT -> US) e priorita' alle serie TV. */
  const val CRUNCHYROLL_TMDB_ID = 283

  /** TMDB ID di Paramount+ (utile per controlli specifici sul catalogo). */
  const val PARAMOUNT_TMDB_ID = 531

  /**
   * Provider ID REALI di TMDB condivisi da entrambi i cataloghi (Film e Serie TV),
   * interrogati con `with_watch_providers` + `watch_region=IT`.
   */
  private val TMDB_PROVIDERS = listOf(
    Pair("8", "netflix"),
    Pair("337", "disney"),
    Pair("119", "prime"),
    Pair("1899|384", "hbo"),
    Pair("350", "apple"),
    Pair("531", "paramount"),
    Pair("283", "crunchyroll")
  )

  /** Pagina scaricata da un catalogo provider, con lo stato di prosecuzione. */
  data class ProviderCatalogPage(
    val providerId: Int,
    val isTv: Boolean,
    val page: Int,
    val totalPages: Int,
    val items: List<MediaItem>
  ) {
    /** Prosegui solo se la pagina ha prodotto titoli e non si sono raggiunti i limiti. */
    val hasMore: Boolean
      get() = items.isNotEmpty() && page < totalPages && page < MAX_PROVIDER_PAGES
  }

  /** Stato di ripresa della paginazione per UNA coppia (provider, tipo). */
  data class ProviderPaginationState(val nextPage: Int, val hasMore: Boolean)

  /**
   * Chiave canonica di un titolo TMDB: separa SEMPRE gli ID film da quelli serie TV,
   * così il film 123 e la serie 123 non collidono mai nella stessa mappa.
   */
  private fun tmdbKey(tmdbId: Int, isTv: Boolean): String =
    if (isTv) "tmdb_tv_$tmdbId" else "tmdb_m_$tmdbId"

  private fun mediaTypeKey(isTv: Boolean): String = if (isTv) "tv" else "movie"

  /**
   * Etichetta provider derivata SOLO da un catalogo reale (endpoint discover
   * con `with_watch_providers`): l'attribuzione tramite `id % 4` è stata eliminata.
   */
  private fun providerTagFor(providerId: Int): String = when (providerId) {
    8 -> "netflix"
    119 -> "prime"
    337 -> "disney"
    384, 1899 -> "hbo"
    350, 2 -> "apple"
    531 -> "paramount"
    283 -> "crunchyroll"
    else -> providerId.toString()
  }

  /** Cache key distinta per provider + tipo + pagina: nessuna collisione tra cataloghi. */
  private fun providerCacheKey(providerIds: String, isTv: Boolean, page: Int): String {
    val sanitized = providerIds.replace("|", "_").replace(",", "_")
    return "provider_${mediaTypeKey(isTv)}_${sanitized}_p$page"
  }

  /** Persiste la progressione (prossima pagina + total_pages) per la coppia (provider, tipo). */
  private suspend fun saveProviderProgress(providerId: Int, isTv: Boolean, page: Int, totalPages: Int) {
    val db = database ?: return
    try {
      db.catalogProgressDao().upsert(
        CatalogProgressEntity(
          providerId = providerId,
          mediaType = mediaTypeKey(isTv),
          nextPage = page + 1,
          totalPages = totalPages
        )
      )
    } catch (e: Exception) {
      Log.w("MediaRepository", "Salvataggio progresso paginazione provider fallito: ${e.message}")
    }
  }

  /**
   * Stato di ripresa della paginazione per la coppia (provider, tipo):
   * prossima pagina persistita e se ne esistono ancora, limitate da
   * `total_pages` (TMDB) e da [MAX_PROVIDER_PAGES].
   */
  suspend fun getProviderPaginationState(providerId: Int, isTv: Boolean): ProviderPaginationState =
    withContext(Dispatchers.IO) {
      val progress = try {
        database?.catalogProgressDao()?.get(providerId, mediaTypeKey(isTv))
      } catch (e: Exception) {
        Log.w("MediaRepository", "Lettura progresso paginazione provider fallita: ${e.message}")
        null
      }
      if (progress == null) {
        return@withContext ProviderPaginationState(nextPage = 1, hasMore = true)
      }
      val cap = if (progress.totalPages > 0) minOf(progress.totalPages, MAX_PROVIDER_PAGES) else MAX_PROVIDER_PAGES
      ProviderPaginationState(
        nextPage = progress.nextPage.coerceIn(1, MAX_PROVIDER_PAGES),
        hasMore = progress.nextPage <= cap
      )
    }

  suspend fun fetchMoviesWithCache(
    cacheKey: String,
    networkCall: suspend () -> TmdbPaginatedResponse<TmdbMovieDto>
  ): TmdbPaginatedResponse<TmdbMovieDto> {
    val db = database
    if (db != null) {
      try {
        val cached = db.tmdbResponseCacheDao().getCache(cacheKey)
        if (cached != null && !cached.isExpired()) {
          val deserialized = MediaCacheMapper.deserializeMovieResponse(cached.jsonResponse)
          if (deserialized != null && deserialized.results.isNotEmpty()) {
            Log.d("MediaRepository", "TMDB Cache HIT (Movie) [$cacheKey] - ${deserialized.results.size} titoli")
            return deserialized
          }
        }
      } catch (e: Exception) {
        Log.w("MediaRepository", "Lettura cache TMDB fallita per $cacheKey: ${e.message}")
      }
    }

    val response = networkCall()
    if (db != null && response.results.isNotEmpty()) {
      try {
        val json = MediaCacheMapper.serializeMovieResponse(response)
        db.tmdbResponseCacheDao().insertCache(
          TmdbResponseCacheEntity(
            endpointKey = cacheKey,
            jsonResponse = json,
            cachedAt = System.currentTimeMillis()
          )
        )
        Log.d("MediaRepository", "TMDB Cache STORED (Movie) [$cacheKey] - ${response.results.size} titoli")
      } catch (e: Exception) {
        Log.w("MediaRepository", "Salvataggio cache TMDB fallito per $cacheKey: ${e.message}")
      }
    }
    return response
  }

  suspend fun fetchTvWithCache(
    cacheKey: String,
    networkCall: suspend () -> TmdbPaginatedResponse<TmdbTvDto>
  ): TmdbPaginatedResponse<TmdbTvDto> {
    val db = database
    if (db != null) {
      try {
        val cached = db.tmdbResponseCacheDao().getCache(cacheKey)
        if (cached != null && !cached.isExpired()) {
          val deserialized = MediaCacheMapper.deserializeTvResponse(cached.jsonResponse)
          if (deserialized != null && deserialized.results.isNotEmpty()) {
            Log.d("MediaRepository", "TMDB Cache HIT (TV) [$cacheKey] - ${deserialized.results.size} titoli")
            return deserialized
          }
        }
      } catch (e: Exception) {
        Log.w("MediaRepository", "Lettura cache TMDB fallita per $cacheKey: ${e.message}")
      }
    }

    val response = networkCall()
    if (db != null && response.results.isNotEmpty()) {
      try {
        val json = MediaCacheMapper.serializeTvResponse(response)
        db.tmdbResponseCacheDao().insertCache(
          TmdbResponseCacheEntity(
            endpointKey = cacheKey,
            jsonResponse = json,
            cachedAt = System.currentTimeMillis()
          )
        )
        Log.d("MediaRepository", "TMDB Cache STORED (TV) [$cacheKey] - ${response.results.size} titoli")
      } catch (e: Exception) {
        Log.w("MediaRepository", "Salvataggio cache TMDB fallito per $cacheKey: ${e.message}")
      }
    }
    return response
  }

  fun toggleFavorite(id: String) {
    var newFavState = false
    _mediaList.update { list ->
      list.map { item ->
        if (item.id == id) {
          newFavState = !item.isFavorite
          item.copy(isFavorite = newFavState)
        } else item
      }
    }
    val currentDetail = _detailUiState.value
    if (currentDetail is MediaDetailUiState.Success && currentDetail.media.id == id) {
      _detailUiState.value = currentDetail.copy(media = currentDetail.media.copy(isFavorite = newFavState))
    }
    database?.let { db ->
      repoScope.launch {
        try {
          val dao = db.cachedMediaDao()
          val existing = dao.getMediaById(id)
          if (existing != null) {
            dao.updateFavorite(id, newFavState)
          } else {
            // Riga non ancora persistita: la crea con lo stato aggiornato (niente preferiti persi)
            _mediaList.value.find { it.id == id }?.let { item ->
              dao.insertOrUpdate(item.copy(isFavorite = newFavState).toEntity())
            }
          }
        } catch (e: Exception) {
          Log.w("MediaRepository", "Failed to update favorite in Room: ${e.message}")
        }
      }
    }
  }

  fun getEpisodeProgress(mediaId: String, seasonNumber: Int, episodeNumber: Int): Long {
    return episodeProgressMap["${mediaId}_s${seasonNumber}e${episodeNumber}"] ?: 0L
  }

  fun updateProgress(id: String, progressMs: Long) {
    val now = System.currentTimeMillis()
    _mediaList.update { list ->
      list.map { item ->
        if (item.id == id) {
          item.copy(currentProgressMs = progressMs, lastWatchedAt = now)
        } else {
          item
        }
      }
    }
    val currentDetail = _detailUiState.value
    if (currentDetail is MediaDetailUiState.Success && currentDetail.media.id == id) {
      _detailUiState.value = currentDetail.copy(
        media = currentDetail.media.copy(currentProgressMs = progressMs, lastWatchedAt = now)
      )
    }
    database?.let { db ->
      repoScope.launch {
        try {
          val dao = db.cachedMediaDao()
          val existing = dao.getMediaById(id)
          if (existing != null) {
            dao.updateProgressWithTimestamp(id, progressMs, now)
          } else {
            _mediaList.value.find { it.id == id }?.let { item ->
              dao.insertOrUpdate(item.copy(currentProgressMs = progressMs, lastWatchedAt = now).toEntity())
            }
          }
        } catch (e: Exception) {
          Log.w("MediaRepository", "Failed to update progress in Room: ${e.message}")
        }
      }
    }
  }

  fun updateEpisodeProgress(
    mediaId: String,
    seasonNumber: Int,
    episodeNumber: Int,
    progressMs: Long,
    durationMs: Long
  ) {
    val key = "${mediaId}_s${seasonNumber}e${episodeNumber}"
    episodeProgressMap[key] = progressMs
    val now = System.currentTimeMillis()

    _mediaList.update { list ->
      list.map { item ->
        if (item.id == mediaId) {
          val updatedEpisodes = item.episodes.map { ep ->
            if (ep.seasonNumber == seasonNumber && ep.episodeNumber == episodeNumber) {
              ep.copy(currentProgressMs = progressMs)
            } else ep
          }
          item.copy(
            lastWatchedSeason = seasonNumber,
            lastWatchedEpisode = episodeNumber,
            lastWatchedAt = now,
            episodes = updatedEpisodes
          )
        } else {
          item
        }
      }
    }

    val currentDetail = _detailUiState.value
    if (currentDetail is MediaDetailUiState.Success && currentDetail.media.id == mediaId) {
      val updatedEpisodes = currentDetail.media.episodes.map { ep ->
        if (ep.seasonNumber == seasonNumber && ep.episodeNumber == episodeNumber) {
          ep.copy(currentProgressMs = progressMs)
        } else ep
      }
      _detailUiState.value = currentDetail.copy(
        media = currentDetail.media.copy(
          lastWatchedSeason = seasonNumber,
          lastWatchedEpisode = episodeNumber,
          lastWatchedAt = now,
          episodes = updatedEpisodes
        )
      )
    }

    database?.let { db ->
      repoScope.launch {
        try {
          db.episodeProgressDao().saveEpisodeProgress(
            EpisodeProgressEntity(
              mediaId = mediaId,
              seasonNumber = seasonNumber,
              episodeNumber = episodeNumber,
              progressMs = progressMs,
              durationMs = durationMs
            )
          )
          db.cachedMediaDao().updateTvProgressWithTimestamp(mediaId, progressMs, seasonNumber, episodeNumber, now)
        } catch (e: Exception) {
          Log.w("MediaRepository", "Failed to update episode progress in Room: ${e.message}")
        }
      }
    }
  }

  suspend fun removeFromContinueWatching(mediaId: String) {
    val rawId = mediaId.removePrefix("tmdb_m_").removePrefix("tmdb_tv_").removePrefix("movie_").removePrefix("tv_")
    val idsToRemove = setOf(mediaId, rawId, "tmdb_m_$rawId", "tmdb_tv_$rawId", "movie_$rawId", "tv_$rawId")
    val rawIds = idsToRemove.mapNotNull { it.toIntOrNull() }

    Log.d("REMOVE_CW", "Removing mediaId=$mediaId rawId=$rawId idsToRemove=$idsToRemove rawIds=$rawIds")
    Log.d("REMOVE_CW", "Before filter: _mediaList size=${_mediaList.value.size}")

    _mediaList.update { list ->
      list.filterNot { item -> idsToRemove.contains(item.id) }
    }

    Log.d("REMOVE_CW", "After filter: _mediaList size=${_mediaList.value.size}")

    database?.let { db ->
      try {
        Log.d("REMOVE_CW", "Deleting from CachedMediaDao for ids: $idsToRemove rawIds: $rawIds")
        val deletedCount = db.cachedMediaDao().deleteByIds(idsToRemove.toList(), rawIds)
        Log.d("REMOVE_CW", "Deleted $deletedCount rows from cached_media_items")

        Log.d("REMOVE_CW", "Clearing EpisodeProgressDao for ids: $idsToRemove")
        idsToRemove.forEach { id ->
          db.episodeProgressDao().clearProgressForMedia(id)
        }
        Log.d("REMOVE_CW", "Database deletion completed")
      } catch (e: Exception) {
        Log.e("REMOVE_CW", "Failed to remove from continue watching in Room: ${e.message}", e)
      }
    }
  }

  suspend fun loadMediaDetails(tmdbId: Int, isTv: Boolean, baseMedia: MediaItem? = null) {
    // Match tipizzato: un ID film e un ID serie TMDB coincidenti non si confondono.
    val wantedType = if (isTv) MediaType.SERIE_TV else MediaType.FILM
    val existingBase = baseMedia ?: _mediaList.value.find { it.tmdbId == tmdbId && it.type == wantedType }
    _detailUiState.value = MediaDetailUiState.Loading(existingBase)

    try {
      val details = fetchExtendedDetails(tmdbId, isTv, existingBase)
      val enrichedMedia = (existingBase ?: detailsToMediaItem(details)).copy(
        synopsis = if (details.overview.isNotBlank()) details.overview else existingBase?.synopsis ?: "",
        genres = if (details.genres.isNotEmpty()) details.genres else existingBase?.genres ?: emptyList(),
        director = if (details.director.isNotBlank()) details.director else existingBase?.director ?: "",
        cast = if (details.cast.isNotEmpty()) details.cast.map { it.name } else existingBase?.cast ?: emptyList(),
        durationMinutes = if (details.durationMinutes > 0) details.durationMinutes else existingBase?.durationMinutes ?: 120,
        seasonsCount = details.seasonsCount ?: existingBase?.seasonsCount,
        rating = if (details.rating > 0f) details.rating else existingBase?.rating ?: 8.0f,
        backdropUrl = details.backdropUrl ?: existingBase?.backdropUrl,
        posterUrl = details.posterUrl ?: existingBase?.posterUrl,
        seasonEpisodesCount = if (details.seasonEpisodesCount.isNotEmpty()) details.seasonEpisodesCount else (existingBase?.seasonEpisodesCount ?: emptyMap()),
        ageRating = details.ageRating ?: existingBase?.ageRating
      )
      _detailUiState.value = MediaDetailUiState.Success(enrichedMedia, details)

      // Se ageRating è stato arricchito o valorizzato, aggiorna lo stato in-memory e la riga Room
      if (enrichedMedia.ageRating != null && existingBase?.ageRating != enrichedMedia.ageRating) {
        _mediaList.update { list ->
          list.map { if (it.id == enrichedMedia.id) enrichedMedia else it }
        }
        database?.cachedMediaDao()?.insertOrUpdate(enrichedMedia.toEntity())
      }
    } catch (e: Exception) {
      Log.e("MediaRepository", "Errore nel caricamento dettagli TMDB ($tmdbId): ${e.message}", e)
      if (existingBase != null) {
        _detailUiState.value = MediaDetailUiState.Error(
          baseMedia = existingBase,
          message = "Errore di connessione a TMDB: ${e.localizedMessage ?: "riprova tra poco"}"
        )
      } else {
        _detailUiState.value = MediaDetailUiState.Error(
          baseMedia = null,
          message = "Impossibile recuperare i dettagli da TMDB: ${e.localizedMessage ?: "connessione assente"}"
        )
      }
    }
  }

  suspend fun fetchExtendedDetails(
    tmdbId: Int,
    isTv: Boolean,
    baseMedia: MediaItem? = null
  ): ExtendedMediaDetails = withContext(Dispatchers.IO) {
    val apiKey = getEffectiveApiKey()
    val cacheKey = if (isTv) "tv_details_$tmdbId" else "movie_details_$tmdbId"

    detailsSingleFlight.run(cacheKey) {
      val db = database

      // 1. Lettura da cache Room
      if (db != null) {
        try {
          val cached = db.tmdbResponseCacheDao().getCache(cacheKey)
          if (cached != null && !cached.isExpired()) {
            val deserialized = if (isTv) {
              MediaCacheMapper.deserializeTvDetail(cached.jsonResponse)?.let { tvDtoToExtended(it, baseMedia) }
            } else {
              MediaCacheMapper.deserializeMovieDetail(cached.jsonResponse)?.let { movieDtoToExtended(it, baseMedia) }
            }
            if (deserialized != null) {
              if (isTv && deserialized.seasonEpisodesCount.isEmpty() && (deserialized.seasonsCount ?: 0) > 0) {
                Log.d("MediaRepository", "TMDB Details Cache HIT ma manca seasonEpisodesCount -> refresh da rete [$cacheKey]")
              } else {
                Log.d("MediaRepository", "TMDB Details Cache HIT [$cacheKey]")
                if (deserialized.ageRating != null) {
                  return@run deserialized
                }
                // Se la cache precedente non aveva release_dates/content_ratings, recuperiamo il rating dall'endpoint dedicato
                val fetchedRating = try {
                  if (isTv) {
                    val tvRatings = TmdbApiClient.service.getTvContentRatings(tmdbId, apiKey)
                    AgeRatingClassifier.classifyTvRating(tvRatings)
                  } else {
                    val movieDates = TmdbApiClient.service.getMovieReleaseDates(tmdbId, apiKey)
                    AgeRatingClassifier.classifyMovieRating(movieDates)
                  }
                } catch (_: Exception) {
                  null
                }
                return@run deserialized.copy(ageRating = fetchedRating ?: baseMedia?.ageRating)
              }
            }
          }
        } catch (e: Exception) {
          Log.w("MediaRepository", "Errore lettura cache dettagli [$cacheKey]: ${e.message}")
        }
      }

      // 2. Chiamata di rete verso TMDB
      val (details, rawJson) = if (isTv) {
        val dto = TmdbApiClient.service.getTvDetails(
          seriesId = tmdbId,
          apiKey = apiKey,
          language = "it-IT",
          append = "credits,similar,content_ratings"
        )
        val json = MediaCacheMapper.serializeTvDetail(dto)
        tvDtoToExtended(dto, baseMedia) to json
      } else {
        val dto = TmdbApiClient.service.getMovieDetails(
          movieId = tmdbId,
          apiKey = apiKey,
          language = "it-IT",
          append = "credits,similar,release_dates"
        )
        val json = MediaCacheMapper.serializeMovieDetail(dto)
        movieDtoToExtended(dto, baseMedia) to json
      }

      // 3. Salvataggio in cache Room
      if (db != null && rawJson.isNotBlank()) {
        try {
          db.tmdbResponseCacheDao().insertCache(
            TmdbResponseCacheEntity(
              endpointKey = cacheKey,
              jsonResponse = rawJson,
              cachedAt = System.currentTimeMillis()
            )
          )
          Log.d("MediaRepository", "TMDB Details Cache STORED [$cacheKey]")
        } catch (e: Exception) {
          Log.w("MediaRepository", "Errore salvataggio cache dettagli [$cacheKey]: ${e.message}")
        }
      }

      details
    }
  }

  private fun movieDtoToExtended(dto: TmdbMovieDetailDto, baseMedia: MediaItem?): ExtendedMediaDetails {
    val castMembers = dto.credits?.cast?.sortedBy { it.order ?: 999 }?.take(15)?.map {
      CastMember(
        id = it.id,
        name = it.name,
        character = it.character ?: "Interprete",
        profileUrl = TmdbApiClient.profileUrl(it.profilePath)
      )
    } ?: emptyList()

    val directorName = dto.credits?.crew?.find { it.job.equals("Director", ignoreCase = true) }?.name
      ?: dto.credits?.crew?.find { it.department.equals("Directing", ignoreCase = true) }?.name
      ?: baseMedia?.director ?: ""

    val similar = dto.similar?.results?.map { movieToMediaItem(it, _mediaList.value) } ?: emptyList()

    val backdrop = TmdbApiClient.backdropUrl(dto.backdropPath) ?: baseMedia?.backdropUrl
    val poster = TmdbApiClient.posterUrl(dto.posterPath) ?: baseMedia?.posterUrl

    val ageRating = AgeRatingClassifier.classifyMovieRating(dto.releaseDates)
      ?: baseMedia?.ageRating

    return ExtendedMediaDetails(
      tmdbId = dto.id,
      title = dto.title?.takeIf { it.isNotBlank() } ?: dto.originalTitle ?: baseMedia?.title ?: "Film",
      originalTitle = dto.originalTitle ?: baseMedia?.originalTitle ?: "",
      overview = dto.overview?.takeIf { it.isNotBlank() } ?: baseMedia?.synopsis ?: "Nessuna trama disponibile.",
      backdropUrl = backdrop,
      posterUrl = poster,
      releaseYear = dto.releaseDate?.take(4)?.toIntOrNull() ?: baseMedia?.year ?: 2024,
      rating = dto.voteAverage ?: baseMedia?.rating ?: 8.0f,
      voteCount = dto.voteCount ?: 0,
      durationMinutes = dto.runtime ?: baseMedia?.durationMinutes ?: 120,
      seasonsCount = null,
      episodesCount = null,
      genres = dto.genres?.map { it.name }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: baseMedia?.genres ?: listOf("Cinema"),
      tagline = dto.tagline,
      status = dto.status,
      cast = castMembers.ifEmpty {
        baseMedia?.cast?.mapIndexed { idx, name -> CastMember(idx, name, "Interprete", null) } ?: emptyList()
      },
      director = directorName,
      similarItems = similar,
      isTv = false,
      ageRating = ageRating
    )
  }

  private fun tvDtoToExtended(dto: TmdbTvDetailDto, baseMedia: MediaItem?): ExtendedMediaDetails {
    val castMembers = dto.credits?.cast?.sortedBy { it.order ?: 999 }?.take(15)?.map {
      CastMember(
        id = it.id,
        name = it.name,
        character = it.character ?: "Interprete",
        profileUrl = TmdbApiClient.profileUrl(it.profilePath)
      )
    } ?: emptyList()

    val directorName = dto.credits?.crew?.find { it.job.equals("Executive Producer", ignoreCase = true) || it.job.equals("Creator", ignoreCase = true) }?.name
      ?: dto.credits?.crew?.find { it.department.equals("Directing", ignoreCase = true) }?.name
      ?: baseMedia?.director ?: ""

    val similar = dto.similar?.results?.map { tvToMediaItem(it, _mediaList.value) } ?: emptyList()

    val backdrop = TmdbApiClient.backdropUrl(dto.backdropPath) ?: baseMedia?.backdropUrl
    val poster = TmdbApiClient.posterUrl(dto.posterPath) ?: baseMedia?.posterUrl

    val seasonEpCounts = dto.seasons
      ?.filter { (it.seasonNumber ?: 0) > 0 }
      ?.associate { (it.seasonNumber ?: 0) to (it.episodeCount ?: 0) }
      ?: emptyMap()

    val ageRating = AgeRatingClassifier.classifyTvRating(dto.contentRatings)
      ?: baseMedia?.ageRating

    return ExtendedMediaDetails(
      tmdbId = dto.id,
      title = dto.name?.takeIf { it.isNotBlank() } ?: dto.originalName ?: baseMedia?.title ?: "Serie TV",
      originalTitle = dto.originalName ?: baseMedia?.originalTitle ?: "",
      overview = dto.overview?.takeIf { it.isNotBlank() } ?: baseMedia?.synopsis ?: "Nessuna trama disponibile.",
      backdropUrl = backdrop,
      posterUrl = poster,
      releaseYear = dto.firstAirDate?.take(4)?.toIntOrNull() ?: baseMedia?.year ?: 2024,
      rating = dto.voteAverage ?: baseMedia?.rating ?: 8.0f,
      voteCount = dto.voteCount ?: 0,
      durationMinutes = dto.episodeRunTime?.firstOrNull() ?: baseMedia?.durationMinutes ?: 50,
      seasonsCount = dto.numberOfSeasons ?: baseMedia?.seasonsCount ?: 1,
      episodesCount = dto.numberOfEpisodes,
      genres = dto.genres?.map { it.name }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: baseMedia?.genres ?: listOf("Serie TV"),
      tagline = dto.tagline,
      status = dto.status,
      cast = castMembers.ifEmpty {
        baseMedia?.cast?.mapIndexed { idx, name -> CastMember(idx, name, "Interprete", null) } ?: emptyList()
      },
      director = directorName,
      similarItems = similar,
      isTv = true,
      seasonEpisodesCount = seasonEpCounts,
      ageRating = ageRating
    )
  }

  private fun detailsToMediaItem(details: ExtendedMediaDetails): MediaItem {
    return MediaItem(
      id = "tmdb_${if (details.isTv) "tv" else "m"}_${details.tmdbId}",
      tmdbId = details.tmdbId,
      title = details.title,
      originalTitle = details.originalTitle,
      synopsis = details.overview,
      videoUrl = "",
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "16+"),
      backdropUrl = details.backdropUrl,
      posterUrl = details.posterUrl,
      type = if (details.isTv) MediaType.SERIE_TV else MediaType.FILM,
      year = details.releaseYear,
      durationMinutes = details.durationMinutes,
      seasonsCount = details.seasonsCount,
      rating = details.rating,
      genres = details.genres,
      director = details.director,
      cast = details.cast.map { it.name },
      seasonEpisodesCount = details.seasonEpisodesCount,
      ageRating = details.ageRating
    )
  }

  /**
   * Recupera il rating d'età normalizzato (0, 12, 14, 16, 18) per un Film o Serie TV.
   * Utilizza in-memory cache, Room cache, deduplicazione single-flight e fallback TMDB.
   * Se il rating non è disponibile o non affidabile, restituisce null.
   */
  suspend fun getAgeRating(tmdbId: Int, isTv: Boolean): Int? = withContext(Dispatchers.IO) {
    val key = "age_rating_${if (isTv) "tv" else "m"}_$tmdbId"
    ageRatingSingleFlight.run(key) {
      // 1. In-memory check
      val existing = _mediaList.value.firstOrNull {
        it.tmdbId == tmdbId && it.type == (if (isTv) MediaType.SERIE_TV else MediaType.FILM)
      }
      if (existing?.ageRating != null) return@run existing.ageRating

      // 2. Room check
      val cachedEntity = database?.cachedMediaDao()?.getMediaById(if (isTv) "tmdb_tv_$tmdbId" else "tmdb_m_$tmdbId")
      if (cachedEntity?.ageRating != null) return@run cachedEntity.ageRating

      // 3. TMDB API call
      val apiKey = getEffectiveApiKey()
      try {
        if (isTv) {
          val ratings = TmdbApiClient.service.getTvContentRatings(tmdbId, apiKey)
          val classified = AgeRatingClassifier.classifyTvRating(ratings)
          if (classified != null && cachedEntity != null) {
            database?.cachedMediaDao()?.insertOrUpdate(cachedEntity.copy(ageRating = classified))
          }
          classified
        } else {
          val dates = TmdbApiClient.service.getMovieReleaseDates(tmdbId, apiKey)
          val classified = AgeRatingClassifier.classifyMovieRating(dates)
          if (classified != null && cachedEntity != null) {
            database?.cachedMediaDao()?.insertOrUpdate(cachedEntity.copy(ageRating = classified))
          }
          classified
        }
      } catch (e: Exception) {
        Log.w("MediaRepository", "Errore recupero ageRating per tmdbId=$tmdbId (isTv=$isTv): ${e.message}")
        null
      }
    }
  }

  suspend fun getSeasonDetails(
    tvTmdbId: Int,
    seasonNumber: Int,
    fallbackSeries: MediaItem? = null
  ): SeasonItem = withContext(Dispatchers.IO) {
    val apiKey = getEffectiveApiKey()
    val cacheKey = "tv_season_${tvTmdbId}_${seasonNumber}"
    val db = database

    // 1. Lettura Cache-First da Room
    if (db != null) {
      try {
        val cached = db.tmdbResponseCacheDao().getCache(cacheKey)
        if (cached != null && !cached.isExpired()) {
          val deserialized = MediaCacheMapper.deserializeSeasonDetail(cached.jsonResponse)
          if (deserialized != null) {
            Log.d("MediaRepository", "TMDB Season Cache HIT [$cacheKey] (${deserialized.episodes?.size ?: 0} ep)")
            return@withContext deserialized.toSeasonItem(tvTmdbId, fallbackSeries)
          }
        }
      } catch (e: Exception) {
        Log.w("MediaRepository", "Errore lettura cache stagione [$cacheKey]: ${e.message}")
      }
    }

    // 2. Chiamata di rete verso TMDB
    try {
      val dto = TmdbApiClient.service.getSeasonDetails(
        seriesId = tvTmdbId,
        seasonNumber = seasonNumber,
        apiKey = apiKey,
        language = "it-IT"
      )

      Log.d("THE_PITT", "Fetching season $seasonNumber for mediaId=$tvTmdbId, episodes returned: ${dto.episodes?.size ?: 0}")
      dto.episodes?.forEach { ep ->
        Log.d("THE_PITT", "  S${ep.seasonNumber}E${ep.episodeNumber}: ${ep.name} (airDate=${ep.airDate})")
      }

      // 3. Salvataggio in cache Room
      val rawJson = MediaCacheMapper.serializeSeasonDetail(dto)
      if (db != null && rawJson.isNotBlank()) {
        try {
          db.tmdbResponseCacheDao().insertCache(
            TmdbResponseCacheEntity(
              endpointKey = cacheKey,
              jsonResponse = rawJson,
              cachedAt = System.currentTimeMillis()
            )
          )
          Log.d("MediaRepository", "TMDB Season Cache STORED [$cacheKey] (${dto.episodes?.size ?: 0} ep)")
        } catch (e: Exception) {
          Log.w("MediaRepository", "Errore salvataggio cache stagione [$cacheKey]: ${e.message}")
        }
      }

      return@withContext dto.toSeasonItem(tvTmdbId, fallbackSeries)
    } catch (e: Exception) {
      Log.w("MediaRepository", "Errore chiamata TMDB stagione $seasonNumber per serie $tvTmdbId: ${e.message}")
      val existingEpisodes = fallbackSeries?.episodes?.filter { it.seasonNumber == seasonNumber }
      if (existingEpisodes != null && existingEpisodes.isNotEmpty()) {
        return@withContext SeasonItem(
          id = seasonNumber,
          seasonNumber = seasonNumber,
          name = "Stagione $seasonNumber",
          overview = "Episodi della stagione $seasonNumber",
          episodes = existingEpisodes.map { ep ->
            EpisodeItem(
              id = ep.id,
              tmdbId = ep.id.hashCode(),
              episodeNumber = ep.episodeNumber,
              seasonNumber = ep.seasonNumber,
              title = ep.title,
              overview = ep.synopsis,
              stillUrl = ep.thumbnailUrl ?: fallbackSeries.backdropUrl,
              durationMinutes = ep.durationMinutes,
              rating = fallbackSeries.rating,
              videoUrl = ep.videoUrl
            )
          }
        )
      } else {
        val count = 8
        val generated = (1..count).map { epIndex ->
          EpisodeItem(
            id = "tv_${tvTmdbId}_s${seasonNumber}e$epIndex",
            tmdbId = tvTmdbId * 100 + epIndex,
            episodeNumber = epIndex,
            seasonNumber = seasonNumber,
            title = "Episodio $epIndex",
            overview = "Episodio $epIndex della Stagione $seasonNumber della serie ${fallbackSeries?.title ?: "TV"}.",
            stillUrl = fallbackSeries?.backdropUrl,
            durationMinutes = fallbackSeries?.durationMinutes?.takeIf { it > 0 } ?: 55,
            rating = fallbackSeries?.rating ?: 8.0f,
            videoUrl = fallbackSeries?.videoUrl ?: ""
          )
        }
        return@withContext SeasonItem(
          id = seasonNumber,
          seasonNumber = seasonNumber,
          name = "Stagione $seasonNumber",
          overview = "",
          episodes = generated
        )
      }
    }
  }

  private fun TmdbSeasonDetailDto.toSeasonItem(tvTmdbId: Int, fallbackSeries: MediaItem? = null): SeasonItem {
    val epList = this.episodes?.map { epDto ->
      EpisodeItem(
        id = "tmdb_tv_${tvTmdbId}_s${epDto.seasonNumber}e${epDto.episodeNumber}",
        tmdbId = epDto.id,
        episodeNumber = epDto.episodeNumber,
        seasonNumber = epDto.seasonNumber,
        title = epDto.name?.takeIf { it.isNotBlank() } ?: "Episodio ${epDto.episodeNumber}",
        overview = epDto.overview?.takeIf { it.isNotBlank() } ?: "Nessuna sinossi disponibile per questo episodio.",
        stillUrl = TmdbApiClient.stillUrl(epDto.stillPath) ?: fallbackSeries?.backdropUrl,
        durationMinutes = epDto.runtime?.takeIf { it > 0 } ?: fallbackSeries?.durationMinutes ?: 50,
        rating = epDto.voteAverage ?: fallbackSeries?.rating ?: 8.0f,
        airDate = epDto.airDate,
        videoUrl = fallbackSeries?.videoUrl ?: ""
      )
    } ?: emptyList()

    return SeasonItem(
      id = this.id,
      seasonNumber = this.seasonNumber,
      name = this.name?.takeIf { it.isNotBlank() } ?: "Stagione ${this.seasonNumber}",
      overview = this.overview ?: "",
      episodes = epList
    )
  }

  fun getContinueWatching(): List<MediaItem> {
    return _mediaList.value.filter { it.currentProgressMs > 0 }
  }

  fun getPopular(): List<MediaItem> {
    return _mediaList.value.sortedByDescending { it.rating }
  }

  fun getMovies(): List<MediaItem> {
    return _mediaList.value.filter { it.type == MediaType.FILM }
  }

  fun getSeries(): List<MediaItem> {
    return _mediaList.value.filter { it.type == MediaType.SERIE_TV }
  }

  fun getFavorites(): List<MediaItem> {
    return _mediaList.value.filter { it.isFavorite }
  }

  // Categorie richieste: Top 10 Film, Top 10 Serie, Trending Film, Trending Serie, For You Film, For You Serie, Popolari
  fun getTop10Movies(): List<MediaItem> {
    return _mediaList.value
      .filter { it.type == MediaType.FILM }
      .sortedByDescending { it.rating }
      .take(10)
  }

  fun getTop10Series(): List<MediaItem> {
    return _mediaList.value
      .filter { it.type == MediaType.SERIE_TV }
      .sortedByDescending { it.rating }
      .take(10)
  }

  fun getTrendingMovies(): List<MediaItem> {
    return _mediaList.value
      .filter { it.type == MediaType.FILM && (it.isTrending || it.year >= 2023) }
      .sortedByDescending { it.year * 10f + it.rating }
  }

  fun getTrendingSeries(): List<MediaItem> {
    return _mediaList.value
      .filter { it.type == MediaType.SERIE_TV && (it.isTrending || it.year >= 2022) }
      .sortedByDescending { it.year * 10f + it.rating }
  }

  fun getForYouMovies(): List<MediaItem> {
    return _mediaList.value
      .filter { it.type == MediaType.FILM }
      .sortedByDescending { (it.rating * 1.5f) + if (it.isFavorite) 5f else 0f }
  }

  fun getForYouSeries(): List<MediaItem> {
    return _mediaList.value
      .filter { it.type == MediaType.SERIE_TV }
      .sortedByDescending { (it.rating * 1.5f) + if (it.isFavorite) 5f else 0f }
  }

  fun getPopularMovies(): List<MediaItem> {
    return _mediaList.value
      .filter { it.type == MediaType.FILM }
      .sortedByDescending { it.rating }
  }

  fun getPopularSeries(): List<MediaItem> {
    return _mediaList.value
      .filter { it.type == MediaType.SERIE_TV }
      .sortedByDescending { it.rating }
  }

  // Cataloghi legati ai Provider di Streaming: Netflix, HBO Max, Disney+, Prime Video
  fun getMediaByProvider(providerId: String): List<MediaItem> {
    val clean = providerId.lowercase().trim()
    return _mediaList.value.filter { it.provider?.lowercase() == clean }
  }

  fun getNetflixCatalog(): List<MediaItem> {
    return getMediaByProvider("netflix")
  }

  fun getHboCatalog(): List<MediaItem> {
    return getMediaByProvider("hbo")
  }

  fun getDisneyCatalog(): List<MediaItem> {
    return getMediaByProvider("disney")
  }

  fun getPrimeCatalog(): List<MediaItem> {
    return getMediaByProvider("prime")
  }

  const val DEFAULT_FALLBACK_TMDB_API_KEY = "da92702177bb03ed4b4517e58eebb018"

  fun getEffectiveApiKey(): String {
    val buildKey = BuildConfig.TMDB_API_KEY.trim().removeSurrounding("\"").removeSurrounding("'")
    if (buildKey.isNotBlank() && buildKey.length >= 20 && !buildKey.startsWith("DEFAULT_")) {
      return buildKey
    }
    val envBuildConfig = BuildConfig.ENV_TMDB_API_KEY.trim().removeSurrounding("\"").removeSurrounding("'")
    if (envBuildConfig.isNotBlank() && envBuildConfig.length >= 20 && !envBuildConfig.startsWith("DEFAULT_")) {
      return envBuildConfig
    }
    val envKey = System.getenv("TMDB_API_KEY")?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
    if (envKey.isNotBlank() && envKey.length >= 20 && !envKey.startsWith("DEFAULT_")) {
      return envKey
    }
    Log.i("MediaRepository", "Uso TMDB API Key predefinita incorporata ($DEFAULT_FALLBACK_TMDB_API_KEY)")
    return DEFAULT_FALLBACK_TMDB_API_KEY
  }

  suspend fun verifyTmdbApiKey(apiKey: String = getEffectiveApiKey()): Boolean = withContext(Dispatchers.IO) {
    try {
      val response = TmdbApiClient.service.getTrendingMovies(apiKey = apiKey, language = "it-IT")
      val isValid = response.results.isNotEmpty()
      Log.i("MediaRepository", "Verifica TMDB API Key: ${if (isValid) "ATTIVA E VALIDA (HTTP 200 OK)" else "NESSUN RISULTATO"}")
      isValid
    } catch (e: Exception) {
      Log.e("MediaRepository", "Verifica TMDB API Key FALLITA: ${e.message}. Verificare la chiave.")
      false
    }
  }

  /**
   * Ricerca remota globale su TMDB con fallback locale (cache-first resiliente).
   * Emette istantaneamente i risultati locali già in memoria / cache.
   * Se la query ha almeno 2 caratteri, interroga l'endpoint /3/search/multi,
   * salva i nuovi titoli in Room e ri-emette la lista aggiornata.
   */
  fun searchTmdb(
    query: String,
    filter: SearchTypeFilter = SearchTypeFilter.ALL
  ): Flow<List<MediaItem>> = flow {
    val cleanQuery = query.trim()
    val currentList = _mediaList.value

    if (cleanQuery.isBlank()) {
      val baseList = when (filter) {
        SearchTypeFilter.FILM -> currentList.filter { it.type == MediaType.FILM }
        SearchTypeFilter.SERIE_TV -> currentList.filter { it.type == MediaType.SERIE_TV }
        SearchTypeFilter.ALL -> currentList
      }
      emit(baseList)
      return@flow
    }

    val q = cleanQuery.lowercase()
    val localMatches = currentList.filter {
      (filter == SearchTypeFilter.ALL ||
        (filter == SearchTypeFilter.FILM && it.type == MediaType.FILM) ||
        (filter == SearchTypeFilter.SERIE_TV && it.type == MediaType.SERIE_TV)) &&
      (it.title.lowercase().contains(q) ||
        it.originalTitle.lowercase().contains(q) ||
        it.synopsis.lowercase().contains(q) ||
        it.genres.any { g -> g.lowercase().contains(q) } ||
        it.cast.any { c -> c.lowercase().contains(q) })
    }
    // Emette subito i risultati locali
    emit(localMatches)

    if (cleanQuery.length >= 2) {
      try {
        val apiKey = getEffectiveApiKey()
        val remoteItems = when (filter) {
          SearchTypeFilter.FILM -> {
            val response = TmdbApiClient.service.searchMovie(
              apiKey = apiKey,
              query = cleanQuery,
              language = "it-IT",
              page = 1,
              includeAdult = false
            )
            response.results.map { movieToMediaItem(it, currentList) }
          }
          SearchTypeFilter.SERIE_TV -> {
            val response = TmdbApiClient.service.searchTv(
              apiKey = apiKey,
              query = cleanQuery,
              language = "it-IT",
              page = 1,
              includeAdult = false
            )
            response.results
              .filter { tv -> tv.name?.contains("Podcast", ignoreCase = true) != true }
              .map { tvToMediaItem(it, currentList) }
          }
          SearchTypeFilter.ALL -> {
            val response = TmdbApiClient.service.searchMulti(
              apiKey = apiKey,
              query = cleanQuery,
              language = "it-IT",
              page = 1,
              includeAdult = false
            )
            response.results.mapNotNull { dto ->
              when (dto.mediaType) {
                "movie" -> multiSearchMovieToMediaItem(dto, currentList)
                "tv" -> if (dto.name?.contains("Podcast", ignoreCase = true) == true) null else multiSearchTvToMediaItem(dto, currentList)
                else -> null
              }
            }
          }
        }

        if (remoteItems.isNotEmpty()) {
          // Salva in Room per cache-first resiliente
          val db = database
          if (db != null) {
            withContext(Dispatchers.IO) {
              try {
                db.cachedMediaDao().insertOrUpdate(remoteItems.map { it.toEntity() })
              } catch (e: Exception) {
                Log.w("MediaRepository", "Errore salvataggio Room searchTmdb: ${e.message}")
              }
            }
          }

          // Unisci risultati remoti e locali evitando duplicati (precedenza ai risultati remoti)
          val resultMap = LinkedHashMap<String, MediaItem>()
          remoteItems.forEach { resultMap[it.id] = it }
          localMatches.forEach {
            if (!resultMap.containsKey(it.id)) {
              resultMap[it.id] = it
            }
          }

          // Aggiungi a _mediaList gli elementi mancanti
          val updatedGlobal = _mediaList.value.toMutableList()
          var addedToGlobal = false
          remoteItems.forEach { item ->
            if (updatedGlobal.none { it.id == item.id }) {
              updatedGlobal.add(item)
              addedToGlobal = true
            }
          }
          if (addedToGlobal) {
            _mediaList.value = updatedGlobal
          }

          emit(resultMap.values.toList())
        }
      } catch (e: Exception) {
        Log.w("MediaRepository", "Ricerca remota TMDB fallita per \"$cleanQuery\" (filtro=$filter): ${e.message}")
      }
    }
  }

  /**
   * Paginazione remota generi/categorie con TMDB Discover:
   * /3/discover/movie?api_key={key}&with_genres={genreId}&language=it-IT&sort_by=popularity.desc&page={page}
   * /3/discover/tv?api_key={key}&with_genres={genreId}&language=it-IT&sort_by=popularity.desc&page={page}
   */
  suspend fun loadCategoryPage(
    movieGenreId: Int?,
    tvGenreId: Int?,
    page: Int
  ): List<MediaItem> = withContext(Dispatchers.IO) {
    val cleanKey = getEffectiveApiKey()
    val currentList = _mediaList.value
    val results = mutableListOf<MediaItem>()

    // Film per genere
    if (movieGenreId != null) {
      try {
        val movieResponse = TmdbApiClient.service.discoverMoviesByGenre(
          apiKey = cleanKey,
          genreId = movieGenreId.toString(),
          sortBy = "popularity.desc",
          language = "it-IT",
          page = page
        )
        val movies = movieResponse.results
          .filterNot { movieGenreId == 16 && it.originalLanguage.equals("ja", ignoreCase = true) }
          .map { movieToMediaItem(it, currentList) }
        results.addAll(movies)
      } catch (e: Exception) {
        Log.w("MediaRepository", "Errore discoverMoviesByGenre (genere=$movieGenreId, pag=$page): ${e.message}")
      }
    }

    // Serie TV per genere
    if (tvGenreId != null) {
      try {
        val tvResponse = TmdbApiClient.service.discoverTvByGenre(
          apiKey = cleanKey,
          genreId = tvGenreId.toString(),
          sortBy = "popularity.desc",
          language = "it-IT",
          page = page
        )
        val tvs = tvResponse.results
          .filterNot { isBlockedTv(it.id, it.name) }
          .filterNot { tvGenreId == 16 && it.originalLanguage.equals("ja", ignoreCase = true) }
          .map { tvToMediaItem(it, currentList) }
        results.addAll(tvs)
      } catch (e: Exception) {
        Log.w("MediaRepository", "Errore discoverTvByGenre (genere=$tvGenreId, pag=$page): ${e.message}")
      }
    }

    // Salva in Room DB per cache-first resiliente
    if (results.isNotEmpty()) {
      val db = database
      if (db != null) {
        try {
          db.cachedMediaDao().insertOrUpdate(results.map { it.toEntity() })
        } catch (e: Exception) {
          Log.w("MediaRepository", "Errore salvataggio Room loadCategoryPage: ${e.message}")
        }
      }
      val updated = _mediaList.value.toMutableList()
      var added = false
      results.forEach { item ->
        if (updated.none { it.id == item.id }) {
          updated.add(item)
          added = true
        }
      }
      if (added) {
        _mediaList.value = updated
      }
    }

    results
  }

  private fun multiSearchMovieToMediaItem(
    movie: TmdbMultiSearchResultDto,
    existingItems: List<MediaItem>
  ): MediaItem {
    val existing = existingItems.firstOrNull { it.tmdbId == movie.id && it.type == MediaType.FILM }
    val year = movie.releaseDate?.take(4)?.toIntOrNull() ?: 2024
    val backdrop = TmdbApiClient.backdropUrl(movie.backdropPath)
    val poster = TmdbApiClient.posterUrl(movie.posterPath)
    val streams = listOf(URL_DUNE, URL_OPPENHEIMER, URL_COSMOS, URL_LASTOFUS)
    val assignedStream = streams[kotlin.math.abs(movie.id) % streams.size]
    val genres = mapGenreIds(movie.genreIds, isMovie = true)
    // Attribuzione provider REALE: mai derivata da `id % 4` (regola eliminata).
    val detectedProvider = existing?.provider
    val dur = existing?.durationMinutes ?: (100 + (kotlin.math.abs(movie.id) % 50))
    val ratingVal = (movie.voteAverage ?: 7.5f).coerceIn(1.0f, 10.0f)
    val titleStr = movie.title?.takeIf { it.isNotBlank() } ?: movie.originalTitle ?: "Film TMDB"

    return MediaItem(
      id = existing?.id ?: "tmdb_m_${movie.id}",
      tmdbId = movie.id,
      title = titleStr,
      originalTitle = movie.originalTitle ?: "",
      synopsis = movie.overview?.takeIf { it.isNotBlank() } ?: existing?.synopsis ?: "Disponibile su catalogo TMDB in streaming 4K HDR.",
      videoUrl = existing?.videoUrl ?: assignedStream,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "Cinema"),
      backdropRes = existing?.backdropRes,
      backdropUrl = backdrop ?: existing?.backdropUrl,
      posterRes = existing?.posterRes,
      posterUrl = poster ?: existing?.posterUrl,
      logoUrl = existing?.logoUrl,
      type = MediaType.FILM,
      year = year,
      durationMinutes = dur,
      rating = ((ratingVal * 10).toInt() / 10f),
      genres = if (genres.isNotEmpty()) genres else (existing?.genres ?: listOf("Cinema")),
      director = existing?.director ?: "Regia Internazionale",
      cast = existing?.cast ?: listOf("Cast Principale"),
      currentProgressMs = existing?.currentProgressMs ?: 0L,
      totalDurationMs = dur * 60 * 1000L,
      isFavorite = existing?.isFavorite ?: false,
      provider = detectedProvider,
      isTrending = existing?.isTrending ?: false,
      isTop10 = existing?.isTop10 ?: false,
      ageRating = existing?.ageRating
    )
  }

  private fun multiSearchTvToMediaItem(
    tv: TmdbMultiSearchResultDto,
    existingItems: List<MediaItem>
  ): MediaItem {
    val existing = existingItems.firstOrNull { it.tmdbId == tv.id && it.type == MediaType.SERIE_TV }
    val year = tv.firstAirDate?.take(4)?.toIntOrNull() ?: 2023
    val backdrop = TmdbApiClient.backdropUrl(tv.backdropPath)
    val poster = TmdbApiClient.posterUrl(tv.posterPath)
    val streams = listOf(URL_LASTOFUS, URL_COSMOS, URL_OPPENHEIMER, URL_DUNE)
    val assignedStream = streams[kotlin.math.abs(tv.id) % streams.size]
    val genres = mapGenreIds(tv.genreIds, isMovie = false)
    // Attribuzione provider REALE: mai derivata da `id % 4` (regola eliminata).
    val detectedProvider = existing?.provider
    val ratingVal = (tv.voteAverage ?: 8.0f).coerceIn(1.0f, 10.0f)
    val titleStr = tv.name?.takeIf { it.isNotBlank() } ?: tv.originalName ?: "Serie TMDB"
    val eps = existing?.episodes?.takeIf { it.isNotEmpty() }
      ?: generateEpisodesForTv(tv.id, titleStr, backdrop, poster, seasons = 2)

    return MediaItem(
      id = existing?.id ?: "tmdb_tv_${tv.id}",
      tmdbId = tv.id,
      title = titleStr,
      originalTitle = tv.originalName ?: "",
      synopsis = tv.overview?.takeIf { it.isNotBlank() } ?: existing?.synopsis ?: "Serie TV originale disponibile in streaming ad alta definizione.",
      videoUrl = existing?.videoUrl ?: assignedStream,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "Serie TV"),
      backdropRes = existing?.backdropRes,
      backdropUrl = backdrop ?: existing?.backdropUrl,
      posterRes = existing?.posterRes,
      posterUrl = poster ?: existing?.posterUrl,
      logoUrl = existing?.logoUrl,
      type = MediaType.SERIE_TV,
      year = year,
      durationMinutes = 55,
      seasonsCount = existing?.seasonsCount ?: 2,
      rating = ((ratingVal * 10).toInt() / 10f),
      genres = if (genres.isNotEmpty()) genres else (existing?.genres ?: listOf("Serie TV")),
      director = existing?.director ?: "Showrunner Internazionale",
      cast = existing?.cast ?: listOf("Cast Principale"),
      currentProgressMs = existing?.currentProgressMs ?: 0L,
      totalDurationMs = 55 * 60 * 1000L,
      isFavorite = existing?.isFavorite ?: false,
      provider = detectedProvider,
      isTrending = existing?.isTrending ?: false,
      isTop10 = existing?.isTop10 ?: false,
      episodes = eps,
      ageRating = existing?.ageRating
    )
  }

  /**
   * Paginazione Catalogo Film: scarica la pagina richiesta da TMDB e la salva in Room.
   */
  suspend fun loadMoreMovies(page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
    val cleanKey = getEffectiveApiKey()
    try {
      val response = TmdbApiClient.service.getPopularMovies(cleanKey, page = page)
      val currentList = _mediaList.value
      val newItems = response.results.map { movieToMediaItem(it, currentList) }
      if (newItems.isNotEmpty()) {
        val db = database
        if (db != null) {
          try {
            db.cachedMediaDao().insertOrUpdate(newItems.map { it.toEntity() })
          } catch (e: Exception) {
            Log.w("MediaRepository", "Errore salvataggio Room loadMoreMovies: ${e.message}")
          }
        }
        val updated = _mediaList.value.toMutableList()
        var added = false
        newItems.forEach { item ->
          if (updated.none { it.id == item.id }) {
            updated.add(item)
            added = true
          }
        }
        if (added) {
          _mediaList.value = updated
        }
      }
      newItems
    } catch (e: Exception) {
      Log.w("MediaRepository", "Errore caricamento pagina $page film: ${e.message}")
      emptyList()
    }
  }

  /**
   * Paginazione Catalogo Serie TV: scarica la pagina richiesta da TMDB e la salva in Room.
   */
  suspend fun loadMoreTv(page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
    val cleanKey = getEffectiveApiKey()
    try {
      val response = TmdbApiClient.service.getPopularTv(cleanKey, page = page)
      val currentList = _mediaList.value
      val newItems = response.results.filterNot { isBlockedTv(it.id, it.name) }
        .map { tvToMediaItem(it, currentList) }
      if (newItems.isNotEmpty()) {
        val db = database
        if (db != null) {
          try {
            db.cachedMediaDao().insertOrUpdate(newItems.map { it.toEntity() })
          } catch (e: Exception) {
            Log.w("MediaRepository", "Errore salvataggio Room loadMoreTv: ${e.message}")
          }
        }
        val updated = _mediaList.value.toMutableList()
        var added = false
        newItems.forEach { item ->
          if (updated.none { it.id == item.id }) {
            updated.add(item)
            added = true
          }
        }
        if (added) {
          _mediaList.value = updated
        }
      }
      newItems
    } catch (e: Exception) {
      Log.w("MediaRepository", "Errore caricamento pagina $page serie TV: ${e.message}")
      emptyList()
    }
  }

  /**
   * Paginazione remota dei cataloghi provider (Film e Serie TV):
   * - `discover/movie` o `discover/tv` con `with_watch_providers` + `watch_region=IT`,
   * - cache key distinta per provider + tipo + pagina,
   * - progresso persistito in Room (prossima pagina, `total_pages`) per la SINGOLA
   *   coppia (provider, tipo), quindi Netflix Film non blocca Netflix Serie TV,
   * - nessun limite globale: la pagina viene avanzata finché `total_pages` (TMDB)
   *   e [MAX_PROVIDER_PAGES] lo consentono.
   */
  fun loadProviderCatalog(
    providerId: Int,
    isTv: Boolean,
    page: Int
  ): Flow<ProviderCatalogPage> = flow {
    val cleanKey = getEffectiveApiKey()
    val providerIds = providerId.toString()
    val providerTag = providerTagFor(providerId)
    val cacheKey = providerCacheKey(providerIds, isTv, page)
    try {
      val currentList = _mediaList.value
      var totalPages = 0
      // Regione catalogo: IT. Per Crunchyroll (283) fallback a "US" se "IT" non
      // restituisce titoli (pochi anime tracciati su TMDB con watch_region=IT).
      var region = "IT"
      val newItems: List<MediaItem> = if (isTv) {
        var response = fetchTvWithCache(cacheKey) {
          TmdbApiClient.service.discoverTvByProvider(
            apiKey = cleanKey,
            providerId = providerIds,
            region = "IT",
            sortBy = "popularity.desc",
            language = "it-IT",
            page = page
          )
        }
        if (response.results.isEmpty() && page == 1 && providerId == CRUNCHYROLL_TMDB_ID) {
          Log.d("PROVIDER_CATALOG", "watch_region=IT vuoto (provider=$providerId), riprova con watch_region=US")
          region = "US"
          response = fetchTvWithCache("${cacheKey}_us") {
            TmdbApiClient.service.discoverTvByProvider(
              apiKey = cleanKey,
              providerId = providerIds,
              region = "US",
              sortBy = "popularity.desc",
              language = "it-IT",
              page = page
            )
          }
        }
        totalPages = response.totalPages ?: 0
        response.results.filterNot { isBlockedTv(it.id, it.name) }.map { tvToMediaItem(it, currentList, provider = providerTag) }
      } else {
        var response = fetchMoviesWithCache(cacheKey) {
          TmdbApiClient.service.discoverMoviesByProvider(
            apiKey = cleanKey,
            providerId = providerIds,
            region = "IT",
            sortBy = "popularity.desc",
            language = "it-IT",
            page = page
          )
        }
        if (response.results.isEmpty() && page == 1 && providerId == CRUNCHYROLL_TMDB_ID) {
          Log.d("PROVIDER_CATALOG", "watch_region=IT vuoto (provider=$providerId, movie), riprova con watch_region=US")
          region = "US"
          response = fetchMoviesWithCache("${cacheKey}_us") {
            TmdbApiClient.service.discoverMoviesByProvider(
              apiKey = cleanKey,
              providerId = providerIds,
              region = "US",
              sortBy = "popularity.desc",
              language = "it-IT",
              page = page
            )
          }
        }
        totalPages = response.totalPages ?: 0
        response.results.map { movieToMediaItem(it, currentList, provider = providerTag) }
      }

      Log.d(
        "PROVIDER_CATALOG",
        "provider=$providerId (${providerTag}) isTv=$isTv page=$page region=$region -> " +
          "${newItems.size} titoli (total_pages=$totalPages)"
      )

      if (newItems.isNotEmpty()) {
        val db = database
        if (db != null) {
          try {
            db.cachedMediaDao().insertOrUpdate(newItems.map { it.toEntity() })
          } catch (e: Exception) {
            Log.w("MediaRepository", "Errore salvataggio Room loadProviderCatalog: ${e.message}")
          }
        }
        val updated = _mediaList.value.toMutableList()
        var modified = false
        newItems.forEach { item ->
          val idx = updated.indexOfFirst { it.id == item.id }
          if (idx >= 0) {
            val existing = updated[idx]
            if (existing.provider == null || !existing.provider.contains(providerTag)) {
              val combinedProvider = if (existing.provider.isNullOrBlank()) providerTag else "${existing.provider},$providerTag"
              updated[idx] = existing.copy(provider = combinedProvider)
              modified = true
            }
          } else {
            updated.add(item)
            modified = true
          }
        }
        if (modified) {
          _mediaList.value = updated
        }
      }

      // Progresso indipendente per coppia (provider, tipo)
      saveProviderProgress(providerId, isTv, page, totalPages)

      emit(ProviderCatalogPage(providerId, isTv, page, totalPages, newItems))
    } catch (e: Exception) {
      Log.w("MediaRepository", "Errore loadProviderCatalog (providerId=$providerId, isTv=$isTv, page=$page): ${e.message}")
      // Lo stato di "fine paginazione" resta limitato a questa coppia: gli altri
      // provider e l'altro tipo (Film/Serie) continuano a caricarsi normalmente.
      emit(ProviderCatalogPage(providerId, isTv, page, 0, emptyList()))
    }
  }.flowOn(Dispatchers.IO)

  private fun mapGenreIds(genreIds: List<Int>?, isMovie: Boolean): List<String> {
    if (genreIds.isNullOrEmpty()) return listOf(if (isMovie) "Cinema" else "Serie TV")
    val movieGenres = mapOf(
      28 to "Azione", 12 to "Avventura", 16 to "Animazione", 35 to "Commedia",
      80 to "Crime", 99 to "Documentario", 18 to "Dramma", 10751 to "Famiglia",
      14 to "Fantasy", 36 to "Storia", 27 to "Horror", 10402 to "Musica",
      9648 to "Mistero", 10749 to "Romance", 878 to "Fantascienza", 10770 to "Film TV",
      53 to "Thriller", 10752 to "Guerra", 37 to "Western"
    )
    val tvGenres = mapOf(
      10759 to "Azione & Avventura", 16 to "Animazione", 35 to "Commedia",
      80 to "Crime", 99 to "Documentario", 18 to "Dramma", 10762 to "Kids",
      9648 to "Mistero", 10763 to "News", 10764 to "Reality",
      10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk",
      10768 to "Guerra & Politica", 37 to "Western"
    )
    val dict = if (isMovie) movieGenres else tvGenres
    val result = genreIds.mapNotNull { dict[it] }
    return if (result.isNotEmpty()) result else listOf(if (isMovie) "Cinema" else "Serie TV")
  }

  fun generateEpisodesForTv(
    tvId: Int,
    tvTitle: String,
    backdropUrl: String?,
    posterUrl: String?,
    seasons: Int
  ): List<Episode> {
    val epList = mutableListOf<Episode>()
    val streams = listOf(URL_LASTOFUS, URL_COSMOS, URL_OPPENHEIMER, URL_DUNE)
    val countSeasons = seasons.coerceIn(1, 3)
    for (s in 1..countSeasons) {
      val count = if (s == 1) 5 else 4
      for (e in 1..count) {
        val dur = 45 + ((tvId + s * 7 + e * 3) % 25)
        epList.add(
          Episode(
            id = "tmdb_ep_${tvId}_s${s}e$e",
            seasonNumber = s,
            episodeNumber = e,
            title = "Episodio $e",
            synopsis = "Puntata $e della stagione $s di $tvTitle. Nuovi sviluppi e colpi di scena emozionanti.",
            durationMinutes = dur,
            thumbnailUrl = backdropUrl ?: posterUrl,
            videoUrl = streams[(tvId + s + e) % streams.size],
            currentProgressMs = if (s == 1 && e == 1) 12 * 60 * 1000L else 0L
          )
        )
      }
    }
    return epList
  }

  private fun movieToMediaItem(
    movie: TmdbMovieDto,
    existingItems: List<MediaItem>,
    provider: String? = null,
    isTrending: Boolean = false,
    isTop10: Boolean = false
  ): MediaItem {
    // Match tipizzato: un film e una serie con lo stesso TMDB ID restano separati.
    val existing = existingItems.firstOrNull { it.tmdbId == movie.id && it.type == MediaType.FILM }
    val year = movie.releaseDate?.take(4)?.toIntOrNull() ?: 2024
    val backdrop = TmdbApiClient.backdropUrl(movie.backdropPath)
    val poster = TmdbApiClient.posterUrl(movie.posterPath)
    val streams = listOf(URL_DUNE, URL_OPPENHEIMER, URL_COSMOS, URL_LASTOFUS)
    val assignedStream = streams[kotlin.math.abs(movie.id) % streams.size]
    val genres = mapGenreIds(movie.genreIds, isMovie = true)
    // Provider solo da catalogo reale (discover con with_watch_providers) o dal titolo
    // già noto: l'attribuzione tramite `id % 4` è stata eliminata.
    val detectedProvider = provider ?: existing?.provider
    val dur = existing?.durationMinutes ?: (100 + (kotlin.math.abs(movie.id) % 50))
    val ratingVal = (movie.voteAverage ?: 7.5f).coerceIn(1.0f, 10.0f)

    return MediaItem(
      id = existing?.id ?: "tmdb_m_${movie.id}",
      tmdbId = movie.id,
      title = movie.title?.takeIf { it.isNotBlank() } ?: movie.originalTitle ?: "Film TMDB",
      originalTitle = movie.originalTitle ?: "",
      synopsis = movie.overview?.takeIf { it.isNotBlank() } ?: existing?.synopsis ?: "Disponibile su catalogo TMDB in streaming 4K HDR.",
      videoUrl = existing?.videoUrl ?: assignedStream,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "16+"),
      backdropRes = existing?.backdropRes,
      backdropUrl = backdrop ?: existing?.backdropUrl,
      posterRes = existing?.posterRes,
      posterUrl = poster ?: existing?.posterUrl,
      logoUrl = existing?.logoUrl,
      type = MediaType.FILM,
      year = year,
      durationMinutes = dur,
      rating = ((ratingVal * 10).toInt() / 10f),
      genres = if (genres.isNotEmpty()) genres else (existing?.genres ?: listOf("Cinema")),
      director = existing?.director ?: "Produzione Internazionale",
      cast = existing?.cast ?: listOf("Cast Principale"),
      currentProgressMs = existing?.currentProgressMs ?: 0L,
      totalDurationMs = dur * 60 * 1000L,
      isFavorite = existing?.isFavorite ?: false,
      provider = detectedProvider,
      isTrending = isTrending || (existing?.isTrending ?: false),
      isTop10 = isTop10 || (existing?.isTop10 ?: false),
      ageRating = existing?.ageRating
    )
  }

  private fun tvToMediaItem(
    tv: TmdbTvDto,
    existingItems: List<MediaItem>,
    provider: String? = null,
    isTrending: Boolean = false,
    isTop10: Boolean = false
  ): MediaItem {
    // Match tipizzato: una serie e un film con lo stesso TMDB ID restano separati.
    val existing = existingItems.firstOrNull { it.tmdbId == tv.id && it.type == MediaType.SERIE_TV }
    val year = tv.firstAirDate?.take(4)?.toIntOrNull() ?: 2023
    val backdrop = TmdbApiClient.backdropUrl(tv.backdropPath)
    val poster = TmdbApiClient.posterUrl(tv.posterPath)
    val streams = listOf(URL_LASTOFUS, URL_COSMOS, URL_OPPENHEIMER, URL_DUNE)
    val assignedStream = streams[kotlin.math.abs(tv.id) % streams.size]
    val genres = mapGenreIds(tv.genreIds, isMovie = false)
    // Provider solo da catalogo reale (discover con with_watch_providers) o dal titolo
    // già noto: l'attribuzione tramite `id % 4` è stata eliminata.
    val detectedProvider = provider ?: existing?.provider
    val ratingVal = (tv.voteAverage ?: 8.0f).coerceIn(1.0f, 10.0f)
    val eps = existing?.episodes?.takeIf { it.isNotEmpty() }
      ?: generateEpisodesForTv(tv.id, tv.name ?: tv.originalName ?: "Serie TV", backdrop, poster, seasons = 2)

    return MediaItem(
      id = existing?.id ?: "tmdb_tv_${tv.id}",
      tmdbId = tv.id,
      title = tv.name?.takeIf { it.isNotBlank() } ?: tv.originalName ?: "Serie TMDB",
      originalTitle = tv.originalName ?: "",
      synopsis = tv.overview?.takeIf { it.isNotBlank() } ?: existing?.synopsis ?: "Serie TV originale disponibile in streaming ad alta definizione.",
      videoUrl = existing?.videoUrl ?: assignedStream,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "Stagione Completa"),
      backdropRes = existing?.backdropRes,
      backdropUrl = backdrop ?: existing?.backdropUrl,
      posterRes = existing?.posterRes,
      posterUrl = poster ?: existing?.posterUrl,
      logoUrl = existing?.logoUrl,
      type = MediaType.SERIE_TV,
      year = year,
      durationMinutes = 55,
      seasonsCount = existing?.seasonsCount ?: 2,
      rating = ((ratingVal * 10).toInt() / 10f),
      genres = if (genres.isNotEmpty()) genres else (existing?.genres ?: listOf("Serie TV")),
      director = existing?.director ?: "Showrunner Internazionale",
      cast = existing?.cast ?: listOf("Cast Principale"),
      currentProgressMs = existing?.currentProgressMs ?: 0L,
      totalDurationMs = 55 * 60 * 1000L,
      isFavorite = existing?.isFavorite ?: false,
      provider = detectedProvider,
      isTrending = isTrending || (existing?.isTrending ?: false),
      isTop10 = isTop10 || (existing?.isTop10 ?: false),
      episodes = eps,
      ageRating = existing?.ageRating
    )
  }

  /** Evita refresh concorrenti: il caricamento è incrementale ma controllato. */
  private val refreshInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Refresh del catalogo TMDB con caricamento INCREMENTALE e controllato:
   * - non blocca l'avvio (viene eseguito in coroutine su Dispatchers.IO),
   * - un solo refresh alla volta (guardia atomica),
   * - ogni blocco fetchato viene subito persistito in Room, così la UI vede il
   *   catalogo crescere progressivamente invece di attendere l'intero ciclo,
   * - le chiavi distinguono SEMPRE gli ID film da quelli serie TV.
   */
  suspend fun refreshTmdbData(apiKey: String = getEffectiveApiKey()) {
    val cleanKey = apiKey.trim().removeSurrounding("\"").removeSurrounding("'")
    if (cleanKey.isBlank() || cleanKey.length < 20) {
      Log.i("MediaRepository", "TMDB API Key non valida: uso catalogo precaricato TMDB")
      return
    }

    if (!refreshInProgress.compareAndSet(false, true)) {
      Log.i("MediaRepository", "Refresh TMDB già in corso: nuova richiesta ignorata (caricamento controllato)")
      return
    }

    try {
      // Garantisce che l'osservatore del Flow Room sia attivo prima di scrivere
      startDatabaseObserver()

      withContext(Dispatchers.IO) {
        try {
          val currentList = _mediaList.value.toMutableList()
          // Nessun MutableMap<Int, MediaItem>: le chiavi sono canoniche
          // ("tmdb_m_<id>" per i film, "tmdb_tv_<id>" per le serie), così un ID
          // film e un ID serie identici non collidono mai.
          val fetchedItemsMap = LinkedHashMap<String, MediaItem>()

          // Persistenza progressiva: la UI (che legge dalla tabella Room) si aggiorna
          // dopo ogni blocco invece di restare ferma per tutto il refresh.
          suspend fun checkpoint(label: String) {
            val total = mergeAndPersistCatalog(currentList, fetchedItemsMap)
            Log.i("MediaRepository", "Checkpoint [$label]: $total titoli in catalogo (persistiti in Room)")
          }

          // 1. Trending Movies & Trending TV
          try {
            val trendingM = fetchMoviesWithCache("trending_movies") {
              TmdbApiClient.service.getTrendingMovies(cleanKey)
            }.results
            trendingM.forEachIndexed { idx, m ->
              fetchedItemsMap[tmdbKey(m.id, isTv = false)] =
                movieToMediaItem(m, currentList, isTrending = true, isTop10 = idx < 10)
            }
          } catch (e: Exception) {
            Log.w("MediaRepository", "Trending movies call failed: ${e.message}")
          }

          try {
            val trendingT = fetchTvWithCache("trending_tv") {
              TmdbApiClient.service.getTrendingTv(cleanKey)
            }.results.filterNot { isBlockedTv(it.id, it.name) }
            trendingT.forEachIndexed { idx, t ->
              fetchedItemsMap[tmdbKey(t.id, isTv = true)] =
                tvToMediaItem(t, currentList, isTrending = true, isTop10 = idx < 10)
            }
          } catch (e: Exception) {
            Log.w("MediaRepository", "Trending TV call failed: ${e.message}")
          }
          checkpoint("trending")

          // 2. Popular Movies (pages 1 & 2)
          try {
            val popM1 = fetchMoviesWithCache("popular_movies_p1") {
              TmdbApiClient.service.getPopularMovies(cleanKey, page = 1)
            }.results
            popM1.forEach { m ->
              val key = tmdbKey(m.id, isTv = false)
              if (!fetchedItemsMap.containsKey(key)) {
                fetchedItemsMap[key] = movieToMediaItem(m, currentList)
              }
            }
            val popM2 = fetchMoviesWithCache("popular_movies_p2") {
              TmdbApiClient.service.getPopularMovies(cleanKey, page = 2)
            }.results
            popM2.forEach { m ->
              val key = tmdbKey(m.id, isTv = false)
              if (!fetchedItemsMap.containsKey(key)) {
                fetchedItemsMap[key] = movieToMediaItem(m, currentList)
              }
            }
          } catch (e: Exception) {
            Log.w("MediaRepository", "Popular movies call failed: ${e.message}")
          }

          // 3. Popular TV (pages 1 & 2)
          try {
            val popT1 = fetchTvWithCache("popular_tv_p1") {
              TmdbApiClient.service.getPopularTv(cleanKey, page = 1)
            }.results.filterNot { isBlockedTv(it.id, it.name) }
            popT1.forEach { t ->
              val key = tmdbKey(t.id, isTv = true)
              if (!fetchedItemsMap.containsKey(key)) {
                fetchedItemsMap[key] = tvToMediaItem(t, currentList)
              }
            }
            val popT2 = fetchTvWithCache("popular_tv_p2") {
              TmdbApiClient.service.getPopularTv(cleanKey, page = 2)
            }.results.filterNot { isBlockedTv(it.id, it.name) }
            popT2.forEach { t ->
              val key = tmdbKey(t.id, isTv = true)
              if (!fetchedItemsMap.containsKey(key)) {
                fetchedItemsMap[key] = tvToMediaItem(t, currentList)
              }
            }
          } catch (e: Exception) {
            Log.w("MediaRepository", "Popular TV call failed: ${e.message}")
          }
          checkpoint("popular")

          // 4. Top Rated Movies & TV
          try {
            val topM = fetchMoviesWithCache("top_rated_movies_p1") {
              TmdbApiClient.service.getTopRatedMovies(cleanKey, page = 1)
            }.results
            topM.forEach { m ->
              val key = tmdbKey(m.id, isTv = false)
              if (!fetchedItemsMap.containsKey(key)) {
                fetchedItemsMap[key] = movieToMediaItem(m, currentList)
              }
            }
          } catch (e: Exception) {
            Log.w("MediaRepository", "Top Rated movies call failed: ${e.message}")
          }

          try {
            val topT = fetchTvWithCache("top_rated_tv_p1") {
              TmdbApiClient.service.getTopRatedTv(cleanKey, page = 1)
            }.results.filterNot { isBlockedTv(it.id, it.name) }
            topT.forEach { t ->
              val key = tmdbKey(t.id, isTv = true)
              if (!fetchedItemsMap.containsKey(key)) {
                fetchedItemsMap[key] = tvToMediaItem(t, currentList)
              }
            }
          } catch (e: Exception) {
            Log.w("MediaRepository", "Top Rated TV call failed: ${e.message}")
          }
          checkpoint("top_rated")

          // 5. Cataloghi Provider per Serie TV: discover/tv con with_watch_providers
          //    + watch_region=IT e STESSI provider ID reali dei cataloghi Film.
          for ((pIds, pName) in TMDB_PROVIDERS) {
            crawlProviderCatalog(
              isTv = true,
              providerIds = pIds,
              providerTag = pName,
              apiKey = cleanKey,
              currentList = currentList,
              fetchedItems = fetchedItemsMap
            )
            checkpoint("provider_tv_$pName")
          }

          // 6. Cataloghi Provider per Film: discover/movie con with_watch_providers + watch_region=IT
          for ((pIds, pName) in TMDB_PROVIDERS) {
            crawlProviderCatalog(
              isTv = false,
              providerIds = pIds,
              providerTag = pName,
              apiKey = cleanKey,
              currentList = currentList,
              fetchedItems = fetchedItemsMap
            )
            checkpoint("provider_movie_$pName")
          }

          // 7. Genere Discover (Azione, Fantascienza, Dramma, Commedia)
          val genreQueries = listOf(
            Pair("28", "10759"),  // Azione Film (28), Azione TV (10759)
            Pair("878", "10765"), // Fantascienza Film (878), Sci-Fi & Fantasy TV (10765)
            Pair("18", "18"),     // Dramma Film (18), Dramma TV (18)
            Pair("35", "35")      // Commedia Film (35), Commedia TV (35)
          )
          for ((mGenre, tvGenre) in genreQueries) {
            try {
              val gMovies = fetchMoviesWithCache("genre_movies_$mGenre") {
                TmdbApiClient.service.discoverMoviesByGenre(cleanKey, mGenre, page = 1)
              }.results
              gMovies.forEach { m ->
                val key = tmdbKey(m.id, isTv = false)
                if (!fetchedItemsMap.containsKey(key)) {
                  fetchedItemsMap[key] = movieToMediaItem(m, currentList)
                }
              }
            } catch (e: Exception) {
              Log.w("MediaRepository", "Discover genre movies $mGenre failed: ${e.message}")
            }

            try {
              val gTv = fetchTvWithCache("genre_tv_$tvGenre") {
                TmdbApiClient.service.discoverTvByGenre(cleanKey, tvGenre, page = 1)
              }.results.filterNot { isBlockedTv(it.id, it.name) }
              gTv.forEach { t ->
                val key = tmdbKey(t.id, isTv = true)
                if (!fetchedItemsMap.containsKey(key)) {
                  fetchedItemsMap[key] = tvToMediaItem(t, currentList)
                }
              }
            } catch (e: Exception) {
              Log.w("MediaRepository", "Discover genre TV $tvGenre failed: ${e.message}")
            }
          }
          checkpoint("generi")

          database?.tmdbResponseCacheDao()?.deleteExpired()
        } catch (e: retrofit2.HttpException) {
          Log.w("MediaRepository", "TMDB HTTP error ${e.code()}: ${e.message}")
        } catch (e: Exception) {
          Log.w("MediaRepository", "TMDB error: ${e.message}")
        }
      }
    } finally {
      refreshInProgress.set(false)
    }
  }

  /**
   * Scarica l'intero catalogo di UN provider per UN tipo (Film o Serie TV) con
   * paginazione indipendente: parte dalla pagina 1 e si ferma su
   * `min(total_pages, MAX_PROVIDER_PAGES)` o su una pagina vuota.
   * Non esiste più il limite fisso `for (pg in 1..2)`.
   */
  private suspend fun crawlProviderCatalog(
    isTv: Boolean,
    providerIds: String,
    providerTag: String,
    apiKey: String,
    currentList: List<MediaItem>,
    fetchedItems: MutableMap<String, MediaItem>
  ) {
    var page = 1
    var totalPages = 0
    while (page <= MAX_PROVIDER_PAGES && (page == 1 || page <= totalPages)) {
      val cacheKey = providerCacheKey(providerIds, isTv, page)
      val pageItems: List<MediaItem> = try {
        if (isTv) {
          val response = fetchTvWithCache(cacheKey) {
            TmdbApiClient.service.discoverTvByProvider(
              apiKey = apiKey,
              providerId = providerIds,
              region = "IT",
              sortBy = "popularity.desc",
              language = "it-IT",
              page = page
            )
          }
          totalPages = response.totalPages ?: 0
          response.results.filterNot { isBlockedTv(it.id, it.name) }.map { tvToMediaItem(it, currentList, provider = providerTag) }
        } else {
          val response = fetchMoviesWithCache(cacheKey) {
            TmdbApiClient.service.discoverMoviesByProvider(
              apiKey = apiKey,
              providerId = providerIds,
              region = "IT",
              sortBy = "popularity.desc",
              language = "it-IT",
              page = page
            )
          }
          totalPages = response.totalPages ?: 0
          response.results.map { movieToMediaItem(it, currentList, provider = providerTag) }
        }
      } catch (e: Exception) {
        Log.w(
          "MediaRepository",
          "Discover $providerTag (${mediaTypeKey(isTv)}) pagina $page fallita: ${e.message}"
        )
        break
      }

      if (pageItems.isEmpty()) break

      pageItems.forEach { item ->
        val existing = fetchedItems[item.id]
        fetchedItems[item.id] = if (existing != null) {
          existing.copy(provider = providerTag)
        } else {
          item
        }
      }
      page++
    }
    Log.d(
      "MediaRepository",
      "Catalogo $providerTag (${mediaTypeKey(isTv)}): ${page - 1} pagine scaricate (total_pages=$totalPages, max=$MAX_PROVIDER_PAGES)"
    )
  }

  /**
   * Merge incrementale del catalogo + persistenza su Room (Single Source of Truth).
   * Nessuno scarto per poster/backdrop mancanti: un titolo con titolo valido resta
   * in catalogo; preferiti e progressi vengono SEMPRE preservati dalla riga Room.
   */
  private suspend fun mergeAndPersistCatalog(
    currentList: List<MediaItem>,
    fetchedItems: Map<String, MediaItem>
  ): Int {
    val finalMap = LinkedHashMap<String, MediaItem>()
    // Prima tutti i titoli appena fetchati (chiavi distinte movie/tv)
    fetchedItems.values.forEach { item ->
      finalMap[item.id] = item
    }

    // Poi sovrapponi/preserva quelli già noti: preferiti e progressi non si perdono mai
    val initMap = getInitialMedia().associateBy { it.id }
    currentList.forEach { cur ->
      val existingKey = cur.tmdbId?.let { tmdbKey(it, cur.type == MediaType.SERIE_TV) } ?: cur.id
      val fetched = finalMap[existingKey] ?: finalMap[cur.id]
      val init = initMap[cur.id]
      if (fetched != null) {
        finalMap[cur.id] = cur.copy(
          backdropUrl = fetched.backdropUrl ?: init?.backdropUrl ?: cur.backdropUrl,
          posterUrl = fetched.posterUrl ?: init?.posterUrl ?: cur.posterUrl,
          rating = fetched.rating,
          synopsis = if (cur.synopsis.length < fetched.synopsis.length) fetched.synopsis else cur.synopsis,
          provider = fetched.provider ?: cur.provider,
          isTrending = cur.isTrending || fetched.isTrending,
          isTop10 = cur.isTop10 || fetched.isTop10
        )
      } else {
        finalMap[cur.id] = if (init != null) {
          cur.copy(
            posterUrl = init.posterUrl ?: cur.posterUrl,
            backdropUrl = init.backdropUrl ?: cur.backdropUrl
          )
        } else {
          cur
        }
      }
    }

    // Catalogo PERSISTENTE e senza limite artificiale: non si elimina più un titolo
    // solo perché manca il poster/backdrop, basta che abbia un titolo.
    val combinedList = finalMap.values.filter { it.title.isNotBlank() }.sortedByDescending { it.rating }

    val db = database
    if (db != null) {
      try {
        val dao = db.cachedMediaDao()
        // Preserva SEMPRE preferiti, progressi ed episodio visto sui dati remoti
        val existingById = dao.getAllCachedMedia().associateBy { it.id }
        val preservedList = combinedList.map { item ->
          existingById[item.id]?.preserveUserStateInto(item) ?: item
        }
        dao.insertOrUpdate(preservedList.map { it.toEntity() })
        // Fallback solo se l'osservatore del Flow non fosse attivo
        if (dbObserverJob?.isActive != true) {
          _mediaList.value = mapEntitiesToUiState(dao.getAllCachedMedia())
        }
        return preservedList.size
      } catch (e: Exception) {
        Log.w("MediaRepository", "Salvataggio in Room fallito: ${e.message}")
        _mediaList.value = combinedList
        return combinedList.size
      }
    }
    // Nessun database disponibile: fallback in-memory
    _mediaList.value = combinedList
    return combinedList.size
  }

  private fun getInitialMedia(): List<MediaItem> = listOf(
    MediaItem(
      id = "dune_2",
      tmdbId = 693134,
      title = "Dune - Parte Due",
      originalTitle = "Dune: Part Two",
      synopsis = "Paul Atreides si unisce a Chani e ai Fremen sul pianeta desertico Arrakis, tramando una vendetta implacabile contro i cospiratori che hanno annientato la sua famiglia. Di fronte alla scelta tra l'amore della sua vita e il destino dell'universo, dovrà scongiurare un futuro terribile che solo lui può prevedere.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR10+", "Dolby Atmos", "16+"),
      backdropRes = R.drawable.banner_dune,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/eZ239CUp1d6OryZEBPnO2n87gMG.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/nhdQxDCI64rMgypYZpsF7UvbdJA.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/eYvF1LhPKuoBxOAmWjFTAK7EPWl.png",
      type = MediaType.FILM,
      year = 2024,
      durationMinutes = 166,
      rating = 8.9f,
      genres = listOf("Fantascienza", "Avventura", "Dramma"),
      director = "Denis Villeneuve",
      cast = listOf("Timothée Chalamet", "Zendaya", "Rebecca Ferguson", "Javier Bardem", "Austin Butler"),
      currentProgressMs = 0L,
      totalDurationMs = 166 * 60 * 1000L,
      isFavorite = false,
      provider = "hbo",
      isTop10 = true,
      isTrending = true,
    ),
    MediaItem(
      id = "the_last_of_us",
      tmdbId = 100088,
      title = "The Last of Us",
      originalTitle = "The Last of Us",
      synopsis = "Vent'anni dopo la caduta della civiltà moderna a causa di una pandemia fungina, Joel, un cinico contrabbandiere segnato da un lutto insuperabile, viene assoldato per scortare la quattordicenne Ellie attraverso un'America post-apocalittica e brutale.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Vision", "18+"),
      backdropRes = R.drawable.banner_lastofus,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/dmo6TYuuJgaYinXBPjrgG9mB5od.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/msYtgZbEo8tAOJ37T50kgqulpKf.png",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 59,
      seasonsCount = 2,
      rating = 9.1f,
      genres = listOf("Dramma", "Fantascienza", "Azione"),
      director = "Craig Mazin & Neil Druckmann",
      cast = listOf("Pedro Pascal", "Bella Ramsey", "Gabriel Luna", "Anna Torv"),
      currentProgressMs = 0L,
      totalDurationMs = 59 * 60 * 1000L,
      lastWatchedSeason = null,
      lastWatchedEpisode = null,
      isFavorite = false,
      provider = "hbo",
      isTop10 = true,
      isTrending = true,
      episodes = theLastOfUsEpisodes,
    ),
    MediaItem(
      id = "interstellar_cosmos",
      tmdbId = 157336,
      title = "Interstellar",
      originalTitle = "Interstellar",
      synopsis = "In un futuro in cui la Terra sta diventando inabitabile, un gruppo di audaci esploratori e astrofisici sfrutta un misterioso wormhole scoperto vicino a Saturno per intraprendere un viaggio interstellare oltre ogni confine conosciuto.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR10+", "IMAX", "12+"),
      backdropRes = R.drawable.banner_cosmos,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/xJHokMbljvjADYdit5fK5VQsXEG.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/yQvGrMoipbRoddT0ZR8tPoR7NfX.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/4Hu4w5p0mwL2Wvyk3gqu2JRDGHl.png",
      type = MediaType.FILM,
      year = 2014,
      durationMinutes = 169,
      rating = 8.8f,
      genres = listOf("Fantascienza", "Dramma", "Avventura"),
      director = "Christopher Nolan",
      cast = listOf("Matthew McConaughey", "Anne Hathaway", "Jessica Chastain", "Michael Caine"),
      currentProgressMs = 0L,
      totalDurationMs = 169 * 60 * 1000L,
      isFavorite = false,
      provider = "prime",
      isTop10 = true,
      isTrending = true,
    ),
    MediaItem(
      id = "stranger_things",
      tmdbId = 66732,
      title = "Stranger Things",
      originalTitle = "Stranger Things",
      synopsis = "Quando un ragazzino svanisce nel nulla in una tranquilla cittadina dell'Indiana nel 1983, i suoi amici, la famiglia e la polizia locale si ritrovano invischiati in un mistero straordinario che coinvolge esperimenti governativi segreti e forze soprannaturali.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "16+"),
      backdropRes = R.drawable.banner_cosmos,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/uOOtwVbSr4QDjAGIifLDwpb2Pdl.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/uyVM5qGksUzCgwo6UU0UrHex8Oj.png",
      type = MediaType.SERIE_TV,
      year = 2022,
      durationMinutes = 55,
      seasonsCount = 4,
      rating = 8.6f,
      genres = listOf("Sci-Fi & Fantasy", "Dramma", "Mistero"),
      director = "I fratelli Duffer",
      cast = listOf("Millie Bobby Brown", "Finn Wolfhard", "Winona Ryder", "David Harbour"),
      currentProgressMs = 0L,
      totalDurationMs = 55 * 60 * 1000L,
      lastWatchedSeason = null,
      lastWatchedEpisode = null,
      isFavorite = false,
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = strangerThingsEpisodes,
    ),
    MediaItem(
      id = "oppenheimer",
      tmdbId = 872585,
      title = "Oppenheimer",
      originalTitle = "Oppenheimer",
      synopsis = "La complessa parabola scientifica e morale del fisico teorico J. Robert Oppenheimer, direttore del Manhattan Project a Los Alamos durante la Seconda Guerra Mondiale per la costruzione della bomba atomica.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "14+"),
      backdropRes = R.drawable.banner_dune,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/fm6KqXpk3M2HVveHwCrBSSBaO0V.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/b07VisHvZb0WzUpA8VB77wfMXwg.png",
      type = MediaType.FILM,
      year = 2023,
      durationMinutes = 180,
      rating = 8.9f,
      genres = listOf("Biografico", "Dramma", "Storia"),
      director = "Christopher Nolan",
      cast = listOf("Cillian Murphy", "Emily Blunt", "Matt Damon", "Robert Downey Jr."),
      currentProgressMs = 0L,
      totalDurationMs = 180 * 60 * 1000L,
      isFavorite = false,
      provider = "hbo",
      isTop10 = true,
      isTrending = true,
    ),
    MediaItem(
      id = "shogun",
      tmdbId = 126308,
      title = "Shōgun",
      originalTitle = "Shōgun",
      synopsis = "Nel Giappone feudale del 1600, all'alba di una guerra civile epica che segnerà il futuro dell'impero, il potente daimyo Lord Toranaga combatte contro il Consiglio dei Reggenti, mentre una misteriosa nave europea naufraga sulle coste.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "18+"),
      backdropRes = R.drawable.banner_lastofus,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/bwSmgmd90hCWwqOKQYTEraeOZhJ.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/7O4iVfOMQmdCSxhOg1WnzG1AgYT.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/eqWtUYYsmW7cPkXzxAF8idOPEX8.png",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 60,
      seasonsCount = 1,
      rating = 8.7f,
      genres = listOf("Dramma", "Storico", "Guerra"),
      director = "Justin Marks & Rachel Kondo",
      cast = listOf("Hiroyuki Sanada", "Cosmo Jarvis", "Anna Sawai"),
      currentProgressMs = 0L,
      totalDurationMs = 60 * 60 * 1000L,
      isFavorite = false,
      provider = "disney",
      isTop10 = true,
      isTrending = true,
      episodes = shogunEpisodes,
    ),
    MediaItem(
      id = "severance",
      tmdbId = 95396,
      title = "Scissione",
      originalTitle = "Severance",
      synopsis = "Mark guida un team di dipendenti i cui ricordi sono stati chirurgicamente divisi tra vita lavorativa e privata. Quando un misterioso collega appare fuori dal lavoro, inizia un viaggio alla scoperta della verità sul loro impiego.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "16+"),
      backdropRes = R.drawable.banner_cosmos,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/ixgFmf1X59PUZam2qbAfskx2gQr.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/jnwqpZpmwvEo0CFLjDwfnnAf4ow.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/yyS1tALk7t3YdTNMOvR5gsnXINA.png",
      type = MediaType.SERIE_TV,
      year = 2022,
      durationMinutes = 55,
      seasonsCount = 2,
      rating = 8.7f,
      genres = listOf("Dramma", "Mistero", "Fantascienza"),
      director = "Ben Stiller & Aoife McArdle",
      cast = listOf("Adam Scott", "Patricia Arquette", "John Turturro", "Christopher Walken"),
      currentProgressMs = 0L,
      totalDurationMs = 55 * 60 * 1000L,
      isFavorite = false,
      provider = "prime",
      isTop10 = true,
      isTrending = true,
      episodes = severanceEpisodes,
    ),
    MediaItem(
      id = "the_batman",
      tmdbId = 414906,
      title = "The Batman",
      originalTitle = "The Batman",
      synopsis = "Nei suoi due anni di pattugliamento notturno come Batman, Bruce Wayne ha instillato la paura nel cuore dei criminali di Gotham City. Quando un sadico killer prende di mira l'élite con una serie di enigmi machiavellici, la pista lo porta nei bassifondi.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR10+", "Dolby Atmos", "14+"),
      backdropRes = R.drawable.banner_dune,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/rvtdN5XkWAfGX6xDuPL6yYS2seK.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/kfrNjwHRw0ORsPx8Scr3JJQuy1I.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/haibnO2TycgulXD1Fj7b94L9m9E.png",
      type = MediaType.FILM,
      year = 2022,
      durationMinutes = 176,
      rating = 8.1f,
      genres = listOf("Crime", "Mistero", "Thriller"),
      director = "Matt Reeves",
      cast = listOf("Robert Pattinson", "Zoë Kravitz", "Paul Dano", "Jeffrey Wright", "Colin Farrell"),
      currentProgressMs = 0L,
      totalDurationMs = 176 * 60 * 1000L,
      isFavorite = false,
      provider = "hbo",
      isTop10 = true,
      isTrending = true,
    ),
    MediaItem(
      id = "blade_runner_2049",
      tmdbId = 335984,
      title = "Blade Runner 2049",
      originalTitle = "Blade Runner 2049",
      synopsis = "Trent'anni dopo gli eventi del primo film, un nuovo blade runner, l'agente K della polizia di Los Angeles, dissotterra un segreto rimasto sepolto a lungo che ha il potenziale di far precipitare nel caos quel che resta della società.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "16+"),
      backdropRes = R.drawable.banner_dune,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/gNdLJU9TxrpGx4dkZidjys3fyy0.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/4XJQG11Ytl8mKfgbl2jkoY5ZuFX.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/gUoDt6GggEFXVzdyDTyF8ggrEm8.png",
      type = MediaType.FILM,
      year = 2017,
      durationMinutes = 164,
      rating = 8.2f,
      genres = listOf("Fantascienza", "Mistero", "Thriller"),
      director = "Denis Villeneuve",
      cast = listOf("Ryan Gosling", "Harrison Ford", "Ana de Armas", "Sylvia Hoeks"),
      currentProgressMs = 0L,
      totalDurationMs = 164 * 60 * 1000L,
      isFavorite = false,
      provider = "netflix",
      isTop10 = true,
      isTrending = false,
    ),
    MediaItem(
      id = "fallout",
      tmdbId = 106379,
      title = "Fallout",
      originalTitle = "Fallout",
      synopsis = "200 anni dopo l'apocalisse nucleare, gli abitanti dei confortevoli rifugi antiatomici Vault sono costretti a tornare nell'incredibilmente complesso, bizzarro e violento mondo contaminato dalle radiazioni che li attende in superficie.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "18+"),
      backdropRes = R.drawable.banner_dune,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/coaPCIqQBPUZsOnJcWZxhaORcDT.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/c15BtJxCXMrISLVmysdsnZUPQft.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/kyhbjHomRagzpNEeyKSssbkPWan.png",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 60,
      seasonsCount = 1,
      rating = 8.5f,
      genres = listOf("Fantascienza", "Azione", "Avventura"),
      director = "Jonathan Nolan",
      cast = listOf("Ella Purnell", "Walton Goggins", "Aaron Moten"),
      currentProgressMs = 0L,
      totalDurationMs = 60 * 60 * 1000L,
      isFavorite = false,
      provider = "prime",
      isTop10 = true,
      isTrending = true,
      episodes = falloutEpisodes,
    ),
    MediaItem(
      id = "gladiator_2",
      tmdbId = 558449,
      title = "Il Gladiatore II",
      originalTitle = "Gladiator II",
      synopsis = "Anni dopo aver assistito alla morte del venerato eroe Massimo per mano dello zio, Lucio deve entrare nel Colosseo dopo che la sua casa è stata conquistata dai tirannici imperatori che ora guidano Roma con pugno di ferro.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "16+"),
      backdropRes = R.drawable.banner_dune,
      backdropUrl = "https://image.tmdb.org/t/p/w1280/tOqIwliWMovSIZ9DyvHcHI7p2im.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/tVBCG6qHQQaq2doZsOvpGNcwMuP.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/jwXk1c2esVoEzVLplPiQubNVyFC.png",
      type = MediaType.FILM,
      year = 2024,
      durationMinutes = 148,
      rating = 8.0f,
      genres = listOf("Azione", "Avventura", "Dramma"),
      director = "Ridley Scott",
      cast = listOf("Paul Mescal", "Pedro Pascal", "Denzel Washington", "Connie Nielsen"),
      currentProgressMs = 0L,
      totalDurationMs = 148 * 60 * 1000L,
      isFavorite = false,
      provider = "prime",
      isTop10 = true,
      isTrending = true,
    ),
    // Nuovi titoli cinematografici TMDB completi
    MediaItem(
      id = "deadpool_wolverine",
      tmdbId = 533535,
      title = "Deadpool & Wolverine",
      originalTitle = "Deadpool & Wolverine",
      synopsis = "Un apatico Wade Wilson lavora come venditore di auto usate quando la Time Variance Authority lo strappa alla sua vita tranquilla per affidargli una missione che coinvolge il mutante Wolverine.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "16+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/by8z9Fe8y7p4jo2YlW2SZDnptyT.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/tDYG0QPL54nUKDUoBPdvtsmzbBI.jpg",
      type = MediaType.FILM,
      year = 2024,
      durationMinutes = 128,
      rating = 8.2f,
      genres = listOf("Azione", "Commedia", "Fantascienza"),
      director = "Shawn Levy",
      cast = listOf("Ryan Reynolds", "Hugh Jackman", "Emma Corrin"),
      provider = "disney",
      isTop10 = true,
      isTrending = true,
    ),
    MediaItem(
      id = "avatar_water",
      tmdbId = 76600,
      title = "Avatar: La via dell'acqua",
      originalTitle = "Avatar: The Way of Water",
      synopsis = "Ambientato più di dieci anni dopo gli eventi del primo film, Avatar: La via dell'acqua racconta la storia della famiglia Sully, il pericolo che li segue, i confini che oltrepassano per proteggersi.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR10+", "IMAX 3D", "12+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/kJsPVzdyBrYHLomuNv5SJDXUQ2f.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/idul2fSUPkk0EHP92EzmES6KGW4.jpg",
      type = MediaType.FILM,
      year = 2022,
      durationMinutes = 192,
      rating = 8.3f,
      genres = listOf("Fantascienza", "Avventura", "Azione"),
      director = "James Cameron",
      cast = listOf("Sam Worthington", "Zoe Saldana", "Sigourney Weaver"),
      provider = "disney",
      isTop10 = true,
      isTrending = false,
    ),
    MediaItem(
      id = "spider_verse_2",
      tmdbId = 569094,
      title = "Spider-Man: Across the Spider-Verse",
      originalTitle = "Spider-Man: Across the Spider-Verse",
      synopsis = "Miles Morales si ritrova catapultato nel Multiverso, dove incontra una squadra di Spider-Eroi incaricati di proteggerne l'esistenza stessa da minacce cosmiche.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "Dolby Atmos"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/4HodYYKEIsGOdinkGi2Ucz6X9i0.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg",
      type = MediaType.FILM,
      year = 2023,
      durationMinutes = 140,
      rating = 8.6f,
      genres = listOf("Animazione", "Azione", "Avventura", "Fantascienza"),
      director = "Joaquim Dos Santos",
      cast = listOf("Shameik Moore", "Hailee Steinfeld", "Oscar Isaac"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
    ),
    MediaItem(
      id = "top_gun_maverick",
      tmdbId = 361743,
      title = "Top Gun: Maverick",
      originalTitle = "Top Gun: Maverick",
      synopsis = "Dopo più di trent'anni di servizio come uno dei migliori aviatori della Marina, Pete 'Maverick' Mitchell guida una squadra di giovani piloti scelti per una missione ad altissimo rischio.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/odJ4hx6g6vBt4lBWKFD1tI8WS4x.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/62HCnUTziyWcpDaBO2i1DX17ljH.jpg",
      type = MediaType.FILM,
      year = 2022,
      durationMinutes = 130,
      rating = 8.4f,
      genres = listOf("Azione", "Dramma"),
      director = "Joseph Kosinski",
      cast = listOf("Tom Cruise", "Miles Teller", "Jennifer Connelly"),
      provider = "prime",
      isTop10 = true,
      isTrending = false,
    ),
    MediaItem(
      id = "glass_onion",
      tmdbId = 661374,
      title = "Glass Onion - Knives Out",
      originalTitle = "Glass Onion: A Knives Out Mystery",
      synopsis = "Il brillante investigatore Benoit Blanc viaggia in Grecia su un'isola privata dove un magnate della tecnologia ha radunato i suoi amici per un eccentrico gioco con delitto.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/y3uOfZAYwLkbvhunswBCskNMrfI.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/yhCxL5ebwiBwkmYrjL8gqJBZKNS.jpg",
      type = MediaType.FILM,
      year = 2022,
      durationMinutes = 139,
      rating = 7.8f,
      genres = listOf("Commedia", "Crime", "Mistero"),
      director = "Rian Johnson",
      cast = listOf("Daniel Craig", "Edward Norton", "Janelle Monáe"),
      provider = "netflix",
      isTop10 = false,
      isTrending = true,
    ),
    MediaItem(
      id = "inception",
      tmdbId = 27205,
      title = "Inception",
      originalTitle = "Inception",
      synopsis = "Dom Cobb è un ladro esperto nell'arte dell'estrazione di preziosi segreti dal profondo del subconscio durante il sonno. Gli viene offerta la redenzione a patto di realizzare l'innesto di un'idea.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR10+", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/s3TBrRGB1iav7gFOCNx3H31MoES.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg",
      type = MediaType.FILM,
      year = 2010,
      durationMinutes = 148,
      rating = 8.8f,
      genres = listOf("Fantascienza", "Azione", "Avventura"),
      director = "Christopher Nolan",
      cast = listOf("Leonardo DiCaprio", "Joseph Gordon-Levitt", "Elliot Page"),
      provider = "netflix",
      isTop10 = true,
      isTrending = false,
    ),
    MediaItem(
      id = "mad_max_fury_road",
      tmdbId = 76341,
      title = "Mad Max: Fury Road",
      originalTitle = "Mad Max: Fury Road",
      synopsis = "In una desolata terra post-apocalittica, Max Rockatansky unisce le forze con l'imperatrice ribelle Furiosa per fuggire attraverso il deserto dagli spietati inseguitori di Immortan Joe.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "16+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/gqrnQA6Xppdl8vIb2eJc58VC1tW.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/5gbm8m1gYvOmVGRcxteiplDLWdg.jpg",
      type = MediaType.FILM,
      year = 2015,
      durationMinutes = 120,
      rating = 8.3f,
      genres = listOf("Azione", "Avventura", "Fantascienza"),
      director = "George Miller",
      cast = listOf("Tom Hardy", "Charlize Theron", "Nicholas Hoult"),
      provider = "hbo",
      isTop10 = false,
      isTrending = true,
    ),
    MediaItem(
      id = "avengers_endgame",
      tmdbId = 299534,
      title = "Avengers: Endgame",
      originalTitle = "Avengers: Endgame",
      synopsis = "Dopo gli eventi devastanti di Infinity War, l'universo è in rovina. Con l'aiuto dei rimanenti alleati, gli Avengers si riuniscono ancora una volta per annullare le azioni di Thanos.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "IMAX", "12+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/7RyHsO4yDXtBv1zUU3mTpHeQ0d5.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/hj1xaEUGMiUxaFYoutlljtlkHSG.jpg",
      type = MediaType.FILM,
      year = 2019,
      durationMinutes = 181,
      rating = 8.6f,
      genres = listOf("Avventura", "Fantascienza", "Azione"),
      director = "Anthony & Joe Russo",
      cast = listOf("Robert Downey Jr.", "Chris Evans", "Mark Ruffalo", "Chris Hemsworth"),
      provider = "disney",
      isTop10 = true,
      isTrending = false,
    ),
    // Nuove Serie TV TMDB con stagioni complete
    MediaItem(
      id = "house_of_dragon",
      tmdbId = 94997,
      title = "House of the Dragon",
      originalTitle = "House of the Dragon",
      synopsis = "Ambientata 200 anni prima degli eventi de Il Trono di Spade, la serie racconta l'inizio della fine della Casa Targaryen e la sanguinosa guerra civile nota come la Danza dei Draghi.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/577eXC8wFQT0eUrJcgznSiFPRmk.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/paD01zRRCY3lXo038pvs2Avu0Q7.jpg",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 65,
      seasonsCount = 2,
      rating = 8.8f,
      genres = listOf("Dramma", "Sci-Fi & Fantasy", "Azione"),
      director = "Ryan J. Condal",
      cast = listOf("Emma D'Arcy", "Matt Smith", "Olivia Cooke"),
      provider = "hbo",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(94997, "House of the Dragon", "https://image.tmdb.org/t/p/w1280/etjA69o0Gh0ETez1tr6y2Bmzq8Q.jpg", "https://image.tmdb.org/t/p/w780/t9Xke5724fqW3429TkW0ZqSc0HG.jpg", 2),
    ),
    MediaItem(
      id = "the_boys",
      tmdbId = 76479,
      title = "The Boys",
      originalTitle = "The Boys",
      synopsis = "In un mondo in cui i supereroi abusano dei loro superpoteri invece di usarli per il bene, un gruppo di vigilanti noto come 'The Boys' intraprende una crociata per smascherarli.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/bq28ajZaoMyzEIm6REelqyqtEDZ.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/in1R2dDc421JxsoRWaIIAqVI2KE.jpg",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 60,
      seasonsCount = 4,
      rating = 8.6f,
      genres = listOf("Sci-Fi & Fantasy", "Azione", "Commedia"),
      director = "Eric Kripke",
      cast = listOf("Karl Urban", "Jack Quaid", "Antony Starr"),
      provider = "prime",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(76479, "The Boys", "https://image.tmdb.org/t/p/w1280/n6bUvigpRFqSwmPp1m2YADdbRBc.jpg", "https://image.tmdb.org/t/p/w780/2zmTngn1tYC1AvfnNDBpQI4r4QO.jpg", 3),
    ),
    MediaItem(
      id = "loki_series",
      tmdbId = 84958,
      title = "Loki",
      originalTitle = "Loki",
      synopsis = "Dopo essere fuggito con il Tesseract durante gli eventi di Avengers: Endgame, il Dio dell'Inganno viene catturato dalla misteriosa Time Variance Authority per riparare le linee temporali.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "12+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/q3jHCb4dMfYF6ojikKuHd6LscxC.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/qLcQc2Emc3GZfZAhHpf8wAbM0d2.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 52,
      seasonsCount = 2,
      rating = 8.4f,
      genres = listOf("Dramma", "Sci-Fi & Fantasy"),
      director = "Michael Waldron",
      cast = listOf("Tom Hiddleston", "Sophia Di Martino", "Owen Wilson"),
      provider = "disney",
      isTop10 = true,
      isTrending = false,
      episodes = generateEpisodesForTv(84958, "Loki", "https://image.tmdb.org/t/p/w1280/oOce47b7s8bW8V2qA74fQc9T64D.jpg", "https://image.tmdb.org/t/p/w780/vo4x83r8Z8k45r0XjW2a20K2vJ2.jpg", 2),
    ),
    MediaItem(
      id = "the_mandalorian",
      tmdbId = 82856,
      title = "The Mandalorian",
      originalTitle = "The Mandalorian",
      synopsis = "Dopo la caduta dell'Impero Galattico, un solitario cacciatore di taglie mandaloriano si fa strada nei recessi più remoti della galassia insieme al misterioso Grogu.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "12+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/9zcbqSxdsRMZWHYtyCd1nXPr2xq.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/sWgBv7LV2PRoQgkxwlibdGXKz1S.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 45,
      seasonsCount = 3,
      rating = 8.5f,
      genres = listOf("Sci-Fi & Fantasy", "Azione", "Avventura"),
      director = "Jon Favreau",
      cast = listOf("Pedro Pascal", "Carl Weathers", "Giancarlo Esposito"),
      provider = "disney",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(82856, "The Mandalorian", "https://image.tmdb.org/t/p/w1280/9zcbqSxdsRMZWHYtyCd1QPr2365.jpg", "https://image.tmdb.org/t/p/w780/eU1i6eHXlzMOlEq0ku1R07Y8vNu.jpg", 3),
    ),
    MediaItem(
      id = "wednesday_series",
      tmdbId = 119051,
      title = "Mercoledì",
      originalTitle = "Wednesday",
      synopsis = "Mentre frequenta la Nevermore Academy, Mercoledì Addams tenta di padroneggiare le sue abilità psichiche emergenti e sventare una mostruosa serie di omicidi che terrorizza la città.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "Dolby Atmos", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/iHSwvRVsRyxpX7FE7GbviaDvgGZ.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/sci4aAuTCWQsIs2uo7LoZVtMdD0.jpg",
      type = MediaType.SERIE_TV,
      year = 2022,
      durationMinutes = 50,
      seasonsCount = 1,
      rating = 8.5f,
      genres = listOf("Sci-Fi & Fantasy", "Mistero", "Commedia"),
      director = "Tim Burton",
      cast = listOf("Jenna Ortega", "Gwendoline Christie", "Emma Myers"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(119051, "Mercoledì", "https://image.tmdb.org/t/p/w1280/iHSwvRVsRyxpX7FE7GbviaDvgGZ.jpg", "https://image.tmdb.org/t/p/w780/9PFonQ95165agEjrmnR96fhR9Te.jpg", 1),
    ),
    MediaItem(
      id = "squid_game",
      tmdbId = 93405,
      title = "Squid Game",
      originalTitle = "Squid Game",
      synopsis = "Centinaia di persone indebitate accettano un misterioso invito a competere in giochi per bambini con un montepremi da capogiro, ma la posta in gioco si rivela mortale.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/2meX1nMdScFOoV4370rqHWKmXhY.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/nW5rupC5zMeAGgGBYXkr8JL8Xq7.jpg",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 55,
      seasonsCount = 2,
      rating = 8.4f,
      genres = listOf("Azione & Avventura", "Dramma", "Mistero"),
      director = "Hwang Dong-hyuk",
      cast = listOf("Lee Jung-jae", "Park Hae-soo", "Wi Ha-joon"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(93405, "Squid Game", "https://image.tmdb.org/t/p/w1280/2meX1nMdScFOoV4370rqHWKmngy.jpg", "https://image.tmdb.org/t/p/w780/dDlGgwLgQ7UvN7lV3F1bT0c8680.jpg", 2),
    ),
    MediaItem(
      id = "rings_of_power",
      tmdbId = 84773,
      title = "Il Signore degli Anelli: Gli Anelli del Potere",
      originalTitle = "The Lord of the Rings: The Rings of Power",
      synopsis = "Iniziando in un periodo di relativa pace, la serie segue un cast corale di personaggi che affrontano il temuto riemergere del male nella Terra di Mezzo.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR10+", "Dolby Atmos", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/o2wg8QiSCQrhj91tBfxunE3O5Ba.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/qUdlsQeJlInEfCD10pAWBO9W0Qb.jpg",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 68,
      seasonsCount = 2,
      rating = 7.7f,
      genres = listOf("Sci-Fi & Fantasy", "Azione", "Avventura"),
      director = "J.D. Payne & Patrick McKay",
      cast = listOf("Morfydd Clark", "Charlie Vickers", "Robert Aramayo"),
      provider = "prime",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(84773, "Gli Anelli del Potere", "https://image.tmdb.org/t/p/w1280/1rO4Tv1GhvrHGjmx9A23GHvQdI2.jpg", "https://image.tmdb.org/t/p/w780/mYLOqiStMxDK3fYZFirgrMt8z5d.jpg", 2),
    ),
    MediaItem(
      id = "reacher",
      tmdbId = 108978,
      title = "Reacher",
      originalTitle = "Reacher",
      synopsis = "Jack Reacher, ex investigatore della polizia militare, arriva nella cittadina rurale di Margrave dove viene ingiustamente arrestato per omicidio, scoprendo una vasta cospirazione.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Dolby Atmos", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/pF0qkRsrHkdYadPWY9AMeFZfcwk.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/xnMVkJFmzAWA6QpfdFfn4qOiTd3.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 50,
      seasonsCount = 2,
      rating = 8.3f,
      genres = listOf("Azione", "Crime", "Dramma"),
      director = "Nick Santora",
      cast = listOf("Alan Ritchson", "Malcolm Goodwin", "Willa Fitzgerald"),
      provider = "prime",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(108978, "Reacher", "https://image.tmdb.org/t/p/w1280/j736cRzBt0j5oI9e9508m2v543p.jpg", "https://image.tmdb.org/t/p/w780/j736cRzBt0j5oI9e9508m2v543p.jpg", 2),
    ),
    // Titoli Aggiuntivi Netflix Serie TV
    MediaItem(
      id = "dark_series",
      tmdbId = 70523,
      title = "Dark",
      originalTitle = "Dark",
      synopsis = "La scomparsa di due bambini in una cittadina tedesca rivela le doppie vite e le relazioni distorte tra quattro famiglie, dipanando un mistero che abbraccia tre generazioni e viaggi temporali.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "16+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/3lBDg3i6nn5R2NKFCJ6oKyUo2j5.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/apbrbWs8M9lyOpJYU5WXrpFbk1Z.jpg",
      type = MediaType.SERIE_TV,
      year = 2020,
      durationMinutes = 60,
      seasonsCount = 3,
      rating = 8.7f,
      genres = listOf("Fantascienza", "Sci-Fi & Fantasy", "Dramma", "Mistero"),
      director = "Baran bo Odar",
      cast = listOf("Louis Hofmann", "Oliver Masucci", "Jördis Triebel"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(70523, "Dark", "https://image.tmdb.org/t/p/w1280/3lBDg3i6nn5R2NKFCJ6oKyUo2j5.jpg", "https://image.tmdb.org/t/p/w780/apbrbWs8M9lyOpJYU5WXrpFbk1Z.jpg", 3),
    ),
    MediaItem(
      id = "black_mirror",
      tmdbId = 42009,
      title = "Black Mirror",
      originalTitle = "Black Mirror",
      synopsis = "Una serie antologica che esplora un futuro contorto e high-tech in cui le più grandi innovazioni dell'umanità e i suoi istinti più oscuri entrano in drammatica collisione.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/dg3OindVAGZBjlT3xYKqIAdukPL.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/seN6rRfN0I6n8iDXjlSMk1QjNcq.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 60,
      seasonsCount = 6,
      rating = 8.5f,
      genres = listOf("Fantascienza", "Sci-Fi & Fantasy", "Dramma"),
      director = "Charlie Brooker",
      cast = listOf("Bryce Dallas Howard", "Daniel Kaluuya", "Jon Hamm"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(42009, "Black Mirror", null, null, 2),
    ),
    MediaItem(
      id = "peaky_blinders",
      tmdbId = 60574,
      title = "Peaky Blinders",
      originalTitle = "Peaky Blinders",
      synopsis = "A Birmingham nel 1919, il famigerato Tommy Shelby guida i Peaky Blinders, una temuta gang criminale le cui lame sono nascoste nelle visiere dei loro berretti.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/dzq83RHwQcnP6WGJ6YkenIqeaa5.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/vUUqzWa2LnHIVqkaKVlVGkVcZIW.jpg",
      type = MediaType.SERIE_TV,
      year = 2022,
      durationMinutes = 58,
      seasonsCount = 6,
      rating = 8.6f,
      genres = listOf("Dramma", "Crime", "Azione"),
      director = "Steven Knight",
      cast = listOf("Cillian Murphy", "Paul Anderson", "Helen McCrory"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(60574, "Peaky Blinders", null, null, 2),
    ),
    MediaItem(
      id = "one_piece_live",
      tmdbId = 111110,
      title = "One Piece",
      originalTitle = "One Piece",
      synopsis = "Con il suo cappello di paglia e una ciurma stravagante, il giovane pirata Monkey D. Luffy intraprende un viaggio epico alla ricerca del leggendario tesoro One Piece.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/qD211Hb5XwFxrszzBBe5EUYJerh.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/mgrEi9VfRoN3bAuhf863f0sWC6A.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 55,
      seasonsCount = 1,
      rating = 8.3f,
      genres = listOf("Azione", "Avventura", "Commedia", "Fantascienza"),
      director = "Matt Owens & Steven Maeda",
      cast = listOf("Iñaki Godoy", "Mackenyu", "Emily Rudd"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(111110, "One Piece", null, null, 1),
    ),
    MediaItem(
      id = "the_crown",
      tmdbId = 65494,
      title = "The Crown",
      originalTitle = "The Crown",
      synopsis = "La cronaca della vita della regina Elisabetta II dagli anni quaranta ai giorni nostri, tra rivalità politiche, intrighi di corte e grandi eventi che hanno plasmato il regno.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/8VXhcrl5z2I1zEU9X3pkkNrZlD.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/1M876KPjulVwppEpldhdc8V4o68.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 58,
      seasonsCount = 6,
      rating = 8.4f,
      genres = listOf("Dramma", "Storia"),
      director = "Peter Morgan",
      cast = listOf("Claire Foy", "Olivia Colman", "Imelda Staunton"),
      provider = "netflix",
      isTop10 = false,
      isTrending = true,
      episodes = generateEpisodesForTv(65494, "The Crown", null, null, 2),
    ),
    MediaItem(
      id = "lupin_series",
      tmdbId = 96677,
      title = "Lupin",
      originalTitle = "Lupin",
      synopsis = "Ispirandosi alle avventure di Arsenio Lupin, il ladro gentiluomo Assane Diop decide di vendicare il padre per un'ingiustizia causata da una ricca e potente famiglia parigina.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/aY7zv2pfk9H0QxaaL3PBjvalbKQ.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/d59yXYdagCO8OSPZIV68lRoB5PF.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 48,
      seasonsCount = 3,
      rating = 8.1f,
      genres = listOf("Crime", "Dramma", "Mistero", "Commedia"),
      director = "George Kay",
      cast = listOf("Omar Sy", "Ludivine Sagnier", "Antoine Gouy"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(96677, "Lupin", null, null, 2),
    ),
    // Titoli Aggiuntivi HBO Serie TV & Film
    MediaItem(
      id = "succession_series",
      tmdbId = 76331,
      title = "Succession",
      originalTitle = "Succession",
      synopsis = "La famiglia Roy controlla una delle più grandi corporazioni di media e intrattenimento del pianeta. Quando il patriarca Logan si ammala, i suoi quattro figli combattono per il potere.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/d87JXX3DLkRJMfm5StCmmnmhHuX.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/z0XiwdrCQ9yVIr4O0pxzaAYRxdW.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 62,
      seasonsCount = 4,
      rating = 8.9f,
      genres = listOf("Dramma", "Commedia"),
      director = "Jesse Armstrong",
      cast = listOf("Brian Cox", "Jeremy Strong", "Sarah Snook", "Kieran Culkin"),
      provider = "hbo",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(76331, "Succession", null, null, 2),
    ),
    MediaItem(
      id = "true_detective",
      tmdbId = 46648,
      title = "True Detective",
      originalTitle = "True Detective",
      synopsis = "Serie antologica incentrata su investigatori della polizia e le loro vite personali mentre tentano di risolvere intricati omicidi rituali e cospirazioni.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/v8YFr8BbU9qsO8PYIulzTeM6Qk.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/zYqVTiHK5ZajYcNzAW7qWte5NWS.jpg",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 58,
      seasonsCount = 4,
      rating = 8.7f,
      genres = listOf("Crime", "Dramma", "Mistero"),
      director = "Nic Pizzolatto",
      cast = listOf("Matthew McConaughey", "Woody Harrelson", "Jodie Foster"),
      provider = "hbo",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(46648, "True Detective", null, null, 2),
    ),
    // Titoli Aggiuntivi Disney+ Serie TV
    MediaItem(
      id = "andor_series",
      tmdbId = 83867,
      title = "Andor",
      originalTitle = "Andor",
      synopsis = "In un'era densa di pericoli e cospirazioni, Cassian Andor intraprende un cammino che farà emergere la sua determinazione ribelle contro il tirannico Impero Galattico.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR10+", "Dolby Atmos", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/quCeAmVQHfsdcYkicbxZWVauCVb.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/dLp2ApdeAk3iPYxjAracJ4aTr3.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 50,
      seasonsCount = 1,
      rating = 8.6f,
      genres = listOf("Fantascienza", "Sci-Fi & Fantasy", "Azione", "Dramma"),
      director = "Tony Gilroy",
      cast = listOf("Diego Luna", "Kyle Soller", "Stellan Skarsgård"),
      provider = "disney",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(83867, "Andor", null, null, 1),
    ),
    MediaItem(
      id = "the_bear_series",
      tmdbId = 136315,
      title = "The Bear",
      originalTitle = "The Bear",
      synopsis = "Un giovane e talentuoso chef dell'alta cucina torna a Chicago per gestire la paninoteca di famiglia dopo la tragica morte del fratello maggiore.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "16+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/aJtG4txtmiRHwAAqENQHZvBs6kY.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/eKfVzzEazSIjJMrw9ADa2x8ksLz.jpg",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 35,
      seasonsCount = 3,
      rating = 8.7f,
      genres = listOf("Dramma", "Commedia"),
      director = "Christopher Storer",
      cast = listOf("Jeremy Allen White", "Ebon Moss-Bachrach", "Ayo Edebiri"),
      provider = "disney",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(136315, "The Bear", null, null, 2),
    ),
    // Titoli Aggiuntivi Prime Video Serie TV
    MediaItem(
      id = "invincible_series",
      tmdbId = 95557,
      title = "Invincible",
      originalTitle = "Invincible",
      synopsis = "Mark Grayson è un normale adolescente, tranne per il fatto che suo padre è Omni-Man, il supereroe più potente del pianeta. Poco dopo il suo 17° compleanno, Mark sviluppa i suoi poteri.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/9qrroces8C6R9aKr08hACNPVXdZ.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/4tblBrslcKSifMVZ3TmtT2ukMor.jpg",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 48,
      seasonsCount = 2,
      rating = 8.7f,
      genres = listOf("Animazione", "Azione", "Sci-Fi & Fantasy", "Dramma"),
      director = "Robert Kirkman",
      cast = listOf("Steven Yeun", "Sandra Oh", "J.K. Simmons"),
      provider = "prime",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(95557, "Invincible", null, null, 2),
    ),
    MediaItem(
      id = "fleabag_series",
      tmdbId = 67070,
      title = "Fleabag",
      originalTitle = "Fleabag",
      synopsis = "Una giovane donna londinese ironica, sfrontata e senza filtri naviga nella vita moderna cercando di fare i conti con un tragico lutto e relazioni complicate.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/hXdQ4MWsEOX6qg6VydKrLb3YJ4g.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/vFn0nLPcIggPH5LTWWaJ2hcsGlc.jpg",
      type = MediaType.SERIE_TV,
      year = 2019,
      durationMinutes = 27,
      seasonsCount = 2,
      rating = 8.7f,
      genres = listOf("Commedia", "Dramma"),
      director = "Phoebe Waller-Bridge",
      cast = listOf("Phoebe Waller-Bridge", "Sian Clifford", "Andrew Scott"),
      provider = "prime",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(67070, "Fleabag", null, null, 2),
    ),
    // Titoli Aggiuntivi per tutte le Categorie di Ricerca Globale
    MediaItem(
      id = "alien_romulus",
      tmdbId = 945961,
      title = "Alien: Romulus",
      originalTitle = "Alien: Romulus",
      synopsis = "Durante la perlustrazione di una stazione spaziale abbandonata, un gruppo di giovani colonizzatori spaziali si trova faccia a faccia con la forma di vita più terrificante dell'universo.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "16+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/iYqSQaWDttQIQzsxg9xHyg0bttG.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/1GO52DWcqWMSIoMqUAxCBK5UIdC.jpg",
      type = MediaType.FILM,
      year = 2024,
      durationMinutes = 119,
      rating = 8.1f,
      genres = listOf("Horror", "Fantascienza", "Thriller", "Sci-Fi & Fantasy"),
      director = "Fede Álvarez",
      cast = listOf("Cailee Spaeny", "David Jonsson", "Archie Renaux"),
      provider = "disney",
      isTop10 = true,
      isTrending = true
    ),
    MediaItem(
      id = "the_conjuring",
      tmdbId = 138843,
      title = "L'Evocazione - The Conjuring",
      originalTitle = "The Conjuring",
      synopsis = "Gli investigatori del paranormale Ed e Lorraine Warren si recano in una remota fattoria nel Rhode Island per aiutare una famiglia terrorizzata da una presenza demoniaca.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "16+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/ecKQlAEG95k62SMGhvX83oEqANK.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/inBZ5Lm0tUNSgMGZZ7f9vfHNKI2.jpg",
      type = MediaType.FILM,
      year = 2013,
      durationMinutes = 112,
      rating = 8.0f,
      genres = listOf("Horror", "Mistero", "Thriller"),
      director = "James Wan",
      cast = listOf("Patrick Wilson", "Vera Farmiga", "Lili Taylor"),
      provider = "hbo",
      isTop10 = false,
      isTrending = true
    ),
    MediaItem(
      id = "django_unchained",
      tmdbId = 68718,
      title = "Django Unchained",
      originalTitle = "Django Unchained",
      synopsis = "Nel Sud degli Stati Uniti, due anni prima della Guerra Civile, uno schiavo liberato dal cacciatore di taglie tedesco dottor Schultz si mette alla ricerca della moglie venduta a un crudele proprietario terriero.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR10+", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/2oZklIzUbvZXXzIFzv7Hi68d6xf.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/igYptikalrjsMueMx6rz15lE4Ts.jpg",
      type = MediaType.FILM,
      year = 2012,
      durationMinutes = 165,
      rating = 8.5f,
      genres = listOf("Western", "Azione", "Dramma"),
      director = "Quentin Tarantino",
      cast = listOf("Jamie Foxx", "Christoph Waltz", "Leonardo DiCaprio"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true
    ),
    MediaItem(
      id = "yellowstone_series",
      tmdbId = 73586,
      title = "Yellowstone",
      originalTitle = "Yellowstone",
      synopsis = "John Dutton cerca di proteggere il più grande ranch di bestiame contiguo degli Stati Uniti dagli attacchi di costruttori di terreni, da una riserva indiana e dal primo parco nazionale americano.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "16+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/ynSOcgDLZfdLCZfRSYZGiTgYJVo.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/peNC0eyc3TQJa6x4TdKcBPNP4t0.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 55,
      seasonsCount = 5,
      rating = 8.4f,
      genres = listOf("Western", "Dramma"),
      director = "Taylor Sheridan",
      cast = listOf("Kevin Costner", "Kelly Reilly", "Luke Grimes"),
      provider = "prime",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(73586, "Yellowstone", null, null, 2)
    ),
    MediaItem(
      id = "our_planet_doc",
      tmdbId = 83880,
      title = "Il Nostro Pianeta",
      originalTitle = "Our Planet",
      synopsis = "Una straordinaria serie di documentari naturalistici che celebra le meraviglie naturali della Terra esplorando l'impatto dei cambiamenti climatici su tutte le specie viventi.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "Dolby Atmos"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/p8EUX6MPSNLxVwqO3fCYTi896Ro.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/imR6dHfuApFPYalreSuWQHeI17s.jpg",
      type = MediaType.SERIE_TV,
      year = 2023,
      durationMinutes = 50,
      seasonsCount = 2,
      rating = 9.0f,
      genres = listOf("Documentario", "Documnetario", "Famiglia", "News"),
      director = "Alastair Fothergill",
      cast = listOf("David Attenborough"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(83880, "Il Nostro Pianeta", null, null, 2)
    ),
    MediaItem(
      id = "f1_drive_to_survive",
      tmdbId = 86564,
      title = "Formula 1: Drive to Survive",
      originalTitle = "Formula 1: Drive to Survive",
      synopsis = "Piloti, manager e proprietari di scuderie vivono a tutta velocità dentro e fuori dalla pista durante ogni spietata stagione del Campionato Mondiale di Formula 1.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "5.1", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/kf1FJxFK0Ef1A9ndx5ekBxpL0pV.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/br0IEMk8xPjlHwvioEAzcyvgAAw.jpg",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 40,
      seasonsCount = 6,
      rating = 8.6f,
      genres = listOf("Documentario", "Documnetario", "Reality", "News"),
      director = "James Gay-Rees",
      cast = listOf("Lewis Hamilton", "Max Verstappen", "Charles Leclerc"),
      provider = "netflix",
      isTop10 = true,
      isTrending = true,
      episodes = generateEpisodesForTv(86564, "Formula 1: Drive to Survive", null, null, 2)
    ),
    MediaItem(
      id = "la_la_land",
      tmdbId = 313369,
      title = "La La Land",
      originalTitle = "La La Land",
      synopsis = "A Los Angeles, un pianista jazz e un'aspirante attrice si innamorano mentre lottano per realizzare i propri sogni, dovendo presto scegliere tra amore e carriera.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/nlPCdZlHtRNcF6C9hzUH4ebmV1w.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/cWYy1YTVaZRAuxLtX1tYNvtzAt1.jpg",
      type = MediaType.FILM,
      year = 2016,
      durationMinutes = 128,
      rating = 8.3f,
      genres = listOf("Romance", "Commedia", "Dramma", "Musica"),
      director = "Damien Chazelle",
      cast = listOf("Ryan Gosling", "Emma Stone", "John Legend"),
      provider = "prime",
      isTop10 = true,
      isTrending = true
    ),
    MediaItem(
      id = "inside_out_2",
      tmdbId = 1022789,
      title = "Inside Out 2",
      originalTitle = "Inside Out 2",
      synopsis = "Il quartier generale mentale di Riley viene improvvisamente demolito per fare spazio a nuove e inaspettate emozioni: Ansia, Invidia, Ennui e Imbarazzo.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "HDR", "Famiglia"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/p5ozvmdgsmbWe0H8Xk7Rc8SCwAB.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/mVkg1k4Iun40ZFiBQPFAxJHRZBu.jpg",
      type = MediaType.FILM,
      year = 2024,
      durationMinutes = 96,
      rating = 8.2f,
      genres = listOf("Animazione", "Famiglia", "Commedia", "Kids", "Avventura"),
      director = "Kelsey Mann",
      cast = listOf("Amy Poehler", "Maya Hawke", "Kensington Tallman"),
      provider = "disney",
      isTop10 = true,
      isTrending = true
    ),
    MediaItem(
      id = "percy_jackson_series",
      tmdbId = 103540,
      title = "Percy Jackson e gli dei dell'Olimpo",
      originalTitle = "Percy Jackson and the Olympians",
      synopsis = "Il semidio Percy Jackson intraprende una pericolosa missione attraverso l'America per ritrovare la Folgore di Zeus e impedire una guerra catastrofica tra gli dei.",
      videoUrl = URL_COSMOS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "Kids"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/sAxx25ijwYQ8xsT56wu2IzvIRss.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/bd5ZIqiz1PRiLC7FLexzVBf8qu1.jpg",
      type = MediaType.SERIE_TV,
      year = 2024,
      durationMinutes = 45,
      seasonsCount = 1,
      rating = 8.1f,
      genres = listOf("Action & Adventure", "Famiglia", "Fantasy", "Kids", "Sci-Fi & Fantasy"),
      director = "Rick Riordan",
      cast = listOf("Walker Scobell", "Leah Sava Jeffries", "Aryan Simhadri"),
      provider = "disney",
      isTop10 = false,
      isTrending = true,
      episodes = generateEpisodesForTv(103540, "Percy Jackson", null, null, 1)
    ),
    MediaItem(
      id = "bohemian_rhapsody",
      tmdbId = 424694,
      title = "Bohemian Rhapsody",
      originalTitle = "Bohemian Rhapsody",
      synopsis = "La travolgente celebrazione dei Queen, della loro musica e del loro straordinario frontman Freddie Mercury, che ha sfidato gli stereotipi per diventare uno degli artisti più amati della storia.",
      videoUrl = URL_LASTOFUS,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "14+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/dcvbs8z0GEXslC1kCT77x19XDeR.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/guQaXtD3hyCqAkX9AdjPALlaR1A.jpg",
      type = MediaType.FILM,
      year = 2018,
      durationMinutes = 135,
      rating = 8.2f,
      genres = listOf("Musica", "Dramma", "Storia"),
      director = "Bryan Singer",
      cast = listOf("Rami Malek", "Lucy Boynton", "Gwilym Lee"),
      provider = "disney",
      isTop10 = true,
      isTrending = true
    ),
    MediaItem(
      id = "1917_war_movie",
      tmdbId = 530915,
      title = "1917",
      originalTitle = "1917",
      synopsis = "Al culmine della Prima Guerra Mondiale, due giovani soldati britannici ricevono una missione apparentemente impossibile: attraversare il territorio nemico per consegnare un messaggio vitale.",
      videoUrl = URL_OPPENHEIMER,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Atmos", "16+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/2lBOQK06tltt8SQaswgb8d657Mv.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/ouxbMjaK6SintHMSUIzy2dWSIwp.jpg",
      type = MediaType.FILM,
      year = 2019,
      durationMinutes = 119,
      rating = 8.5f,
      genres = listOf("Guerra", "Guerra & Politica", "Azione", "Dramma", "Storia"),
      director = "Sam Mendes",
      cast = listOf("George MacKay", "Dean-Charles Chapman", "Mark Strong"),
      provider = "prime",
      isTop10 = true,
      isTrending = true
    ),
    MediaItem(
      id = "el_camino_film",
      tmdbId = 559969,
      title = "El Camino: Il film di Breaking Bad",
      originalTitle = "El Camino: A Breaking Bad Movie",
      synopsis = "Dopo la sua drammatica fuga dalla prigionia, Jesse Pinkman deve fare i conti con il proprio passato per potersi costruire una nuova vita e un nuovo futuro.",
      videoUrl = URL_DUNE,
      resolution = VideoResolution.UHD_4K,
      qualityTags = listOf("4K", "Dolby Vision", "18+"),
      backdropUrl = "https://image.tmdb.org/t/p/w1280/uLXK1LQM28XovWHPao3ViTeggXA.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/23QhQR9nCXaJ1DhFUg4MVghUtu4.jpg",
      type = MediaType.FILM,
      year = 2019,
      durationMinutes = 122,
      rating = 8.0f,
      genres = listOf("Televisione film", "Film TV", "Crime", "Dramma", "Thriller"),
      director = "Vince Gilligan",
      cast = listOf("Aaron Paul", "Jesse Plemons", "Robert Forster"),
      provider = "netflix",
      isTop10 = false,
      isTrending = true
    )
  )
}

