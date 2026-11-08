package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.api.TmdbApiClient
import com.example.data.local.CatalogProgressEntity
import com.example.data.local.StreamNovaDatabase
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
 * Pipeline COMPLETA del catalogo Provider su TMDB (server HTTP locale multi-pagina):
 * richiesta pagina, interpretazione di `total_pages`, accodamento senza sovrascrittura,
 * deduplicazione, errori di rete, catalogo vuoto, separazione Film/Serie e query
 * canonica di HBO / Max.
 *
 * Il difetto di partenza: la ProviderScreen chiedeva solo la prima pagina (o la
 * perdeva) e il catalogo HBO era interrogato con il provider TMDB 384, che in
 * `watch_region=IT` restituisce 0 risultati. La progressione veniva poi persistita
 * come "esaurita", rendendo il catalogo definitivamente incompleto.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProviderCatalogPipelineTest {

  private companion object {
    const val TEST_KEY = "test-api-key-0000000000000000"
    const val PAGE_SIZE = 10
    const val SHARED_ID = 424242
  }

  private lateinit var database: StreamNovaDatabase
  private lateinit var server: MultiPageTmdbServer

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    database = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    MediaRepository.setDatabaseForTesting(database)
    server = MultiPageTmdbServer().apply { start() }
  }

  @After
  fun tearDown() {
    server.stop()
    TmdbApiClient.overrideServiceBaseUrl(null)
    MediaRepository.setDatabaseForTesting(null)
    database.close()
  }

  private fun load(providerId: Int, isTv: Boolean, page: Int): MediaRepository.ProviderCatalogPage =
    runBlocking {
      MediaRepository.loadProviderCatalog(providerId, isTv, page).toList().single()
    }

  private fun catalogItems(type: MediaType) =
    MediaRepository.mediaList.value.filter { it.type == type }

  // ── 1. più pagine accodate, nessuna sovrascrittura ─────────────────────────

  @Test
  fun `piu pagine vengono accodate senza sovrascrivere le precedenti`() {
    server.movieTotalPages = 3

    val page1 = load(8, isTv = false, page = 1)
    val page2 = load(8, isTv = false, page = 2)
    val page3 = load(8, isTv = false, page = 3)

    assertEquals(PAGE_SIZE, page1.items.size)
    assertEquals(PAGE_SIZE, page2.items.size)
    assertEquals(PAGE_SIZE, page3.items.size)
    assertTrue("page 1 di 3 deve proseguire", page1.hasMore)
    assertTrue("page 2 di 3 deve proseguire", page2.hasMore)
    assertFalse("page 3 di 3 esaurisce il catalogo", page3.hasMore)

    val items = catalogItems(MediaType.FILM)
    assertEquals("tutte le pagine devono restare in catalogo", 3 * PAGE_SIZE, items.size)
    assertEquals("nessun id duplicato", 3 * PAGE_SIZE, items.map { it.id }.toSet().size)
    assertTrue(
      "ogni titolo deve conservare il provider del catalogo",
      items.all { it.provider?.contains("netflix") == true }
    )
  }

  // ── 2. total_pages e limite tecnico TMDB ──────────────────────────────────

  @Test
  fun `total_pages viene interpretato correttamente e non e troncato a 25 pagine`() {
    val page30Of100 = MediaRepository.ProviderCatalogPage(
      providerId = 8,
      isTv = false,
      page = 30,
      totalPages = 100,
      items = emptyList()
    )
    assertTrue("la pagina 30 deve essere raggiungibile", page30Of100.hasMore)
    assertEquals(100, page30Of100.effectiveTotalPages)

    val lastPage = page30Of100.copy(page = 100)
    assertFalse("l'ultima pagina dichiarata esaurisce il catalogo", lastPage.hasMore)

    val capped = page30Of100.copy(page = 600, totalPages = 900)
    assertFalse("oltre il tetto tecnico TMDB non si prosegue", capped.hasMore)

    assertEquals(
      "HBO / Max in Italia usa il provider 1899, non il solo 384",
      "1899|384",
      MediaRepository.providerTmdbQuery(384).providerIds
    )
    assertEquals("IT", MediaRepository.providerTmdbQuery(384).watchRegion)
    assertEquals("1899|384", MediaRepository.providerTmdbQuery(1899).providerIds)
    assertEquals("8", MediaRepository.providerTmdbQuery(8).providerIds)
  }

  // ── 3. deduplicazione tra pagine consecutive ───────────────────────────────

  @Test
  fun `un titolo ripetuto tra due pagine resta una sola card`() {
    server.movieTotalPages = 2
    server.repeatFirstItemFromPreviousPage = true

    load(8, isTv = false, page = 1)
    load(8, isTv = false, page = 2)

    val items = catalogItems(MediaType.FILM)
    // 10 + 10 con il primo titolo della pagina 2 ripetuto dalla pagina 1.
    assertEquals(2 * PAGE_SIZE - 1, items.size)
    assertEquals(items.size, items.map { it.id }.toSet().size)

    val rows = runBlocking { database.cachedMediaDao().getAllCachedMedia() }
    val duplicatedRows = rows.groupBy { it.id }.filterValues { it.size > 1 }
    assertTrue("Room non deve contenere righe duplicate: $duplicatedRows", duplicatedRows.isEmpty())
  }

  // ── 4. errore su pagina successiva: nessuna progressione persa ────────────

  @Test
  fun `errore della pagina successiva non avanza ne esaurisce la progressione`() {
    server.movieTotalPages = 3
    val page1 = load(8, isTv = false, page = 1)
    assertTrue(page1.hasMore)
    assertEquals(PAGE_SIZE, catalogItems(MediaType.FILM).size)

    server.failMoviesFromPage = 2
    val failed = load(8, isTv = false, page = 2)
    assertFalse("la richiesta fallita non è una pagina valida", failed.requestSucceeded)
    assertTrue("la pagina fallita non contiene titoli", failed.items.isEmpty())

    val stateAfterFailure = runBlocking {
      MediaRepository.getProviderPaginationState(8, isTv = false)
    }
    assertEquals(
      "il cursore NON deve avanzare saltando la pagina fallita",
      2,
      stateAfterFailure.nextPage
    )
    assertTrue("il catalogo non deve essere dichiarato esaurito", stateAfterFailure.hasMore)
    assertEquals(
      "i titoli della pagina 1 restano in catalogo",
      PAGE_SIZE,
      catalogItems(MediaType.FILM).size
    )

    // Retry della STESSA pagina dopo il ripristino della rete.
    server.failMoviesFromPage = null
    val retried = load(8, isTv = false, page = 2)
    assertTrue(retried.requestSucceeded)
    assertEquals(2, retried.page)
    assertTrue(retried.hasMore)

    val stateAfterRetry = runBlocking {
      MediaRepository.getProviderPaginationState(8, isTv = false)
    }
    assertEquals(3, stateAfterRetry.nextPage)
    assertTrue(stateAfterRetry.hasMore)
    assertEquals(2 * PAGE_SIZE, catalogItems(MediaType.FILM).size)
  }

  // ── 5. catalogo vuoto ──────────────────────────────────────────────────────

  @Test
  fun `catalogo vuoto non produce crash e non dichiara pagine successive`() {
    server.emptyCatalog = true
    // Un catalogo realmente vuoto dichiara una sola pagina (nessun risultato).
    server.movieTotalPages = 1

    val page = load(8, isTv = false, page = 1)

    assertTrue("la richiesta è riuscita ma senza risultati", page.requestSucceeded)
    assertTrue(page.items.isEmpty())
    assertFalse("un catalogo vuoto non ha altre pagine", page.hasMore)
    assertTrue(catalogItems(MediaType.FILM).isEmpty())
  }

  @Test
  fun `una progressione esaurita con total_pages sospetto viene verificata di nuovo`() {
    runBlocking {
      database.catalogProgressDao().upsert(
        CatalogProgressEntity(
          providerId = 384,
          mediaType = "movie",
          nextPage = 2,
          totalPages = 1
        )
      )
    }

    val state = runBlocking { MediaRepository.getProviderPaginationState(384, isTv = false) }
    assertEquals(
      "lo stato 'esaurito' di un catalogo a 1 pagina va riverificato (recupero HBO 384)",
      1,
      state.nextPage
    )
    assertTrue(state.hasMore)
  }

  // ── 6. Film e Serie TV separati ────────────────────────────────────────────

  @Test
  fun `film e serie tv usano endpoint e stati di paginazione indipendenti`() {
    server.movieTotalPages = 2
    server.tvTotalPages = 2

    val moviePage1 = load(8, isTv = false, page = 1)
    val tvPage1 = load(8, isTv = true, page = 1)
    val moviePage2 = load(8, isTv = false, page = 2)

    assertTrue(moviePage1.items.all { it.type == MediaType.FILM })
    assertTrue(tvPage1.items.all { it.type == MediaType.SERIE_TV })
    assertTrue(moviePage1.hasMore)
    assertTrue(tvPage1.hasMore)

    // Lo stesso ID numerico TMDB esiste in entrambi i cataloghi: le identità dell'app
    // devono restare distinte (`tmdb_m_` vs `tmdb_tv_`).
    val movieIds = catalogItems(MediaType.FILM).map { it.id }
    val tvIds = catalogItems(MediaType.SERIE_TV).map { it.id }
    assertTrue(movieIds.any { it.startsWith("tmdb_m_") })
    assertTrue(tvIds.any { it.startsWith("tmdb_tv_") })
    val sharedNumeric = moviePage1.items.first().tmdbId
    assertNotNull(sharedNumeric)
    assertTrue(tvPage1.items.any { it.tmdbId == sharedNumeric })

    assertEquals(
      "le pagine Film caricate devono essere accodate",
      2 * PAGE_SIZE,
      catalogItems(MediaType.FILM).size
    )
    val tvState = runBlocking { MediaRepository.getProviderPaginationState(8, isTv = true) }
    assertEquals(2, tvState.nextPage)
    val movieState = runBlocking { MediaRepository.getProviderPaginationState(8, isTv = false) }
    assertEquals(3, movieState.nextPage)
  }

  // ── 7. query canonica HBO / Max ────────────────────────────────────────────

  @Test
  fun `il catalogo HBO interroga il provider 1899 in Italia per film e serie`() {
    server.movieTotalPages = 2
    server.tvTotalPages = 2

    val movies = load(384, isTv = false, page = 1)
    val series = load(384, isTv = true, page = 1)

    val providerRequests = server.discoverRequests()
    assertEquals(2, providerRequests.size)
    assertTrue(
      "HBO deve usare 1899|384, mai il solo 384: ${providerRequests.map { it.params }}",
      providerRequests.all { it.param("with_watch_providers") == "1899|384" }
    )
    assertTrue(
      "HBO / Max va cercato nella regione italiana",
      providerRequests.all { it.param("watch_region") == "IT" }
    )
    assertTrue(movies.items.isNotEmpty())
    assertTrue(series.items.isNotEmpty())
    assertTrue(movies.items.all { it.provider?.contains("hbo") == true })
    assertTrue(series.items.all { it.provider?.contains("hbo") == true })
  }

  // ── 8. un titolo presente su più provider conserva TUTTI i tag ────────────

  @Test
  fun `un titolo presente in due cataloghi conserva entrambi i provider`() {
    server.movieTotalPages = 2
    server.includeSharedIdOnFirstPage = true

    load(8, isTv = false, page = 1)   // netflix
    load(337, isTv = false, page = 1) // disney

    val shared = MediaRepository.mediaList.value
      .filter { it.type == MediaType.FILM && it.tmdbId == SHARED_ID }
    assertEquals("il titolo condiviso deve restare una sola card", 1, shared.size)
    val tags = shared.single().provider.orEmpty()
    assertTrue("tag netflix mancante: $tags", tags.contains("netflix"))
    assertTrue("tag disney mancante: $tags", tags.contains("disney"))
  }

  @Test
  fun `mergeProviderTags unisce senza duplicati e senza perdere l ordine`() {
    assertEquals("netflix,hbo", MediaRepository.mergeProviderTags("netflix", "hbo"))
    assertEquals("netflix", MediaRepository.mergeProviderTags("netflix", "NETFLIX"))
    assertEquals("hbo,netflix", MediaRepository.mergeProviderTags("hbo,netflix", "netflix"))
    assertEquals("hbo", MediaRepository.mergeProviderTags(null, "hbo"))
    assertEquals("hbo", MediaRepository.mergeProviderTags("hbo", null))
    assertEquals("hbo,prime,disney", MediaRepository.mergeProviderTags("hbo,prime", "disney,prime"))
  }

  // ── Fixture: TMDB locale multi-pagina ─────────────────────────────────────

  private data class Request(val path: String, val params: Map<String, String>) {
    fun param(name: String): String? = params[name]
    val page: Int get() = param("page")?.toIntOrNull() ?: 1
  }

  private inner class MultiPageTmdbServer {
    var movieTotalPages = 3
    var tvTotalPages = 3
    var emptyCatalog = false
    var repeatFirstItemFromPreviousPage = false
    var includeSharedIdOnFirstPage = false
    var failMoviesFromPage: Int? = null
    var failTvFromPage: Int? = null

    private val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    private val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start() {
      httpServer.createContext("/") { exchange -> handle(exchange) }
      httpServer.start()
      TmdbApiClient.overrideServiceBaseUrl("http://127.0.0.1:${httpServer.address.port}/3/")
    }

    fun stop() = httpServer.stop(0)

    fun discoverRequests(): List<Request> = synchronized(requests) {
      requests.filter { it.path.startsWith("/3/discover/") }
    }

    fun requestsFor(path: String): List<Request> = synchronized(requests) {
      requests.filter { it.path == path }
    }

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.path
      val params = parseParams(exchange.requestURI.query.orEmpty())
      val request = Request(path, params)
      requests += request
      try {
        when (path) {
          "/3/discover/movie" -> {
            if (emptyCatalog) {
              respond(exchange, 200, movieBody(request, emptyList(), movieTotalPages))
              return
            }
            if (failMoviesFromPage != null && request.page >= failMoviesFromPage!!) {
              respond(exchange, 500, "")
              return
            }
            respond(exchange, 200, movieBody(request, idsFor(request, PAGE_SIZE), movieTotalPages))
          }
          "/3/discover/tv" -> {
            if (emptyCatalog) {
              respond(exchange, 200, tvBody(request, emptyList(), tvTotalPages))
              return
            }
            if (failTvFromPage != null && request.page >= failTvFromPage!!) {
              respond(exchange, 500, "")
              return
            }
            respond(exchange, 200, tvBody(request, idsFor(request, PAGE_SIZE), tvTotalPages))
          }
          else -> respond(exchange, 404, "")
        }
      } finally {
        exchange.close()
      }
    }

    private fun parseParams(query: String): Map<String, String> =
      query.split('&')
        .mapNotNull { pair ->
          if (pair.isBlank() || !pair.contains('=')) return@mapNotNull null
          val name = decode(pair.substringBefore('='))
          val value = decode(pair.substringAfter('='))
          name to value
        }
        .toMap()

    private fun decode(value: String): String =
      try {
        URLDecoder.decode(value, "UTF-8")
      } catch (_: Exception) {
        value
      }

    private fun idsFor(request: Request, size: Int): List<Int> {
      val provider = request.param("with_watch_providers").orEmpty()
      val seed = seedFor(provider)
      val ids = MutableList(size) { index -> seed * 100_000 + request.page * 100 + index }
      if (repeatFirstItemFromPreviousPage && request.page > 1) {
        ids[0] = seed * 100_000 + (request.page - 1) * 100
      }
      if (includeSharedIdOnFirstPage && request.page == 1) {
        ids[0] = SHARED_ID
      }
      return ids
    }

    private fun seedFor(provider: String): Int =
      provider.filter { it.isDigit() }.take(4).toIntOrNull() ?: 1

    private fun movieBody(request: Request, ids: List<Int>, totalPages: Int): String =
      """{"page":${request.page},"results":[${ids.joinToString(",") { id ->
        """{"id":$id,"title":"Film $id","original_title":"Film $id",""" +
          """"overview":"Sinossi Film $id","backdrop_path":"/bd$id.jpg","poster_path":"/po$id.jpg",""" +
          """"release_date":"2026-10-15","vote_average":8.1,"genre_ids":[28,878],""" +
          """"original_language":"it"}"""
      }}],"total_pages":$totalPages,"total_results":${totalPages * ids.size}}"""

    private fun tvBody(request: Request, ids: List<Int>, totalPages: Int): String =
      """{"page":${request.page},"results":[${ids.joinToString(",") { id ->
        """{"id":$id,"name":"Serie $id","original_name":"Serie $id",""" +
          """"overview":"Sinossi Serie $id","backdrop_path":"/bd$id.jpg","poster_path":"/po$id.jpg",""" +
          """"first_air_date":"2026-03-01","vote_average":7.5,"genre_ids":[35],""" +
          """"original_language":"it"}"""
      }}],"total_pages":$totalPages,"total_results":${totalPages * ids.size}}"""

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
  }
}
