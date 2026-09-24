package com.example.data.repository

import android.util.Log
import com.example.BuildConfig
import com.example.R
import com.example.data.api.TmdbApiClient
import com.example.data.model.AudioTrack
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SubtitleTrack
import com.example.data.model.VideoResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

object MediaRepository {

  // Video streams ad alta definizione stabili (HLS multi-bitrate e MP4 ad alte prestazioni)
  private const val URL_DUNE = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"
  private const val URL_LASTOFUS = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"
  private const val URL_COSMOS = "https://vjs.zencdn.net/v/oceans.mp4"
  private const val URL_OPPENHEIMER = "https://storage.googleapis.com/exoplayer-test-media-1/mp4/frame-counter-one-hour.mp4"
  const val FALLBACK_VIDEO_URL = "https://vjs.zencdn.net/v/oceans.mp4"

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
      currentProgressMs = 81 * 60 * 1000L, // completato
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
      currentProgressMs = 53 * 60 * 1000L,
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
      currentProgressMs = 75 * 60 * 1000L,
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
      currentProgressMs = 45 * 60 * 1000L,
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
      currentProgressMs = 31 * 60 * 1000L, // 28m rimanenti!
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
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
      videoUrl = URL_COSMOS,
      currentProgressMs = 18 * 60 * 1000L,
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
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/9faGSFi5jam6pDWGNdFaF8IRneP.jpg",
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
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/9faGSFi5jam6pDWGNdFaF8IRneP.jpg",
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
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/9faGSFi5jam6pDWGNdFaF8IRneP.jpg",
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
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/9faGSFi5jam6pDWGNdFaF8IRneP.jpg",
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
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/9faGSFi5jam6pDWGNdFaF8IRneP.jpg",
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
      thumbnailUrl = "https://image.tmdb.org/t/p/w780/9faGSFi5jam6pDWGNdFaF8IRneP.jpg",
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

  val featuredHero: MediaItem
    get() = _mediaList.value.firstOrNull { it.id == "dune_2" } ?: getInitialMedia().first()

  fun getMediaById(id: String): MediaItem? {
    return _mediaList.value.find { it.id == id }
  }

  fun toggleFavorite(id: String) {
    _mediaList.update { list ->
      list.map { item ->
        if (item.id == id) item.copy(isFavorite = !item.isFavorite) else item
      }
    }
  }

  fun updateProgress(id: String, progressMs: Long) {
    _mediaList.update { list ->
      list.map { item ->
        if (item.id == id) {
          item.copy(currentProgressMs = progressMs)
        } else {
          item
        }
      }
    }
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

  suspend fun refreshTmdbData(apiKey: String = BuildConfig.TMDB_API_KEY) {
    if (apiKey.isBlank() || apiKey == "DEFAULT_TMDB_API_KEY") {
      Log.d("MediaRepository", "TMDB API Key non configurata o placeholder: uso catalogo precaricato TMDB")
      return
    }

    withContext(Dispatchers.IO) {
      try {
        val trendingMovies = TmdbApiClient.service.getTrendingMovies(apiKey)
        val trendingTv = TmdbApiClient.service.getTrendingTv(apiKey)

        val movieLogos = trendingMovies.results.take(6)
          .associate { it.id to fetchLogoUrl(movieId = it.id, apiKey = apiKey) }
        val tvLogos = trendingTv.results.take(6)
          .associate { it.id to fetchLogoUrl(tvId = it.id, apiKey = apiKey) }

        val updatedItems = _mediaList.value.toMutableList()
        var updatedExisting = 0
        var addedNew = 0
        var skippedDuplicates = 0

        // 1) Aggiorna i preloaded esistenti (dedup su tmdbId): solo campi TMDB,
        //    preservando isFavorite, currentProgressMs, episodi e altri dati locali utente.
        for (movie in trendingMovies.results) {
          val existingIndex = updatedItems.indexOfFirst { it.tmdbId == movie.id }
          if (existingIndex >= 0) {
            val existing = updatedItems[existingIndex]
            updatedItems[existingIndex] = existing.copy(
              title = movie.title?.takeIf { it.isNotBlank() } ?: existing.title,
              synopsis = if (!movie.overview.isNullOrBlank()) movie.overview else existing.synopsis,
              rating = movie.voteAverage?.takeIf { it > 0f } ?: existing.rating,
              year = parseTmdbYear(movie.releaseDate) ?: existing.year,
              backdropUrl = TmdbApiClient.backdropUrl(movie.backdropPath) ?: existing.backdropUrl,
              posterUrl = TmdbApiClient.posterUrl(movie.posterPath) ?: existing.posterUrl,
              logoUrl = movieLogos[movie.id] ?: existing.logoUrl,
            )
            updatedExisting++
          }
        }
        for (tv in trendingTv.results) {
          val existingIndex = updatedItems.indexOfFirst { it.tmdbId == tv.id }
          if (existingIndex >= 0) {
            val existing = updatedItems[existingIndex]
            updatedItems[existingIndex] = existing.copy(
              title = tv.name?.takeIf { it.isNotBlank() } ?: existing.title,
              synopsis = if (!tv.overview.isNullOrBlank()) tv.overview else existing.synopsis,
              rating = tv.voteAverage?.takeIf { it > 0f } ?: existing.rating,
              year = parseTmdbYear(tv.firstAirDate) ?: existing.year,
              backdropUrl = TmdbApiClient.backdropUrl(tv.backdropPath) ?: existing.backdropUrl,
              posterUrl = TmdbApiClient.posterUrl(tv.posterPath) ?: existing.posterUrl,
              logoUrl = tvLogos[tv.id] ?: existing.logoUrl,
            )
            updatedExisting++
          }
        }

        // 2) Aggiunge come NUOVI item i risultati TMDB non ancora in catalogo (dedup rigoroso su tmdbId).
        //    I nuovi titoli usano il video fallback già presente (nessuna modifica al player).
        for (movie in trendingMovies.results) {
          if (updatedItems.none { it.tmdbId == movie.id }) {
            updatedItems.add(
              MediaItem(
                id = "tmdb_movie_${movie.id}",
                tmdbId = movie.id,
                title = movie.title ?: movie.originalTitle ?: "Senza titolo",
                originalTitle = movie.originalTitle ?: movie.title ?: "",
                synopsis = movie.overview ?: "",
                videoUrl = FALLBACK_VIDEO_URL,
                backdropUrl = TmdbApiClient.backdropUrl(movie.backdropPath),
                posterUrl = TmdbApiClient.posterUrl(movie.posterPath),
                logoUrl = movieLogos[movie.id],
                type = MediaType.FILM,
                year = parseTmdbYear(movie.releaseDate) ?: 0,
                rating = movie.voteAverage ?: 0f,
                genres = tmdbGenreNames(movie.genreIds),
              )
            )
            addedNew++
          } else {
            skippedDuplicates++
          }
        }
        for (tv in trendingTv.results) {
          if (updatedItems.none { it.tmdbId == tv.id }) {
            updatedItems.add(
              MediaItem(
                id = "tmdb_tv_${tv.id}",
                tmdbId = tv.id,
                title = tv.name ?: tv.originalName ?: "Senza titolo",
                originalTitle = tv.originalName ?: tv.name ?: "",
                synopsis = tv.overview ?: "",
                videoUrl = FALLBACK_VIDEO_URL,
                backdropUrl = TmdbApiClient.backdropUrl(tv.backdropPath),
                posterUrl = TmdbApiClient.posterUrl(tv.posterPath),
                logoUrl = tvLogos[tv.id],
                type = MediaType.SERIE_TV,
                year = parseTmdbYear(tv.firstAirDate) ?: 0,
                rating = tv.voteAverage ?: 0f,
                genres = tmdbGenreNames(tv.genreIds),
              )
            )
            addedNew++
          } else {
            skippedDuplicates++
          }
        }

        _mediaList.value = updatedItems
        Log.d(
          "MediaRepository",
          "TMDB merge completato: nuovi=$addedNew, aggiornati=$updatedExisting, duplicati_evitati=$skippedDuplicates"
        )
      } catch (e: Exception) {
        Log.e("MediaRepository", "Errore durante sincronizzazione TMDB: ${e.message}", e)
      }
    }
  }

  // --- Helpers mapping DTO TMDB → MediaItem (riusano i modelli esistenti, nessuna nuova architettura) ---

  private fun parseTmdbYear(date: String?): Int? =
    date?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2100 }

  // Mappa minimale genre_id TMDB → nome in italiano, compatibile con MediaItem.genres: List<String>
  private val tmdbGenreById = mapOf(
    28 to "Azione", 12 to "Avventura", 16 to "Animazione", 35 to "Commedia",
    80 to "Crime", 99 to "Documentario", 18 to "Dramma", 10751 to "Famiglia",
    14 to "Fantasy", 36 to "Storia", 27 to "Horror", 10402 to "Musica",
    9648 to "Mistero", 10749 to "Romance", 878 to "Fantascienza",
    10770 to "Film TV", 53 to "Thriller", 10752 to "Guerra", 37 to "Western",
    10759 to "Azione & Avventura", 10762 to "Bambini", 10763 to "News",
    10764 to "Reality", 10765 to "Fantascienza & Fantasy", 10766 to "Soap",
    10767 to "Talk Show", 10768 to "Guerra & Politica",
  )

  private fun tmdbGenreNames(genreIds: List<Int>?): List<String> =
    genreIds?.mapNotNull { tmdbGenreById[it] } ?: emptyList()

  // Logo tramite il meccanismo /images già in uso (it → en → primo disponibile)
  private suspend fun fetchLogoUrl(movieId: Int? = null, tvId: Int? = null, apiKey: String): String? {
    return try {
      val images = when {
        movieId != null -> TmdbApiClient.service.getMovieImages(movieId, apiKey)
        tvId != null -> TmdbApiClient.service.getTvImages(tvId, apiKey)
        else -> return null
      }
      val logo = images.logos?.firstOrNull { it.language == "it" }
        ?: images.logos?.firstOrNull { it.language == "en" }
        ?: images.logos?.firstOrNull()
      TmdbApiClient.logoUrl(logo?.filePath)
    } catch (e: Exception) {
      null
    }
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
      backdropUrl = "https://image.tmdb.org/t/p/w1280/xOMo8BRK7PfcJv9JCnx7s520DRq.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/6izwz7rsy95ARzTR3poZ8H6c5pp.jpg",
      logoUrl = "https://image.tmdb.org/t/p/w500/eYvF1LhPKuoBxOAmWjFTAK7EPWl.png",
      type = MediaType.FILM,
      year = 2024,
      durationMinutes = 166,
      rating = 8.9f,
      genres = listOf("Fantascienza", "Avventura", "Dramma"),
      director = "Denis Villeneuve",
      cast = listOf("Timothée Chalamet", "Zendaya", "Rebecca Ferguson", "Javier Bardem", "Austin Butler"),
      currentProgressMs = 72 * 60 * 1000L, // 1h 12m guardati
      totalDurationMs = 166 * 60 * 1000L, // 2h 46m totali
      isFavorite = true,
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
      currentProgressMs = 31 * 60 * 1000L,
      totalDurationMs = 59 * 60 * 1000L,
      lastWatchedSeason = 1,
      lastWatchedEpisode = 5,
      isFavorite = true,
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
      currentProgressMs = 105 * 60 * 1000L, // 1h 45m
      totalDurationMs = 169 * 60 * 1000L,
      isFavorite = false,
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
      currentProgressMs = 18 * 60 * 1000L,
      totalDurationMs = 55 * 60 * 1000L,
      lastWatchedSeason = 4,
      lastWatchedEpisode = 1,
      isFavorite = true,
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
      backdropUrl = "https://image.tmdb.org/t/p/w1280/9faGSFi5jam6pDWGNdFaF8IRneP.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/pPHpeI2X1qEd1CS1SeyrdhZ4qnT.jpg",
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
      isFavorite = true,
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
      backdropUrl = "https://image.tmdb.org/t/p/w1280/b0PlSFdDwbyK0cf5RxwDpaxtQvQ.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/74xTEgt7R36Fpooo50r9T25onhq.jpg",
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
      backdropUrl = "https://image.tmdb.org/t/p/w1280/ilRyAZwNpHq5EN49qHXIg3Spv6N.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/gajva2L0rPYkEWjzgFlBXCAVBE5.jpg",
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
      isFavorite = true,
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
      backdropUrl = "https://image.tmdb.org/t/p/w1280/euYIwmwkmz95mnExvufVGyq5Hgj.jpg",
      posterUrl = "https://image.tmdb.org/t/p/w780/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg",
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
    ),
  )
}

