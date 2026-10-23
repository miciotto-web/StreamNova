package com.example.data.repository

import com.example.TmdbNetwork
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Verifica contro il servizio TMDB reale che la sinossi dell'Hero venga davvero
 * localizzata in italiano, sia quando il catalogo espone un id TMDB sia quando
 * espone solo l'IMDb id (Cinemeta restituisce `tt...`).
 *
 * Richiede `TMDB_API_KEY` (local.properties o variabile d'ambiente) e la rete:
 * senza configurazione questi test vengono saltati, non falliti.
 */
class HeroSynopsisLocalizerNetworkTest {

  private fun item(
    id: String,
    tmdbId: Int? = null,
    synopsis: String = ENGLISH_SYNOPSIS,
    type: MediaType = MediaType.FILM
  ) = MediaItem(
    id = id,
    title = "Fight Club",
    synopsis = synopsis,
    videoUrl = "",
    tmdbId = tmdbId,
    type = type
  )

  @Test
  fun laSinossiArrivaInItalianoPerUnIdTmdbNoto() = runBlocking {
    TmdbNetwork.assumeConfiguredApiKey()
    assumeTrue("Lingua app non italiana", HeroSynopsisLocalizer.languageCode() == "it")

    val synopsis = HeroSynopsisLocalizer.fetchLocalizedSynopsis(item(id = "tt0137523", tmdbId = 550))

    assertTrue("Sinossi assente", !synopsis.isNullOrBlank())
    assertTrue(
      "Sinossi non italiana: $synopsis",
      HeroSynopsisLocalizer.isItalianLookingText(synopsis.orEmpty(), ENGLISH_SYNOPSIS)
    )
  }

  @Test
  fun laSinossiArrivaInItalianoRisolvendoLImdbIdDiCinemeta() = runBlocking {
    TmdbNetwork.assumeConfiguredApiKey()
    assumeTrue("Lingua app non italiana", HeroSynopsisLocalizer.languageCode() == "it")

    // Stesso formato restituito da Cinemeta: solo l'IMDb id, nessun id TMDB.
    val synopsis = HeroSynopsisLocalizer.fetchLocalizedSynopsis(item(id = "tt0137523"))

    assertTrue("Sinossi assente", !synopsis.isNullOrBlank())
    assertFalse("Nessuna localizzazione: $synopsis", synopsis == ENGLISH_SYNOPSIS)
  }

  private companion object {
    const val ENGLISH_SYNOPSIS =
      "An insomniac office worker and a devil-may-care soap maker form an underground fight club."
  }
}
