package com.example.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaType
import com.example.data.stremio.StremioAddonRepository
import com.example.ui.components.ProviderConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * La schermata **Provider** non ha un catalogo Stremio di default: senza addon
 * utente il piano è VUOTO e la UI usa TMDB Discover (`with_watch_providers`).
 * Nessun addon built-in fornisce l'associazione titolo/provider necessaria a
 * verificarne la disponibilità.
 *
 * Le Categorie della Ricerca, senza addon utente, non interrogano alcun catalogo
 * Stremio: il tipo è servito nativamente da TMDB Discover, il gestore di cataloghi
 * predefinito di StreamNova.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProviderSourceTmdbDefaultTest {

  @Before
  fun setUp() {
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    StremioCatalogRepository.clearCache()
  }

  @After
  fun tearDown() {
    StremioAddonRepository.getInstalledAddons().forEach { StremioAddonRepository.removeAddon(it.id) }
    StremioAddonRepository.clearCaches()
    StremioCatalogRepository.clearCache()
  }

  @Test
  fun senzaAddonIlProviderNonHaTargetStremioELaSorgenteETmdb() {
    val provider = ProviderConstants.ALL.first()

    val moviePlan = StremioCatalogRepository.providerCatalogPlan(provider, MediaType.FILM)
    val seriesPlan = StremioCatalogRepository.providerCatalogPlan(provider, MediaType.SERIE_TV)

    assertTrue(moviePlan.resolved.isEmpty())
    assertTrue(seriesPlan.resolved.isEmpty())
    assertFalse(moviePlan.hasConfirmedBindings)
    assertFalse(seriesPlan.hasConfirmedBindings)
    assertEquals(ProviderCoverage.NONE, StremioCatalogRepository.providerCoverage(provider))
  }

  @Test
  fun senzaAddonLeCategorieNonHannoTargetStremioELaSorgenteETmdbNativa() {
    // I target degli addon utente restano vuoti...
    assertTrue(StremioCatalogRepository.getAvailableTargets().isEmpty())

    // ...e nessuna richiesta di catalogo Stremio viene costruita per la categoria:
    // TMDB Discover è la fonte nativa (routing deciso dal ViewModel).
    assertTrue(StremioCatalogRepository.findCategoryRequests(listOf("azione", "Action")).isEmpty())
    assertFalse(StremioCatalogRepository.hasUserGenreCatalogOfType(MediaType.FILM))
    assertFalse(StremioCatalogRepository.hasUserGenreCatalogOfType(MediaType.SERIE_TV))
  }
}
