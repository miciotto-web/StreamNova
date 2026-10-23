package com.example.data.repository

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.stremio.CinemetaAddon
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * La localizzazione della sinossi tocca SOLO l'Hero Banner.
 *
 * Cinemeta restituisce `description` sempre in inglese: [StremioCatalogRepository]
 * sostituisce il testo dei titoli dell'Hero, mentre poster, titoli, ordine, id,
 * provider e tutte le altre righe della Home restano identici.
 */
class HomeHeroSynopsisLocalizationTest {

  private fun media(
    id: String,
    title: String,
    type: MediaType,
    synopsis: String = ENGLISH_SYNOPSIS,
    backdropUrl: String? = "https://image.tmdb.org/t/p/w1280/$id.jpg",
    posterUrl: String? = "https://image.tmdb.org/t/p/w500/$id.jpg"
  ) = MediaItem(
    id = id,
    title = title,
    synopsis = synopsis,
    videoUrl = "",
    backdropUrl = backdropUrl,
    posterUrl = posterUrl,
    type = type
  )

  private fun section(
    catalogId: String,
    mediaType: MediaType,
    items: List<MediaItem>
  ) = CatalogSection(
    id = "stremio_${CinemetaAddon.MANIFEST_ID}_" +
      "${if (mediaType == MediaType.SERIE_TV) TYPE_SERIES else TYPE_MOVIE}_$catalogId",
    title = "$catalogId • ${CinemetaAddon.DISPLAY_NAME}",
    addonId = CinemetaAddon.MANIFEST_ID,
    addonName = CinemetaAddon.DISPLAY_NAME,
    catalogId = catalogId,
    mediaType = mediaType,
    items = items
  )

  private fun defaultSections(): List<CatalogSection> = listOf(
    section(
      CinemetaAddon.CATALOG_POPULAR,
      MediaType.FILM,
      listOf(media("tt0137523", "Fight Club", MediaType.FILM))
    ),
    section(
      CinemetaAddon.CATALOG_POPULAR,
      MediaType.SERIE_TV,
      listOf(media("tt0903747", "Breaking Bad", MediaType.SERIE_TV))
    ),
    section(
      CinemetaAddon.CATALOG_TOP_RATED,
      MediaType.FILM,
      listOf(media("tt0111161", "Le ali della libertà", MediaType.FILM))
    )
  )

  @Test
  fun soloGliItemDellHeroRicevonoLaSinossiLocalizzata() = runBlocking {
    var localizedBatch: List<String> = emptyList()

    val catalogs = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = defaultSections(),
      userSections = emptyList(),
      allMedia = emptyList(),
      heroSynopsisLocalizer = { hero ->
        localizedBatch = hero.map { it.id }
        hero.map { it.copy(synopsis = "Sinossi italiana di ${it.title}") }
      }
    )

    assertEquals(listOf("tt0137523", "tt0903747", "tt0111161"), localizedBatch)
    assertEquals(
      listOf(
        "Sinossi italiana di Fight Club",
        "Sinossi italiana di Breaking Bad",
        "Sinossi italiana di Le ali della libertà"
      ),
      catalogs.heroItems.map { it.synopsis }
    )
    // Le altre righe restano in inglese: nessuna localizzazione fuori dall'Hero.
    assertEquals(ENGLISH_SYNOPSIS, catalogs.popularMovies.single().synopsis)
    assertEquals(ENGLISH_SYNOPSIS, catalogs.popularSeries.single().synopsis)
    assertEquals(ENGLISH_SYNOPSIS, catalogs.top10Movies.single().synopsis)
  }

  @Test
  fun laLocalizzazioneNonAlteraTitoliPosterOrdineEId() = runBlocking {
    val base = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = defaultSections(),
      userSections = emptyList(),
      allMedia = emptyList()
    )

    val localized = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = defaultSections(),
      userSections = emptyList(),
      allMedia = emptyList(),
      heroSynopsisLocalizer = { hero ->
        hero.map { it.copy(synopsis = "Sinossi italiana di ${it.title}") }
      }
    )

    assertEquals(base.heroItems.map { it.id }, localized.heroItems.map { it.id })
    assertEquals(base.heroItems.map { it.title }, localized.heroItems.map { it.title })
    assertEquals(base.heroItems.map { it.posterUrl }, localized.heroItems.map { it.posterUrl })
    assertEquals(base.heroItems.map { it.backdropUrl }, localized.heroItems.map { it.backdropUrl })
    assertEquals(base.heroItems.map { it.provider }, localized.heroItems.map { it.provider })
    assertNotEquals(base.heroItems.map { it.synopsis }, localized.heroItems.map { it.synopsis })
  }

  @Test
  fun unErroreDelLocalizerMantieneLeSinossiOriginali() = runBlocking {
    val catalogs = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = defaultSections(),
      userSections = emptyList(),
      allMedia = emptyList(),
      heroSynopsisLocalizer = { throw IllegalStateException("TMDB non raggiungibile") }
    )

    assertEquals(3, catalogs.heroItems.size)
    assertEquals(
      List(3) { ENGLISH_SYNOPSIS },
      catalogs.heroItems.map { it.synopsis }
    )
  }

  @Test
  fun senzaLocalizerIlComportamentoRestaQuelloDiPrima() {
    val catalogs = StremioCatalogRepository.buildHomeCatalogs(
      defaultSections = defaultSections(),
      userSections = emptyList(),
      allMedia = emptyList()
    )

    assertEquals(List(3) { ENGLISH_SYNOPSIS }, catalogs.heroItems.map { it.synopsis })
  }

  private companion object {
    const val ENGLISH_SYNOPSIS =
      "An insomniac office worker and a devil-may-care soap maker form an underground fight club."
  }
}
