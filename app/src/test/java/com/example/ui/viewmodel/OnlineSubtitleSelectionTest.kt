package com.example.ui.viewmodel

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.opensubtitles.OpenSubtitlesApi
import com.example.data.opensubtitles.OpenSubtitlesDownloadError
import com.example.data.opensubtitles.OpenSubtitlesDownloadRequest
import com.example.data.opensubtitles.OpenSubtitlesDownloadResponse
import com.example.data.opensubtitles.OpenSubtitlesSearchResponse
import com.example.data.opensubtitles.OpenSubtitlesSubtitleDownloader
import com.example.domain.model.Subtitle
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSubtitleSelectionTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * API fittizia: registra il file_id inviato al POST /download e risponde con il link
   * configurato. I bytes del file, invece, vengono fittizzati dal [fetchBytes] del downloader.
   */
  private class FakeOpenSubtitlesApi(
    private val link: String? = "https://sub.test/temporaneo.srt",
    private val httpCode: Int = 200
  ) : OpenSubtitlesApi {
    var lastRequestedFileId: Long? = null

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
      lastRequestedFileId = request.fileId
      return if (httpCode == 200) {
        Response.success(OpenSubtitlesDownloadResponse(link = link))
      } else {
        Response.error(httpCode, "".toResponseBody("application/json".toMediaType()))
      }
    }
  }

  private fun fixedDownloader(
    api: OpenSubtitlesApi,
    payload: ByteArray,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher
  ): OpenSubtitlesSubtitleDownloader =
    object : OpenSubtitlesSubtitleDownloader(api = api, ioDispatcher = dispatcher) {
      override suspend fun fetchBytes(url: String): ByteArray = payload
    }

  private fun failingDownloader(
    api: OpenSubtitlesApi,
    failure: Exception,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher
  ): OpenSubtitlesSubtitleDownloader =
    object : OpenSubtitlesSubtitleDownloader(api = api, ioDispatcher = dispatcher) {
      override suspend fun fetchBytes(url: String): ByteArray = throw failure
    }

  private val srtPayload =
    "1\n00:00:01,000 --> 00:00:02,000\nCiao\n".toByteArray()

  private fun subtitle(fileId: Long? = 42L, url: String = "") = Subtitle(
    id = "os-42",
    url = url,
    lang = "it",
    addonName = "OpenSubtitles v3",
    fileId = fileId
  )

  @Test
  fun selezioneConFileIdScaricaFileLocaleEPubblicaReady() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)
    viewModel.onlineSubtitleCacheDirProvider = { tempFolder.root }
    val api = FakeOpenSubtitlesApi()
    viewModel.onlineSubtitleDownloader = fixedDownloader(api, srtPayload, dispatcher)

    viewModel.selectOnlineSubtitle(subtitle(fileId = 4242L))
    testScheduler.advanceUntilIdle()

    val state = viewModel.onlineSubtitleDownload.value
    assertTrue(state is OnlineSubtitleDownloadState.Ready)
    val ready = state as OnlineSubtitleDownloadState.Ready
    val localUrl = ready.localSubtitle.url
    assertTrue("Url locale atteso (file://), trovato: $localUrl", localUrl.startsWith("file://"))

    // Il file locale esiste DAVVERO e contiene il testo del sottotitolo.
    val localPath = localUrl.removePrefix("file://")
    val localFile = java.io.File(localPath)
    assertTrue(localFile.exists())
    assertTrue(localFile.readText().contains("Ciao"))

    // La richiesta al POST /download usa il file_id del risultato selezionato.
    assertEquals(4242L, api.lastRequestedFileId)

    // La traccia locale è presente tra i sottotitoli passabili al player.
    val attached = viewModel.playbackState.value.subtitles.first { it.id == "os-42" }
    assertEquals(localUrl, attached.url)
  }

  @Test
  fun selezioneDiErroreDiReteEsponeErroreERiprovaFunziona() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)
    viewModel.onlineSubtitleCacheDirProvider = { tempFolder.root }
    val api = FakeOpenSubtitlesApi()
    viewModel.onlineSubtitleDownloader = failingDownloader(api, IOException("rete offline"), dispatcher)

    viewModel.selectOnlineSubtitle(subtitle())
    testScheduler.advanceUntilIdle()

    assertTrue(viewModel.onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Error)
    assertEquals(
      OpenSubtitlesDownloadError.NETWORK,
      (viewModel.onlineSubtitleDownload.value as OnlineSubtitleDownloadState.Error).error
    )

    // La rete torna: il retry usa di nuovo il file_id corretto e porta allo stato Ready.
    viewModel.onlineSubtitleDownloader = fixedDownloader(api, srtPayload, dispatcher)
    viewModel.retryOnlineSubtitleDownload()
    testScheduler.advanceUntilIdle()

    assertTrue(viewModel.onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Ready)
  }

  @Test
  fun selezioneConTimeoutEsponeErroreTimeout() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)
    viewModel.onlineSubtitleCacheDirProvider = { tempFolder.root }
    viewModel.onlineSubtitleDownloader =
      failingDownloader(FakeOpenSubtitlesApi(), SocketTimeoutException("troppo lento"), dispatcher)
    viewModel.selectOnlineSubtitle(subtitle())
    testScheduler.advanceUntilIdle()

    assertEquals(
      OpenSubtitlesDownloadError.TIMEOUT,
      (viewModel.onlineSubtitleDownload.value as OnlineSubtitleDownloadState.Error).error
    )
  }

  @Test
  fun risultatoApiSenzaLinkEsponeErroreNoLinkSenzaScrivereFile() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)
    val cacheDir = tempFolder.newFolder("cache")
    viewModel.onlineSubtitleCacheDirProvider = { cacheDir }
    viewModel.onlineSubtitleDownloader =
      fixedDownloader(FakeOpenSubtitlesApi(link = null), srtPayload, dispatcher)

    viewModel.selectOnlineSubtitle(subtitle(fileId = 11L))
    testScheduler.advanceUntilIdle()

    assertEquals(
      OpenSubtitlesDownloadError.NO_DOWNLOAD_LINK,
      (viewModel.onlineSubtitleDownload.value as OnlineSubtitleDownloadState.Error).error
    )
    // Nessun file scritto: la risposta JSON non è mai stata trattata come file.
    assertTrue(cacheDir.listFiles().isNullOrEmpty())
  }

  @Test
  fun selezioneSenzaFileIdESenzaUrlNonAvviaIlDownload() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)
    viewModel.onlineSubtitleCacheDirProvider = { tempFolder.root }
    var fetchCalls = 0
    val downloader = object : OpenSubtitlesSubtitleDownloader() {
      override suspend fun fetchBytes(url: String): ByteArray {
        fetchCalls++
        return srtPayload
      }
    }
    viewModel.onlineSubtitleDownloader = downloader

    viewModel.selectOnlineSubtitle(subtitle(fileId = null, url = ""))
    testScheduler.advanceUntilIdle()

    assertEquals(0, fetchCalls)
    assertTrue(viewModel.onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Idle)
  }

  @Test
  fun nuovoCaricamentoDellaSezioneAzzeraLoStatoDiDownload() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = StreamNovaViewModel(ioDispatcher = dispatcher)
    viewModel.onlineSubtitleCacheDirProvider = { tempFolder.root }
    viewModel.onlineSubtitleDownloader = fixedDownloader(FakeOpenSubtitlesApi(), srtPayload, dispatcher)
    viewModel.openSubtitlesProvider = object : com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: com.example.data.model.Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ): List<Subtitle> = emptyList()
    }

    viewModel.selectOnlineSubtitle(subtitle())
    testScheduler.advanceUntilIdle()
    assertTrue(viewModel.onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Ready)

    viewModel.loadOnlineSubtitles(
      mediaItem = MediaItem(id = "tt123", title = "T", synopsis = "", type = MediaType.FILM, videoUrl = "")
    )
    testScheduler.advanceUntilIdle()

    assertTrue(viewModel.onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Idle)
  }
}
