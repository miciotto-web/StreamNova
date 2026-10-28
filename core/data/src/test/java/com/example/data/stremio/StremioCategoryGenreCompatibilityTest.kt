package com.example.data.stremio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaType
import com.example.data.repository.StremioCatalogRepository
import com.example.data.repository.StremioCatalogRepository.CategoryTypeOutcome
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regole di compatibilità tra un CATALOGO Stremio e il filtro per genere della sezione
 * Ricerca, verificate sui manifest dichiarati (nessun servizio esterno):
 *
 *  - un catalogo che dichiara `extra.genre` con opzioni riceve SOLO un'opzione
 *    dichiarata per quel tipo (i generi dei film non sono quelli delle serie);
 *  - un catalogo che dichiara `extra.genre` SENZA opzioni è comunque filtrabile
 *    (il protocollo non impone l'elenco `options`);
 *  - un catalogo che NON dichiara `extra.genre` non diventa mai la risposta del
 *    genere solo perché il suo titolo coincide;
 *  - un catalogo tematico generico (es. "Top 100 Today") resta visitabile in Home ma
 *    non è usato come se filtrasse il genere;
 *  - catalogo assente, incompatibile, interrogato-vuoto e fallito sono stati distinti;
 *  - `sanitizeExtra`/`buildCatalogPath` producono la richiesta documentata da Stremio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StremioCategoryGenreCompatibilityTest {

  private lateinit var dispatcher: TestDispatcher
  private var fixture: ManifestFixture? = null

  @Before
  fun setUp() {
    dispatcher = StandardTestDispatcher()
    Dispatchers.setMain(dispatcher)
    clearAddons()
  }

  @After
  fun tearDown() {
    fixture?.stop()
    fixture = null
    clearAddons()
    Dispatchers.resetMain()
  }

  private fun clearAddons() {
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioCatalogRepository.clearCache()
    StremioAddonRepository.clearCaches()
  }

  // ── 1. Catalogo FILM con Horror dichiarato ─────────────────────────────────

  @Test
  fun filmConGenereHorrorRiceveHorror(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.genre","name":"Genre Addon","version":"1","resources":["catalog"],
       "types":["movie","series"],"catalogs":[
        {"type":"movie","id":"discover_movies","name":"Movies",
         "extra":[{"name":"genre","isRequired":true,"options":["Action","Horror","Comedy"]}]}
      ]}
      """
    )
    val requests = StremioCatalogRepository.findCategoryRequests(horrorKeywords)

    val movie = requests.single { it.target.isMovie }
    assertEquals("Horror deve essere inviato al catalogo film", "Horror", movie.extra[EXTRA_GENRE])
    assertTrue("La richiesta della categoria è filtrata per genere", movie.isGenreFiltered)
    assertFalse("Nessuna serie nei cataloghi dichiarati", requests.any { it.target.isSeries })
  }

  // ── 2. Catalogo SERIE con Horror dichiarato ────────────────────────────────

  @Test
  fun serieConGenereHorrorRiceveHorror(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.genre","name":"Genre Addon","version":"1","resources":["catalog"],
       "types":["movie","series"],"catalogs":[
        {"type":"series","id":"discover_series","name":"Series",
         "extra":[{"name":"genre","isRequired":true,"options":["Drama","Horror"]}]}
      ]}
      """
    )
    val requests = StremioCatalogRepository.findCategoryRequests(horrorKeywords)

    val series = requests.single { it.target.isSeries }
    assertEquals("Horror deve essere inviato al catalogo serie", "Horror", series.extra[EXTRA_GENRE])
    assertTrue(series.target.type == TYPE_SERIES)
  }

  // ── 3. Catalogo `series` senza supporto al filtro per genere ───────────────

  @Test
  fun catalogoSerieSenzaFiltroGenereNonVieneUsato(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.plain","name":"Plain Addon","version":"1","resources":["catalog"],
       "types":["movie","series"],"catalogs":[
        {"type":"movie","id":"discover_movies","name":"Movies",
         "extra":[{"name":"genre","options":["Horror","Comedy"]}]},
        {"type":"series","id":"trending_series","name":"Trending Series",
         "extra":[{"name":"skip"}]}
      ]}
      """
    )
    val requests = StremioCatalogRepository.findCategoryRequests(horrorKeywords)
    assertTrue("Il catalogo serie non applica il filtro: ${requests}", requests.none { it.target.isSeries })

    val load = StremioCatalogRepository.loadCategoryCatalogs(horrorKeywords)
    assertEquals("Serie incompatibili, non interrogate", 0, load.matchedSeriesTargets)
    assertEquals(1, load.availableSeriesCatalogs)
    assertEquals(
      "Il tipo è dichiarato incompatibile, non 'assente'",
      CategoryTypeOutcome.NOT_SUPPORTED,
      load.outcomeFor(MediaType.SERIE_TV)
    )
  }

  // ── 4. Catalogo tematico senza filtro per genere ───────────────────────────

  @Test
  fun catalogoTematicoSenzaFiltroGenereNonDiventaRispostaDelGenere(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.themed","name":"Themed Addon","version":"1","resources":["catalog"],
       "types":["movie","series"],"catalogs":[
        {"type":"movie","id":"snoak_top100_movies","name":"Top 100 Today",
         "extra":[{"name":"skip"}]},
        {"type":"movie","id":"title_trick","name":"Horror",
         "extra":[{"name":"skip"}]},
        {"type":"movie","id":"discover_movies","name":"Movies",
         "extra":[{"name":"genre","options":["Horror"]}]}
      ]}
      """
    )
    val requests = StremioCatalogRepository.findCategoryRequests(horrorKeywords)
    val ids = requests.map { it.target.catalogId }

    assertFalse("'Top 100 Today' non filtra il genere: non deve essere usato", ids.contains("snoak_top100_movies"))
    assertFalse(
      "Un catalogo riconosciuto solo dal titolo non può mostrare la sua lista generica",
      ids.contains("title_trick")
    )
    assertEquals(
      "Solo il catalogo con filtro genere dichiarato risponde",
      listOf("discover_movies"),
      ids
    )
  }

  // ── 5. Catalogo compatibile che restituisce zero risultati ─────────────────

  @Test
  fun catalogoCompatibileVuotoNonPresentatoComeNonSupportato(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.empty","name":"Empty Addon","version":"1","resources":["catalog"],
       "types":["movie"],"catalogs":[
        {"type":"movie","id":"discover_movies","name":"Movies",
         "extra":[{"name":"genre","options":["Horror"]}]}
      ]}
      """,
      catalogBody = { """{"metas":[]}""" }
    )
    val load = StremioCatalogRepository.loadCategoryCatalogs(horrorKeywords)

    assertEquals(1, load.matchedMovieTargets)
    assertEquals(1, load.genreFilteredRequests)
    assertTrue("Il catalogo è stato interrogato", load.entries.isNotEmpty())
    assertTrue("Risposta vuota", load.items.isEmpty())
    assertEquals(CategoryTypeOutcome.EMPTY, load.outcomeFor(MediaType.FILM))
    assertTrue("Nessun errore", load.errors.isEmpty())
  }

  // ── 6. Manifest con `extra` che dichiara il filtro per genere ──────────────

  @Test
  fun manifestConExtraGenreSenzaOpzioniRiceveComunqueIlFiltro(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.optionsless","name":"Optionsless","version":"1","resources":["catalog"],
       "types":["movie","series"],"catalogs":[
        {"type":"movie","id":"discover_movies","name":"Movies","extra":[{"name":"genre"}]},
        {"type":"series","id":"discover_series","name":"Series","extra":[{"name":"genre"}]}
      ]}
      """,
      catalogBody = { """{"metas":[{"id":"tt1","type":"movie","name":"X"}]}""" }
    )
    val requests = StremioCatalogRepository.findCategoryRequests(horrorKeywords)
    assertEquals(2, requests.size)
    assertTrue(
      "Senza opzioni statiche il filtro resto supportato: ${requests.map { it.extra }}",
      requests.all { it.extra[EXTRA_GENRE] == "Horror" }
    )

    val load = StremioCatalogRepository.loadCategoryCatalogs(horrorKeywords)
    assertEquals(2, load.genreFilteredRequests)
    assertEquals(CategoryTypeOutcome.OK, load.outcomeFor(MediaType.FILM))
    assertEquals(CategoryTypeOutcome.OK, load.outcomeFor(MediaType.SERIE_TV))
  }

  // ── 7. Manifest senza `extra` per genere ──────────────────────────────────

  @Test
  fun manifestSenzaExtraGenreNonECompatibile(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.nogenre","name":"No Genre","version":"1","resources":["catalog"],
       "types":["movie","series"],"catalogs":[
        {"type":"movie","id":"trending_movies","name":"Trending","extra":[{"name":"skip"}]},
        {"type":"series","id":"trending_series","name":"Trending","extra":[{"name":"skip"}]}
      ]}
      """
    )
    assertTrue(
      "Nessun catalogo può rispondere a una categoria: nessun genere dichiarato",
      StremioCatalogRepository.findCategoryRequests(horrorKeywords).isEmpty()
    )
    val load = StremioCatalogRepository.loadCategoryCatalogs(horrorKeywords)
    assertEquals(0, load.matchedTargets)
    assertEquals(CategoryTypeOutcome.NOT_SUPPORTED, load.outcomeFor(MediaType.FILM))
    assertEquals(CategoryTypeOutcome.NOT_SUPPORTED, load.outcomeFor(MediaType.SERIE_TV))
  }

  // ── 8. Parametri della richiesta ──────────────────────────────────────────

  @Test
  fun iParametriDellaRichiestaSonoQuelliPrevistiDalManifest(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.params","name":"Params","version":"1","resources":["catalog"],
       "types":["movie"],"catalogs":[
        {"type":"movie","id":"discover_movies","name":"Movies",
         "extra":[{"name":"genre","isRequired":true,"options":["Science Fiction","Horror"]}]}
      ]}
      """,
      catalogBody = { """{"metas":[{"id":"tt1","type":"movie","name":"X"}]}""" }
    )
    val keywords = listOf("Fantascienza", "Sci-Fi", "Science Fiction")
    val request = StremioCatalogRepository.findCategoryRequests(keywords).single()

    assertEquals(
      "Va inviata l'opzione ESATTA del manifest, mai un alias dell'app",
      "Science Fiction",
      request.extra[EXTRA_GENRE]
    )
    val path = StremioAddonRepository.buildCatalogPath(
      type = request.target.type,
      id = request.target.catalogId,
      extra = request.extra
    )
    assertEquals("catalog/movie/discover_movies/genre=Science%20Fiction.json", path)

    // Il sanitize mantiene il filtro dichiarato e ne elimina uno non dichiarato.
    assertEquals(
      mapOf(EXTRA_GENRE to "Horror"),
      StremioCatalogEngine.sanitizeExtra(request.target.catalog, mapOf(EXTRA_GENRE to "Horror"))
    )
    assertTrue(
      "Un genere non dichiarato non deve superare il sanitize",
      StremioCatalogEngine.sanitizeExtra(request.target.catalog, mapOf(EXTRA_GENRE to "Horrore")).isEmpty()
    )
  }

  // ── 9. Il filtro per tipo esclude sempre l'altro tipo ────────────────────

  @Test
  fun unTipoNonRiceveMaiIlGenereDellAltroTipo(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.split","name":"Split","version":"1","resources":["catalog"],
       "types":["movie","series"],"catalogs":[
        {"type":"movie","id":"discover_movies","name":"Movies",
         "extra":[{"name":"genre","isRequired":true,"options":["Horror","Thriller"]}]},
        {"type":"series","id":"discover_series","name":"Series",
         "extra":[{"name":"genre","isRequired":true,"options":["Mystery","Drama"]}]}
      ]}
      """,
      catalogBody = { path ->
        if (path.contains("series")) {
          """{"metas":[{"id":"tt2","name":"Solo Serie"}]}"""
        } else {
          """{"metas":[{"id":"tt1","type":"movie","name":"Solo Film"}]}"""
        }
      }
    )
    val keywords = listOf("Thriller", "Suspense", "Mystery")
    val requests = StremioCatalogRepository.findCategoryRequests(keywords)

    assertEquals("Thriller", requests.single { it.target.isMovie }.extra[EXTRA_GENRE])
    assertEquals("Mystery", requests.single { it.target.isSeries }.extra[EXTRA_GENRE])

    val load = StremioCatalogRepository.loadCategoryCatalogs(keywords)
    val movie = load.entries.filter { it.section.mediaType == MediaType.FILM }.flatMap { it.section.items }
    val series = load.entries.filter { it.section.mediaType == MediaType.SERIE_TV }.flatMap { it.section.items }
    assertTrue("Il film è solo nel ramo film", movie.all { it.type == MediaType.FILM })
    assertTrue("La serie è solo nel ramo serie", series.all { it.type == MediaType.SERIE_TV })
    // Il tipo dei metas senza `type` viene ereditato dal CATALOGO che li ha serviti.
    assertEquals(MediaType.SERIE_TV, series.single().type)
    assertEquals(MediaType.FILM, movie.single().type)
  }

  // ── 10. Ricerca testuale e "tutti i tipi" non cambiano ────────────────────

  @Test
  fun laRicercaTestualeUsaSoloLExtraSearchSenzaGenere(): Unit = runTest(dispatcher) {
    install(
      """
      {"id":"addon.search","name":"Search","version":"1","resources":["catalog"],
       "types":["movie","series"],"catalogs":[
        {"type":"movie","id":"search_movies","name":"Risultati",
         "extra":[{"name":"search","isRequired":true}]},
        {"type":"series","id":"search_series","name":"Risultati",
         "extra":[{"name":"search","isRequired":true}]}
      ]}
      """,
      catalogBody = { """{"metas":[{"id":"tt9","type":"movie","name":"Trovato"}]}""" }
    )
    // Nessun genere è mai inviato alla ricerca testuale: solo `search`.
    val both = StremioCatalogRepository.loadCategoryCatalogs(listOf("Trovato"))
    assertTrue("Richiede search: non è un catalogo di categoria", both.matchedTargets == 0)

    val targets = StremioCatalogEngine.getAvailableCatalogs().filter { it.supportsSearch }
    assertEquals(2, targets.size)
    assertTrue(
      "Il filtro per genere non deve essere accodato alla ricerca",
      StremioCatalogEngine.sanitizeExtra(targets.first().catalog, mapOf(EXTRA_SEARCH to "x")).keys
        .none { it == EXTRA_GENRE }
    )

    // Il filtro per tipo aperto (ALL) interroga entrambi i tipi e nulla di più.
    assertTrue(targets.any { it.isSeries } && targets.any { it.isMovie })
  }

  // ── Helper ────────────────────────────────────────────────────────────────

  private val horrorKeywords = listOf("Horror")

  /**
   * Installa un addon che espone [manifestJson] e risponde ai cataloghi con
   * [catalogBody] (per default una lista vuota), senza alcuna rete esterna.
   */
  private suspend fun install(
    manifestJson: String,
    catalogBody: ((path: String) -> String)? = null
  ) {
    val server = ManifestFixture(manifestJson, catalogBody)
    fixture = server
    StremioAddonRepository.installAddon(server.start())
  }

  private class ManifestFixture(
    private val manifestJson: String,
    private val catalogBody: ((String) -> String)?
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
      val body = if (path == "/manifest.json") {
        manifestJson
      } else {
        catalogBody?.invoke(path) ?: """{"metas":[]}"""
      }
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

  private companion object {
    private const val EXTRA_GENRE = "genre"
    private const val EXTRA_SEARCH = "search"
  }
}
