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
 * Dopo la migrazione a TMDB, la schermata **Provider** non ha più un catalogo
 * Stremio di default: senza addon utente il piano è VUOTO e la UI usa TMDB
 * Discover (`with_watch_providers`). Cinemeta non è più un fallback.
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
  fun laRicercaPerGenereNonHaUnCatalogoStremioDiSistema() {
    // Nessun addon: nessun target Stremio per le categorie. La sorgente nativa è
    // TMDB Discover, decisa dal ViewModel con gli id genere della categoria.
    assertTrue(StremioCatalogRepository.getAvailableTargets().isEmpty())
  }
}
