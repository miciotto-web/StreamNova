package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.remote.simkl.SimklApi
import com.example.data.remote.simkl.SimklApiClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scritture "visto"/"non visto" e chiusura delle sessioni di pausa, verificate sulle
 * **richieste HTTP reali** con un `MockWebServer` locale e nessuna credenziale vera.
 *
 * Cosa era rotto e ora è coperto:
 * - `markWatched` era codice morto (nessun chiamante): le marcature locali non arrivavano
 *   a SIMKL. Ora il percorso `markWatched` → `POST /sync/history` è verificato, con il
 *   rifiuto documentato delle marcature «intera serie» (SIMKL le espande a tutti gli
 *   episodi) e il rispetto di `not_found`.
 * - La rimozione da «Continua a guardare» era solo locale: la sessione di pausa restava
 *   aperta lato SIMKL e la card rientrava alla sync successiva. Ora
 *   `deletePlaybackSessionsFor` → `DELETE /sync/playback/{id}` chiude SOLO le sessioni
 *   del titolo rimosso.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimklHistoryAndPlaybackRemovalTest {

  private lateinit var server: MockWebServer
  private lateinit var api: SimklApi

  private class Recorded(val method: String, val path: String, val body: String)

  /** Richieste ricevute dal server di prova, in ordine di arrivo. */
  private val received = java.util.Collections.synchronizedList(mutableListOf<Recorded>())

  /** Path → codice d'errore. Assente = 200. */
  private var failureCodes: Map<String, Int> = emptyMap()

  /** Path → corpo di risposta. Assente = risposta standard per quell'endpoint. */
  private var bodies: Map<String, String> = emptyMap()

  @Before
  fun setUp() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    AppSettingsRepository.setSimklClientId("client-di-prova")

    server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore("?")
        received.add(Recorded(request.method.orEmpty(), path, request.body.readUtf8()))
        failureCodes[path]?.let { code ->
          return MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json").setBody("""{"error":"guasto simulato"}""")
        }
        val payload = bodies[path] ?: when {
          path == "/sync/history" -> HISTORY_ADDED
          path == "/sync/history/remove" -> HISTORY_REMOVED
          path == "/sync/playback" -> PLAYBACK_SESSIONS
          path.startsWith("/sync/playback/") -> "{}"
          path == "/oauth2/device" -> OAUTH_DEVICE
          path == "/oauth2/token" -> OAUTH_TOKEN
          path == "/oauth2/revoke" -> "{}"
          else -> "{}"
        }
        return MockResponse().setResponseCode(200)
          .setHeader("Content-Type", "application/json").setBody(payload)
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
    received.clear()
    collegaAccount()
  }

  @After
  fun tearDown() {
    // Isola le credenziali OAuth di prova dagli altri test Robolectric.
    runBlocking { SimklRepository.signOut() }
    server.shutdown()
  }

  // ── «Visto» verso SIMKL ────────────────────────────────────────────────────

  @Test
  fun laMarcaturaDiUnFilmInviaSyncHistoryConIlBucketMovies() = runBlocking {
    val consegnata = SimklRepository.markWatched(film())

    assertTrue("la marcatura deve arrivare a SIMKL", consegnata)
    val richiesta = received.single { it.path == "/sync/history" }
    assertEquals("POST", richiesta.method)
    val body = JSONObject(richiesta.body)
    val film = body.getJSONArray("movies").getJSONObject(0)
    assertEquals("Matrix", film.getString("title"))
    assertEquals(1999, film.getInt("year"))
    assertEquals("603", film.getJSONObject("ids").getString("tmdb"))
    assertEquals("tt0133093", film.getJSONObject("ids").getString("imdb"))
    assertFalse("una marcatura senza episodio non tocca le serie", body.has("shows"))
  }

  @Test
  fun laMarcaturaDiUnaSerieInteraNonVieneMaiInviata() = runBlocking {
    // SIMKL espande una voce `shows[]` senza `seasons`/`episodes` a TUTTI gli episodi:
    // il toggle «visto» di una serie non deve produrre questa scrittura.
    val serieIntera = SimklMediaPayload(
      "La Casa Senza Fine", 2020, 5502, "tt5502000",
      episode = null, kind = SimklMediaKind.SERIES
    )

    val consegnata = SimklRepository.markWatched(serieIntera)

    assertFalse(consegnata)
    assertEquals(
      "nessuna scrittura di cronologia per una serie intera",
      emptyList<String>(),
      percorsi().filter { it.startsWith("/sync/") }
    )
  }

  @Test
  fun unTitoloNonRiconosciutoDaSimklNonEDichiaratoVisto() = runBlocking {
    bodies = mapOf(
      "/sync/history" to """
        {"added":{"movies":0,"shows":0,"episodes":0},
         "not_found":{"movies":[{"title":"Sconosciuto","year":2099}]}}
      """.trimIndent()
    )

    val consegnata = SimklRepository.markWatched(film())

    assertFalse("un `not_found` non è un salvataggio riuscito", consegnata)
  }

  @Test
  fun unErroreHttpSullaCronologiaNonPropaga() = runBlocking {
    failureCodes = mapOf("/sync/history" to 500)

    val consegnata = SimklRepository.markWatched(film())

    assertFalse("il guasto non deve lanciare: la marcatura locale è già avvenuta", consegnata)
    assertEquals(1, percorsi().count { it == "/sync/history" })
  }

  @Test
  fun laDecadenzaDellaMarcaturaInviaSyncHistoryRemoveSenzaWatchedAt() = runBlocking {
    val consegnata = SimklRepository.unmarkWatched(film())

    assertTrue(consegnata)
    val richiesta = received.single { it.path == "/sync/history/remove" }
    assertEquals("POST", richiesta.method)
    val body = JSONObject(richiesta.body)
    assertEquals("603", body.getJSONArray("movies").getJSONObject(0).getJSONObject("ids").getString("tmdb"))
    assertFalse(
      "senza `watched_at` il server RIMUOVE la cronologia invece di fissarla a un nuovo momento",
      body.getJSONArray("movies").getJSONObject(0).has("watched_at")
    )
  }

  // ── Stagione / episodio: marcature e ritiri bulk ───────────────────────────

  @Test
  fun laMarcaturaDiUnaStagioneInviaUnSoloShowConTutteLePuntate() = runBlocking {
    val consegnata = SimklRepository.markSeasonWatched(
      serie(),
      season = 2,
      episodeNumbers = listOf(3, 1, 2)
    )

    assertTrue("la stagione deve arrivare a SIMKL", consegnata)
    val richiesta = received.single { it.path == "/sync/history" }
    assertEquals("POST", richiesta.method)
    val show = JSONObject(richiesta.body).getJSONArray("shows").getJSONObject(0)
    assertEquals("5502", show.getJSONObject("ids").getString("tmdb"))
    assertFalse("una stagione non è una serie intera", show.has("status"))
    val season = show.getJSONArray("seasons").getJSONObject(0)
    assertEquals(2, season.getInt("number"))
    val numeri = season.getJSONArray("episodes").let { episodi ->
      (0 until episodi.length()).map { episodi.getJSONObject(it).getInt("number") }
    }
    assertEquals("puntate ordinate e senza duplicati", listOf(1, 2, 3), numeri)
  }

  @Test
  fun laRimozioneDiUnEpisodioInviaShowConSeasonsEdEpisodes() = runBlocking {
    val episodio = serie().copy(episode = SimklEpisodePayload(2, 5))

    val consegnata = SimklRepository.removeEpisodeHistory(episodio)

    assertTrue(consegnata)
    val richiesta = received.single { it.path == "/sync/history/remove" }
    val show = JSONObject(richiesta.body).getJSONArray("shows").getJSONObject(0)
    assertFalse("la rimozione non fissa un nuovo stato", show.has("status"))
    val season = show.getJSONArray("seasons").getJSONObject(0)
    assertEquals(2, season.getInt("number"))
    assertEquals(5, season.getJSONArray("episodes").getJSONObject(0).getInt("number"))
  }

  @Test
  fun laRimozioneDiUnaStagioneInviaLeStessePuntateMarcate() = runBlocking {
    val consegnata = SimklRepository.removeSeasonHistory(
      serie(),
      season = 4,
      episodeNumbers = listOf(2, 4)
    )

    assertTrue(consegnata)
    val richiesta = received.single { it.path == "/sync/history/remove" }
    val show = JSONObject(richiesta.body).getJSONArray("shows").getJSONObject(0)
    val season = show.getJSONArray("seasons").getJSONObject(0)
    assertEquals(4, season.getInt("number"))
    val numeri = season.getJSONArray("episodes").let { episodi ->
      (0 until episodi.length()).map { episodi.getJSONObject(it).getInt("number") }
    }
    assertEquals(listOf(2, 4), numeri)
  }

  // ── Rimozione da «Continua a guardare» → chiusura sessioni ───────────────

  @Test
  fun laConclusioneDiUnaSerieInviaStatusCompletedAShowLevel() = runBlocking {
    val consegnata = SimklRepository.markSeriesCompleted(serie())

    assertTrue("la conclusione deve arrivare a SIMKL", consegnata)
    val richiesta = received.single { it.path == "/sync/history" }
    assertEquals("POST", richiesta.method)
    val body = JSONObject(richiesta.body)
    val show = body.getJSONArray("shows").getJSONObject(0)
    assertEquals(
      "`status=completed` è la forma documentata per dichiarare conclusa una serie intera",
      "completed",
      show.getString("status")
    )
    assertEquals("5502", show.getJSONObject("ids").getString("tmdb"))
    assertFalse(
      "senza `seasons`/`episodes`: SIMKL espande la cronologia da solo e non serve un watched_at",
      show.has("seasons") || show.has("episodes") || show.has("watched_at")
    )
  }

  @Test
  fun ilRitiroDellaSerieInviaHistoryRemoveSenzaStatus() = runBlocking {
    val consegnata = SimklRepository.removeSeriesHistory(serie())

    assertTrue(consegnata)
    val richiesta = received.single { it.path == "/sync/history/remove" }
    assertEquals("POST", richiesta.method)
    val show = JSONObject(richiesta.body).getJSONArray("shows").getJSONObject(0)
    assertEquals("5502", show.getJSONObject("ids").getString("tmdb"))
    assertFalse(
      "la rimozione NON porta `status`: cancella la cronologia invece di fissarne una nuova",
      show.has("status")
    )
  }

  @Test
  fun laRimozioneDellaCardChiudeSoloLeSessioniDelTitolo() = runBlocking {
    val chiuse = SimklRepository.deletePlaybackSessionsFor(
      title = "Matrix", year = 1999, tmdbId = 603, imdbId = "tt0133093"
    )

    assertEquals("una sola sessione di pausa appartiene al titolo", 1, chiuse)
    val delete = received.single { it.method == "DELETE" }
    assertEquals("/sync/playback/111", delete.path)
    assertEquals("l'altra sessione non deve essere toccata", 1, received.count { it.method == "DELETE" })
  }

  @Test
  fun nessunaSessioneCorrispondenteNonProduceNessunaDelete() = runBlocking {
    val chiuse = SimklRepository.deletePlaybackSessionsFor(
      title = "Titolo Assente", year = 2001, tmdbId = 999_999, imdbId = "tt9999999"
    )

    assertEquals(0, chiuse)
    assertEquals("nessuna DELETE senza match", 0, received.count { it.method == "DELETE" })
  }

  @Test
  fun laLetturaDelleSessioniFalliteNonPropaga() = runBlocking {
    failureCodes = mapOf("/sync/playback" to 503)

    val chiuse = SimklRepository.deletePlaybackSessionsFor(
      title = "Matrix", year = 1999, tmdbId = 603, imdbId = "tt0133093"
    )

    assertEquals("guasto best-effort: la rimozione locale resta valida", 0, chiuse)
  }

  // ── Infrastruttura ─────────────────────────────────────────────────────────

  private fun percorsi(): List<String> = received.map { it.path }

  private suspend fun collegaAccount() {
    SimklRepository.awaitLogin(SimklRepository.startLogin())
  }

  private fun film(): SimklMediaPayload =
    SimklMediaPayload("Matrix", 1999, 603, "tt0133093")

  private fun serie(): SimklMediaPayload =
    SimklMediaPayload(
      "La Casa Senza Fine", 2020, 5502, "tt5502000",
      episode = null, kind = SimklMediaKind.SERIES
    )

  private companion object {
    val OAUTH_DEVICE = """
      {"device_code":"dev-code","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin",
       "verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}
    """.trimIndent()

    val OAUTH_TOKEN = """
      {"access_token":"token-di-prova","refresh_token":"refresh-di-prova","token_type":"Bearer",
       "expires_in":604800,"scope":"media:read media:write"}
    """.trimIndent()

    val USER_SETTINGS = """{"user":{"name":"tester"},"account":{"id":42,"type":"vip"}}"""

    val HISTORY_ADDED = """{"added":{"movies":1,"shows":0,"episodes":0},"not_found":{"movies":[],"shows":[],"episodes":[]}}"""

    val HISTORY_REMOVED = """{"deleted":{"movies":1,"episodes":0}}"""

    val PLAYBACK_SESSIONS = """
      [
        {"id":111,"progress":50.0,"paused_at":"2026-11-09T15:16:09Z","type":"movie",
         "movie":{"title":"Matrix","year":1999,"ids":{"tmdb":"603","imdb":"tt0133093"}}},
        {"id":222,"progress":30.0,"paused_at":"2026-11-09T15:15:00Z","type":"episode",
         "show":{"title":"Lost","year":2004,"ids":{"tmdb":"270630"}},
         "episode":{"season":1,"number":2}}
      ]
    """.trimIndent()
  }
}
