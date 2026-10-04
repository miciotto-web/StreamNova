package com.example.data.streaming

import com.example.data.streaming.providers.MultiEmbedProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamPriorityTest {

  @Test
  fun testQualityScoreCalculation() {
    assertTrue(StreamManager.isFullHdOrHigher("1080p"))
    assertTrue(StreamManager.isFullHdOrHigher("4K"))
    assertTrue(StreamManager.isFullHdOrHigher("FHD"))
    assertTrue(!StreamManager.isFullHdOrHigher("720p"))
    assertTrue(!StreamManager.isFullHdOrHigher("480p"))
    assertTrue(!StreamManager.isFullHdOrHigher("Auto"))

    assertTrue(StreamManager.qualityScore("1080p") > StreamManager.qualityScore("720p"))
    assertTrue(StreamManager.qualityScore("720p") > StreamManager.qualityScore("480p"))
  }

  @Test
  fun testMultiEmbedProviderDoesNotReturnMockUrl(): Unit = runBlocking {
    val provider = MultiEmbedProvider()
    val streams = try {
      provider.getStreams(
        tmdbId = 1418, // The Big Bang Theory
        isTv = true,
        season = 1,
        episode = 1,
        title = "The Big Bang Theory",
        year = 2007
      )
    } catch (e: Exception) {
      emptyList()
    }
    // Non deve MAI restituire l'url mock test-streams.mux.dev
    streams.forEach { source ->
      assertTrue("Rilevato URL mock vietato: ${source.url}", !source.url.contains("test-streams.mux.dev"))
      assertTrue("URL non valido: ${source.url}", source.url.startsWith("http"))
      assertTrue("Header Referer assente", source.headers.containsKey("Referer"))
      assertTrue("Header User-Agent assente", source.headers.containsKey("User-Agent"))
    }
  }

  @Test
  fun testVixSrcResolvesTbbtS1E1(): Unit = runBlocking {
    val provider = VixSrcProvider()
    val sources = provider.getStreams(
      tmdbId = 1418, // The Big Bang Theory
      isTv = true,
      season = 1,
      episode = 1,
      title = "The Big Bang Theory",
      year = 2007
    )
    assertTrue("VixSrc deve trovare almeno una sorgente per TBBT S1E1", sources.isNotEmpty())
    val first = sources.first()
    assertTrue("URL deve iniziare per http", first.url.startsWith("http"))
    assertTrue("Header Referer presente", first.headers.containsKey("Referer"))
  }

  @Test
  fun testStreamManagerPrioritizes1080pOver720p(): Unit = runBlocking {
    val mock720pProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int, isTv: Boolean, season: Int?, episode: Int?, title: String?, year: Int?
      ): List<StreamSource> {
        delay(50) // risponde veloce (50ms)
        return listOf(
          StreamSource(
            url = "https://example.com/stream_720p.m3u8",
            quality = "720p",
            serverName = "MockServer 720p"
          )
        )
      }
    }

    val mock1080pProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int, isTv: Boolean, season: Int?, episode: Int?, title: String?, year: Int?
      ): List<StreamSource> {
        delay(200) // risponde dopo 200ms
        return listOf(
          StreamSource(
            url = "https://example.com/stream_1080p.m3u8",
            quality = "1080p",
            serverName = "MockServer 1080p"
          )
        )
      }
    }

    val streamManager = StreamManager(
      providers = listOf(mock720pProvider, mock1080pProvider),
      timeoutMs = 5000L
    )

    // La prima emissione deve attendere la priorità qualitativa e contenere il 1080p in cima
    val result = streamManager.resolveFlow(1418, true, 1, 1).first { list ->
      list.any { StreamManager.isFullHdOrHigher(it.quality) }
    }

    assertNotNull("Risultato nullo", result)
    assertTrue("Lista sorgenti vuota", result.isNotEmpty())
    assertEquals("Il primo flusso deve essere 1080p", "1080p", result.first().quality)
    assertEquals("MockServer 1080p", result.first().serverName)
  }

  @Test
  fun testStreamManagerFallsBackToWorkingProviderWhenOneReturnsEmptyOrFails(): Unit = runBlocking {
    val failingProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int, isTv: Boolean, season: Int?, episode: Int?, title: String?, year: Int?
      ): List<StreamSource> {
        delay(30)
        return emptyList() // Fallimento pulito / nessuna sorgente
      }
    }

    val workingVixProvider = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int, isTv: Boolean, season: Int?, episode: Int?, title: String?, year: Int?
      ): List<StreamSource> {
        delay(80)
        return listOf(
          StreamSource(
            url = "https://vixcloud.co/playlist/real_vix.m3u8",
            quality = "720p",
            serverName = "VixCloud"
          )
        )
      }
    }

    val streamManager = StreamManager(
      providers = listOf(failingProvider, workingVixProvider),
      timeoutMs = 5000L
    )

    val streams = streamManager.resolve(1418, true, 1, 1)
    assertTrue("Le sorgenti non devono essere vuote", streams.isNotEmpty())
    assertEquals("Deve agganciare il flusso reale di VixCloud", "VixCloud", streams.first().serverName)
    assertEquals("https://vixcloud.co/playlist/real_vix.m3u8", streams.first().url)
  }
}

