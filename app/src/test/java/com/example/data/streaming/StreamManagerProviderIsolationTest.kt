package com.example.data.streaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Isolamento dei provider nella risoluzione delle sorgenti.
 *
 * Un provider che fallisce (eccezione o timeout) NON deve cancellare le sorgenti
 * gia' raccolte dagli altri ne' bloccare i provider ancora in corso: e' la
 * premessa del fallback automatico, che puo' contare solo sulle sorgenti che la
 * risoluzione ha effettivamente consegnato al player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StreamManagerProviderIsolationTest {

  private fun source(name: String, url: String, quality: String = "1080p") = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = name
  )

  private fun provider(vararg sources: StreamSource, delayMs: Long = 0L) = object : StreamProvider {
    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      if (delayMs > 0L) delay(delayMs)
      return sources.toList()
    }
  }

  /** Provider che fallisce DOPO aver lasciato partire gli altri. */
  private fun failingProvider(delayMs: Long) = object : StreamProvider {
    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      delay(delayMs)
      throw IllegalStateException("provider non raggiungibile")
    }
  }

  @Test
  fun providerCheFallisceDopoNonCancellaLeSorgentiGiaRaccolte() = runTest {
    val manager = StreamManager(
      providers = listOf(
        provider(source("Primo", "https://cdn.example/primo/index.m3u8")),
        provider(
          source("Secondo", "https://cdn.example/secondo/index.m3u8", quality = "720p"),
          delayMs = 500L
        ),
        failingProvider(delayMs = 1_500L)
      ),
      timeoutMs = 2_000L
    )

    val emissions = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList()
    val names = emissions.flatten().map { it.serverName }.toSet()

    assertTrue(
      "Le sorgenti raccolte prima del fallimento devono restare nel flusso (trovate: $names)",
      names.containsAll(setOf("Primo", "Secondo"))
    )
    assertTrue(
      "Il provider funzionante deve poter avviare la riproduzione",
      emissions.any { it.isNotEmpty() }
    )
  }

  @Test
  fun providerSenzaRisultatiNonImpedisceAgliAltriDiConsegnare() = runTest {
    val manager = StreamManager(
      providers = listOf(
        provider(), // nessuna sorgente: esito legittimo, non un errore
        provider(source("Unico", "https://cdn.example/unico/index.m3u8"), delayMs = 400L)
      ),
      timeoutMs = 2_000L
    )

    val emissions = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList()
    val names = emissions.flatten().map { it.serverName }.toSet()

    assertTrue(
      "Un provider a vuoto non deve bloccare gli altri (trovate: $names)",
      names.contains("Unico")
    )
  }
}
