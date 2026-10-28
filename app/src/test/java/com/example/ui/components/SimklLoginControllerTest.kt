package com.example.ui.components

import com.example.data.remote.simkl.SimklAccountState
import com.example.data.remote.simkl.SimklAuthFlow
import com.example.data.remote.simkl.SimklAuthException
import com.example.data.remote.simkl.SimklClientConfigException
import com.example.data.remote.simkl.SimklClientIdMissingException
import com.example.data.remote.simkl.SimklClientRejectedException
import com.example.data.remote.simkl.SimklInvalidResponseException
import com.example.data.remote.simkl.SimklLoginExpiredException
import com.example.data.remote.simkl.SimklLoginPrompt
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ciclo di vita del login SIMKL: un solo polling per volta, fine del polling per
 * approvazione/annullamento/scadenza, "Nuovo codice" che non chiude il pannello, errori di
 * rete gestiti e sincronizzazione che non può far fallire un login riuscito.
 *
 * Il tempo è quello del [runTest]: nessuna attesa reale, nessuna rete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimklLoginControllerTest {

  private fun promptOf(code: String = "BDWP-HQPK", expiresInMs: Long = 900_000L) = SimklLoginPrompt(
    flow = SimklAuthFlow.V2_DEVICE,
    userCode = code,
    verificationUri = "https://simkl.com/pin",
    qrPayload = "https://simkl.com/pin?user_code=$code",
    deviceCode = "device-$code",
    expiresAtEpochMs = System.currentTimeMillis() + expiresInMs,
    intervalSeconds = 5L
  )

  private val loggedIn = SimklAccountState(
    accessToken = "simkl_at_test",
    refreshToken = "simkl_rt_test",
    username = "tester",
    flow = SimklAuthFlow.V2_DEVICE,
    scope = "media:read media:write"
  )

  private fun controller(
    scope: CoroutineScope,
    requestPrompt: suspend () -> SimklLoginPrompt,
    awaitAuthorization: suspend (SimklLoginPrompt) -> SimklAccountState,
    onAuthorized: suspend (SimklAccountState) -> Unit = {}
  ) = SimklLoginController(requestPrompt, awaitAuthorization, onAuthorized, scope)

  @Test
  fun startChiedeUnCodiceEAvviaUnSoloPolling() = runTest {
    var promptRequests = 0
    var activePollers = 0
    val gate = CompletableDeferred<SimklAccountState>()
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptRequests++; promptOf() },
      awaitAuthorization = { activePollers++; try { gate.await() } finally { activePollers-- } }
    )

    controller.start()
    runCurrent()

    assertEquals(1, promptRequests)
    assertEquals(1, activePollers)
    val state = controller.state.value
    assertTrue("il pannello deve essere aperto", state.visible)
    assertTrue("in attesa dell'approvazione", state.waiting)
    assertEquals("BDWP-HQPK", state.prompt?.userCode)
    assertFalse(state.isError)
    assertFalse("nessun nuovo codice da offrire mentre quello attuale è valido", state.canRetry)
  }

  @Test
  fun unSecondoStartNonAffiancaUnSecondoPolling() = runTest {
    var promptRequests = 0
    var activePollers = 0
    var cancelledPollers = 0
    val gate = CompletableDeferred<SimklAccountState>()
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptRequests++; promptOf(code = "CODE-$promptRequests") },
      awaitAuthorization = {
        activePollers++
        try {
          gate.await()
        } catch (cancellation: CancellationException) {
          cancelledPollers++
          throw cancellation
        } finally {
          activePollers--
        }
      }
    )

    controller.start()
    runCurrent()
    // Secondo tocco (o "Nuovo codice") mentre il primo polling è ancora in corso.
    controller.start()
    runCurrent()

    assertEquals("un codice per ogni richiesta", 2, promptRequests)
    assertEquals("un solo polling attivo", 1, activePollers)
    assertEquals("il ciclo precedente è stato fermato, non lasciato a girare", 1, cancelledPollers)
  }

  @Test
  fun ilSuccessoChiudeIlPannelloESincronizzaUnaVoltaSola() = runTest {
    var hookCalls = 0
    var hookAccount: SimklAccountState? = null
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptOf() },
      awaitAuthorization = { loggedIn },
      onAuthorized = { hookCalls++; hookAccount = it }
    )

    controller.start()
    runCurrent()

    val state = controller.state.value
    assertEquals(loggedIn, state.account)
    assertFalse("a login riuscito il pannello sparisce", state.visible)
    assertNull(state.prompt)
    assertFalse(state.isError)
    assertEquals("la sincronizzazione parte subito dopo il login", 1, hookCalls)
    assertEquals(loggedIn, hookAccount)
  }

  @Test
  fun unErroreDiSincronizzazioneNonTrasformailLoginInUnFallimento() = runTest {
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptOf() },
      awaitAuthorization = { loggedIn },
      onAuthorized = { throw IOException("catalogo non raggiungibile") }
    )

    controller.start()
    runCurrent()

    val state = controller.state.value
    assertEquals("il login resta riuscito", loggedIn, state.account)
    assertFalse("un errore di sincronizzazione non è un errore di login", state.isError)
    assertFalse(state.visible)
  }

  @Test
  fun erroreDiReteSullaRichiestaDelCodiceLasciaIlPannelloApertoConNuovoCodice() = runTest {
    var promptRequests = 0
    var pollers = 0
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptRequests++; throw IOException("timeout") },
      awaitAuthorization = { pollers++; loggedIn }
    )

    controller.start()
    runCurrent()

    val state = controller.state.value
    assertTrue("il pannello resta aperto per spiegare l'errore", state.visible)
    assertTrue(state.isError)
    assertTrue("si può chiedere un nuovo codice", state.canRetry)
    assertFalse(state.waiting)
    assertEquals(SIMKL_NETWORK_ERROR_MESSAGE, state.statusMessage)
    assertEquals(0, pollers)
    assertEquals(1, promptRequests)
  }

  @Test
  fun codiceScadutoOffreUnNuovoCodiceSenzaRichiederloDaSolo() = runTest {
    var promptRequests = 0
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptRequests++; promptOf() },
      awaitAuthorization = { throw SimklLoginExpiredException("Codice SIMKL scaduto. Richiedine uno nuovo.") }
    )

    controller.start()
    runCurrent()

    val state = controller.state.value
    assertTrue(state.isError)
    assertTrue(state.canRetry)
    assertEquals("Codice SIMKL scaduto. Richiedine uno nuovo.", state.statusMessage)
    assertNotNull("il codice scaduto resta a schermo finché non se ne chiede uno nuovo", state.prompt)
    assertEquals("nessuna richiesta automatica di un nuovo codice", 1, promptRequests)
    assertFalse(state.waiting)
  }

  @Test
  fun nuovoCodiceNonChiudeIlPannelloEChiedeUnCodiceFresco() = runTest {
    var promptRequests = 0
    var activePollers = 0
    // Il ciclo di polling è uno per codice: il primo riceve la scadenza, il secondo
    // (dopo "Nuovo codice") resta appeso così lo stato si può osservare.
    var pollGate = CompletableDeferred<SimklAccountState>()
    val secondPrompt = CompletableDeferred<SimklLoginPrompt>()
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = {
        promptRequests++
        if (promptRequests == 1) promptOf(code = "PRIMO")
        else secondPrompt.await()
      },
      awaitAuthorization = { activePollers++; try { pollGate.await() } finally { activePollers-- } }
    )

    controller.start()
    runCurrent()
    assertEquals("PRIMO", controller.state.value.prompt?.userCode)

    // Scadenza del primo codice.
    pollGate.completeExceptionally(SimklLoginExpiredException("Codice SIMKL scaduto. Richiedine uno nuovo."))
    runCurrent()
    assertTrue(controller.state.value.canRetry)

    // "Nuovo codice": la richiesta resta appesa a bella posta, per osservare lo stato
    // mentre il pannello deve restare aperto.
    pollGate = CompletableDeferred()
    controller.retry()
    runCurrent()
    assertTrue("il pannello non deve chiudersi", controller.state.value.visible)
    assertTrue("richiesta in corso", controller.state.value.waiting)
    assertEquals(2, promptRequests)
    assertFalse(
      "il pannello non mostra errori mentre sta chiedendo il nuovo codice",
      controller.state.value.isError
    )
    assertEquals(SIMKL_REQUESTING_CODE_MESSAGE, controller.state.value.statusMessage)

    secondPrompt.complete(promptOf(code = "SECONDO"))
    runCurrent()
    assertEquals("SECONDO", controller.state.value.prompt?.userCode)
    assertEquals(1, activePollers)
  }

  @Test
  fun annullaFermaIlPollingEChiudeIlPannello() = runTest {
    var activePollers = 0
    var cancelledPollers = 0
    val gate = CompletableDeferred<SimklAccountState>()
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptOf() },
      awaitAuthorization = {
        activePollers++
        try {
          gate.await()
        } catch (cancellation: CancellationException) {
          cancelledPollers++
          throw cancellation
        } finally {
          activePollers--
        }
      }
    )

    controller.start()
    runCurrent()
    controller.cancel()
    runCurrent()

    assertEquals(0, activePollers)
    assertEquals("il polling si è fermato con l'annullamento", 1, cancelledPollers)
    val state = controller.state.value
    assertFalse("pannello chiuso", state.visible)
    assertNull(state.prompt)
    assertFalse(state.waiting)
    assertNull(state.account)
  }

  @Test
  fun clientIdMancanteNonFaNascereAlcunaAttesaEMostraUnMessaggioPerLUtente() = runTest {
    var promptRequests = 0
    var pollers = 0
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = {
        promptRequests++
        throw SimklClientIdMissingException(
          "Client ID SIMKL non configurato in questa build: impostare SIMKL_CLIENT_ID in " +
            ".env, local.properties o nell'ambiente e ricompilare."
        )
      },
      awaitAuthorization = { pollers++; loggedIn }
    )

    controller.start()
    runCurrent()

    val state = controller.state.value
    assertTrue(state.isError)
    assertTrue(state.canRetry)
    assertEquals("nessun polling senza un codice", 0, pollers)
    assertEquals(1, promptRequests)
    assertEquals(SIMKL_NOT_AVAILABLE_MESSAGE, state.statusMessage)
    val message = state.statusMessage.orEmpty()
    assertFalse("all'utente non si chiede nulla di tecnico", message.contains("Client ID"))
    assertFalse("e non si nomina SIMKL_CLIENT_ID", message.contains("SIMKL_CLIENT_ID"))
    assertFalse("nemmeno un file di configurazione", message.contains(".env"))
    // La versione installata non è il problema: un aggiornamento riporterebbe lo stesso
    // messaggio, quindi non lo si promette.
    assertFalse("niente 'aggiorna l'app': non è la versione a mancare", message.contains("Aggiorna"))
  }

  @Test
  fun unClientRifiutatoDaSimklLasciaIlPannelloApertoEProponeUnNuovoCodice() = runTest {
    var promptRequests = 0
    var pollers = 0
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = {
        promptRequests++
        throw SimklClientRejectedException("SIMKL: il client_id configurato non è utilizzabile.")
      },
      awaitAuthorization = { pollers++; loggedIn }
    )

    controller.start()
    runCurrent()

    val state = controller.state.value
    assertTrue("il pannello resta aperto per spiegare il rifiuto", state.visible)
    assertTrue(state.isError)
    assertTrue("un rifiuto può essere temporaneo: si può riprovare", state.canRetry)
    assertFalse(state.waiting)
    assertEquals(0, pollers)
    assertEquals(1, promptRequests)
    assertEquals(SIMKL_CLIENT_REJECTED_MESSAGE, state.statusMessage)
  }

  @Test
  fun unaRispostaNonValidaNonVieneSpacciataPerUnErroreDiRete() = runTest {
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptOf() },
      awaitAuthorization = { throw SimklInvalidResponseException("Risposta SIMKL non interpretabile.") }
    )

    controller.start()
    runCurrent()

    val state = controller.state.value
    assertTrue(state.isError)
    assertTrue(state.canRetry)
    assertEquals(SIMKL_INVALID_RESPONSE_MESSAGE, state.statusMessage)
    // Il codice resta a schermo: la richiesta era riuscita, è il seguito ad aver fallito.
    assertNotNull(state.prompt)
  }

  @Test
  fun unGuastoNonClassificatoNonVieneSpacciatoPerUnErroreDiRete() = runTest {
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptOf() },
      awaitAuthorization = { throw IllegalStateException("guasto imprevisto") }
    )

    controller.start()
    runCurrent()

    val state = controller.state.value
    assertTrue(state.isError)
    assertEquals(SIMKL_GENERIC_ERROR_MESSAGE, state.statusMessage)
  }

  @Test
  fun iMessaggiDiErroreNonChiedonoConfigurazioniAllUtente() {
    assertEquals(
      SIMKL_NOT_AVAILABLE_MESSAGE,
      simklLoginErrorMessage(SimklClientIdMissingException("Configura SIMKL_CLIENT_ID"))
    )
    assertEquals(
      SIMKL_CLIENT_REJECTED_MESSAGE,
      simklLoginErrorMessage(SimklClientRejectedException("invalid_client / client_id_failed"))
    )
    // Configurazione non utilizzabile in generale: mai il testo tecnico dell'eccezione.
    assertEquals(
      SIMKL_NOT_AVAILABLE_MESSAGE,
      simklLoginErrorMessage(SimklClientConfigException("Configura SIMKL_CLIENT_ID"))
    )
    assertEquals(
      SIMKL_NETWORK_ERROR_MESSAGE,
      simklLoginErrorMessage(IOException("Failed to connect"))
    )
    assertEquals(
      SIMKL_INVALID_RESPONSE_MESSAGE,
      simklLoginErrorMessage(SimklInvalidResponseException("Risposta SIMKL priva del codice device."))
    )
    assertEquals(
      SIMKL_GENERIC_ERROR_MESSAGE,
      simklLoginErrorMessage(IllegalStateException("guasto imprevisto"))
    )
    assertEquals(
      "Codice SIMKL scaduto. Richiedine uno nuovo.",
      simklLoginErrorMessage(SimklLoginExpiredException("Codice SIMKL scaduto. Richiedine uno nuovo."))
    )
    assertEquals(
      "Autorizzazione SIMKL rifiutata.",
      simklLoginErrorMessage(SimklAuthException("Autorizzazione SIMKL rifiutata."))
    )
    // Nessuno dei messaggi dell'utente contiene il gergo della configurazione.
    listOf(
      SIMKL_NOT_AVAILABLE_MESSAGE,
      SIMKL_CLIENT_REJECTED_MESSAGE,
      SIMKL_NETWORK_ERROR_MESSAGE,
      SIMKL_INVALID_RESPONSE_MESSAGE,
      SIMKL_GENERIC_ERROR_MESSAGE
    ).forEach { message ->
      assertFalse(message, message.contains("Client ID"))
      assertFalse(message, message.contains("SIMKL_CLIENT_ID"))
      assertFalse(message, message.contains("Aggiorna"))
    }
  }

  @Test
  fun clearResultNonLasciaTracceDelLoginRiuscito() = runTest {
    val controller = controller(
      scope = backgroundScope,
      requestPrompt = { promptOf() },
      awaitAuthorization = { loggedIn }
    )

    controller.start()
    runCurrent()
    assertNotNull(controller.state.value.account)

    controller.clearResult()

    assertNull(controller.state.value.account)
    assertFalse(controller.state.value.visible)
  }
}
