package com.example.data.streaming

import com.example.data.prefs.StreamingEngineMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gerarchia della selezione automatica:
 * Altadefinizione (risoluzione più alta realmente riproducibile) → VixSrc →
 * Streaming Community → provider successivi.
 *
 * Il verificatore è iniettato: i test non aprono connessioni reali e possono
 * stabilire esattamente quali varianti sono riproducibili. La selezione manuale
 * ([StreamManager.resolveFlow] con `automaticSelection = false`) resta sul
 * percorso storico e non esegue alcuna verifica.
 */
class AutomaticProviderPriorityTest {

  // ── A. Altadefinizione 4K valido → 4K ─────────────────────────────────────

  @Test
  fun a_altadefinizione4kValidoSeleziona4k() {
    val alta = altaWith(RES_2160, RES_1440, RES_1080, RES_720)
    val vix = fake(VIX, listOf(source(VIX, VIX_2160, "4K")))
    val verifier = FakeVerifier(playable = setOf(RES_2160))

    val selected = select(manager(listOf(alta, vix), verifier))

    assertEquals(RES_2160, selected.first().streamUrl)
    assertEquals("4K", selected.first().quality)
    assertEquals("Altadefinizione", selected.first().sourceProviderId)
    assertEquals("nessuna variante inferiore deve essere verificata", listOf(RES_2160), verifier.verified)
    assertTrue(
      "dopo la selezione VixSrc può essere interrogato solo per arricchire il selettore",
      selected.any { it.sourceProviderId == VIX }
    )
  }

  // ── B. 4K non valido, 1440p valido → 1440p ────────────────────────────────

  @Test
  fun b_altadefinizione4kNonValidoSeleziona1440p() {
    val alta = altaWith(RES_2160, RES_1440, RES_1080, RES_720)
    val vix = fake(VIX, listOf(source(VIX, VIX_2160, "4K")))
    val verifier = FakeVerifier(playable = setOf(RES_1440))

    val selected = select(manager(listOf(alta, vix), verifier))

    assertEquals(RES_1440, selected.first().streamUrl)
    assertEquals("1440p", selected.first().quality)
    assertEquals(
      "le varianti vanno verificate in ordine decrescente e ci si ferma alla prima valida",
      listOf(RES_2160, RES_1440),
      verifier.verified
    )
    assertTrue(
      "VixSrc resta disponibile nel selettore dopo la scelta di Altadefinizione",
      selected.any { it.sourceProviderId == VIX }
    )
  }

  // ── C. 4K e 1440p non validi, 1080p valido → 1080p ────────────────────────

  @Test
  fun c_altadefinizione4kE1440pNonValidiSeleziona1080p() {
    val alta = altaWith(RES_2160, RES_1440, RES_1080, RES_720)
    val vix = fake(VIX, listOf(source(VIX, VIX_2160, "4K")))
    val verifier = FakeVerifier(playable = setOf(RES_1080))

    val selected = select(manager(listOf(alta, vix), verifier))

    assertEquals(RES_1080, selected.first().streamUrl)
    assertEquals("1080p", selected.first().quality)
    assertEquals(listOf(RES_2160, RES_1440, RES_1080), verifier.verified)
    assertTrue(selected.any { it.sourceProviderId == VIX })
  }

  // ── D. Soltanto 720p valido → 720p ────────────────────────────────────────

  @Test
  fun d_altadefinizioneSolo720pValidoSeleziona720p() {
    val alta = altaWith(RES_2160, RES_1440, RES_1080, RES_720)
    val vix = fake(VIX, listOf(source(VIX, VIX_2160, "4K")))
    val verifier = FakeVerifier(playable = setOf(RES_720))

    val selected = select(manager(listOf(alta, vix), verifier))

    assertEquals(RES_720, selected.first().streamUrl)
    assertEquals("720p", selected.first().quality)
    assertTrue(selected.any { it.sourceProviderId == VIX })
  }

  // ── E. Tutte le risoluzioni Altadefinizione non valide → VixSrc ───────────

  @Test
  fun e_tutteLeRisoluzioniAltadefinizioneNonValidePassaAVixSrc() {
    val alta = altaWith(RES_2160, RES_1440, RES_1080, RES_720)
    val vix = fake(VIX, listOf(source(VIX, VIX_1080, "1080p")))
    val sc = fake(SC, listOf(source(SC, SC_1080, "1080p")))
    val verifier = FakeVerifier(playable = setOf(VIX_1080))

    val selected = select(manager(listOf(alta, vix, sc), verifier))

    assertEquals(VIX_1080, selected.first().streamUrl)
    assertEquals(VIX, selected.first().sourceProviderId)
    assertEquals(
      "tutte le varianti Altadefinizione devono essere state provate prima del fallback",
      listOf(RES_2160, RES_1440, RES_1080, RES_720),
      verifier.verified.take(4)
    )
    assertTrue(
      "la sorgente VixSrc deve essere stata verificata prima della selezione",
      VIX_1080 in verifier.verified
    )
    assertEquals(1, alta.started.get())
    assertEquals(1, vix.started.get())
    assertEquals(
      "dopo la selezione di VixSrc, Streaming Community viene interrogato solo per il selettore",
      1,
      sc.started.get()
    )
    assertTrue(
      "Streaming Community deve comparire tra le alternative del selettore",
      selected.any { it.sourceProviderId == SC }
    )
  }

  // ── F. VixSrc non valido → Streaming Community ────────────────────────────

  @Test
  fun f_vixSrcNonValidoPassaAStreamingCommunity() {
    val alta = altaWith(RES_2160, RES_1080)
    val vix = fake(VIX, listOf(source(VIX, VIX_1080, "1080p")))
    val sc = fake(SC, listOf(source(SC, SC_1080, "1080p")))
    val verifier = FakeVerifier(playable = setOf(SC_1080))

    val selected = select(manager(listOf(alta, vix, sc), verifier))

    assertEquals(SC_1080, selected.first().streamUrl)
    assertEquals(SC, selected.first().sourceProviderId)
    assertEquals(1, sc.started.get())
  }

  // ── G. Nessun provider disponibile → errore gestito, nessuna eccezione ────

  @Test
  fun g_nessunaSorgenteRiproducibileNonEmetteNulla() {
    val alta = altaWith(RES_2160, RES_1080)
    val vix = fake(VIX, listOf(source(VIX, VIX_1080, "1080p")))
    val sc = fake(SC, listOf(source(SC, SC_1080, "1080p")))
    val verifier = FakeVerifier(playable = emptySet())

    val emissions = runBlocking {
      withTimeout(10_000) {
        manager(listOf(alta, vix, sc), verifier)
          .resolveFlow(
            tmdbId = 550,
            isTv = false,
            title = "Nessuna sorgente",
            streamingEngineMode = StreamingEngineMode.HTTP_WEB,
            automaticSelection = true
          )
          .toList()
      }
    }

    assertTrue(
      "nessuna sorgente riproducibile: il chiamante deve gestire l'errore, non ricevere un flusso rotto",
      emissions.isEmpty()
    )
    assertEquals(1, alta.started.get())
    assertEquals(1, vix.started.get())
    assertEquals(1, sc.started.get())
  }

  // ── H. Altadefinizione 720p vale più di VixSrc 4K ─────────────────────────

  @Test
  fun h_altadefinizione720pValidoBatIAVixSrc4k() {
    val alta = fake(ALTA, listOf(source(ALTA, ALTA_720, "720p")))
    val vix = fake(VIX, listOf(source(VIX, VIX_2160, "4K")))
    val verifier = FakeVerifier(playable = setOf(ALTA_720, VIX_2160))

    val selected = select(manager(listOf(vix, alta), verifier))

    assertEquals(
      "la fascia Altadefinizione precede la qualità superiore di un provider alternativo",
      ALTA_720,
      selected.first().streamUrl
    )
    assertEquals(ALTA, selected.first().sourceProviderId)
    assertEquals("720p", selected.first().quality)
    assertEquals(
      "VixSrc viene interrogato solo dopo la scelta, per arricchire il selettore",
      1,
      vix.started.get()
    )
    assertTrue(
      "la 4K di VixSrc resta selezionabile manualmente ma non sostituisce la scelta automatica",
      selected.any { it.sourceProviderId == VIX && it.streamUrl == VIX_2160 }
    )
  }

  // ── K. Dopo la selezione, il selettore contiene anche gli altri provider ──

  @Test
  fun k_ilSelettoreDelPlayerContieneIProviderAlternativi() {
    val alta = fake(ALTA, listOf(source(ALTA, ALTA_720, "720p")))
    val vix = fake(VIX, listOf(source(VIX, VIX_1080, "1080p")))
    val sc = fake(SC, listOf(source(SC, SC_1080, "1080p")))
    val verifier = FakeVerifier(playable = setOf(ALTA_720))

    val sources = select(manager(listOf(alta, vix, sc), verifier))

    assertEquals(
      "la sorgente in riproduzione resta quella scelta automaticamente",
      ALTA_720,
      sources.first().streamUrl
    )
    assertTrue(
      "VixSrc deve essere selezionabile dal selettore del player",
      sources.any { it.sourceProviderId == VIX && it.streamUrl == VIX_1080 }
    )
    assertTrue(
      "Streaming Community deve essere selezionabile dal selettore del player",
      sources.any { it.sourceProviderId == SC && it.streamUrl == SC_1080 }
    )
  }

  // ── I. 4K dichiarato ma manifest non accessibile → non valido ─────────────

  @Test
  fun i_risoluzioneDichiarata4kMaManifestNonAccessibileNonVieneScelta() {
    val alta = fake(ALTA, listOf(source(ALTA, RES_2160, "4K", verifiedHeight = 2160)))
    val verifier = FakeVerifier(playable = emptySet())

    val emissions = runBlocking {
      withTimeout(10_000) {
        manager(listOf(alta), verifier)
          .resolveFlow(
            tmdbId = 550,
            isTv = false,
            title = "Manifest rotto",
            streamingEngineMode = StreamingEngineMode.HTTP_WEB,
            automaticSelection = true
          )
          .toList()
      }
    }

    assertTrue("un 4K dichiarato ma non riproducibile non deve essere selezionato", emissions.isEmpty())
    assertEquals(listOf(RES_2160), verifier.verified)
  }

  // ── K. Qualità "Auto" ma verifica 1080p: la risoluzione osservata vince ──

  @Test
  fun k_qualitaAutoMaVerifica1080pAggiornaLaSorgenteSelezionata() {
    val alta = fake(
      ALTA,
      listOf(
        StreamSource(
          streamUrl = "https://alta.test/hls/master.m3u8?t=abc",
          quality = "Auto",
          serverName = "Altadefinizione"
        )
      )
    )
    val vix = fake(VIX, listOf(source(VIX, VIX_720, "720p")))
    val verifier = HttpSourceVerifier(
      probe = StreamProbe { url, _, rangeBytes ->
        when {
          url.contains("master.m3u8") ->
            ProbeResponse(200, url, AUTO_MASTER, "application/vnd.apple.mpegurl")
          url.contains("index-1080.m3u8") ->
            ProbeResponse(200, url, AUTO_MEDIA, "application/vnd.apple.mpegurl")
          url.contains("seg-1.ts") && rangeBytes != null ->
            ProbeResponse(200, url, "", "video/mp2t")
          else -> ProbeResponse(404, url, "", null)
        }
      }
    )

    val selected = select(manager(listOf(alta, vix), verifier))

    assertEquals(ALTA, selected.first().sourceProviderId)
    assertEquals(
      "la risoluzione osservata dal verifier deve sostituire l'etichetta Auto",
      "1080p",
      selected.first().quality
    )
    assertEquals(1080, selected.first().verifiedHeight)
  }

  // ── J. Selezione manuale invariata ────────────────────────────────────────

  @Test
  fun j_selezioneManualeNonApplicaLaGerarchiaNeVerifica() {
    val alta = fake(ALTA, listOf(source(ALTA, ALTA_720, "720p")))
    val vix = fake(VIX, listOf(source(VIX, VIX_2160, "4K")))
    val verifier = FakeVerifier(playable = setOf(VIX_2160))

    val selected = select(
      manager(listOf(vix, alta), verifier),
      automaticSelection = false
    )

    assertEquals(
      "in modalità manuale vince l'ordinamento storico per qualità (4K)",
      VIX_2160,
      selected.first().streamUrl
    )
    assertTrue(
      "la selezione manuale non deve eseguire verifiche di riproducibilità",
      verifier.verified.isEmpty()
    )
    assertEquals(1, alta.started.get())
    assertEquals(1, vix.started.get())
  }

  // ── harness ───────────────────────────────────────────────────────────────

  private fun manager(providers: List<StreamProvider>, verifier: SourceVerifier) =
    StreamManager(
      providers = providers,
      timeoutMs = 2_000L,
      maxConcurrentProviders = 3,
      addonStreamsFetcher = { _, _, _, _, _ -> emptyList() },
      sourceVerifier = verifier
    )

  private fun select(
    manager: StreamManager,
    automaticSelection: Boolean = true
  ): List<StreamSource> = runBlocking {
    withTimeout(10_000) {
      manager.resolveFlow(
        tmdbId = 550,
        isTv = false,
        title = "Titolo di test",
        streamingEngineMode = StreamingEngineMode.HTTP_WEB,
        automaticSelection = automaticSelection
      ).toList().lastOrNull().orEmpty()
    }
  }

  private fun altaWith(vararg urls: String): FakeProvider =
    fake(ALTA, urls.map { url -> source(ALTA, url, qualityOf(url), heightOf(url)) })

  private fun source(
    provider: String,
    url: String,
    quality: String,
    verifiedHeight: Int? = heightOf(url).takeIf { it > 0 }
  ) = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = provider,
    declaredQuality = quality,
    verifiedHeight = verifiedHeight
  )

  private fun qualityOf(url: String): String = when (heightOf(url)) {
    2160 -> "4K"
    1440 -> "1440p"
    1080 -> "1080p"
    720 -> "720p"
    else -> "Auto"
  }

  private fun heightOf(url: String): Int = when {
    url.contains("2160") -> 2160
    url.contains("1440") -> 1440
    url.contains("1080") -> 1080
    url.contains("720") -> 720
    else -> 0
  }

  private fun fake(providerId: String, sources: List<StreamSource>) =
    FakeProvider(providerId, sources)

  private class FakeProvider(
    override val providerId: String,
    private val sources: List<StreamSource>,
    private val fail: Boolean = false
  ) : StreamProvider {
    val started = AtomicInteger(0)

    override suspend fun getStreams(
      tmdbId: Int,
      isTv: Boolean,
      season: Int?,
      episode: Int?,
      title: String?,
      year: Int?
    ): List<StreamSource> {
      started.incrementAndGet()
      if (fail) throw IOException("provider failure")
      return sources
    }
  }

  private class FakeVerifier(
    private val playable: Set<String>
  ) : SourceVerifier {
    val verified = mutableListOf<String>()

    override suspend fun verify(source: StreamSource): SourcePlayability {
      val url = source.streamUrl.orEmpty()
      verified += url
      return if (url in playable) SourcePlayability.Playable else SourcePlayability.Unavailable
    }
  }

  private companion object {
    const val ALTA = AutoSourcePriority.ALTADEFINIZIONE_ID
    const val VIX = AutoSourcePriority.VIXSRC_ID
    const val SC = AutoSourcePriority.STREAMING_COMMUNITY_ID

    const val RES_2160 = "https://alta.test/hls/movie/2160.m3u8"
    const val RES_1440 = "https://alta.test/hls/movie/1440.m3u8"
    const val RES_1080 = "https://alta.test/hls/movie/1080.m3u8"
    const val RES_720 = "https://alta.test/hls/movie/720.m3u8"
    const val ALTA_720 = "https://alta.test/hls/movie/solo-720.m3u8"

    const val VIX_2160 = "https://vix.test/hls/movie/2160.m3u8"
    const val VIX_1080 = "https://vix.test/hls/movie/1080.m3u8"
    const val VIX_720 = "https://vix.test/hls/movie/720.m3u8"
    const val SC_1080 = "https://sc.test/hls/movie/1080.m3u8"

    val AUTO_MASTER = """
      #EXTM3U
      #EXT-X-STREAM-INF:BANDWIDTH=3098676,RESOLUTION=1920x800,CODECS="avc1.4d4028"
      index-1080.m3u8?t=abc
    """.trimIndent()

    val AUTO_MEDIA = """
      #EXTM3U
      #EXT-X-TARGETDURATION:6
      #EXTINF:6.000,
      seg-1.ts?t=abc
    """.trimIndent()
  }
}
