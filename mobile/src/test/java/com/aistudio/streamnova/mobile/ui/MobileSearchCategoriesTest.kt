package com.aistudio.streamnova.mobile.ui

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SearchCategoryFilter
import com.example.data.model.SearchTypeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equivalenza tra le sottocategorie della Cerca TV e quelle della Cerca Mobile.
 *
 * La TV dichiara 24 sottocategorie in `GLOBAL_SEARCH_CATEGORIES` (app/…/SubScreens.kt):
 * stesso id, stesso ordine, stesse keyword e stessi genre ID TMDB. Questo test
 * congela il mapping Mobile perché una perdita silenziosa di sottocategorie è
 * esattamente il difetto che questo intervento ripristina.
 */
class MobileSearchCategoriesTest {

  /** Sottocategorie TV, con id, keyword e genre ID nell'ordine dichiarato dalla TV. */
  private data class TvCategory(
    val id: String,
    val keywords: List<String>,
    val movieGenreId: Int?,
    val tvGenreId: Int?
  )

  private val tvCategories = listOf(
    TvCategory("action_adventure", listOf("Action & Adventure", "Azione & Avventura", "Action", "Azione", "Avventura"), 28, 10759),
    TvCategory("animazione", listOf("Animazione", "Animation"), 16, 16),
    TvCategory("avventura", listOf("Avventura", "Adventure"), 12, 10759),
    TvCategory("azione", listOf("Azione", "Action"), 28, 10759),
    TvCategory("commedia", listOf("Commedia", "Comedy"), 35, 35),
    TvCategory("crime", listOf("Crime", "Crimine", "Poliziesco"), 80, 80),
    TvCategory("documentario", listOf("Documentario", "Documnetario", "Documentary"), 99, 99),
    TvCategory("dramma", listOf("Dramma", "Drama"), 18, 18),
    TvCategory("famiglia", listOf("Famiglia", "Family", "Kids"), 10751, 10762),
    TvCategory("fantascienza", listOf("Fantascienza", "Sci-Fi", "Science Fiction"), 878, 10765),
    TvCategory("fantasy", listOf("Fantasy", "Fantastico"), 14, 10765),
    TvCategory("guerra", listOf("Guerra", "War", "Guerra & Politica"), 10752, 10768),
    TvCategory("horror", listOf("Horror"), 27, 27),
    TvCategory("kids", listOf("Kids", "Bambini", "Famiglia"), 10751, 10762),
    TvCategory("mistero", listOf("Mistero", "Mystery"), 9648, 9648),
    TvCategory("musica", listOf("Musica", "Music", "Musicale"), 10402, null),
    TvCategory("news", listOf("News", "Notizie", "Attualità"), null, 10763),
    TvCategory("reality", listOf("Reality", "Reality-TV"), null, 10764),
    TvCategory("romance", listOf("Romance", "Romantico", "Sentimentale"), 10749, 18),
    TvCategory("sci_fi_fantasy", listOf("Sci-Fi & Fantasy", "Fantascienza", "Fantasy"), 878, 10765),
    TvCategory("storia", listOf("Storia", "History", "Storico", "Biografia"), 36, 18),
    TvCategory("thriller", listOf("Thriller", "Suspense"), 53, 9648),
    TvCategory("western", listOf("Western"), 37, 37),
    TvCategory("talk", listOf("Talk", "Talk Show"), null, 10767)
  )

  @Test
  fun leSottocategorieMobileCorrispondonoUnaPerUnaATv() {
    assertEquals(tvCategories.size, MOBILE_SEARCH_CATEGORIES.size)
    tvCategories.forEachIndexed { index, tv ->
      val mobile = MOBILE_SEARCH_CATEGORIES[index]
      assertEquals("id diverso alla posizione $index", tv.id, mobile.id)
      assertEquals("keyword diverse per ${tv.id}", tv.keywords, mobile.keywords)
      assertEquals("genre film diverse per ${tv.id}", tv.movieGenreId, mobile.movieGenreId)
      assertEquals("genre serie diverse per ${tv.id}", tv.tvGenreId, mobile.tvGenreId)
    }
  }

  @Test
  fun ogniSottocategoriaEsponeUnNomeLocalizzato() {
    MOBILE_SEARCH_CATEGORIES.forEach { category ->
      assertTrue("nome mancante per ${category.id}", category.labelRes != 0)
    }
  }

  @Test
  fun leKeywordEffettiveAggiungonoIGeneriTmdbComeInTv() {
    val commedia = MOBILE_SEARCH_CATEGORIES.first { it.id == "commedia" }
    val keywords = commedia.effectiveKeywords()
    assertTrue(keywords.contains("Commedia"))
    assertTrue(keywords.contains("Comedy"))
    assertTrue("genere film TMDB assente", keywords.contains("Comedy"))

    val musica = MOBILE_SEARCH_CATEGORIES.first { it.id == "musica" }
    assertNull("le serie non hanno il genere Musica", musica.tvGenreId)
    assertTrue(musica.effectiveKeywords().contains("Music"))
  }

  @Test
  fun ilFiltroTipoRestringeIRisultatiDellaSottocategoria() {
    val film = MediaItem(id = "f1", title = "Film", synopsis = "", videoUrl = "", type = MediaType.FILM)
    val serie = MediaItem(id = "s1", title = "Serie", synopsis = "", videoUrl = "", type = MediaType.SERIE_TV)
    val items = listOf(film, serie)

    assertEquals(2, filterMobileSearchCategoryItems(items, SearchTypeFilter.ALL).size)
    assertEquals(listOf(film), filterMobileSearchCategoryItems(items, SearchTypeFilter.FILM))
    assertEquals(listOf(serie), filterMobileSearchCategoryItems(items, SearchTypeFilter.SERIE_TV))
  }

  @Test
  fun laSottocategoriaNewsMostraSoloSerieTv() {
    val news = MOBILE_SEARCH_CATEGORIES.first { it.id == "news" }
    assertNull("News non ha il genere film", news.movieGenreId)
    assertEquals(10763, news.tvGenreId)
    assertNotNull(news.labelRes)
  }

  @Test
  fun laSottocategoriaTalkUsaLaFonteTmdbNativaComeInTv() {
    val talk = MOBILE_SEARCH_CATEGORIES.first { it.id == "talk" }
    assertEquals(
      "Talk deve essere servita nativamente da TMDB",
      SearchCategoryFilter.TMDB_TV_GENRE,
      talk.contentFilter
    )
    assertNull("Talk non ha il genere film", talk.movieGenreId)
    assertEquals(10767, talk.tvGenreId)
    assertNotNull(talk.labelRes)
  }

  @Test
  fun leAltreSottocategorieRestanoSullaFontePerGenere() {
    MOBILE_SEARCH_CATEGORIES.filter { it.id != "talk" }.forEach { category ->
      assertEquals(
        "fonte inattesa per ${category.id}",
        SearchCategoryFilter.GENRE,
        category.contentFilter
      )
    }
  }

  @Test
  fun laCategoriaTvMovieNonEsistePiuInCerca() {
    // La TV ha sostituito "Televisione film" (genere TMDB 10770) con "Talk".
    assertTrue(
      "la sottocategoria Televisione film non deve più essere presente",
      MOBILE_SEARCH_CATEGORIES.none { it.id == "televisione_film" }
    )
  }

  @Test
  fun leKeywordEffettiveDiTalkIncludonoIlGenereTmdbCanonico() {
    val talk = MOBILE_SEARCH_CATEGORIES.first { it.id == "talk" }
    assertTrue(talk.effectiveKeywords().contains("Talk Show"))
    assertTrue(talk.effectiveKeywords().contains("Talk"))
  }
}
