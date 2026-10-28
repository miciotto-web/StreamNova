package com.example.ui.screens.player

import android.graphics.Bitmap
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressione: l'anteprima video non altera il comportamento dello scrubbing.
 *
 * `PlayerScrubController` viene lasciato intatto; questi test verificano che, con un
 * `SeekPreviewController` collegato allo stesso flusso di stato (come fa PlayerScreen),
 * restino vere le garanzie esistenti:
 * - nessuna seek a ogni pressione (solo alla conferma);
 * - una sola seek alla conferma, con l'ultima posizione proposta;
 * - BACK annulla senza seek;
 * - il player si mette in pausa una volta sola all'apertura.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SeekPreviewScrubRegressionTest {

  private val tenMinutesMs = 600_000L

  private class FakePlayer(
    private val durationMs: Long,
    private var positionMs: Long = 0L
  ) : SimpleBasePlayer(Looper.getMainLooper()) {

    val seekTargets = mutableListOf<Long>()
    val playWhenReadyChanges = mutableListOf<Boolean>()
    private var playWhenReady = true

    override fun getState(): State = State.Builder()
      .setAvailableCommands(
        Player.Commands.Builder()
          .addAll(
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_PLAY_PAUSE
          )
          .build()
      )
      .setPlaybackState(Player.STATE_READY)
      .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaylist(
        listOf(
          MediaItemData.Builder("fake-media-item")
            .setMediaItem(MediaItem.fromUri("test://streamnova/regression"))
            .setMediaMetadata(MediaMetadata.EMPTY)
            .setDurationUs(durationMs * 1_000L)
            .setIsSeekable(true)
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
  }

  /**
   * Sorgente dell'anteprima che risponde subito senza fotogramma.
   *
   * Questi test verificano lo scrubbing, non l'acquisizione: la sorgente deve concludere
   * al volo, altrimenti (con `hide` che annulla la cattura in volo) resterebbe una
   * coroutine attiva a fine test.
   */
  private class PendingSource : SeekPreviewSource {
    var requests = 0

    override val isAvailable: Boolean = true

    override suspend fun load(positionMs: Long): SeekPreviewFrame? {
      requests++
      return null
    }

    override fun release() = Unit
  }

  /** PlayerScreen-like wiring: scrub + anteprima sullo stesso flusso di stato. */
  private class Wiring(player: FakePlayer, scope: TestScope) {
    val source = PendingSource()
    val previewStates = mutableListOf<SeekPreviewState?>()
    val scrubStates = mutableListOf<PlayerScrubUiState?>()

    val preview = SeekPreviewController(
      scope = scope,
      // UnconfinedTestDispatcher e non Dispatchers.Unconfined: stesso avvio immediato, ma la
      // pausa di 250 ms del prefetch resta sul tempo virtuale del test (con Unconfined
      // scadrebbe in tempo reale e advanceTimeBy/advanceUntilIdle non la controllerebbero).
      dispatcher = UnconfinedTestDispatcher(scope.testScheduler),
      sourceProvider = { source }
    ).also { it.setSource(key = "contenuto", seekable = true) }

    val scrub = PlayerScrubController(
      scope,
      player,
      onScrubStateChanged = { state ->
        scrubStates += state
        // Stesso collegamento di PlayerScreen: lo stato di anteprima segue lo scrubbing.
        if (state == null) {
          preview.hide()
        } else {
          preview.show(state.positionMs)
          previewStates += preview.state.value
        }
      }
    )
  }

  private fun TestScope.wiring(durationMs: Long = 600_000L, positionMs: Long = 0L) =
    Wiring(FakePlayer(durationMs, positionMs), this)

  private fun TestScope.advanceBy(elapsedMs: Long) {
    advanceTimeBy(elapsedMs)
    runCurrent()
  }

  // ---------------------------------------------------------------------------

  @Test
  fun `le pressioni ravvicinate non producono seek intermedie`() = runTest {
    val player = FakePlayer(tenMinutesMs, positionMs = 100_000L)
    val w = Wiring(player, this)

    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    w.scrub.onArrowPressed(PlayerScrub.REWIND)
    runCurrent()

    assertTrue("lo scrubbing resta aperto tra una pressione e l'altra", w.scrub.isScrubbing)
    assertTrue("nessuna seek durante lo scrubbing", player.seekTargets.isEmpty())
    assertEquals("l'ultima posizione proposta è 120s", 120_000L, w.scrubStates.last()?.positionMs)
    w.scrub.release()
    w.preview.release()
  }

  @Test
  fun `alla conferma arriva una sola seek con l'ultima posizione`() = runTest {
    val player = FakePlayer(tenMinutesMs, positionMs = 100_000L)
    val w = Wiring(player, this)

    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    advanceBy(PlayerScrub.COMMIT_DELAY_MS + 1L)

    assertEquals("una sola seek, all'ultima posizione proposta", listOf(120_000L), player.seekTargets)
    assertFalse(w.scrub.isScrubbing)
    assertEquals("l'anteprima si chiude con lo scrubbing", null, w.preview.state.value)

    w.preview.release()
  }

  @Test
  fun `BACK annulla senza seek e l'anteprima sparisce`() = runTest {
    val player = FakePlayer(tenMinutesMs, positionMs = 100_000L)
    val w = Wiring(player, this)

    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    w.scrub.cancel()
    advanceUntilIdle()

    assertTrue("BACK non applica seek", player.seekTargets.isEmpty())
    assertEquals(null, w.preview.state.value)
    w.preview.release()
  }

  @Test
  fun `il player va in pausa una volta sola all'apertura dello scrubbing`() = runTest {
    val player = FakePlayer(tenMinutesMs, positionMs = 50_000L)
    val w = Wiring(player, this)

    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    w.scrub.onArrowPressed(PlayerScrub.FORWARD)

    assertEquals("una sola pausa", listOf(false), player.playWhenReadyChanges)
    w.scrub.release()
    w.preview.release()
  }

  @Test
  fun `l'anteprima non sposta mai il player durante lo scrubbing`() = runTest {
    val player = FakePlayer(tenMinutesMs, positionMs = 60_000L)
    val w = Wiring(player, this)

    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    runCurrent()
    w.scrub.onArrowPressed(PlayerScrub.FORWARD)
    w.scrub.onArrowPressed(PlayerScrub.REWIND)
    runCurrent()

    assertTrue("nessuna seek prodotta dall'anteprima", player.seekTargets.isEmpty())
    w.scrub.release()
    w.preview.release()
  }
}
