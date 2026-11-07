package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.ProviderConstants
import java.util.Collections
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Caricamento progressivo della griglia Provider pilotato dalla UI.
 *
 * Il vecchio trigger `totalItems > 12` bloccava per sempre i cataloghi la cui prima
 * pagina (o il cui filtro corrente) mostrava 12 card o meno. Questi test verificano
 * che la richiesta parta con una lista corta e quando il focus si avvicina alla fine,
 * senza alcuna soglia sulla dimensione del catalogo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-tvdpi")
class ProviderGridProgressiveLoadingTest {

  @get:Rule
  val rule = createComposeRule()

  private var liveItems by mutableStateOf<List<MediaItem>>(emptyList())
  private val requestedTypes: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

  private fun item(index: Int): MediaItem = MediaItem(
    id = "m%02d".format(index + 1),
    title = "Titolo %02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1,
    rating = 10f - index * 0.05f,
    provider = "netflix"
  )

  private fun render(items: List<MediaItem>) {
    liveItems = items
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        ProviderScreen(
          provider = ProviderConstants.NETFLIX,
          allMedia = liveItems,
          onBackClick = {},
          onMediaClick = {},
          viewModel = null,
          onLoadMore = { isTv -> requestedTypes += isTv }
        )
      }
    }
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun unaListaCortaDopoUnAggiornamentoRichiedeLaPaginaSuccessiva() {
    render(List(20) { item(it) })
    // Scarta il pre-caricamento iniziale della prima composizione.
    requestedTypes.clear()

    // Il catalogo si riduce a 4 card (una sola riga, tutta visibile): con la vecchia
    // soglia `totalItems > 12` nessuna richiesta sarebbe mai partita.
    liveItems = List(4) { item(it) }
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) { requestedTypes.isNotEmpty() }
    assertTrue("Nessuna richiesta per la lista corta", requestedTypes.isNotEmpty())
    assertTrue(
      "Il filtro ALL deve interrogare entrambi i tipi: $requestedTypes",
      requestedTypes.contains(false) && requestedTypes.contains(true)
    )
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun laRichiestaParteQuandoIlFocusSiAvvicinaAllaFineDellaLista() {
    render(List(20) { item(it) })
    // Scarta il pre-caricamento iniziale della prima composizione.
    requestedTypes.clear()
    rule.waitForIdle()
    assertTrue(
      "Con 20 card e il focus sulla prima riga non deve partire alcuna richiesta: $requestedTypes",
      requestedTypes.isEmpty()
    )

    // Scorre fino all'ULTIMA card: il prefetch deve scattare prima del bordo.
    rule.onNodeWithTag(PROVIDER_GRID_TEST_TAG)
      .performScrollToNode(hasText("Titolo 20"))
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) { requestedTypes.isNotEmpty() }
    assertTrue("La pagina successiva non è stata richiesta", requestedTypes.isNotEmpty())
  }
}
