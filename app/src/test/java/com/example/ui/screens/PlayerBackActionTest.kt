package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BACK del telecomando nel player: **una sola pressione, una sola azione**.
 *
 * Due difetti coperti da questa decisione:
 * 1. Sulle TV con dispatch "legacy" del BACK (Android 11 e precedenti) il KEY_DOWN veniva
 *    consumato nella gerarchia di viste e `Activity.onKeyUp` non chiamava più
 *    `onBackPressed()`: la pressione andava persa e serviva un secondo BACK per uscire dal
 *    player. Il player ora gestisce il tasto fisico in preview (DOWN consumato, azione sul
 *    rilascio) e questa funzione resta l'unico punto che decide COSA fare.
 * 2. Dopo uno scrubbing con ◀ / ▶ la seek può essere già stata confermata dal timer mentre
 *    l'anteprima è ancora in "settling": il BACK deve ANNULLARE quella seek e riportare il
 *    player al punto di partenza, non chiudere il player. Il settling è quindi una
 *    superficie contestuale esattamente come lo scrubbing ancora aperto.
 *
 * Senza anteprima di scrub a video il BACK chiude il player come sempre.
 */
class PlayerBackActionTest {

  private fun action(
    anyModalOpen: Boolean = false,
    activeScrub: Boolean = false,
    seekSettling: Boolean = false,
    skipIntroVisible: Boolean = false,
    nextEpisodePromptVisible: Boolean = false
  ): PlayerBackAction = resolvePlayerBackAction(
    anyModalOpen = anyModalOpen,
    activeScrub = activeScrub,
    seekSettling = seekSettling,
    skipIntroVisible = skipIntroVisible,
    nextEpisodePromptVisible = nextEpisodePromptVisible
  )

  @Test
  fun videoPulitoChiudeIlPlayerConUnaSolaPressione() {
    assertEquals(PlayerBackAction.CLOSE_PLAYER, action())
  }

  /**
   * Regressione corretta in Fase 6.2/B: con la seek già confermata ma l'anteprima ancora
   * in settling il BACK annulla il salto e lascia il player aperto. Il vecchio
   * comportamento (chiusura del player) perdeva la possibilità di tornare al punto di
   * partenza ed era la regressione riscontrata sul Fire TV.
   */
  @Test
  fun settlingDellaSeekPreviewAnnullaIlBack() {
    assertEquals(PlayerBackAction.CANCEL_SCRUB, action(seekSettling = true))
  }

  @Test
  fun modaleApertoSiChiudeSenzaUscireDalPlayer() {
    assertEquals(PlayerBackAction.DISMISS_MODALS, action(anyModalOpen = true))
  }

  /**
   * Il modale resta la superficie più alta: anche con un'anteprima di scrub a video il
   * BACK chiude prima il modale, come prima di questa fase.
   */
  @Test
  fun modaleApertoHaLaPrecedenzaSuScrubESettling() {
    assertEquals(
      PlayerBackAction.DISMISS_MODALS,
      action(anyModalOpen = true, activeScrub = true, seekSettling = true)
    )
  }

  @Test
  fun scrubbingApertoAnnullaSenzaChiudereIlPlayer() {
    assertEquals(PlayerBackAction.CANCEL_SCRUB, action(activeScrub = true))
  }

  @Test
  fun scrubbingApertoHaLaPrecedenzaSugliAltriOverlay() {
    assertEquals(
      PlayerBackAction.CANCEL_SCRUB,
      action(activeScrub = true, skipIntroVisible = true, nextEpisodePromptVisible = true)
    )
  }

  /**
   * Durante il settling la posizione è già cambiata e il banner "Salta intro" può essere
   * ricomparso: il BACK deve comunque annullare la seek, non consumarsi sul banner.
   */
  @Test
  fun settlingHaLaPrecedenzaSulBannerSaltaIntro() {
    assertEquals(
      PlayerBackAction.CANCEL_SCRUB,
      action(seekSettling = true, skipIntroVisible = true)
    )
  }

  @Test
  fun bannerSaltaIntroSiDismetteSenzaChiudereIlPlayer() {
    assertEquals(PlayerBackAction.DISMISS_SKIP_INTRO, action(skipIntroVisible = true))
  }

  @Test
  fun overlayProssimoEpisodioSiNascondeSenzaChiudereIlPlayer() {
    assertEquals(PlayerBackAction.DISMISS_NEXT_EPISODE, action(nextEpisodePromptVisible = true))
  }

  /**
   * Un film non ha né banner "Salta intro" (nessun timestamp intro per i FILM) né overlay
   * "Prossimo Episodio" (solo serie TV): in riproduzione ogni singola pressione di BACK
   * deve sempre e solo chiudere il player, anche subito dopo una seek conclusa.
   */
  @Test
  fun filmInRiproduzioneChiudeSempreIlPlayer() {
    assertEquals(PlayerBackAction.CLOSE_PLAYER, action())
  }

  /** BACK senza overlay di scrub (né anteprima né settling) mantiene la chiusura normale. */
  @Test
  fun backSenzaScrubNeSettlingChiudeIlPlayer() {
    assertEquals(
      PlayerBackAction.CLOSE_PLAYER,
      action(activeScrub = false, seekSettling = false)
    )
  }
}
