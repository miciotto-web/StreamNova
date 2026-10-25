package com.example.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate del PTS: il controllo che decide se il frame presentato dal renderer e il frame del
 * seek appena emesso.
 *
 * E il punto esatto in cui si nascondeva il fotogramma fisso dello scrubbing inverso: la
 * versione precedente chiedeva solo "sei vicino a quello che ho chiesto?", con una tolleranza
 * di 10 s — un passo intero di scrubbing. Il frame del punto di partenza era sempre dentro
 * quella finestra della richiesta fatta un passo prima, quindi veniva accettato al posto del
 * frame nuovo.
 *
 * Le coppie di test avanti/indietro sono volutamente speculari: se il gate fosse corretto solo
 * in un verso, il bug sopravviverebbe nell'altro.
 */
class SeekPreviewSeekGateTest {

  private companion object {
    const val STEP_MS = 10_000L
  }

  // ---------------------------------------------------------------------------
  // Symetrie: lo stesso seek deve essere accettato in avanti e allindietro
  // ---------------------------------------------------------------------------

  @Test
  fun `il frame sul target e accettato andando avanti`() {
    assertTrue(SeekPreviewSeekGate.belongsToSeek(ptsMs = 110_025L, requestedMs = 110_000L, preSeekMs = 100_000L))
  }

  @Test
  fun `il frame sul target e accettato andando indietro`() {
    assertTrue(SeekPreviewSeekGate.belongsToSeek(ptsMs = 189_975L, requestedMs = 190_000L, preSeekMs = 200_000L))
  }

  // ---------------------------------------------------------------------------
  // Il frame del punto di partenza non deve mai passare, in nessun verso
  // ---------------------------------------------------------------------------

  @Test
  fun `il frame del punto di partenza e scartato in avanti`() {
    assertFalse(SeekPreviewSeekGate.belongsToSeek(ptsMs = 100_000L, requestedMs = 110_000L, preSeekMs = 100_000L))
  }

  @Test
  fun `il frame del punto di partenza e scartato indietro`() {
    assertFalse(SeekPreviewSeekGate.belongsToSeek(ptsMs = 200_000L, requestedMs = 190_000L, preSeekMs = 200_000L))
  }

  @Test
  fun `un passo intero di scarto non e mai il frame del seek`() {
    // Esattamente il caso che la tolleranza da 10 s lasciava passare.
    assertFalse(
      SeekPreviewSeekGate.belongsToSeek(
        ptsMs = 190_000L,
        requestedMs = 200_000L,
        preSeekMs = 190_000L
      )
    )
    assertFalse(
      SeekPreviewSeekGate.belongsToSeek(
        ptsMs = 200_000L,
        requestedMs = 190_000L,
        preSeekMs = 200_000L
      )
    )
  }

  // ---------------------------------------------------------------------------
  // Atterraggio realistico su HLS
  // ---------------------------------------------------------------------------

  @Test
  fun `il keyframe entro la finestra di atterraggio e accettato`() {
    // Decoder senza seek accurato: atterra un po prima del target, ma ben piu vicino al
    // target che al punto di partenza.
    assertTrue(
      SeekPreviewSeekGate.belongsToSeek(
        ptsMs = 189_200L,
        requestedMs = 190_000L,
        preSeekMs = 200_000L
      )
    )
  }

  @Test
  fun `oltre la finestra di atterraggio il frame e sempre scartato`() {
    assertFalse(
      SeekPreviewSeekGate.belongsToSeek(
        ptsMs = 188_000L,
        requestedMs = 190_000L,
        preSeekMs = 200_000L
      )
    )
    assertFalse(
      SeekPreviewSeekGate.belongsToSeek(
        ptsMs = 30_000L,
        requestedMs = 190_000L,
        preSeekMs = 200_000L
      )
    )
  }

  @Test
  fun `la finestra di atterraggio e molto piu piccola di un passo di scrubbing`() {
    // Se la finestra tornasse dellordine del passo, il frame del punto di partenza ci
    // rientrerebbe di nuovo: e linvariante che tiene vivo il bug.
    assertTrue(SeekPreviewSeekGate.SEEK_LANDING_TOLERANCE_MS * 4 < STEP_MS)
  }

  // ---------------------------------------------------------------------------
  // Casi limite
  // ---------------------------------------------------------------------------

  @Test
  fun `una pressione che non sposta la posizione accetta comunque il proprio frame`() {
    // Ai limiti del contenuto la posizione non cambia: pre-seek e target coincidono.
    assertTrue(SeekPreviewSeekGate.belongsToSeek(ptsMs = 0L, requestedMs = 0L, preSeekMs = 0L))
  }

  @Test
  fun `un frame leggermente oltre il target ma gia lontano dalla partenza e accettato`() {
    assertTrue(
      SeekPreviewSeekGate.belongsToSeek(
        ptsMs = 190_900L,
        requestedMs = 190_000L,
        preSeekMs = 200_000L
      )
    )
  }
}
