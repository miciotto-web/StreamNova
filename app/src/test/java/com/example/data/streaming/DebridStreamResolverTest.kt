package com.example.data.streaming

import com.example.data.debrid.DebridCacheEntry
import com.example.data.debrid.DebridCacheGateway
import com.example.data.debrid.DebridStreamResolver
import com.example.data.stremio.StremioStreamCandidate
import com.example.data.stremio.StremioStreamItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Risoluzione condivisa dei candidati torrent per i servizi Debrid accostati a
 * TorBox (Real-Debrid, Premiumize) via [DebridStreamResolver]:
 *  - UN solo check multi-hash del gateway per tutti gli hash;
 *  - sorgenti cached con `streamUrl = null` (sblocco on-demand al click);
 *  - gli hash non in cache NON generano sorgenti;
 *  - servizio non configurato → nessuna sorgente e nessuna interrogazione.
 */
class DebridStreamResolverTest {

  private companion object {
    const val CACHED_HASH = "0123456789abcdef0123456789abcdef01234567"
    const val OTHER_HASH = "fedcba9876543210fedcba9876543210fedcba98"
  }

  /** Gateway finto: mappa hash → entry, con conteggio delle interrogazioni. */
  private class FakeGateway(
    private val entries: Map<String, DebridCacheEntry>,
    private val configured: Boolean = true
  ) : DebridCacheGateway {
    var checkCalls = 0

    override fun isConfigured(): Boolean = configured

    override suspend fun checkCached(hashes: List<String>): Map<String, DebridCacheEntry> {
      checkCalls++
      return entries.filterKeys { hash -> hashes.contains(hash) }
    }
  }

  private fun candidate(
    addonName: String,
    hash: String,
    fileIdx: Int? = null,
    streamName: String = "$addonName\n1080p"
  ) = StremioStreamCandidate(
    addonName = addonName,
    baseUrl = "https://addon.example.org/$addonName/|config",
    item = StremioStreamItem(
      name = streamName,
      title = "Show.S01E02.1080p.mkv\n2.18 GB | 👤 474",
      infoHash = hash,
      fileIdx = fileIdx
    )
  )

  @Test
  fun `un solo check per tutti gli hash e sorgenti cached senza url`() = runTest {
    val gateway = FakeGateway(
      entries = mapOf(CACHED_HASH to DebridCacheEntry(cached = true))
    )
    val candidates = listOf(
      candidate("Torrentio", CACHED_HASH),
      candidate("Torrentio IT", CACHED_HASH, fileIdx = 2),
      candidate("AltraAddon", OTHER_HASH)
    )

    val sources = DebridStreamResolver.resolveCachedSources(gateway, candidates, "TestDebrid")

    assertEquals("UN solo check multi-hash", 1, gateway.checkCalls)
    assertEquals("Solo l'hash cached genera sorgenti (2 installazioni dell'addon)", 2, sources.size)
    assertTrue(sources.all { it.infoHash == CACHED_HASH })
    assertTrue("streamUrl resta NULL fino al click dell'utente", sources.all { it.streamUrl == null })
    assertTrue(sources.all { it.isCached })
    assertEquals("Il badge sorgente usa l'addon di origine", "Torrentio", sources[0].addonName)
  }

  @Test
  fun `sorgenti ordinate prima italiane poi per qualita' decrescente`() = runTest {
    val gateway = FakeGateway(
      entries = mapOf(
        CACHED_HASH to DebridCacheEntry(cached = true),
        OTHER_HASH to DebridCacheEntry(cached = true)
      )
    )
    val candidates = listOf(
      candidate("Torrentio", CACHED_HASH, streamName = "Torrentio\n1080p"),
      candidate("Torrentio ITA", OTHER_HASH, streamName = "Torrentio ITA\n4K [ITA/MULTI]")
    )

    val sources = DebridStreamResolver.resolveCachedSources(gateway, candidates, "TestDebrid")

    assertEquals(2, sources.size)
    assertEquals("La sorgente italiana precede", "Torrentio ITA", sources[0].addonName)
    assertTrue(sources[0].isItalian)
  }

  @Test
  fun `servizio non configurato non interroga il gateway`() = runTest {
    val gateway = FakeGateway(entries = emptyMap(), configured = false)

    val sources = DebridStreamResolver.resolveCachedSources(
      gateway,
      listOf(candidate("Torrentio", CACHED_HASH)),
      "TestDebrid"
    )

    assertTrue(sources.isEmpty())
    assertEquals(0, gateway.checkCalls)
  }

  @Test
  fun `nessun candidato torrent produce nessuna sorgente`() = runTest {
    val gateway = FakeGateway(entries = mapOf(CACHED_HASH to DebridCacheEntry(cached = true)))

    val sources = DebridStreamResolver.resolveCachedSources(
      gateway,
      listOf(
        StremioStreamCandidate(
          addonName = "DirectAddon",
          baseUrl = "https://addon.example.org/direct",
          // Stream diretto senza infoHash: non è di competenza dei provider Debrid.
          item = StremioStreamItem(name = "DirectAddon", url = "https://video.example.org/file.mp4")
        )
      ),
      "TestDebrid"
    )

    assertTrue(sources.isEmpty())
    assertEquals(0, gateway.checkCalls)
    assertNull(sources.firstOrNull())
  }
}
