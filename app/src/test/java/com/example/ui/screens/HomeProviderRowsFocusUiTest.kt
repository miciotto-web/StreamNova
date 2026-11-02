package com.example.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.CatalogSection
import com.example.data.repository.CatalogSourceOrigin
import com.example.data.repository.HomeCatalogs
import com.example.data.repository.TmdbHomeCatalogProvider
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressione FOCUS/SCROLLING delle 10 NUOVE righe native TMDB
 * "Novità <provider> Film/Serie" (extraSections).
 *
 * Verifica che l'aggiunta delle righe non alteri il flusso Focus esistente:
 *  - il D-pad Su/Giù attraversa le 10 righe una per una (scroll verticale incluso);
 *  - Destra/Sinistra scorrono dentro una riga provider;
 *  - un refresh dei cataloghi non fa perdere il focus;
 *  - il rientro dal Dettaglio ripristina la card su una riga provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class HomeProviderRowsFocusUiTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_POPULAR_FILM = "section_popolari_film"
    const val ROW_POPULAR_SERIE = "section_popolari_serie"

    const val CARDS_PER_ROW = 6

    val PROVIDER_ROWS: List<Triple<String, String, MediaType>> = listOf(
      Triple("netflix_m", "NFM", MediaType.FILM),
      Triple("netflix_s", "NFS", MediaType.SERIE_TV),
      Triple("hbo_m", "HBM", MediaType.FILM),
      Triple("hbo_s", "HBS", MediaType.SERIE_TV),
      Triple("disney_m", "DSM", MediaType.FILM),
      Triple("disney_s", "DSS", MediaType.SERIE_TV),
      Triple("apple_m", "ATM", MediaType.FILM),
      Triple("apple_s", "ATS", MediaType.SERIE_TV),
      Triple("paramount_m", "PPM", MediaType.FILM),
      Triple("paramount_s", "PPS", MediaType.SERIE_TV)
    )

    val PROVIDER_SECTION_IDS: List<String> = listOf(
      TmdbHomeCatalogProvider.SECTION_NETFLIX_MOVIES_NEW,
      TmdbHomeCatalogProvider.SECTION_NETFLIX_SERIES_NEW,
      TmdbHomeCatalogProvider.SECTION_HBO_MAX_MOVIES_NEW,
      TmdbHomeCatalogProvider.SECTION_HBO_MAX_SERIES_NEW,
      TmdbHomeCatalogProvider.SECTION_DISNEY_MOVIES_NEW,
      TmdbHomeCatalogProvider.SECTION_DISNEY_SERIES_NEW,
      TmdbHomeCatalogProvider.SECTION_APPLE_MOVIES_NEW,
      TmdbHomeCatalogProvider.SECTION_APPLE_SERIES_NEW,
      TmdbHomeCatalogProvider.SECTION_PARAMOUNT_MOVIES_NEW,
      TmdbHomeCatalogProvider.SECTION_PARAMOUNT_SERIES_NEW
    )

    const val YEAR_POPULAR_FILM = 2101
    const val YEAR_POPULAR_SERIE = 2102
    const val YEAR_PROVIDER_BASE = 2300
  }

  private fun media(
    rowKey: String,
    index: Int,
    title: String,
    type: MediaType,
    year: Int
  ) = MediaItem(
    id = "$rowKey-${index + 1}",
    title = title,
    synopsis = "",
    videoUrl = "",
    type = type,
    year = year,
    tmdbId = index + 1
  )

  private fun row(
    rowKey: String,
    prefix: String,
    type: MediaType,
    year: Int,
    count: Int = CARDS_PER_ROW
  ): List<MediaItem> = List(count) { index ->
    media(rowKey, index, "$prefix ${index + 1}", type, year)
  }

  private fun providerSection(
    id: String,
    title: String,
    type: MediaType,
    items: List<MediaItem>
  ) = CatalogSection(
    id = id,
    title = title,
    addonId = null,
    addonName = null,
    catalogId = id,
    mediaType = type,
    origin = CatalogSourceOrigin.TMDB_NATIVE,
    items = items
  )

  /**
   * Home senza Hero: due righe Popolari seguite dalle 10 righe provider, nello
   * stesso ordine in cui [TmdbHomeCatalogProvider] le produce.
   */
  private fun catalogs(netflixSeriesCardCount: Int = CARDS_PER_ROW): HomeCatalogs = HomeCatalogs(
    heroItems = emptyList(),
    popularMovies = row(ROW_POPULAR_FILM, "PF", MediaType.FILM, YEAR_POPULAR_FILM),
    popularSeries = row(ROW_POPULAR_SERIE, "PS", MediaType.SERIE_TV, YEAR_POPULAR_SERIE),
    extraSections = PROVIDER_ROWS.mapIndexed { index, (rowKey, prefix, type) ->
      val sectionId = PROVIDER_SECTION_IDS[index]
      providerSection(
        id = sectionId,
        title = sectionId,
        type = type,
        items = row(
          rowKey = "section_$sectionId",
          prefix = prefix,
          type = type,
          year = YEAR_PROVIDER_BASE + index,
          count = if (index == 1) netflixSeriesCardCount else CARDS_PER_ROW
        )
      )
    },
    stockSourceOrigin = CatalogSourceOrigin.TMDB_NATIVE
  )

  private var restoreTarget by mutableStateOf<Pair<String, String>?>(null)
  private var restoreToken by mutableStateOf(0)
  private var liveCatalogs by mutableStateOf<HomeCatalogs?>(null)

  private fun renderHome(catalogs: HomeCatalogs) {
    liveCatalogs = catalogs
    rule.setContent {
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides restoreTarget?.second,
        LocalLastFocusedTarget provides restoreTarget,
        LocalFocusRestoreToken provides restoreToken,
        LocalClearLastFocusedMedia provides { restoreTarget = null }
      ) {
        HomeMainBrowsingContent(
          allMedia = emptyList(),
          homeCatalogs = liveCatalogs,
          onMediaClick = {},
          onPlayClick = {},
          onRemoveFromContinueWatching = {},
          viewModel = null
        )
      }
    }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helper

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused()).fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList() }

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key, times: Int = 1) {
    repeat(times) {
      rule.onNode(isFocused()).performKeyInput { pressKey(key) }
      rule.waitForIdle()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun assertFocusedCard(title: String, year: Int) {
    val expectedYear = year.toString()
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedTexts().let { it.contains(title) && it.contains(expectedYear) }
    }
    rule.onNode(isFocused()).assertTextContains(title)
    rule.onNode(isFocused()).assertTextContains(expectedYear)
  }

  private fun sectionIdOf(index: Int) = "section_${PROVIDER_SECTION_IDS[index]}"

  private fun titleOf(index: Int) = "${PROVIDER_ROWS[index].second} 1"

  /** Porta il focus dalla prima riga Popolari alla prima riga provider. */
  private fun focusFirstProviderRow() {
    focusCard("PF 1")
    pressKey(Key.DirectionDown)
    assertFocusedCard("PS 1", YEAR_POPULAR_SERIE)
    pressKey(Key.DirectionDown)
    assertFocusedCard(titleOf(0), YEAR_PROVIDER_BASE)
  }

  // ---------------------------------------------------------------- test

  @Test
  fun giuAttraversaLeDieciRigheProviderUnaPerUna() {
    renderHome(catalogs())

    focusCard("PF 1")
    assertFocusedCard("PF 1", YEAR_POPULAR_FILM)

    pressKey(Key.DirectionDown)
    assertFocusedCard("PS 1", YEAR_POPULAR_SERIE)

    PROVIDER_ROWS.indices.forEach { index ->
      pressKey(Key.DirectionDown)
      assertFocusedCard(titleOf(index), YEAR_PROVIDER_BASE + index)
    }
  }

  @Test
  fun suRipercorreLeRigheProviderInSensoInverso() {
    renderHome(catalogs())

    // Scende fino all'ultima riga provider (Apple TV+ Serie) tramite D-pad reale.
    focusFirstProviderRow()
    repeat(PROVIDER_ROWS.size - 1) { pressKey(Key.DirectionDown) }
    val last = PROVIDER_ROWS.lastIndex
    assertFocusedCard(titleOf(last), YEAR_PROVIDER_BASE + last)

    pressKey(Key.DirectionUp)
    assertFocusedCard(titleOf(last - 1), YEAR_PROVIDER_BASE + last - 1)

    pressKey(Key.DirectionUp)
    assertFocusedCard(titleOf(last - 2), YEAR_PROVIDER_BASE + last - 2)
  }

  @Test
  fun destraESinistraScorronoDentroUnaRigaProvider() {
    renderHome(catalogs())

    focusFirstProviderRow()

    pressKey(Key.DirectionRight, times = 3)
    assertFocusedCard("${PROVIDER_ROWS[0].second} 4", YEAR_PROVIDER_BASE)

    pressKey(Key.DirectionLeft)
    assertFocusedCard("${PROVIDER_ROWS[0].second} 3", YEAR_PROVIDER_BASE)
  }

  @Test
  fun unRefreshDeiCataloghiNonFaPerdereIlFocusSullaRigaProvider() {
    renderHome(catalogs())

    focusFirstProviderRow()
    pressKey(Key.DirectionDown)
    pressKey(Key.DirectionRight)
    assertFocusedCard("${PROVIDER_ROWS[1].second} 2", YEAR_PROVIDER_BASE + 1)

    // Ondata dati successiva: stessa riga e stessa key, lista più lunga.
    liveCatalogs = catalogs(netflixSeriesCardCount = CARDS_PER_ROW + 2)
    rule.waitForIdle()

    assertFocusedCard("${PROVIDER_ROWS[1].second} 2", YEAR_PROVIDER_BASE + 1)

    pressKey(Key.DirectionRight)
    assertFocusedCard("${PROVIDER_ROWS[1].second} 3", YEAR_PROVIDER_BASE + 1)
  }

  @Test
  fun ilRientroDalDettaglioRipristinaUnaCardDiUnaRigaProviderLontana() {
    renderHome(catalogs())

    // Apple TV+ Serie è la nona riga extra: il ripristino deve funzionare anche
    // quando la riga non è ancora composta nel viewport iniziale.
    val index = 7
    val rowKey = sectionIdOf(index)
    restoreTarget = rowKey to "$rowKey-3"
    restoreToken = 1
    rule.waitForIdle()

    assertFocusedCard("${PROVIDER_ROWS[index].second} 3", YEAR_PROVIDER_BASE + index)
  }
}
