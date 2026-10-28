package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Selettore delle sorgenti video interno al player.
 *
 * Contratti verificati:
 *  - il comando "Sorgente video" e' disponibile anche durante il buffering e in
 *    stato di errore (non solo durante la riproduzione);
 *  - la scelta manuale dell'utente e' AUTOREVOLE: le emissioni tardive degli
 *    altri provider non la sovrascrivono;
 *  - la lista del selettore si aggiorna progressivamente quando arrivano nuove
 *    sorgenti, senza riavviare la sorgente attualmente in riproduzione;
 *  - il cambio sorgente manuale e' gestito correttamente (URL, header, stato del
 *    player) e il selettore resta riutilizzabile se la sorgente scelta fallisce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerSourceSelectorTest {

  private val testDispatcher = StandardTestDispatcher()

  /** ViewModel creato dal test: le sue coroutine vengono chiuse in [tearDown]. */
  private var createdViewModel: StreamNovaViewModel? = null

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    AppSettingsRepository.setTorBoxApiKey(null)
    // Nessun addon Stremio: le query di catalogo reali farebbero scattare i
    // timeout virtuali (clock jump) e renderebbero i test non deterministici.
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()
  }

  @After
  fun tearDown() {
    // Le coroutine del ViewModel devono essere chiuse PRIMA di resetMain():
    // se restano vive possono accedere a Dispatchers.Main mentre la classe di
    // test successiva lo sta reimpostando ("Dispatchers.Main is used
    // concurrently with setting it").
    createdViewModel?.let { vm ->
      vm.viewModelScope.coroutineContext[Job]?.let { job ->
        job.cancel()
        // Propaga le cancellazioni sul dispatcher virtuale (nessun'attesa reale).
        testDispatcher.scheduler.advanceUntilIdle()
      }
    }
    createdViewModel = null
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()
    AppSettingsRepository.setTorBoxApiKey(null)
    Dispatchers.resetMain()
  }

  private fun useHttpWebMode() {
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setAutoplayEnabled(true)
  }

  private fun movieFixture() = MediaItem(
    id = "tt0111161",
    imdbId = "tt0111161",
    title = "Selettore Sorgenti",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 278
  )

  private fun provider(vararg sources: StreamSource, delayMs: Long = 0L) = object : StreamProvider {
    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      if (delayMs > 0L) delay(delayMs)
      return sources.toList()
    }
  }

  private fun createViewModel(providers: List<StreamProvider>): StreamNovaViewModel {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    createdViewModel = viewModel
    viewModel.streamManager = StreamManager(providers = providers)
    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ) = emptyList<com.example.domain.model.Subtitle>()
    }
    return viewModel
  }

  private fun source(
    name: String,
    url: String,
    quality: String = "1080p",
    headers: Map<String, String> = emptyMap()
  ) = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = name,
    headers = headers
  )

  // ── 1. Apertura del selettore durante buffering ed errore ────────────────

  @Test
  fun selectorOpensWhileTheStreamIsStillBuffering() = runTest(testDispatcher) {
    useHttpWebMode()
    val viewModel = createViewModel(
      listOf(provider(source("Lento", "https://cdn.example/lento/index.m3u8"), delayMs = 4_000L))
    )

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    // La risoluzione e' ancora in corso: il player e' in buffering.
    advanceTimeBy(500L)

    assertTrue(
      "Durante il buffering il comando deve restare disponibile (stato=${viewModel.streamResolutionState.value})",
      viewModel.streamResolutionState.value is StreamResolutionState.Loading
    )

    viewModel.openSourceSelector()

    assertTrue(
      "Il selettore sorgenti deve aprirsi anche durante il buffering",
      viewModel.showSourceSelector.value
    )
  }

  @Test
  fun selectorOpensEvenWhenTheResolutionFailed() = runTest(testDispatcher) {
    useHttpWebMode()
    // Titolo senza identificatori: la risoluzione fallisce con errore.
    val untitled = MediaItem(
      id = "",
      imdbId = null,
      title = "",
      synopsis = "",
      videoUrl = "",
      type = MediaType.FILM,
      tmdbId = null
    )
    val viewModel = createViewModel(listOf(provider(source("Provider", "https://cdn.example/a/index.m3u8"))))

    viewModel.openStreamForMedia(untitled, allowReuse = false)
    advanceUntilIdle()

    assertTrue(
      "Il caso di errore di risoluzione deve essere riproducibile (stato=${viewModel.streamResolutionState.value})",
      viewModel.streamResolutionState.value is StreamResolutionState.Error
    )

    viewModel.openSourceSelector()

    assertTrue(
      "Il selettore sorgenti deve restare apribile anche in stato di errore",
      viewModel.showSourceSelector.value
    )
  }

  // ── 2. La scelta manuale non e' sovrascrivibile dalle emissioni tardive ──

  @Test
  fun manualSelectionIsNotOverwrittenByLateProviderEmissions() = runTest(testDispatcher) {
    useHttpWebMode()
    val autoPicked = source("Auto 1080p", "https://cdn.example/auto/1080p/index.m3u8")
    val manualChoice = source(
      "Manuale 720p",
      "https://cdn.example/manuale/720p/index.m3u8",
      quality = "720p",
      headers = mapOf("Referer" to "https://manuale.example/")
    )
    val lateSource = source("Tardiva 1080p", "https://cdn.example/tardiva/1080p/index.m3u8")

    val viewModel = createViewModel(
      listOf(
        provider(autoPicked, manualChoice),
        // Emissione tardiva: arriva dopo la scelta manuale dell'utente.
        provider(lateSource, delayMs = 5_000L)
      )
    )

    val observedUrls = mutableListOf<String?>()
    backgroundScope.launch(testDispatcher) {
      viewModel.playbackState.collect { observedUrls.add(it.streamUrl) }
    }

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    advanceTimeBy(1_000L)

    // L'utente sceglie la sorgente dal selettore del player.
    val urlsBeforeSelection = observedUrls.filterNotNull().distinct()
    viewModel.openSourceSelector()
    viewModel.selectSource(manualChoice)
    advanceUntilIdle()

    assertEquals(
      "La scelta manuale dell'utente deve essere la sorgente in riproduzione",
      "https://cdn.example/manuale/720p/index.m3u8",
      viewModel.playbackState.value.streamUrl
    )
    val urlsAfterSelection = observedUrls.filterNotNull().distinct()

    // Trascorre il tempo in cui la risoluzione tardiva avrebbe emesso.
    advanceTimeBy(6_000L)
    advanceUntilIdle()

    assertEquals(
      "Le emissioni tardive degli altri provider non devono sostituire la scelta manuale",
      "https://cdn.example/manuale/720p/index.m3u8",
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Dopo la scelta manuale il flusso non deve essere stato riavviato " +
        "(prima: $urlsBeforeSelection, dopo: $urlsAfterSelection)",
      urlsAfterSelection,
      observedUrls.filterNotNull().distinct()
    )
  }

  // ── 3. Aggiornamento progressivo della lista senza riavvio ───────────────

  @Test
  fun candidateListGrowsProgressivelyWithoutRestartingTheActiveSource() = runTest(testDispatcher) {
    useHttpWebMode()
    val first = source("Provider Veloce", "https://cdn.example/veloce/1080p/index.m3u8")
    val late = source("Provider Lento", "https://cdn.example/lento/720p/index.m3u8", quality = "720p")

    val viewModel = createViewModel(
      listOf(
        provider(first),
        provider(late, delayMs = 3_000L)
      )
    )

    val observedUrls = mutableListOf<String?>()
    backgroundScope.launch(testDispatcher) {
      viewModel.playbackState.collect { observedUrls.add(it.streamUrl) }
    }

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    advanceUntilIdle()

    assertEquals(
      "La lista del selettore deve essere arricchita dalle emissioni successive",
      2,
      viewModel.sourceCandidates.value.size
    )
    assertTrue(
      "La lista del selettore deve contenere entrambe le sorgenti",
      viewModel.sourceCandidates.value.any { it.serverName == "Provider Veloce" } &&
        viewModel.sourceCandidates.value.any { it.serverName == "Provider Lento" }
    )
    assertEquals(
      "La sorgente in riproduzione non deve essere stata sostituita",
      "https://cdn.example/veloce/1080p/index.m3u8",
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Il player non deve essere mai stato riavviato",
      1,
      observedUrls.filterNotNull().distinct().size
    )
  }

  // ── 4. Gestione del cambio sorgente e riapertura del selettore ───────────

  @Test
  fun manualSourceSwitchUpdatesTheStreamAndKeepsTheSelectorUsable() = runTest(testDispatcher) {
    useHttpWebMode()
    val autoPicked = source("Auto 1080p", "https://cdn.example/auto/1080p/index.m3u8")
    val second = source(
      "Seconda 720p",
      "https://cdn.example/seconda/720p/index.m3u8",
      quality = "720p",
      headers = mapOf("Referer" to "https://seconda.example/")
    )
    val third = source("Terza 480p", "https://cdn.example/terza/480p/index.m3u8", quality = "480p")

    val viewModel = createViewModel(listOf(provider(autoPicked, second, third)))

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    advanceUntilIdle()

    val initialToken = viewModel.manualSourceSelectionToken.value
    val initialUrl = viewModel.playbackState.value.streamUrl
    assertNotNull("Il player deve avere una sorgente attiva", initialUrl)

    // Prima scelta manuale dal selettore del player.
    viewModel.openSourceSelector()
    assertTrue(viewModel.showSourceSelector.value)
    viewModel.selectSource(second)
    advanceUntilIdle()

    assertEquals(
      "Il nuovo URL deve essere quello della sorgente scelta",
      "https://cdn.example/seconda/720p/index.m3u8",
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Gli header HTTP della sorgente scelta devono essere applicati",
      mapOf("Referer" to "https://seconda.example/"),
      viewModel.playbackState.value.streamHeaders
    )
    assertEquals(
      "La sorgente in riproduzione deve essere quella scelta",
      second.serverName,
      viewModel.playbackState.value.currentSource?.serverName
    )
    assertEquals(
      "Il token di cambio sorgente deve essere incrementato",
      initialToken + 1,
      viewModel.manualSourceSelectionToken.value
    )
    assertFalse(
      "Il selettore deve chiudersi dopo la scelta",
      viewModel.showSourceSelector.value
    )
    assertTrue(
      "La lista dei candidati deve sopravvivere alla scelta (retry su sorgente fallita)",
      viewModel.sourceCandidates.value.isNotEmpty()
    )

    // Seconda scelta: il percorso di fallback (nessun dialog iniziale) deve
    // funzionare, cosi' l'utente puo' provare un'altra sorgente.
    viewModel.openSourceSelector()
    assertTrue(viewModel.showSourceSelector.value)
    viewModel.selectSource(third)
    advanceUntilIdle()

    assertEquals(
      "La seconda scelta manuale deve sostituire la sorgente in riproduzione",
      "https://cdn.example/terza/480p/index.m3u8",
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Il token di cambio sorgente deve essere incrementato anche per la seconda scelta",
      initialToken + 2,
      viewModel.manualSourceSelectionToken.value
    )
  }
}
