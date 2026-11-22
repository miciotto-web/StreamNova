package com.example.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratto delle ricerche recenti (Nova Discovery). */
class RecentSearchesCodecTest {

  @Test
  fun `insert puts the newest query first`() {
    val list = RecentSearchesCodec.insert(listOf("dune", "alien"), "matrix")
    assertEquals(listOf("matrix", "dune", "alien"), list)
  }

  @Test
  fun `insert deduplicates case-insensitively and moves to top`() {
    val list = RecentSearchesCodec.insert(listOf("Dune", "alien"), "dune")
    assertEquals(listOf("dune", "alien"), list)
  }

  @Test
  fun `insert ignores too short queries`() {
    val list = RecentSearchesCodec.insert(listOf("dune"), "a")
    assertEquals(listOf("dune"), list)
  }

  @Test
  fun `caps the list size`() {
    var list = emptyList<String>()
    repeat(20) { index -> list = RecentSearchesCodec.insert(list, "query $index") }
    assertEquals(RecentSearchesCodec.MAX_ENTRIES, list.size)
    assertEquals("query 19", list.first())
  }

  @Test
  fun `roundtrip and corrupted json`() {
    val list = listOf("dune", "alien")
    assertEquals(list, RecentSearchesCodec.decode(RecentSearchesCodec.encode(list)))
    assertTrue(RecentSearchesCodec.decode("{rotto").isEmpty())
    assertTrue(RecentSearchesCodec.decode(null).isEmpty())
  }
}
