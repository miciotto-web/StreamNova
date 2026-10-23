package com.example.data.streaming

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La stagione/episodio richiesti devono arrivare **identici** a ogni provider e
 * le sorgenti dei provider alternativi non devono essere perse nella lista
 * finale (fast-start incluso).
 */
class StreamManagerEpisodePropagationTest {

  private class RecordingProvider(
    override val providerId: String,
    private val sources: List<StreamSource>
  ) : StreamProvider {

    data class Call(
      val tmdbId: Int,
      val isTv: Boolean,
      val season: Int?,
      val episode: Int?,
      val title: String?,
      val year: Int?
    )

    val calls = mutableListOf<Call>()

    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      calls += Call(tmdbId, isTv, season, episode, title, year)
      return sources
    }
  }

  private fun source(name: String, quality: String, url: String) = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = name
  )

  @Test
  fun episodioArrivaAiProviderConStagioneENumeroEsatti() = runBlocking {
    val alt = RecordingProvider(
      "Alternativo",
      listOf(source("Alternativo", "720p", "https://cdn.example.test/ep.m3u8"))
    )
    val manager = StreamManager(
      providers = listOf(alt),
      timeoutMs = 3_000,
      maxConcurrentProviders = 1
    )

    manager.resolveFlow(
      tmdbId = 1399,
      isTv = true,
      season = 3,
      episode = 7,
      title = "Serie Test",
      year = 2011
    ).toList()

    val call = alt.calls.single()
    assertEquals(1399, call.tmdbId)
    assertTrue(call.isTv)
    assertEquals(3, call.season)
    assertEquals(7, call.episode)
    assertEquals("Serie Test", call.title)
    assertEquals(2011, call.year)
  }

  @Test
  fun perUnFilmStagioneEEpisodioRestanoNull() = runBlocking {
    val provider = RecordingProvider(
      "FilmProvider",
      listOf(source("FilmProvider", "1080p", "https://cdn.example.test/film.mp4"))
    )
    val manager = StreamManager(
      providers = listOf(provider),
      timeoutMs = 3_000,
      maxConcurrentProviders = 1
    )

    manager.resolveFlow(
      tmdbId = 27205,
      isTv = false,
      title = "Film Test",
      year = 2010
    ).toList()

    val call = provider.calls.single()
    assertTrue(!call.isTv)
    assertNull(call.season)
    assertNull(call.episode)
  }

  @Test
  fun ogniProviderRiceveLaStessaCoppiaStagioneEpisodio() = runBlocking {
    val first = RecordingProvider(
      "Primo",
      listOf(source("Primo", "1080p", "https://cdn.example.test/primario.m3u8"))
    )
    val second = RecordingProvider(
      "Secondo",
      listOf(source("Secondo", "720p", "https://cdn.example.test/alternativo.m3u8"))
    )
    val manager = StreamManager(
      providers = listOf(first, second),
      timeoutMs = 3_000,
      maxConcurrentProviders = 2
    )

    manager.resolveFlow(
      tmdbId = 100,
      isTv = true,
      season = 2,
      episode = 10,
      title = "Serie",
      year = 2020
    ).toList()

    assertEquals(1, first.calls.size)
    assertEquals(1, second.calls.size)
    (first.calls + second.calls).forEach { call ->
      assertEquals(2, call.season)
      assertEquals(10, call.episode)
    }
  }

  @Test
  fun leSorgentiAlternativeRestanoNellaListaFinale() = runBlocking {
    val vix = RecordingProvider(
      "VixSrc",
      listOf(source("VixSrc", "1080p", "https://cdn.example.test/vix.m3u8"))
    )
    val alt = RecordingProvider(
      "2Embed",
      listOf(source("2Embed", "720p", "https://cdn.example.test/alt.m3u8"))
    )
    val manager = StreamManager(
      providers = listOf(vix, alt),
      timeoutMs = 3_000,
      maxConcurrentProviders = 2
    )

    val emissions = manager.resolveFlow(
      tmdbId = 200,
      isTv = true,
      season = 1,
      episode = 3,
      title = "Serie",
      year = 2022
    ).toList()

    val final = emissions.last()
    assertEquals(2, final.size)
    assertEquals(setOf("VixSrc", "2Embed"), final.map { it.serverName }.toSet())
    // Il ranking resta per qualità: 1080p prima del 720p.
    assertEquals("VixSrc", final.first().serverName)
  }
}
