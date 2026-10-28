package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.AppSettingsRepository
import com.example.data.remote.simkl.SimklApi
import com.example.data.remote.simkl.SimklApiClient
import com.example.data.remote.simkl.SimklAuthFlow
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Login SIMKL reale (MockWebServer) fino alla persistenza: il token **deve finire su disco**,
 * altrimenti il collegamento andrebbe perso al riavvio dell'app e la sincronizzazione della
 * Fase 3 non avrebbe più un account da usare.
 *
 * Il Client ID non viene chiesto all'utente ma è una configurazione di build: qui si usa il
 * canale residuo su disco solo per simulare una build già configurata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimklLoginPersistenceTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
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

  @Test
  fun ilLoginSalvaIlTokenSuDiscoConIIDatiDellAccount() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    AppSettingsRepository.setSimklClientId("client-di-prova")
    val api: SimklApi = SimklApiClient.createApi(
      client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build(),
      baseUrl = server.url("/").toString()
    )
    SimklRepository.init(context, api)

    // Ordine reale delle richieste: codice → token → dati account (/users/settings).
    enqueue(
      200,
      """{"device_code":"dev-code","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin",
         "verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}"""
    )
    enqueue(
      200,
      """{"access_token":"simkl_at_su_disco","refresh_token":"simkl_rt_su_disco","token_type":"Bearer",
         "expires_in":604800,"scope":"media:read media:write"}"""
    )
    enqueue(200, """{"user":{"name":"tester"},"account":{"id":42,"type":"vip"}}""")

    val prompt = SimklRepository.startLogin()
    assertEquals("BDWP-HQPK", prompt.userCode)
    val state = SimklRepository.awaitLogin(prompt)

    assertEquals("simkl_at_su_disco", state.accessToken)
    assertEquals("simkl_rt_su_disco", state.refreshToken)
    assertEquals("tester", state.username)
    assertEquals(42L, state.accountId)
    assertEquals(SimklAuthFlow.V2_DEVICE, state.flow)
    assertTrue("l'account risulta collegato", SimklRepository.accountState.value.isLoggedIn)
    assertTrue("scope in scrittura conservato", !state.isReadOnly)

    // Prova di persistenza: i token sono nel DataStore (che è su disco), non solo nello
    // stato in memoria del repository. Il percorso del file non è asseribile qui: la
    // Property di DataStore è un singleton di processo e in Robolectric può essere legata
    // alla cartella temporanea di un'altra classe di test.
    assertEquals(
      "l'access token deve essere persistito",
      "simkl_at_su_disco",
      SimklRepository.persistedAccessToken()
    )
  }
}
