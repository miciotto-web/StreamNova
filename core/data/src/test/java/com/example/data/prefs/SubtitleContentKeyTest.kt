package com.example.data.prefs

import com.example.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifica che ogni film/episodio abbia una chiave indipendente e stabile. */
class SubtitleContentKeyTest {

  private fun episode(season: Int, number: Int) = Episode(
    id = "ep-$season-$number",
    seasonNumber = season,
    episodeNumber = number,
    title = "Episodio",
    synopsis = "",
    durationMinutes = 45,
    videoUrl = ""
  )

  @Test
  fun filmUsaIdDelMedia() {
    assertEquals("tmdb:550", SubtitleContentKey.forMovie("tmdb:550"))
  }

  @Test
  fun episodiDiversiHannoChiaviDiverse() {
    val s1e1 = SubtitleContentKey.forEpisode("tt0944947", 1, 1)
    val s1e2 = SubtitleContentKey.forEpisode("tt0944947", 1, 2)
    val s2e1 = SubtitleContentKey.forEpisode("tt0944947", 2, 1)

    assertNotEquals(s1e1, s1e2)
    assertNotEquals(s1e1, s2e1)
    assertNotEquals(s1e2, s2e1)
  }

  @Test
  fun stessaSerieStagioneEpisodioProduceSempreLaStessaChiave() {
    assertEquals(
      SubtitleContentKey.forEpisode("tt0944947", 3, 7),
      SubtitleContentKey.forEpisode("tt0944947", 3, 7)
    )
  }

  @Test
  fun chiaveEpisodioNonCollideConFilmDellaStessaSerie() {
    assertNotEquals(
      SubtitleContentKey.forMovie("tt0944947"),
      SubtitleContentKey.forEpisode("tt0944947", 1, 1)
    )
  }

  @Test
  fun forContentSceglieFilmOEpisodio() {
    assertEquals(
      "tmdb:550",
      SubtitleContentKey.forContent("tmdb:550", null)
    )
    assertEquals(
      "tt0944947::season:2:episode:4",
      SubtitleContentKey.forContent("tt0944947", episode(2, 4))
    )
  }

  @Test
  fun estraeMediaIdENumeriEpisodio() {
    val key = "tt0944947::season:2:episode:4"

    assertEquals("tt0944947", SubtitleContentKey.extractMediaId(key))
    assertEquals(2 to 4, SubtitleContentKey.extractEpisodeNumbers(key))
    assertEquals("tmdb:550", SubtitleContentKey.extractMediaId("tmdb:550"))
    assertNull(SubtitleContentKey.extractEpisodeNumbers("tmdb:550"))
  }
}
