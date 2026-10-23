package com.example.data.repository

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Sinossi dell'Hero Banner nella lingua dell'app.
 *
 * Le righe della Home arrivano da Cinemeta, che restituisce `description` solo in
 * inglese e non accetta parametri di lingua. [HeroSynopsisLocalizer] chiede la
 * sinossi a TMDB (`language` dell'app + `append_to_response=translations`) e
 * sostituisce SOLO il testo dell'Hero, senza mai svuotarlo.
 */
class HeroSynopsisLocalizerTest {

  private fun movie(
    id: String = "tt0137523",
    synopsis: String = ENGLISH_SYNOPSIS,
    tmdbId: Int? = null,
    imdbId: String? = null,
    type: MediaType = MediaType.FILM
  ) = MediaItem(
    id = id,
    title = "Fight Club",
    synopsis = synopsis,
    videoUrl = "",
    tmdbId = tmdbId,
    imdbId = imdbId,
    type = type
  )

  @Test
  fun laSinossiItalianaDiTmdbSostituisceQuellaInglese() {
    val item = movie(tmdbId = 550)

    val result = HeroSynopsisLocalizer.pickSynopsis(
      item = item,
      localized = ITALIAN_SYNOPSIS,
      translations = emptyList(),
      code = "it"
    )

    assertEquals(ITALIAN_SYNOPSIS, result)
  }

  @Test
  fun senzaOverviewItalianaUsaLaTraduzioneItalianaEsplicita() {
    val item = movie()

    val result = HeroSynopsisLocalizer.pickSynopsis(
      item = item,
      localized = ENGLISH_SYNOPSIS,
      translations = listOf("en" to ENGLISH_SYNOPSIS, "it" to ITALIAN_SYNOPSIS),
      code = "it"
    )

    assertEquals(ITALIAN_SYNOPSIS, result)
  }

  @Test
  fun senzaTraduzioniMantieneLaSinossiOriginaleMaiVuota() {
    val item = movie()

    val result = HeroSynopsisLocalizer.pickSynopsis(
      item = item,
      localized = null,
      translations = emptyList(),
      code = "it"
    )

    assertEquals(ENGLISH_SYNOPSIS, result)
  }

  @Test
  fun unaOverviewIngleseRestituitaDaTmdbNonVieneSpacciataPerItaliana() {
    val item = movie()

    val result = HeroSynopsisLocalizer.pickSynopsis(
      item = item,
      localized = ENGLISH_SYNOPSIS,
      translations = emptyList(),
      code = "it"
    )

    assertEquals(ENGLISH_SYNOPSIS, result)
    assertEquals("it", HeroSynopsisLocalizer.languageCode("it-IT"))
  }

  @Test
  fun unTestoItalianoSenzaTraduzioneSostituisceQuelloInglese() {
    val item = movie()

    val result = HeroSynopsisLocalizer.pickSynopsis(
      item = item,
      localized = "Un uomo qualunque scopre un segreto che cambia la sua vita.",
      translations = emptyList(),
      code = "it"
    )

    assertEquals("Un uomo qualunque scopre un segreto che cambia la sua vita.", result)
  }

  @Test
  fun sinossiVuotaNonVieneMaiProdottaQuandoLItemNeAvevaUna() {
    val item = movie()

    val result = HeroSynopsisLocalizer.pickSynopsis(
      item = item,
      localized = "   ",
      translations = listOf("it" to "", "en" to ""),
      code = "it"
    )

    assertEquals(ENGLISH_SYNOPSIS, result)
  }

  @Test
  fun lIdTmdbVieneEstrattoDaiFormatiDeiCataloghi() {
    assertEquals(603, HeroSynopsisLocalizer.tmdbIdOf(movie(id = "tt0133093", tmdbId = 603)))
    assertEquals(603, HeroSynopsisLocalizer.tmdbIdOf(movie(id = "tmdb:603")))
    assertEquals(603, HeroSynopsisLocalizer.tmdbIdOf(movie(id = "tmdb_m_603")))
    assertEquals(603, HeroSynopsisLocalizer.tmdbIdOf(movie(id = "tmdb_tv_603")))
    assertEquals(603, HeroSynopsisLocalizer.tmdbIdOf(movie(id = "603")))
    assertNull(HeroSynopsisLocalizer.tmdbIdOf(movie(id = "tt0133093")))
  }

  @Test
  fun lIdImdbVieneEstrattoAncheQuandoImdbIdNonEValorizzato() {
    assertEquals("tt0133093", HeroSynopsisLocalizer.imdbIdOf(movie(id = "tt0133093")))
    assertEquals(
      "tt0903747",
      HeroSynopsisLocalizer.imdbIdOf(movie(id = "tt0903747:1:1", type = MediaType.SERIE_TV))
    )
    assertEquals("tt0133093", HeroSynopsisLocalizer.imdbIdOf(movie(id = "tmdb:603", imdbId = "tt0133093")))
    assertNull(HeroSynopsisLocalizer.imdbIdOf(movie(id = "tmdb:603")))
  }

  @Test
  fun localizeAllCambiaSoloLaSinossiEConservaOrdineEPoster() = runBlocking {
    val items = listOf(
      movie(id = "tt0137523", tmdbId = 550).copy(posterUrl = "poster-a", backdropUrl = "backdrop-a"),
      movie(id = "tt0133093", tmdbId = 603).copy(posterUrl = "poster-b", synopsis = "")
    )

    val localized = HeroSynopsisLocalizer.localizeAll(items) { tmdbId, _ ->
      if (tmdbId == 550) {
        HeroSynopsisLocalizer.LocalizedSynopsisSource(ITALIAN_SYNOPSIS)
      } else {
        // Nessuna sinossi per il secondo titolo: resta il testo originale (vuoto).
        HeroSynopsisLocalizer.LocalizedSynopsisSource(null)
      }
    }

    assertEquals(listOf("tt0137523", "tt0133093"), localized.map { it.id })
    assertEquals(ITALIAN_SYNOPSIS, localized[0].synopsis)
    assertEquals("poster-a", localized[0].posterUrl)
    assertEquals("backdrop-a", localized[0].backdropUrl)
    assertEquals("", localized[1].synopsis)
  }

  @Test
  fun laSinossiItalianaRealeDiTmdbVieneRiconosciutaComeItaliana() {
    // Testo reale restituito da TMDB con language=it-IT per il film 550.
    val real = "Un uomo di trent'anni è insofferente su tutto e la notte non riesce più a dormire. " +
      "In cerca di una soluzione al suo malessere incontra Tyler Durden."

    assertEquals(true, HeroSynopsisLocalizer.isItalianLookingText(real, ENGLISH_SYNOPSIS))
    assertEquals(false, HeroSynopsisLocalizer.isItalianLookingText(ENGLISH_SYNOPSIS, ""))
    assertEquals(false, HeroSynopsisLocalizer.isItalianLookingText("Бойцовский клуб", ""))
  }

  private companion object {
    const val ENGLISH_SYNOPSIS =
      "An insomniac office worker and a devil-may-care soap maker form an underground fight club."
    const val ITALIAN_SYNOPSIS =
      "Un impiegato insonne e un venditore di sapone fondano un club di combattimenti clandestini."
  }
}
