package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.update.ApkInstaller
import com.example.data.update.DownloadErrorReason
import com.example.data.update.UpdateCheckState
import com.example.data.update.UpdateDownloadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Popup dell'aggiornamento rilevato all'avvio: mostra versione installata,
 * versione disponibile e note di rilascio, e mette a disposizione le due sole
 * scelte previste — «Aggiorna» e «Più tardi».
 *
 * Ciò che il test difende è il vincolo dichiarato all'utente: NIENTE parte da
 * solo. Senza un click (o una pressione nuova del tasto di conferma) non parte
 * alcun download, e l'installer di sistema resta dietro un secondo click
 * esplicito come nel dialog delle Impostazioni.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class UpdatePromptDialogTest {

  @get:Rule
  val rule = createComposeRule()

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private fun text(resId: Int): String = context.getString(resId)

  private val update = UpdateCheckState.UpdateAvailable(
    currentVersion = "1.0.2",
    newVersion = "1.0.3",
    tagName = "v1.0.3",
    assetName = "StreamNova-1.0.3-x86_64.apk",
    changelog = "Novità: correzioni e miglioramenti",
    releaseUrl = "https://github.com/miciotto-web/StreamNova/releases/tag/v1.0.3"
  )

  @Test
  fun showsInstalledVersionAvailableVersionAndReleaseNotes() {
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        UpdatePromptDialog(
          update = update,
          downloadState = UpdateDownloadState.Idle,
          onUpdate = {},
          onLater = {}
        )
      }
    }

    rule.onNodeWithText(text(R.string.settings_update_prompt_title)).assertIsDisplayed()
    rule.onNodeWithText(
      context.getString(R.string.settings_update_installed_version, "1.0.2")
    ).assertIsDisplayed()
    rule.onNodeWithText(
      context.getString(R.string.settings_update_available, "1.0.3")
    ).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.settings_update_changelog)).assertIsDisplayed()
    rule.onNodeWithText(update.changelog!!).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.settings_update_prompt_update)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.settings_update_prompt_later)).assertIsDisplayed()
  }

  @Test
  fun nothingStartsWithoutAChoice() {
    var updateCalls = 0
    var laterCalls = 0
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        UpdatePromptDialog(
          update = update,
          downloadState = UpdateDownloadState.Idle,
          onUpdate = { updateCalls++ },
          onLater = { laterCalls++ }
        )
      }
    }

    rule.waitForIdle()
    rule.mainClock.advanceTimeBy(1_000)

    // Il popup attende l'utente: nessun download, nessuna chiusura, nessuna
    // finestra di avanzamento già in corso.
    assertEquals("Nessun download può partire da solo", 0, updateCalls)
    assertEquals("Il popup non si chiude da solo", 0, laterCalls)
    rule.onNodeWithText(text(R.string.settings_update_downloading)).assertDoesNotExist()
    rule.onNodeWithText(text(R.string.settings_update_download_complete)).assertDoesNotExist()
  }

  @Test
  fun updateIsTheExplicitChoiceThatStartsTheDownload() {
    var updateCalls = 0
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        UpdatePromptDialog(
          update = update,
          downloadState = UpdateDownloadState.Idle,
          onUpdate = { updateCalls++ },
          onLater = {}
        )
      }
    }

    clickByConfirmKey(text(R.string.settings_update_prompt_update))

    assertEquals(1, updateCalls)
  }

  @Test
  fun laterClosesThePopupWithoutDownloading() {
    var updateCalls = 0
    var laterCalls = 0
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        UpdatePromptDialog(
          update = update,
          downloadState = UpdateDownloadState.Idle,
          onUpdate = { updateCalls++ },
          onLater = { laterCalls++ }
        )
      }
    }

    // Focus iniziale su «Aggiorna»: con il D-pad si passa a «Più tardi» (destra) e si
    // conferma. Se il focus non si spostasse, il tasto confermerebbe «Aggiorna» e il
    // test lo vedrebbe nel contatore sbagliato.
    pressOnFocused(Key.DirectionRight)
    pressOnFocused(Key.DirectionCenter)

    assertEquals("«Più tardi» chiude il popup", 1, laterCalls)
    assertEquals("«Più tardi» non scarica nulla", 0, updateCalls)
  }

  @Test
  fun aFreshConfirmPressChoosesNormally() {
    var updateCalls = 0
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        UpdatePromptDialog(
          update = update,
          downloadState = UpdateDownloadState.Idle,
          onUpdate = { updateCalls++ },
          onLater = {}
        )
      }
    }
    rule.waitForIdle()

    // La guardia sul tasto di conferma (coperta a parte da [ConfirmKeyGuardTest], che
    // esercita il rilascio "vecchio" non riproducibile con questo harness) non deve
    // bloccare la pressione genuina: il popup resta comandabile dal telecomando.
    rule.onNodeWithText(text(R.string.settings_update_prompt_update)).performKeyInput {
      pressKey(Key.DirectionCenter)
    }
    rule.waitForIdle()
    assertEquals(1, updateCalls)
  }

  @Test
  fun whileDownloadingThePopupShowsProgressInsteadOfTheChoices() {
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        UpdatePromptDialog(
          update = update,
          downloadState = UpdateDownloadState.Downloading(42),
          onUpdate = {},
          onLater = {}
        )
      }
    }

    rule.onNodeWithText(text(R.string.settings_update_downloading) + " 42%").assertIsDisplayed()
    rule.onNodeWithText(text(R.string.settings_update_prompt_update)).assertDoesNotExist()
    rule.onNodeWithText(text(R.string.settings_update_prompt_later)).assertDoesNotExist()
  }

  @Test
  fun whenTheDownloadIsCompleteThePopupOffersInstall() {
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        UpdatePromptDialog(
          update = update,
          downloadState = UpdateDownloadState.Completed("/tmp/StreamNova-1.0.3.apk"),
          onUpdate = {},
          onLater = {}
        )
      }
    }

    rule.onNodeWithText(text(R.string.settings_update_download_complete)).assertIsDisplayed()
    // Il ramo dipende dall'autorizzazione di sistema già concessa o meno: il test
    // verifica quello che l'app mostra davvero in questo ambiente, non un ramo a caso.
    if (ApkInstaller.canInstall(context)) {
      rule.onNodeWithText(text(R.string.settings_update_install)).assertIsDisplayed()
    } else {
      rule.onNodeWithText(text(R.string.settings_update_install_permission_needed))
        .assertIsDisplayed()
      rule.onNodeWithText(text(R.string.settings_update_install_permission_button))
        .assertIsDisplayed()
    }
  }

  @Test
  fun aFailedDownloadExplainsTheReasonAndOffersARetry() {
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        UpdatePromptDialog(
          update = update,
          downloadState = UpdateDownloadState.Error(DownloadErrorReason.NO_NETWORK),
          onUpdate = {},
          onLater = {}
        )
      }
    }

    rule.onNodeWithText(text(R.string.settings_update_dl_error_network)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.settings_update_download)).assertIsDisplayed()
  }

  /** Click col tasto di conferma, come il telecomando: stesso percorso della TV. */
  private fun clickByConfirmKey(nodeText: String) {
    rule.onNodeWithText(nodeText).performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()
  }

  /** Pressione sul nodo a fuoco: è il nodo che riceve i tasti del telecomando. */
  private fun pressOnFocused(key: Key) {
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }
}
