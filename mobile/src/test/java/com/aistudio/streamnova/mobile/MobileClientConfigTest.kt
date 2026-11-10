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

  /**
   * L'updater in-app del client Mobile interroga SOLO il canale GitHub dedicato
   * all'app Mobile: mai le release della TV (repo `StreamNova`), altrimenti le
   * due app si proporrebbero a vicenda aggiornamenti sbagliati.
   */
  @Test
  fun ilCanaleAggiornamentiMobileEIlRepoDedicato() {
    val config = mobileClientConfig(
      versionName = "0.1.0",
      isDebug = true,
      tmdbApiKeyCandidates = listOf("test-key"),
      simklClientIdChannels = emptyList()
    )

    assertEquals("miciotto-web/StreamNova-Mobile", config.updateRepoSlug)
  }
}
