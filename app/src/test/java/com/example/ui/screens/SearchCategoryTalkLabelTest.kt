package com.example.ui.screens

import com.example.R
import com.example.data.api.TmdbGenreMapper
import com.example.data.model.SearchCategoryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La categoria "Talk" sostituisce "Lingua Originale" nella sezione Ricerca.
 *
 * "Talk" è il genere TV ufficiale TMDB (id 10767) ed è servita nativamente da
 * `discover/tv`, non dai cataloghi degli addon. Il test blocca la configurazione:
 * etichetta, id di genere, fonte nativa e posizione nella lista delle categorie,
 * oltre alla mappatura del progetto che riconosce l'id 10767 come "Talk".
 */
class SearchCategoryTalkLabelTest {

    @Test
    fun `la vecchia categoria lingua originale non esiste piu`() {
        assertTrue(
            "La categoria lingua_originale non deve più comparire",
            GLOBAL_SEARCH_CATEGORIES.none { it.id == "lingua_originale" }
        )
    }

    @Test
    fun `talk e l'unica categoria nativa TMDB e usa il genere TV 10767`() {
        val talk = GLOBAL_SEARCH_CATEGORIES.single { it.id == "talk" }

        assertEquals(R.string.search_cat_talk, talk.labelRes)
        assertEquals(SearchCategoryFilter.TMDB_TV_GENRE, talk.contentFilter)
        assertEquals("Genere TV TMDB Talk", 10767, talk.tvGenreId)
        assertNull("Talk è un genere TV: nessun genere film", talk.movieGenreId)
        assertEquals(
            "Una sola categoria nativa TMDB",
            1,
            GLOBAL_SEARCH_CATEGORIES.count {
                it.contentFilter == SearchCategoryFilter.TMDB_TV_GENRE
            }
        )
    }

    @Test
    fun `talk occupa l'ultimo posto come la categoria che ha sostituito`() {
        assertEquals("talk", GLOBAL_SEARCH_CATEGORIES.last().id)
    }

    @Test
    fun `l'id 10767 e gia mappato sul genere Talk del progetto`() {
        assertEquals(
            "La mappatura TMDB esistente deve riconoscere 10767 come Talk (non un id inventato)",
            listOf("Talk"),
            TmdbGenreMapper.mapGenreIds(listOf(10767), isMovie = false)
        )
    }

    @Test
    fun `le categorie di genere restano invariate`() {
        val thriller = GLOBAL_SEARCH_CATEGORIES.first { it.id == "thriller" }
        assertEquals(SearchCategoryFilter.GENRE, thriller.contentFilter)
        assertEquals(53, thriller.movieGenreId)
        assertEquals(9648, thriller.tvGenreId)

        assertEquals(
            "Le categorie di genere restano quelle storiche",
            23,
            GLOBAL_SEARCH_CATEGORIES.count { it.contentFilter == SearchCategoryFilter.GENRE }
        )
        assertEquals("23 categorie di genere + Talk", 24, GLOBAL_SEARCH_CATEGORIES.size)
    }
}
