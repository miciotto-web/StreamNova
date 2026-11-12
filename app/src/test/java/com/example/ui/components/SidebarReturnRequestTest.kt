package com.example.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.SidebarSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Uscita dalla sidebar con RIGHT.
 *
 * Il RIGHT è l'unico evento che deve trasferire il focus al contenuto. Con
 * `onReturnRequest` il chiamante (MainActivity) chiude la sidebar e ripristina l'elemento di
 * provenienza della Home: qui si verifica che la sidebar NON faccia più la ricerca geometrica
 * a destra (che sceglierebbe un nodo qualunque del contenuto).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class SidebarReturnRequestTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val CONTENT_TAG = "sidebar_content_target"
  }

  private lateinit var requesters: Map<SidebarSection, FocusRequester>

  private fun render(
    expanded: Boolean,
    onReturnRequest: (() -> Unit)? = null,
    onCollapse: () -> Unit = {}
  ) {
    requesters = SidebarSection.entries.associateWith { FocusRequester() }
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        // Contenuto a DESTRA della sidebar: è il candidato della vecchia ricerca geometrica.
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(start = 220.dp)
        ) {
          Box(
            modifier = Modifier
              .align(Alignment.CenterEnd)
              .size(120.dp)
              .focusable()
              .testTag(CONTENT_TAG)
          )
        }
        SidebarNavigation(
          currentSection = SidebarSection.HOME,
          onSectionSelected = {},
          isSidebarExpanded = expanded,
          onCollapseRequest = onCollapse,
          itemFocusRequesters = requesters,
          onReturnRequest = onReturnRequest,
          modifier = Modifier.align(Alignment.CenterStart)
        )
      }
    }
    rule.waitForIdle()
  }

  private fun focusHomeItem() {
    rule.runOnIdle { requesters.getValue(SidebarSection.HOME).requestFocus() }
    rule.waitForIdle()
  }

  private fun pressRight() {
    rule.onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
    rule.waitForIdle()
  }

  @Test
  fun rightConReturnRequest_usaIlPercorsoDelChiamanteESaltaLaRicercaGeometrica() {
    var returnRequests = 0
    var collapses = 0
    render(
      expanded = true,
      onReturnRequest = { returnRequests++ },
      onCollapse = { collapses++ }
    )
    focusHomeItem()

    pressRight()

    assertEquals("Il RIGHT deve passare dal ritorno deterministico", 1, returnRequests)
    assertEquals("Il ritorno non deve chiudere la sidebar per conto suo", 0, collapses)
    // La vecchia ricerca geometrica non è avvenuta: il contenuto a destra non ha il focus.
    rule.onNodeWithTag(CONTENT_TAG).assertIsNotFocused()
  }

  @Test
  fun dopoIlRightIlFocusRestaSullaVoceFincheIlContenutoNonLoChiede() {
    // Sidebar chiusa dal RIGHT (come fa MainActivity) senza un ritorno accettato: nessuna
    // ricerca geometrica, il focus resta sulla voce. È la Home a richiederlo quando è pronta.
    render(expanded = true, onReturnRequest = {})
    focusHomeItem()

    pressRight()

    assertTrue(
      "Nessun salto automatico: il focus resta dove si trova finché la Home non lo richiede",
      rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()
    )
    rule.onNodeWithTag(CONTENT_TAG).assertIsNotFocused()
  }

  @Test
  fun navigazioneVerticaleNellaSidebarNonChiedeIlRitorno() {
    var returnRequests = 0
    render(expanded = true, onReturnRequest = { returnRequests++ })
    focusHomeItem()

    rule.onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
    rule.waitForIdle()
    rule.onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
    rule.waitForIdle()

    assertEquals("UP/DOWN non devono alterare il punto di ritorno", 0, returnRequests)
  }
}
