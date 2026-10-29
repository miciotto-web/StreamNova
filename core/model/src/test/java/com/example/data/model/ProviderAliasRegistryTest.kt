package com.example.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il registry è l'unica fonte dichiarativa di id, nomi e alias dei provider.
 * Data layer e design system lo leggono, quindi questi invarianti valgono per entrambi.
 */
class ProviderAliasRegistryTest {

  @Test
  fun `registry contains the expected providers in display order`() {
    assertEquals(
      listOf("netflix", "hbo", "disney", "prime", "apple", "paramount", "crunchyroll"),
      ProviderAliasRegistry.ALL.map { it.providerId }
    )
  }

  @Test
  fun `lookup by id exposes the declared name and aliases`() {
    val disney = ProviderAliasRegistry.byId("disney")
    assertEquals("Disney+", disney?.providerName)
    assertEquals(setOf("disney", "disney+", "disney plus"), disney?.providerAliases)
    assertEquals("Prime Video", ProviderAliasRegistry.nameOf("prime"))
    assertEquals(setOf("prime", "prime video", "amazon", "amazon prime"), ProviderAliasRegistry.aliasesOf("prime"))
  }

  @Test
  fun `unknown provider id has no invented metadata`() {
    assertNull(ProviderAliasRegistry.byId("unknown"))
    assertNull(ProviderAliasRegistry.nameOf("unknown"))
    assertTrue(ProviderAliasRegistry.aliasesOf("unknown").isEmpty())
  }

  @Test
  fun `an alias belongs to exactly one provider`() {
    val duplicated = ProviderAliasRegistry.ALL
      .flatMap { provider -> provider.providerAliases.map { it to provider.providerId } }
      .groupBy({ it.first }, { it.second })
      .filterValues { owners -> owners.distinct().size > 1 }

    assertTrue("Alias duplicati: $duplicated", duplicated.isEmpty())
  }

  @Test
  fun `every provider is matchable through its id and name`() {
    ProviderAliasRegistry.ALL.forEach { provider ->
      assertTrue(provider.providerId.isNotBlank())
      assertTrue(provider.providerName.isNotBlank())
      assertTrue(provider.providerAliases.isNotEmpty())
    }
  }
}
