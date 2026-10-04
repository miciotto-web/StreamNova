package com.example.data.streaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.prefs.AppSettingsRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.data.torbox.TorBoxGateway
import com.example.data.stremio.StremioManifest
import com.example.data.stremio.StremioStreamResponse
import com.example.data.torbox.TorBoxRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.InetSocketAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test delle 4 aree nuove:
 *  - normalizzazione URL e validazione manifest degli **addon Stremio**;
 *  - parsing dei DTO Stremio (manifest / stream);
 *  - parsing della risposta `checkcached` di **TorBox**;
 *  - ordinamento delle sorgenti per qualità con spareggio Debrid in [StreamManager].
 */
@RunWith(AndroidJUnit4::class)
class StremioTorBoxTest {

  // ── 1. Normalizzazione URL addon ────────────────────────────────────────

  @Test
  fun normalizeUrlConverteStremioEMozzaManifest() {
    assertEquals(
      "https://torrentio.strem.fun",
      StremioAddonRepository.normalizeUrl("stremio://torrentio.strem.fun/manifest.json")
    )
    assertEquals(
      "https://addon.example.com/50540a7325788",
      StremioAddonRepository.normalizeUrl("https://addon.example.com/50540a7325788/manifest.json")
    )
    assertEquals(
      "https://torrentio.strem.fun",
      StremioAddonRepository.normalizeUrl("  torrentio.strem.fun/  ")
    )
    assertEquals(
      "https://cinemeta.example.org",
      StremioAddonRepository.normalizeUrl("https://cinemeta.example.org/")
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun normalizeUrlRifiutaUrlVuoto() {
    StremioAddonRepository.normalizeUrl("   ")
  }

  // ── 2. Path stream e manifest ───────────────────────────────────────────

  @Test
  fun buildStreamPathSerieEFilm() {
    assertEquals(
      "series/tt0944947:1:2.json",
      StremioAddonRepository.buildStreamPath("series", "tt0944947", 1, 2)
    )
    assertEquals(
      "movie/tt0111161.json",
      StremioAddonRepository.buildStreamPath("movie", "tt0111161", null, null)
    )
  }

  @Test
  fun manifestParsingRisorseCompatteEOggetti() {
    val json = """
      {
        "id": "torrentio",
        "name": "Torrentio",
        "version": "0.0.5",
        "description": "Addon di prova",
        "resources": ["stream", {"name": "meta"}],
        "types": ["movie", "series"],
        "idPrefixes": ["tt"]
      }
    """.trimIndent()
    val manifest = moshi().adapter(StremioManifest::class.java).fromJson(json)!!

    assertEquals("torrentio", manifest.id)
    assertEquals("Torrentio", manifest.displayTitle)
    assertEquals(listOf("stream", "meta"), manifest.resourceNames())
    assertTrue("deve dichiarare la risorsa stream", manifest.supportsStream())
    assertTrue(manifest.supportsType("series"))
    assertFalse(manifest.supportsType("anime"))
  }

  @Test
  fun streamResponseParsingConInfoHashEFileIdx() {
    val json = """
      {"streams":[{"name":"Torrentio 4K HDR","title":"file.mkv","infoHash":"ABCDEF","fileIdx":3}]}
    """.trimIndent()
    val response = moshi().adapter(StremioStreamResponse::class.java).fromJson(json)!!
    val item = response.streams!!.first()
    assertEquals("ABCDEF", item.infoHash)
    assertEquals(3, item.fileIdx)
    assertTrue("label deve contenere il nome", item.label.contains("4K"))
    assertEquals("4K", TorBoxStreamProvider.parseQualityLabel(item.label))
  }

  // ── 3. TorBox: parsing checkcached ──────────────────────────────────────

  @Test
  fun torBoxParseCheckCachedFormatoObject() {
    val data = mapOf(
      "ABC123" to mapOf(
        "cached" to true,
        "torrent" to mapOf("id" to 42, "files" to listOf(mapOf("id" to 1, "name" to "a.mkv", "size" to 10)))
      ),
      "DEF456" to mapOf("cached" to false)
    )
    val parsed = TorBoxRepository.parseCheckCached(data)

    val entry = parsed["abc123"]
    assertTrue("hash in cache", entry != null && entry.cached)
    assertEquals(42L, entry?.torrentId)
    assertEquals(1, entry?.files?.first()?.id)
    assertFalse("hash non in cache", parsed["def456"]?.cached ?: true)
  }

  @Test
  fun torBoxParseCheckCachedFormatoListEVuoto() {
    val data = listOf(mapOf("hash" to "BEEF", "cached" to true, "id" to 7))
    val parsed = TorBoxRepository.parseCheckCached(data)
    assertTrue(parsed["beef"]?.cached == true)
    assertEquals(7L, parsed["beef"]?.torrentId)

    assertTrue(TorBoxRepository.parseCheckCached(null).isEmpty())
  }

  @Test
  fun torBoxNonConfiguratoNonFornisceStream() = runBlocking {
    // Nessuna chiave salvata (DataStore non inizializzato nei test): il provider
    // deve restituire vuoto senza fare alcuna chiamata di rete.
    val provider = TorBoxStreamProvider()
    val sources = provider.getStreams(
      tmdbId = 1418,
      isTv = true,
      season = 1,
      episode = 1,
      title = "The Big Bang Theory",
      year = 2007
    )
    assertTrue(sources.isEmpty())
    assertTrue(TorBoxRepository.resolveInfoHash("deadbeef", null) == null)
  }

  // ── 4. Installazione/rimozione addon su server locale ───────────────────

  @Test
  fun installaAbilitaERimuoveAddon(): Unit = runBlocking {
    val manifestJson = """
      {"id":"localtest","name":"Local Test Addon","version":"1.2.3",
       "description":"Addon di prova","resources":["stream"],
       "types":["movie","series"],"idPrefixes":["tt"]}
    """.trimIndent().replace("\n", "")

    val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/manifest.json") { exchange ->
      val bytes = manifestJson.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    val baseUrl = "http://127.0.0.1:${server.address.port}"

    try {
      // Installa incollando l'URL CON il suffisso /manifest.json: deve essere normalizzato.
      val addon = StremioAddonRepository.installAddon("$baseUrl/manifest.json")
      assertEquals("localtest", addon.manifest.id)
      assertEquals(baseUrl, addon.baseUrl)
      assertTrue(
        StremioAddonRepository.getInstalledAddons().any { it.id == "localtest" }
      )

      // Disabilita / riabilita (switch della schermata Addon)
      StremioAddonRepository.setAddonEnabled("localtest", false)
      val disabled = StremioAddonRepository.getInstalledAddons().first { it.id == "localtest" }
      assertFalse(disabled.isEnabled)
      assertTrue(StremioAddonRepository.getActiveAddons().none { it.id == "localtest" })

      StremioAddonRepository.setAddonEnabled("localtest", true)
      assertTrue(StremioAddonRepository.getActiveAddons().any { it.id == "localtest" })

      // Rimozione
      StremioAddonRepository.removeAddon("localtest")
      assertTrue(StremioAddonRepository.getInstalledAddons().none { it.id == "localtest" })
    } finally {
      StremioAddonRepository.removeAddon("localtest")
      server.stop(0)
      StremioAddonRepository.clearCaches()
    }
  }

  // ── 5. Ordinamento qualità + spareggio Debrid ───────────────────────────

  @Test
  fun streamManagerOrdina4K1080P720EPreferisceDebrid(): Unit = runBlocking {
    fun provider(name: String, quality: String, delayMs: Long) = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int, isTv: Boolean, season: Int?, episode: Int?, title: String?, year: Int?
      ): List<StreamSource> {
        delay(delayMs)
        return listOf(
          StreamSource(
            url = "https://cdn.example/$name.m3u8",
            quality = quality,
            serverName = name
          )
        )
      }
    }

    val manager = StreamManager(
      providers = listOf(
        provider("Free720", "720p", 30),
        provider("Free1080", "1080p", 60),
        provider("TorBox Instant 4K", "4K", 90),
        provider("TorBox Instant 1080p", "1080p", 120)
      ),
      timeoutMs = 5000L
    )

    val all = manager.resolveFlow(tmdbId = 603, isTv = false).last()

    assertEquals("4K in cima", "4K", all[0].quality)
    assertEquals("1080p debrid prima del 1080p free", "TorBox Instant 1080p", all[1].serverName)
    assertEquals("1080p free terzo", "Free1080", all[2].serverName)
    assertEquals("720p in coda", "Free720", all[3].serverName)
  }

  // ── Preferenze TorBox (Impostazioni) ──────────────────────────────────

  @Test
  fun preferenzeTorBoxSiSalvanoESiLeggono(): Unit = runBlocking {
    // 1) Inserimento e salvataggio della chiave API nelle Impostazioni.
    AppSettingsRepository.setTorBoxApiKey("TK_TEST_KEY_123456")
    assertEquals("TK_TEST_KEY_123456", AppSettingsRepository.torBoxApiKey.value)
    assertEquals("TK_TEST_KEY_123456", AppSettingsRepository.torBoxPreferences.apiKey)

    // Nessun read-back asincrono di DataStore deve azzerare la chiave appena salvata.
    delay(500)
    assertEquals("TK_TEST_KEY_123456", AppSettingsRepository.torBoxApiKey.value)

    // 2) Toggle "Usa TorBox Instant Debrid".
    AppSettingsRepository.setTorBoxInstantDebridEnabled(false)
    assertFalse(AppSettingsRepository.torBoxInstantDebridEnabled.value)
    assertFalse(AppSettingsRepository.torBoxPreferences.instantDebridEnabled)
    AppSettingsRepository.setTorBoxInstantDebridEnabled(true)
    assertTrue(AppSettingsRepository.torBoxInstantDebridEnabled.value)

    // Pulizia: nessuna chiave residua per gli altri test.
    AppSettingsRepository.setTorBoxApiKey(null)
    assertTrue(AppSettingsRepository.torBoxApiKey.value == null)
  }

  // ── 6. Batch multi-hash + selezione del flusso migliore ─────────────────

  /** Fake del gateway: registra i batch `checkcached` e gli sblocchi `requestdl`. */
  private class FakeTorBoxGateway(
    private val cached: Set<String>
  ) : TorBoxGateway {
    val checkedBatches = mutableListOf<List<String>>()
    val unlocked = mutableListOf<String>()

    override fun isConfigured(): Boolean = true
    override fun normalizeHash(infoHash: String): String = infoHash.trim().lowercase()

    override suspend fun checkCached(
      hashes: List<String>
    ): Map<String, TorBoxRepository.TorBoxCacheEntry> {
      checkedBatches += hashes
      return hashes.associateWith { hash ->
        TorBoxRepository.TorBoxCacheEntry(cached = cached.contains(hash), torrentId = 42L)
      }
    }

    override suspend fun unlockCached(
      infoHash: String,
      fileIdx: Int?,
      entry: TorBoxRepository.TorBoxCacheEntry
    ): String? {
      unlocked += normalizeHash(infoHash)
      return if (entry.cached) "https://cdn.example.org/best.mkv" else null
    }
  }

  private fun fifteenCandidates(): List<TorrentCandidate> = (1..15).map { i ->
    TorrentCandidate(
      infoHash = "hash" + i.toString().padStart(2, '0'),
      fileIdx = i,
      label = when (i) {
        7 -> "Torrentio 4K HDR"
        3 -> "Torrentio 1080p"
        12 -> "Torrentio 720p"
        else -> "Torrentio 480p"
      },
      sourceName = "Torrentio"
    )
  }

  @Test
  fun checkCachedInUnSoloBatchESelezionaIlFlussoMigliore(): Unit = runBlocking {
    val candidates = fifteenCandidates()
    val cached = setOf("hash07", "hash03", "hash12")
    val fake = FakeTorBoxGateway(cached)
    val provider = TorBoxStreamProvider(torBox = fake)

    val sources = provider.resolveItems(candidates)

    // 1) UN SOLO checkcached per tutti gli hash (non uno per hash)
    assertEquals("deve partire un solo batch", 1, fake.checkedBatches.size)
    assertEquals("tutti gli hash nel batch", 15, fake.checkedBatches[0].size)
    assertTrue(fake.checkedBatches[0].containsAll(candidates.map { it.infoHash }))

    // 2) requestdl solo sul candidato migliore in cache (4K > 1080p > 720p)
    assertEquals(listOf("hash07"), fake.unlocked)
    assertEquals(1, sources.size)
    assertEquals("4K", sources[0].quality)
    assertEquals("[TorBox Instant 4K]", sources[0].serverName)
    assertEquals("https://cdn.example.org/best.mkv", sources[0].url)
  }

  @Test
  fun nessunHashInCacheNessunoSblocco(): Unit = runBlocking {
    val fake = FakeTorBoxGateway(emptySet())
    val provider = TorBoxStreamProvider(torBox = fake)

    val sources = provider.resolveItems(fifteenCandidates())

    assertTrue(sources.isEmpty())
    assertEquals("un solo batch anche senza risultati", 1, fake.checkedBatches.size)
    assertTrue("nessun requestdl se niente è in cache", fake.unlocked.isEmpty())
  }

  @Test
  fun selectBestCachedOrdinaPerQualitaDecrescente() {
    val ordered = TorBoxStreamProvider.selectBestCached(
      fifteenCandidates(),
      setOf("hash07", "hash03", "hash12")
    )
    assertEquals(listOf("hash07", "hash03", "hash12"), ordered.map { it.infoHash })
    // Gli hash NON in cache non entrano mai nella selezione.
    assertTrue(ordered.none { it.infoHash == "hash05" })
  }

  private fun moshi(): Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
}
