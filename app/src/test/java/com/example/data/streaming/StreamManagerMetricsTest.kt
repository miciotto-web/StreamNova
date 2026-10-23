package com.example.data.streaming

import com.example.data.streaming.diagnostics.DiagnosticClock
import com.example.data.streaming.diagnostics.StreamAttempt
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.data.streaming.diagnostics.StreamSearchMetrics
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metriche locali del resolver: tempi di coda/esecuzione, concorrenza, prima
 * sorgente valida, deduplicazione e coerenza dei contatori. Nessuna rete.
 *
 * Il clock e' il tempo virtuale del test scheduler: `delay` nei provider fake
 * fa avanzare esattamente lo stesso tempo che le metriche leggono, quindi i
 * valori sono deterministici e non dipendono dall'orologio della macchina.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamManagerMetricsTest {

  private fun TestScope.virtualClock(): DiagnosticClock =
    DiagnosticClock { currentTime }

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
  fun queueMsMisuraSoloLAttesaDelSemaforoERunMsLEsecuzione() = runTest {
    // 3 provider, 1 slot: il secondo e il terzo aspettano in coda, poi eseguono.
    val providers = (1..3).map { index ->
      provider(id = "Q$index", delayMs = 100L) {
        listOf(source("S$index", "https://cdn.example/q$index/index.m3u8"))
      }
    }
    val (diagnostics, attempts) = sink()
    val metrics = Collections.synchronizedList(mutableListOf<StreamSearchMetrics>())

    val manager = StreamManager(
      diagnostics = diagnostics,
      providers = providers,
      timeoutMs = 5_000L,
      maxConcurrentProviders = 1,
      clock = virtualClock(),
      onSearchMetrics = { metrics += it }
    )

    manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList()

    val byProvider = attempts.associateBy { it.providerId }
    // Primo provider: nessuna coda.
    assertEquals(0L, byProvider.getValue("Q1").queueMs)
    // Secondo e terzo: 100ms e 200ms di attesa del semaforo.
    assertEquals(100L, byProvider.getValue("Q2").queueMs)
    assertEquals(200L, byProvider.getValue("Q3").queueMs)
    // Tutti eseguono 100ms sotto slot, indipendentemente dalla coda.
    attempts.forEach {
      assertEquals("runMs di ${it.providerId}", 100L, it.runMs)
      assertEquals("durationMs = queue + run", it.queueMs!! + it.runMs!!, it.durationMs)
    }

    assertEquals(1, metrics.size)
    assertEquals(3, metrics.first().providersCompleted)
  }

  @Test
  fun peakConcurrentNonSuperaMaiIlLimite() = runTest {
    val providers = (1..6).map { index ->
      provider(id = "C$index", delayMs = 100L) {
        listOf(source("C$index", "https://cdn.example/c$index/index.m3u8"))
      }
    }
    val metrics = Collections.synchronizedList(mutableListOf<StreamSearchMetrics>())
    val manager = StreamManager(
      providers = providers,
      timeoutMs = 5_000L,
      maxConcurrentProviders = 3,
      clock = virtualClock(),
      onSearchMetrics = { metrics += it }
    )

    val last = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()

    assertEquals("nessun provider deve restare fuori", 6, last.size)
    val peak = metrics.first().peakConcurrent
    assertTrue("peak ($peak) deve essere <= limite 3", peak <= 3)
    assertEquals("con 6 provider e 3 slot il picco deve saturare", 3, peak)
  }

  @Test
  fun timeoutECancellazioneNonGonfianoIlContatore() = runTest {
    val slow = provider(id = "TooSlow", delayMs = 10_000L) {
      listOf(source("Slow", "https://cdn.example/slow/index.m3u8"))
    }
    val hanging = object : StreamProvider {
      override val providerId: String = "Hanging"
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = awaitCancellation()
    }
    val good = provider(id = "Fast", delayMs = 20L) {
      listOf(source("Fast", "https://cdn.example/fast/index.m3u8", quality = "720p"))
    }
    val (diagnostics, attempts) = sink()
    val metrics = Collections.synchronizedList(mutableListOf<StreamSearchMetrics>())
    val manager = StreamManager(
      diagnostics = diagnostics,
      providers = listOf(slow, hanging, good),
      timeoutMs = 200L,
      maxConcurrentProviders = 3,
      clock = virtualClock(),
      onSearchMetrics = { metrics += it }
    )

    manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList()
    advanceUntilIdle()

    val byProvider = attempts.associateBy { it.providerId }
    val timedOut = byProvider.getValue("TooSlow")
    assertEquals(StreamAttemptOutcome.TIMEOUT, timedOut.outcome)
    assertEquals(200L, timedOut.runMs)
    assertEquals(0L, timedOut.queueMs)
    // Un provider sospeso allo scadere del budget viene classificato TIMEOUT
    // (non "cancellato dall'utente") e comunque registrato una sola volta.
    assertEquals(StreamAttemptOutcome.TIMEOUT, byProvider.getValue("Hanging").outcome)

    val search = metrics.single()
    assertEquals(3, search.providersStarted)
    assertEquals("ogni provider avviato deve risultare concluso", 3, search.providersCompleted)
    assertEquals(0, search.neverStarted)
    assertEquals(2, search.timeout)
    assertEquals(0, search.cancelled)
    assertEquals(1, search.success)
    // Con 3 provider e 3 slot non ci sono attese di coda.
    attempts.forEach { assertEquals(0L, it.queueMs) }
    assertTrue("peak (${search.peakConcurrent}) <= 3", search.peakConcurrent <= 3)
  }

  @Test
  fun primaSorgenteValidaIgnoraIProviderNonRiproducibili() = runTest {
    // Un provider "vuoto" e uno che restituisce un URL non riproducibile
    // rispondono subito: il cronometro non puo' partire su quei tick.
    val empty = provider(id = "Empty", delayMs = 10L) { emptyList() }
    val nonPlayable = provider(id = "NonPlayable", delayMs = 20L) {
      listOf(source("Broken", "not-a-url"))
    }
    val real = provider(id = "Real", delayMs = 300L) {
      listOf(source("Real", "https://cdn.example/real/index.m3u8"))
    }
    val metrics = Collections.synchronizedList(mutableListOf<StreamSearchMetrics>())
    val manager = StreamManager(
      providers = listOf(empty, nonPlayable, real),
      timeoutMs = 5_000L,
      maxConcurrentProviders = 3,
      clock = virtualClock(),
      onSearchMetrics = { metrics += it }
    )

    val last = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()

    // La sorgente non riproducibile resta nella lista (alla coda, qualita' non
    // valida) come prima: la metrica ignora solo il suo tick.
    assertEquals(setOf("Broken", "Real"), last.map { it.serverName }.toSet())
    val search = metrics.single()
    val first = search.firstSourceMs
    assertNotNull("deve esistere una prima sorgente valida", first)
    assertTrue(
      "firstSourceMs deve riflettere il provider reale (300ms), non i tick vuoti: $first",
      first!! >= 300L
    )
    assertTrue("firstSourceMs non puo' superare la durata totale", first <= search.totalMs)
    assertTrue("totalMs non e' negativo", search.totalMs >= 0L)
  }

  @Test
  fun deduplicazioneContaRicevutiAggiuntiEScattati() = runTest {
    val shared = "https://cdn.example/shared/index.m3u8"
    val first = provider(id = "First") {
      listOf(
        source("A", shared),
        source("B", "https://cdn.example/b/index.m3u8"),
        source("C", "https://cdn.example/c/index.m3u8", quality = "720p")
      )
    }
    val second = provider(id = "Second", delayMs = 50L) {
      listOf(
        source("A2", shared),
        source("D", "https://cdn.example/d/index.m3u8")
      )
    }
    val metrics = Collections.synchronizedList(mutableListOf<StreamSearchMetrics>())
    val manager = StreamManager(
      providers = listOf(first, second),
      timeoutMs = 5_000L,
      clock = virtualClock(),
      onSearchMetrics = { metrics += it }
    )

    val last = manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList().last()

    assertEquals("A, B, C, D: il duplicato viene scartato", 4, last.size)
    val search = metrics.single()
    assertEquals(4, search.validSources)
    assertEquals(1, search.duplicates)
    assertEquals("ricevuti = aggiunti + duplicati", 5, search.validSources + search.duplicates)
  }

  @Test
  fun deduplicazioneTrattaComeDistinteLeStesseUrlConHeaderDiversi() {
    val url = "https://cdn.example/same/index.m3u8"
    val a = source("A", url).copy(headers = mapOf("Referer" to "https://one.example/"))
    val b = source("B", url).copy(headers = mapOf("Referer" to "https://two.example/"))
    val target = mutableListOf<StreamSource>()

    val added = StreamManager.addDistinctSources(target, listOf(a, b, a))

    assertEquals("Referer diverso = sorgente diversa, stesso Referer = duplicato", 2, added)
    assertEquals(2, target.size)
  }

  @Test
  fun metricheCoerentiQuandoLaRicercaVieneCancellata() = runTest {
    val started = CompletableDeferred<Unit>()
    val hanging = object : StreamProvider {
      override val providerId: String = "HangForCancel"
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> {
        started.complete(Unit)
        awaitCancellation()
      }
    }
    val metrics = Collections.synchronizedList(mutableListOf<StreamSearchMetrics>())
    val manager = StreamManager(
      providers = listOf(hanging),
      timeoutMs = 30_000L,
      clock = virtualClock(),
      onSearchMetrics = { metrics += it }
    )

    // Il collector viene cancellato dall'esterno (nuova ricerca utente):
    // `take(1)` chiude il channelFlow appena il provider e' partito.
    val job = launch {
      manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").collect { }
    }
    started.await()
    job.cancel()
    // La pubblicazione delle metriche e' garantita anche alla cancellazione,
    // ma avviene nel finally del producer: si attende l'idle del test scheduler.
    advanceUntilIdle()
    job.join()

    val search = metrics.single()
    assertEquals(1, search.providersStarted)
    assertEquals(1, search.providersCompleted)
    assertEquals(0, search.neverStarted)
    assertEquals(0, search.validSources)
    assertEquals(0, search.duplicates)
    // La ricerca annullata dall'utente termina con TEMPO PARZIALE reale, non
    // con il budget globale: totalMs non deve arrivare a timeoutMs.
    assertTrue("totalMs=${search.totalMs} deve restare sotto il budget", search.totalMs < 30_000L)
    assertNull("nessuna sorgente valida: nessun firstSourceMs inventato", search.firstSourceMs)
  }

  @Test
  fun diagnosticaNonEsponeTokenCookieOUrlFirmati() = runTest {
    val broken = provider(id = "Leaky") {
      throw IOException(
        "HTTP 403 su https://cdn.example.test/e/abc?token=SEGRETO&sig=FIRMA " +
          "cookie=sessionid=COOKIESECRET Authorization: Bearer BEARERSECRET"
      )
    }
    val (diagnostics, attempts) = sink()
    val manager = StreamManager(
      diagnostics = diagnostics,
      providers = listOf(broken),
      timeoutMs = 3_000L,
      clock = virtualClock()
    )

    manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList()

    val attempt = attempts.single { it.providerId == "Leaky" }
    val message = attempt.message.orEmpty()
    assertEquals(StreamAttemptOutcome.HTTP_ERROR, attempt.outcome)
    assertFalse("token in query", message.contains("SEGRETO"))
    assertFalse("firma in query", message.contains("FIRMA"))
    assertFalse("cookie", message.contains("COOKIESECRET"))
    assertFalse("bearer", message.contains("BEARERSECRET"))
    assertTrue("l'origin resta utile", message.contains("https://cdn.example.test"))
    // Il sanitize e' idempotente: registrare due volte non riapre i segreti.
    assertEquals(message, StreamDiagnostics.sanitize(message))
    // Nessun URL firmato completo nella diagnostica strutturata.
    assertFalse("/e/abc" in message)
  }

  @Test
  fun senzaSinkNonVieneRegistratoNulla() = runTest {
    val (_, attempts) = sink()
    val manager = StreamManager(
      // Default: StreamDiagnosticSink.NONE.
      providers = listOf(provider(id = "Quiet") { emptyList() }),
      timeoutMs = 3_000L,
      clock = virtualClock()
    )

    manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList()

    assertTrue(attempts.isEmpty())
  }

  @Test
  fun providerInCodaNonConsumaIlProprioBudgetDuranteLAttesa() = runTest {
    // Uno slot occupato a lungo: il secondo provider attende il semaforo e
    // solo DOPO averlo ottenuto parte il suo budget. Con un limite di 1 il
    // provider in coda deve riuscire dopo 150ms di attesa + 10ms di lavoro,
    // a dimostrazione che queueMs non e' addebitato a runMs.
    val slotHolder = provider(id = "SlotHolder", delayMs = 150L) {
      listOf(source("Holder", "https://cdn.example/holder/index.m3u8"))
    }
    val queued = provider(id = "Queued", delayMs = 10L) {
      listOf(source("Queued", "https://cdn.example/queued/index.m3u8"))
    }
    val (diagnostics, attempts) = sink()
    val manager = StreamManager(
      diagnostics = diagnostics,
      providers = listOf(slotHolder, queued),
      timeoutMs = 200L,
      maxConcurrentProviders = 1,
      clock = virtualClock()
    )

    manager.resolveFlow(tmdbId = 550, isTv = false, title = "Titolo").toList()
    advanceUntilIdle()

    val byProvider = attempts.associateBy { it.providerId }
    assertEquals(StreamAttemptOutcome.SUCCESS, byProvider.getValue("SlotHolder").outcome)
    val inCoda = byProvider.getValue("Queued")
    assertEquals(StreamAttemptOutcome.SUCCESS, inCoda.outcome)
    assertEquals("attesa del semaforo", 150L, inCoda.queueMs)
    assertEquals("esecuzione effettiva", 10L, inCoda.runMs)
    assertEquals(160L, inCoda.durationMs)
  }
}
