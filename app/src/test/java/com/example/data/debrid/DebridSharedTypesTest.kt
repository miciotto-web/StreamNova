package com.example.data.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tipi condivisi dai servizi Debrid accostati a TorBox:
 *  - [DebridService.fromStorage]: persistenza robusta (valori sconosciuti → TORBOX);
 *  - [DebridEpisodeMatcher]: selezione dell'episodio dentro un torrent di stagione
 *    (stessi pattern usati dal repository TorBox storico).
 */
class DebridSharedTypesTest {

  @Test
  fun `fromStorage legge i valori persistiti in modo case-insensitive`() {
    assertEquals(DebridService.TORBOX, DebridService.fromStorage("torbox"))
    assertEquals(DebridService.REALDEBRID, DebridService.fromStorage("realdebrid"))
    assertEquals(DebridService.PREMIUMIZE, DebridService.fromStorage("premiumize"))
    assertEquals(DebridService.REALDEBRID, DebridService.fromStorage(" RealDebrid "))
  }

  @Test
  fun `fromStorage ricade su TORBOX per valori assenti o sconosciuti`() {
    assertEquals(DebridService.TORBOX, DebridService.fromStorage(null))
    assertEquals(DebridService.TORBOX, DebridService.fromStorage(""))
    assertEquals(DebridService.TORBOX, DebridService.fromStorage("alldebrid"))
  }

  @Test
  fun `storage roundtrip per ogni servizio`() {
    DebridService.entries.forEach { service ->
      assertEquals(service, DebridService.fromStorage(service.storage))
    }
  }

  private fun file(name: String, size: Long) = DebridFile(name = name, size = size)

  private fun seasonPack(): List<DebridFile> = listOf(
    file("Show.S01E01.1080p.ITA-ENG.mkv", 4_000_000_000L),
    file("Show.S01E02.1080p.ITA-ENG.mkv", 3_000_000_000L),
    file("Show.S01E03.1080p.ITA-ENG.mkv", 2_000_000_000L)
  )

  @Test
  fun `findEpisodeFile seleziona il file dell'episodio richiesto`() {
    val matched = DebridEpisodeMatcher.findEpisodeFile(seasonPack(), season = 1, episode = 2)
    assertEquals("Show.S01E02.1080p.ITA-ENG.mkv", matched?.name)
  }

  @Test
  fun `findEpisodeFile preferisce la variante con piu' token della release corrente`() {
    val files = listOf(
      file("Show.S01E02.720p.x264.mkv", 1_000_000_000L),
      file("Show.S01E02.1080p.HEVC.GROUP.mkv", 3_000_000_000L)
    )
    val matched = DebridEpisodeMatcher.findEpisodeFile(
      files, season = 1, episode = 2, preferTokens = listOf("1080p", "HEVC")
    )
    assertEquals("Show.S01E02.1080p.HEVC.GROUP.mkv", matched?.name)
  }

  @Test
  fun `findEpisodeFile restituisce null quando l'episodio non e' nel pack`() {
    assertNull(DebridEpisodeMatcher.findEpisodeFile(seasonPack(), season = 1, episode = 9))
  }

  @Test
  fun `selectBestVideo sceglie il video piu' grande ignorando sample e sottotitoli`() {
    val files = listOf(
      file("Show.S01E01.1080p.mkv", 3_000_000_000L),
      file("Sample.mkv", 9_000_000_000L),
      file("Show.S01E01.srt", 500_000L)
    )
    assertEquals("Show.S01E01.1080p.mkv", DebridEpisodeMatcher.selectBestVideo(files)?.name)
  }
}
