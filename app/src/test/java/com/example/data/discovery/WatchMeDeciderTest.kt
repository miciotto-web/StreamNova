package com.example.data.discovery

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratto di "Non so cosa guardare" e "Scopri qualcosa di nuovo". */
class WatchMeDeciderTest {

  private fun item(
    id: String,
    title: String,
    type: MediaType = MediaType.FILM,
    genres: List<String> = listOf("Azione"),
    year: Int = 2020,
    rating: Float = 8f,
    durationMinutes: Int = 100,
    favorite: Boolean = false,
    progressMs: Long = 0L,
    director: String = ""
  ) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = type,
    year = year,
    rating = rating,
    genres = genres,
    durationMinutes = durationMinutes,
    isFavorite = favorite,
    currentProgressMs = progressMs,
    director = director
  )

  @Test
  fun `respects type rating and duration filters`() {
    val movie = item("m", "Film", type = MediaType.FILM, durationMinutes = 90, rating = 8f)
    val longMovie = item("l", "Film lungo", type = MediaType.FILM, durationMinutes = 200, rating = 8f)
    val series = item("s", "Serie", type = MediaType.SERIE_TV, rating = 8f)
    val low = item("low", "Scarso", type = MediaType.FILM, rating = 5f)
    val filters = WatchMeFilters(
      type = MediaType.FILM,
      maxDurationMinutes = 120,
      minRating = 7f,
      onlyUnwatched = true
    )
    val result = WatchMeDecider.candidates(listOf(movie, longMovie, series, low), filters)
    assertEquals(listOf("m"), result.map { it.item.id })
  }

  @Test
  fun `watchlist filter only returns favorites`() {
    val favorite = item("f", "Preferito", favorite = true)
    val other = item("o", "Altro")
    val result = WatchMeDecider.candidates(
      listOf(favorite, other),
      WatchMeFilters(onlyWatchlist = true, onlyUnwatched = false)
    )
    assertEquals(listOf("f"), result.map { it.item.id })
  }

  @Test
  fun `never suggests watched titles when onlyUnwatched`() {
    val watched = item("w", "Visto", progressMs = 5000)
    val fresh = item("f", "Nuovo")
    val result = WatchMeDecider.candidates(
      listOf(watched, fresh),
      WatchMeFilters(minRating = null, onlyUnwatched = true)
    )
    assertEquals(listOf("f"), result.map { it.item.id })
  }

  @Test
  fun `taste profile boosts favorite genre`() {
    val action = item("a", "Azione", genres = listOf("Azione"))
    val drama = item("d", "Dramma", genres = listOf("Drammatico"))
    val taste = TasteProfile(favoriteGenres = mapOf("azione" to 5))
    val suggestion = WatchMeDecider.pick(
      listOf(drama, action),
      WatchMeFilters(minRating = null, onlyUnwatched = true),
      taste,
      seed = 42L
    )
    assertNotNull(suggestion)
    assertTrue(suggestion!!.reasons.any { it.contains("Genere") })
  }

  @Test
  fun `same seed produces the same suggestion`() {
    val items = (1..20).map { item("id$it", "Titolo $it", rating = 7f + (it % 3)) }
    val filters = WatchMeFilters(minRating = null, onlyUnwatched = true)
    val first = WatchMeDecider.pick(items, filters, TasteProfile(), seed = 123L)
    val second = WatchMeDecider.pick(items, filters, TasteProfile(), seed = 123L)
    assertEquals(first!!.item.id, second!!.item.id)
  }

  @Test
  fun `empty eligible list returns null`() {
    assertNull(
      WatchMeDecider.pick(
        listOf(item("x", "Visto", progressMs = 1000)),
        WatchMeFilters(minRating = 9f, onlyUnwatched = true)
      )
    )
  }

  @Test
  fun `discover excludes everything already watched`() {
    val watched = item("w", "Visto", progressMs = 1000, rating = 9f)
    val candidate = item("c", "Candidato", rating = 8f)
    val suggestion = WatchMeDecider.discover(listOf(watched, candidate), TasteProfile(), seed = 7L)
    assertEquals("c", suggestion!!.item.id)
  }

  @Test
  fun `taste profile builder excludes placeholder directors`() {
    val items = listOf(
      item("1", "Uno", favorite = true, director = "Produzione Internazionale", genres = listOf("Azione")),
      item("2", "Due", favorite = true, director = "Christopher Nolan", genres = listOf("Azione", "Thriller"))
    )
    val taste = TasteProfileBuilder.build(items)
    assertTrue(taste.favoriteGenres.containsKey("azione"))
    assertEquals(setOf("christopher nolan"), taste.favoriteDirectors)
    // I soli preferiti NON contano come "visti": devono poter essere proposti.
    assertTrue(taste.watchedIds.isEmpty())
  }

  @Test
  fun `watchlist never watched item can be suggested`() {
    val favorite = item("f", "In watchlist", favorite = true)
    val suggestion = WatchMeDecider.pick(
      items = listOf(favorite),
      filters = WatchMeFilters(
        onlyWatchlist = true,
        onlyUnwatched = true,
        minRating = null
      ),
      taste = TasteProfileBuilder.build(listOf(favorite)),
      seed = 1L
    )
    assertEquals("f", suggestion!!.item.id)
    assertTrue(suggestion.reasons.any { it.contains("watchlist") })
  }
}
