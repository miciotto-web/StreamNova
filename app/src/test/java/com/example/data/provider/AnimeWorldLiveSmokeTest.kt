package com.example.data.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Smoke test **live** (rete reale) del client AnimeWorld.
 *
 * Non fa parte della suite CI: viene eseguito solo impostando la variabile
 * d'ambiente `STREAMNOVA_LIVE_TESTS=1`. Serve a verificare che il parser
 * sopravviva al markup reale del sito e che l'API `episode/info` restituisca
 * un URL riproducibile.
 *
 * Esecuzione manuale:
 * ```
 * STREAMNOVA_LIVE_TESTS=1 ./gradlew :app:testDebugUnitTest \
 *   --tests "com.example.data.provider.AnimeWorldLiveSmokeTest"
 * ```
 */
class AnimeWorldLiveSmokeTest {

  @Test
  fun risolveNarutoEpisodio1DalSitoReale() = runBlocking {
    assumeTrue(
      "smoke live disattivato (STREAMNOVA_LIVE_TESTS != 1)",
      System.getenv("STREAMNOVA_LIVE_TESTS") == "1"
    )

    val sources = AnimeWorldClient().resolve(
      queries = listOf("Naruto"),
      episodeCandidates = listOf(1),
      year = 2002,
      isTv = true
    )

    assertTrue("AnimeWorld non ha restituito sorgenti", sources.isNotEmpty())
    assertTrue(
      "nessuna sorgente con URL HTTP riproducibile: ${sources.map { it.streamUrl }}",
      sources.any { source ->
        source.streamUrl?.startsWith("http://") == true ||
          source.streamUrl?.startsWith("https://") == true
      }
    )
    // Regressione del falso match: "Boruto: Naruto Next Generations" e
    // "Naruto Shippuden" non sono la stessa opera di "Naruto".
    assertTrue(
      "nessuna sorgente Naruto trovata: ${sources.map { it.streamUrl }}",
      sources.any { it.streamUrl?.contains("Naruto", ignoreCase = true) == true }
    )
    assertTrue(
      "spin-off/sequel non devono essere inclusi: ${sources.map { it.streamUrl }}",
      sources.none { source ->
        source.streamUrl?.contains("Boruto", ignoreCase = true) == true ||
          source.streamUrl?.contains("Shippuden", ignoreCase = true) == true
      }
    )
    println(
      "AnimeWorld live OK -> " + sources.joinToString {
        "${it.serverName}:${it.streamUrl?.substringBefore('?')} (ita=${it.isItalian})"
      }
    )
  }
}
