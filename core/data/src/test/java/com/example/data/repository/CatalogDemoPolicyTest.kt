package com.example.data.repository

import com.example.data.config.StreamNovaClientConfig
import com.example.data.config.StreamNovaConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Il seed/demo resta un comportamento esclusivo della TV: Mobile deve partire
 * senza catalogo inventato e senza episodi provvisori.
 */
class CatalogDemoPolicyTest {

  @Before
  fun setUp() {
    StreamNovaConfig.install(
      StreamNovaClientConfig(
        appId = "tv-test",
        versionName = "1.0.4",
        isDebug = true
      )
    )
  }

  @After
  fun tearDown() {
    StreamNovaConfig.install(
      StreamNovaClientConfig(
        appId = "unconfigured",
        versionName = "",
        isDebug = false
      )
    )
  }

  @Test
  fun ilClientTvMantieneIlCatalogoSeedEUIlEpisodiGenerati() {
    assertTrue(MediaRepository.initialCatalogForCurrentClient().isNotEmpty())
    assertTrue(MediaRepository.syntheticEpisodesEnabled())
  }

  @Test
  fun ilClientMobileNonEsponeSeedNeEpisodiSintetici() {
    StreamNovaConfig.install(
      StreamNovaClientConfig(
        appId = "mobile",
        versionName = "0.1.0",
        isDebug = true,
        includeDemoCatalog = false,
        generateSyntheticEpisodes = false
      )
    )

    assertTrue(MediaRepository.initialCatalogForCurrentClient().isEmpty())
    assertFalse(MediaRepository.syntheticEpisodesEnabled())
  }
}
