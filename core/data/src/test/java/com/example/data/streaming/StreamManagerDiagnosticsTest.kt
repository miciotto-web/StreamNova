package com.example.data.streaming

import com.example.data.streaming.diagnostics.StreamAttempt
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fallback, timeout, cancellazione, concorrenza e diagnostica di
 * [StreamManager] con provider fake: nessuna dipendenza dalla rete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamManagerDiagnosticsTest {

  private fun source(name: String, url: String, quality: String = "1080p") = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = name
  )

  private fun provider(
    id: String,
    delayMs: Long = 0L,
    result: suspend () -> List<StreamSource>
  ): StreamProvider = object : StreamProvider {
    override val providerId: String = id

    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      if (delayMs > 0L) delay(delayMs)
      return result()
    }
  }

  private fun sink(): Pair<StreamDiagnosticSink, MutableList<StreamAttempt>> {
    val attempts = Collections.synchronizedList(mutableListOf<StreamAttempt>())
    return StreamDiagnosticSink { attempt -> attempts += attempt } to attempts
  }

  @Test
  fun providerSenzaRisultatiNonBloccaIlProviderFunzionante() = runTest {
    val empty = provider(id = "Empty") { emptyList() }
    val good = provider(id = "Good", delayMs = 100L) {
      listOf(source("Good", "https://cdn.example/good/index.m3u8"))
    }
    val (diagnostics, attempts) = sink()

    val manager = StreamManager(
      diagnostics = diagnostics,
      providers = listOf(empty, good),
      timeoutMs = 3_000L
    )

    val last = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()

    assertEquals(listOf("Good"), last.map { it.serverName })
    assertTrue(
      "il provider a vuoto deve produrre NO_RESULTS",
      attempts.any { it.providerId == "Empty" && it.outcome == StreamAttemptOutcome.NO_RESULTS }
    )
    assertTrue(
      "il provider funzionante deve produrre SUCCESS",
      attempts.any { it.providerId == "Good" && it.outcome == StreamAttemptOutcome.SUCCESS }
    )
  }

  @Test
  fun timeoutDiUnProviderNonAnnullaGliAltriEProduceFallback() = runTest {
    val slow = provider(id = "SlowTimeout", delayMs = 5_000L) {
      listOf(source("Slow", "https://cdn.example/slow/index.m3u8"))
    }
    val fast = provider(id = "FastAfterTimeout", delayMs = 50L) {
      listOf(source("Fast", "https://cdn.example/fast/index.m3u8", quality = "720p"))
    }
    val (diagnostics, attempts) = sink()

    val manager = StreamManager(
      diagnostics = diagnostics,
      providers = listOf(slow, fast),
      timeoutMs = 300L
    )

    val last = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()

    assertEquals(listOf("Fast"), last.map { it.serverName })
    assertEquals(
      StreamAttemptOutcome.TIMEOUT,
      attempts.first { it.providerId == "SlowTimeout" }.outcome
    )
    assertEquals(
      StreamAttemptOutcome.SUCCESS,
      attempts.first { it.providerId == "FastAfterTimeout" }.outcome
    )
  }

  @Test
  fun eccezioneDiUnProviderNonBloccaGliAltriEEHaCategoriaCorretta() = runTest {
    val broken = provider(id = "BrokenHttp", delayMs = 50L) {
      throw IOException("HTTP 503 su https://cdn.example/e/x")
    }
    val good = provider(id = "GoodAfterError", delayMs = 150L) {
      listOf(source("Good", "https://cdn.example/good/index.m3u8"))
    }
    val (diagnostics, attempts) = sink()

    val manager = StreamManager(
      diagnostics = diagnostics,
      providers = listOf(broken, good),
      timeoutMs = 3_000L
    )

    val last = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()

    assertEquals(listOf("Good"), last.map { it.serverName })
    assertEquals(
      StreamAttemptOutcome.HTTP_ERROR,
      attempts.first { it.providerId == "BrokenHttp" }.outcome
    )
  }

  @Test
  fun cancellazioneDellaRicercaAnnullaIEffettiviLavoriFigli() = runTest {
    var slowCancelled = false
    val slow = object : StreamProvider {
      override val providerId: String = "SlowCancellable"

      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = try {
        awaitCancellation()
        emptyList()
      } catch (e: CancellationException) {
        slowCancelled = true
        throw e
      }
    }
    val fast = provider(id = "FastCancellationProbe") {
      listOf(source("Fast", "https://cdn.example/fast/index.m3u8"))
    }

    val manager = StreamManager(
      providers = listOf(slow, fast),
      timeoutMs = 10_000L
    )

    val firstEmission = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").first()
    assertTrue(firstEmission.isNotEmpty())

    advanceUntilIdle()
    assertTrue("il provider in volo deve essere cancellato con la ricerca", slowCancelled)
  }

  @Test
  fun limiteDiConcorrenzaRispettatoETuttiIProviderInterrogati() = runTest {
    val active = AtomicInteger(0)
    val peak = AtomicInteger(0)
    val providers = (1..6).map { index ->
      object : StreamProvider {
        override val providerId: String = "ConcurrentP$index"

        override suspend fun getStreams(
          tmdbId: Int,
          isTv: Boolean,
          season: Int?,
          episode: Int?,
          title: String?,
          year: Int?
        ): List<StreamSource> {
          val now = active.incrementAndGet()
          peak.updateAndGet { current -> maxOf(current, now) }
          try {
            delay(100L)
            return listOf(source("P$index", "https://cdn.example/p$index/index.m3u8"))
          } finally {
            active.decrementAndGet()
          }
        }
      }
    }

    val manager = StreamManager(
      providers = providers,
      timeoutMs = 5_000L,
      maxConcurrentProviders = 2
    )

    val last = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()

    assertEquals("nessun provider deve restare fuori", 6, last.size)
    assertTrue("il limite deve essere rispettato (peak=$peak)", peak.get() <= 2)
    assertEquals("i provider devono girare in parallelo a coppie", 2, peak.get())
  }

  @Test
  fun stessoUrlDaProviderDiversiVieneDeduplicato() = runTest {
    val url = "https://cdn.example/same/index.m3u8"
    val first = provider(id = "First") { listOf(source("Uno", url)) }
    val second = provider(id = "Second", delayMs = 50L) { listOf(source("Due", url)) }

    val manager = StreamManager(
      providers = listOf(first, second),
      timeoutMs = 3_000L
    )

    val last = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()

    assertEquals("lo stesso URL non deve comparire due volte", 1, last.size)
  }

  @Test
  fun raccoltaCompletaETSelezioneRapidaRestanoDistinte() = runTest {
    val fast = provider(id = "FastQuick", delayMs = 10L) {
      listOf(source("Fast", "https://cdn.example/fast/index.m3u8"))
    }
    val slow = provider(id = "SlowCollectAll", delayMs = 2_000L) {
      listOf(source("Slow", "https://cdn.example/slow/index.m3u8", quality = "720p"))
    }
    val manager = StreamManager(
      providers = listOf(fast, slow),
      timeoutMs = 4_000L
    )

    val all = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()
    assertEquals(setOf("Fast", "Slow"), all.map { it.serverName }.toSet())

    val quick = manager.resolve(tmdbId = 550, isTv = false, title = "Titolo")
    assertEquals("la selezione rapida non attende i provider lenti", listOf("Fast"), quick.map { it.serverName })
  }

  @Test
  fun episodiConservanoMetadatiEErroreDiUnProviderNonLiPerde() = runTest {
    var captured: CapturedCall? = null
    val good = object : StreamProvider {
      override val providerId: String = "EpisodeGood"

      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> {
        captured = CapturedCall(tmdbId, isTv, season, episode, title, year)
        return listOf(
          StreamSource(
            streamUrl = "https://cdn.example/s0307/index.m3u8",
            quality = "1080p",
            serverName = "Serie",
            releaseTitle = "Serie.S03E07.1080p"
          )
        )
      }
    }
    val broken = provider(id = "EpisodeBroken") { throw IOException("episodio non trovato") }

    val manager = StreamManager(
      providers = listOf(broken, good),
      timeoutMs = 3_000L
    )

    val last = manager.resolveFlow(
      tmdbId = 42,
      isTv = true,
      season = 3,
      episode = 7,
      title = "Serie",
      year = 2021
    ).toList().last()

    assertEquals(CapturedCall(42, true, 3, 7, "Serie", 2021), captured)
    assertEquals(1, last.size)
    assertEquals("Serie.S03E07.1080p", last.first().releaseTitle)
    assertEquals("1080p", last.first().quality)
  }

  @Test
  fun risultatiTardiviDellaRicercaAnnullataNonArrivanoAllaNuova() = runTest {
    val calls = AtomicInteger(0)
    val firstStarted = CompletableDeferred<Unit>()
    val provider = object : StreamProvider {
      override val providerId: String = "LateThenFresh"

      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> {
        return if (calls.incrementAndGet() == 1) {
          firstStarted.complete(Unit)
          delay(5_000L)
          listOf(source("Late", "https://cdn.example/late/index.m3u8"))
        } else {
          listOf(source("Fresh", "https://cdn.example/fresh/index.m3u8"))
        }
      }
    }

    val manager = StreamManager(providers = listOf(provider), timeoutMs = 10_000L)

    val firstReceived = mutableListOf<StreamSource>()
    val firstJob = launch {
      manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").collect { emitted ->
        firstReceived += emitted
      }
    }
    firstStarted.await()
    firstJob.cancel()
    firstJob.join()

    val second = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()

    assertEquals(listOf("Fresh"), second.map { it.serverName })
    assertFalse(
      "una sorgente della ricerca annullata non deve comparire nella nuova",
      second.any { it.serverName == "Late" } || firstReceived.any { it.serverName == "Late" }
    )
  }

  private data class CapturedCall(
    val tmdbId: Int,
    val isTv: Boolean,
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val year: Int?
  )
}
