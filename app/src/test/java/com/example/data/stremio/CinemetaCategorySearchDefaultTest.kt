package com.example.data.stremio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaType
import com.example.data.repository.StremioCatalogRepository
import com.example.ui.screens.GLOBAL_SEARCH_CATEGORIES
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Ripristino di Cinemeta come sorgente di default delle CATEGORIE
 * ("Esplora per Genere") e della RICERCA testuale quando nessun addon utente
 * copre la richiesta:
 *
 *  - [StremioCatalogRepository.findCategoryRequests] senza addon installati deve
 *    restituire i cataloghi `top` di Cinemeta per Film e Serie TV, con il filtro
 *    `genre` realmente dichiarato dal manifest;
 *  - [StremioCatalogRepository.searchAddonCatalogs] senza risultati dagli addon
 *    usa i cataloghi `top` di Cinemeta con extra `search`; il ViewModel continua a
 *    interrogare TMDB in parallelo come completamento;
 *  - la precedenza degli addon utente resta invariata quando un addon dichiara un
 *    catalogo per la categoria o per la ricerca.
 *
 * Non c'è rete: manifest e risposte catalogo sono fixture locali; la base URL di
 * Cinemeta è puntata al server di test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CinemetaCategorySearchDefaultTest {

  private lateinit var testDispatcher: TestDispatcher
  private var fixture: AddonFixture? = null
  private var cinemetaFixture: CinemetaSearchFixture? = null

  @Before
  fun setUp() {
    testDispatcher = StandardTestDispatcher()
    Dispatchers.setMain(testDispatcher)
    clearAddons()
  }

  @After
  fun tearDown() {
    fixture?.stop()
    fixture = null
    cinemetaFixture?.stop()
    cinemetaFixture = null
    StremioCatalogRepository.resetCinemetaBaseUrl()
    clearAddons()
    Dispatchers.resetMain()
  }

  private fun clearAddons() {
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioCatalogRepository.clearCache()
  }

  private val actionCategory = GLOBAL_SEARCH_CATEGORIES.first { it.id == "azione" }

  // ── 1. Categorie senza addon utente → Cinemeta è la sorgente ────────────────

  @Test
  fun senzaAddonUtenteLaCategoriaRisolveICataloghiCinemeta(): Unit =
    runTest(testDispatcher) {
      assertTrue(
        "Nessun addon utente installato",
        StremioCatalogRepository.getAvailableTargets().isEmpty()
      )

      val requests = StremioCatalogRepository.findCategoryRequests(actionCategory.keywords)

      assertEquals("Film e Serie TV dal catalogo `top` di Cinemeta", 2, requests.size)
      requests.forEach { request ->
        assertEquals(CinemetaAddon.MANIFEST_ID, request.target.addonId)
        assertEquals(CinemetaAddon.DISPLAY_NAME, request.target.addonName)
        assertEquals(CinemetaAddon.CATALOG_POPULAR, request.target.catalogId)
        assertEquals(
          "Il filtro genre deve usare l'opzione dichiarata dal manifest",
          "Action",
          request.extra[EXTRA_GENRE]
        )
      }
      assertEquals(
        setOf(MediaType.FILM, MediaType.SERIE_TV),
        requests.map { StremioCatalogRepository.toMediaType(it.target.type) }.toSet()
      )
    }

  @Test
  fun unAddonSenzaCoperturaDelGenereNonFaComparireCinemeta(): Unit =
    runTest(testDispatcher) {
      val server = AddonFixture(GENERIC_MOVIE_MANIFEST)
      fixture = server
      StremioAddonRepository.installAddon(server.start())

      val requests = StremioCatalogRepository.findCategoryRequests(actionCategory.keywords)

      assertTrue(
        "Nessun catalogo dell'addon copre Azione e Cinemeta non è un fallback: ${requests.map { it.target.catalogId }}",
        requests.isEmpty()
      )
    }

  // ── 2. Ricerca senza risultati dagli addon → Cinemeta ───────────────────────

  @Test
  fun laRicercaSenzaAddonUsaICataloghiCinemeta(): Unit =
    runTest(testDispatcher) {
      val cinemeta = CinemetaSearchFixture()
      cinemetaFixture = cinemeta
      cinemeta.start()

      val items = StremioCatalogRepository.searchAddonCatalogs("matrix", null)

      assertTrue("La ricerca deve restituire risultati Cinemeta", items.isNotEmpty())
      assertEquals(
        setOf(MediaType.FILM, MediaType.SERIE_TV),
        items.map { it.type }.toSet()
      )
      assertTrue("Id IMDb attesi: ${items.map { it.id }}", items.all { it.id.startsWith("tt") })
      assertTrue(
        "Ricerca film Cinemeta attesa: ${cinemeta.requested}",
        cinemeta.requested.any { it.contains("/catalog/movie/top/search=matrix.json") }
      )
      assertTrue(
        "Ricerca serie Cinemeta attesa: ${cinemeta.requested}",
        cinemeta.requested.any { it.contains("/catalog/series/top/search=matrix.json") }
      )
    }

  // ── 3. Precedenza addon utente invariata ────────────────────────────────────

  @Test
  fun conUnAddonUtenteCheCopreLaCategoriaCinemetaNonInterviene(): Unit =
    runTest(testDispatcher) {
      val server = AddonFixture(ACTION_MANIFEST)
      fixture = server
      val baseUrl = server.start()
      StremioAddonRepository.installAddon(baseUrl)

      val requests = StremioCatalogRepository.findCategoryRequests(actionCategory.keywords)

      assertEquals("Solo il catalogo dell'addon utente", 1, requests.size)
      assertNotEquals(CinemetaAddon.MANIFEST_ID, requests.single().target.addonId)
      assertEquals("action_movies", requests.single().target.catalogId)
      assertFalse(
        "Cinemeta non deve comparire quando un addon utente copre la categoria",
        requests.any { it.target.addonId == CinemetaAddon.MANIFEST_ID }
      )
    }

  @Test
  fun laRicercaConUnAddonUtenteNonRicadeSuCinemeta(): Unit =
    runTest(testDispatcher) {
      val server = AddonFixture(
        SEARCH_MANIFEST,
        catalogBody = """{"metas":[{"id":"tt777777","type":"movie","name":"Da Addon"}]}"""
      )
      fixture = server
      StremioAddonRepository.installAddon(server.start())
      val cinemeta = CinemetaSearchFixture()
      cinemetaFixture = cinemeta
      cinemeta.start()

      val items = StremioCatalogRepository.searchAddonCatalogs("matrix", MediaType.FILM)

      assertEquals("Risultato dell'addon utente", listOf("Da Addon"), items.map { it.title })
      assertTrue(
        "Cinemeta non deve essere interrogato se l'addon produce risultati: ${cinemeta.requested}",
        cinemeta.requested.isEmpty()
      )
    }

  @Test
  fun categorieSenzaGenereCinemetaNonVengonoInventate(): Unit =
    runTest(testDispatcher) {
      // "Musica" non è un genere dichiarato da Cinemeta (né film né serie):
      // la categoria non deve inventare un'opzione e resta al fallback TMDB.
      val musica = GLOBAL_SEARCH_CATEGORIES.first { it.id == "musica" }
      assertTrue(
        "Cinemeta non dichiara Music: ${StremioCatalogRepository.findCategoryRequests(musica.keywords)}",
        StremioCatalogRepository.findCategoryRequests(musica.keywords).isEmpty()
      )

      // "Reality" esiste solo tra i generi SERIE: nessun target film va inventato.
      val reality = GLOBAL_SEARCH_CATEGORIES.first { it.id == "reality" }
      val realityRequests = StremioCatalogRepository.findCategoryRequests(reality.keywords)
      assertEquals(1, realityRequests.size)
      assertTrue(realityRequests.single().target.isSeries)
      assertEquals("Reality-TV", realityRequests.single().extra[EXTRA_GENRE])
    }

  // ── Fixture Cinemeta locale ─────────────────────────────────────────────────

  /**
   * Server locale che risponde ai cataloghi Cinemeta usati dalla ricerca
   * (`movie/top` e `series/top` con extra `search`). La base URL dell'addon di
   * sistema viene puntata qui: nessuna richiesta esce dalla macchina.
   */
  private class CinemetaSearchFixture {
    val requested: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    fun start() {
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      StremioCatalogRepository.cinemetaBaseUrlProvider = { baseUrl }
    }

    fun stop() {
      StremioCatalogRepository.resetCinemetaBaseUrl()
      server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.path
      requested += path
      try {
        val isMovie = path.startsWith("/catalog/movie/")
        val isSeries = path.startsWith("/catalog/series/")
        if (!isMovie && !isSeries) {
          exchange.sendResponseHeaders(404, -1)
          return
        }
        val type = if (isMovie) "movie" else "series"
        val itemType = if (isMovie) "movie" else "series"
        val id = if (isMovie) "tt900001" else "tt900002"
        val title = if (isMovie) "Matrix Film" else "Matrix Serie"
        val body = """{"metas":[{"id":"$id","type":"$itemType","name":"$title",""" +
          """"poster":"https://img.example/$id.jpg","releaseInfo":"1999","description":"Sinossi $type"}]}"""
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      } finally {
        exchange.close()
      }
    }
  }

  // ── Fixture addon locale ────────────────────────────────────────────────────

  /** Server locale che espone un manifest con un catalogo film per la categoria Action. */
  private class AddonFixture(
    private val manifestJson: String,
    private val catalogBody: String? = null
  ) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start(): String {
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      return "http://127.0.0.1:${server.address.port}"
    }

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
      try {
        if (exchange.requestURI.rawPath == "/manifest.json") {
          val bytes = manifestJson.toByteArray()
          exchange.responseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } else if (catalogBody != null && exchange.requestURI.rawPath.startsWith("/catalog/")) {
          val bytes = catalogBody.toByteArray()
          exchange.responseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } else {
          exchange.sendResponseHeaders(404, -1)
        }
      } finally {
        exchange.close()
      }
    }
  }

  private companion object {
    private val ACTION_MANIFEST = """
      {
        "id": "app.test.actionaddon",
        "name": "Action Addon",
        "version": "1.0.0",
        "description": "Fixture locale con un catalogo Action",
        "resources": ["catalog"],
        "types": ["movie"],
        "idPrefixes": ["tt"],
        "catalogs": [
          {"type":"movie","id":"action_movies","name":"Azione","extra":[{"name":"skip"}]}
        ]
      }
    """.trimIndent()

    /** Addon con un catalogo generico che NON dichiara né il genere né Azione. */
    private val GENERIC_MOVIE_MANIFEST = """
      {
        "id": "app.test.genericaddon",
        "name": "Generic Addon",
        "version": "1.0.0",
        "description": "Fixture locale senza copertura di genere",
        "resources": ["catalog"],
        "types": ["movie"],
        "idPrefixes": ["tt"],
        "catalogs": [
          {"type":"movie","id":"home_movies","name":"Home Movies","extra":[{"name":"skip"}]}
        ]
      }
    """.trimIndent()

    /** Addon con un catalogo di sola ricerca testuale (extra `search` obbligatorio). */
    private val SEARCH_MANIFEST = """
      {
        "id": "app.test.searchaddon",
        "name": "Search Addon",
        "version": "1.0.0",
        "description": "Fixture locale per la ricerca testuale",
        "resources": ["catalog"],
        "types": ["movie"],
        "idPrefixes": ["tt"],
        "catalogs": [
          {"type":"movie","id":"search_movies","name":"Risultati",
           "extra":[{"name":"search","isRequired":true}]}
        ]
      }
    """.trimIndent()
  }
}
