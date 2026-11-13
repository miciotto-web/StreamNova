package com.example.data.streaming.providers

import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.extractors.ExtractorHttp
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Test offline del provider Altadefinizione: costruzione dell'URL del player,
 * fallback di ricerca, rilevamento qualità e gestione errori. Nessuna richiesta
 * verso Internet.
 */
class AltadefinizioneProviderTest {

  private fun provider(
    http: AltadefinizioneHttp,
    mirrors: List<String> = listOf("https://catalog.test")
  ) = AltadefinizioneProvider(
    mirrors = mirrors,
    playerOrigin = "https://player.test",
    diagnostics = StreamDiagnosticSink.NONE,
    http = http
  )

  private fun tokenJson(url: String, expire: Long = FUTURE_EXPIRE) =
    """{"url":"$url","expire":$expire}"""

  @Test
  fun filmUsaImdbPaddatoERicavaLaQualita() = runBlocking {
    val http = FakeHttp { url, _, _ ->
      when {
        url.endsWith("/t/0133093") -> tokenJson(MASTER_1080_URL)
        url.startsWith(MASTER_1080_URL) -> MASTER_1080
        else -> error("URL inatteso: $url")
      }
    }

    val sources = provider(http).getStreams(
      tmdbId = 603,
      isTv = false,
      season = null,
      episode = null,
      title = "Matrix",
      year = 1999,
      imdbId = "tt0133093"
    )

    val source = sources.single()
    assertEquals(MASTER_1080_URL, source.streamUrl)
    assertEquals("1080p", source.quality)
    assertEquals("Altadefinizione", source.serverName)
    assertTrue(source.isItalian)
    assertEquals(Vidxgo.BROWSER_USER_AGENT, source.headers["User-Agent"])
    assertEquals(Vidxgo.ORIGIN, source.headers["Origin"])
    assertEquals("${Vidxgo.ORIGIN}/", source.headers["Referer"])
    assertEquals("cors", source.headers["Sec-Fetch-Mode"])
    assertTrue(http.requests.any { it == "https://player.test/t/0133093" })
  }

  @Test
  fun serieUsaPercorsoConStagioneEdEpisodio() = runBlocking {
    val http = FakeHttp { url, _, _ ->
      when {
        url.endsWith("/t/16358384/2/1") -> tokenJson(MASTER_TV_URL)
        url.startsWith(MASTER_TV_URL) -> MASTER_720
        else -> error("URL inatteso: $url")
      }
    }

    val source = provider(http).getStreams(
      tmdbId = 138471,
      isTv = true,
      season = 2,
      episode = 1,
      title = "Tulsa King",
      year = 2022,
      imdbId = "tt16358384"
    ).single()

    assertEquals("720p", source.quality)
    assertTrue(http.requests.first().endsWith("/t/16358384/2/1"))
  }

  @Test
  fun senzaImdbRicadeSullaRicercaPerTitoloEAnno() = runBlocking {
    val http = FakeHttp { url, _, _ ->
      when {
        url.startsWith("https://catalog.test/api/v1/web/archive") -> ARCHIVE
        url.endsWith("/t/0133093") -> tokenJson(MASTER_1080_URL)
        url.startsWith(MASTER_1080_URL) -> MASTER_1080
        else -> error("URL inatteso: $url")
      }
    }

    provider(http).getStreams(
      tmdbId = 0,
      isTv = false,
      season = null,
      episode = null,
      title = "Matrix",
      year = 1999,
      imdbId = null
    )

    val search = http.requests.first()
    assertTrue("la ricerca deve contenere titolo e anno: $search", search.contains("q=Matrix+1999"))
    assertTrue(http.requests.any { it.endsWith("/t/0133093") })
  }

  @Test
  fun ricercaSenzaMatchLanciaIOException() {
    val http = FakeHttp { url, _, _ ->
      if (url.startsWith("https://catalog.test/api/v1/web/archive")) {
        """{"ok":true,"items":[],"count":0}"""
      } else {
        error("URL inatteso: $url")
      }
    }

    assertThrows(IOException::class.java) {
      runBlocking {
        provider(http).getStreams(
          tmdbId = 0,
          isTv = false,
          season = null,
          episode = null,
          title = "Titolo Inesistente",
          year = 2024,
          imdbId = null
        )
      }
    }
  }

  @Test
  fun tokenNonDisponibileLanciaIOException() {
    val http = FakeHttp { url, _, _ ->
      if (url.endsWith("/t/0133093")) throw IOException("HTTP 404 su $url")
      error("URL inatteso: $url")
    }

    assertThrows(IOException::class.java) {
      runBlocking {
        provider(http).getStreams(
          tmdbId = 603,
          isTv = false,
          season = null,
          episode = null,
          title = "Matrix",
          year = 1999,
          imdbId = "tt0133093"
        )
      }
    }
  }

  @Test
  fun erroreSullaMasterNonInvalidaLaSorgente() = runBlocking {
    val http = FakeHttp { url, _, _ ->
      when {
        url.endsWith("/t/0133093") -> tokenJson(MASTER_1080_URL)
        url.startsWith(MASTER_1080_URL) -> throw IOException("HTTP 403 su $url")
        else -> error("URL inatteso: $url")
      }
    }

    val source = provider(http).getStreams(
      tmdbId = 603,
      isTv = false,
      season = null,
      episode = null,
      title = "Matrix",
      year = 1999,
      imdbId = "tt0133093"
    ).single()

    assertEquals(MASTER_1080_URL, source.streamUrl)
    assertEquals("Auto", source.quality)
  }

  @Test
  fun qualityFromMasterRiconosceLeRisoluzioni() {
    val p = provider(FakeHttp { _, _, _ -> "" })
    assertEquals("1080p", p.qualityFromMaster(MASTER_1080))
    assertEquals("720p", p.qualityFromMaster(MASTER_720))
    assertEquals("4K", p.qualityFromMaster("#EXT-X-STREAM-INF:RESOLUTION=3840x2160"))
    assertEquals("Auto", p.qualityFromMaster("#EXTM3U\nnessuna risoluzione"))
  }

  @Test
  fun pickItemPreferisceTitoloEsattoEAnno() {
    val p = provider(FakeHttp { _, _, _ -> "" })
    val items = listOf(
      ArchiveItem(id = 1, title = "Matrix Reloaded", year = 2003, imdbId = "tt0234215", kind = "movie"),
      ArchiveItem(id = 2, title = "Matrix", year = 1999, imdbId = "tt0133093", kind = "movie"),
      ArchiveItem(id = 3, title = "Matrix", year = 2024, imdbId = "tt9999999", kind = "series")
    )

    assertEquals("tt0133093", p.pickItem(items, "Matrix", 1999, "movie")?.imdbId)
    assertEquals("tt9999999", p.pickItem(items, "Matrix", 2024, "series")?.imdbId)
    assertNull(p.pickItem(items, "Titolo Inesistente", 1999, "movie"))
  }

  @Test
  fun tokenPathPerUrlPlayerVidxgo() {
    assertEquals(
      "/t/1872181",
      Vidxgo.tokenPathForPlayerUrl("https://v.vidxgo.co/1872181?info=0")
    )
    assertEquals(
      "/t/16358384/1/2",
      Vidxgo.tokenPathForPlayerUrl("https://v.vidxgo.co/16358384/1/2?se=0")
    )
    assertNull(Vidxgo.tokenPathForPlayerUrl("https://example.com/123"))
    assertNull(Vidxgo.tokenPathForPlayerUrl("https://v.vidxgo.co/abc"))
  }

  @Test
  fun playerIdApplicaZeroPaddingASetteCifre() {
    assertEquals("0133093", Vidxgo.playerId("tt0133093"))
    assertEquals("16358384", Vidxgo.playerId("tt16358384"))
    assertEquals("0000001", Vidxgo.playerId("1"))
    assertNull(Vidxgo.playerId(null))
    assertNull(Vidxgo.playerId("  "))
  }

  /**
   * Verifica end-to-end su rete reale (manuale): risolve Matrix dal catalogo
   * live e controlla che la master HLS risponda con gli header CDN della
   * sorgente. Non fa parte della suite automatica.
   */
  @Ignore("Online: dipende dai domini esterni; eseguire manualmente per la verifica end-to-end")
  @Test
  fun risolveMatrixSullaReteReale() = runBlocking {
    val source = AltadefinizioneProvider().getStreams(
      tmdbId = 603,
      isTv = false,
      season = null,
      episode = null,
      title = "Matrix",
      year = 1999,
      imdbId = "tt0133093"
    ).single()

    assertTrue(source.streamUrl.orEmpty().startsWith("https://"))
    assertTrue(source.quality == "1080p" || source.quality == "720p")

    val playlist = ExtractorHttp.get(
      source.streamUrl.orEmpty(),
      referer = source.headers["Referer"],
      extraHeaders = source.headers
    )
    assertTrue("risposta non HLS: ${playlist.take(40)}", playlist.startsWith("#EXTM3U"))
  }

  private class FakeHttp(
    private val handler: (String, String?, Map<String, String>) -> String
  ) : AltadefinizioneHttp {
    val requests = mutableListOf<String>()

    override suspend fun get(
      url: String,
      referer: String?,
      headers: Map<String, String>
    ): String {
      requests += url
      return handler(url, referer, headers)
    }
  }

  companion object {
    private const val FUTURE_EXPIRE = 9_999_999_999L
    private const val MASTER_1080_URL =
      "https://cdn.test/hls/0133093/master.m3u8?t=abc&e=9999999999&b=3098"
    private const val MASTER_TV_URL =
      "https://cdn.test/hls/tv/16358384/2/1/master.m3u8?t=abc&e=9999999999&b=3098"

    private val MASTER_1080 = """
      #EXTM3U
      #EXT-X-STREAM-INF:BANDWIDTH=3098676,RESOLUTION=1920x800,CODECS="avc1.4d4028,mp4a.40.2"
      index-v1-a1.m3u8?t=abc&e=9999999999&b=3098&p=
    """.trimIndent()

    private val MASTER_720 = """
      #EXTM3U
      #EXT-X-STREAM-INF:BANDWIDTH=1521314,RESOLUTION=1206x720,CODECS="avc1.4d401f,mp4a.40.2"
      index-v1-a1.m3u8?t=abc&e=9999999999&b=3098&p=
    """.trimIndent()

    private val ARCHIVE = """
      {"ok":true,"items":[
        {"id":1,"title":"Matrix Reloaded","year":2003,"imdbId":"tt0234215","kind":"movie"},
        {"id":2,"title":"Matrix","year":1999,"imdbId":"tt0133093","kind":"movie"}
      ],"count":2}
    """.trimIndent()
  }
}
