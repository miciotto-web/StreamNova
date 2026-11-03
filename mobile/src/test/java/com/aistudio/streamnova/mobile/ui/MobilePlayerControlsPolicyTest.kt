package com.aistudio.streamnova.mobile.ui

import com.example.domain.model.IntroWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePlayerControlsPolicyTest {

  // ── Auto-hide ────────────────────────────────────────────────────────

  @Test
  fun autoHideScattaSoloConVideoInRiproduzioneEUiVisibile() {
    assertTrue(
      shouldAutoHidePlayerControls(
        controlsVisible = true,
        isPlaying = true,
        isLocked = false,
        panel = MobilePlayerPanel.NONE,
        isScrubbing = false,
        hasPlaybackError = false
      )
    )
  }

  @Test
  fun autoHideNonScattaInPausa() {
    assertFalse(
      shouldAutoHidePlayerControls(
        controlsVisible = true,
        isPlaying = false,
        isLocked = false,
        panel = MobilePlayerPanel.NONE,
        isScrubbing = false,
        hasPlaybackError = false
      )
    )
  }

  @Test
  fun autoHideNonScattaConPannelloAperto() {
    assertFalse(
      shouldAutoHidePlayerControls(
        controlsVisible = true,
        isPlaying = true,
        isLocked = false,
        panel = MobilePlayerPanel.SUBTITLES,
        isScrubbing = false,
        hasPlaybackError = false
      )
    )
  }

  @Test
  fun autoHideNonScattaDuranteLoScrub() {
    assertFalse(
      shouldAutoHidePlayerControls(
        controlsVisible = true,
        isPlaying = true,
        isLocked = false,
        panel = MobilePlayerPanel.NONE,
        isScrubbing = true,
        hasPlaybackError = false
      )
    )
  }

  @Test
  fun autoHideNonScattaConControlliBloccatiONascostiOErrore() {
    assertFalse(
      shouldAutoHidePlayerControls(
        controlsVisible = true,
        isPlaying = true,
        isLocked = true,
        panel = MobilePlayerPanel.NONE,
        isScrubbing = false,
        hasPlaybackError = false
      )
    )
    assertFalse(
      shouldAutoHidePlayerControls(
        controlsVisible = false,
        isPlaying = true,
        isLocked = false,
        panel = MobilePlayerPanel.NONE,
        isScrubbing = false,
        hasPlaybackError = false
      )
    )
    assertFalse(
      shouldAutoHidePlayerControls(
        controlsVisible = true,
        isPlaying = true,
        isLocked = false,
        panel = MobilePlayerPanel.NONE,
        isScrubbing = false,
        hasPlaybackError = true
      )
    )
  }

  @Test
  fun ilTimerAutoHideEEntroCinqueSecondi() {
    assertTrue(MobilePlayerControlsPolicy.AUTO_HIDE_MS in 3_000L..5_000L)
  }

  @Test
  fun ilBackSistemaRestaAttivoConLaUiNascosta() {
    assertTrue(shouldHandleSystemBack(isPlayerVisible = true))
    assertFalse(shouldHandleSystemBack(isPlayerVisible = false))
  }

  @Test
  fun lAvvisoDiSbloccoCompareSoloSeBloccato() {
    assertTrue(shouldShowLockedHint(lockHintVisible = true, isLocked = true))
    assertFalse(shouldShowLockedHint(lockHintVisible = true, isLocked = false))
    assertFalse(shouldShowLockedHint(lockHintVisible = false, isLocked = true))
  }

  // ── Seek e formattazione ─────────────────────────────────────────────

  @Test
  fun ilSeekRestaDentroDurataEZero() {
    assertEquals(15_000L, playerSeekTarget(5_000L, 10_000L, 60_000L))
    assertEquals(0L, playerSeekTarget(5_000L, -10_000L, 60_000L))
    assertEquals(60_000L, playerSeekTarget(55_000L, 10_000L, 60_000L))
    // Durata sconosciuta: nessun limite superiore, mai negativo.
    assertEquals(100_000L, playerSeekTarget(90_000L, 10_000L, 0L))
    assertEquals(0L, playerSeekTarget(-100L, 0L, 0L))
  }

  @Test
  fun iTempiUsanoMMSSSottoLOraEHHMMSSSopra() {
    assertEquals("00:00", formatPlayerTime(0L))
    assertEquals("00:48", formatPlayerTime(48_000L))
    assertEquals("1:01:01", formatPlayerTime(3_661_000L))
    assertEquals("00:00", formatPlayerTime(-5_000L))
  }

  // ── Qualità e velocità ───────────────────────────────────────────────

  @Test
  fun leEtichetteQualitaUsanoLaRisoluzioneReale() {
    assertEquals("Auto", playerQualityLabel(null))
    assertEquals("Auto", playerQualityLabel(0))
    assertEquals("4K", playerQualityLabel(2_160))
    assertEquals("1080p", playerQualityLabel(1_080))
    assertEquals("720p", playerQualityLabel(720))
    assertEquals("480p", playerQualityLabel(480))
    assertEquals("360p", playerQualityLabel(360))
  }

  @Test
  fun laRigaQualitaMostraAutoELaTracciaInUso() {
    assertEquals("Auto • 1080p", playerQualitySummary(isAuto = true, currentHeight = 1_080))
    assertEquals("720p", playerQualitySummary(isAuto = false, currentHeight = 720))
  }

  @Test
  fun leVelocitaSonoFormattateENormalizzate() {
    assertEquals("1.0x", formatPlayerSpeed(1.0f))
    assertEquals("0.75x", formatPlayerSpeed(0.75f))
    assertEquals("1.25x", formatPlayerSpeed(1.25f))
    assertEquals(1.0f, nearestPlayerSpeed(1.1f))
    assertEquals(1.5f, nearestPlayerSpeed(1.6f))
    assertTrue(MobilePlayerControlsPolicy.SPEED_OPTIONS.contains(2.0f))
  }

  // ── Salta intro ──────────────────────────────────────────────────────

  @Test
  fun saltaIntroVisibileSoloDentroLaFinestraReale() {
    val window = IntroWindow(startMs = 10_000L, endMs = 70_000L)
    assertFalse(shouldShowSkipIntro(9_999L, window))
    assertTrue(shouldShowSkipIntro(10_000L, window))
    assertTrue(shouldShowSkipIntro(60_000L, window))
    assertFalse(shouldShowSkipIntro(68_000L, window))
    assertFalse(shouldShowSkipIntro(70_000L, window))
  }

  @Test
  fun saltaIntroMaiVisibileSenzaFinestraValida() {
    assertFalse(shouldShowSkipIntro(30_000L, null))
    assertFalse(shouldShowSkipIntro(30_000L, IntroWindow(startMs = 10_000L, endMs = 10_000L)))
  }

  // ── Autoplay episodio successivo ─────────────────────────────────────

  @Test
  fun autoplayScattaSoloConToggleSerieSuccessoreENonGiaEseguito() {
    assertTrue(
      shouldAutoPlayNextEpisode(
        enabled = true,
        isTvShow = true,
        hasNextEpisode = true,
        alreadyTriggered = false
      )
    )
  }

  @Test
  fun autoplayNonScattaSeDisattivatoSenzaSuccessorePerFilmODoppioEvento() {
    assertFalse(
      shouldAutoPlayNextEpisode(true, isTvShow = true, hasNextEpisode = true, alreadyTriggered = true)
    )
    assertFalse(
      shouldAutoPlayNextEpisode(false, isTvShow = true, hasNextEpisode = true, alreadyTriggered = false)
    )
    assertFalse(
      shouldAutoPlayNextEpisode(true, isTvShow = true, hasNextEpisode = false, alreadyTriggered = false)
    )
    assertFalse(
      shouldAutoPlayNextEpisode(true, isTvShow = false, hasNextEpisode = true, alreadyTriggered = false)
    )
  }

  // ── Schermo e tracce ─────────────────────────────────────────────────

  @Test
  fun loSchermoRestaAccessoDuranteLaRiproduzioneESoloSeRichiestoInPausa() {
    assertTrue(shouldKeepPlayerScreenOn(isPlaying = true, keepScreenOnInPause = false))
    assertTrue(shouldKeepPlayerScreenOn(isPlaying = true, keepScreenOnInPause = true))
    assertTrue(shouldKeepPlayerScreenOn(isPlaying = false, keepScreenOnInPause = true))
    assertFalse(shouldKeepPlayerScreenOn(isPlaying = false, keepScreenOnInPause = false))
  }

  @Test
  fun leLingueDelleTracceSonoLeggibili() {
    assertEquals("Italiano", playerTrackLanguageLabel("it", null))
    assertEquals("English", playerTrackLanguageLabel("en", null))
    assertEquals("Italiano", playerTrackLanguageLabel("it", "Italiano (forzato)"))
    assertEquals("SDH", playerTrackLanguageLabel("und", "SDH"))
    assertEquals("—", playerTrackLanguageLabel(null, null))
    assertEquals("—", playerTrackLanguageLabel("und", null))
  }

  @Test
  fun ilDettaglioTracciaUnisceLePartiNonVuote() {
    assertEquals("AC-3 • 5.1", playerTrackDetail("AC-3", "5.1"))
    assertEquals("AC-3", playerTrackDetail("AC-3", null, " "))
    assertEquals("", playerTrackDetail(null, null))
  }

  // ── Zoom ────────────────────────────────────────────────────────────

  @Test
  fun ilCicloZoomTornaAlPuntoDiPartenza() {
    var scale = MobileVideoScale.FIT
    val visited = mutableListOf(scale)
    repeat(MobileVideoScale.entries.size - 1) {
      scale = scale.next()
      visited += scale
    }
    assertEquals(MobileVideoScale.entries.size, visited.distinct().size)
    assertEquals(MobileVideoScale.FIT, scale.next())
  }
}
