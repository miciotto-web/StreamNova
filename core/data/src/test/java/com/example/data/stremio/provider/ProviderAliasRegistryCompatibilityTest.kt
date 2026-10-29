package com.example.data.stremio.provider

import com.example.data.model.DefaultProviderAlias
import com.example.data.model.ProviderAliasRegistry
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.StremioCatalogTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica che, dopo la separazione `:core:data` ↔ `:core:designsystem`, il resolver
 * continui a lavorare sugli stessi metadati dichiarativi e a produrre gli stessi match.
 */
class ProviderAliasRegistryCompatibilityTest {

  private fun target(
    catalogId: String,
    catalogName: String? = null,
    addonName: String = "Custom Catalog Addon"
  ) = StremioCatalogTarget(
    addonId = "custom-addon",
    addonName = addonName,
    baseUrl = "https://example.test/manifest.json",
    catalog = StremioCatalogDefinition(type = "movie", id = catalogId, name = catalogName)
  )

  @Test
  fun `default alias providers mirror the shared registry without duplicates`() {
    val providers = DefaultProviderCatalogResolver.defaultAliasProviders()

    assertEquals(ProviderAliasRegistry.ALL.map { it.providerId }, providers.map { it.providerId })
    ProviderAliasRegistry.ALL.forEach { descriptor ->
      val provider = providers.first { it.providerId == descriptor.providerId }
      assertEquals(descriptor.providerName, provider.providerName)
      assertEquals(descriptor.providerAliases, provider.providerAliases)
    }
  }

  @Test
  fun `registry descriptors are accepted by the resolver view`() {
    val custom = DefaultProviderAlias("custom", "Custom+", setOf("custom", "custom plus"))
    val converted = DefaultProviderCatalogResolver.aliasProvidersOf(ProviderAliasRegistry.ALL + custom)

    assertEquals(ProviderAliasRegistry.ALL.size + 1, converted.size)
    assertTrue(converted.any { it.providerId == "custom" && it.providerAliases.contains("custom plus") })
  }

  @Test
  fun `shared aliases keep matching provider catalogs`() {
    val providers = DefaultProviderCatalogResolver.defaultAliasProviders()

    assertTrue(
      ProviderCatalogSourcePolicy.matchesProvider(
        providers.first { it.providerId == "disney" },
        target("disney_plus_movies", "Disney+ Movies")
      )
    )
    assertTrue(
      ProviderCatalogSourcePolicy.matchesProvider(
        providers.first { it.providerId == "paramount" },
        target("paramountplus_movies")
      )
    )
    assertTrue(
      ProviderCatalogSourcePolicy.matchesProvider(
        providers.first { it.providerId == "prime" },
        target("tmdb.movie.amazon", "Amazon Movies")
      )
    )
    assertTrue(
      ProviderCatalogSourcePolicy.matchesProvider(
        providers.first { it.providerId == "hbo" },
        target("max_top10_movies", "HBO Max Top 10")
      )
    )
  }

  @Test
  fun `aliases remain unique across the registry`() {
    val owners = ProviderAliasRegistry.ALL
      .flatMap { provider -> provider.providerAliases.map { alias -> alias to provider.providerId } }
      .groupBy({ it.first }, { it.second })
      .filterValues { it.distinct().size > 1 }

    assertTrue("Alias condivisi tra provider: $owners", owners.isEmpty())
  }
}
