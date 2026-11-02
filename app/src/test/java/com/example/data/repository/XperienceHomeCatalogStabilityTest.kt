package com.example.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaItem
import com.example.data.stremio.StremioAddonRepository
import com.example.ui.screens.homeExtraCardKey
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Stabilità dei cataloghi Xperience nella Home (Fase 3 della richiesta).
 *
 * L'addon reale non è disponibile nei test: il server locale RICREA lo schema dei
 * suoi cataloghi (`themed_*`, `trending_*`, `genre_*`, con extra `skip`) e usa
 * fixture rappresentative delle collection "Jurassic World" e "Nightmare".
 * Nessun id di catalogo è codificato nel codice di produzione.
 *
 * Ogni test riproduce una condizione reale di scroll/refresh:
 *  - caricamento iniziale di una collection;
 *  - refresh con catalogo in errore, risposta vuota o pagina parziale;
 *  - risposte asincrone fuori ordine;
 *  - poster mancante per un singolo elemento;
 *  - refresh ripetuti (nessun duplicato, chiavi stabili).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class XperienceHomeCatalogStabilityTest {

  private lateinit var addon: MutableResponseAddon

  // ── Percorsi dei cataloghi dichiarati nel manifest di fixture ───────────────

  private val jurassicPath = "/catalog/movie/mlist_themed_jurassic.json"
  private val nightmarePath = "/catalog/movie/mlist_themed_nightmare.json"
  private val trendingPath = "/catalog/movie/mlist_trending_movies.json"
  private val staticPath = "/catalog/movie/mlist_themed_static.json"
  private val sagaPath = "/catalog/series/mlist_themed_saga.json"

  @Before
  fun setUp() {
    StremioCatalogRepository.resetHomeSectionMemory()
    StremioCatalogRepository.clearCache()
    addon = MutableResponseAddon(manifest())
    runBlocking { StremioAddonRepository.installAddon(addon.start()) }
    addon.respond(jurassicPath, metas(JURASSIC))
    addon.respond(nightmarePath, metas(NIGHTMARE))
    addon.respond(trendingPath, metas(TRENDING))
    addon.respond(staticPath, metas(STATIC))
    addon.respond(sagaPath, metas(SAGA, type = "series"))
  }

  @After
  fun tearDown() {
    StremioCatalogRepository.clearCache()
    StremioCatalogRepository.resetHomeSectionMemory()
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    addon.stop()
  }

  // ── Fixture locale ──────────────────────────────────────────────────────────

  private data class Response(val code: Int, val body: String)

  /**
   * Addon Stremio locale con risposte MUTABILI: ogni test può simulare errore,
   * risposta vuota o pagina parziale senza reinstallare l'addon (l'id del catalogo
   * e quindi la riga Home restano gli stessi, come nell'addon reale).
   */
  private class MutableResponseAddon(private val manifestJson: String) {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val requestsByPath = ConcurrentHashMap<String, Int>()
    private val responses = ConcurrentHashMap<String, Response>()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start(): String {
      server.executor = java.util.concurrent.Executors.newCachedThreadPool()
      server.createContext("/") { exchange -> handle(exchange) }
      server.start()
      return "http://127.0.0.1:${server.address.port}"
    }

    fun stop() = server.stop(0)

    fun respond(path: String, body: String, code: Int = 200) {
      responses[path] = Response(code, body)
    }

    /** Rende la risposta di [path] bloccante finché il latch non viene aperto. */
    fun gate(path: String): CountDownLatch = gates.getOrPut(path) { CountDownLatch(1) }

    fun clearGate(path: String) {
      gates.remove(path)
    }

    private fun handle(exchange: HttpExchange) {
      val path = exchange.requestURI.rawPath
      requested += path
      requestsByPath.merge(path, 1, Int::plus)
      try {
        when {
          path == "/manifest.json" -> respond(exchange, 200, manifestJson)
          responses.containsKey(path) -> {
            gates[path]?.await(30, TimeUnit.SECONDS)
            val response = responses.getValue(path)
            respond(exchange, response.code, response.body)
          }
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

  private fun manifest(): String = """
    {
      "id": "org.xperience.test",
      "name": "Xperience",
      "version": "1.0.0",
      "description": "Fixture locale Xperience per la stabilità della Home",
      "resources": ["catalog"],
      "types": ["movie", "series"],
      "idPrefixes": ["tt", "tmdb:"],
      "catalogs": [
        {"type":"movie","id":"mlist_themed_jurassic","name":"Jurassic World",
         "extra":[{"name":"skip","isRequired":false}]},
        {"type":"movie","id":"mlist_themed_nightmare","name":"Nightmare",
         "extra":[{"name":"skip","isRequired":false}]},
        {"type":"movie","id":"mlist_trending_movies","name":"Trending",
         "extra":[{"name":"skip","isRequired":false}]},
        {"type":"movie","id":"mlist_themed_static","name":"Collection Complete"},
        {"type":"series","id":"mlist_themed_saga","name":"Saga",
         "extra":[{"name":"skip","isRequired":false}]}
      ]
    }
  """.trimIndent()

  /** Metadati Stremio con gli `id` indicati, nell'ordine dato (nessun poster). */
  private fun metas(items: List<FixtureItem>, type: String = "movie"): String =
    """{"metas":[${items.joinToString(",") { item ->
      """{"id":"${item.id}","type":"$type","name":"${item.title}","releaseInfo":"${item.year}"}"""
    }}]}"""

  /** Come [metas], ma con la locandina inviata dall'addon (salvo gli [withoutPoster]). */
  private fun metasWithPoster(
    items: List<FixtureItem>,
    type: String = "movie",
    withoutPoster: Set<String> = emptySet()
  ): String {
    val entries = items.joinToString(",") { item ->
      val fields = mutableListOf(
        "\"id\":\"${item.id}\"",
        "\"type\":\"$type\"",
        "\"name\":\"${item.title}\"",
        "\"releaseInfo\":\"${item.year}\""
      )
      if (item.id !in withoutPoster) {
        fields += "\"poster\":\"${item.poster}\""
      }
      "{${fields.joinToString(",")}}"
    }
    return """{"metas":[$entries]}"""
  }

  private data class FixtureItem(
    val id: String,
    val title: String,
    val year: Int = 2024,
    val poster: String = "https://images.example/$id.jpg"
  )

  private val JURASSIC = listOf(
    FixtureItem("tt0107290", "Jurassic Park", 1993),
    FixtureItem("tt0119567", "The Lost World: Jurassic Park", 1997),
    FixtureItem("tt0163025", "Jurassic Park III", 2001),
    FixtureItem("tt0369610", "Jurassic World", 2015),
    FixtureItem("tt4881806", "Jurassic World: Fallen Kingdom", 2018),
    FixtureItem("tt8041270", "Jurassic World Dominion", 2022),
    FixtureItem("tt13627748", "Jurassic World Rebirth", 2025),
    FixtureItem("tt0107291", "Jurassic Park: The Lost Cut", 1994)
  )

  private val NIGHTMARE = listOf(
    FixtureItem("tt0087800", "Nightmare - Dal profondo della notte", 1984),
    FixtureItem("tt1179904", "Nightmare 2 - La rivincita", 1985),
    FixtureItem("tt0093629", "Nightmare 3 - I guerrieri del sogno", 1987),
    FixtureItem("tt0095742", "Nightmare 4 - Il non risveglio", 1988),
    FixtureItem("tt0097981", "Nightmare 5 - Il mito", 1989),
    FixtureItem("tt0100649", "Nightmare 6 - La fine", 1991),
    FixtureItem("tt1877830", "Nightmare - Nuovo incubo", 1994),
    FixtureItem("tt1259014", "Nightmare: The Reboot", 2010)
  )

  private val TRENDING = listOf(
    FixtureItem("tt9000001", "Trending Uno"),
    FixtureItem("tt9000002", "Trending Due"),
    FixtureItem("tt9000003", "Trending Tre")
  )

  private val STATIC = listOf(
    FixtureItem("tt9100001", "Static Uno"),
    FixtureItem("tt9100002", "Static Due"),
    FixtureItem("tt9100003", "Static Tre"),
    FixtureItem("tt9100004", "Static Quattro"),
    FixtureItem("tt9100005", "Static Cinque")
  )

  private val SAGA = listOf(
    FixtureItem("tt9200001", "Saga Uno"),
    FixtureItem("tt9200002", "Saga Due")
  )

  // ── Helper di risoluzione ───────────────────────────────────────────────────

  private fun resolve(): HomeCatalogs = runBlocking {
    StremioCatalogRepository.resolveHomeCatalogs(emptyList()) { emptyList() }
  }

  /** Contenuto atteso per un elemento della fixture (identità della card). */
  private fun itemFor(fixture: FixtureItem): MediaItem = MediaItem(
    id = fixture.id,
    title = fixture.title,
    synopsis = "",
    videoUrl = "",
    year = fixture.year
  )

  /** Sezione per `catalogId`, oppure `null` se la riga è sparita dalla Home. */
  private fun HomeCatalogs.section(catalogId: String): CatalogSection? =
    extraSections.firstOrNull { it.catalogId == catalogId }

  private fun requireSection(catalogId: String, catalogs: HomeCatalogs = resolve()): CatalogSection {
    val section = catalogs.section(catalogId)
    assertNotNull("La riga '$catalogId' deve restare nella Home", section)
    return section!!
  }

  // ── 1. Caricamento iniziale ─────────────────────────────────────────────────

  @Test
  fun laCollectionJurassicWorldSiCaricaConTutteLeCard() {
    val catalogs = resolve()
    val jurassic = requireSection("mlist_themed_jurassic", catalogs)

    assertEquals("stremio_org.xperience.test_movie_mlist_themed_jurassic", jurassic.id)
    assertEquals(JURASSIC.map { it.id }, jurassic.items.map { it.id })
    assertEquals(8, jurassic.items.size)
  }

  // ── 2. Refresh con catalogo in errore ───────────────────────────────────────

  @Test
  fun unCatalogoInErroreNonCancellaLaRigaGiaCaricata() {
    requireSection("mlist_themed_jurassic")
    StremioCatalogRepository.clearCache()

    addon.respond(jurassicPath, """{"error":"temporaneo"}""", code = 503)
    val refreshed = resolve()

    val jurassic = requireSection("mlist_themed_jurassic", refreshed)
    assertEquals(JURASSIC.map { it.id }, jurassic.items.map { it.id })
  }

  // ── 3. Risposta vuota temporanea ────────────────────────────────────────────

  @Test
  fun unaRispostaVuotaTemporaneaNonCancellaLaCollectionNightmare() {
    requireSection("mlist_themed_nightmare")
    StremioCatalogRepository.clearCache()

    addon.respond(nightmarePath, """{"metas":[]}""")
    val refreshed = resolve()

    val nightmare = requireSection("mlist_themed_nightmare", refreshed)
    assertEquals(NIGHTMARE.map { it.id }, nightmare.items.map { it.id })
  }

  // ── 4. Risposta parziale ────────────────────────────────────────────────────

  @Test
  fun unaRispostaParzialeNonRiduceLaCollectionAllUltimoPoster() {
    requireSection("mlist_themed_jurassic")
    StremioCatalogRepository.clearCache()

    // L'addon risponde con una sola card: la riga NON deve passare da 8 a 1.
    addon.respond(jurassicPath, metas(listOf(JURASSIC.first())))
    val refreshed = resolve()

    val jurassic = requireSection("mlist_themed_jurassic", refreshed)
    assertEquals(8, jurassic.items.size)
    assertEquals(8, jurassic.items.map { it.id }.distinct().size)
    assertEquals(JURASSIC.first().id, jurassic.items.first().id)
  }

  // ── 5. Risposte asincrone fuori ordine ──────────────────────────────────────

  /**
   * Una risoluzione LENTA (iniziata prima, conclusa dopo) non deve sovrascrivere
   * quella più recente già applicata: la Home resta quella fresca e la riga non si
   * accorcia. Il ritardo è nel loader delle righe di default (le serie non sono
   * coperte dall'addon), non nelle risposte del catalogo: nessun deadlock sul
   * single-flight HTTP.
   */
  @Test
  fun unaRisoluzioneFuoriOrdineNonSovrascriveLaHomePiuRecente(): Unit = runBlocking {
    addon.respond(sagaPath, """{"metas":[]}""")
    val slowEntered = CountDownLatch(1)
    val releaseSlow = CountDownLatch(1)

    val slow = async(Dispatchers.IO) {
      StremioCatalogRepository.resolveHomeCatalogs(emptyList()) {
        slowEntered.countDown()
        releaseSlow.await(30, TimeUnit.SECONDS)
        emptyList()
      }
    }
    assertTrue(
      "La risoluzione lenta deve entrare nel loader",
      slowEntered.await(30, TimeUnit.SECONDS)
    )

    // Risoluzione più recente: la collection arriva parziale (1 card).
    StremioCatalogRepository.clearCache()
    addon.respond(jurassicPath, metas(listOf(JURASSIC.first())))
    val fresh = StremioCatalogRepository.resolveHomeCatalogs(emptyList()) { emptyList() }

    releaseSlow.countDown()
    val stale = slow.await()

    assertEquals(
      "Il risultato obsoleto non deve essere applicato",
      fresh,
      stale
    )
    val jurassic = requireSection("mlist_themed_jurassic", stale)
    assertEquals(8, jurassic.items.size)
  }

  // ── 6. Poster mancante per un singolo elemento ──────────────────────────────
  @Test
  fun ilRefreshDiUnSoloCatalogoNonRicreaLeAltreRighe() {
    val first = resolve()
    StremioCatalogRepository.clearCache()

    // Cambia SOLO Jurassic World: le altre righe non devono essere ricreate,
    // così Compose non rimonta le loro card (niente poster che riappaiono).
    addon.respond(jurassicPath, metas(listOf(JURASSIC.first())))
    val second = resolve()

    val nightmareFirst = requireSection("mlist_themed_nightmare", first)
    val nightmareSecond = requireSection("mlist_themed_nightmare", second)
    assertSame(
      "Una riga non toccata dal refresh deve mantenere la stessa istanza",
      nightmareFirst,
      nightmareSecond
    )
    assertSame(nightmareFirst.items.first(), nightmareSecond.items.first())

    val trendingFirst = requireSection("mlist_trending_movies", first)
    val trendingSecond = requireSection("mlist_trending_movies", second)
    assertSame(trendingFirst, trendingSecond)
  }

  @Test
  fun unPosterMancanteNonRimuoveLaCardNeGliAltriPoster() {
    addon.respond(jurassicPath, metasWithPoster(JURASSIC))
    val first = resolve()
    val before = requireSection("mlist_themed_jurassic", first)
    assertTrue(before.items.all { !it.posterUrl.isNullOrBlank() })

    // Il refresh restituisce le stesse card, ma UNA SOLA senza locandina.
    val withoutPoster = JURASSIC[2].id
    StremioCatalogRepository.clearCache()
    addon.respond(jurassicPath, metasWithPoster(JURASSIC, withoutPoster = setOf(withoutPoster)))
    val refreshed = requireSection("mlist_themed_jurassic", resolve())

    assertEquals(8, refreshed.items.size)
    assertTrue(
      "Nessuna card deve perdere la locandina già mostrata",
      refreshed.items.all { !it.posterUrl.isNullOrBlank() }
    )
    val affected = refreshed.items.first { it.id == withoutPoster }
    assertEquals(before.items.first { it.id == withoutPoster }.posterUrl, affected.posterUrl)
  }

  // ── 7. Refresh ripetuti senza duplicati ─────────────────────────────────────

  @Test
  fun refreshRipetutiNonIntroduconoDuplicati() {
    val first = resolve()
    StremioCatalogRepository.clearCache()
    val second = resolve()
    StremioCatalogRepository.clearCache()
    val third = resolve()

    listOf(first, second, third).forEach { catalogs ->
      val jurassic = requireSection("mlist_themed_jurassic", catalogs)
      val nightmare = requireSection("mlist_themed_nightmare", catalogs)
      assertEquals(8, jurassic.items.size)
      assertEquals(8, nightmare.items.size)
      assertEquals(jurassic.items.size, jurassic.items.map { it.id }.distinct().size)
      assertEquals(nightmare.items.size, nightmare.items.map { it.id }.distinct().size)
    }
  }

  // ── 8. Stabilità delle chiavi e del numero di card ──────────────────────────

  @Test
  fun chiaviDiRigheECardRestanoStabiliTraRefresh() {
    val first = resolve()
    StremioCatalogRepository.clearCache()
    // Refresh con risposta parziale: la riga deve avere ancora le stesse card.
    addon.respond(jurassicPath, metas(listOf(JURASSIC[3])))
    addon.respond(nightmarePath, """{"metas":[]}""")
    val second = resolve()

    val rowKey = "section_stremio_org.xperience.test_movie_mlist_themed_jurassic"
    val jurassicFirst = requireSection("mlist_themed_jurassic", first)
    val jurassicSecond = requireSection("mlist_themed_jurassic", second)

    assertEquals(jurassicFirst.id, jurassicSecond.id)
    assertEquals(
      "La key Compose della riga non deve cambiare",
      rowKey,
      "section_${jurassicSecond.id}"
    )

    val keysFirst = jurassicFirst.items.map { homeExtraCardKey(jurassicFirst.id, it) }
    val keysSecond = jurassicSecond.items.map { homeExtraCardKey(jurassicSecond.id, it) }
    // Le STESSE card (identità, non posizione): la risposta parziale porta in testa
    // la card ricevuta, ma nessuna card viene ricreata o persa.
    assertEquals(keysFirst.toSet(), keysSecond.toSet())
    assertEquals(keysFirst.size, keysSecond.size)
    assertEquals(keysFirst.size, keysFirst.distinct().size)
    assertEquals(JURASSIC.size, keysSecond.size)

    val nightmare = requireSection("mlist_themed_nightmare", second)
    assertEquals(NIGHTMARE.size, nightmare.items.size)
    assertEquals(
      NIGHTMARE.map { homeExtraCardKey(nightmare.id, itemFor(it)) },
      nightmare.items.map { homeExtraCardKey(nightmare.id, it) }
    )
  }

  // ── 9. Semantica della rimozione ────────────────────────────────────────────

  @Test
  fun unCatalogoCompletoNonPaginatoPuoAccorciarsi() {
    val first = requireSection("mlist_themed_static")
    assertEquals(STATIC.size, first.items.size)
    StremioCatalogRepository.clearCache()

    // Nessun extra `skip`: la risposta È l'elenco completo, quindi è finale.
    addon.respond(staticPath, metas(STATIC.take(3)))
    val refreshed = requireSection("mlist_themed_static", resolve())
    assertEquals(STATIC.take(3).map { it.id }, refreshed.items.map { it.id })
  }

  @Test
  fun unCatalogoNonPiuDichiaratoVieneRimossoDallaHome() {
    requireSection("mlist_themed_jurassic")
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }

    val refreshed = resolve()

    assertTrue(
      "Le righe di un addon rimosso non devono restare",
      refreshed.extraSections.isEmpty()
    )
    assertTrue(StremioCatalogRepository.homeSectionMemorySnapshot().isEmpty())
  }
}
