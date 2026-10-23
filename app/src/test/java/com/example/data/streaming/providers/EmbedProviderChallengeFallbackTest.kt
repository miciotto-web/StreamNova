package com.example.data.streaming.providers

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I provider embed provano più endpoint documentati: una pagina con il gate
 * Cloudflare Turnstile non deve impedire di raggiungere l'endpoint successivo
 * (che può esporre la stessa API senza gate). Nessun bypass: se tutti gli
 * endpoint sono protetti il provider resta "non disponibile".
 */
class EmbedProviderChallengeFallbackTest {

  private val challengePage = """
    <html><body>
      <div class="captcha-gate"></div>
      <script src="https://challenges.cloudflare.com/turnstile/v0/api.js"></script>
    </body></html>
  """.trimIndent()

  private val playerWithMp4 = """
    <html><body>
      <video id="player" src="https://cdn.example.test/episodio.mp4"></video>
    </body></html>
  """.trimIndent()

  @Test
  fun multiEmbedProvaIlSecondoEndpointDopoIlGate() = runBlocking {
    val requested = mutableListOf<String>()
    val provider = MultiEmbedProvider(
      diagnostics = com.example.data.streaming.diagnostics.StreamDiagnosticSink.NONE,
      http = { url, _ ->
        requested += url
        when {
          url.startsWith("https://multiembed.mov/?video_id=42") -> challengePage
          url.startsWith("https://multiembed.mov/se_player.php?video_id=42") -> playerWithMp4
          url.startsWith("https://superembed.stream") -> challengePage
          else -> throw IOException("endpoint non atteso: $url")
        }
      }
    )

    val source = provider.getStreams(42, isTv = true, season = 3, episode = 5, title = "Serie", year = 2026).single()

    assertEquals("https://cdn.example.test/episodio.mp4", source.streamUrl)
    // MP4 senza probe di risoluzione: la qualità non è inventata ("Auto").
    assertEquals("Auto", source.quality)
    assertTrue(requested.any { it.contains("se_player.php") && it.contains("s=3") && it.contains("e=5") })
  }

  @Test
  fun multiEmbedTuttiIGateRestanoNonDisponibile() = runBlocking {
    val provider = MultiEmbedProvider(
      diagnostics = com.example.data.streaming.diagnostics.StreamDiagnosticSink.NONE,
      http = { _, _ -> challengePage }
    )

    val error = runCatching { provider.getStreams(42, true, 1, 1, "Serie", null) }.exceptionOrNull()

    assertTrue("attesa IOException, ottenuto $error", error is IOException)
    assertTrue(error?.message.orEmpty().contains("captcha", ignoreCase = true))
  }

  @Test
  fun superEmbedProvaIlSecondoEndpointDopoIlGate() = runBlocking {
    val requested = mutableListOf<String>()
    val provider = SuperEmbedProvider(
      diagnostics = com.example.data.streaming.diagnostics.StreamDiagnosticSink.NONE,
      http = { url, _ ->
        requested += url
        when {
          url == "https://superembed.stream/se_player.php?video_id=42&s=3&e=5" -> challengePage
          url == "https://superembed.stream/?video_id=42&s=3&e=5" -> playerWithMp4
          url.startsWith("https://multiembed.mov") -> challengePage
          else -> throw IOException("endpoint non atteso: $url")
        }
      }
    )

    val source = provider.getStreams(42, isTv = true, season = 3, episode = 5, title = "Serie", year = 2026).single()

    assertEquals("https://cdn.example.test/episodio.mp4", source.streamUrl)
    assertEquals("SuperEmbed", source.serverName)
    // MP4 senza probe di risoluzione: la qualità non è inventata ("Auto"),
    // sia per il markup diretto sia per l'eventuale <video>/<source>.
    assertEquals("Auto", source.quality)
    assertTrue(requested.any { it == "https://superembed.stream/?video_id=42&s=3&e=5" })
  }

  @Test
  fun superEmbedTuttiIGateRestanoNonDisponibile() = runBlocking {
    val provider = SuperEmbedProvider(
      diagnostics = com.example.data.streaming.diagnostics.StreamDiagnosticSink.NONE,
      http = { _, _ -> challengePage }
    )

    val error = runCatching { provider.getStreams(42, true, 1, 1, "Serie", null) }.exceptionOrNull()

    assertTrue("attesa IOException, ottenuta $error", error is IOException)
    assertTrue(error?.message.orEmpty().contains("captcha", ignoreCase = true))
  }

  @Test
  fun ilGateNonScartaUnaSorgentePresenteNellaStessaPagina() = runBlocking {
    val page = challengePage.replace("</body>", """<video src="https://cdn.example.test/ok.m3u8"></video></body>""")
    val provider = MultiEmbedProvider(
      diagnostics = com.example.data.streaming.diagnostics.StreamDiagnosticSink.NONE,
      http = { url, _ ->
        if (url.startsWith("https://multiembed.mov/")) page else throw IOException("non atteso")
      }
    )

    val sources = provider.getStreams(7, false, null, null, "Film", null)
    assertEquals("https://cdn.example.test/ok.m3u8", sources.single().streamUrl)
  }
}
