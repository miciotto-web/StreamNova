package com.example.data.stremio

import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Dopo la migrazione a TMDB, le categorie ("Esplora per Genere") e la ricerca
 * testuale NON devono più ricadere sull'addon di sistema **Cinemeta**:
 *
 *  - [StremioCatalogRepository.findCategoryRequests] senza addon installati deve
 *    restituire una lista VUOTA: la categoria è alimentata nativamente da TMDB
 *    Discover nel ViewModel (che possiede gli id genere);
 *  - [StremioCatalogRepository.searchAddonCatalogs] senza addon restituisce una
 *    lista vuota: la ricerca nativa è TMDB (`MediaRepository.searchTmdb`);
 *  - la precedenza degli addon utente resta invariata quando un addon dichiara un
 *    catalogo per la categoria o per la ricerca.
 *
 * Non c'è rete: i manifest sono fixture locali e Cinemeta non viene interrogato.
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

  // ── 1. Categorie senza addon utente → nessun target Stremio ─────────────────

  @Test
  fun senzaAddonUtenteLaCategoriaNonRisolveTargetCinemeta(): Unit =
    runTest(testDispatcher) {
      assertTrue(
        "Nessun addon utente installato",
        StremioCatalogRepository.getAvailableTargets().isEmpty()
      )

      val requests = StremioCatalogRepository.findCategoryRequests(actionCategory.keywords)

      assertTrue(
        "La categoria non deve dipendere da Cinemeta: la sorgente nativa è TMDB",
        requests.isEmpty()
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

  // ── 2. Ricerca senza addon utente → nessun risultato Stremio ────────────────

  @Test
  fun laRicercaSenzaAddonNonInterrogaCinemeta(): Unit =
    runTest(testDispatcher) {
      val items = StremioCatalogRepository.searchAddonCatalogs("matrix", null)

      assertTrue(
        "La ricerca nativa è TMDB: nessun risultato deve arrivare da Cinemeta",
        items.isEmpty()
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
  }
}
