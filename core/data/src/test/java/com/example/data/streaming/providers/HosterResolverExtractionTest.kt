package com.example.data.streaming.providers

import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamAttempt
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.extractors.VideoExtractor
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catena di estrazione offline: un hoster in errore non deve impedire il
 * tentativo successivo, e ogni tentativo deve produrre una categoria
 * diagnostica sanificata.
 */
class HosterResolverExtractionTest {

  private fun sink(): Pair<StreamDiagnosticSink, MutableList<StreamAttempt>> {
    val attempts = Collections.synchronizedList(mutableListOf<StreamAttempt>())
    return StreamDiagnosticSink { attempt -> attempts += attempt } to attempts
  }

  private fun extractor(block: suspend () -> List<StreamSource>): VideoExtractor =
    object : VideoExtractor {
      override suspend fun extract(embedUrl: String): List<StreamSource> = block()
    }

  @Test
  fun erroreDiUnExtractorNonImpedisceQuelloAlternativo() = runBlocking {
    val (diagnostics, attempts) = sink()
    val broken = extractor {
      throw IOException(
        "HTTP 403 su https://maxstream.video/e/uno?token=TOPSECRET&sig=FIRMA"
      )
    }
    val working = extractor {
      listOf(StreamSource(streamUrl = "https://cdn.example/ok.m3u8", serverName = "Mixdrop"))
    }

    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf(
        "Maxstream" to "https://maxstream.video/e/uno",
        "Mixdrop" to "https://mixdrop.co/e/due"
      ),
      referer = "https://catalog.example/articolo",
      providerId = "TestProvider",
      mirrorId = "catalog.example",
      diagnostics = diagnostics,
      extractorSelector = { url ->
        if (url.contains("maxstream")) broken else working
      }
    )

    assertEquals(1, sources.size)
    assertEquals("https://cdn.example/ok.m3u8", sources.first().streamUrl)

    // Gli hoster sono risolti in parallelo: l'ordine di completamento non è
    // garantito, quindi si cerca il tentativo per esito e non per posizione.
    val failed = attempts.first { it.outcome == StreamAttemptOutcome.HTTP_ERROR }
    assertEquals("TestProvider", failed.providerId)
    assertEquals("catalog.example", failed.mirrorId)
    assertEquals(StreamAttemptOutcome.HTTP_ERROR, failed.outcome)
    assertFalse("il token non deve finire nella diagnostica", failed.message.orEmpty().contains("TOPSECRET"))
    assertFalse("la firma non deve finire nella diagnostica", failed.message.orEmpty().contains("FIRMA"))
    assertTrue(
      "il tentativo successivo deve essere registrato come SUCCESS",
      attempts.any { it.outcome == StreamAttemptOutcome.SUCCESS }
    )
  }

  @Test
  fun challengeClassificataSenzaBypass() = runBlocking {
    val (diagnostics, attempts) = sink()
    val calls = AtomicInteger(0)
    val challenged = extractor {
      calls.incrementAndGet()
      throw IOException("Cloudflare challenge: captcha richiesto")
    }

    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf("Maxstream" to "https://maxstream.video/e/challenge"),
      referer = "https://catalog.example/articolo",
      providerId = "TestProvider",
      mirrorId = "catalog.example",
      diagnostics = diagnostics,
      extractorSelector = { challenged }
    )

    assertTrue(sources.isEmpty())
    assertEquals("nessun tentativo di bypass: una sola richiesta", 1, calls.get())
    assertEquals(StreamAttemptOutcome.BLOCKED_BY_CHALLENGE, attempts.single().outcome)
  }

  @Test
  fun shortenerConCaptchaUprotClassificatoSenzaRete() = runBlocking {
    val (diagnostics, attempts) = sink()
    val neverCalled = extractor {
      throw AssertionError("nessun estrattore deve essere invocato su un captcha")
    }

    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf("Maxstream" to "https://uprot.net/msf/abc123"),
      referer = "https://catalog.example/articolo",
      providerId = "TestProvider",
      diagnostics = diagnostics,
      extractorSelector = { neverCalled }
    )

    assertTrue(sources.isEmpty())
    assertEquals(StreamAttemptOutcome.BLOCKED_BY_CHALLENGE, attempts.single().outcome)
  }

  @Test
  fun urlDuplicatoNonVieneEstrattoDueVolte() = runBlocking {
    val (diagnostics, attempts) = sink()
    val calls = AtomicInteger(0)
    val counting = extractor {
      calls.incrementAndGet()
      listOf(StreamSource(streamUrl = "https://cdn.example/uno.m3u8", serverName = "Uno"))
    }

    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf(
        "Maxstream" to "https://maxstream.video/e/uno",
        "Maxstream" to "https://maxstream.video/e/uno"
      ),
      referer = "https://catalog.example/articolo",
      providerId = "TestProvider",
      diagnostics = diagnostics,
      extractorSelector = { counting }
    )

    assertEquals(1, sources.size)
    assertEquals("lo stesso link non va estratto due volte", 1, calls.get())
    assertEquals(1, attempts.size)
  }

  @Test
  fun extractorSenzaSorgentiProduceNoResults() = runBlocking {
    val (diagnostics, attempts) = sink()

    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf("Maxstream" to "https://maxstream.video/e/vuoto"),
      referer = "https://catalog.example/articolo",
      providerId = "TestProvider",
      diagnostics = diagnostics,
      extractorSelector = { extractor { emptyList() } }
    )

    assertTrue(sources.isEmpty())
    assertEquals(StreamAttemptOutcome.NO_RESULTS, attempts.single().outcome)
  }

  @Test
  fun sorgenteSenzaUrlRiproducibileVieneSegnalataComeInvalidStreamUrl() = runBlocking {
    val (diagnostics, attempts) = sink()

    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf("Maxstream" to "https://maxstream.video/e/senza-url"),
      referer = "https://catalog.example/articolo",
      providerId = "TestProvider",
      diagnostics = diagnostics,
      extractorSelector = {
        extractor { listOf(StreamSource(streamUrl = null, serverName = "Senza URL")) }
      }
    )

    assertEquals(1, sources.size)
    assertEquals(StreamAttemptOutcome.INVALID_STREAM_URL, attempts.single().outcome)
  }
}
