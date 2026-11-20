package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.api.TmdbApiClient
import com.example.data.model.MediaType
import com.example.data.model.SearchCategoryFilter
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
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
 * Categoria "TV Show" della sezione Ricerca.
 *
 * La categoria è servita NATIVAMENTE da TMDB sul catalogo degli show TV
 * (`discover/tv`): gli show ITALIANI in testa (`with_original_language=it`) e poi
 * quelli delle altre lingue, recenti e meno recenti, ordinati per popolarità. Non
 * deve dipendere da addon Stremio, modalità Debrid o sorgenti di riproduzione.
 * I test verificano che:
 *  - le DUE query della categoria siano `discover/tv` (italiani + generale) con
 *    `sort_by=popularity.desc`;
 *  - gli show italiani compaiano PRIMA e un show italiano già in testa non sia
 *    duplicato dalla lista generale;
 *  - la lista contenga titoli RECENTI e meno recenti;
 *  - i contenuti di scarto (podcast) restino filtrati;
 *  - la categoria funzioni senza addon installati e con Debrid disattivato;
 *  - la paginazione avanzi su ENTRAMBE le query e deduplichi i titoli già mostrati;
 *  - loading, errore TMDB e stato vuoto siano dichiarati.
 *
 * Determinismo: TMDB è servito da un server HTTP locale
 * ([TmdbApiClient.overrideServiceBaseUrl]); nessuna rete e nessuna credenziale reale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SearchCategoryTvShowTest {

  private lateinit var testDispatcher: TestDispatcher
  private val createdViewModels = mutableListOf<StreamNovaViewModel>()

  @Before
  fun setUp() {
    testDispatcher = StandardTestDispatcher()
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    AppSettingsRepository.setAppLanguage("it")
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
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
    AppSettingsRepository.setAppLanguage("it")
    Dispatchers.resetMain()
  }

  private enum class TmdbMode { OK, ERROR, EMPTY }

  /**
   * Server TMDB locale che distingue le DUE query della categoria:
   *  - `with_original_language=it` → elenco degli show TV italiani;
   *  - senza filtro di lingua → elenco generale (tutte le lingue).
   *
   * La lista generale contiene un DUPLICATO italiano ("Don Matteo", già in testa
   * dalla lista italiana: la dedup non deve mostrare due card), un titolo con
   * "Podcast" nel nome (contenuto di scarto: deve restare filtrato) e titoli
   * RECENTI (2024/2026) e MENO recenti (2000/2008). La pagina 2 di entrambe le
   * liste porta titoli nuovi.
   */
  private inner class TmdbFixture(private val mode: TmdbMode = TmdbMode.OK) {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start() {
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      TmdbApiClient.overrideServiceBaseUrl("http://127.0.0.1:${server.address.port}/3/")
    }

    fun stop() {
      TmdbApiClient.overrideServiceBaseUrl(null)
      server.stop(0)
    }

    /**
     * Richieste DELLA CATEGORIA: le sole `discover/tv` per popolarità senza alcun
     * filtro di genere/provider/anno (il traffico TMDB della Home in sfondo usa
     * quelli e non è traffico della categoria).
     */
    val categoryRequests: List<String>
      get() = synchronized(requested) {
        requested.filter { request ->
          request.contains("/discover/tv") &&
            request.contains("sort_by=popularity.desc") &&
            !request.contains("with_genres=") &&
            !request.contains("with_keywords=") &&
            !request.contains("first_air_date_year=") &&
            !request.contains("with_watch_providers=") &&
            !request.contains("with_networks=")
        }
      }

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.rawPath
      val query = exchange.requestURI.rawQuery.orEmpty()
      requested += "$path?$query"
      try {
        when (mode) {
          TmdbMode.ERROR -> respond(exchange, 500, """{"status_message":"tmdb down"}""")
          TmdbMode.EMPTY -> respond(exchange, 200, emptyBody())
          TmdbMode.OK -> when {
            path.endsWith("/discover/tv") -> {
              val page = Regex("page=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 1
              val body = if (query.contains("with_original_language=it")) {
                italianBody(page)
              } else {
                generalBody(page)
              }
              respond(exchange, 200, body)
            }
            else -> respond(exchange, 404, """{"status_message":"not found"}""")
          }
        }
      } finally {
        exchange.close()
      }
    }

    private fun emptyBody(): String =
      """{"page":1,"results":[],"total_pages":1,"total_results":0}"""

    /** Show TV italiani: pagina 1 con un titolo RECENTE e uno MENO recente. */
    private fun italianBody(page: Int): String = if (page > 1) """
      {"page":$page,"results":[
        {"id":910003,"name":"Battute Italiane","original_name":"Battute Italiane",
         "first_air_date":"2023-03-06","overview":"Show italiano di comicità","poster_path":"/i3.jpg",
         "vote_average":7.1,"genre_ids":[35],"original_language":"it"}
      ],"total_pages":3,"total_results":5}
    """.trimIndent() else """
      {"page":1,"results":[
        {"id":910001,"name":"Il Nuovo Show Italiano","original_name":"Il Nuovo Show Italiano",
         "first_air_date":"2026-10-01","overview":"Show italiano appena lanciato","poster_path":"/i1.jpg",
         "vote_average":7.4,"genre_ids":[35],"original_language":"it"},
        {"id":910002,"name":"Don Matteo","original_name":"Don Matteo",
         "first_air_date":"2000-01-01","overview":"Storico show televisivo italiano","poster_path":"/i2.jpg",
         "vote_average":6.9,"genre_ids":[18],"original_language":"it"}
      ],"total_pages":3,"total_results":5}
    """.trimIndent()

    /** Elenco generale: tutte le lingue, con duplicato italiano e scarto podcast. */
    private fun generalBody(page: Int): String = if (page > 1) """
      {"page":$page,"results":[
        {"id":910007,"name":"Nova Serie Francese","original_name":"Nova Série Française",
         "first_air_date":"2021-05-05","overview":"Serie francese recente","poster_path":"/g2.jpg",
         "vote_average":7.2,"genre_ids":[18],"original_language":"fr"},
        {"id":910008,"name":"The Late Night Show USA","original_name":"The Late Night Show USA",
         "first_air_date":"2019-09-10","overview":"Late night americano","poster_path":"/g3.jpg",
         "vote_average":7.9,"genre_ids":[10767],"original_language":"en"}
      ],"total_pages":3,"total_results":9}
    """.trimIndent() else """
      {"page":1,"results":[
        {"id":910002,"name":"Don Matteo","original_name":"Don Matteo",
         "first_air_date":"2000-01-01","overview":"Storico show televisivo italiano","poster_path":"/i2.jpg",
         "vote_average":6.9,"genre_ids":[18],"original_language":"it"},
        {"id":910004,"name":"The Tonight Show USA","original_name":"The Tonight Show USA",
         "first_air_date":"2024-09-09","overview":"Late night americano","poster_path":"/g1.jpg",
         "vote_average":7.9,"genre_ids":[10767],"original_language":"en"},
        {"id":910005,"name":"Breaking Bad","original_name":"Breaking Bad",
         "first_air_date":"2008-01-20","overview":"Serie americana meno recente","poster_path":"/g4.jpg",
         "vote_average":8.9,"genre_ids":[18],"original_language":"en"},
        {"id":910006,"name":"Podcast della Sera","original_name":"Podcast della Sera",
         "first_air_date":"2025-02-02","overview":"Podcast pubblicato come serie","poster_path":"/g5.jpg",
         "vote_average":5.0,"genre_ids":[10767],"original_language":"en"}
      ],"total_pages":3,"total_results":9}
    """.trimIndent()

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
  }

  // ── 1. Fonte: le DUE query discover/tv per popolarità ───────────────────────

  @Test
  fun tvShowUsaLeDueQueryDiscoverConPopolarita(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTvShowCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      val italian = tmdb.categoryRequests.filter { it.contains("with_original_language=it") }
      val general = tmdb.categoryRequests.filter { !it.contains("with_original_language") }
      assertEquals("Query show italiani attesa: ${tmdb.categoryRequests}", 1, italian.size)
      assertEquals("Query generale attesa: ${tmdb.categoryRequests}", 1, general.size)
      (italian + general).forEach { request ->
        assertTrue("Endpoint serie TV atteso: $request", request.startsWith("/3/discover/tv"))
        assertTrue(
          "Ordinamento per popolarità atteso: $request",
          request.contains("sort_by=popularity.desc")
        )
        assertTrue("Prima pagina attesa: $request", request.contains("page=1"))
      }

      // La CATEGORIA non interroga i film (il traffico TMDB della Home in sfondo
      // usa i propri endpoint e non è traffico della categoria).
      assertFalse(
        "La categoria TV Show non interroga i film: ${tmdb.categoryRequests}",
        tmdb.categoryRequests.any { it.contains("/discover/movie") }
      )

      assertEquals(SearchCategorySource.TMDB, viewModel.categoryState.value.source)
      assertNull(
        "Nessun errore TMDB atteso: ${viewModel.categoryState.value.statusMessage}",
        viewModel.categoryState.value.statusMessage
      )

      val record = viewModel.lastGenreLoads.value.singleOrNull()
      assertEquals(MediaType.SERIE_TV, record?.mediaType)
      assertEquals(GenreLoadReason.TMDB_NATIVE, record?.reason)
      assertEquals("Una pagina TMDB richiesta", 1, record?.tmdbPages)
    } finally {
      tmdb.stop()
    }
  }

  // ── 2. Italiani PRIMA, poi le altre lingue; niente duplicati né podcast ─────

  @Test
  fun italianiPrimaPoiAltreLingueSenzaDuplicatiNemmenoSuUnShowRecente(): Unit =
    runTest(testDispatcher) {
      val tmdb = TmdbFixture()
      tmdb.start()
      try {
        val viewModel = createViewModel()
        viewModel.openTvShowCategory()

        assertTrue(
          "La categoria deve terminare il caricamento",
          awaitCondition {
            viewModel.categoryState.value.source != null &&
              !viewModel.isCategoryLoading.value
          }
        )

        val titles = viewModel.categoryItems.value.map { it.title }
        // Italiani in testa (entrambi), poi le altre lingue.
        assertEquals(
          "Gli show italiani devono comparire per primi: $titles",
          listOf("Il Nuovo Show Italiano", "Don Matteo"),
          titles.take(2)
        )
        assertTrue("Show straniero atteso: $titles", titles.contains("The Tonight Show USA"))
        assertTrue("Show straniero atteso: $titles", titles.contains("Breaking Bad"))

        // Il duplicato italiano della lista generale resta UNA card sola.
        assertEquals(
          "Show italiano duplicato tra le due query: $titles",
          1,
          titles.count { it == "Don Matteo" }
        )
        // Il podcast è contenuto di scarto: filtrato anche dal catalogo TV Show.
        assertFalse(
          "Il podcast non deve comparire nel catalogo TV Show: $titles",
          titles.contains("Podcast della Sera")
        )
      } finally {
        tmdb.stop()
      }
    }

  // ── 3. Recenti E meno recenti nella stessa lista ────────────────────────────

  @Test
  fun laListaContieneTitoliRecentiEMenoRecenti(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTvShowCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      val items = viewModel.categoryItems.value
      val years = items.associate { it.title to it.year }
      assertTrue(
        "Titolo RECENTE atteso (2026): $years",
        years["Il Nuovo Show Italiano"]?.let { it >= 2026 } == true
      )
      assertTrue(
        "Titolo MENO recente atteso (2000): $years",
        years["Don Matteo"]?.let { it <= 2000 } == true
      )
      assertTrue(
        "Titolo MENO recente atteso (2008): $years",
        years["Breaking Bad"]?.let { it <= 2008 } == true
      )
      assertTrue(
        "Titolo RECENTE atteso (2024): $years",
        years["The Tonight Show USA"]?.let { it >= 2024 } == true
      )
    } finally {
      tmdb.stop()
    }
  }

  // ── 4. Funziona senza Debrid e senza addon installati ──────────────────────

  @Test
  fun tvShowFunzionaSenzaDebridESenzaAddon(): Unit = runTest(testDispatcher) {
    // Debrid disattivato (modalità HTTP diretta, default) e nessun addon installato.
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    assertTrue(
      "Nessun addon deve essere installato",
      StremioAddonRepository.getInstalledAddons().isEmpty()
    )
    assertFalse(
      "Nessun catalogo addon personale",
      StremioCatalogRepository.hasCustomCatalogAddons()
    )
    assertEquals(
      "Debrid disattivato",
      StreamingEngineMode.HTTP_WEB,
      AppSettingsRepository.streamingEngineMode.value
    )

    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTvShowCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      assertEquals(SearchCategorySource.TMDB, viewModel.categoryState.value.source)
      assertTrue(
        "Contenuti attesi senza Debrid/addon: ${viewModel.categoryItems.value}",
        viewModel.categoryItems.value.isNotEmpty()
      )
      // Tutte le richieste della categoria sono andate a TMDB, nessuna a un addon.
      assertTrue(
        "Solo richieste TMDB attese: ${tmdb.requested}",
        // Snapshot: il server locale può registrare richieste mentre il test legge.
        tmdb.requested.toList().all { it.startsWith("/3/") }
      )
    } finally {
      tmdb.stop()
    }
  }

  // ── 5. Paginazione su ENTRAMBE le query, senza duplicati tra le pagine ─────

  @Test
  fun tvShowPaginaEntrambeLeQuerySenzaDuplicati(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTvShowCategory()
      assertTrue(
        "Prima pagina caricata",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )
      // Pagina 1: 2 italiani + generale deduplicata (duplicato e podcast scarti) = 4.
      assertEquals(
        "Prima pagina completa: ${viewModel.categoryItems.value.map { it.title }}",
        4,
        viewModel.categoryItems.value.size
      )

      viewModel.loadNextCategoryPage()
      assertTrue(
        "Seconda pagina caricata",
        awaitCondition {
          viewModel.categoryItems.value.any { it.title == "Battute Italiane" } &&
            viewModel.categoryItems.value.any { it.title == "Nova Serie Francese" }
        }
      )

      val pageTwo = tmdb.categoryRequests.filter { it.contains("page=2") }
      assertEquals(
        "Pagina 2 richiesta su ENTRAMBE le query: ${tmdb.categoryRequests}",
        2,
        pageTwo.size
      )
      assertTrue(
        "La paginazione mantiene la query italiana: $pageTwo",
        pageTwo.any { it.contains("with_original_language=it") }
      )

      val items = viewModel.categoryItems.value
      val titles = items.map { it.title }
      assertEquals(
        "Id duplicati tra le pagine: ${items.map { it.id }}",
        items.size,
        items.map { it.id }.distinct().size
      )
      // I titoli della prima pagina restano e si aggiungono solo quelli nuovi
      // (1 italiano + 2 generali).
      assertEquals(
        "Totale atteso (4 + 3 nuovi): $titles",
        7,
        items.size
      )
      assertTrue("Prima pagina conservata: $titles", titles.contains("Il Nuovo Show Italiano"))
      assertTrue("Prima pagina conservata: $titles", titles.contains("The Tonight Show USA"))
    } finally {
      tmdb.stop()
    }
  }

  // ── 6. Errore TMDB dichiarato, non mascherato da categoria vuota riuscita ───

  @Test
  fun tvShowDichiaraErroreTmdb(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture(mode = TmdbMode.ERROR)
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTvShowCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      assertTrue(
        "Lo stato deve dichiarare l'errore TMDB: ${viewModel.categoryState.value.statusMessage}",
        !viewModel.categoryState.value.statusMessage.isNullOrBlank()
      )
      assertTrue("Nessun contenuto senza risposta TMDB", viewModel.categoryItems.value.isEmpty())
      val record = viewModel.lastGenreLoads.value.singleOrNull()
      assertEquals(GenreLoadReason.TMDB_UNAVAILABLE, record?.reason)
      assertEquals("Nessuna pagina TMDB riuscita", 0, record?.tmdbPages)
    } finally {
      tmdb.stop()
    }
  }

  // ── 7. Stato vuoto dichiarato, mai una griglia come caricamento riuscito ────

  @Test
  fun tvShowDichiaraStatoVuoto(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture(mode = TmdbMode.EMPTY)
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTvShowCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      assertEquals(SearchCategorySource.TMDB, viewModel.categoryState.value.source)
      assertTrue("Nessun titolo su TMDB: nessuna card", viewModel.categoryItems.value.isEmpty())
      assertTrue(
        "Lo stato dichiara l'assenza di risultati: ${viewModel.categoryState.value.statusMessage}",
        viewModel.categoryState.value.statusMessage.orEmpty().contains("TV Show")
      )
      val record = viewModel.lastGenreLoads.value.singleOrNull()
      assertEquals(GenreLoadReason.TMDB_NATIVE, record?.reason)
      assertEquals("Una pagina TMDB interrogata", 1, record?.tmdbPages)
      assertEquals("Zero risultati TMDB", 0, record?.tmdbItems)
    } finally {
      tmdb.stop()
    }
  }

  // ── 8. Il filtro anti-podcast delle altre categorie resta invariato ────────

  @Test
  fun ilFiltroAntiJunkRestaInvariatoSulleAltreCategorie(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      // Percorso storico per genere (default excludeNonEditorialTv = true): i
      // contenuti di scarto restano esclusi dai cataloghi generici per genere.
      val generic = MediaRepository.discoverByGenre(MediaType.SERIE_TV, 18, page = 1)
      assertTrue("Risposta TMDB attesa", generic != null)
      assertTrue(
        "Titolo valido atteso: ${generic?.map { it.title }}",
        generic.orEmpty().any { it.title == "The Tonight Show USA" }
      )
      assertFalse(
        "Il filtro anti-podcast dei cataloghi generici non deve cambiare: ${generic?.map { it.title }}",
        generic.orEmpty().any { it.title == "Podcast della Sera" }
      )
    } finally {
      tmdb.stop()
    }
  }

  // ── Helper ──────────────────────────────────────────────────────────────────

  private fun createViewModel(): StreamNovaViewModel =
    StreamNovaViewModel(ioDispatcher = testDispatcher).also {
      it.attachContext(ApplicationProvider.getApplicationContext())
      createdViewModels += it
    }

  private fun StreamNovaViewModel.openTvShowCategory() {
    val cat = GLOBAL_SEARCH_CATEGORIES.first { it.id == "tv_show" }
    assertEquals(SearchCategoryFilter.TMDB_TV_SHOWS, cat.contentFilter)
    assertNull("La categoria TV Show non dichiara un genere TV", cat.tvGenreId)
    initCategory(
      categoryId = cat.id,
      keywords = cat.keywords,
      movieGenreId = cat.movieGenreId,
      tvGenreId = cat.tvGenreId,
      contentFilter = cat.contentFilter
    )
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
}
