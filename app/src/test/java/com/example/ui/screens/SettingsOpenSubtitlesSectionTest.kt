package com.example.ui.screens

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.ApiKeyCipher
import com.example.data.prefs.ApiKeyCipherFormat
import com.example.data.prefs.AppSettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La sezione OpenSubtitles nelle Impostazioni → Sottotitoli deve essere visibile e
 * navigabile con la LazyColumn, mascherare la chiave salvata (mai in chiaro) e
 * spiegare che, senza chiave, resta attiva la sorgente alternativa (addon Stremio).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [34],
  // Lingua esplicita (it): le asserzioni verificano i testi italiani.
  qualifiers = "it-w1280dp-h720dp-tvdpi"
)
class SettingsOpenSubtitlesSectionTest {

  @get:Rule
  val rule = createComposeRule()

  private val savedKey = "abcd1234EFGH5678"

  /** Cifratura finta: il Keystore Android non esiste su Robolectric. */
  private class TrivialCipher : ApiKeyCipher {
    override fun encrypt(plain: String): String = ApiKeyCipherFormat.MARKER + plain.reversed()
    override fun decrypt(stored: String): String? = if (ApiKeyCipherFormat.isEncrypted(stored)) {
      ApiKeyCipherFormat.payload(stored).reversed()
    } else {
      null
    }
  }

  @Before
  fun setUp() {
    // Nessuna chiave ereditata da altri test Robolectric dello stesso sandbox.
    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(ApplicationProvider.getApplicationContext(), TrivialCipher())
    AppSettingsRepository.setOpenSubtitlesApiKey(null)
  }

  @After
  fun tearDown() {
    AppSettingsRepository.setOpenSubtitlesApiKey(null)
  }

  /** La schermata è una LazyColumn: la sezione va portata in vista prima di verificarla. */
  private fun scrollToOpenSubtitles() {
    rule.onNode(hasScrollToNodeAction())
      .performScrollToNode(hasText("Chiave API OpenSubtitles"))
    rule.waitForIdle()
  }

  @Test
  fun laSezioneOpenSubtitlesVisibileSenzaChiave() {
    rule.setContent { SettingsScreen(viewModel = null) }
    scrollToOpenSubtitles()

    rule.onNodeWithText("OPENSUBTITLES").assertExists()
    rule.onNodeWithText("Chiave API OpenSubtitles").assertExists()
    rule.onNodeWithText("Verifica connessione").assertExists()
    // "Non configurata" compare sia sulla riga chiave sia sulla riga verifica.
    rule.onAllNodesWithText("Non configurata")[0].assertExists()
    rule.onNodeWithText(MISSING_NOTE).assertExists()
  }

  @Test
  fun laChiaveSalvataAppareMascherataEMaiInChiaro() {
    AppSettingsRepository.setOpenSubtitlesApiKey(savedKey)
    rule.setContent { SettingsScreen(viewModel = null) }
    scrollToOpenSubtitles()

    // Maschera attesa: primi 4 + pallini + ultimi 4 (maskApiKey).
    rule.onNodeWithText("abcd••••••••5678").assertExists()

    // La chiave in chiaro NON deve apparire da nessuna parte nella schermata.
    rule.onNode(hasText(savedKey, substring = true)).assertDoesNotExist()
  }

  @Test
  fun laNotaSenzaChiaveScompareQuandoLaChiaveEsiste() {
    AppSettingsRepository.setOpenSubtitlesApiKey(savedKey)
    rule.setContent { SettingsScreen(viewModel = null) }
    scrollToOpenSubtitles()

    rule.onNodeWithText(MISSING_NOTE).assertDoesNotExist()
    "abcd••••••••5678".let { rule.onNodeWithText(it).assertExists() }
  }

  private companion object {
    /** Testo della nota "manca la chiave": stesso rendering di stringResource (locale it). */
    val MISSING_NOTE =
      "Senza chiave API la ricerca ufficiale OpenSubtitles non è disponibile: " +
        "i sottotitoli dell'addon Stremio continuano a funzionare normalmente."
  }
}
