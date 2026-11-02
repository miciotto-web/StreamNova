package com.example.ui.screens.player

import androidx.media3.common.C
import com.example.ui.viewmodel.PlayerPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 6.1 — guardie sull'isolamento dello stato aggiornato ogni 500 ms dal polling.
 *
 * Questi test verificano il MECCANISMO su cui si regge l'ottimizzazione:
 * 1. la proiezione di composizione ([playbackStateWithoutPosition]) non cambia quando il
 *    polling aggiorna solo posizione/buffer (quindi `derivedStateOf` non invalida il corpo
 *    di `PlayerScreen`), ma cambia per qualsiasi modifica reale di contenuto/sorgente;
 * 2. [PlayerPlaybackClock] non scrive lo stato quando il valore è identico o sconosciuto;
 * 3. [SubtitleStyleApplier] riapplica stile/dimensione/posizione (e quindi il log) solo
 *    quando i valori effettivi cambiano o la `PlayerView` viene ricreata.
 *
 * Solo JVM: nessun Android/emulatore richiesto.
 */
class PlayerPlaybackStateIsolationTest {

  @Test
  fun laSolaPosizioneAggiornataDalPollingNonCambiaLaProiezioneDiComposizione() {
    val base = PlayerPlaybackState(
      durationMs = 100_000L,
      streamUrl = "https://example.org/video.m3u8",
      streamServer = "Server A"
    )
    val dopoUnTick = base.copy(
      currentPositionMs = 1_500L,
      bufferedPositionMs = 9_000L
    )

    // Lo stato emesso dal ViewModel cambia davvero ad ogni tick...
    assertNotEquals(base, dopoUnTick)
    // ...ma la proiezione letta in composizione resta identica: nessuna invalidazione.
    assertEquals(
      playbackStateWithoutPosition(base),
      playbackStateWithoutPosition(dopoUnTick)
    )

    // Una sequenza di tick consecutivi produce sempre lo stesso valore di proiezione.
    val proiezioneIniziale = playbackStateWithoutPosition(base)
    for (tick in 1..120) {
      val emesso = base.copy(
        currentPositionMs = tick * 500L,
        bufferedPositionMs = tick * 500L + 2_000L
      )
      assertEquals(proiezioneIniziale, playbackStateWithoutPosition(emesso))
    }
  }

  @Test
  fun unCambioRealeDiContenutoRestaVisibileInComposizione() {
    val base = PlayerPlaybackState(
      durationMs = 100_000L,
      streamUrl = "https://example.org/video.m3u8",
      currentPositionMs = 5_000L,
      bufferedPositionMs = 8_000L
    )

    val nuovaSorgente = base.copy(streamUrl = "https://example.org/altra.m3u8")
    assertNotEquals(
      "Il cambio di sorgente deve invalidare la composizione",
      playbackStateWithoutPosition(base),
      playbackStateWithoutPosition(nuovaSorgente)
    )

    val nuovaDurata = base.copy(durationMs = 120_000L)
    assertNotEquals(
      "La durata nota deve invalidare la composizione",
      playbackStateWithoutPosition(base),
      playbackStateWithoutPosition(nuovaDurata)
    )

    val nuovoOffset = base.copy(subtitleOffsetMs = 750L)
    assertNotEquals(
      "L'offset sottotitoli deve invalidare la composizione",
      playbackStateWithoutPosition(base),
      playbackStateWithoutPosition(nuovoOffset)
    )

    // Metadati del contenuto: cambiano raramente e devono propagarsi subito.
    assertNotEquals(
      playbackStateWithoutPosition(base),
      playbackStateWithoutPosition(base.copy(streamServer = "Server B"))
    )
  }

  @Test
  fun orologioNonScriveStatoQuandoIlValoreNonCambiaONonEValido() {
    val clock = PlayerPlaybackClock(initialPositionMs = 1_000L, initialDurationMs = 60_000L)

    assertFalse("Posizione identica: nessuna scrittura", clock.updatePosition(1_000L))
    assertTrue("Posizione diversa: scrittura", clock.updatePosition(2_000L))
    assertEquals(2_000L, clock.positionMs)
    assertFalse("Posizione identica dopo l'aggiornamento", clock.updatePosition(2_000L))

    // Durata sconosciuta (C.TIME_UNSET) e 0 non sono durate: non sovrascrivono il noto.
    assertFalse(clock.updateDuration(C.TIME_UNSET))
    assertFalse(clock.updateDuration(0L))
    assertEquals(60_000L, clock.durationMs)
    assertTrue(clock.updateDuration(61_000L))
    assertEquals(61_000L, clock.durationMs)
    assertFalse(clock.updateDuration(61_000L))
  }

  @Test
  fun stileSottotitoliRiapplicatoSoloAlCambioDeiValori() {
    val applier = SubtitleStyleApplier()
    val view = Any()
    val snapshot = SubtitleStyleSnapshot(
      textSizeFraction = 0.06f,
      backgroundColor = 0x80000000.toInt(),
      bottomPaddingFraction = 0.05f
    )

    // Prima applicazione: sempre necessaria (la PlayerView nasce con lo stile di default).
    assertTrue(applier.shouldApply(view, snapshot))
    applier.markApplied(view, snapshot)

    // Ricomposizioni con gli stessi valori: nessuna riapplicazione, nessun log ripetitivo.
    repeat(20) { assertFalse(applier.shouldApply(view, snapshot)) }

    // Ogni valore effettivo, se cambia, deve riapplicare.
    assertTrue(applier.shouldApply(view, snapshot.copy(textSizeFraction = 0.08f)))
    assertTrue(applier.shouldApply(view, snapshot.copy(backgroundColor = 0)))
    assertTrue(applier.shouldApply(view, snapshot.copy(bottomPaddingFraction = 0.10f)))

    // PlayerView ricreata (istanza diversa): le preferenze vanno riapplicate subito.
    assertTrue(
      "Una nuova PlayerView deve ricevere lo stile corrente senza aspettare un cambio",
      applier.shouldApply(Any(), snapshot)
    )
  }
}
