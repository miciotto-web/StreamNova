package com.example.data.api

import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * I mapper TMDB portano nel modello la data completa solo quando è affidabile.
 * È l'unica sorgente che alimenta il filtro "Nascondi contenuti non rilasciati":
 * un anno da solo o un campo vuoto restano "data sconosciuta".
 */
class TmdbIsoDateMappingTest {

  private fun movie(releaseDate: String?) = TmdbMovieDto(
    id = 1,
    title = "Film",
    originalTitle = "Movie",
    overview = "",
    backdropPath = null,
    posterPath = null,
    releaseDate = releaseDate,
    voteAverage = 7f
  )

  private fun tv(firstAirDate: String?) = TmdbTvDto(
    id = 2,
    name = "Serie",
    originalName = "Series",
    overview = "",
    backdropPath = null,
    posterPath = null,
    firstAirDate = firstAirDate,
    voteAverage = 8f
  )

  @Test
  fun filmTmdbEsponeLaDataDiUscitaCompleta() {
    assertEquals(
      "2026-12-01",
      TmdbCatalogMapper.movieToMediaItem(movie("2026-12-01")).releaseDate
    )
  }

  @Test
  fun serieTmdbEsponeLaDataDiPrimaMessaInOnda() {
    assertEquals(
      "2026-09-10",
      TmdbCatalogMapper.tvToMediaItem(tv("2026-09-10")).releaseDate
    )
  }

  @Test
  fun dataAssenteNonDiventaUnaDataInventata() {
    assertNull(TmdbCatalogMapper.movieToMediaItem(movie(null)).releaseDate)
    assertNull(TmdbCatalogMapper.movieToMediaItem(movie("")).releaseDate)
    assertNull(TmdbCatalogMapper.tvToMediaItem(tv("2026")).releaseDate)
    assertNull(TmdbCatalogMapper.tvToMediaItem(tv("2026-02-30")).releaseDate)
  }

  @Test
  fun ilMapperMantieneIlTipoDellaSorgente() {
    assertEquals(MediaType.FILM, TmdbCatalogMapper.movieToMediaItem(movie("2026-12-01")).type)
    assertEquals(MediaType.SERIE_TV, TmdbCatalogMapper.tvToMediaItem(tv("2026-09-10")).type)
  }
}
