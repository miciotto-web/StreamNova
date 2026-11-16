package com.example.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.StremioCatalogEngine
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifica la SELEZIONE della sorgente dei cataloghi Home, per tipo.
 *
 * Regole coperte:
 *  - senza addon utilizzabili restano le righe stock TMDB (sorgente di default in produzione);
 *  - un addon solo-film non sopprime le righe Serie TV (e viceversa);
 *  - cataloghi solo-search, con extra obbligatori o di tipi non supportati non
 *    contano come utilizzabili;
 *  - un addon con contenuti validi sostituisce le righe stock dello stesso tipo,
 *    senza coesistenza;
 *  - risposta vuota o errore producono la sorgente di default deterministica;
 *  - una risoluzione iniziata prima non sovrascrive una più recente.
 *
 * Il server locale ricrea il manifest di un addon Stremio: nessun id reale è
 * codificato nel codice di produzione. Il loader della sorgente di default è
 * INIETTATO: qui usa sezioni stock TMDB sintetiche, mentre in produzione
 * [StremioCatalogRepository.loadDefaultHomeSections] combina le righe stock TMDB
 * con le righe Novità provider TMDB. Nessun test dipende dalla rete.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HomeCatalogSourceSelectionTest {

  private val fixtures = mutableListOf<FakeAddon>()

  /** Tipi richiesti al loader di default, nell'ordine delle risoluzioni. */
  private val defaultLoaderRequests = mutableListOf<List<MediaType>>()

  @Before
  fun setUp() {
    // La memoria delle sezioni Home è un singleton di processo: un test precedente
    // (es. un ViewModel che risolve la Home) non deve influenzare questo.
    StremioCatalogRepository.resetHomeSectionMemory()
  }

  @After
  fun tearDown() {
    StremioCatalogRepository.clearCache()
    StremioCatalogRepository.resetHomeSectionMemory()
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    fixtures.forEach { it.stop() }
    fixtures.clear()
    defaultLoaderRequests.clear()
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

  private fun tmdbStockSection(catalogId: String, slot: HomeCatalogSlot, mediaType: MediaType, items: List<MediaItem>) =
    CatalogSection(
      id = "tmdb_home_${if (mediaType == MediaType.SERIE_TV) "series" else "movies"}_$catalogId",
      title = catalogId,
      addonId = null,
      addonName = null,
      catalogId = catalogId,
      mediaType = mediaType,
      origin = CatalogSourceOrigin.TMDB_NATIVE,
      items = items,
      homeSlot = slot
    )

  /** Sezioni stock TMDB sintetiche per i soli tipi richiesti. */
  private fun tmdbStockSections(types: List<MediaType>, idBase: String = "tt70000"): List<CatalogSection> =
    buildList {
      if (MediaType.FILM in types) {
        add(tmdbStockSection("tmdb_movies_popular", HomeCatalogSlot.POPULAR, MediaType.FILM, listOf(movie("${idBase}01", "Popolare Film"))))
        add(tmdbStockSection("tmdb_movies_top_rated", HomeCatalogSlot.TOP_RATED, MediaType.FILM, listOf(movie("${idBase}02", "Votato Film"))))
        add(tmdbStockSection("tmdb_movies_new", HomeCatalogSlot.NEW, MediaType.FILM, listOf(movie("${idBase}03", "Nuovo Film"))))
      }
      if (MediaType.SERIE_TV in types) {
        add(tmdbStockSection("tmdb_series_popular", HomeCatalogSlot.POPULAR, MediaType.SERIE_TV, listOf(series("${idBase}11", "Popolare Serie"))))
        add(tmdbStockSection("tmdb_series_top_rated", HomeCatalogSlot.TOP_RATED, MediaType.SERIE_TV, listOf(series("${idBase}12", "Votata Serie"))))
        add(tmdbStockSection("tmdb_series_new", HomeCatalogSlot.NEW, MediaType.SERIE_TV, listOf(series("${idBase}13", "Nuova Serie"))))
      }
    }

  private fun resolve(media: List<MediaItem> = emptyList()): HomeCatalogs = runBlocking {
    StremioCatalogRepository.resolveHomeCatalogs(media) { types ->
      defaultLoaderRequests += types
      tmdbStockSections(types)
    }
  }

  // ── 1. Nessun addon: le righe stock TMDB sono la sorgente ─────────────────────────────

  @Test
  fun senzaAddonLeRigheStockTmdbFornisconoICataloghiDellaHome() {
    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), defaultLoaderRequests)
    assertFalse(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.extraSections.isEmpty())

    assertEquals(listOf("tt7000001"), catalog.popularMovies.map { it.id })
    assertEquals(listOf("tt7000002"), catalog.top10Movies.map { it.id })
    assertEquals(listOf("tt7000003"), catalog.trendingMovies.map { it.id })
    assertEquals(listOf("tt7000011"), catalog.popularSeries.map { it.id })
    assertEquals(listOf("tt7000012"), catalog.top10Series.map { it.id })
    assertEquals(listOf("tt7000013"), catalog.trendingSeries.map { it.id })
    assertTrue(catalog.moviesFromTmdbDefault)
    assertTrue(catalog.seriesFromTmdbDefault)
  }

  // ── 2. Addon solo film: le righe stock TMDB restano per le serie TV ──────────────────────

  @Test
  fun addonSoloFilmNonSopprimeLeRigheStockTmdbPerLeSerie() {
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

    assertEquals(listOf(listOf(MediaType.SERIE_TV)), defaultLoaderRequests)

    // Film: righe addon, nessuna riga stock TMDB dello stesso tipo.
    assertTrue(catalog.hasCustomCatalogMovies)
    assertTrue(catalog.popularMovies.isEmpty())
    assertTrue(catalog.top10Movies.isEmpty())
    assertTrue(catalog.trendingMovies.isEmpty())
    assertEquals(listOf("tt9100001", "tt9100002"), catalog.allMovies.map { it.id })
    assertEquals(listOf(MediaType.FILM), catalog.extraSections.map { it.mediaType })

    // Serie TV: le righe stock TMDB restano la sorgente.
    assertFalse(catalog.hasCustomCatalogSeries)
    assertEquals(listOf("tt7000011"), catalog.popularSeries.map { it.id })
    assertEquals(listOf("tt7000012"), catalog.top10Series.map { it.id })
    assertTrue(catalog.seriesFromTmdbDefault)
  }

  // ── 3. Addon solo serie TV: le righe stock TMDB restano per i film ───────────────────────

  @Test
  fun addonSoloSerieNonSopprimeLeRigheStockTmdbPerIFilm() {
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

    assertEquals(listOf(listOf(MediaType.FILM)), defaultLoaderRequests)

    assertFalse(catalog.hasCustomCatalogMovies)
    assertEquals(listOf("tt7000001"), catalog.popularMovies.map { it.id })
    assertEquals(listOf("tt7000002"), catalog.top10Movies.map { it.id })
    assertTrue(catalog.moviesFromTmdbDefault)

    assertTrue(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.popularSeries.isEmpty())
    assertTrue(catalog.top10Series.isEmpty())
    assertTrue(catalog.trendingSeries.isEmpty())
    assertEquals(listOf("tt9200001"), catalog.allSeries.map { it.id })
    assertEquals(listOf(MediaType.SERIE_TV), catalog.extraSections.map { it.mediaType })
  }

  // ── 4. Cataloghi solo-search / extra obbligatori: non utilizzabili ──────────

  @Test
  fun cataloghiSoloSearchOConExtraObbligatoriNonSopprimonoLeRigheStock() {
    val fixture = installAddon(
      manifest(
        catalogs = """
          {"type":"movie","id":"search_movies","name":"Search Movies","extra":[{"name":"search","isRequired":true}]},
          {"type":"series","id":"genre_series","name":"Genre Series","extra":[{"name":"genre","isRequired":true,"options":["Drama"]}]}
        """
      )
    )

    val catalog = resolve()

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), defaultLoaderRequests)
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
  fun cataloghiDiTipiNonSupportatiNonSopprimonoLeRigheStock() {
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

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), defaultLoaderRequests)
    assertFalse(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.extraSections.isEmpty())
    assertEquals(listOf("tt7000001"), catalog.popularMovies.map { it.id })
    assertEquals(listOf("tt7000011"), catalog.popularSeries.map { it.id })
  }

  // ── 6. Cataloghi dichiarati `tv`: sono serie TV a tutti gli effetti ─────────

  /**
   * Manifest nella forma di TvVoo: tipi `tv`, catalogo `tv` con filtro `genre`
   * dichiarato. Il tipo originale non viene mai riscritto.
   */
  private fun tvManifest(): String = """
    {
      "id": "org.stremio.vavoo.clean",
      "name": "TvVoo | ElfHosted",
      "version": "1.0.0",
      "description": "Canali TV",
      "resources": ["catalog", "meta", "stream"],
      "types": ["tv"],
      "idPrefixes": ["vavoo", "vavoo_"],
      "catalogs": [
        {
          "type": "tv",
          "id": "vavoo_tv_home_it",
          "name": "TvVoo TV",
          "extra": [{"name": "genre", "options": ["Tutti", "Rai", "Mediaset", "Sport"]}]
        }
      ]
    }
  """.trimIndent()

  @Test
  fun ilCatalogoDiTipoTvCompareTraITargetSerieDelCatalogEngine() {
    installAddon(
      tvManifest(),
      responses = mapOf(
        "/catalog/tv/vavoo_tv_home_it.json" to metas("vavoo_rai1" to "Rai 1", type = "tv")
      )
    )

    val seriesTargets = StremioCatalogEngine.getAvailableCatalogs(TYPE_SERIES)
    assertEquals(
      listOf("vavoo_tv_home_it"),
      seriesTargets.filter { it.addonId == "org.stremio.vavoo.clean" }.map { it.catalogId }
    )
    // Il tipo richiesto all'addon resta quello dichiarato nel manifest.
    assertEquals(
      listOf("tv"),
      seriesTargets.filter { it.addonId == "org.stremio.vavoo.clean" }.map { it.type }
    )
    assertTrue(StremioCatalogEngine.getAvailableCatalogs(TYPE_MOVIE).none { it.addonId == "org.stremio.vavoo.clean" })
  }

  @Test
  fun iCanaliTvAlimentanoLeRigheSerieDellaHome() {
    val fixture = installAddon(
      tvManifest(),
      responses = mapOf(
        "/catalog/tv/vavoo_tv_home_it.json" to metas(
          "vavoo_rai1" to "Rai 1",
          "vavoo_canale5" to "Canale 5",
          type = "tv"
        )
      )
    )

    val catalog = resolve()

    // Le serie arrivano dall.addon: le righe stock TMDB restano solo per i film.
    assertEquals(listOf(listOf(MediaType.FILM)), defaultLoaderRequests)
    assertTrue(catalog.hasCustomCatalogSeries)
    assertFalse(catalog.hasCustomCatalogMovies)
    assertEquals(listOf("vavoo_rai1", "vavoo_canale5"), catalog.allSeries.map { it.id })
    assertEquals(listOf(MediaType.SERIE_TV), catalog.extraSections.map { it.mediaType })
    assertTrue(catalog.popularSeries.isEmpty())
    assertTrue(!catalog.seriesFromTmdbDefault)

    // L'endpoint interrogato conserva il tipo originale `tv`.
    assertTrue(fixture.requested.contains("/catalog/tv/vavoo_tv_home_it.json"))
    assertTrue(fixture.requested.none { it.startsWith("/catalog/series/") })
  }

  // ── 7. Addon con risultati validi: sostituzione per tipo, no coesistenza ────

  @Test
  fun addonConRisultatiValidiSostituisceLeRigheStockSenzaCoesistenza() {
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

    // Nessun tipo richiede le righe di default.
    assertTrue(defaultLoaderRequests.isEmpty())
    assertTrue(catalog.hasCustomCatalogMovies)
    assertTrue(catalog.hasCustomCatalogSeries)

    // Nessuna riga stock dello stesso tipo: le due sorgenti non coesistono.
    assertTrue(catalog.popularMovies.isEmpty())
    assertTrue(catalog.top10Movies.isEmpty())
    assertTrue(catalog.trendingMovies.isEmpty())
    assertTrue(catalog.popularSeries.isEmpty())
    assertTrue(catalog.top10Series.isEmpty())
    assertTrue(catalog.trendingSeries.isEmpty())
    assertFalse(catalog.moviesFromTmdbDefault)
    assertFalse(catalog.seriesFromTmdbDefault)

    assertEquals(listOf("tt9300001"), catalog.allMovies.map { it.id })
    assertEquals(listOf("tt9300002"), catalog.allSeries.map { it.id })
    assertEquals(2, catalog.extraSections.size)
    assertTrue(catalog.extraSections.all { it.items.isNotEmpty() })
  }

  // ── 7. Addon senza risultati: righe stock di default deterministiche ──────────────

  @Test
  fun addonSenzaRisultatiUsaLeRigheStockDiDefaultSenzaDuplicati() {
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

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), defaultLoaderRequests)
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
  fun erroreDiCaricamentoUsaLeRigheStockDiDefault() {
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

    assertEquals(listOf(listOf(MediaType.FILM, MediaType.SERIE_TV)), defaultLoaderRequests)
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

    assertEquals(listOf(listOf(MediaType.SERIE_TV)), defaultLoaderRequests)
    assertTrue(catalog.hasCustomCatalogMovies)
    assertFalse(catalog.hasCustomCatalogSeries)
    assertTrue(catalog.popularMovies.isEmpty())
    assertEquals(listOf("tt9400001"), catalog.allMovies.map { it.id })
    assertEquals(listOf("tt7000011"), catalog.popularSeries.map { it.id })
    assertTrue(catalog.seriesFromTmdbDefault)
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
        tmdbStockSections(types, idBase = "tt80000")
      }
    }

    assertTrue("La risoluzione lenta deve entrare nel loader", slowEntered.await(30, TimeUnit.SECONDS))

    val fresh = StremioCatalogRepository.resolveHomeCatalogs(emptyList()) { types ->
      tmdbStockSections(types)
    }

    releaseSlow.countDown()
    val stale = slow.await()

    assertSame("Il risultato obsoleto non deve essere applicato", fresh, stale)
    assertEquals(fresh.allMovies.map { it.id }, stale.allMovies.map { it.id })
  }
}
