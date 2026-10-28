package com.example.data.remote.simkl

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Flussi di autorizzazione SIMKL, verificati contro le forme di risposta documentate
 * su api.simkl.org (device flow V2 RFC 8628 e PIN V1) servite da MockWebServer.
 *
 * Il tempo è virtuale: [VirtualClock] avanza da sé, quindi i test del polling
 * (`authorization_pending`, `slow_down`, scadenza) girano in millisecondi reali.
 */
class SimklAuthClientTest {

  private lateinit var server: MockWebServer
  private lateinit var api: SimklApi

  private class VirtualClock(startAt: Long = 1_700_000_000_000L) {
    var now: Long = startAt
    val waits = mutableListOf<Long>()
    val sleeper: suspend (Long) -> Unit = { ms ->
      waits.add(ms)
      now += ms
    }
  }

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    api = SimklApiClient.createApi(
      client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build(),
      baseUrl = server.url("/").toString()
    )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun enqueue(code: Int, body: String) {
    server.enqueue(
      MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
    )
  }

  private val deviceAuthorizationBody = """
    {"device_code":"dev-code-1","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin",
     "verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}
  """.trimIndent()

  private val pinBody = """
    {"user_code":"ABCDE","verification_uri":"https://simkl.com/pin","device_code":"DEVICE_CODE",
     "expires_in":900,"interval":5}
  """.trimIndent()

  private val refreshTokenGrant = "refresh_token"

  @Test
  fun startLoginUsaIlDeviceFlowV2ECompilaIlQrConIlCodice() = runBlocking {
    val clock = VirtualClock()
    enqueue(200, deviceAuthorizationBody)
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    val prompt = client.startLogin("client-v2")

    assertEquals(SimklAuthFlow.V2_DEVICE, prompt.flow)
    assertEquals("BDWP-HQPK", prompt.userCode)
    // Il QR deve contenere l'URL precompilato: è l'unico modo per non digitare nulla.
    assertEquals("https://simkl.com/pin?user_code=BDWP-HQPK", prompt.qrPayload)
    assertEquals("https://simkl.com/pin", prompt.verificationUri)
    assertTrue("il codice è davvero già inserito nell'URL del QR", prompt.qrPrefillsCode)
    assertEquals(900L, prompt.secondsLeft(clock.now))
    assertTrue(prompt.isV2)

    val request = server.takeRequest()
    assertEquals("/oauth2/device", request.path?.substringBefore('?'))
    val body = request.body.readUtf8()
    assertTrue(body, body.contains("client_id=client-v2"))
    // media:write è indispensabile per scrobble e cronologia: senza, il token è read-only.
    assertTrue(body, body.contains("scope="))
    assertTrue(body, body.contains("media%3Aread"))
    assertTrue(body, body.contains("media%3Awrite"))
  }

  @Test
  fun startLoginRipiegaSulPinV1SeIlClientNonEAbilitatoPerOAuth2() = runBlocking {
    val clock = VirtualClock()
    // Risposta reale di SIMKL per un client_id V1 (verificata in Fase A).
    enqueue(401, """{"error":"invalid_client","error_description":"Unknown or missing client_id"}""")
    enqueue(200, pinBody)
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    val prompt = client.startLogin("client-v1")

    assertEquals(SimklAuthFlow.V1_PIN, prompt.flow)
    assertEquals("ABCDE", prompt.userCode)
    // In V1 non esiste un URL precompilato documentato: il QR apre la pagina del PIN.
    assertEquals("https://simkl.com/pin", prompt.qrPayload)
    assertFalse("in V1 il codice va digitato: il QR non lo precompila", prompt.qrPrefillsCode)
    assertFalse(prompt.isV2)

    assertEquals("/oauth2/device", server.takeRequest().path?.substringBefore('?'))
    assertEquals("/oauth/pin", server.takeRequest().path?.substringBefore('?'))
  }

  @Test
  fun senzaVerificationUriCompleteIlQrNonDichiaraIlCodiceGiaInserito() = runBlocking {
    // Il campo `verification_uri_complete` è documentato per il flusso V2 ma la risposta
    // può non contenerlo: in quel caso il QR è il solo indirizzo di verifica e il codice
    // va digitato, quindi la UI non deve dichiarare il contrario.
    val clock = VirtualClock()
    enqueue(
      200,
      """{"device_code":"dev-no-complete","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin",
         "expires_in":900,"interval":5}"""
    )
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    val prompt = client.startLogin("client-v2")

    assertEquals(SimklAuthFlow.V2_DEVICE, prompt.flow)
    assertEquals("https://simkl.com/pin", prompt.qrPayload)
    assertFalse("senza URL precompilato il codice non è già inserito", prompt.qrPrefillsCode)
  }

  @Test
  fun startLoginProvaPrimaIlFlussoMemorizzato() = runBlocking {
    val clock = VirtualClock()
    enqueue(200, pinBody)
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    val prompt = client.startLogin("client-v1", rememberedFlow = SimklAuthFlow.V1_PIN)

    assertEquals(SimklAuthFlow.V1_PIN, prompt.flow)
    assertEquals("/oauth/pin", server.takeRequest().path?.substringBefore('?'))
    assertEquals(1, server.requestCount)
  }

  @Test
  fun startLoginSegnalaUnClientIdInutilizzabilePerEntrambiIFlussi() = runBlocking {
    val clock = VirtualClock()
    enqueue(401, """{"error":"invalid_client","error_description":"Unknown or missing client_id"}""")
    enqueue(412, """{"error":"client_id_failed","code":412,"message":"Your client_id is wrong. Try another one"}""")
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    val error = try {
      client.startLogin("client-rotto")
      fail("atteso SimklClientRejectedException")
      null
    } catch (e: SimklClientRejectedException) {
      e
    }

    val message = error?.localizedMessage.orEmpty()
    assertTrue(message, message.contains("invalid_client"))
    assertTrue(message, message.contains("client_id_failed"))
    // Un ID rifiutato dal server non è lo stesso guasto di una build senza Client ID:
    // i due casi restano distinguibili per chi legge i log e per i messaggi a schermo.
    assertFalse(
      "il rifiuto del server non è una configurazione assente",
      error is SimklClientIdMissingException
    )
  }

  @Test
  fun awaitLoginV2AttendeLApprovazioneEPoiSalvaIToken() = runBlocking {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)
    enqueue(400, """{"error":"authorization_pending"}""")
    enqueue(400, """{"error":"authorization_pending"}""")
    enqueue(
      200,
      """{"access_token":"simkl_at_abc","refresh_token":"simkl_rt_def","token_type":"Bearer",
          "expires_in":604800,"scope":"media:read media:write"}"""
    )

    val prompt = SimklLoginPrompt(
      flow = SimklAuthFlow.V2_DEVICE,
      userCode = "BDWP-HQPK",
      verificationUri = "https://simkl.com/pin",
      qrPayload = "https://simkl.com/pin?user_code=BDWP-HQPK",
      deviceCode = "dev-code-1",
      expiresAtEpochMs = clock.now + 900_000L,
      intervalSeconds = 5L
    )
    val bundle = client.awaitLogin("client-v2", prompt)

    assertEquals("simkl_at_abc", bundle.accessToken)
    assertEquals("simkl_rt_def", bundle.refreshToken)
    assertTrue(bundle.canWrite)
    // 3 richieste = 2 pending + 1 riuscita, ognuna preceduta dall'attesa dell'interval.
    assertEquals(3, server.requestCount)
    assertEquals(listOf(5_000L, 5_000L, 5_000L), clock.waits)
    assertEquals(clock.now + 604_800_000L, bundle.expiresAtEpochMs)
  }

  @Test
  fun awaitLoginV2RispettaIlSlowDownAggiungendoCinqueSecondi() = runBlocking {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)
    enqueue(400, """{"error":"slow_down"}""")
    enqueue(200, """{"access_token":"simkl_at_slow","scope":"media:read media:write","expires_in":604800}""")

    client.awaitLogin(
      "client-v2",
      SimklLoginPrompt(
        flow = SimklAuthFlow.V2_DEVICE,
        userCode = "BDWP-HQPK",
        verificationUri = "https://simkl.com/pin",
        qrPayload = "https://simkl.com/pin?user_code=BDWP-HQPK",
        deviceCode = "dev-code-1",
        expiresAtEpochMs = clock.now + 900_000L,
        intervalSeconds = 5L
      )
    )

    // Dopo slow_down l'interval passa a 10 s: ritentare subito resterebbe rate-limited.
    assertEquals(listOf(5_000L, 10_000L), clock.waits)
  }

  @Test
  fun awaitLoginV2SiFermaQuandoIlCodiceScade() = runBlocking {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)
    enqueue(400, """{"error":"expired_token"}""")

    try {
      client.awaitLogin(
        "client-v2",
        SimklLoginPrompt(
          flow = SimklAuthFlow.V2_DEVICE,
          userCode = "BDWP-HQPK",
          verificationUri = "https://simkl.com/pin",
          qrPayload = "https://simkl.com/pin?user_code=BDWP-HQPK",
          deviceCode = "dev-code-1",
          expiresAtEpochMs = clock.now + 900_000L,
          intervalSeconds = 5L
        )
      )
      fail("atteso SimklLoginExpiredException")
    } catch (e: SimklLoginExpiredException) {
      assertTrue(e.localizedMessage.orEmpty().contains("scaduto"))
    }
    assertEquals(1, server.requestCount)
  }

  @Test
  fun awaitLoginV2NonInterrogaOltreLaScadenzaDelCodice() = runBlocking {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)
    // Nessuna risposta in coda: se il client interrogasse, MockWebServer risponderebbe 404.

    try {
      client.awaitLogin(
        "client-v2",
        SimklLoginPrompt(
          flow = SimklAuthFlow.V2_DEVICE,
          userCode = "BDWP-HQPK",
          verificationUri = "https://simkl.com/pin",
          qrPayload = "https://simkl.com/pin?user_code=BDWP-HQPK",
          deviceCode = "dev-code-1",
          // Scadenza già superata dall'attesa dell'interval (5 s).
          expiresAtEpochMs = clock.now + 1_000L,
          intervalSeconds = 5L
        )
      )
      fail("atteso SimklLoginExpiredException")
    } catch (e: SimklLoginExpiredException) {
      assertTrue(e.localizedMessage.orEmpty().contains("scaduto"))
    }
    assertEquals(0, server.requestCount)
  }

  @Test
  fun awaitLoginV1InterpretaLaFormaPendingPoiIlToken() = runBlocking {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)
    enqueue(200, """{"result":"KO","message":"Authorization pending"}""")
    enqueue(200, """{"result":"OK","access_token":"simkl_at_v1"}""")

    val bundle = client.awaitLogin(
      "client-v1",
      SimklLoginPrompt(
        flow = SimklAuthFlow.V1_PIN,
        userCode = "ABCDE",
        verificationUri = "https://simkl.com/pin",
        qrPayload = "https://simkl.com/pin",
        deviceCode = "DEVICE_CODE",
        expiresAtEpochMs = clock.now + 900_000L,
        intervalSeconds = 5L
      )
    )

    assertEquals("simkl_at_v1", bundle.accessToken)
    assertNull(bundle.refreshToken)
    assertTrue("i token V1 sono sempre full-access", bundle.canWrite)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun awaitLoginV1SiFermaSeIlCodiceNonEsistePiu() = runBlocking {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)
    // Codice consumato: SIMKL risponde con la forma di creazione di un NUOVO PIN.
    enqueue(200, pinBody)

    try {
      client.awaitLogin(
        "client-v1",
        SimklLoginPrompt(
          flow = SimklAuthFlow.V1_PIN,
          userCode = "ABCDE",
          verificationUri = "https://simkl.com/pin",
          qrPayload = "https://simkl.com/pin",
          deviceCode = "DEVICE_CODE",
          expiresAtEpochMs = clock.now + 900_000L,
          intervalSeconds = 5L
        )
      )
      fail("atteso SimklLoginExpiredException")
    } catch (e: SimklLoginExpiredException) {
      assertTrue(e.localizedMessage.orEmpty().contains("non più valido"))
    }
    assertEquals(1, server.requestCount)
  }

  @Test
  fun refreshConservaIlRefreshTokenQuandoLaRispostaNonNeRestituisceUno() = runBlocking {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)
    enqueue(
      200,
      """{"access_token":"simkl_at_nuovo","token_type":"Bearer","expires_in":604800,
          "scope":"media:read media:write"}"""
    )

    val bundle = client.refresh("client-v2", "simkl_rt_vecchio")

    assertEquals("simkl_at_nuovo", bundle.accessToken)
    // Il refresh token V2 è non rotante: se non torna indietro vale ancora quello inviato.
    assertEquals("simkl_rt_vecchio", bundle.refreshToken)
    val request = server.takeRequest()
    assertEquals("/oauth2/token", request.path?.substringBefore('?'))
    val body = request.body.readUtf8()
    assertTrue(body, body.contains("grant_type=$refreshTokenGrant"))
    assertTrue(body, body.contains("refresh_token=simkl_rt_vecchio"))
  }

  @Test
  fun refreshSegnalaUnGrantMortoDistinguendoloDaUnErroreDiRete() = runBlocking {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)
    enqueue(400, """{"error":"invalid_grant","error_description":"Refresh token expired"}""")

    try {
      client.refresh("client-v2", "simkl_rt_morto")
      fail("atteso SimklRefreshException")
    } catch (e: SimklRefreshException) {
      assertEquals("invalid_grant", e.error)
      assertTrue(e.needsReauthorization)
    }
  }

  @Test
  fun revocaRestituisceTrueSu200AncheSeNonEUnaProva() = runBlocking {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)
    enqueue(200, "{}")

    assertTrue(client.revoke("client-v2", "simkl_rt_x"))
    val body = server.takeRequest().body.readUtf8()
    assertTrue(body, body.contains("token=simkl_rt_x"))
  }

  @Test
  fun parseErrorIgnoraUnBodyNonJson() {
    val clock = VirtualClock()
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    assertNull(client.parseError("non-json"))
    assertNull(client.parseError(null))
    assertEquals("slow_down", client.parseError("""{"error":"slow_down"}""")?.error)
  }

  // ── Risposte accettate ma inutilizzabili ───────────────────────────────────────
  // Un 2xx che non contiene i campi del protocollo non è un errore di rete (la risposta
  // è arrivata) né un rifiuto del client (il client non è stato rifiutato): è una
  // [SimklInvalidResponseException], e va distinta perché l'azione dell'utente cambia.

  @Test
  fun unCorpoNonJsonDopoUn200EUnRispostaNonValida() = runBlocking {
    val clock = VirtualClock()
    // Proxy/captive portal che risponde 200 con una pagina HTML.
    enqueue(200, "<html><body>Access denied</body></html>")
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    try {
      client.startLogin("client-v2")
      fail("atteso SimklInvalidResponseException")
    } catch (e: SimklInvalidResponseException) {
      assertTrue(e.localizedMessage.orEmpty().isNotBlank())
    }
    assertEquals("nessun ripiego indovinato sull'altro flusso", 1, server.requestCount)
  }

  @Test
  fun un200SenzaICampiDelProtocolloEUnRispostaNonValida() = runBlocking {
    val clock = VirtualClock()
    enqueue(200, """{"user_code":"BDWP-HQPK"}""")
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    try {
      client.startLogin("client-v2")
      fail("atteso SimklInvalidResponseException")
    } catch (e: SimklInvalidResponseException) {
      assertTrue(e.localizedMessage.orEmpty().isNotBlank())
    }
    assertEquals(1, server.requestCount)
  }

  @Test
  fun un200VuotoEUnRispostaNonValida() = runBlocking {
    val clock = VirtualClock()
    enqueue(200, "")
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    try {
      client.startLogin("client-v2")
      fail("atteso SimklInvalidResponseException")
    } catch (e: SimklInvalidResponseException) {
      assertTrue(e.localizedMessage.orEmpty().isNotBlank())
    }
    assertEquals(1, server.requestCount)
  }

  @Test
  fun unPollingAccettatoSenzaAccessTokenEUnRispostaNonValida() = runBlocking {
    val clock = VirtualClock()
    enqueue(200, """{"token_type":"Bearer","scope":"media:read"}""")
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    try {
      client.awaitLogin(
        "client-v2",
        SimklLoginPrompt(
          flow = SimklAuthFlow.V2_DEVICE,
          userCode = "BDWP-HQPK",
          verificationUri = "https://simkl.com/pin",
          qrPayload = "https://simkl.com/pin?user_code=BDWP-HQPK",
          deviceCode = "dev-code-1",
          expiresAtEpochMs = clock.now + 900_000L,
          intervalSeconds = 5L
        )
      )
      fail("atteso SimklInvalidResponseException")
    } catch (e: SimklInvalidResponseException) {
      assertTrue(e.localizedMessage.orEmpty().contains("access_token"))
    }
  }

  @Test
  fun unaConnessioneCadutaRestaUnErroreDiRete() = runBlocking {
    val clock = VirtualClock()
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    try {
      client.startLogin("client-v2")
      fail("atteso un errore di rete")
    } catch (e: SimklInvalidResponseException) {
      fail("una connessione caduta non è una risposta API: ${e.localizedMessage}")
    } catch (e: IOException) {
      // La classificazione "errore di rete" resta in capo al chiamante.
      assertTrue(e.localizedMessage.orEmpty().isNotBlank())
    }
  }

  @Test
  fun unaRispostaV1SenzaScadenzaUsaLaFinestraDocumentata() = runBlocking {
    val clock = VirtualClock()
    // `expires_in` è documentato ma non sempre presente: il codice appena ottenuto non si
    // butta via, si usa la finestra documentata di 15 minuti.
    enqueue(
      200,
      """{"user_code":"ABCDE","verification_uri":"https://simkl.com/pin","device_code":"DEVICE_CODE"}"""
    )
    val client = SimklAuthClient(api, now = { clock.now }, sleeper = clock.sleeper)

    val prompt = client.startLogin("client-v1", rememberedFlow = SimklAuthFlow.V1_PIN)

    assertEquals("ABCDE", prompt.userCode)
    assertEquals("https://simkl.com/pin", prompt.qrPayload)
    assertEquals(900L, prompt.secondsLeft(clock.now))
  }
}
