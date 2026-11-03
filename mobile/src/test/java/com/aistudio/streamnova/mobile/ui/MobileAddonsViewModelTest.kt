package com.aistudio.streamnova.mobile.ui

import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.StremioManifest
import com.example.data.stremio.provider.BindingConfidence
import com.example.data.stremio.provider.BindingSource
import com.example.data.stremio.provider.CatalogKey
import com.example.data.stremio.provider.ProviderBinding
import com.example.data.stremio.provider.UserProviderBindingStore
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test JVM del ViewModel Add-on con gateway fake: nessuna rete, nessun Android.
 *
 * Il fake è la sola sorgente di addon/binding: il ViewModel resta l'unità
 * verificata (guardie, esiti tipizzati, chiavi di gestione `installId`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MobileAddonsViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private class FakeGateway : MobileAddonGateway {
    val addonsFlow = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val bindingsFlow = MutableStateFlow("[]")

    var installResult: (String) -> InstalledAddon = {
      throw IllegalStateException("install non configurato nel fake")
    }
    val installCalls = mutableListOf<String>()
    val removedIds = mutableListOf<String>()
    val enabledChanges = mutableListOf<Pair<String, Boolean>>()
    val persistedBindings = mutableListOf<List<ProviderBinding>>()
    var existing: (CatalogKey) -> ProviderBinding? = { null }

    override val addons: StateFlow<List<InstalledAddon>> = addonsFlow
    override val userBindingsJson: StateFlow<String> = bindingsFlow

    override suspend fun install(rawUrl: String): InstalledAddon {
      installCalls += rawUrl
      return installResult(rawUrl)
    }

    override fun remove(installId: String) {
      removedIds += installId
    }

    override fun setEnabled(installId: String, enabled: Boolean) {
      enabledChanges += installId to enabled
    }

    override fun existingBinding(key: CatalogKey): ProviderBinding? = existing(key)

    override suspend fun persistUserBindings(bindings: List<ProviderBinding>) {
      persistedBindings += bindings
    }
  }

  /** Addon con manifest id CONDIVISO e installId diverso: la chiave di gestione. */
  private fun addon(
    baseUrl: String = "http://addon.example/conf",
    name: String = "Local Test Addon",
    version: String = "1.0.0"
  ) = InstalledAddon(
    baseUrl = baseUrl,
    manifest = StremioManifest(id = "shared.addon.id", name = name, version = version)
  )

  @Test
  fun lInstallRiuscitoPubblicaIlNomeRealeDelManifest() = runTest(dispatcher) {
    val gateway = FakeGateway()
    gateway.installResult = { addon(name = "Local Test Addon") }
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.install("http://addon.example/conf/manifest.json")
    advanceUntilIdle()

    val state = viewModel.state.value
    assertEquals(listOf("http://addon.example/conf/manifest.json"), gateway.installCalls)
    assertEquals(MobileAddonFeedback.Installed("Local Test Addon"), state.feedback)
    assertFalse(state.isInstalling)
  }

  @Test
  fun unUrlVuotoNonChiamaIlGatewayEPubblicaLErroreReale() = runTest(dispatcher) {
    val gateway = FakeGateway()
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.install("   ")
    advanceUntilIdle()

    assertTrue("Nessuna chiamata per un URL vuoto", gateway.installCalls.isEmpty())
    assertEquals(
      MobileAddonFeedback.Failed(MobileAddonError.INVALID_URL),
      viewModel.state.value.feedback
    )
  }

  @Test
  fun lInstallFallitoClassificaLErroreDelRepository() = runTest(dispatcher) {
    val gateway = FakeGateway()
    gateway.installResult = {
      throw IOException("Manifest non trovato (HTTP 502) per http://addon.example/manifest.json")
    }
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.install("http://addon.example")
    advanceUntilIdle()

    assertEquals(
      MobileAddonFeedback.Failed(MobileAddonError.HTTP),
      viewModel.state.value.feedback
    )
    assertFalse(viewModel.state.value.isInstalling)
  }

  @Test
  fun lAggiornamentoRiusaLoStessoBaseUrlEnePubblicaIlNuovoNome() = runTest(dispatcher) {
    val gateway = FakeGateway()
    gateway.installResult = { addon(name = "Local Test Addon v2", version = "2.0.0") }
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.update(addon())
    advanceUntilIdle()

    assertEquals(listOf("http://addon.example/conf"), gateway.installCalls)
    assertEquals(
      MobileAddonFeedback.Updated("Local Test Addon v2"),
      viewModel.state.value.feedback
    )
  }

  @Test
  fun lAggiornamentoFallitoClassificaIlTimeoutSenzaToccareLInstallazione() = runTest(dispatcher) {
    val gateway = FakeGateway()
    gateway.installResult = { throw SocketTimeoutException("connect timed out") }
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.update(addon())
    advanceUntilIdle()

    assertEquals(
      MobileAddonFeedback.Failed(MobileAddonError.TIMEOUT),
      viewModel.state.value.feedback
    )
    assertTrue("Nessuna rimozione durante un aggiornamento fallito", gateway.removedIds.isEmpty())
  }

  @Test
  fun laRimozioneUsaLInstallIdNonIlManifestId() = runTest(dispatcher) {
    val gateway = FakeGateway()
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    val target = addon(baseUrl = "http://torrentio.strem.fun/realdebrid=KEY")
    viewModel.remove(target)
    advanceUntilIdle()

    assertEquals(listOf("http://torrentio.strem.fun/realdebrid=KEY"), gateway.removedIds)
    assertEquals(MobileAddonFeedback.Removed, viewModel.state.value.feedback)
  }

  @Test
  fun ilToggleUsaLInstallId() = runTest(dispatcher) {
    val gateway = FakeGateway()
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    val target = addon(baseUrl = "http://torrentio.strem.fun/realdebrid=KEY")
    viewModel.setEnabled(target, false)

    assertEquals(
      listOf("http://torrentio.strem.fun/realdebrid=KEY" to false),
      gateway.enabledChanges
    )
  }

  // ── Associazioni Provider (binding utente) ───────────────────────────────────

  private val key = CatalogKey("addon.disney", "movie", "streaming_disney_movies")

  @Test
  fun laConfermaBindingScriveSoloIlBindingUtente() = runTest(dispatcher) {
    val gateway = FakeGateway()
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.confirmBinding(key, "disney")
    advanceUntilIdle()

    val persisted = gateway.persistedBindings.single()
    assertEquals(1, persisted.size)
    assertEquals(BindingSource.USER, persisted.single().source)
    assertEquals("disney", persisted.single().providerId)
    assertEquals(key.stableId, persisted.single().key.stableId)
  }

  @Test
  fun laConfermaDiUnBindingGiaDichiaratoNonDuplicaNulla() = runTest(dispatcher) {
    val gateway = FakeGateway()
    gateway.existing = { k ->
      if (k.stableId == key.stableId) {
        ProviderBinding(
          key = key,
          providerId = "disney",
          source = BindingSource.REGISTRY,
          confidence = BindingConfidence.CONFIRMED
        )
      } else {
        null
      }
    }
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.confirmBinding(key, "disney")
    advanceUntilIdle()

    assertTrue(
      "Un binding già dichiarato dal registry non produce scritture",
      gateway.persistedBindings.isEmpty()
    )
  }

  @Test
  fun laRimozioneBindingEliminaSoloIlBindingUtente() = runTest(dispatcher) {
    val gateway = FakeGateway()
    val userBinding = ProviderBinding(
      key = key,
      providerId = "disney",
      source = BindingSource.USER,
      confidence = BindingConfidence.CONFIRMED
    )
    gateway.bindingsFlow.value = UserProviderBindingStore.encode(listOf(userBinding))
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.removeBinding(key, "disney")
    advanceUntilIdle()

    val persisted = gateway.persistedBindings.single()
    assertTrue("Il binding utente rimosso non deve restare", persisted.isEmpty())
  }

  @Test
  fun ilFeedbackSiPuoAzzarareEsplicitamente() = runTest(dispatcher) {
    val gateway = FakeGateway()
    gateway.installResult = { addon() }
    val viewModel = MobileAddonsViewModel(gateway)
    backgroundScope.launch { viewModel.state.collect {} }
    advanceUntilIdle()

    viewModel.install("http://addon.example/conf")
    advanceUntilIdle()
    assertTrue(viewModel.state.value.feedback != null)

    viewModel.clearFeedback()
    advanceUntilIdle()
    assertEquals(null, viewModel.state.value.feedback)
  }
}
