package com.example.data.opensubtitles

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.domain.model.Subtitle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioOpenSubtitlesV3Test {

  @Test
  fun parseAndSortStremioSubtitlesOrdinaPerPrioritaItalianoIngleseAltre() {
    val json = """
      {
        "subtitles": [
          {
            "id": "1",
            "url": "https://subs.test/es.srt",
            "lang": "es",
            "subtitleFileName": "spanish.srt"
          },
          {
            "id": "2",
            "url": "https://subs.test/en.srt",
            "lang": "eng",
            "subtitleFileName": "english.srt"
          },
          {
            "id": "3",
            "url": "https://subs.test/it.srt",
            "lang": "ita",
            "subtitleFileName": "italian.srt"
          },
          {
            "id": "4",
            "url": "https://subs.test/fr.vtt",
            "lang": "fr",
            "subtitleFileName": "french.vtt"
          },
          {
            "id": "5",
            "url": "https://subs.test/de.srt",
            "lang": "de",
            "subtitleFileName": "german.srt"
          }
        ]
      }
    """.trimIndent()

    val result = OpenSubtitlesSubtitleProvider.parseAndSortStremioSubtitles(json)

    assertEquals(5, result.size)
    // 1. Italiano in cima
    assertEquals("ita", result[0].lang)
    assertEquals("italian.srt", result[0].label)
    // 2. Inglese al secondo posto
    assertEquals("eng", result[1].lang)
    assertEquals("english.srt", result[1].label)
    // 3. Altre lingue in ordine alfabetico per display language (Deutsch, Español, Français)
    assertEquals("de", result[2].lang)
    assertEquals("es", result[3].lang)
    assertEquals("fr", result[4].lang)
  }

  @Test
  fun parseAndSortScartaElementiSenzaUrlOValoriNonHttp() {
    val json = """
      {
        "subtitles": [
          {
            "id": "1",
            "url": "",
            "lang": "it"
          },
          {
            "id": "2",
            "url": "ftp://invalid/sub.srt",
            "lang": "en"
          },
          {
            "id": "3",
            "url": "https://subs.test/valid.srt",
            "lang": "it",
            "movieReleaseName": "Movie.1080p.Italian"
          }
        ]
      }
    """.trimIndent()

    val result = OpenSubtitlesSubtitleProvider.parseAndSortStremioSubtitles(json)

    assertEquals(1, result.size)
    assertEquals("https://subs.test/valid.srt", result[0].url)
    assertEquals("Movie.1080p.Italian", result[0].label)
    assertEquals("OpenSubtitles v3", result[0].addonName)
  }

  @Test
  fun parseAndSortGestisceJsonMalformatoOSenzaSubtitlesSenzaLanciareEccezioni() {
    val empty = OpenSubtitlesSubtitleProvider.parseAndSortStremioSubtitles("")
    assertTrue(empty.isEmpty())

    val malformed = OpenSubtitlesSubtitleProvider.parseAndSortStremioSubtitles("{ invalid json }")
    assertTrue(malformed.isEmpty())

    val emptyList = OpenSubtitlesSubtitleProvider.parseAndSortStremioSubtitles("""{"subtitles": []}""")
    assertTrue(emptyList.isEmpty())
  }

  @Test
  fun fetchFromStremioOpenSubtitlesCostruisceParametriMovieESeriesCorretti() = runBlocking {
    var recordedType: String? = null
    var recordedId: String? = null

    val provider = object : OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchFromStremioOpenSubtitles(type: String, id: String): List<Subtitle> {
        recordedType = type
        recordedId = id
        return listOf(
          Subtitle(id = "os-1", url = "https://subs.test/test.srt", lang = "it", addonName = "OpenSubtitles v3")
        )
      }
    }

    val movie = MediaItem(
      id = "tt0944947",
      title = "Film Test",
      synopsis = "Sinossi",
      type = MediaType.FILM,
      videoUrl = ""
    )
    val subsMovie = provider.fetchFromStremioOpenSubtitles(movie)

    assertEquals(1, subsMovie.size)
    assertEquals("movie", recordedType)
    assertEquals("tt0944947", recordedId)

    val series = MediaItem(
      id = "tt0944947",
      title = "GoT",
      synopsis = "Sinossi",
      type = MediaType.SERIE_TV,
      videoUrl = ""
    )
    val episode = Episode(
      id = "tt0944947:2:5",
      seasonNumber = 2,
      episodeNumber = 5,
      title = "Ep 5",
      synopsis = "",
      durationMinutes = 55,
      videoUrl = ""
    )
    val subsSeries = provider.fetchFromStremioOpenSubtitles(series, episode)

    assertEquals(1, subsSeries.size)
    assertEquals("series", recordedType)
    assertEquals("tt0944947:2:5", recordedId)
  }
}
