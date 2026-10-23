package com.example.data.streaming.providers

import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamAttempt
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.extractors.VideoExtractor
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shortener intermedi (es. `clicka.cc`): il link etichettato "MaxStream" non è
 * un hoster finale ma un redirect. Senza captcha bypass si segue UN salto e si
 * delega l'URL finale all'estrattore; se il salto porta a una challenge il link
 * viene registrato come non disponibile.
 */
class HosterResolverUnshortenerTest {

  private fun sink(): Pair<StreamDiagnosticSink, MutableList<StreamAttempt>> {
    val attempts = Collections.synchronizedList(mutableListOf<StreamAttempt>())
    return StreamDiagnosticSink { attempt -> attempts += attempt } to attempts
  }

  private fun extractor(block: suspend (String) -> List<StreamSource>): VideoExtractor =
    object : VideoExtractor {
      override suspend fun extract(embedUrl: String): List<StreamSource> = block(embedUrl)
    }

  @Test
  fun redirectHttpVieneSeguitoFinoAllHosterSupportato() = runBlocking {
    val resolved = HosterResolver.followUnshortener(
      url = "https://clicka.cc/abc123",
      referer = "https://eurostream.example/serie/",
      label = "MaxStream",
      fetcher = { _, _ -> "https://maxstream.video/e/REAL" to "<html></html>" }
    )

    assertEquals("https://maxstream.video/e/REAL", resolved)
  }

  @Test
  fun redirectEspressoNelMarkupVieneRiconosciuto() {
    val meta = """<meta http-equiv="refresh" content="0; url=https://mixdrop.co/e/ABC">"""
    val js = """<script>window.location.replace("https://maxstream.video/e/JS")</script>"""
    val protocolRelative = """<script>location.href = "//maxstream.video/e/REL"</script>"""

    assertEquals("https://mixdrop.co/e/ABC", HosterResolver.findRedirectTarget(meta, "https://clicka.cc/x"))
    assertEquals("https://maxstream.video/e/JS", HosterResolver.findRedirectTarget(js, "https://clicka.cc/x"))
    assertEquals("https://maxstream.video/e/REL", HosterResolver.findRedirectTarget(protocolRelative, "https://clicka.cc/x"))
  }

  @Test
  fun unLogoCdnNonVieneScambiatoPerUnaSorgente() {
    val html = """<img src="https://maxstream.video/images/logo.png">"""
    assertNull(HosterResolver.findRedirectTarget(html, "https://clicka.cc/x"))
  }

  @Test
  fun linkNonEtichettatoComeHosterNonVieneSeguito() = runBlocking {
    var called = false
    val resolved = HosterResolver.followUnshortener(
      url = "https://clicka.cc/banner",
      referer = "https://eurostream.example/",
      label = "Pubblicità",
      fetcher = { _, _ ->
        called = true
        "https://maxstream.video/e/AD" to ""
      }
    )

    assertNull(resolved)
    assertTrue("nessuna richiesta per un banner", !called)
  }

  @Test
  fun dominioNonShortenerNonVieneSeguito() = runBlocking {
    var called = false
    val resolved = HosterResolver.followUnshortener(
      url = "https://sconosciuto.example/e/ABC",
      referer = "https://eurostream.example/",
      label = "MaxStream",
      fetcher = { _, _ ->
        called = true
        "https://maxstream.video/e/REAL" to ""
      }
    )

    assertNull(resolved)
    assertTrue("nessuna richiesta verso domini arbitrari", !called)
  }

  @Test
  fun catenaCompletaClickaVersoMaxstreamEstratta() = runBlocking {
    val (diagnostics, attempts) = sink()
    val working = extractor { url ->
      assertEquals("https://maxstream.video/e/REAL", url)
      listOf(StreamSource(streamUrl = "https://cdn.example.test/tv/master.m3u8", serverName = "MaxStream"))
    }

    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf("MaxStream" to "https://clicka.cc/abc123"),
      referer = "https://eurostream.example/serie/",
      providerId = "Eurostreaming",
      mirrorId = "eurostream.example",
      diagnostics = diagnostics,
      extractorSelector = { url -> if (url.contains("maxstream")) working else null },
      redirectFetcher = { _, _ -> "https://maxstream.video/e/REAL" to "<html></html>" }
    )

    assertEquals("https://cdn.example.test/tv/master.m3u8", sources.single().streamUrl)
    assertEquals(StreamAttemptOutcome.SUCCESS, attempts.last().outcome)
  }

  @Test
  fun redirectVersoCaptchaRegistratoComeChallenge() = runBlocking {
    val (diagnostics, attempts) = sink()
    var extractorCalls = 0
    val neverCalled = extractor {
      extractorCalls++
      emptyList()
    }

    val sources = HosterResolver.extractFromLinks(
      rawUrls = listOf("MaxStream" to "https://clicka.cc/captcha"),
      referer = "https://eurostream.example/serie/",
      providerId = "Eurostreaming",
      mirrorId = "eurostream.example",
      diagnostics = diagnostics,
      extractorSelector = { url -> if (url.startsWith("https://clicka.cc")) null else neverCalled },
      redirectFetcher = { _, _ -> "https://uprot.net/msf/CODE" to "<html></html>" }
    )

    assertTrue(sources.isEmpty())
    assertEquals("nessun tentativo di bypass", 0, extractorCalls)
    assertEquals(StreamAttemptOutcome.BLOCKED_BY_CHALLENGE, attempts.single().outcome)
  }
}
