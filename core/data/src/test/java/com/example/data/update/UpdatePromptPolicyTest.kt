package com.example.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visibilità del popup dell'aggiornamento rilevato all'avvio: compare solo quando
 * c'è qualcosa da proporre e la schermata è libera.
 *
 * La Home non deve mai essere interrotta da un controllo che l'utente non ha
 * chiesto: splash in corso, riproduzione attiva o un altro modale aperto rinviano
 * il popup, che resta in attesa (il suo stato non viene perso).
 */
class UpdatePromptPolicyTest {

  private fun visible(
    hasPrompt: Boolean = true,
    isSplashActive: Boolean = false,
    isPlayerActive: Boolean = false,
    isAnotherModalVisible: Boolean = false
  ) = shouldShowStartupUpdatePrompt(
    hasPrompt = hasPrompt,
    isSplashActive = isSplashActive,
    isPlayerActive = isPlayerActive,
    isAnotherModalVisible = isAnotherModalVisible
  )

  @Test
  fun thePromptAppearsOnlyOnAFreeScreen() {
    assertTrue("Schermata libera: il popup compare", visible())
  }

  @Test
  fun theSplashScreenHoldsThePromptBack() {
    assertFalse("Durante la splash il popup non compare", visible(isSplashActive = true))
  }

  @Test
  fun playbackHoldsThePromptBack() {
    assertFalse("Con il player attivo il popup non compare", visible(isPlayerActive = true))
  }

  @Test
  fun anotherOpenDialogHoldsThePromptBack() {
    assertFalse(
      "Con un altro modale aperto il popup non compare sopra di esso",
      visible(isAnotherModalVisible = true)
    )
  }

  @Test
  fun withoutAnAvailableUpdateThereIsNothingToShow() {
    assertFalse(
      "Nessun aggiornamento da proporre: nessun popup, anche a schermata libera",
      visible(hasPrompt = false)
    )
  }
}
