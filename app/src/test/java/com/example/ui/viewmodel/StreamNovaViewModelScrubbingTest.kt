package com.example.ui.viewmodel

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.example.data.prefs.AppSettingsRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.ui.screens.player.PlayerChapter
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Suite di test unitari per la gestione dello Scrubbing Virtuale Disaccoppiato,
 * Debounce a 500ms, Accelerazione Adattiva a Step e Navigazione Rapida Capitoli
 * nel [StreamNovaViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StreamNovaViewModelScrubbingTest {

  private val testDispatcher = StandardTestDispatcher()
  private var createdViewModel: StreamNovaViewModel? = null
  private val tenMinutesMs = 600_000L

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    AppSettingsRepository.setTorBoxApiKey(null)
    StremioAddonRepository.getInstalledAddons().forEach {
      StremioAddonRepository.removeAddon(it.id)
    }
    StremioAddonRepository.clearCaches()
  }

  @After
  fun tearDown() {
    createdViewModel?.let { vm ->
      vm.viewModelScope.coroutineContext[Job]?.let { job ->
        job.cancel()
        testDispatcher.scheduler.advanceUntilIdle()
      }
    }
    createdViewModel = null
    StremioAddonRepository.getInstalledAddons().forEach {
      StremioAddonRepository.removeAddon(it.id)
    }
    StremioAddonRepository.clearCaches()
    AppSettingsRepository.setTorBoxApiKey(null)
    Dispatchers.resetMain()
  }

  private fun createViewModel(): StreamNovaViewModel {
    val vm = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    createdViewModel = vm
    return vm
  }

  /** Player controllabile per testare chiamate seekTo e stato di riproduzione. */
  private class TestFakePlayer(
    private val seekable: Boolean = true,
    private val durationMs: Long = 600_000L,
    private var positionMs: Long = 0L,
    private var playWhenReady: Boolean = true,
    private val playbackState: Int = Player.STATE_READY
  ) : SimpleBasePlayer(Looper.getMainLooper()) {

    val seekTargets = mutableListOf<Long>()

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
      .setPlaybackState(playbackState)
      .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaylist(
        listOf(
          MediaItemData.Builder("test-media-item")
            .setMediaItem(MediaItem.fromUri("test://streamnova/scrub"))
            .setMediaMetadata(MediaMetadata.EMPTY)
            .setDurationUs(durationMs * 1_000L)
            .setIsSeekable(seekable)
            .build()
        )
      )
      .setCurrentMediaItemIndex(0)
      .setContentPositionMs(positionMs)
      .build()

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

  // ---------------------------------------------------------------------------
  // 1. Debounce e Singola Chiamata a seekTo
  // ---------------------------------------------------------------------------

  @Test
  fun `click rapidi consecutivi incrementano la posizione virtuale ed eseguono una sola seekTo allo scadere del debounce`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val player = TestFakePlayer(durationMs = tenMinutesMs, positionMs = 100_000L)
    viewModel.attachPlayer(player)

    var simulatedTime = 1_000L
    viewModel.currentTimeProvider = { simulatedTime }

    // 1° Click: avanti (+10s)
    viewModel.onSeekForward(player)
    assertTrue("isScrubbing deve essere attivo", viewModel.isScrubbing.value)
    assertEquals(110_000L, viewModel.uiSeekPosition.value)
    assertEquals(10_000L, viewModel.seekStepDelta.value)
    assertTrue("Nessun seekTo immediato durante i click", player.seekTargets.isEmpty())

    // 2° Click dopo 150ms (< 400ms): avanti (+10s)
    simulatedTime += 150L
    viewModel.onSeekForward(player)
    assertEquals(120_000L, viewModel.uiSeekPosition.value)
    assertEquals(20_000L, viewModel.seekStepDelta.value)
    assertTrue("Ancora nessun seekTo", player.seekTargets.isEmpty())

    // 3° Click dopo 150ms (< 400ms): scatta a +30s
    simulatedTime += 150L
    viewModel.onSeekForward(player)
    assertEquals(150_000L, viewModel.uiSeekPosition.value)
    assertEquals(50_000L, viewModel.seekStepDelta.value)
    assertTrue("Ancora nessun seekTo", player.seekTargets.isEmpty())

    // Avanziamo il tempo virtuale di 400ms (il debounce di 500ms non è ancora scaduto)
    advanceTimeBy(400L)
    runCurrent()
    assertTrue("Prima di 500ms nessun seekTo", player.seekTargets.isEmpty())
    assertTrue(viewModel.isScrubbing.value)

    // Avanziamo altri 105ms (totale > 500ms): il debounce scade
    advanceTimeBy(105L)
    runCurrent()

    assertEquals("seekTo deve essere chiamato esattamente UNA volta", listOf(150_000L), player.seekTargets)
    assertFalse("isScrubbing deve resettarsi a false", viewModel.isScrubbing.value)
    assertNull("uiSeekPosition deve tornare a null", viewModel.uiSeekPosition.value)
    assertEquals("seekStepDelta deve tornare a 0", 0L, viewModel.seekStepDelta.value)
  }

  // ---------------------------------------------------------------------------
  // 2. Curva di Accelerazione Adattiva a Step
  // ---------------------------------------------------------------------------

  @Test
  fun `curva di accelerazione incrementa step a 10s per click 1-2, 30s per 3-5, e 60s per oltre 5`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val player = TestFakePlayer(durationMs = tenMinutesMs, positionMs = 0L)
    viewModel.attachPlayer(player)

    var simulatedTime = 10_000L
    viewModel.currentTimeProvider = { simulatedTime }

    // Click 1 (step = 10s -> pos = 10s, delta = +10s)
    viewModel.onSeekForward(player)
    assertEquals(10_000L, viewModel.uiSeekPosition.value)
    assertEquals(10_000L, viewModel.seekStepDelta.value)

    // Click 2 a +100ms (step = 10s -> pos = 20s, delta = +20s)
    simulatedTime += 100L
    viewModel.onSeekForward(player)
    assertEquals(20_000L, viewModel.uiSeekPosition.value)
    assertEquals(20_000L, viewModel.seekStepDelta.value)

    // Click 3 a +100ms (step = 30s -> pos = 50s, delta = +50s)
    simulatedTime += 100L
    viewModel.onSeekForward(player)
    assertEquals(50_000L, viewModel.uiSeekPosition.value)
    assertEquals(50_000L, viewModel.seekStepDelta.value)

    // Click 4 a +100ms (step = 30s -> pos = 80s, delta = +80s)
    simulatedTime += 100L
    viewModel.onSeekForward(player)
    assertEquals(80_000L, viewModel.uiSeekPosition.value)
    assertEquals(80_000L, viewModel.seekStepDelta.value)

    // Click 5 a +100ms (step = 30s -> pos = 110s, delta = +110s)
    simulatedTime += 100L
    viewModel.onSeekForward(player)
    assertEquals(110_000L, viewModel.uiSeekPosition.value)
    assertEquals(110_000L, viewModel.seekStepDelta.value)

    // Click 6 a +100ms (> 5 consecutive: step = 60s -> pos = 170s, delta = +170s)
    simulatedTime += 100L
    viewModel.onSeekForward(player)
    assertEquals(170_000L, viewModel.uiSeekPosition.value)
    assertEquals(170_000L, viewModel.seekStepDelta.value)

    // Click 7 a +100ms (> 5 consecutive: step = 60s -> pos = 230s, delta = +230s)
    simulatedTime += 100L
    viewModel.onSeekForward(player)
    assertEquals(230_000L, viewModel.uiSeekPosition.value)
    assertEquals(230_000L, viewModel.seekStepDelta.value)

    // Se passano più di 400ms (es. 450ms) ma meno di 500ms (ancora prima del debounce)
    // l'accelerazione si resetta al salto base di 10s:
    simulatedTime += 450L
    viewModel.onSeekForward(player)
    // Click 8 con intervallo > 400ms -> riparte da step base 10s (pos = 240s, delta = +240s)
    assertEquals(240_000L, viewModel.uiSeekPosition.value)
    assertEquals(240_000L, viewModel.seekStepDelta.value)

    // Commit con timeout
    advanceTimeBy(505L)
    runCurrent()
    assertEquals(listOf(240_000L), player.seekTargets)
  }

  // ---------------------------------------------------------------------------
  // 3. Commit Immediato con D-Pad OK / Center
  // ---------------------------------------------------------------------------

  @Test
  fun `commitSeekImmediately esegue subito il seek e cancella il timer debounce`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val player = TestFakePlayer(durationMs = tenMinutesMs, positionMs = 100_000L)
    viewModel.attachPlayer(player)

    // 1° Click: +10s
    viewModel.onSeekForward(player)
    assertEquals(110_000L, viewModel.uiSeekPosition.value)
    assertTrue(viewModel.isScrubbing.value)
    assertTrue("Prima del commit nessun seekTo", player.seekTargets.isEmpty())

    // Pressione D-Pad OK / Center: commit immediato
    viewModel.commitSeekImmediately(player)

    assertEquals("seekTo deve essere chiamato immediatamente", listOf(110_000L), player.seekTargets)
    assertFalse("isScrubbing deve resettarsi subito", viewModel.isScrubbing.value)
    assertNull("uiSeekPosition deve essere null", viewModel.uiSeekPosition.value)
    assertEquals(0L, viewModel.seekStepDelta.value)

    // Attendiamo oltre i 500ms: nessun secondo seekTo deve partire
    advanceTimeBy(1_000L)
    runCurrent()
    assertEquals("Nessun secondo seek residuo", listOf(110_000L), player.seekTargets)
  }

  // ---------------------------------------------------------------------------
  // 4. Annullamento Scrubbing (es. tasto BACK)
  // ---------------------------------------------------------------------------

  @Test
  fun `cancelScrub annulla la sessione senza chiamare seekTo`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val player = TestFakePlayer(durationMs = tenMinutesMs, positionMs = 50_000L)
    viewModel.attachPlayer(player)

    viewModel.onSeekForward(player)
    viewModel.onSeekForward(player)
    assertTrue(viewModel.isScrubbing.value)

    // Annullamento esplicito
    viewModel.cancelScrub()

    assertFalse(viewModel.isScrubbing.value)
    assertNull(viewModel.uiSeekPosition.value)
    assertEquals(0L, viewModel.seekStepDelta.value)
    assertTrue("seekTo non deve mai essere chiamato su annullamento", player.seekTargets.isEmpty())

    advanceTimeBy(1_000L)
    runCurrent()
    assertTrue("Nessun seekTo residuo dopo il timeout", player.seekTargets.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // 5. Isolamento Flussi Live e Durata Non Valida
  // ---------------------------------------------------------------------------

  @Test
  fun `su canale live o durata non valida lo seek non viene avviato`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val player = TestFakePlayer(durationMs = tenMinutesMs, positionMs = 10_000L)
    viewModel.attachPlayer(player)

    // Canale live: isLive = true
    viewModel.onSeekForward(player, isLive = true)
    viewModel.onSeekBackward(player, isLive = true)

    assertFalse(viewModel.isScrubbing.value)
    assertNull(viewModel.uiSeekPosition.value)
    assertTrue(player.seekTargets.isEmpty())

    // Flusso con durata <= 0L
    val zeroDurationPlayer = TestFakePlayer(durationMs = 0L, positionMs = 0L)
    viewModel.onSeekForward(zeroDurationPlayer, isLive = false)
    assertFalse(viewModel.isScrubbing.value)
    assertTrue(zeroDurationPlayer.seekTargets.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // 6. Limiti del Contenuto (Clamping tra 0 e duration)
  // ---------------------------------------------------------------------------

  @Test
  fun `lo scrubbing si arresta ai limiti di inizio (0) e fine (duration)`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val player = TestFakePlayer(durationMs = 25_000L, positionMs = 5_000L)
    viewModel.attachPlayer(player)

    // Indietro oltre lo zero
    viewModel.onSeekBackward(player)
    assertEquals(0L, viewModel.uiSeekPosition.value)
    assertEquals(-5_000L, viewModel.seekStepDelta.value)

    viewModel.onSeekBackward(player)
    assertEquals(0L, viewModel.uiSeekPosition.value)

    // Avanti oltre la durata (25s)
    viewModel.onSeekForward(player) // 10s
    viewModel.onSeekForward(player) // 20s
    viewModel.onSeekForward(player) // 20s + 30s -> clamp a 25s
    assertEquals(25_000L, viewModel.uiSeekPosition.value)
    assertEquals(20_000L, viewModel.seekStepDelta.value)

    advanceTimeBy(505L)
    runCurrent()
    assertEquals(listOf(25_000L), player.seekTargets)
  }

  // ---------------------------------------------------------------------------
  // 7. Navigazione Rapida Capitoli (D-Pad UP / DOWN)
  // ---------------------------------------------------------------------------

  @Test
  fun `navigazione capitoli D-Pad UP salta al successivo e DOWN salta a inizio corrente o precedente se entro 3s`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    val player = TestFakePlayer(durationMs = tenMinutesMs, positionMs = 75_000L)
    viewModel.attachPlayer(player)

    val testChapters = listOf(
      PlayerChapter(id = "ch1", title = "Intro", startMs = 0L, endMs = 60_000L),
      PlayerChapter(id = "ch2", title = "Capitolo 2", startMs = 60_000L, endMs = 120_000L),
      PlayerChapter(id = "ch3", title = "Capitolo 3", startMs = 120_000L, endMs = 300_000L)
    )
    viewModel.setChapters(testChapters)

    // Ci troviamo a 75_000ms (15 secondi dentro il Capitolo 2)
    // D-Pad UP: salta direttamente al capitolo successivo (ch3 a 120_000L) con seek immediato
    viewModel.onChapterNext(player)
    assertEquals(listOf(120_000L), player.seekTargets)

    // Ci troviamo a 75_000ms (15s dentro il capitolo corrente, > 3s dall'inizio)
    // D-Pad DOWN: salta all'inizio del capitolo corrente (ch2 a 60_000L)
    val playerAt75 = TestFakePlayer(durationMs = tenMinutesMs, positionMs = 75_000L)
    viewModel.onChapterPrevious(playerAt75)
    assertEquals(listOf(60_000L), playerAt75.seekTargets)

    // Ci troviamo a 62_000ms (solo 2s dentro il capitolo 2, <= 3s dall'inizio)
    // D-Pad DOWN: salta all'inizio del capitolo precedente (ch1 a 0L)
    val playerAt62 = TestFakePlayer(durationMs = tenMinutesMs, positionMs = 62_000L)
    viewModel.onChapterPrevious(playerAt62)
    assertEquals(listOf(0L), playerAt62.seekTargets)
  }
}
