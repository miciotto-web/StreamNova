package com.example.data.streaming.providers

import com.example.data.streaming.diagnostics.StreamAttempt
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catena provider -> mirror registrata senza rete: ogni mirror fallito produce
 * un tentativo con host (mai URL completi) e categoria corretta.
 */
class CatalogMirrorDiagnosticsTest {

  @Test
  fun ogniMirrorFallitoProduceUnTentativoConHostECategoria() = runBlocking {
    val first = "https://cb01-one.test"
    val second = "https://cb01-two.test"
    val article = "https://cb01.shared.test/titolo-2020/"
    val attempts = Collections.synchronizedList(mutableListOf<StreamAttempt>())
    val diagnostics = StreamDiagnosticSink { attempt -> attempts += attempt }

    val provider = Cb01Provider(listOf(first, second), diagnostics) { url, _ ->
      when {
        url.startsWith("$first/?s=") -> """<a href="$article">Titolo</a>"""
        // Il primo mirror trova l'articolo ma non ha link hoster.
        url == article -> "<html><body>nessun player</body></html>"
        // Il secondo mirror non trova alcun articolo.
        else -> "<html><body>nessun risultato</body></html>"
      }
    }

    runCatching {
      provider.getStreams(1, false, null, null, "Titolo", 2020)
    }

    val mirrors = attempts.filter { it.providerId == Cb01Provider.PROVIDER_ID }
    assertEquals(2, mirrors.size)
    assertEquals(setOf("cb01-one.test", "cb01-two.test"), mirrors.mapNotNull { it.mirrorId }.toSet())
    assertTrue(
      "un mirror senza link hoster non e' un errore di rete",
      mirrors.all { it.outcome == StreamAttemptOutcome.EXTRACTION_FAILED }
    )
    assertTrue(
      "nessun URL completo deve comparire nella diagnostica",
      mirrors.all { it.mirrorId?.contains("/") != true }
    )
  }

  /**
   * La diagnostica per-mirror di 2Embed registra sempre l'esito del tentativo
   * sul mirror (host soltanto) anche quando i messaggi interni contengono URL
   * pieni e parametri sensibili: la sanitizzazione dell'estensione `record`
   * riduce ogni URL all'origin e redige i token.
   */
  @Test
  fun dueEmbedRegistraIlTentativoPerMirrorConMessaggiSanificati() = runBlocking {
    val first = "https://twoembed-one.test"
    val second = "https://twoembed-two.test"
    val attempts = Collections.synchronizedList(mutableListOf<StreamAttempt>())
    val diagnostics = StreamDiagnosticSink { attempt -> attempts += attempt }

    val provider = TwoEmbedProvider(
      listOf(first, second),
      TwoEmbedHttp { url, _, _ ->
        when {
          // Il primo mirror trova una pagina embed valida ma il suo player
          // risponde 403 con un token sensibile: il messaggio d'eccezione che
          // arriva al record contiene l'URL completo e il token.
          url == "$first/embed/movie/9" ->
            url to """<iframe src="https://blocked.example/embed/movie/9"></iframe>"""
          url.startsWith("https://blocked.example/") ->
            throw IOException("HTTP 403 su $url?token=SEGRETO")
          // Il secondo mirror completa l'intera catena VidSrc.
          url == "$second/embed/movie/9" ->
            url to """<iframe src="https://vidsrc.example/embed/movie/9"></iframe>"""
          url.startsWith("https://vidsrc.example/embed/movie/9") ->
            url to """<script>var Q = {"type":"movie","id":"tt1234567","t":"tok","s":0,"e":0};</script>"""
          url.contains("/pl/api.php?a=sources") ->
            url to """{"servers":[{"name":"Alpha","ref":"ref-a"}]}"""
          url.contains("/pl/api.php?a=play") ->
            url to """{"url":"/stream/master.m3u8","type":"hls"}"""
          url == "https://vidsrc.example/stream/master.m3u8" ->
            url to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1920x1080\nhd.m3u8"
          else -> throw IllegalStateException("richiesta inattesa: $url")
        }
      },
      diagnostics
    )

    val sources = provider.getStreams(9, false, null, null, "DueEmbed", null)

    assertEquals(1, sources.size)

    val records = attempts.filter { it.providerId == TwoEmbedProvider.PROVIDER_ID }
    assertEquals(2, records.size)
    assertEquals(
      setOf("twoembed-one.test", "twoembed-two.test"),
      records.mapNotNull { it.mirrorId }.toSet()
    )
    assertEquals(
      "un 403 del player e' un errore HTTP, non di rete",
      StreamAttemptOutcome.HTTP_ERROR,
      records.first { it.mirrorId == "twoembed-one.test" }.outcome
    )
    assertEquals(
      StreamAttemptOutcome.SUCCESS,
      records.first { it.mirrorId == "twoembed-two.test" }.outcome
    )
    // Solo host nei mirrorId, nessun percorso o credenziale nei messaggi.
    assertTrue(
      "il mirrorId deve essere un host, non un URL",
      records.all { it.mirrorId?.contains("/") != true }
    )
    val fullUrlRegex = Regex("""https?://[^/\s"']+/\S""")
    assertTrue(
      "nessun URL completo deve sopravvivere alla sanitizzazione: ${records.map { it.message }}",
      records.none { fullUrlRegex.containsMatchIn(it.message.orEmpty()) }
    )
    assertFalse(
      "nessun token sensibile deve finire nella diagnostica",
      records.any { it.message.orEmpty().contains("SEGRETO") }
    )
  }
}
