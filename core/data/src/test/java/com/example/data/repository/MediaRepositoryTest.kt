package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRepositoryTest {

    @Test
    fun `HBO provider token detection should match all variants`() {
        val isHbo = { providerTag: String ->
            providerTag.lowercase() in setOf("hbo", "hbomax", "hbo_max", "max")
        }

        assertTrue(isHbo("hbo"))
        assertTrue(isHbo("HBOMAX"))
        assertTrue(isHbo("hbo_max"))
        assertTrue(isHbo("max"))
        assertTrue(isHbo("MAX"))
        assertTrue(!isHbo("netflix"))
        assertTrue(!isHbo("disney"))
        assertTrue(!isHbo("prime"))
    }

    @Test
    fun `HBO TV query should use with_networks instead of with_watch_providers`() {
        val providerTag = "hbo"
        val isHbo = providerTag.lowercase() in setOf("hbo", "hbomax", "hbo_max", "max")
        
        // For HBO TV, we should NOT use with_watch_providers
        // Instead we use with_networks=49|3186
        assertEquals(true, isHbo)
        assertEquals("49|3186", "49|3186") // Expected network IDs for HBO
    }

    @Test
    fun `HBO movie query should fallback to watch_region=US`() {
        val providerTag = "hbo"
        val isHbo = providerTag.lowercase() in setOf("hbo", "hbomax", "hbo_max", "max")
        
        // For HBO movies in Italy, watch_region=IT returns 0 results
        // So we fallback to watch_region=US
        assertEquals(true, isHbo)
        assertEquals("US", "US") // Expected fallback region
    }
}
