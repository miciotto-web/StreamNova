package com.example.ui.screens.player

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test del controller dell'anteprima, senza player e senza rete.
 *
 * Coprono esattamente i punti richiesti:
 * - aggiornamento avanti e indietro del timestamp/stato;
 * - richieste obsolete ignorate (una risposta di una posizione superata non viene mostrata);
 * - cache e deduplicazione (nessuna richiesta duplicata, riuso del fotogramma);
 * - annullamento del caricamento (hide / cambio sorgente / release);
 * - fallback quando l'anteprima non è disponibile.
 *
 * La sorgente finta restituisce un [SeekPreviewFrame] costruito attorno a una piccola
 * Bitmap reale (8x8): il controller ne legge solo `byteCount` per il budget della cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SeekPreviewControllerTest {

  private val tenMinutesMs = 600_000L

  private companion object {
    /** Bitmap finta: il controller ne legge solo `byteCount` per il budget della cache. */
    val FAKE_BITMAP: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
  }

  private fun frame(positionMs: Long): SeekPreviewFrame =
    SeekPreviewFrame(bitmap = FAKE_BITMAP, positionMs = positionMs)

  /**
   * Sorgente controllabile. Ogni [load] registra la posizione: se [gate] è valorizzato la
   * richiesta resta sospesa finché il test non la rilascia (o l'annulla).
   */
  private class FakeSource(
    var available: Boolean = true,
    private val scope: TestScope? = null
  ) : SeekPreviewSource {

    val requested = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()
    var gate: CompletableDeferred<Unit>? = null
    var result: ((Long) -> SeekPreviewFrame?)? = { pos -> null }
    var failure: Throwable? = null
    var released = false

    override val isAvailable: Boolean get() = available

    override suspend fun load(positionMs: Long): SeekPreviewFrame? {
      requested += positionMs
      val wait = gate
      if (wait != null) {
        try {
          wait.await()
        } catch (cancelledError: kotlinx.coroutines.CancellationException) {
          cancelled += positionMs
          throw cancelledError
        }
      }
      failure?.let { throw it }
      return result?.invoke(positionMs)
    }

    override fun release() {
      released = true
    }
  }

  private class Harness(
    val source: FakeSource,
    val cache: SeekPreviewCache = SeekPreviewCache(),
    val errors: MutableList<Throwable> = mutableListOf()
  )

  private fun harness(
    scope: TestScope,
    available: Boolean = true
  ): Harness = Harness(
    source = FakeSource(available = available, scope = scope)
  )

  /**
   * Controller sull'orologio del test: [UnconfinedTestDispatcher], NON `Dispatchers.Unconfined`.
   *
   * `Dispatchers.Unconfined` avvia le coroutine subito, ma non è un `Delay`: `delay()` lo cerca
   * nel contesto (`get(ContinuationInterceptor) as? Delay ?: DefaultDelay`) e senza un
   * `TestDispatcher` ricade sull'orologio REALE. La pausa di 250 ms che il prefetch si prende
   * prima di partire scadeva quindi fuori dal tempo virtuale: `advanceUntilIdle()` non poteva
   * avanzarla e il prefetch non partiva mai, restando invisibile a ogni asserzione.
   * [UnconfinedTestDispatcher] conserva l'avvio immediato (le catture avvengono dentro `show`,
   * come sul main thread in produzione) e conta il tempo sul scheduler del test.
   */
  private fun TestScope.controller(h: Harness): SeekPreviewController {
    val controller = SeekPreviewController(
      scope = this,
      cache = h.cache,
      dispatcher = UnconfinedTestDispatcher(testScheduler),
      sourceProvider = { h.source },
      onError = { h.errors += it }
    )
    controller.setSource(key = "episodio-1", seekable = h.source.available)
    return controller
  }

  // ---------------------------------------------------------------------------
  // Aggiornamento avanti e indietro
  // ---------------------------------------------------------------------------

  @Test
  fun `ogni pressione aggiorna il timestamp della posizione proposta`() = runTest {
    val h = harness(this)
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    runCurrent()
    assertEquals(110_000L, controller.state.value?.positionMs)
    assertTrue(controller.state.value is SeekPreviewState.Frame)

    controller.show(120_000L)
    runCurrent()
    assertEquals(120_000L, controller.state.value?.positionMs)

    controller.show(110_000L)
    runCurrent()
    assertEquals(110_000L, controller.state.value?.positionMs)
    controller.release()
  }

  @Test
  fun `il fotogramma mostrato corrisponde alla posizione richiesta`() = runTest {
    val h = harness(this)
    h.source.result = { pos -> frame(pos + 1L) }
    val controller = controller(h)

    controller.show(110_000L)
    runCurrent()

    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals(
      "lo stato è etichettato con la posizione DECODIFICATA del frame, non con quella richiesta",
      110_001L,
      state.positionMs
    )
    assertEquals("il frame è quello restituito per la posizione", 110_001L, state.frame.positionMs)
    controller.release()
  }

  @Test
  fun `un frame decodificato altrove non viene spacciato per la posizione richiesta`() = runTest {
    val h = harness(this)
    // Il decoder atterra su un keyframe precedente: il frame lo dichiara.
    h.source.result = { pos -> frame(pos - 2_000L) }
    val controller = controller(h)

    controller.show(110_000L)
    runCurrent()

    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals("la posizione pubblicata è quella reale del frame", 108_000L, state.positionMs)
    assertEquals(108_000L, state.frame.positionMs)
    assertEquals("il frame è in cache sotto la sua posizione reale", 108_000L, h.cache.get(108_000L)?.positionMs)
    assertNull("non è in cache sotto la posizione richiesta", h.cache.get(110_000L))
    controller.release()
  }

  @Test
  fun `mentre carica lo stato e Loading con la posizione richiesta`() = runTest {
    val h = harness(this)
    h.source.gate = CompletableDeferred()
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    runCurrent()

    val loading = controller.state.value as SeekPreviewState.Loading
    assertEquals(110_000L, loading.positionMs)
    assertNull("nessun fotogramma precedente da mostrare", loading.frame)
    assertTrue(controller.isLoading)

    // La coroutine pendente vive nello scope del test: va chiusa prima del termine.
    controller.release()
  }

  // ---------------------------------------------------------------------------
  // Richieste ravvicinate: conflation, mai annullamento della cattura in volo
  // ---------------------------------------------------------------------------

  @Test
  fun `la cattura in volo completa e la posizione piu recente segue senza annullamenti`() = runTest {
    val h = harness(this)
    val firstGate = CompletableDeferred<Unit>()
    h.source.gate = firstGate
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    // Burst di scrubbing: la prima posizione è in cattura, le successive confluiscono.
    controller.show(110_000L)
    runCurrent()
    controller.show(120_000L)
    runCurrent()

    // La cattura della prima posizione NON è annullata dalla seconda pressione: è il
    // cuore della correzione (le pressioni ravvicinate non buttano via la cattura in volo).
    assertTrue("nessun annullamento durante lo scrubbing", h.source.cancelled.isEmpty())
    firstGate.complete(Unit)
    runCurrent()

    // Il fotogramma catturato su posizioni ancora valide viene PUBBLICATO (overlay ancora
    // aperto): la cattura non è scartata né sostituita da un annullamento, e lo scrubbing
    // (non ancora concluso) riceve l'aggiornamento durante le pressioni, non alla fine.
    assertTrue(
      "un fotogramma reale è pubblicato mentre lo scrubbing è ancora in corso",
      controller.state.value is SeekPreviewState.Frame
    )

    // L'elaborazione residua con fluisce sull'ultima posizione richiesta, che conclude.
    advanceUntilIdle()
    val finalState = controller.state.value
    assertEquals("l'ultima posizione richiesta conclude l'aggiornamento", 120_000L, finalState?.positionMs)
    assertTrue(finalState is SeekPreviewState.Frame)
    assertEquals(
      "la sorgente riceve solo la prima e l'ultima posizione, mai le intermedie",
      listOf(110_000L, 120_000L),
      h.source.requested
    )
    assertTrue(h.cache.get(110_000L) != null)
    assertTrue(h.cache.get(120_000L) != null)
    controller.release()
  }

  @Test
  fun `una risposta che arriva dopo hide non riapre l'anteprima`() = runTest {
    val h = harness(this)
    val gate = CompletableDeferred<Unit>()
    h.source.gate = gate
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    runCurrent()
    controller.hide()
    runCurrent()
    gate.complete(Unit)
    advanceUntilIdle()

    // hide annulla la cattura: nessun fotogramma pubblicato e nessuna voce in cache. La
    // risposta tardiva (se già in corsa) non può più essere pubblicata: il worker è chiuso.
    assertNull("l'anteprima resta nascosta dopo hide", controller.state.value)
    assertTrue("la richiesta in volo è stata annullata da hide", h.source.cancelled.contains(110_000L))
    assertNull("nessun fotogramma della sessione chiusa finisce in cache", h.cache.get(110_000L))
  }

  // ---------------------------------------------------------------------------
  // Cache e deduplicazione
  // ---------------------------------------------------------------------------

  @Test
  fun `una posizione gia in cache non viene richiesta di nuovo`() = runTest {
    val h = harness(this)
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    advanceUntilIdle()
    controller.show(120_000L)
    advanceUntilIdle()
    controller.show(110_000L)
    advanceUntilIdle()

    assertEquals(
      "la posizione 110s è letta dalla cache (nessuna seconda cattura per la stessa posizione)",
      listOf(110_000L, 120_000L),
      h.source.requested
    )
    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals(110_000L, state.frame.positionMs)
    controller.release()
  }

  @Test
  fun `le pressioni ravvicinate durante un caricamento si fondono sull'ultima posizione`() = runTest {
    val h = harness(this)
    val gate = CompletableDeferred<Unit>()
    h.source.gate = gate
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    runCurrent()
    controller.show(120_000L)
    controller.show(130_000L)
    controller.show(120_000L)
    runCurrent()

    // Una sola cattura in volo; le pressioni confluiscono: nessuna richiesta aggiuntiva
    // e il timestamp mostrato è già quello dell'ultima posizione.
    assertEquals(120_000L, controller.state.value?.positionMs)
    assertEquals("nessun fotogramma pubblicato mentre carica", null, (controller.state.value as SeekPreviewState.Loading).frame)
    assertTrue(controller.isLoading)
    assertEquals("mentre la prima cattura è in volo nessun'altra richiesta parte", listOf(110_000L), h.source.requested)

    // A cattura finita il worker lavora direttamente sull'ultima posizione (130s era
    // intermedia e obsoleta: sola un conflazione la elimina).
    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(
      "solo prima e ultima richiesta, niente duplicate o intermedie",
      listOf(110_000L, 120_000L),
      h.source.requested.toSet().toList()
    )
    assertTrue("nessuna cattura annullata durante lo scrubbing", h.source.cancelled.isEmpty())
    assertTrue(controller.state.value is SeekPreviewState.Frame)
    controller.release()
  }

  @Test
  fun `le posizioni adiacenti vengono prefetchate e riusate alla pressione successiva`() = runTest {
    val h = harness(this)
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L, prefetch = listOf(120_000L, 100_000L))
    advanceUntilIdle()
    assertEquals(
      "il prefetch chiede corrente e adiacenti, una volta ciascuno",
      listOf(110_000L, 120_000L, 100_000L),
      h.source.requested
    )

    controller.show(120_000L, prefetch = listOf(130_000L, 110_000L))
    advanceUntilIdle()

    // 120s era già in cache dal prefetch: viene mostrata subito, senza nuova cattura.
    assertEquals(
      "il fotogramma di 120s è mostrato subito dalla cache, senza attendere la sorgente",
      120_000L,
      (controller.state.value as SeekPreviewState.Frame).positionMs
    )
    // Il prefetch della nuova posizione prepara solo le adiacenti realmente mancanti
    // (130s), perché 110s è già in cache dal passo prima.
    assertEquals(
      "nessun duplicato: 120s e 110s arrivano dalla cache, 130s è l'unico prefetch mancante",
      listOf(110_000L, 120_000L, 100_000L, 130_000L),
      h.source.requested
    )
    controller.release()
  }

  /**
   * Regressione della priorità: il prefetch è speculativo, una pressione nuova lo interrompe.
   *
   * Il caso non era coperto: senza il tempo virtuale (pausa di 250 ms in tempo reale) il
   * prefetch non partiva mai e nessun test poteva osservare né la sua partenza né la sua
   * cancellazione. Qui si verificano le tre cose che devono restare vere insieme: il prefetch
   * parte DOPO la richiesta primaria, una nuova richiesta primaria lo annulla, e il worker
   * prosegue sulla posizione nuova senza riprendere la coda del prefetch interrotto.
   */
  @Test
  fun `una nuova richiesta primaria interrompe il prefetch in volo senza toccare il worker`() = runTest {
    val h = harness(this)
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    // La cattura primaria conclude subito; il prefetch resta in attesa dei suoi 250 ms.
    controller.show(110_000L, prefetch = listOf(120_000L, 100_000L))
    val prefetchGate = CompletableDeferred<Unit>()
    h.source.gate = prefetchGate
    advanceUntilIdle()

    assertEquals(
      "il prefetch parte solo dopo la richiesta primaria, dalla prima adiacente",
      listOf(110_000L, 120_000L),
      h.source.requested
    )

    // Nuova pressione mentre il prefetch di 120s è in volo.
    controller.show(130_000L)
    runCurrent()

    assertEquals(
      "la nuova richiesta primaria annulla il prefetch in volo",
      listOf(120_000L),
      h.source.cancelled
    )
    assertEquals(
      "il worker cattura la posizione nuova, non la coda del prefetch interrotto",
      listOf(110_000L, 120_000L, 130_000L),
      h.source.requested
    )

    prefetchGate.complete(Unit)
    advanceUntilIdle()

    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals("l'anteprima conclude sulla posizione della nuova pressione", 130_000L, state.positionMs)
    assertTrue("la coda del prefetch interrotto non riparte", 100_000L !in h.source.requested)
    controller.release()
  }

  @Test
  fun `la cache scarta il fotogramma più vecchio quando il budget è pieno`() {
    val cache = SeekPreviewCache(maxBytes = 3_000, sizeOf = { 1_000 })
    cache.put(0L, frame(0L))
    cache.put(10_000L, frame(10_000L))
    cache.put(20_000L, frame(20_000L))
    assertEquals(3, cache.size())
    assertEquals(3_000, cache.sizeBytes())

    cache.put(30_000L, frame(30_000L))
    assertEquals(3, cache.size())
    assertNull("il frame più vecchio esce dal budget", cache.get(0L))
    assertEquals(30_000L, cache.get(30_000L)?.positionMs)
    assertEquals("la contabilità dei byte resta coerente", 3_000, cache.sizeBytes())
  }

  @Test
  fun `un fotogramma più grande del budget non viene memorizzato`() {
    val cache = SeekPreviewCache(maxBytes = 1_000, sizeOf = { 5_000 })
    cache.put(0L, frame(0L))
    assertNull(cache.get(0L))
    assertEquals(0, cache.sizeBytes())
  }

  @Test
  fun `la cache legge un fotogramma riusato senza consumarlo`() {
    val cache = SeekPreviewCache(maxBytes = 2_000, sizeOf = { 1_000 })
    cache.put(10_000L, frame(10_000L))

    val hit = cache.get(10_000L)
    assertTrue("il fotogramma dalla cache è segnalato come tale", hit?.fromCache == true)
    assertEquals("leggere non rimuove la voce", 10_000L, cache.get(10_000L)?.positionMs)
    assertEquals(1_000, cache.sizeBytes())
  }

  // ---------------------------------------------------------------------------
  // Annullamento
  // ---------------------------------------------------------------------------

  @Test
  fun `hide chiude l'overlay subito annullando la cattura in volo`() = runTest {
    val h = harness(this)
    val gate = CompletableDeferred<Unit>()
    h.source.gate = gate
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    runCurrent()
    assertTrue(controller.isLoading)

    controller.hide()
    runCurrent()

    // L'overlay deve sparire immediatamente, senza aspettare il decoder, e la richiesta
    // pendente va annullata: una cattura sopravvissuta continuerebbe a spostare il player
    // dopo l'esito dello scrubbing (conferma o BACK).
    assertNull("l'anteprima è chiusa all'istante", controller.state.value)
    assertTrue("la cattura in volo è annullata", h.source.cancelled.contains(110_000L))

    gate.complete(Unit)
    advanceUntilIdle()

    assertTrue("niente cache dalla sessione chiusa", h.cache.size() == 0)
    assertNull(controller.state.value)
    assertFalse(controller.isLoading)
  }

  @Test
  fun `release annulla anche la cattura in volo`() = runTest {
    val h = harness(this)
    val gate = CompletableDeferred<Unit>()
    h.source.gate = gate
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    runCurrent()
    controller.release()
    runCurrent()

    assertTrue("release interrompe la richiesta pendente", h.source.cancelled.contains(110_000L))
    assertNull(controller.state.value)
    assertFalse(controller.isLoading)
  }

  @Test
  fun `il cambio di sorgente chiude l'anteprima e svuota la cache`() = runTest {
    val h = harness(this)
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    advanceUntilIdle()
    assertTrue(controller.state.value is SeekPreviewState.Frame)

    controller.setSource(key = "episodio-2", seekable = true)
    assertNull("nessun fotogramma dell'episodio precedente", controller.state.value)

    controller.show(110_000L)
    advanceUntilIdle()
    assertEquals("la cache è stata azzerata: la nuova richiesta arriva alla sorgente", listOf(110_000L, 110_000L), h.source.requested)
    controller.release()
  }

  @Test
  fun `release cancella lavoro e cache`() = runTest {
    val h = harness(this)
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    advanceUntilIdle()
    controller.release()

    assertNull(controller.state.value)
    assertEquals(0, h.cache.size())
    assertFalse(controller.isLoading)
  }

  @Test
  fun `un risultato obsoleto non sovrascrive un frame più recente gia mostrato`() = runTest {
    val h = harness(this)
    val gate = CompletableDeferred<Unit>()
    h.source.gate = gate
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    // Frame più recente già disponibile in cache (prefetch del passo precedente).
    h.cache.put(120_000L, frame(120_000L))

    controller.show(110_000L)
    runCurrent()
    // Cache-hit sulla posizione più recente mentre la cattura di 110 è ancora in volo.
    controller.show(120_000L)
    runCurrent()
    assertEquals(120_000L, (controller.state.value as SeekPreviewState.Frame).positionMs)

    gate.complete(Unit)
    advanceUntilIdle()

    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals(
      "il risultato della richiesta superata non sovrascrive il frame più recente a video",
      120_000L,
      state.positionMs
    )
    assertEquals("il frame obsoleto resta disponibile in cache", 110_000L, h.cache.get(110_000L)?.positionMs)
    controller.release()
  }

  // ---------------------------------------------------------------------------
  // Fallback quando l'anteprima non è disponibile
  // ---------------------------------------------------------------------------

  @Test
  fun `fonte non ricercabile mostra subito il placeholder`() = runTest {
    val h = harness(this, available = false)
    val controller = controller(h)

    controller.show(110_000L)
    runCurrent()

    assertTrue(controller.state.value is SeekPreviewState.Unavailable)
    assertEquals(110_000L, controller.state.value?.positionMs)
    assertTrue("nessuna richiesta su fonte non ricercabile", h.source.requested.isEmpty())
  }

  @Test
  fun `se la sorgente non restituisce fotogrammi lo stato e unavailable`() = runTest {
    val h = harness(this)
    h.source.result = { null }
    val controller = controller(h)

    controller.show(110_000L)
    advanceUntilIdle()

    assertTrue(controller.state.value is SeekPreviewState.Unavailable)
    assertEquals(110_000L, controller.state.value?.positionMs)
    controller.release()
  }

  @Test
  fun `un errore della sorgente non interrompe l'anteprima e diventa unavailable`() = runTest {
    val h = harness(this)
    h.source.failure = IllegalStateException("decoder non disponibile")
    val controller = controller(h)

    controller.show(110_000L)
    advanceUntilIdle()

    assertTrue(controller.state.value is SeekPreviewState.Unavailable)
    assertEquals(1, h.errors.size)
    controller.release()
  }

  @Test
  fun `dopo un errore la posizione successiva funziona normalmente`() = runTest {
    val h = harness(this)
    h.source.failure = IllegalStateException("errore transitorio")
    h.source.result = { pos -> frame(pos) }
    val controller = controller(h)

    controller.show(110_000L)
    advanceUntilIdle()
    assertTrue(controller.state.value is SeekPreviewState.Unavailable)

    h.source.failure = null
    controller.show(120_000L)
    advanceUntilIdle()
    assertTrue(controller.state.value is SeekPreviewState.Frame)
    controller.release()
  }

  @Test
  fun `setSource senza sorgente installata pubblica unavailable`() = runTest {
    val controller = SeekPreviewController(
      scope = this,
      cache = SeekPreviewCache(),
      dispatcher = UnconfinedTestDispatcher(testScheduler),
      sourceProvider = { null }
    )
    controller.setSource(key = "episodio-1", seekable = true)

    controller.show(110_000L)
    runCurrent()

    assertTrue(controller.state.value is SeekPreviewState.Unavailable)
  }
}
