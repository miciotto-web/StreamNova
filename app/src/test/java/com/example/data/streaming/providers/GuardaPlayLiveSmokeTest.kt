package com.example.data.streaming.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Smoke test **live** (rete reale) di GuardaPlay.
 *
 * Non fa parte della suite CI: si esegue solo con
 * `STREAMNOVA_LIVE_TESTS=1`. Verifica la catena completa
 * ricerca -> articolo -> trembed -> Uqload sul sito reale.
 */
class GuardaPlayLiveSmokeTest {

  @Test
  fun risolveUnFilmDalSitoReale() = runBlocking {
    assumeTrue(
      "smoke live disattivato (STREAMNOVA_LIVE_TESTS != 1)",
      System.getenv("STREAMNOVA_LIVE_TESTS") == "1"
    )

    val sources = GuardaPlayProvider().getStreams(
      tmdbId = 0,
      isTv = false,
      season = null,
      episode = null,
      title = "Deep Water - Incubo degli abissi",
      year = 2026
    )

    assertTrue("GuardaPlay non ha restituito sorgenti", sources.isNotEmpty())
    assertTrue(
      "nessuna sorgente HTTP: ${sources.map { it.streamUrl }}",
      sources.any {
        it.streamUrl?.startsWith("http://") == true ||
          it.streamUrl?.startsWith("https://") == true
      }
    )
    println(
      "GuardaPlay live OK -> " + sources.joinToString {
        "${it.serverName}:${it.streamUrl?.substringBefore('?')}"
      }
    )
  }
}
