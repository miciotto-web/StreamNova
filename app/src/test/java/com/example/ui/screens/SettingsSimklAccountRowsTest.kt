package com.example.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.data.repository.SimklRepository
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La schermata Impostazioni non deve chiedere all'utente nulla di tecnico per SIMKL: si
 * collega l'account e basta. Il Client ID è una configurazione di build (vedi
 * `.env.example`) e non deve ricomparire come campo da compilare o come istruzione.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class SettingsSimklAccountRowsTest {

  @get:Rule
  val rule = createComposeRule()

  // Le classi di test Robolectric condividono lo stesso sandbox: senza questo, una classe
  // che ha collegato un account (es. la persistenza del token) cambierebbe i testi di questa
  // schermata. Qui interessa solo che cosa la schermata offre all'utente.
  @Before
  fun azzeraLoStatoDellAccount() = runBlocking { SimklRepository.signOut() }

  @Test
  fun lAccountSimklSiCollegaSenzaChiedereIlClientId() {
    rule.setContent { SettingsScreen(viewModel = null) }

    // Il titolo di sezione è reso in maiuscolo da SettingsSection.
    rule.onNodeWithText("ACCOUNT SIMKL").assertExists()
    rule.onNodeWithText("Collega account SIMKL").assertExists()
    rule.onNodeWithText("Sincronizza ora").assertDoesNotExist()

    // Nessun campo, nessun segnaposto e nessuna istruzione di configurazione per l'utente.
    rule.onNodeWithText("Client ID SIMKL").assertDoesNotExist()
    rule.onNode(hasText("Client ID", substring = true)).assertDoesNotExist()
    rule.onNode(hasText("SIMKL_CLIENT_ID", substring = true)).assertDoesNotExist()
    rule.onNode(hasText("Configura", substring = true)).assertDoesNotExist()
    rule.onNode(hasText("simkl.com/settings/developer", substring = true)).assertDoesNotExist()
  }
}
