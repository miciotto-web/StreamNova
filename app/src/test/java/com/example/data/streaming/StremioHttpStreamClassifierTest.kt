package com.example.data.streaming

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test di compatibilità HTTP proxy degli addon Stremio (livello comune).
 *
 * Verifica che il tipo effettivo di uno stream (HLS vs progressivo) sia
 * determinato dal CONTENUTO quando l'URL non lo dichiara, e che gli URL,
 * gli header e l'ordine delle sorgenti restino intatti.
 *
 * Nessuna rete: la sonda [StreamProbe] è iniettata con risposte controllate.
 */
class StremioHttpStreamClassifierTest {

  /** Sonda controllata: mappa URL → risposta (o eccezione simulata). */
  private class FakeProbe(
    private val responses: Map<String, ProbeResponse>,
    private val failWith: Exception? = null,
    private val seenHeaders: MutableMap<String, Map<String, String>>? = null
  ) : StreamProbe {
    override suspend fun get(
      url: String,
      headers: Map<String, String>,
      rangeBytes: Int?
    ): ProbeResponse {
      seenHeaders?.put(url, headers)
      failWith?.let { throw it }
      return responses[url]
        ?: throw IllegalStateException("URL non in mappa: $url")
    }
  }

  private fun source(
    url: String?,
    headers: Map<String, String> = emptyMap(),
    isProgressive: Boolean? = null,
    serverName: String = "Addon Test"
  ) = StreamSource(
    streamUrl = url,
    serverName = serverName,
    headers = headers,
    isProgressive = isProgressive
  )

  // ── urlKind: fast path sull'estensione del PATH ─────────────────────────────

  @Test
  fun pathM3u8ClassificatoHls() {
    assertEquals(HttpStreamKind.HLS, StremioHttpStreamClassifier.urlKind("https://proxy.example/stream/master.m3u8"))
    assertEquals(HttpStreamKind.HLS, StremioHttpStreamClassifier.urlKind("https://proxy.example/PLAYLIST.M3U8"))
  }

  @Test
  fun contenitoriNotiClassificatiProgressivi() {
    val urls = listOf(
      "https://cdn.example/file.mp4",
      "https://cdn.example/file.mkv",
      "https://cdn.example/file.webm",
      "https://cdn.example/file.avi",
      "https://cdn.example/file.m4v",
      "https://cdn.example/file.ts",
      "https://cdn.example/file.mov",
      "https://cdn.example/file.flv",
      "https://cdn.example/FILE.MP4"
    )
    urls.forEach { url ->
      assertEquals(url, HttpStreamKind.PROGRESSIVE, StremioHttpStreamClassifier.urlKind(url))
    }
  }

  @Test
  fun tokenNellaQueryNonDichiaraIlFormato() {
    // Un token che contiene ".mp4" nella query NON dichiara il contenuto:
    // solo il PATH dell'URL conta.
    assertEquals(
      null,
      StremioHttpStreamClassifier.urlKind("https://proxy.example/stream?token=abc.mp4&r=0")
    )
    // Anche il caso opposto: query con .m3u8 su path ambiguo → sonda, non estensione.
    assertEquals(
      null,
      StremioHttpStreamClassifier.urlKind("https://proxy.example/stream?ref=master.m3u8")
    )
  }

  @Test
  fun pathSenzaEstensioneEUrlVuotoRestanoAmbigui() {
    assertNull(StremioHttpStreamClassifier.urlKind("https://proxy.example/playback/abc123"))
    assertNull(StremioHttpStreamClassifier.urlKind(null))
    assertNull(StremioHttpStreamClassifier.urlKind(""))
    assertNull(StremioHttpStreamClassifier.urlKind("   "))
  }

  @Test
  fun estensioneConFragmentOReturningRiconosciuta() {
    assertEquals(HttpStreamKind.HLS, StremioHttpStreamClassifier.urlKind("https://p.example/x.m3u8?t=1#frag"))
    assertEquals(HttpStreamKind.PROGRESSIVE, StremioHttpStreamClassifier.urlKind("https://p.example/x.mp4#t=0"))
  }

  @Test
  fun mpdNonVieneClassificatoComeEstensioneNota() {
    // DASH non è gestito dal percorso MediaSourceFactory corrente: resta ambiguo.
    assertNull(StremioHttpStreamClassifier.urlKind("https://cdn.example/stream.mpd"))
  }

  // ── fromContentType / fromBody ──────────────────────────────────────────────

  @Test
  fun contentTypeMpegurlClassificaHls() {
    assertEquals(
      HttpStreamKind.HLS,
      StremioHttpStreamClassifier.fromContentType("application/vnd.apple.mpegurl")
    )
    assertEquals(
      HttpStreamKind.HLS,
      StremioHttpStreamClassifier.fromContentType("application/x-mpegURL")
    )
    assertEquals(
      HttpStreamKind.HLS,
      StremioHttpStreamClassifier.fromContentType("application/vnd.apple.mpegurl; charset=utf-8")
    )
  }

  @Test
  fun contentTypeVideoClassificaProgressivo() {
    assertEquals(HttpStreamKind.PROGRESSIVE, StremioHttpStreamClassifier.fromContentType("video/mp4"))
    assertEquals(HttpStreamKind.PROGRESSIVE, StremioHttpStreamClassifier.fromContentType("video/MP2T"))
    assertEquals(HttpStreamKind.PROGRESSIVE, StremioHttpStreamClassifier.fromContentType("video/webm"))
    assertEquals(
      HttpStreamKind.PROGRESSIVE,
      StremioHttpStreamClassifier.fromContentType("application/octet-stream")
    )
    assertEquals(
      HttpStreamKind.PROGRESSIVE,
      StremioHttpStreamClassifier.fromContentType("binary/octet-stream")
    )
  }

  @Test
  fun contentTypeNonConclusivoRestituisceNull() {
    assertNull(StremioHttpStreamClassifier.fromContentType("text/html"))
    assertNull(StremioHttpStreamClassifier.fromContentType("application/json"))
    assertNull(StremioHttpStreamClassifier.fromContentType(null))
    assertNull(StremioHttpStreamClassifier.fromContentType(""))
  }

  @Test
  fun corpoExtm3uClassificaHlsAncheConBom() {
    assertEquals(HttpStreamKind.HLS, StremioHttpStreamClassifier.fromBody("#EXTM3U\n#EXT-X-VERSION:3"))
    assertEquals(
      HttpStreamKind.HLS,
      StremioHttpStreamClassifier.fromBody("\uFEFF\r\n#EXTM3U")
    )
  }

  @Test
  fun corpoFtypClassificaProgressivo() {
    val mp4Head = "\u0000\u0000\u0000\u0018ftypisom\u0000\u0000\u0002\u0000isomiso2"
    assertEquals(HttpStreamKind.PROGRESSIVE, StremioHttpStreamClassifier.fromBody(mp4Head))
  }

  @Test
  fun corpoHtmlOrJsonRestaNullo() {
    assertNull(StremioHttpStreamClassifier.fromBody("<!doctype html><html>"))
    assertNull(StremioHttpStreamClassifier.fromBody("{\"error\":\"not found\"}"))
    assertNull(StremioHttpStreamClassifier.fromBody(""))
  }

  // ── probeKind: sonda HTTP con header dello stream ───────────────────────────

  @Test
  fun sondaConContentTypeHlsClassificaHls() = runTest {
    val probe = FakeProbe(
      mapOf("https://proxy.example/stream?token=x" to ProbeResponse(200, "https://proxy.example/stream?token=x", "", "application/vnd.apple.mpegurl"))
    )
    assertEquals(
      HttpStreamKind.HLS,
      StremioHttpStreamClassifier.probeKind("https://proxy.example/stream?token=x", emptyMap(), probe)
    )
  }

  @Test
  fun sondaPassaGliHeaderDelloStream() = runTest {
    val seen = mutableMapOf<String, Map<String, String>>()
    val probe = FakeProbe(
      mapOf("https://proxy.example/stream" to ProbeResponse(200, "https://proxy.example/stream", "", "video/mp4")),
      seenHeaders = seen
    )
    val headers = mapOf("user-agent" to "UA-proxy", "Referer" to "https://referrer.example/")
    assertEquals(
      HttpStreamKind.PROGRESSIVE,
      StremioHttpStreamClassifier.probeKind("https://proxy.example/stream", headers, probe)
    )
    assertEquals(headers, seen["https://proxy.example/stream"])
  }

  @Test
  fun sondaSuRisposta403RestaNullo() = runTest {
    val probe = FakeProbe(
      mapOf("https://proxy.example/stream" to ProbeResponse(403, "https://proxy.example/stream", "", "text/html"))
    )
    assertNull(
      StremioHttpStreamClassifier.probeKind("https://proxy.example/stream", emptyMap(), probe)
    )
  }

  @Test
  fun sondaSuErroreDiReteRestaNullo() = runTest {
    val probe = FakeProbe(emptyMap(), failWith = java.io.IOException("network down"))
    assertNull(
      StremioHttpStreamClassifier.probeKind("https://proxy.example/stream", emptyMap(), probe)
    )
  }

  @Test
  fun sondaSuBodySenzaContentTypeClassificaDalCorpo() = runTest {
    val playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nvariant.m3u8"
    val probe = FakeProbe(
      mapOf("https://proxy.example/stream" to ProbeResponse(200, "https://proxy.example/stream", playlist, null))
    )
    assertEquals(
      HttpStreamKind.HLS,
      StremioHttpStreamClassifier.probeKind("https://proxy.example/stream", emptyMap(), probe)
    )
  }

  // ── refineSources: tristate senza alterare ordine né URL ────────────────────

  @Test
  fun estensioniNoteNonVengonoSondate() = runTest {
    val seen = mutableMapOf<String, Map<String, String>>()
    val probe = FakeProbe(emptyMap(), seenHeaders = seen)
    val sources = listOf(
      source("https://cdn.example/a.m3u8"),
      source("https://cdn.example/b.mp4")
    )
    val refined = StremioHttpStreamClassifier.refineSources(sources, probe)

    assertFalse("nessuna sonda per URL con estensione nota", seen.isNotEmpty())
    assertEquals(false, refined[0].isProgressive)
    assertEquals(true, refined[1].isProgressive)
  }

  @Test
  fun urlAmbiguoClassificatoDalContenuto() = runTest {
    val probe = FakeProbe(
      mapOf(
        "https://proxy.example/hls?token=a" to
          ProbeResponse(200, "https://proxy.example/hls?token=a", "", "application/vnd.apple.mpegurl"),
        "https://proxy.example/file?token=b" to
          ProbeResponse(200, "https://proxy.example/file?token=b", "", "video/mp4")
      )
    )
    val refined = StremioHttpStreamClassifier.refineSources(
      listOf(
        source("https://proxy.example/hls?token=a"),
        source("https://proxy.example/file?token=b")
      ),
      probe
    )
    assertEquals(false, refined[0].isProgressive)
    assertEquals(true, refined[1].isProgressive)
    // Gli URL restano IDENTICI: nessun rewriting di token o query.
    assertEquals("https://proxy.example/hls?token=a", refined[0].streamUrl)
    assertEquals("https://proxy.example/file?token=b", refined[1].streamUrl)
  }

  @Test
  fun sondaInconclusivaLasciaFlagNull() = runTest {
    val probe = FakeProbe(
      mapOf("https://proxy.example/stream" to ProbeResponse(403, "https://proxy.example/stream", "", "text/html"))
    )
    val refined = StremioHttpStreamClassifier.refineSources(listOf(source("https://proxy.example/stream")), probe)
    assertNull(refined[0].isProgressive)
    assertEquals("https://proxy.example/stream", refined[0].streamUrl)
  }

  @Test
  fun ordineDelleSorgentiNonCambia() = runTest {
    val probe = FakeProbe(
      mapOf(
        "https://proxy.example/1" to ProbeResponse(200, "u", "", "video/mp4"),
        "https://proxy.example/2" to ProbeResponse(200, "u", "", "video/mp4"),
        "https://proxy.example/3" to ProbeResponse(200, "u", "", "video/mp4")
      )
    )
    val names = listOf("Prima", "Seconda", "Terza")
    val refined = StremioHttpStreamClassifier.refineSources(
      names.mapIndexed { index, name -> source("https://proxy.example/${index + 1}", serverName = name) },
      probe
    )
    assertEquals(names, refined.map { it.serverName })
  }

  @Test
  fun capDiSondeSuMolteSorgentiAmbigue() = runTest {
    val seen = mutableMapOf<String, Map<String, String>>()
    val probe = FakeProbe(
      (1..20).associate { i ->
        "https://proxy.example/$i" to ProbeResponse(200, "u", "", "video/mp4")
      },
      seenHeaders = seen
    )
    val refined = StremioHttpStreamClassifier.refineSources(
      (1..20).map { i -> source("https://proxy.example/$i") },
      probe
    )
    assertEquals("sonde limitate a MAX_PROBES", StremioHttpStreamClassifier.MAX_PROBES, seen.size)
    // Le prime 8 classificate, le restanti lasciate al default del player (null).
    assertTrue(refined.take(StremioHttpStreamClassifier.MAX_PROBES).all { it.isProgressive == true })
    assertTrue(refined.drop(StremioHttpStreamClassifier.MAX_PROBES).all { it.isProgressive == null })
  }

  @Test
  fun sorgentiSenzaUrlRestanoInvariate() = runTest {
    val probe = FakeProbe(emptyMap())
    val refined = StremioHttpStreamClassifier.refineSources(listOf(source(null)), probe)
    assertEquals(1, refined.size)
    assertNull(refined[0].streamUrl)
    assertNull(refined[0].isProgressive)
  }
}
