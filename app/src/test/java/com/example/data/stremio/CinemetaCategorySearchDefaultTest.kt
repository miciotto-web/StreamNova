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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
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
 * Le categorie ("Esplora per Genere") e la ricerca testuale devono ricadere
 * sull'addon di SISTEMA **Cinemeta** quando nessun addon utente copre la richiesta:
 *
 *  - [StremioCatalogRepository.findCategoryRequests] senza addon installati deve
 *    risolvere il catalogo Popolari (`top`) di Cinemeta per Film e Serie TV,
 *    inviando `genre={NomeGenere}` (es. Action);
 *  - la ricerca testuale di default usa lo stesso catalogo con l'extra `search`;
 *  - la precedenza degli addon utente resta invariata: se un addon dichiara un
 *    catalogo per la categoria, Cinemeta NON viene aggiunto.
 *
 * I test verificano la RISOLUZIONE dei target (nessuna rete): la sorgente Cinemeta
 * è deterministica perché deriva dal manifest statico [CinemetaAddon].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CinemetaCategorySearchDefaultTest {

  private lateinit var testDispatcher: TestDispatcher
  private var fixture: AddonFixture? = null

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
    clearAddons()
    Dispatchers.resetMain()
  }

  private fun clearAddons() {
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioCatalogRepository.clearCache()
  }

  private val actionCategory = GLOBAL_SEARCH_CATEGORIES.first { it.id == "azione" }

  // ── 1. Categorie senza addon utente → Cinemeta ──────────────────────────────

  @Test
  fun senzaAddonUtenteLaCategoriaActionRisolveCinemetaPerFilmESerie(): Unit =
    runTest(testDispatcher) {
      val requests = StremioCatalogRepository.findCategoryRequests(actionCategory.keywords)

      assertEquals("Action deve risolvere film + serie su Cinemeta", 2, requests.size)
      assertTrue(
        "Tutti i target devono provenire dall'addon di sistema Cinemeta",
        requests.all { it.target.addonId == CinemetaAddon.MANIFEST_ID }
      )
      assertTrue(
        "Deve usare il catalogo Popolari (top) di Cinemeta",
        requests.all { it.target.catalogId == CinemetaAddon.CATALOG_POPULAR }
      )
      assertTrue(
        "Tutti i target devono filtrare per genre=Action",
        requests.all { it.extra[EXTRA_GENRE] == "Action" }
      )
      assertTrue("Un target film", requests.any { it.target.isMovie })
      assertTrue("Un target serie", requests.any { it.target.isSeries })
      assertFalse(
        "I cataloghi di sola ricerca non possono essere fonte primaria di una categoria",
        requests.any { it.target.requiresSearch }
      )
    }

  @Test
  fun iCataloghiDiGenereCinemetaCopronoFilmESerieConIlFiltroGenre(): Unit =
    runTest(testDispatcher) {
      val movie = CinemetaAddon.findCatalog(MediaType.FILM, CinemetaAddon.CATALOG_POPULAR)
      val series = CinemetaAddon.findCatalog(MediaType.SERIE_TV, CinemetaAddon.CATALOG_POPULAR)

      assertTrue("Il catalogo film dichiara il genere", movie?.supportsGenre() == true)
      assertTrue("Il catalogo serie dichiara il genere", series?.supportsGenre() == true)
      assertTrue(
        "Action è un'opzione di genere dichiarata",
        movie!!.genreOptions().contains("Action") && series!!.genreOptions().contains("Action")
      )

      val targets = StremioCatalogRepository.defaultCinemetaGenreTargets()
      assertEquals(2, targets.size)
      assertTrue(targets.any { it.isMovie } && targets.any { it.isSeries })
    }

  // ── 2. Ricerca senza addon utente → Cinemeta ────────────────────────────────

  @Test
  fun laRicercaDiDefaultUsaIlCatalogoCinemetaConExtraSearch(): Unit =
    runTest(testDispatcher) {
      val catalogs = CinemetaAddon.searchCatalogs()

      assertEquals("Film + Serie dal catalogo di ricerca Cinemeta", 2, catalogs.size)
      assertTrue(catalogs.all { it.effectiveId == CinemetaAddon.CATALOG_POPULAR })
      assertTrue("Il catalogo deve dichiarare l'extra search", catalogs.all { it.supportsSearch() })

      val movie = CinemetaAddon.searchCatalogs(MediaType.FILM).single()
      assertEquals(TYPE_MOVIE, movie.effectiveType)

      // L'extra `search` viene effettivamente mantenuto verso l'endpoint.
      val sanitized = StremioCatalogEngine.sanitizeExtra(movie, mapOf(EXTRA_SEARCH to "matrix"))
      assertEquals("matrix", sanitized[EXTRA_SEARCH])

      val targets = StremioCatalogRepository.defaultCinemetaSearchTargets()
      assertEquals(2, targets.size)
      assertTrue(targets.all { it.addonId == CinemetaAddon.MANIFEST_ID && it.supportsSearch })
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

  // ── Fixture addon locale ────────────────────────────────────────────────────

  /** Server locale che espone un manifest con un catalogo film per la categoria Action. */
  private class AddonFixture(private val manifestJson: String) {
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
  }
}
