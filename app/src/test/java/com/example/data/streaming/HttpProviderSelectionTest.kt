package com.example.data.streaming

import com.example.data.prefs.HttpProviderChoices
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.StreamingEngineMode
import com.example.data.provider.AnimeStreamProvider
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scelta manuale del provider HTTP principale: coerenza del catalogo con i
 * provider reali e priorità nell'avvio/ordinamento delle sorgenti.
 */
class HttpProviderSelectionTest {

  private fun manager(
    providers: List<StreamProvider>,
    maxConcurrentProviders: Int = 1,
    preferredProviderGraceMs: Long = 100L
  ) = StreamManager(
    providers = providers,
    timeoutMs = 5_000L,
    maxConcurrentProviders = maxConcurrentProviders,
    preferredProviderGraceMs = preferredProviderGraceMs,
    addonStreamsFetcher = { _, _, _, _, _ -> emptyList() }
  )

  @Test
  fun catalogoCopreTuttiIProviderHttpDiDefault() {
    val defaultIds = StreamManager.defaultProviders(StreamDiagnosticSink.NONE)
      .filterNot { it is TorBoxStreamProvider || it is AnimeStreamProvider }
      .map { it.providerId }
      .toSet()

    assertEquals(
      "ogni provider HTTP deve avere una voce selezionabile nelle impostazioni",
      HttpProviderChoices.all.map { it.id }.toSet(),
      defaultIds
    )
    assertEquals(
      "in automatico devono restare attivi solo i provider verificati",
      setOf("VixSrcProvider", "Altadefinizione"),
      HttpProviderChoices.defaultEnabledIds
    )
    assertTrue(
      "CB01 deve essere classificato come sperimentale",
      "Cb01Provider" in HttpProviderChoices.experimentalIds
    )
  }

  @Test
  fun providerPreferitoVieneAvviatoPerPrimoEProduceLaPrimaSorgente() = runBlocking {
    val other = FakeProvider("FakeA", "https://a.test/1080.m3u8", "1080p")
    val preferred = FakeProvider("FakeB", "https://b.test/720.m3u8", "720p")
    val manager = manager(listOf(other, preferred))

    val sources = withTimeout(5_000) {
      manager.resolveFlow(
        tmdbId = 1,
        isTv = false,
        title = "Test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        httpPrimaryProviderId = "FakeB"
      ).first { it.isNotEmpty() }
    }

    assertEquals(
      "la sorgente del provider preferito deve essere la prima",
      "FakeB",
      sources.first().sourceProviderId
    )
    assertEquals("https://b.test/720.m3u8", sources.first().streamUrl)
    assertTrue(
      "il provider preferito deve partire prima degli altri",
      other.startedAt.isEmpty() || preferred.startedAt.first() <= other.startedAt.first()
    )
  }

  @Test
  fun automaticoMantieneLOrdineStorico() = runBlocking {
    val first = FakeProvider("FakeA", "https://a.test/1080.m3u8", "1080p")
    val second = FakeProvider("FakeB", "https://b.test/720.m3u8", "720p")
    val manager = manager(listOf(first, second))

    val sources = withTimeout(5_000) {
      manager.resolveFlow(
        tmdbId = 1,
        isTv = false,
        title = "Test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        httpPrimaryProviderId = HttpProviderChoices.AUTO_ID
      ).first { it.isNotEmpty() }
    }

    assertEquals("FakeA", sources.first().sourceProviderId)
  }

  @Test
  fun providerSconosciutoNonRompeLaRicerca() = runBlocking {
    val provider = FakeProvider("FakeA", "https://a.test/1080.m3u8", "1080p")
    val manager = manager(listOf(provider))

    val sources = withTimeout(5_000) {
      manager.resolveFlow(
        tmdbId = 1,
        isTv = false,
        title = "Test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        httpPrimaryProviderId = "ProviderRimosso"
      ).first { it.isNotEmpty() }
    }

    assertEquals("FakeA", sources.first().sourceProviderId)
  }

  @Test
  fun providerPreferitoLentoNonVieneScavalcatoDaUnProviderPiuVeloce() = runBlocking {
    val fastOther = FakeProvider("FakeFast", "https://fast.test/1080.m3u8", "1080p", delayMs = 0L)
    val preferred = FakeProvider("FakeSlow", "https://slow.test/720.m3u8", "720p", delayMs = 250L)
    val manager = manager(
      providers = listOf(preferred, fastOther),
      maxConcurrentProviders = 2,
      preferredProviderGraceMs = 2_000L
    )

    val sources = withTimeout(5_000) {
      manager.resolveFlow(
        tmdbId = 1,
        isTv = false,
        title = "Test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        httpPrimaryProviderId = "FakeSlow"
      ).first { it.isNotEmpty() }
    }

    assertEquals(
      "la sorgente del provider preferito deve arrivare prima anche se più lenta",
      "FakeSlow",
      sources.first().sourceProviderId
    )
  }

  @Test
  fun seIlProviderPreferitoNonRispondeScattaIlFallback() = runBlocking {
    val fastOther = FakeProvider("FakeFast", "https://fast.test/1080.m3u8", "1080p", delayMs = 0L)
    val slowPreferred = FakeProvider("FakeSlow", "https://slow.test/720.m3u8", "720p", delayMs = 1_000L)
    val manager = manager(
      providers = listOf(slowPreferred, fastOther),
      maxConcurrentProviders = 2,
      preferredProviderGraceMs = 150L
    )

    val sources = withTimeout(5_000) {
      manager.resolveFlow(
        tmdbId = 1,
        isTv = false,
        title = "Test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        httpPrimaryProviderId = "FakeSlow"
      ).first { it.isNotEmpty() }
    }

    assertEquals(
      "scaduta la grazia, si usa la migliore sorgente disponibile",
      "FakeFast",
      sources.first().sourceProviderId
    )
  }

  @Test
  fun ordinamentoUtenteMetteIlProviderPreferitoPrimaDellaQualita() {
    val highQuality = StreamSource(
      streamUrl = "https://a.test/1080.m3u8",
      quality = "1080p",
      serverName = "A",
      sourceProviderId = "FakeA"
    )
    val lowQuality = StreamSource(
      streamUrl = "https://b.test/720.m3u8",
      quality = "720p",
      serverName = "B",
      sourceProviderId = "FakeB"
    )

    val preferred = TorBoxSourceOrdering.sort(
      listOf(highQuality, lowQuality),
      PlaybackSettings(),
      preferredProviderId = "FakeB"
    )
    assertEquals("FakeB", preferred.first().sourceProviderId)

    val automatic = TorBoxSourceOrdering.sort(
      listOf(highQuality, lowQuality),
      PlaybackSettings()
    )
    assertEquals("FakeA", automatic.first().sourceProviderId)
  }

  @Test
  fun strictModeUsaSoloIlProviderPreferito() = runBlocking {
    val preferred = FakeProvider("Altadefinizione", "https://a.test/1080.m3u8", "1080p")
    val other = FakeProvider("VixSrcProvider", "https://b.test/720.m3u8", "720p")
    val manager = manager(listOf(other, preferred), maxConcurrentProviders = 2)

    val sources = withTimeout(5_000) {
      manager.resolveFlow(
        tmdbId = 1,
        isTv = false,
        title = "Test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        httpPrimaryProviderId = "Altadefinizione",
        httpPrimaryProviderStrict = true
      ).first { it.isNotEmpty() }
    }

    assertEquals("Altadefinizione", sources.first().sourceProviderId)
    assertTrue(
      "in modalità solo provider scelto gli altri non devono partire",
      other.startedAt.isEmpty()
    )
  }

  @Test
  fun strictModeNonRipiegaSeIlProviderPreferitoFallisce() = runBlocking {
    val preferred = FakeProvider(
      "Altadefinizione",
      "https://a.test/1080.m3u8",
      "1080p",
      fail = true
    )
    val other = FakeProvider("VixSrcProvider", "https://b.test/720.m3u8", "720p")
    val manager = manager(listOf(other, preferred), maxConcurrentProviders = 2)

    val emissions = withTimeout(5_000) {
      manager.resolveFlow(
        tmdbId = 1,
        isTv = false,
        title = "Test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        httpPrimaryProviderId = "Altadefinizione",
        httpPrimaryProviderStrict = true
      ).toList()
    }

    assertTrue("nessun fallback atteso in strict mode", emissions.isEmpty())
    assertTrue(other.startedAt.isEmpty())
  }

  @Test
  fun providerSperimentaliDisabilitatiNonVengonoEseguiti() = runBlocking {
    val core = FakeProvider("VixSrcProvider", "https://core.test/720.m3u8", "720p")
    val experimental = FakeProvider("Cb01Provider", "https://exp.test/1080.m3u8", "1080p")
    val manager = manager(listOf(core, experimental), maxConcurrentProviders = 2)

    val sources = withTimeout(5_000) {
      manager.resolveFlow(
        tmdbId = 1,
        isTv = false,
        title = "Test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        httpExperimentalProvidersEnabled = false
      ).first { it.isNotEmpty() }
    }

    assertEquals("VixSrcProvider", sources.first().sourceProviderId)
    assertTrue(
      "un provider sperimentale disabilitato non deve essere eseguito",
      experimental.startedAt.isEmpty()
    )
  }

  @Test
  fun providerSperimentaliAbilitatiVengonoEseguiti() = runBlocking {
    val core = FakeProvider("VixSrcProvider", "https://core.test/720.m3u8", "720p")
    val experimental = FakeProvider("Cb01Provider", "https://exp.test/1080.m3u8", "1080p")
    val manager = manager(listOf(core, experimental), maxConcurrentProviders = 2)

    val found = withTimeoutOrNull(5_000) {
      manager.resolveFlow(
        tmdbId = 1,
        isTv = false,
        title = "Test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        httpExperimentalProvidersEnabled = true
      ).first { list -> list.any { it.sourceProviderId == "Cb01Provider" } }
    }

    assertNotNull("il provider sperimentale abilitato deve essere eseguito", found)
    assertTrue(experimental.startedAt.isNotEmpty())
  }

  private class FakeProvider(
    override val providerId: String,
    private val sourceUrl: String,
    private val quality: String,
    private val delayMs: Long = 0L,
    private val fail: Boolean = false
  ) : StreamProvider {

    val startedAt = mutableListOf<Long>()

    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      if (delayMs > 0) delay(delayMs)
      startedAt += System.currentTimeMillis()
      if (fail) throw IOException("provider failure")
      return listOf(
        StreamSource(
          streamUrl = sourceUrl,
          quality = quality,
          serverName = providerId
        )
      )
    }
  }
}
