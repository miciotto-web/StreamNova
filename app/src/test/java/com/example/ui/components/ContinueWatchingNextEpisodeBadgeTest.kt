package com.example.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.NextEpisodeInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Badge "Prossima puntata" sulla card reale di Continua a guardare (Fase 5).
 *
 * Monta [ContinueWatchingCard] come in Home e verifica che il badge compaia solo con dati
 * TMDB completi: serie in onda, prossima puntata annunciata, data valida e non trascorsa.
 * Le date sono calcolate rispetto a oggi, così il test non scade col passare del tempo.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class ContinueWatchingNextEpisodeBadgeTest {

  @get:Rule
  val rule = createComposeRule()

  private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
  private val italian = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).apply { isLenient = false }

  private fun dateAt(daysFromToday: Int, formatter: SimpleDateFormat): String {
    val calendar = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 12)
      add(Calendar.DAY_OF_YEAR, daysFromToday)
    }
    return formatter.format(calendar.time)
  }

  private fun isoDate(daysFromToday: Int) = dateAt(daysFromToday, iso)
  private fun italianDate(daysFromToday: Int) = dateAt(daysFromToday, italian)

  private fun series(
    status: String? = "Returning Series",
    nextEpisode: NextEpisodeInfo? = NextEpisodeInfo(2, 5, isoDate(7)),
    type: MediaType = MediaType.SERIE_TV,
    resumeSeason: Int? = 2
  ) = MediaItem(
    id = "tmdb_tv_1399",
    title = "Il Trono di Spade",
    synopsis = "",
    videoUrl = "",
    type = type,
    tmdbId = 1399,
    currentProgressMs = 12 * 60 * 1000L,
    totalDurationMs = 55 * 60 * 1000L,
    lastWatchedSeason = resumeSeason,
    lastWatchedEpisode = 4,
    tvStatus = status,
    nextEpisode = nextEpisode
  )

  private fun showCard(media: MediaItem) {
    rule.setContent {
      ContinueWatchingCard(media = media, onClick = {})
    }
    rule.waitForIdle()
  }

  private fun badgeExists(): Boolean =
    rule.onAllNodesWithTag(NEXT_EPISODE_BADGE_TEST_TAG, useUnmergedTree = true)
      .fetchSemanticsNodes().isNotEmpty()

  // 1. Returning Series con prossima puntata valida: badge visibile con data italiana e S/E.
  @Test
  fun validNextEpisodeShowsBadgeOnCard() {
    showCard(series())

    rule.onNodeWithTag(NEXT_EPISODE_BADGE_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithText(italianDate(7), useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithText("S02E05", useUnmergedTree = true).assertIsDisplayed()
  }

  // 2. Serie Ended: badge assente.
  @Test
  fun endedSeriesHasNoBadge() {
    showCard(series(status = "Ended"))
    assertFalse("Il badge non deve comparire per una serie Ended", badgeExists())
  }

  // 3. Serie Canceled: badge assente.
  @Test
  fun canceledSeriesHasNoBadge() {
    showCard(series(status = "Canceled"))
    assertFalse("Il badge non deve comparire per una serie Canceled", badgeExists())
  }

  // 4. Film: badge assente.
  @Test
  fun movieHasNoBadge() {
    showCard(series(type = MediaType.FILM))
    assertFalse("Il badge non deve comparire per un film", badgeExists())
  }

  // 5. Prossima puntata assente: badge assente.
  @Test
  fun missingNextEpisodeHasNoBadge() {
    showCard(series(nextEpisode = null))
    assertFalse("Il badge non deve comparire senza prossima puntata", badgeExists())
  }

  // 6. Data vuota o non valida: badge assente.
  @Test
  fun blankAirDateHasNoBadge() {
    showCard(series(nextEpisode = NextEpisodeInfo(2, 5, "")))
    assertFalse("Il badge non deve comparire con data vuota", badgeExists())
  }

  @Test
  fun nonIsoAirDateHasNoBadge() {
    showCard(series(nextEpisode = NextEpisodeInfo(2, 5, "12/11/2026")))
    assertFalse("Il badge non deve comparire con data non ISO", badgeExists())
  }

  // 7. Data già trascorsa: badge assente.
  @Test
  fun pastAirDateHasNoBadge() {
    showCard(series(nextEpisode = NextEpisodeInfo(2, 5, isoDate(-1))))
    assertFalse("Il badge non deve comparire con data trascorsa", badgeExists())
  }

  // 9. Serie con più stagioni: stagione ed episodio esatti.
  @Test
  fun multiSeasonSeriesShowsExactSeasonAndEpisode() {
    showCard(
      series(
        nextEpisode = NextEpisodeInfo(seasonNumber = 10, episodeNumber = 3, airDate = isoDate(7)),
        resumeSeason = 10
      )
    )

    rule.onNodeWithText("S10E03", useUnmergedTree = true).assertIsDisplayed()
  }

  // 2. Stagione in visione diversa da quella della prossima puntata: badge assente.
  @Test
  fun earlierWatchingSeasonHasNoBadge() {
    var media by mutableStateOf(series(nextEpisode = NextEpisodeInfo(4, 4, isoDate(7)), resumeSeason = 1))
    rule.setContent {
      ContinueWatchingCard(media = media, onClick = {})
    }
    rule.waitForIdle()
    assertFalse("S01 in visione non deve mostrare la prossima puntata della S04", badgeExists())

    media = media.copy(lastWatchedSeason = 2)
    rule.waitForIdle()
    assertFalse("S02 in visione non deve mostrare la prossima puntata della S04", badgeExists())

    media = media.copy(lastWatchedSeason = 3)
    rule.waitForIdle()
    assertFalse("S03 in visione non deve mostrare la prossima puntata della S04", badgeExists())
  }

  @Test
  fun laterWatchingSeasonHasNoBadge() {
    showCard(series(nextEpisode = NextEpisodeInfo(4, 4, isoDate(7)), resumeSeason = 5))
    assertFalse("S05 in visione non deve mostrare la prossima puntata della S04", badgeExists())
  }

  @Test
  fun sameWatchingSeasonShowsBadge() {
    showCard(series(nextEpisode = NextEpisodeInfo(4, 4, isoDate(7)), resumeSeason = 4))

    rule.onNodeWithTag(NEXT_EPISODE_BADGE_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithText("S04E04", useUnmergedTree = true).assertIsDisplayed()
  }

  // 6. Stagione di ripresa mancante: nessun confronto attendibile, badge assente.
  @Test
  fun missingResumeSeasonHasNoBadge() {
    showCard(series(nextEpisode = NextEpisodeInfo(4, 4, isoDate(7)), resumeSeason = null))
    assertFalse("Senza stagione salvata il badge non deve comparire", badgeExists())
  }

  // 10. Cambio stagione durante la visione: il badge si aggiorna senza un nuovo dato TMDB.
  @Test
  fun seasonChangeDuringPlaybackUpdatesBadge() {
    var media by mutableStateOf(series(nextEpisode = NextEpisodeInfo(4, 4, isoDate(7)), resumeSeason = 2))
    rule.setContent {
      ContinueWatchingCard(media = media, onClick = {})
    }
    rule.waitForIdle()
    assertFalse("Con S02 in visione il badge S04 deve essere nascosto", badgeExists())

    // L'utente passa alla S04: stesso `next_episode_to_air`, ora coerente.
    media = media.copy(lastWatchedSeason = 4)
    rule.waitForIdle()
    rule.onNodeWithTag(NEXT_EPISODE_BADGE_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithText("S04E04", useUnmergedTree = true).assertIsDisplayed()

    // Nuovo cambio stagione: il badge torna nascosto.
    media = media.copy(lastWatchedSeason = 5)
    rule.waitForIdle()
    assertFalse("Con S05 in visione il badge S04 deve tornare nascosto", badgeExists())
  }

  // 9. Ripresa Debrid: conta la stagione effettivamente salvata con il progresso.
  @Test
  fun debridResumeUsesSavedSeason() {
    val debrid = series(
      nextEpisode = NextEpisodeInfo(4, 4, isoDate(7)),
      resumeSeason = 4
    ).copy(
      id = "tt0944947:4:2",
      videoUrl = "https://store-001.torbox.app/debrid/stream/abc",
      lastWatchedEpisode = 2
    )
    showCard(debrid)

    rule.onNodeWithTag(NEXT_EPISODE_BADGE_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithText("S04E04", useUnmergedTree = true).assertIsDisplayed()
  }

  // 8. Quando TMDB aggiorna la prossima puntata, la card mostra la nuova data.
  @Test
  fun updatedTmdbDateRefreshesBadge() {
    var media by mutableStateOf(series(nextEpisode = NextEpisodeInfo(2, 5, isoDate(7))))
    rule.setContent {
      ContinueWatchingCard(media = media, onClick = {})
    }
    rule.waitForIdle()
    rule.onNodeWithText(italianDate(7), useUnmergedTree = true).assertIsDisplayed()

    // Stessa card, dato TMDB aggiornato (nuovo episodio e nuova data).
    media = media.copy(nextEpisode = NextEpisodeInfo(2, 6, isoDate(14)))
    rule.waitForIdle()

    rule.onNodeWithText(italianDate(14), useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithText(italianDate(7), useUnmergedTree = true).assertDoesNotExist()
    rule.onNodeWithText("S02E06", useUnmergedTree = true).assertIsDisplayed()
  }
}
