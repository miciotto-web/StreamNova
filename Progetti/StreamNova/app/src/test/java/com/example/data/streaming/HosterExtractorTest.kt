package com.example.data.streaming

import com.example.data.streaming.extractors.ExtractorHttp
import com.example.data.streaming.extractors.MaxStreamExtractor
import com.example.data.streaming.extractors.MixdropExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test di estrazione su hoster **reali** (richiedono rete).
 *
 * - MaxStream: si prende un embed dalla home ufficiale e se ne estrae la master
 *   playlist HLS, verificando che risponda `#EXTM3U`.
 * - MixDrop: si verifica che l'embed venga riconosciuto e che, se il file è
 *   protetto da reCAPTCHA v3, l'errore sia esplicito (e non un parsing fallito).
 */
class HosterExtractorTest {

  @Test
  fun maxStreamEstraeMasterPlaylistHls(): Unit = runBlocking {
    val home = ExtractorHttp.get(MAXSTREAM_HOME, referer = MAXSTREAM_HOME)
    val embedUrl = IFRAME_REGEX.find(home)?.groupValues?.get(1)
    assertTrue("embed MaxStream non trovato nella home", embedUrl != null)

    val sources = MaxStreamExtractor().extract(embedUrl!!)
    assertTrue("nessuna sorgente MaxStream", sources.isNotEmpty())

    val url = sources.first().url
    assertTrue("sorgente non HLS: $url", url.contains(".m3u8", ignoreCase = true))
    assertTrue("Referer mancante", sources.first().headers["Referer"] == "https://maxstream.video/")

    // La playlist deve rispondere effettivamente come HLS con gli header dichiarati
    val playlist = ExtractorHttp.get(url, referer = sources.first().headers["Referer"])
    assertTrue("risposta non HLS: ${playlist.take(40)}", playlist.startsWith("#EXTM3U"))
    println("MaxStream OK -> $url")
  }

  @Test
  fun mixdropRiconosceEmbedEVincoloRecaptcha(): Unit = runBlocking {
    // Embed reale ottenuto risolvendo un link stayonline di un articolo CB01
    val embed = "https://m1xdrop.net/f/owq06gznilklpj0"
    val result = runCatching { MixdropExtractor().extract(embed) }
    if (result.isSuccess) {
      val url = result.getOrNull()!!.first().url
      assertTrue("sorgente MixDrop non media: $url",
        url.contains(".m3u8", true) || url.contains(".mp4", true))
      println("MixDrop OK -> $url")
    } else {
      val message = result.exceptionOrNull()?.message.orEmpty()
      println("MixDrop gate: $message")
      assertTrue(
        "errore inatteso (atteso gate reCAPTCHA o file rimosso): $message",
        message.contains("reCAPTCHA", ignoreCase = true) ||
          message.contains("protett", ignoreCase = true) ||
          message.contains("nessuna sorgente", ignoreCase = true) ||
          message.contains("HTTP", ignoreCase = true)
      )
    }
  }

  companion object {
    private const val MAXSTREAM_HOME = "https://maxstream.video/"
    private val IFRAME_REGEX = Regex("""<iframe[^>]+src="(https://maxstream\.video/[^"]+)"""")
  }
}
