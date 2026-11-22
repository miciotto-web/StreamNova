package com.example.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratto del codec/nega della mappa offset sottotitoli. */
class SubtitleSyncCodecTest {

  @Test
  fun `roundtrip preserves offsets`() {
    val records = mapOf(
      "tmdb:1" to SubtitleSyncRecord(250, 1000L),
      "tmdb:1::season:1:episode:2" to SubtitleSyncRecord(-500, 2000L)
    )
    val decoded = SubtitleSyncCodec.decode(SubtitleSyncCodec.encode(records))
    assertEquals(250, decoded.getValue("tmdb:1").offsetMs)
    assertEquals(-500, decoded.getValue("tmdb:1::season:1:episode:2").offsetMs)
  }

  @Test
  fun `corrupted json degrades to empty map`() {
    assertTrue(SubtitleSyncCodec.decode("{not json").isEmpty())
    assertTrue(SubtitleSyncCodec.decode(null).isEmpty())
    assertTrue(SubtitleSyncCodec.decode("").isEmpty())
  }

  @Test
  fun `out of range offsets are discarded on decode`() {
    val json = """{"a":{"offsetMs":999999,"updatedAt":1},"b":{"offsetMs":100,"updatedAt":1}}"""
    val decoded = SubtitleSyncCodec.decode(json)
    assertTrue(decoded.containsKey("a").not())
    assertEquals(100, decoded.getValue("b").offsetMs)
  }

  @Test
  fun `prune keeps the newest entries`() {
    val records = (1..10).associate { index ->
      "key$index" to SubtitleSyncRecord(index * 10, updatedAt = index.toLong())
    }
    val pruned = SubtitleSyncCodec.prune(records, maxEntries = 3)
    assertEquals(3, pruned.size)
    assertEquals(setOf("key10", "key9", "key8"), pruned.keys)
  }

  @Test
  fun `blank keys are dropped`() {
    val json = """{"":{"offsetMs":10,"updatedAt":1},"ok":{"offsetMs":20,"updatedAt":1}}"""
    val decoded = SubtitleSyncCodec.decode(json)
    assertEquals(setOf("ok"), decoded.keys)
  }
}
