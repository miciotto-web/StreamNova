package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifica la selezione del target di focus del player a video pulito.
 *
 * Il banner "Salta intro" deve ricevere il focus quando è visibile e l'HUD è nascosto, ma
 * il focus NON deve essere toccato durante scrubbing, modali, overlay "Prossimo Episodio"
 * o con l'HUD già a schermo (quel caso è governato dagli effetti HUD).
 *
 * NOTA: è un test della funzione di selezione, non del sistema di focus Compose reale.
 */
class PlayerVideoFocusTargetTest {

  private fun target(
    hudShown: Boolean = false,
    isIntroActive: Boolean = false,
    isScrubbing: Boolean = false,
    anyModalOpen: Boolean = false,
    isNextEpisodePromptVisible: Boolean = false
  ): VideoFocusTarget = resolveVideoFocusTarget(
    hudShown = hudShown,
    isIntroActive = isIntroActive,
    isScrubbing = isScrubbing,
    anyModalOpen = anyModalOpen,
    isNextEpisodePromptVisible = isNextEpisodePromptVisible
  )

  @Test
  fun bannerIntroQuandoVisibileEVideoPulito() {
    assertEquals(VideoFocusTarget.INTRO_BANNER, target(isIntroActive = true))
  }

  @Test
  fun nodoDiRiposoQuandoNessunaIntro() {
    assertEquals(VideoFocusTarget.IDLE, target())
  }

  @Test
  fun hudVisibileNonVieneToccato() {
    assertEquals(VideoFocusTarget.NONE, target(hudShown = true, isIntroActive = true))
  }

  @Test
  fun scrubbingNonVieneToccato() {
    assertEquals(VideoFocusTarget.NONE, target(isIntroActive = true, isScrubbing = true))
  }

  @Test
  fun modaleApertaNonVieneToccata() {
    assertEquals(VideoFocusTarget.NONE, target(isIntroActive = true, anyModalOpen = true))
  }

  @Test
  fun promptProssimoEpisodioNonVieneToccato() {
    assertEquals(VideoFocusTarget.NONE, target(isIntroActive = true, isNextEpisodePromptVisible = true))
  }
}
