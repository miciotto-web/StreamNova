package com.example.data.opensubtitles

import com.example.domain.model.Subtitle
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

class OpenSubtitlesSubtitleDownloaderTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var server: MockWebServer

  /** Fake minimale dell'API: registra i file_id ricevuti e risponde con il link dato. */
  private inner class FakeDownloadApi(
    private val link: String?,
    private val httpCode: Int = 200
  ) : OpenSubtitlesApi {
    var lastFileId: Long? = null

    override suspend fun searchSubtitles(
      imdbId: Int?,
      tmdbId: Int?,
      type: String?,
      seasonNumber: Int?,
      episodeNumber: Int?,
      languages: String?,
      year: Int?,
      query: String?
    ): Response<OpenSubtitlesSearchResponse> = Response.success(OpenSubtitlesSearchResponse())

    override suspend fun requestDownloadLink(
      request: OpenSubtitlesDownloadRequest
    ): Response<OpenSubtitlesDownloadResponse> {
      lastFileId = request.fileId
      if (httpCode != 200) {
        return Response.error(
          httpCode,
          """{"error":"quota"}""".toResponseBody("application/json".toMediaType())
        )
      }
      return Response.success(OpenSubtitlesDownloadResponse(link = link))
    }
  }

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private val srtBody =
    "1\n00:00:01,000 --> 00:00:02,000\nCiao\n\n2\n00:00:03,000 --> 00:00:04,000\nMondo\n"

  private fun subtitle(fileId: Long? = null, url: String = "", lang: String = "it") = Subtitle(
    id = "os-42",
    url = url,
    lang = lang,
    addonName = "OpenSubtitles v3",
    fileId = fileId
  )

  private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zos ->
      entries.forEach { (name, content) ->
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray())
        zos.closeEntry()
      }
    }
    return output.toByteArray()
  }

  private fun gzipOf(content: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { gz -> gz.write(content) }
    return output.toByteArray()
  }

  @Test
  fun downloadConFileIdUsaIlLinkTemporaneoESalvaIlFileLocale() = runTest {
    server.enqueue(MockResponse().setBody(srtBody))
    val link = server.url("/dl/token-temporaneo.srt").toString()
    val api = FakeDownloadApi(link = link)

    val target = tempFolder.newFolder("subs")
    val downloader = OpenSubtitlesSubtitleDownloader(api = api)
    val file = downloader.download(subtitle(fileId = 4242L), target).getOrThrow()

    assertEquals(4242L, api.lastFileId)
    assertTrue(file.exists())
    assertTrue(file.name.endsWith(".srt"))
    assertTrue(file.readText().contains("Ciao"))
    // La richiesta al link temporaneo NON invia Api-Key: il token è già preautorizzato.
    val recorded = server.takeRequest()
    assertNull(recorded.getHeader("Api-Key"))
  }

  @Test
  fun downloadInterpretaZipEstrattoIlFileSottotitolo() = runTest {
    val payload = zipOf(
      "readme.nfo" to "non e' un sottotitolo",
      "Movie.2024.1080p.italian.srt" to srtBody
    )
    server.enqueue(MockResponse().setBody(Buffer().write(payload)))
    val api = FakeDownloadApi(link = server.url("/zip").toString())

    val target = tempFolder.newFolder("subs-zip")
    val downloader = OpenSubtitlesSubtitleDownloader(api = api)
    val file = downloader.download(subtitle(fileId = 7L), target).getOrThrow()

    assertTrue(file.name.endsWith(".srt"))
    assertTrue(file.readText().contains("Mondo"))
  }

  @Test
  fun downloadDaUrlDirettoSenzaFileIdGestisceZip() = runTest {
    val payload = zipOf("solo.srt" to srtBody)
    server.enqueue(MockResponse().setBody(Buffer().write(payload)))

    val target = tempFolder.newFolder("subs-url")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val file = downloader.download(
      subtitle(url = server.url("/addon/handler.zip").toString()),
      target
    ).getOrThrow()

    assertTrue(file.name.endsWith(".srt"))
    assertTrue(file.readText().contains("Ciao"))
  }

  @Test
  fun zipConVoceNonSicuraVieneIgnorata() {
    val zip = zipOf("../evil.srt" to "evil", "safe.srt" to srtBody)
    val extracted = OpenSubtitlesPayloadCodec.extractFirstSubtitleFromZip(zip, 1024 * 1024)
    assertNotNull(extracted)
    assertTrue(extracted!!.decodeToString().contains("Ciao"))
  }

  @Test
  fun zipSenzaVociUtilizzabiliRestituisceErroreInvalidFile() = runTest {
    server.enqueue(MockResponse().setBody(Buffer().write(zipOf("solo-nfo.nfo" to "x"))))
    val target = tempFolder.newFolder("subs-zipbad")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val failure = downloader
      .download(subtitle(url = server.url("/bad.zip").toString()), target)
      .exceptionOrNull()
    assertTrue(failure is OpenSubtitlesDownloadException)
    assertEquals(
      OpenSubtitlesDownloadError.INVALID_FILE,
      (failure as OpenSubtitlesDownloadException).error
    )
  }

  @Test
  fun gzipVieneDecompresso() = runTest {
    server.enqueue(MockResponse().setBody(Buffer().write(gzipOf(srtBody.toByteArray()))))
    val target = tempFolder.newFolder("subs-gzip")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val file = downloader.download(subtitle(url = server.url("/file.gz").toString()), target)
      .getOrThrow()
    assertTrue(file.readText().contains("Ciao"))
  }

  @Test
  fun contenutoWebVttSenzaEstensioneSalvatoComeVtt() = runTest {
    server.enqueue(MockResponse().setBody("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nCiao\n"))
    val target = tempFolder.newFolder("subs-vtt")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val file = downloader.download(
      subtitle(url = server.url("/noextension").toString()),
      target
    ).getOrThrow()
    assertTrue(file.name.endsWith(".vtt"))
  }

  @Test
  fun payloadHtmlRestituisceErroreInvalidFile() = runTest {
    server.enqueue(
      MockResponse().setBody("<!DOCTYPE html><html><body>Not Found</body></html>")
    )
    val target = tempFolder.newFolder("subs-html")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val failure = downloader
      .download(subtitle(url = server.url("/page").toString()), target)
      .exceptionOrNull()
    assertTrue(failure is OpenSubtitlesDownloadException)
    assertEquals(
      OpenSubtitlesDownloadError.INVALID_FILE,
      (failure as OpenSubtitlesDownloadException).error
    )
  }

  @Test
  fun fileVuotoRestituisceErroreEmptyFile() = runTest {
    server.enqueue(MockResponse().setBody(""))
    val target = tempFolder.newFolder("subs-empty")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val failure = downloader
      .download(subtitle(url = server.url("/empty.srt").toString()), target)
      .exceptionOrNull()
    assertTrue(failure is OpenSubtitlesDownloadException)
    assertEquals(
      OpenSubtitlesDownloadError.EMPTY_FILE,
      (failure as OpenSubtitlesDownloadException).error
    )
  }

  @Test
  fun risposteHttpNonRiusciteRestituisconoErroreHttp() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    val target = tempFolder.newFolder("subs-404")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val failure = downloader
      .download(subtitle(url = server.url("/missing.srt").toString()), target)
      .exceptionOrNull()
    assertTrue(failure is OpenSubtitlesDownloadException)
    assertEquals(OpenSubtitlesDownloadError.HTTP, (failure as OpenSubtitlesDownloadException).error)
  }

  @Test
  fun endpointDownloadHttpFallitoRestituisceErroreHttp() = runTest {
    val api = FakeDownloadApi(link = null, httpCode = 429)

    val target = tempFolder.newFolder("subs-429")
    val downloader = OpenSubtitlesSubtitleDownloader(api = api)
    val failure = downloader
      .download(subtitle(fileId = 5L), target)
      .exceptionOrNull()
    assertTrue(failure is OpenSubtitlesDownloadException)
    assertEquals(OpenSubtitlesDownloadError.HTTP, (failure as OpenSubtitlesDownloadException).error)
  }

  @Test
  fun downloadSeguitoDaLinkTemporaneoNonSalvaLaJsonComeSrt() = runTest {
    // La risposta del POST /download (JSON con il link temporaneo) è restituita dal fake
    // api: NON va mai trattata come il file. Il contenuto salvato deve essere il payload
    // reale servito dal link temporaneo.
    server.enqueue(MockResponse().setBody(srtBody))
    val api = FakeDownloadApi(
      link = server.url("/real.srt").toString(),
      httpCode = 200
    )

    val target = tempFolder.newFolder("subs-json")
    val downloader = OpenSubtitlesSubtitleDownloader(api = api)
    val file = downloader.download(subtitle(fileId = 3L), target).getOrThrow()

    assertTrue(file.readText().contains("Ciao"))
    assertFalse(file.readText().contains("\"link\""))
    // Una sola richiesta HTTP: quella verso il link temporaneo del file reale.
    assertEquals(1, server.requestCount)
  }

  @Test
  fun rispostaSenzaLinkUtilizzabileRestituisceNoDownloadLink() = runTest {
    // Caso reale quando la quota è esaurita: JSON con message, senza link.
    val api = FakeDownloadApi(link = null)

    val target = tempFolder.newFolder("subs-nolink")
    val downloader = OpenSubtitlesSubtitleDownloader(api = api)
    val failure = downloader.download(subtitle(fileId = 8L), target).exceptionOrNull()
    assertTrue(failure is OpenSubtitlesDownloadException)
    assertEquals(
      OpenSubtitlesDownloadError.NO_DOWNLOAD_LINK,
      (failure as OpenSubtitlesDownloadException).error
    )
    assertTrue(target.listFiles().isNullOrEmpty())
  }

  @Test
  fun rispostaConLinkVuotoRestituisceNoDownloadLink() = runTest {
    val api = FakeDownloadApi(link = "   ")

    val target = tempFolder.newFolder("subs-blank")
    val downloader = OpenSubtitlesSubtitleDownloader(api = api)
    val failure = downloader.download(subtitle(fileId = 9L), target).exceptionOrNull()
    assertEquals(
      OpenSubtitlesDownloadError.NO_DOWNLOAD_LINK,
      (failure as OpenSubtitlesDownloadException).error
    )
  }

  @Test
  fun selezioneSenzaFileIdESenzaUrlRestituisceNoDownloadLink() = runTest {
    val target = tempFolder.newFolder("subs-niente")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val failure = downloader.download(subtitle(), target).exceptionOrNull()
    assertEquals(
      OpenSubtitlesDownloadError.NO_DOWNLOAD_LINK,
      (failure as OpenSubtitlesDownloadException).error
    )
    // Nessun file scritto.
    assertTrue(target.listFiles().isNullOrEmpty())
  }

  @Test
  fun timeoutDiReteRestituisceErroreTimeout() = runTest {
    server.enqueue(
      MockResponse().setBody(srtBody).setHeadersDelay(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
    )
    val slowClient = OkHttpClient.Builder()
      .readTimeout(150, java.util.concurrent.TimeUnit.MILLISECONDS)
      .build()
    val target = tempFolder.newFolder("subs-timeout")
    val downloader = OpenSubtitlesSubtitleDownloader(client = slowClient)
    val failure = downloader
      .download(subtitle(url = server.url("/slow.srt").toString()), target)
      .exceptionOrNull()
    assertTrue(failure is OpenSubtitlesDownloadException)
    assertEquals(
      OpenSubtitlesDownloadError.TIMEOUT,
      (failure as OpenSubtitlesDownloadException).error
    )
  }

  @Test
  fun hostIrraggiungibileRestituisceErroreNetwork() = runTest {
    server.shutdown()
    val target = tempFolder.newFolder("subs-net")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val failure = downloader
      .download(subtitle(url = server.url("/offline.srt").toString()), target)
      .exceptionOrNull()
    assertTrue(failure is OpenSubtitlesDownloadException)
    assertEquals(
      OpenSubtitlesDownloadError.NETWORK,
      (failure as OpenSubtitlesDownloadException).error
    )
  }

  @Test
  fun redirectHttpSeguitiFinoAFileSrt() = runTest {
    // Il primo endpoint risponde 302 verso il file reale (pattern dl.opensubtitles.com).
    server.enqueue(
      MockResponse().setResponseCode(302).setHeader("Location", server.url("/final.srt").toString())
    )
    server.enqueue(MockResponse().setBody(srtBody))

    val target = tempFolder.newFolder("subs-redirect")
    val downloader = OpenSubtitlesSubtitleDownloader()
    val file = downloader.download(
      subtitle(url = server.url("/start-redirect").toString()),
      target
    ).getOrThrow()

    assertTrue(file.readText().contains("Ciao"))
    assertEquals(2, server.requestCount)
  }

  @Test
  fun clientDiDownloadNonHaLoggingInterceptor() {
    val client = OpenSubtitlesSubtitleDownloader.defaultDownloadClient()
    assertTrue(client.interceptors.none { it is HttpLoggingInterceptor })
    assertTrue(client.networkInterceptors.none { it is HttpLoggingInterceptor })
  }

  @Test
  fun codecDistingueZipEGzipEHtml() {
    assertTrue(OpenSubtitlesPayloadCodec.isZip(zipOf("a.srt" to "x")))
    assertFalse(OpenSubtitlesPayloadCodec.isZip(srtBody.toByteArray()))
    assertTrue(OpenSubtitlesPayloadCodec.isGzip(gzipOf(srtBody.toByteArray())))
    assertFalse(OpenSubtitlesPayloadCodec.isGzip(srtBody.toByteArray()))
    assertTrue(OpenSubtitlesPayloadCodec.looksLikeHtml("<!DOCTYPE html><html>".toByteArray()))
    assertFalse(OpenSubtitlesPayloadCodec.looksLikeHtml(srtBody.toByteArray()))
  }

  @Test
  fun inferSubtitleExtensionDeduceDalContenuto() {
    assertEquals(".srt", OpenSubtitlesPayloadCodec.inferSubtitleExtension(srtBody.toByteArray(), null))
    assertEquals(".vtt", OpenSubtitlesPayloadCodec.inferSubtitleExtension("WEBVTT\n".toByteArray(), null))
    assertEquals(
      ".ass",
      OpenSubtitlesPayloadCodec.inferSubtitleExtension("[Script Info]\n".toByteArray(), null)
    )
  }
}
