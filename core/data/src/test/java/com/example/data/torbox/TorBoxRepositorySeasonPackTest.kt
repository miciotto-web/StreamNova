package com.example.data.torbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Test del match "season pack" del bingewatching: [TorBoxRepository.findEpisodeFile]
 * individua il file del nuovo episodio dentro lo stesso torrent di stagione.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TorBoxRepositorySeasonPackTest {

  private fun file(id: Int, name: String) = TorBoxRepository.TorBoxFile(id = id, name = name, size = null)

  @Test
  fun findEpisodeFile_matchesCanonicalSeasonEpisodePattern() {
    val files = listOf(
      file(1, "Show.S01E01.1080p.mkv"),
      file(2, "Show.S01E02.1080p.mkv"),
      file(3, "Show.S01E03.1080p.mkv")
    )

    val ep2 = TorBoxRepository.findEpisodeFile(files, season = 1, episode = 2)
    assertEquals(2, ep2?.id)
  }

  @Test
  fun findEpisodeFile_matchesSingleDigitEpisodeVariants() {
    val files = listOf(file(10, "Show.1x02.720p.mkv"))
    assertEquals(10, TorBoxRepository.findEpisodeFile(files, season = 1, episode = 2)?.id)

    val filesS1e2 = listOf(file(11, "Show.S01E2.WEB-DL.mkv"))
    assertEquals(11, TorBoxRepository.findEpisodeFile(filesS1e2, season = 1, episode = 2)?.id)
  }

  @Test
  fun findEpisodeFile_prefersPinnedQualityTokensWhenMultipleFilesMatch() {
    val files = listOf(
      file(1, "Show.S01E02.720p.WEB-DL.mkv"),
      file(2, "Show.S01E02.1080p.WEB-DL.mkv")
    )

    val ep = TorBoxRepository.findEpisodeFile(
      files = files,
      season = 1,
      episode = 2,
      preferTokens = listOf("1080p", "hevc")
    )
    assertEquals("1080p must win over 720p for the same episode", 2, ep?.id)
  }

  @Test
  fun findEpisodeFile_fallsBackToFirstMatchWithoutTokens() {
    val files = listOf(
      file(1, "Show.S01E02.720p.WEB-DL.mkv"),
      file(2, "Show.S01E02.1080p.WEB-DL.mkv")
    )

    val ep = TorBoxRepository.findEpisodeFile(files, season = 1, episode = 2)
    assertEquals("Without tokens the first match is deterministic", 1, ep?.id)
  }

  @Test
  fun findEpisodeFile_returnsNullWhenEpisodeNotInPack() {
    val files = listOf(
      file(1, "Show.S01E01.1080p.mkv"),
      file(2, "Show.S01E03.1080p.mkv"),
      file(3, "Show.S02E02.1080p.mkv")
    )

    assertNull(TorBoxRepository.findEpisodeFile(files, season = 1, episode = 2))
  }
}
