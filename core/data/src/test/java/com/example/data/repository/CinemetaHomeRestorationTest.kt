package com.example.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaType
import com.example.data.stremio.CinemetaAddon
import com.example.data.stremio.StremioAddonRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Ripristino dei cataloghi ufficiali Cinemeta sulla Home.
 *
 * Server locale che riproduce gli endpoint reali
 * `/catalog/{movie,series}/{top,imdbRating,year}.json` (incluso `genre` per `year`):
 * verifica che il caricamento usi il manifest statico ufficiale, che Film e Serie TV
 * restino separati, che i metadati (id IMDb, titolo, poster, sinossi, anno) arrivino
 * sulle card e che un catalogo vuoto o in errore non azzeri gli altri.
 *
 * La base URL è iniettata: nessun test dipende dalla rete.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CinemetaHomeRestorationTest {

  private lateinit var server: FakeCinemetaServer

  @Before
  fun setUp() {
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    StremioCatalogRepository.clearCache()
    server = FakeCinemetaServer().apply { start() }
    StremioCatalogRepository.cinemetaBaseUrlProvider = { server.baseUrl }
  }

  @After
  fun tearDown() {
    StremioCatalogRepository.resetCinemetaBaseUrl()
    StremioCatalogRepository.clearCache()
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    server.stop()
  }

  @Test
  fun caricaISeiCataloghiUfficialiConFilmESerieSeparati() = runBlocking {
    val sections = StremioCatalogRepository.loadDefaultCinemetaSections(
      listOf(MediaType.FILM, MediaType.SERIE_TV)
    )

    assertEquals(6, sections.size)
    assertEquals(
      setOf(
        MediaType.FILM to CinemetaAddon.CATALOG_POPULAR,
        MediaType.FILM to CinemetaAddon.CATALOG_TOP_RATED,
        MediaType.FILM to CinemetaAddon.CATALOG_NEW,
        MediaType.SERIE_TV to CinemetaAddon.CATALOG_POPULAR,
        MediaType.SERIE_TV to CinemetaAddon.CATALOG_TOP_RATED,
        MediaType.SERIE_TV to CinemetaAddon.CATALOG_NEW
      ),
      sections.map { it.mediaType to it.catalogId }.toSet()
    )
    assertEquals(6, sections.map { it.id }.toSet().size)

    sections.forEach { section ->
      assertEquals(CinemetaAddon.MANIFEST_ID, section.addonId)
      assertEquals(CinemetaAddon.DISPLAY_NAME, section.addonName)
      assertEquals(CatalogSourceOrigin.STREMIO_ADDON, section.origin)
      assertTrue("Catalogo ${section.catalogId} vuoto", section.items.isNotEmpty())
      section.items.forEach { item ->
        assertTrue("Id IMDb atteso: ${item.id}", item.id.startsWith("tt"))
        assertTrue("Titolo mancante", item.title.isNotBlank())
        assertTrue("Poster mancante per ${item.id}", item.posterUrl?.startsWith("https://") == true)
        assertTrue("Sinossi mancante per ${item.id}", item.synopsis.isNotBlank())
        assertEquals(2026, item.year)
        assertEquals(item.id, item.imdbId)
      }
    }

    // Le richieste usano i percorsi ufficiali, incluso `genre` obbligatorio per year.
    assertTrue(server.requested.contains("/catalog/movie/top.json"))
    assertTrue(server.requested.contains("/catalog/movie/imdbRating.json"))
    assertTrue(server.requested.contains("/catalog/movie/year/genre=2026.json"))
    assertTrue(server.requested.contains("/catalog/series/top.json"))
    assertTrue(server.requested.contains("/catalog/series/imdbRating.json"))
    assertTrue(server.requested.contains("/catalog/series/year/genre=2026.json"))

    // Mapping Home: ogni riga usa il proprio catalogo, senza mescolare i tipi.
    val home = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = sections,
      userSections = emptyList(),
      allMedia = emptyList()
    )
    assertEquals("tt1001", home.popularMovies.single().id)
    assertEquals("tt1002", home.top10Movies.single().id)
    assertEquals("tt1003", home.trendingMovies.single().id)
    assertEquals("tt2001", home.popularSeries.single().id)
    assertEquals("tt2002", home.top10Series.single().id)
    assertEquals("tt2003", home.trendingSeries.single().id)
    assertTrue(home.moviesFromDefaultCatalog)
    assertTrue(home.seriesFromDefaultCatalog)
    assertEquals(CatalogSourceOrigin.STREMIO_ADDON, home.stockSourceOrigin)
  }

  @Test
  fun unCatalogoVuotoVieneOmessoSenzaAzzeraGliAltri() = runBlocking {
    server.emptyPaths += "/catalog/movie/top.json"

    val sections = StremioCatalogRepository.loadDefaultCinemetaSections(
      listOf(MediaType.FILM, MediaType.SERIE_TV)
    )

    assertEquals(5, sections.size)
    assertNull(sections.firstOrNull {
      it.mediaType == MediaType.FILM && it.catalogId == CinemetaAddon.CATALOG_POPULAR
    })
    assertTrue(sections.any { it.mediaType == MediaType.FILM && it.catalogId == CinemetaAddon.CATALOG_TOP_RATED })
    assertTrue(sections.any { it.mediaType == MediaType.SERIE_TV && it.catalogId == CinemetaAddon.CATALOG_POPULAR })
  }

  @Test
  fun unCatalogoInErroreNonAzzeraGliAltri() = runBlocking {
    server.failPaths += "/catalog/series/imdbRating.json"

    val sections = StremioCatalogRepository.loadDefaultCinemetaSections(
      listOf(MediaType.FILM, MediaType.SERIE_TV)
    )

    assertEquals(5, sections.size)
    assertNull(sections.firstOrNull {
      it.mediaType == MediaType.SERIE_TV && it.catalogId == CinemetaAddon.CATALOG_TOP_RATED
    })
    assertTrue(sections.any { it.mediaType == MediaType.FILM && it.catalogId == CinemetaAddon.CATALOG_TOP_RATED })
    assertTrue(sections.any { it.mediaType == MediaType.SERIE_TV && it.catalogId == CinemetaAddon.CATALOG_POPULAR })
  }

  @Test
  fun laPaginazioneUsaICataloghiCinemetaConSkip() = runBlocking {
    val movies = StremioCatalogRepository.paginateMovies(50)
    val series = StremioCatalogRepository.paginateSeries(50)

    assertTrue(movies.isNotEmpty())
    assertTrue(series.isNotEmpty())
    assertTrue(
      "Paginazione film Cinemeta attesa: ${server.requested}",
      server.requested.any { it.contains("/catalog/movie/top/skip=50.json") }
    )
    assertTrue(
      "Paginazione serie Cinemeta attesa: ${server.requested}",
      server.requested.any { it.contains("/catalog/series/top/skip=50.json") }
    )
    assertTrue(movies.all { it.type == MediaType.FILM })
    assertTrue(series.all { it.type == MediaType.SERIE_TV })
  }

  // ── Fixture ────────────────────────────────────────────────────────────────

  private class FakeCinemetaServer {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val emptyPaths: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val failPaths: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl = "http://127.0.0.1:${http.address.port}"

    fun start() {
      http.createContext("/") { exchange -> handle(exchange) }
      http.start()
    }

    fun stop() = http.stop(0)

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.path
      requested += path
      try {
        if (path in failPaths) {
          respond(exchange, 500, """{"error":"catalog down"}""")
          return
        }
        val parts = path.trim('/').split("/")
        if (parts.size < 3 || parts[0] != "catalog") {
          respond(exchange, 404, "")
          return
        }
        val mediaType = parts[1]
        val catalogId = parts[2].removeSuffix(".json")
        val basePath = "/catalog/$mediaType/$catalogId.json"
        if (basePath in emptyPaths) {
          respond(exchange, 200, """{"metas":[]}""")
          return
        }
        val skip = Regex("""skip=(\d+)""").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val id = when {
          skip > 0 -> "tt9${if (mediaType == "movie") "0" else "1"}$skip"
          catalogId == CinemetaAddon.CATALOG_POPULAR -> "tt1001"
          catalogId == CinemetaAddon.CATALOG_TOP_RATED -> "tt1002"
          else -> "tt1003"
        }.let { raw ->
          if (mediaType == "series") raw.replace("tt10", "tt20") else raw
        }
        val name = "Cinemeta $mediaType $catalogId ${if (skip > 0) "pagina $skip" else ""}".trim()
        respond(
          exchange,
          200,
          """{"metas":[{"id":"$id","type":"$mediaType","name":"$name",""" +
            """"poster":"https://img.example/$id.jpg","releaseInfo":"2026",""" +
            """"description":"Sinossi $id","imdbRating":"8.2"}]}"""
        )
      } finally {
        exchange.close()
      }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
  }
}
