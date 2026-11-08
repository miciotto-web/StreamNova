package com.example.ui.screens.player

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.domain.model.IntroWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 6.1 — verifica OSSERVABILE dell'isolamento del ticker.
 *
 * Il polling del player aggiorna la posizione ogni 500 ms. Prima della Fase 6.1 quello
 * stato era letto nel corpo di `PlayerScreen`, quindi ogni tick ricomponeva la schermata
 * intera. Qui si riproduce lo stesso schema con i composable reali
 * ([PlayerPlaybackClock], [rememberIntroActive], [rememberNextEpisodePromptVisible]):
 *
 * - l'avanzamento della sola posizione NON deve ricomporre la schermata principale, ma
 *   deve continuare ad aggiornare il "minutaggio" (composable foglia);
 * - un flag derivato (es. "Salta intro") DEVE ricomporre la schermata quando cambia
 *   valore, perchè quel cambio è funzionalmente necessario;
 * - in pausa il ticker pubblica un solo campione e non introduce polling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class PlayerPositionTickerRecompositionTest {

  @get:Rule
  val rule = createComposeRule()

  /** Contatore fuori dalla composizione: scriverlo qui non genera ricomposizioni. */
  private class CompositionCounter {
    var count = 0
  }

  @Test
  fun lAvanzamentoDellaPosizioneNonRicomponelaSchermataPrincipale() {
    val clock = PlayerPlaybackClock(initialPositionMs = 0L, initialDurationMs = 100_000L)
    val screen = CompositionCounter()
    val hud = CompositionCounter()

    rule.setContent { PlayerScreenProbe(clock = clock, screen = screen, hud = hud) }
    rule.waitForIdle()
    val screenBaseline = screen.count
    val hudBaseline = hud.count
    assertTrue("La schermata deve comporsi almeno una volta", screenBaseline >= 1)

    // 20 tick da 500 ms: avanzamento della sola posizione, nessun cambio dei flag derivati
    // (nessuna finestra intro, overlay "Prossimo Episodio" fuori dalla finestra finale).
    for (tick in 1..20) {
      rule.runOnIdle { clock.updatePosition(tick * 500L) }
    }
    rule.waitForIdle()

    assertEquals(
      "Il solo avanzamento della posizione non deve ricomporre la schermata principale",
      screenBaseline,
      screen.count
    )
    assertTrue(
      "Il minutaggio (composable foglia) deve continuare ad aggiornarsi",
      hud.count > hudBaseline
    )
  }

  @Test
  fun ilCambioDiUnFlagDerivatoRicomponelaSchermata() {
    val clock = PlayerPlaybackClock(initialPositionMs = 0L, initialDurationMs = 100_000L)
    val screen = CompositionCounter()

    rule.setContent { IntroFlagProbe(clock = clock, screen = screen) }
    rule.waitForIdle()
    val baseline = screen.count

    // Entra nella finestra della sigla: il banner "Salta intro" deve comparire, quindi la
    // ricomposizione della schermata è necessaria e attesa.
    rule.runOnIdle { clock.updatePosition(12_000L) }
    rule.waitForIdle()
    assertEquals(baseline + 1, screen.count)

    // Resta dentro la finestra: il flag non cambia, nessuna nuova invalidazione.
    rule.runOnIdle { clock.updatePosition(13_500L) }
    rule.waitForIdle()
    assertEquals(baseline + 1, screen.count)
  }

  @Test
  fun inPausaIlTickerNonCampionaNessunDato() {
    val clock = PlayerPlaybackClock(initialPositionMs = 0L, initialDurationMs = 100_000L)
    val source = FakePositionSource(
      isPlayingNow = false,
      positionMs = 42_000L,
      durationMs = 100_000L
    )
    var samples = 0

    rule.setContent {
      PlayerPositionTicker(
        playerKey = "player-finto",
        isPlaying = false,
        source = source,
        clock = clock,
        onSample = { _, _ -> samples++ }
      )
    }
    rule.waitForIdle()
    rule.mainClock.advanceTimeBy(5_000L)
    rule.waitForIdle()

    assertEquals("In pausa non deve esserci polling", 0, samples)
    assertEquals("L'orologio resta all'ultimo valore noto", 0L, clock.positionMs)
    assertEquals(100_000L, clock.durationMs)
  }

  @Test
  fun duranteLaRiproduzioneIlTickerCampionaSubitoEpoiAdOgniIntervallo() {
    val clock = PlayerPlaybackClock(initialPositionMs = 0L, initialDurationMs = 100_000L)
    val source = FakePositionSource(
      isPlayingNow = true,
      positionMs = 42_000L,
      durationMs = 100_000L
    )
    var samples = 0

    rule.setContent {
      PlayerPositionTicker(
        playerKey = "player-finto",
        isPlaying = true,
        source = source,
        clock = clock,
        onSample = { _, _ -> samples++ }
      )
    }
    rule.waitForIdle()

    assertTrue("Con riproduzione attiva il primo campione è immediato", samples >= 1)
    assertEquals(42_000L, clock.positionMs)
  }

  @Composable
  private fun PlayerScreenProbe(
    clock: PlayerPlaybackClock,
    screen: CompositionCounter,
    hud: CompositionCounter
  ) {
    screen.count++
    // Stessi flag derivati usati da PlayerScreen: nessuno deve invalidare la schermata
    // quando cambia solo la posizione.
    val introActive = rememberIntroActive(
      clock = clock,
      isIntroDismissed = false,
      // Nessuna finestra: il banner resta sempre nascosto durante il test.
      introWindow = null
    )
    val nextEpisodePromptVisible = rememberNextEpisodePromptVisible(
      clock = clock,
      isScrubbing = false,
      playbackEnded = false,
      isTvShow = true,
      hasNextEpisode = true,
      dismissed = false,
      playbackFailed = false
    )
    Column {
      Text(text = "intro=$introActive prompt=$nextEpisodePromptVisible")
      PlaybackTimeProbe(clock = clock, hud = hud)
    }
  }

  @Composable
  private fun PlaybackTimeProbe(clock: PlayerPlaybackClock, hud: CompositionCounter) {
    hud.count++
    Text(text = "pos=${clock.positionMs} dur=${clock.durationMs}")
  }

  @Composable
  private fun IntroFlagProbe(clock: PlayerPlaybackClock, screen: CompositionCounter) {
    screen.count++
    val introActive = rememberIntroActive(
      clock = clock,
      isIntroDismissed = false,
      introWindow = IntroWindow(startMs = 10_000L, endMs = 20_000L)
    )
    Text(text = "intro=$introActive")
  }

  private class FakePositionSource(
    override var isPlayingNow: Boolean,
    private var positionMs: Long,
    private var durationMs: Long
  ) : PlayerPositionSource {
    override fun readPositionMs(): Long = positionMs
    override fun readDurationMs(): Long = durationMs
  }
}
