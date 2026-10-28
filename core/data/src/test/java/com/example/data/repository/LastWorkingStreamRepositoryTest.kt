package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della logica **pura** della cache "Riusa l'ultimo link":
 * validità temporale, (de)serializzazione degli header e ricostruzione della sorgente.
 */
class LastWorkingStreamRepositoryTest {

  private val hour = 3_600_000L

  @Test
  fun `durata in ms calcolata dalle ore`() {
    assertEquals(0L, LastWorkingStreamRepository.cacheDurationMs(0))
    assertEquals(6 * hour, LastWorkingStreamRepository.cacheDurationMs(6))
    assertEquals(24 * hour, LastWorkingStreamRepository.cacheDurationMs(24))
    assertEquals(168 * hour, LastWorkingStreamRepository.cacheDurationMs(168))
    // Le durate negative non producono finestre valide.
    assertEquals(0L, LastWorkingStreamRepository.cacheDurationMs(-5))
  }

  @Test
  fun `cache valida entro la finestra`() {
    val now = 10_000_000L
    val cachedAt = now - 3 * hour
    assertTrue(LastWorkingStreamRepository.isCacheValid(cachedAt, durationHours = 24, now = now))
  }

  @Test
  fun `cache valida esattamente al limite della finestra`() {
    val now = 10_000_000L
    val cachedAt = now - 24 * hour
    assertTrue(LastWorkingStreamRepository.isCacheValid(cachedAt, durationHours = 24, now = now))
  }

  @Test
  fun `cache scaduta oltre la finestra`() {
    val now = 10_000_000L
    val cachedAt = now - (24 * hour + 1)
    assertFalse(LastWorkingStreamRepository.isCacheValid(cachedAt, durationHours = 24, now = now))
  }

  @Test
  fun `timestamp futuro non e valido`() {
    val now = 10_000_000L
    assertFalse(LastWorkingStreamRepository.isCacheValid(now + 1000, durationHours = 24, now = now))
  }

  @Test
  fun `durata non positiva disattiva sempre la cache`() {
    val now = 10_000_000L
    assertFalse(LastWorkingStreamRepository.isCacheValid(now, durationHours = 0, now = now))
    assertFalse(LastWorkingStreamRepository.isCacheValid(now, durationHours = -1, now = now))
  }

  @Test
  fun `header serializzati e riletti senza perdite`() {
    val headers = mapOf("Referer" to "https://example.com", "User-Agent" to "StreamNova/1.0")
    val json = LastWorkingStreamRepository.encodeHeaders(headers)
    assertEquals(headers, LastWorkingStreamRepository.decodeHeaders(json))
  }

  @Test
  fun `header vuoti non vengono serializzati`() {
    assertNull(LastWorkingStreamRepository.encodeHeaders(emptyMap()))
    assertTrue(LastWorkingStreamRepository.decodeHeaders(null).isEmpty())
    assertTrue(LastWorkingStreamRepository.decodeHeaders("").isEmpty())
    assertTrue(LastWorkingStreamRepository.decodeHeaders("non-json").isEmpty())
  }

  @Test
  fun `ricostruzione sorgente conserva url qualita e header`() {
    val cached = CachedWorkingStream(
      mediaId = "movie_550",
      seasonNumber = LastWorkingStreamRepository.NO_EPISODE,
      episodeNumber = LastWorkingStreamRepository.NO_EPISODE,
      streamUrl = "https://cdn.example.com/film.mkv",
      streamTitle = "Film.2024.1080p.mkv",
      streamQuality = "1080p",
      serverName = "TorBox Instant",
      headers = mapOf("Referer" to "https://torbox.app"),
      isProgressive = true,
      cachedAtTimestamp = 123L
    )

    val source = cached.toStreamSource()
    assertEquals("https://cdn.example.com/film.mkv", source.streamUrl)
    assertEquals("1080p", source.quality)
    assertEquals("TorBox Instant", source.serverName)
    assertEquals("Film.2024.1080p.mkv", source.releaseTitle)
    assertEquals(mapOf("Referer" to "https://torbox.app"), source.headers)
    assertEquals(true, source.isProgressive)
    assertTrue(source.isCached)
  }

  @Test
  fun `ricostruzione sorgente applica default sicuri`() {
    val cached = CachedWorkingStream(
      mediaId = "tv_1396_s1e1",
      seasonNumber = 1,
      episodeNumber = 1,
      streamUrl = "https://cdn.example.com/ep1.mkv",
      streamTitle = null,
      streamQuality = null,
      serverName = null,
      isProgressive = null
    )

    val source = cached.toStreamSource()
    assertEquals("Auto", source.quality)
    assertEquals("Ultimo flusso", source.serverName)
    assertNull(source.releaseTitle)
    assertTrue(source.headers.isEmpty())
    assertNull(source.isProgressive)
  }
}
