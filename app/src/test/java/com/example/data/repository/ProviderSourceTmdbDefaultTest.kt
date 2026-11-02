package com.example.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MediaType
import com.example.data.stremio.CinemetaAddon
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
 * Cinemeta non è un fallback dei cataloghi provider perché non espone
 * l'associazione titolo/provider necessaria a verificarne la disponibilità.
 *
 * La Ricerca per genere, invece, usa i target di sistema Cinemeta
 * ([StremioCatalogRepository.defaultCinemetaGenreTargets]) quando non ci sono
 * addon utente.
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
  fun laRicercaPerGenereUsaITargetCinemetaDiSistemaQuandoNonCiSonoAddon() {
    // I target degli addon utente restano vuoti...
    assertTrue(StremioCatalogRepository.getAvailableTargets().isEmpty())

    // ...ma le categorie della Ricerca hanno i due cataloghi `top` ufficiali di Cinemeta.
    val cinemetaTargets = StremioCatalogRepository.defaultCinemetaGenreTargets()
    assertEquals(2, cinemetaTargets.size)
    assertTrue(cinemetaTargets.all { it.addonId == CinemetaAddon.MANIFEST_ID })
    assertEquals(
      setOf(MediaType.FILM, MediaType.SERIE_TV),
      cinemetaTargets.map { StremioCatalogRepository.toMediaType(it.type) }.toSet()
    )
  }
}
