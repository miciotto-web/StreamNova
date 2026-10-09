package com.example.data.streaming

import com.example.data.streaming.extractors.ExtractorHttp
import com.example.data.streaming.providers.StreamingCommunityProvider
import com.example.data.streaming.providers.SuperEmbedProvider
import com.example.data.streaming.providers.TwoEmbedProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Test dei provider aggiuntivi su servizi **reali** (richiedono rete).
 */
class NewProvidersTest {

  /** 2Embed: catena completa 2embed.cc -> VidSrc -> master HLS. */
  @Ignore("Flaky: dipende da resolver e domini esterni soggetti a cambi DNS/takedown")
  @Test
  fun twoEmbedRisolveFilmRealeConHls(): Unit = runBlocking {
    // I mirror possono andare in timeout transient: si riprova una volta
    var sources: List<StreamSource>? = null
    repeat(2) { attempt ->
      if (sources == null) {
        sources = runCatching {
          TwoEmbedProvider().getStreams(
            tmdbId = 27205,          // Inception
            isTv = false,
            title = "Inception",
            year = 2010
          )
        }.getOrNull()
        if (sources == null && attempt == 0) {
          println("2Embed: primo tentativo fallito, riprovo...")
          delay(1_500)
        } else if (sources != null && attempt == 1) {
          println("2Embed: tentativo 2 riuscito")
        }
      }
    }
    assertTrue("nessuna sorgente 2Embed", !sources.isNullOrEmpty())

    val best = sources!!.first()
    assertTrue("url non HLS: ${best.url}",
      best.url.contains(".m3u8", true) || best.url.contains("_stream", true) ||
        best.url.contains("stream", true))
    assertTrue("Referer mancante: ${best.headers}", best.headers.containsKey("Referer"))
    assertTrue("User-Agent mancante: ${best.headers}", best.headers.containsKey("User-Agent"))

    // La master playlist deve rispondere effettivamente come HLS
    if (best.url.contains("stream", true) || best.url.contains(".m3u8", true)) {
      val playlist = ExtractorHttp.get(best.url, referer = best.headers["Referer"])
      assertTrue("risposta non HLS: ${playlist.take(40)}", playlist.startsWith("#EXTM3U"))
    }
    println("2Embed OK -> ${best.serverName} ${best.quality} ${best.url}")
  }

  /** SuperEmbed/MultiEmbed: riconoscimento del player e del gate Turnstile. */
  @Test
  fun superEmbedGestiscePlayerEGate(): Unit = runBlocking {
    val result = runCatching {
      SuperEmbedProvider().getStreams(tmdbId = 27205, isTv = false, title = "Inception", year = 2010)
    }
    if (result.isSuccess) {
      val sources = result.getOrNull()!!.filter { it.url.startsWith("http") }
      assertTrue("sorgenti SuperEmbed non valide", sources.isNotEmpty())
      println("SuperEmbed OK -> " + sources.joinToString { "${it.serverName} ${it.quality} ${it.url}" })
    } else {
      val message = result.exceptionOrNull()?.message.orEmpty()
      println("SuperEmbed gate: $message")
      assertTrue(
        "messaggio inatteso: $message",
        message.contains("captcha", ignoreCase = true) ||
          message.contains("protett", ignoreCase = true) ||
          message.contains("sorgenti", ignoreCase = true) ||
          message.contains("non risolvibile", ignoreCase = true)
      )
    }
  }

  /** StreamingCommunity: nessun mirror attivo -> fallimento esplicito e tracciato. */
  @Test
  fun streamingCommunityFallisceInModoEsplicito(): Unit = runBlocking {
    val result = runCatching {
      StreamingCommunityProvider().getStreams(
        tmdbId = 1396, isTv = true, season = 1, episode = 1,
        title = "Breaking Bad", year = 2008
      )
    }
    if (result.isSuccess) {
      val sources = result.getOrNull()!!
      assertTrue("sorgenti SC non valide", sources.isNotEmpty())
      println("StreamingCommunity OK -> " + sources.joinToString { "${it.serverName} ${it.quality}" })
    } else {
      val message = result.exceptionOrNull()?.message.orEmpty()
      println("StreamingCommunity: $message")
      assertTrue(
        "atteso errore di mirror, ottenuto: $message",
        message.contains("non risolvibile", ignoreCase = true)
      )
      assertTrue("elenco mirror mancante: $message", message.contains("streamingcommunity"))
    }
  }
}
