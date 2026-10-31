package com.example.data.api

import com.example.data.local.MediaCacheMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il badge "Prossima puntata" dipende INTERAMENTE dal campo `next_episode_to_air` della
 * risposta `tv/{id}` di TMDB. Questi test bloccano il contratto JSON e il round-trip della
 * cache dettagli (`tmdb_response_cache`), così un campo rinominato o non serializzato non
 * può far sparire il badge senza far fallire la build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TmdbNextEpisodeParsingTest {

  private val adapter = TmdbApiClient.moshi.adapter(TmdbTvDetailDto::class.java)

  private val tvDetailsJson = """
    {
      "id": 1399,
      "name": "Il Trono di Spade",
      "original_name": "Game of Thrones",
      "overview": "Nobili famiglie si contendono il Trono di Spade.",
      "first_air_date": "2011-04-17",
      "number_of_seasons": 8,
      "number_of_episodes": 73,
      "status": "Returning Series",
      "next_episode_to_air": {
        "id": 6305600,
        "name": "Winter Is Coming",
        "overview": "",
        "vote_average": 8.1,
        "vote_count": 120,
        "air_date": "2026-11-12",
        "episode_number": 5,
        "episode_type": "standard",
        "production_code": "",
        "runtime": 55,
        "season_number": 2,
        "show_id": 1399,
        "still_path": null
      }
    }
  """.trimIndent()

  @Test
  fun `next episode is parsed from tv details`() {
    val dto = adapter.fromJson(tvDetailsJson)

    assertNotNull(dto)
    assertEquals("Returning Series", dto!!.status)
    val next = dto.nextEpisodeToAir
    assertNotNull(next)
    assertEquals(2, next!!.seasonNumber)
    assertEquals(5, next.episodeNumber)
    assertEquals("2026-11-12", next.airDate)
  }

  @Test
  fun `missing next episode stays null`() {
    val dto = adapter.fromJson("""{"id": 1399, "name": "Serie", "status": "Ended"}""")

    assertNotNull(dto)
    assertNull(dto!!.nextEpisodeToAir)
  }

  @Test
  fun `next episode without air date stays null`() {
    val dto = adapter.fromJson(
      """
      {
        "id": 1399,
        "name": "Serie",
        "status": "Returning Series",
        "next_episode_to_air": {
          "id": 1,
          "name": "Episodio",
          "episode_number": 5,
          "season_number": 2,
          "air_date": null
        }
      }
      """.trimIndent()
    )

    assertNotNull(dto)
    assertNotNull(dto!!.nextEpisodeToAir)
    assertNull(dto.nextEpisodeToAir!!.airDate)
  }

  @Test
  fun `tv details cache round trip preserves next episode`() {
    val dto = adapter.fromJson(tvDetailsJson)!!

    val cached = MediaCacheMapper.serializeTvDetail(dto)
    val restored = MediaCacheMapper.deserializeTvDetail(cached)

    assertNotNull(restored)
    assertEquals("Returning Series", restored!!.status)
    assertEquals(2, restored.nextEpisodeToAir?.seasonNumber)
    assertEquals(5, restored.nextEpisodeToAir?.episodeNumber)
    assertEquals("2026-11-12", restored.nextEpisodeToAir?.airDate)
  }

  @Test
  fun `legacy cached tv details without the field are still readable`() {
    // Payload scritto dalle versioni precedenti dell'app: nessun `next_episode_to_air`.
    val legacy = MediaCacheMapper.deserializeTvDetail(
      """{"id": 1399, "name": "Serie", "status": "Returning Series", "number_of_seasons": 3}"""
    )

    assertNotNull(legacy)
    assertNull(legacy!!.nextEpisodeToAir)
  }
}
