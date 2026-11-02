package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.stremio.StremioAddonRepository
import com.example.ui.components.TvFocusableBox
import com.example.ui.viewmodel.StreamNovaViewModel
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressione FOCUS della sezione Addon: al termine dell'installazione il focus deve
 * restare nella schermata, non finire sulla sidebar.
 *
 * MECCANISMO (Compose 1.7.2): [AddonsScreen] chiude il dialog di installazione
 * (`TextInputDialog`) smontandolo. Il dialog è un overlay nella STESSA finestra (non una
 * seconda Window), quindi il nodo focalizzato — il campo di testo — esce dalla
 * composizione e Compose azzera il focus. Senza una richiesta esplicita il ripiego
 * geometrico atterra sul primo focusabile della finestra: le voci della sidebar, che
 * restano focusabili anche quando è chiusa (serve ad aprirla con LEFT). La schermata
 * Addon non aveva alcun ripristino, quindi il focus la abbandonava a ogni installazione.
 *
 * Il test monta la schermata REALE accanto a una sidebar focalizzabile composta dopo il
 * contenuto (stessa disposizione della MainActivity) e usa il ViewModel e il repository
 * reali con un manifest servito in locale: nessuna simulazione della catena di install.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class AddonsScreenInstallFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private lateinit var server: HttpServer
  private lateinit var viewModel: StreamNovaViewModel
  private lateinit var context: Context

  private val addButtonLabel: String get() = context.getString(R.string.addons_add_button)
  private val switchActiveLabel: String get() = context.getString(R.string.addons_switch_active)

  private companion object {
    const val SIDEBAR_TAG_PREFIX = "sidebar_"
    const val SIDEBAR_ITEMS = 4
    const val ADDON_TIMEOUT_MS = 20_000L
    const val ADDON_NAME = "Focus Regression Addon"
    val SIDEBAR_WIDTH = 60.dp

    val MANIFEST = """
      {
        "id": "org.streamnova.focus.regression",
        "version": "1.0.0",
        "name": "Focus Regression Addon",
        "description": "Addon di test per la regressione del focus dopo l'installazione",
        "resources": ["catalog"],
        "types": ["movie"],
        "catalogs": [{"type": "movie", "id": "focus_regression"}]
      }
    """.trimIndent()
  }

  // ── Fixture ────────────────────────────────────────────────────────────────

  private val manifestUrl: String get() = "http://127.0.0.1:${server.address.port}/manifest.json"

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    removeAllInstalledAddons()

    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange -> handle(exchange) }
    server.start()
  }

  @After
  fun tearDown() {
    removeAllInstalledAddons()
    server.stop(0)
  }

  private fun handle(exchange: HttpExchange) {
    val body = when (exchange.requestURI.rawPath) {
      "/manifest.json" -> MANIFEST
      else -> null
    }
    if (body == null) {
      exchange.sendResponseHeaders(404, -1)
    } else {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    exchange.close()
  }

  private fun removeAllInstalledAddons() {
    StremioAddonRepository.getInstalledAddons().forEach {
      StremioAddonRepository.removeAddon(it.installId)
    }
  }

  // ── Harness (stessa disposizione della MainActivity) ───────────────────────

  @Composable
  private fun App() {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
      AddonsScreen(
        viewModel = viewModel,
        modifier = Modifier
          .fillMaxSize()
          .padding(start = SIDEBAR_WIDTH)
      )
      SidebarStub()
    }
  }

  @Composable
  private fun BoxScope.SidebarStub() {
    Box(
      modifier = Modifier
        .align(Alignment.CenterStart)
        .fillMaxHeight()
        .width(SIDEBAR_WIDTH)
        .background(Color(0xFF101820))
    ) {
      Column(
        modifier = Modifier
          .fillMaxHeight()
          .padding(vertical = 16.dp, horizontal = 4.dp)
      ) {
        Spacer(modifier = Modifier.height(48.dp))
        Column(
          modifier = Modifier.fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          repeat(SIDEBAR_ITEMS) { index ->
            TvFocusableBox(
              modifier = Modifier
                .height(48.dp)
                .width(48.dp)
                .testTag("$SIDEBAR_TAG_PREFIX$index")
            ) {}
          }
        }
      }
    }
  }

  private fun render() {
    viewModel = StreamNovaViewModel()
    rule.setContent { App() }
    rule.waitForIdle()
  }

  // ── Helper di focus ────────────────────────────────────────────────────────

  private fun focusableNodes(): List<SemanticsNode> =
    rule.onAllNodes(
      SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus),
      useUnmergedTree = true
    ).fetchSemanticsNodes()

  private fun allTextsOf(node: SemanticsNode): List<String> {
    val own = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
    return own + node.children.flatMap { allTextsOf(it) }
  }

  private fun focusedNodes(): List<SemanticsNode> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()

  private fun focusedTexts(): List<String> = focusedNodes().flatMap { allTextsOf(it) }

  private fun testTagOf(node: SemanticsNode): String? =
    generateSequence(node) { it.parent }
      .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
      .firstOrNull()

  private fun isSidebarFocused(): Boolean =
    focusedNodes().any { testTagOf(it)?.startsWith(SIDEBAR_TAG_PREFIX) == true }

  private fun focusAddButton() {
    val index = focusableNodes().indexOfFirst { node ->
      allTextsOf(node).any { it.contains(addButtonLabel) }
    }
    check(index >= 0) { "Pulsante '$addButtonLabel' non trovato" }
    rule.onAllNodes(
      SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus),
      useUnmergedTree = true
    )[index].requestFocus()
    rule.waitForIdle()
    assertFalse("Il focus iniziale deve partire dal contenuto Addon", isSidebarFocused())
  }

  private fun pressFocusedKey(key: Key) {
    rule.onNode(isFocused(), useUnmergedTree = true)
      .performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun openInstallDialog() {
    pressFocusedKey(Key.DirectionCenter)
    assertTrue(
      "Il dialog deve essere aperto con il campo di testo focalizzato",
      focusedTexts().any { it.contains("manifest.json") }
    )
  }

  private fun submitUrl(url: String) {
    rule.onNode(isFocused(), useUnmergedTree = true)
      .performTextInput(url)
    rule.waitForIdle()
    pressFocusedKey(Key.Enter)
  }

  private fun awaitMessage(predicate: (String) -> Boolean) {
    rule.waitUntil(timeoutMillis = ADDON_TIMEOUT_MS) {
      viewModel.addonMessage.value?.let(predicate) == true
    }
    rule.waitForIdle()
  }

  /** Il focus è sul pulsante che ha avviato l'installazione e MAI sulla sidebar. */
  private fun assertFocusStaysInAddons(stage: String) {
    val texts = focusedTexts()
    assertFalse(
      "$stage: il focus è finito sulla sidebar (testi=$texts)",
      isSidebarFocused()
    )
    assertTrue(
      "$stage: il focus deve tornare su '$addButtonLabel', trovato $texts",
      texts.any { it.contains(addButtonLabel) }
    )
  }

  // ── Test ───────────────────────────────────────────────────────────────────

  @Test
  fun installazioneDaDialogMantieneIlFocusNellaSezioneAddon() {
    render()
    focusAddButton()
    openInstallDialog()

    submitUrl(manifestUrl)
    awaitMessage { it.contains("installato", ignoreCase = true) }

    assertTrue(
      "L'addon deve risultare installato",
      StremioAddonRepository.getInstalledAddons().any { it.manifest.name == ADDON_NAME }
    )
    assertFocusStaysInAddons("dopo l'installazione")

    // Il D-pad continua a funzionare: DOWN dall'add button raggiunge lo switch del
    // nuovo addon (primo controllo focusabile della card appena inserita).
    pressFocusedKey(Key.DirectionDown)
    assertTrue(
      "DOWN dopo l'installazione deve raggiungere l'addon installato, trovato ${focusedTexts()}",
      focusedTexts().any { it == switchActiveLabel }
    )
    assertFalse("La navigazione D-pad non deve portare sulla sidebar", isSidebarFocused())
  }

  @Test
  fun annullareIlDialogMantieneIlFocusNellaSezioneAddon() {
    render()
    focusAddButton()
    openInstallDialog()

    pressFocusedKey(Key.Back)

    assertFocusStaysInAddons("dopo l'annullamento del dialog")
    assertTrue(
      "L'annullamento non deve installare nulla",
      StremioAddonRepository.getInstalledAddons().isEmpty()
    )
  }

  @Test
  fun reinstallareUnAddonGiaPresenteMantieneIlFocus() {
    // Addon già installato: la reinstallazione dello stesso URL aggiorna l'installazione
    // esistente (stesso installId/baseUrl) senza duplicarla.
    runBlocking { StremioAddonRepository.installAddon(manifestUrl) }
    render()
    focusAddButton()
    openInstallDialog()

    submitUrl(manifestUrl)
    awaitMessage { it.contains("installato", ignoreCase = true) }

    assertEquals(
      "La reinstallazione dello stesso URL non deve duplicare l'addon",
      1,
      StremioAddonRepository.getInstalledAddons().count { it.manifest.name == ADDON_NAME }
    )
    assertFocusStaysInAddons("dopo la reinstallazione")
  }

  @Test
  fun installazioneFallitaMantieneIlFocusNellaSezioneAddon() {
    render()
    focusAddButton()
    openInstallDialog()

    submitUrl("http://127.0.0.1:${server.address.port}/missing/manifest.json")
    awaitMessage { it.contains("fallita", ignoreCase = true) }

    assertTrue(
      "L'addon non deve essere installato in caso di errore",
      StremioAddonRepository.getInstalledAddons().isEmpty()
    )
    assertFocusStaysInAddons("dopo l'installazione fallita")
  }
}
