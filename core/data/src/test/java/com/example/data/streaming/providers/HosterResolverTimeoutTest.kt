package com.example.data.streaming.providers

import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.VideoExtractor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Budget per singolo hoster nella catena CB01/Eurostreaming: un estrattore che
 * non risponde non deve bloccare i link successivi, mentre la cancellazione del
 * chiamante deve continuare a propagarsi.
 */
class HosterResolverTimeoutTest {

  @Test
  fun estrattoreLentoNonBloccaIlLinkSuccessivo() = runBlocking {
    val invoked = mutableListOf<String>()
    val slow = object : VideoExtractor {
      override suspend fun extract(embedUrl: String): List<StreamSource> {
        invoked += "slow"
        delay(5_000)
        return listOf(StreamSource(streamUrl = "https://cdn.slow/x.m3u8", serverName = "Slow"))
      }
    }
    val fast = object : VideoExtractor {
      override suspend fun extract(embedUrl: String): List<StreamSource> {
        invoked += "fast"
        return listOf(StreamSource(streamUrl = "https://cdn.fast/x.m3u8", serverName = "Fast"))
      }
    }

    val startedAt = System.currentTimeMillis()
    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf(
        "Slow" to "https://slow.hoster/e/a",
        "Fast" to "https://fast.hoster/e/b"
      ),
      referer = "https://catalog.test/articolo/",
      perHosterTimeoutMs = 120,
      extractorSelector = { url -> if (url.contains("slow.hoster")) slow else fast }
    )
    val elapsed = System.currentTimeMillis() - startedAt

    assertEquals(listOf("https://cdn.fast/x.m3u8"), sources.map { it.streamUrl })
    assertEquals(setOf("slow", "fast"), invoked.toSet())
    assertTrue("il timeout per hoster non è scattato (${elapsed}ms)", elapsed < 2_000)
  }

  @Test
  fun dueHosterLentiNonSommanoILoroTimeout() = runBlocking {
    // Regressione: la risoluzione sequenziale sommava i timeout (fino a 10 s
    // per link) e faceva scadere il budget del mirror prima di provare gli
    // altri hoster. In parallelo il tempo totale è quello del singolo timeout.
    val slow = object : VideoExtractor {
      override suspend fun extract(embedUrl: String): List<StreamSource> {
        delay(1_000)
        return emptyList()
      }
    }

    val startedAt = System.currentTimeMillis()
    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf(
        "Maxstream" to "https://slow-one.hoster/e/a",
        "Mixdrop" to "https://slow-two.hoster/e/b"
      ),
      referer = "https://catalog.test/articolo/",
      perHosterTimeoutMs = 1_000,
      extractorSelector = { slow }
    )
    val elapsed = System.currentTimeMillis() - startedAt

    assertTrue(sources.isEmpty())
    assertTrue(
      "i timeout non devono essere sommati in sequenza (${elapsed}ms)",
      elapsed < 1_800
    )
  }

  @Test
  fun laCancellazioneDelChiamanteNonDiventaUnTimeoutPerHoster() = runBlocking {
    val slow = object : VideoExtractor {
      override suspend fun extract(embedUrl: String): List<StreamSource> {
        delay(5_000)
        return emptyList()
      }
    }

    val thrown = runCatching {
      withTimeout(80) {
        HosterResolver.extractFromLinks(
          rawUrls = listOf("Slow" to "https://slow.hoster/e/a"),
          referer = "https://catalog.test/articolo/",
          perHosterTimeoutMs = 10_000,
          extractorSelector = { slow }
        )
      }
    }.exceptionOrNull()

    assertTrue("atteso TimeoutCancellationException, ottenuto $thrown", thrown is TimeoutCancellationException)
  }
}
