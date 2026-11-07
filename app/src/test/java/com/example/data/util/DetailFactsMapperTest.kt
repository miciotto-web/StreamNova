package com.example.data.util

import com.example.data.api.TmdbCountryReleaseDatesDto
import com.example.data.api.TmdbMovieReleaseDatesResponseDto
import com.example.data.api.TmdbProductionCountryDto
import com.example.data.api.TmdbReleaseDateItemDto
import com.example.data.api.TmdbTvContentRatingItemDto
import com.example.data.api.TmdbTvContentRatingsResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mapping dei metadati mostrati in «Dettagli Film».
 *
 * Ogni test copre anche il caso negativo: un dato assente o non valido deve restare
 * `null`, così la schermata Dettagli nasconde la riga invece di mostrare un valore
 * inventato.
 */
class DetailFactsMapperTest {

  // ---------------------------------------------------------------- stato

  @Test
  fun gliStatiTmdbSonoNormalizzatiSenzaDistinzioneDiMaiuscole() {
    assertEquals(
      DetailFactsMapper.TitleStatus.RELEASED,
      DetailFactsMapper.status("Released")
    )
    assertEquals(
      DetailFactsMapper.TitleStatus.POST_PRODUCTION,
      DetailFactsMapper.status("  post production ")
    )
    assertEquals(
      DetailFactsMapper.TitleStatus.RETURNING_SERIES,
      DetailFactsMapper.status("Returning Series")
    )
    assertEquals(
      DetailFactsMapper.TitleStatus.CANCELED,
      DetailFactsMapper.status("Cancelled")
    )
    assertEquals(
      DetailFactsMapper.TitleStatus.PILOT,
      DetailFactsMapper.status("Pilot")
    )
  }

  @Test
  fun statoAssenteVuotoOSconosciutoNonVieneInventato() {
    assertNull(DetailFactsMapper.status(null))
    assertNull(DetailFactsMapper.status(""))
    assertNull(DetailFactsMapper.status("   "))
    assertNull(DetailFactsMapper.status("Archived"))
  }

  // ---------------------------------------------------------------- durata

  @Test
  fun laDurataUsaIlFormatoOreMinuti() {
    assertEquals("2h 13m", DetailFactsMapper.formatDuration(133))
    assertEquals("2h 0m", DetailFactsMapper.formatDuration(120))
    assertEquals("45m", DetailFactsMapper.formatDuration(45))
  }

  @Test
  fun laDurataMancanteONonPositivaNonProduceUnaRiga() {
    assertNull(DetailFactsMapper.formatDuration(null))
    assertNull(DetailFactsMapper.formatDuration(0))
    assertNull(DetailFactsMapper.formatDuration(-10))
  }

  // ---------------------------------------------------------------- anno

  @Test
  fun lAnnoVieneEstrattoSoloDaUnaDataIsoCompletaEValida() {
    assertEquals(2025, DetailFactsMapper.releaseYear("2025-09-19"))
    assertNull(DetailFactsMapper.releaseYear("2025"))
    assertNull(DetailFactsMapper.releaseYear("2025-02-30"))
    assertNull(DetailFactsMapper.releaseYear(""))
    assertNull(DetailFactsMapper.releaseYear(null))
  }

  // ---------------------------------------------------------------- certificazione film

  private fun movieDates(vararg countries: Pair<String, List<TmdbReleaseDateItemDto>>) =
    TmdbMovieReleaseDatesResponseDto(
      id = 1,
      results = countries.map { (iso, dates) ->
        TmdbCountryReleaseDatesDto(iso3166_1 = iso, releaseDates = dates)
      }
    )

  @Test
  fun laCertificazioneFilmHaPrioritaItaliana() {
    val response = movieDates(
      "US" to listOf(TmdbReleaseDateItemDto(certification = "PG-13", type = 3)),
      "IT" to listOf(TmdbReleaseDateItemDto(certification = "T", type = 3))
    )
    assertEquals("T", DetailFactsMapper.movieCertification(response))
  }

  @Test
  fun senzaCertificazioneItalianaSiUsaIlFallbackUs() {
    val response = movieDates(
      "FR" to listOf(TmdbReleaseDateItemDto(certification = "12", type = 3)),
      "US" to listOf(TmdbReleaseDateItemDto(certification = "PG-13", type = 3))
    )
    assertEquals("PG-13", DetailFactsMapper.movieCertification(response))
  }

  @Test
  fun leCertificazioniNonClassificabiliVengonoScartate() {
    val response = movieDates(
      "IT" to listOf(
        TmdbReleaseDateItemDto(certification = "NR", type = 3),
        TmdbReleaseDateItemDto(certification = "VM14", type = 5)
      )
    )
    assertEquals("VM14", DetailFactsMapper.movieCertification(response))
  }

  @Test
  fun laCertificazioneTeatraleHaPrioritaSuQuellaHomeVideo() {
    val response = movieDates(
      "US" to listOf(
        TmdbReleaseDateItemDto(certification = "R", type = 5),
        TmdbReleaseDateItemDto(certification = "PG-13", type = 3)
      )
    )
    assertEquals("PG-13", DetailFactsMapper.movieCertification(response))
  }

  @Test
  fun senzaReleaseDatesLaCertificazioneFilmRestaAssente() {
    assertNull(DetailFactsMapper.movieCertification(null))
    assertNull(DetailFactsMapper.movieCertification(movieDates()))
    assertNull(
      DetailFactsMapper.movieCertification(
        movieDates("IT" to listOf(TmdbReleaseDateItemDto(certification = "  ", type = 3)))
      )
    )
  }

  // ---------------------------------------------------------------- certificazione serie

  @Test
  fun laCertificazioneSerieSegueLaPrioritaPaesi() {
    val response = TmdbTvContentRatingsResponseDto(
      id = 2,
      results = listOf(
        TmdbTvContentRatingItemDto(iso3166_1 = "US", rating = "TV-14"),
        TmdbTvContentRatingItemDto(iso3166_1 = "IT", rating = "T")
      )
    )
    assertEquals("T", DetailFactsMapper.tvCertification(response))
  }

  @Test
  fun senzaContentRatingsLaCertificazioneSerieRestaAssente() {
    assertNull(DetailFactsMapper.tvCertification(null))
    assertNull(
      DetailFactsMapper.tvCertification(
        TmdbTvContentRatingsResponseDto(id = 2, results = emptyList())
      )
    )
    assertNull(
      DetailFactsMapper.tvCertification(
        TmdbTvContentRatingsResponseDto(
          id = 2,
          results = listOf(TmdbTvContentRatingItemDto(iso3166_1 = "US", rating = null))
        )
      )
    )
  }

  // ---------------------------------------------------------------- paese d'origine

  @Test
  fun ilPaeseUsaIlNomeDiProductionCountriesAbbinatoAlCodiceDiOrigine() {
    val countries = listOf(
      TmdbProductionCountryDto(iso3166_1 = "FR", name = "France"),
      TmdbProductionCountryDto(iso3166_1 = "US", name = "United States of America")
    )
    assertEquals(
      "United States of America",
      DetailFactsMapper.originCountry(countries, listOf("US"))
    )
  }

  @Test
  fun senzaCodiceDiOrigineSiUsaIlPrimoPaeseDiProduzione() {
    val countries = listOf(
      TmdbProductionCountryDto(iso3166_1 = "GB", name = "United Kingdom")
    )
    assertEquals(
      "United Kingdom",
      DetailFactsMapper.originCountry(countries, emptyList())
    )
  }

  @Test
  fun senzaProductionCountriesIlCodiceIsoDiventaUnNomeLeggibile() {
    assertEquals("Italy", DetailFactsMapper.originCountry(emptyList(), listOf("IT")))
    assertEquals("United States", DetailFactsMapper.originCountry(null, listOf("US")))
  }

  @Test
  fun iDatiPaeseMancantiOVuotiRestanoAssenti() {
    assertNull(DetailFactsMapper.originCountry(null, null))
    assertNull(DetailFactsMapper.originCountry(emptyList(), emptyList()))
    assertNull(
      DetailFactsMapper.originCountry(
        listOf(TmdbProductionCountryDto(iso3166_1 = "US", name = "  ")),
        emptyList()
      )
    )
    assertNull(DetailFactsMapper.originCountry(null, listOf("", "  ")))
  }
}
