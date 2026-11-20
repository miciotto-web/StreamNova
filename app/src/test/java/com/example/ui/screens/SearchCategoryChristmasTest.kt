package com.example.ui.screens

import com.example.R
import com.example.data.model.SearchCategoryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La categoria "Christmas" sostituisce "News" nella sezione Ricerca.
 *
 * È servita nativamente da TMDB tramite la keyword canonica 207317 ("Christmas"):
 * film e serie TV sono scoperti con `discover/movie` e `discover/tv` filtrati per
 * `with_keywords`, quindi la categoria non dichiara generi e non passa dai
 * cataloghi degli addon.
 */
class SearchCategoryChristmasTest {

    @Test
    fun `la vecchia categoria news non esiste piu`() {
        assertTrue(
            "La categoria news non deve più comparire",
            GLOBAL_SEARCH_CATEGORIES.none { it.id == "news" }
        )
    }

    @Test
    fun `christmas usa la keyword TMDB 207317 per film e serie`() {
        val christmas = GLOBAL_SEARCH_CATEGORIES.single { it.id == "christmas" }

        assertEquals(R.string.search_cat_christmas, christmas.labelRes)
        assertEquals(SearchCategoryFilter.TMDB_KEYWORD, christmas.contentFilter)
        assertEquals("Keyword TMDB Christmas", 207317, christmas.tmdbKeywordId)
        assertNull("Christmas non filtra per genere film", christmas.movieGenreId)
        assertNull("Christmas non filtra per genere serie", christmas.tvGenreId)
        assertTrue(
            "Le keyword dichiarano il concetto di Natale: ${christmas.keywords}",
            christmas.keywords.any { it.equals("Natale", ignoreCase = true) } &&
                christmas.keywords.any { it.equals("Christmas", ignoreCase = true) }
        )
        assertEquals(
            "Una sola categoria nativa TMDB per keyword",
            1,
            GLOBAL_SEARCH_CATEGORIES.count {
                it.contentFilter == SearchCategoryFilter.TMDB_KEYWORD
            }
        )
    }

    @Test
    fun `christmas occupa il posto della categoria che ha sostituito`() {
        val ids = GLOBAL_SEARCH_CATEGORIES.map { it.id }
        val index = ids.indexOf("christmas")

        assertTrue("Christmas deve essere presente: $ids", index >= 0)
        assertTrue(
            "Christmas resta nell'ordine alfabetico tra Musica e Reality: $ids",
            ids.indexOf("musica") < index && index < ids.indexOf("reality")
        )
    }
}
