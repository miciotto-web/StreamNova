package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.example.data.model.MediaType
import com.example.data.api.TmdbApiClient
import com.example.data.repository.StremioCatalogRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.ui.screens.GLOBAL_SEARCH_CATEGORIES
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Test della sezione Ricerca: le categorie devono caricare i POSTER restituiti dai
 * cataloghi dell'addon (Xperience/Stremio) dichiarati nel manifest, non una lista
 * locale/TMDB.
 *
 * Il manifest reale di Xperience NON è presente nel repository (viene installato a
 * runtime dall'utente), quindi il test usa un server locale che ne RICREA la
 * struttura con lo stesso schema `genre_<x>_movies` già verificato in
 * `ShippedRegistryTest`. Nessun id di catalogo è codificato nel codice di
 * produzione: i target vengono sempre risolti dal manifest installato.
 *
 * Copertura richiesta:
 *  - HORROR interroga il catalogo associato e mostra i suoi poster;
 *  - ANIMAZIONE interroga il proprio catalogo senza ereditare la lista di HORROR;
 *  - il cambio categoria svuota i risultati precedenti e avvia la richiesta giusta;
 *  - errore/risposta vuota dell'addon non vengono presentati come caricamento
 *    TMDB riuscito (fallback dichiarato nello stato);
 *  - Home, ricerca testuale e altri cataloghi continuano a funzionare.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "it")
class SearchCategoryXperienceCatalogTest {

  /**
   * Dispatcher NUOVO per ogni test: un dispatcher condiviso da tutti i test farebbe
   * eseguire le coroutine rimaste pendenti di un test (es. il refresh Home di un
   * ViewModel) durante il test successivo, toccando il fixture di quest'ultimo.
   */
  private lateinit var testDispatcher: TestDispatcher

  /** ViewModel creati dai test: il loro scope va cancellato a fine test (nessun leak). */
  private val createdViewModels = mutableListOf<StreamNovaViewModel>()

  @Before
  fun setUp() {
    testDispatcher = StandardTestDispatcher()
    Dispatchers.setMain(testDispatcher)
    // Fallback TMDB deterministico: le serie non coperte dai cataloghi arrivano dal server
    // locale della fixture, mai dalla rete.
    TmdbApiClient.overrideServiceBaseUrl(stubTmdb.start())
  }

  @After
  fun tearDown() {
    createdViewModels.forEach { it.viewModelScope.cancel() }
    createdViewModels.clear()
    stubTmdb.stop()
    Dispatchers.resetMain()
  }

  /**
   * Server TMDB locale: risponde alle `discover` con liste minime e valide. Serve solo a
   * rendere deterministiche le categorie in cui le serie non hanno un catalogo compatibile
   * (il comportamento del fallback è verificato in `SearchCategoryTmdbFallbackTest`).
   */
  private class StubTmdbServer {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start(): String {
      server.createContext("/") { exchange ->
        val body = """{"page":1,"results":[],"total_pages":1,"total_results":0}"""
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
      }
      server.start()
      return "http://127.0.0.1:${server.address.port}/3/"
    }

    fun stop() = server.stop(0)
  }

  private val stubTmdb = StubTmdbServer()

  /** Crea un ViewModel tracciandolo: il suo scope viene cancellato in [tearDown]. */
  private fun createViewModel(): StreamNovaViewModel =
    StreamNovaViewModel().also { createdViewModels += it }

  // ── Fixture addon locale ─────────────────────────────────────────────────────

  private enum class FixtureMode { OK, ERROR, EMPTY }

  private class XperienceFixture(
    private val mode: FixtureMode,
    private val manifestJson: String = MANIFEST_JSON
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

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.rawPath
      requested += path
      try {
        when {
          path == "/manifest.json" -> respond(exchange, 200, manifestJson)
          path.startsWith("/catalog/") -> when (mode) {
            FixtureMode.ERROR -> respond(exchange, 500, """{"error":"catalog down"}""")
            FixtureMode.EMPTY -> respond(exchange, 200, """{"metas":[]}""")
            FixtureMode.OK -> {
              val body = CATALOGS[path]
              if (body == null) {
                respond(exchange, 404, """{"error":"not found: $path"}""")
              } else {
                respond(exchange, 200, body)
              }
            }
          }
          else -> respond(exchange, 404, "")
        }
      } finally {
        exchange.close()
      }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) {
        exchange.responseBody.use { it.write(bytes) }
      }
    }

    companion object {
      private val MANIFEST_JSON = """
        {
          "id": "app.xperience.60613fcd-0780-420d-a736-59c46cfa7ff9",
          "name": "Xperience",
          "version": "1.0.0",
          "description": "Fixture locale con la struttura cataloghi di Xperience",
          "resources": ["catalog"],
          "types": ["movie", "series"],
          "idPrefixes": ["tt"],
          "catalogs": [
            {"type":"movie","id":"genre_horror_movies","name":"Horror Movies","extra":[{"name":"skip"}]},
            {"type":"series","id":"genre_horror_series","name":"Horror Series","extra":[{"name":"skip"}]},
            {"type":"movie","id":"genre_animation_movies","name":"Animation Movies","extra":[{"name":"skip"}]},
            {"type":"movie","id":"genre_thriller_movies","name":"Thriller Movies","extra":[{"name":"skip"}]},
            {"type":"series","id":"genre_thriller_series","name":"Thriller Series","extra":[{"name":"skip"}]},
            {"type":"movie","id":"genre_dramma_movies","name":"Dramma Movies","extra":[{"name":"skip"}]},
            {"type":"movie","id":"genre_commedia_movies","name":"Commedia Movies","extra":[{"name":"skip"}]},
            {"type":"movie","id":"discover_genres","name":"Discover by Genre","extra":[{"name":"genre","options":["Horror","Animation"]}]},
            {"type":"movie","id":"xperience_search","name":"Search","extra":[{"name":"search","isRequired":true},{"name":"skip"}]}
          ]
        }
      """.trimIndent()

      private fun meta(id: String, type: String, name: String, year: String) =
        """{"id":"$id","type":"$type","name":"$name","poster":"https://img.example/$id.jpg","releaseInfo":"$year","genres":["Drama"]}"""

      /**
       * Meta SENZA campo `type`: alcuni manifest non lo dichiarano nei metas;
       * il tipo deve venire dal CATALOGO che ha restituito l'item.
       */
      private fun metaUntyped(id: String, name: String, year: String) =
        """{"id":"$id","name":"$name","poster":"https://img.example/$id.jpg","releaseInfo":"$year"}"""

      private fun metas(vararg items: String) = """{"metas":[${items.joinToString(",")}]}"""

      private val CATALOGS = mapOf(
        "/catalog/movie/genre_horror_movies.json" to metas(
          meta("tt9000001", "movie", "La Casa dello Spavento", "2021"),
          meta("tt9000002", "movie", "Notte dei Morti", "2019")
        ),
        "/catalog/movie/genre_horror_movies/skip=2.json" to metas(
          meta("tt9000005", "movie", "Alba dei Zombie", "2016")
        ),
        "/catalog/series/genre_horror_series.json" to metas(
          meta("tt9000003", "series", "Residenza Maledetta", "2020")
        ),
        "/catalog/movie/genre_animation_movies.json" to metas(
          meta("tt9100001", "movie", "Il Mondo di Cartone", "2022"),
          meta("tt9100002", "movie", "Robot Amichevoli", "2023")
        ),
        "/catalog/movie/genre_thriller_movies.json" to metas(
          meta("tt9300001", "movie", "Il Corrociatore", "2020")
        ),
        // Serie senza `type` nei metas: il tipo deve restare SERIE_TV.
        "/catalog/series/genre_thriller_series.json" to metas(
          metaUntyped("tt9300002", "Ombre sul Lago", "2021")
        ),
        "/catalog/series/genre_thriller_series/skip=1.json" to metas(
          metaUntyped("tt9300003", "Notte Senza Fine", "2019")
        ),
        "/catalog/movie/genre_dramma_movies.json" to metas(
          meta("tt9400001", "movie", "Vite Sospese", "2018")
        ),
        "/catalog/movie/genre_commedia_movies.json" to metas(
          meta("tt9500001", "movie", "Risate in Famiglia", "2019")
        ),
        "/catalog/movie/discover_genres/genre=Horror.json" to metas(
          meta("tt9000004", "movie", "Terrore in Campeggio", "2018")
        ),
        "/catalog/movie/discover_genres/genre=Animation.json" to metas(
          meta("tt9100003", "movie", "Acquerelli Blu", "2021")
        ),
        "/catalog/movie/xperience_search/search=qualcosa.json" to metas(
          meta("tt9200001", "movie", "Qualcosa nel Buio", "2017")
        )
      )
    }
  }

  /** Keyword della categoria HORROR come da [com.example.ui.screens.GLOBAL_SEARCH_CATEGORIES]. */
  private val horrorKeywords = listOf("Horror")

  /** Keyword della categoria ANIMAZIONE come da [com.example.ui.screens.GLOBAL_SEARCH_CATEGORIES]. */
  private val animationKeywords = listOf("Animazione", "Animation")

  private fun cleanup(fixture: XperienceFixture) {
    StremioCatalogRepository.clearCache()
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    fixture.stop()
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

  // ── 1. HORROR → catalogo Xperience + poster dell'addon ──────────────────────

  @Test
  fun horrorInterrogaIlSuoCatalogoEPresentaIPosterRestituitiDallAddon(): Unit =
    runTest(testDispatcher) {
      val fixture = XperienceFixture(FixtureMode.OK)
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)

        // Selezione target: solo i cataloghi REALMENTE dichiarati dal manifest.
        val requests = StremioCatalogRepository.findCategoryRequests(horrorKeywords)
        val requestIds = requests.map { it.target.catalogId }
        assertTrue(
          "HORROR deve risolvere il catalogo horror del manifest: $requestIds",
          requestIds.contains("genre_horror_movies")
        )
        assertFalse(
          "HORROR non deve risolvere il catalogo animazione: $requestIds",
          requestIds.contains("genre_animation_movies")
        )
        assertTrue(
          "I cataloghi di sola ricerca non possono essere fonte primaria di una categoria",
          requests.none { it.target.requiresSearch }
        )

        val load = StremioCatalogRepository.loadCategoryCatalogs(horrorKeywords)
        assertTrue("Nessun errore atteso: ${load.errors}", load.errors.isEmpty())
        assertTrue(load.matchedTargets > 0)

        val titles = load.items.map { it.title }
        assertTrue("Poster horror attesi: $titles", titles.contains("La Casa dello Spavento"))
        assertFalse("Nessun poster di un'altra categoria: $titles", titles.contains("Il Mondo di Cartone"))
        assertTrue(
          "Endpoint del catalogo horror interrogato: ${fixture.requested}",
          fixture.requested.contains("/catalog/movie/genre_horror_movies.json")
        )
        assertFalse(
          "Il catalogo animazione non deve essere toccato: ${fixture.requested}",
          fixture.requested.any { it.contains("genre_animation_movies") }
        )

        // Metadati necessari ad aprire la scheda e avviare la riproduzione.
        val first = load.items.first { it.title == "La Casa dello Spavento" }
        assertEquals("tt9000001", first.id)
        assertEquals(MediaType.FILM, first.type)
        assertEquals("Xperience", first.provider)
        assertFalse("Poster assente", first.posterUrl.isNullOrBlank())
      } finally {
        cleanup(fixture)
      }
    }

  // ── 2. ANIMAZIONE → catalogo proprio, mai la lista di HORROR ────────────────

  @Test
  fun animazioneInterrogaIlProprioCatalogoENonLaListaDellHorror(): Unit =
    runTest(testDispatcher) {
      val fixture = XperienceFixture(FixtureMode.OK)
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)

        val horror = StremioCatalogRepository.loadCategoryCatalogs(horrorKeywords)
        assertTrue(horror.items.isNotEmpty())
        fixture.requested.clear()

        val animation = StremioCatalogRepository.loadCategoryCatalogs(animationKeywords)
        val animationTitles = animation.items.map { it.title }
        val horrorTitles = horror.items.map { it.title }.toSet()

        assertTrue("Poster animazione attesi: $animationTitles", animationTitles.contains("Il Mondo di Cartone"))
        assertFalse(
          "Stato condiviso/cache: nessun titolo horror in ANIMAZIONE",
          animationTitles.any { horrorTitles.contains(it) }
        )
        assertTrue(
          "Endpoint del catalogo animazione interrogato: ${fixture.requested}",
          fixture.requested.contains("/catalog/movie/genre_animation_movies.json")
        )
        assertFalse(
          "Il catalogo horror non deve essere interrogato per ANIMAZIONE: ${fixture.requested}",
          fixture.requested.any { it.contains("genre_horror") }
        )
        assertTrue(
          "L'extra genre dichiarato dal manifest viene usato per la categoria",
          fixture.requested.contains("/catalog/movie/discover_genres/genre=Animation.json")
        )
      } finally {
        cleanup(fixture)
      }
    }

  // ── 3. ViewModel: categoria, cambio categoria e stato ───────────────────────

  @Test
  fun laCategoriaHorrorMostraIPosterDellAddonENonIlSeedLocale(): Unit =
    runTest(testDispatcher) {
      val fixture = XperienceFixture(FixtureMode.OK)
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)
        val viewModel = createViewModel()

        viewModel.initCategory("horror", horrorKeywords, movieGenreId = 27, tvGenreId = 27)
        assertTrue(
          "La categoria deve caricare i poster del catalogo",
          awaitCondition { viewModel.categoryItems.value.isNotEmpty() }
        )

        val state = viewModel.categoryState.value
        assertEquals(SearchCategorySource.ADDON, state.source)
        assertTrue("Etichetta del catalogo: ${state.addonLabel}", state.addonLabel.orEmpty().contains("Horror"))
        assertNull("Nessun avviso quando il catalogo carica correttamente", state.statusMessage)
        assertEquals("horror", viewModel.activeSearchCategoryId)
        assertFalse(viewModel.isCategoryLoading.value)

        val titles = viewModel.categoryItems.value.map { it.title }
        assertTrue("Poster dell'addon attesi: $titles", titles.contains("La Casa dello Spavento"))
        assertFalse("Nessun seed da allMedia/TMDB Discover: $titles", titles.contains("Il Mondo di Cartone"))
        assertTrue(
          "Il catalogo horror deve essere stato interrogato: ${fixture.requested}",
          fixture.requested.contains("/catalog/movie/genre_horror_movies.json")
        )
      } finally {
        cleanup(fixture)
      }
    }

  @Test
  fun ilCambioCategoriaSvuotaIRisultatiPrecedentiEAvviaLaRichiestaCorretta(): Unit =
    runTest(testDispatcher) {
      val fixture = XperienceFixture(FixtureMode.OK)
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)
        val viewModel = createViewModel()

        viewModel.initCategory("horror", horrorKeywords, movieGenreId = 27, tvGenreId = 27)
        assertTrue(awaitCondition { viewModel.categoryItems.value.isNotEmpty() })
        val horrorTitles = viewModel.categoryItems.value.map { it.title }.toSet()
        assertTrue(horrorTitles.isNotEmpty())

        // Cambio categoria: i poster precedenti spariscono PRIMA del nuovo caricamento.
        viewModel.initCategory("animazione", animationKeywords, movieGenreId = 16, tvGenreId = 16)
        assertTrue(
          "I risultati della categoria precedente non devono restare visibili",
          viewModel.categoryItems.value.isEmpty()
        )
        assertTrue("Il caricamento della nuova categoria deve essere attivo", viewModel.isCategoryLoading.value)
        assertEquals("Lo stato della categoria vecchia viene azzerato", SearchCategoryState(), viewModel.categoryState.value)
        assertEquals("animazione", viewModel.activeSearchCategoryId)

        assertTrue(
          "La nuova categoria deve caricare",
          awaitCondition { viewModel.categoryItems.value.isNotEmpty() }
        )
        val animationTitles = viewModel.categoryItems.value.map { it.title }
        assertTrue(animationTitles.contains("Il Mondo di Cartone"))
        assertFalse(
          "La lista dell'horror non deve sopravvivere al cambio categoria",
          animationTitles.any { horrorTitles.contains(it) }
        )
        assertEquals(
          "I film della categoria vengono dal catalogo compatibile",
          GenreLoadReason.SUPPORTED_BY_CATALOG,
          viewModel.lastGenreLoads.value.firstOrNull { it.mediaType == MediaType.FILM }?.reason
        )
        assertFalse(
          "Nessun risultato TMDB per i FILM: la categoria non è servita dal fallback",
          viewModel.lastGenreLoads.value
            .firstOrNull { it.mediaType == MediaType.FILM }?.reason == GenreLoadReason.TMDB_FALLBACK
        )
        assertEquals("animazione", viewModel.activeSearchCategoryId)
      } finally {
        cleanup(fixture)
      }
    }

  // ── 4. Errore: dichiarato, mai "riuscito"; vuoto: fallback TMDB ────────────
  //
  // Il catalogo FILM della categoria è compatibile con il genere: se FALLISCE resta la
  // fonte (nessun fallback TMDB che mascheri l'errore, motivo FAILED). Se risponde HTTP
  // 200 VUOTO non ci sono risultati utili dagli addon: i titoli arrivano dal fallback
  // TMDB (qui la stub TMDB risponde con zero risultati, quindi il caso "nemmeno su
  // TMDB" resta dichiarato, mai presentato come caricamento addon riuscito).

  @Test
  fun erroreDelCatalogoAddonNonVienePresentatoComeCaricamentoRiuscito(): Unit =
    runTest(testDispatcher) {
      val fixture = XperienceFixture(FixtureMode.ERROR)
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)
        val viewModel = createViewModel()

        viewModel.initCategory("horror", horrorKeywords, movieGenreId = 27, tvGenreId = 27)
        assertTrue(
          "Lo stato di categoria deve essere valorizzato",
          awaitCondition { viewModel.categoryState.value.source != null }
        )
        assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

        val state = viewModel.categoryState.value
        assertNull("Nessuna etichetta addon quando nessun catalogo ha contenuti", state.addonLabel)
        // Catalogo FILM compatibile FALLITO: il motivo deve essere dichiarato, e TMDB non
        // deve essere usato per i film (maschererebbe l'errore del catalogo).
        assertEquals(
          "Il fallback TMDB non è stato usato per i film (catalogo compatibile fallito)",
          GenreLoadReason.FAILED,
          viewModel.lastGenreLoads.value.firstOrNull { it.mediaType == MediaType.FILM }?.reason
        )
        assertTrue(
          "Il motivo del fallimento deve essere mostrato in UI: ${state.statusMessage}",
          state.statusMessage.orEmpty().contains("Catalogo film non raggiungibile")
        )
        assertTrue(
          "Nessun poster dell'addon può comparire dopo un errore",
          viewModel.categoryItems.value.none { it.provider == "Xperience" }
        )
      } finally {
        cleanup(fixture)
      }
    }

  @Test
  fun rispostaVuotaDelCatalogoAddonAttivaIlFallbackTmdb(): Unit = runTest(testDispatcher) {
    val fixture = XperienceFixture(FixtureMode.EMPTY)
    val baseUrl = fixture.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)
      val viewModel = createViewModel()

      viewModel.initCategory("animazione", animationKeywords, movieGenreId = 16, tvGenreId = 16)
      assertTrue(awaitCondition { viewModel.categoryState.value.source != null })
      assertTrue(awaitCondition { !viewModel.isCategoryLoading.value })

      val state = viewModel.categoryState.value
      assertNull(state.addonLabel)
      // Catalogo FILM COMPATIBILE (il genere è stato inviato) ma senza risultati: non è
      // un errore, MA non ci sono risultati utili dagli addon → fallback TMDB. La stub
      // TMDB risponde con zero risultati, quindi il caso resta dichiarato in UI.
      val filmRecord = viewModel.lastGenreLoads.value.firstOrNull { it.mediaType == MediaType.FILM }
      assertEquals(
        "Catalogo compatibile e vuoto: fallback TMDB per completare la ricerca",
        GenreLoadReason.TMDB_FALLBACK,
        filmRecord?.reason
      )
      assertEquals(
        "Il motivo registra il catalogo prima vuoto",
        true,
        filmRecord?.catalogWasEmpty
      )
      assertEquals("TMDB interrogato una volta", 1, filmRecord?.tmdbPages)
      assertTrue(
        "Il caso 'nemmeno su TMDB' è dichiarato: ${state.statusMessage}",
        state.statusMessage.orEmpty().contains("nemmeno su TMDB")
      )
      assertTrue(
        "La fonte non è presentata come addon",
        state.source == SearchCategorySource.TMDB_FALLBACK
      )
      assertTrue(viewModel.categoryItems.value.none { it.provider == "Xperience" })
    } finally {
      cleanup(fixture)
    }
  }

  // ── 5. Gli altri percorsi dei cataloghi restano funzionanti ─────────────────

  @Test
  fun homeRicercaEAltriCataloghiContinuanoAFunzionare(): Unit = runTest(testDispatcher) {
    val fixture = XperienceFixture(FixtureMode.OK)
    val baseUrl = fixture.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)

      // Percorso Home (stessa API dei cataloghi addon).
      val homeMovies = StremioCatalogRepository.loadAllCatalogSections(MediaType.FILM)
      assertTrue("Sezioni Home vuote", homeMovies.isNotEmpty())
      assertTrue(homeMovies.any { it.catalogId == "genre_horror_movies" })
      assertTrue(homeMovies.any { it.catalogId == "genre_animation_movies" })

      val homeSeries = StremioCatalogRepository.loadAllCatalogSections(MediaType.SERIE_TV)
      assertTrue(homeSeries.any { it.catalogId == "genre_horror_series" })

      // Ricerca testuale sugli addon (extra `search` dichiarato dal manifest).
      val searchResults = StremioCatalogRepository.searchAddonCatalogs("qualcosa", MediaType.FILM)
      assertTrue(
        "La ricerca testuale deve restituire il titolo del catalogo search: ${searchResults.map { it.title }}",
        searchResults.any { it.title == "Qualcosa nel Buio" }
      )

      // Target ancora disponibili per altre sezioni.
      assertTrue(
        StremioCatalogRepository.getAvailableTargets(MediaType.FILM).any { it.catalogId == "genre_animation_movies" }
      )
    } finally {
      cleanup(fixture)
    }
  }

  // ── 6. Paginazione dei cataloghi della categoria (extra `skip`) ─────────────

  @Test
  fun laPaginazioneDellaCategoriaUsaIlProprioSkipDiCatalogo(): Unit = runTest(testDispatcher) {
    val fixture = XperienceFixture(FixtureMode.OK)
    val baseUrl = fixture.start()
    try {
      StremioAddonRepository.installAddon(baseUrl)

      val load = StremioCatalogRepository.loadCategoryCatalogs(horrorKeywords)
      assertTrue(load.items.isNotEmpty())

      val updated = StremioCatalogRepository.paginateCategoryCatalogs(load.entries)
      val allTitles = updated.flatMap { it.section.items }.map { it.title }

      assertTrue(
        "La pagina successiva del catalogo horror deve essere aggiunta: $allTitles",
        allTitles.contains("Alba dei Zombie")
      )
      assertTrue(
        "La richiesta deve usare lo skip del singolo catalogo: ${fixture.requested}",
        fixture.requested.contains("/catalog/movie/genre_horror_movies/skip=2.json")
      )
    } finally {
      cleanup(fixture)
    }
  }

  /** Segmenti di una keyword, con la stessa normalizzazione usata dal repository. */
  private fun slugOf(keyword: String): String =
    keyword.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.joinToString("_")

  /**
   * Manifest generato che dichiara, PER OGNI categoria reale di
   * [GLOBAL_SEARCH_CATEGORIES], sia il catalogo film sia quello serie secondo lo
   * schema `genre_<slug>_{movies,series}`.
   *
   * È una FIXTURE di test per verificare il COMPORTAMENTO dell'app (richiesta di
   * entrambi i tipi quando dichiarati): non afferma che Xperience dichiari questi
   * id nel manifest reale, che non è presente nel repository.
   */
  private fun generatedManifestJson(): String {
    val catalogs = GLOBAL_SEARCH_CATEGORIES.joinToString(",\n") { cat ->
      val slug = slugOf(cat.keywords.first())
      """{"type":"movie","id":"genre_${slug}_movies","name":"${cat.id} Movies","extra":[{"name":"skip"}]},""" +
        "\n" +
        """{"type":"series","id":"genre_${slug}_series","name":"${cat.id} Series","extra":[{"name":"skip"}]}"""
    }
    return """
      {
        "id": "app.xperience.60613fcd-0780-420d-a736-59c46cfa7ff9",
        "name": "Xperience",
        "version": "1.0.0",
        "resources": ["catalog"],
        "types": ["movie", "series"],
        "idPrefixes": ["tt"],
        "catalogs": [
          $catalogs
        ]
      }
    """.trimIndent()
  }

  // ── 7. Categoria con cataloghi FILM E SERIE: richiesti entrambi, merge ──────

  @Test
  fun categoriaConCataloghiFilmESerieRichiedeEntrambiEMergeIContenuti(): Unit =
    runTest(testDispatcher) {
      val fixture = XperienceFixture(FixtureMode.OK)
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)
        val thriller = GLOBAL_SEARCH_CATEGORIES.first { it.id == "thriller" }

        val requests = StremioCatalogRepository.findCategoryRequests(thriller.keywords)
        val ids = requests.map { it.target.catalogId }
        assertTrue(
          "Thriller deve richiedere sia il catalogo film sia quello serie dichiarati: $ids",
          ids.contains("genre_thriller_movies") && ids.contains("genre_thriller_series")
        )

        val load = StremioCatalogRepository.loadCategoryCatalogs(thriller.keywords)
        assertTrue("Nessun errore atteso: ${load.errors}", load.errors.isEmpty())

        val titles = load.items.map { it.title }
        assertTrue("Poster film attesi: $titles", titles.contains("Il Corrociatore"))
        assertTrue("Poster serie attesi: $titles", titles.contains("Ombre sul Lago"))

        // I due cataloghi restano SEZIONI DISTINTE: nessuno sovrascrive l'altro.
        val catalogIds = load.entries.map { it.section.catalogId }
        assertTrue(
          "Merge senza sovrascritture: $catalogIds",
          catalogIds.containsAll(listOf("genre_thriller_movies", "genre_thriller_series"))
        )

        // Tipo conservato: i metas della serie NON dichiarano `type`, deve valere
        // il tipo del catalogo che li ha restituiti.
        assertEquals(MediaType.FILM, load.items.first { it.title == "Il Corrociatore" }.type)
        assertEquals(
          "I metas senza type ereditano il tipo del catalogo serie",
          MediaType.SERIE_TV,
          load.items.first { it.title == "Ombre sul Lago" }.type
        )

        // La paginazione prosegue ANCHE sul catalogo serie, non solo su quello film.
        val updated = StremioCatalogRepository.paginateCategoryCatalogs(load.entries)
        val pagedTitles = updated.flatMap { it.section.items }.map { it.title }
        assertTrue(
          "Pagina successiva del catalogo serie caricata: $pagedTitles",
          pagedTitles.contains("Notte Senza Fine")
        )
        assertTrue(
          "skip usato anche per il catalogo serie: ${fixture.requested}",
          fixture.requested.contains("/catalog/series/genre_thriller_series/skip=1.json")
        )
      } finally {
        cleanup(fixture)
      }
    }

  // ── 8. Categoria con un SOLO tipo disponibile: mancanza dichiarata ─────────

  @Test
  fun categoriaSenzaCatalogoSerieDichiaraCheNonEDisponibile(): Unit =
    runTest(testDispatcher) {
      val fixture = XperienceFixture(FixtureMode.OK)
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)
        val viewModel = createViewModel()
        val drama = GLOBAL_SEARCH_CATEGORIES.first { it.id == "dramma" }

        viewModel.initCategory(drama.id, drama.keywords, drama.movieGenreId, drama.tvGenreId)
        assertTrue(awaitCondition { viewModel.categoryItems.value.isNotEmpty() })

        val state = viewModel.categoryState.value
        // Il manifest dichiara cataloghi serie ma NESSUNO applica il filtro per genere:
        // i film restano dell'addon, le serie passano al fallback TMDB (switch attivo).
        assertEquals(
          "Film dal catalogo compatibile",
          GenreLoadReason.SUPPORTED_BY_CATALOG,
          viewModel.lastGenreLoads.value.firstOrNull { it.mediaType == MediaType.FILM }?.reason
        )
        assertEquals(
          "Serie: nessun catalogo compatibile → TMDB",
          GenreLoadReason.TMDB_FALLBACK,
          viewModel.lastGenreLoads.value.firstOrNull { it.mediaType == MediaType.SERIE_TV }?.reason
        )
        // Nessun messaggio attribuisce ai FILM un limite che non hanno.
        assertFalse(
          "Nessun messaggio sui film: ${state.statusMessage}",
          state.statusMessage.orEmpty().contains("Catalogo film")
        )
        assertTrue(
          "Film caricati: ${viewModel.categoryItems.value.map { it.title }}",
          viewModel.categoryItems.value.any { it.title == "Vite Sospese" }
        )
        // Nessun catalogo serie è stato interrogato: la serie "Horror" dell'addon resta
        // visibile in Home, ma non viene usata come se filtrasse il genere Dramma.
        fixture.requested.clear()
        val reloaded = StremioCatalogRepository.loadCategoryCatalogs(
          drama.keywords + listOfNotNull(TMDB_MOVIE_GENRE_NAMES[drama.movieGenreId]) +
            listOfNotNull(TMDB_TV_GENRE_NAMES[drama.tvGenreId])
        )
        assertTrue("Serie non richieste: ${fixture.requested}", reloaded.matchedSeriesTargets == 0)
        assertTrue(
          "Nessuna richiesta a un catalogo serie non filtrato: ${fixture.requested}",
          fixture.requested.none { it.startsWith("/catalog/series/") }
        )
      } finally {
        cleanup(fixture)
      }
    }

  // ── 9. Cambio da film+serie a solo film: nessun riuso dei risultati ────────

  @Test
  fun ilCambioCategoriaDaFilmESerieASoloFilmNonRiusaIRisultatiPrecedenti(): Unit =
    runTest(testDispatcher) {
      val fixture = XperienceFixture(FixtureMode.OK)
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)
        val viewModel = createViewModel()
        val thriller = GLOBAL_SEARCH_CATEGORIES.first { it.id == "thriller" }
        val drama = GLOBAL_SEARCH_CATEGORIES.first { it.id == "dramma" }

        viewModel.initCategory(thriller.id, thriller.keywords, thriller.movieGenreId, thriller.tvGenreId)
        assertTrue(awaitCondition { viewModel.categoryItems.value.size >= 2 })
        val thrillerTitles = viewModel.categoryItems.value.map { it.title }.toSet()
        assertTrue(thrillerTitles.containsAll(listOf("Il Corrociatore", "Ombre sul Lago")))
        assertTrue(viewModel.categoryItems.value.any { it.type == MediaType.SERIE_TV })

        viewModel.initCategory(drama.id, drama.keywords, drama.movieGenreId, drama.tvGenreId)
        assertTrue(
          "I risultati precedenti spariscono prima del nuovo caricamento",
          viewModel.categoryItems.value.isEmpty()
        )
        assertTrue(awaitCondition { viewModel.categoryItems.value.isNotEmpty() })

        val dramaTitles = viewModel.categoryItems.value.map { it.title }
        assertTrue(dramaTitles.contains("Vite Sospese"))
        assertFalse(
          "Nessun titolo della categoria precedente riutilizzato: $dramaTitles",
          dramaTitles.any { thrillerTitles.contains(it) }
        )
        assertTrue(
          "I film della nuova categoria vengono dal catalogo compatibile",
          viewModel.categoryItems.value.any { it.type == MediaType.FILM }
        )
        assertEquals(
          "Dramma: catalogo film compatibile, nessun fallback TMDB",
          GenreLoadReason.SUPPORTED_BY_CATALOG,
          viewModel.lastGenreLoads.value.firstOrNull { it.mediaType == MediaType.FILM }?.reason
        )
        assertFalse(
          "Nessun risultato TMDB per i FILM: la categoria non è servita dal fallback",
          viewModel.lastGenreLoads.value
            .firstOrNull { it.mediaType == MediaType.FILM }?.reason == GenreLoadReason.TMDB_FALLBACK
        )
      } finally {
        cleanup(fixture)
      }
    }


  // ── 10. Tutte le categorie: nessun catalogo incompatibile usato per il genere ─

  @Test
  fun tutteLeCategorieUsanoEntrambiITipiQuandoDichiaratiENessunIncompatibile(): Unit =
    runTest(testDispatcher) {
      val fixture = XperienceFixture(FixtureMode.OK, manifestJson = generatedManifestJson())
      val baseUrl = fixture.start()
      try {
        StremioAddonRepository.installAddon(baseUrl)

        assertTrue("Categorie attese", GLOBAL_SEARCH_CATEGORIES.isNotEmpty())
        GLOBAL_SEARCH_CATEGORIES.forEach { cat ->
          val slug = slugOf(cat.keywords.first())
          val requests = StremioCatalogRepository.findCategoryRequests(cat.keywords)
          val found = requests.map { "${it.target.type}/${it.target.catalogId}" }
          println("CATREPORT ${cat.id} => richieste: $found")
          // La categoria ha sia un equivalente per nome nel manifest sia, per la
          // stessa keyword, cataloghi con filtro `genre` dichiarato: entrambi i tipi.
          assertTrue(
            "[${cat.id}] catalogo film non richiesto (slug $slug): $found",
            found.contains("movie/genre_${slug}_movies")
          )
          assertTrue(
            "[${cat.id}] catalogo serie non richiesto NONOSTANTE dichiarato nel manifest (slug $slug): $found",
            found.contains("series/genre_${slug}_series")
          )
          // MAI un catalogo tematico generico (non filtrato per genere) per un tipo
          // l'app può mostrare? Il manifest generato non ne contiene: nessun extra
          // `genre` può essere inviato a cataloghi che non lo supportano.
          assertTrue(
            "[${cat.id}] extra genre inviato a un catalogo che non lo supporta: " +
              requests.filter { it.extra.isNotEmpty() }.map { it.target.catalogId },
            requests.all { request ->
              request.extra.isEmpty() || request.target.supportsGenre
            }
          )
        }
      } finally {
        cleanup(fixture)
      }
    }

}
