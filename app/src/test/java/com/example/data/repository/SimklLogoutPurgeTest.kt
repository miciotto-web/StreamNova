package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CachedMediaItemEntity
import com.example.data.local.EpisodeProgressEntity
import com.example.data.local.MEDIA_SOURCE_SIMKL
import com.example.data.local.StreamNovaDatabase
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.remote.simkl.SimklApi
import com.example.data.remote.simkl.SimklApiClient
import com.example.data.remote.simkl.SimklSyncResult
import com.example.data.remote.simkl.SimklSyncStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Disconnessione dell'account SIMKL: cosa resta visibile e cosa viene rimosso.
 *
 * Gli scenari coproni i nove casi richiesti:
 *  1. account collegato: i preferiti della watchlist sono visibili (con provenienza);
 *  2. account collegato: Continua a guardare è visibile (con provenienza);
 *  3. disconnesso: i preferiti SIMKL scompaiono subito (interfaccia e Room);
 *  4. disconnesso: Continua a guardare SIMKL scompare subito;
 *  5. disconnesso: i dati restano assenti anche dopo il riavvio dell'app;
 *  6. disconnesso: i dati LOCALI (preferiti e progressi) restano disponibili;
 *  7. riconnesso: i dati vengono nuovamente sincronizzati da zero;
 *  8. logout durante una sincronizzazione: nessun dato SIMKL viene ripubblicato e
 *     nessuna credenziale "risuscita" nel datastore;
 *  9. nessun danno ai dati di altri provider: il catalogo locale resta intatto e i dati
 *     STORICI senza provenienza (scritti prima della tracciatura) non vengono toccati.
 *
 * Il percorso è quello reale: MockWebServer per SIMKL, Room in memoria per il catalogo.
 * Nessun titolo viene inventato: le voci SIMKL agganciano schede già presenti nel
 * catalogo locale (come nei test di sincronizzazione), gli import "veri" sono creati
 * con `upsertExternalItem(source = MEDIA_SOURCE_SIMKL)`, il punto unico in cui il
 * flag `importedFromSimkl` viene scritto in produzione.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimklLogoutPurgeTest {

  private lateinit var db: StreamNovaDatabase
  private lateinit var server: MockWebServer
  private lateinit var api: SimklApi

  private var activitiesBody: String = ""
  private var playbackBody: String = "[]"
  private var watchlistBodies: Map<String, String> = emptyMap()
  private var libraryBody: String = "{}"

  /** Ritardo artificiale del corpo di `/sync/playback`: apre la finestra per il logout. */
  private var playbackDelayMs: Long = 0

  private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())

  private lateinit var defaultRuntimeProvider: suspend (Int, Int, Int) -> Int?
  private lateinit var defaultMovieRuntimeProvider: suspend (Int) -> Int?

  @Before
  fun setUp() = runBlocking {
    val tick = COUNTER.getAndIncrement()
    activitiesBody = activitiesBodyFor(tick)

    defaultRuntimeProvider = MediaRepository.episodeRuntimeProvider
    MediaRepository.episodeRuntimeProvider = { _, _, _ -> null }
    defaultMovieRuntimeProvider = MediaRepository.movieRuntimeProvider
    MediaRepository.movieRuntimeProvider = { _ -> null }

    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    AppSettingsRepository.setSimklClientId("client-di-prova")
    db = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    MediaRepository.init(context, db)
    // Stato di partenza deterministico: il repository è un singleton di processo e i test
    // precedenti possono aver lasciato titoli (e preferiti locali) in memoria, con
    // l'osservatore ancora agganciato al database di quello precedente.
    MediaRepository.setDatabaseForTesting(null)
    MediaRepository.setDatabaseForTesting(db)
    MediaRepository.loadFromCache()
    assertTrue(attendi { MediaRepository.mediaList.value.isNotEmpty() })

    server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val endpoint = path.split("?", limit = 2)[0]
        recorded.add(endpoint)
        if (endpoint == "/sync/playback" && playbackDelayMs > 0) {
          return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(playbackBody)
            .setBodyDelay(playbackDelayMs, TimeUnit.MILLISECONDS)
        }
        val body = when {
          endpoint == "/oauth2/device" -> OAUTH_DEVICE
          endpoint == "/oauth2/token" -> OAUTH_TOKEN
          endpoint == "/users/settings" -> USER_SETTINGS
          endpoint == "/sync/activities" -> activitiesBody
          endpoint == "/sync/playback" -> playbackBody
          endpoint.endsWith("/plantowatch") -> watchlistBodies[endpoint].orEmpty().ifEmpty { "{}" }
          endpoint.startsWith("/sync/all-items/shows") -> libraryBody
          else -> "{}"
        }
        return MockResponse().setResponseCode(200)
          .setHeader("Content-Type", "application/json")
          .setBody(body)
      }
    }
    server.start()
    api = SimklApiClient.createApi(
      client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build(),
      baseUrl = server.url("/").toString()
    )
    SimklRepository.init(context, api)
    SimklRepository.savePendingPlaybacks(emptyList())
    collegaAccount()
  }

  @After
  fun tearDown() {
    MediaRepository.episodeRuntimeProvider = defaultRuntimeProvider
    MediaRepository.movieRuntimeProvider = defaultMovieRuntimeProvider
    runBlocking {
      SimklRepository.signOut()
      SimklRepository.savePendingPlaybacks(emptyList())
    }
    server.shutdown()
    db.close()
  }

  // ── 1 + 2: con l'account collegato i dati sono visibili, con la provenienza scritta ──

  @Test
  fun conAccountCollegatoPreferitiEProgressiSonoVisibiliConProvenienza() = runBlocking {
    catalogo("tmdb_m_5501", 5501, "Il Ladro di Sogni", MediaType.FILM)
    playbackBody = """
      [{"id":11,"progress":45.5,"paused_at":"2026-05-12T09:15:00.000Z","type":"movie",
        "movie":{"title":"Il Ladro di Sogni","year":2020,
                 "ids":{"simkl":5501,"slug":"il-ladro-di-sogni","tmdb":"5501","imdb":"tt5501000"}}}]
    """.trimIndent()
    watchlist(
      "movies" to """
        {"movies":[{"status":"plantowatch","added_to_watchlist_at":"2026-04-10T20:13:02Z",
          "movie":{"title":"Il Film da Guardare","year":2020,
                   "ids":{"simkl":5601,"slug":"il-film-da-guardare","tmdb":"5601","imdb":"tt5601000"}}}]}
      """.trimIndent(),
      "shows" to "{}", "anime" to "{}"
    )

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.SYNCED, result.status)
    // Preferiti visibili, con provenienza SIMKL scritta su disco.
    assertTrue(attendi { inCatalogo("tmdb_m_5601")?.isFavorite == true })
    assertTrue(attendi { rigaRoom("tmdb_m_5601")?.favoriteSource == MEDIA_SOURCE_SIMKL })
    // Continua a guardare visibile, stessa provenienza.
    assertTrue((inCatalogo("tmdb_m_5501")?.currentProgressMs ?: 0L) > 0L)
    assertTrue(attendi { rigaRoom("tmdb_m_5501")?.progressSource == MEDIA_SOURCE_SIMKL })
  }

  @Test
  fun conAccountCollegatoIlProgressoEpisodioSimklHaLaSuaProvenienza() = runBlocking {
    catalogo("tmdb_tv_5503", 5503, "La Casa Senza Fine 2", MediaType.SERIE_TV, episodiTemporada = 3)
    playbackBody = """
      [{"id":12,"progress":60,"paused_at":"2026-05-13T10:36:01Z","type":"episode",
        "episode":{"season":1,"number":2,"title":"Capitolo Due"},
        "show":{"title":"La Casa Senza Fine 2","year":2020,
                "ids":{"simkl":5503,"slug":"la-casa-senza-fine-2","tmdb":"5503","imdb":"tt5503000"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.SYNCED, result.status)
    assertEquals(1, result.adoptedEpisodes)
    assertTrue(attendi { rigaEpisodio("tmdb_tv_5503", 1, 2)?.source == MEDIA_SOURCE_SIMKL })
    assertTrue(attendi { rigaRoom("tmdb_tv_5503")?.progressSource == MEDIA_SOURCE_SIMKL })
    assertEquals(2, inCatalogo("tmdb_tv_5503")?.lastWatchedEpisode)
  }

  // ── 3: il preferito SIMKL esce dai Preferiti alla disconnessione ──────────────

  @Test
  fun ilLogoutRimuoveSubitoIPreferitiProvenientiDaSimkl() = runBlocking {
    catalogo("tmdb_m_5602", 5602, "Titolo in Watchlist", MediaType.FILM)
    watchlist(
      "movies" to """
        {"movies":[{"status":"plantowatch","added_to_watchlist_at":"2026-04-10T20:13:02Z",
          "movie":{"title":"Titolo in Watchlist","year":2020,
                   "ids":{"simkl":5602,"slug":"titolo-in-watchlist","tmdb":"5602","imdb":"tt5602000"}}}]}
      """.trimIndent(),
      "shows" to "{}", "anime" to "{}"
    )
    assertEquals(SimklSyncStatus.SYNCED, SimklSyncManager.sync(force = true).status)
    assertTrue(attendi { inCatalogo("tmdb_m_5602")?.isFavorite == true })

    SimklRepository.signOut()

    // Interfaccia (mediaList) e persistenza (Room) nello stesso istante: nessuna copia.
    assertEquals(false, inCatalogo("tmdb_m_5602")?.isFavorite)
    assertNull(inCatalogo("tmdb_m_5602")?.favoriteSource)
    assertEquals(false, rigaRoom("tmdb_m_5602")?.isFavorite)
    assertNull(rigaRoom("tmdb_m_5602")?.favoriteSource)
  }

  // ── 4: Continua a guardare SIMKL esce dalla riga alla disconnessione ──────────

  @Test
  fun ilLogoutRimuoveSubitoIlProgressoProvenienteDaSimkl() = runBlocking {
    catalogo("tmdb_m_5501", 5501, "Il Ladro di Sogni", MediaType.FILM)
    playbackBody = """
      [{"id":11,"progress":45.5,"paused_at":"2026-05-12T09:15:00.000Z","type":"movie",
        "movie":{"title":"Il Ladro di Sogni","year":2020,
                 "ids":{"simkl":5501,"slug":"il-ladro-di-sogni","tmdb":"5501","imdb":"tt5501000"}}}]
    """.trimIndent()
    assertEquals(SimklSyncStatus.SYNCED, SimklSyncManager.sync(force = true).status)
    assertTrue(attendi { (rigaRoom("tmdb_m_5501")?.currentProgressMs ?: 0L) > 0L })

    SimklRepository.signOut()

    val item = inCatalogo("tmdb_m_5501")
    assertEquals("nessun progresso di visione residuo", 0L, item?.currentProgressMs ?: -1L)
    assertNull(item?.lastWatchedAt)
    assertNull(item?.progressSource)
    val persisted = rigaRoom("tmdb_m_5501")
    assertEquals(0L, persisted?.currentProgressMs ?: -1L)
    assertNull(persisted?.lastWatchedAt)
    assertNull(persisted?.progressSource)
    assertNull("nessuna credenziale residua", SimklRepository.persistedAccessToken())
    assertNull("nessun checkpoint della vecchia sessione", SimklRepository.loadActivitiesSnapshot())
    assertNull(SimklRepository.lastSyncResult.value)
  }

  // ── 5: dopo il riavvio dell'app i dati restano assenti ───────────────────────

  @Test
  fun dopoIlRiavvioDellAppIDatiSimklRestanoAssenti() = runBlocking {
    catalogo("tmdb_m_5602", 5602, "Titolo in Watchlist", MediaType.FILM)
    catalogo("tmdb_m_5501", 5501, "Il Ladro di Sogni", MediaType.FILM)
    playbackBody = """
      [{"id":11,"progress":45.5,"paused_at":"2026-05-12T09:15:00.000Z","type":"movie",
        "movie":{"title":"Il Ladro di Sogni","year":2020,
                 "ids":{"simkl":5501,"slug":"il-ladro-di-sogni","tmdb":"5501","imdb":"tt5501000"}}}]
    """.trimIndent()
    watchlist(
      "movies" to """
        {"movies":[{"status":"plantowatch","added_to_watchlist_at":"2026-04-10T20:13:02Z",
          "movie":{"title":"Titolo in Watchlist","year":2020,
                   "ids":{"simkl":5602,"slug":"titolo-in-watchlist","tmdb":"5602","imdb":"tt5602000"}}}]}
      """.trimIndent(),
      "shows" to "{}", "anime" to "{}"
    )
    assertEquals(SimklSyncStatus.SYNCED, SimklSyncManager.sync(force = true).status)
    SimklRepository.signOut()

    // "Riavvio": lo stato in memoria è azzerato e ricaricato dal database persistito
    // (esattamente ciò che accade all'avvio: loadFromCache osserva le righe Room).
    MediaRepository.setDatabaseForTesting(null)
    MediaRepository.setDatabaseForTesting(db)
    MediaRepository.loadFromCache()
    assertTrue(attendi { MediaRepository.mediaList.value.isNotEmpty() })

    assertTrue(
      "il preferito SIMKL non deve riapparire",
      MediaRepository.mediaList.value.none { it.isFavorite }
    )
    assertTrue(
      "nessun progresso di visione SIMKL nel catalogo ricaricato",
      MediaRepository.mediaList.value.all { it.currentProgressMs == 0L && it.lastWatchedAt == null }
    )
    assertTrue(attendi { rigaRoom("tmdb_m_5602")?.isFavorite == false })
    assertTrue(attendi { rigaRoom("tmdb_m_5501")?.currentProgressMs == 0L })
    assertNull(SimklRepository.persistedAccessToken())
  }

  // ── 6: i dati locali restano disponibili dopo il logout ──────────────────────

  @Test
  fun ilPreferitoLocaleSopravviveAlLogout() = runBlocking {
    // L'utente ha favorito il titolo PRIMA della sincronizzazione: la watchlist SIMKL lo
    // trova già preferito (nessun cambio di proprietà) e il logout non lo toglie.
    catalogo("tmdb_m_9001", 9001, "Scelto Da Me", MediaType.FILM)
    MediaRepository.toggleFavorite("tmdb_m_9001")
    watchlist(
      "movies" to """
        {"movies":[{"status":"plantowatch","added_to_watchlist_at":"2026-04-10T20:13:02Z",
          "movie":{"title":"Scelto Da Me","year":2020,
                   "ids":{"simkl":9001,"slug":"scelto-da-me","tmdb":"9001","imdb":"tt9001000"}}}]}
      """.trimIndent(),
      "shows" to "{}", "anime" to "{}"
    )
    val result = SimklSyncManager.sync(force = true)
    assertEquals(0, result.watchlistImported)
    assertEquals(1, result.watchlistAlreadyFavorite)

    SimklRepository.signOut()

    assertEquals("il preferito locale resta", true, inCatalogo("tmdb_m_9001")?.isFavorite)
    assertNull(inCatalogo("tmdb_m_9001")?.favoriteSource)
    assertEquals(true, rigaRoom("tmdb_m_9001")?.isFavorite)
  }

  @Test
  fun ilProgressoLocaleDelFilmSopravviveAlLogout() = runBlocking {
    catalogo("tmdb_m_9002", 9002, "Visto In Locale", MediaType.FILM)
    // Scrittura del PLAYER (source di default = locale): la sincronizzazione non la tocca
    // (nessuna sessione di pausa SIMKL per questo titolo).
    MediaRepository.updateProgress("tmdb_m_9002", 600_000L, 1_000_000L)
    assertTrue(attendi { (rigaRoom("tmdb_m_9002")?.currentProgressMs ?: 0L) == 600_000L })

    SimklRepository.signOut()

    assertEquals("posizione locale intatta", 600_000L, inCatalogo("tmdb_m_9002")?.currentProgressMs ?: -1L)
    assertNull(inCatalogo("tmdb_m_9002")?.progressSource)
    assertEquals(600_000L, rigaRoom("tmdb_m_9002")?.currentProgressMs ?: -1L)
    assertNull(rigaRoom("tmdb_m_9002")?.progressSource)
  }

  @Test
  fun ilProgressoEpisodioLocaleSopravviveAEUnaSovrascritturaSimkl() = runBlocking {
    // Serie con EPISODIO 1 guardato in locale e episodio 2 adottato da SIMKL (più recente):
    // la scheda è momentaneamente "di SIMKL", ma le righe locali restano. Il logout rimuove
    // solo la posizione di SIMKL e ricostruisce lo stato della scheda dal punto locale più
    // recente: nessun progresso locale viene perso.
    catalogo("tmdb_tv_9003", 9003, "Serie Mista", MediaType.SERIE_TV, episodiTemporada = 3)
    MediaRepository.updateEpisodeProgress("tmdb_tv_9003", 1, 1, 300_000L, 40 * 60_000L)
    assertTrue(attendi { rigaEpisodio("tmdb_tv_9003", 1, 1)?.progressMs == 300_000L })
    playbackBody = """
      [{"id":13,"progress":50,"paused_at":"2026-05-13T12:00:00Z","type":"episode",
        "episode":{"season":1,"number":2,"title":"Capitolo Due"},
        "show":{"title":"Serie Mista","year":2020,
                "ids":{"simkl":9003,"slug":"serie-mista","tmdb":"9003","imdb":"tt9003000"}}}]
    """.trimIndent()
    assertEquals(SimklSyncStatus.SYNCED, SimklSyncManager.sync(force = true).status)
    assertTrue(attendi { rigaEpisodio("tmdb_tv_9003", 1, 2)?.source == MEDIA_SOURCE_SIMKL })
    assertEquals(2, rigaRoom("tmdb_tv_9003")?.lastWatchedEpisode)

    SimklRepository.signOut()

    // La riga episodio di SIMKL è sparita, quella locale no.
    assertNull(rigaEpisodio("tmdb_tv_9003", 1, 2))
    assertEquals(300_000L, rigaEpisodio("tmdb_tv_9003", 1, 1)?.progressMs ?: -1L)
    // La scheda è stata ricostruita dal punto locale più recente (E1): la card torna
    // a proporre la visione locale, non un progresso azzerato.
    val item = inCatalogo("tmdb_tv_9003")
    assertEquals(300_000L, item?.currentProgressMs ?: -1L)
    assertEquals(1, item?.lastWatchedEpisode)
    assertNull(item?.progressSource)
    val persisted = rigaRoom("tmdb_tv_9003")
    assertEquals(300_000L, persisted?.currentProgressMs ?: -1L)
    assertEquals(1, persisted?.lastWatchedEpisode)
    assertNull(persisted?.progressSource)
  }

  @Test
  fun leSchedeImportateDaSimklVengonoEliminateSoloSenzaStatoLocale() = runBlocking {
    // Importata dalla watchlist e mai toccata dall'utente: esiste SOLO per SIMKL → via.
    val soloSimkl = MediaRepository.upsertExternalItem(
      scheda("tmdb_m_9101", 9101, "Solo Per Simkl", MediaType.FILM),
      markFavorite = true,
      source = MEDIA_SOURCE_SIMKL
    )
    assertNotNull(soloSimkl)
    // Importata e poi favorita dall'UTENTE: ha acquisito stato locale → resta (senza
    // che il preferito locale venga toccato).
    val conPreferitoLocale = MediaRepository.upsertExternalItem(
      scheda("tmdb_m_9102", 9102, "Importata E Scelta", MediaType.FILM),
      source = MEDIA_SOURCE_SIMKL
    )
    assertNotNull(conPreferitoLocale)
    MediaRepository.toggleFavorite("tmdb_m_9102")
    // Importata con progresso scritto dal PLAYER: resta, con il suo progresso.
    val conProgressoLocale = MediaRepository.upsertExternalItem(
      scheda("tmdb_m_9103", 9103, "Importata E Vista", MediaType.FILM),
      source = MEDIA_SOURCE_SIMKL
    )
    assertNotNull(conProgressoLocale)
    MediaRepository.updateProgress("tmdb_m_9103", 500_000L, 1_000_000L)
    assertTrue(attendi { rigaRoom("tmdb_m_9103")?.importedFromSimkl == true })

    SimklRepository.signOut()

    assertNull("scheda creata da SIMKL senza stato locale: eliminata", rigaRoom("tmdb_m_9101"))
    assertTrue(MediaRepository.mediaList.value.none { it.id == "tmdb_m_9101" })
    // Schede con stato locale: restano, con il loro stato intatto.
    assertEquals(true, rigaRoom("tmdb_m_9102")?.isFavorite)
    assertNull(rigaRoom("tmdb_m_9102")?.favoriteSource)
    assertTrue(MediaRepository.mediaList.value.any { it.id == "tmdb_m_9102" && it.isFavorite })
    assertEquals(500_000L, rigaRoom("tmdb_m_9103")?.currentProgressMs ?: -1L)
    assertNull(rigaRoom("tmdb_m_9103")?.progressSource)
    assertTrue(MediaRepository.mediaList.value.any { it.id == "tmdb_m_9103" })
  }

  // ── 7: alla riconnessione i dati tornano con una nuova sincronizzazione ───────

  @Test
  fun allaRiconnessioneIDatiVengonoRisincronizzati() = runBlocking {
    catalogo("tmdb_m_5501", 5501, "Il Ladro di Sogni", MediaType.FILM)
    playbackBody = """
      [{"id":11,"progress":45.5,"paused_at":"2026-05-12T09:15:00.000Z","type":"movie",
        "movie":{"title":"Il Ladro di Sogni","year":2020,
                 "ids":{"simkl":5501,"slug":"il-ladro-di-sogni","tmdb":"5501","imdb":"tt5501000"}}}]
    """.trimIndent()
    assertEquals(SimklSyncStatus.SYNCED, SimklSyncManager.sync(force = true).status)
    SimklRepository.signOut()
    assertEquals(0L, rigaRoom("tmdb_m_5501")?.currentProgressMs ?: -1L)

    // Nuova sessione: nuovo login e nuova sincronizzazione completa (il checkpoint della
    // sessione precedente è stato cancellato dal logout).
    collegaAccount()
    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.SYNCED, result.status)
    assertEquals(1, result.adoptedMovies)
    assertTrue((rigaRoom("tmdb_m_5501")?.currentProgressMs ?: 0L) > 0L)
    assertEquals(MEDIA_SOURCE_SIMKL, rigaRoom("tmdb_m_5501")?.progressSource)
    assertTrue(SimklRepository.accountState.value.isLoggedIn)
  }

  // ── 8: il logout durante una sincronizzazione vince sempre ───────────────────

  @Test
  fun ilLogoutDuranteUnaSincronizzazioneImpedisceOgniRipubblicazione() = runBlocking {
    catalogo("tmdb_m_5501", 5501, "Il Ladro di Sogni", MediaType.FILM)
    playbackBody = """
      [{"id":11,"progress":45.5,"paused_at":"2026-05-12T09:15:00.000Z","type":"movie",
        "movie":{"title":"Il Ladro di Sogni","year":2020,
                 "ids":{"simkl":5501,"slug":"il-ladro-di-sogni","tmdb":"5501","imdb":"tt5501000"}}}]
    """.trimIndent()
    // Il corpo del pull di playback arriva lentamente: il logout avviene MENTRE la
    // sincronizzazione è in volo, prima di qualsiasi scrittura locale.
    playbackDelayMs = 2_000

    var syncResult: SimklSyncResult? = null
    val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    val syncJob: Job = scope.launch { syncResult = SimklSyncManager.sync(force = true) }

    assertTrue("il pull deve essere partito", attendi { recorded.any { it == "/sync/playback" } })
    delay(200) // la richiesta è in volo
    SimklRepository.signOut()
    assertFalse(SimklRepository.accountState.value.isLoggedIn)

    syncJob.join()
    assertEquals(
      "la sincronizzazione abortita non dichiara successo",
      SimklSyncStatus.NOT_LOGGED_IN,
      syncResult?.status
    )
    assertEquals(0, syncResult?.adoptedTotal ?: -1)

    // Nessun dato pubblicato dopo la disconnessione: né interfaccia né Room né datastore.
    assertEquals(0L, rigaRoom("tmdb_m_5501")?.currentProgressMs ?: -1L)
    assertNull(rigaRoom("tmdb_m_5501")?.progressSource)
    assertTrue(MediaRepository.mediaList.value.none { it.isFavorite })
    assertNull("nessun token risuscitato dal refresh/salvataggi in volo", SimklRepository.persistedAccessToken())
    assertNull("nessun checkpoint della sessione precedente", SimklRepository.loadActivitiesSnapshot())
    assertNull(SimklRepository.lastSyncResult.value)
    // E il catalogo con lo stato utente locale è rimasto intatto (nessun danno collaterale).
    assertTrue(MediaRepository.mediaList.value.isNotEmpty())
  }

  // ── 9: dati storici e di altri provider non vengono toccati ──────────────────

  @Test
  fun iDatiStoriciSenzaProvenienzaRestanoAlLogout() = runBlocking {
    // Riga scritta da una versione PRECEDENTE alla tracciatura (provenienza null):
    // preferito e progresso esistono, ma il database non può dimostrarne l'origine
    // SIMKL → conservati (nessuna provenienza inventata, nessuna cancellazione cieca).
    db.cachedMediaDao().insertOrUpdate(
      CachedMediaItemEntity(
        id = "tmdb_m_9201",
        tmdbId = 9201,
        title = "Preferenzato Storicamente",
        originalTitle = "",
        synopsis = "scheda di prova",
        videoUrl = "",
        resolution = "UHD_4K",
        qualityTags = "4K",
        backdropUrl = null,
        posterUrl = null,
        logoUrl = null,
        type = "FILM",
        year = 2020,
        durationMinutes = 100,
        seasonsCount = null,
        rating = 8f,
        genres = "Azione",
        director = null,
        cast = "Cast",
        provider = null,
        isFavorite = true,
        currentProgressMs = 300_000L,
        totalDurationMs = 6_000_000L
      )
    )
    db.episodeProgressDao().saveEpisodeProgress(
      EpisodeProgressEntity(mediaId = "tmdb_m_9201", seasonNumber = 1, episodeNumber = 1, progressMs = 120_000L, durationMs = 600_000L)
    )
    assertTrue(attendi { rigaRoom("tmdb_m_9201")?.isFavorite == true })

    // Il caso reale dei dati storici: scritti da una versione precedente, l'app RIAVVIA
    // e li carica dal database. Il riavvio è simulato come nel test "dopoIlRiavvio".
    MediaRepository.setDatabaseForTesting(null)
    MediaRepository.setDatabaseForTesting(db)
    MediaRepository.loadFromCache()
    assertTrue(attendi { MediaRepository.mediaList.value.any { it.id == "tmdb_m_9201" && it.isFavorite } })

    SimklRepository.signOut()

    val persisted = rigaRoom("tmdb_m_9201")
    assertEquals("il preferito storico resta", true, persisted?.isFavorite)
    assertEquals("il progresso storico resta", 300_000L, persisted?.currentProgressMs ?: -1L)
    assertNull(persisted?.favoriteSource)
    assertNull(persisted?.progressSource)
    assertEquals(120_000L, rigaEpisodio("tmdb_m_9201", 1, 1)?.progressMs ?: -1L)
    // Anche dopo la pulizia, l'interfaccia mostra ancora lo stato storico (locale).
    assertTrue(
      "il titolo storico resta visibile nei Preferiti dell'interfaccia",
      MediaRepository.mediaList.value.any { it.id == "tmdb_m_9201" && it.isFavorite }
    )
  }

  // ── Infrastruttura ─────────────────────────────────────────────────────────

  private suspend fun collegaAccount() {
    SimklRepository.awaitLogin(SimklRepository.startLogin())
  }

  private suspend fun catalogo(
    id: String,
    tmdbId: Int,
    titolo: String,
    tipo: MediaType,
    episodiTemporada: Int = 0
  ) {
    val base = MediaItem(
      id = id,
      tmdbId = tmdbId,
      title = titolo,
      synopsis = "scheda di prova",
      videoUrl = "",
      type = tipo,
      year = 2020,
      durationMinutes = 100,
      totalDurationMs = 100 * 60_000L
    )
    val item = if (tipo == MediaType.SERIE_TV && episodiTemporada > 0) {
      base.copy(
        episodes = (1..episodiTemporada).map { numero ->
          com.example.data.model.Episode(
            id = "${id}_s1e$numero",
            seasonNumber = 1,
            episodeNumber = numero,
            title = "Episodio $numero",
            synopsis = "",
            durationMinutes = 40,
            videoUrl = ""
          )
        }
      )
    } else {
      base
    }
    MediaRepository.upsertExternalItem(item) ?: error("catalogo di prova non inserito")
  }

  private fun scheda(id: String, tmdbId: Int, titolo: String, tipo: MediaType) = MediaItem(
    id = id,
    tmdbId = tmdbId,
    title = titolo,
    synopsis = "scheda di prova",
    videoUrl = "",
    type = tipo,
    year = 2020,
    durationMinutes = 100,
    totalDurationMs = 100 * 60_000L
  )

  private fun watchlist(vararg entries: Pair<String, String>) {
    watchlistBodies = entries.associate { (type, body) -> "/sync/all-items/$type/plantowatch" to body }
  }

  private suspend fun attendi(timeoutMs: Long = 5000L, condizione: suspend () -> Boolean): Boolean {
    val scadenza = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < scadenza) {
      if (condizione()) return true
      delay(25)
    }
    return condizione()
  }

  private fun inCatalogo(id: String) = MediaRepository.mediaList.value.firstOrNull { it.id == id }

  private fun rigaRoom(id: String) = runBlocking { db.cachedMediaDao().getMediaById(id) }

  private fun rigaEpisodio(id: String, season: Int, episode: Int) = runBlocking {
    db.episodeProgressDao().getEpisodeProgress(id, season, episode)
  }

  private companion object {
    val COUNTER = java.util.concurrent.atomic.AtomicInteger(0)

    fun activitiesBodyFor(tick: Int): String {
      val stamp = "2026-05-14T%02d:%02d:20Z".format((tick / 60) % 60, tick % 60)
      return """
        {"all":"$stamp",
         "settings":{"all":"$stamp"},
         "tv_shows":{"all":"$stamp","playback":"$stamp","plantowatch":"$stamp","watching":"$stamp"},
         "anime":{"all":"$stamp","playback":"$stamp","plantowatch":"$stamp"},
         "movies":{"all":"$stamp","playback":"$stamp","plantowatch":"$stamp"}}
      """.trimIndent()
    }

    val OAUTH_DEVICE = """
      {"device_code":"dev-code","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin",
       "verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}
    """.trimIndent()

    val OAUTH_TOKEN = """
      {"access_token":"token-di-prova","refresh_token":"refresh-di-prova","token_type":"Bearer",
       "expires_in":604800,"scope":"media:read media:write"}
    """.trimIndent()

    val USER_SETTINGS = """{"user":{"name":"tester"},"account":{"id":42,"type":"vip"}}"""
  }
}
