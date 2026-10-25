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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressioni P0/P1 dell'anteprima sullo scrubbing Fire TV (mt8696), lato controller.
 *
 * I due bug di accettazione:
 * - **frame fisso all'indietro**: il gate di pubblicazione confrontava i PTS in modo monotòno
 *   crescente (`decoded >= displayed`), quindi ogni fotogramma di un seek inverso veniva
 *   scartato e l'overlay restava inchiodato all'immagine del punto di partenza;
 * - **fotogramma finale incoerente in avanti**: il frame della posizione corrente arrivato per
 *   un'altra strada (prefetch, cattura superata) non veniva mai adottato, e l'overlay restava
 *   su `Loading` con l'immagine vecchia.
 *
 * Più le garanzie che dovevano sopravvivere alla correzione: nessun risultato obsoleto
 * sovrascrive un frame più vicino all'intento, in nessuno dei due versi, e un frame di cui il
 * decoder non ha confermato il PTS non viene mai spacciato per il risultato definitivo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SeekPreviewPublicationGateTest {

  private companion object {
    val FAKE_BITMAP: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    /** Passo di scrubbing reale, per costruire sequenze avanti/indietro credibili. */
    const val STEP_MS = 10_000L
  }

  private fun frame(positionMs: Long, ptsAuthoritative: Boolean = true): SeekPreviewFrame =
    SeekPreviewFrame(
      bitmap = FAKE_BITMAP,
      positionMs = positionMs,
      ptsAuthoritative = ptsAuthoritative
    )

  /**
   * Sorgente controllabile: [gate] blocca ogni cattura finché il test non la rilascia,
   * [result] decide il PTS restituito (e quindi la scena) per una posizione richiesta.
   */
  private class FakeSource : SeekPreviewSource {
    val requested = mutableListOf<Long>()
    var gate: CompletableDeferred<Unit>? = null

    // Costruito qui (e non con un helper della classe esterna) perch FakeSource annidato:
    // non vede i membri istanza del test.
    var result: (Long) -> SeekPreviewFrame? = { pos ->
      SeekPreviewFrame(bitmap = FAKE_BITMAP, positionMs = pos)
    }
    override val isAvailable: Boolean get() = true

    override suspend fun load(positionMs: Long): SeekPreviewFrame? {
      requested += positionMs
      gate?.await()
      return result.invoke(positionMs)
    }

    override fun release() {}
  }

  private fun harness(scope: TestScope): Harness {
    val source = FakeSource()
    val cache = SeekPreviewCache()
    // UnconfinedTestDispatcher e non Dispatchers.Unconfined: avvio immediato uguale (le catture
    // avvengono dentro show), ma la pausa di 250 ms del prefetch resta sul tempo virtuale.
    // Con Dispatchers.Unconfined ricadrebbe sull'orologio reale e advanceUntilIdle() non la
    // avanzerebbe: il prefetch non partirebbe mai.
    val controller = SeekPreviewController(
      scope = scope,
      cache = cache,
      dispatcher = UnconfinedTestDispatcher(scope.testScheduler),
      sourceProvider = { source }
    )
    controller.setSource(key = "hls-content", seekable = true)
    return Harness(controller, source, cache)
  }

  /** Triplo di servizio: controller, sorgente finta e cache condivisa. */
  private data class Harness(
    val controller: SeekPreviewController,
    val source: FakeSource,
    val cache: SeekPreviewCache
  )

  private fun completedGate(): CompletableDeferred<Unit> =
    CompletableDeferred<Unit>().apply { complete(Unit) }

  // ---------------------------------------------------------------------------
  // Bug 2 — seek inverso: ogni posizione deve poter produrre un frame nuovo
  // ---------------------------------------------------------------------------

  @Test
  fun `un seek inverso pubblica il suo fotogramma invece di tenere quello vecchio`() = runTest {
    val (controller, _) = harness(this)

    controller.show(200_000L)
    advanceUntilIdle()
    val forward = controller.state.value as SeekPreviewState.Frame
    assertEquals(200_000L, forward.positionMs)

    controller.show(190_000L)
    advanceUntilIdle()

    val backward = controller.state.value as SeekPreviewState.Frame
    assertEquals(
      "il fotogramma del seek inverso deve andare a video (prima il gate monotono lo scartava)",
      190_000L,
      backward.positionMs
    )
    assertTrue("a PTS coerente corrisponde un risultato definitivo", backward.isDefinitive)
    controller.release()
  }

  @Test
  fun `una sequenza completa allindietro aggiorna ogni posizione`() = runTest {
    val (controller, source) = harness(this)

    controller.show(240_000L)
    advanceUntilIdle()

    val visited = mutableListOf<Long>()
    var position = 240_000L
    repeat(5) {
      position -= STEP_MS
      controller.show(position)
      advanceUntilIdle()
      val state = controller.state.value
      assertNotNull("nessuna posizione deve restare senza stato", state)
      assertEquals(
        "posizione $position: l'anteprima non deve restare ferma",
        position,
        state!!.positionMs
      )
      visited += state.positionMs
    }

    assertEquals(
      "ogni pressione all'indietro produce un fotogramma distinto",
      5,
      visited.distinct().size
    )
    assertFalse(
      "la sorgente viene interrogata per le posizioni inverse",
      source.requested.isEmpty()
    )
    controller.release()
  }

  @Test
  fun `linversione di direzione non fa scartare il frame come obsoleto`() = runTest {
    val (controller, _) = harness(this)

    // Avanti, poi inversione: il caso esatto del collaudo (press AVANTI seguiti da INDIETRO).
    controller.show(150_000L)
    advanceUntilIdle()
    controller.show(160_000L)
    advanceUntilIdle()
    controller.show(150_000L)
    advanceUntilIdle()
    controller.show(140_000L)
    advanceUntilIdle()

    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals(
      "il frame di 140s e piu vicino all'intento di quello di 150s: va pubblicato",
      140_000L,
      state.positionMs
    )
    controller.release()
  }

  // ---------------------------------------------------------------------------
  // Bug 1 — frame finale: il fotogramma deve arrivare a video qualunque strada abbia preso
  // ---------------------------------------------------------------------------

  @Test
  fun `il frame arrivato dal prefetch per la posizione corrente viene adottato`() = runTest {
    val (controller, source) = harness(this)

    // 184s parte e viene servita subito.
    source.gate = completedGate()
    controller.show(184_000L, prefetch = listOf(174_000L))
    runCurrent()
    assertEquals(184_000L, (controller.state.value as SeekPreviewState.Frame).positionMs)

    // Da qui il prefetch di 174s resta in volo; nel frattempo l'utente ci arriva davvero.
    val prefetchGate = CompletableDeferred<Unit>()
    source.gate = prefetchGate
    controller.show(174_000L)
    runCurrent()
    assertTrue("174s e in attesa: l'overlay mostra ancora 184s", controller.isLoading)

    prefetchGate.complete(Unit)
    advanceUntilIdle()

    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals(
      "il frame di 174s prodotto dal prefetch deve finire a video, non restare solo in cache",
      174_000L,
      state.positionMs
    )
    assertTrue(state.isDefinitive)
    controller.release()
  }

  @Test
  fun `dopo un movimento rapido la posizione finale ottiene un frame definitivo`() = runTest {
    val (controller, source) = harness(this)
    val gate = CompletableDeferred<Unit>()
    source.gate = gate

    // Pressioni rapide: la prima resta in volo mentre l'utente corre a 190s.
    controller.show(110_000L)
    runCurrent()
    var position = 110_000L
    repeat(8) {
      position += STEP_MS
      controller.show(position)
      runCurrent()
    }
    gate.complete(Unit)
    advanceUntilIdle()

    val finalPosition = position
    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals(
      "l'anteprima deve concludere sulla scena del punto finale, non su una tappa intermedia",
      finalPosition,
      state.positionMs
    )
    assertTrue(
      "il frame finale deve essere definitivo, non un'approssimazione",
      state.isDefinitive
    )
    assertEquals(
      "la posizione richiesta registrata e quella finale",
      finalPosition,
      state.requestedPositionMs
    )
    controller.release()
  }

  @Test
  fun `lo stato non resta Loading quando la sorgente puo servire la posizione corrente`() = runTest {
    val (controller, _) = harness(this)

    // Alternanza avanti/indietro senza chiudere l'overlay: nessuna pressione deve lasciare
    // l'overlay appeso a un fotogramma di un'altra scena.
    var position = 200_000L
    listOf(+1, +1, -1, -1, +1, -1, +1, +1).forEach { step ->
      position += step * STEP_MS
      controller.show(position)
      advanceUntilIdle()
      val state = controller.state.value
      assertFalse(
        "dopo aver servito $position lo stato non deve essere Loading",
        state is SeekPreviewState.Loading
      )
      assertEquals(position, state?.positionMs)
    }
    controller.release()
  }

  // ---------------------------------------------------------------------------
  // Onestà del timestamp: approssimazione vs risultato definitivo
  // ---------------------------------------------------------------------------

  @Test
  fun `un frame di PTS lontano e marcato come approssimazione`() = runTest {
    val (controller, source) = harness(this)
    // Il decoder atterra cinque secondi prima: scena diversa da quella chiesta.
    source.result = { pos -> frame(pos - 5_000L) }

    controller.show(200_000L)
    advanceUntilIdle()

    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals(
      "la posizione pubblicata e il PTS reale del frame, non quello che e stato chiesto",
      195_000L,
      state.positionMs
    )
    assertEquals(200_000L, state.requestedPositionMs)
    assertTrue("un frame a 5s dalla richiesta e un'approssimazione", state.approximate)
    assertFalse(
      "e non puo essere presentato come l'esito del punto scelto",
      state.isDefinitive
    )
    controller.release()
  }

  @Test
  fun `un frame senza PTS confermato e sempre un approssimazione`() = runTest {
    val (controller, source) = harness(this)
    source.result = { pos -> frame(pos, ptsAuthoritative = false) }

    controller.show(200_000L)
    advanceUntilIdle()

    val state = controller.state.value as SeekPreviewState.Frame
    assertTrue("senza PTS autorevole il frame non e mai definitivo", state.approximate)
    assertFalse(state.isDefinitive)
    controller.release()
  }

  // ---------------------------------------------------------------------------
  // Protezioni contro i risultati obsoleti: corrette, non rimosse
  // ---------------------------------------------------------------------------

  @Test
  fun `un risultato superato non sovrascrive il frame piu vicino allintento`() = runTest {
    val (controller, source, cache) = harness(this)
    val gate = CompletableDeferred<Unit>()
    source.gate = gate
    // Il frame della posizione NUOVA è già disponibile (arrivato prima, dal prefetch).
    cache.put(210_000L, frame(210_000L))

    // La cattura di 200s parte e resta in volo.
    controller.show(200_000L)
    runCurrent()

    // L'utente si sposta su 210s: cache-hit, va subito a video.
    source.gate = completedGate()
    controller.show(210_000L)
    runCurrent()
    assertEquals(210_000L, (controller.state.value as SeekPreviewState.Frame).positionMs)

    // Adesso termina la cattura di 200s: è una richiesta superata e più lontana dall'intento.
    gate.complete(Unit)
    advanceUntilIdle()

    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals(
      "il fotogramma della richiesta superata non sostituisce quello piu vicino all'intento",
      210_000L,
      state.positionMs
    )
    assertNotNull(
      "il frame scartato resta comunque in cache per le pressioni successive",
      cache.get(200_000L)
    )
    controller.release()
  }

  @Test
  fun `a parita di distanza avanza sulla richiesta piu recente`() = runTest {
    val (controller, source) = harness(this)
    // I due frame sono ugualmente lontani dall'intento (190s e 210s rispetto a 200s):
    // deve andare a video quello della richiesta piu recente, altrimenti l'anteprima resta
    // inchiodata di un passo intero indietro rispetto a dove l'utente si è fermato.
    source.result = { pos -> frame(pos) }

    controller.show(190_000L)
    advanceUntilIdle()
    assertEquals(190_000L, (controller.state.value as SeekPreviewState.Frame).positionMs)

    // La richiesta nuova (200s) viene servita da un frame caduto su 210s: stessa distanza
    // del 190s gia a video, ma richiesta piu recente -> vince lui.
    source.result = { pos -> frame(210_000L) }
    controller.show(200_000L)
    advanceUntilIdle()

    val state = controller.state.value as SeekPreviewState.Frame
    assertEquals(
      "a distanza identica precede la richiesta piu recente",
      210_000L,
      state.positionMs
    )
    assertEquals(200_000L, state.requestedPositionMs)
    assertTrue(
      "un frame a 10s dalla richiesta resta un approssimazione, anche se va a video",
      !state.isDefinitive
    )
    controller.release()
  }
}
