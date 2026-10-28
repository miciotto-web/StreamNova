package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MediaCacheMapper.toEntity
import com.example.data.local.StreamNovaDatabase
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.remote.simkl.SimklActivitiesSnapshot
import com.example.data.remote.simkl.SimklApi
import com.example.data.remote.simkl.SimklApiClient
import com.example.data.remote.simkl.SimklDecisionReason
import com.example.data.remote.simkl.SimklPendingPlayback
import com.example.data.remote.simkl.SimklPendingPlaybackStore
import com.example.data.remote.simkl.SimklPlaybackEntry
import com.example.data.remote.simkl.SimklPlaybackKind
import com.example.data.remote.simkl.SimklPlaybackParser
import com.example.data.remote.simkl.SimklSyncSection
import com.example.data.remote.simkl.SimklSyncStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
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
 * Percorso completo della sincronizzazione SIMKL con risposte HTTP controllate:
 * `GET /sync/activities` → `GET /sync/playback` e `GET /sync/all-items/...` → mapping →
 * repository → archivio locale → stato osservato dalla UI ([MediaRepository.mediaList]).
 *
 * Le risposte sono quelle documentate da SIMKL (`paused_at`, `progress` percentuale,
 * `show`/`movie` con `ids`, watchlist con `status`). Nessun account reale e nessuna
 * credenziale: il server è locale e la durata degli episodi è iniettata.
 *
 * Vincoli verificati qui: i due pull sono indipendenti (l'errore di uno non tocca i dati
 * dell'altro), una risposta vuota valida non è un errore, nulla viene cancellato, non
 * nascono duplicati e nessuna durata/posizione viene inventata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimklSyncIntegrationTest {

  private lateinit var server: MockWebServer
  private lateinit var db: StreamNovaDatabase
  private lateinit var api: SimklApi

  /** Corpo corrente servito per ciascun endpoint logico (sostituibile da ogni test). */
  private var activitiesBody: String = ""
  private var playbackBody: String = "[]"
  private var watchlistBodies: Map<String, String> = emptyMap()
  private var libraryBody: String = "{}"

  /** Se non `null`, l'endpoint logico risponde con questo codice invece del corpo. */
  private var failureCodes: Map<String, Int> = emptyMap()

  /** Se `true`, l'endpoint risponde 200 senza corpo (risposta vuota ma valida). */
  private var emptyBodyEndpoints: Set<String> = emptySet()

  private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())

  /** Durata reale dell'episodio "da TMDB": iniettata per non dipendere dalla rete. */
  private var episodeRuntimes: Map<Pair<Int, Int>, Int> = emptyMap()
  private var defaultEpisodeRuntime: Int? = null
  private var episodeRuntimeCalls: MutableList<Triple<Int, Int, Int>> =
    java.util.Collections.synchronizedList(mutableListOf())

  /** Durata reale del film "da TMDB": stessa iniezione, nessuna dipendenza dalla rete. */
  private var movieRuntimes: Map<Int, Int> = emptyMap()
  private var movieRuntimeCalls: MutableList<Int> = java.util.Collections.synchronizedList(mutableListOf())

  private lateinit var defaultRuntimeProvider: suspend (Int, Int, Int) -> Int?
  private lateinit var defaultMovieRuntimeProvider: suspend (Int) -> Int?

  @Before
  fun setUp() = runBlocking {
    // Timestamp delle attività unico per test: lo snapshot salvato da un test precedente
    // (il repository è un singleton di processo) non deve far risultare "aggiornato" questo.
    val tick = COUNTER.getAndIncrement()
    activitiesBody = activitiesBodyFor(tick)

    defaultRuntimeProvider = MediaRepository.episodeRuntimeProvider
    MediaRepository.episodeRuntimeProvider = { tmdbId, season, episode ->
      episodeRuntimeCalls.add(Triple(tmdbId, season, episode))
      episodeRuntimes[tmdbId to episode] ?: defaultEpisodeRuntime
    }
    defaultMovieRuntimeProvider = MediaRepository.movieRuntimeProvider
    MediaRepository.movieRuntimeProvider = { tmdbId ->
      movieRuntimeCalls.add(tmdbId)
      movieRuntimes[tmdbId]
    }

    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    AppSettingsRepository.setSimklClientId("client-di-prova")
    db = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    MediaRepository.init(context, db)

    server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val (endpoint, query) = path.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        recorded.add(endpoint + if (query.isEmpty()) "" else "?${query.substringBefore("&app-name")}")
        return respondTo(endpoint)
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
    // Le voci rimandate vivono su DataStore, che è un singleton di processo: senza questa
    // pulizia l'esito di un test dipenderebbe dall'ordine di esecuzione. La pulizia viene
    // VERIFICATA: se non ha effetto (archivio già sporco da un test precedente) il test
    // fallirebbe più avanti con un sintomo fuorviante ("una sezione vuota non è un guasto!"),
    // quindi meglio dirlo qui.
    pulisciVociRimandate()
    collegaAccount()
  }

  @After
  fun tearDown() {
    MediaRepository.episodeRuntimeProvider = defaultRuntimeProvider
    MediaRepository.movieRuntimeProvider = defaultMovieRuntimeProvider
    episodeRuntimeCalls.clear()
    movieRuntimeCalls.clear()
    // Anche in uscita: un test che lascia di proposito una voce rimandata non deve cambiare
    // l'esito del test successivo (che può appartenere a un'altra classe dello stesso JVM).
    runBlocking { pulisciVociRimandate() }
    server.shutdown()
    db.close()
  }

  /** Svuota le voci rimandate e verifica che l'archivio sia davvero vuoto. */
  private suspend fun pulisciVociRimandate() {
    SimklRepository.savePendingPlaybacks(emptyList())
    val residue = SimklRepository.loadPendingPlaybacks()
    check(residue.isEmpty()) {
      "archivio delle voci rimandate non svuotato: ${residue.map { it.entry.resumeKey }}"
    }
  }

  // ── Infrastruttura ─────────────────────────────────────────────────────────

  private fun respondTo(endpoint: String): MockResponse {
    failureCodes[endpoint]?.let { return MockResponse().setResponseCode(it).setBody("""{"error":"guasto simulato"}""") }
    if (endpoint in emptyBodyEndpoints) {
      return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("")
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
    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
  }

  private suspend fun collegaAccount() {
    SimklRepository.awaitLogin(SimklRepository.startLogin())
  }

  /** Un titolo già presente nel catalogo locale (TMDB, titolo localizzato, `imdbId` assente). */
  private suspend fun catalogo(
    id: String,
    tmdbId: Int,
    titolo: String,
    tipo: MediaType,
    titoloOriginale: String = "",
    durataMinuti: Int = 100,
    episodiTemporada: Int = 0,
    progressoMs: Long = 0L,
    preferito: Boolean = false
  ): MediaItem {
    val item = MediaItem(
      id = id,
      tmdbId = tmdbId,
      title = titolo,
      originalTitle = titoloOriginale,
      synopsis = "scheda di prova",
      videoUrl = "",
      type = tipo,
      year = 2020,
      durationMinutes = durataMinuti,
      currentProgressMs = progressoMs,
      totalDurationMs = durataMinuti * 60_000L,
      isFavorite = preferito
    ).let { base ->
      if (tipo == MediaType.SERIE_TV && episodiTemporada > 0) {
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
    }
    return MediaRepository.upsertExternalItem(item) ?: error("catalogo di prova non inserito")
  }

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

  private fun inCatalogo(id: String): MediaItem? = MediaRepository.mediaList.value.firstOrNull { it.id == id }

  private fun rigaRoom(id: String) = runBlocking { db.cachedMediaDao().getMediaById(id) }

  // ── Continua a guardare ────────────────────────────────────────────────────

  @Test
  fun unFilmDellaSessioneDiventaUnaRigaDiContinuaAGuardare() = runBlocking {
    catalogo("tmdb_m_5501", 5501, "Il Ladro di Sogni", MediaType.FILM, durataMinuti = 100)
    playbackBody = """
      [{"id":11,"progress":45.5,"paused_at":"2026-05-12T09:15:00.000Z","type":"movie",
        "movie":{"title":"Il Ladro di Sogni","year":2020,
                 "ids":{"simkl":5501,"slug":"il-ladro-di-sogni","tmdb":"5501","imdb":"tt5501000"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.SYNCED, result.status)
    assertEquals(1, result.adoptedMovies)
    val item = inCatalogo("tmdb_m_5501")
    assertNotNull("la Home legge questo elenco: la riga deve esserci", item)
    assertEquals("45.5% di 100 minuti reali", (6_000_000L * 45.5 / 100.0).toLong(), item!!.currentProgressMs)
    assertEquals(6_000_000L, item.totalDurationMs)

    // Persistenza: la riga Room porta la stessa posizione (sopravvive al riavvio).
    val persisted = attendi { (rigaRoom("tmdb_m_5501")?.currentProgressMs ?: 0L) > 0L }
    assertTrue("posizione non persistita in Room", persisted)
    assertEquals(item.currentProgressMs, rigaRoom("tmdb_m_5501")?.currentProgressMs)

    // Il tempo di visione è quello di SIMKL, non l'ora della sincronizzazione.
    assertEquals(
      "paused_at deve fare da data di visione",
      com.example.data.remote.simkl.SimklPlaybackParser.parseIsoUtcToEpochMs("2026-05-12T09:15:00.000Z"),
      item.lastWatchedAt
    )
  }

  @Test
  fun unEpisodioDiventaLaRigaDaRiprendereDellaSerie() = runBlocking {
    catalogo("tmdb_tv_5502", 5502, "La Casa Senza Fine", MediaType.SERIE_TV, episodiTemporada = 3)
    playbackBody = """
      [{"id":12,"progress":60,"paused_at":"2026-05-13T10:36:01Z","type":"episode",
        "episode":{"season":1,"number":2,"title":"Capitolo Due"},
        "show":{"title":"La Casa Senza Fine","year":2020,
                "ids":{"simkl":5502,"slug":"la-casa-senza-fine","tmdb":"5502","imdb":"tt5502000"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.SYNCED, result.status)
    assertEquals(1, result.adoptedEpisodes)
    val item = inCatalogo("tmdb_tv_5502")
    assertEquals(1, item?.lastWatchedSeason)
    assertEquals(2, item?.lastWatchedEpisode)
    assertEquals((40 * 60_000L * 0.6).toLong(), item?.currentProgressMs)
    assertEquals(40 * 60_000L, item?.totalDurationMs)

    assertTrue(attendi { (rigaRoom("tmdb_tv_5502")?.lastWatchedEpisode ?: 0) == 2 })
    assertEquals(1, rigaRoom("tmdb_tv_5502")?.lastWatchedSeason)
  }

  @Test
  fun unEpisodioFuoriDaiSegnapostoLocaliUsaLaDurataRealeDiTmdb() = runBlocking {
    // Il catalogo locale genera episodi segnaposto solo per le prime stagioni: l'episodio
    // da riprendere non c'è e senza la sua durata reale la posizione non è ricostruibile.
    catalogo("tmdb_tv_5503", 5503, "La Casa Senza Fine 2", MediaType.SERIE_TV, episodiTemporada = 3)
    defaultEpisodeRuntime = null
    episodeRuntimes = mapOf((5503 to 6) to 52)
    playbackBody = """
      [{"id":13,"progress":50,"paused_at":"2026-05-13T12:00:00Z","type":"episode",
        "episode":{"season":1,"number":6,"title":"Capitolo Sei"},
        "show":{"title":"La Casa Senza Fine 2","year":2020,
                "ids":{"simkl":5503,"slug":"la-casa-senza-fine-2","tmdb":"5503"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.SYNCED, result.status)
    assertEquals("l'episodio deve essere adottato con la durata reale", 1, result.adoptedEpisodes)
    assertEquals(listOf(Triple(5503, 1, 6)), episodeRuntimeCalls.toList())
    val item = inCatalogo("tmdb_tv_5503")
    assertEquals(6, item?.lastWatchedEpisode)
    assertEquals("52 minuti reali, non una stima", 52 * 60_000L, item?.totalDurationMs)
    assertEquals((52 * 60_000L * 0.5).toLong(), item?.currentProgressMs)
  }

  @Test
  fun senzaDurataRealeNonVieneInventataLaPosizione() = runBlocking {
    catalogo("tmdb_tv_5504", 5504, "La Casa Senza Durata", MediaType.SERIE_TV, episodiTemporada = 2)
    defaultEpisodeRuntime = null
    episodeRuntimes = emptyMap()
    playbackBody = """
      [{"id":14,"progress":50,"paused_at":"2026-05-13T12:00:00Z","type":"episode",
        "episode":{"season":9,"number":9},
        "show":{"title":"La Casa Senza Durata","year":2020,
                "ids":{"simkl":5504,"slug":"la-casa-senza-durata","tmdb":"5504"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(0, result.adoptedEpisodes)
    assertEquals(1, result.skipped[SimklDecisionReason.UNKNOWN_DURATION])
    assertEquals(0L, inCatalogo("tmdb_tv_5504")?.currentProgressMs)
    assertNull("nessuna posizione di ripresa inventata", inCatalogo("tmdb_tv_5504")?.lastWatchedEpisode)
  }

  @Test
  fun unaVoceSenzaDataValidaNonRegredisceUnProgressoLocale() = runBlocking {
    // Il film è già a metà: una pausa SIMKL più vecchia non deve riportarlo indietro.
    catalogo(
      "tmdb_m_5505", 5505, "Film Avanti", MediaType.FILM,
      durataMinuti = 100, progressoMs = 5_000_000L
    )
    MediaRepository.updateProgress("tmdb_m_5505", 5_000_000L, 6_000_000L, 1_800_000_000_000L)
    playbackBody = """
      [{"id":15,"progress":10,"paused_at":"2024-01-01T00:00:00Z","type":"movie",
        "movie":{"title":"Film Avanti","year":2020,
                 "ids":{"simkl":5505,"slug":"film-avanti","tmdb":"5505"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(0, result.adoptedMovies)
    assertEquals(1, result.skipped[SimklDecisionReason.LOCAL_NEWER])
    assertEquals(5_000_000L, inCatalogo("tmdb_m_5505")?.currentProgressMs)
  }

  @Test
  fun ilTitoloLocalizzatoVieneAgganciatoConIlTitoloOriginale() = runBlocking {
    // Catalogo costruito da TMDB con `language=it-IT`, voce SIMKL con il titolo originale e
    // senza id TMDB: senza il confronto con `originalTitle` la voce andrebbe persa.
    catalogo(
      "tmdb_m_5506", 5506, "La Pioggia", MediaType.FILM,
      titoloOriginale = "The Rain Within", durataMinuti = 100
    )
    playbackBody = """
      [{"id":16,"progress":25,"paused_at":"2026-05-13T12:00:00Z","type":"movie",
        "movie":{"title":"The Rain Within","year":2020,
                 "ids":{"simkl":5506,"slug":"the-rain-within","imdb":"tt5506000"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.SYNCED, result.status)
    assertEquals(1, result.adoptedMovies)
    assertEquals("nessun titolo non riconosciuto", 0, result.unresolvedTitles.size)
    assertEquals(1_500_000L, inCatalogo("tmdb_m_5506")?.currentProgressMs)
  }

  @Test
  fun unaSessioneSenzaEpisodioVieneContataNonAdottata() = runBlocking {
    playbackBody = """
      [{"id":17,"progress":30,"paused_at":"2026-05-13T12:00:00Z","type":"episode",
        "show":{"title":"Serie Senza Episodio","year":2020,
                "ids":{"simkl":5507,"slug":"serie-senza-episodio","tmdb":"5507"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(0, result.adoptedTotal)
    assertEquals(1, result.skipped[SimklDecisionReason.INCOMPLETE_SESSION])
  }

  @Test
  fun nessunPuntoDiRipresaNonEUnErrore() = runBlocking {
    // `[]` è la risposta documentata di "nessun playback salvato".
    playbackBody = "[]"

    val result = SimklSyncManager.sync(force = true)

    assertTrue(result.isComplete)
    assertEquals(0, result.adoptedTotal)
    assertEquals("niente in attesa: una lista vuota valida non lascia residui", 0, result.deferredForRetry)
    assertTrue(result.message.contains("Nessun nuovo punto di ripresa"))
  }

  // ── Recupero delle voci non applicabili adesso ────────────────────────────

  @Test
  fun unaVoceSenzaDurataVieneRimandataEPoiRecuperataQuandoLaDurataArriva() = runBlocking {
    catalogo("tmdb_tv_5801", 5801, "La Serie in Attesa", MediaType.SERIE_TV, episodiTemporada = 2)
    defaultEpisodeRuntime = null
    episodeRuntimes = emptyMap()
    playbackBody = """
      [{"id":31,"progress":50,"paused_at":"2026-05-20T10:00:00Z","type":"episode",
        "episode":{"season":1,"number":7},
        "show":{"title":"La Serie in Attesa","year":2020,
                "ids":{"simkl":5801,"slug":"la-serie-in-attesa","tmdb":"5801"}}}]
    """.trimIndent()

    // 1) Prima sincronizzazione: la durata reale non è nota a nessuno (mai vista, TMDB muto).
    val prima = SimklSyncManager.sync(force = true)
    assertEquals(0, prima.adoptedEpisodes)
    assertEquals(1, prima.skipped[SimklDecisionReason.UNKNOWN_DURATION])
    assertEquals("la voce è rimandata, non persa", 1, prima.deferredForRetry)
    assertEquals("rimandare non è un abbandono", 0, prima.abandonedRecoveries)
    assertEquals(SimklSyncStatus.PARTIAL, prima.status)
    assertTrue(
      "il motivo deve essere leggibile: ${prima.message}",
      prima.message.contains("durata non nota")
    )
    assertTrue(prima.message.contains("verranno riprovate"))
    assertEquals(1, SimklRepository.loadPendingPlaybacks().size)

    // 2) La durata reale diventa disponibile (TMDB risponde, o il player ha misurato
    //    l'episodio) e su SIMKL non è cambiato NULLA: le attività sono ferme. La voce
    //    rimandata deve comunque essere riprovata — è il caso che prima era irrecuperabile.
    episodeRuntimes = mapOf((5801 to 7) to 45)
    recorded.clear()
    val seconda = SimklSyncManager.sync(force = false)

    assertTrue(
      "la voce rimandata deve forzare la rilettura del playback: $recorded",
      recorded.any { it.startsWith("/sync/playback") }
    )
    assertEquals(SimklSyncStatus.SYNCED, seconda.status)
    assertEquals(1, seconda.adoptedEpisodes)
    assertEquals(0, seconda.deferredForRetry)
    assertEquals(1, inCatalogo("tmdb_tv_5801")?.lastWatchedSeason)
    assertEquals(7, inCatalogo("tmdb_tv_5801")?.lastWatchedEpisode)
    assertEquals((45 * 60_000L * 0.5).toLong(), inCatalogo("tmdb_tv_5801")?.currentProgressMs)
    assertTrue("nessuna voce in attesa dopo il recupero", SimklRepository.loadPendingPlaybacks().isEmpty())
  }

  @Test
  fun unaVoceSenzaDataEsenzaDurataRestaLiSenzaInventareNulla() = runBlocking {
    catalogo("tmdb_m_5807", 5807, "Film Senza Nulla", MediaType.FILM, durataMinuti = 0)
    movieRuntimes = emptyMap()
    playbackBody = """
      [{"id":37,"progress":40,"paused_at":"2026-05-21T10:00:00Z","type":"movie",
        "movie":{"title":"Film Senza Nulla","year":2020,"ids":{"simkl":5807,"tmdb":"5807"}}}]
    """.trimIndent()

    val prima = SimklSyncManager.sync(force = true)

    assertEquals("la durata non è nota: nessuna posizione inventata", 0, prima.adoptedTotal)
    assertEquals(1, prima.skipped[SimklDecisionReason.UNKNOWN_DURATION])
    assertEquals(1, prima.deferredForRetry)
    assertEquals("la durata del film è stata chiesta a TMDB", listOf(5807), movieRuntimeCalls.toList())
    assertEquals(0L, inCatalogo("tmdb_m_5807")?.currentProgressMs)
    assertNull(
      "mai guardato in locale: nessuna data di visione inventata",
      inCatalogo("tmdb_m_5807")?.lastWatchedAt
    )

    // La durata arriva al giro successivo: la voce viene applicata.
    movieRuntimes = mapOf(5807 to 90)
    val seconda = SimklSyncManager.sync(force = false)
    assertEquals(1, seconda.adoptedMovies)
    assertEquals((90 * 60_000L * 0.4).toLong(), inCatalogo("tmdb_m_5807")?.currentProgressMs)
  }

  @Test
  fun unFilmSenzaDurataInSchedaUsaLaDurataRealeDiTmdb() = runBlocking {
    // La scheda locale esiste ma la durata è ignota: senza la durata reale di TMDB la
    // percentuale non è convertibile e il punto di ripresa andrebbe perso.
    catalogo("tmdb_m_5804", 5804, "Film Senza Durata", MediaType.FILM, durataMinuti = 0)
    movieRuntimes = mapOf(5804 to 120)
    playbackBody = """
      [{"id":34,"progress":25,"paused_at":"2026-05-20T11:00:00Z","type":"movie",
        "movie":{"title":"Film Senza Durata","year":2020,"ids":{"simkl":5804,"tmdb":"5804"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(1, result.adoptedMovies)
    assertEquals(0, result.deferredForRetry)
    assertEquals(120 * 60_000L, inCatalogo("tmdb_m_5804")?.totalDurationMs)
    assertEquals((120 * 60_000L * 0.25).toLong(), inCatalogo("tmdb_m_5804")?.currentProgressMs)
  }

  @Test
  fun dopoITentativiPrevistiLaVoceNonForzaPiuIlPull() = runBlocking {
    catalogo("tmdb_tv_5802", 5802, "Serie Mai Risolvibile", MediaType.SERIE_TV, episodiTemporada = 2)
    defaultEpisodeRuntime = null
    episodeRuntimes = emptyMap()
    // SIMKL non restituisce più nulla; la voce rimandata è all'ultimo tentativo utile.
    playbackBody = "[]"
    val sessione = SimklPlaybackEntry(
      playbackId = 32,
      kind = SimklPlaybackKind.EPISODE,
      progressPercent = 50.0,
      pausedAtEpochMs = SimklPlaybackParser.parseIsoUtcToEpochMs("2026-05-20T10:00:00Z"),
      title = "Serie Mai Risolvibile",
      year = 2020,
      tmdbId = 5802,
      imdbId = null,
      simklId = 5802,
      season = 9,
      episode = 9
    )
    SimklRepository.savePendingPlaybacks(
      listOf(SimklPendingPlayback(sessione, SimklPendingPlaybackStore.MAX_ATTEMPTS - 1))
    )

    val result = SimklSyncManager.sync(force = false)

    assertEquals(1, result.abandonedRecoveries)
    assertEquals(0, result.deferredForRetry)
    assertEquals(1, result.skipped[SimklDecisionReason.RECOVERY_EXHAUSTED])
    assertTrue("il motivo compare nel messaggio: ${result.message}", result.message.contains("non recuperabili"))
    assertTrue("recupero chiuso", SimklRepository.loadPendingPlaybacks().isEmpty())

    // Evita il ciclo infinito: senza voci in attesa e senza cambiamenti su SIMKL, la
    // sincronizzazione successiva si dichiara aggiornata senza riscaricare le liste.
    recorded.clear()
    val dopo = SimklSyncManager.sync(force = false)
    assertEquals(SimklSyncStatus.UP_TO_DATE, dopo.status)
    assertEquals(1, recorded.size)
  }

  @Test
  fun sincronizzazioniRipetuteDeiPlaybackNonCreanoDuplicatiNeRegredisconoIlProgresso() = runBlocking {
    catalogo("tmdb_m_5803", 5803, "Film Ripetuto", MediaType.FILM, durataMinuti = 100)
    playbackBody = """
      [{"id":33,"progress":30,"paused_at":"2026-05-20T12:00:00Z","type":"movie",
        "movie":{"title":"Film Ripetuto","year":2020,"ids":{"simkl":5803,"tmdb":"5803"}}}]
    """.trimIndent()

    val prima = SimklSyncManager.sync(force = true)
    assertEquals(1, prima.adoptedMovies)
    val posizione = inCatalogo("tmdb_m_5803")!!.currentProgressMs
    assertEquals((6_000_000L * 0.30).toLong(), posizione)

    val seconda = SimklSyncManager.sync(force = true)

    assertEquals("la stessa sessione non viene riapplicata", 0, seconda.adoptedMovies)
    assertEquals(
      "già allineato (o più recente in locale): nessuna scrittura",
      1,
      (seconda.skipped[SimklDecisionReason.ALREADY_IN_SYNC] ?: 0) +
        (seconda.skipped[SimklDecisionReason.LOCAL_NEWER] ?: 0)
    )
    assertEquals("il progresso non regredisce", posizione, inCatalogo("tmdb_m_5803")?.currentProgressMs)
    assertEquals("nessun duplicato", 1, MediaRepository.mediaList.value.count { it.id == "tmdb_m_5803" })
    assertEquals("niente in attesa: la voce è applicata", 0, seconda.deferredForRetry)
  }

  // ── Bidirezionale: quello che l'app invia è quello che SIMKL le restituisce ─

  @Test
  fun unFilmAvviatoSuStreamNovaTornaImportabileDaSimkl() = runBlocking {
    // Il payload che l'app spedisce per il film (id TMDB + IMDb) è lo stesso con cui SIMKL
    // identifica la sessione restituita: se i due lati divergessero, il progresso tornerebbe
    // su un titolo diverso (o su nessuno). L'account reale non c'entra: la risposta del
    // server di prova è costruita con gli identificativi che l'app invia.
    val media = SimklMediaPayload("Film in Due Direzioni", 2019, 5806, "tt5806000")
    val inviato = media.toPayload(42.0)
    val idsInviati = (inviato["movie"] as Map<*, *>)["ids"] as Map<*, *>
    assertEquals("5806", idsInviati["tmdb"])
    assertEquals("tt5806000", idsInviati["imdb"])

    catalogo("tmdb_m_5806", 5806, "Film in Due Direzioni", MediaType.FILM, durataMinuti = 100)
    playbackBody = """
      [{"id":36,"progress":42,"paused_at":"2026-05-22T10:00:00Z","type":"movie",
        "movie":{"title":"Film in Due Direzioni","year":2019,
                 "ids":{"simkl":5806,"slug":"film-in-due-direzioni","tmdb":"5806","imdb":"tt5806000"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(1, result.adoptedMovies)
    val item = inCatalogo("tmdb_m_5806")
    assertEquals("il progresso inviato torna sullo stesso film", 5806, item?.tmdbId)
    assertEquals((6_000_000L * 0.42).toLong(), item?.currentProgressMs)
  }

  @Test
  fun unEpisodioAvviatoSuStreamNovaTornaSulloStessoEpisodio() = runBlocking {
    val media = SimklMediaPayload("La Casa in Due Direzioni", 2020, 5805, null, SimklEpisodePayload(3, 5))
    val inviato = media.toPayload(64.0)
    assertEquals(mapOf("season" to 3, "episode" to 5), inviato["episode"])
    assertEquals("5805", ((inviato["show"] as Map<*, *>)["ids"] as Map<*, *>)["tmdb"])

    catalogo("tmdb_tv_5805", 5805, "La Casa in Due Direzioni", MediaType.SERIE_TV, episodiTemporada = 0)
    episodeRuntimes = mapOf((5805 to 5) to 50)
    playbackBody = """
      [{"id":35,"progress":64,"paused_at":"2026-05-22T11:00:00Z","type":"episode",
        "episode":{"season":3,"number":5,"title":"Capitolo Cinque"},
        "show":{"title":"La Casa in Due Direzioni","year":2020,
                "ids":{"simkl":5805,"slug":"la-casa-in-due-direzioni","tmdb":"5805"}}}]
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals(1, result.adoptedEpisodes)
    val item = inCatalogo("tmdb_tv_5805")
    assertEquals(3, item?.lastWatchedSeason)
    assertEquals(5, item?.lastWatchedEpisode)
    assertEquals((50 * 60_000L * 0.64).toLong(), item?.currentProgressMs)
    // L'episodio giusto: il vicino non viene toccato.
    assertTrue(MediaRepository.getEpisodeProgress("tmdb_tv_5805", 3, 5) > 0L)
    assertEquals(0L, MediaRepository.getEpisodeProgress("tmdb_tv_5805", 3, 6))
  }

  @Test
  fun leAltreSezioniVuoteNonSonoUnErrore() = runBlocking {
    // `{}` è la risposta documentata di una libreria/watchlist vuota.
    activitiesBody = "{}"
    watchlist("movies" to "{}", "shows" to "{}", "anime" to "{}")
    libraryBody = "{}"

    val result = SimklSyncManager.sync(force = true)

    assertTrue("sezioni vuote ma valide: nessun errore", result.isComplete)
    assertEquals(
      "una sezione vuota non è un guasto: ${result.message} " +
        "[inAttesa=${result.deferredForRetry} salti=${result.skipped} nonRiconosciuti=${result.unresolvedTitles}]",
      SimklSyncStatus.SYNCED,
      result.status
    )
    assertEquals(0, result.adoptedTotal)
    assertEquals(0, result.watchlistImported)
  }

  @Test
  fun unCorpoVuotoSu200NonVieneScambiatoPerUnaListaVuota() = runBlocking {
    // Un 200 a corpo vuoto NON è la risposta documentata dell'endpoint (che è un array
    // `[]`): viene trattato come sezione non scaricata — mai come "tutto a posto" — e non
    // tocca nulla in locale.
    catalogo("tmdb_m_5704", 5704, "Film Intatto", MediaType.FILM, progressoMs = 2_000_000L)
    emptyBodyEndpoints = setOf("/sync/playback")

    val result = SimklSyncManager.sync(force = true)

    assertFalse("un corpo vuoto non è una sincronizzazione riuscita", result.isComplete)
    assertEquals(listOf(SimklSyncSection.PLAYBACK), result.failedSections)
    assertEquals(2_000_000L, inCatalogo("tmdb_m_5704")?.currentProgressMs)
  }

  // ── Behavior delle attività ────────────────────────────────────────────────

  @Test
  fun senzaModificheLaSincronizzazioneSiFermaSenzaScaricareLeListe() = runBlocking {
    SimklSyncManager.sync(force = true)
    val dopoIlPrimo = recorded.size
    recorded.clear()

    val result = SimklSyncManager.sync(force = false)

    assertEquals(SimklSyncStatus.UP_TO_DATE, result.status)
    assertTrue(
      "nessun pull quando il gate delle attività dice che non è cambiato nulla: $recorded",
      recorded.size == 1 && recorded[0].startsWith("/sync/activities")
    )
    assertTrue(dopoIlPrimo > 0)
  }

  // ── Watchlist → Preferiti ─────────────────────────────────────────────────

  @Test
  fun laWatchlistDiFilmVieneLettaDAIPercorsiPlantToWatch() = runBlocking {
    catalogo("tmdb_m_5601", 5601, "Il Film da Guardare", MediaType.FILM)
    watchlist(
      "movies" to """
        {"movies":[{"status":"plantowatch","added_to_watchlist_at":"2026-04-10T20:13:02Z",
          "movie":{"title":"Il Film da Guardare","year":2020,
                   "ids":{"simkl":5601,"slug":"il-film-da-guardare","tmdb":"5601","imdb":"tt5601000"}}}]}
      """.trimIndent()
    )

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.SYNCED, result.status)
    assertEquals(1, result.watchlistImported)
    assertTrue("il film deve comparire nei Preferiti", inCatalogo("tmdb_m_5601")?.isFavorite == true)
    assertTrue(attendi { rigaRoom("tmdb_m_5601")?.isFavorite == true })

    assertTrue(
      "l'endpoint documentato della watchlist",
      recorded.any { it.startsWith("/sync/all-items/movies/plantowatch") }
    )
    assertTrue(
      "l'endpoint documentato della watchlist",
      recorded.any { it.startsWith("/sync/all-items/shows/plantowatch") }
    )
    assertTrue(
      "anche gli anime sono richiesti",
      recorded.any { it.startsWith("/sync/all-items/anime/plantowatch") }
    )
    assertFalse(
      "niente `extended` sulla watchlist: bastano titolo, anno e id",
      recorded.any { it.contains("plantowatch?") && it.contains("extended") }
    )
  }

  @Test
  fun laWatchlistDiSerieVieneImportataNeiPreferiti() = runBlocking {
    catalogo("tmdb_tv_5602", 5602, "La Serie da Guardare", MediaType.SERIE_TV, episodiTemporada = 4)
    watchlist(
      "shows" to """
        {"shows":[{"status":"plantowatch","added_to_watchlist_at":"2018-02-24T23:55:13Z",
          "show":{"title":"La Serie da Guardare","year":2020,
                  "ids":{"simkl":5602,"slug":"la-serie-da-guardare","tmdb":"5602","imdb":"tt5602000"}}}]}
      """.trimIndent()
    )

    val result = SimklSyncManager.sync(force = true)

    assertEquals(1, result.watchlistImported)
    assertTrue(inCatalogo("tmdb_tv_5602")?.isFavorite == true)
    // I Preferiti sono letti da `mediaList`: è esattamente la fonte di FavoritesScreen.
    assertTrue(MediaRepository.mediaList.value.any { it.id == "tmdb_tv_5602" && it.isFavorite })
  }

  @Test
  fun unaWatchlistVuotaNonCancellaIPreferitiEsistenti() = runBlocking {
    catalogo("tmdb_m_5603", 5603, "Preferito Locale", MediaType.FILM, preferito = true)
    watchlist("movies" to "{}", "shows" to "{}", "anime" to "{}")

    val result = SimklSyncManager.sync(force = true)

    assertEquals(0, result.watchlistImported)
    assertTrue(result.isComplete)
    assertTrue(
      "un preferito locale non viene mai tolto",
      inCatalogo("tmdb_m_5603")?.isFavorite == true
    )
  }

  @Test
  fun unaSolaRegistrazioneNonProduceDuplicati() = runBlocking {
    catalogo("tmdb_m_5604", 5604, "Film Stabile", MediaType.FILM)
    watchlist(
      "movies" to """
        {"movies":[{"status":"plantowatch",
          "movie":{"title":"Film Stabile","year":2020,"ids":{"simkl":5604,"tmdb":"5604"}}}]}
      """.trimIndent()
    )

    val primo = SimklSyncManager.sync(force = true)
    val secondo = SimklSyncManager.sync(force = true)

    assertEquals(1, primo.watchlistImported)
    assertEquals("alla seconda passata è già preferito", 1, secondo.watchlistAlreadyFavorite)
    assertEquals("nessun preferito nuovo", 0, secondo.watchlistImported)
    assertEquals(1, MediaRepository.mediaList.value.count { it.id == "tmdb_m_5604" })
  }

  @Test
  fun unaVoceNonRiconoscibileVieneRegistrataESaltataSenzaInventareNulla() = runBlocking {
    // Nessun id utilizzabile e nessun titolo corrispondente: la voce non può essere
    // risolta senza interrogare TMDB, quindi viene segnalata (nessun titolo inventato).
    watchlist(
      "movies" to """
        {"movies":[{"status":"plantowatch",
          "movie":{"title":"Titolo Mai Visto Prima","year":1931,
                   "ids":{"simkl":999001,"slug":"titolo-mai-visto"}}}]}
      """.trimIndent()
    )

    val result = SimklSyncManager.sync(force = true)

    assertEquals(0, result.watchlistImported)
    assertEquals(listOf("Titolo Mai Visto Prima"), result.watchlistUnresolved)
    assertEquals(SimklSyncStatus.PARTIAL, result.status)
    assertTrue(result.message.contains("non riconosciuti"))
  }

  // ── Indipendenza delle due sincronizzazioni ───────────────────────────────

  @Test
  fun unErroreSullaWatchlistNonCancellaIContinuaAGuardare() = runBlocking {
    catalogo("tmdb_m_5701", 5701, "Film con Progresso", MediaType.FILM, durataMinuti = 100)
    playbackBody = """
      [{"id":21,"progress":40,"paused_at":"2026-05-13T12:00:00Z","type":"movie",
        "movie":{"title":"Film con Progresso","year":2020,"ids":{"simkl":5701,"tmdb":"5701"}}}]
    """.trimIndent()
    failureCodes = mapOf("/sync/all-items/movies/plantowatch" to 500)

    val result = SimklSyncManager.sync(force = true)

    assertEquals("una sincronizzazione incompleta non è riuscita", SimklSyncStatus.PARTIAL, result.status)
    assertEquals(listOf(SimklSyncSection.WATCHLIST), result.failedSections)
    assertFalse(result.isComplete)
    assertEquals("i punti di ripresa sono comunque stati applicati", 1, result.adoptedMovies)
    assertTrue(inCatalogo("tmdb_m_5701")?.currentProgressMs ?: 0L > 0L)
    assertTrue(result.message.contains("continua a guardare", ignoreCase = true))
  }

  @Test
  fun unErroreSulPlaybackNonCancellaIPreferitiImportati() = runBlocking {
    catalogo("tmdb_m_5702", 5702, "Film in Watchlist", MediaType.FILM)
    watchlist(
      "movies" to """
        {"movies":[{"status":"plantowatch",
          "movie":{"title":"Film in Watchlist","year":2020,"ids":{"simkl":5702,"tmdb":"5702"}}}]}
      """.trimIndent()
    )
    failureCodes = mapOf("/sync/playback" to 503)

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.PARTIAL, result.status)
    assertEquals(listOf(SimklSyncSection.PLAYBACK), result.failedSections)
    assertEquals("la watchlist è stata importata lo stesso", 1, result.watchlistImported)
    assertTrue(inCatalogo("tmdb_m_5702")?.isFavorite == true)
    assertTrue(result.message.contains("Continua a guardare"))
  }

  @Test
  fun unErroreNonFaAvanzareLoSnapshotDelleAttivita() = runBlocking {
    // Base nota e diversa dalle attività correnti: il gate vedrà quindi un cambiamento.
    SimklRepository.saveActivitiesSnapshot(SimklActivitiesSnapshot.fromResponse(attivitaBaseline()))

    // 1) La watchlist cade: quella sezione non viene scaricata.
    failureCodes = mapOf("/sync/all-items/shows/plantowatch" to 500)
    val conErrore = SimklSyncManager.sync(force = false)

    assertEquals(SimklSyncStatus.PARTIAL, conErrore.status)
    assertEquals(listOf(SimklSyncSection.WATCHLIST), conErrore.failedSections)

    // 2) La causa sparisce. Il gate deve ANCORA segnalare un cambiamento: se lo snapshot
    //    fosse stato salvato nonostante l'errore, ciò che è andato perso non verrebbe mai
    //    recuperato e la sincronizzazione si dichiarerebbe "aggiornata" per sempre.
    failureCodes = emptyMap()
    recorded.clear()
    val dopo = SimklSyncManager.sync(force = false)

    assertFalse(
      "lo snapshot non deve avanzare quando una sezione non è stata scaricata",
      dopo.status == SimklSyncStatus.UP_TO_DATE
    )
    assertTrue("il pull deve essere ritentato: $recorded", recorded.any { it.startsWith("/sync/playback") })
    assertTrue("senza errori la sincronizzazione è completa", dopo.isComplete)
  }

  @Test
  fun unErroreSullaWatchlistNonToccaIProgressiLocali() = runBlocking {
    catalogo(
      "tmdb_m_5703", 5703, "Film con Progresso Locale", MediaType.FILM,
      durataMinuti = 100, progressoMs = 3_000_000L
    )
    failureCodes = mapOf("/sync/all-items/anime/plantowatch" to 412)

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.PARTIAL, result.status)
    assertEquals(3_000_000L, inCatalogo("tmdb_m_5703")?.currentProgressMs)
  }

  // ── Episodi visti dalla libreria (`status=watching`) ───────────────────────
  // Il caso reale: `/sync/playback` dell'account contiene SOLO un film, mentre la libreria
  // ha 36 serie in visione con l'ultimo episodio visto. Senza leggere questi campi la
  // sincronizzazione riportava "0 episodi" senza alcun motivo di scarto, perché non c'era
  // nulla da scartare: i progressi degli episodi arrivano da qui.

  @Test
  fun unaSerieInVisioneDellaLibreriaDiventaUnaCardConLEpisodioVisto() = runBlocking {
    catalogo("tmdb_tv_5505", 5505, "La Casa Senza Fine 5", MediaType.SERIE_TV, episodiTemporada = 3)
    playbackBody = "[]"
    libraryBody = libreriaSerieInVisione(
      titolo = "La Casa Senza Fine 5", tmdbId = 5505, imdbId = "tt5505000",
      stagione = 1, episodio = 2, vistoIl = "2026-05-14T10:00:00Z"
    )

    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.SYNCED, result.status)
    assertEquals("non è un punto di pausa: contata a parte", 0, result.adoptedEpisodes)
    assertEquals(1, result.adoptedLibraryEpisodes)
    assertTrue(
      "il report deve dire da dove arriva la posizione: ${result.message}",
      result.message.contains("ultimo episodio visto")
    )

    val item = inCatalogo("tmdb_tv_5505")
    assertEquals(1, item?.lastWatchedSeason)
    assertEquals(2, item?.lastWatchedEpisode)
    assertEquals("episodio visto = posizione al 100% della durata reale nota", 40 * 60_000L, item?.currentProgressMs)
    assertEquals(
      "la data è quella di SIMKL",
      SimklPlaybackParser.parseIsoUtcToEpochMs("2026-05-14T10:00:00Z"),
      item?.lastWatchedAt
    )
    assertEquals(
      "una sola card in Continua a guardare",
      1,
      MediaRepository.getContinueWatching().count { it.tmdbId == 5505 }
    )
    assertTrue("posizione persistita", attendi { rigaRoom("tmdb_tv_5505")?.lastWatchedEpisode == 2 })
  }

  @Test
  fun unEpisodioVistoOltreISegnapostoUsaLaDurataRealeDiTmdb() = runBlocking {
    catalogo("tmdb_tv_5506", 5506, "La Casa Senza Fine 6", MediaType.SERIE_TV, episodiTemporada = 3)
    defaultEpisodeRuntime = null
    episodeRuntimes = mapOf((5506 to 9) to 45)
    playbackBody = "[]"
    libraryBody = libreriaSerieInVisione(
      titolo = "La Casa Senza Fine 6", tmdbId = 5506, imdbId = "tt5506000",
      stagione = 1, episodio = 9, vistoIl = "2026-05-14T11:00:00Z"
    )

    val result = SimklSyncManager.sync(force = true)

    assertEquals(1, result.adoptedLibraryEpisodes)
    assertEquals("la durata dell'episodio è chiesta a TMDB", listOf(Triple(5506, 1, 9)), episodeRuntimeCalls.toList())
    val item = inCatalogo("tmdb_tv_5506")
    assertEquals(9, item?.lastWatchedEpisode)
    assertEquals("45 minuti reali, non una stima", 45 * 60_000L, item?.currentProgressMs)
  }

  @Test
  fun laLibreriaNonRiportaIndietroUnaSeriePiuAvantiInLocale() = runBlocking {
    catalogo("tmdb_tv_5507", 5507, "La Casa Senza Fine 7", MediaType.SERIE_TV, episodiTemporada = 9)
    // In locale la serie è più avanti NEL TEMPO, su un episodio diverso: il dato di SIMKL
    // ("ultimo visto S1E2", il 14 maggio) è vecchio e non deve spostare la card indietro.
    MediaRepository.updateEpisodeProgress(
      mediaId = "tmdb_tv_5507", seasonNumber = 1, episodeNumber = 5,
      progressMs = 600_000L, durationMs = 2_400_000L,
      lastWatchedAtMs = SimklPlaybackParser.parseIsoUtcToEpochMs("2026-06-01T10:00:00Z")!!
    )
    playbackBody = "[]"
    libraryBody = libreriaSerieInVisione(
      titolo = "La Casa Senza Fine 7", tmdbId = 5507, imdbId = "tt5507000",
      stagione = 1, episodio = 2, vistoIl = "2026-05-14T10:00:00Z"
    )

    val result = SimklSyncManager.sync(force = true)

    assertEquals(0, result.adoptedLibraryEpisodes)
    assertEquals(
      "la voce resta un salto spiegato, non una scrittura: ${result.skipped}",
      1,
      result.skipped[SimklDecisionReason.LOCAL_NEWER] ?: 0
    )
    val item = inCatalogo("tmdb_tv_5507")
    assertEquals("l'episodio locale resta quello corrente", 5, item?.lastWatchedEpisode)
    assertEquals(600_000L, item?.currentProgressMs)
  }

  @Test
  fun unaSerieConclusaDellaLibreriaNonToccaLoStatoLocale() = runBlocking {
    catalogo("tmdb_tv_5508", 5508, "Serie Finita", MediaType.SERIE_TV, episodiTemporada = 5)
    playbackBody = "[]"
    libraryBody = """
      {"shows":[{"status":"completed","last_watched":"S01E05","last_watched_at":"2026-05-14T10:00:00Z",
        "show":{"title":"Serie Finita","year":2020,"ids":{"tmdb":"5508","imdb":"tt5508000"}},
        "seasons":[{"number":1,"episodes":[{"number":5,"watched_at":"2026-05-14T10:00:00Z"}]}]}]}
    """.trimIndent()

    val result = SimklSyncManager.sync(force = true)

    assertEquals("una serie conclusa non è un punto di ripresa", 0, result.adoptedLibraryEpisodes)
    assertEquals(0L, inCatalogo("tmdb_tv_5508")?.currentProgressMs)
    assertEquals("i badge 'visto' restano disponibili", 1, result.watchedShowsUpdated)
  }

  @Test
  fun sincronizzazioniRipetuteDellaLibreriaNonRiscrivonoNeDuplicano() = runBlocking {
    catalogo("tmdb_tv_5509", 5509, "La Casa Senza Fine 9", MediaType.SERIE_TV, episodiTemporada = 3)
    playbackBody = "[]"
    libraryBody = libreriaSerieInVisione(
      titolo = "La Casa Senza Fine 9", tmdbId = 5509, imdbId = "tt5509000",
      stagione = 1, episodio = 2, vistoIl = "2026-05-14T10:00:00Z"
    )

    val prima = SimklSyncManager.sync(force = true)
    assertEquals(1, prima.adoptedLibraryEpisodes)

    val seconda = SimklSyncManager.sync(force = true)

    assertEquals("la stessa posizione non viene riapplicata", 0, seconda.adoptedLibraryEpisodes)
    assertEquals(
      "già allineato: nessuna scrittura",
      1,
      (seconda.skipped[SimklDecisionReason.ALREADY_IN_SYNC] ?: 0) +
        (seconda.skipped[SimklDecisionReason.LOCAL_NEWER] ?: 0)
    )
    assertEquals("una sola riga in catalogo", 1, MediaRepository.mediaList.value.count { it.tmdbId == 5509 })
    assertEquals("una sola card", 1, MediaRepository.getContinueWatching().count { it.tmdbId == 5509 })
    assertEquals("niente in attesa: la voce è applicata", 0, seconda.deferredForRetry)
  }

  // ── Titolo in due schede: TMDB + Stremio/IMDb ──────────────────────────────

  /**
   * Le due schede dello stesso film, come sul device: `tt6262770` (Stremio/Cinemeta, il cui
   * id **è** l'id IMDb) e `tmdb_m_1288069` (catalogo TMDB). Sono entrambe nel catalogo e in
   * Room, con due progressi diversi: è la coppia che l'utente vedeva come due card identiche.
   *
   * Lo stato utente viene scritto ESPLICITAMENTE dopo l'inserimento (l'upsert conserva per
   * progetto il progresso già presente su una riga esistente): così il test è deterministico
   * anche se una scheda con lo stesso id è rimasta in memoria da un test precedente
   * (`MediaRepository` è un singleton di processo).
   */
  private suspend fun seminaTitoloDuplicato(): Pair<MediaItem, MediaItem> {
    val cinemeta = MediaRepository.upsertExternalItem(
      MediaItem(
        id = "tt6262770",
        tmdbId = null,
        title = "Tenzing",
        originalTitle = "Tenzing",
        synopsis = "scheda Cinemeta di prova",
        videoUrl = "",
        type = MediaType.FILM,
        year = 2026,
        durationMinutes = 126,
        provider = "Cinemeta",
        totalDurationMs = 7_560_000L
      )
    ) ?: error("scheda Cinemeta di prova non inserita")
    MediaRepository.updateProgress(
      cinemeta.id, 1_176_348L, 7_560_000L, lastWatchedAtMs = 1_793_016_244_973L
    )

    val schedaTmdb = MediaRepository.upsertExternalItem(
      MediaItem(
        id = "tmdb_m_1288069",
        tmdbId = 1288069,
        imdbId = "tt6262770",
        title = "Tenzing",
        originalTitle = "Tenzing",
        synopsis = "scheda TMDB di prova",
        videoUrl = "",
        type = MediaType.FILM,
        year = 2026,
        durationMinutes = 126,
        totalDurationMs = 7_560_000L
      )
    ) ?: error("scheda TMDB di prova non inserita")
    // La scheda TMDB ha un progresso più vecchio: è il caso osservato.
    MediaRepository.updateProgress(
      schedaTmdb.id, 1_112_842L, 7_560_000L, lastWatchedAtMs = 1_793_016_234_891L
    )
    return MediaRepository.getMediaById(schedaTmdb.id)
      ?.let { it to (MediaRepository.getMediaById(cinemeta.id) ?: it) }
      ?: error("schede di prova non presenti in catalogo")
  }

  private fun schedeTenzing(): List<MediaItem> = MediaRepository.mediaList.value
    .filter { it.id == "tmdb_m_1288069" || it.id == "tt6262770" }

  @Test
  fun unTitoloInDueSchedeMostraUnaSolaCardConIlProgressoPiuRecente() = runBlocking {
    seminaTitoloDuplicato()
    assertEquals("due schede persistite, come sul device", 2, schedeTenzing().size)

    val card = MediaRepository.getContinueWatching().filter { it.id in setOf("tmdb_m_1288069", "tt6262770") }

    assertEquals("una sola card per il titolo", 1, card.size)
    assertEquals("la card mostra la scheda col progresso più recente", "tt6262770", card.single().id)
    assertEquals(1_176_348L, card.single().currentProgressMs)
    assertEquals("nessuna riga viene cancellata dal catalogo", 2, schedeTenzing().size)
  }

  @Test
  fun laSincronizzazioneAggiornaLaSchedaVisibileSenzaCreareNuoviDuplicati() = runBlocking {
    seminaTitoloDuplicato()
    // Pausa più recente delle due date locali (le schede sono state viste il 26-10 alle 12:03
    // e 12:04 UTC): è la stessa relazione del caso reale, dove la pausa SIMKL era più nuova.
    playbackBody = """
      [{"id":77,"progress":20,"paused_at":"2026-10-26T13:30:00Z","type":"movie",
        "movie":{"title":"Tenzing","year":2026,
                 "ids":{"simkl":2461445,"tmdb":"1288069","imdb":"tt6262770"}}}]
    """.trimIndent()

    val prima = SimklSyncManager.sync(force = true)
    assertEquals(1, prima.adoptedMovies)

    val atteso = (7_560_000L * 0.20).toLong()
    assertEquals(
      "la sincronizzazione scrive la scheda che la UI mostra",
      "tt6262770",
      MediaRepository.newestWatchingItem("tmdb_m_1288069")?.id
    )
    assertTrue(
      "la card visibile porta la posizione importata",
      attendi {
        MediaRepository.getContinueWatching()
          .singleOrNull { it.id == "tt6262770" }?.currentProgressMs == atteso
      }
    )
    val card = MediaRepository.getContinueWatching().single { it.id == "tt6262770" }
    assertEquals("nessuna terza riga", 2, schedeTenzing().size)
    assertEquals(
      "la data di visione è quella di SIMKL",
      SimklPlaybackParser.parseIsoUtcToEpochMs("2026-10-26T13:30:00Z"),
      card.lastWatchedAt
    )
    assertEquals(
      "una sola card per il titolo",
      1,
      MediaRepository.getContinueWatching().count { it.id in setOf("tmdb_m_1288069", "tt6262770") }
    )

    val seconda = SimklSyncManager.sync(force = true)

    assertEquals("la stessa sessione non viene riapplicata", 0, seconda.adoptedMovies)
    assertEquals("nessuna riga in più", 2, schedeTenzing().size)
    assertEquals(
      "una sola card, con la posizione più recente",
      atteso,
      MediaRepository.getContinueWatching().single { it.id == "tt6262770" }.currentProgressMs
    )
  }

  @Test
  fun rimuovereLaCardConDueSchedeRimuoveIlTitoloIntero() = runBlocking {
    seminaTitoloDuplicato()
    val card = MediaRepository.getContinueWatching().single { it.id == "tt6262770" }

    MediaRepository.removeFromContinueWatching(card.id)

    assertTrue(
      "la gemella non deve far riapparire la card",
      attendi { MediaRepository.getContinueWatching().none { it.id in setOf("tmdb_m_1288069", "tt6262770") } }
    )
    assertTrue(
      "entrambe le schede sono fuori dal catalogo",
      attendi { MediaRepository.mediaList.value.none { it.id in setOf("tmdb_m_1288069", "tt6262770") } }
    )
    assertTrue(
      "nessuna riga orfana in Room",
      attendi { rigaRoom("tmdb_m_1288069") == null && rigaRoom("tt6262770") == null }
    )
  }

  /**
   * Corpo di prova di una serie in visione, nella forma documentata e osservata
   * (`status`, `last_watched`, `last_watched_at`, `next_to_watch`).
   */
  private fun libreriaSerieInVisione(
    titolo: String,
    tmdbId: Int,
    imdbId: String?,
    stagione: Int,
    episodio: Int,
    vistoIl: String
  ): String {
    val codice = "S${stagione.toString().padStart(2, '0')}E${episodio.toString().padStart(2, '0')}"
    val successivo = "S${stagione.toString().padStart(2, '0')}E${(episodio + 1).toString().padStart(2, '0')}"
    val imdb = imdbId?.let { "\"imdb\":\"$it\"," }.orEmpty()
    return """
      {"shows":[{
        "status":"watching","last_watched":"$codice","last_watched_at":"$vistoIl",
        "next_to_watch":"$successivo","watched_episodes_count":$episodio,"total_episodes_count":20,
        "show":{"title":"$titolo","year":2020,"runtime":40,
                "ids":{"simkl":$tmdbId,$imdb"tmdb":"$tmdbId"}},
        "seasons":[{"number":$stagione,"episodes":[{"number":$episodio,"watched_at":"$vistoIl"}]}]
      }]}
    """.trimIndent()
  }

  // ── Corpi di prova ─────────────────────────────────────────────────────────

  private companion object {
    /** Contatore di processo: rende unico il timestamp delle attività per ogni test. */
    val COUNTER = java.util.concurrent.atomic.AtomicInteger(0)

    /** Base di confronto del gate: un istante vecchio, diverso dalle attività correnti. */
    fun attivitaBaseline(): Map<String, Any> = mapOf(
      "all" to BASE_STAMP,
      "settings" to mapOf("all" to BASE_STAMP),
      "tv_shows" to mapOf(
        "all" to BASE_STAMP, "playback" to BASE_STAMP,
        "plantowatch" to BASE_STAMP, "watching" to BASE_STAMP
      ),
      "anime" to mapOf("all" to BASE_STAMP, "playback" to BASE_STAMP, "plantowatch" to BASE_STAMP),
      "movies" to mapOf("all" to BASE_STAMP, "playback" to BASE_STAMP, "plantowatch" to BASE_STAMP)
    )

    const val BASE_STAMP = "1999-01-01T00:00:00Z"

    /**
     * Attività con un timestamp unico per test, applicato a TUTTI i bucket.
     * `SimklRepository` è un singleton di processo: lo snapshot salvato da un test deve
     * risultare "diverso" per quello successivo, altrimenti il gate direbbe "aggiornato".
     */
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
