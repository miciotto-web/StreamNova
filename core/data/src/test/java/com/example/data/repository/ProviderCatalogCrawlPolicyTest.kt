package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy del crawl dei cataloghi provider.
 *
 * Il bootstrap della Home deve chiedere UNA sola pagina per provider/tipo; la
 * ProviderScreen continua a poter chiedere tutte le pagine disponibili on-demand.
 */
class ProviderCatalogCrawlPolicyTest {

  @Test
  fun `il bootstrap limita il crawl provider alla prima pagina`() {
    assertEquals(1, MediaRepository.INITIAL_PROVIDER_PAGES)

    val bootstrapLimit = MediaRepository.INITIAL_PROVIDER_PAGES
    assertTrue(ProviderCatalogCrawlPolicy.shouldFetchPage(1, totalPages = 10, pageLimit = bootstrapLimit))
    assertFalse(ProviderCatalogCrawlPolicy.shouldFetchPage(2, totalPages = 10, pageLimit = bootstrapLimit))
    assertFalse(ProviderCatalogCrawlPolicy.shouldFetchPage(10, totalPages = 10, pageLimit = bootstrapLimit))
  }

  @Test
  fun `la paginazione on demand conserva tutte le pagine disponibili`() {
    val onDemandLimit = MediaRepository.MAX_PROVIDER_PAGES
    for (page in 1..10) {
      assertTrue(
        "pagina $page deve essere richiedibile on-demand",
        ProviderCatalogCrawlPolicy.shouldFetchPage(page, totalPages = 10, pageLimit = onDemandLimit)
      )
    }
    assertFalse(
      "oltre total_pages non si deve chiedere nulla",
      ProviderCatalogCrawlPolicy.shouldFetchPage(11, totalPages = 10, pageLimit = onDemandLimit)
    )
  }

  @Test
  fun `la prima pagina si chiede anche quando total_pages non e ancora noto`() {
    assertTrue(ProviderCatalogCrawlPolicy.shouldFetchPage(1, totalPages = 0, pageLimit = 25))
    assertFalse(ProviderCatalogCrawlPolicy.shouldFetchPage(2, totalPages = 0, pageLimit = 25))
  }

  @Test
  fun `il limite non puo superare MAX_PROVIDER_PAGES ne essere nullo`() {
    assertFalse(
      ProviderCatalogCrawlPolicy.shouldFetchPage(
        page = MediaRepository.MAX_PROVIDER_PAGES + 1,
        totalPages = 100,
        pageLimit = MediaRepository.MAX_PROVIDER_PAGES
      )
    )
    assertFalse(ProviderCatalogCrawlPolicy.shouldFetchPage(1, totalPages = 10, pageLimit = 0))
  }
}
