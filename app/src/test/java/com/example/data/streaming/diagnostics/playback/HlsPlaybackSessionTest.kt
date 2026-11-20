package com.example.data.streaming.diagnostics.playback

import com.example.data.streaming.diagnostics.DiagnosticClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collettore diagnostico di sessione (FASI 2/5/6/9): buffering, separazione
 * delle sessioni, assenza di duplicazioni, report senza credenziali,
 * diagnosi finali mai definitive su evidenze temporanee.
 */
class HlsPlaybackSessionTest {

  /** Clock controllabile: il tempo avanza solo quando il test lo decide. */
  private class FakeClock : DiagnosticClock {
    var now = 0L
    override fun nowMs(): Long = now
    fun advance(ms: Long) { now += ms }
  }

  private fun session(
    clock: FakeClock,
    provider: String? = "VixSrcProvider",
    bufferingTimeoutMs: Long = HlsPlaybackSession.DEFAULT_BUFFERING_TIMEOUT_MS
  ) = HlsPlaybackSession(
    sessionId = HlsPlaybackSession.nextSessionId(),
    providerId = provider,
    contentLabel = "Titolo test",
    clock = clock,
    bufferingTimeoutThresholdMs = bufferingTimeoutMs
  )

  // ------------------------------------------------------------------
  // Sorgente: nessuna credenziale nel report
  // ------------------------------------------------------------------

  @Test
  fun reportSenzaCredenziali() {
    val clock = FakeClock()
    val s = session(clock)
    s.setSource("https://cdn.example.com/master.m3u8?token=SECRET&expires=999", SourceKind.HLS)
    s.onHttpCompleted(
      url = "https://cdn.example.com/master.m3u8?token=SECRET&expires=999",
      phase = PlaybackPhase.MANIFEST,
      httpStatus = 200,
      durationMs = 120L,
      contentType = "application/vnd.apple.mpegurl"
    )
    clock.advance(50)

    val report = s.buildReport()

    // Host presente, URL completa e token assenti da TUTTO il report.
    assertEquals(StreamProtocol.HTTPS, report.protocol)
    assertEquals("cdn.example.com", report.httpEvents.single().hostId)
    val serialized = report.toString()
    assertFalse("Il token non deve comparire nel report", serialized.contains("SECRET"))
    assertFalse("La query string non deve comparire nel report", serialized.contains("expires"))
  }

  // ------------------------------------------------------------------
  // Startup e stato
  // ------------------------------------------------------------------

  @Test
  fun tempoDiAvvioAlPrimoFrame() {
    val clock = FakeClock()
    val s = session(clock)
    clock.advance(2100)
    s.onFirstFrameRendered()

    val report = s.buildReport()
    assertEquals(2100L, report.startupMs)
  }

  @Test
  fun ilPrimoFrameNonVieneRiMisurato() {
    val clock = FakeClock()
    val s = session(clock)
    clock.advance(1800)
    s.onFirstFrameRendered()
    clock.advance(3000)
    s.onFirstFrameRendered()

    assertEquals(1800L, s.buildReport().startupMs)
  }

  // ------------------------------------------------------------------
  // Buffering
  // ------------------------------------------------------------------

  @Test
  fun rebufferContatiConDurata() {
    val clock = FakeClock()
    val s = session(clock)
    s.onPlayerStateChanged(PlaybackStateKind.IDLE)
    s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)   // avvio: non è rebuffer
    s.onPlayerStateChanged(PlaybackStateKind.READY)
    clock.advance(5000)
    s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)   // rebuffer #1
    clock.advance(700)
    s.onPlayerStateChanged(PlaybackStateKind.READY)
    clock.advance(3000)
    s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)   // rebuffer #2
    clock.advance(300)
    s.onPlayerStateChanged(PlaybackStateKind.READY)

    val report = s.buildReport()
    assertEquals(2, report.rebufferCount)
    assertEquals(1000L, report.rebufferTotalMs)
    assertEquals(listOf(700L, 300L), report.bufferingEvents.map { it.durationMs })
    assertTrue(report.bufferingEvents[0].startElapsedMs > 0)
  }

  @Test
  fun bufferingContinuoOltreSogliaETimeout() {
    val clock = FakeClock()
    val s = session(clock, bufferingTimeoutMs = 10_000)
    s.onPlayerStateChanged(PlaybackStateKind.READY)
    s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)
    clock.advance(9_999)
    assertFalse(s.isBufferingTimeout())
    clock.advance(1)
    assertTrue(s.isBufferingTimeout())

    val diagnosis = s.bufferingTimeoutDiagnosis()
    assertNotNull(diagnosis)
    assertEquals(HlsErrorCategory.BUFFERING_TIMEOUT, diagnosis!!.category)
    assertTrue("Il timeout di buffering è sempre temporaneo", diagnosis.temporary)
  }

  @Test
  fun bufferingTimeoutDiagnosiUnaVoltaPerEpisodioDiBuffering() {
    val clock = FakeClock()
    val s = session(clock, bufferingTimeoutMs = 10_000)
    s.onPlayerStateChanged(PlaybackStateKind.READY)
    s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)
    clock.advance(10_000)
    assertNotNull(s.bufferingTimeoutDiagnosis())
    clock.advance(5_000)
    // Tick successivi mentre la STESSA condizione persiste: nessuna nuova
    // occorrenza (i tick osservativi non gonfiano il contatore).
    assertNull(s.bufferingTimeoutDiagnosis())
    assertNull(s.bufferingTimeoutDiagnosis())
    assertEquals(1, s.buildReport().diagnoses.single().attemptCount)

    // Due rebuffer chiudono l'episodio precedente; al terzo rebuffer IN CORSO
    // la condizione "buffering ripetuto" (3+ eventi) è di nuovo vera: nuovo
    // episodio → nuova occorrenza della stessa firma (aggregata in una riga).
    s.onPlayerStateChanged(PlaybackStateKind.READY)
    s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)
    s.onPlayerStateChanged(PlaybackStateKind.READY)
    s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)
    assertNotNull(s.bufferingTimeoutDiagnosis())
    val report = s.buildReport()
    assertEquals(1, report.diagnoses.size)
    assertEquals(2, report.diagnoses.single().attemptCount)
  }

  @Test
  fun bufferingRipetutoETimeoutAncheSenzaSogliaContinua() {
    val clock = FakeClock()
    val s = session(clock)
    s.onPlayerStateChanged(PlaybackStateKind.READY)
    repeat(2) {
      s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)
      clock.advance(100)
      s.onPlayerStateChanged(PlaybackStateKind.READY)
      clock.advance(1000)
    }
    // Due rebuffer brevi e conclusi: nessun timeout.
    assertFalse(s.isBufferingTimeout())
    // Il terzo rebuffer IN CORSO soddisfa la condizione "buffering ripetuto".
    s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)
    clock.advance(150)
    assertTrue(s.isBufferingTimeout())
  }

  @Test
  fun bufferingFinitoNonETimeout() {
    val clock = FakeClock()
    val s = session(clock)
    s.onPlayerStateChanged(PlaybackStateKind.READY)
    repeat(3) {
      s.onPlayerStateChanged(PlaybackStateKind.BUFFERING)
      clock.advance(50)
      s.onPlayerStateChanged(PlaybackStateKind.READY)
    }
    assertFalse("Fuori da BUFFERING non c'è timeout di buffering", s.isBufferingTimeout())
  }

  // ------------------------------------------------------------------
  // Separazione delle sessioni
  // ------------------------------------------------------------------

  @Test
  fun leSessioniSonoIndipendenti() {
    val clock = FakeClock()
    val s1 = session(clock, provider = "Provider A")
    val s2 = session(clock, provider = "Provider B")

    s1.onHttpCompleted("https://a.example.com/x.m3u8", PlaybackPhase.MANIFEST, 200, 10)
    s2.onError(PlaybackErrorInput(httpStatus = 403, phase = PlaybackPhase.MANIFEST))
    clock.advance(10)
    s1.onError(PlaybackErrorInput(httpStatus = 429, phase = PlaybackPhase.MANIFEST))

    val r1 = s1.buildReport()
    val r2 = s2.buildReport()
    assertEquals(1, r1.httpEvents.size)
    assertEquals(1, r1.diagnoses.size)
    assertEquals(HlsErrorCategory.HTTP_ERROR, r1.finalCategory)
    assertEquals(0, r2.httpEvents.size)
    assertEquals(1, r2.diagnoses.size)
    assertEquals(HlsErrorCategory.AUTHORIZATION_ERROR, r2.finalCategory)
    assertEquals("Provider A", r1.providerId)
    assertEquals("Provider B", r2.providerId)
  }

  // ------------------------------------------------------------------
  // Deduplicazione eventi
  // ------------------------------------------------------------------

  @Test
  fun erroriIdenticiAggregatiNonDuplicati() {
    val clock = FakeClock()
    val s = session(clock)
    val input = PlaybackErrorInput(httpStatus = 429, phase = PlaybackPhase.MEDIA_SEGMENT)

    s.onError(input)
    clock.advance(10)
    s.onError(input)
    clock.advance(10)
    s.onError(input)

    val report = s.buildReport()
    assertEquals("Una sola riga per firma identica", 1, report.diagnoses.size)
    assertEquals(3, report.diagnoses.single().attemptCount)
    // Il ripetersi della stessa firma eleva la confidenza.
    assertEquals(DiagnosisConfidence.HIGH, report.diagnoses.single().confidence)
  }

  @Test
  fun leCodaHttpElimitataSenzaCrescitaInfinita() {
    val clock = FakeClock()
    val s = session(clock)
    repeat(HlsPlaybackSession.MAX_HTTP_EVENTS + 50) { index ->
      s.onHttpCompleted("https://cdn.example.com/seg$index.ts", PlaybackPhase.MEDIA_SEGMENT, 200, 1)
    }
    assertEquals(HlsPlaybackSession.MAX_HTTP_EVENTS, s.buildReport().httpEvents.size)
  }

  // ------------------------------------------------------------------
  // Diagnosi finale: mai definitiva su evidenze temporanee
  // ------------------------------------------------------------------

  @Test
  fun timeoutSingoloNonDichiaraSorgenteIndisponibile() {
    val clock = FakeClock()
    val s = session(clock)
    s.setSource("https://cdn.example.com/master.m3u8", SourceKind.HLS)
    // Un caricamento riuscito, poi un timeout: l'ultimo evento riuscito resta.
    s.onHttpCompleted("https://cdn.example.com/master.m3u8", PlaybackPhase.MANIFEST, null, 120)
    clock.advance(4000)
    val diagnosis = s.onError(
      PlaybackErrorInput(
        exceptionClassNames = listOf("SocketTimeoutException"),
        phase = PlaybackPhase.MANIFEST
      )
    )

    val report = s.buildReport()
    assertNotNull(diagnosis)
    assertEquals(HlsErrorCategory.NETWORK_ERROR, report.finalCategory)
    assertTrue("Un timeout singolo è temporaneo, non definitivo", report.finalDiagnosis!!.temporary)
    assertTrue(report.hasTransientProblem)
    assertEquals("manifest:cdn.example.com", report.lastSuccessfulEvent)
  }

  @Test
  fun erroreDefinitivoPrevaleSuTransitorio() {
    val clock = FakeClock()
    val s = session(clock)
    s.onError(PlaybackErrorInput(httpStatus = 429, phase = PlaybackPhase.MANIFEST))
    clock.advance(10)
    s.onError(PlaybackErrorInput(httpStatus = 403, phase = PlaybackPhase.MANIFEST))

    val report = s.buildReport()
    assertEquals(HlsErrorCategory.AUTHORIZATION_ERROR, report.finalCategory)
    assertFalse(report.finalDiagnosis!!.temporary)
    assertFalse(report.hasTransientProblem)
  }

  @Test
  fun sessioneSenzaErroriNonHaDiagnosiFinale() {
    val clock = FakeClock()
    val s = session(clock)
    s.onPlayerStateChanged(PlaybackStateKind.READY)
    s.onFirstFrameRendered()
    s.onHttpCompleted("https://cdn.example.com/seg.ts", PlaybackPhase.MEDIA_SEGMENT, null, 200, bytesLoaded = 65536)

    val report = s.buildReport()
    assertNull(report.finalDiagnosis)
    assertNull(report.finalCategory)
    assertEquals(PlaybackStateKind.READY, report.playbackState)
    assertEquals(1, report.mediaSegmentCount)
    assertEquals(65536L, report.mediaSegmentBytesTotal)
  }

  // ------------------------------------------------------------------
  // Informazioni sulla sorgente: le tre qualità distinte
  // ------------------------------------------------------------------

  @Test
  fun qualitaDichiarataSelezionataERilevataRestanoDistinte() {
    val clock = FakeClock()
    val s = session(clock)
    s.setSource("https://cdn.example.com/master.m3u8", SourceKind.HLS)
    s.setDeclaredResolution("4K")           // dichiarata dal provider/playlist
    s.setSelectedResolution("1080p")        // variante selezionata
    s.onVideoSizeChanged(1920, 1080)        // risoluzione reale

    val report = s.buildReport()
    assertEquals("4K", report.declaredResolution)
    assertEquals("1080p", report.selectedResolution)
    assertEquals("1080p", report.detectedResolution)
    assertEquals(SourceKind.HLS, report.sourceKind)
  }

  @Test
  fun qhdRilevataNonDiventa4k() {
    val clock = FakeClock()
    val s = session(clock)
    s.setDeclaredHeight(1440)
    s.onVideoSizeChanged(2560, 1440)

    val report = s.buildReport()
    assertEquals("1440p", report.declaredResolution)
    assertEquals("1440p", report.detectedResolution)
  }

  @Test
  fun messaggiSanificatiNelReport() {
    val clock = FakeClock()
    val s = session(clock)
    s.onError(
      PlaybackErrorInput(
        exceptionMessage = "Errore su https://cdn.example.com/seg.ts?token=TOPSECRET durante il load",
        phase = PlaybackPhase.MEDIA_SEGMENT
      )
    )

    val message = s.buildReport().diagnoses.single().technicalMessage.orEmpty()
    assertFalse(message.contains("TOPSECRET"))
    assertTrue(message.contains("cdn.example.com"))
  }
}
