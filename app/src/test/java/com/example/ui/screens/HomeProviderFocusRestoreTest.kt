package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalClearLastFocusedProvider
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedProviderId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressione del difetto FOCUS D-PAD "ritorno dalla scheda Provider".
 *
 * Difetto: selezionando una card provider dell'hub (es. HBO Max) e tornando indietro, il focus
 * atterrava sulla HERO invece che sulla card provider di origine. Causa: la card provider non
 * era registrata come target di ripristino, quindi MainActivity cadeva nel ramo
 * ANCHOR_CONTENT (ancora all'Hero).
 *
 * Contratto riprodotto (stesso di MainActivity):
 * - ViewModel REALE con il canale dedicato `saveLastFocusedProvider` / `clearLastFocusedProvider`;
 * - `pendingContentFocusRestore` a livello app: al BACK, se non c'è un target media ma c'è un
 *   target provider, si segnala il ripristino con l'id del PROVIDER;
 * - browsing e scheda Provider su SaveableStateProvider separati (smontaggio/rimontaggio).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class HomeProviderFocusRestoreTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val HBO_NAME = "HBO Max"
    const val HBO_ID = "hbo"
  }

  private lateinit var viewModel: StreamNovaViewModel
  private var showProviderDestination by mutableStateOf(false)
  private var pendingContentFocusRestore by mutableStateOf(false)

  private fun catalogs(): HomeCatalogs = HomeCatalogs(
    heroItems = listOf(
      MediaItem(
        id = "hero-0",
        title = "HERO 1",
        synopsis = "",
        videoUrl = "",
        type = MediaType.FILM,
        year = 2099,
        tmdbId = 900
      )
    ),
    trendingMovies = List(6) { index ->
      MediaItem(
        id = "tf-${index + 1}",
        title = "TF ${index + 1}",
        synopsis = "",
        videoUrl = "",
        type = MediaType.FILM,
        year = 2001,
        tmdbId = index + 1
      )
    }
  )

  @Composable
  private fun App() {
    val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
    val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
    val lastFocusedProviderId by viewModel.lastFocusedProviderId.collectAsState()
    val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()

    CompositionLocalProvider(
      LocalLastFocusedMediaId provides lastFocusedMediaId,
      LocalLastFocusedTarget provides lastFocusedTarget,
      LocalLastFocusedProviderId provides lastFocusedProviderId,
      LocalFocusRestoreToken provides focusRestoreToken,
      LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() },
      LocalClearLastFocusedProvider provides { viewModel.clearLastFocusedProvider() }
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        val stateHolder = rememberSaveableStateHolder()
        if (showProviderDestination) {
          stateHolder.SaveableStateProvider("provider") { ProviderDestinationStub() }
        } else {
          stateHolder.SaveableStateProvider("browsing") {
            var isInitialHomeFocusSet by rememberSaveable { mutableStateOf(false) }

            // Effetto d'ingresso identico a MainActivity: al BACK dalla scheda Provider, se non
            // c'è un target media ma c'è un target PROVIDER, si segnala il ripristino del provider.
            LaunchedEffect(pendingContentFocusRestore, isInitialHomeFocusSet) {
              if (!isInitialHomeFocusSet) {
                isInitialHomeFocusSet = true
                viewModel.requestHomeScrollToTop()
              } else if (pendingContentFocusRestore) {
                pendingContentFocusRestore = false
                val mediaId = viewModel.lastFocusedMediaId.value
                val providerId = viewModel.lastFocusedProviderId.value
                when {
                  mediaId != null -> viewModel.signalFocusRestore(mediaId)
                  providerId != null -> viewModel.signalFocusRestore(providerId)
                }
              }
            }

            Box(
              modifier = Modifier
                .fillMaxSize()
                .padding(start = 60.dp)
            ) {
              HomeMainBrowsingContent(
                allMedia = emptyList(),
                homeCatalogs = catalogs(),
                onMediaClick = {},
                onPlayClick = {},
                onRemoveFromContinueWatching = {},
                onProviderClick = { showProviderDestination = true },
                viewModel = viewModel
              )
            }
          }
        }
      }
    }
  }

  /** Sostituto della scheda Provider: schermo intero con un nodo realmente focalizzato. */
  @Composable
  private fun ProviderDestinationStub() {
    val focusRequester = remember { FocusRequester() }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      Box(
        modifier = Modifier
          .size(200.dp)
          .align(Alignment.Center)
          .focusRequester(focusRequester)
          .background(Color(0xFF3A3A3A))
          .clickable { }
      )
      LaunchedEffect(Unit) {
        try {
          focusRequester.requestFocus()
        } catch (_: Exception) {
        }
      }
    }
  }

  private fun render() {
    viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    rule.setContent { App() }
    rule.waitForIdle()
  }

  private fun focusedContentDescriptions(): List<String> =
    rule.onAllNodes(isFocused()).fetchSemanticsNodes().flatMap { node ->
      node.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()
    }

  @OptIn(ExperimentalTestApi::class)
  private fun backFromProvider() {
    pendingContentFocusRestore = true
    showProviderDestination = false
    rule.waitForIdle()
  }

  @Test
  fun ritornoDallaSchedaProviderRipristinaLaCardProvider() {
    render()

    // Focus sulla card HBO Max dell'hub provider, poi click: come l'utente che entra nella scheda.
    rule.onNode(hasContentDescription(HBO_NAME)).requestFocus()
    rule.waitForIdle()
    rule.onNode(hasContentDescription(HBO_NAME)).performClick()
    rule.waitForIdle()

    assertEquals(
      "Il click sulla card provider deve salvare il target provider dedicato",
      HBO_ID,
      viewModel.lastFocusedProviderId.value
    )
    assertTrue(
      "Il target media non deve essere impostato dalla navigazione provider",
      viewModel.lastFocusedMediaId.value == null
    )

    backFromProvider()

    rule.waitUntil(timeoutMillis = 5_000) {
      focusedContentDescriptions().contains(HBO_NAME)
    }
    rule.onNode(hasContentDescription(HBO_NAME)).assertIsFocused()
    assertTrue(
      "Il focus non deve finire sull'Hero: focused=${focusedContentDescriptions()}",
      !focusedContentDescriptions().contains("HERO 1")
    )
  }
}
