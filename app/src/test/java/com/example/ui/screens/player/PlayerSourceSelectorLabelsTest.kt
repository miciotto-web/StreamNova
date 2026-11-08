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
    fileIdx: Int? = null,
    addonInstallId: String? = null,
    releaseTitle: String? = null,
    headers: Map<String, String> = emptyMap()
  ) = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = name,
    declaredQuality = declaredQuality,
    addonName = addonName,
    infoHash = infoHash,
    fileIdx = fileIdx,
    addonInstallId = addonInstallId,
    releaseTitle = releaseTitle,
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

  @Test
  fun sameProviderAndResolutionAreDistinguishedByTorrentIdentity() {
    // Due sorgenti dello stesso addon con la stessa risoluzione: solo il torrent
    // realmente in riproduzione (infoHash) deve risultare corrente.
    val playing = source(
      name = "IlCorsaroViola",
      quality = "1080p",
      addonName = "IlCorsaroViola",
      infoHash = "HASH_PLAYING"
    )
    val sibling = source(
      name = "IlCorsaroViola",
      quality = "1080p",
      addonName = "IlCorsaroViola",
      infoHash = "HASH_SIBLING"
    )
    val entries = PlayerSourceSelector.buildEntries(listOf(sibling, playing), playing)

    assertFalse("La prima sorgente non è quella in riproduzione", entries[0].isCurrent)
    assertTrue("La sorgente con lo stesso infoHash in riproduzione deve essere marcata", entries[1].isCurrent)
    assertEquals(1, PlayerSourceSelector.currentIndexOf(listOf(sibling, playing), playing))
  }

  @Test
  fun differentFilesOfTheSameSeasonPackAreNotTheCurrentSource() {
    // Stesso torrent (season pack) ma file diverso: fileIdx distingue i due episodi.
    val playing = source("Pack", quality = "1080p", infoHash = "HASH_PACK", fileIdx = 0)
    val otherEpisode = source("Pack", quality = "1080p", infoHash = "HASH_PACK", fileIdx = 1)
    val entries = PlayerSourceSelector.buildEntries(listOf(otherEpisode, playing), playing)

    assertFalse(entries[0].isCurrent)
    assertTrue(entries[1].isCurrent)
  }

  @Test
  fun theResolvedCopyOfTheSameFileInASeasonPackIsStillCurrent() {
    // Sblocco TorBox on-demand: la sorgente corrente ha l'URL, la voce della lista
    // no. Hash e fileIdx restano l'identità reale della sorgente.
    val candidate = source("Pack", quality = "4K", infoHash = "HASH_PACK", fileIdx = 3)
    val resolved = candidate.copy(streamUrl = "https://cdn.example/unlocked.m3u8")
    val entries = PlayerSourceSelector.buildEntries(listOf(candidate), resolved)

    assertTrue(entries[0].isCurrent)
  }

  @Test
  fun theSameTorrentFromADifferentAddonInstallIsNotCurrent() {
    // Stesso torrent restituito da due configurazioni Stremio distinte: l'identità
    // dell'installazione fa parte della sorgente, quindi solo una voce è corrente.
    val playing = source(
      name = "Torrentio",
      quality = "1080p",
      infoHash = "HASH_SHARED",
      addonName = "Torrentio",
      addonInstallId = "https://torrentio.strem.fun/"
    )
    val otherInstall = source(
      name = "Torrentio",
      quality = "1080p",
      infoHash = "HASH_SHARED",
      addonName = "Torrentio",
      addonInstallId = "https://torrentioita.stremio-italia.eu/"
    )
    val entries = PlayerSourceSelector.buildEntries(listOf(otherInstall, playing), playing)

    assertFalse(entries[0].isCurrent)
    assertTrue(entries[1].isCurrent)
  }

  @Test
  fun duplicateIdentitiesMarkOnlyTheFirstListEntry() {
    // Due voci indistinguibili (stesso torrent, stesso file, stessa installazione):
    // la lista non deve mostrare DUE badge "In riproduzione".
    val playing = source("Addon", quality = "1080p", infoHash = "HASH_DUP", fileIdx = 0)
    val duplicate = playing.copy()
    val entries = PlayerSourceSelector.buildEntries(listOf(playing, duplicate), playing)

    assertTrue(entries[0].isCurrent)
    assertFalse(entries[1].isCurrent)
  }
}
