package com.example.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratto del payload Sync e regole di fusione della libreria. Sono la parte
 * critica del protocollo: un cambio implicito qui rompe la compatibilità tra
 * dispositivi con build diverse.
 */
class SyncPayloadTest {

  // ── Serializzazione ─────────────────────────────────────────────────────────

  @Test
  fun `payload round trip preserva i dati`() {
    val payload = SyncConfigPayload(
      schemaVersion = SYNC_SCHEMA_VERSION,
      deviceName = "TV Soggiorno",
      createdAtEpochMs = 1_700_000_000_000L,
      appSettings = SyncAppSettingsPayload(
        torBoxApiKey = "tb-key",
        openSubtitlesApiKey = "os-key",
        simklClientId = null,
        torBoxInstantDebridEnabled = true,
        streamingEngineMode = "DEBRID_TORBOX",
        autoplayEnabled = false,
        appLanguage = "it",
        parentalControlEnabled = true,
        parentalControlPinHash = "a".repeat(64),
        parentalControlLevel = "16",
        tmdbGenreFallbackEnabled = true,
        hideUnreleasedContent = false,
        userProviderBindingsJson = "[]",
        cwShowRow = true,
        cwLayout = "WIDE",
        cwEpisodeThumbnails = false,
        cwBlurUnwatched = true,
        cwNextFromFurthest = false,
        cwShowUnaired = true,
        cwSort = "TITLE"
      ),
      playbackSettings = SyncPlaybackSettingsPayload(
        preferredResolution = "FULL_HD_1080P",
        autoPlayNextEpisodeEnabled = true,
        autoResume = true,
        reuseLastWorkingStreamEnabled = true,
        lastWorkingStreamCacheDurationHours = 24,
        prioritizeItalianAudio = true,
        preferredAudioLanguage = "it",
        subtitlesEnabled = false,
        forcedSubtitlesEnabled = true,
        preferredSubtitleLanguage = "it",
        subtitleSize = "LARGE",
        subtitleBackground = "BLACK",
        subtitleOpacity = "FIFTY",
        subtitlePosition = "BOTTOM",
        loadingOverlayEnabled = true,
        loadingStatusEnabled = true,
        pauseOverlayEnabled = false,
        parentalGuideEnabled = false,
        targetBuffer = "EXTENDED",
        initialBuffer = "FAST",
        backBuffer = "EXTENDED",
        audioPassthroughEnabled = true,
        audioTunnelingEnabled = false,
        decoderFallbackMode = "PREFER",
        autoFrameRateMatching = true,
        dolbyVisionFallbackEnabled = true,
        debugOverlayEnabled = false
      ),
      addons = listOf(SyncAddonPayload(url = "https://torrentio.strem.fun/config.json", name = "Torrentio", enabled = true)),
      simklAccount = SyncSimklAccountPayload(
        accessToken = "acc", refreshToken = "ref", accountId = 42L, username = "miciotto",
        accountType = "premium", tokenExpiresAt = "2026-01-01", tokenExpiresAtEpochMs = 1L,
        scope = "media:write", flow = "V2_DEVICE", sessionExpired = false
      ),
      mediaItems = listOf(testMedia(id = "tt1156398", favorite = true)),
      episodeProgress = listOf(
        SyncEpisodeProgressPayload("tt1156398", 1, 3, 600_000L, 2_400_000L, 5L, null)
      )
    )

    val json = SyncConfigPayloadAdapter.toJson(payload)
    val parsed = SyncConfigPayloadAdapter.fromJson(json)

    assertEquals(payload, parsed)
  }

  @Test
  fun `campi assenti nel json valgono null e non fanno crashare`() {
    // Un payload scritto da una build più vecchia: le sezioni mancano del tutto.
    val json = """{"schemaVersion":1,"deviceName":"Vecchia","createdAtEpochMs":1}"""
    val parsed = SyncConfigPayloadAdapter.fromJson(json)

    assertEquals("Vecchia", parsed?.deviceName)
    assertNull(parsed?.appSettings)
    assertNull(parsed?.simklAccount)
    assertTrue(parsed?.mediaItems?.isEmpty() == true)
  }

  @Test
  fun `categorie ignote in rete vengono scartate`() {
    val parsed = SyncCategory.parseList(listOf("SETTINGS", "NON_ESISTE", "LIBRARY", null))
    assertEquals(setOf(SyncCategory.SETTINGS, SyncCategory.LIBRARY), parsed)
    assertTrue(SyncCategory.parseList(null).isEmpty())
  }

  // ── Fusione libreria ────────────────────────────────────────────────────────

  @Test
  fun `libreria vuota sul ricevente - tutto arriva come aggiunto`() {
    val incoming = listOf(
      testMedia(id = "a", favorite = true),
      testMedia(id = "b", favorite = false, progress = 100L)
    )
    val result = mergeLibrary(
      localMedia = emptyList(),
      incomingMedia = incoming,
      localEpisodes = emptyList(),
      incomingEpisodes = listOf(testEpisode("a", episode = 1, updatedAt = 5L))
    )

    assertEquals(2, result.mediaAdded)
    assertEquals(0, result.mediaUpdated)
    assertEquals(1, result.episodesAdded)
    assertEquals(incoming, result.mediaRows)
  }

  @Test
  fun `visione locale piu recente vince ma il preferito in arrivo non si perde`() {
    val local = testMedia(id = "a", favorite = false, lastWatchedAt = 200L)
    val incoming = testMedia(id = "a", favorite = true, lastWatchedAt = 100L)

    val result = mergeLibrary(listOf(local), listOf(incoming), emptyList(), emptyList())

    assertEquals(0, result.mediaAdded)
    assertEquals(1, result.mediaUpdated)
    val row = result.mediaRows.single()
    assertEquals(200L, row.lastWatchedAt)
    assertTrue(row.isFavorite)
  }

  @Test
  fun `riga in arrivo piu recente vince ma il preferito locale resta`() {
    val local = testMedia(id = "a", favorite = true, lastWatchedAt = 100L)
    val incoming = testMedia(id = "a", favorite = false, lastWatchedAt = 300L)

    val result = mergeLibrary(listOf(local), listOf(incoming), emptyList(), emptyList())

    val row = result.mediaRows.single()
    assertEquals(300L, row.lastWatchedAt)
    assertTrue(row.isFavorite)
  }

  @Test
  fun `righe solo locali restano al loro posto`() {
    val localOnly = testMedia(id = "locale", favorite = true)
    val result = mergeLibrary(
      localMedia = listOf(localOnly),
      incomingMedia = listOf(testMedia(id = "altro", favorite = true)),
      localEpisodes = emptyList(),
      incomingEpisodes = emptyList()
    )

    // La fusione produce le righe DA SCRIVERE (upsert): quella solo locale non è
    // tra le in arrivo e quindi non viene toccata.
    assertTrue(result.mediaRows.none { it.id == "locale" })
    assertEquals(1, result.mediaAdded)
  }

  @Test
  fun `episodi - vince il updatedAt piu recente, in parita arriva quello in arrivo`() {
    val local = listOf(testEpisode("a", season = 1, episode = 2, updatedAt = 10L, progress = 50L))
    val incoming = listOf(
      testEpisode("a", season = 1, episode = 2, updatedAt = 9L, progress = 999L),
      testEpisode("a", season = 1, episode = 3, updatedAt = 4L, progress = 10L)
    )

    val result = mergeLibrary(
      localMedia = emptyList(),
      incomingMedia = emptyList(),
      localEpisodes = local,
      incomingEpisodes = incoming
    )

    val kept = result.episodeRows.first { it.episodeNumber == 2 }
    assertEquals(50L, kept.progressMs) // il locale è più recente: resta
    assertEquals(1, result.episodesAdded) // l'episodio 3 è nuovo
    assertEquals(0, result.episodesUpdated) // l'episodio 2 non viene sovrascritto
  }

  // ── Enum tolleranti ─────────────────────────────────────────────────────────

  @Test
  fun `enum ignoto vale il fallback`() {
    val values = com.example.data.prefs.ContinueWatchingLayout.entries.toTypedArray()
    assertEquals(
      com.example.data.prefs.ContinueWatchingLayout.WIDE,
      syncEnumFromName(values, "WIDE", com.example.data.prefs.ContinueWatchingLayout.CARD)
    )
    assertEquals(
      com.example.data.prefs.ContinueWatchingLayout.CARD,
      syncEnumFromName(values, "INVENTATO", com.example.data.prefs.ContinueWatchingLayout.CARD)
    )
  }

  // ── Fixture ─────────────────────────────────────────────────────────────────

  private fun testMedia(
    id: String,
    favorite: Boolean,
    progress: Long = 0L,
    lastWatchedAt: Long? = null
  ): SyncMediaItemPayload = SyncMediaItemPayload(
    id = id,
    tmdbId = 1,
    title = "Titolo $id",
    originalTitle = "Original $id",
    synopsis = "",
    videoUrl = "",
    resolution = "FHD_1080P",
    qualityTags = "",
    backdropUrl = null,
    posterUrl = null,
    logoUrl = null,
    type = "FILM",
    year = 2024,
    durationMinutes = 100,
    seasonsCount = null,
    rating = 7.5f,
    genres = "",
    director = null,
    cast = "",
    provider = null,
    isTrending = false,
    isTop10 = false,
    isFavorite = favorite,
    currentProgressMs = progress,
    totalDurationMs = 6_000_000L,
    lastWatchedSeason = null,
    lastWatchedEpisode = null,
    lastWatchedAt = lastWatchedAt,
    inContinueWatching = progress > 0,
    completedAt = null,
    ageRating = null,
    favoriteSource = null,
    progressSource = null,
    importedFromSimkl = false,
    cachedAt = 1L
  )

  private fun testEpisode(
    mediaId: String,
    season: Int = 1,
    episode: Int,
    updatedAt: Long,
    progress: Long = 0L
  ): SyncEpisodeProgressPayload = SyncEpisodeProgressPayload(
    mediaId = mediaId,
    seasonNumber = season,
    episodeNumber = episode,
    progressMs = progress,
    durationMs = 2_400_000L,
    updatedAt = updatedAt,
    source = null
  )
}
