package com.example.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.domain.model.Subtitle
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Persistenza reale delle selezioni OpenSubtitles su DataStore.
 *
 * Copre i requisiti fondamentali:
 * - associazione univoca film / singolo episodio;
 * - sopravvivenza a un riavvio (cache in memoria svuotata e rilettura da disco);
 * - rimozione della selezione quando l'utente sceglie nativo/"Nessuno";
 * - file locale riutilizzato solo quando esiste davvero.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PersistentSubtitleRepositoryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var context: Context

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
    title = "Episodio $season-$number",
    synopsis = "",
    durationMinutes = 45,
    videoUrl = ""
  )

  private fun subtitle(fileId: Long = 4242L, lang: String = "it") = Subtitle(
    id = "os-$fileId",
    url = "",
    lang = lang,
    addonName = "OpenSubtitles v3",
    label = "Italiano",
    fileId = fileId
  )

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    PersistentSubtitleRepository.init(context)
  }

  @Test
  fun directoryPermanenteEDentroFilesDir() {
    val expected = File(context.filesDir, "online_subtitles").absolutePath
    assertEquals(expected, PersistentSubtitleRepository.subtitlesDir()?.absolutePath)
  }

  @Test
  fun salvaERecuperaLaSelezioneDelFilm() = runBlocking {
    val media = media("tmdb:550")
    val file = tempFolder.newFile("movie.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nCiao\n") }

    PersistentSubtitleRepository.saveSelectionAndAwait(media, null, subtitle(), file)
    PersistentSubtitleRepository.awaitPendingWritesForTests()

    val stored = PersistentSubtitleRepository.getSelection(media, null)
    assertNotNull(stored)
    assertEquals(4242L, stored!!.fileId)
    assertEquals("it", stored.lang)
    assertEquals(file.absolutePath, stored.localFilePath)
    assertEquals("tmdb:550", stored.contentId)
  }

  @Test
  fun selezioneSopravviveAlRiavvioDellApp() = runBlocking {
    val media = media("tmdb:601")
    val file = tempFolder.newFile("restart.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nCiao\n") }

    PersistentSubtitleRepository.saveSelectionAndAwait(media, null, subtitle(fileId = 601L), file)
    PersistentSubtitleRepository.awaitPendingWritesForTests()

    // Simula il riavvio: la cache in memoria è vuota, il dato deve arrivare dal disco.
    PersistentSubtitleRepository.resetInMemoryForTests()
    assertNull(PersistentSubtitleRepository.getSelection(media, null))

    val restored = PersistentSubtitleRepository.getSelectionAwait(media, null)
    assertNotNull(restored)
    assertEquals(601L, restored!!.fileId)
    assertNotNull(PersistentSubtitleRepository.getLocalFile(restored))
  }

  @Test
  fun episodiDiversiHannoSelezioniIndipendenti() = runBlocking {
    val series = media("tt0944947")
    val ep1 = episode(1, 1)
    val ep2 = episode(1, 2)
    val file1 = tempFolder.newFile("s1e1.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nUno\n") }
    val file2 = tempFolder.newFile("s1e2.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nDue\n") }

    PersistentSubtitleRepository.saveSelectionAndAwait(series, ep1, subtitle(fileId = 101L, lang = "it"), file1)
    PersistentSubtitleRepository.saveSelectionAndAwait(series, ep2, subtitle(fileId = 202L, lang = "en"), file2)
    PersistentSubtitleRepository.awaitPendingWritesForTests()

    val stored1 = PersistentSubtitleRepository.getSelection(series, ep1)
    val stored2 = PersistentSubtitleRepository.getSelection(series, ep2)
    assertEquals(101L, stored1!!.fileId)
    assertEquals(202L, stored2!!.fileId)
    assertEquals("it", stored1.lang)
    assertEquals("en", stored2.lang)
  }

  @Test
  fun selezioneDiUnEpisodioNonCompareSulFilmOSuUnAltroEpisodio() = runBlocking {
    val series = media("tt0903747")
    val ep1 = episode(2, 3)
    val ep2 = episode(2, 4)
    val file = tempFolder.newFile("only-ep.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nX\n") }

    PersistentSubtitleRepository.saveSelectionAndAwait(series, ep1, subtitle(fileId = 303L), file)
    PersistentSubtitleRepository.awaitPendingWritesForTests()

    assertNotNull(PersistentSubtitleRepository.getSelection(series, ep1))
    assertNull(PersistentSubtitleRepository.getSelection(series, ep2))
    assertNull(PersistentSubtitleRepository.getSelection(series, null))
  }

  @Test
  fun clearSelectionRimuoveSoloIlContenutoIndicato() = runBlocking {
    val series = media("tt0108778")
    val ep1 = episode(1, 1)
    val ep2 = episode(1, 2)
    val file = tempFolder.newFile("clear.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nX\n") }

    PersistentSubtitleRepository.saveSelectionAndAwait(series, ep1, subtitle(fileId = 11L), file)
    PersistentSubtitleRepository.saveSelectionAndAwait(series, ep2, subtitle(fileId = 22L), file)
    PersistentSubtitleRepository.awaitPendingWritesForTests()

    PersistentSubtitleRepository.clearSelectionAndAwait(series, ep1)
    PersistentSubtitleRepository.awaitPendingWritesForTests()

    assertNull(PersistentSubtitleRepository.getSelection(series, ep1))
    assertNotNull(PersistentSubtitleRepository.getSelection(series, ep2))

    PersistentSubtitleRepository.resetInMemoryForTests()
    assertNull(PersistentSubtitleRepository.getSelectionAwait(series, ep1))
    assertEquals(22L, PersistentSubtitleRepository.getSelectionAwait(series, ep2)!!.fileId)
  }

  @Test
  fun fileLocaleDisponibileSoloSeEsiste() = runBlocking {
    val media = media("tmdb:777")
    val file = tempFolder.newFile("present.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nX\n") }
    PersistentSubtitleRepository.saveSelectionAndAwait(media, null, subtitle(fileId = 777L), file)
    PersistentSubtitleRepository.awaitPendingWritesForTests()

    val stored = PersistentSubtitleRepository.getSelection(media, null)!!
    assertNotNull(PersistentSubtitleRepository.getLocalFile(stored))
    assertTrue(PersistentSubtitleRepository.isLocalFileAvailable(stored))

    assertTrue(file.delete())
    assertNull(PersistentSubtitleRepository.getLocalFile(stored))
    assertFalse(PersistentSubtitleRepository.isLocalFileAvailable(stored))
  }

  @Test
  fun toSubtitleUsaIlFileLocaleELaStessaIdentitaOpenSubtitles() = runBlocking {
    val media = media("tmdb:888")
    val file = tempFolder.newFile("identity.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nX\n") }
    PersistentSubtitleRepository.saveSelectionAndAwait(media, null, subtitle(fileId = 888L), file)
    PersistentSubtitleRepository.awaitPendingWritesForTests()

    val stored = PersistentSubtitleRepository.getSelection(media, null)!!
    val subtitle = PersistentSubtitleRepository.toSubtitle(stored)

    assertEquals("tmdb:888", subtitle.id)
    assertEquals(888L, subtitle.fileId)
    assertEquals("it", subtitle.lang)
    assertTrue(subtitle.url.startsWith("file://"))
    assertTrue(subtitle.url.endsWith(file.name))
  }

  @Test
  fun sottotitoloSenzaFileIdNonVieneSalvato() = runBlocking {
    val media = media("tmdb:999")
    val file = tempFolder.newFile("nofileid.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nX\n") }

    PersistentSubtitleRepository.saveSelectionAndAwait(
      media,
      null,
      subtitle(fileId = 0L).copy(fileId = null),
      file
    )
    PersistentSubtitleRepository.awaitPendingWritesForTests()

    assertNull(PersistentSubtitleRepository.getSelection(media, null))
  }
}
