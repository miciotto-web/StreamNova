package com.example.data.stremio

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica la classificazione dei canali LIVE degli addon Stremio (es. TvVoo):
 * un canale non è una serie TV, ma conserva il tipo ORIGINALE `tv` dichiarato dal
 * catalogo per le richieste `meta`/`stream`.
 */
class StremioLiveChannelTest {

  private val tvVoo = InstalledAddon(
    baseUrl = "https://vavoo.example/stremio",
    manifest = StremioManifest(
      id = "org.stremio.vavoo.clean",
      name = "TvVoo | ElfHosted",
      types = listOf("tv"),
      resources = listOf("catalog", "meta", "stream"),
      idPrefixes = listOf("vavoo", "vavoo_"),
      catalogs = listOf(
        StremioCatalogDefinition(
          type = "tv",
          id = "vavoo_tv_home_it",
          name = "TvVoo TV",
          extra = listOf(mapOf("name" to "genre", "options" to listOf("Tutti", "Rai", "Sport")))
        ),
        StremioCatalogDefinition(type = "tv", id = "vavoo_search_it", name = "TvVoo Search")
      )
    )
  )

  private val resolver = StremioLiveChannelClassifier.AddonResolver { mediaId ->
    if (mediaId.contains("vavoo", ignoreCase = true)) tvVoo else null
  }

  @Test
  fun gliIdDiCanaleSonoQuelliNonImdbNeTmdb() {
    assertTrue(StremioLiveChannelClassifier.isChannelStyleId("vavoo_rai1"))
    assertTrue(StremioLiveChannelClassifier.isChannelStyleId("vavoo_canale5"))
    assertFalse(StremioLiveChannelClassifier.isChannelStyleId("tt0944947"))
    assertFalse(StremioLiveChannelClassifier.isChannelStyleId("tt0944947:1:2"))
    assertFalse(StremioLiveChannelClassifier.isChannelStyleId("tmdb:1399"))
    assertFalse(StremioLiveChannelClassifier.isChannelStyleId("tmdb_tv_1399"))
    assertFalse(StremioLiveChannelClassifier.isChannelStyleId("1399"))
    assertFalse(StremioLiveChannelClassifier.isChannelStyleId(""))
    assertFalse(StremioLiveChannelClassifier.isChannelStyleId(null))
  }

  @Test
  fun unCanaleVieneRiconosciutoTramiteLAddonCheLoEspone() {
    val key = StremioLiveChannelClassifier.channelKeyFor("vavoo_rai1", resolver)
    assertEquals("channel:org.stremio.vavoo.clean|vavoo_rai1", key)
    assertTrue(StremioLiveChannelClassifier.isLiveChannel(key, listOf(tvVoo)))
  }

  @Test
  fun unIdSconosciutoNonDiventaMaiUnCanale() {
    assertNull(StremioLiveChannelClassifier.channelKeyFor("vavoo_sconosciuto", StremioLiveChannelClassifier.AddonResolver { null }))
    assertNull(StremioLiveChannelClassifier.channelKeyFor("tt0944947", resolver))
    assertFalse(
      StremioLiveChannelClassifier.isLiveChannel(
        "channel:org.stremio.vavoo.clean|vavoo_rai1",
        emptyList()
      )
    )
  }

  @Test
  fun unAddonDisabilitatoNonEsponeCanali() {
    val disabled = tvVoo.copy(isEnabled = false)
    val key = StremioLiveChannelClassifier.channelKeyFor("vavoo_rai1", resolver)
    assertFalse(StremioLiveChannelClassifier.isLiveChannel(key, listOf(disabled)))
  }

  @Test
  fun laChiaveDelCanaleSiScomponeSenzaPerdereIlTipoOriginale() {
    val key = StremioLiveChannelClassifier.channelKey("org.stremio.vavoo.clean", "vavoo_rai1")
    assertEquals("org.stremio.vavoo.clean" to "vavoo_rai1", StremioLiveChannelClassifier.parseChannelKey(key))
    assertNull(StremioLiveChannelClassifier.parseChannelKey("vavoo_rai1"))
    assertNull(StremioLiveChannelClassifier.parseChannelKey("channel:noaddon"))

    // Il tipo usato per `meta`/`stream` è quello del catalogo, mai convertito in `series`.
    assertEquals("tv", StremioLiveChannelClassifier.catalogType(tvVoo, "vavoo_tv_home_it"))
    assertEquals("tv", StremioLiveChannelClassifier.catalogType(tvVoo, "vavoo_search_it"))
    assertNull(StremioLiveChannelClassifier.catalogType(tvVoo, "catalogo_inesistente"))
  }

  @Test
  fun unaSerieNormaleRestaSulDettaglioTradizionale() {
    val series = MediaItem(
      id = "tt0903747",
      title = "Breaking Bad",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 1396
    )
    assertEquals(MediaType.SERIE_TV, series.type)
    assertNull(StremioLiveChannelClassifier.channelKeyFor(series.id, resolver))
  }

  /**
   * Il canale è mappato come SERIE_TV dal catalogo `tv` (la Home lo mostra come
   * riga serie), ma il suo id resta quello dell'addon: è questo che gli permette
   * di essere trattato come live e non come serie con stagioni.
   */
  @Test
  fun laCardDelCanaleConservaIdOriginaleETipoDelCatalogo() {
    val mapped = StremioCatalogMapper.toMediaItem(
      StremioMetaItem(id = "vavoo_rai1", type = "tv", name = "Rai 1"),
      addonName = "TvVoo | ElfHosted",
      fallbackType = MediaType.SERIE_TV
    )
    assertEquals("vavoo_rai1", mapped.id)
    assertEquals(MediaType.SERIE_TV, mapped.type)
    assertEquals("TvVoo | ElfHosted", mapped.provider)
    assertTrue(StremioLiveChannelClassifier.isChannelStyleId(mapped.id))
  }
}
