package com.example.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica il confronto condiviso dei tipi usato per la SELEZIONE dei cataloghi.
 *
 * Un catalogo che dichiara `tv` (es. TvVoo) è una fonte di serie TV esattamente come
 * uno che dichiara `series`: senza questa normalizzazione la Home richiedeva solo
 * `movie`/`series` e il catalogo veniva scartato prima della richiesta HTTP.
 * Il tipo ORIGINALE resta `tv`, così l'endpoint prodotto è `catalog/tv/...`.
 */
class StremioCatalogTypeSelectionTest {

  private val tvVooManifest = StremioManifest(
    id = "org.stremio.vavoo.clean",
    name = "TvVoo | ElfHosted",
    types = listOf("tv"),
    resources = listOf("catalog", "meta", "stream"),
    idPrefixes = listOf("vavoo", "vavoo_"),
    catalogs = listOf(
      StremioCatalogDefinition(
        type = "tv",
        id = "vavoo_tv_home_it",
        name = "TvVoo TV",
        extra = listOf(
          mapOf("name" to "genre", "options" to listOf("Tutti", "Rai", "Mediaset", "Sport"))
        )
      ),
      StremioCatalogDefinition(
        type = "tv",
        id = "vavoo_search_it",
        name = "TvVoo Search",
        extra = listOf(mapOf("name" to "search"))
      )
    )
  )

  @Test
  fun manifestTvVooEsponeIlCatalogoTvAncheQuandoSiChiedonoLeSerie() {
    assertEquals(
      listOf("vavoo_tv_home_it", "vavoo_search_it"),
      tvVooManifest.catalogsForType(TYPE_SERIES).map { it.effectiveId }
    )
    assertTrue(tvVooManifest.supportsCatalogForType(TYPE_SERIES))
  }

  @Test
  fun iCanaliTvNonVengonoPresentatiComeFilm() {
    assertTrue(tvVooManifest.catalogsForType(TYPE_MOVIE).isEmpty())
    assertFalse(tvVooManifest.supportsCatalogForType(TYPE_MOVIE))
  }

  @Test
  fun ilTipoOriginaleDelCatalogoRestaTv() {
    val catalog = tvVooManifest.catalogsForType(TYPE_SERIES).first()
    assertEquals("tv", catalog.effectiveType)
    assertTrue(catalog.isSeries)
    assertFalse(catalog.isMovie)

    assertEquals(
      "https://vavoo.example/stremio/catalog/tv/vavoo_tv_home_it.json",
      StremioAddonRepository.buildCatalogUrl(
        baseUrl = "https://vavoo.example/stremio",
        type = catalog.effectiveType,
        id = catalog.effectiveId
      )
    )
  }

  @Test
  fun gliAliasDelleSerieSonoEquivalentiAlTipoSeries() {
    listOf("series", "SERIES", "tv", "TV", "show", "tvshow", " tv ").forEach { declared ->
      assertTrue("$declared deve contare come serie", catalogTypeMatches(declared, TYPE_SERIES))
      val manifest = tvVooManifest.copy(
        types = listOf(declared),
        catalogs = listOf(StremioCatalogDefinition(type = declared, id = "c1", name = "C1"))
      )
      assertTrue(manifest.supportsCatalogForType(TYPE_SERIES))
      assertEquals(1, manifest.catalogsForType(TYPE_SERIES).size)
    }
  }

  @Test
  fun iCataloghiSerieRestanoVisibiliQuandoSiChiedeIlTipoSeries() {
    assertTrue(catalogTypeMatches(TYPE_SERIES, TYPE_SERIES))
    assertTrue(catalogTypeMatches("show", "series"))
  }

  @Test
  fun unCatalogoMovieNonVieneInclusoTraLeSerie() {
    val movieManifest = tvVooManifest.copy(
      types = listOf(TYPE_MOVIE),
      catalogs = listOf(StremioCatalogDefinition(type = TYPE_MOVIE, id = "home_movies", name = "Home Movies"))
    )
    assertTrue(movieManifest.catalogsForType(TYPE_SERIES).isEmpty())
    assertFalse(movieManifest.supportsCatalogForType(TYPE_SERIES))
    assertEquals(1, movieManifest.catalogsForType(TYPE_MOVIE).size)

    assertFalse(catalogTypeMatches(TYPE_MOVIE, TYPE_SERIES))
    assertFalse(catalogTypeMatches("tv", TYPE_MOVIE))
    assertFalse(catalogTypeMatches("", TYPE_SERIES))
    assertFalse(catalogTypeMatches("series", ""))
  }

  @Test
  fun gliAddonConSoloCataloghiTvContanoComeAddonConCataloghiPersonali() {
    assertTrue(
      StremioAddonRepository.isCustomCatalogAddon(
        InstalledAddon("https://vavoo.example/stremio", tvVooManifest)
      )
    )
  }

  @Test
  fun cinemetaMantieneLaSelezionePerTipoInvariata() {
    val cinemeta = CinemetaAddon.addon.manifest
    assertEquals(
      listOf(CinemetaAddon.CATALOG_POPULAR),
      cinemeta.catalogsForType(TYPE_MOVIE).map { it.effectiveId }.take(1)
    )
    assertTrue(cinemeta.supportsCatalogForType(TYPE_MOVIE))
    assertTrue(cinemeta.supportsCatalogForType(TYPE_SERIES))
    assertTrue(cinemeta.catalogsForType(TYPE_SERIES).isNotEmpty())
    // Nessun catalogo Cinemeta dichiara un alias: il confronto resta stretto.
    assertTrue(cinemeta.catalogsForType(TYPE_SERIES).all { it.effectiveType == TYPE_SERIES })
  }
}
