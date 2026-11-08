package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.api.TmdbApiClient
import com.example.data.model.MediaType
import com.example.data.model.SearchCategoryFilter
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.repository.MediaRepository
import com.example.data.repository.StremioCatalogRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.ui.screens.GLOBAL_SEARCH_CATEGORIES
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * Categoria "Talk" della sezione Ricerca.
 *
 * La categoria è servita NATIVAMENTE da TMDB sul genere TV ufficiale "Talk"
 * (`discover/tv?with_genres=10767`) e non deve dipendere da addon Stremio, modalità
 * Debrid o sorgenti di riproduzione. I test verificano che:
 *  - l'endpoint sia `discover/tv` con `with_genres=10767`, ordinato per popolarità;
 *  - nessun filtro di lingua/paese escluda i talk show stranieri;
 *  - la categoria funzioni senza addon installati e con Debrid disattivato;
 *  - la paginazione avanzi su TMDB e deduplichi i titoli già mostrati;
 *  - loading, errore TMDB e stato vuoto siano dichiarati;
 *  - il filtro anti-podcast delle altre categorie non venga applicato qui.
 *
 * Determinismo: TMDB è servito da un server HTTP locale
 * ([TmdbApiClient.overrideServiceBaseUrl]); nessuna rete e nessuna credenziale reale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SearchCategoryTalkTest {

  private lateinit var testDispatcher: TestDispatcher
  private val createdViewModels = mutableListOf<StreamNovaViewModel>()

  @Before
  fun setUp() {
    testDispatcher = StandardTestDispatcher()
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    AppSettingsRepository.init(context)
    AppSettingsRepository.setAppLanguage("it")
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    MediaRepository.init(context)
  }

  @After
  fun tearDown() {
    createdViewModels.forEach { it.viewModelScope.cancel() }
    createdViewModels.clear()
    TmdbApiClient.overrideServiceBaseUrl(null)
    StremioCatalogRepository.resetCinemetaBaseUrl()
    StremioCatalogRepository.clearCache()
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    AppSettingsRepository.setAppLanguage("it")
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
    Dispatchers.resetMain()
  }

  // ── 1. Fonte: TMDB, genere TV Talk, ordinamento per popolarità ───────────────

  @Test
  fun talkUsaTmdbConIlGenereTv10767EPopolarita(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTalkCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      val requests = tmdb.categoryRequests
      assertTrue("Richiesta discover attesa: $requests", requests.isNotEmpty())
      requests.forEach { request ->
        assertTrue("Endpoint serie TV atteso: $request", request.startsWith("/3/discover/tv"))
        assertTrue("Genere TV Talk atteso: $request", request.contains("with_genres=10767"))
        assertTrue(
          "Ordinamento per popolarità atteso: $request",
          request.contains("sort_by=popularity.desc")
        )
      }
      assertFalse(
        "La categoria Talk non interroga i film: ${tmdb.requested}",
        tmdb.requested.any { it.contains("/discover/movie") }
      )
      assertFalse(
        "Il filtro audio/sottotitoli non è delegato a TMDB: ${tmdb.requested}",
        tmdb.requested.any {
          it.contains("with_spoken_languages") ||
            it.contains("with_audio_language") ||
            it.contains("with_subtitle_language") ||
            it.contains("with_translations")
        }
      )

      assertEquals(SearchCategorySource.TMDB, viewModel.categoryState.value.source)
      assertNull(
        "Nessun errore TMDB atteso: ${viewModel.categoryState.value.statusMessage}",
        viewModel.categoryState.value.statusMessage
      )

      val record = viewModel.lastGenreLoads.value.singleOrNull()
      assertEquals(MediaType.SERIE_TV, record?.mediaType)
      assertEquals(GenreLoadReason.TMDB_NATIVE, record?.reason)
      assertEquals("Una pagina TMDB richiesta", 1, record?.tmdbPages)
    } finally {
      tmdb.stop()
    }
  }

  // ── 2. Italiano e straniero: nessun filtro di lingua o paese ────────────────

  @Test
  fun talkIncludeTalkShowItalianiEStranieriSenzaFiltroLingua(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTalkCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      val titles = viewModel.categoryItems.value.map { it.title }
      assertTrue("Talk show italiano mancante: $titles", titles.contains("Talk Italia Uno"))
      assertTrue("Talk show italiano mancante: $titles", titles.contains("Battute Italiane"))
      assertTrue("Talk show straniero mancante: $titles", titles.contains("The Late Night Show USA"))
      // Nessun filtro fisso per lingua originale o paese: anche un titolo con
      // "Talk Show" nel nome (che i cataloghi generici scartano) resta valido qui.
      assertTrue(
        "Talk show straniero scartato: $titles",
        titles.contains("Talk Show Straniero")
      )

      tmdb.requested.forEach { request ->
        assertFalse(
          "Nessun filtro di lingua originale: $request",
          request.contains("with_original_language")
        )
        assertFalse(
          "Nessun filtro di paese: $request",
          request.contains("with_origin_country") ||
            request.contains("region=") ||
            request.contains("watch_region=")
        )
      }
    } finally {
      tmdb.stop()
    }
  }

  // ── 3. Funziona senza Debrid e senza addon installati ──────────────────────

  @Test
  fun talkFunzionaSenzaDebridESenzaAddon(): Unit = runTest(testDispatcher) {
    // Debrid disattivato (modalità HTTP diretta, default) e nessun addon installato.
    // Anche lo switch "Usa TMDB come fallback per i generi" è OFF: la categoria Talk
    // non è un fallback, quindi deve funzionare comunque.
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setTmdbGenreFallbackEnabled(false)
    assertTrue(
      "Nessun addon deve essere installato",
      StremioAddonRepository.getInstalledAddons().isEmpty()
    )
    assertFalse(
      "Nessun catalogo addon personale",
      StremioCatalogRepository.hasCustomCatalogAddons()
    )
    assertEquals(
      "Debrid disattivato",
      StreamingEngineMode.HTTP_WEB,
      AppSettingsRepository.streamingEngineMode.value
    )

    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTalkCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      assertEquals(SearchCategorySource.TMDB, viewModel.categoryState.value.source)
      assertTrue(
        "Contenuti attesi senza Debrid/addon: ${viewModel.categoryItems.value}",
        viewModel.categoryItems.value.isNotEmpty()
      )
      // Tutte le richieste della categoria sono andate a TMDB, nessuna a un addon.
      assertTrue(
        "Solo richieste TMDB attese: ${tmdb.requested}",
        tmdb.requested.all { it.startsWith("/3/") }
      )
    } finally {
      tmdb.stop()
    }
  }

  // ── 4. Paginazione TMDB con deduplicazione tra le pagine ───────────────────

  @Test
  fun talkPaginaSuTmdbESenzaDuplicatiTraLePagine(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTalkCategory()
      assertTrue(
        "Prima pagina caricata",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )
      assertEquals("Prima pagina completa", 4, viewModel.categoryItems.value.size)

      viewModel.loadNextCategoryPage()
      assertTrue(
        "Seconda pagina caricata",
        awaitCondition {
          viewModel.categoryItems.value.any { it.title == "Talk Tedesco" } &&
            viewModel.categoryItems.value.any { it.title == "Talk Spagnolo" }
        }
      )

      val pageTwo = tmdb.categoryRequests.filter { it.contains("page=2") }
      assertTrue(
        "Pagina 2 richiesta su discover/tv: ${tmdb.categoryRequests}",
        pageTwo.isNotEmpty()
      )
      pageTwo.forEach { request ->
        assertTrue(
          "La paginazione mantiene il genere Talk: $request",
          request.contains("with_genres=10767")
        )
      }

      val items = viewModel.categoryItems.value
      val titles = items.map { it.title }
      // La pagina 2 del fixture ripete "The Late Night Show USA": deve comparire una volta.
      assertEquals(
        "Titolo duplicato tra le pagine: $titles",
        1,
        titles.count { it == "The Late Night Show USA" }
      )
      assertEquals(
        "Id duplicati tra le pagine: ${items.map { it.id }}",
        items.size,
        items.map { it.id }.distinct().size
      )
      // I titoli della prima pagina restano e si aggiungono solo quelli nuovi.
      assertEquals(
        "Totale atteso (4 + 2 nuovi): $titles",
        6,
        items.size
      )
      assertTrue("Prima pagina conservata: $titles", titles.contains("Talk Italia Uno"))
      assertTrue("Prima pagina conservata: $titles", titles.contains("Talk Show Straniero"))
    } finally {
      tmdb.stop()
    }
  }

  // ── 5. Errore TMDB dichiarato, non mascherato da categoria vuota riuscita ───

  @Test
  fun talkDichiaraErroreTmdb(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture(mode = TmdbMode.ERROR)
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTalkCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      assertTrue(
        "Lo stato deve dichiarare l'errore TMDB: ${viewModel.categoryState.value.statusMessage}",
        !viewModel.categoryState.value.statusMessage.isNullOrBlank()
      )
      assertTrue("Nessun contenuto senza risposta TMDB", viewModel.categoryItems.value.isEmpty())
      val record = viewModel.lastGenreLoads.value.singleOrNull()
      assertEquals(GenreLoadReason.TMDB_UNAVAILABLE, record?.reason)
      assertEquals("Nessuna pagina TMDB riuscita", 0, record?.tmdbPages)
    } finally {
      tmdb.stop()
    }
  }

  // ── 6. Stato vuoto dichiarato ───────────────────────────────────────────────

  @Test
  fun talkDichiaraStatoVuoto(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture(mode = TmdbMode.EMPTY)
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTalkCategory()

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )

      assertTrue("Nessun contenuto restituito", viewModel.categoryItems.value.isEmpty())
      assertEquals(SearchCategorySource.TMDB, viewModel.categoryState.value.source)
      assertTrue(
        "Lo stato vuoto deve essere dichiarato: ${viewModel.categoryState.value.statusMessage}",
        !viewModel.categoryState.value.statusMessage.isNullOrBlank()
      )
      val record = viewModel.lastGenreLoads.value.singleOrNull()
      assertEquals(GenreLoadReason.TMDB_NATIVE, record?.reason)
      assertEquals("TMDB ha risposto, ma senza risultati", 0, record?.tmdbItems)
    } finally {
      tmdb.stop()
    }
  }

  // ── 7. Loading: spinner finché la prima pagina non è pubblicata ─────────────

  @Test
  fun talkRestaLInCaricamentoFinoAlPrimoRisultato(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      val viewModel = createViewModel()
      viewModel.openTalkCategory()

      // Il dispatcher di test non ha ancora avanzato le coroutine: il caricamento
      // è iniziato e lo stato non dichiara ancora una sorgente.
      assertTrue("Loading atteso subito dopo l'apertura", viewModel.isCategoryLoading.value)
      assertNull(
        "Nessuna sorgente dichiarata prima della risposta TMDB",
        viewModel.categoryState.value.source
      )

      assertTrue(
        "La categoria deve terminare il caricamento",
        awaitCondition {
          viewModel.categoryState.value.source != null &&
            !viewModel.isCategoryLoading.value
        }
      )
      assertFalse(viewModel.isCategoryLoading.value)
    } finally {
      tmdb.stop()
    }
  }

  // ── 8. Le altre categorie mantengono il filtro anti-podcast ────────────────

  @Test
  fun talkNonCambiaIlFiltroAntiPodcastDelleAltreCategorie(): Unit = runTest(testDispatcher) {
    val tmdb = TmdbFixture()
    tmdb.start()
    try {
      // Percorso storico (default excludeNonEditorialTv = true): un titolo con
      // "Talk Show" nel nome resta escluso dai cataloghi generici.
      val generic = MediaRepository.discoverByGenre(
        MediaType.SERIE_TV,
        TMDB_TV_GENRE_ID_TALK_FOR_TEST,
        page = 1
      )
      assertTrue("Risposta TMDB attesa", generic != null)
      assertFalse(
        "Il filtro anti-podcast delle altre categorie non deve cambiare: ${generic?.map { it.title }}",
        generic.orEmpty().any { it.title == "Talk Show Straniero" }
      )
    } finally {
      tmdb.stop()
    }
  }

  // ── Helper ──────────────────────────────────────────────────────────────────

  private fun createViewModel(): StreamNovaViewModel =
    StreamNovaViewModel(ioDispatcher = testDispatcher).also {
      it.attachContext(ApplicationProvider.getApplicationContext())
      createdViewModels += it
    }

  private fun StreamNovaViewModel.openTalkCategory() {
    val cat = GLOBAL_SEARCH_CATEGORIES.first { it.id == "talk" }
    assertEquals(SearchCategoryFilter.TMDB_TV_GENRE, cat.contentFilter)
    assertEquals(TMDB_TV_GENRE_ID_TALK_FOR_TEST, cat.tvGenreId)
    initCategory(
      categoryId = cat.id,
      keywords = cat.keywords,
      movieGenreId = cat.movieGenreId,
      tvGenreId = cat.tvGenreId,
      contentFilter = cat.contentFilter
    )
  }

  /** Attende un evento reale (rete/thread di IO) facendo avanzare il dispatcher di test. */
  private fun awaitCondition(timeoutMs: Long = 30_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      testDispatcher.scheduler.advanceUntilIdle()
      if (condition()) return true
      if (System.currentTimeMillis() >= deadline) return false
      Thread.sleep(50)
    }
  }

  private companion object {
    /** Genere TV TMDB "Talk" dichiarato da TMDB (`/3/genre/tv/list`). */
    const val TMDB_TV_GENRE_ID_TALK_FOR_TEST = 10767
  }

  // ── Fixture TMDB ────────────────────────────────────────────────────────────

  private enum class TmdbMode { OK, ERROR, EMPTY }

  /**
   * Server TMDB locale con due pagine del genere Talk.
   *
   * La prima pagina mescola talk show italiani (`original_language=it`) e stranieri
   * (en): la categoria non deve filtrare per lingua. "Talk Show Straniero" contiene
   * la parola chiave scartata dai cataloghi generici, per provare che qui resta.
   * La seconda pagina ripete un titolo della prima: la deduplicazione deve evitarlo.
   */
  private class TmdbFixture(private val mode: TmdbMode = TmdbMode.OK) {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start() {
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      TmdbApiClient.overrideServiceBaseUrl("http://127.0.0.1:${server.address.port}/3/")
    }

    fun stop() {
      TmdbApiClient.overrideServiceBaseUrl(null)
      server.stop(0)
    }

    /** Richieste della categoria: solo le `discover` con il genere Talk. */
    val categoryRequests: List<String>
      get() = synchronized(requested) {
        requested.filter {
          it.contains("/discover/") && it.contains("with_genres=10767")
        }
      }

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.rawPath
      val query = exchange.requestURI.rawQuery.orEmpty()
      requested += "$path?$query"
      try {
        when (mode) {
          TmdbMode.ERROR -> respond(exchange, 500, """{"status_message":"tmdb down"}""")
          TmdbMode.EMPTY -> respond(exchange, 200, emptyBody())
          TmdbMode.OK -> when {
            path.endsWith("/discover/tv") -> {
              val page = Regex("page=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 1
              respond(exchange, 200, tvBody(page))
            }
            else -> respond(exchange, 404, """{"status_message":"not found"}""")
          }
        }
      } finally {
        exchange.close()
      }
    }

    private fun emptyBody(): String =
      """{"page":1,"results":[],"total_pages":1,"total_results":0}"""

    private fun tvBody(page: Int): String = if (page > 1) """
      {"page":$page,"results":[
        {"id":910002,"name":"The Late Night Show USA","original_name":"The Late Night Show USA",
         "first_air_date":"2019-09-10","overview":"Talk show serale americano","poster_path":"/t2.jpg",
         "vote_average":7.9,"genre_ids":[10767],"original_language":"en"},
        {"id":910005,"name":"Talk Tedesco","original_name":"Talk Tedesco",
         "first_air_date":"2021-04-02","overview":"Talk show tedesco","poster_path":"/t5.jpg",
         "vote_average":7.1,"genre_ids":[10767],"original_language":"de"},
        {"id":910006,"name":"Talk Spagnolo","original_name":"Talk Spagnolo",
         "first_air_date":"2022-02-18","overview":"Talk show spagnolo","poster_path":"/t6.jpg",
         "vote_average":6.8,"genre_ids":[10767],"original_language":"es"}
      ],"total_pages":3,"total_results":7}
    """.trimIndent() else """
      {"page":1,"results":[
        {"id":910001,"name":"Talk Italia Uno","original_name":"Talk Italia Uno",
         "first_air_date":"2020-01-13","overview":"Talk show italiano del daytime","poster_path":"/t1.jpg",
         "vote_average":7.4,"genre_ids":[10767],"original_language":"it"},
        {"id":910002,"name":"The Late Night Show USA","original_name":"The Late Night Show USA",
         "first_air_date":"2019-09-10","overview":"Talk show serale americano","poster_path":"/t2.jpg",
         "vote_average":7.9,"genre_ids":[10767],"original_language":"en"},
        {"id":910003,"name":"Talk Show Straniero","original_name":"Talk Show Straniero",
         "first_air_date":"2018-06-04","overview":"Talk show internazionale","poster_path":"/t3.jpg",
         "vote_average":7.0,"genre_ids":[10767],"original_language":"en"},
        {"id":910004,"name":"Battute Italiane","original_name":"Battute Italiane",
         "first_air_date":"2023-03-06","overview":"Talk show italiano in seconda serata","poster_path":"/t4.jpg",
         "vote_average":6.9,"genre_ids":[10767],"original_language":"it"}
      ],"total_pages":3,"total_results":7}
    """.trimIndent()

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
  }
}
