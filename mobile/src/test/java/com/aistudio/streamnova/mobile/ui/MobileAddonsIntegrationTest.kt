package com.aistudio.streamnova.mobile.ui

import com.example.data.prefs.AppSettingsRepository
import com.example.data.stremio.StremioAddonRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Test di integrazione del flusso Add-on Mobile sui repository CONDIVISI
 * (`StremioAddonRepository`), con un server HTTP locale:
 *
 * - installazione reale da manifest, aggiornamento (stesso URL → sostituzione,
 *   mai duplicato), toggle per `installId`, rimozione;
 * - manifest HTTP 404 / non-JSON / senza `id` → errore reale, nessuna
 *   installazione fantasma;
 * - catalogo vuoto e catalogo in errore → lista vuota, nessun falso successo;
 * - URL invalido → errore di input senza alcuna chiamata di rete.
 */
class MobileAddonsIntegrationTest {

  private lateinit var server: HttpServer
  private var baseUrl = ""

  @Before
  fun setUp() {
    AppSettingsRepository.resetForTesting()
    cleanupAddons()
  }

  @After
  fun tearDown() {
    cleanupAddons()
    if (::server.isInitialized) server.stop(0)
    StremioAddonRepository.clearCaches()
    AppSettingsRepository.resetForTesting()
  }

  private fun cleanupAddons() {
    StremioAddonRepository.getInstalledAddons().forEach {
      StremioAddonRepository.removeAddon(it.installId)
    }
    StremioAddonRepository.clearCaches()
  }

  /** Avvia un server locale: manifest dinamico + risposte extra per path. */
  private fun startServer(
    manifest: () -> Pair<Int, String>,
    extra: Map<String, Pair<Int, String>> = emptyMap()
  ) {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/manifest.json") { exchange -> respond(exchange, manifest()) }
    extra.forEach { (path, response) ->
      server.createContext(path) { exchange -> respond(exchange, response) }
    }
    server.start()
    baseUrl = "http://127.0.0.1:${server.address.port}"
  }

  private fun respond(exchange: HttpExchange, response: Pair<Int, String>) {
    val bytes = response.second.toByteArray()
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(response.first, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private fun manifestJson(version: String) = """
    {"id":"localtest","name":"Local Test Addon","version":"$version",
     "resources":["stream","catalog"],"types":["movie"],
     "catalogs":[{"type":"movie","id":"top","name":"Top"}]}
  """.trimIndent().replace("\n", "")

  @Test
  fun installAggiornaToggleERimuoviSuAddonReale() = runBlocking {
    var version = "1.0.0"
    startServer(manifest = { 200 to manifestJson(version) })

    // Installazione incollando l'URL CON /manifest.json: viene normalizzato.
    val addon = StremioAddonRepository.installAddon("$baseUrl/manifest.json")
    assertEquals("localtest", addon.manifest.id)
    assertEquals("Local Test Addon", addon.manifest.name)
    assertEquals("1.0.0", addon.manifest.version)
    assertEquals(baseUrl, addon.baseUrl)
    assertEquals(1, StremioAddonRepository.getInstalledAddons().size)

    // Aggiornamento: reinstallo dello STESSO baseUrl → sostituzione, non duplicato.
    version = "2.0.0"
    val refreshed = StremioAddonRepository.installAddon(addon.baseUrl)
    assertEquals("2.0.0", refreshed.manifest.version)
    assertEquals(
      "L'aggiornamento non deve duplicare l'installazione",
      1,
      StremioAddonRepository.getInstalledAddons().size
    )

    // Toggle per installId (mai per manifest id).
    StremioAddonRepository.setAddonEnabled(addon.installId, false)
    assertTrue(StremioAddonRepository.getActiveAddons().isEmpty())
    StremioAddonRepository.setAddonEnabled(addon.installId, true)
    assertEquals(1, StremioAddonRepository.getActiveAddons().size)

    // Rimozione per installId.
    StremioAddonRepository.removeAddon(addon.installId)
    assertTrue(StremioAddonRepository.getInstalledAddons().isEmpty())
  }

  @Test
  fun unManifestHttp404NonProduceInstallazione() = runBlocking {
    startServer(manifest = { 404 to """{"error":"not found"}""" })

    try {
      StremioAddonRepository.installAddon(baseUrl)
      fail("Attesa IOException per manifest 404")
    } catch (e: IOException) {
      assertEquals(MobileAddonError.HTTP, classifyAddonError(e))
    }
    assertTrue(StremioAddonRepository.getInstalledAddons().isEmpty())
  }

  @Test
  fun unManifestNonJsonNonProduceInstallazione() = runBlocking {
    startServer(manifest = { 200 to "this is not json" })

    try {
      StremioAddonRepository.installAddon(baseUrl)
      fail("Attesa IOException per manifest non JSON")
    } catch (e: IOException) {
      assertEquals(MobileAddonError.MANIFEST_INVALID, classifyAddonError(e))
    }
    assertTrue(StremioAddonRepository.getInstalledAddons().isEmpty())
  }

  @Test
  fun unManifestSenzaIdNonProduceInstallazione() = runBlocking {
    startServer(manifest = { 200 to """{"name":"Addon senza id"}""" })

    try {
      StremioAddonRepository.installAddon(baseUrl)
      fail("Attesa IOException per manifest privo di id")
    } catch (e: IOException) {
      assertEquals(MobileAddonError.MANIFEST_INVALID, classifyAddonError(e))
      assertTrue(e.message.orEmpty().contains("'id'"))
    }
    assertTrue(StremioAddonRepository.getInstalledAddons().isEmpty())
  }

  @Test
  fun catalogoVuotoOInErroreNonInventaContenuti() = runBlocking {
    startServer(
      manifest = { 200 to manifestJson("1.0.0") },
      extra = mapOf(
        "/catalog/movie/top.json" to (200 to """{"metas":[]}"""),
        "/catalog/movie/broken.json" to (500 to """{"error":"boom"}""")
      )
    )

    val addon = StremioAddonRepository.installAddon(baseUrl)

    val empty = StremioAddonRepository.fetchCatalog(addon, "movie", "top")
    assertTrue("Un catalogo vuoto resta vuoto", empty.isEmpty())

    val broken = StremioAddonRepository.fetchCatalog(addon, "movie", "broken")
    assertTrue("Un catalogo in errore non produce item", broken.isEmpty())
  }

  @Test
  fun unUrlInvalidoNonChiamaLaRete() {
    try {
      runBlocking { StremioAddonRepository.installAddon("   ") }
      fail("Attesa IllegalArgumentException per URL vuoto")
    } catch (e: IllegalArgumentException) {
      assertEquals(MobileAddonError.INVALID_URL, classifyAddonError(e))
    }
    assertTrue(StremioAddonRepository.getInstalledAddons().isEmpty())
  }
}
