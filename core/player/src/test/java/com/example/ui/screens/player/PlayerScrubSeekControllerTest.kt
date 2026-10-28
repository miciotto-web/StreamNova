package com.example.ui.screens.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test del comportamento del controller sul player: apertura dello scrubbing con un passo
 * di anteprima, pausa immediata, nessuna seek prima della conferma, conferma dal timer di
 * inattività o da OK, annullamento con BACK, ripresa della riproduzione solo se era attiva
 * e limiti del contenuto.
 *
 * Il player è un finto [SimpleBasePlayer]: nessun media reale, nessuna rete, ma stesse
 * decisioni di Media3 su ricercabilità, durata, play/pausa e posizione.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerScrubSeekControllerTest {

  private val tenMinutesMs = 600_000L

  /** Player controllabile: elemento non ricercabile = live/flusso senza seek. */
  private class FakePlayer(
    private val seekable: Boolean,
    private val durationMs: Long,
    private var playWhenReady: Boolean = true,
    private val playbackState: Int = Player.STATE_READY,
    private var positionMs: Long = 0L
  ) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** Posizioni richieste con seekTo: vuoto finché lo scrubbing non viene confermato. */
    val seekTargets = mutableListOf<Long>()

    /** Richieste di play(true)/pausa(false) arrivate al player, in ordine. */
    val playWhenReadyChanges = mutableListOf<Boolean>()

    override fun getState(): State = State.Builder()
      .setAvailableCommands(availableCommands())
      .setPlaybackState(playbackState)
      .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaylist(
        listOf(
          MediaItemData.Builder("fake-media-item")
            .setMediaItem(MediaItem.fromUri("test://streamnova/fake"))
            .setMediaMetadata(MediaMetadata.EMPTY)
            .setDurationUs(durationMs * 1_000L)
            .setIsSeekable(seekable)
            .build()
        )
      )
      .setCurrentMediaItemIndex(0)
      .setContentPositionMs(positionMs)
      .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
      this.playWhenReady = playWhenReady
      playWhenReadyChanges += playWhenReady
      return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
      mediaItemIndex: Int,
      positionMs: Long,
      seekCommand: Int
    ): ListenableFuture<*> {
      seekTargets += positionMs
      this.positionMs = positionMs
      return Futures.immediateVoidFuture()
    }

    private fun availableCommands(): Player.Commands = if (seekable) {
      Player.Commands.Builder()
        .addAll(
          Player.COMMAND_GET_TIMELINE,
          Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
          Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
          Player.COMMAND_PLAY_PAUSE
        )
        .build()
    } else {
      Player.Commands.Builder()
        .addAll(
          Player.COMMAND_GET_TIMELINE,
          Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
          Player.COMMAND_PLAY_PAUSE
        )
        .build()
    }
  }

  /** Controller collegato al tempo virtuale del test, con lo stato pubblicato raccolto qui. */
  private class Harness(
    val player: FakePlayer,
    scope: TestScope
  ) {
    val published = mutableListOf<PlayerScrubUiState?>()
    val controller = PlayerScrubController(scope, player) { published += it }

    /** Ultimo stato pubblicato: null quando lo scrubbing è concluso. */
    val state: PlayerScrubUiState? get() = published.last()
  }

  private fun harness(
    scope: TestScope,
    seekable: Boolean = true,
    durationMs: Long = tenMinutesMs,
    positionMs: Long = 0L,
    wasPlaying: Boolean = true,
    playbackState: Int = Player.STATE_READY
  ) = Harness(
    player = FakePlayer(
      seekable = seekable,
      durationMs = durationMs,
      playWhenReady = wasPlaying,
      playbackState = playbackState,
      positionMs = positionMs
    ),
    scope = scope
  )

  /** Avanza il tempo virtuale (e quindi il timer di conferma) eseguendo le coroutine. */
  private fun TestScope.advanceBy(elapsedMs: Long) {
    advanceTimeBy(elapsedMs)
    runCurrent()
  }

  // ---------------------------------------------------------------------------
  // Ricercabilità del flusso
  // ---------------------------------------------------------------------------

  @Test
  fun `un flusso non ricercabile non apre lo scrubbing`() = runTest {
    val h = harness(this, seekable = false)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.onArrowPressed(PlayerScrub.FORWARD)

    assertFalse("su live/flussi senza seek non deve aprirsi nulla", h.controller.isScrubbing)
    assertTrue("nessuno stato di anteprima va pubblicato", h.published.isEmpty())
    assertTrue("la riproduzione non deve essere toccata", h.player.playWhenReadyChanges.isEmpty())

    advanceBy(PlayerScrub.COMMIT_DELAY_MS + 100L)
    assertTrue("nessuna seek su contenuto non ricercabile", h.player.seekTargets.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Apertura: pausa e anteprima
  // ---------------------------------------------------------------------------

  @Test
  fun `la prima pressione mette in pausa e apre l'anteprima un passo avanti`() = runTest {
    val h = harness(this, positionMs = 100_000L)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)

    assertTrue(h.controller.isScrubbing)
    assertEquals("il video si ferma subito", listOf(false), h.player.playWhenReadyChanges)
    assertTrue("finché non si conferma il player non viene spostato", h.player.seekTargets.isEmpty())
    assertEquals(110_000L, h.state?.positionMs)
    assertEquals(100_000L, h.state?.originPositionMs)
    assertEquals(10_000L, h.state?.deltaMs)
    assertEquals(tenMinutesMs, h.state?.durationMs)
    assertTrue(h.state?.wasPlayingBeforeScrub == true)
  }

  @Test
  fun `le pressioni successive spostano l'anteprima senza toccare il player`() = runTest {
    val h = harness(this, positionMs = 100_000L)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.onArrowPressed(PlayerScrub.REWIND)

    assertEquals("la pausa arriva una volta sola", listOf(false), h.player.playWhenReadyChanges)
    assertTrue("nessuna seek durante l'anteprima", h.player.seekTargets.isEmpty())
    assertEquals(120_000L, h.state?.positionMs)
    assertEquals(20_000L, h.state?.deltaMs)
  }

  @Test
  fun `un video gia in pausa non viene rimesso in riproduzione dalla conferma`() = runTest {
    val h = harness(this, positionMs = 100_000L, wasPlaying = false)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    advanceBy(PlayerScrub.COMMIT_DELAY_MS + 1L)

    assertEquals("un video in pausa resta in pausa", listOf<Boolean>(false), h.player.playWhenReadyChanges)
    assertEquals(listOf(110_000L), h.player.seekTargets)
    assertFalse(h.controller.isScrubbing)
  }

  @Test
  fun `un video in caricamento viene fermato e poi ripreso`() = runTest {
    val h = harness(this, positionMs = 30_000L, playbackState = Player.STATE_BUFFERING)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    assertEquals("anche in buffering lo scrubbing mette in pausa", listOf(false), h.player.playWhenReadyChanges)

    advanceBy(PlayerScrub.COMMIT_DELAY_MS + 1L)
    assertEquals("la ripresa non si perde se al momento della pressione stava caricando", listOf(false, true), h.player.playWhenReadyChanges)
  }

  // ---------------------------------------------------------------------------
  // Conferma dal timer di inattività
  // ---------------------------------------------------------------------------

  @Test
  fun `conferma dopo il silenzio - la seek arriva una volta sola e la barra sparisce`() = runTest {
    val h = harness(this, positionMs = 100_000L)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.onArrowPressed(PlayerScrub.FORWARD)

    advanceBy(PlayerScrub.COMMIT_DELAY_MS - 1L)
    assertTrue("prima dello scadere del timer nessuna seek", h.player.seekTargets.isEmpty())

    advanceBy(2L)
    assertEquals("la seek applica la posizione di anteprima", listOf(120_000L), h.player.seekTargets)
    assertEquals("la riproduzione riprende", listOf(false, true), h.player.playWhenReadyChanges)
    assertFalse(h.controller.isScrubbing)
    assertNull("lo stato di anteprima viene chiuso", h.state)

    advanceBy(PlayerScrub.COMMIT_DELAY_MS * 3L)
    assertEquals("nessuna seek residua", listOf(120_000L), h.player.seekTargets)
  }

  @Test
  fun `ogni pressione riavvia il timer di conferma`() = runTest {
    val h = harness(this, positionMs = 0L)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    advanceBy(PlayerScrub.COMMIT_DELAY_MS - 100L)
    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    advanceBy(PlayerScrub.COMMIT_DELAY_MS - 100L)
    h.controller.onArrowPressed(PlayerScrub.FORWARD)

    assertTrue("le pressioni ravvicinate non devono produrre seek", h.player.seekTargets.isEmpty())

    advanceBy(PlayerScrub.COMMIT_DELAY_MS + 1L)
    assertEquals("la conferma usa l'ultima posizione di anteprima", listOf(30_000L), h.player.seekTargets)
  }

  // ---------------------------------------------------------------------------
  // Conferma immediata (OK) e annullamento (BACK)
  // ---------------------------------------------------------------------------

  @Test
  fun `la conferma immediata applica la seek senza aspettare il timer`() = runTest {
    val h = harness(this, positionMs = 100_000L)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.commit()

    assertEquals(listOf(110_000L), h.player.seekTargets)
    assertEquals(listOf(false, true), h.player.playWhenReadyChanges)
    assertNull(h.state)

    advanceBy(PlayerScrub.COMMIT_DELAY_MS * 2L)
    assertEquals("il timer è disarmato: nessuna seek duplicata", listOf(110_000L), h.player.seekTargets)
    assertFalse(h.controller.isScrubbing)
  }

  @Test
  fun `l'annullamento non sposta il video e ripristina la riproduzione`() = runTest {
    val h = harness(this, positionMs = 100_000L)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.cancel()

    assertTrue("BACK non deve applicare alcuna seek", h.player.seekTargets.isEmpty())
    assertEquals("si riprende dal punto di partenza", listOf(false, true), h.player.playWhenReadyChanges)
    assertNull(h.state)
    assertFalse(h.controller.isScrubbing)

    advanceBy(PlayerScrub.COMMIT_DELAY_MS * 2L)
    assertTrue("il timer è disarmato anche dopo l'annullamento", h.player.seekTargets.isEmpty())
  }

  @Test
  fun `l'annullamento di un video in pausa non avvia la riproduzione`() = runTest {
    val h = harness(this, positionMs = 100_000L, wasPlaying = false)

    h.controller.onArrowPressed(PlayerScrub.REWIND)
    h.controller.cancel()

    assertEquals(listOf<Boolean>(false), h.player.playWhenReadyChanges)
    assertTrue(h.player.seekTargets.isEmpty())
  }

  @Test
  fun `conferma e annullamento senza scrubbing aperto non fanno nulla`() = runTest {
    val h = harness(this, positionMs = 100_000L)

    h.controller.commit()
    h.controller.cancel()

    assertTrue(h.player.seekTargets.isEmpty())
    assertTrue(h.player.playWhenReadyChanges.isEmpty())
    assertTrue(h.published.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Limiti del contenuto
  // ---------------------------------------------------------------------------

  @Test
  fun `l'anteprima non supera la durata e la conferma si ferma sulla fine`() = runTest {
    val h = harness(this, durationMs = 30_000L, positionMs = 20_000L)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.onArrowPressed(PlayerScrub.FORWARD)

    assertEquals("l'anteprima si ferma sulla durata", 30_000L, h.state?.positionMs)

    advanceBy(PlayerScrub.COMMIT_DELAY_MS + 1L)
    assertEquals(listOf(30_000L), h.player.seekTargets)
  }

  @Test
  fun `l'anteprima indietro si ferma all'inizio del contenuto`() = runTest {
    val h = harness(this, positionMs = 5_000L)

    h.controller.onArrowPressed(PlayerScrub.REWIND)
    assertEquals(0L, h.state?.positionMs)
    assertEquals(-5_000L, h.state?.deltaMs)

    h.controller.onArrowPressed(PlayerScrub.REWIND)
    assertEquals(0L, h.state?.positionMs)

    advanceBy(PlayerScrub.COMMIT_DELAY_MS + 1L)
    assertEquals(listOf(0L), h.player.seekTargets)
  }

  // ---------------------------------------------------------------------------
  // Chiusura senza effetti (uscita dal player o player ricreato)
  // ---------------------------------------------------------------------------

  @Test
  fun `il rilascio chiude lo scrubbing senza seek ne ripresa`() = runTest {
    val h = harness(this, positionMs = 100_000L)

    h.controller.onArrowPressed(PlayerScrub.FORWARD)
    h.controller.release()

    assertTrue(h.player.seekTargets.isEmpty())
    assertEquals("la pausa resta: il player è ricreato o la schermata è uscita", listOf(false), h.player.playWhenReadyChanges)
    assertNull("l'overlay non deve restare a schermo", h.state)
    assertFalse(h.controller.isScrubbing)

    advanceBy(PlayerScrub.COMMIT_DELAY_MS * 2L)
    assertTrue("nessun job residuo", h.player.seekTargets.isEmpty())
  }
}
