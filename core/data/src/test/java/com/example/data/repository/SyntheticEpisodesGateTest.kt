package com.example.data.repository

import com.example.data.config.StreamNovaClientConfig
import com.example.data.config.StreamNovaConfig
import com.example.data.local.CachedMediaItemEntity
import com.example.data.local.MediaCacheMapper
import com.example.data.model.MediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyntheticEpisodesGateTest {

  private lateinit var previousConfig: StreamNovaClientConfig

  @Before
  fun saveConfig() {
    previousConfig = StreamNovaConfig.current
  }

  @After
  fun restoreConfig() {
    StreamNovaConfig.install(previousConfig)
  }

  private fun config(generateSyntheticEpisodes: Boolean) = StreamNovaClientConfig(
    appId = if (generateSyntheticEpisodes) "tv" else "mobile",
    versionName = "test",
    isDebug = true,
    includeDemoCatalog = generateSyntheticEpisodes,
    generateSyntheticEpisodes = generateSyntheticEpisodes
  )

  private fun seriesEntity() = CachedMediaItemEntity(
    id = "tmdb_tv_292741",
    tmdbId = 292741,
    title = "Nocturne",
    originalTitle = "Nocturne",
    synopsis = "Serie",
    videoUrl = "",
    resolution = "UHD_4K",
    qualityTags = "4K",
    backdropUrl = null,
    posterUrl = null,
    logoUrl = null,
    type = MediaType.SERIE_TV.name,
    year = 2026,
    durationMinutes = 50,
    seasonsCount = 2,
    rating = 7.5f,
    genres = "Crime",
    director = null,
    cast = "",
    provider = null
  )

  @Test
  fun ilMapperNonGeneraEpisodiProvvisoriPerIlClientMobile() {
    StreamNovaConfig.install(config(generateSyntheticEpisodes = false))

    val media = with(MediaCacheMapper) { seriesEntity().toMediaItem() }

    assertTrue(media.episodes.isEmpty())
  }

  @Test
  fun ilMapperMantieneGliEpisodiProvvisoriPerIlClientTv() {
    StreamNovaConfig.install(config(generateSyntheticEpisodes = true))

    val media = with(MediaCacheMapper) { seriesEntity().toMediaItem() }

    assertEquals(9, media.episodes.size)
  }

  @Test
  fun ilHelperGeneraSoloQuandoIlClientLoAbilita() {
    StreamNovaConfig.install(config(generateSyntheticEpisodes = false))
    assertTrue(MediaRepository.syntheticEpisodesIfEnabled(1, "Serie", null, null, 2).isEmpty())

    StreamNovaConfig.install(config(generateSyntheticEpisodes = true))
    assertEquals(9, MediaRepository.syntheticEpisodesIfEnabled(1, "Serie", null, null, 2).size)
  }
}
