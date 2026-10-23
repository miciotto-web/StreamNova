package com.example.ui.screens.player

import com.example.data.streaming.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logica pura del selettore sorgenti del player.
 *
 * Non richiede Compose: verifica etichette (provider + qualita'), riconoscimento
 * della sorgente in riproduzione e costruzione delle righe del selettore.
 */
class PlayerSourceSelectorLabelsTest {

  private fun source(
    name: String,
    url: String? = null,
    quality: String = "Auto",
    declaredQuality: String = quality,
    addonName: String? = null,
    infoHash: String? = null,
    headers: Map<String, String> = emptyMap()
  ) = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = name,
    declaredQuality = declaredQuality,
    addonName = addonName,
    infoHash = infoHash,
    headers = headers
  )

  @Test
  fun labelShowsProviderAndQualityWhenKnown() {
    val entry = source("Provider Veloce", "https://cdn.example/a.m3u8", quality = "1080p")
    assertEquals("Provider Veloce · 1080p", PlayerSourceSelector.labelOf(entry))
  }

  @Test
  fun labelFallsBackToAddonNameWhenServerNameIsGeneric() {
    val entry = source(
      name = "Server generico",
      url = "https://cdn.example/a.m3u8",
      quality = "720p",
      addonName = "IlCorsaroViola IT"
    )
    assertEquals("IlCorsaroViola IT · 720p", PlayerSourceSelector.labelOf(entry))
  }

  @Test
  fun autoQualityIsNotShownAsAKnownQuality() {
    val entry = source("Provider Veloce", "https://cdn.example/a.m3u8", quality = "Auto")
    assertEquals("Provider Veloce", PlayerSourceSelector.labelOf(entry))
    assertEquals(null, PlayerSourceSelector.qualityLabelOf(entry))
  }

  @Test
  fun declaredQualityWinsOverGenericQuality() {
    val entry = source(
      name = "Provider",
      url = "https://cdn.example/a.m3u8",
      quality = "Auto",
      declaredQuality = "4K"
    )
    assertEquals("Provider · 4K", PlayerSourceSelector.labelOf(entry))
  }

  @Test
  fun currentSourceIsDetectedByUrl() {
    val playing = source("Provider A", "https://cdn.example/a.m3u8")
    val other = source("Provider B", "https://cdn.example/b.m3u8")
    val entries = PlayerSourceSelector.buildEntries(listOf(playing, other), playing)

    assertTrue(entries[0].isCurrent)
    assertFalse(entries[1].isCurrent)
    assertEquals(0, PlayerSourceSelector.currentIndexOf(listOf(playing, other), playing))
  }

  @Test
  fun currentSourceIsDetectedEvenWhenItWasResolvedAfterwards() {
    // La sorgente in riproduzione e' stata risolta (es. sblocco TorBox): non e'
    // piu' uguale per struttura alla voce della lista, ma e' la stessa sorgente.
    val candidate = source("Provider A", url = null, quality = "1080p", infoHash = "ABC123")
    val resolved = candidate.copy(streamUrl = "https://cdn.example/resolved.m3u8")
    val entries = PlayerSourceSelector.buildEntries(listOf(candidate), resolved)

    assertTrue("La sorgente in riproduzione deve essere riconosciuta", entries[0].isCurrent)
  }

  @Test
  fun noCurrentSourceLeavesEveryRowUnmarked() {
    val entries = PlayerSourceSelector.buildEntries(
      listOf(source("A", "https://cdn.example/a.m3u8")),
      null
    )
    assertFalse(entries[0].isCurrent)
    assertEquals(-1, PlayerSourceSelector.currentIndexOf(entries.map { it.source }, null))
  }
}
