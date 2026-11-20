package com.example.ui.screens

import com.example.R
import com.example.data.model.SearchCategoryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La categoria "TV Show" sostituisce "Talk" nella sezione Ricerca.
 *
 * È servita nativamente da TMDB (`discover/tv`, show TV italiani in testa e poi le
 * altre lingue, recenti e meno recenti), non dai cataloghi degli addon. Il test
 * blocca la configurazione: etichetta, fonte nativa, assenza di filtri per genere
 * (il catalogo show TV non è un genere) e posizione nella lista delle categorie.
 */
class SearchCategoryTvShowLabelTest {

    @Test
    fun `la vecchia categoria talk non esiste piu`() {
        assertTrue(
            "La categoria talk non deve più comparire",
            GLOBAL_SEARCH_CATEGORIES.none { it.id == "talk" }
        )
    }

    @Test
    fun `tv show e l'unica categoria nativa TMDB senza filtri per genere`() {
        val tvShow = GLOBAL_SEARCH_CATEGORIES.single { it.id == "tv_show" }

        assertEquals(R.string.search_cat_tv_show, tvShow.labelRes)
        assertEquals(SearchCategoryFilter.TMDB_TV_SHOWS, tvShow.contentFilter)
        assertNull("Il catalogo show TV non filtra per genere film", tvShow.movieGenreId)
        assertNull("Il catalogo show TV non filtra per genere serie", tvShow.tvGenreId)
        assertTrue(
            "Le keyword dichiarano il concetto di show TV: ${tvShow.keywords}",
            tvShow.keywords.any { it.equals("TV Show", ignoreCase = true) }
        )
        assertEquals(
            "Una sola categoria nativa TMDB",
            1,
            GLOBAL_SEARCH_CATEGORIES.count {
                it.contentFilter == SearchCategoryFilter.TMDB_TV_SHOWS
            }
        )
    }

    @Test
    fun `tv show occupa l'ultimo posto come la categoria che ha sostituito`() {
        assertEquals("tv_show", GLOBAL_SEARCH_CATEGORIES.last().id)
    }

    @Test
    fun `le categorie di genere restano invariate`() {
        val thriller = GLOBAL_SEARCH_CATEGORIES.first { it.id == "thriller" }
        assertEquals(SearchCategoryFilter.GENRE, thriller.contentFilter)
        assertEquals(53, thriller.movieGenreId)
        assertEquals(9648, thriller.tvGenreId)

        assertEquals(
            "Le categorie di genere restano quelle storiche (News è stata sostituita da Christmas, nativa TMDB)",
            22,
            GLOBAL_SEARCH_CATEGORIES.count { it.contentFilter == SearchCategoryFilter.GENRE }
        )
        assertEquals(
            "22 categorie di genere + TV Show + Christmas",
            24,
            GLOBAL_SEARCH_CATEGORIES.size
        )
    }
}
