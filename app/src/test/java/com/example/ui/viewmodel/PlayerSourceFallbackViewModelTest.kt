package com.example.ui.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.ui.screens.player.PlayerSourceFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fallback automatico della sorgente del player.
 *
 * Contratti verificati:
 *  - errore DEFINITIVO con un'alternativa gia' risolta -> passaggio automatico
 *    alla sorgente successiva (stesso meccanismo della scelta manuale);
 *  - errore senza alternative -> nessun cambio: il player resta sull'errore e il
 *    selettore manuale continua a funzionare;
 *  - errore transitorio (rete) o di codec -> nessun cambio automatico;
 *  - la sorgente fallita e quelle gia' tentate non vengono ritentate nello stesso
 *    ciclo di riproduzione (nessun ciclo, nessun ping-pong);
 *  - un'emissione tardiva dei provider non sostituisce la sorgente attiva;
 *  - una scelta manuale in corso non viene mai interrotta dal fallback;
 *  - l'errore di un provider non cancella le sorgenti gia' raccolte dagli altri;
 *  - una callback di errore obsoleta (sorgente non piu' attiva) viene scartata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerSourceFallbackViewModelTest {

  private val testDispatcher = StandardTestDispatcher()

  /** ViewModel creato dal test: le sue coroutine vengono chiuse in [tearDown]. */
  private var createdViewModel: StreamNovaViewModel? = null

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    AppSettingsRepository.setTorBoxApiKey(null)
    com.example.data.stremio.StremioAddonRepository.getInstalledAddons().forEach {
      com.example.data.stremio.StremioAddonRepository.removeAddon(it.id)
    }
    com.example.data.stremio.StremioAddonRepository.clearCaches()
  }

  @After
  fun tearDown() {
    // Le coroutine del ViewModel devono essere chiuse PRIMA di resetMain():
    // se restano vive possono accedere a Dispatchers.Main mentre la classe di
    // test successiva lo sta reimpostando.
    createdViewModel?.let { vm ->
      vm.viewModelScope.coroutineContext[Job]?.let { job ->
        job.cancel()
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
    id = "tt0137523",
    imdbId = "tt0137523",
    title = "Fallback Sorgenti",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 550
  )

  private fun source(
    name: String,
    url: String,
    quality: String = "1080p"
  ) = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = name
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

  /** Provider che fallisce SEMPRE: non deve bloccare gli altri ne' cancellare le sorgenti. */
  private fun failingProvider() = object : StreamProvider {
    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> = throw IllegalStateException("provider non raggiungibile")
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

  private val primaUrl = "https://cdn.example/prima/720p/index.m3u8"
  private val secondaUrl = "https://cdn.example/seconda/1080p/index.m3u8"
  private val terzaUrl = "https://cdn.example/terza/480p/index.m3u8"

  /** Avvia la riproduzione della prima sorgente (720p) lasciando le alternative in lista. */
  private suspend fun TestScope.startPlaybackWithAlternative(viewModel: StreamNovaViewModel) {
    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    advanceUntilIdle()
  }

  // ── 1. Errore definitivo con alternativa -> passaggio automatico ─────────────

  @Test
  fun erroreDefinitivoConAlternativaAvviaLaSorgenteSuccessiva() = runTest(testDispatcher) {
    useHttpWebMode()
    val viewModel = createViewModel(
      listOf(
        provider(source("Prima 720p", primaUrl, quality = "720p")),
        provider(source("Seconda 1080p", secondaUrl), delayMs = 3_000L)
      )
    )
    startPlaybackWithAlternative(viewModel)

    assertEquals(
      "La riproduzione deve partire dalla prima sorgente disponibile",
      primaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "L'alternativa migliore deve essere gia' in lista per il selettore",
      2,
      viewModel.sourceCandidates.value.size
    )
    val tokenBefore = viewModel.manualSourceSelectionToken.value

    val switched = viewModel.handleSourcePlaybackFailure(
      failedStreamUrl = primaUrl,
      verdict = PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE
    )

    assertTrue("Un'alternativa disponibile deve essere avviata automaticamente", switched)
    assertEquals(
      "La sorgente attiva deve essere l'alternativa migliore rimasta",
      secondaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "La sorgente in riproduzione deve essere aggiornata (header e metadati)",
      "Seconda 1080p",
      viewModel.playbackState.value.currentSource?.serverName
    )
    assertEquals(
      "Il cambio deve usare il token di ricarica del player (stessa istanza ExoPlayer)",
      tokenBefore + 1,
      viewModel.manualSourceSelectionToken.value
    )
    assertEquals(
      "La lista del selettore non deve perdere nessuna sorgente",
      2,
      viewModel.sourceCandidates.value.size
    )
  }

  // ── 2. Errore senza alternative -> errore visibile e selettore disponibile ───

  @Test
  fun erroreSenzaAlternativeMantieneLErroreEIlSelettoreManuale() = runTest(testDispatcher) {
    useHttpWebMode()
    val viewModel = createViewModel(
      listOf(provider(source("Unica 1080p", secondaUrl)))
    )
    startPlaybackWithAlternative(viewModel)
    val tokenBefore = viewModel.manualSourceSelectionToken.value

    val switched = viewModel.handleSourcePlaybackFailure(
      failedStreamUrl = secondaUrl,
      verdict = PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE
    )

    assertFalse("Senza alternative non deve avvenire alcun cambio", switched)
    assertEquals(
      "Il player deve restare sulla sorgente che ha fallito (nessun cambio a vuoto)",
      secondaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Nessun ricaricamento del player senza un'alternativa",
      tokenBefore,
      viewModel.manualSourceSelectionToken.value
    )
    assertEquals(
      "La sorgente deve restare elencata: l'utente puo' ritentarla a mano",
      1,
      viewModel.sourceCandidates.value.size
    )

    viewModel.openSourceSelector()
    assertTrue(
      "In stato di errore il selettore manuale deve restare apribile",
      viewModel.showSourceSelector.value
    )
  }

  // ── 3. Errore transitorio o di codec -> nessun cambio automatico ─────────────

  /**
   * Un buffering temporaneo non produce alcun `onPlayerError` (Media3 segnala il
   * buffering solo con `STATE_BUFFERING`), quindi non entra mai nel fallback. Un
   * errore transitorio che invece arriva al player — rete instabile, lettura
   * fuori intervallo dopo un seek, codec, sottotitoli — non deve comunque far
   * cambiare sorgente: il flusso resta quello in riproduzione.
   */
  @Test
  fun erroreTransitorioNonFaCambiareSorgente() = runTest(testDispatcher) {
    useHttpWebMode()
    val viewModel = createViewModel(
      listOf(
        provider(source("Prima 720p", primaUrl, quality = "720p")),
        provider(source("Seconda 1080p", secondaUrl), delayMs = 3_000L)
      )
    )
    startPlaybackWithAlternative(viewModel)
    val tokenBefore = viewModel.manualSourceSelectionToken.value

    val verdicts = listOf(
      PlayerSourceFallback.Verdict.TRANSIENT,
      PlayerSourceFallback.Verdict.NOT_SOURCE_RELATED
    )
    verdicts.forEach { verdict ->
      assertFalse(
        "Un esito $verdict (rete instabile, codec, sottotitoli) non deve cambiare sorgente",
        viewModel.handleSourcePlaybackFailure(primaUrl, verdict)
      )
    }

    assertEquals(
      "La sorgente in riproduzione non deve cambiare",
      primaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Nessun ricaricamento del player per un errore non definitivo",
      tokenBefore,
      viewModel.manualSourceSelectionToken.value
    )
    assertEquals(
      "L'alternativa resta disponibile per il selettore manuale",
      2,
      viewModel.sourceCandidates.value.size
    )
  }

  // ── 4. La sorgente fallita non viene ritentata nello stesso ciclo ────────────

  @Test
  fun ogniSorgenteVieneTentataUnaSolaVoltaPoiIlFallbackSiFerma() = runTest(testDispatcher) {
    useHttpWebMode()
    val viewModel = createViewModel(
      listOf(
        provider(source("Prima 720p", primaUrl, quality = "720p")),
        provider(
          source("Seconda 1080p", secondaUrl),
          source("Terza 480p", terzaUrl, quality = "480p"),
          delayMs = 3_000L
        )
      )
    )
    startPlaybackWithAlternative(viewModel)
    val tokenBefore = viewModel.manualSourceSelectionToken.value

    // Prima -> Seconda (alternativa migliore rimasta).
    assertTrue(viewModel.handleSourcePlaybackFailure(primaUrl, PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE))
    assertEquals(secondaUrl, viewModel.playbackState.value.streamUrl)

    // Seconda -> Terza: la prima non deve tornare in gioco.
    assertTrue(viewModel.handleSourcePlaybackFailure(secondaUrl, PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE))
    assertEquals(terzaUrl, viewModel.playbackState.value.streamUrl)

    // Ciclo esaurito: nessun nuovo tentativo e nessun ritorno alle sorgenti fallite.
    assertFalse(
      "Dopo aver tentato tutte le sorgenti il fallback deve fermarsi",
      viewModel.handleSourcePlaybackFailure(terzaUrl, PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE)
    )
    assertFalse(
      "Un secondo errore della stessa sorgente non deve riavviare il ciclo",
      viewModel.handleSourcePlaybackFailure(terzaUrl, PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE)
    )
    assertEquals(
      "La sorgente attiva deve restare l'ultima avviata",
      terzaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertNotEquals(
      "Nessun ping-pong verso la sorgente iniziale",
      primaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Solo i due cambi reali devono aver ricaricato il player",
      tokenBefore + 2,
      viewModel.manualSourceSelectionToken.value
    )
  }

  // ── 5. Emissione tardiva dopo il fallback -> nessuna sostituzione ────────────

  @Test
  fun emissioneTardivaNonSostituisceLaSorgenteAvviataDalFallback() = runTest(testDispatcher) {
    useHttpWebMode()
    val quartaUrl = "https://cdn.example/quarta/4k/index.m3u8"
    val viewModel = createViewModel(
      listOf(
        provider(source("Prima 720p", primaUrl, quality = "720p")),
        provider(source("Seconda 1080p", secondaUrl), delayMs = 3_000L),
        provider(source("Quarta 4K", quartaUrl, quality = "4K"), delayMs = 8_000L)
      )
    )

    viewModel.openStreamForMedia(movieFixture(), allowReuse = false)
    // t=4s: la prima sorgente e' in riproduzione, la seconda e' gia' arrivata.
    advanceTimeBy(4_000L)
    assertEquals(primaUrl, viewModel.playbackState.value.streamUrl)

    assertTrue(viewModel.handleSourcePlaybackFailure(primaUrl, PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE))
    assertEquals(secondaUrl, viewModel.playbackState.value.streamUrl)
    val tokenAfterFallback = viewModel.manualSourceSelectionToken.value

    // t=10s: arriva la sorgente 4K risolta in ritardo (emissione tardiva).
    advanceTimeBy(6_000L)

    assertEquals(
      "Una sorgente arrivata dopo non deve sostituire il flusso in riproduzione",
      secondaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Nessun ricaricamento del player per l'emissione tardiva",
      tokenAfterFallback,
      viewModel.manualSourceSelectionToken.value
    )
    assertEquals(
      "La sorgente tardiva resta disponibile al selettore manuale",
      3,
      viewModel.sourceCandidates.value.size
    )
  }

  // ── 6. Scelta manuale: nessun fallback automatico concorrente ────────────────

  @Test
  fun laSceltaManualeInCorsoNonVieneInterrottaDalFallback() = runTest(testDispatcher) {
    useHttpWebMode()
    val viewModel = createViewModel(
      listOf(
        provider(source("Prima 720p", primaUrl, quality = "720p")),
        provider(source("Seconda 1080p", secondaUrl), delayMs = 3_000L)
      )
    )
    startPlaybackWithAlternative(viewModel)
    val tokenBefore = viewModel.manualSourceSelectionToken.value

    // L'utente sceglie a mano la seconda sorgente: la scelta e' autorevole.
    viewModel.selectSource(source("Seconda 1080p", secondaUrl))
    // Errore della sorgente precedente mentre la scelta e' in applicazione.
    assertFalse(
      "Il fallback non deve inserirsi in una scelta manuale in corso",
      viewModel.handleSourcePlaybackFailure(primaUrl, PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE)
    )

    advanceUntilIdle()

    assertEquals(
      "La scelta manuale deve vincere sul fallback",
      secondaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Un solo cambio: quello manuale (nessun avvio concorrente)",
      tokenBefore + 1,
      viewModel.manualSourceSelectionToken.value
    )
  }

  // ── 7. Errore di un provider -> le altre sorgenti restano disponibili ────────

  @Test
  fun erroreDiUnProviderNonCancellaLeSorgentiGiaRaccolte() = runTest(testDispatcher) {
    useHttpWebMode()
    val viewModel = createViewModel(
      listOf(
        failingProvider(),
        provider(source("Seconda 1080p", secondaUrl))
      )
    )

    startPlaybackWithAlternative(viewModel)

    assertEquals(
      "Il provider funzionante deve aver avviato la riproduzione",
      secondaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Le sorgenti valide devono restare in lista nonostante il provider fallito",
      1,
      viewModel.sourceCandidates.value.size
    )
    assertTrue(
      "La risoluzione deve essere conclusa con successo",
      viewModel.streamResolutionState.value is StreamResolutionState.Success
    )

    // Nessuna alternativa (l'unica sorgente e' quella attiva): il fallback si
    // ferma e la sorgente resta comunque selezionabile a mano.
    assertFalse(
      viewModel.handleSourcePlaybackFailure(secondaUrl, PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE)
    )
    assertEquals(
      "La sorgente resta disponibile al selettore dopo l'errore",
      1,
      viewModel.sourceCandidates.value.size
    )
  }

  // ── 8. Callback obsoleta -> ignorata ────────────────────────────────────────

  @Test
  fun erroreDiUnaSorgenteNonPiuAttivaVieneIgnorato() = runTest(testDispatcher) {
    useHttpWebMode()
    val viewModel = createViewModel(
      listOf(
        provider(source("Prima 720p", primaUrl, quality = "720p")),
        provider(source("Seconda 1080p", secondaUrl), delayMs = 3_000L)
      )
    )
    startPlaybackWithAlternative(viewModel)
    val tokenBefore = viewModel.manualSourceSelectionToken.value

    val switched = viewModel.handleSourcePlaybackFailure(
      failedStreamUrl = "https://cdn.example/superata/index.m3u8",
      verdict = PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE
    )

    assertFalse("L'errore riguarda una sorgente non piu' attiva", switched)
    assertEquals(
      "Il flusso in riproduzione non deve essere toccato",
      primaUrl,
      viewModel.playbackState.value.streamUrl
    )
    assertEquals(
      "Nessun ricaricamento del player",
      tokenBefore,
      viewModel.manualSourceSelectionToken.value
    )
  }
}
