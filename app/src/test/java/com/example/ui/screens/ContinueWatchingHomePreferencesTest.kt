package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.ContinueWatchingLayout
import com.example.data.prefs.ContinueWatchingPreferences
import com.example.data.prefs.ContinueWatchingSort
import com.example.ui.components.continueWatchingLayoutTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integrazione della riga «Continua a guardare» nella Home.
 *
 * Le preferenze sono uno stato Compose: cambiandole la riga deve aggiornarsi SUBITO
 * (nessun riavvio, nessuna nuova risoluzione dei cataloghi) e i tre layout devono essere
 * davvero composti con la geometria/struttura corrispondente.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "it-w1280dp-h720dp-tvdpi")
class ContinueWatchingHomePreferencesTest {

  @get:Rule
  val rule = createComposeRule()

  private fun movie(
    id: String,
    title: String,
    lastWatchedAt: Long,
    rating: Float = 8f
  ) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    currentProgressMs = 20 * 60_000L,
    totalDurationMs = 100 * 60_000L,
    lastWatchedAt = lastWatchedAt,
    // Card di «Continua a guardare» con posizione reale: dalla v12 il default del flag
    // è `false` (una scheda TMDB fresca non è candidata) e va esplicitato.
    inContinueWatching = true,
    backdropUrl = "https://img.example/$id-backdrop.jpg",
    posterUrl = "https://img.example/$id-poster.jpg",
    rating = rating
  )

  private val items = listOf(
    movie("film_alfa", "Alfa", lastWatchedAt = 1_000L),
    movie("film_bravo", "Bravo", lastWatchedAt = 3_000L),
    movie("film_charlie", "Charlie", lastWatchedAt = 2_000L)
  )

  @Test
  fun laRigaSparisceQuandoLaPreferenzaLaNasconde() {
    var preferences by mutableStateOf(ContinueWatchingPreferences())
    rule.setContent {
      HomeMainBrowsingContent(
        allMedia = items,
        onMediaClick = {},
        onPlayClick = {},
        onRemoveFromContinueWatching = {},
        continueWatchingPreferences = preferences
      )
    }
    rule.waitForIdle()
    rule.onNodeWithText("Continua a guardare").assertIsDisplayed()

    rule.runOnIdle { preferences = preferences.copy(showRow = false) }
    rule.waitForIdle()
    rule.onNodeWithText("Continua a guardare").assertDoesNotExist()

    rule.runOnIdle { preferences = preferences.copy(showRow = true) }
    rule.waitForIdle()
    rule.onNodeWithText("Continua a guardare").assertIsDisplayed()
  }

  @Test
  fun iTreLayoutVengonoCompostiDavvero() {
    var preferences by mutableStateOf(ContinueWatchingPreferences())
    rule.setContent {
      HomeMainBrowsingContent(
        allMedia = items,
        onMediaClick = {},
        onPlayClick = {},
        onRemoveFromContinueWatching = {},
        continueWatchingPreferences = preferences
      )
    }
    rule.waitForIdle()

    rule.onAllNodesWithTag(continueWatchingLayoutTestTag(ContinueWatchingLayout.CARD))
      .fetchSemanticsNodes().let { assertEquals(3, it.size) }

    rule.runOnIdle { preferences = preferences.copy(layout = ContinueWatchingLayout.WIDE) }
    rule.waitForIdle()
    rule.onAllNodesWithTag(continueWatchingLayoutTestTag(ContinueWatchingLayout.WIDE))
      .fetchSemanticsNodes().let { assertEquals(3, it.size) }
    rule.onAllNodesWithTag(continueWatchingLayoutTestTag(ContinueWatchingLayout.CARD))
      .fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

    rule.runOnIdle { preferences = preferences.copy(layout = ContinueWatchingLayout.POSTER) }
    rule.waitForIdle()
    rule.onAllNodesWithTag(continueWatchingLayoutTestTag(ContinueWatchingLayout.POSTER))
      .fetchSemanticsNodes().let { assertEquals(3, it.size) }
  }

  @Test
  fun lOrdinamentoAggiornaSubitoLaRiga() {
    var preferences by mutableStateOf(ContinueWatchingPreferences())
    rule.setContent {
      HomeMainBrowsingContent(
        allMedia = items,
        onMediaClick = {},
        onPlayClick = {},
        onRemoveFromContinueWatching = {},
        continueWatchingPreferences = preferences
      )
    }
    rule.waitForIdle()

    fun x(id: String) = rule.onNodeWithTag("cw_card_$id").fetchSemanticsNode().positionInRoot.x
    // Predefinito: visione più recente (Bravo, Charlie, Alfa).
    assertTrue(x("film_bravo") < x("film_charlie"))
    assertTrue(x("film_charlie") < x("film_alfa"))

    rule.runOnIdle { preferences = preferences.copy(sort = ContinueWatchingSort.TITLE) }
    rule.waitForIdle()
    // La preferenza TITLE non scavalca la cronologia: l'ultima visione resta la prima.
    assertTrue(x("film_bravo") < x("film_charlie"))
    assertTrue(x("film_charlie") < x("film_alfa"))
  }

  @Test
  fun laCardDellaRigaMostraTitoloEdEpisodioDelloStatoSalvato() {
    val series = MediaItem(
      id = "serie_uno",
      title = "Serie Uno",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      currentProgressMs = 10 * 60_000L,
      totalDurationMs = 50 * 60_000L,
      lastWatchedSeason = 2,
      lastWatchedEpisode = 4,
      lastWatchedAt = 5_000L,
      // Card di «Continua a guardare» con episodio corrente salvato: il flag va esplicito
      // dalla v12 (il default `false` descrive una scheda TMDB fresca).
      inContinueWatching = true,
      backdropUrl = "https://img.example/serie-backdrop.jpg"
    )
    rule.setContent {
      HomeMainBrowsingContent(
        allMedia = listOf(series),
        onMediaClick = {},
        onPlayClick = {},
        onRemoveFromContinueWatching = {},
        continueWatchingPreferences = ContinueWatchingPreferences()
      )
    }
    rule.waitForIdle()

    rule.onNodeWithTag(continueWatchingLayoutTestTag(ContinueWatchingLayout.CARD))
      .assertIsDisplayed()
    rule.onNodeWithTag("cw_card_serie_uno").assertIsDisplayed()
    // L'etichetta stagione/episodio compare solo nella card della riga (l'Hero mostra
    // il titolo del contenuto, non la puntata salvata).
    rule.onNodeWithText("S2 E4").assertIsDisplayed()
    assertTrue(
      "Il titolo del contenuto è presente nella card",
      rule.onAllNodesWithText("Serie Uno").fetchSemanticsNodes().isNotEmpty()
    )
  }
}
