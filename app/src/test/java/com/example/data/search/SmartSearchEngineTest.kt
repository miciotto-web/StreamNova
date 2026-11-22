package com.example.data.search

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratto della Ricerca cinematografica evoluta. */
class SmartSearchEngineTest {

  private fun item(
    id: String,
    title: String,
    year: Int = 2020,
    rating: Float = 7.5f,
    type: MediaType = MediaType.FILM,
    genres: List<String> = listOf("Azione"),
    originalTitle: String = "",
    language: String = "",
    favorite: Boolean = false,
    progressMs: Long = 0L,
    lastWatchedAt: Long? = null
  ) = MediaItem(
    id = id,
    title = title,
    originalTitle = originalTitle,
    synopsis = "",
    videoUrl = "",
    year = year,
    rating = rating,
    type = type,
    genres = genres,
    originalLanguage = language,
    isFavorite = favorite,
    currentProgressMs = progressMs,
    lastWatchedAt = lastWatchedAt
  )

  @Test
  fun `parses inline filters and keeps free text`() {
    val parsed = SmartSearchEngine.parse("genere:azione anno:2010-2020 voto:7 tipo:film dune")
    assertEquals("dune", parsed.text)
    assertTrue(parsed.refinement.genres.contains("azione"))
    assertEquals(2010, parsed.refinement.yearFrom)
    assertEquals(2020, parsed.refinement.yearTo)
    assertEquals(7f, parsed.refinement.minRating!!, 0.001f)
    assertEquals(MediaType.FILM, parsed.refinement.type)
  }

  @Test
  fun `parses excluded genre and person query`() {
    val parsed = SmartSearchEngine.parse("-genere:horror attore:\"Tom Hanks\"")
    assertTrue(parsed.refinement.excludedGenres.contains("horror"))
    assertEquals("Tom Hanks", parsed.personQuery!!.name)
    assertEquals(PersonRole.ACTOR, parsed.personQuery!!.role)
  }

  @Test
  fun `parses director and sort`() {
    val parsed = SmartSearchEngine.parse("regista:Nolan ordina:voto")
    assertEquals(PersonRole.DIRECTOR, parsed.personQuery!!.role)
    assertEquals(SearchSort.RATING_DESC, parsed.refinement.sort)
  }

  @Test
  fun `parses saga query`() {
    val parsed = SmartSearchEngine.parse("saga:\"Harry Potter\"")
    assertEquals("Harry Potter", parsed.sagaQuery)
    assertTrue(parsed.text.isBlank())
  }

  @Test
  fun `applies combined filters`() {
    val items = listOf(
      item("1", "Azione 2015", year = 2015, genres = listOf("Azione")),
      item("2", "Horror 2015", year = 2015, genres = listOf("Horror")),
      item("3", "Azione 2025", year = 2025, genres = listOf("Azione"))
    )
    val refinement = SearchRefinement(
      genres = setOf("azione"),
      excludedGenres = setOf("horror"),
      yearFrom = 2010,
      yearTo = 2020
    )
    val result = SmartSearchEngine.applyFilters(items, refinement)
    assertEquals(listOf("1"), result.map { it.id })
  }

  @Test
  fun `language filter applies only when metadata is known`() {
    val italian = item("it", "Film IT", language = "it")
    val english = item("en", "Film EN", language = "en")
    val unknown = item("na", "Film ignoto")
    val result = SmartSearchEngine.applyFilters(
      listOf(italian, english, unknown),
      SearchRefinement(language = "it")
    )
    assertEquals(listOf("it", "na"), result.map { it.id })
  }

  @Test
  fun `unwatched and watchlist filters`() {
    val fresh = item("fresh", "Mai visto")
    val watched = item("watched", "Visto", progressMs = 1000)
    val favorite = item("fav", "Preferito", favorite = true)
    assertEquals(
      listOf("fresh", "fav"),
      SmartSearchEngine.applyFilters(
        listOf(fresh, watched, favorite),
        SearchRefinement(onlyUnwatched = true)
      ).map { it.id }
    )
    assertEquals(
      listOf("fav"),
      SmartSearchEngine.applyFilters(
        listOf(fresh, favorite),
        SearchRefinement(onlyWatchlist = true)
      ).map { it.id }
    )
  }

  @Test
  fun `sorts by rating year and title`() {
    val items = listOf(
      item("b", "Beta", year = 2019, rating = 6f),
      item("a", "Alfa", year = 2024, rating = 9f),
      item("c", "Gamma", year = 2010, rating = 7f)
    )
    assertEquals(
      listOf("a", "c", "b"),
      SmartSearchEngine.sort(items, "", SearchSort.RATING_DESC).map { it.id }
    )
    assertEquals(
      listOf("a", "b", "c"),
      SmartSearchEngine.sort(items, "", SearchSort.YEAR_DESC).map { it.id }
    )
    assertEquals(
      listOf("c", "b", "a"),
      SmartSearchEngine.sort(items, "", SearchSort.YEAR_ASC).map { it.id }
    )
    assertEquals(
      listOf("a", "b", "c"),
      SmartSearchEngine.sort(items, "", SearchSort.TITLE_ASC).map { it.id }
    )
  }

  @Test
  fun `fuzzy score tolerates typos and accents`() {
    val score = SmartSearchEngine.fuzzyScore("interstelar", "Interstellar")
    assertTrue("score=$score", score >= 0.5)
    val accent = SmartSearchEngine.fuzzyScore("amelie", "Amélie")
    assertTrue("score=$accent", accent >= 0.5)
  }

  @Test
  fun `fuzzy matches recover misspelled local titles`() {
    val items = listOf(
      item("1", "Interstellar"),
      item("2", "Inception"),
      item("3", "Il Padrino")
    )
    val result = SmartSearchEngine.fuzzyMatches(items, "interstelar")
    assertEquals("1", result.first().id)
  }

  @Test
  fun `relevance sorting puts exact title first`() {
    val items = listOf(
      item("1", "Dune: Part Two"),
      item("2", "Dune"),
      item("3", "Dune Drifter")
    )
    val result = SmartSearchEngine.refine(items, "Dune", SearchRefinement())
    assertEquals("2", result.first().id)
  }

  @Test
  fun `dedupe keeps first occurrence across sources`() {
    val a = item("tmdb_m_1", "Dune", year = 2021)
    val b = item("tmdb_m_1", "Dune", year = 2021)
    val c = item("other", "Dune", year = 2021)
    val deduped = SmartSearchEngine.dedupe(listOf(a, b, c))
    assertEquals(1, deduped.size)
    assertEquals("tmdb_m_1", deduped.first().id)
  }

  @Test
  fun `has active filters ignores sort`() {
    assertFalse(SearchRefinement().hasActiveFilters)
    assertFalse(SearchRefinement(sort = SearchSort.RATING_DESC).hasActiveFilters)
    assertTrue(SearchRefinement(minRating = 7f).hasActiveFilters)
  }

  @Test
  fun `merged refinement combines inline and panel values`() {
    val inline = SearchRefinement(genres = setOf("azione"), yearFrom = 2010)
    val panel = SearchRefinement(genres = setOf("thriller"), yearTo = 2020, minRating = 7f)
    val merged = inline.mergedWith(panel)
    assertEquals(setOf("azione", "thriller"), merged.genres)
    assertEquals(2010, merged.yearFrom)
    assertEquals(2020, merged.yearTo)
    assertEquals(7f, merged.minRating!!, 0.001f)
  }
}
