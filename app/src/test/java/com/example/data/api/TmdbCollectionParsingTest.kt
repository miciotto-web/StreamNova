package com.example.data.api

import com.example.data.local.MediaCacheMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La sezione «Franchise - Collezione» della scheda Dettaglio dipende dal campo
 * `belongs_to_collection` della risposta `movie/{id}` e dall'endpoint `collection/{id}`
 * (`parts`). Questi test bloccano il contratto JSON e il round-trip della cache
 * dettagli/collezioni (`tmdb_response_cache`): un campo rinominato o non serializzato
 * non può far sparire la sezione senza far fallire la build. Coprono anche la
 * retrocompatibilità con le voci di cache scritte prima dell'aggiornamento.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TmdbCollectionParsingTest {

  private val movieAdapter = TmdbApiClient.moshi.adapter(TmdbMovieDetailDto::class.java)
  private val collectionAdapter = TmdbApiClient.moshi.adapter(TmdbCollectionDetailDto::class.java)

  private val movieDetailsJson = """
    {
      "id": 671,
      "title": "Harry Potter e la pietra filosofale",
      "original_title": "Harry Potter and the Philosopher's Stone",
      "overview": "Ritaglio magico entra a Hogwarts.",
      "release_date": "2001-11-16",
      "belongs_to_collection": {
        "id": 1241,
        "name": "Harry Potter Collection",
        "poster_path": "/1JywyC2CLQkrIwpFsEQKj27SjA9.jpg",
        "backdrop_path": "/31G9V7nF1v9dCJGclmYqU6AvqYg.jpg"
      }
    }
  """.trimIndent()

  private val collectionDetailJson = """
    {
      "id": 1241,
      "name": "Harry Potter Collection",
      "overview": "Il mondo magico di Hogwarts.",
      "parts": [
        {
          "id": 671,
          "title": "Harry Potter e la pietra filosofale",
          "original_title": "Harry Potter and the Philosopher's Stone",
          "overview": "Primo film.",
          "backdrop_path": "/backdrop1.jpg",
          "poster_path": "/poster1.jpg",
          "release_date": "2001-11-16",
          "vote_average": 7.6,
          "genre_ids": [14]
        },
        {
          "id": 672,
          "title": "Harry Potter e la camera dei segreti",
          "original_title": "Harry Potter and the Chamber of Secrets",
          "overview": "Secondo film.",
          "backdrop_path": "/backdrop2.jpg",
          "poster_path": "/poster2.jpg",
          "release_date": "2002-11-13",
          "vote_average": 7.4,
          "genre_ids": [14]
        }
      ]
    }
  """.trimIndent()

  @Test
  fun `belongs to collection is parsed from movie details`() {
    val dto = movieAdapter.fromJson(movieDetailsJson)

    assertNotNull(dto)
    val collection = dto!!.belongsToCollection
    assertNotNull(collection)
    assertEquals(1241, collection!!.id)
    assertEquals("Harry Potter Collection", collection.name)
  }

  @Test
  fun `movie without collection stays null`() {
    val dto = movieAdapter.fromJson("""{"id": 671, "title": "Film senza franchise"}""")

    assertNotNull(dto)
    assertNull(dto!!.belongsToCollection)
  }

  @Test
  fun `movie details cache round trip preserves collection`() {
    val dto = movieAdapter.fromJson(movieDetailsJson)!!

    val cached = MediaCacheMapper.serializeMovieDetail(dto)
    val restored = MediaCacheMapper.deserializeMovieDetail(cached)

    assertNotNull(restored)
    assertEquals(1241, restored!!.belongsToCollection?.id)
    assertEquals("Harry Potter Collection", restored.belongsToCollection?.name)
  }

  @Test
  fun `legacy cached movie details without the field are still readable`() {
    // Payload scritto dalle versioni precedenti dell'app: nessun `belongs_to_collection`.
    val legacy = MediaCacheMapper.deserializeMovieDetail(
      """{"id": 671, "title": "Film legacy", "runtime": 120}"""
    )

    assertNotNull(legacy)
    assertNull(legacy!!.belongsToCollection)
  }

  @Test
  fun `collection parts are parsed from collection details`() {
    val dto = collectionAdapter.fromJson(collectionDetailJson)

    assertNotNull(dto)
    assertEquals(1241, dto!!.id)
    assertEquals("Harry Potter Collection", dto.name)
    assertEquals(2, dto.parts?.size)
    assertEquals(671, dto.parts?.first()?.id)
    assertEquals("2001-11-16", dto.parts?.first()?.releaseDate)
  }

  @Test
  fun `collection details cache round trip preserves parts`() {
    val dto = collectionAdapter.fromJson(collectionDetailJson)!!

    val cached = MediaCacheMapper.serializeCollectionDetail(dto)
    val restored = MediaCacheMapper.deserializeCollectionDetail(cached)

    assertNotNull(restored)
    assertEquals(2, restored!!.parts?.size)
    assertEquals(672, restored.parts?.last()?.id)
    assertTrue(restored.parts?.all { !it.backdropPath.isNullOrBlank() } == true)
  }
}
