package com.example.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test di [StremioAddonRepository.normalizeUrl].
 *
 * Verifica che il percorso di configurazione (predisposto da addon come Comet /
 * Torrentio / MediaFusion) resti intatto: in particolare la stringa Base64
 * URL-safe non deve perdere i caratteri `=`, `-`, `_`.
 */
class StremioAddonRepositoryUrlTest {

  @Test
  fun rimuoveIlSoloSuffissoManifestJson() {
    assertEquals(
      "https://torrentio.strem.fun/realdebrid=KEY",
      StremioAddonRepository.normalizeUrl("https://torrentio.strem.fun/realdebrid=KEY/manifest.json")
    )
  }

  @Test
  fun preservaLaStringaBase64UrlSafe() {
    val base64 = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9-_Ab"
    assertEquals(
      "https://comet.host/$base64",
      StremioAddonRepository.normalizeUrl("https://comet.host/$base64/manifest.json")
    )
  }

  @Test
  fun preservaIlPaddingBase64() {
    assertEquals(
      "https://comet.host/eyJ0eXA==",
      StremioAddonRepository.normalizeUrl("https://comet.host/eyJ0eXA==/manifest.json")
    )
  }

  @Test
  fun converteSchemaStremioInHttps() {
    assertEquals(
      "https://comet.host/eyJjb25maWcifQ",
      StremioAddonRepository.normalizeUrl("stremio://comet.host/eyJjb25maWcifQ/manifest.json")
    )
  }

  @Test
  fun preservaLaQueryStringDiConfigurazione() {
    assertEquals(
      "https://addon.example.com/token?x=y",
      StremioAddonRepository.normalizeUrl("https://addon.example.com/token/manifest.json?x=y")
    )
  }
}
