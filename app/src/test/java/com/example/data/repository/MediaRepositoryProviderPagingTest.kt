package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.api.TmdbApiClient
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.AppSettingsRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Bootstrap provider vs paginazione on-demand, con TMDB servito da un HTTP locale.
 *
 * Verifica misurable:
 * - il refresh iniziale chiede UNA sola pagina per provider/tipo (14 richieste totali);
 * - le pagine successive partono SOLO quando [MediaRepository.loadProviderCatalog]
 *   viene invocato, cioè quando la ProviderScreen ne ha bisogno;
 * - una seconda richiesta della stessa pagina non produce traffico (cache Room);
 * - il numero di pagine disponibili non diminuisce (fino a total_pages);
 * - i titoli già persistiti non vengono duplicati e il provider viene conservato.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MediaRepositoryProviderPagingTest {

  private companion object {
    const val TEST_KEY = "test-api-key-0000000000000000"
    const val TOTAL_PAGES = 10
  }

  private lateinit var database: StreamNovaDatabase
  private lateinit var server: FakeTmdbServer

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    database = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    MediaRepository.setDatabaseForTesting(database)
    server = FakeTmdbServer().apply { start() }
  }

  @After
  fun tearDown() {
    server.stop()
    TmdbApiClient.overrideServiceBaseUrl(null)
    MediaRepository.setDatabaseForTesting(null)
    database.close()
  }

  @Test
  fun `il bootstrap chiede solo la prima pagina provider e le successive sono on demand`() =
    runBlocking {
      MediaRepository.refreshTmdbData(TEST_KEY)

      val movieProviderRequests = server.providerMovieRequests()
      val tvProviderRequests = server.providerTvRequests()

      assertEquals("una richiesta Film per ciascun provider", 7, movieProviderRequests.size)
      assertEquals("una richiesta Serie TV per ciascun provider", 7, tvProviderRequests.size)
      assertTrue(
        "il bootstrap non deve chiedere pagine successive: ${movieProviderRequests.map { it.page }}",
        (movieProviderRequests + tvProviderRequests).all { it.page == 1 }
      )
      assertEquals(
        "nessuna pagina provider > 1 durante il refresh",
        0,
        server.allProviderRequests().count { it.page > 1 }
      )

      // La progressione persistita non viene consumata dal bootstrap: la prima
      // apertura della ProviderScreen riparte dalla pagina 1 (già in cache Room).
      val stateBeforeOpen = MediaRepository.getProviderPaginationState(8, isTv = false)
      assertEquals(1, stateBeforeOpen.nextPage)
      assertTrue(stateBeforeOpen.hasMore)

      // ── On-demand: pagina 2 richiesta solo ora ─────────────────────────────
      assertEquals(0, server.providerMoviePageRequests(providerId = "8", page = 2).size)
      val page2 = MediaRepository.loadProviderCatalog(8, isTv = false, page = 2).toList().single()
      assertEquals(2, page2.page)
      assertTrue("la pagina 2 deve contenere titoli", page2.items.isNotEmpty())
      assertTrue("total_pages=10: esistono ancora pagine", page2.hasMore)
      assertEquals(1, server.providerMoviePageRequests(providerId = "8", page = 2).size)

      val stateAfterPage2 = MediaRepository.getProviderPaginationState(8, isTv = false)
      assertEquals(3, stateAfterPage2.nextPage)
      assertTrue(stateAfterPage2.hasMore)

      // ── Nessuna richiesta duplicata per la stessa pagina ───────────────────
      MediaRepository.loadProviderCatalog(8, isTv = false, page = 2).toList()
      assertEquals(
        "la risposta in cache Room evita una seconda richiesta HTTP",
        1,
        server.providerMoviePageRequests(providerId = "8", page = 2).size
      )

      // ── Il numero di pagine disponibili non è stato ridotto ────────────────
      val lastPage = MediaRepository.loadProviderCatalog(8, isTv = false, page = TOTAL_PAGES)
        .toList()
        .single()
      assertEquals(TOTAL_PAGES, lastPage.page)
      assertFalse("l'ultima pagina dichiarata da TMDB esaurisce il catalogo", lastPage.hasMore)
      assertEquals(1, server.providerMoviePageRequests(providerId = "8", page = TOTAL_PAGES).size)

      // ── Deduplicazione e conservazione del provider ────────────────────────
      val overlapId = "tmdb_m_${server.movieId("8", page = 1, index = 0)}"
      val overlapRows = database.cachedMediaDao()
        .getAllCachedMedia()
        .filter { it.id == overlapId }
      assertEquals("un titolo ripetuto tra pagine resta una sola riga", 1, overlapRows.size)
      assertTrue(
        "il provider associato dal catalogo non si perde",
        overlapRows.single().provider?.contains("netflix") == true
      )
    }

  // ── Fixture: TMDB locale deterministico ───────────────────────────────────

  private data class Request(val path: String, val query: String) {
    fun param(name: String): String? =
      query.split("&")
        .firstOrNull { it.substringBefore("=") == name }
        ?.substringAfter("=", "")
        ?.takeIf { it.isNotEmpty() }

    val page: Int get() = param("page")?.toIntOrNull() ?: 1
  }

  private inner class FakeTmdbServer {
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    private val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start() {
      httpServer.createContext("/") { exchange -> handle(exchange) }
      httpServer.start()
      TmdbApiClient.overrideServiceBaseUrl("http://127.0.0.1:${httpServer.address.port}/3/")
    }

    fun stop() = httpServer.stop(0)

    fun providerMovieRequests(): List<Request> =
      synchronized(requests) {
        requests.filter {
          it.path == "/3/discover/movie" && it.param("with_watch_providers") != null
        }
      }

    fun providerTvRequests(): List<Request> =
      synchronized(requests) {
        requests.filter {
          it.path == "/3/discover/tv" &&
            (it.param("with_watch_providers") != null || it.param("with_networks") != null)
        }
      }

    fun allProviderRequests(): List<Request> = providerMovieRequests() + providerTvRequests()

    fun providerMoviePageRequests(providerId: String, page: Int): List<Request> =
      synchronized(requests) {
        requests.filter {
          it.path == "/3/discover/movie" &&
            it.param("with_watch_providers") == providerId &&
            it.page == page
        }
      }

    /** Stesso algoritmo usato dal server per gli id: il test può nominarli. */
    fun movieId(provider: String, page: Int, index: Int): Int =
      providerSeed(provider) * 1_000 + page * 10 + index

    private fun providerSeed(provider: String): Int =
      provider.fold(0) { acc, ch -> (acc * 31 + ch.code) and 0x3FFF }

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.path
      val query = exchange.requestURI.query.orEmpty()
      val request = Request(path, query)
      requests += request
      try {
        val body = when {
          path == "/3/trending/movie/week" -> movieBody(request.page, provider = null)
          path == "/3/trending/tv/week" -> tvBody(request.page, provider = null)
          path == "/3/movie/popular" -> movieBody(request.page, provider = null)
          path == "/3/tv/popular" -> tvBody(request.page, provider = null)
          path == "/3/movie/top_rated" -> movieBody(request.page, provider = null)
          path == "/3/tv/top_rated" -> tvBody(request.page, provider = null)
          path == "/3/discover/movie" -> {
            val provider = request.param("with_watch_providers")
            movieBody(request.page, provider)
          }
          path == "/3/discover/tv" -> {
            val provider = request.param("with_watch_providers")
              ?: request.param("with_networks")?.let { "network:$it" }
            tvBody(request.page, provider)
          }
          else -> {
            respond(exchange, 404, "")
            return
          }
        }
        respond(exchange, 200, body)
      } finally {
        exchange.close()
      }
    }

    /**
     * Pagina Film: 10 titoli nuovi. Dalla pagina 2 in poi il primo titolo della
     * pagina precedente viene ripetuto, così il test può verificare la dedup.
     */
    private fun movieBody(page: Int, provider: String?): String {
      val ids = mutableListOf<Int>()
      if (provider != null && page > 1) {
        ids += movieId(provider, page - 1, index = 0)
      }
      repeat(10) { index ->
        ids += if (provider != null) {
          movieId(provider, page, index)
        } else {
          900_000 + page * 100 + index
        }
      }
      return movieResponse(page, ids)
    }

    private fun tvBody(page: Int, provider: String?): String {
      val ids = mutableListOf<Int>()
      if (provider != null && page > 1) {
        ids += tvId(provider, page - 1, index = 0)
      }
      repeat(10) { index ->
        ids += if (provider != null) {
          tvId(provider, page, index)
        } else {
          800_000 + page * 100 + index
        }
      }
      return tvResponse(page, ids)
    }

    private fun tvId(provider: String, page: Int, index: Int): Int =
      providerSeed(provider) * 1_000 + page * 10 + index

    private fun movieResponse(page: Int, ids: List<Int>): String =
      """{"page":$page,"results":[${ids.joinToString(",") { id ->
        """{"id":$id,"title":"Film $id","original_title":"Film $id","overview":"Sinossi Film $id",""" +
          """"backdrop_path":"/bd$id.jpg","poster_path":"/po$id.jpg","release_date":"2026-10-15",""" +
          """"vote_average":8.1,"genre_ids":[28,878],"original_language":"it"}"""
      }}],"total_pages":TOTAL_PLACEHOLDER,"total_results":1000}"""
        .replace("TOTAL_PLACEHOLDER", TOTAL_PAGES.toString())

    private fun tvResponse(page: Int, ids: List<Int>): String =
      """{"page":$page,"results":[${ids.joinToString(",") { id ->
        """{"id":$id,"name":"Serie $id","original_name":"Serie $id","overview":"Sinossi Serie $id",""" +
          """"backdrop_path":"/bd$id.jpg","poster_path":"/po$id.jpg","first_air_date":"2026-03-01",""" +
          """"vote_average":7.5,"genre_ids":[35],"original_language":"it"}"""
      }}],"total_pages":TOTAL_PLACEHOLDER,"total_results":1000}"""
        .replace("TOTAL_PLACEHOLDER", TOTAL_PAGES.toString())

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
  }
}
