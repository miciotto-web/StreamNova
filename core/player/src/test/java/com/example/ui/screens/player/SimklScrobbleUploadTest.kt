package com.example.ui.screens.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.remote.simkl.SimklApi
import com.example.data.remote.simkl.SimklApiClient
import com.example.data.repository.SimklEpisodePayload
import com.example.data.repository.SimklMediaPayload
import com.example.data.repository.SimklRepository
import com.example.data.repository.SimklRepository.SimklScrobbleEvent
import com.example.data.repository.SimklRepository.SimklScrobbleOutcome
import com.example.data.repository.SimklRepository.SimklScrobbleRejection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * Upload StreamNova → SIMKL verificato sulle **richieste HTTP reali**, dal callback del
 * player (`SimklScrobbler`) fino al server: nessun mock dell'API, un `MockWebServer` locale
 * con le risposte documentate di SIMKL e nessuna credenziale vera.
 *
 * Copre ciò che era scoperto: l'avvio invia `scrobble/start`, la pausa `scrobble/pause`, il
 * ritorno dal player `scrobble/stop` con la posizione prevista; la chiusura del player NON
 * annulla più la scrittura (prima la richiesta viveva sullo scope della composizione e
 * veniva cancellata insieme alla schermata); gli errori sono diagnosticabili e una durata
 * sconosciuta non produce un payload inventato.
 *
 * Limite dichiarato: `PlayerScreen` non è componibile in un test JVM, quindi il percorso è
 * verificato dal **suo punto di ingresso** (`SimklScrobbler`, lo stesso oggetto che il
 * player istanzia e chiama) fino al server. Il cablaggio Compose (quale callback del player
 * chiami quale metodo) resta verificabile solo sul dispositivo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimklScrobbleUploadTest {

  private lateinit var server: MockWebServer
  private lateinit var api: SimklApi

  private class RecordedScrobble(val path: String, val body: String)

  /** Richieste di scrittura ricevute dal server di prova, in ordine di arrivo. */
  private val received = java.util.Collections.synchronizedList(mutableListOf<RecordedScrobble>())

  /** Endpoint → codice di errore. Assente = 200. */
  private var failureCodes: Map<String, Int> = emptyMap()

  /** Ritardo della risposta per le scritture: serve a tenere la richiesta "in volo". */
  private var scrobbleResponseDelayMs: Long = 0L

  @Before
  fun setUp() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    AppSettingsRepository.setSimklClientId("client-di-prova")

    server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore("?")
        val body = request.body.readUtf8()
        if (path.startsWith("/scrobble/")) {
          received.add(RecordedScrobble(path, body))
          failureCodes[path]?.let { code ->
            return MockResponse().setResponseCode(code).setBody("""{"error":"guasto simulato"}""")
          }
          val response = json("{}")
          if (scrobbleResponseDelayMs > 0L) {
            response.setBodyDelay(scrobbleResponseDelayMs, TimeUnit.MILLISECONDS)
          }
          return response
        }
        val payload = when (path) {
          "/oauth2/device" -> OAUTH_DEVICE
          "/oauth2/token" -> OAUTH_TOKEN
          "/users/settings" -> USER_SETTINGS
          else -> "{}"
        }
        return json(payload)
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
    SimklRepository.clearScrobbleDiagnostics()
    received.clear()
    // Le voci rimandate vivono su DataStore, che è un singleton di processo: senza questa
    // pulizia l'esito di un test dipenderebbe dall'ordine di esecuzione.
    SimklRepository.savePendingPlaybacks(emptyList())
    collegaAccount()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  // ── Infrastruttura ─────────────────────────────────────────────────────────

  private fun json(body: String): MockResponse = MockResponse()
    .setResponseCode(200)
    .setHeader("Content-Type", "application/json")
    .setBody(body)

  private suspend fun collegaAccount() {
    SimklRepository.awaitLogin(SimklRepository.startLogin())
  }

  private fun film(): SimklMediaPayload =
    SimklMediaPayload("Matrix", 1999, 603, "tt0133093")

  private fun serie(): SimklMediaPayload =
    SimklMediaPayload("La Casa Senza Fine", 2020, 5502, "tt5502000", SimklEpisodePayload(2, 4))

  private fun paths(): List<String> = received.map { it.path }

  private fun bodyAt(index: Int): JSONObject = JSONObject(received[index].body)

  private fun bodyOf(path: String): JSONObject = bodyAt(paths().indexOf(path))

  private suspend fun attendi(timeoutMs: Long = 5000L, condizione: () -> Boolean): Boolean {
    val scadenza = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < scadenza) {
      if (condizione()) return true
      delay(25)
    }
    return condizione()
  }

  private suspend fun attesaCodaScrobble(): Boolean =
    attendi { SimklRepository.pendingScrobbleCount() == 0 }

  // ── Il percorso completo: evento del player → richiesta HTTP ───────────────

  @Test
  fun lAvvioLaPausaELaUscitaProduconoStartPauseEStopConLaPosizionePrevista() = runBlocking {
    val scrobbler = SimklScrobbler(film())

    scrobbler.onPlay(12.5)
    assertTrue(attesaCodaScrobble())
    assertTrue("l'avvio deve inviare scrobble/start: ${paths()}", paths().contains("/scrobble/start"))
    assertEquals(12.5, bodyOf("/scrobble/start").getDouble("progress"), 0.0001)

    scrobbler.onPause(41.0)
    assertTrue(attendi { paths().contains("/scrobble/pause") })
    assertEquals(41.0, bodyOf("/scrobble/pause").getDouble("progress"), 0.0001)

    scrobbler.onStop(66.5)
    assertTrue(attendi { paths().contains("/scrobble/stop") })
    assertEquals(66.5, bodyOf("/scrobble/stop").getDouble("progress"), 0.0001)

    // Un solo consumatore: l'ordine degli eventi è quello prodotto dal player.
    assertEquals(listOf("/scrobble/start", "/scrobble/pause", "/scrobble/stop"), paths())
    assertTrue(
      "esiti registrati: ${SimklRepository.scrobbleDiagnostics()}",
      SimklRepository.scrobbleDiagnostics().all { it.isSuccess }
    )
  }

  @Test
  fun unFilmInviaGliIdentificativiTmdbEImdb() = runBlocking {
    SimklScrobbler(film()).onPlay(20.0)
    assertTrue(attendi { paths().contains("/scrobble/start") })

    val body = bodyOf("/scrobble/start")
    val movie = body.getJSONObject("movie")
    assertEquals("Matrix", movie.getString("title"))
    assertEquals(1999, movie.getInt("year"))
    val ids = movie.getJSONObject("ids")
    assertEquals("603", ids.getString("tmdb"))
    assertEquals("tt0133093", ids.getString("imdb"))
    assertFalse("un film non deve portare `episode`", body.has("episode"))
    assertFalse(body.has("show"))
  }

  @Test
  fun unEpisodioInviaStagioneEpisodioEIdentificativiDellaSerie() = runBlocking {
    SimklScrobbler(serie()).onPlay(50.0)
    assertTrue(attendi { paths().contains("/scrobble/start") })

    val body = bodyOf("/scrobble/start")
    assertFalse("un episodio non è un film", body.has("movie"))
    val show = body.getJSONObject("show")
    assertEquals("La Casa Senza Fine", show.getString("title"))
    assertEquals("5502", show.getJSONObject("ids").getString("tmdb"))
    val episode = body.getJSONObject("episode")
    assertEquals(2, episode.getInt("season"))
    assertEquals(4, episode.getInt("episode"))
  }

  @Test
  fun laChiusuraDelPlayerNonAnnullaIlSalvataggioDelPuntoDiRipresa() = runBlocking {
    // La risposta arriva DOPO la chiusura simulata del player: la richiesta è quindi in volo
    // mentre la schermata è già stata dismessa.
    scrobbleResponseDelayMs = 400L
    val schermataPlayer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val scrobbler = SimklScrobbler(film())

    scrobbler.onPlay(30.0)
    scrobbler.onStop(64.0)
    // Uscita dal player: tutto ciò che era legato alla composizione viene cancellato. È
    // esattamente il punto in cui la richiesta di `stop` veniva persa.
    schermataPlayer.cancel()

    assertTrue(
      "lo `stop` non è arrivato al server: ${SimklRepository.scrobbleDiagnostics()}",
      attendi(10000L) {
        SimklRepository.scrobbleDiagnostics().any { it.event == SimklScrobbleEvent.STOP && it.isSuccess }
      }
    )
    assertEquals(1, paths().count { it == "/scrobble/stop" })
    assertEquals(64.0, bodyOf("/scrobble/stop").getDouble("progress"), 0.0001)
  }

  @Test
  fun laFineDellaRiproduzioneChiudeLaSessioneAlCentoPerCento() = runBlocking {
    val scrobbler = SimklScrobbler(serie())
    scrobbler.onPlay(90.0)
    scrobbler.onEnded()

    assertTrue(attendi { paths().contains("/scrobble/stop") })
    assertEquals(100.0, bodyOf("/scrobble/stop").getDouble("progress"), 0.0001)
    // `onEnded` non è un `pause`: la sessione si chiude, non si sospende.
    assertFalse(paths().contains("/scrobble/pause"))
  }

  // ── Errori: gestiti, ritentati se ha senso, sempre diagnosticabili ─────────

  @Test
  fun unErroreHttpVieneRitentatoRegistratoENonPropaga() = runBlocking {
    failureCodes = mapOf("/scrobble/stop" to 500)
    val scrobbler = SimklScrobbler(film())

    scrobbler.onPlay(20.0)
    scrobbler.onStop(55.0)
    assertTrue(
      attendi(10000L) { SimklRepository.scrobbleDiagnostics().any { it.event == SimklScrobbleEvent.STOP } }
    )
    assertTrue(attesaCodaScrobble())

    val report = SimklRepository.scrobbleDiagnostics().last { it.event == SimklScrobbleEvent.STOP }
    assertEquals(SimklScrobbleOutcome.HTTP_ERROR, report.outcome)
    assertEquals(500, report.httpCode)
    assertEquals("un 5xx è ritentato una volta", 2, report.attempts)
    assertEquals(2, paths().count { it == "/scrobble/stop" })
    // Il `start`, andato a buon fine, resta registrato come riuscito.
    assertEquals(1, paths().count { it == "/scrobble/start" })
    assertTrue(SimklRepository.scrobbleDiagnostics().any { it.event == SimklScrobbleEvent.START && it.isSuccess })
  }

  @Test
  fun unErroreClientNonVieneRitentatoInvano() = runBlocking {
    // 403 = token senza `media:write`: la stessa richiesta non diventerà valida.
    failureCodes = mapOf("/scrobble/start" to 403)
    SimklScrobbler(film()).onPlay(10.0)

    assertTrue(attendi(8000L) { SimklRepository.scrobbleDiagnostics().isNotEmpty() })
    assertTrue(attesaCodaScrobble())
    delay(100L)

    val report = SimklRepository.scrobbleDiagnostics().single()
    assertEquals(SimklScrobbleOutcome.HTTP_ERROR, report.outcome)
    assertEquals(403, report.httpCode)
    assertEquals(1, report.attempts)
    assertEquals(1, paths().count { it == "/scrobble/start" })
  }

  @Test
  fun unGuastoDiReteNonPropagaECompareNellaDiagnosi() = runBlocking {
    // Nessun server: l'errore è di rete, non HTTP — e deve restare distinguibile.
    server.shutdown()
    SimklScrobbler(film()).onPlay(10.0)

    assertTrue(
      attendi(10000L) { SimklRepository.scrobbleDiagnostics().isNotEmpty() }
    )
    assertTrue(attesaCodaScrobble())

    val report = SimklRepository.scrobbleDiagnostics().single()
    assertEquals(SimklScrobbleOutcome.NETWORK_ERROR, report.outcome)
    assertEquals(0, report.httpCode)
    assertTrue("la classe dell'errore finisce nella diagnosi", report.detail.isNotBlank())
  }

  @Test
  fun senzaAccountCollegatoNonSiInviaNullaEIlMotivoEregistrato() = runBlocking {
    SimklRepository.signOut()
    SimklScrobbler(film()).onPlay(15.0)

    assertTrue(
      attendi { SimklRepository.scrobbleDiagnostics().any { it.outcome == SimklScrobbleOutcome.NOT_LOGGED_IN } }
    )
    assertTrue("nessuna scrittura senza account", received.none { it.path.startsWith("/scrobble/") })
  }

  // ── Payload non validi: mai inventati ────────────────────────────────────

  @Test
  fun durataSconosciutaNonProduceUnPayloadInventato() = runBlocking {
    val scrobbler = SimklScrobbler(film())
    // `null` è "durata non nota" (`exoPlayer.duration` = C.TIME_UNSET), non "posizione zero".
    scrobbler.onPlay(null)
    scrobbler.onStop(null)

    assertTrue(attesaCodaScrobble())
    delay(200L)
    assertEquals("nessuna richiesta con una percentuale inventata", emptyList<String>(), paths())
    // Un solo evento arriva al repository: senza un `start` accettato non c'è sessione da
    // chiudere, quindi l'uscita non genera nemmeno un evento da registrare.
    val report = SimklRepository.scrobbleDiagnostics().single()
    assertEquals(SimklScrobbleEvent.START, report.event)
    assertEquals(SimklScrobbleOutcome.REJECTED, report.outcome)
    assertEquals(SimklScrobbleRejection.UNKNOWN_DURATION, report.rejection)
  }

  @Test
  fun unaDurataCheArrivaDopoInviaLoStartCheMancava() = runBlocking {
    val scrobbler = SimklScrobbler(film())
    // Prima callback con durata ancora ignota (buffering): l'evento è rifiutato.
    scrobbler.onPlay(null)
    assertTrue(attesaCodaScrobble())
    assertEquals(emptyList<String>(), paths())

    // La durata diventa nota: la sessione non era stata segnata come avviata, quindi il
    // `start` parte adesso invece di essere perso per sempre.
    scrobbler.onPlay(37.5)
    assertTrue(attendi { paths().contains("/scrobble/start") })
    assertEquals(37.5, bodyOf("/scrobble/start").getDouble("progress"), 0.0001)
  }

  @Test
  fun senzaIdentificativiNonVieneInviataAlcunaRichiesta() = runBlocking {
    val senzaId = SimklMediaPayload("Titolo senza identificativi", null, null, null)
    SimklScrobbler(senzaId).onPlay(10.0)

    assertTrue(attesaCodaScrobble())
    delay(200L)
    assertEquals(emptyList<String>(), paths())
    val report = SimklRepository.scrobbleDiagnostics().single()
    assertEquals(SimklScrobbleOutcome.REJECTED, report.outcome)
    assertEquals(SimklScrobbleRejection.MISSING_IDS, report.rejection)
  }

  // ── Macchina a stati dell'adattatore (nessun HTTP) ────────────────────────

  @Test
  fun leCallbackRipetuteNonDuplicanoLaSequenzaStartPausaStartStop() {
    val eventi = mutableListOf<String>()
    val progressi = mutableListOf<Double?>()
    val scrobbler = SimklScrobbler(film()) { event, _, progress ->
      eventi.add(event.name)
      progressi.add(progress)
      true
    }

    scrobbler.onPlay(1.0)
    scrobbler.onPlay(2.0)
    scrobbler.onPause(3.0)
    scrobbler.onPause(4.0)
    scrobbler.onPlay(5.0)
    scrobbler.onPlay(6.0)
    scrobbler.onStop(7.0)
    scrobbler.onStop(8.0)

    assertEquals(listOf("START", "PAUSE", "START", "STOP"), eventi)
    assertEquals(listOf(1.0, 3.0, 5.0, 7.0), progressi)
  }

  @Test
  fun unEventoRifiutatoNonSegnaLaSessioneComeAvviata() {
    val eventi = mutableListOf<String>()
    // Il primo `start` è rifiutato (durata non nota), il secondo è accettato.
    var accetta = false
    val scrobbler = SimklScrobbler(film()) { event, _, _ ->
      eventi.add(event.name)
      accetta
    }

    scrobbler.onPlay(null)
    scrobbler.onPlay(null)
    scrobbler.onPause(43.0)
    assertEquals("nessuna sessione avviata: nessun `pause` da inviare", listOf("START", "START"), eventi)

    accetta = true
    scrobbler.onPlay(44.0)
    scrobbler.onPause(45.0)
    assertEquals(listOf("START", "START", "START", "PAUSE"), eventi)
  }

  @Test
  fun unAvvioRifiutatoNonProduceUnoStop() {
    // Nessuno `stop` dopo un avvio mai accettato: non c'è sessione da chiudere e uno `stop`
    // isolato scriverebbe su SIMKL un punto di ripresa che non corrisponde a nulla.
    val eventi = mutableListOf<String>()
    val scrobbler = SimklScrobbler(film()) { event, _, _ ->
      eventi.add(event.name)
      false
    }
    scrobbler.onPlay(10.0)
    scrobbler.onStop(20.0)
    scrobbler.onEnded()
    assertEquals(listOf("START"), eventi)
  }

  @Test
  fun ilPayloadDelloScrobbleSuperaLaValidazioneDiRetrofit() {
    // TRAPPOLA GIÀ SCATTATA: `@Body payload: Map<String, Any>` compila in `Map<String, ?>`
    // e Retrofit rifiuta la chiamata con "Parameter type must not include a type variable
    // or wildcard" PRIMA di qualunque richiesta HTTP. Con quel tipo nessuno scrobble è mai
    // partito, e l'errore era assorbito da un `runCatching` silenzioso. Il payload è ora
    // `Map<String, @JvmSuppressWildcards Any>`: questo test fallisce se qualcuno lo riporta
    // indietro, senza dover arrivare al server.
    val metodiScrittura = listOf("startScrobble", "pauseScrobble", "stopScrobble", "addHistory")
    for (nome in metodiScrittura) {
      val metodo = SimklApi::class.java.declaredMethods.firstOrNull { it.name == nome }
        ?: error("metodo $nome assente da SimklApi")
      val payload = metodo.genericParameterTypes
        .filterIsInstance<java.lang.reflect.ParameterizedType>()
        .firstOrNull { it.rawType == Map::class.java }
        ?: error("$nome non ha più un payload Map")
      val argomenti = payload.actualTypeArguments
      assertTrue(
        "$nome: il payload non deve contenere wildcard o type variable ($payload)",
        argomenti.none { it is java.lang.reflect.WildcardType || it is java.lang.reflect.TypeVariable<*> }
      )
      assertEquals("$nome: payload con chiavi String", String::class.java, argomenti[0])
    }
  }

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
  }
}
