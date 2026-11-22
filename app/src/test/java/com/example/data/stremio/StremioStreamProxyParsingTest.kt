package com.example.data.stremio

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test di parsing della risposta `stream` per gli stream HTTP PROXY degli addon
 * Stremio (livello comune, nessun addon specifico).
 *
 * Un endpoint proxy tipico restituisce:
 *  - `url` su percorso dinamico con token (NON termina con .mp4/.m3u8);
 *  - `behaviorHints.proxyHeaders.request` con User-Agent/Referer (case vario);
 *  - `behaviorHints` arricchite di campi non previsti dal modello;
 *  - `notWebReady` e altri hint.
 *
 * Il parsing deve conservare TUTTO ciò che serve alla riproduzione: URL byte
 * per byte (token inclusi) e header come dichiarati. Nessuna rete, nessun
 * Android: Moshi come in produzione.
 */
class StremioStreamProxyParsingTest {

  private val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val adapter = moshi.adapter(StremioStreamResponse::class.java)

  private fun parse(body: String): StremioStreamItem? = try {
    adapter.fromJson(body)?.streams?.firstOrNull()
  } catch (e: Exception) {
    null
  }

  @Test
  fun streamConUrlProxyEQueryTokenConservatiBytePerByte() {
    val url = "https://proxy.example.com/v/9f8b2c/playback?token=eyJhbGciOi.eyJ1&quality=1080"
    val stream = parse("""{ "streams": [ { "url": "$url" } ] }""")

    assertTrue(stream != null)
    assertEquals(url, stream!!.url)
  }

  @Test
  fun urlSenzaEstensioneRestaIntatto() {
    val url = "https://proxy.example.com/dynamic/a1b2c3d4/stream"
    val stream = parse("""{ "streams": [ { "url": "$url" } ] }""")
    assertEquals(url, stream!!.url)
  }

  @Test
  fun proxyHeadersRequestConCaseMistoVengonoConservati() {
    val stream = parse(
      """
      { "streams": [ {
        "url": "https://proxy.example.com/stream",
        "behaviorHints": {
          "proxyHeaders": {
            "request": {
              "user-agent": "Mozilla/5.0 (proxy agent)",
              "Referer": "https://origin.example/page",
              "ORIGIN": "https://origin.example"
            }
          }
        }
      } ] }
      """.trimIndent()
    )

    val headers = stream!!.proxyHeaders
    assertEquals(3, headers.size)
    assertEquals("Mozilla/5.0 (proxy agent)", headers["user-agent"])
    assertEquals("https://origin.example/page", headers["Referer"])
    assertEquals("https://origin.example", headers["ORIGIN"])
  }

  @Test
  fun proxyHeadersConAutorizzazioneBasicConservata() {
    val stream = parse(
      """
      { "streams": [ {
        "url": "https://host.example/file",
        "behaviorHints": {
          "proxyHeaders": {
            "request": { "Authorization": "Basic dXNlcjpwYXNz" }
          }
        }
      } ] }
      """.trimIndent()
    )

    assertEquals("Basic dXNlcjpwYXNz", stream!!.proxyHeaders["Authorization"])
  }

  @Test
  fun proxyHeadersResponseEsposibileSenzaFondereLaRequest() {
    val stream = parse(
      """
      { "streams": [ {
        "url": "https://proxy.example.com/stream",
        "behaviorHints": {
          "proxyHeaders": {
            "request": { "user-agent": "UA" },
            "response": { "Content-Type": "application/vnd.apple.mpegurl" }
          }
        }
      } ] }
      """.trimIndent()
    )

    assertEquals(mapOf("user-agent" to "UA"), stream!!.proxyHeaders)
    assertEquals(
      "application/vnd.apple.mpegurl",
      stream.behaviorHints?.proxyHeaders?.response?.get("Content-Type")
    )
  }

  @Test
  fun notWebReadyEFilenameVengonoDeserializzati() {
    val stream = parse(
      """
      { "streams": [ {
        "url": "https://proxy.example.com/stream",
        "behaviorHints": {
          "notWebReady": true,
          "filename": "Movie.2021.1080p.mkv",
          "videoSize": 2147483648
        }
      } ] }
      """.trimIndent()
    )

    assertTrue(stream!!.behaviorHints?.notWebReady == true)
    assertEquals("Movie.2021.1080p.mkv", stream.behaviorHints?.filename)
    assertEquals(2147483648L, stream.behaviorHints?.videoSize)
  }

  @Test
  fun campiSconosciutiNelloStreamNonFannoFallireIlParsing() {
    val stream = parse(
      """
      { "streams": [ {
        "name": "Proxy 1080p",
        "url": "https://proxy.example.com/stream",
        "externalUrl": "https://web.example/watch",
        "embyId": "abc123",
        "futureField": { "nested": [1, 2, 3] },
        "behaviorHints": {
          "unknownHint": "whatever",
          "proxyHeaders": { "request": { "user-agent": "UA" } }
        }
      } ] }
      """.trimIndent()
    )

    assertTrue(stream != null)
    assertEquals("https://proxy.example.com/stream", stream!!.url)
    assertEquals("UA", stream.proxyHeaders["user-agent"])
    assertNull(stream.infoHash)
  }

  @Test
  fun streamTorrentConInfoHashNonConfondeIlPercorsoProxy() {
    val stream = parse(
      """
      { "streams": [ {
        "infoHash": "9f86d081884c7d659a2feaa0c55ad015",
        "fileIdx": 2,
        "name": "Torrent 1080p"
      } ] }
      """.trimIndent()
    )

    assertTrue(stream != null)
    assertNull(stream!!.url)
    assertEquals("9f86d081884c7d659a2feaa0c55ad015", stream.infoHash)
    assertEquals(2, stream.fileIdx)
    assertFalse(stream.proxyHeaders.isNotEmpty())
  }

  @Test
  fun behaviorHintsNullOMancantiProduconoHeaderVuoti() {
    val stream = parse("""{ "streams": [ { "url": "https://p.example/x" } ] }""")
    assertTrue(stream!!.proxyHeaders.isEmpty())

    val nullHints = parse("""{ "streams": [ { "url": "https://p.example/x", "behaviorHints": null } ] }""")
    assertTrue(nullHints!!.proxyHeaders.isEmpty())
  }
}
