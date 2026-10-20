package com.example.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.stremio.CinemetaAddon
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifica la SELEZIONE della sorgente dei cataloghi Home, per tipo.
 *
 * Regole coperte:
 *  - senza addon utilizzabili Cinemeta resta la sorgente;
 *  - un addon solo-film non sopprime le righe Serie TV (e viceversa);
 *  - cataloghi solo-search, con extra obbligatori o di tipi non supportati non
 *    contano come utilizzabili;
 *  - un addon con contenuti validi sostituisce le righe Cinemeta dello stesso tipo,
 *    senza coesistenza;
 *  - risposta vuota o errore producono il fallback Cinemeta deterministico;
 *  - una risoluzione iniziata prima non sovrascrive una più recente.
 *
 * Il server locale ricrea il manifest di un addon Stremio: nessun id reale è
 * codificato nel codice di produzione. Il caricamento Cinemeta è sostituito da un
 * loader iniettato, così il test non dipende dalla rete.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HomeCatalogSourceSelectionTest {

  private val fixtures = mutableListOf<FakeAddon>()

  /** Tipi richiesti al loader Cinemeta, nell'ordine delle risoluzioni. */
  private val cinemetaRequests = mutableListOf<List<MediaType>>()

  @After
  fun tearDown() {
    StremioCatalogRepository.clearCache()
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    fixtures.forEach { it.stop() }
    fixtures.clear()
    cinemetaRequests.clear()
  }

  // ── Fixture addon locale ────────────────────────────────────────────────────

  private class FakeAddon(
    private val manifestJson: String,
    private val responses: Map<String, String> = emptyMap()
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
      try {
        when {
          path == "/manifest.json" -> respond(exchange, 200, manifestJson)
          responses.containsKey(path) -> respond(exchange, 200, responses.getValue(path))
          // Catalogo dichiarato ma non servito: errore di caricamento.
          path.startsWith("/catalog/") -> respond(exchange, 500, """{"error":"catalog down"}""")
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
  }

  private fun installAddon(manifest: String, responses: Map<String, String> = emptyMap()): FakeAddon {
    val fixture = FakeAddon(manifest, responses)
    fixtures += fixture
    val baseUrl = fixture.start()
    runBlocking { StremioAddonRepository.installAddon(baseUrl) }
    return fixture
  }

  private fun manifest(
    catalogs: String,
    types: String = "\"movie\",\"series\""
  ): String = """
    {
      "id": "test.home.addon",
      "name": "Home Test Addon",
      "version": "1.0.0",
      "description": "Fixture locale per la selezione cataloghi Home",
      "resources": ["catalog"],
      "types": [$types],
      "idPrefixes": ["tt"],
      "catalogs": [$catalogs]
    }
  """.trimIndent()

  private fun metas(vararg items: Pair<String, String>, type: String = TYPE_MOVIE): String =
    """{"metas":[${items.joinToString(",") {
      """{"id":"${it.first}","type":"$type","name":"${it.second}","poster":"https://img.example/${it.first}.jpg"}"""
    }}]}"""

  // ── Helper di modello ───────────────────────────────────────────────────────

  private fun movie(id: String, title: String) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM
  )

  private fun series(id: String, title: String) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV
  )

  private fun section(
    catalogId: String,
    mediaType: MediaType,
    addonName: String,
    items: List<MediaItem>
  ) = CatalogSection(
    id = "stremio_${addonName}_${if (mediaType == MediaType.SERIE_TV) TYPE_SERIES else TYPE_MOVIE}_$catalogId",
    title = "$catalogId • $addonName",
    addonId = addonName,
    addonName = addonName,
    catalogId = catalogId,
    mediaType = mediaType,
    items = items
  )

  private fun cinemetaSection(catalogId: String, mediaType: MediaType, items: List<MediaItem>) =
    section(catalogId, mediaType, CinemetaAddon.DISPLAY_NAME, items).copy(addonId = CinemetaAddon.MANIFEST_ID)

  /** Sezioni Cinemeta sintetiche per i soli tipi richiesti. */
  private fun cinemetaSections(types: List<MediaType>, idBase: String = "tt70000"): List<CatalogSection> =
    buildList {
      if (MediaType.FILM in types) {
        add(cinemetaSection(CinemetaAddon.CATALOG_POPULAR, MediaType.FILM, listOf(movie("${idBase}01", "Popolare Film"))))
        add(cinemetaSection(CinemetaAddon.CATALOG_TOP_RATED, MediaType.FILM, listOf(movie("${idBase}02", "Votato Film"))))
        add(cinemetaSection(CinemetaAddon.CATALOG_NEW, MediaType.FILM, listOf(movie("${idBase}03", "Nuovo Film"))))
      }
      if (MediaType.SERIE_TV in types) {
        add(cinemetaSection(CinemetaAddon.CATALOG_POPULAR, MediaType.SERIE_TV, listOf(series("${idBase}11", "Popolare Serie"))))
        add(cinemetaSection(CinemetaAddon.CATALOG_TOP_RATED, MediaType.SERIE_TV, listOf(series("${idBase}12", "Votata Serie"))))
        add(cinemetaSection(CinemetaAddon.CATALOG_NEW, MediaType.SERIE_TV, listOf(series("${idBase}13", "Nuova Serie"))))
      }
    }

  private fun resolve(media: List<MediaItem> = emptyList()): HomeCatalogs = runBlocking {
    StremioCatalogRepository.resolveHomeCatalogs(media) { types ->
      cinemetaRequests += types
      cinemetaSections(types)
    }
  }

  // ── 1. Nessun addon: Cinemeta resta la sorgente ─────────────────────────────

  @Test
  fun senzaAddonCinemetaFornisceICataloghiDellaHome() {
    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), cinemetaRequests)
    assertFalse(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.extraSections.isEmpty())

    assertEquals(listOf("tt7000001"), catalog.popularMovies.map { it.id })
    assertEquals(listOf("tt7000002"), catalog.top10Movies.map { it.id })
    assertEquals(listOf("tt7000003"), catalog.trendingMovies.map { it.id })
    assertEquals(listOf("tt7000011"), catalog.popularSeries.map { it.id })
    assertEquals(listOf("tt7000012"), catalog.top10Series.map { it.id })
    assertEquals(listOf("tt7000013"), catalog.trendingSeries.map { it.id })
    assertTrue(catalog.moviesFromDefaultCatalog)
    assertTrue(catalog.seriesFromDefaultCatalog)
  }

  // ── 2. Addon solo film: Cinemeta resta per le serie TV ──────────────────────

  @Test
  fun addonSoloFilmNonSopprimeCinemetaPerLeSerie() {
    installAddon(
      manifest(
        catalogs = """{"type":"movie","id":"home_movies","name":"Home Movies"}"""
      ),
      responses = mapOf(
        "/catalog/movie/home_movies.json" to metas(
          "tt9100001" to "Film Addon Uno",
          "tt9100002" to "Film Addon Due"
        )
      )
    )

    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.SERIE_TV)), cinemetaRequests)

    // Film: righe addon, nessuna riga Cinemeta dello stesso tipo.
    assertTrue(catalog.hasCustomCatalogMovies)
    assertTrue(catalog.popularMovies.isEmpty())
    assertTrue(catalog.top10Movies.isEmpty())
    assertTrue(catalog.trendingMovies.isEmpty())
    assertEquals(listOf("tt9100001", "tt9100002"), catalog.allMovies.map { it.id })
    assertEquals(listOf(MediaType.FILM), catalog.extraSections.map { it.mediaType })

    // Serie TV: Cinemeta resta la sorgente.
    assertFalse(catalog.hasCustomCatalogSeries)
    assertEquals(listOf("tt7000011"), catalog.popularSeries.map { it.id })
    assertEquals(listOf("tt7000012"), catalog.top10Series.map { it.id })
    assertTrue(catalog.seriesFromDefaultCatalog)
  }

  // ── 3. Addon solo serie TV: Cinemeta resta per i film ───────────────────────

  @Test
  fun addonSoloSerieNonSopprimeCinemetaPerIFilm() {
    installAddon(
      manifest(
        catalogs = """{"type":"series","id":"home_series","name":"Home Series"}"""
      ),
      responses = mapOf(
        "/catalog/series/home_series.json" to metas(
          "tt9200001" to "Serie Addon",
          type = TYPE_SERIES
        )
      )
    )

    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.FILM)), cinemetaRequests)

    assertFalse(catalog.hasCustomCatalogMovies)
    assertEquals(listOf("tt7000001"), catalog.popularMovies.map { it.id })
    assertEquals(listOf("tt7000002"), catalog.top10Movies.map { it.id })
    assertTrue(catalog.moviesFromDefaultCatalog)

    assertTrue(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.popularSeries.isEmpty())
    assertTrue(catalog.top10Series.isEmpty())
    assertTrue(catalog.trendingSeries.isEmpty())
    assertEquals(listOf("tt9200001"), catalog.allSeries.map { it.id })
    assertEquals(listOf(MediaType.SERIE_TV), catalog.extraSections.map { it.mediaType })
  }

  // ── 4. Cataloghi solo-search / extra obbligatori: non utilizzabili ──────────

  @Test
  fun cataloghiSoloSearchOConExtraObbligatoriNonSopprimonoCinemeta() {
    val fixture = installAddon(
      manifest(
        catalogs = """
          {"type":"movie","id":"search_movies","name":"Search Movies","extra":[{"name":"search","isRequired":true}]},
          {"type":"series","id":"genre_series","name":"Genre Series","extra":[{"name":"genre","isRequired":true,"options":["Drama"]}]}
        """
      )
    )

    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), cinemetaRequests)
    assertFalse(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.extraSections.isEmpty())
    assertTrue(catalog.popularMovies.isNotEmpty())
    assertTrue(catalog.popularSeries.isNotEmpty())
    // Nessuna chiamata di catalogo: non sono interrogabili senza parametri.
    assertTrue(fixture.requested.none { it.startsWith("/catalog/") })
  }

  // ── 5. Cataloghi di tipi non supportati: non sopprimono nulla ───────────────

  @Test
  fun cataloghiDiTipiNonSupportatiNonSopprimonoCinemeta() {
    installAddon(
      manifest(
        catalogs = """
          {"type":"channel","id":"live_channels","name":"Live Channels"},
          {"type":"documentary","id":"docs","name":"Documentaries"}
        """,
        types = "\"channel\",\"documentary\""
      )
    )

    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), cinemetaRequests)
    assertFalse(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.extraSections.isEmpty())
    assertEquals(listOf("tt7000001"), catalog.popularMovies.map { it.id })
    assertEquals(listOf("tt7000011"), catalog.popularSeries.map { it.id })
  }

  // ── 6. Addon con risultati validi: sostituzione per tipo, no coesistenza ────

  @Test
  fun addonConRisultatiValidiSostituisceLeRigheCinemetaSenzaCoesistenza() {
    installAddon(
      manifest(
        catalogs = """
          {"type":"movie","id":"home_movies","name":"Home Movies"},
          {"type":"series","id":"home_series","name":"Home Series"}
        """
      ),
      responses = mapOf(
        "/catalog/movie/home_movies.json" to metas("tt9300001" to "Film Addon"),
        "/catalog/series/home_series.json" to metas("tt9300002" to "Serie Addon", type = TYPE_SERIES)
      )
    )

    val catalog = resolve()

    // Nessun tipo richiede Cinemeta.
    assertTrue(cinemetaRequests.isEmpty())
    assertTrue(catalog.hasCustomCatalogMovies)
    assertTrue(catalog.hasCustomCatalogSeries)

    // Nessuna riga stock dello stesso tipo: le due sorgenti non coesistono.
    assertTrue(catalog.popularMovies.isEmpty())
    assertTrue(catalog.top10Movies.isEmpty())
    assertTrue(catalog.trendingMovies.isEmpty())
    assertTrue(catalog.popularSeries.isEmpty())
    assertTrue(catalog.top10Series.isEmpty())
    assertTrue(catalog.trendingSeries.isEmpty())
    assertFalse(catalog.moviesFromDefaultCatalog)
    assertFalse(catalog.seriesFromDefaultCatalog)

    assertEquals(listOf("tt9300001"), catalog.allMovies.map { it.id })
    assertEquals(listOf("tt9300002"), catalog.allSeries.map { it.id })
    assertEquals(2, catalog.extraSections.size)
    assertTrue(catalog.extraSections.all { it.items.isNotEmpty() })
  }

  // ── 7. Addon senza risultati: fallback Cinemeta deterministico ──────────────

  @Test
  fun addonSenzaRisultatiUsaIlFallbackCinemetaSenzaDuplicati() {
    installAddon(
      manifest(
        catalogs = """
          {"type":"movie","id":"home_movies","name":"Home Movies"},
          {"type":"series","id":"home_series","name":"Home Series"}
        """
      ),
      responses = mapOf(
        "/catalog/movie/home_movies.json" to """{"metas":[]}""",
        "/catalog/series/home_series.json" to """{"metas":[]}"""
      )
    )

    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), cinemetaRequests)
    assertFalse(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.extraSections.isEmpty())
    assertEquals(listOf("tt7000001"), catalog.popularMovies.map { it.id })
    assertEquals(listOf("tt7000011"), catalog.popularSeries.map { it.id })
    // Nessuna riga duplicata: una sola sorgente per tipo.
    assertEquals(catalog.allMovies.map { it.id }, catalog.allMovies.map { it.id }.distinct())
    assertEquals(catalog.allSeries.map { it.id }, catalog.allSeries.map { it.id }.distinct())
  }

  // ── 8. Errore di caricamento: stesso fallback deterministico ────────────────

  @Test
  fun erroreDiCaricamentoUsaIlFallbackCinemeta() {
    installAddon(
      manifest(
        catalogs = """
          {"type":"movie","id":"home_movies","name":"Home Movies"},
          {"type":"series","id":"home_series","name":"Home Series"}
        """
      )
      // Nessuna risposta registrata: il fixture restituisce HTTP 500.
    )

    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), cinemetaRequests)
    assertFalse(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.extraSections.isEmpty())
    assertTrue(catalog.popularMovies.isNotEmpty())
    assertTrue(catalog.popularSeries.isNotEmpty())
  }

  // ── 9. Un tipo non disponibile: l'altro continua a funzionare ───────────────

  @Test
  fun catalogoDiUnTipoNonDisponibileNonBloccaLAltroTipo() {
    installAddon(
      manifest(
        catalogs = """
          {"type":"movie","id":"home_movies","name":"Home Movies"},
          {"type":"series","id":"home_series","name":"Home Series"}
        """
      ),
      responses = mapOf(
        "/catalog/movie/home_movies.json" to metas("tt9400001" to "Film Addon")
        // La serie restituisce 500.
      )
    )

    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.SERIE_TV)), cinemetaRequests)
    assertTrue(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.popularMovies.isEmpty())
    assertEquals(listOf("tt9400001"), catalog.allMovies.map { it.id })
    assertEquals(listOf("tt7000011"), catalog.popularSeries.map { it.id })
    assertTrue(catalog.seriesFromDefaultCatalog)
  }

  // ── 10. Risultati obsoleti: non sovrascrivono una sorgente più recente ──────

  @Test
  fun ilGateScartaUnaRisoluzioneGiaSuperataDaUnaPiuRecente() {
    val gate = StremioCatalogRepository.HomeResolutionGate()
    val first = gate.nextRequest()
    val second = gate.nextRequest()
    val fresh = HomeCatalogs(popularMovies = listOf(movie("tt9500001", "Recente")))
    val stale = HomeCatalogs(popularMovies = listOf(movie("tt9500002", "Obsoleto")))

    assertSame(fresh, gate.complete(second, fresh))
    assertSame(fresh, gate.complete(first, stale))
  }

  @Test
  fun unaRisoluzioneLentaNonSovrascriveUnaPiuRecente(): Unit = runBlocking {
    val slowEntered = CountDownLatch(1)
    val releaseSlow = CountDownLatch(1)

    val slow = async(Dispatchers.IO) {
      StremioCatalogRepository.resolveHomeCatalogs(emptyList()) { types ->
        slowEntered.countDown()
        releaseSlow.await(30, TimeUnit.SECONDS)
        cinemetaSections(types, idBase = "tt80000")
      }
    }

    assertTrue("La risoluzione lenta deve entrare nel loader", slowEntered.await(30, TimeUnit.SECONDS))

    val fresh = StremioCatalogRepository.resolveHomeCatalogs(emptyList()) { types ->
      cinemetaSections(types)
    }

    releaseSlow.countDown()
    val stale = slow.await()

    assertSame("Il risultato obsoleto non deve essere applicato", fresh, stale)
    assertEquals(fresh.allMovies.map { it.id }, stale.allMovies.map { it.id })
  }
}
