package com.example.ui.viewmodel

import com.example.data.opensubtitles.OpenSubtitlesConnectionCheck
import com.example.data.opensubtitles.OpenSubtitlesVerifyError
import com.example.data.prefs.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Collegamento Impostazioni → client OpenSubtitles già esistente:
 * salvar la chiave deve aggiornare immediatamente il flusso letto dal client
 * (senza ricreare nulla) e la verifica della connessione deve pubblicare uno
 * stato tipizzato, senza mai esporre la chiave nei dati di stato.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenSubtitlesSettingsViewModelTest {

  private val testDispatcher = StandardTestDispatcher()
  private var lastCheckedKey: String? = null
  private var nextResult: OpenSubtitlesConnectionCheck =
    OpenSubtitlesConnectionCheck.Valid

  private fun fakeVerifier() = object : com.example.data.opensubtitles.OpenSubtitlesConnectionVerifier() {
    override suspend fun check(currentApiKey: String?): OpenSubtitlesConnectionCheck {
      lastCheckedKey = currentApiKey
      return nextResult
    }
  }

  private lateinit var viewModel: StreamNovaViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    AppSettingsRepository.setOpenSubtitlesApiKey(null)
    lastCheckedKey = null
    nextResult = OpenSubtitlesConnectionCheck.Valid
    viewModel = StreamNovaViewModel(
      ioDispatcher = testDispatcher,
      openSubtitlesVerifier = fakeVerifier()
    )
  }

  @After
  fun tearDown() {
    AppSettingsRepository.setOpenSubtitlesApiKey(null)
    Dispatchers.resetMain()
  }

  @Test
  fun salvataggioAggiornaIlFlussoConIlValoreNormalizzato() = runTest(testDispatcher) {
    viewModel.setOpenSubtitlesApiKey("  my-os-key  ")
    assertEquals("my-os-key", viewModel.openSubtitlesApiKey.value)

    viewModel.setOpenSubtitlesApiKey("   ")
    assertEquals(null, viewModel.openSubtitlesApiKey.value)
  }

  @Test
  fun salvataggioResettaLoStatoDiVerifica() = runTest(testDispatcher) {
    viewModel.verifyOpenSubtitlesConnection()
    advanceUntilIdle()
    assertEquals(OpenSubtitlesConnectionState.NotConfigured, viewModel.openSubtitlesConnectionState.value)

    viewModel.setOpenSubtitlesApiKey("another-key")
    assertEquals(
      "Una nuova chiave invalida l'esito precedente",
      OpenSubtitlesConnectionState.Unknown,
      viewModel.openSubtitlesConnectionState.value
    )
  }

  @Test
  fun senzaChiaveLaVerificaRestaDisabilitataSenzaChiamate() = runTest(testDispatcher) {
    viewModel.verifyOpenSubtitlesConnection()
    advanceUntilIdle()

    assertEquals(
      OpenSubtitlesConnectionState.NotConfigured,
      viewModel.openSubtitlesConnectionState.value
    )
    assertEquals("Nessuna richiesta senza chiave", null, lastCheckedKey)
  }

  @Test
  fun chiaveValidaPubblicaConnectedEPassaLaChiaveAlClient() = runTest(testDispatcher) {
    viewModel.setOpenSubtitlesApiKey("live-key")
    viewModel.verifyOpenSubtitlesConnection()
    advanceUntilIdle()

    assertEquals(
      OpenSubtitlesConnectionState.Connected,
      viewModel.openSubtitlesConnectionState.value
    )
    assertEquals(
      "La verifica usa la chiave corrente (quella usata dal client)",
      "live-key",
      lastCheckedKey
    )
  }

  @Test
  fun chiaveRifiutataPubblicaErroreTipizzato() = runTest(testDispatcher) {
    nextResult = OpenSubtitlesConnectionCheck.Failed(OpenSubtitlesVerifyError.INVALID_KEY)
    viewModel.setOpenSubtitlesApiKey("bad-key")
    viewModel.verifyOpenSubtitlesConnection()
    advanceUntilIdle()

    val state = viewModel.openSubtitlesConnectionState.value
    assertTrue("Stato atteso Error(INVALID_KEY), trovato $state", state is OpenSubtitlesConnectionState.Error)
    assertEquals(OpenSubtitlesVerifyError.INVALID_KEY, (state as OpenSubtitlesConnectionState.Error).error)
  }

  @Test
  fun erroreDiRetePubblicaErroreTipizzato() = runTest(testDispatcher) {
    nextResult = OpenSubtitlesConnectionCheck.Failed(OpenSubtitlesVerifyError.NETWORK)
    viewModel.setOpenSubtitlesApiKey("key")
    viewModel.verifyOpenSubtitlesConnection()
    advanceUntilIdle()

    val state = viewModel.openSubtitlesConnectionState.value
    assertTrue("Stato atteso Error(NETWORK), trovato $state", state is OpenSubtitlesConnectionState.Error)
    assertEquals(OpenSubtitlesVerifyError.NETWORK, (state as OpenSubtitlesConnectionState.Error).error)
  }

  @Test
  fun loStatoDiErroreNonEsponeMaiLaChiave() = runTest(testDispatcher) {
    nextResult = OpenSubtitlesConnectionCheck.Failed(OpenSubtitlesVerifyError.INVALID_KEY)
    viewModel.setOpenSubtitlesApiKey("secret-xyz")
    viewModel.verifyOpenSubtitlesConnection()
    advanceUntilIdle()

    // Lo stato pubblicato è solo la tipologia di errore: nessun dato sensibile.
    val stateDescription = viewModel.openSubtitlesConnectionState.value.toString()
    assertTrue(
      "Lo stato NON deve contenere la chiave",
      !stateDescription.contains("secret-xyz")
    )
  }
}
