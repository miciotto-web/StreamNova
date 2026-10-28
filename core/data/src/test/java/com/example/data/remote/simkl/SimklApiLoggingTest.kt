package com.example.data.remote.simkl

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Nessun PIN e nessun token nei log di SIMKL.
 *
 * Il flusso AUTH V1 interroga `GET /oauth/pin/{user_code}`: il codice utente sta nell'URL,
 * l'unico posto dove potrebbe finire in una riga di log. Questi test fissano la redazione
 * (regola dichiarata: `[redactSimklLogLine]`) e verificano che le richieste reali non
 * portino mai il codice nel percorso registrato.
 */
class SimklApiLoggingTest {

  private lateinit var server: MockWebServer
  private lateinit var api: SimklApi

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    api = SimklApiClient.createApi(
      client = OkHttpClient.Builder().build(),
      baseUrl = server.url("/").toString()
    )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun ilCodiceUtenteNonCompareNelleRigheDiLog() {
    val pollLine =
      "--> GET https://api.simkl.com/oauth/pin/ABCDE?client_id=abc123&app-name=streamnova-tv"
    val completeLine =
      "--> GET https://simkl.com/pin?user_code=BDWP-HQPK&client_id=abc123"

    val redactedPoll = redactSimklLogLine(pollLine)
    val redactedComplete = redactSimklLogLine(completeLine)

    assertFalse("il PIN non deve restare nella riga", redactedPoll.contains("ABCDE"))
    assertFalse("il codice non deve restare nella riga", redactedComplete.contains("BDWP-HQPK"))
    // Il resto della riga resta diagnosticabile.
    assertTrue(redactedPoll, redactedPoll.contains("/oauth/pin/"))
    assertTrue(redactedPoll, redactedPoll.contains("client_id=abc123"))
    assertTrue(redactedPoll, redactedPoll.contains("GET"))
  }

  @Test
  fun leRigheSenzaCodiceRestanoIntatte() {
    val deviceLine = "--> POST https://api.simkl.com/oauth2/device (48-byte body)"
    val tokenLine = "<-- 200 OK https://api.simkl.com/oauth2/token (123ms, 182-byte body)"

    assertEquals(deviceLine, redactSimklLogLine(deviceLine))
    assertEquals(tokenLine, redactSimklLogLine(tokenLine))
  }

  @Test
  fun laRedazioneCopreAncheLeFormeSenzaSpazi() {
    assertEquals(
      "--> GET https://api.simkl.com/oauth/pin/<codice-omesso>",
      redactSimklLogLine("--> GET https://api.simkl.com/oauth/pin/3552F")
    )
    assertEquals(
      "user_code=<codice-omesso>",
      redactSimklLogLine("user_code=3552F")
    )
  }

  @Test
  fun laRichiestaDiPollingRealeVieneRegistrataSenzaIlCodice() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"result":"OK","access_token":"simkl_at_test"}""")
    )

    runBlocking { api.pollPin("ABCDE", "client-di-prova") }

    val request = server.takeRequest()
    // Il codice sta nell'URL (non in un header o nel body): è esattamente la riga che
    // OkHttp registrerebbe, e che la redazione deve ripulire.
    val loggedLine = "--> GET ${request.requestUrl} (0-byte body)"
    assertTrue("l'URL reale contiene il codice", loggedLine.contains("ABCDE"))
    assertFalse("la riga registrata no", redactSimklLogLine(loggedLine).contains("ABCDE"))
  }
}
