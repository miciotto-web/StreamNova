package com.example.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pulsante «Seleziona fonte» della barra azioni della scheda di dettaglio (Fase 4).
 *
 * Il pulsante è composto SOLO quando [DetailScreen] riceve `onSelectSourceClick` (la
 * MainActivity lo passa esclusivamente in modalità Debrid e per contenuti che non
 * siano canali live): i test verificano i due stati di visibilità e il comportamento
 * D-pad completo sulla scheda reale:
 *
 * 1. visibilità con callback (modalità Debrid) e assenza senza callback (HTTP/live);
 * 2. il pulsante Trailer resta invariato ed è raggiungibile come prima;
 * 3. la catena di focus si estende: Riproduci -> Cuore -> Occhio -> Trailer -> Seleziona
 *    fonte, con ritorno a sinistra su Trailer (nessuna trappola di focus);
 * 4. OK/ENTER su «Seleziona fonte» apre la selezione manuale passando l'identità del
 *    contenuto e NON tocca né Riproduci né Trailer.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class DetailSelectSourceButtonTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    // La lingua del test dipende dalla locale di Robolectric: si accettano la
    // stringa di default (it) e quella en, come nelle altre regressioni UI.
    const val SELECT_SOURCE_LABEL_IT = "Seleziona fonte"
    const val SELECT_SOURCE_LABEL_EN = "Select source"
    const val TRAILER_LABEL_IT = "Riproduci trailer"
    const val TRAILER_LABEL_EN = "Play trailer"
    const val PLAY_LABEL_IT = "Riproduci"
    const val PLAY_LABEL_EN = "Play"
  }

  private var openedSelectSourceMediaId: String? = null
  private var openedSelectSourceEpisode: Episode? = null
  private var trailerClicks = 0
  private var playClicks = 0

  private fun movie() = MediaItem(
    id = "tt0117731",
    title = "Toy Story",
    synopsis = "Il film d'animazione Pixar.",
    videoUrl = "",
    type = MediaType.FILM,
    year = 1995,
    tmdbId = 862
  )

  /** Nodo che PORTA il descrittore (il Box interno del controllo circolare): uno solo. */
  private fun selectSourceLabel() =
    hasContentDescription(SELECT_SOURCE_LABEL_IT) or hasContentDescription(SELECT_SOURCE_LABEL_EN)

  private fun trailerLabel() =
    hasContentDescription(TRAILER_LABEL_IT) or hasContentDescription(TRAILER_LABEL_EN)

  /** Nodo FOCUSABILE del controllo: il Box esterno focusable che contiene il descrittore. */
  private fun focusableSelectSource() = isFocusable() and hasAnyDescendant(selectSourceLabel())
  private fun focusableTrailer() = isFocusable() and hasAnyDescendant(trailerLabel())
  private fun focusablePlay() = isFocusable() and hasAnyDescendant(hasText(PLAY_LABEL_IT) or hasText(PLAY_LABEL_EN))

  private fun pressDpad(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun render(withSelectSource: Boolean) {
    openedSelectSourceMediaId = null
    openedSelectSourceEpisode = null
    trailerClicks = 0
    playClicks = 0
    val media = movie()
    rule.setContent {
      DetailScreen(
        media = media,
        allMedia = listOf(media),
        onBackClick = {},
        onPlayClick = { _, _ -> playClicks++ },
        onToggleFavorite = {},
        onPlayTrailer = { trailerClicks++ },
        onSelectSourceClick = if (withSelectSource) {
          { m, ep ->
            openedSelectSourceMediaId = m.id
            openedSelectSourceEpisode = ep
          }
        } else {
          null
        }
      )
    }
    rule.waitForIdle()
  }

  // ── 1. Visibilità ────────────────────────────────────────────────────────────────

  @Test
  fun selectSourceButtonIsVisibleWhenDebridCallbackIsProvided() {
    render(withSelectSource = true)
    rule.onAllNodes(selectSourceLabel()).assertCountEquals(1)
    // Il pulsante Trailer esiste ancora accanto al nuovo controllo.
    rule.onAllNodes(trailerLabel()).assertCountEquals(1)
  }

  @Test
  fun selectSourceButtonIsAbsentWithoutCallback() {
    render(withSelectSource = false)
    rule.onAllNodes(selectSourceLabel()).assertCountEquals(0)
    // In modalità HTTP/live la barra resta quella storica a quattro controlli.
    rule.onAllNodes(trailerLabel()).assertCountEquals(1)
  }

  // ── 2. Catena di focus D-pad e conferma con OK ───────────────────────────────────

  @Test
  fun dpadRightReachesSelectSourceAfterTrailerAndOkOpensManualSelection() {
    render(withSelectSource = true)

    // Focus iniziale: "Riproduci".
    rule.onNode(focusablePlay()).assertIsFocused()

    // Catena: Riproduci -> Cuore -> Occhio -> Trailer -> Seleziona fonte.
    repeat(4) { pressDpad(Key.DirectionRight) }
    rule.onNode(focusableSelectSource()).assertIsFocused()

    // Il focus non è intrappolato: da "Seleziona fonte" si torna a Trailer e si rientra.
    pressDpad(Key.DirectionLeft)
    rule.onNode(focusableTrailer()).assertIsFocused()
    pressDpad(Key.DirectionRight)
    rule.onNode(focusableSelectSource()).assertIsFocused()

    // OK/ENTER apre la selezione manuale con l'identità del contenuto corrente.
    pressDpad(Key.DirectionCenter)
    assertEquals("tt0117731", openedSelectSourceMediaId)
    assertNull("Per un film nessun episodio nel bersaglio", openedSelectSourceEpisode)
    assertEquals("OK su «Seleziona fonte» non tocca il Trailer", 0, trailerClicks)
    assertEquals("OK su «Seleziona fonte» non avvia la riproduzione", 0, playClicks)
  }

  @Test
  fun trailerButtonKeepsHistoricalFocusBehaviorWithoutSelectSource() {
    render(withSelectSource = false)

    // Con la barra storica a quattro controlli, tre RIGHT portano su Trailer...
    repeat(3) { pressDpad(Key.DirectionRight) }
    rule.onNode(focusableTrailer()).assertIsFocused()

    // ...e OK su Trailer apre il trailer, NON la selezione manuale.
    pressDpad(Key.DirectionCenter)
    assertEquals(1, trailerClicks)
    assertNull(openedSelectSourceMediaId)
  }

  @Test
  fun selectSourceButtonRespondsToDirectFocusRequest() {
    render(withSelectSource = true)

    // Ingresso diretto (ripristino di focus programmatico): il pulsante accetta
    // il focus e OK apre la selezione manuale.
    rule.onNode(focusableSelectSource()).requestFocus()
    rule.waitForIdle()
    rule.onNode(focusableSelectSource()).assertIsFocused()

    pressDpad(Key.DirectionCenter)
    assertEquals("tt0117731", openedSelectSourceMediaId)
  }
}
