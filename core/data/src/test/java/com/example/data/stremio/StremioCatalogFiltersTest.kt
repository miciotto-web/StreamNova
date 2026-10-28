package com.example.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica il filtro rigido dei cataloghi/addon che non devono mai comparire
 * ("Top Film Nova" / "Top serie Nova" / "Nova stream addon") applicato al punto di
 * ingresso del manifest, e la generalizzazione della precedenza su Cinemeta
 * ([StremioAddonRepository.isCustomCatalogAddon]).
 */
class StremioCatalogFiltersTest {

  private val novaManifest = StremioManifest(
    id = "org.nova.streamaddon",
    name = "Nova stream addon",
    types = listOf("movie", "series"),
    resources = listOf("catalog"),
    catalogs = listOf(
      StremioCatalogDefinition(type = "movie", id = "top_film_nova", name = "Top Film Nova"),
      StremioCatalogDefinition(type = "series", id = "top_serie_nova", name = "Top serie Nova")
    )
  )

  private val customManifest = StremioManifest(
    id = "org.xperience",
    name = "Xperience",
    types = listOf("movie", "series"),
    resources = listOf("catalog"),
    catalogs = listOf(
      StremioCatalogDefinition(type = "movie", id = "netflix_top10", name = "Netflix Top 10")
    )
  )

  @Test
  fun iCataloghiNovaPresentiNelManifestNonVengonoMaiEsposti() {
    // Le definizioni restano nel manifest grezzo...
    assertTrue(novaManifest.catalogs?.isNotEmpty() == true)
    // ...ma il filtro le rimuove dal punto di ingresso.
    assertTrue(novaManifest.validCatalogs().isEmpty())
    assertFalse(novaManifest.supportsCatalogForType("movie"))
    assertFalse(novaManifest.supportsCatalogForType("series"))
  }

  @Test
  fun unCatalogoRegolareSopravviveAlFiltro() {
    assertEquals(listOf("netflix_top10"), customManifest.validCatalogs().map { it.effectiveId })
    assertTrue(customManifest.supportsCatalogForType("movie"))
  }

  @Test
  fun ilFiltroRimuoveSoloLeVociIgnorateInUnManifestMisto() {
    val mixed = customManifest.copy(
      catalogs = customManifest.catalogs.orEmpty() + novaManifest.catalogs.orEmpty()
    )
    assertEquals(listOf("netflix_top10"), mixed.validCatalogs().map { it.effectiveId })
  }

  @Test
  fun lAddonNovaNonContaComeAddonConCataloghiPersonali() {
    assertFalse(
      StremioAddonRepository.isCustomCatalogAddon(
        InstalledAddon("https://nova.example.com", novaManifest)
      )
    )
    assertTrue(
      StremioAddonRepository.isCustomCatalogAddon(
        InstalledAddon("https://xperience.example.com", customManifest)
      )
    )
  }

  @Test
  fun cinemetaNonContaComeAddonConCataloghiPersonali() {
    assertFalse(StremioAddonRepository.isCustomCatalogAddon(CinemetaAddon.addon))
  }
}
