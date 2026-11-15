package com.example.data.streaming

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica di riproducibilità **senza player**: manifest, playlist di variante,
 * segmento di inizializzazione e primo segmento vengono sondati in sequenza.
 */
class StreamSourceVerifierTest {

  @Test
  fun playlistHlsConSegmentoRaggiungibileEPlayable() = runBlocking {
    val probe = RecordingProbe { url, range ->
      when {
        url == MASTER_URL -> response(200, MEDIA_PLAYLIST)
        url == SEGMENT_URL && range != null -> response(206, "video-bytes")
        else -> response(404, "")
      }
    }

    val verdict = HttpSourceVerifier(probe).verify(source(MASTER_URL))

    assertEquals(SourcePlayability.Playable, verdict)
    assertTrue("il primo segmento deve essere sondato con Range", probe.calls.any { it.first == SEGMENT_URL && it.second != null })
  }

  @Test
  fun masterPlaylistSegueLaVarianteDichiarata() = runBlocking {
    val probe = RecordingProbe { url, range ->
      when {
        url == MASTER_MULTI_URL -> response(200, MASTER_MULTI)
        url.startsWith("https://cdn.test/hls/variant-1080.m3u8") && range == null ->
          response(200, MEDIA_PLAYLIST)
        url.startsWith("https://cdn.test/hls/segment.ts") && range != null ->
          response(206, "video-bytes")
        else -> response(404, "")
      }
    }

    val verdict = HttpSourceVerifier(probe).verify(source(MASTER_MULTI_URL, verifiedHeight = 1080))

    assertEquals(SourcePlayability.Playable, verdict)
    assertTrue(
      "la variante 1080p deve essere preferita alla 4K quando la sorgente la dichiara",
      probe.calls.any { it.first.startsWith("https://cdn.test/hls/variant-1080.m3u8") }
    )
  }

  @Test
  fun manifest403NonEPlayable() = runBlocking {
    val probe = RecordingProbe { _, _ -> response(403, "") }
    assertEquals(SourcePlayability.Unavailable, HttpSourceVerifier(probe).verify(source(MASTER_URL)))
  }

  @Test
  fun erroreServerTemporaneoNonDichiaraLaSorgenteInesistente() = runBlocking {
    val probe = RecordingProbe { _, _ -> response(503, "") }
    assertEquals(SourcePlayability.TransientFailure, HttpSourceVerifier(probe).verify(source(MASTER_URL)))
  }

  @Test
  fun varianteSenzaSegmentiNonEPlayable() = runBlocking {
    val probe = RecordingProbe { url, _ ->
      when {
        url == MASTER_URL -> response(
          200,
          """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160
            index-4k.m3u8
          """.trimIndent()
        )
        url.startsWith("https://cdn.test/hls/index-4k.m3u8") ->
          response(200, "#EXTM3U\n#EXT-X-ENDLIST")
        else -> response(404, "")
      }
    }

    val verdict = HttpSourceVerifier(probe).verify(source(MASTER_URL))

    assertEquals(SourcePlayability.Unavailable, verdict)
  }

  @Test
  fun segmento403NonEPlayable() = runBlocking {
    val probe = RecordingProbe { url, range ->
      when {
        url == MASTER_URL -> response(200, MEDIA_PLAYLIST)
        range != null -> response(403, "")
        else -> response(404, "")
      }
    }

    assertEquals(SourcePlayability.Unavailable, HttpSourceVerifier(probe).verify(source(MASTER_URL)))
  }

  @Test
  fun paginaHtmlAlPostoDelFlussoNonEPlayable() = runBlocking {
    val probe = RecordingProbe { _, _ -> response(200, "<!DOCTYPE html><html></html>") }
    assertEquals(SourcePlayability.Unavailable, HttpSourceVerifier(probe).verify(source(MASTER_URL)))
  }

  @Test
  fun fileProgressivoRaggiungibileEPlayable() = runBlocking {
    val probe = RecordingProbe { _, _ -> response(200, "mp4-bytes") }

    val verdict = HttpSourceVerifier(probe).verify(
      source("https://cdn.test/movie/movie.mp4")
    )

    assertEquals(SourcePlayability.Playable, verdict)
  }

  @Test
  fun erroreDiReteNonDichiaraLaSorgenteInesistente() = runBlocking {
    val probe = RecordingProbe { _, _ -> throw IOException("timeout di rete") }
    assertEquals(
      SourcePlayability.TransientFailure,
      HttpSourceVerifier(probe).verify(source(MASTER_URL))
    )
  }

  private fun source(url: String, verifiedHeight: Int? = null) = StreamSource(
    streamUrl = url,
    quality = "Auto",
    serverName = "Verifica",
    verifiedHeight = verifiedHeight
  )

  private fun response(code: Int, body: String) = ProbeResponse(
    code = code,
    finalUrl = "",
    body = body,
    contentType = null
  )

  private class RecordingProbe(
    private val handler: suspend (String, Int?) -> ProbeResponse
  ) : StreamProbe {
    val calls = mutableListOf<Pair<String, Int?>>()

    override suspend fun get(
      url: String,
      headers: Map<String, String>,
      rangeBytes: Int?
    ): ProbeResponse {
      calls += url to rangeBytes
      return handler(url, rangeBytes)
    }
  }

  private companion object {
    const val MASTER_URL = "https://cdn.test/hls/master.m3u8?t=abc"
    const val MASTER_MULTI_URL = "https://cdn.test/hls/multi.m3u8?t=abc"
    const val SEGMENT_URL = "https://cdn.test/hls/segment.ts?t=abc"

    val MEDIA_PLAYLIST = """
      #EXTM3U
      #EXT-X-VERSION:3
      #EXT-X-TARGETDURATION:6
      #EXTINF:6.0,
      segment.ts
      #EXT-X-ENDLIST
    """.trimIndent()

    val MASTER_MULTI = """
      #EXTM3U
      #EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160
      variant-4k.m3u8
      #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080
      variant-1080.m3u8
    """.trimIndent()
  }
}
