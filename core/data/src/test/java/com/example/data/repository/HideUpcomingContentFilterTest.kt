package com.example.data.repository

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regole del filtro condiviso "Nascondi contenuti non rilasciati".
 *
 * La data corrente è INIETTATA ([TODAY]): i test non dipendono dal giorno in cui
 * vengono eseguiti e non usano date hardcoded nel codice di produzione.
 */
class HideUpcomingContentFilterTest {

  private fun media(
    id: String,
    type: MediaType,
    releaseDate: String?,
    tvStatus: String? = null,
    backdropUrl: String? = "https://img.example/$id.jpg"
  ): MediaItem = MediaItem(
    id = id,
    title = id,
    synopsis = "",
    videoUrl = "",
    type = type,
    year = 2026,
    releaseDate = releaseDate,
    tvStatus = tvStatus,
    backdropUrl = backdropUrl
  )

  private fun catalogs(
    movies: List<MediaItem> = emptyList(),
    series: List<MediaItem> = emptyList(),
    extra: List<CatalogSection> = emptyList()
  ): HomeCatalogs = HomeCatalogs(
    heroItems = (movies + series).take(5),
    top10Movies = movies,
    top10Series = series,
    trendingMovies = movies,
    trendingSeries = series,
    popularMovies = movies,
    popularSeries = series,
    extraSections = extra,
    allMovies = movies,
    allSeries = series
  )

  @Test
  fun preferenzaOffNessunFiltroApplicato() {
    val future = media("film_futuro", MediaType.FILM, "2026-12-24")
    val source = catalogs(movies = listOf(future))

    // Stessa istanza: con OFF il filtro non ricostruisce né tocca la Home.
    assertSame(source, UnreleasedContentFilter.filterHomeCatalogs(source, enabled = false, todayIso = TODAY))
  }

  @Test
  fun filmConDataFuturaNascostoQuandoLaPreferenzaEOn() {
    val future = media("film_futuro", MediaType.FILM, "2026-07-01")
    val source = catalogs(movies = listOf(future, media("oggi", MediaType.FILM, TODAY)))

    val filtered = UnreleasedContentFilter.filterHomeCatalogs(source, enabled = true, todayIso = TODAY)

    assertEquals(listOf("oggi"), filtered.allMovies.map { it.id })
    assertEquals(listOf("oggi"), filtered.popularMovies.map { it.id })
    assertEquals(listOf("oggi"), filtered.top10Movies.map { it.id })
    assertEquals(listOf("oggi"), filtered.heroItems.map { it.id })
  }

  @Test
  fun serieNonAncoraRilasciataNascostaQuandoLaPreferenzaEOn() {
    val futureSeries = media("serie_futura", MediaType.SERIE_TV, "2026-09-10")
    val source = catalogs(series = listOf(futureSeries, media("serie_oggi", MediaType.SERIE_TV, TODAY)))

    val filtered = UnreleasedContentFilter.filterHomeCatalogs(source, enabled = true, todayIso = TODAY)

    assertEquals(listOf("serie_oggi"), filtered.allSeries.map { it.id })
    assertEquals(listOf("serie_oggi"), filtered.popularSeries.map { it.id })
    assertEquals(listOf("serie_oggi"), filtered.top10Series.map { it.id })
  }

  @Test
  fun contenutiGiaRilasciatiRestanoVisibili() {
    val past = media("film_passato", MediaType.FILM, "2020-01-01")
    val today = media("film_oggi", MediaType.FILM, TODAY)
    val source = catalogs(movies = listOf(past, today))

    val filtered = UnreleasedContentFilter.filterHomeCatalogs(source, enabled = true, todayIso = TODAY)

    assertEquals(listOf("film_passato", "film_oggi"), filtered.allMovies.map { it.id })
  }

  @Test
  fun dataSconosciutaOEncodingNonValidoNonNascondeIlContenuto() {
    val unknownDates = listOf(
      media("senza_data", MediaType.FILM, null),
      media("vuota", MediaType.FILM, ""),
      media("solo_anno", MediaType.SERIE_TV, "2026"),
      media("mese_impossibile", MediaType.FILM, "2026-13-01"),
      media("giorno_impossibile", MediaType.FILM, "2026-02-30"),
      media("formato_libero", MediaType.FILM, "24/12/2026")
    )
    val source = catalogs(movies = unknownDates)

    val filtered = UnreleasedContentFilter.filterHomeCatalogs(source, enabled = true, todayIso = TODAY)

    assertEquals(unknownDates.map { it.id }, filtered.allMovies.map { it.id })
  }

  @Test
  fun cataloghiNonTmdbSenzaMetadatiAffidabiliNonVengonoAlterati() {
    // Item come li produce un catalogo addon: solo l'anno, nessuna data completa.
    val addonMovies = (1..5).map { media("addon_film_$it", MediaType.FILM, null) }
    val addonSeries = (1..5).map { media("addon_serie_$it", MediaType.SERIE_TV, null) }
    val addonSection = CatalogSection(
      id = "addon_section",
      title = "Addon",
      origin = CatalogSourceOrigin.STREMIO_ADDON,
      items = addonMovies
    )
    val source = catalogs(movies = addonMovies, series = addonSeries, extra = listOf(addonSection))

    val filtered = UnreleasedContentFilter.filterHomeCatalogs(source, enabled = true, todayIso = TODAY)

    assertEquals(addonMovies, filtered.allMovies)
    assertEquals(addonSeries, filtered.allSeries)
    assertEquals(addonMovies, filtered.extraSections.single().items)
  }

  @Test
  fun serieConStatoGiaInOndaRestaVisibileAncheConDataIncoerente() {
    // Metadato di stato TMDB esplicito: la serie non è "non rilasciata".
    val returning = media(
      "serie_in_onda",
      MediaType.SERIE_TV,
      "2026-12-31",
      tvStatus = "Returning Series"
    )

    assertFalse(UnreleasedContentFilter.isUnreleased(returning, TODAY))
  }

  @Test
  fun righeExtraSvuotateDalFiltroVengonoRimosse() {
    val onlyFuture = CatalogSection(
      id = "novita_futura",
      title = "Solo futuri",
      items = listOf(media("film_futuro", MediaType.FILM, "2026-12-01"))
    )
    val mixed = CatalogSection(
      id = "novita_mista",
      title = "Mista",
      items = listOf(
        media("film_passato", MediaType.FILM, "2021-01-01"),
        media("film_futuro", MediaType.FILM, "2026-12-01")
      )
    )
    val source = catalogs(extra = listOf(onlyFuture, mixed))

    val filtered = UnreleasedContentFilter.filterHomeCatalogs(source, enabled = true, todayIso = TODAY)

    assertEquals(listOf("novita_mista"), filtered.extraSections.map { it.id })
    assertEquals(listOf("film_passato"), filtered.extraSections.single().items.map { it.id })
  }

  @Test
  fun laVistaFiltrataNonModificaLaSorgenteOriginale() {
    val future = media("film_futuro", MediaType.FILM, "2026-12-01")
    val source = catalogs(movies = listOf(future))

    UnreleasedContentFilter.filterHomeCatalogs(source, enabled = true, todayIso = TODAY)

    // La sorgente completa resta intatta: quando l'utente disattiva lo switch la
    // Home deve tornare identica senza una nuova risoluzione dei cataloghi.
    assertEquals(listOf(future), source.allMovies)
    assertTrue(source.extraSections.isEmpty())
  }

  private companion object {
    const val TODAY = "2026-06-15"
  }
}
