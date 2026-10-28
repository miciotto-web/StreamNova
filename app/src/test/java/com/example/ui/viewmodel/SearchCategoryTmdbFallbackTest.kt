package com.example.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.example.data.api.TmdbApiClient
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.StremioCatalogRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.ui.screens.GLOBAL_SEARCH_CATEGORIES
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fallback TMDB "Usa TMDB come fallback per i generi" (sezione Ricerca).
 *
 * Scenario di riferimento: Serie TV → Horror. L'addon (struttura di Xperience, il cui
 * manifest non è nel repository) dichiara cataloghi serie che NON applicano il filtro
 * per genere: nessun catalogo configurato copre la combinazione, quindi — con lo switch
 * attivo — i titoli arrivano da TMDB. Lo stesso vale quando un catalogo compatibile è
 * interrogato con successo ma risponde HTTP 200 senza risultati (catalogo vuoto).
 *
 * Determinismo: addon su un server HTTP locale, TMDB su un secondo server locale
 * ([TmdbApiClient.overrideServiceBaseUrl]). Nessuna credenziale reale, nessuna rete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SearchCategoryTmdbFallbackTest {

  private lateinit var testDispatcher: TestDispatcher
  private val createdViewModels = mutableListOf<StreamNovaViewModel>()

  @Before
  fun setUp() {
    testDispatcher = StandardTestDispatcher()
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
    MediaRepository.init(context)
  }

  @After
  fun tearDown() {
    createdViewModels.forEach { it.viewModelScope.cancel() }
    createdViewModels.clear()
    TmdbApiClient.overrideServiceBaseUrl(null)
    StremioCatalogRepository.clearCache()
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
    Dispatchers.resetMain()
  }

  // ── Fixture addon Stremio ────────────────────────────────────────────────────

  private enum class CatalogMode { OK, ERROR, EMPTY }

  /**
   * Struttura del manifest dell'addon di test.
   *
   * - [XPERIENCE_LIKE] riproduce la struttura di Xperience: cataloghi film con filtro
   *   `genre`, ma NESSUN catalogo serie che accetti il filtro per genere (i cataloghi
   *   serie esistono, semplicemente non sono filtrabili).
   * - [SERIES_GENRE_CAPABLE] è un secondo addon che dichiara esplicitamente il genere
   *   `Horror` per le serie: quando è installato, il fallback NON deve partire.
   */
  private enum class Mode { XPERIENCE_LIKE, SERIES_GENRE_CAPABLE, BOTH_GENRE_CAPABLE }

  private inner class AddonFixture(
    private val mode: Mode,
    private val seriesMode: CatalogMode = CatalogMode.OK,
    private val movieMode: CatalogMode = CatalogMode.OK
  ) {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    fun start(): String {
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      return baseUrl
    }

    fun stop() = server.stop(0)

    val catalogRequests: List<String>
      get() = synchronized(requested) { requested.filter { it.startsWith("/catalog/") } }

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.rawPath
      requested += path
      try {
        when {
          path == "/manifest.json" -> respond(exchange, 200, manifestJson())
          path.startsWith("/catalog/series/") -> when (seriesMode) {
            CatalogMode.ERROR -> respond(exchange, 500, """{"error":"series down"}""")
            CatalogMode.EMPTY -> respond(exchange, 200, """{"metas":[]}""")
            CatalogMode.OK -> respond(exchange, 200, SERIES_BODY)
          }
          path.startsWith("/catalog/movie/") -> when (movieMode) {
            CatalogMode.ERROR -> respond(exchange, 500, """{"error":"movies down"}""")
            CatalogMode.EMPTY -> respond(exchange, 200, """{"metas":[]}""")
            CatalogMode.OK -> respond(exchange, 200, movieBody(path))
          }
          else -> respond(exchange, 404, "")
        }
      } finally {
        exchange.close()
      }
    }

    private fun manifestJson(): String = when (mode) {
      Mode.XPERIENCE_LIKE -> XPERIENCE_MANIFEST
      Mode.SERIES_GENRE_CAPABLE -> SERIES_GENRE_MANIFEST
      Mode.BOTH_GENRE_CAPABLE -> BOTH_GENRE_MANIFEST
    }

    /** Playlist film dell'addon: una lista "Horror" e una "Fantascienza". */
    private fun movieBody(path: String): String = when {
      path.contains("genre_horror") -> HORROR_MOVIES
      path.contains("genre_fantascienza") -> SCI_FI_MOVIES
      // `discover_movies` (catalogo generico con filtro `genre`): risponde per il genere
      // RICHIESTO, come un addon vero.
      path.contains("genre=Fantascienza") -> SCI_FI_MOVIES
      path.contains("genre=Horror") -> HORROR_MOVIES
      else -> """{"metas":[]}"""
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
  }

  // ── Fixture TMDB (server locale) ─────────────────────────────────────────────

  private enum class TmdbMode { OK, ERROR, EMPTY }

  /**
   * Server TMDB locale: risponde solo agli endpoint `discover` realmente usati dal
   * fallback. Registra le richieste per verificare che NON vengano fatte quando non
   * servono (switch disattivato, catalogo compatibile, errore del catalogo).
   */
  private inner class TmdbFixture(private val mode: TmdbMode = TmdbMode.OK) {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start(): String {
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      TmdbApiClient.overrideServiceBaseUrl("http://127.0.0.1:${server.address.port}/3/")
      return "http://127.0.0.1:${server.address.port}/3/"
    }

    fun stop() {
      TmdbApiClient.overrideServiceBaseUrl(null)
      server.stop(0)
    }

    val discoverRequests: List<String>
      get() = synchronized(requested) { requested.filter { it.contains("/discover/") } }

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.rawPath
      val query = exchange.requestURI.rawQuery.orEmpty()
      requested += "$path?$query"
      try {
        if (mode == TmdbMode.ERROR) {
          respond(exchange, 500, """{"status_message":"tmdb down"}""")
          return
        }
        if (mode == TmdbMode.EMPTY) {
          respond(exchange, 200, """{"page":1,"results":[],"total_pages":1,"total_results":0}""")
          return
        }
        val page = Regex("page=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        when {
          path.endsWith("/discover/tv") -> respond(exchange, 200, tvBody(page))
          path.endsWith("/discover/movie") -> respond(exchange, 200, movieBody(page))
          else -> respond(exchange, 404, """{"status_message":"not found"}""")
        }
      } finally {
        exchange.close()
      }
    }

    private fun tvBody(page: Int): String = if (page > 1) """
      {"page":$page,"results":[
        {"id":80010$page,"name":"Pagina $page Serie","original_name":"Page $page",
         "first_air_date":"2019-01-01","overview":"Horror TV page $page","poster_path":"/tvp$page.jpg",
         "vote_average":7.0,"genre_ids":[27],"original_language":"en"}
      ],"total_pages":3,"total_results":60}
    """.trimIndent() else """
      {"page":1,"results":[
        {"id":800001,"name":"La Casa del Lago","original_name":"The Lake House",
         "first_air_date":"2021-10-01","overview":"Horror TV","poster_path":"/tv1.jpg",
         "vote_average":7.4,"genre_ids":[27],"original_language":"en"},
        {"id":800002,"name":"Sussurri","original_name":"Whispers",
         "first_air_date":"2022-01-15","overview":"Horror TV 2","poster_path":"/tv2.jpg",
         "vote_average":6.9,"genre_ids":[27],"original_language":"en"},
        {"id":800003,"name":"Notte Infinita","original_name":"Endless Night",
         "first_air_date":"2020-03-03","overview":"Horror TV 3","poster_path":"/tv3.jpg",
         "vote_average":7.1,"genre_ids":[27],"original_language":"en"}
      ],"total_pages":3,"total_results":60}
    """.trimIndent()

    private fun movieBody(page: Int): String = """
      {"page":$page,"results":[
        {"id":900001,"title":"Il Buio","original_title":"The Dark","release_date":"2019-05-05",
         "overview":"Horror movie","poster_path":"/m1.jpg","vote_average":7.0,
         "genre_ids":[27],"original_language":"en"},
        {"id":900002,"title":"Ombra Condivisa","original_title":"Shared Shadow",
         "release_date":"2022-06-06","overview":"Horror movie 2","poster_path":"/m2.jpg",
         "vote_average":6.5,"genre_ids":[27],"original_language":"en"}
      ],"total_pages":2,"total_results":40}
    """.trimIndent()

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
  }

  // ── 1. Serie TV → Horror: nessun catalogo copre il genere, switch attivo ─────

  @Test
  fun serieHorrorSenzaCatalogoCompatibileUsaTmdb() = runTest(testDispatcher) {
    val addon = AddonFixture(Mode.XPERIENCE_LIKE)
    val tmdb = TmdbFixture()
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      val series = loadRecord(viewModel, MediaType.SERIE_TV)
      assertEquals(
        "Nessun catalogo serie copre Horror: si usa TMDB",
        GenreLoadReason.TMDB_FALLBACK,
        series?.reason
      )
      assertEquals("Una sola pagina TMDB per le serie", 1, series?.tmdbPages)
      assertTrue(
        "Le serie horror usano il keyword TMDB canonico (315058): ${tmdb.discoverRequests}",
        tmdb.discoverRequests.any { it.contains("/discover/tv") && it.contains("with_keywords=315058") }
      )
      // TMDB TV non definisce il genere Horror: con with_genres=27 l'endpoint TV
      // risponderebbe sempre zero risultati (200 vuoto), avvolgendo il difetto.
      assertFalse(
        "Nessun with_genres=27 sull'endpoint TV: ${tmdb.discoverRequests}",
        tmdb.discoverRequests.any {
          it.contains("/discover/tv") && it.contains("with_genres=27")
        }
      )
      // Il fallback riguarda SOLO la combinazione scoperta: qui i film restano sul
      // catalogo addon e nessun discover/movie movie-genre viene raggiunto.
      assertFalse(
        "Nessun discover/movie dal fallback (film coperti dall'addon): ${tmdb.discoverRequests}",
        tmdb.discoverRequests.any { it.contains("/discover/movie") }
      )

      val titles = viewModel.categoryItems.value
        .filter { it.type == MediaType.SERIE_TV }
        .map { it.title }
      assertTrue("Serie TMDB mostrate: $titles", titles.contains("La Casa del Lago"))
      assertTrue("Serie TMDB mostrate: $titles", titles.contains("Sussurri"))

      // L'endpoint del tipo giusto: discover/tv con il keyword Horror.
      val tvRequest = tmdb.discoverRequests.firstOrNull { it.contains("/discover/tv") }
      assertTrue("Richiesta discover/tv assente: ${tmdb.discoverRequests}", tvRequest != null)
      assertTrue("Keyword Horror (315058) usato: $tvRequest", tvRequest!!.contains("with_keywords=315058"))
    } finally {
      addon.stop()
      tmdb.stop()
    }
  }

  // ── 2. Stessa ricerca con lo switch disattivato: nessuna chiamata TMDB ──────

  @Test
  fun switchDisattivatoNonChiamaTmdbEMostraStatoVuoto() = runTest(testDispatcher) {
    val addon = AddonFixture(Mode.XPERIENCE_LIKE)
    val tmdb = TmdbFixture()
    val baseUrl = addon.start()
    tmdb.start()
    try {
      AppSettingsRepository.setTmdbGenreFallbackEnabled(false)
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      assertTrue(
        "Nessuna richiesta TMDB con lo switch disattivato: ${tmdb.discoverRequests}",
        tmdb.discoverRequests.isEmpty()
      )
      assertEquals(
        GenreLoadReason.TMDB_DISABLED,
        loadRecord(viewModel, MediaType.SERIE_TV)?.reason
      )
      assertTrue(
        "Nessun titolo TMDB a schermo",
        viewModel.categoryItems.value.none { it.title == "La Casa del Lago" }
      )
      val state = viewModel.categoryState.value
      assertTrue("Lo stato dichiara lo switch disattivato", state.genresTmdbSkipped)
      assertFalse(state.statusMessage.isNullOrBlank())
    } finally {
      AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
      addon.stop()
      tmdb.stop()
    }
  }

  @Test
  fun ilFallbackDecidePerTipoFilmDaiCataloghiESerieDaTmdb() = runTest(testDispatcher) {
    // Servizio TMDB senza la fixture locale: qui conta il ROUTING, non il contenuto.
    // Addon con il catalogo film filtrabile (Horror-only) e nessun catalogo serie
    // filtrabile: i film devono restare dell'addon, le serie passano a TMDB.
    val addon = AddonFixture(Mode.XPERIENCE_LIKE)
    val baseUrl = addon.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      assertEquals(
        "Film: il catalogo compatibile resta la fonte",
        GenreLoadReason.SUPPORTED_BY_CATALOG,
        loadRecord(viewModel, MediaType.FILM)?.reason
      )
      assertEquals(
        "Serie TV: nessun catalogo compatibile → TMDB",
        GenreLoadReason.TMDB_FALLBACK,
        loadRecord(viewModel, MediaType.SERIE_TV)?.reason
      )
      assertTrue(
        "La richiesta TMDB è solo per le serie: ${StremioCatalogRepository.getAvailableTargets().map { it.catalogId }}",
        StremioCatalogRepository.hasUserCatalogCoveringGenre(category("horror").keywords, MediaType.FILM)
      )
      assertFalse(
        "Nessun catalogo utente copre Horror per le serie",
        StremioCatalogRepository.hasUserCatalogCoveringGenre(category("horror").keywords, MediaType.SERIE_TV)
      )
    } finally {
      addon.stop()
    }
  }

  // ── 3. Film → Horror: i cataloghi compatibili continuano a funzionare ───────

  @Test
  fun filmHorrorRestaSuiCataloghiCompatibili() = runTest(testDispatcher) {
    // Addon con i cataloghi film E serie che dichiarano Horror: la ricerca resta sugli
    // addon, TMDB non viene interrogato per nessuno dei due tipi.
    val addon = AddonFixture(Mode.BOTH_GENRE_CAPABLE)
    val tmdb = TmdbFixture()
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      assertEquals(
        "Il catalogo film compatibile resta la fonte",
        GenreLoadReason.SUPPORTED_BY_CATALOG,
        loadRecord(viewModel, MediaType.FILM)?.reason
      )
      assertEquals(
        "Anche le serie restano sull'addon compatibile",
        GenreLoadReason.SUPPORTED_BY_CATALOG,
        loadRecord(viewModel, MediaType.SERIE_TV)?.reason
      )
      assertTrue(
        "Nessuna richiesta TMDB: ${tmdb.discoverRequests}",
        tmdb.discoverRequests.isEmpty()
      )
      val titles = viewModel.categoryItems.value.map { it.title }
      assertTrue("Poster reali del catalogo film: $titles", titles.contains("La Casa dello Spavento"))
      assertTrue("Poster reali del catalogo serie: $titles", titles.contains("Residenza Maledetta"))
      assertEquals(SearchCategorySource.ADDON, viewModel.categoryState.value.source)
    } finally {
      addon.stop()
      tmdb.stop()
    }
  }

  // ── 4. Un altro addon copre il genere: il fallback non parte ────────────────

  @Test
  fun unAltroAddonCompatibileEvitaIlFallback() = runTest(testDispatcher) {
    val xperience = AddonFixture(Mode.XPERIENCE_LIKE)
    val genreAware = AddonFixture(Mode.SERIES_GENRE_CAPABLE)
    val tmdb = TmdbFixture()
    val xpUrl = xperience.start()
    val awareUrl = genreAware.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(xpUrl)
      // Il secondo addon dichiara `Horror` per le serie: il limite di Xperience non è
      // un limite globale, quindi TMDB non deve essere interrogato.
      val installed = StremioAddonRepository.installAddon(awareUrl)
      assertTrue("Secondo addon installato", installed != null)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      val series = loadRecord(viewModel, MediaType.SERIE_TV)
      assertEquals(
        "Un catalogo serie compatibile esiste: fonte addon",
        GenreLoadReason.SUPPORTED_BY_CATALOG,
        series?.reason
      )
      assertTrue(
        "Nessuna richiesta TMDB: ${tmdb.discoverRequests}",
        tmdb.discoverRequests.isEmpty()
      )
      val titles = viewModel.categoryItems.value
        .filter { it.type == MediaType.SERIE_TV }
        .map { it.title }
      assertTrue("Serie del catalogo compatibile: $titles", titles.contains("Residenza Maledetta"))
      assertFalse("Nessuna serie TMDB", titles.contains("La Casa del Lago"))
    } finally {
      addonCleanup(xperience, genreAware)
      tmdb.stop()
    }
  }
  // ── 5. Catalogo compatibile vuoto (HTTP 200 senza risultati): fallback TMDB ─

  @Test
  fun catalogoCompatibileVuotoAttivaIlFallbackTmdb() = runTest(testDispatcher) {
    // Film coperti con risultati, serie coperte ma con risposta VUOTA (HTTP 200 senza
    // metas): un catalogo vuoto non è né un errore né un'incompatibilità, ma non ci sono
    // risultati utili: il fallback TMDB con l'endpoint del tipo fornisce i titoli.
    val addon = AddonFixture(Mode.BOTH_GENRE_CAPABLE, seriesMode = CatalogMode.EMPTY)
    val tmdb = TmdbFixture()
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      val series = loadRecord(viewModel, MediaType.SERIE_TV)
      assertEquals(
        "Catalogo serie compatibile interrogato e vuoto: il fallback TMDB capita comunque",
        GenreLoadReason.TMDB_FALLBACK,
        series?.reason
      )
      assertEquals(
        "Il motivo registra che il catalogo era vuoto prima del fallback",
        true,
        series?.catalogWasEmpty
      )
      assertEquals(
        "Il fallback riguarda SOLO le serie: i film restano dell'addon",
        GenreLoadReason.SUPPORTED_BY_CATALOG,
        loadRecord(viewModel, MediaType.FILM)?.reason
      )
      assertTrue(
        "La richiesta al catalogo serie compatibile è avvenuta: ${addon.catalogRequests}",
        addon.catalogRequests.any { it.contains("discover_series") && it.contains("genre=Horror") }
      )
      assertTrue(
        "Il fallback usa l'endpoint serie con il keyword Horror: ${tmdb.discoverRequests}",
        tmdb.discoverRequests.any { it.contains("/discover/tv") && it.contains("with_keywords=315058") }
      )
      assertFalse(
        "Nessuna chiamata discover/movie: i film non sono serviti da TMDB: ${tmdb.discoverRequests}",
        tmdb.discoverRequests.any { it.contains("/discover/movie") }
      )
      assertTrue(
        "Le serie TMDB sono mostrate",
        viewModel.categoryItems.value.any { it.title == "La Casa del Lago" }
      )
      assertTrue(
        "Il fallback è dichiarato nella UI",
        viewModel.categoryState.value.statusMessage.orEmpty().isNotBlank()
      )
    } finally {
      addon.stop()
      tmdb.stop()
    }
  }

  // ── 5b. Catalogo vuoto e TMDB senza risultati: caso dichiarato, mai inventato ──

  @Test
  fun catalogoVuotoETmdbSenzaRisultatiDichiaraNessunTitolo() = runTest(testDispatcher) {
    val addon = AddonFixture(Mode.BOTH_GENRE_CAPABLE, seriesMode = CatalogMode.EMPTY)
    val tmdb = TmdbFixture(TmdbMode.EMPTY)
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      val series = loadRecord(viewModel, MediaType.SERIE_TV)
      assertEquals(
        "TMDB interrogato con successo ma senza risultati",
        GenreLoadReason.TMDB_FALLBACK,
        series?.reason
      )
      assertEquals(0, series?.tmdbItems)
      assertTrue(
        "Nessuna serie TMDB inventata",
        viewModel.categoryItems.value.none { it.type == MediaType.SERIE_TV }
      )
      assertTrue(
        "Lo stato dichiara il caso: ${viewModel.categoryState.value.statusMessage}",
        viewModel.categoryState.value.statusMessage.orEmpty().isNotBlank()
      )
      assertFalse(
        "Non è presentato come caricamento addon riuscito",
        viewModel.categoryItems.value.any { it.title == "Residenza Maledetta" }
      )
    } finally {
      addon.stop()
      tmdb.stop()
    }
  }

  // ── 6. Errore di rete del catalogo ≠ incompatibilità del genere ─────────────

  @Test
  fun erroreDiReteDelCatalogoNonAttivaIlFallback() = runTest(testDispatcher) {
    val addon = AddonFixture(
      Mode.BOTH_GENRE_CAPABLE,
      seriesMode = CatalogMode.ERROR,
      movieMode = CatalogMode.ERROR
    )
    val tmdb = TmdbFixture()
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      assertEquals(
        "Il catalogo serie compatibile è fallito: nessun fallback che maschera l'errore",
        GenreLoadReason.FAILED,
        loadRecord(viewModel, MediaType.SERIE_TV)?.reason
      )
      assertTrue(
        "Nessuna richiesta TMDB dopo un errore del catalogo: ${tmdb.discoverRequests}",
        tmdb.discoverRequests.isEmpty()
      )
      assertFalse(
        "Lo stato deve dichiarare l'errore del catalogo",
        viewModel.categoryState.value.statusMessage.isNullOrBlank()
      )
    } finally {
      addon.stop()
      tmdb.stop()
    }
  }

  // ── 7. Stesso titolo da catalogo e da TMDB: nessun duplicato ────────────────

  @Test
  fun loStessoTitoloDaCatalogoETmdbNonVieneDuplicato() = runTest(testDispatcher) {
    val addon = AddonFixture(Mode.XPERIENCE_LIKE)
    val tmdb = TmdbFixture()
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      // Film dal catalogo + serie da TMDB; "Ombra Condivisa" arriva da entrambe le fonti.
      val all = viewModel.categoryItems.value
      val duplicated = all.groupBy { "${it.type}|${it.title}|${it.year}" }.filterValues { it.size > 1 }
      assertTrue("Duplicati a schermo: $duplicated", duplicated.isEmpty())

      val shared = all.filter {
        it.type == MediaType.FILM && it.title.equals("Ombra Condivisa", ignoreCase = true)
      }
      assertEquals("Il titolo condiviso compare una sola volta", 1, shared.size)
      assertTrue("Serie TMDB presenti", all.any { it.title == "La Casa del Lago" })
    } finally {
      addon.stop()
      tmdb.stop()
    }
  }

  // ── 8. Lo switch aggiorna la categoria senza riavviare l'app ────────────────

  @Test
  fun ilCambioDelloSwitchRicalcolaLaCategoriaAttiva() = runTest(testDispatcher) {
    val addon = AddonFixture(Mode.XPERIENCE_LIKE)
    val tmdb = TmdbFixture()
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })
      assertTrue(
        "Prima (switch attivo): serie da TMDB",
        viewModel.categoryItems.value.any { it.title == "La Casa del Lago" }
      )

      // Switch OFF: la categoria aperta viene ricalcolata e TMDB non viene più usato.
      viewModel.setTmdbGenreFallbackEnabled(false)
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })
      assertEquals(
        GenreLoadReason.TMDB_DISABLED,
        loadRecord(viewModel, MediaType.SERIE_TV)?.reason
      )
      assertFalse(
        "Nessuna serie TMDB dopo la disattivazione",
        viewModel.categoryItems.value.any { it.title == "La Casa del Lago" }
      )
      val requestsAfterDisable = tmdb.discoverRequests.size

      // Switch ON: la categoria torna a interrogare TMDB.
      viewModel.setTmdbGenreFallbackEnabled(true)
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })
      assertEquals(
        GenreLoadReason.TMDB_FALLBACK,
        loadRecord(viewModel, MediaType.SERIE_TV)?.reason
      )
      assertTrue(
        "Nuova richiesta TMDB dopo la riattivazione",
        tmdb.discoverRequests.size > requestsAfterDisable
      )
      assertTrue(
        "Serie TMDB di nuovo visibili",
        viewModel.categoryItems.value.any { it.title == "La Casa del Lago" }
      )
    } finally {
      AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
      addon.stop()
      tmdb.stop()
    }
  }
  // ── 11. Paginazione: la pagina successiva usa lo stesso tipo e genere ───────

  @Test
  fun laPaginaSuccessivaTmdbRispettaTipoEGenere() = runTest(testDispatcher) {
    val addon = AddonFixture(Mode.XPERIENCE_LIKE, seriesMode = CatalogMode.EMPTY)
    val tmdb = TmdbFixture()
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })
      assertTrue(
        "Prima pagina TMDB delle serie",
        viewModel.categoryItems.value.any { it.title == "La Casa del Lago" }
      )

      viewModel.loadNextCategoryPage()
      assertTrue(
        "Seconda pagina TMDB caricata",
        awaitCondition { viewModel.categoryItems.value.any { it.title == "Pagina 2 Serie" } }
      )
      assertTrue(
        "page=2 richiesta su discover/tv: ${tmdb.discoverRequests}",
        tmdb.discoverRequests.any { it.contains("/discover/tv") && it.contains("page=2") }
      )
      // Nessun duplicato dopo l'append della pagina.
      val duplicated = viewModel.categoryItems.value
        .groupBy { "${it.type}|${it.title}" }
        .filterValues { it.size > 1 }
      assertTrue("Duplicati dopo la paginazione: $duplicated", duplicated.isEmpty())
    } finally {
      addon.stop()
      tmdb.stop()
    }
  }

  // ── 12. TMDB non raggiungibile: errore dichiarato, nessun titolo inventato ──

  @Test
  fun erroreTmdbNonProduceTitoliEDichiaraIlProblema() = runTest(testDispatcher) {
    val addon = AddonFixture(Mode.XPERIENCE_LIKE, seriesMode = CatalogMode.EMPTY)
    val tmdb = TmdbFixture(TmdbMode.ERROR)
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.openCategory("horror")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      assertEquals(
        "TMDB ha risposto con errore: il caso è dichiarato, non mascherato",
        GenreLoadReason.TMDB_UNAVAILABLE,
        loadRecord(viewModel, MediaType.SERIE_TV)?.reason
      )
      assertTrue(
        "Nessuna serie mostrata",
        viewModel.categoryItems.value.none { it.type == MediaType.SERIE_TV }
      )
      val message = viewModel.categoryState.value.statusMessage.orEmpty()
      assertTrue("Messaggio di errore TMDB mostrato: $message", message.contains("TMDB"))
    } finally {
      addon.stop()
      tmdb.stop()
    }
  }

  // ── 13. Cambio rapido di categoria: nessun risultato obsoleto ───────────────

  @Test
  fun ilCambioRapidoDiCategoriaNonMostraRisultatiObsoleti() = runTest(testDispatcher) {
    // Entrambe le categorie sono coperte dai cataloghi addon (film) e, per le serie, non
    // coperte: TMDB non viene coinvolto, quindi ciò che resta a schermo può venire SOLO
    // dalla categoria corrente.
    val addon = AddonFixture(Mode.XPERIENCE_LIKE)
    val tmdb = TmdbFixture()
    val baseUrl = addon.start()
    tmdb.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      // Due categorie aperte in sequenza senza attendere la prima: le risposte della
      // categoria abbandonata non devono arrivare a schermo.
      viewModel.openCategory("horror")
      viewModel.openCategory("fantascienza")
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      val titles = viewModel.categoryItems.value.map { it.title }
      assertFalse(
        "Nessun titolo Horror della categoria abbandonata: $titles",
        titles.contains("La Casa dello Spavento") || titles.contains("Ombra Condivisa")
      )
      assertTrue("Contenuti della categoria corrente: $titles", titles.contains("Orbite Lontane"))
      assertEquals(
        "Film della categoria corrente serviti dal catalogo compatibile",
        GenreLoadReason.SUPPORTED_BY_CATALOG,
        loadRecord(viewModel, MediaType.FILM)?.reason
      )
      assertEquals(
        "Le serie della categoria corrente sono quelle richieste a TMDB (genere corrente)",
        GenreLoadReason.TMDB_FALLBACK,
        loadRecord(viewModel, MediaType.SERIE_TV)?.reason
      )
      assertFalse(
        "Nessuna richiesta TMDB per il genere abbandonato: ${tmdb.discoverRequests}",
        tmdb.discoverRequests.any { it.contains("with_genres=27") || it.contains("with_keywords=315058") }
      )
    } finally {
      addon.stop()
      tmdb.stop()
    }
  }



  // ── Helper ───────────────────────────────────────────────────────────────────


  /** Ferma gli addon di test e svuota le cache dei cataloghi. */
  private fun addonCleanup(vararg fixtures: AddonFixture) {
    StremioCatalogRepository.clearCache()
    fixtures.forEach { it.stop() }
  }


  // ── 9. Regola di copertura per TIPO (indipendente dal ViewModel) ────────────

  @Test
  fun soloUnCatalogoDelTipoRichiestoConFiltroGenereCopreLaRicerca() = runTest(testDispatcher) {
    val addon = AddonFixture(Mode.XPERIENCE_LIKE)
    val baseUrl = addon.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val horror = category("horror").keywords
      val targets = StremioCatalogRepository.getAvailableTargets()

      assertFalse(
        "Nessun catalogo dell'utente copre Horror per le serie: ${targets.map { it.catalogId }}",
        StremioCatalogRepository.hasUserCatalogCoveringGenre(horror, MediaType.SERIE_TV)
      )
      assertTrue(
        "I cataloghi dell'utente coprono Horror per i film",
        StremioCatalogRepository.hasUserCatalogCoveringGenre(horror, MediaType.FILM)
      )
      // `isSeries` vale "non è un film": un catalogo film non deve MAI contare come
      // copertura delle serie.
      val movieOnly = targets.filter { it.isMovie }
      assertFalse(
        "Un catalogo film non copre le serie",
        StremioCatalogRepository.hasConfiguredCatalogCoveringGenre(
          horror,
          MediaType.SERIE_TV,
          movieOnly
        )
      )
      assertTrue(
        "Lo stesso catalogo copre i film",
        StremioCatalogRepository.hasConfiguredCatalogCoveringGenre(
          horror,
          MediaType.FILM,
          movieOnly
        )
      )
    } finally {
      addonCleanup(addon)
    }
  }

  @Test
  fun senzaAddonDellUtenteCinemetaNonBloccaIlFallback() = runTest(testDispatcher) {
    StremioAddonRepository.clearCaches()
    StremioCatalogRepository.clearCache()
    val horror = category("horror").keywords

    // Cinemeta (catalogo di sistema) dichiara `genre` per entrambi i tipi: è la fonte di
    // default delle categorie, ma non è un catalogo dell'utente e non deve impedire il
    // fallback TMDB quando nessun addon dell'utente copre la combinazione.
    assertFalse(
      "Nessun addon utente installato",
      StremioCatalogRepository.hasUserCatalogCoveringGenre(horror, MediaType.SERIE_TV)
    )
    assertTrue(
      "Cinemeta resta un catalogo configurato e interrogabile",
      StremioCatalogRepository.hasConfiguredCatalogCoveringGenre(
        horror,
        MediaType.SERIE_TV,
        StremioCatalogRepository.defaultCinemetaGenreTargets(MediaType.SERIE_TV)
      )
    )
  }

  private fun createViewModel(): StreamNovaViewModel =
    StreamNovaViewModel().also {
      it.attachContext(ApplicationProvider.getApplicationContext())
      createdViewModels += it
    }

  private fun category(id: String) = GLOBAL_SEARCH_CATEGORIES.first { it.id == id }

  /** Apre una categoria passando le stesse keyword usate dalla UI. */
  private fun StreamNovaViewModel.openCategory(id: String) {
    val cat = category(id)
    initCategory(cat.id, cat.keywords, cat.movieGenreId, cat.tvGenreId)
  }

  /** Attende un evento reale (rete/thread di IO) facendo avanzare il dispatcher di test. */
  private fun awaitCondition(timeoutMs: Long = 30_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      testDispatcher.scheduler.advanceUntilIdle()
      if (condition()) return true
      if (System.currentTimeMillis() >= deadline) return false
      Thread.sleep(50)
    }
  }

  private fun loadRecord(viewModel: StreamNovaViewModel, type: MediaType): GenreLoadRecord? =
    viewModel.lastGenreLoads.value.firstOrNull { it.mediaType == type }

  /** true se tra le richieste TMDB ce n'è una sull'endpoint `discover` del tipo. */
  private fun List<String>.hasDiscover(type: String): Boolean =
    any { it.contains("/discover/$type") }

  companion object {
    /** Manifest con la struttura di Xperience: nessun catalogo serie filtrabile per genere. */
    private val XPERIENCE_MANIFEST = """
      {
        "id": "app.xperience.fallback-fixture",
        "name": "Xperience",
        "version": "1.0.0",
        "resources": ["catalog"],
        "types": ["movie", "series"],
        "idPrefixes": ["tt"],
        "catalogs": [
          {"type":"movie","id":"genre_horror_movies","name":"Horror Movies","extra":[{"name":"skip"}]},
          {"type":"movie","id":"genre_fantascienza_movies","name":"Sci-Fi Movies","extra":[{"name":"skip"}]},
          {"type":"movie","id":"discover_movies","name":"Movies","extra":[
            {"name":"genre","options":["Horror","Fantascienza"]},{"name":"skip"}]},
          {"type":"series","id":"popolari_series","name":"Popolari","extra":[{"name":"skip"}]},
          {"type":"series","id":"novita_series","name":"Novità","extra":[{"name":"skip"}]}
        ]
      }
    """.trimIndent()

    /** Secondo addon: dichiara il genere Horror per le serie. */
    private val SERIES_GENRE_MANIFEST = """
      {
        "id": "app.fixture.genre-aware",
        "name": "GenreAware",
        "version": "1.0.0",
        "resources": ["catalog"],
        "types": ["series"],
        "idPrefixes": ["tt"],
        "catalogs": [
          {"type":"series","id":"discover_series","name":"Series","extra":[
            {"name":"genre","options":["Horror"]},{"name":"skip"}]}
        ]
      }
    """.trimIndent()

    /** Addon che dichiara il genere Horror per film E serie: copertura completa. */
    private val BOTH_GENRE_MANIFEST = """
      {
        "id": "app.fixture.genre-both",
        "name": "GenreBoth",
        "version": "1.0.0",
        "resources": ["catalog"],
        "types": ["movie", "series"],
        "idPrefixes": ["tt"],
        "catalogs": [
          {"type":"movie","id":"discover_movies","name":"Movies","extra":[
            {"name":"genre","options":["Horror"]},{"name":"skip"}]},
          {"type":"series","id":"discover_series","name":"Series","extra":[
            {"name":"genre","options":["Horror"]},{"name":"skip"}]}
        ]
      }
    """.trimIndent()

    /** Film del catalogo Horror dell'addon. */
    private val HORROR_MOVIES = """
      {"metas":[
        {"id":"tt9000001","name":"La Casa dello Spavento","poster":"https://img.example/tt9000001.jpg","releaseInfo":"2021","type":"movie"},
        {"id":"tt9099999","name":"Ombra Condivisa","poster":"https://img.example/tt9099999.jpg","releaseInfo":"2022","type":"movie"}
      ]}
    """.trimIndent()

    /** Film del catalogo Fantascienza: nessun titolo in comune con Horror. */
    private val SCI_FI_MOVIES = """
      {"metas":[
        {"id":"tt9100002","name":"Orbite Lontane","poster":"https://img.example/tt9100002.jpg","releaseInfo":"2023","type":"movie"}
      ]}
    """.trimIndent()

    private val SERIES_BODY = """
      {"metas":[
        {"id":"tt9000003","name":"Residenza Maledetta","poster":"https://img.example/tt9000003.jpg","releaseInfo":"2020","type":"series"}
      ]}
    """.trimIndent()
  }
}
