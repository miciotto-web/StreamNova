package com.example.ui.viewmodel

import com.example.data.prefs.AppSettingsRepository
import com.example.data.streaming.StreamSource
import com.example.domain.model.Subtitle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M9: passaggio all'episodio successivo DENTRO lo stesso torrent di stagione.
 *
 * Il torrent resta lo stesso (e quindi `infoHash`/`addonName` vanno conservati), ma il
 * FILE è un altro episodio: i sottotitoli allegati alla sorgente precedente e il
 * `fileIdx` dichiarato dall'addon non valgono più e non devono essere trascinati.
 */
class NextEpisodeSameTorrentResetTest {

  private companion object {
    const val INFO_HASH = "1234abcd1234abcd1234abcd1234abcd1234abcd"
  }

  @After
  fun tearDown() {
    AppSettingsRepository.setTorBoxApiKey(null)
    AppSettingsRepository.setTorBoxInstantDebridEnabled(true)
  }

  @Test
  fun sameTorrentEpisodeSwitchResetsSubtitlesAndFileIdxButKeepsTorrentIdentity() = runTest {
    AppSettingsRepository.setTorBoxInstantDebridEnabled(true)
    AppSettingsRepository.setTorBoxApiKey("fake-debrid-key")

    val pinned = StreamSource(
      streamUrl = "https://torbox-cdn.example/show/s1e1.mkv",
      quality = "1080p",
      serverName = "Torrentio",
      addonName = "Torrentio",
      infoHash = INFO_HASH,
      fileIdx = 7,
      isCached = true,
      subtitles = listOf(
        Subtitle(
          id = "s1e1-it",
          url = "https://subtitles.example/s1e1.srt",
          lang = "it",
          addonName = "OpenSubtitles v3"
        )
      )
    )

    val next = tryResolveNextEpisodeFromSameTorrent(
      pinned = pinned,
      season = 1,
      episode = 2,
      seasonPackUrlResolver = { hash, season, episode ->
        assertEquals(INFO_HASH, hash)
        assertEquals(1, season)
        assertEquals(2, episode)
        "https://torbox-cdn.example/show/s1e2.mkv"
      }
    )

    assertNotNull("il file del nuovo episodio deve essere risolto", next)
    assertEquals("https://torbox-cdn.example/show/s1e2.mkv", next!!.streamUrl)
    assertEquals("lo stesso torrent resta riutilizzabile", INFO_HASH, next.infoHash)
    assertEquals("l'addon di origine resta noto", "Torrentio", next.addonName)
    assertNull("fileIdx dell'episodio precedente non vale per il nuovo", next.fileIdx)
    assertTrue("i sottotitoli dell'episodio precedente non vanno trascinati", next.subtitles.isEmpty())

    // La sorgente di partenza non viene mutata: la copia è quella del nuovo episodio.
    assertEquals(7, pinned.fileIdx)
    assertEquals(1, pinned.subtitles.size)
  }
}
