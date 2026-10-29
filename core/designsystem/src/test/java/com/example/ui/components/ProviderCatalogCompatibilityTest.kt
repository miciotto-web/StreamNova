package com.example.ui.components

import com.example.data.model.ProviderAliasDescriptor
import com.example.data.model.ProviderAliasRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il catalogo visuale non duplica gli alias: li deriva dal registry condiviso in
 * `:core:model`. Questi test impediscono che le due viste divergano di nuovo.
 */
class ProviderCatalogCompatibilityTest {

  @Test
  fun `design system catalog mirrors the shared registry`() {
    assertEquals(ProviderAliasRegistry.ALL.size, ProviderConstants.ALL.size)

    ProviderConstants.ALL.forEach { provider ->
      val descriptor = ProviderAliasRegistry.byId(provider.id)
      assertNotNull("Provider assente dal registry: ${provider.id}", descriptor)
      assertEquals(descriptor!!.providerName, provider.name)
      assertEquals(descriptor.providerName, provider.providerName)
      assertEquals(descriptor.providerAliases, provider.aliases)
      assertEquals(descriptor.providerAliases, provider.providerAliases)
    }
  }

  @Test
  fun `alias sets keep the previous matching contract`() {
    assertEquals(setOf("netflix"), ProviderConstants.NETFLIX.aliases)
    assertEquals(setOf("hbo", "hbo max", "max"), ProviderConstants.HBO.aliases)
    assertEquals(setOf("disney", "disney+", "disney plus"), ProviderConstants.DISNEY.aliases)
    assertEquals(
      setOf("prime", "prime video", "amazon", "amazon prime"),
      ProviderConstants.PRIME_VIDEO.aliases
    )
    assertEquals(setOf("apple", "apple tv", "apple tv+", "appletv"), ProviderConstants.APPLE_TV.aliases)
    assertEquals(setOf("paramount", "paramount+", "paramount plus"), ProviderConstants.PARAMOUNT_PLUS.aliases)
    assertEquals(setOf("crunchyroll"), ProviderConstants.CRUNCHYROLL.aliases)
  }

  @Test
  fun `provider ids are unique`() {
    val ids = ProviderConstants.ALL.map { it.id }
    assertEquals(ids.size, ids.distinct().size)
  }

  @Test
  fun `descriptor view is available without importing data internals`() {
    val descriptors: List<ProviderAliasDescriptor> = ProviderConstants.ALL

    assertEquals(ProviderConstants.ALL.size, descriptors.size)
    assertTrue(descriptors.all { it.providerAliases.isNotEmpty() })
  }
}
