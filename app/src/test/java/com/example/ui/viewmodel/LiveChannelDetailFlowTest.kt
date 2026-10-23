package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SeasonEpisodesUiState
import com.example.data.repository.StremioCatalogRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.StremioCatalogEngine
import com.example.data.stremio.StremioCatalogMapper
import com.example.data.stremio.StremioMetaItem
import com.example.data.streaming.StreamResult
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Un canale TvVoo (`type = tv`, id `vavoo_*`) non deve passare dal flusso
 * stagioni/episodi: apre la scheda live e mostra le sue sorgenti.
 *
 * Il manifest e la risposta catalogo sono ricreati da un server locale con la
 * stessa forma dell'addon reale: nessun id di addon reale è codificato.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LiveChannelDetailFlowTest {

  private lateinit var testDispatcher: TestDispatcher
  private val createdViewModels = mutableListOf<StreamNovaViewModel>()
  private val fixtures = mutableListOf<Fixture>()

  @Before
  fun setUp() {
    testDispatcher = StandardTestDispatcher()
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    createdViewModels.forEach { it.viewModelScope.cancel() }
    createdViewModels.clear()
    Dispatchers.resetMain()
    StremioCatalogEngine.clearCache()
    fixtures.forEach { it.stop() }
    fixtures.clear()
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    StremioCatalogRepository.clearChannelItemIndex()
  }

  private class Fixture(
    private val responses: Map<String, String>
  ) {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start(): String {
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      return "http://127.0.0.1:${server.address.port}"
    }

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.rawPath
      requested += path
      try {
        when {
          path == "/manifest.json" -> respond(exchange, 200, MANIFEST_JSON)
          responses.containsKey(path) -> respond(exchange, 200, responses.getValue(path))
          else -> respond(exchange, 404, """{"error":"not found"}""")
        }
      } finally {
        exchange.close()
      }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
      private val MANIFEST_JSON = """
        {
          "id": "org.stremio.vavoo.clean",
          "name": "TvVoo | ElfHosted",
          "version": "1.0.0",
          "description": "Canali TV",
          "resources": ["catalog", "meta", "stream"],
          "types": ["tv"],
          "idPrefixes": ["vavoo", "vavoo_"],
          "catalogs": [
            {
              "type": "tv",
              "id": "vavoo_tv_home_it",
              "name": "TvVoo TV",
              "extra": [{"name": "genre", "options": ["Tutti", "Rai", "Sport"]}]
            }
          ]
        }
      """.trimIndent()

      const val CHANNEL_CATALOG_PATH = "/catalog/tv/vavoo_tv_home_it.json"

      val CHANNEL_CATALOG = """
        {"metas":[
          {"id":"vavoo_rai1","type":"tv","name":"Rai 1","poster":"https://img.example/rai1.png"},
          {"id":"vavoo_canale5","type":"tv","name":"Canale 5","poster":"https://img.example/c5.png"}
        ]}
      """.trimIndent()

      val CHANNEL_STREAMS = """
        {"streams":[
          {"name":"TvVoo","title":"Rai 1 • HD","url":"https://cdn.example/rai1/index.m3u8"},
          {"name":"TvVoo","title":"Rai 1 • Backup","url":"https://cdn2.example/rai1/index.m3u8"}
        ]}
      """.trimIndent()
    }
  }

  /**
   * Attende il termine reale della risoluzione canale. La risoluzione usa il
   * dispatcher di I/O del repository (non il TestDispatcher), quindi non basta
   * `advanceUntilIdle`: si attende che lo stato esca da `Loading`.
   */
  private suspend fun awaitChannelResolution(viewModel: StreamNovaViewModel) {
    // L'attesa gira FUORI dal thread principale e fa avanzare il Looper principale
    // di Robolectric (sospeso di default nei test JVM): senza `idle()` i
    // `withContext(Dispatchers.Main)` che pubblicano lo stato non verrebbero mai
    // eseguiti e la risoluzione resterebbe per sempre in `Loading`.
    withContext(Dispatchers.IO) {
      repeat(400) {
        val state = viewModel.streamResult.value
        if (state is StreamResult.Success || state is StreamResult.Error) return@withContext
        Thread.sleep(25)
      }
    }
  }

  /** Attende che la scelta manuale della sorgente sia stata applicata al player. */
  private suspend fun awaitPlayback(viewModel: StreamNovaViewModel) {
    withContext(Dispatchers.IO) {
      repeat(400) {
        if (viewModel.playbackState.value.streamUrl != null) return@withContext
        Thread.sleep(25)
      }
    }
  }

  private fun createViewModel(): StreamNovaViewModel =
    StreamNovaViewModel(ioDispatcher = Dispatchers.IO).also { createdViewModels += it }

  private fun installTvVoo(): Fixture {
    val fixture = Fixture(
      responses = mapOf(
        Fixture.CHANNEL_CATALOG_PATH to Fixture.CHANNEL_CATALOG,
        "/stream/tv/vavoo_rai1.json" to Fixture.CHANNEL_STREAMS
      )
    )
    fixtures += fixture
    val baseUrl = fixture.start()
    runBlocking { StremioAddonRepository.installAddon(baseUrl) }
    return fixture
  }

  /** Carica il catalogo TvVoo come fa la Home e restituisce la card del canale. */
  private fun loadChannelCard(): MediaItem {
    val target = StremioCatalogEngine.getAvailableCatalogs("series")
      .first { it.addonId == "org.stremio.vavoo.clean" }
    val result = runBlocking { StremioCatalogEngine.fetchCatalog(target) }
    assertTrue("Catalogo canali non caricato", result.isSuccess)
    StremioCatalogRepository.let { repo ->
      runBlocking { repo.loadCatalogSection(target) }
    }
    return StremioCatalogMapper.toMediaItem(
      StremioMetaItem(id = "vavoo_rai1", type = "tv", name = "Rai 1"),
      addonName = target.addonName,
      fallbackType = MediaType.SERIE_TV
    )
  }

  @Test
  fun unCanaleApreIlFlussoliveENonCaricaStagioni() = runTest {
    installTvVoo()
    val viewModel = createViewModel()
    val channel = loadChannelCard()

    viewModel.openLiveChannel(channel)
    awaitChannelResolution(viewModel)

    // Nessun caricamento stagioni/episodi: il canale non ne ha.
    assertFalse(viewModel.seasonEpisodesUiState.value is SeasonEpisodesUiState.Loading)
    assertTrue(viewModel.seasonEpisodesUiState.value is SeasonEpisodesUiState.Idle)

    // Sorgenti del canale pronte per il selettore esistente.
    val sources = viewModel.availableSources.value
    assertEquals(2, sources.size)
    assertTrue(sources.all { it.serverName == "TvVoo | ElfHosted" })
    assertEquals("https://cdn.example/rai1/index.m3u8", sources.first().streamUrl)
  }

  @Test
  fun leSorgentiDelCanaleUsanoIlTipoOriginaleTv() = runTest {
    val fixture = installTvVoo()
    val viewModel = createViewModel()
    val channel = loadChannelCard()

    viewModel.openLiveChannel(channel)
    awaitChannelResolution(viewModel)

    // L'addon è interrogato con `tv` (tipo del suo catalogo), mai con `series`,
    // e senza stagione/episodio.
    assertTrue(
      "Richiesta stream mancante: ${fixture.requested}",
      fixture.requested.contains("/stream/tv/vavoo_rai1.json")
    )
    assertTrue(fixture.requested.none { it.startsWith("/stream/series/") })
    assertTrue(fixture.requested.none { it.contains(":1:1") })
  }

  @Test
  fun unaSorgenteDelCanalePuoEssereSelezionata() = runTest {
    installTvVoo()
    val viewModel = createViewModel()
    val channel = loadChannelCard()

    viewModel.openLiveChannel(channel)
    awaitChannelResolution(viewModel)
    val chosen = viewModel.availableSources.value.last()

    viewModel.selectSource(chosen)
    awaitPlayback(viewModel)

    val playback = viewModel.playbackState.value
    assertEquals("vavoo_rai1", playback.media?.id)
    assertEquals(chosen.streamUrl, playback.streamUrl)
    assertFalse("Il canale non deve essere riprodotto come episodio", playback.currentEpisode != null)
  }

  @Test
  fun unaSerieNormaleRestaSulDettaglioTradizionale() = runTest {
    installTvVoo()
    val viewModel = createViewModel()

    assertFalse(viewModel.isLiveChannel("tt0903747"))
    assertFalse(viewModel.isLiveChannel("tmdb:1396"))
    assertFalse(viewModel.isLiveChannel("1396"))

    val series = MediaItem(
      id = "tt0903747",
      title = "Breaking Bad",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 1396
    )
    viewModel.openDetail(series)
    awaitChannelResolution(viewModel)

    // Il percorso tradizionale non è stato sostituito dal flusso canale.
    assertTrue(viewModel.availableSources.value.isEmpty())
    assertFalse(viewModel.isLiveChannel(series.id))
  }
}
