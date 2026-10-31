package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.api.TmdbApiClient
import com.example.data.local.StreamNovaDatabase
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.stremio.StremioAddonRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Calendar
import java.util.Collections
import kotlinx.coroutines.runBlocking
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
 * Verifica il provider NATIVO dei cataloghi Home TMDB e la sua integrazione con
 * [StremioCatalogRepository.resolveHomeCatalogs].
 *
 * Nessuna rete e nessuna credenziale reale: TMDB è servito da un server HTTP locale
 * ([TmdbApiClient.overrideServiceBaseUrl]) e l'orologio è fisso, così le finestre
 * delle novità digitali/HBO sono deterministiche.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TmdbHomeCatalogProviderTest {

  private companion object {
    const val TEST_KEY = "test-api-key-0000000000000000"

    /** 31 ottobre 2026, 12:00 (ora locale): data di riferimento delle finestre. */
    val FIXED_NOW: Long = Calendar.getInstance().apply {
      set(2026, Calendar.OCTOBER, 31, 12, 0, 0)
      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Provider a cui è applicata la finestra FILM estesa di 365 giorni. */
    val extendedMovieProviderIds: Set<String> = setOf(
      TmdbHomeCatalogProvider.ItalyProviderIds.APPLE_TV_PLUS.toString(),
      TmdbHomeCatalogProvider.ItalyProviderIds.PARAMOUNT_PLUS.toString()
    )
  }

  private lateinit var server: FakeTmdbServer

  /** Database Room IN MEMORY: nessun test esterno condivide cache o progressi. */
  private lateinit var database: StreamNovaDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    database = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    MediaRepository.init(context, database)
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()

    server = FakeTmdbServer().apply { start() }
    TmdbHomeCatalogProvider.apiKeyProvider = { TEST_KEY }
    TmdbHomeCatalogProvider.languageProvider = { "it-IT" }
    TmdbHomeCatalogProvider.clockProvider = { FIXED_NOW }
  }

  @After
  fun tearDown() {
    server.stop()
    TmdbApiClient.overrideServiceBaseUrl(null)
    TmdbHomeCatalogProvider.resetOverrides()
    database.close()
  }

  private fun Request.queryParam(name: String): String? =
    query.split("&")
      .firstOrNull { it.substringBefore("=") == name }
      ?.substringAfter("=", "")
      ?.takeIf { it.isNotEmpty() }

  /** Id delle 10 righe provider nell'ordine editoriale della Home. */
  private fun expectedProviderRowIds(): List<String> = listOf(
    TmdbHomeCatalogProvider.SECTION_NETFLIX_MOVIES_NEW,
    TmdbHomeCatalogProvider.SECTION_NETFLIX_SERIES_NEW,
    TmdbHomeCatalogProvider.SECTION_DISNEY_MOVIES_NEW,
    TmdbHomeCatalogProvider.SECTION_DISNEY_SERIES_NEW,
    TmdbHomeCatalogProvider.SECTION_PRIME_MOVIES_NEW,
    TmdbHomeCatalogProvider.SECTION_PRIME_SERIES_NEW,
    TmdbHomeCatalogProvider.SECTION_APPLE_MOVIES_NEW,
    TmdbHomeCatalogProvider.SECTION_APPLE_SERIES_NEW,
    TmdbHomeCatalogProvider.SECTION_PARAMOUNT_MOVIES_NEW,
    TmdbHomeCatalogProvider.SECTION_PARAMOUNT_SERIES_NEW
  )

  /** Id delle righe extra nell'ordine in cui la Home le mostra. */
  private fun expectedExtraIds(): List<String> = listOf(
    TmdbHomeCatalogProvider.SECTION_ON_AIR_SERIES,
    TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW,
    TmdbHomeCatalogProvider.SECTION_HBO_NEW
  ) + expectedProviderRowIds()

  // ── 1. Tutte le categorie, mapping e parametri degli endpoint ───────────────

  @Test
  fun caricaTutteLeCategorieConLeRigheStockELeTreNuoveRighe() = runBlocking {
    val sections = TmdbHomeCatalogProvider.loadHomeSections(
      listOf(MediaType.FILM, MediaType.SERIE_TV)
    )

    assertEquals(
      listOf(
        "tmdb_home_movies_popular",
        "tmdb_home_movies_top_rated",
        "tmdb_home_movies_new",
        "tmdb_home_series_popular",
        "tmdb_home_series_top_rated",
        "tmdb_home_series_new"
      ) + expectedExtraIds(),
      sections.map { it.id }
    )
    sections.forEach { section ->
      assertEquals(CatalogSourceOrigin.TMDB_NATIVE, section.origin)
      assertNull(section.addonId)
      assertNull(section.addonName)
      assertTrue(section.items.isNotEmpty())
    }

    // Ruoli stock: Popolari / In evidenza / Novità per entrambi i tipi.
    val stockMovies = sections.filter { it.mediaType == MediaType.FILM && it.homeSlot != null }
    val stockSeries = sections.filter { it.mediaType == MediaType.SERIE_TV && it.homeSlot != null }
    assertEquals(
      listOf(HomeCatalogSlot.POPULAR, HomeCatalogSlot.TOP_RATED, HomeCatalogSlot.NEW),
      stockMovies.map { it.homeSlot }
    )
    assertEquals(
      listOf(HomeCatalogSlot.POPULAR, HomeCatalogSlot.TOP_RATED, HomeCatalogSlot.NEW),
      stockSeries.map { it.homeSlot }
    )

    // Righe extra: nessuno slot, id STABILI usati come key di focus nella Home.
    assertEquals(
      expectedExtraIds().toSet(),
      sections.filter { it.homeSlot == null }.map { it.id }.toSet()
    )

    // Ogni riga provider ha contenuti PROPRI, separati per Film e Serie TV: il
    // fixture restituisce id diversi per provider e per tipo.
    val providerSections = sections.filter { it.id in expectedProviderRowIds() }
    assertEquals(10, providerSections.size)
    assertEquals(
      5,
      providerSections.count { it.mediaType == MediaType.FILM }
    )
    assertEquals(
      5,
      providerSections.count { it.mediaType == MediaType.SERIE_TV }
    )
    assertEquals(
      10,
      providerSections.map { it.items.map(MediaItem::id) }.toSet().size
    )

    // Mapping film: id canonico stabile, tmdbId, anno, generi, immagini, voto.
    val popularMovie = sections.first {
      it.id == "tmdb_home_movies_popular"
    }.items.first()
    assertEquals("tmdb_m_101", popularMovie.id)
    assertEquals(101, popularMovie.tmdbId)
    assertEquals("Popolare Film 1", popularMovie.title)
    assertEquals(MediaType.FILM, popularMovie.type)
    assertEquals(2026, popularMovie.year)
    assertEquals(listOf("Azione", "Fantascienza"), popularMovie.genres)
    assertEquals("https://image.tmdb.org/t/p/w500/po101.jpg", popularMovie.posterUrl)
    assertEquals("https://image.tmdb.org/t/p/w1280/bd101.jpg", popularMovie.backdropUrl)
    assertEquals(8.1f, popularMovie.rating, 0.01f)
    assertEquals(8.1f, popularMovie.tmdbRating!!, 0.01f)

    // Top 10: il flag è impostato sui primi dieci elementi della classifica e il
    // genere "Top 10" alimenta il filtro omonimo della schermata categoria.
    val topMovie = sections.first { it.id == "tmdb_home_movies_top_rated" }.items.first()
    assertEquals("tmdb_m_201", topMovie.id)
    assertTrue(topMovie.isTop10)
    assertTrue(topMovie.genres.contains("Top 10"))

    // Mapping serie: id canonico, tipo e anno dalla first_air_date.
    val popularSeries = sections.first { it.id == "tmdb_home_series_popular" }.items.first()
    assertEquals("tmdb_tv_401", popularSeries.id)
    assertEquals(401, popularSeries.tmdbId)
    assertEquals(MediaType.SERIE_TV, popularSeries.type)
    assertEquals(2026, popularSeries.year)
    assertEquals(listOf("Commedia"), popularSeries.genres)

    // Etichette delle nuove righe nella lingua dell'app.
    assertEquals(
      "Serie TV in onda",
      sections.first { it.id == TmdbHomeCatalogProvider.SECTION_ON_AIR_SERIES }.title
    )
    assertEquals(
      "Novità in digitale",
      sections.first { it.id == TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW }.title
    )
    assertEquals(
      "Novità HBO",
      sections.first { it.id == TmdbHomeCatalogProvider.SECTION_HBO_NEW }.title
    )

    // Titoli italiani richiesti per le 10 righe provider.
    assertEquals(
      listOf(
        "Novità Netflix Film",
        "Novità Netflix Serie",
        "Novità Disney+ Film",
        "Novità Disney+ Serie",
        "Novità Prime Video Film",
        "Novità Prime Video Serie",
        "Novità Apple TV+ Film",
        "Novità Apple TV+ Serie",
        "Novità Paramount+ Film",
        "Novità Paramount+ Serie"
      ),
      expectedProviderRowIds().map { id -> sections.first { it.id == id }.title }
    )
  }

  @Test
  fun gliEndpointUsanoRegioneOrdinamentoEFinestreDocumentate() = runBlocking {
    TmdbHomeCatalogProvider.loadHomeSections(listOf(MediaType.FILM, MediaType.SERIE_TV))

    val popularMovies = server.single("/3/movie/popular")
    assertTrue(popularMovies.query, popularMovies.query.contains("page=1"))
    assertTrue(popularMovies.query, popularMovies.query.contains("language=it-IT"))

    val nowPlaying = server.single("/3/movie/now_playing")
    assertTrue(nowPlaying.query, nowPlaying.query.contains("region=IT"))
    assertTrue(nowPlaying.query, nowPlaying.query.contains("page=1"))

    val onAir = server.single("/3/tv/on_the_air")
    assertTrue(onAir.query, onAir.query.contains("page=1"))

    val discoverTv = server.requestsFor("/3/discover/tv")
    val newSeries = discoverTv.single { it.query.contains("first_air_date_year") }
    assertTrue(newSeries.query, newSeries.query.contains("first_air_date_year=2026"))
    assertTrue(newSeries.query, newSeries.query.contains("sort_by=popularity.desc"))

    val hbo = discoverTv.single { it.query.contains("with_networks") }
    assertTrue(
      hbo.query,
      hbo.query.contains("with_networks=49|3186") || hbo.query.contains("with_networks=49%7C3186")
    )
    assertTrue(hbo.query, hbo.query.contains("first_air_date.gte=2025-10-31"))
    assertTrue(hbo.query, hbo.query.contains("first_air_date.lte=2026-10-31"))

    val digital = server.requestsFor("/3/discover/movie")
      .single { it.query.contains("with_release_type") }
    assertTrue(digital.query, digital.query.contains("region=IT"))
    assertTrue(digital.query, digital.query.contains("with_release_type=4"))
    assertTrue(digital.query, digital.query.contains("release_date.gte=2026-09-16"))
    assertTrue(digital.query, digital.query.contains("release_date.lte=2026-10-31"))
    assertTrue(digital.query, digital.query.contains("sort_by=popularity.desc"))

    // Righe "Novità provider": ID provider IT verificati, flatrate, regione IT e
    // finestra temporale sulle DATE di uscita/trasmissione.
    val providerMovies = server.requestsFor("/3/discover/movie")
      .filter { it.query.contains("with_watch_providers") }
    val providerSeries = server.requestsFor("/3/discover/tv")
      .filter { it.query.contains("with_watch_providers") }
    assertEquals(5, providerMovies.size)
    assertEquals(5, providerSeries.size)

    val expectedProviderIds = listOf(
      TmdbHomeCatalogProvider.ItalyProviderIds.NETFLIX,
      TmdbHomeCatalogProvider.ItalyProviderIds.DISNEY_PLUS,
      TmdbHomeCatalogProvider.ItalyProviderIds.PRIME_VIDEO,
      TmdbHomeCatalogProvider.ItalyProviderIds.APPLE_TV_PLUS,
      TmdbHomeCatalogProvider.ItalyProviderIds.PARAMOUNT_PLUS
    ).map(Int::toString)
    // Le richieste partono in parallelo: l'ordine di ARRIVO al server non è quello
    // editoriale (verificato sopra sulle sezioni), qui conta l'insieme degli ID.
    assertEquals(
      expectedProviderIds.toSet(),
      providerMovies.mapNotNull { it.queryParam("with_watch_providers") }.toSet()
    )
    assertEquals(
      expectedProviderIds.toSet(),
      providerSeries.mapNotNull { it.queryParam("with_watch_providers") }.toSet()
    )

    providerMovies.forEach { request ->
      assertTrue(request.query, request.query.contains("watch_region=IT"))
      assertTrue(
        request.query,
        request.query.contains("with_watch_monetization_types=flatrate")
      )
      // 365 giorni solo per Apple TV+ (350) e Paramount+ (531); le altre righe
      // Provider Film restano a 90 giorni.
      val providerId = request.queryParam("with_watch_providers")
      val expectedSince = if (providerId in extendedMovieProviderIds) {
        "2025-10-31"
      } else {
        "2026-08-02"
      }
      assertTrue(
        request.query,
        request.query.contains("primary_release_date.gte=$expectedSince")
      )
      assertTrue(request.query, request.query.contains("primary_release_date.lte=2026-10-31"))
      assertTrue(request.query, request.query.contains("sort_by=primary_release_date.desc"))
      assertTrue(request.query, request.query.contains("page=1"))
    }
    providerSeries.forEach { request ->
      assertTrue(request.query, request.query.contains("watch_region=IT"))
      assertTrue(
        request.query,
        request.query.contains("with_watch_monetization_types=flatrate")
      )
      assertTrue(request.query, request.query.contains("first_air_date.gte=2026-05-04"))
      assertTrue(request.query, request.query.contains("first_air_date.lte=2026-10-31"))
      assertTrue(request.query, request.query.contains("sort_by=first_air_date.desc"))
      assertTrue(request.query, request.query.contains("page=1"))
    }
  }

  @Test
  fun gliIdProviderSonoQuelliItalianiVerificatiSuTmdb() {
    // Verificati con /watch/providers/{movie,tv}?watch_region=IT: in Italia Prime
    // Video è 119 (negli USA è 9) e Apple TV+ è il servizio 350 (non lo store 2).
    assertEquals(8, TmdbHomeCatalogProvider.ItalyProviderIds.NETFLIX)
    assertEquals(337, TmdbHomeCatalogProvider.ItalyProviderIds.DISNEY_PLUS)
    assertEquals(119, TmdbHomeCatalogProvider.ItalyProviderIds.PRIME_VIDEO)
    assertEquals(350, TmdbHomeCatalogProvider.ItalyProviderIds.APPLE_TV_PLUS)
    assertEquals(531, TmdbHomeCatalogProvider.ItalyProviderIds.PARAMOUNT_PLUS)
  }

  @Test
  fun soloAppleEParamountFilmUsanoLaFinestraEstesaDi365Giorni() {
    val rows = TmdbHomeCatalogProvider.providerNewRows(
      TmdbHomeCatalogProvider.labelsFor("it-IT")
    )
    assertEquals(5, rows.size)

    // Le due righe estese sono esattamente Apple TV+ Film e Paramount+ Film.
    assertEquals(
      setOf(
        TmdbHomeCatalogProvider.SECTION_APPLE_MOVIES_NEW,
        TmdbHomeCatalogProvider.SECTION_PARAMOUNT_MOVIES_NEW
      ),
      rows.filter { it.movieWindowDays == 365 }.map { it.movieSectionId }.toSet()
    )
    rows.filter { it.providerId == TmdbHomeCatalogProvider.ItalyProviderIds.APPLE_TV_PLUS }
      .single()
      .let { assertEquals(365, it.movieWindowDays) }
    rows.filter { it.providerId == TmdbHomeCatalogProvider.ItalyProviderIds.PARAMOUNT_PLUS }
      .single()
      .let { assertEquals(365, it.movieWindowDays) }

    // Tutte le altre righe Provider Film mantengono i 90 giorni.
    rows.filter { it.providerId.toString() !in extendedMovieProviderIds }
      .forEach { row ->
        assertEquals(
          row.movieSectionId,
          TmdbHomeCatalogProvider.PROVIDER_MOVIES_WINDOW_DAYS,
          row.movieWindowDays
        )
        assertEquals(90, row.movieWindowDays)
      }

    // La finestra delle righe Serie TV non è stata toccata.
    assertEquals(180, TmdbHomeCatalogProvider.PROVIDER_SERIES_WINDOW_DAYS)
    assertEquals(90, TmdbHomeCatalogProvider.PROVIDER_MOVIES_WINDOW_DAYS)
    assertEquals(365, TmdbHomeCatalogProvider.PROVIDER_MOVIES_EXTENDED_WINDOW_DAYS)
  }

  @Test
  fun iFilmCaricanoSoloLeRigheFilmELeSerieSoloLeRigheSerie() = runBlocking {
    val moviesOnly = TmdbHomeCatalogProvider.loadHomeSections(listOf(MediaType.FILM))
    assertTrue(moviesOnly.all { it.mediaType == MediaType.FILM })
    assertEquals(
      listOf(
        TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW,
        TmdbHomeCatalogProvider.SECTION_NETFLIX_MOVIES_NEW,
        TmdbHomeCatalogProvider.SECTION_DISNEY_MOVIES_NEW,
        TmdbHomeCatalogProvider.SECTION_PRIME_MOVIES_NEW,
        TmdbHomeCatalogProvider.SECTION_APPLE_MOVIES_NEW,
        TmdbHomeCatalogProvider.SECTION_PARAMOUNT_MOVIES_NEW
      ),
      moviesOnly.filter { it.homeSlot == null }.map { it.id }
    )
    assertTrue(server.requestsFor("/3/discover/tv").none { it.query.contains("with_watch_providers") })

    MediaRepository.clearCache()
    val seriesOnly = TmdbHomeCatalogProvider.loadHomeSections(listOf(MediaType.SERIE_TV))
    assertTrue(seriesOnly.all { it.mediaType == MediaType.SERIE_TV })
    assertEquals(
      listOf(
        TmdbHomeCatalogProvider.SECTION_ON_AIR_SERIES,
        TmdbHomeCatalogProvider.SECTION_HBO_NEW,
        TmdbHomeCatalogProvider.SECTION_NETFLIX_SERIES_NEW,
        TmdbHomeCatalogProvider.SECTION_DISNEY_SERIES_NEW,
        TmdbHomeCatalogProvider.SECTION_PRIME_SERIES_NEW,
        TmdbHomeCatalogProvider.SECTION_APPLE_SERIES_NEW,
        TmdbHomeCatalogProvider.SECTION_PARAMOUNT_SERIES_NEW
      ),
      seriesOnly.filter { it.homeSlot == null }.map { it.id }
    )
  }

  // ── 2. Cache e refresh ─────────────────────────────────────────────────────

  @Test
  fun laSecondaRisoluzioneUsaLaCacheRoomESiAggiornaDopoUnRefreshEsplicito() = runBlocking {
    TmdbHomeCatalogProvider.loadHomeSections(listOf(MediaType.FILM, MediaType.SERIE_TV))
    val requestsAfterFirstLoad = server.requests.size
    assertTrue(requestsAfterFirstLoad > 0)

    // Stessa risoluzione: tutte le risposte devono arrivare dalla cache Room.
    TmdbHomeCatalogProvider.loadHomeSections(listOf(MediaType.FILM, MediaType.SERIE_TV))
    assertEquals(requestsAfterFirstLoad, server.requests.size)

    // Refresh esplicito: la cache viene svuotata e la Home torna in rete.
    MediaRepository.clearCache()
    TmdbHomeCatalogProvider.loadHomeSections(listOf(MediaType.FILM, MediaType.SERIE_TV))
    assertTrue(server.requests.size > requestsAfterFirstLoad)
  }

  // ── 3. Errori isolati e risposte vuote ─────────────────────────────────────

  @Test
  fun unCatalogoCheFallisceNonAzzeraLeAltreRighe() = runBlocking {
    server.failPaths += "/3/tv/popular"

    val sections = TmdbHomeCatalogProvider.loadHomeSections(
      listOf(MediaType.FILM, MediaType.SERIE_TV)
    )

    assertNull(sections.firstOrNull { it.id == "tmdb_home_series_popular" })
    assertNotNull(sections.firstOrNull { it.id == "tmdb_home_movies_popular" })
    assertNotNull(sections.firstOrNull { it.id == TmdbHomeCatalogProvider.SECTION_ON_AIR_SERIES })
    assertNotNull(sections.firstOrNull { it.id == TmdbHomeCatalogProvider.SECTION_HBO_NEW })
    assertNotNull(sections.firstOrNull { it.id == "tmdb_home_series_top_rated" })
  }

  @Test
  fun unaRispostaVuotaRimuoveSoloLaRigaInteressata() = runBlocking {
    server.emptyPaths += "/3/movie/now_playing"

    val sections = TmdbHomeCatalogProvider.loadHomeSections(
      listOf(MediaType.FILM, MediaType.SERIE_TV)
    )

    assertNull(sections.firstOrNull { it.id == "tmdb_home_movies_new" })
    assertNotNull(sections.firstOrNull { it.id == "tmdb_home_movies_popular" })
    assertNotNull(sections.firstOrNull { it.id == TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW })
  }

  @Test
  fun unaRigaProviderSenzaRisultatiSpariceSenzaFallbackCinemeta() = runBlocking {
    // Apple TV+ non ha film nella finestra: sparisce SOLO quella riga.
    server.emptyProviderRows += "movie:${TmdbHomeCatalogProvider.ItalyProviderIds.APPLE_TV_PLUS}"

    val sections = TmdbHomeCatalogProvider.loadHomeSections(
      listOf(MediaType.FILM, MediaType.SERIE_TV)
    )

    assertNull(sections.firstOrNull { it.id == TmdbHomeCatalogProvider.SECTION_APPLE_MOVIES_NEW })
    assertNotNull(sections.firstOrNull { it.id == TmdbHomeCatalogProvider.SECTION_APPLE_SERIES_NEW })
    assertNotNull(sections.firstOrNull { it.id == TmdbHomeCatalogProvider.SECTION_NETFLIX_MOVIES_NEW })
    // Nessun contenuto addon/Cinemeta è stato interrogato né costruito.
    assertTrue(sections.all { it.origin == CatalogSourceOrigin.TMDB_NATIVE })
    assertTrue(sections.all { it.addonId == null && it.addonName == null })
    assertTrue(server.requests.none { it.path.startsWith("/catalog/") })
  }

  @Test
  fun senzaCredenzialeTmdbNonParteAlcunaRichiesta() = runBlocking {
    TmdbHomeCatalogProvider.apiKeyProvider = { "" }

    val sections = TmdbHomeCatalogProvider.loadHomeSections(
      listOf(MediaType.FILM, MediaType.SERIE_TV)
    )

    assertTrue(sections.isEmpty())
    assertEquals(0, server.requests.size)
  }

  // ── 4. Paginazione ─────────────────────────────────────────────────────────

  @Test
  fun paginazioneFilmESerieUsaLaPaginaRichiesta() = runBlocking {
    val page2Movies = TmdbHomeCatalogProvider.paginateMovies(2)
    assertEquals(listOf("tmdb_m_101", "tmdb_m_102"), page2Movies.map { it.id })
    assertTrue(server.single("/3/movie/popular").query.contains("page=2"))

    val page2Series = TmdbHomeCatalogProvider.paginateSeries(2)
    assertEquals(listOf("tmdb_tv_401", "tmdb_tv_402"), page2Series.map { it.id })
    assertTrue(server.single("/3/tv/popular").query.contains("page=2"))

    // La pagina 1 è già caricata dalla Home: non viene richiesta di nuovo.
    val before = server.requests.size
    assertTrue(TmdbHomeCatalogProvider.paginateMovies(1).isEmpty())
    assertTrue(TmdbHomeCatalogProvider.paginateSeries(1).isEmpty())
    assertEquals(before, server.requests.size)
  }

  // ── 5. Integrazione con resolveHomeCatalogs ────────────────────────────────

  @Test
  fun laHomeSenzaAddonVieneAlimentataDaTmdb() = runBlocking {
    val catalogs = StremioCatalogRepository.resolveHomeCatalogs(emptyList())

    assertTrue(catalogs.popularMovies.isNotEmpty())
    assertTrue(catalogs.top10Movies.isNotEmpty())
    assertTrue(catalogs.trendingMovies.isNotEmpty())
    assertTrue(catalogs.popularSeries.isNotEmpty())
    assertTrue(catalogs.top10Series.isNotEmpty())
    assertTrue(catalogs.trendingSeries.isNotEmpty())
    assertTrue(catalogs.heroItems.isNotEmpty())

    assertTrue(catalogs.moviesFromTmdbDefault)
    assertTrue(catalogs.seriesFromTmdbDefault)
    assertFalse(catalogs.moviesFromDefaultCatalog)
    assertFalse(catalogs.seriesFromDefaultCatalog)
    assertFalse(catalogs.isMoviesFromStremio)
    assertFalse(catalogs.isSeriesFromStremio)
    assertFalse(catalogs.hasCustomCatalogAddons)

    assertEquals(
      expectedExtraIds(),
      catalogs.extraSections.map { it.id }
    )
    assertEquals(
      listOf(MediaType.SERIE_TV, MediaType.FILM, MediaType.SERIE_TV) +
        List(5) { listOf(MediaType.FILM, MediaType.SERIE_TV) }.flatten(),
      catalogs.extraSections.map { it.mediaType }
    )
    // Nessuna riga nativa può esporre un nome addon come attribuzione.
    catalogs.extraSections.forEach { section ->
      assertNull(section.addonId)
      assertNull(section.addonName)
      assertNull(section.attributionAddonName)
    }
    // Le righe stock sono tutte TMDB: la UI può mostrare il badge TMDB e mai un addon.
    assertEquals(CatalogSourceOrigin.TMDB_NATIVE, catalogs.stockSourceOrigin)
    assertTrue(catalogs.allMovies.isNotEmpty())
    assertTrue(catalogs.allSeries.isNotEmpty())
  }

  // ── Fixture: server TMDB locale ────────────────────────────────────────────

  private data class Request(val path: String, val query: String)

  private inner class FakeTmdbServer {
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    val failPaths: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val emptyPaths: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Righe provider da servire vuote, nel formato `movie:<id>` / `tv:<id>`. */
    val emptyProviderRows: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start() {
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      TmdbApiClient.overrideServiceBaseUrl("http://127.0.0.1:${server.address.port}/3/")
    }

    fun stop() = server.stop(0)

    fun requestsFor(path: String): List<Request> =
      synchronized(requests) { requests.filter { it.path == path } }

    fun single(path: String): Request = requestsFor(path).single()

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.path
      val query = exchange.requestURI.query.orEmpty()
      requests += Request(path, query)
      try {
        if (path in failPaths) {
          respond(exchange, 500, """{"status_message":"catalog down"}""")
          return
        }
        val body = when {
          path in emptyPaths -> """{"page":1,"results":[],"total_pages":1,"total_results":0}"""
          path == "/3/movie/popular" -> movieBody(
            101 to "Popolare Film 1",
            102 to "Popolare Film 2"
          )
          path == "/3/movie/top_rated" -> movieBody(
            201 to "Film in evidenza 1",
            202 to "Film in evidenza 2"
          )
          path == "/3/movie/now_playing" -> movieBody(
            301 to "Film al cinema 1",
            302 to "Film al cinema 2"
          )
          path == "/3/tv/popular" -> tvBody(
            401 to "Serie popolare 1",
            402 to "Serie popolare 2"
          )
          path == "/3/tv/top_rated" -> tvBody(
            501 to "Serie in evidenza 1",
            502 to "Serie in evidenza 2"
          )
          path == "/3/tv/on_the_air" -> tvBody(
            701 to "Serie in onda 1",
            702 to "Serie in onda 2"
          )
          path == "/3/discover/tv" && query.contains("with_networks") -> tvBody(
            801 to "Novità HBO 1",
            802 to "Novità HBO 2"
          )
          path == "/3/discover/tv" && query.contains("with_watch_providers") -> {
            val providerId = queryParam(query, "with_watch_providers").orEmpty()
            if ("tv:$providerId" in emptyProviderRows) {
              """{"page":1,"results":[],"total_pages":1,"total_results":0}"""
            } else {
              val base = providerBase(providerId)
              tvBody(
                base + 101 to "Novità provider $providerId Serie 1",
                base + 102 to "Novità provider $providerId Serie 2"
              )
            }
          }
          path == "/3/discover/movie" && query.contains("with_watch_providers") -> {
            val providerId = queryParam(query, "with_watch_providers").orEmpty()
            if ("movie:$providerId" in emptyProviderRows) {
              """{"page":1,"results":[],"total_pages":1,"total_results":0}"""
            } else {
              val base = providerBase(providerId)
              movieBody(
                base + 1 to "Novità provider $providerId Film 1",
                base + 2 to "Novità provider $providerId Film 2"
              )
            }
          }
          path == "/3/discover/tv" -> tvBody(
            601 to "Nuova serie 1",
            602 to "Nuova serie 2"
          )
          path == "/3/discover/movie" -> movieBody(
            901 to "Novità digitale 1",
            902 to "Novità digitale 2"
          )
          else -> {
            // Dettagli/traduzioni eventualmente richiesti dall'Hero localizer.
            respond(exchange, 404, "")
            return
          }
        }
        respond(exchange, 200, body)
      } finally {
        exchange.close()
      }
    }

    private fun queryParam(query: String, name: String): String? =
      query.split("&")
        .firstOrNull { it.substringBefore("=") == name }
        ?.substringAfter("=", "")
        ?.takeIf { it.isNotEmpty() }

    /** Base id distinta per ogni provider: le righe non condividono contenuti. */
    private fun providerBase(providerId: String): Int = when (providerId) {
      "8" -> 1000
      "337" -> 1100
      "119" -> 1200
      "350" -> 1300
      "531" -> 1400
      else -> 1500
    }

    private fun movieBody(vararg movies: Pair<Int, String>): String =
      """{"page":1,"results":[${movies.joinToString(",") { (id, title) ->
        """{"id":$id,"title":"$title","original_title":"$title","overview":"Sinossi $id",""" +
          """"backdrop_path":"/bd$id.jpg","poster_path":"/po$id.jpg","release_date":"2026-10-15",""" +
          """"vote_average":8.1,"genre_ids":[28,878],"original_language":"it"}"""
      }}],"total_pages":10,"total_results":200}"""

    private fun tvBody(vararg series: Pair<Int, String>): String =
      """{"page":1,"results":[${series.joinToString(",") { (id, name) ->
        """{"id":$id,"name":"$name","original_name":"$name","overview":"Sinossi $id",""" +
          """"backdrop_path":"/bd$id.jpg","poster_path":"/po$id.jpg","first_air_date":"2026-03-01",""" +
          """"vote_average":7.5,"genre_ids":[35],"original_language":"it"}"""
      }}],"total_pages":10,"total_results":200}"""

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
  }
}
