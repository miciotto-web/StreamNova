package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.api.TmdbApiClient
import com.example.data.model.MediaType
import com.example.data.repository.MediaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TmdbSearchAndPaginationTest {

  @Test
  fun testTmdbApiKeyValidity() = runBlocking {
    val isValid = MediaRepository.verifyTmdbApiKey()
    assertTrue("TMDB API Key deve essere valida ed attiva (HTTP 200)", isValid)
  }

  @Test
  fun testSearchTheBigBangTheoryDirectApi() = runBlocking {
    val apiKey = MediaRepository.getEffectiveApiKey()
    val response = TmdbApiClient.service.searchMulti(
      apiKey = apiKey,
      query = "The Big Bang Theory",
      language = "it-IT",
      page = 1
    )
    assertNotNull(response.results)
    assertTrue("La ricerca remota deve restituire almeno 1 risultato per 'The Big Bang Theory'", response.results.isNotEmpty())

    val tbbt = response.results.firstOrNull {
      it.mediaType == "tv" && (it.name?.contains("Big Bang Theory", ignoreCase = true) == true ||
        it.originalName?.contains("Big Bang Theory", ignoreCase = true) == true)
    }
    assertNotNull("Deve esistere la serie TV 'The Big Bang Theory'", tbbt)
    assertEquals(1418, tbbt!!.id)
    assertNotNull("Deve avere il poster", tbbt.posterPath)
    assertNotNull("Deve avere il backdrop", tbbt.backdropPath)
  }

  @Test
  fun testSearchIlPrincipeCercaMoglieDirectApi() = runBlocking {
    val apiKey = MediaRepository.getEffectiveApiKey()
    val response = TmdbApiClient.service.searchMulti(
      apiKey = apiKey,
      query = "Il principe cerca moglie",
      language = "it-IT",
      page = 1
    )
    assertNotNull(response.results)
    assertTrue("La ricerca remota deve restituire almeno 1 risultato per 'Il principe cerca moglie'", response.results.isNotEmpty())

    val movie = response.results.firstOrNull {
      it.mediaType == "movie" && (it.title?.contains("principe cerca moglie", ignoreCase = true) == true ||
        it.originalTitle?.contains("Coming to America", ignoreCase = true) == true)
    }
    assertNotNull("Deve esistere il film 'Il principe cerca moglie'", movie)
    assertEquals(9602, movie!!.id)
    assertNotNull("Deve avere il poster", movie.posterPath)
    assertNotNull("Deve avere la trama", movie.overview)
  }

  @Test
  fun testRepositorySearchFlowTheBigBangTheory() = runBlocking {
    // Esegui la ricerca tramite MediaRepository.searchTmdb
    val emissions = MediaRepository.searchTmdb("The Big Bang Theory").toList()
    assertTrue("Il flow di ricerca deve emettere almeno un risultato", emissions.isNotEmpty())

    val lastEmission = emissions.last()
    val found = lastEmission.firstOrNull {
      it.title.contains("Big Bang Theory", ignoreCase = true) ||
        it.originalTitle.contains("Big Bang Theory", ignoreCase = true)
    }
    assertNotNull("La serie TV 'The Big Bang Theory' deve essere presente nei risultati del repository", found)
    assertEquals(MediaType.SERIE_TV, found!!.type)
    assertTrue("Il poster deve essere popolato con URL valido", found.posterUrl?.startsWith("https://image.tmdb.org/") == true)
  }

  @Test
  fun testRepositorySearchFlowIlPrincipeCercaMoglie() = runBlocking {
    // Esegui la ricerca tramite MediaRepository.searchTmdb
    val emissions = MediaRepository.searchTmdb("Il principe cerca moglie").toList()
    assertTrue("Il flow di ricerca deve emettere almeno un risultato", emissions.isNotEmpty())

    val lastEmission = emissions.last()
    val found = lastEmission.firstOrNull {
      it.title.contains("principe cerca moglie", ignoreCase = true) ||
        it.originalTitle.contains("Coming to America", ignoreCase = true)
    }
    assertNotNull("Il film 'Il principe cerca moglie' deve essere presente nei risultati del repository", found)
    assertEquals(MediaType.FILM, found!!.type)
    assertTrue("Il poster deve essere popolato con URL valido", found.posterUrl?.startsWith("https://image.tmdb.org/") == true)
  }

  @Test
  fun testPaginationMoviesAndTv() = runBlocking {
    // Test paginazione Film pagina 2
    val moviesP2 = MediaRepository.loadMoreMovies(2)
    assertNotNull(moviesP2)
    assertTrue("La pagina 2 dei film popolari deve restituire elementi", moviesP2.isNotEmpty())
    assertTrue("Tutti gli elementi caricati devono essere di tipo FILM", moviesP2.all { it.type == MediaType.FILM })

    // Test paginazione Serie TV pagina 2
    val tvP2 = MediaRepository.loadMoreTv(2)
    assertNotNull(tvP2)
    assertTrue("La pagina 2 delle serie TV popolari deve restituire elementi", tvP2.isNotEmpty())
    assertTrue("Tutti gli elementi caricati devono essere di tipo SERIE_TV", tvP2.all { it.type == MediaType.SERIE_TV })
  }
}
