package com.aistudio.streamnova.mobile.ui

import com.aistudio.streamnova.mobile.ui.screens.uniqueSourceKeys
import com.example.data.streaming.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione crash del selettore sorgenti mobile: due INSTALLAZIONI dello stesso
 * addon Stremio (Torrentio globale e Torrentio IT) restituiscono lo stesso torrent
 * con `fileIdx` null e la stessa identità visiva («Torrentio 🧲 1080p»). La chiave
 * del LazyColumn basata solo su serverName/streamUrl/infoHash collideva e chiudeva
 * l'app con `IllegalArgumentException: Key ... was already used`.
 *
 * Le chiavi generate da [uniqueSourceKeys] devono essere SEMPRE univoche.
 */
class MobileSourceKeysTest {

  private fun torboxSource(
    installId: String,
    fileIdx: Int? = null,
    hash: String = "77b4e714d3fc9336ddefa8623ec90f905b43f9d8"
  ) = StreamSource(
    streamUrl = null,
    quality = "1080p",
    serverName = "Torrentio 🧲 1080p",
    declaredQuality = "1080p",
    infoHash = hash,
    fileIdx = fileIdx,
    addonInstallId = installId
  )

  @Test
  fun `stesso torrent da due installazioni addon genera chiavi distinte`() {
    val keys = uniqueSourceKeys(
      listOf(
        torboxSource("https://torrentio.strem.fun"),
        torboxSource("https://torrentio-it.strem.fun")
      )
    )

    assertEquals(2, keys.size)
    assertEquals("Chiavi tutte diverse", keys.toSet().size, keys.size)
  }

  @Test
  fun `duplicato esatto viene disambiguato con suffisso progressivo`() {
    val keys = uniqueSourceKeys(
      listOf(
        torboxSource("stesso-install-id"),
        torboxSource("stesso-install-id")
      )
    )

    assertEquals(2, keys.size)
    assertEquals("Chiavi tutte diverse", keys.toSet().size, keys.size)
    assertTrue(keys[1].endsWith("#2"))
  }

  @Test
  fun `file diversi dello stesso torrent restano distinti`() {
    val keys = uniqueSourceKeys(
      listOf(
        torboxSource("stesso-install-id", fileIdx = 0),
        torboxSource("stesso-install-id", fileIdx = 1)
      )
    )

    assertEquals("Chiavi tutte diverse", keys.toSet().size, keys.size)
  }

  @Test
  fun `sorgenti http con stesso server e url vengono disambiguate`() {
    val http = StreamSource(
      streamUrl = "https://cdn.example.com/master.m3u8",
      quality = "Auto",
      serverName = "VixSrc"
    )
    val keys = uniqueSourceKeys(listOf(http, http.copy()))

    assertEquals(2, keys.size)
    assertEquals("Chiavi tutte diverse", keys.toSet().size, keys.size)
  }
}
