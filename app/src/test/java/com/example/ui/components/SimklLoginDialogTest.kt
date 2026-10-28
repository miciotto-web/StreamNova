package com.example.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.example.data.remote.simkl.SimklAuthFlow
import com.example.data.remote.simkl.SimklLoginPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pannello di login SIMKL su TV: wordmark, istruzione, QR, codice, indirizzo di verifica,
 * conto alla rovescia, stato dell'attesa e pulsanti raggiungibili col D-pad.
 *
 * Il tempo usato dal pannello è quello del clock di composizione
 * ([androidx.compose.ui.test.MainTestClock]), così il conto alla rovescia è deterministico
 * e il ciclo che lo aggiorna termina davvero (nessuna attesa infinita nel test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class SimklLoginDialogTest {

  @get:Rule
  val rule = createComposeRule()

  private fun prompt(
    flow: SimklAuthFlow = SimklAuthFlow.V2_DEVICE,
    userCode: String = "BDWP-HQPK",
    qrPayload: String = "https://simkl.com/pin?user_code=BDWP-HQPK",
    expiresInMs: Long = 95_000L
  ) = SimklLoginPrompt(
    flow = flow,
    userCode = userCode,
    verificationUri = "https://simkl.com/pin",
    qrPayload = qrPayload,
    deviceCode = "dev-code",
    expiresAtEpochMs = expiresInMs,
    intervalSeconds = 5L
  )

  private fun show(
    state: SimklLoginUiState,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {}
  ) {
    rule.setContent {
      SimklLoginDialog(
        state = state,
        onRetry = onRetry,
        onDismiss = onDismiss,
        nowMs = { rule.mainClock.currentTime }
      )
    }
  }

  /**
   * Il QR è codificato su un dispatcher di background ([QrCodeImage] usa `produceState` su
   * `Dispatchers.Default`): senza attenderlo il nodo può non esistere ancora e il pannello
   * sarebbe misurato senza la sua area.
   */
  private fun awaitQrCode() {
    rule.waitUntil(timeoutMillis = 5_000L) {
      rule.onAllNodesWithContentDescription("QR di accesso SIMKL", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun pressOnFocusedNode(key: Key) {
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(key) }
  }

  /**
   * Il focus si verifica sul nodo del pulsante: lo sfondo del pannello unisce i discendenti
   * in un unico nodo semantico, quindi il testo non è leggibile dal nodo che possiede il focus.
   */
  private fun assertHasFocus(tag: String) {
    rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsFocused()
  }

  @Test
  fun mostraWordmarkIstruzioneQrCodiceUrlContoAllaRovesciaEAttesa() {
    show(SimklLoginUiState(visible = true, prompt = prompt(), waiting = true))
    awaitQrCode()

    // Composizione del riferimento: logo, istruzione, QR, codice, URL, scadenza, attesa, Annulla.
    rule.onNodeWithText("SIMKL").assertIsDisplayed()
    rule.onNodeWithText(
      "Inquadra il QR con il telefono: la pagina di verifica Simkl si apre con il codice già " +
        "inserito e ti resta solo da confermare l'accesso."
    ).assertIsDisplayed()
    try {
      rule.onNodeWithContentDescription("QR di accesso SIMKL").assertIsDisplayed()
    } catch (e: AssertionError) {
      val nodes = rule.onAllNodesWithContentDescription("QR di accesso SIMKL").fetchSemanticsNodes()
      val un = rule.onAllNodesWithContentDescription("QR di accesso SIMKL", useUnmergedTree = true)
        .fetchSemanticsNodes()
      throw AssertionError(
        "DIAG merged=${nodes.map { "${it.id}@${it.boundsInWindow} placed=${it.layoutInfo.isPlaced}" }} " +
          "unmerged=${un.map { "${it.id}@${it.boundsInWindow} placed=${it.layoutInfo.isPlaced}" }}",
        e
      )
    }
    rule.onNodeWithText("BDWP-HQPK").assertIsDisplayed()
    rule.onNodeWithText("https://simkl.com/pin").assertIsDisplayed()
    rule.onNodeWithText("Il codice scade in 1m 35s").assertIsDisplayed()
    rule.onNodeWithText("In attesa dell'approvazione di Simkl…").assertIsDisplayed()
    // Il pulsante resta dentro la finestra: il pannello non deve sforare i 720 dp di una TV.
    rule.onNodeWithText("Annulla").assertIsDisplayed()
    // Senza errore non si offre un nuovo codice: quello attuale è ancora valido.
    rule.onNodeWithText("Nuovo codice").assertDoesNotExist()
  }

  @Test
  fun inFlussoV1ChiedeDiInserireIlCodiceSullaPaginaDiVerifica() {
    show(
      SimklLoginUiState(
        visible = true,
        prompt = prompt(
          flow = SimklAuthFlow.V1_PIN,
          userCode = "3552F",
          qrPayload = "https://simkl.com/pin"
        ),
        waiting = true
      )
    )

    awaitQrCode()
    // Il testo del riferimento: il QR apre la pagina, il codice si digita.
    rule.onNodeWithText("Apri la pagina di verifica Simkl e inserisci questo codice.").assertIsDisplayed()
    rule.onNodeWithText("3552F").assertIsDisplayed()
    rule.onNodeWithContentDescription("QR di accesso SIMKL").assertIsDisplayed()
  }

  @Test
  fun aCodiceScadutoOffreUnNuovoCodiceSenzaChiudereIlPannello() {
    show(
      SimklLoginUiState(
        visible = true,
        prompt = prompt(expiresInMs = -1_000L),
        waiting = false,
        statusMessage = "Codice SIMKL scaduto. Richiedine uno nuovo.",
        isError = true,
        canRetry = true
      )
    )

    rule.onNodeWithText("Il codice è scaduto.").assertIsDisplayed()
    rule.onNodeWithText("Codice SIMKL scaduto. Richiedine uno nuovo.").assertIsDisplayed()
    rule.onNodeWithText("Nuovo codice").assertIsDisplayed()
    rule.onNodeWithText("Annulla").assertIsDisplayed()
    // Il codice scaduto resta leggibile (a schermo) finché non se ne chiede uno nuovo.
    rule.onNodeWithText("BDWP-HQPK").assertIsDisplayed()
  }

  @Test
  fun senzaPromptMostraIlMotivoSenzaQr() {
    // Configurazione assente nella build (o client rifiutato): nessun codice richiesto,
    // quindi nessun QR da mostrare. Il pannello resta aperto con il motivo e il pulsante
    // per ritentare.
    show(
      SimklLoginUiState(
        visible = true,
        prompt = null,
        waiting = false,
        statusMessage = SIMKL_NOT_AVAILABLE_MESSAGE,
        isError = true,
        canRetry = true
      )
    )

    rule.onNodeWithText(SIMKL_NOT_AVAILABLE_MESSAGE).assertIsDisplayed()
    rule.onNodeWithText("Nessun codice").assertIsDisplayed()
    rule.onNodeWithText("Nuovo codice").assertIsDisplayed()
    rule.onNodeWithContentDescription("QR di accesso SIMKL").assertDoesNotExist()
  }

  @Test
  fun mentreRichiedeIlCodiceMostraLaRotellaSenzaQr() {
    show(SimklLoginUiState(visible = true, prompt = null, waiting = true, statusMessage = SIMKL_REQUESTING_CODE_MESSAGE))

    rule.onNodeWithText(SIMKL_REQUESTING_CODE_MESSAGE).assertIsDisplayed()
    rule.onNodeWithContentDescription("QR di accesso SIMKL").assertDoesNotExist()
    rule.onNodeWithText("Annulla").assertIsDisplayed()
    // Il pannello non offre un nuovo codice mentre ne sta già chiedendo uno.
    rule.onNodeWithText("Nuovo codice").assertDoesNotExist()
  }

  @Test
  fun ilTastoCentraleSuAnnullaDismetteIlPannello() {
    var dismissed = 0
    show(SimklLoginUiState(visible = true, prompt = prompt(), waiting = true), onDismiss = { dismissed++ })

    // Con il codice valido l'unica azione è "Annulla", ed è quella che ha il focus.
    assertHasFocus(SIMKL_LOGIN_ANNULLA_TAG)
    pressOnFocusedNode(Key.DirectionCenter)

    assertEquals("il pulsante Annulla deve dismettere il pannello", 1, dismissed)
  }

  @Test
  fun ilDpadRaggiungeINuoviCodiciEIlTastoCentraleLiAttiva() {
    var retried = 0
    var dismissed = 0
    show(
      SimklLoginUiState(
        visible = true,
        prompt = prompt(expiresInMs = -1_000L),
        waiting = false,
        statusMessage = "Codice SIMKL scaduto. Richiedine uno nuovo.",
        isError = true,
        canRetry = true
      ),
      onRetry = { retried++ },
      onDismiss = { dismissed++ }
    )

    // Focus iniziale sul pulsante utile: "Nuovo codice".
    assertHasFocus(SIMKL_LOGIN_RETRY_TAG)
    // A sinistra c'è "Annulla": il tasto centrale lo attiva (il pannello si chiude)…
    pressOnFocusedNode(Key.DirectionLeft)
    assertHasFocus(SIMKL_LOGIN_ANNULLA_TAG)
    pressOnFocusedNode(Key.DirectionCenter)

    assertEquals("il tasto centrale su Annulla chiude il pannello", 1, dismissed)
    assertEquals("e non deve attivare anche il nuovo codice", 0, retried)
  }

  @Test
  fun ilTastoCentraleSuNuovoCodiceNonChiudeIlPannello() {
    var retried = 0
    var dismissed = 0
    show(
      SimklLoginUiState(
        visible = true,
        prompt = null,
        waiting = false,
        statusMessage = SIMKL_NOT_AVAILABLE_MESSAGE,
        isError = true,
        canRetry = true
      ),
      onRetry = { retried++ },
      onDismiss = { dismissed++ }
    )

    // Il focus di partenza è sul pulsante utile: l'OK riavvia il flusso.
    assertHasFocus(SIMKL_LOGIN_RETRY_TAG)
    pressOnFocusedNode(Key.DirectionCenter)

    assertEquals("con il focus di partenza su Nuovo codice, l'OK riavvia il flusso", 1, retried)
    assertEquals("e non deve chiudere il pannello", 0, dismissed)
  }

  @Test
  fun aCodiceValidoNonSiEsceDalPannelloConIlDpad() {
    var dismissed = 0
    show(
      SimklLoginUiState(visible = true, prompt = prompt(), waiting = true),
      onDismiss = { dismissed++ }
    )

    assertHasFocus(SIMKL_LOGIN_ANNULLA_TAG)
    // Nessuna direzione porta fuori dal pannello (sopra c'è solo il QR).
    pressOnFocusedNode(Key.DirectionUp)
    pressOnFocusedNode(Key.DirectionLeft)
    assertEquals("il D-pad non deve chiudere il pannello da solo", 0, dismissed)
    assertHasFocus(SIMKL_LOGIN_ANNULLA_TAG)
  }

  @Test
  fun ilPannelloEntraInteramenteNellaSchermataDiUnaTv() {
    // Geometria di riferimento: la TV 1080p offre 960x540 dp, quindi il pannello non può
    // essere alto quanto un mockup da telefono. Wordmark e pulsanti devono restare dentro.
    show(
      SimklLoginUiState(
        visible = true,
        prompt = prompt(),
        waiting = true,
        statusMessage = null
      )
    )

    awaitQrCode()
    val root = rule.onRoot().getUnclippedBoundsInRoot()
    val wordmark = rule.onNodeWithText("SIMKL", useUnmergedTree = true).getUnclippedBoundsInRoot()
    val annulla = rule.onNodeWithTag(SIMKL_LOGIN_ANNULLA_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()

    assertTrue("il wordmark non deve uscire in alto: $wordmark vs $root", wordmark.top >= root.top)
    assertTrue("i pulsanti non devono uscire in basso: $annulla vs $root", annulla.bottom <= root.bottom)
  }

  @Test
  fun contoAllaRovesciaTestualeComeNelRiferimento() {
    assertEquals("Il codice è scaduto.", simklCountdownLabel(0L))
    assertEquals("Il codice è scaduto.", simklCountdownLabel(-5L))
    assertEquals("Il codice scade in 45s", simklCountdownLabel(45L))
    assertEquals("Il codice scade in 1m 0s", simklCountdownLabel(60L))
    assertEquals("Il codice scade in 14m 41s", simklCountdownLabel(881L))
  }

  @Test
  fun listruzioneDichiaraIlCodiceGiaInseritoSoloSeIlQrLoContiene() {
    // V2 con verification_uri_complete: il QR precompila la pagina.
    assertEquals(
      "Inquadra il QR con il telefono: la pagina di verifica Simkl si apre con il codice già " +
        "inserito e ti resta solo da confermare l'accesso.",
      simklLoginInstruction(prompt())
    )
    // V1 PIN: nessun URL precompilato documentato, quindi il QR apre la sola pagina.
    assertEquals(
      "Apri la pagina di verifica Simkl e inserisci questo codice.",
      simklLoginInstruction(
        prompt(flow = SimklAuthFlow.V1_PIN, userCode = "3552F", qrPayload = "https://simkl.com/pin")
      )
    )
    // V2 senza il campo completo: non si può dichiarare che il codice sia già inserito.
    assertEquals(
      "Apri la pagina di verifica Simkl e inserisci questo codice.",
      simklLoginInstruction(prompt(qrPayload = "https://simkl.com/pin"))
    )
  }
}
