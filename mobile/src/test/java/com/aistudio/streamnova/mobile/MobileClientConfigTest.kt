package com.aistudio.streamnova.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MobileClientConfigTest {

  @Test
  fun ilClientMobileDisattivaSempreSeedDemoEdEpisodiSintetici() {
    val config = mobileClientConfig(
      versionName = "0.1.0",
      isDebug = true,
      tmdbApiKeyCandidates = listOf("test-key"),
      simklClientIdChannels = emptyList()
    )

    assertEquals("mobile", config.appId)
    assertFalse(config.includeDemoCatalog)
    assertFalse(config.generateSyntheticEpisodes)
  }
}
