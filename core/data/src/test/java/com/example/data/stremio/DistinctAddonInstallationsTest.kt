package com.example.data.stremio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxStreamProvider
import com.example.data.torbox.TorBoxGateway
import com.example.data.torbox.TorBoxRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Due CONFIGURAZIONI dello stesso addon (Torrentio globale e Torrentio IT) devono
 * restare installazioni distinte.
 *
 * Torrentio restituisce lo stesso `manifest.id` e lo stesso `name` per ogni
 * configurazione: l'identità di gestione non può quindi essere il manifest id.
 * Questi test coprono:
 *  - stato enabled/disabled indipendente;
 *  - interrogazione separata delle due configurazioni;
 *  - attribuzione delle sorgenti e deduplica per installazione;
 *  - compatibilità del fallback legacy sul manifest id (una sola installazione).
 */
@RunWith(AndroidJUnit4::class)
class DistinctAddonInstallationsTest {

  private lateinit var server: HttpServer
  private lateinit var baseUrl: String
  private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

  private val globalBaseUrl: String get() = "$baseUrl/global"
  private val itBaseUrl: String get() = "$baseUrl/it"

  private val globalHash = "a".repeat(40)
  private val itHash = "b".repeat(40)
  private val sharedHash = "c".repeat(40)

  @Before
  fun setUp() {
    removeAllInstalledAddons()
    StremioAddonRepository.clearCaches()

    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange -> handle(exchange) }
    server.start()
    baseUrl = "http://127.0.0.1:${server.address.port}"
  }

  @After
  fun tearDown() {
    removeAllInstalledAddons()
    StremioAddonRepository.clearCaches()
    server.stop(0)
  }

  // ── 1. Identità e stato indipendente ───────────────────────────────────────

  @Test
  fun dueConfigurazioniHannoStessaIdentitaManifestMaInstallIdDiversi() = runBlocking {
    val global = StremioAddonRepository.installAddon("$globalBaseUrl/manifest.json")
    val italian = StremioAddonRepository.installAddon("$itBaseUrl/manifest.json")

    val installed = StremioAddonRepository.getInstalledAddons()
    assertEquals("entrambe le installazioni devono essere persistite", 2, installed.size)

    // Stessa identità di manifest (è ciò che Torrentio dichiara per ogni config)...
    assertEquals(global.id, italian.id)
    // ...ma identità di INSTALLAZIONE distinta, basata sul baseUrl configurato.
    assertNotEquals(global.installId, italian.installId)
    assertEquals(globalBaseUrl, global.installId)
    assertEquals(itBaseUrl, italian.installId)
    assertTrue(StremioAddonRepository.getActiveAddons().size == 2)
  }

  @Test
  fun disabilitareUnaConfigurazioneNonToccaLAltra() = runBlocking {
    val global = StremioAddonRepository.installAddon(globalBaseUrl)
    val italian = StremioAddonRepository.installAddon(itBaseUrl)

    fun byInstallId(id: String): InstalledAddon =
      StremioAddonRepository.getInstalledAddons().first { it.installId == id }

    // Disattiva Globale: IT resta attivo.
    StremioAddonRepository.setAddonEnabled(global.installId, false)
    assertFalse(byInstallId(global.installId).isEnabled)
    assertTrue(byInstallId(italian.installId).isEnabled)

    // Riattiva Globale senza modificare IT (che era rimasto attivo).
    StremioAddonRepository.setAddonEnabled(global.installId, true)
    assertTrue(byInstallId(global.installId).isEnabled)
    assertTrue(byInstallId(italian.installId).isEnabled)

    // Disattiva IT: Globale resta attivo.
    StremioAddonRepository.setAddonEnabled(italian.installId, false)
    assertTrue(byInstallId(global.installId).isEnabled)
    assertFalse(byInstallId(italian.installId).isEnabled)

    StremioAddonRepository.setAddonEnabled(italian.installId, true)
    assertTrue(byInstallId(global.installId).isEnabled)
    assertTrue(byInstallId(italian.installId).isEnabled)
  }

  @Test
  fun chiaveManifestLegacyModificaUnaSolaInstallazione() = runBlocking {
    val global = StremioAddonRepository.installAddon(globalBaseUrl)
    val italian = StremioAddonRepository.installAddon(itBaseUrl)

    // Chiamata legacy con il manifest id condiviso: non deve più spegnere entrambe.
    StremioAddonRepository.setAddonEnabled(global.id, false)

    val installed = StremioAddonRepository.getInstalledAddons()
    assertEquals("una sola installazione deve essere stata modificata", 1, installed.count { !it.isEnabled })
    assertTrue(installed.any { it.installId == italian.installId })
  }

  @Test
  fun rimuovereUnaConfigurazioneNonRimuoveLAltra() = runBlocking {
    val global = StremioAddonRepository.installAddon(globalBaseUrl)
    val italian = StremioAddonRepository.installAddon(itBaseUrl)

    StremioAddonRepository.removeAddon(global.installId)

    val installed = StremioAddonRepository.getInstalledAddons()
    assertEquals(1, installed.size)
    assertEquals(italian.installId, installed.single().installId)
  }

  // ── 2. Interrogazione separata e attribuzione dei risultati ────────────────

  @Test
  fun fetchStreamsInterrogaEntrambeLeConfigurazioniEAttribuisceISource() = runBlocking {
    StremioAddonRepository.installAddon(globalBaseUrl)
    StremioAddonRepository.installAddon(itBaseUrl)

    val candidates = StremioAddonRepository.fetchStreams(
      tmdbId = 0,
      isTv = false,
      imdbId = "tt0111161"
    )

    val byAddon = candidates.groupBy { it.baseUrl }
    assertEquals("entrambe le configurazioni devono produrre risultati", 2, byAddon.size)
    assertEquals(1, byAddon[globalBaseUrl].orEmpty().size)
    assertEquals(2, byAddon[itBaseUrl].orEmpty().size)
    assertTrue(
      "la variante IT deve contribuire con il proprio torrent",
      byAddon[itBaseUrl].orEmpty().any { it.item.infoHash == itHash }
    )

    // Ogni configurazione è stata interrogata sul PROPRIO path di configurazione.
    assertTrue(requests.contains("/global/stream/movie/tt0111161.json"))
    assertTrue(requests.contains("/it/stream/movie/tt0111161.json"))
  }

  @Test
  fun stesseRisposteDiDueConfigurazioniRestanoDistinteNellaDeduplica() {
    val global = StreamSource(
      infoHash = sharedHash,
      fileIdx = 0,
      serverName = "Torrentio 🧲 1080p",
      addonName = "Torrentio",
      addonInstallId = globalBaseUrl
    )
    val italian = global.copy(addonInstallId = itBaseUrl)

    assertNotEquals(
      "lo stesso torrent da due installazioni non è un duplicato",
      StreamManager.sourceDedupKey(global),
      StreamManager.sourceDedupKey(italian)
    )
    assertEquals(
      "lo stesso torrent nella stessa installazione resta un duplicato",
      StreamManager.sourceDedupKey(global),
      StreamManager.sourceDedupKey(global.copy(serverName = "Torrentio 🧲 4K"))
    )

    val collected = mutableListOf<StreamSource>()
    val added = StreamManager.addDistinctSources(collected, listOf(global, italian))
    assertEquals(2, added)
    assertEquals(listOf(globalBaseUrl, itBaseUrl), collected.map { it.addonInstallId })
  }

  @Test
  fun linkDirettoUgualeDaDueConfigurazioniRestaDistinto() {
    val url = "https://debrid.example.test/resolve/abc"
    val global = StreamSource(streamUrl = url, serverName = "Torrentio", addonName = "Torrentio", addonInstallId = globalBaseUrl)
    val italian = global.copy(addonInstallId = itBaseUrl)

    assertNotEquals(StreamManager.sourceDedupKey(global), StreamManager.sourceDedupKey(italian))
    assertEquals(
      StreamManager.sourceDedupKey(global),
      StreamManager.sourceDedupKey(global.copy(releaseTitle = "Another.Release.1080p.mkv"))
    )
  }

  @Test
  fun torBoxMantieneLoStessoTorrentPerOgniInstallazione() = runBlocking {
    val candidates = listOf(
      candidate(globalBaseUrl, sharedHash, fileIdx = 0),
      candidate(itBaseUrl, sharedHash, fileIdx = 0),
      // Duplicato interno alla stessa installazione: va scartato.
      candidate(itBaseUrl, sharedHash, fileIdx = 0)
    )
    val provider = TorBoxStreamProvider(torBox = FakeTorBoxGateway(setOf(sharedHash)))

    val sources = provider.resolveItems(candidates)

    assertEquals("una sorgente per installazione", 2, sources.size)
    assertEquals(setOf(globalBaseUrl, itBaseUrl), sources.map { it.addonInstallId }.toSet())
    assertTrue(sources.all { it.infoHash == sharedHash })
  }

  // ── Fixture ────────────────────────────────────────────────────────────────

  private fun candidate(baseUrl: String, hash: String, fileIdx: Int): StremioStreamCandidate =
    StremioStreamCandidate(
      addonName = "Torrentio",
      baseUrl = baseUrl,
      item = StremioStreamItem(
        name = "Torrentio\n1080p",
        title = "Release.1080p.mkv",
        infoHash = hash,
        fileIdx = fileIdx
      )
    )

  private fun handle(exchange: HttpExchange) {
    val path = exchange.requestURI.rawPath
    requests += path
    try {
      val body = when (path) {
        "/global/manifest.json", "/it/manifest.json" -> TORRENTIO_MANIFEST
        "/global/stream/movie/tt0111161.json" ->
          """{"streams":[{"name":"Torrentio\n1080p","title":"Global.Release.1080p.mkv","infoHash":"$globalHash","fileIdx":0}]}"""
        "/it/stream/movie/tt0111161.json" ->
          """{"streams":[
            {"name":"Torrentio\n1080p","title":"Italian.Release.ITA.1080p.mkv","infoHash":"$itHash","fileIdx":0},
            {"name":"Torrentio\n1080p","title":"Shared.Release.1080p.mkv","infoHash":"$sharedHash","fileIdx":0}
          ]}"""
        else -> null
      }
      if (body == null) {
        exchange.sendResponseHeaders(404, -1)
      } else {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
    } finally {
      exchange.close()
    }
  }

  private fun removeAllInstalledAddons() {
    // installId è univoco: usa quello, così anche due configurazioni con lo
    // stesso manifest.id vengono rimosse entrambe.
    StremioAddonRepository.getInstalledAddons().forEach {
      StremioAddonRepository.removeAddon(it.installId)
    }
  }

  private class FakeTorBoxGateway(private val cached: Set<String>) : TorBoxGateway {
    override fun isConfigured(): Boolean = true
    override fun normalizeHash(infoHash: String): String = infoHash.trim().lowercase()
    override suspend fun checkCached(
      hashes: List<String>
    ): Map<String, TorBoxRepository.TorBoxCacheEntry> =
      hashes.associateWith { hash ->
        TorBoxRepository.TorBoxCacheEntry(cached = cached.contains(hash), torrentId = 42L)
      }
    override suspend fun unlockCached(
      infoHash: String,
      fileIdx: Int?,
      entry: TorBoxRepository.TorBoxCacheEntry,
      season: Int?,
      episode: Int?
    ): String? = null
  }

  private companion object {
    /**
     * Manifest reale di Torrentio (campi rilevanti): id e name NON cambiano con la
     * configurazione, che vive solo nel path del baseUrl.
     */
    val TORRENTIO_MANIFEST = """
      {
        "id": "com.stremio.torrentio.addon",
        "version": "0.0.15",
        "name": "Torrentio",
        "description": "Provides torrent streams from scraped torrent providers.",
        "resources": [
          {"name": "stream", "types": ["movie", "series", "anime"], "idPrefixes": ["tt", "kitsu"]}
        ],
        "types": ["movie", "series", "anime", "other"],
        "idPrefixes": ["tt", "kitsu"]
      }
    """.trimIndent()
  }
}
