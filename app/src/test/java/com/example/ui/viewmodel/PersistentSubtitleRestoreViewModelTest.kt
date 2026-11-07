package com.example.ui.viewmodel

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.opensubtitles.OpenSubtitlesApi
import com.example.data.opensubtitles.OpenSubtitlesDownloadError
import com.example.data.opensubtitles.OpenSubtitlesDownloadRequest
import com.example.data.opensubtitles.OpenSubtitlesDownloadResponse
import com.example.data.opensubtitles.OpenSubtitlesSearchResponse
import com.example.data.opensubtitles.OpenSubtitlesSubtitleDownloader
import com.example.data.prefs.PersistentSubtitleSelection
import com.example.data.prefs.PersistentSubtitleStore
import com.example.data.prefs.SubtitleContentKey
import com.example.domain.model.Subtitle
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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

/**
 * Ripristino automatico del sottotitolo OpenSubtitles persistente.
 *
 * Verifica il contratto approvato:
 * - file locale valido → traccia pronta per il player, nessun download;
 * - file mancante/corrotto → re-download tramite lo stesso `fileId`;
 * - download non recuperabile → avviso non bloccante, nessuna traccia alternativa;
 * - episodi diversi → selezioni indipendenti;
 * - nessuna selezione → nessun avviso.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersistentSubtitleRestoreViewModelTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val testDispatcher = StandardTestDispatcher()

  private val srtPayload = "1\n00:00:01,000 --> 00:00:02,000\nCiao\n".toByteArray()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private class FakeApi(private val link: String? = "https://sub.test/temp.srt") : OpenSubtitlesApi {
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
      return if (link == null) {
        Response.error(404, "".toResponseBody("application/json".toMediaType()))
      } else {
        Response.success(OpenSubtitlesDownloadResponse(link = link))
      }
    }
  }

  private fun fixedDownloader(
    api: OpenSubtitlesApi,
    payload: ByteArray = srtPayload
  ): OpenSubtitlesSubtitleDownloader =
    object : OpenSubtitlesSubtitleDownloader(api = api, ioDispatcher = testDispatcher) {
      override suspend fun fetchBytes(url: String): ByteArray = payload
    }

  private fun failingDownloader(api: OpenSubtitlesApi): OpenSubtitlesSubtitleDownloader =
    object : OpenSubtitlesSubtitleDownloader(api = api, ioDispatcher = testDispatcher) {
      override suspend fun fetchBytes(url: String): ByteArray = throw IOException("rete offline")
    }

  private class FakeStore(
    initial: List<PersistentSubtitleSelection> = emptyList()
  ) : PersistentSubtitleStore {

    private val selections = initial.associateBy { it.contentId }.toMutableMap()

    override fun getSelection(mediaItem: MediaItem, episode: Episode?): PersistentSubtitleSelection? =
      selections[SubtitleContentKey.forContent(mediaItem.id, episode)]

    override suspend fun getSelectionAwait(
      mediaItem: MediaItem,
      episode: Episode?
    ): PersistentSubtitleSelection? = getSelection(mediaItem, episode)

    override fun saveSelection(
      mediaItem: MediaItem,
      episode: Episode?,
      subtitle: Subtitle,
      localFile: File?
    ) {
      val fileId = subtitle.fileId ?: return
      val key = SubtitleContentKey.forContent(mediaItem.id, episode)
      selections[key] = PersistentSubtitleSelection(
        contentId = key,
        fileId = fileId,
        lang = subtitle.lang,
        label = subtitle.label,
        localFilePath = localFile?.absolutePath,
        timestamp = 1L
      )
    }

    override fun clearSelection(mediaItem: MediaItem, episode: Episode?) {
      selections.remove(SubtitleContentKey.forContent(mediaItem.id, episode))
    }

    override fun getLocalFile(selection: PersistentSubtitleSelection): File? =
      selection.localFilePath?.let { path -> File(path).takeIf { it.exists() } }

    override fun toSubtitle(selection: PersistentSubtitleSelection): Subtitle =
      Subtitle(
        id = selection.contentId,
        url = getLocalFile(selection)?.let { "file://${it.absolutePath}" } ?: "",
        lang = selection.lang,
        addonName = "OpenSubtitles",
        label = selection.label,
        fileId = selection.fileId
      )

    override fun subtitlesDir(): File? = null
  }

  private fun media(id: String = "tmdb:550") = MediaItem(
    id = id,
    title = "Fight Club",
    synopsis = "",
    videoUrl = ""
  )

  private fun episode(season: Int, number: Int) = Episode(
    id = "ep-$season-$number",
    seasonNumber = season,
    episodeNumber = number,
    title = "Episodio",
    synopsis = "",
    durationMinutes = 45,
    videoUrl = ""
  )

  private fun selection(
    mediaId: String,
    episode: Episode? = null,
    fileId: Long = 4242L,
    lang: String = "it",
    localFilePath: String?
  ) = PersistentSubtitleSelection(
    contentId = SubtitleContentKey.forContent(mediaId, episode),
    fileId = fileId,
    lang = lang,
    label = "Italiano",
    localFilePath = localFilePath,
    timestamp = 1L
  )

  private fun validSubtitleFile(name: String = "sub.srt"): File =
    tempFolder.newFile(name).apply { writeText(String(srtPayload)) }

  private fun viewModel(
    store: FakeStore,
    downloader: OpenSubtitlesSubtitleDownloader
  ) = StreamNovaViewModel(ioDispatcher = testDispatcher).apply {
    persistentSubtitleStore = store
    onlineSubtitleStorageDirProvider = { tempFolder.root }
    onlineSubtitleDownloader = downloader
  }

  @Test
  fun fileLocaleValidoVieneEspostoAlPlayerSenzaDownload() = runTest {
    val movie = media()
    val file = validSubtitleFile()
    val store = FakeStore(listOf(selection("tmdb:550", localFilePath = file.absolutePath)))
    val api = FakeApi()
    val viewModel = viewModel(store, fixedDownloader(api))

    val restored = viewModel.tryRestorePersistentSubtitle(movie, null)

    assertTrue(restored)
    val subtitle = viewModel.restoredPersistentSubtitle.value
    assertNotNull(subtitle)
    assertEquals(4242L, subtitle!!.fileId)
    assertTrue(subtitle.url.startsWith("file://"))
    assertTrue(subtitle.url.endsWith(file.name))
    assertNull(api.lastRequestedFileId)
    assertFalse(viewModel.persistentSubtitleRestoreFailed.value)
  }

  @Test
  fun fileMancanteAvviaIlReDownloadConLoStessoFileId() = runTest {
    val movie = media("tmdb:601")
    val store = FakeStore(
      listOf(selection("tmdb:601", fileId = 98765L, localFilePath = null))
    )
    val api = FakeApi()
    val viewModel = viewModel(store, fixedDownloader(api))

    val restored = viewModel.tryRestorePersistentSubtitle(movie, null)
    advanceUntilIdle()

    assertTrue(restored)
    assertNull("Nessun file locale da allegare: la traccia arriva dal download", viewModel.restoredPersistentSubtitle.value)
    assertEquals(98765L, api.lastRequestedFileId)
    val state = viewModel.onlineSubtitleDownload.value
    assertTrue("Download atteso Ready, trovato $state", state is OnlineSubtitleDownloadState.Ready)
    val localUrl = (state as OnlineSubtitleDownloadState.Ready).localSubtitle.url
    assertTrue(localUrl.startsWith("file://"))
    assertTrue(File(localUrl.removePrefix("file://")).exists())
  }

  @Test
  fun fileLocaleCorrottoVieneSostituitoTramiteReDownload() = runTest {
    val movie = media("tmdb:602")
    val corrupt = tempFolder.newFile("corrotto.srt").apply {
      writeText("<!doctype html><html><body>errore</body></html>")
    }
    val store = FakeStore(
      listOf(selection("tmdb:602", fileId = 602L, localFilePath = corrupt.absolutePath))
    )
    val api = FakeApi()
    val viewModel = viewModel(store, fixedDownloader(api))

    assertTrue(viewModel.tryRestorePersistentSubtitle(movie, null))
    advanceUntilIdle()

    assertEquals(602L, api.lastRequestedFileId)
    assertTrue(viewModel.onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Ready)
    assertNull(viewModel.restoredPersistentSubtitle.value)
  }

  @Test
  fun downloadFallitoMostraAvvisoNonBloccante() = runTest {
    val movie = media("tmdb:603")
    val store = FakeStore(
      listOf(selection("tmdb:603", fileId = 603L, localFilePath = null))
    )
    val viewModel = viewModel(store, failingDownloader(FakeApi()))

    assertTrue(viewModel.tryRestorePersistentSubtitle(movie, null))
    advanceUntilIdle()

    assertTrue(viewModel.persistentSubtitleRestoreFailed.value)
    val state = viewModel.onlineSubtitleDownload.value
    assertTrue(state is OnlineSubtitleDownloadState.Error)
    assertEquals(
      OpenSubtitlesDownloadError.NETWORK,
      (state as OnlineSubtitleDownloadState.Error).error
    )
    assertNull(viewModel.restoredPersistentSubtitle.value)
  }

  @Test
  fun nessunaSelezioneNonMostraAvviso() = runTest {
    val viewModel = viewModel(FakeStore(), fixedDownloader(FakeApi()))

    val restored = viewModel.tryRestorePersistentSubtitle(media("tmdb:604"), null)

    assertFalse(restored)
    assertFalse(viewModel.persistentSubtitleRestoreFailed.value)
    assertNull(viewModel.restoredPersistentSubtitle.value)
  }

  @Test
  fun selezioniDiEpisodiDiversiRestanoIndipendenti() = runTest {
    val series = media("tt0944947")
    val ep1 = episode(1, 1)
    val ep2 = episode(1, 2)
    val file1 = validSubtitleFile("ep1.srt")
    val file2 = validSubtitleFile("ep2.srt")
    val store = FakeStore(
      listOf(
        selection("tt0944947", ep1, fileId = 101L, lang = "it", localFilePath = file1.absolutePath),
        selection("tt0944947", ep2, fileId = 202L, lang = "en", localFilePath = file2.absolutePath)
      )
    )
    val viewModel = viewModel(store, fixedDownloader(FakeApi()))

    assertTrue(viewModel.tryRestorePersistentSubtitle(series, ep1))
    val subtitle1 = viewModel.restoredPersistentSubtitle.value!!
    assertEquals(101L, subtitle1.fileId)
    assertTrue(subtitle1.url.endsWith(file1.name))

    assertTrue(viewModel.tryRestorePersistentSubtitle(series, ep2))
    val subtitle2 = viewModel.restoredPersistentSubtitle.value!!
    assertEquals(202L, subtitle2.fileId)
    assertEquals("en", subtitle2.lang)
    assertTrue(subtitle2.url.endsWith(file2.name))
  }

  @Test
  fun clearPersistentSubtitleForContentRimuoveSoloLaSelezioneCorrente() = runTest {
    val series = media("tt0108778")
    val ep1 = episode(1, 1)
    val ep2 = episode(1, 2)
    val file = validSubtitleFile("clear.srt")
    val store = FakeStore(
      listOf(
        selection("tt0108778", ep1, fileId = 11L, localFilePath = file.absolutePath),
        selection("tt0108778", ep2, fileId = 22L, localFilePath = file.absolutePath)
      )
    )
    val viewModel = viewModel(store, fixedDownloader(FakeApi()))

    viewModel.clearPersistentSubtitleForContent(series, ep1)

    assertNull(store.getSelection(series, ep1))
    assertNotNull(store.getSelection(series, ep2))
    assertTrue(viewModel.onlineSubtitleDownload.value is OnlineSubtitleDownloadState.Idle)
  }

  @Test
  fun persistOnlineSubtitleSelectionSalvaIlFileLocale() = runTest {
    val movie = media("tmdb:605")
    val file = validSubtitleFile("saved.srt")
    val store = FakeStore()
    val viewModel = viewModel(store, fixedDownloader(FakeApi()))

    viewModel.persistOnlineSubtitleSelection(
      media = movie,
      episode = null,
      subtitle = Subtitle(
        id = "os-605",
        url = "file://${file.absolutePath}",
        lang = "it",
        addonName = "OpenSubtitles v3",
        label = "Italiano",
        fileId = 605L
      ),
      localFile = file
    )

    val stored = store.getSelection(movie, null)
    assertNotNull(stored)
    assertEquals(605L, stored!!.fileId)
    assertEquals(file.absolutePath, stored.localFilePath)
  }
}
