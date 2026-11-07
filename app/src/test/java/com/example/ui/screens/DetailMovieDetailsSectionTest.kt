package com.example.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.ExtendedMediaDetails
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scheda «Dettagli Film» nella schermata Dettagli.
 *
 * Verifica i requisiti TV: la scheda è accanto a «Produzione», si attiva con OK del
 * telecomando e le righe informative sono navigabili con il D-pad. Verifica inoltre
 * che per le serie compaiano solo i campi equivalenti e che i dati mancanti non
 * producano righe vuote o valori inventati.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "it-w1280dp-h720dp-tvdpi")
class DetailMovieDetailsSectionTest {

  @get:Rule
  val rule = createComposeRule()

  private fun movieMedia() = MediaItem(
    id = "tmdb_m_603",
    tmdbId = 603,
    title = "Matrix",
    synopsis = "Un hacker scopre la verità sul mondo che lo circonda.",
    videoUrl = "",
    type = MediaType.FILM,
    year = 1999,
    releaseDate = "1999-03-31",
    durationMinutes = 136
  )

  private fun seriesMedia() = MediaItem(
    id = "tmdb_tv_1399",
    tmdbId = 1399,
    title = "Il Trono di Spade",
    synopsis = "Nobili casate si contendono il trono.",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = 2011,
    releaseDate = "2011-04-17",
    seasonsCount = 8
  )

  private fun movieDetails() = ExtendedMediaDetails(
    tmdbId = 603,
    title = "Matrix",
    originalTitle = "The Matrix",
    overview = "Un hacker scopre la verità sul mondo che lo circonda.",
    backdropUrl = null,
    posterUrl = null,
    releaseYear = 1999,
    releaseDate = "1999-03-31",
    rating = 8.2f,
    durationMinutes = 136,
    runtimeMinutes = 136,
    status = "Released",
    certification = "R",
    originCountry = "United States of America"
  )

  private fun render(media: MediaItem, details: ExtendedMediaDetails) {
    rule.setContent {
      DetailScreen(
        media = media,
        allMedia = listOf(media),
        onBackClick = {},
        onPlayClick = { _, _ -> },
        onToggleFavorite = {},
        detailUiState = MediaDetailUiState.Success(media, details)
      )
    }
    rule.waitForIdle()
  }

  /** Porta in vista la scheda e la attiva con OK, come il telecomando Android TV. */
  private fun openTab(label: String) {
    // Per le serie la scheda Episodi contiene anche una LazyRow: il primo nodo
    // scrollabile della schermata resta la LazyColumn verticale del Dettaglio.
    rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(label))
    rule.waitForIdle()
    val tab = rule.onNode(
      isFocusable() and hasAnyDescendant(hasText(label)),
      useUnmergedTree = true
    )
    tab.requestFocus()
    rule.waitForIdle()
    tab.performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()
  }

  private fun pressDown() {
    rule.onNode(isFocused(), useUnmergedTree = true)
      .performKeyInput { pressKey(Key.DirectionDown) }
    rule.waitForIdle()
  }

  private fun assertFocusedRow(label: String) {
    rule.onNode(
      isFocused() and hasAnyDescendant(hasText(label)),
      useUnmergedTree = true
    ).assertIsFocused()
  }

  // ---------------------------------------------------------------- ordine schede

  @Test
  fun perIFilmDettagliFilmESubitoDopoProduzione() {
    val tabs = detailTabs(isTvSeries = false)
    assertTrue(tabs.indexOf(DetailTab.DETTAGLI_FILM) == tabs.indexOf(DetailTab.PRODUZIONE) + 1)
  }

  @Test
  fun perLeSerieLaNuovaSchedaSempreAccantoAProduzione() {
    val tabs = detailTabs(isTvSeries = true)
    assertTrue(tabs.indexOf(DetailTab.DETTAGLI_FILM) == tabs.indexOf(DetailTab.PRODUZIONE) + 1)
  }

  @Test
  fun iCanaliLiveNonRicevonoLaSchedaDettagliFilm() {
    val tabs = detailTabs(isTvSeries = false, includeMovieDetails = false)
    assertFalse(DetailTab.DETTAGLI_FILM in tabs)
    assertTrue(DetailTab.PRODUZIONE in tabs)
  }

  // ---------------------------------------------------------------- film

  @Test
  fun laSchedaMostraLeInformazioniRealiDelFilm() {
    render(movieMedia(), movieDetails())

    // Entrambe le schede sono presenti e «Dettagli Film» sta a destra di «Produzione».
    rule.onNodeWithText("Produzione").assertExists()
    rule.onNodeWithText("Dettagli Film").assertExists()
    val productionX = rule.onNodeWithText("Produzione").getUnclippedBoundsInRoot().left
    val detailsX = rule.onNodeWithText("Dettagli Film").getUnclippedBoundsInRoot().left
    assertTrue("«Dettagli Film» deve essere accanto a «Produzione»", detailsX > productionX)

    openTab("Dettagli Film")

    // Titolo della sezione + tab attiva con la stessa etichetta.
    rule.onAllNodesWithText("Dettagli Film").assertCountEquals(2)
    rule.onNodeWithText("Uscito").assertExists()
    // Anno nel badge di testata + valore della riga «Info rilascio».
    rule.onAllNodesWithText("1999").assertCountEquals(2)
    // Durata nel badge di testata + valore della riga «Durata».
    rule.onAllNodesWithText("2h 16m").assertCountEquals(2)
    rule.onNodeWithText("R").assertExists()
    rule.onNodeWithText("United States of America").assertExists()
  }

  @Test
  fun leRigheSonoRaggiungibiliConIlDpad() {
    render(movieMedia(), movieDetails())
    openTab("Dettagli Film")

    // La prima pressione di GIÙ entra nella prima riga informativa.
    pressDown()
    assertFocusedRow("Stato")

    // La successiva passa alla riga seguente: l'elenco è una vera sequenza D-pad.
    pressDown()
    assertFocusedRow("Info rilascio")
  }

  @Test
  fun iCampiMancantiNonProduconoRighe() {
    val media = movieMedia()
    val details = movieDetails().copy(
      releaseDate = null,
      runtimeMinutes = null,
      status = null,
      certification = null,
      originCountry = null
    )
    render(media.copy(releaseDate = null), details)
    openTab("Dettagli Film")

    rule.onNodeWithText("Nessun dettaglio disponibile").assertExists()
    rule.onNodeWithText("Stato").assertDoesNotExist()
    rule.onNodeWithText("Info rilascio").assertDoesNotExist()
    rule.onNodeWithText("Durata").assertDoesNotExist()
    rule.onNodeWithText("Certificazione").assertDoesNotExist()
    rule.onNodeWithText("Paese d'origine").assertDoesNotExist()
  }

  // ---------------------------------------------------------------- serie TV

  @Test
  fun perLeSerieMostraSoloICampiEquivalenti() {
    val media = seriesMedia()
    val details = ExtendedMediaDetails(
      tmdbId = 1399,
      title = media.title,
      originalTitle = "Game of Thrones",
      overview = media.synopsis,
      backdropUrl = null,
      posterUrl = null,
      releaseYear = 2011,
      releaseDate = "2011-04-17",
      rating = 8.4f,
      durationMinutes = 55,
      runtimeMinutes = 55,
      seasonsCount = 8,
      status = "Returning Series",
      certification = "TV-MA",
      originCountry = "United States of America"
    )
    render(media, details)

    openTab("Dettagli")

    rule.onNodeWithText("Dettagli Serie").assertExists()
    rule.onNodeWithText("In corso").assertExists()
    rule.onNodeWithText("Prima messa in onda").assertExists()
    rule.onNodeWithText("Durata episodio").assertExists()
    // Anno nel badge di testata + valore della riga «Prima messa in onda».
    rule.onAllNodesWithText("2011").assertCountEquals(2)
    rule.onNodeWithText("55m").assertExists()
    rule.onNodeWithText("TV-MA").assertExists()
    rule.onNodeWithText("United States of America").assertExists()

    // Nessun dato specifico dei film presentato come se fosse della serie.
    rule.onNodeWithText("Info rilascio").assertDoesNotExist()
    rule.onNodeWithText("Durata").assertDoesNotExist()
  }
}
