package com.example.ui.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaType
import com.example.data.repository.StremioCatalogRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.ui.screens.GLOBAL_SEARCH_CATEGORIES
import com.example.ui.screens.GlobalSearchCategory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
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
 * Test della sezione Ricerca basati sul MANIFEST XPERIENCE REALE
 * (snapshot `src/test/resources/xperience_manifest_real.json`, 45 cataloghi) e su
 * risposte REALI dei cataloghi `discover_all_movies` / `discover_all_series`
 * (snapshot delle prime voci, `xperience_*_discover_*.json`).
 *
 * Il manifest reale dichiara:
 *  - `discover_all_movies` (type `movie`) con `genre` OBBLIGATORIO e generi FILM
 *    (`Action`, `Adventure`, `Horror`, `Music`, `Science Fiction`, `Thriller`, ...);
 *  - `discover_all_series` (type `series`) con `genre` OBBLIGATORIO e generi SERIE
 *    DIVERSI (`Action & Adventure`, `Sci-Fi & Fantasy`, `Mystery`, `Kids`, ...).
 *
 * Quindi il valore di `genre` va scelto tra le opzioni realmente dichiarate per il
 * tipo giusto: mai un id numerico, mai un genere non dichiarato (es. `Music` o
 * `Thriller` sul catalogo serie). Le asserzioni confrontano sempre il valore usato
 * con le opzioni dello snapshot: se il manifest cambiasse, il test fallirebbe invece
 * di mentire.
 *
 * Dove il valore dipende dal nome canonico TMDB del genere (es. `9648 -> Mystery`
 * per le serie, aggiunto alla mappa TV) il test passa dal ViewModel (`initCategory`),
 * così verifica la MAPPATURA reale dell'app e non una keyword scritta a mano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SearchCategoryXperienceRealManifestTest {

  private lateinit var testDispatcher: TestDispatcher
  private val createdViewModels = mutableListOf<StreamNovaViewModel>()

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
  }

  private fun createViewModel(): StreamNovaViewModel =
    StreamNovaViewModel().also { createdViewModels += it }

  // ── Server che espone il manifest reale e le risposte reali ─────────────────

  private class RealManifestFixture(private val routes: Map<String, String>) {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl = "http://127.0.0.1:${server.address.port}"

    fun start(): String {
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      return baseUrl
    }

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.rawPath
      requested += path
      // I cataloghi non dedicati rispondono VUOTO (non errore): il caricamento resta
      // "riuscito" e i risultati sono esattamente quelli delle risposte reali servite.
      val body = if (path == "/manifest.json") MANIFEST_JSON else routes[path] ?: """{"metas":[]}"""
      try {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      } finally {
        exchange.close()
      }
    }
  }

  private fun awaitCondition(timeoutMs: Long = 30_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      testDispatcher.scheduler.advanceUntilIdle()
      if (condition()) return true
      if (System.currentTimeMillis() >= deadline) return false
      Thread.sleep(50)
    }
  }

  private fun cleanup(fixture: RealManifestFixture) {
    StremioCatalogRepository.clearCache()
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    fixture.stop()
  }

  private fun category(id: String): GlobalSearchCategory =
    GLOBAL_SEARCH_CATEGORIES.first { it.id == id }

  /** Stesse keyword che [StreamNovaViewModel.initCategory] costruisce (etichette + nomi TMDB). */
  private fun categoryKeywords(cat: GlobalSearchCategory): List<String> =
    cat.keywords +
      listOfNotNull(cat.movieGenreId?.let { TMDB_MOVIE_GENRE_NAMES[it] }) +
      listOfNotNull(cat.tvGenreId?.let { TMDB_TV_GENRE_NAMES[it] })

  /** Generi dichiarati dal manifest reale per l'extra `genre` di un catalogo. */
  private fun declaredGenreOptions(type: String, catalogId: String): List<String> {
    val catalogs = JSONObject(MANIFEST_JSON).getJSONArray("catalogs")
    for (i in 0 until catalogs.length()) {
      val catalog = catalogs.getJSONObject(i)
      if (catalog.getString("type") != type || catalog.getString("id") != catalogId) continue
      val extra = catalog.optJSONArray("extra") ?: return emptyList()
      for (j in 0 until extra.length()) {
        val entry = extra.getJSONObject(j)
        if (entry.optString("name") == "genre") {
          val options = entry.optJSONArray("options") ?: return emptyList()
          return (0 until options.length()).map { options.getString(it) }
        }
      }
    }
    return emptyList()
  }

  private fun extraOf(
    requests: List<StremioCatalogRepository.CategoryCatalogRequest>,
    catalogId: String
  ): Map<String, String>? = requests.firstOrNull { it.target.catalogId == catalogId }?.extra

  companion object {
    private val MANIFEST_JSON: String by lazy { resource("xperience_manifest_real.json") }
    private val MOVIE_THRILLER: String by lazy { resource("xperience_movie_discover_thriller.json") }
    private val MOVIE_ACTION: String by lazy { resource("xperience_movie_discover_action.json") }
    private val MOVIE_MUSIC: String by lazy { resource("xperience_movie_discover_music.json") }
    private val SERIES_MYSTERY: String by lazy { resource("xperience_series_discover_mystery.json") }
    private val SERIES_ACTION_ADVENTURE: String by lazy {
      resource("xperience_series_discover_action_adventure.json")
    }

    /** Snapshot serie SENZA campo `type` nei metas: il tipo deve venire dal catalogo. */
    private val SERIES_MYSTERY_WITHOUT_TYPE: String by lazy {
      JSONObject(SERIES_MYSTERY).let { json ->
        val metas = json.getJSONArray("metas")
        for (i in 0 until metas.length()) metas.getJSONObject(i).remove("type")
        json.toString()
      }
    }

    private fun resource(name: String): String =
      SearchCategoryXperienceRealManifestTest::class.java.classLoader!!
        .getResourceAsStream(name)!!
        .bufferedReader()
        .use { it.readText() }

    /** Gli extra viaggiano nel PATH, con il valore URL-encoded (come `buildCatalogPath`). */
    private fun catalogRoute(type: String, id: String, extra: Map<String, String>): String {
      val extraSegment = extra.entries.joinToString("&") { (k, v) ->
        val encoded = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        "$k=$encoded"
      }
      return "/catalog/$type/$id/$extraSegment.json"
    }
  }

  // ── 1. Categoria con generi film E serie compatibili (Action & Adventure) ───

  @Test
  fun actionAdventureRichiedeEntrambiIDiscoverConIlGenereDichiarato(): Unit =
    runTest(testDispatcher) {
      val moviePath = catalogRoute("movie", "discover_all_movies", mapOf("genre" to "Action"))
      val seriesPath =
        catalogRoute("series", "discover_all_series", mapOf("genre" to "Action & Adventure"))
      val fixture =
        RealManifestFixture(mapOf(moviePath to MOVIE_ACTION, seriesPath to SERIES_ACTION_ADVENTURE))
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)
        val cat = category("action_adventure")

        val requests = StremioCatalogRepository.findCategoryRequests(cat.keywords)
        val movieExtra = extraOf(requests, "discover_all_movies")
        val seriesExtra = extraOf(requests, "discover_all_series")
        assertNotNull("discover_all_movies non richiesto", movieExtra)
        assertNotNull("discover_all_series non richiesto", seriesExtra)
        assertEquals("Action", movieExtra!!["genre"])
        assertEquals("Action & Adventure", seriesExtra!!["genre"])

        // Coerenza con il manifest reale: le opzioni usate sono davvero dichiarate.
        assertTrue(
          "Action non dichiarato dal manifest film",
          declaredGenreOptions("movie", "discover_all_movies").contains("Action")
        )
        assertTrue(
          "Action & Adventure non dichiarato dal manifest serie",
          declaredGenreOptions("series", "discover_all_series").contains("Action & Adventure")
        )

        val load = StremioCatalogRepository.loadCategoryCatalogs(cat.keywords)
        assertTrue("Nessun errore atteso: ${load.errors}", load.errors.isEmpty())
        val movie = load.items.firstOrNull { it.title == "Spider-Man: Brand New Day" }
        val series = load.items.firstOrNull { it.title == "Reacher" }
        assertNotNull("Poster film reali assenti: ${load.items.map { it.title }}", movie)
        assertNotNull("Poster serie reali assenti: ${load.items.map { it.title }}", series)
        assertEquals(MediaType.FILM, movie!!.type)
        assertEquals(MediaType.SERIE_TV, series!!.type)
      } finally {
        cleanup(fixture)
      }
    }

  // ── 2. Thriller: genere FILM e genere SERIE separati ───────────────────────

  @Test
  fun thrillerUsaThrillerPerIFilmEMysteryPerLeSerie(): Unit = runTest(testDispatcher) {
    val moviePath = catalogRoute("movie", "discover_all_movies", mapOf("genre" to "Thriller"))
    val seriesPath = catalogRoute("series", "discover_all_series", mapOf("genre" to "Mystery"))
    val fixture = RealManifestFixture(mapOf(moviePath to MOVIE_THRILLER, seriesPath to SERIES_MYSTERY))
    val baseUrl = fixture.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val cat = category("thriller")
      val viewModel = createViewModel()

      viewModel.initCategory(cat.id, cat.keywords, cat.movieGenreId, cat.tvGenreId)
      assertTrue(
        "La categoria deve caricare film e serie",
        awaitCondition { viewModel.categoryItems.value.size >= 2 }
      )
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      // Mappatura REALE: il ViewModel passa Thriller ai film e Mystery alle serie
      // (tvGenreId 9648 -> nome canonico TMDB "Mystery").
      assertTrue(
        "Richiesta film con genre=Thriller mancante: ${fixture.requested}",
        fixture.requested.contains(moviePath)
      )
      assertTrue(
        "Richiesta serie con genre=Mystery mancante: ${fixture.requested}",
        fixture.requested.contains(seriesPath)
      )
      // "Thriller" NON è un genere serie: nessuna richiesta serie può usarlo.
      assertTrue(
        "Nessuna richiesta serie deve usare genre=Thriller: ${fixture.requested}",
        fixture.requested.none { it.startsWith("/catalog/series/") && it.contains("genre=Thriller") }
      )
      val seriesOptions = declaredGenreOptions("series", "discover_all_series")
      assertTrue("Mystery deve essere dichiarato dal manifest serie", seriesOptions.contains("Mystery"))
      assertFalse("Thriller non deve essere tra i generi serie", seriesOptions.contains("Thriller"))

      val items = viewModel.categoryItems.value
      val titles = items.map { "${it.type}:${it.title}" }
      assertEquals(
        "Film thriller reale assente: $titles",
        MediaType.FILM,
        items.firstOrNull { it.title == "Fall 2: Deadpoint" }?.type
      )
      assertEquals(
        "Serie Mystery reale assente: $titles",
        MediaType.SERIE_TV,
        items.firstOrNull { it.title == "The Mentalist" }?.type
      )
    } finally {
      cleanup(fixture)
    }
  }

  // ── 3. Musica: solo film, NESSUNA serie con genere non dichiarato ──────────

  @Test
  fun musicaNonRichiedeNessunCatalogoSerie(): Unit = runTest(testDispatcher) {
    val moviePath = catalogRoute("movie", "discover_all_movies", mapOf("genre" to "Music"))
    val fixture = RealManifestFixture(mapOf(moviePath to MOVIE_MUSIC))
    val baseUrl = fixture.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val cat = category("musica")

      val requests = StremioCatalogRepository.findCategoryRequests(cat.keywords)
      assertEquals("Music", extraOf(requests, "discover_all_movies")!!["genre"])
      assertTrue("Music NON è un genere serie: $requests", requests.none { it.target.isSeries })
      assertFalse(
        "Music non deve essere tra i generi serie",
        declaredGenreOptions("series", "discover_all_series").contains("Music")
      )

      fixture.requested.clear()
      val load = StremioCatalogRepository.loadCategoryCatalogs(cat.keywords)
      assertEquals(MediaType.FILM, load.items.first { it.title == "Whiplash" }.type)
      assertTrue(
        "Nessuna richiesta a un catalogo serie: ${fixture.requested}",
        fixture.requested.none { it.startsWith("/catalog/series/") }
      )
    } finally {
      cleanup(fixture)
    }
  }

  // ── 4. Merge, dedup (tipo+id), tipo ereditato, cambio categoria ────────────

  @Test
  fun mergeDedupTipoEreditatoECambioCategoria(): Unit = runTest(testDispatcher) {
    // Lo stesso titolo reale "The Fix" (tt9601292) compare sia nella lista Thriller
    // sia in quella Action: servendolo da due cataloghi della stessa categoria si
    // verifica che il merge NON duplichi.
    val thrillerPath = catalogRoute("movie", "discover_all_movies", mapOf("genre" to "Thriller"))
    val otherThrillerPath = catalogRoute("movie", "genre_action_movies", mapOf("genre" to "Thriller"))
    val seriesPath = catalogRoute("series", "discover_all_series", mapOf("genre" to "Mystery"))
    val musicPath = catalogRoute("movie", "discover_all_movies", mapOf("genre" to "Music"))
    val fixture = RealManifestFixture(
      mapOf(
        thrillerPath to MOVIE_THRILLER,
        otherThrillerPath to MOVIE_THRILLER,
        // La serie viene servita SENZA `type` nei metas: il tipo deve restare SERIE_TV.
        seriesPath to SERIES_MYSTERY_WITHOUT_TYPE,
        musicPath to MOVIE_MUSIC
      )
    )
    val baseUrl = fixture.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val thriller = category("thriller")
      val musica = category("musica")
      val viewModel = createViewModel()

      viewModel.initCategory(thriller.id, thriller.keywords, thriller.movieGenreId, thriller.tvGenreId)
      assertTrue(awaitCondition { viewModel.categoryItems.value.size >= 2 })
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      val items = viewModel.categoryItems.value
      val theFix = items.filter { it.title == "The Fix" }
      assertEquals("Il titolo servito da due cataloghi deve comparire una sola volta", 1, theFix.size)
      assertEquals(MediaType.FILM, theFix.first().type)
      assertEquals(
        "I metas serie senza `type` ereditano il tipo del catalogo",
        MediaType.SERIE_TV,
        items.first { it.title == "The Mentalist" }.type
      )
      val thrillerTitles = items.map { it.title }.toSet()

      viewModel.initCategory(musica.id, musica.keywords, musica.movieGenreId, musica.tvGenreId)
      assertTrue("I risultati precedenti spariscono subito", viewModel.categoryItems.value.isEmpty())
      assertTrue(awaitCondition { viewModel.categoryItems.value.isNotEmpty() })
      val musicTitles = viewModel.categoryItems.value.map { it.title }
      assertTrue("Film musica attesi: $musicTitles", musicTitles.contains("Whiplash"))
      assertFalse(
        "Nessun titolo della categoria precedente riutilizzato: $musicTitles",
        musicTitles.any { thrillerTitles.contains(it) }
      )
    } finally {
      cleanup(fixture)
    }
  }

  // ── 5. Serie non supportata (genere assente) ≠ serie presente ma vuota ─────

  @Test
  fun serieNonSupportataDistintaDaSeriePresenteMaVuota(): Unit = runTest(testDispatcher) {
    val actionMoviePath = catalogRoute("movie", "discover_all_movies", mapOf("genre" to "Action"))
    val actionSeriesPath =
      catalogRoute("series", "discover_all_series", mapOf("genre" to "Action & Adventure"))
    // Azione: film reali NON vuoti, serie dichiarata ma con risposta VUOTA.
    val fixture = RealManifestFixture(
      mapOf(actionMoviePath to MOVIE_ACTION, actionSeriesPath to """{"metas":[]}""")
    )
    val baseUrl = fixture.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)

      // (a) HORROR: il manifest serie NON dichiara "Horror" -> categoria non supportata.
      assertFalse(
        "Horror non deve essere un genere serie del manifest",
        declaredGenreOptions("series", "discover_all_series").contains("Horror")
      )
      val horror = category("horror")
      assertFalse(
        "Horror non deve richiedere un catalogo serie",
        StremioCatalogRepository.findCategoryRequests(categoryKeywords(horror))
          .any { it.target.isSeries }
      )
      val vm = createViewModel()
      vm.initCategory(horror.id, horror.keywords, horror.movieGenreId, horror.tvGenreId)
      assertTrue(awaitCondition { vm.categoryState.value.source != null })
      assertTrue(awaitCondition { !vm.isCategoryLoading.value })
      assertTrue(
        "Genere serie non dichiarato: ${vm.categoryState.value.statusMessage}",
        vm.categoryState.value.statusMessage.orEmpty()
          .contains("Categoria non supportata per le serie")
      )

      // (b) AZIONE: catalogo serie DICHIARATO ma con zero risultati (≠ non supportata).
      val action = category("action_adventure")
      vm.initCategory(action.id, action.keywords, action.movieGenreId, action.tvGenreId)
      assertTrue(awaitCondition { vm.categoryItems.value.isNotEmpty() })
      assertTrue(awaitCondition { !vm.isCategoryLoading.value })
      val message = vm.categoryState.value.statusMessage.orEmpty()
      assertTrue(
        "Catalogo serie presente ma vuoto deve essere dichiarato: $message",
        message.contains("interrogato ma senza risultati")
      )
      assertFalse(
        "Non è il caso 'categoria non supportata': $message",
        message.contains("Categoria non supportata per le serie")
      )
    } finally {
      cleanup(fixture)
    }
  }

  // ── 6. Report di TUTTE le categorie sul manifest reale ─────────────────────

  @Test
  fun reportPerTutteLeCategorieUsaSoloGeneriDichiarati(): Unit = runTest(testDispatcher) {
    val movieOptions = declaredGenreOptions("movie", "discover_all_movies")
    val seriesOptions = declaredGenreOptions("series", "discover_all_series")
    assertTrue("Thriller deve essere un genere FILM", movieOptions.contains("Thriller"))
    assertTrue("Mystery deve essere un genere SERIE", seriesOptions.contains("Mystery"))
    assertFalse("Thriller NON è un genere serie", seriesOptions.contains("Thriller"))

    val fixture = RealManifestFixture(emptyMap())
    val baseUrl = fixture.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val seriesUnsupported = mutableListOf<String>()
      GLOBAL_SEARCH_CATEGORIES.forEach { cat ->
        val requests = StremioCatalogRepository.findCategoryRequests(categoryKeywords(cat))
        val movie = extraOf(requests, "discover_all_movies")?.get("genre")
        val series = extraOf(requests, "discover_all_series")?.get("genre")
        println("XPRREPORT ${cat.id} => movie=${movie ?: "NONE"} series=${series ?: "NONE"}")
        if (movie != null) {
          assertTrue("[${cat.id}] genere FILM non dichiarato: $movie", movieOptions.contains(movie))
        }
        if (series != null) {
          assertTrue("[${cat.id}] genere SERIE non dichiarato: $series", seriesOptions.contains(series))
        }
        if (cat.tvGenreId != null && series == null) seriesUnsupported += cat.id
      }
      println("XPRREPORT categorie senza serie supportata: $seriesUnsupported")
      assertTrue("Horror non ha equivalente serie nel manifest", seriesUnsupported.contains("horror"))
      assertFalse("Thriller ha equivalente serie (Mystery)", seriesUnsupported.contains("thriller"))
    } finally {
      cleanup(fixture)
    }
  }
}
